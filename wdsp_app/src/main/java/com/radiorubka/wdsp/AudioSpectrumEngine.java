package com.radiorubka.wdsp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized audio spectrum capture and 16-band Post-DSP analysis engine.
 * Sourced from Android's global output mix via {@link Visualizer} (audio session 0).
 *
 * Synthesizes the actual acoustic spectrum AFTER wDSP processing (EQ gains, Q-factor bell curves,
 * Fletcher-Munson Loudness compensation, and fatigue filters) so the user observes the true
 * post-processed sound rather than pre-DSP input.
 *
 * Broadcasts calculated band levels to subscribed listeners (SpectrumAnalyzerView in MainActivity
 * and StatusBarVisualizerView) without multiple conflicting Visualizer sessions.
 */
public class AudioSpectrumEngine {
    private static final String TAG = "wDSP_SpectrumEngine";

    // Edge frequencies (Hz) bounding each of the 16 AudioConfig bands, placed
    // at the geometric mean between neighboring band centers (20...20k).
    private static final float[] BAND_EDGES_HZ = {
            15.9f, 25.1f, 39.7f, 63.2f, 100f, 158.1f, 250.8f, 397.4f,
            632.5f, 1000f, 1581.1f, 2506.0f, 3969.1f, 6299.6f, 10000f, 15849f, 25198f
    };

    private static final float REF_MIN_DB = 0f;
    private static final float REF_MAX_DB = 54f;
    private static final float RISE_SMOOTHING = 0.92f; // Instant explosive attack on beats/transients
    private static final float FALL_SMOOTHING = 0.26f; // Snappy musical release for punchy bounce
    private static final float ATTENUATION_DB = 8f;
    private static final float SILENCE_FADE_DB = 3.0f;

    // Residual measurement tilt, in dB per band. Zero by design: with band energy summed
    // instead of averaged per bin, pink noise reads flat on its own. The previous table rose to
    // +25.5 dB at 20 kHz because it was fitted on top of the averaging bug - it made pink noise
    // look perfect while burying real, narrow-band content such as cymbals. Re-measure with a
    // pink noise disc before putting any number back here.
    private static final float[] PINK_NOISE_CALIBRATION_DB = new float[16];

    // Fast, responsive bass resolution (4096-sample FFT window for 10.7Hz bin resolution, zero throttling)
    private static final int LOW_FFT_SIZE = 4096;
    // Scale 4096-FFT peak magnitude (N/2 = 2048 for full-scale float) to match Android 8-bit FFT magnitude domain (~128 max)
    private static final float LOW_BAND_MAGNITUDE_SCALE = 128.0f / (LOW_FFT_SIZE / 2.0f); // 0.0625f

    private final float[] lowRingBuffer = new float[LOW_FFT_SIZE];
    private int lowRingFill = 0;
    private long lastLowFftTime = 0;
    private boolean lowBandsReady = false;
    /** Band 0 and 1 energy, already converted into the same units as the main FFT path. */
    private final double[] lowBandPower = new double[2];
    private final int[] lowBandBins = new int[2];
    /** Coherent gain of the Hann window applied before the low-band transform. */
    private static final float HANN_COHERENT_GAIN = 0.5f;
    private final float[] fftScratchRe = new float[LOW_FFT_SIZE];
    private final float[] fftScratchIm = new float[LOW_FFT_SIZE];

    private Visualizer visualizer;

    public static final int NUM_BANDS_16 = 16;
    public static final int NUM_BANDS_32 = 32;

    private final float[] rawLevels16 = new float[NUM_BANDS_16];
    private final float[] displayLevels16 = new float[NUM_BANDS_16];
    private final float[] prevLevels16 = new float[NUM_BANDS_16];

    private final float[] rawLevels16Norm = new float[NUM_BANDS_16];
    private final float[] displayLevels16Norm = new float[NUM_BANDS_16];
    private final float[] prevLevels16Norm = new float[NUM_BANDS_16];

    private final float[] smoothedContentDb = new float[NUM_BANDS_16];

    /** Per-band noise floor in the power domain - see the subtraction in processFft(). */
    private final double[] noiseFloorPower = new double[NUM_BANDS_16];
    /** Within a quiet frame the floor drops at once and rises only very slowly. */
    private static final float NOISE_FLOOR_RISE = 0.0002f;
    /** Loudest recent frame, used to recognise a quiet one. Decays slowly so it survives a pause. */
    private double frameMaxPower = 0;
    private static final double FRAME_MAX_DECAY = 0.999;
    /** A frame this far below the loudest recent one counts as silence: about 17 dB down. */
    private static final double QUIET_FRACTION = 0.02;
    /** Subtract slightly more than the floor so noise reads as nothing, not as a low bar. */
    private static final float NOISE_FLOOR_MARGIN = 1.2f;
    private static final double POWER_EPSILON = 1e-6;
    /** Offset applied after the power-to-dB conversion, to sit in the display's 0..54 dB window. */
    private static final float POWER_REFERENCE_DB = 0f;

    // DSP state as last sent to the hardware by McuService: Fletcher-Munson already folded into
    // the gain indices, quantised to 2 dB steps and clamped at +/-12, plus the subwoofer. This is
    // the truth about what the listener hears; the setGains/setQFactors/setFmOffsets values below
    // are raw slider positions from MainActivity and are only a fallback for when the service has
    // not published yet.
    private final int[] dspGainIdx = new int[NUM_BANDS_16];
    private final boolean[] dspQNarrow = new boolean[NUM_BANDS_16];
    private int dspSubFreqIdx = -1;
    private int dspSubGainIdx = 0;
    private boolean hasServiceDspState = false;

    private final float[] dspCurveDb = new float[NUM_BANDS_16];
    private volatile boolean dspCurveDirty = true;
    private float dspCurveSampleRate = 0f;

    private final float[] rawLevels32 = new float[NUM_BANDS_32];
    private final float[] displayLevels32 = new float[NUM_BANDS_32];
    private final float[] prevLevels32 = new float[NUM_BANDS_32];

    private final float[] rawLevels32Norm = new float[NUM_BANDS_32];
    private final float[] displayLevels32Norm = new float[NUM_BANDS_32];
    private final float[] prevLevels32Norm = new float[NUM_BANDS_32];

    // State parameters for Post-DSP synthesis
    private final int[] gains = new int[NUM_BANDS_16];
    private final boolean[] qNarrow = new boolean[NUM_BANDS_16];
    private final float[] fmOffsets = new float[NUM_BANDS_16];
    private float runningPeakDb = 25f;

