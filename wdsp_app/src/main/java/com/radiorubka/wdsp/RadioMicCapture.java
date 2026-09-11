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

import java.util.Locale;

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
 *
 * <h2>The assistant and the microphone</h2>
 *
 * <p>An assistant hotword listener opens the microphone at boot at 16 kHz, and on this platform
 * whoever opens the input first sets its rate for everybody - a capture started after it gets
 * nothing above 8 kHz. The answer is order, not force. Measured 11.09.2026: once our 48 kHz
 * stream is open, the assistant comes back as a 16 kHz client riding on it, keeps listening and
 * answers "Ok Google", and the input stays at 48 kHz even after we leave. So the capture listens
 * to its own first half second; if the top end is missing it stops the assistant once, through
 * root, and reopens at once - the assistant is back within two seconds and has to find us there.
 *
 * <p>Versions 0.4.9 to 0.4.9.6 did something else here: they set the assistant's RECORD_AUDIO
 * app-op to "ignore", permanently. That does not make it share - it makes it deaf, and the mode
 * outlived our own uninstall. {@link MicrophoneGuard#repairAssistantMicOnce} puts it back.
 */
public class RadioMicCapture {
    private static final String TAG = "RadioMicCapture";

    public interface PcmCallback {
        void onPcmChunk(short[] buffer, int count);
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    public static final int CHUNK_SIZE = 512;

    /** How much of our own stream is heard before deciding whether it has a top end. */
    private static final int BANDWIDTH_PROBE_SAMPLES = SAMPLE_RATE / 2;
    /** Below this the probe was silence, and silence says nothing about the stream's rate. */
    private static final double PROBE_MIN_RMS = 1e-4;

    // AGC parameters for quiet cabin radio listening
    private static final float TARGET_PEAK = 24000.0f; // ~ -2.7 dBFS
    private static final float MIN_GAIN = 0.5f;        // -6 dB attenuation for loud listening
    private static final float MAX_GAIN = 16.0f;       // +24 dB boost for quiet volumes (2-4 units)
    private static final int NOISE_GATE_THRESHOLD = 150; // Silence floor

    private AudioRecord audioRecord;
    private MicProbe.Suspension suspension;
    private Thread captureThread;
    private volatile boolean running = false;
    private float currentGain = 1.0f;
    private Context appContext;
    private int bufferSize;

    public synchronized boolean start(Context context, PcmCallback callback) {
        if (running) return true;
        if (context == null || callback == null) return false;

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted");
            return false;
        }
        appContext = context.getApplicationContext();

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        bufferSize = Math.max(minBuf, CHUNK_SIZE * 4);

        if (!openRecordLocked()) return false;

        running = true;
        currentGain = 1.0f;

        captureThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            short[] shortChunk = new short[CHUNK_SIZE];
            short[] agcChunk = new short[CHUNK_SIZE];
            float[] probe = new float[BANDWIDTH_PROBE_SAMPLES];
            int probed = 0;
            boolean probing = true;
            boolean reopened = false;

            while (running) {
                AudioRecord rec = audioRecord;
                if (rec == null || rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    break;
                }

                int read = rec.read(shortChunk, 0, CHUNK_SIZE);
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        Log.w(TAG, "AudioRecord read error: " + read);
                        break;
                    }
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                if (probing) {
                    int n = Math.min(read, probe.length - probed);
                    for (int i = 0; i < n; i++) probe[probed + i] = shortChunk[i] / 32768f;
                    probed += n;
                    if (probed == probe.length) {
                        probing = false;
                        float bw = bandwidthDb(probe, probed);
                        if (!Float.isNaN(bw)) {
                            boolean narrow = bw < MicrophoneGuard.BANDWIDTH_OK_DB;
                            Log.i(TAG, String.format(Locale.US, "own stream: %.1f dB above 8 kHz - %s",
                                    bw, narrow ? "held at 16 kHz by another app" : "full band"));
                            if (narrow && !reopened) {
                                reopened = true;
                                if (!reopenAfterStoppingAssistant()) break;
                                probed = 0;
                                probing = true;   // listen once more, for the log only
                                continue;
                            }
                        }
                    }
                }

                // Measure peak amplitude of this chunk
                int peak = 0;
                for (int i = 0; i < read; i++) {
                    int abs = Math.abs(shortChunk[i]);
                    if (abs > peak) peak = abs;
                }

                float startGain = currentGain;

                if (peak > NOISE_GATE_THRESHOLD) {
                    float desiredGain = TARGET_PEAK / peak;
                    if (desiredGain > MAX_GAIN) desiredGain = MAX_GAIN;
                    if (desiredGain < MIN_GAIN) desiredGain = MIN_GAIN;

                    if (desiredGain < currentGain) {
                        // Fast attack (~20 ms) against clipping
                        currentGain += (desiredGain - currentGain) * 0.35f;
                    } else {
                        // Smooth release (~200 ms) for musical breathing
                        currentGain += (desiredGain - currentGain) * 0.05f;
                    }
                } else {
                    // Decay towards 1.0 during silence so cabin rumble isn't amplified
                    currentGain += (1.0f - currentGain) * 0.10f;
                }

                // Smooth linear interpolation across the chunk to prevent clicks
                float gainStep = (currentGain - startGain) / read;
                for (int i = 0; i < read; i++) {
                    float g = startGain + gainStep * i;
                    int boosted = Math.round(shortChunk[i] * g);
                    if (boosted > 32767) boosted = 32767;
                    else if (boosted < -32768) boosted = -32768;
                    agcChunk[i] = (short) boosted;
                }

                try {
                    callback.onPcmChunk(agcChunk, read);
                } catch (Throwable t) {
                    Log.w(TAG, "PCM callback exception: " + t);
                }
            }
        }, "wDSP_RadioMic");

        captureThread.setPriority(Thread.MAX_PRIORITY - 1);
        captureThread.start();
        Log.i(TAG, "RadioMicCapture started at " + SAMPLE_RATE + " Hz UNPROCESSED (Adaptive AGC enabled)");
        return true;
    }

    /**
     * Our stream came up narrow: the assistant had opened the input first. Stop it and take the
     * input back at once - it returns within two seconds, and whoever is there first decides the
     * rate for both. Runs on the capture thread, at most once per start, never in a loop.
     *
     * @return false when capturing has to end
     */
    private boolean reopenAfterStoppingAssistant() {
        Context ctx = appContext;
        if (ctx == null || !RootAccess.hasRoot(ctx)) {
            Log.i(TAG, "microphone held at 16 kHz and no root to free it - staying on the narrow stream");
            return true;
        }
        synchronized (this) {
            if (!running) return false;
            releaseRecordLocked();
        }
        int stopped = MicrophoneGuard.stopAssistantsAsRoot(ctx);
        synchronized (this) {
            if (!running) return false;
            boolean ok = openRecordLocked();
            Log.i(TAG, "stopped " + stopped + " assistant(s) and reopened the microphone: "
                    + (ok ? "ok" : "FAILED"));
            if (!ok) running = false;
            return ok;
        }
    }

    /** NaN when the half second was silence or the native library is missing - then nothing is known. */
    private static float bandwidthDb(float[] samples, int n) {
        if (!NativeSweep.isAvailable() || n <= 0) return Float.NaN;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += samples[i] * samples[i];
        if (Math.sqrt(sum / n) < PROBE_MIN_RMS) return Float.NaN;
        return NativeSweep.bandwidthRatioDb(samples, n, SAMPLE_RATE);
    }

    /** Creates and starts the recorder, with the platform's pre-processing switched off on it. */
    private boolean openRecordLocked() {
        AudioRecord rec;
        try {
            rec = new AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );
        } catch (Throwable t) {
            Log.w(TAG, "AudioRecord(UNPROCESSED) failed, trying DEFAULT: " + t);
            try {
                rec = new AudioRecord(
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

        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            try {
                rec.release();
            } catch (Throwable ignored) {}
            return false;
        }

        audioRecord = rec;
        try {
            suspension = MicProbe.suspendCapturePreprocessing(rec.getAudioSessionId(), TAG);
            rec.startRecording();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start recording: " + t);
            releaseRecordLocked();
            return false;
        }
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

        releaseRecordLocked();
        Log.i(TAG, "RadioMicCapture stopped");
    }

    /** Hands the platform's pre-processing back as it was, then lets the recorder go. */
    private void releaseRecordLocked() {
        if (suspension != null) {
            try {
                suspension.restore();
            } catch (Throwable ignored) {}
            suspension = null;
        }
        safeReleaseRecord();
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
