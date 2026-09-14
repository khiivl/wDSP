package com.radiorubka.wdsp;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.List;
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
 * to its own first half second - it reads the device rate the platform reports for our recording
 * (the content itself cannot tell: a quiet cabin has nothing above 8 kHz either) - and if the input
 * runs at 16 kHz it stops the assistant once, through root, and reopens at once - the assistant is
 * back within two seconds and has to find us there.
 *
 * <p>The input can also be rebuilt underneath an open capture: an audioserver restart restores the
 * record inside the same AudioRecord, with no error, at whatever rate the first client to come back
 * asked for (measured 14.09.2026). So the platform's recording callback is listened to for the
 * device rate. Without root, or when the heal does not take, the microphone is declared unavailable
 * and the spectrum goes to calculated (owner's decision, 14.09.2026) - a narrow stream is never shown
 * as the cabin - until {@link MicInputWindow} sees the input free and the capture is opened again.
 *
 * <h2>Effects are not ours to operate</h2>
 *
 * <p>The capture opens {@code UNPROCESSED}, the source the platform attaches no pre-processing to
 * (on this unit {@code /vendor/etc/audio_effects.xml} gives AEC and NS to {@code mic},
 * {@code voice_communication} and {@code voice_recognition}, nothing to {@code unprocessed}), and
 * then leaves the effects alone. It used to switch AEC and NS off on its session. On a shared
 * input only one session's chain is applied - the dump of 14.09.2026 showed ours active and the
 * assistant's AEC and NS suspended - so switching ours off switched them off for the assistant too.
 * Owner, 14.09.2026: "ці ефекти важливі асистенту, дзвінкам. Ми маємо тупо сідати на потік без
 * ефектів, а не оперувати ними".
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

    /** The rate this capture asks for, and the rate its analyser is built at. */
    static final int SAMPLE_RATE = 48000;
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

    /** Volatile because the recording callback reads it on the main thread. */
    private volatile AudioRecord audioRecord;
    /** Volatile because isCapturing() reads it from other threads, without the lock. */
    private volatile Thread captureThread;
    private volatile boolean running = false;
    private float currentGain = 1.0f;
    /** The cabin's own level, in RMS counts, measured from the raw stream. 0 until the first chunk. */
    private volatile float noiseRms = 0f;
    /**
     * Set from outside, consumed by the capture thread before its next chunk. The floor and the gain
     * are the capture thread's own; writing them from another thread would race its
     * read-modify-write and could be undone by the chunk already in flight.
     */
    private volatile boolean forgetFloorRequested = false;

    /**
     * Set by the platform's recording callback when the device under our recorder runs below
     * {@link #SAMPLE_RATE}; consumed by the capture thread, which alone decides what to do.
     */
    private volatile boolean narrowReported = false;
    private volatile int reportedDeviceRate = 0;
    /**
     * Session of the most recent recorder, kept after it is released: the platform can list a
     * recording for a moment after it is gone, and whoever waits for the input to be free must not
     * mistake ours for somebody else's.
     */
    private volatile int lastSessionId = 0;

    public int lastSessionId() {
        return lastSessionId;
    }

    /**
     * Recordings already active when our recorder was opened - taken just before it, so none of
     * them is ours. Non-zero means we joined somebody else's input: whoever opened it set it up, and
     * on 14.09.2026 that left our session with the echo canceller and noise suppressor active.
     */
    private volatile int othersOnInputAtOpen = 0;
    private AudioManager audioManager;
    private AudioManager.AudioRecordingCallback recordingCallback;

    /**
     * Forget the cabin's floor and the gain, as a fresh start would, without closing the stream.
     * For waking up: the car was parked and started since the floor was learned.
     */
    public void forgetNoiseFloor() {
        forgetFloorRequested = true;
    }

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
        if (isCapturing()) return true;
        // Started, but the read loop is gone: the stream died underneath us. Give its recorder back
        // before opening a new one. This used to answer "already running" and do nothing.
        if (running) stop();
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
        registerRecordingCallbackLocked();
        currentGain = 1.0f;
        // A fresh floor: the previous session may have ended in a different car, at a different
        // speed, or with the blower on. Carrying its number over would gate this one wrongly.
        noiseRms = 0f;
        forgetFloorRequested = false;

        captureThread = new Thread(() -> {
          try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            short[] shortChunk = new short[CHUNK_SIZE];
            short[] agcChunk = new short[CHUNK_SIZE];
            float[] probe = new float[BANDWIDTH_PROBE_SAMPLES];
            int probed = 0;
            boolean probing = true;
            // One attempt to take the input back through root per narrowing. Cleared once the
            // stream is heard full band again, so a later narrowing - the next audioserver restart,
            // an hour on - gets its own attempt; a heal that did not take ends in "unavailable"
            // rather than in a loop of stopping the assistant.
            boolean healAttempted = false;
            // One attempt per open to take a full-band input that somebody else opened first.
            boolean takeoverAttempted = false;
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
                    // Every negative value ends the loop. ERROR_DEAD_OBJECT (-6) - the recorder is
                    // gone, e.g. audioserver restarted - used to fall through to the sleep below and
                    // spin there for ever, a thread that looked alive over a stream that was not.
                    if (read < 0) {
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

                if (forgetFloorRequested) {
                    forgetFloorRequested = false;
                    noiseRms = 0f;
                    currentGain = 1.0f;
                }

                // Whether the input is narrow is the platform's fact, never our ear's guess: the
                // device rate of our own recording, as AudioManager describes it. Read half a second
                // into every open, and again whenever the recording callback says the input was
                // rebuilt underneath us - which after an audioserver restart happens with no error,
                // the same AudioRecord and the same recording id (measured 14.09.2026).
                //
                // 🔴 The half-second listen used to decide, and it cannot: it judges the CONTENT
                // above 8 kHz, and a quiet cabin has none. After a cold boot on 14.09.2026 our capture
                // was the only client of a 48 kHz input, nothing was playing yet, the listen read
                // "narrow", and with root denied the microphone was declared unavailable until
                // restart, 0.8 s after it opened. The listen stays, for the log only.
                String narrowBecause = null;
                String takeoverBecause = null;
                if (narrowReported) {
                    narrowReported = false;
                    narrowBecause = "the platform reports the input at " + reportedDeviceRate + " Hz";
                }
                if (probing) {
                    int n = Math.min(read, probe.length - probed);
                    for (int i = 0; i < n; i++) probe[probed + i] = shortChunk[i] / 32768f;
                    probed += n;
                    if (probed == probe.length) {
                        probing = false;
                        float bw = bandwidthDb(probe, probed);
                        int deviceRate = deviceRateNow();
                        Log.i(TAG, String.format(Locale.US,
                                "own stream: %s above 8 kHz (content, for the record); input device at %s",
                                Float.isNaN(bw) ? "silence" : String.format(Locale.US, "%.1f dB", bw),
                                deviceRate > 0 ? deviceRate + " Hz" : "unknown"));
                        if (deviceRate > 0 && deviceRate < SAMPLE_RATE) {
                            narrowBecause = "input device at " + deviceRate + " Hz";
                        } else if (deviceRate >= SAMPLE_RATE && othersOnInputAtOpen > 0
                                && !takeoverAttempted && RootAccess.hasRoot()) {
                            // Full band, but not ours: somebody opened the input before us, and the
                            // one who opens it sets it up (owner, 14.09.2026: with root, take it -
                            // "в любому випадку"). Without root this is left alone; a unit without
                            // root holds the microphone from start-up, so it is first anyway.
                            takeoverBecause = othersOnInputAtOpen + " recording(s) were on the input before us";
                        } else if (deviceRate >= SAMPLE_RATE) {
                            healAttempted = false;
                            UnavailableListener l = unavailableListener;
                            if (l != null) l.onMicrophoneFullBand();
                        }
                    }
                }
                if (narrowBecause != null) {
                    // Owner, 14.09.2026: with root, heal it on the fly; without root, the microphone
                    // is not available - no narrow stream is shown as if it were the cabin.
                    if (!healAttempted && RootAccess.hasRoot()) {
                        healAttempted = true;
                        Log.i(TAG, "input narrow (" + narrowBecause + ") - taking it back through root");
                        if (!reopenAfterStoppingAssistant()) break;
                        probed = 0;
                        probing = true;   // the heal is judged by the next half second
                        continue;
                    }
                    Log.w(TAG, "input narrow (" + narrowBecause + ")"
                            + (healAttempted ? " again after stopping the assistant" : ", no root")
                            + " - microphone unavailable until the input is free");
                    UnavailableListener l = unavailableListener;
                    if (l != null) l.onMicrophoneUnavailable();
                    break;
                }
                if (takeoverBecause != null) {
                    takeoverAttempted = true;
                    Log.i(TAG, takeoverBecause + " - taking the input through root");
                    if (!reopenAfterStoppingAssistant()) break;
                    probed = 0;
                    probing = true;   // our own input is judged by the next half second
                    continue;
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
          } finally {
            // Nobody asked us to stop, yet the loop is over: from here isCapturing() says so, and
            // the engine opens the microphone again on its next start or watchdog pass.
            if (running && captureThread == Thread.currentThread()) {
                Log.w(TAG, "capture loop ended by itself - the stream is dead until reopened");
            }
          }
        }, "wDSP_RadioMic");

        captureThread.setPriority(Thread.MAX_PRIORITY - 1);
        captureThread.start();
        Log.i(TAG, "RadioMicCapture started at " + SAMPLE_RATE + " Hz UNPROCESSED (Adaptive AGC enabled)");
        return true;
    }

    /** Called on the capture thread. */
    public interface UnavailableListener {
        /**
         * The microphone cannot be had at full band and root cannot fix it. The capture has already
         * ended by the time this is called; what to show instead is the listener's business.
         */
        void onMicrophoneUnavailable();

        /**
         * Half a second into an open, the platform reports the input under our recorder at full
         * band - after a cold start, and after taking the input back through a gap.
         */
        void onMicrophoneFullBand();
    }

    private volatile UnavailableListener unavailableListener;

    public void setUnavailableListener(UnavailableListener listener) {
        this.unavailableListener = listener;
    }

    /**
     * Somebody else holds the input - narrow, or first. Stop whoever it is and take the input back
     * - they return within two seconds, and whoever is there first sets the input up for everybody.
     * Root only; the caller has checked. Runs on the capture thread.
     *
     * <p>Then both halves of the owner's check: our input is judged by the next half-second probe,
     * and whether the stopped app is running again is reported by
     * {@link MicrophoneGuard#checkCameBackAsync}.
     *
     * @return false when capturing has to end
     */
    private boolean reopenAfterStoppingAssistant() {
        Context ctx = appContext;
        if (ctx == null) return false;
        synchronized (this) {
            if (!running) return false;
            releaseRecordLocked();
            // Whatever the platform said so far was about the recorder just released.
            narrowReported = false;
        }
        java.util.List<String> stopped = MicrophoneGuard.takeInputAsRoot(ctx);
        synchronized (this) {
            if (!running) return false;
            boolean ok = openRecordLocked();
            Log.i(TAG, "stopped " + stopped + " and reopened the microphone: " + (ok ? "ok" : "FAILED")
                    + (ok ? ", " + othersOnInputAtOpen + " other recording(s) on the input now" : ""));
            if (!ok) running = false;
            MicrophoneGuard.checkCameBackAsync(ctx, stopped);
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

    /** Creates and starts the recorder. Its effects are left exactly as the platform set them. */
    private boolean openRecordLocked() {
        othersOnInputAtOpen = activeRecordingsNow();
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
        lastSessionId = rec.getAudioSessionId();
        try {
            rec.startRecording();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start recording: " + t);
            releaseRecordLocked();
            return false;
        }
        return true;
    }

    /**
     * Listens for the platform rebuilding the input under our recorder. Registered once per start;
     * a reopen inside the capture keeps it, because it matches by the current recorder's session.
     */
    private void registerRecordingCallbackLocked() {
        if (recordingCallback != null || appContext == null) return;
        audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        recordingCallback = new AudioManager.AudioRecordingCallback() {
            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                checkDeviceRate(configs);
            }
        };
        try {
            audioManager.registerAudioRecordingCallback(recordingCallback,
                    new Handler(Looper.getMainLooper()));
        } catch (Throwable t) {
            Log.w(TAG, "recording callback unavailable: " + t);
            recordingCallback = null;
        }
    }

    private void unregisterRecordingCallbackLocked() {
        if (recordingCallback == null || audioManager == null) return;
        try {
            audioManager.unregisterAudioRecordingCallback(recordingCallback);
        } catch (Throwable ignored) {
        }
        recordingCallback = null;
    }

    /** Every active recording the platform lists, whoever's; 0 when it cannot be asked. */
    private int activeRecordingsNow() {
        Context ctx = appContext;
        AudioManager am = ctx != null ? (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE) : null;
        if (am == null) return 0;
        try {
            return am.getActiveRecordingConfigurations().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * The device rate under our recorder right now, or 0 when the platform does not list it (yet).
     * The same fact the recording callback carries, asked for directly.
     */
    private int deviceRateNow() {
        AudioManager am = audioManager;
        AudioRecord rec = audioRecord;
        if (am == null || rec == null) return 0;
        try {
            int session = rec.getAudioSessionId();
            for (AudioRecordingConfiguration config : am.getActiveRecordingConfigurations()) {
                if (config.getClientAudioSessionId() != session) continue;
                AudioFormat device = config.getFormat();
                return device != null ? device.getSampleRate() : 0;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * Our own recording, found by session, as the platform describes it. getFormat() is the device
     * side - after the 14.09.2026 audioserver restart it read 16000 Hz while everything the recorder
     * says about itself still read 48000.
     */
    private void checkDeviceRate(List<AudioRecordingConfiguration> configs) {
        AudioRecord rec = audioRecord;
        if (!running || rec == null || configs == null) return;
        final int session;
        try {
            session = rec.getAudioSessionId();
        } catch (Throwable t) {
            return;
        }
        for (AudioRecordingConfiguration config : configs) {
            if (config.getClientAudioSessionId() != session) continue;
            AudioFormat device = config.getFormat();
            int rate = device != null ? device.getSampleRate() : 0;
            if (rate > 0 && rate < SAMPLE_RATE) {
                reportedDeviceRate = rate;
                if (!narrowReported) {
                    Log.w(TAG, "recording callback: the input under our recorder runs at " + rate + " Hz");
                }
                narrowReported = true;
            }
            return;
        }
    }

    public synchronized void stop() {
        running = false;
        unregisterRecordingCallbackLocked();
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

    /** Lets the recorder go. There is no pre-processing state of ours to hand back any more. */
    private void releaseRecordLocked() {
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

    /**
     * Started and not yet stopped - that is, holding a recorder that stop() has to give back. Says
     * nothing about whether anything is being read; that is {@link #isCapturing()}.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Started, and the read loop is still alive.
     *
     * <p>Not the same as {@link #isRunning()}, and the difference is the point: the loop leaves on a
     * read error while {@code running} stays true until someone calls stop(). Asked "is the
     * microphone ours" with isRunning(), a dead stream answered yes indefinitely - hidden only
     * because every new listener closed and reopened the capture anyway (measured 14.09.2026).
     */
    public boolean isCapturing() {
        Thread t = captureThread;
        return running && t != null && t.isAlive();
    }
}