    private long lastCaptureTime = 0;
    private long captureIntervalMs = 50;

    public interface OnSpectrumDataListener {
        void onSpectrumCapture(float[] displayLevels16, float[] displayLevels16Norm,
                               float[] prevLevels16, float[] prevLevels16Norm,
                               float[] displayLevels32, float[] displayLevels32Norm,
                               float[] prevLevels32, float[] prevLevels32Norm,
                               long lastCaptureTime, long captureIntervalMs);
    }

    private final CopyOnWriteArrayList<OnSpectrumDataListener> listeners = new CopyOnWriteArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<OnSpectrumDataListener, Integer> consumerOf
            = new java.util.concurrent.ConcurrentHashMap<>();

    // --- Native path ---------------------------------------------------------------------------
    //
    // When the shared library is present the whole measurement chain runs in C++ and the platform
    // Visualizer is used only as a tap. Two things change fundamentally:
    //
    // Capture becomes polled instead of callback-driven. The callback rate is capped at 20 Hz and
    // hands over 1024 samples each time, which at 48 kHz is 21 ms of audio out of every 50 - the
    // rest is lost, and no transform over the stitched remains can be trusted below the block
    // rate. Polling faster than the block duration makes consecutive reads overlap, and the native
    // stitcher aligns them by cross-correlation into a genuinely continuous stream.
    //
    // Analysis becomes 32 third-octave bands folded down to the 16 hardware bands, rather than 16
    // bands linearly interpolated up to 32 as before. Interpolation cannot create detail that was
    // never measured, which is why the bottom of the display used to move as one lump.

    private NativeAnalyzer nativeAnalyzer;
    private Thread pollThread;
    private Thread displayThread;
    private volatile boolean capturePolling = false;

    /** Poll period. Must be shorter than one block (21 ms at 48 kHz) for the reads to overlap. */
    private static final long POLL_PERIOD_MS = 9;
    /** Display refresh, independent of how fast measurements arrive. */
    private static final long DISPLAY_PERIOD_MS = 16;

    private final ConsumerFrames[] frames = {new ConsumerFrames(), new ConsumerFrames()};

    // Display settings, read from preferences and pushed into native. Defaults are deliberately
    // asymmetric: the main analyser starts with its automatic gain OFF, because a tool that
    // silently rescales itself cannot be read, while the status bar widget starts with it ON,
    // because there it is decoration and a flat line would just look broken.
    public static final String PREF_AGC_MAIN_ENABLED = "spec_agc_main_enabled";
    public static final String PREF_AGC_MAIN_STRENGTH = "spec_agc_main_strength";
    public static final String PREF_AGC_MAIN_FLOOR = "spec_agc_main_floor_db";
    public static final String PREF_AGC_BAR_ENABLED = "spec_agc_bar_enabled";
    public static final String PREF_AGC_BAR_STRENGTH = "spec_agc_bar_strength";
    public static final String PREF_AGC_BAR_FLOOR = "spec_agc_bar_floor_db";
    public static final String PREF_LATENCY_TRIM = "spec_latency_trim_ms";
    public static final String PREF_RANGE_DB = "spec_range_db";

    private float nativeAttackMs = 25f;
    private float nativeReleaseMs = 260f;
    private float nativeLatencyMs = 0f;
    private float nativeRefMaxDb = 0f;
    private float nativeRangeDb = 60f;

    private boolean mainAgcEnabled = false;
    private float mainAgcStrength = 0.6f;
    private float mainAgcFloorDb = -45f;
    private boolean barAgcEnabled = true;
    private float barAgcStrength = 1.0f;
    private float barAgcFloorDb = -50f;

    /** User trim on top of the measured playback latency, +/- 250 ms. */
    private int latencyTrimMs = 0;

    /** Re-reads the display preferences and pushes them into the native analyser. */
    public void loadDisplaySettings(Context context) {
        if (context == null) return;
        android.content.SharedPreferences prefs =
                com.radiorubka.wdsp.ui.theme.ThemeManager.prefs(context.getApplicationContext());
        mainAgcEnabled = prefs.getBoolean(PREF_AGC_MAIN_ENABLED, false);
        mainAgcStrength = prefs.getInt(PREF_AGC_MAIN_STRENGTH, 60) / 100f;
        mainAgcFloorDb = prefs.getInt(PREF_AGC_MAIN_FLOOR, -45);
        barAgcEnabled = prefs.getBoolean(PREF_AGC_BAR_ENABLED, true);
        barAgcStrength = prefs.getInt(PREF_AGC_BAR_STRENGTH, 100) / 100f;
        barAgcFloorDb = prefs.getInt(PREF_AGC_BAR_FLOOR, -50);
        latencyTrimMs = prefs.getInt(PREF_LATENCY_TRIM, 0);
        nativeRangeDb = prefs.getInt(PREF_RANGE_DB, 60);
        nativeLatencyMs = Math.max(0f, measuredLatencyMs() + latencyTrimMs);
        applyNativeSettings();
    }

    /**
     * Best estimate of how long after capture the audio is actually heard.
     *
     * What the Visualizer hands us has not left the mixer yet: the track's own dump reports a
     * latency of half a second, and on top of that sits the head unit's own path. Players differ
     * wildly here - a streaming client buffers far more than the system player - so this is only
     * ever a starting point, which is why the user gets a trim.
     */
    private float measuredLatencyMs() {
        float outputMs = 0f;
        try {
            if (audioManager != null) {
                // Framework-reported output latency, hidden API, present on this platform.
                java.lang.reflect.Method m = audioManager.getClass()
                        .getMethod("getOutputLatency", int.class);
                Object result = m.invoke(audioManager, android.media.AudioManager.STREAM_MUSIC);
                if (result instanceof Integer) outputMs = (Integer) result;
            }
        } catch (Throwable ignored) {
        }
        if (outputMs <= 0f) outputMs = DEFAULT_OUTPUT_LATENCY_MS;
        return outputMs + BU32107_LATENCY_MS;
    }

    /** Used when the framework will not say. Measured on this platform as roughly half a second. */
    private static final float DEFAULT_OUTPUT_LATENCY_MS = 480f;
    /** Allowance for the hardware DSP itself. Small: it is a filter bank, not a buffer. */
    private static final float BU32107_LATENCY_MS = 20f;

    /** One set of display buffers per consumer, matching the shape the views already expect. */
    private static final class ConsumerFrames {
        final float[] level32 = new float[NUM_BANDS_32];
        final float[] level16 = new float[NUM_BANDS_16];
        final float[] level32Agc = new float[NUM_BANDS_32];
        final float[] level16Agc = new float[NUM_BANDS_16];

