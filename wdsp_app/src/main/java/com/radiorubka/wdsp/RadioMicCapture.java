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
        /**
         * One chunk, delivered twice over.
         *
         * @param boosted samples after the automatic gain - what a waveform should draw
         * @param raw     the samples as the microphone gave them, at a gain of exactly one. Any
         *                analysis that learns a level over time needs these: a gain that moves
         *                between 0.5x and 16x underneath a measurement makes the measurement
         *                describe the gain instead of the car.
         * @param count   valid samples in both arrays
         * @param gain    the gain applied to {@code boosted}, so a caller can undo or report it
         */
        void onPcmChunk(short[] boosted, short[] raw, int count, float gain);
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
    private static final int NOISE_GATE_THRESHOLD = 150; // absolute last resort, see the gate below

    /**
     * How far above the measured floor a chunk must stand before it counts as something to amplify.
     *
     * <p>Three times the floor is about ten decibels. The gate used to be {@link #NOISE_GATE_THRESHOLD}
     * alone - an absolute number - and cabin noise clears it without difficulty: in a pause the gate
     * opened, the gain climbed towards 16x, and every rustle became a full-height bar while the
     * calibration reference it was measured against grew stale. What decides is not how loud a chunk
     * is but how far above the floor it stands, so the floor is measured here, continuously.
     */
    private static final float SIGNAL_OVER_FLOOR = 2.5f;
    /**
     * How fast the floor is allowed to climb, per chunk of {@value #CHUNK_SIZE} samples (~10.7 ms).
     *
     * <p>It falls instantly to any quieter chunk and rises only slowly, which is what a moving car
     * needs: speed, blower and road surface all lift the floor, and a floor measured at a standstill
     * would be wrong a minute later. At this rate it covers a tenfold rise in about half a minute.
     */
    private static final float NOISE_FLOOR_RISE = 0.0015f;

    private AudioRecord audioRecord;
    private MicProbe.Suspension suspension;
    private Thread captureThread;
    private volatile boolean running = false;
    private float currentGain = 1.0f;
    /** The cabin's own level, in RMS counts, measured from the raw stream. 0 until the first chunk. */
    private volatile float noiseRms = 0f;

    /**
     * Whether the application believes nothing is coming out of the speakers right now.
     *
     * <p>Pushed in by {@link AudioSpectrumEngine}, which owns the question: it asks the platform's
     * own source property, the hardware volume manager's active type, Android's playback
     * configurations and {@link NowPlaying} - and it knows that radio bypasses AudioFlinger, so
     * "no media session" does not mean "no sound". Starts false: until told otherwise, assume
     * something is playing and do not learn a floor from music.
     */
    private volatile boolean playbackSilent = false;

    /** Called by the engine whenever its picture of who is playing changes. */
    public void setPlaybackSilent(boolean silent) {
        if (this.playbackSilent != silent) {
            Log.i(TAG, "playback " + (silent ? "silent - the floor may learn" : "live - the floor is held"));
        }
        this.playbackSilent = silent;
    }

    /** What the floor currently reads, in RMS counts, for whoever wants to report or subtract it. */
    public float noiseFloorRms() {
        return noiseRms;
    }

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
        // A fresh floor: the previous session may have ended in a different car, at a different
        // speed, or with the blower on. Carrying its number over would gate this one wrongly.
        noiseRms = 0f;

        captureThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            short[] shortChunk = new short[CHUNK_SIZE];
            short[] agcChunk = new short[CHUNK_SIZE];
            float[] probe = new float[BANDWIDTH_PROBE_SAMPLES];
            int probed = 0;
            boolean probing = true;
            boolean reopened = false;
            // One line a second, not one a chunk: at 512 samples of 48 kHz a chunk is 10.7 ms, and
            // ninety-four log lines a second would cost more than the analysis.
            int logTick = 0;
            final int logEvery = SAMPLE_RATE / CHUNK_SIZE;

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

                // Measured twice: the peak says how much gain would fit without clipping, the RMS
                // says whether there is anything here worth amplifying at all.
                int peak = 0;
                double sumSquares = 0;
                for (int i = 0; i < read; i++) {
                    int abs = Math.abs(shortChunk[i]);
                    if (abs > peak) peak = abs;
                    sumSquares += (double) shortChunk[i] * shortChunk[i];
                }
                final float rms = (float) Math.sqrt(sumSquares / read);

                // The gate asks about the ratio, not the level, and it asks BEFORE the floor is
                // touched. The absolute threshold stays as a last resort for the first chunks,
                // while the floor is still unknown.
                final float ratio = noiseRms > 0f ? rms / noiseRms : 0f;
                final boolean contentPresent = peak > NOISE_GATE_THRESHOLD
                        && (noiseRms <= 0f || ratio > SIGNAL_OVER_FLOOR);

                // The floor learns ONLY while the gate is shut - that is, only while nothing is
                // believed to be playing. The first version of this updated it on every chunk, and
                // the measurement showed exactly what that costs: in silence the floor sat at ~190
                // counts, and thirty seconds into music it had climbed to 4241 while the music
                // itself read 7746. The ratio collapsed to 1.8, the gate shut in the middle of the
                // music, and the analyser went deaf below about five units of volume - reported from
                // the car, not guessed. A floor that is allowed to learn while content plays walks
                // onto the content: the same fault as the branch just removed from analyzer.cpp:287,
                // one layer up.
                //
                // Learning only in the gaps is also what the owner asked for in the first place -
                // re-measure the cabin in the pauses. Instant down to anything quieter, slow up, so
                // a car that gets noisier with speed is followed between tracks rather than during
                // them.
                // Two conditions, not one. The gate being shut is our own opinion about the sound;
                // playbackSilent is what the application KNOWS - who is playing and whether they are
                // paused. Guessing the pause from the audio was the wrong way round: the program
                // already tracks the players, the screensaver drives their play/pause, and the
                // platform reports the active source. When the speakers are silent, whatever the
                // microphone hears IS the cabin - no inference needed. (Owner, 13.09.2026: «не треба
                // вгадувати сиру величину - динаміки мовчать - ось тобі й сирий шум».)
                final boolean learnFloor = !contentPresent && playbackSilent;
                if (learnFloor) {
                    if (noiseRms <= 0f || rms < noiseRms) {
                        noiseRms = rms;
                    } else {
                        noiseRms += (rms - noiseRms) * NOISE_FLOOR_RISE;
                    }
                } else if (noiseRms <= 0f) {
                    // Nothing has ever been measured and something is playing: seed the floor low
                    // rather than leave it at zero, or the ratio stays 0 and the gate can only be
                    // opened by the absolute threshold.
                    noiseRms = Math.min(rms, NOISE_GATE_THRESHOLD);
                }

                float startGain = currentGain;

                if (contentPresent) {
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

                // What the gate decided and why. This is the only way to tell, from a car, whether a
                // climbing bar is music or the gate letting the floor through: three numbers and
                // their ratio say it outright, where a picture cannot.
                if (++logTick >= logEvery) {
                    logTick = 0;
                    Log.i(TAG, String.format(Locale.US,
                            "gate: rms=%.0f floor=%.0f ratio=%.2f (opens at %.2f) peak=%d gain=%.2f %s, floor %s",
                            rms, noiseRms, ratio, SIGNAL_OVER_FLOOR,
                            peak, currentGain,
                            contentPresent ? "OPEN" : "closed",
                            learnFloor ? "learning" : (playbackSilent ? "held (gate open)" : "held (a player is live)")));
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
                    callback.onPcmChunk(agcChunk, shortChunk, read, currentGain);
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
    /** Once per process: the restart advice is worth one toast, not one on every reopen. */
    private static volatile boolean sToldToRestart;

    private boolean reopenAfterStoppingAssistant() {
        Context ctx = appContext;
        if (ctx == null || !RootAccess.hasRoot(ctx)) {
            Log.i(TAG, "microphone held at 16 kHz and no root to free it - staying on the narrow stream");
            // Without root there is no fighting for the input, and there does not need to be: after
            // a start-up wDSP opens the microphone first (measured 14.09.2026, 31 s ahead of the
            // assistant). So the honest thing is to say how to get the full band back (owner's
            // wording, 14.09.2026) - not to stop anyone, and not to stay silent about it.
            if (ctx != null && !sToldToRestart) {
                sToldToRestart = true;
                Toaster.show(ctx, R.string.mic_narrow_restart);
            }
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
