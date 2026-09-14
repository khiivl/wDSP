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
    /**
     * The running native analyser holds an older curve than {@link #getEffectiveSpectrumCurve()}
     * would give now. Consumed by {@link #dispatchNativeFrame()}, the one place that hands the curve
     * over - on the display thread, which is joined before the analyser is released.
     *
     * <p>🔴 Until 14.09.2026 the curve reached the analyser only when a capture started or settings
     * were reloaded, while the service republished the DSP state on every preset change. Measured on
     * pink noise: the hardware had been flat for a minute (EQ 80 66 66 ...) and the spectrum still
     * drew the previous AutoEQ preset's curve - +20 dB of bass that was never played.
     */
    private volatile boolean nativeCurveStale = true;

    /** Every change that alters the effective curve comes through here. */
    private void markDspCurveChanged() {
        dspCurveDirty = true;
        nativeCurveStale = true;
    }
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

    // Live raw PCM waveform snapshot for real-time oscilloscope visualization
    private final byte[] latestWaveform = new byte[1024];
    private volatile int latestWaveformLen = 0;
    private final Object waveformLock = new Object();

    /**
     * Copies the latest raw PCM waveform snapshot (unsigned 8-bit, 128 = zero baseline).
     *
     * @param outBuffer Destination array to copy into
     * @return Number of samples copied, or 0 if no waveform available
     */
    public int getLatestWaveform(byte[] outBuffer) {
        if (outBuffer == null || latestWaveformLen <= 0) return 0;
        synchronized (waveformLock) {
            int len = Math.min(outBuffer.length, latestWaveformLen);
            System.arraycopy(latestWaveform, 0, outBuffer, 0, len);
            return len;
        }
    }

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
    private Thread analysisThread;
    private Thread displayThread;

    /** Frames a second while the main analyser is on screen. */
    private static final int HOP_ACTIVE = 512;
    /**
     * Halved rate when only the status bar widget is listening. The main screen is opened rarely;
     * heating the processor with a second visualizer that nobody is looking at buys nothing.
     */
    private static final int HOP_IDLE = 1024;
    private volatile boolean capturePolling = false;

    /**
     * Poll period while the main analyser is on screen. Must stay well under one block - 1024
     * samples is 21 ms at 48 kHz - or consecutive reads stop overlapping and the stitcher has
     * nothing to align against.
     */
    private static final long POLL_PERIOD_MS = 9;
    /**
     * Poll period when only the status bar widget is listening. Still leaves 9 ms of overlap,
     * comfortably more than the 256-sample window the alignment search compares, while making a
     * third fewer crossings into native.
     */
    private static final long POLL_PERIOD_IDLE_MS = 12;
    /** Refresh while the main analyser is on screen: it is an instrument and deserves 60. */
    private static final long DISPLAY_PERIOD_MS = 16;
    /**
     * Refresh when only the status bar widget is listening. Measured on the K706, redrawing that
     * overlay at 60 costs as much processor as the entire measurement chain, and it is decoration.
     */
    private static final long DISPLAY_PERIOD_IDLE_MS = 33;

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
    /** Written by {@link LatencyProbe}; absent until a measurement has actually been run. */
    public static final String PREF_LATENCY_BASE = "spec_latency_base_ms";
    public static final String PREF_RANGE_DB = "spec_range_db";
    public static final String PREF_RADIO_MIC_VISUALIZER = "pref_radio_mic_visualizer";
    public static final String PREF_SPECTRUM_MODE = "pref_spectrum_mode";
    public static final String SPECTRUM_MODE_CALC = "calc";
    public static final String SPECTRUM_MODE_MIC = "mic";

    private String spectrumMode = SPECTRUM_MODE_CALC;

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

    private boolean radioMicVisualizerEnabled = true;
    private final RadioMicCapture radioMicCapture = new RadioMicCapture();
    private volatile long lastMicSignalTime = 0;

    /** User trim on top of the measured playback latency, +/- 250 ms. */
    private int latencyTrimMs = 0;

    /**
     * Everything below the DAC: the outboard sound processor, the amplifier and the air.
     *
     * Measured indirectly - the microphone hears a burst about 38 ms after the HAL says it was
     * presented, and that figure still carries the input path, so the true tail is smaller. Used
     * only when a measurement was taken without the microphone.
     */
    private static final float OUTBOARD_LATENCY_MS = 38f;

    /**
     * Turns a measurement into the base the analyser will use from now on, and returns it.
     *
     * The acoustic figure wins when there is one: it is the distance from the moment a sample is
     * seen to the moment it reaches the cabin, which is exactly what the bars have to wait for.
     * Without a microphone only the journey through Android is known and the rest is allowed for.
     * Returns -1 when the measurement produced nothing worth keeping.
     */
    public static int storeMeasuredLatency(Context context, LatencyProbe.Result result) {
        if (context == null || result == null || !result.ok) return -1;
        float base = !Float.isNaN(result.acousticMedianMs)
                ? result.acousticMedianMs
                : result.medianMs + OUTBOARD_LATENCY_MS;
        int ms = Math.round(base);
        com.radiorubka.wdsp.ui.theme.ThemeManager.prefs(context.getApplicationContext())
                .edit()
                .putInt(PREF_LATENCY_BASE, ms)
                .apply();
        getInstance().loadDisplaySettings(context.getApplicationContext());
        Log.i(TAG, "latency base stored: " + ms + " ms (" + result + ")");
        return ms;
    }

    /** The stored base, or -1 when this head unit has never been measured. */
    public static int storedLatencyBaseMs(Context context) {
        if (context == null) return -1;
        return com.radiorubka.wdsp.ui.theme.ThemeManager.prefs(context.getApplicationContext())
                .getInt(PREF_LATENCY_BASE, -1);
    }

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
        spectrumMode = prefs.getString(PREF_SPECTRUM_MODE, SPECTRUM_MODE_CALC);
        boolean oldRadioMic = radioMicVisualizerEnabled;
        radioMicVisualizerEnabled = prefs.getBoolean(PREF_RADIO_MIC_VISUALIZER, true);
        int storedBase = prefs.getInt(PREF_LATENCY_BASE, -1);
        float base = storedBase >= 0 ? storedBase : declaredLatencyMs();
        nativeLatencyMs = Math.max(0f, base + latencyTrimMs);
        applyNativeSettings();
        if (oldRadioMic != radioMicVisualizerEnabled) {
            checkSourceState();
        }
    }

    public String getSpectrumMode() {
        return spectrumMode;
    }

    public synchronized void setSpectrumMode(String mode) {
        this.spectrumMode = mode;
        if (appContext != null) {
            com.radiorubka.wdsp.ui.theme.ThemeManager.prefs(appContext).edit()
                    .putString(PREF_SPECTRUM_MODE, mode).apply();
        }
        checkSourceState();
    }

    public boolean isRadioMicVisualizerEnabled() {
        return radioMicVisualizerEnabled;
    }

    /**
      * Fallback for a head unit that has never been measured.
      *
      * This is the platform's declaration, not an observation, and on the unit this was written
      * for it is wrong by a factor of seven: {@code getOutputLatency()} says 125 ms where the
      * measured distance from capture to the listener's ear is about 53 ms. The declared figure
      * counts buffering that has already elapsed by the time an effect sees the samples.
      *
      * So it is kept only until {@link LatencyProbe} has run once and written
      * {@link #PREF_LATENCY_BASE}; after that the measurement wins.
      */
     private float declaredLatencyMs() {
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
        final Handler main = new Handler(Looper.getMainLooper());
        radioMicCapture.setUnavailableListener(new RadioMicCapture.UnavailableListener() {
            @Override
            public void onMicrophoneUnavailable() {
                main.post(AudioSpectrumEngine.this::onMicrophoneUnavailable);
            }

            @Override
            public void onMicrophoneFullBand() {
                main.post(AudioSpectrumEngine.this::onMicrophoneFullBand);
            }
        });
    }

    /**
     * The microphone cannot be had at full band right now: another app holds the input at 16 kHz and
     * root did not take it back, or there is no root. Until {@link MicInputWindow} sees a gap on the
     * input, and the capture opened in it hears full band.
     *
     * <p>Owner, 14.09.2026: "точно переходити на розрахунковий, і пофіг на радіо" - the spectrum
     * goes to the calculated mode even where that mode has nothing to show (radio bypasses
     * AudioFlinger), because a narrow microphone passed off as the cabin is worse than no picture.
     * The stored choice is left alone: once the microphone is back the person should not have to
     * choose it a second time.
     */
    private volatile boolean micUnavailable;
    /**
     * One toast per episode. Failed attempts inside an episode say nothing more; a microphone that
     * came back at full band and was lost again is a new episode, and is told.
     */
    private volatile boolean toldMicUnavailable;
    private final MicInputWindow micInputWindow = new MicInputWindow();

    public boolean isMicrophoneUnavailable() {
        return micUnavailable;
    }

    /** Main thread. */
    private synchronized void onMicrophoneUnavailable() {
        micUnavailable = true;
        if (appContext != null && !toldMicUnavailable) {
            toldMicUnavailable = true;
            // Daily use: the spectrum is decoration and an instrument, not a reason to restart the
            // car (owner, 14.09.2026). Calibration asks differently, and is not this path.
            Toaster.show(appContext, R.string.mic_busy_calculated);
        }
        Log.w(TAG, "microphone unavailable - spectrum falls back to calculated until the input is free");
        stopNativeCapture();
        stopRadioMicCapture();
        if (!listeners.isEmpty()) {
            startInternal(currentSessionId);
            requestResolve("microphone unavailable");
        }
        micInputWindow.watch(appContext, radioMicCapture::isRunning, radioMicCapture.lastSessionId(),
                this::onMicrophoneInputFree);
    }

    /**
     * Main thread. The input has no narrow recording left on it: open the capture now, while it is
     * ours to set the rate. Whether that worked is decided by the capture itself, half a second in,
     * by the device rate - full band ends the episode, narrow again starts the wait again.
     */
    private synchronized void onMicrophoneInputFree() {
        if (!micUnavailable) return;
        micUnavailable = false;
        if (!listeners.isEmpty() && !pausedForCall && wantsMicPipeline(isRadioSourceNow())) {
            Log.i(TAG, "input free - reopening the microphone pipeline");
            startInternal(currentSessionId);
        } else {
            // Nothing wants the microphone at this moment. Holding the input for nobody is not ours
            // to do; the next start opens it and is judged the same way.
            Log.i(TAG, "input free - nothing wants the microphone now; the next start will judge it");
        }
    }

    /** Main thread. */
    private synchronized void onMicrophoneFullBand() {
        if (toldMicUnavailable) Log.i(TAG, "microphone back at full band");
        toldMicUnavailable = false;
    }

    /** Whether the microphone spectrum is what is actually in force, not merely what was chosen. */
    private boolean micModeInEffect() {
        return SPECTRUM_MODE_MIC.equals(spectrumMode) && canRunMic();
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
        applyAnalysisProfile();
    }

    public synchronized void unregisterListener(OnSpectrumDataListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
        consumerOf.remove(listener);
        if (listeners.isEmpty() && visualizer != null) {
            stop();
        }
        applyAnalysisProfile();
    }

    public void setGains(int[] newGains) {
        if (newGains == null) return;
        synchronized (gains) {
            System.arraycopy(newGains, 0, this.gains, 0, Math.min(newGains.length, AudioConfig.NUM_BANDS));
        }
        markDspCurveChanged();
    }

    public void setQFactors(boolean[] newQNarrow) {
        if (newQNarrow == null) return;
        synchronized (qNarrow) {
            System.arraycopy(newQNarrow, 0, this.qNarrow, 0, Math.min(newQNarrow.length, AudioConfig.NUM_BANDS));
        }
        markDspCurveChanged();
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
            markDspCurveChanged();
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

    // --- Capture dump, driven by PROBE_SESSION --ei wav <ms> ---
    //
    // Answers "is the shape already in what the analyser is fed, or does the band maths make it?"
    // on a real unit, where the host harness cannot look: every raw Visualizer block for a few
    // seconds with its poll time and how many samples the stitcher took from it, then the stitched
    // stream the transforms actually read, as a WAV. Both land in the app's files directory.

    private volatile long captureDumpUntil = 0;
    private java.io.DataOutputStream captureDumpBlocks;
    private int captureDumpCount;

    /** Starts a capture dump of {@code ms} milliseconds, 500..2700 (the stitched ring holds 2.7 s). */
    public void dumpCapture(int ms) {
        if (appContext == null || nativeAnalyzer == null || visualizer == null) {
            Log.w(TAG, "capture dump: no Visualizer capture running (microphone mode, or nothing attached)");
            return;
        }
        ms = Math.max(500, Math.min(2700, ms));
        try {
            java.io.File file = new java.io.File(appContext.getFilesDir(), "capture_blocks.bin");
            captureDumpBlocks = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                    new java.io.FileOutputStream(file)));
            captureDumpCount = 0;
            Visualizer v = visualizer;
            Log.i(TAG, "capture dump: " + ms + " ms, session " + currentSessionId
                    + ", Visualizer rate " + (v != null ? v.getSamplingRate() : -1) + " mHz"
                    + ", scaling " + (v != null ? v.getScalingMode() : -1)
                    + ", measurement mode " + (v != null ? v.getMeasurementMode() : -1)
                    + ", capture size " + (v != null ? v.getCaptureSize() : -1));
            captureDumpUntil = System.currentTimeMillis() + ms;
        } catch (Throwable t) {
            Log.w(TAG, "capture dump could not start: " + t);
            captureDumpUntil = 0;
        }
    }

    /** Capture thread. One record per poll: nanoTime, samples the stitcher took, the raw block. */
    private void writeCaptureDump(byte[] block, int size, int fresh, int sampleRate) {
        java.io.DataOutputStream out = captureDumpBlocks;
        if (out == null) return;
        try {
            out.writeLong(System.nanoTime());
            out.writeInt(fresh);
            out.writeInt(size);
            out.write(block, 0, size);
            captureDumpCount++;
            if (System.currentTimeMillis() < captureDumpUntil) return;

            captureDumpUntil = 0;
            captureDumpBlocks = null;
            out.close();
            NativeAnalyzer analyzer = nativeAnalyzer;
            float[] stream = new float[1 << 17];
            int got = analyzer != null ? analyzer.readStream(stream) : 0;
            java.io.File wav = new java.io.File(appContext.getFilesDir(), "capture_stitched.wav");
            writeWav16(wav, stream, got, sampleRate);
            Log.i(TAG, "capture dump written: " + captureDumpCount + " blocks, " + got
                    + " stitched samples at " + sampleRate + " Hz, discontinuities "
                    + (analyzer != null ? analyzer.discontinuities() : -1));
        } catch (Throwable t) {
            Log.w(TAG, "capture dump failed: " + t);
            captureDumpUntil = 0;
            captureDumpBlocks = null;
        }
    }

    private static void writeWav16(java.io.File file, float[] samples, int count, int sampleRate)
            throws java.io.IOException {
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + count * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + count * 2).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(count * 2);
        for (int i = 0; i < count; i++) {
            float s = Math.max(-1f, Math.min(1f, samples[i]));
            b.putShort((short) Math.round(s * 32767f));
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(b.array());
        }
    }

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
        // The display level term by term: power - 1.2 x floor, in dB, plus the curve.
        float[] power = new float[NUM_BANDS_32], floor = new float[NUM_BANDS_32], curve = new float[NUM_BANDS_32];
        analyzer.getTermsDb(power, floor, curve);
        StringBuilder p = new StringBuilder("POWER32 "), f = new StringBuilder("FLOOR32 "), c = new StringBuilder("CURVE32 ");
        for (int i = 0; i < NUM_BANDS_32; i++) {
            p.append(String.format(java.util.Locale.US, "%.0f ", power[i]));
            f.append(String.format(java.util.Locale.US, "%.0f ", floor[i]));
            c.append(String.format(java.util.Locale.US, "%.1f ", curve[i]));
        }
        Log.i(TAG, p.toString());
        Log.i(TAG, f.toString());
        Log.i(TAG, c.toString());
        Log.i(TAG, "NATIVE frames=" + analyzer.frames()
                + " discontinuities=" + analyzer.discontinuities()
                + " latencyMs=" + nativeLatencyMs
                // The EFFECTIVE gain, not the preference: in microphone mode the main consumer is
                // normalised regardless of it, and a log that printed the preference had me reading
                // "agcMain=false" while the analyser was normalising.
                + " agcMain=" + (isRadioCaptureActive() || mainAgcEnabled)
                + (isRadioCaptureActive() ? " (forced: mic mode)" : "")
                + " agcBar=" + barAgcEnabled);
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

    /**
     * Single Source of Truth for spectrum analyzer curve (DSP EQ vs Mic Compensation).
     *
     * - In Microphone capture mode (isRadioCaptureActive()):
     *   Sound in the cabin has already passed through the hardware DSP (BU32107), power amp,
     *   and cabin speakers. The analyzer must NOT apply DSP EQ again.
     *   Instead, it applies the calibrated microphone inverse compensation curve from
     *   RoomMeasurement.getMicCompensationCurve(appContext) to restore hardware mic roll-off
     *   in the sub-bass (20–125 Hz) and upper treble.
     *
     * - In Calculated capture mode (!isRadioCaptureActive()):
     *   Audio is captured from pre-DSP AudioFlinger. The analyzer applies the simulated
     *   hardware DSP curve from getDspCurve().
     */
    public float[] getEffectiveSpectrumCurve() {
        if (isRadioCaptureActive() || micModeInEffect()) {
            return RoomMeasurement.getMicCompensationCurve(appContext);
        }
        // Calculated mode: the DSP's own response, and nothing about the car.
        //
        // 🔴 Owner, 14.09.2026: "розрахунковий спектроаналізатор це цільове, мікрофонний наявне" -
        // "в розрахунковому не місце для враховування всякиї специфік кабіни". The calculated
        // spectrum shows what the preset MEANS to do to the signal; what the car then does to it is
        // what the microphone spectrum is for. The two views are useful precisely because they
        // differ.
        //
        // From 13.09 to 14.09.2026 the stored cabin response (RoomMeasurement.PREF_CABIN_RESPONSE)
        // was added here, on the owner's word of 13.09 that the calculated spectrum would otherwise
        // "show untruth". Measured on pink noise with the flat preset, it bent a flat input by
        // +4.6 dB at 20 Hz, -7.7 at 3.15 kHz and -13 at 20 kHz - a curve taken on the bench, which
        // is not a cabin. It is still stored and still reported by the measurement; it no longer
        // reaches the spectrum.
        //
        // A fresh array: getDspCurve hands back its own cached buffer, and a caller changing it would
        // corrupt the cache for every later reader. Sixteen floats, built only when settings change.
        final float[] dsp = getDspCurve(dspCurveSampleRate > 0 ? dspCurveSampleRate : 48000f);
        return Arrays.copyOf(dsp, NUM_BANDS_16);
    }

    /**
     * Re-pushes whatever the measurements have taught us into the running analyser.
     *
     * <p>Called when a calibration or a cabin sweep finishes. It was named
     * {@code onMicCompensationUpdated} while the microphone's curve was the only measured thing the
     * spectrum used; the cabin response now feeds the calculated mode as well, and a name that
     * covered half of what it does is how a reader ends up believing the other half is not
     * refreshed. One caller, so the rename cost nothing.
     */
    public void onMeasuredCurvesChanged() {
        // Marked, not pushed from here: this runs on the measurement's thread, and the analyser may
        // be released under it. The display thread hands the new curve over on its next frame.
        markDspCurveChanged();
        checkSourceState();
    }

    public void setFmOffsets(float[] newFmOffsets) {
        synchronized (fmOffsets) {
            if (newFmOffsets == null) {
                Arrays.fill(this.fmOffsets, 0f);
            } else {
                System.arraycopy(newFmOffsets, 0, this.fmOffsets, 0, Math.min(newFmOffsets.length, AudioConfig.NUM_BANDS));
            }
        }
        markDspCurveChanged();
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

    /**
     * What the analyser is doing, in the words a report needs.
     *
     * <h2>Why the report has to carry this</h2>
     *
     * "The visualiser does not work" arrives from units we cannot borrow, and everything that could
     * cause it is invisible from the outside: whether an effect could be attached at all, which
     * session it landed on, and whether that session is carrying anything. Without those three the
     * only honest answer is a guess, and one such guess has already cost this project a fortnight.
     *
     * <p>Kept to facts. A session of 0 with audio playing means the effect went onto the primary
     * output while the music is elsewhere - the classic dead attach on factory policies. A session
     * that is not 0 but silent means the sweep picked the wrong one. Nothing attached at all means
     * the platform refused, which is what newer Android does with another application's session.
     */
    public String describeForReport() {
        StringBuilder sb = new StringBuilder();
        boolean attached = visualizer != null;
        sb.append("  effect attached  = ").append(attached).append('\n');
        sb.append("  radio mic active = ").append(radioMicCapture.isRunning()).append('\n');
        sb.append("  session          = ").append(currentSessionId);
        if (attached && currentSessionId == 0) sb.append("  (session 0 - the output mix)");
        sb.append('\n');
        long since = lastSignalTime == 0 ? -1 : System.currentTimeMillis() - lastSignalTime;
        sb.append("  signal seen      = ")
                .append(since < 0 ? "never since the effect was created"
                        : (since < SILENCE_TOLERANCE_MS ? "yes, " + since + " ms ago"
                        : "not for " + since + " ms"))
                .append('\n');
        sb.append("  native analyzer  = ").append(NativeAnalyzer.isAvailable()).append('\n');
        try {
            sb.append("  capture size     = ")
                    .append(Arrays.toString(Visualizer.getCaptureSizeRange()))
                    .append("  max rate ").append(Visualizer.getMaxCaptureRate()).append(" mHz\n");
        } catch (Throwable t) {
            sb.append("  capture limits   = could not be read: ").append(t).append('\n');
        }
        return sb.toString();
    }

    /**
     * Whether the spectrum engine has delivered signal just now, from either AudioFlinger
     * media playback or the cabin microphone for analogue radio.
     */
    public boolean hasSignalNow() {
        long now = System.currentTimeMillis();
        return (lastSignalTime != 0 && now - lastSignalTime < SILENCE_TOLERANCE_MS)
                || (lastMicSignalTime != 0 && now - lastMicSignalTime < SILENCE_TOLERANCE_MS);
    }

    /**
     * Whether the AudioFlinger media session has delivered signal just now (excluding mic capture).
     * Used by NowPlaying and McuService to distinguish software media from analogue tuner.
     */
    public boolean hasMediaSignalNow() {
        return lastSignalTime != 0
                && System.currentTimeMillis() - lastSignalTime < SILENCE_TOLERANCE_MS;
    }

    private void noteMicSignal(short[] buffer, int len) {
        if (buffer == null || len <= 0) return;
        for (int i = 0; i < len; i++) {
            if (Math.abs(buffer[i]) > 300) {
                lastMicSignalTime = System.currentTimeMillis();
                return;
            }
        }
    }

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
            // Tell the microphone capture whether the speakers are quiet, so its noise floor is
            // re-measured in the gaps between tracks rather than guessed from the audio. This is the
            // one place that can answer it: radio bypasses AudioFlinger, so an empty session list is
            // not silence, and isMediaPlaybackActive() already knows that.
            publishPlaybackSilence();
            if (!listeners.isEmpty()) {
                checkSourceState();
                long now = System.currentTimeMillis();
                long wait = lastResolveFoundNothing ? RESOLVE_RETRY_MS : RESOLVE_COOLDOWN_MS;
                boolean micActive = micModeInEffect() || isRadioCaptureActive();
                if (!micActive && now - lastSignalTime > SILENCE_TOLERANCE_MS
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
        if (appContext != null && (NowPlaying.getInstance(appContext).isRadioSource()
                || micModeInEffect() || isRadioCaptureActive())) {
            return;
        }
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
                } else {
                    if (sessionId != previousSession) {
                        Log.i(TAG, "Switching capture session: " + previousSession + " -> " + sessionId);
                    }
                    lastSignalTime = System.currentTimeMillis();
                }
                currentSessionId = target;
                // A microphone pipeline that came up while the sweep ran is left alone - the same
                // guard as in start(), for the same reason: visualizer is null for the whole life of
                // a microphone pipeline. Measured 14.09.2026: a resolve requested while the
                // microphone was unavailable finished 3 s after MicInputWindow had taken the input
                // back, and closed and reopened the full-band capture for nothing.
                if (isMicPipelineRunning() && wantsMicPipeline(isRadioSourceNow())) {
                    armWatchdog();
                } else if (!listeners.isEmpty() && visualizer == null) {
                    startInternal(target);
                    armWatchdog();
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
    /**
     * The one place an analyser is made, and the one place that says whether its input is acoustic.
     * A noise floor is learned and taken off only for a microphone: digital PCM from the Visualizer
     * carries no cabin noise, and a floor subtracted from it can only eat content.
     */
    private static NativeAnalyzer newAnalyzer(int sampleRate, int captureSize, boolean acoustic) {
        NativeAnalyzer analyzer = new NativeAnalyzer(sampleRate, captureSize);
        analyzer.setNoiseFloorEnabled(acoustic);
        return analyzer;
    }

    private void startNativeCapture(int captureSize, int samplingRateMilliHz) {
        stopNativeCapture();

        int sampleRate = samplingRateMilliHz > 0 ? samplingRateMilliHz / 1000 : 48000;
        nativeAnalyzer = newAnalyzer(sampleRate, captureSize, false);
        if (!nativeAnalyzer.isValid()) {
            Log.w(TAG, "Native analyser did not initialise; nothing will be measured");
            return;
        }
        applyNativeSettings();

        capturePolling = true;
        applyAnalysisProfile();
        final int size = captureSize;

        pollThread = new Thread(() -> {
            byte[] buffer = new byte[size];
            while (capturePolling) {
                Visualizer v = visualizer;
                if (v == null) break;
                try {
                    if (!pausedForCall && v.getWaveForm(buffer) == Visualizer.SUCCESS) {
                        noteSignal(buffer);
                        int fresh = nativeAnalyzer.push(buffer, size);
                        if (captureDumpUntil != 0) writeCaptureDump(buffer, size, fresh, sampleRate);
                        synchronized (waveformLock) {
                            System.arraycopy(buffer, 0, latestWaveform, 0, Math.min(size, latestWaveform.length));
                            latestWaveformLen = Math.min(size, latestWaveform.length);
                        }
                    }
                } catch (Throwable t) {
                    break;
                }
                try {
                    Thread.sleep(hasListenerFor(NativeAnalyzer.CONSUMER_MAIN)
                            ? POLL_PERIOD_MS : POLL_PERIOD_IDLE_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "wDSP_Capture");
        pollThread.setPriority(Thread.MAX_PRIORITY - 1);
        pollThread.start();

        // Analysis on its own thread. The platform has four to eight cores, so the transforms and
        // the polling genuinely run side by side rather than taking turns - and, more importantly,
        // a long transform can no longer hold up a poll, which is how the stitcher used to lose
        // its place and count a discontinuity.
        analysisThread = new Thread(() -> {
            while (capturePolling) {
                NativeAnalyzer analyzer = nativeAnalyzer;
                if (analyzer == null) break;
                try {
                    if (pausedForCall) {
                        Thread.sleep(50);   // interruption on stop lands in the catch below
                        continue;
                    }
                    analyzer.process(20);
                } catch (Throwable t) {
                    break;
                }
            }
        }, "wDSP_Analysis");
        analysisThread.start();

        displayThread = new Thread(() -> {
            while (capturePolling) {
                try {
                    dispatchNativeFrame();
                    Thread.sleep(hasListenerFor(NativeAnalyzer.CONSUMER_MAIN)
                            ? DISPLAY_PERIOD_MS : DISPLAY_PERIOD_IDLE_MS);
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
        if (nativeAnalyzer != null) nativeAnalyzer.stop();
        Thread poll = pollThread;
        Thread analysis = analysisThread;
        Thread display = displayThread;
        pollThread = null;
        analysisThread = null;
        displayThread = null;
        // Join before releasing: the native object must not vanish while a thread is inside it.
        // The wait is bounded by one poll period, so this costs milliseconds.
        try {
            if (poll != null) {
                poll.interrupt();
                poll.join(200);
            }
            if (analysis != null) {
                analysis.interrupt();
                analysis.join(200);
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

    /**
     * Whether the microphone capture is actually reading - not merely whether it was started.
     * A capture whose stream died is not active, so the watchdog and start() open it again.
     */
    public boolean isRadioCaptureActive() {
        return radioMicCapture != null && radioMicCapture.isCapturing();
    }

    /** Pushes the current settings - ballistics, latency and both gain profiles - into native. */
    public void applyNativeSettings() {
        NativeAnalyzer analyzer = nativeAnalyzer;
        if (analyzer == null || !analyzer.isValid()) return;
        boolean radioActive = isRadioCaptureActive();
        float latency = radioActive ? 0f : nativeLatencyMs;
        analyzer.setConfig(nativeAttackMs, nativeReleaseMs, latency,
                nativeRefMaxDb, nativeRangeDb);
        // In microphone mode the main analyser is normalised whatever the preference says, and the
        // preference is not touched - it still governs the calculated mode, where it belongs.
        //
        // Why it has to differ by mode. The capture side's automatic gain no longer reaches the
        // analyser: it moves between 0.5x and 16x, and a moving gain underneath a noise floor
        // learned in linear power makes the floor describe the gain instead of the car - that is
        // what turned cabin hiss into full-height bars in a pause. So the analyser is fed the raw
        // stream. But the main consumer's gain is OFF by default, deliberately: for the CALCULATED
        // spectrum the levels are absolute and an instrument that silently rescales itself cannot be
        // read. Taking the capture gain away with nothing in its place left the microphone spectrum
        // sitting far below the calculated one - the owner saw it at once ("до недавніх переробок
        // розрахунковий і спектр мікрофона були значно ближчі між собою").
        //
        // For a microphone there is nothing absolute to preserve: the level depends on how loudly
        // the car happens to be playing. So normalisation goes here, in the decibel domain, where it
        // cannot corrupt the floor - instead of in the capture, where it did. No new setting: the
        // owner's rule is that the default must already be right.
        final boolean mainAgc = radioActive || mainAgcEnabled;
        final float mainStrength = radioActive ? 1.0f : mainAgcStrength;
        analyzer.setAgc(NativeAnalyzer.CONSUMER_MAIN, mainAgc, mainStrength, mainAgcFloorDb);
        analyzer.setAgc(NativeAnalyzer.CONSUMER_STATUS_BAR, barAgcEnabled, barAgcStrength, barAgcFloorDb);
        // The curve is handed over by dispatchNativeFrame, the only place that does it.
        nativeCurveStale = true;
    }

    /** Picks the frame rate from who is actually watching. */
    private void applyAnalysisProfile() {
        NativeAnalyzer analyzer = nativeAnalyzer;
        if (analyzer == null || !analyzer.isValid()) return;
        if (isRadioCaptureActive()) {
            analyzer.setHop(HOP_ACTIVE);
        } else {
            analyzer.setHop(hasListenerFor(NativeAnalyzer.CONSUMER_MAIN) ? HOP_ACTIVE : HOP_IDLE);
        }
    }

    private boolean hasListenerFor(int consumer) {
        for (OnSpectrumDataListener l : listeners) {
            Integer c = consumerOf.get(l);
            int id = (c != null && c == NativeAnalyzer.CONSUMER_STATUS_BAR)
                    ? NativeAnalyzer.CONSUMER_STATUS_BAR : NativeAnalyzer.CONSUMER_MAIN;
            if (id == consumer) return true;
        }
        return false;
    }

    private void dispatchNativeFrame() {
        if (pausedForCall) return;
        NativeAnalyzer analyzer = nativeAnalyzer;
        if (analyzer == null || !analyzer.isValid() || listeners.isEmpty()) return;
        if (nativeCurveStale) {
            nativeCurveStale = false;   // before the read: a change during it marks it again
            analyzer.setDspCurve(getEffectiveSpectrumCurve());
        }

        long now = System.currentTimeMillis();
        if (lastCaptureTime != 0) {
            long observed = now - lastCaptureTime;
            if (observed > 0) captureIntervalMs = observed;
        }
        lastCaptureTime = now;

        for (int consumer = 0; consumer <= 1; consumer++) {
            // Nobody is looking at this one - every call below is a lock taken away from the
            // measurement thread for nothing.
            if (!hasListenerFor(consumer)) continue;
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
        stopRadioMicCapture();
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
    /**
     * Tells the microphone capture whether the speakers are quiet, so its noise floor is measured
     * in the gaps instead of being guessed from the sound.
     *
     * <p>Only this class can answer it, which is why the answer is pushed rather than asked for:
     * {@link #isMediaPlaybackActive()} already knows the platform's own source property, the
     * hardware volume manager's active type and Android's playback configurations, and it knows
     * that the radio bypasses AudioFlinger - so an empty session list is not silence. The radio is
     * added back here explicitly: in microphone spectrum mode it is usually the thing playing, and
     * {@link NowPlaying#isPlaying()} is what follows the screensaver's own play/pause.
     */
    private void publishPlaybackSilence() {
        if (appContext == null) return;
        NowPlaying now = NowPlaying.getInstance(appContext);
        boolean radioAudible = now.isRadioSource() && now.isPlaying();
        boolean silent = !radioAudible && !isMediaPlaybackActive() && !hasMediaSignalNow();
        radioMicCapture.setPlaybackSilent(silent);
    }

    private boolean isMediaPlaybackActive() {
        if (appContext != null && NowPlaying.getInstance(appContext).isRadioSource()) {
            return false;
        }
        String source = getActivePlayerPackage();
        if (source != null && !source.isEmpty()
                && !"nothing".equalsIgnoreCase(source) && !"Unknown".equalsIgnoreCase(source)
                && !NowPlaying.isRadioPackage(source)) {
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
    /**
     * The analyser is paused for the length of a phone call and resumed after it (owner,
     * 14.09.2026: "подзвонили - аналізатор на паузу").
     *
     * <p>🔴 Paused, NOT stopped: the capture stays open and the microphone stays ours. The owner,
     * the same night: "не треба віддавати мікрофон, дріт доказав". Measured on 13.09.2026 over four
     * Bluetooth calls - our 48 kHz capture and the call's own recorder ran side by side, each with
     * its own session and effects, with no echo or noise at the far end. Letting go would only
     * invite whoever grabs the input next to set its rate for everybody, which is the one thing
     * that does hurt. So during a call the samples are read and discarded: nothing is fed to the
     * analyser, nothing is analysed, nothing is drawn.
     *
     * <p>The source-state check is frozen too, because its radio branch is able to switch the
     * pipeline - and switching releases the microphone. Whatever pipeline was running when the call
     * began is the one running when it ends.
     *
     * <p>Set from McuService's poll; read by the capture, analysis and display threads.
     */
    private volatile boolean pausedForCall;

    public synchronized void setCallActive(boolean active) {
        if (active == pausedForCall) return;
        pausedForCall = active;
        if (active) {
            Log.i(TAG, "call in progress: analyser paused, capture and microphone kept");
        } else {
            Log.i(TAG, "call ended: analyser resumed");
            if (!listeners.isEmpty() && visualizer == null && !isRadioCaptureActive()) start();
        }
    }

    public synchronized void start() {
        if (visualizer != null) return;
        // A resolution is in flight and has deliberately released the capture; its callback will
        // attach. Racing it here produces "setCaptureSize() called in wrong state".
        //
        // The watchdog is armed BEFORE that check on purpose. At boot the service resolves as
        // soon as it has a context, and the status bar widget registers a moment later - so this
        // early return was taken every single time the unit started, the capture was attached by
        // the resolver's callback instead, and the watchdog was never scheduled at all. Nothing
        // then noticed that the session was silent once music began, and the analyser sat on the
        // empty session 0 until the visualizer was switched off and on by hand.
        armWatchdog();
        // 🔴 A microphone pipeline that is already reading is left alone.
        //
        // visualizer is null for the whole life of a microphone pipeline, so the check above never
        // stopped a second start - and startRadioMicPipeline() begins by closing the capture. Every
        // new listener therefore closed the microphone and reopened it: the status bar widget
        // coming back on ACC_ON, the main screen opening (three times in half a second).
        //
        // Measured 14.09.2026 on a real sleep: the process, its threads and the open AudioRecord all
        // survive suspend-to-RAM, and the pre-sleep capture delivered samples before ACC_ON was even
        // broadcast. The restart on wake closed that live stream and left the Google assistant
        // alone on the input for 164 ms - the only moment of the whole wake at which something else
        // could have set the input's rate (platform/05-AUDIO-PATH.md, "After hibernation...").
        //
        // A capture that has died is not "reading": isRadioCaptureActive() asks whether its read
        // loop is alive, so a dead stream still falls through and is opened again here, and by the
        // watchdog's checkSourceState(). That restart used to heal it by accident.
        if (isMicPipelineRunning() && wantsMicPipeline(isRadioSourceNow())) return;
        if (sessionResolver != null && sessionResolver.isResolving()) return;
        startInternal(currentSessionId);
        requestResolve("capture started");
    }

    /**
     * The head unit has woken up (ACC_ON; also called at boot, where nothing is running yet).
     *
     * <p>What survives a sleep is kept - the stream, the analyser, their threads - but not what the
     * two noise floors learned before it. The car has been parked and started since: engine, blower
     * and road are not what they were. Closing and reopening the capture used to forget both floors
     * as a side effect; now that it no longer happens, forgetting them has to be said out loud.
     */
    public synchronized void onWake() {
        if (!isMicPipelineRunning()) return;
        radioMicCapture.forgetNoiseFloor();
        NativeAnalyzer analyzer = nativeAnalyzer;
        if (analyzer != null) analyzer.forgetNoiseFloor();
        Log.i(TAG, "woke up with the microphone still open: capture kept, noise floors forgotten");
    }

    /** A microphone capture whose read loop is alive, with its analyser and threads behind it. */
    private boolean isMicPipelineRunning() {
        return isRadioCaptureActive() && capturePolling && nativeAnalyzer != null;
    }

    private boolean isRadioSourceNow() {
        return appContext != null && NowPlaying.getInstance(appContext).isRadioSource();
    }

    /**
     * Whether the analyser should listen through the microphone rather than tap PCM.
     *
     * <p>One function for a decision that was written out twice - in startInternal and
     * checkSourceState - and is now needed a third time in start, so that "already running what it
     * should" and "what to start" cannot drift apart. On radio there is no PCM at all, so the widget alone is reason enough to use the
     * microphone; on a PCM source only the chosen mode is.
     */
    private boolean wantsMicPipeline(boolean isRadio) {
        if (!canRunMic()) return false;
        boolean micMode = micModeInEffect();
        return isRadio ? (micMode || radioMicVisualizerEnabled) : micMode;
    }

    /** Schedules the silence watchdog. Safe to call repeatedly; it never stacks. */
    private void armWatchdog() {
        if (watchdogHandler == null) {
            watchdogHandler = new Handler(Looper.getMainLooper());
        }
        watchdogHandler.removeCallbacks(watchdog);
        watchdogHandler.postDelayed(watchdog, WATCHDOG_PERIOD_MS);
    }

    private void startRadioMicPipeline() {
        stopNativeCapture();
        stopRadioMicCapture();
        if (appContext == null) return;

        final int captureSize = 1024;
        final int sampleRate = 48000;
        nativeAnalyzer = newAnalyzer(sampleRate, captureSize, true);
        if (!nativeAnalyzer.isValid()) {
            Log.w(TAG, "Native analyser did not initialise for Radio MIC");
            return;
        }

        // Microphone inverse compensation curve: restores hardware capsule sub-bass and treble
        // roll-off. Handed over by dispatchNativeFrame on the first frame, like every other curve.
        nativeCurveStale = true;

        boolean started = radioMicCapture.start(appContext, (boosted, raw, len, captureGain) -> {
            if (!capturePolling) return;
            if (pausedForCall) return;   // the stream stays open; the samples go nowhere
            noteMicSignal(boosted, len);
            NativeAnalyzer analyzer = nativeAnalyzer;
            if (analyzer != null) {
                // The raw chunk, not the boosted one. The analyser learns its noise floor in
                // linear power, and the capture side's automatic gain moves between 0.5x and 16x:
                // every power value shifts underneath a floor that stays where it was learned. In
                // a pause that gain climbs, the floor does not follow, and "power minus floor"
                // turns cabin hiss into a full-height bar - which is exactly the fault reported
                // from the car. The display still lifts quiet music, but through the analyser's
                // own gain in getLevels(), which works in decibels and leaves the floor alone.
                analyzer.pushPcm16(raw, len, 1.0f);
            }
            synchronized (waveformLock) {
                // The waveform is a picture, so it keeps the boosted samples: at volume 2-4 the
                // raw stream is a flat line on screen.
                int copyLen = Math.min(len, latestWaveform.length);
                for (int i = 0; i < copyLen; i++) {
                    int val = (boosted[i] >> 8) + 128;
                    latestWaveform[i] = (byte) Math.max(0, Math.min(255, val));
                }
                latestWaveformLen = copyLen;
            }
        });

        if (!started) {
            Log.w(TAG, "RadioMicCapture failed to start");
            stopNativeCapture();
            return;
        }

        applyNativeSettings();
        capturePolling = true;
        applyAnalysisProfile();

        analysisThread = new Thread(() -> {
            while (capturePolling) {
                NativeAnalyzer analyzer = nativeAnalyzer;
                if (analyzer == null) break;
                try {
                    if (pausedForCall) {
                        Thread.sleep(50);   // interruption on stop lands in the catch below
                        continue;
                    }
                    analyzer.process(20);
                } catch (Throwable t) {
                    break;
                }
            }
        }, "wDSP_Analysis");
        analysisThread.start();

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
        Log.i(TAG, "Radio MIC analysis pipeline running with adaptive AGC");
    }

    private void stopRadioMicCapture() {
        if (radioMicCapture.isRunning()) {
            radioMicCapture.stop();
        }
    }

    public synchronized void checkSourceState() {
        if (pausedForCall) return;
        if (listeners.isEmpty() || appContext == null) return;
        boolean isRadio = NowPlaying.getInstance(appContext).isRadioSource();

        if (isRadio) {
            if (wantsMicPipeline(true)) {
                if (visualizer != null || !isRadioCaptureActive()) {
                    Log.i(TAG, "Source is Radio - switching to calibrated mic capture pipeline");
                    startInternal(currentSessionId);
                }
            } else {
                if (isRadioCaptureActive() || visualizer != null) {
                    Log.i(TAG, "Source is Radio (mic uncalibrated / mode calc) - stopping active capture");
                    stopRadioMicCapture();
                    stopNativeCapture();
                    if (visualizer != null) {
                        try {
                            visualizer.setEnabled(false);
                            visualizer.release();
                        } catch (Throwable ignored) {}
                        visualizer = null;
                    }
                }
            }
        } else {
            if (wantsMicPipeline(false)) {
                if (!isRadioCaptureActive()) {
                    Log.i(TAG, "Spectrum mode is MIC - switching to mic capture pipeline");
                    startInternal(currentSessionId);
                }
            } else {
                if (isRadioCaptureActive()) {
                    Log.i(TAG, "Source switched to AudioFlinger - restoring PCM capture");
                    startInternal(currentSessionId);
                    requestResolve("source switched to audioflinger");
                }
            }
        }
    }

    /**
     * Whether the microphone pipeline may run: a calibrated microphone, and nothing else.
     *
     * <p>🔴 Root is not a condition any more (owner, 14.09.2026: "рут лише запасний", and "якщо ми
     * перші, і гарантовано, беремо мікрофон, то і рут там не потрібен"). It was required because root
     * is how the assistant is stopped when it holds the input at 16 kHz - but measured after a cold
     * boot, wDSP opens the microphone 31 s before the assistant does, so the input is ours at 48 kHz
     * without stopping anyone. Root remains the fallback inside RadioMicCapture for a stream that
     * does come up narrow; without root, that case tells the person to restart the head unit.
     *
     * <p>One function now: this condition used to be computed twice, in checkSourceState and
     * startInternal, with identical code that would have drifted the first time one was edited.
     */
    private boolean canRunMic() {
        return !micUnavailable && appContext != null && RoomMeasurement.hasMicCompensation(appContext);
    }

    private void startInternal(int sessionId) {
        boolean isRadio = isRadioSourceNow();
        boolean wantMic = wantsMicPipeline(isRadio);

        if (isRadio) {
            if (visualizer != null) {
                try {
                    visualizer.setEnabled(false);
                    visualizer.release();
                } catch (Throwable ignored) {}
                visualizer = null;
            }
            if (wantMic) {
                startRadioMicPipeline();
            } else {
                stopRadioMicCapture();
                stopNativeCapture();
            }
            return;
        }

        if (wantMic) {
            if (visualizer != null) {
                try {
                    visualizer.setEnabled(false);
                    visualizer.release();
                } catch (Throwable ignored) {}
                visualizer = null;
            }
            startRadioMicPipeline();
            return;
        }

        stopRadioMicCapture();
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
                        if (pausedForCall) return;
                        noteSignal(waveform);
                        processWaveform(waveform, samplingRate);
                        if (waveform != null) {
                            synchronized (waveformLock) {
                                int len = Math.min(waveform.length, latestWaveform.length);
                                System.arraycopy(waveform, 0, latestWaveform, 0, len);
                                latestWaveformLen = len;
                            }
                        }
                    }

                    @Override
                    public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                        if (pausedForCall) return;
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
        stopRadioMicCapture();
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

        // 2. Content level per band
        float[] postSignalDb = new float[AudioConfig.NUM_BANDS];
        float maxPostDb = 0f;

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            // 🔴 No noise floor comes off here. This path only ever reads the Visualizer's digital
            // PCM, and there is no acoustic noise in it to take away - owner, 14.09.2026: "нахріна
            // це взагалі враховувати в розрахунковій кривій, це ж для мікрофону". It used to learn a
            // floor in quiet frames and subtract it linearly, which on steady content can take a
            // whole band down to nothing (the native twin read -133 dB at 17.8 kHz on pink noise).
            double signalPower = bandPower[i];

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