        final float[] display16 = new float[NUM_BANDS_16];
        final float[] display16Norm = new float[NUM_BANDS_16];
        final float[] prev16 = new float[NUM_BANDS_16];
        final float[] prev16Norm = new float[NUM_BANDS_16];
        final float[] display32 = new float[NUM_BANDS_32];
        final float[] display32Norm = new float[NUM_BANDS_32];
        final float[] prev32 = new float[NUM_BANDS_32];
        final float[] prev32Norm = new float[NUM_BANDS_32];
    }

    private static AudioSpectrumEngine instance;

    public static synchronized AudioSpectrumEngine getInstance() {
        if (instance == null) {
            instance = new AudioSpectrumEngine();
        }
        return instance;
    }

    private AudioSpectrumEngine() {
        Arrays.fill(gains, 6);
        Arrays.fill(qNarrow, false);
        Arrays.fill(fmOffsets, 0f);
    }

    public synchronized void registerListener(OnSpectrumDataListener listener) {
        registerListener(listener, NativeAnalyzer.CONSUMER_MAIN);
    }

    /**
     * @param consumer which automatic-gain profile this listener wants - the main analyser is an
     *                 instrument and may be asked to show absolute levels, while the status bar
     *                 widget is decoration and is usually allowed to normalise.
     */
    public synchronized void registerListener(OnSpectrumDataListener listener, int consumer) {
        if (listener == null) return;
        consumerOf.put(listener, consumer);
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        if (!listeners.isEmpty() && visualizer == null) {
            start();
        }
    }

    public synchronized void unregisterListener(OnSpectrumDataListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
        consumerOf.remove(listener);
        if (listeners.isEmpty() && visualizer != null) {
            stop();
        }
    }

    public void setGains(int[] newGains) {
        if (newGains == null) return;
        synchronized (gains) {
            System.arraycopy(newGains, 0, this.gains, 0, Math.min(newGains.length, AudioConfig.NUM_BANDS));
        }
        dspCurveDirty = true;
    }

    public void setQFactors(boolean[] newQNarrow) {
        if (newQNarrow == null) return;
        synchronized (qNarrow) {
            System.arraycopy(newQNarrow, 0, this.qNarrow, 0, Math.min(newQNarrow.length, AudioConfig.NUM_BANDS));
        }
        dspCurveDirty = true;
    }

    /**
     * Publishes the DSP state exactly as it was sent to the hardware. Called by McuService, which
     * is the only place that knows the final values - it is the one that folds in the
     * Fletcher-Munson curve, quantises to the hardware's 2 dB steps, clamps at +/-12 dB and
     * applies the low-volume subwoofer compensation.
     *
     * Feeding the analyser from here rather than from MainActivity also means the status bar
     * visualizer stays correct while the UI is closed.
     */
    public void setDspState(int[] gainIdx, boolean[] qNarrow, int subFreqIdx, int subGainIdx) {
        synchronized (dspGainIdx) {
            if (gainIdx != null) {
                System.arraycopy(gainIdx, 0, dspGainIdx, 0, Math.min(gainIdx.length, NUM_BANDS_16));
            }
            if (qNarrow != null) {
                System.arraycopy(qNarrow, 0, dspQNarrow, 0, Math.min(qNarrow.length, NUM_BANDS_16));
            }
            dspSubFreqIdx = subFreqIdx;
            dspSubGainIdx = subGainIdx;
            hasServiceDspState = true;
            dspCurveDirty = true;
        }
    }

    // --- Diagnostics, driven by the PROBE_SESSION debug broadcast ---

    private volatile boolean debugDump = false;
    private long lastDumpTime = 0;

    /** Logs one line per second: content level and DSP curve per band, so the numbers can be checked. */
    public void setDebugDump(boolean enabled) {
        this.debugDump = enabled;
        Log.i(TAG, "Band dump " + (enabled ? "ON" : "OFF")
                + "; session=" + currentSessionId
                + " captureRate=" + Visualizer.getMaxCaptureRate() + " mHz"
                + " captureSizeRange=" + Arrays.toString(Visualizer.getCaptureSizeRange()));
    }

    private final float[] dumpDb32 = new float[NUM_BANDS_32];

    /** Logs the 32 measured bands plus the health of the stitcher. */
    private void dumpNativeBands(NativeAnalyzer analyzer) {
        long now = System.currentTimeMillis();
        if (now - lastDumpTime < 1000) return;
        lastDumpTime = now;
        analyzer.getLevelsDb(dumpDb32);
        StringBuilder sb = new StringBuilder("NATIVE32 ");
        for (int i = 0; i < NUM_BANDS_32; i++) {
            sb.append(String.format(java.util.Locale.US, "%.0f ", dumpDb32[i]));
        }
        Log.i(TAG, sb.toString());
        Log.i(TAG, "NATIVE frames=" + analyzer.frames()
                + " discontinuities=" + analyzer.discontinuities()
                + " latencyMs=" + nativeLatencyMs
                + " agcMain=" + mainAgcEnabled + " agcBar=" + barAgcEnabled);
    }

