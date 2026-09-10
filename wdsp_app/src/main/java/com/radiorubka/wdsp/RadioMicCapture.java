package com.radiorubka.wdsp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * 🎙️ Captures cabin acoustic audio via microphone for analogue FM Radio visualization.
 *
 * <p>Analogue FM Radio on platform QF is routed by MCU directly into the power amplifier/DSP
 * (hardware channel 2), completely bypassing Android AudioFlinger PCM stream. Android's Visualizer
 * effect therefore receives only silence.
 *
 * <p>This capture uses {@link MediaRecorder.AudioSource#UNPROCESSED} at 48000 Hz 16-bit mono.
 * Hardware inspection on UIS7862 confirmed that the unprocessed stream carries the full acoustic
 * spectrum from sub-bass (20 Hz) to treble without high-pass cutoff or DSP voice suppression.
 *
 * <p>Includes built-in adaptive AGC (Automatic Gain Control):
 * <ul>
 *   <li>At low listening volumes (e.g. 2–4 volume units), boosts weak microphone pickup up to +24 dB (16.0x).</li>
 *   <li>At high listening volumes (20–30 units), attenuates down to 0.5x to prevent clipping.</li>
 *   <li>Fast attack (~20 ms) to suppress loud transients, smooth release (~200 ms) for musical dynamics.</li>
 *   <li>Noise gate: during cabin silence or muted radio, gain decays to 1.0x to avoid amplifying noise.</li>
 *   <li>Per-sample linear interpolation eliminates clicks and waveform discontinuities.</li>
 * </ul>
 *
 * <p>PCM samples are converted to unsigned 8-bit format matching {@link android.media.audiofx.Visualizer#getWaveForm(byte[])}
 * and fed into {@link NativeAnalyzer#push(byte[], int)} with 50% overlap (512 samples hop, 1024 window)
 * ensuring 100% cross-correlation in {@code Stitcher}.
 */
public class RadioMicCapture {
    private static final String TAG = "RadioMicCapture";

    public interface Callback {
        void onWaveform(byte[] buffer, int length);
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int HOP_SIZE = 512;
    public static final int WINDOW_SIZE = 1024;

    // AGC parameters
    private static final float TARGET_PEAK = 24000.0f; // ~ -2.7 dBFS
    private static final float MIN_GAIN = 0.5f;        // -6 dB attenuation
    private static final float MAX_GAIN = 16.0f;       // +24 dB boost
    private static final int NOISE_GATE_THRESHOLD = 200; // 16-bit silence floor

    private AudioRecord audioRecord;
    private MicProbe.Suspension suspension;
    private Thread captureThread;
    private volatile boolean running = false;
    private float currentGain = 1.0f;

    public synchronized boolean start(Context context, Callback callback) {
        if (running) return true;
        if (context == null || callback == null) return false;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted");
            return false;
        }

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBuf, WINDOW_SIZE * 4);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
        } catch (Throwable t) {
            Log.w(TAG, "AudioRecord(UNPROCESSED) failed, trying DEFAULT: " + t);
            try {
                audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                );
            } catch (Throwable t2) {
                Log.e(TAG, "Failed to create AudioRecord: " + t2);
                return false;
            }
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            safeReleaseRecord();
            return false;
        }

        try {
            suspension = MicProbe.suspendCapturePreprocessing(audioRecord.getAudioSessionId(), TAG);
            audioRecord.startRecording();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start recording: " + t);
            stop();
            return false;
        }

        running = true;
        currentGain = 1.0f;

        captureThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            short[] shortChunk = new short[HOP_SIZE];
            byte[] rollingWindow = new byte[WINDOW_SIZE];
            java.util.Arrays.fill(rollingWindow, (byte) 128);

            while (running) {
                AudioRecord rec = audioRecord;
                if (rec == null || rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    break;
                }

                int offset = 0;
                while (running && offset < HOP_SIZE) {
                    int read = rec.read(shortChunk, offset, HOP_SIZE - offset);
                    if (read <= 0) {
                        if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                            Log.w(TAG, "AudioRecord read error: " + read);
                            offset = -1;
                            break;
                        }
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            offset = -1;
                            break;
                        }
                        continue;
                    }
                    offset += read;
                }

                if (offset != HOP_SIZE) {
                    continue;
                }

                // Measure peak amplitude of this chunk
                int peak = 0;
                for (int i = 0; i < HOP_SIZE; i++) {
                    int abs = Math.abs(shortChunk[i]);
                    if (abs > peak) peak = abs;
                }

                float startGain = currentGain;
                if (peak > NOISE_GATE_THRESHOLD) {
                    float desiredGain = TARGET_PEAK / peak;
                    if (desiredGain > MAX_GAIN) desiredGain = MAX_GAIN;
                    if (desiredGain < MIN_GAIN) desiredGain = MIN_GAIN;

                    if (desiredGain < currentGain) {
                        // Fast attack (~20 ms) to prevent distortion
                        currentGain += (desiredGain - currentGain) * 0.40f;
                    } else {
                        // Smooth release (~200 ms) for musical dynamics
                        currentGain += (desiredGain - currentGain) * 0.05f;
                    }
                } else {
                    // Silence / noise floor: gracefully decay gain to 1.0f
                    currentGain += (1.0f - currentGain) * 0.10f;
                }

                // Shift older half of rolling window to the left (512 samples)
                System.arraycopy(rollingWindow, HOP_SIZE, rollingWindow, 0, HOP_SIZE);

                // Convert 16-bit signed PCM to 8-bit unsigned PCM with smooth gain interpolation
                float gainStep = (currentGain - startGain) / HOP_SIZE;
                for (int i = 0; i < HOP_SIZE; i++) {
                    float g = startGain + gainStep * i;
                    int boosted = Math.round(shortChunk[i] * g);
                    if (boosted > 32767) boosted = 32767;
                    else if (boosted < -32768) boosted = -32768;

                    int val = (boosted >> 8) + 128;
                    if (val < 0) val = 0;
                    else if (val > 255) val = 255;

                    rollingWindow[HOP_SIZE + i] = (byte) val;
                }

                try {
                    callback.onWaveform(rollingWindow, WINDOW_SIZE);
                } catch (Throwable t) {
                    Log.w(TAG, "Waveform callback exception: " + t);
                }
            }
        }, "wDSP_RadioMic");

        captureThread.setPriority(Thread.MAX_PRIORITY - 1);
        captureThread.start();
        Log.i(TAG, "RadioMicCapture started at " + SAMPLE_RATE + " Hz UNPROCESSED (Adaptive AGC enabled)");
        return true;
    }

    public synchronized void stop() {
        running = false;
        Thread t = captureThread;
        captureThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(300);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        if (suspension != null) {
            try {
                suspension.restore();
            } catch (Throwable ignored) {}
            suspension = null;
        }

        safeReleaseRecord();
        Log.i(TAG, "RadioMicCapture stopped");
    }

    private void safeReleaseRecord() {
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Throwable ignored) {}
            audioRecord = null;
        }
    }

    public boolean isRunning() {
        return running;
    }
}