    private void dumpBands(float[] contentDb, float[] curveDb) {
        long now = System.currentTimeMillis();
        if (now - lastDumpTime < 1000) return;
        lastDumpTime = now;
        StringBuilder level = new StringBuilder("LEVEL ");
        StringBuilder curve = new StringBuilder("CURVE ");
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            level.append(String.format(java.util.Locale.US, "%s=%.0f ", AudioConfig.BAND_LABELS[i], contentDb[i]));
            curve.append(String.format(java.util.Locale.US, "%s=%+.1f ", AudioConfig.BAND_LABELS[i], curveDb[i]));
        }
        Log.i(TAG, level.toString());
        Log.i(TAG, curve.toString());
    }

    /** The DSP response curve, recomputed only when the state or the sample rate changed. */
    private float[] getDspCurve(float sampleRateHz) {
        synchronized (dspGainIdx) {
            if (dspCurveDirty || sampleRateHz != dspCurveSampleRate) {
                if (hasServiceDspState) {
                    DspResponse.compute(dspGainIdx, dspQNarrow, null,
                            dspSubFreqIdx, dspSubGainIdx, sampleRateHz, dspCurveDb);
                } else {
                    // No service state yet: fall back to the raw sliders, and since the curve is
                    // not baked into them here, add the Fletcher-Munson offsets explicitly.
                    synchronized (gains) {
                        synchronized (qNarrow) {
                            synchronized (fmOffsets) {
                                DspResponse.compute(gains, qNarrow, fmOffsets,
                                        -1, 0, sampleRateHz, dspCurveDb);
                            }
                        }
                    }
                }
                dspCurveSampleRate = sampleRateHz;
                dspCurveDirty = false;
            }
            return dspCurveDb;
        }
    }

    public void setFmOffsets(float[] newFmOffsets) {
        synchronized (fmOffsets) {
            if (newFmOffsets == null) {
                Arrays.fill(this.fmOffsets, 0f);
            } else {
                System.arraycopy(newFmOffsets, 0, this.fmOffsets, 0, Math.min(newFmOffsets.length, AudioConfig.NUM_BANDS));
            }
        }
        dspCurveDirty = true;
    }

    private int currentSessionId = 0;
    private AudioManager audioManager;
    private AudioManager.AudioPlaybackCallback playbackCallback;

    private Context appContext;
    private SessionResolver sessionResolver;
    /** Last time the attached session actually delivered something other than silence. */
    private volatile long lastSignalTime = 0;
    private volatile long lastResolveTime = 0;
    private Handler watchdogHandler;

    /** How long the attached session may stay silent while media plays before we re-resolve. */
    private static final long SILENCE_TOLERANCE_MS = 4000;
    /** Floor between two resolutions, so a genuinely quiet track cannot start a sweep loop. */
    private static final long RESOLVE_COOLDOWN_MS = 20000;
    /**
     * Shorter wait after a resolution that found nothing. The usual reason is that the player was
     * momentarily silent - bringing our own UI to the front makes some players duck - and making
     * the user stare at a dead visualizer for twenty seconds over that is not acceptable.
     */
    private static final long RESOLVE_RETRY_MS = 4000;
    private boolean lastResolveFoundNothing = false;
    private static final long WATCHDOG_PERIOD_MS = 2000;
    /** Waveform samples are unsigned 8-bit centred on 128. */
    private static final int SIGNAL_THRESHOLD = 2;

    public void initContext(Context context) {
        if (context == null) return;
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.sessionResolver = SessionResolver.getInstance(appContext);
        this.sessionResolver.start();
        loadDisplaySettings(appContext);
        if (watchdogHandler == null) {
            watchdogHandler = new Handler(Looper.getMainLooper());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null && playbackCallback == null) {
            playbackCallback = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    checkAndSwitchSession();
                }
            };
            try {
                audioManager.registerAudioPlaybackCallback(playbackCallback, new Handler(Looper.getMainLooper()));
            } catch (Throwable t) {
                Log.w(TAG, "Failed to register AudioPlaybackCallback: " + t);
            }
        }

        // Capture usually starts before this runs: MainActivity's views register listeners in
        // onCreate, and only then does it start McuService, which is what gives us a Context.
        // So whatever start() could not do without a resolver has to be done here.
        synchronized (this) {
            if (visualizer != null) {
                lastSignalTime = System.currentTimeMillis();
                watchdogHandler.removeCallbacks(watchdog);
                watchdogHandler.postDelayed(watchdog, WATCHDOG_PERIOD_MS);
            }
        }
        requestResolve("context initialised");
    }

    /**
     * The player changed. We cannot ask the platform which session it uses - on Android 10
     * getActivePlaybackConfigurations() hands non-privileged apps anonymized copies with the
     * session id zeroed - so we hand the question to {@link SessionResolver} and give the new
     * source a moment to prove it carries audio before the watchdog starts judging it.
     */
    private void checkAndSwitchSession() {
        if (listeners.isEmpty()) return;
        // A player came or went. Deliberately no resolution here: the attached session cannot be
        // verified by attaching a second Visualizer to it, and track changes leave gaps of a
        // second or two that are not worth a sweep. Just lift the cooldown so that if the session
        // really has gone, the watchdog may act at once instead of waiting one out.
        lastResolveTime = 0;
    }

    /** Marks the currently attached session as alive; called from the capture callback. */
    private void noteSignal(byte[] waveform) {
        if (waveform == null) return;
        for (byte b : waveform) {
            if (Math.abs((b & 0xFF) - 128) > SIGNAL_THRESHOLD) {
                lastSignalTime = System.currentTimeMillis();
                return;
            }
        }
    }

    /**
     * Watches for the one failure this whole mechanism exists for: the platform says media is
     * playing, yet the session we listen to is flat. That is what a session-0 effect looks like
     * when it landed on the idle primary output.
     */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!listeners.isEmpty()) {
                long now = System.currentTimeMillis();
                long wait = lastResolveFoundNothing ? RESOLVE_RETRY_MS : RESOLVE_COOLDOWN_MS;
                if (now - lastSignalTime > SILENCE_TOLERANCE_MS
                        && now - lastResolveTime > wait
                        && isMediaPlaybackActive()) {
                    requestResolve("attached session silent while media plays");
                }
                if (watchdogHandler != null) {
                    watchdogHandler.postDelayed(this, WATCHDOG_PERIOD_MS);
                }
            }
        }
    };

    /**
     * Resolution is exclusive: our own capture is released first, so the resolver can probe any
     * session - including the one we were just on - without two Visualizer handles fighting over
     * the same effect. Costs a sub-second gap in the visuals, and only happens on start or on a
     * genuine loss of signal.
     */
    private void requestResolve(String reason) {
        if (sessionResolver == null || sessionResolver.isResolving()) return;
        lastResolveTime = System.currentTimeMillis();
        Log.i(TAG, "Resolving audio session: " + reason);

        final int previousSession;
        synchronized (this) {
            previousSession = currentSessionId;
            releaseCapture();
        }

        boolean started = sessionResolver.resolveAsync(getActivePlayerPackage(), sessionId -> {
            synchronized (AudioSpectrumEngine.this) {
                int target = sessionId >= 0 ? sessionId : previousSession;
                lastResolveFoundNothing = sessionId < 0;
                if (sessionId < 0) {
                    Log.w(TAG, "No session carrying audio found; staying on " + previousSession);
                } else if (sessionId != previousSession) {
                    Log.i(TAG, "Switching capture session: " + previousSession + " -> " + sessionId);
                }
                lastSignalTime = System.currentTimeMillis();
                currentSessionId = target;
                if (!listeners.isEmpty() && visualizer == null) {
                    startInternal(target);
                }
            }
        });

        // The capture is already released at this point. If the resolver refused the request -
        // one was somehow still running - nobody would ever put it back, and the visualizer would
        // stay dead until the app restarted. That is what "it never came back after Bluetooth"
        // looked like from the outside.
        if (!started) {
            synchronized (this) {
                if (!listeners.isEmpty() && visualizer == null) {
                    Log.w(TAG, "Resolver busy; restoring capture on session " + previousSession);
                    currentSessionId = previousSession;
                    startInternal(previousSession);
                }
            }
        }
    }

    /**
     * Starts the two native-path threads: one polling the tap, one driving the display.
     *
     * They are deliberately separate. Measurements arrive whenever the audio buffer has moved on,
     * which is not a rate anyone should be drawing at; the display wants a steady 60 and does not
     * care how many measurements happened in between.
     */
    private void startNativeCapture(int captureSize, int samplingRateMilliHz) {
        stopNativeCapture();

        int sampleRate = samplingRateMilliHz > 0 ? samplingRateMilliHz / 1000 : 48000;
        nativeAnalyzer = new NativeAnalyzer(sampleRate, captureSize);
        if (!nativeAnalyzer.isValid()) {
            Log.w(TAG, "Native analyser did not initialise; nothing will be measured");
            return;
        }
        applyNativeSettings();

        capturePolling = true;
        final int size = captureSize;

        pollThread = new Thread(() -> {
            byte[] buffer = new byte[size];
            while (capturePolling) {
                Visualizer v = visualizer;
                if (v == null) break;
                try {
                    if (v.getWaveForm(buffer) == Visualizer.SUCCESS) {
                        noteSignal(buffer);
                        nativeAnalyzer.push(buffer, size);
                    }
                } catch (Throwable t) {
                    break;
                }
                try {
                    Thread.sleep(POLL_PERIOD_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "wDSP_Capture");
        pollThread.setPriority(Thread.MAX_PRIORITY - 1);
        pollThread.start();

        displayThread = new Thread(() -> {
            while (capturePolling) {
                try {
                    dispatchNativeFrame();
                    Thread.sleep(DISPLAY_PERIOD_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable ignored) {
                }
            }
        }, "wDSP_Display");
        displayThread.start();
    }

    private void stopNativeCapture() {
        capturePolling = false;
        Thread poll = pollThread;
        Thread display = displayThread;
        pollThread = null;
        displayThread = null;
        // Join before releasing: the native object must not vanish while a thread is inside it.
        // The wait is bounded by one poll period, so this costs milliseconds.
        try {
            if (poll != null) {
                poll.interrupt();
                poll.join(200);
            }
            if (display != null) {
                display.interrupt();
                display.join(200);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (nativeAnalyzer != null) {
            nativeAnalyzer.release();
            nativeAnalyzer = null;
        }
    }

    /** Pushes the current settings - ballistics, latency and both gain profiles - into native. */
    public void applyNativeSettings() {
        NativeAnalyzer analyzer = nativeAnalyzer;
        if (analyzer == null || !analyzer.isValid()) return;
        analyzer.setConfig(nativeAttackMs, nativeReleaseMs, nativeLatencyMs,
                nativeRefMaxDb, nativeRangeDb);
        analyzer.setAgc(NativeAnalyzer.CONSUMER_MAIN, mainAgcEnabled, mainAgcStrength, mainAgcFloorDb);
        analyzer.setAgc(NativeAnalyzer.CONSUMER_STATUS_BAR, barAgcEnabled, barAgcStrength, barAgcFloorDb);
        analyzer.setDspCurve(getDspCurve(dspCurveSampleRate > 0 ? dspCurveSampleRate : 48000f));
    }

    private void dispatchNativeFrame() {
        NativeAnalyzer analyzer = nativeAnalyzer;
        if (analyzer == null || !analyzer.isValid() || listeners.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (lastCaptureTime != 0) {
            long observed = now - lastCaptureTime;
            if (observed > 0) captureIntervalMs = observed;
        }
        lastCaptureTime = now;

        for (int consumer = 0; consumer <= 1; consumer++) {
            ConsumerFrames f = frames[consumer];
            // Absolute levels for the "no normalisation" view, and this consumer's gain profile
            // for the other. Two calls because the gain state is per consumer by design.
            analyzer.setAgc(consumer, false, 0f, 0f);
            analyzer.getLevels(consumer, f.level32, f.level16);
            boolean enabled = consumer == NativeAnalyzer.CONSUMER_MAIN ? mainAgcEnabled : barAgcEnabled;
            float strength = consumer == NativeAnalyzer.CONSUMER_MAIN ? mainAgcStrength : barAgcStrength;
            float floorDb = consumer == NativeAnalyzer.CONSUMER_MAIN ? mainAgcFloorDb : barAgcFloorDb;
            analyzer.setAgc(consumer, enabled, strength, floorDb);
            analyzer.getLevels(consumer, f.level32Agc, f.level16Agc);

            System.arraycopy(f.display16, 0, f.prev16, 0, NUM_BANDS_16);
            System.arraycopy(f.level16, 0, f.display16, 0, NUM_BANDS_16);
            System.arraycopy(f.display16Norm, 0, f.prev16Norm, 0, NUM_BANDS_16);
            System.arraycopy(f.level16Agc, 0, f.display16Norm, 0, NUM_BANDS_16);
            System.arraycopy(f.display32, 0, f.prev32, 0, NUM_BANDS_32);
            System.arraycopy(f.level32, 0, f.display32, 0, NUM_BANDS_32);
            System.arraycopy(f.display32Norm, 0, f.prev32Norm, 0, NUM_BANDS_32);
            System.arraycopy(f.level32Agc, 0, f.display32Norm, 0, NUM_BANDS_32);
        }

        if (debugDump) dumpNativeBands(analyzer);

        for (OnSpectrumDataListener l : listeners) {
            Integer c = consumerOf.get(l);
            ConsumerFrames f = frames[c != null && c == NativeAnalyzer.CONSUMER_STATUS_BAR ? 1 : 0];
            try {
                l.onSpectrumCapture(f.display16, f.display16Norm, f.prev16, f.prev16Norm,
                        f.display32, f.display32Norm, f.prev32, f.prev32Norm,
                        lastCaptureTime, captureIntervalMs);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Drops the Visualizer without touching listeners or the watchdog. */
    private synchronized void releaseCapture() {
        stopNativeCapture();
        if (visualizer == null) return;
        try {
            visualizer.release();
        } catch (Throwable ignored) {
        }
        visualizer = null;
    }

    /** Active player package, so the resolver can cache the session against it. */
    private String getActivePlayerPackage() {
        try {
            @SuppressLint("PrivateApi") Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            return (String) get.invoke(null, "sys.qf.last_audio_src", "");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * True when something is playing - asked of the platform QF way first, then the Android way.
     *
     * Android's own answer is not sufficient here. Bluetooth audio on this head unit is produced by
     * gocsdk_zj, a native daemon that opens its AudioTrack through libmedia and never creates a
     * Java PlayerBase, so getActivePlaybackConfigurations() comes back empty while music is very
     * much playing. Relying on it meant the watchdog never even looked, and the visualizer sat at
     * zero for the whole of Bluetooth playback.
     */
    private boolean isMediaPlaybackActive() {
        String source = getActivePlayerPackage();
        if (source != null && !source.isEmpty()
                && !"nothing".equalsIgnoreCase(source) && !"Unknown".equalsIgnoreCase(source)) {
            return true;
        }
        String type = VolumeHelper.getActivePlayerType();
        if (type != null && !"radio_type".equals(type)) {
            // Anything the hardware volume manager considers an active non-radio source counts:
            // media, Bluetooth music, AUX. Radio is excluded because it bypasses AudioFlinger.
            return true;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || audioManager == null) return false;
        try {
            List<AudioPlaybackConfiguration> configs = audioManager.getActivePlaybackConfigurations();
            if (configs == null) return false;
            for (AudioPlaybackConfiguration config : configs) {
                if (!isConfigActive(config)) continue;
                AudioAttributes attr = config.getAudioAttributes();
                if (attr != null && (attr.getUsage() == AudioAttributes.USAGE_MEDIA
                        || attr.getUsage() == AudioAttributes.USAGE_GAME
                        || attr.getUsage() == AudioAttributes.USAGE_UNKNOWN)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isConfigActive(AudioPlaybackConfiguration config) {
        if (config == null) return false;
        try {
            java.lang.reflect.Method m = config.getClass().getMethod("isActive");
            Object res = m.invoke(config);
            if (res instanceof Boolean) return (Boolean) res;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = config.getClass().getMethod("getPlayerState");
            Object res = m.invoke(config);
            if (res instanceof Integer) return ((Integer) res) == 2; // 2 = PLAYER_STATE_STARTED
        } catch (Throwable ignored) {}
        return true;
    }

    private synchronized void restartWithSession(int sessionId) {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Throwable ignored) {}
            visualizer = null;
        }
        currentSessionId = sessionId;
        if (!listeners.isEmpty()) {
            startInternal(sessionId);
        }
    }

    /**
     * Attaches to the last session known to work (session 0 on a fresh start, which is correct on
     * head units with custom audio policies) and immediately asks the resolver whether this really
     * is where the audio is. The watchdog keeps asking later if it turns out to be silent.
     */
    public synchronized void start() {
        if (visualizer != null) return;
        // A resolution is in flight and has deliberately released the capture; its callback will
        // attach. Racing it here produces "setCaptureSize() called in wrong state".
        if (sessionResolver != null && sessionResolver.isResolving()) return;
        startInternal(currentSessionId);
        lastSignalTime = System.currentTimeMillis();
        if (watchdogHandler == null) {
            watchdogHandler = new Handler(Looper.getMainLooper());
        }
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, WATCHDOG_PERIOD_MS);
        requestResolve("capture started");
    }

    private void startInternal(int sessionId) {
        try {
            Visualizer v = new Visualizer(sessionId);

            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = range[1];
            if (captureSize < range[0]) captureSize = range[0];
            // Capture size is only settable while the effect is disabled. It can already be
            // enabled if something else - a probe, or another DSP app - holds this session.
            if (v.getEnabled()) {
                v.setEnabled(false);
            }
            v.setCaptureSize(captureSize);

            int rate = Visualizer.getMaxCaptureRate();
            if (rate <= 0) rate = 20000;

            if (NativeAnalyzer.isAvailable()) {
                // No capture listener at all: the 20 Hz callback is exactly what we are getting
                // away from. The polling thread below reads the same buffer far more often.
                v.setEnabled(true);
                visualizer = v;
                startNativeCapture(captureSize, v.getSamplingRate());
            } else {
                v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override
                    public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                        noteSignal(waveform);
                        processWaveform(waveform, samplingRate);
                    }

                    @Override
                    public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                        processFft(fft, samplingRate);
                    }
                }, rate, true, true);

                v.setEnabled(true);
                visualizer = v;
            }
            Log.d(TAG, "AudioSpectrumEngine attached to session " + sessionId
                    + ", captureSize=" + captureSize
                    + (NativeAnalyzer.isAvailable() ? ", native polled capture" : ", java callbacks"));
        } catch (Throwable t) {
            Log.w(TAG, "AudioSpectrumEngine session " + sessionId + " failed: " + t);
            if (sessionId != 0) {
                try {
                    startInternal(0);
                    currentSessionId = 0;
                } catch (Throwable ignored) {
                    visualizer = null;
                }
            } else {
                visualizer = null;
            }
        }
    }

    public synchronized void stop() {
        if (watchdogHandler != null) {
            watchdogHandler.removeCallbacks(watchdog);
        }
        stopNativeCapture();
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (Throwable t) {
            Log.w(TAG, "Error releasing AudioSpectrumEngine: " + t);
        } finally {
            visualizer = null;
        }
        lowRingFill = 0;
        lowBandsReady = false;
        lastLowFftTime = 0;
    }

    private void processFft(byte[] fft, int samplingRateMilliHz) {
        int n = fft.length;
        if (n < 4) return;
        int numBins = n / 2;
        float sampleRateHz = samplingRateMilliHz / 1000f;

        // Band level is the ENERGY in the band - the sum of bin powers - not the average
        // magnitude per bin. The bands are 2/3 of an octave wide, so at a 46.9 Hz bin spacing the
        // 50 Hz band holds about one bin while the 20 kHz band holds about 174. Averaging over
        // those 174 returned the noise floor as a steady number, drowning a cymbal (a few loud
        // bins among the 174) by some 45 dB while lighting the top of the display permanently.
        // Summing power is also what makes pink noise read flat by itself, with no correction
        // table: equal energy per octave in, equal reading out.
        double[] bandPower = new double[AudioConfig.NUM_BANDS];
        int[] bandBins = new int[AudioConfig.NUM_BANDS];

        for (int bin = 0; bin <= numBins; bin++) {
            float re, im;
            if (bin == 0) {
                re = fft[0]; im = 0;
            } else if (bin == numBins) {
                re = fft[1]; im = 0;
            } else {
                re = fft[2 * bin]; im = fft[2 * bin + 1];
            }

            float freqHz = bin * sampleRateHz / n;
            int band = bandForFrequency(freqHz);
            if (band >= 0) {
                bandPower[band] += (double) re * re + (double) im * im;
                bandBins[band]++;
            }
        }

        // The bottom two bands are narrower than one bin of this FFT, so they come from the
        // separate 4096-point transform fed by the waveform.
        // Bands 0 (15.9-25.1 Hz) and 1 (25.1-39.7 Hz) are deliberately left empty here, so the
        // density fallback below fills them from the 50 Hz band.
        //
        // They cannot be measured with what Visualizer gives us. Capture size tops out at 1024,
        // which at 48 kHz is a 46.9 Hz bin - both bands are narrower than a single bin. The
        // previous attempt at a workaround stitched 1024-sample blocks into a 4096-point buffer
        // and transformed that, but the blocks are not contiguous: 1024 samples arrive 20 times a
        // second while the stream runs at 48000, so 29 ms out of every 50 is missing and the
        // assembled buffer runs 2.3x fast. Its output below 47 Hz was an artefact of the
        // stitching, which is why 31.5 Hz jumped between 17 and 0 on stationary pink noise.
        //
        // Measuring these two for real needs a continuous PCM stream - polled capture with
        // overlap alignment, or MediaProjection - not a larger transform.

        // Turn the accumulated bin powers into band energy the same way for every band:
        // mean power per bin, times the number of bins the band SHOULD hold at this resolution.
        //
        // A plain sum is only right where a band is wide enough to be sampled properly. At a
        // 46.9 Hz bin spacing the 50, 80 and 125 Hz bands land on a single bin each, and the two
        // bottom bands are narrower than one bin - summing there reports whatever that one bin
        // happened to catch. Scaling the density by the band's own width removes that bias and
        // makes wide and narrow bands directly comparable.
        double binWidthHz = sampleRateHz / n;
        double[] bandDensity = new double[AudioConfig.NUM_BANDS];
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            if (bandBins[i] > 0) bandDensity[i] = bandPower[i] / bandBins[i];
        }
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            double density = bandDensity[i];
            if (bandBins[i] == 0) {
                // No bin of its own: borrow the nearest measured density rather than invent a level.
                for (int distance = 1; distance < AudioConfig.NUM_BANDS && density == 0; distance++) {
                    int lo = i - distance, hi = i + distance;
                    if (lo >= 0 && bandBins[lo] > 0) density = bandDensity[lo];
                    else if (hi < AudioConfig.NUM_BANDS && bandBins[hi] > 0) density = bandDensity[hi];
                }
            }
            double expectedBins = (BAND_EDGES_HZ[i + 1] - BAND_EDGES_HZ[i]) / binWidthHz;
            bandPower[i] = density * expectedBins;
        }

        // 1. What the hardware DSP will do to this content - real biquad responses, see DspResponse.
        float[] postDspGainDb = getDspCurve(sampleRateHz);

        // 2. Content level per band, above that band's own noise floor
        float[] postSignalDb = new float[AudioConfig.NUM_BANDS];
        float maxPostDb = 0f;

        // The noise floor may only be learned from frames where essentially nothing is playing.
        // Learning it continuously eats stationary signals: pink noise never varies, so a floor
        // that chases the running minimum settles onto the signal itself - and it does so faster
        // in wide high bands, whose many bins average out the variation, than in narrow low ones.
        // That alone produces a convincing but entirely artificial high-frequency roll-off, which
        // is exactly the sort of thing a calibration disc would then bake into a correction table.
        double frameTotal = 0;
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) frameTotal += bandPower[i];
        if (frameTotal > frameMaxPower) {
            frameMaxPower = frameTotal;
        } else {
            frameMaxPower *= FRAME_MAX_DECAY;
        }
        boolean quietFrame = frameMaxPower > 0 && frameTotal < frameMaxPower * QUIET_FRACTION;

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            double power = bandPower[i];

            if (quietFrame) {
                if (noiseFloorPower[i] <= 0) {
                    noiseFloorPower[i] = power;
                } else if (power < noiseFloorPower[i]) {
                    noiseFloorPower[i] = power;
                } else {
                    noiseFloorPower[i] += (power - noiseFloorPower[i]) * NOISE_FLOOR_RISE;
                }
            }
            double signalPower = power - noiseFloorPower[i] * NOISE_FLOOR_MARGIN;
            if (signalPower < 0) signalPower = 0;

            float rawSignalDb = (float) (10.0 * Math.log10(signalPower + POWER_EPSILON)
                    - POWER_REFERENCE_DB);
            if (rawSignalDb < 0f) rawSignalDb = 0f;

            // Presence gate: only a band with real content above its floor earns the DSP curve.
            float presence = rawSignalDb / SILENCE_FADE_DB;
            presence = Math.max(0f, Math.min(1f, presence));

            // Residual measurement tilt. Zero by design now that band energy is summed rather
            // than averaged - kept so the curve can be re-measured with a pink noise disc.
            float calibratedDb = rawSignalDb + presence * PINK_NOISE_CALIBRATION_DB[i];

            // Fast-attack, smooth-release ballistics applied to content
            float prevContentDb = smoothedContentDb[i];
            float contentSmoothing = calibratedDb > prevContentDb ? RISE_SMOOTHING : FALL_SMOOTHING;
            smoothedContentDb[i] = prevContentDb + (calibratedDb - prevContentDb) * contentSmoothing;

            float finalDb = smoothedContentDb[i] + presence * postDspGainDb[i] - ATTENUATION_DB;
            postSignalDb[i] = Math.max(0f, finalDb);
            if (finalDb > maxPostDb) {
                maxPostDb = finalDb;
            }
        }

        if (debugDump) {
            dumpBands(postSignalDb, postDspGainDb);
        }

        // 3. Compute Unnormalized Mapping [0..54 dB] with soft ceiling headroom (16-band)
        for (int i = 0; i < NUM_BANDS_16; i++) {
            float normalized = (postSignalDb[i] - REF_MIN_DB) / (REF_MAX_DB - REF_MIN_DB);
            rawLevels16[i] = applySoftHeadroom(normalized);
        }

        // 4. Compute Dynamic AGC Normalization Mapping (16-band)
        if (maxPostDb > runningPeakDb) {
            // Fast attack on musical transients/beats
            runningPeakDb += (maxPostDb - runningPeakDb) * 0.40f;
        } else {
            // Smooth musical release (~2.5 sec)
            runningPeakDb += (maxPostDb - runningPeakDb) * 0.008f;
        }
        float effectivePeak = Math.max(10f, runningPeakDb);
        for (int i = 0; i < NUM_BANDS_16; i++) {
            float normAgc = postSignalDb[i] / effectivePeak;
            rawLevels16Norm[i] = applySoftHeadroom(normAgc);
        }

        // 5. Synthesize continuous, ultra-smooth 32-band spectrum for RTA/Status bar
        for (int k = 0; k < NUM_BANDS_32; k++) {
            float pos = k * (15f / 31f);
            int idx = (int) pos;
            float frac = pos - idx;
            if (idx < 15) {
                rawLevels32[k] = rawLevels16[idx] * (1.0f - frac) + rawLevels16[idx + 1] * frac;
                rawLevels32Norm[k] = rawLevels16Norm[idx] * (1.0f - frac) + rawLevels16Norm[idx + 1] * frac;
            } else {
                rawLevels32[k] = rawLevels16[15];
                rawLevels32Norm[k] = rawLevels16Norm[15];
            }
        }

        long now = System.currentTimeMillis();
        if (lastCaptureTime != 0) {
            long observed = now - lastCaptureTime;
            if (observed > 0) captureIntervalMs = observed;
        }
        System.arraycopy(displayLevels16, 0, prevLevels16, 0, NUM_BANDS_16);
        System.arraycopy(rawLevels16, 0, displayLevels16, 0, NUM_BANDS_16);

        System.arraycopy(displayLevels16Norm, 0, prevLevels16Norm, 0, NUM_BANDS_16);
        System.arraycopy(rawLevels16Norm, 0, displayLevels16Norm, 0, NUM_BANDS_16);

        System.arraycopy(displayLevels32, 0, prevLevels32, 0, NUM_BANDS_32);
        System.arraycopy(rawLevels32, 0, displayLevels32, 0, NUM_BANDS_32);

        System.arraycopy(displayLevels32Norm, 0, prevLevels32Norm, 0, NUM_BANDS_32);
        System.arraycopy(rawLevels32Norm, 0, displayLevels32Norm, 0, NUM_BANDS_32);

        lastCaptureTime = now;

        for (OnSpectrumDataListener l : listeners) {
            try {
                l.onSpectrumCapture(displayLevels16, displayLevels16Norm, prevLevels16, prevLevels16Norm,
                                   displayLevels32, displayLevels32Norm, prevLevels32, prevLevels32Norm,
                                   lastCaptureTime, captureIntervalMs);
            } catch (Throwable ignored) {}
        }
    }

    private void processWaveform(byte[] waveform, int samplingRateMilliHz) {
        int len = waveform.length;
        if (len == 0) return;

        if (len >= LOW_FFT_SIZE) {
            for (int i = 0; i < LOW_FFT_SIZE; i++) {
                int b = waveform[len - LOW_FFT_SIZE + i] & 0xFF;
                lowRingBuffer[i] = (b - 128) / 128f;
            }
            lowRingFill = LOW_FFT_SIZE;
        } else {
            System.arraycopy(lowRingBuffer, len, lowRingBuffer, 0, LOW_FFT_SIZE - len);
            for (int i = 0; i < len; i++) {
                int b = waveform[i] & 0xFF;
                lowRingBuffer[LOW_FFT_SIZE - len + i] = (b - 128) / 128f;
            }
            lowRingFill = Math.min(LOW_FFT_SIZE, lowRingFill + len);
        }

        if (lowRingFill < LOW_FFT_SIZE) return;

        // computeLowBandMagnitudes() is intentionally not called: see the note in processFft()
        // about why a transform over stitched, non-contiguous blocks cannot measure 20-40 Hz.
        // The ring buffer and the transform stay in place for the continuous-capture rework.
    }

    private void computeLowBandMagnitudes(float sampleRateHz) {
        for (int i = 0; i < LOW_FFT_SIZE; i++) {
            float w = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / (LOW_FFT_SIZE - 1));
            fftScratchRe[i] = lowRingBuffer[i] * w;
            fftScratchIm[i] = 0f;
        }
        fft(fftScratchRe, fftScratchIm);

        // Sum POWER, in the same units the main path uses, so the two bottom bands sit on the
        // same scale as the other fourteen. Averaging magnitude here while the main path summed
        // power was showing up as a hole at the very bottom of the display.
        double band0Power = 0; int band0Bins = 0;
        double band1Power = 0; int band1Bins = 0;
        int half = LOW_FFT_SIZE / 2;
        for (int bin = 1; bin < half; bin++) {
            float freqHz = bin * sampleRateHz / LOW_FFT_SIZE;
            if (freqHz >= BAND_EDGES_HZ[2]) break;
            float magnitude = (float) Math.sqrt(fftScratchRe[bin] * fftScratchRe[bin]
                    + fftScratchIm[bin] * fftScratchIm[bin]);
            // Into the platform FFT's magnitude domain, undoing the Hann window's coherent gain
            // (the platform's own transform is not windowed).
            double mag = magnitude * LOW_BAND_MAGNITUDE_SCALE / HANN_COHERENT_GAIN;
            double power = mag * mag;
            if (freqHz >= BAND_EDGES_HZ[0] && freqHz < BAND_EDGES_HZ[1]) {
                band0Power += power; band0Bins++;
            } else if (freqHz >= BAND_EDGES_HZ[1]) {
                band1Power += power; band1Bins++;
            }
        }

        lowBandPower[0] = band0Power;
        lowBandPower[1] = band1Power;
        lowBandBins[0] = band0Bins;
        lowBandBins[1] = band1Bins;
        lowBandsReady = true;
    }

    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angStep = -2 * Math.PI / len;
            float wr = (float) Math.cos(angStep);
            float wi = (float) Math.sin(angStep);
            for (int start = 0; start < n; start += len) {
                float curWr = 1f, curWi = 0f;
                int half = len / 2;
                for (int k = 0; k < half; k++) {
                    int a = start + k;
                    int b = start + k + half;
                    float tr = re[b] * curWr - im[b] * curWi;
                    float ti = re[b] * curWi + im[b] * curWr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    float nextWr = curWr * wr - curWi * wi;
                    float nextWi = curWr * wi + curWi * wr;
                    curWr = nextWr; curWi = nextWi;
                }
            }
        }
    }

    private int bandForFrequency(float freqHz) {
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            if (freqHz >= BAND_EDGES_HZ[i] && freqHz < BAND_EDGES_HZ[i + 1]) return i;
        }
        return -1;
    }

    private static float applySoftHeadroom(float normalized) {
        if (normalized <= 0.80f) {
            return Math.max(0f, normalized);
        }
        // Graceful analog-style saturation knee: smoothly compresses extreme peaks between 80% and 95%
        float excess = normalized - 0.80f;
        float compressed = 0.80f + (float) Math.tanh(excess * 1.5f) * 0.15f;
        return Math.min(0.95f, compressed);
    }
}
