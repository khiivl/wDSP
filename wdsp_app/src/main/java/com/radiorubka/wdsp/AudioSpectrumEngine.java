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

    private Visualizer visualizer;

    public static final int NUM_BANDS_16 = 16;
    public static final int NUM_BANDS_32 = 32;

    // DSP state as last sent to the hardware by McuService: Fletcher-Munson already folded into
    // the gain indices, quantised to 2 dB steps and clamped at +/-12, plus the subwoofer. This is
    // the truth about what the listener hears; the setGains/setQFactors/setFmOffsets values below
    // are raw slider positions from MainActivity and are only a fallback for when the service has
    // not published yet.
    private final int[] dspGainIdx = new int[NUM_BANDS_16];
    private final boolean[] dspQNarrow = new boolean[NUM_BANDS_16];
    private int dspSubFreqIdx = -1;
    private int dspSubGainIdx = 0;
    /** Door high-pass codes as sent in 0x88 byte 3; 0 is Through. See DspResponse.DOOR_HPF_HZ. */
    private int dspHpfFrontCode = 0;
    private int dspHpfRearCode = 0;
    private boolean hasServiceDspState = false;

    private final float[] dspCurveDb = new float[NUM_BANDS_16];
    private volatile boolean dspCurveDirty = true;
    /**
     * The Visualizer's analyser holds an older curve than {@link #getEffectiveSpectrumCurve}
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
        micCurveStale = true;
    }
    private float dspCurveSampleRate = 0f;

    // State parameters for Post-DSP synthesis
    private final int[] gains = new int[NUM_BANDS_16];
    private final boolean[] qNarrow = new boolean[NUM_BANDS_16];
    private final float[] fmOffsets = new float[NUM_BANDS_16];

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

    /** The Visualizer's analyser - digital, the calculated spectrum. */
    private volatile NativeAnalyzer nativeAnalyzer;
    private Thread pollThread;
    private Thread analysisThread;
    private volatile Thread displayThread;
    private volatile boolean displaying = false;

    /**
     * The microphone's analyser, running beside the Visualizer's while the microphone spectrum is
     * wanted (owner's option (b), 14.09.2026) - or alone on radio, where there is no PCM.
     */
    private volatile NativeAnalyzer micAnalyzer;
    private Thread micAnalysisThread;
    private volatile boolean micAnalysing = false;
    /** The microphone's analyser holds an older curve than it should - see {@link #nativeCurveStale}. */
    private volatile boolean micCurveStale = true;

    /**
     * The shift that puts the microphone on the calculated spectrum's scale, in dB, and whether one
     * has been measured since the microphone's analysis started.
     *
     * <p>Owner, 14.09.2026, option (b): the microphone spectrum is moved so that its 200-800 Hz middle
     * (bands 5..8 - the reference the calibration and the stored cabin response use) equals the
     * calculated spectrum's. Both then read in the track's dBFS, and what differs between them is
     * what the speakers and the car did. Smoothed over {@link #MIC_OFFSET_TIME_CONSTANT_MS} so a drum
     * hit does not move the whole picture, and held while the track is silent. Until the first valid
     * reading, and on radio where there is no calculated spectrum, the microphone is normalised as
     * before.
     */
    private volatile boolean micOffsetValid = false;
    private float micOffsetDb = 0f;
    private static final float MIC_OFFSET_TIME_CONSTANT_MS = 2000f;
    /** Below this the track's middle is silence - there is nothing to align the microphone to. */
    private static final float OFFSET_REFERENCE_MIN_DB = -70f;
    /** Below this the microphone's middle, after its noise floor, has nothing in it either. */
    private static final float OFFSET_MICROPHONE_MIN_DB = -100f;
    private final float[] offsetReferenceDb16 = new float[NUM_BANDS_16];
    private final float[] offsetMicrophoneDb16 = new float[NUM_BANDS_16];

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

    /**
     * The lowest level the decorations' automatic gain may lift to full scale when they draw the
     * Visualizer's digital tap, in dBFS.
     *
     * <p>Owner, 14.09.2026: the status bar widget and the screensaver are decoration - the more of
     * the screen they fill the better - but a visualiser showing full signal while the music has gone
     * quiet is not good either. Since the tap reads absolute levels (SCALING_MODE_AS_PLAYED) a quiet
     * passage really is lower, and this floor is what keeps it lower: the gain stops rising once the
     * reference reaches it. ⚠️ A starting value, not a measured one - to be set on real music. The
     * microphone keeps {@link #barAgcFloorDb}: a cabin at a low volume is far quieter than any track.
     */
    static final float BAR_AGC_FLOOR_DIGITAL_DB = -30f;

    /** The decorations' gain floor for what this analyser listens to. */
    private float barFloorDb(NativeAnalyzer analyzer) {
        return analyzer != null && analyzer.isAcoustic() ? barAgcFloorDb : BAR_AGC_FLOOR_DIGITAL_DB;
    }

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
        // A person has switched the microphone on: the moment root is asked for (owner, 14.09.2026),
        // once per process - Magisk's prompt, if it has never answered us, comes from this tap.
        if (SPECTRUM_MODE_MIC.equals(mode)) decideMicrophonePolicy(RootQuestion.MICROPHONE);
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

    /** The spectrum's own microphone capture is open and reading. */
    public boolean isMicrophoneOpen() {
        return radioMicCapture.isCapturing();
    }

    /** The spectrum's capture opened the input itself - see RadioMicCapture.firstOnInput. */
    public boolean isMicrophoneFirstOnInput() {
        return radioMicCapture.firstOnInput();
    }

    /** Session of the spectrum's latest recorder (kept after release), 0 when there has been none. */
    public int microphoneSessionId() {
        return radioMicCapture.lastSessionId();
    }

    /** Main thread. */
    private synchronized void onMicrophoneUnavailable() {
        micUnavailable = true;
        // A held microphone nobody was drawing is lost quietly: the spectrum on screen is the
        // calculated one already, and there is nothing to fall back from.
        final boolean wasAnalysed = analysingMicrophone();
        if (appContext != null && !toldMicUnavailable && wasAnalysed) {
            toldMicUnavailable = true;
            // Daily use: the spectrum is decoration and an instrument, not a reason to restart the
            // car (owner, 14.09.2026). Calibration asks differently, and is not this path.
            Toaster.show(appContext, R.string.mic_busy_calculated);
        }
        Log.w(TAG, "microphone unavailable - " + (wasAnalysed
                ? "spectrum falls back to calculated until the input is free"
                : "it was held, not drawn; waiting for the input to be free"));
        stopRadioMicCapture();
        if (wasAnalysed) {
            // The tap has been running underneath on a PCM source; on radio there is nothing to show.
            stopMicAnalysis();
            if (!listeners.isEmpty() && (visualizer == null || nativeAnalyzer == null)) {
                attachKnownSessionOrResolve("microphone unavailable");
            }
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
        } else if (holdMicrophone) {
            Log.i(TAG, "input free - holding the microphone again");
            ensureMicrophoneHeld();
        } else {
            // Nothing wants the microphone at this moment, and with root it is not held; the next
            // start opens it, takes the input if somebody is on it, and is judged the same way.
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
    public void setDspState(int[] gainIdx, boolean[] qNarrow, int subFreqIdx, int subGainIdx,
                            int hpfFrontCode, int hpfRearCode) {
        synchronized (dspGainIdx) {
            if (gainIdx != null) {
                System.arraycopy(gainIdx, 0, dspGainIdx, 0, Math.min(gainIdx.length, NUM_BANDS_16));
            }
            if (qNarrow != null) {
                System.arraycopy(qNarrow, 0, dspQNarrow, 0, Math.min(qNarrow.length, NUM_BANDS_16));
            }
            dspSubFreqIdx = subFreqIdx;
            dspSubGainIdx = subGainIdx;
            dspHpfFrontCode = hpfFrontCode;
            dspHpfRearCode = hpfRearCode;
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
    /** Blocks before this time are not written: the stream is settling after a scaling switch. */
    private volatile long captureDumpFrom = 0;
    /** Scaling mode to put back when the dump ends, or -1 when the dump did not change it. */
    private volatile int captureDumpRestoreScaling = -1;
    private java.io.DataOutputStream captureDumpBlocks;
    private int captureDumpCount;

    /**
     * Starts a capture dump of {@code ms} milliseconds, 500..2700 (the stitched ring holds 2.7 s).
     *
     * @param asPlayed switch the Visualizer to SCALING_MODE_AS_PLAYED for the dump - to find out
     *                 whether its levels are absolute - and back to what it was afterwards. The
     *                 first 3 s after the switch are not recorded, so the stitched ring holds only
     *                 samples captured in the new mode.
     */
    public void dumpCapture(int ms, boolean asPlayed) {
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
            long settle = 0;
            captureDumpRestoreScaling = -1;
            if (asPlayed && v != null) {
                int before = v.getScalingMode();
                int status = v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
                Log.i(TAG, "capture dump: scaling " + before + " -> AS_PLAYED, status " + status);
                captureDumpRestoreScaling = before;
                settle = 3000;
            }
            captureDumpFrom = System.currentTimeMillis() + settle;
            Log.i(TAG, "capture dump: " + ms + " ms, session " + currentSessionId
                    + ", tap rate " + visualizerSampleRateHz() + " Hz"
                    + " (Visualizer reports " + (v != null ? v.getSamplingRate() : -1) + " mHz)"
                    + ", scaling " + (v != null ? v.getScalingMode() : -1)
                    + ", measurement mode " + (v != null ? v.getMeasurementMode() : -1)
                    + ", capture size " + (v != null ? v.getCaptureSize() : -1));
            captureDumpUntil = captureDumpFrom + ms;
        } catch (Throwable t) {
            Log.w(TAG, "capture dump could not start: " + t);
            captureDumpUntil = 0;
        }
    }

    /** Capture thread. One record per poll: nanoTime, samples the stitcher took, the raw block. */
    private void writeCaptureDump(byte[] block, int size, int fresh, int sampleRate) {
        java.io.DataOutputStream out = captureDumpBlocks;
        if (out == null) return;
        if (System.currentTimeMillis() < captureDumpFrom) return;
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
            Visualizer v = visualizer;
            if (captureDumpRestoreScaling >= 0 && v != null) {
                v.setScalingMode(captureDumpRestoreScaling);
                Log.i(TAG, "capture dump: scaling restored to " + v.getScalingMode());
            }
            captureDumpRestoreScaling = -1;
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
                + " agcMain=" + mainGainEnabled(analyzer)
                + (analyzer.isAcoustic() ? (micOffsetValid
                        ? String.format(java.util.Locale.US, " (mic aligned, offset %+.1f dB)", micOffsetDb)
                        : " (forced: mic not aligned)") : "")
                + " agcBar=" + barAgcEnabled);
    }

    /** The DSP response curve, recomputed only when the state or the sample rate changed. */
    private float[] getDspCurve(float sampleRateHz) {
        synchronized (dspGainIdx) {
            if (dspCurveDirty || sampleRateHz != dspCurveSampleRate) {
                if (hasServiceDspState) {
                    DspResponse.compute(dspGainIdx, dspQNarrow, null,
                            dspSubFreqIdx, dspSubGainIdx, dspHpfFrontCode, dspHpfRearCode,
                            sampleRateHz, dspCurveDb);
                } else {
                    // No service state yet: fall back to the raw sliders, and since the curve is
                    // not baked into them here, add the Fletcher-Munson offsets explicitly.
                    synchronized (gains) {
                        synchronized (qNarrow) {
                            synchronized (fmOffsets) {
                                DspResponse.compute(gains, qNarrow, fmOffsets,
                                        -1, 0, 0, 0, sampleRateHz, dspCurveDb);
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
     * - For an analyser listening to the microphone (analyzer.isAcoustic()):
     *   Sound in the cabin has already passed through the hardware DSP (BU32107), power amp,
     *   and cabin speakers. The analyzer must NOT apply DSP EQ again.
     *   Instead, it applies the calibrated microphone inverse compensation curve from
     *   RoomMeasurement.getMicCompensationCurve(appContext) to restore hardware mic roll-off
     *   in the sub-bass (20–125 Hz) and upper treble.
     *
     * - For an analyser on the Visualizer tap:
     *   Audio is captured from pre-DSP AudioFlinger. The analyzer applies the simulated
     *   hardware DSP curve from getDspCurve().
     *
     * @param analyzer the analyser the curve is for - two can run at once, each with its own
     */
    private float[] getEffectiveSpectrumCurve(NativeAnalyzer analyzer) {
        if (analyzer.isAcoustic()) {
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
    /**
     * The player {@link #currentSessionId} was last heard carrying audio for, from
     * sys.qf.last_audio_src; null until a session has been heard.
     *
     * <p>Owner, 14.09.2026: switching the spectrum from the microphone back to the calculated mode
     * took about five seconds, the other way round was instant - "ми знаємо що плеєр не мінявся,
     * відповідно канал можна просто зберігати". The five seconds were a full session sweep, run on
     * every return to the Visualizer whether or not anything had changed. A session heard for this
     * very player is attached again at once; the watchdog still re-resolves if it then stays silent.
     */
    private volatile String sessionHeardFor;

    /** Whether the session we would attach to was heard carrying the player that is current now. */
    private boolean sessionKnownForCurrentPlayer() {
        String heard = sessionHeardFor;
        return heard != null && !heard.isEmpty() && heard.equals(getActivePlayerPackage());
    }

    /**
     * Attaches the Visualizer to the session we already have, and sweeps for another only when that
     * session was not heard for the current player. {@code reason} goes to the log either way.
     */
    private void attachKnownSessionOrResolve(String reason) {
        startInternal(currentSessionId);
        if (sessionKnownForCurrentPlayer()) {
            // The watchdog judges silence from here, so give the fresh capture its tolerance.
            lastSignalTime = System.currentTimeMillis();
            armWatchdog();
            Log.i(TAG, reason + ": session " + currentSessionId + " was heard for " + sessionHeardFor
                    + " and the player has not changed - attached without a sweep");
        } else {
            requestResolve(reason);
        }
    }
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
    /**
     * Sweeps in a row that found nothing. Each one doubles the wait before the next, up to
     * {@link #RESOLVE_RETRY_MAX_MS}; any signal on the tap, a resolution that finds a session, or a
     * change of player starts it again from {@link #RESOLVE_RETRY_MS}.
     *
     * <p>Measured 14.09.2026 after a reboot (boot logger, bootlog/0008): with YouTube Music paused,
     * sys.qf.last_audio_src still names it, isMediaPlaybackActive() says media plays, the tap is
     * silent - and a sweep ran every four to five seconds, from 39 s after boot onwards, finding
     * nothing each time. The backoff keeps the case the retry exists for (a player that ducked for a
     * moment) and stops the loop over one that is simply paused, without trusting a player's own play
     * state - Bluetooth audio has no Java player for that.
     */
    private int fruitlessResolves = 0;
    private static final long RESOLVE_RETRY_MAX_MS = 60000;
    private static final long WATCHDOG_PERIOD_MS = 2000;
    /**
     * Waveform samples are unsigned 8-bit centred on 128. Any step off the centre is signal.
     *
     * <p>It was 2 while the Visualizer normalised every block to full scale, where only digital
     * silence stayed near 128. With absolute levels (AS_PLAYED) 2 steps is a peak of -36 dBFS, and a
     * quiet passage below it would read as silence: the watchdog would go looking for another session
     * and hasMediaSignalNow() would tell the service nothing is playing.
     */
    private static final int SIGNAL_THRESHOLD = 0;

    public void initContext(Context context) {
        if (context == null) return;
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.sessionResolver = SessionResolver.getInstance(appContext);
        this.sessionResolver.start();
        loadDisplaySettings(appContext);
        // At unlock, straight after audioserver is up: without root this is what makes us first on
        // the input for the rest of the drive.
        decideMicrophonePolicy(RootQuestion.START);
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
        fruitlessResolves = 0;
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
                // The session is carrying this player right now: remember it, so a return from the
                // microphone does not have to sweep for it again.
                if (visualizer != null && System.currentTimeMillis() - lastSignalTime < SILENCE_TOLERANCE_MS) {
                    sessionHeardFor = getActivePlayerPackage();
                }
                checkSourceState();
                long now = System.currentTimeMillis();
                if (now - lastSignalTime < SILENCE_TOLERANCE_MS) fruitlessResolves = 0;
                long wait = lastResolveFoundNothing
                        ? Math.min(RESOLVE_RETRY_MAX_MS, RESOLVE_RETRY_MS << Math.min(Math.max(0, fruitlessResolves - 1), 4))
                        : RESOLVE_COOLDOWN_MS;
                // The tap runs in the microphone mode too, as its reference, so a silent session is
                // re-resolved whatever the mode. On radio isMediaPlaybackActive() is false.
                if (visualizer != null && now - lastSignalTime > SILENCE_TOLERANCE_MS
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
        // Radio only: on a PCM source the tap runs in the microphone mode too, as its reference, and
        // is resolved like any other; the microphone's analysis is not touched by it.
        if (appContext != null && NowPlaying.getInstance(appContext).isRadioSource()) {
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
                fruitlessResolves = sessionId < 0 ? fruitlessResolves + 1 : 0;
                if (sessionId < 0) {
                    Log.w(TAG, "No session carrying audio found; staying on " + previousSession);
                } else {
                    if (sessionId != previousSession) {
                        Log.i(TAG, "Switching capture session: " + previousSession + " -> " + sessionId);
                    }
                    lastSignalTime = System.currentTimeMillis();
                    // The resolver heard audio on it, for the player that is current now.
                    sessionHeardFor = getActivePlayerPackage();
                }
                currentSessionId = target;
                // A microphone pipeline that came up while the sweep ran is left alone: startInternal
                // only starts the microphone's analysis when it is not running. Measured 14.09.2026:
                // a resolve requested while the microphone was unavailable finished 3 s after
                // MicInputWindow had taken the input back, and closed and reopened the full-band
                // capture for nothing - back when the resolver restarted the whole pipeline.
                if (!listeners.isEmpty() && visualizer == null) {
                    startInternal(target);
                }
                armWatchdog();
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
        return new NativeAnalyzer(sampleRate, captureSize, acoustic);
    }

    /**
     * Whether the analyser that is running listens to a microphone.
     *
     * <p>🔴 The one question every setting pushed into an analyser depends on - the curve (the
     * microphone's compensation or the DSP model), the main gain, the display latency, the decorations'
     * gain floor, the frame rate - and it is answered by the analyser itself, which was told at
     * creation what feeds it. Until 14.09.2026 each of those asked something else: whether the
     * capture thread was alive, or which mode had been chosen - three answers to one question, which
     * part company exactly when it matters. A microphone stream that dies ("capture loop ended by
     * itself") leaves its acoustic analyser running until the watchdog reopens it, and every frame in
     * between took the digital tap's gain floor, as would any settings push the digital latency and
     * no gain. A mode that says microphone while the Visualizer's analyser still runs - after a call,
     * until the watchdog switches - would hand that analyser the microphone's +30 dB bass
     * compensation. Found reading the code, not seen on screen; owner: "це не дрібне, це не
     * стабільна поведінка".
     *
     * <p>Since the microphone is drawn on the calculated scale (owner's option (b), 14.09.2026) two
     * analysers can run at once, and this asks about the one that is SHOWN: the microphone's while
     * its analysis runs, the Visualizer's otherwise.
     */
    private boolean analysingMicrophone() {
        NativeAnalyzer analyzer = shownAnalyzer();
        return analyzer != null && analyzer.isAcoustic();
    }

    /** The analyser the display draws: the microphone's while it runs, else the Visualizer's. */
    private NativeAnalyzer shownAnalyzer() {
        NativeAnalyzer mic = micAnalyzer;
        return micAnalysing && mic != null ? mic : nativeAnalyzer;
    }

    /**
     * The rate the Visualizer's samples are really at - the one number every frequency on the
     * spectrum hangs on, and every consumer of the tap reads it here.
     *
     * <p>🔴 Not {@link Visualizer#getSamplingRate()}. Measured 14.09.2026: it reports 44 100 000 mHz
     * while delivering 48 kHz. A 1 kHz + 10 kHz test tone read at the reported rate came out at
     * 918.5 and 9187 Hz, in every raw block, with no stitching involved; two dumps of music gave
     * 47 992 and 48 011 new samples a second. Every band on screen had been drawn 8.8 % below its
     * label - invisible on pink noise, which looks the same at any scale. The samples are the output
     * mix, so their rate is the output's: here the primary output, AudioOut_D, at 48 000 Hz.
     */
    private int visualizerSampleRateHz() {
        try {
            AudioManager am = appContext != null
                    ? (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE) : null;
            String rate = am != null ? am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) : null;
            if (rate != null) {
                int hz = Integer.parseInt(rate.trim());
                if (hz > 0) return hz;
            }
        } catch (RuntimeException ignored) {
            // A property the platform does not fill in: fall through to the rate this unit runs at.
        }
        return 48000;
    }

    /** The Visualizer's pipeline: its analyser, the poll thread and the analysis thread. */
    private void startNativeCapture(int captureSize, int sampleRate) {
        stopVisualizerPipeline();

        NativeAnalyzer created = newAnalyzer(sampleRate, captureSize, false);
        if (!created.isValid()) {
            Log.w(TAG, "Native analyser did not initialise; nothing will be measured");
            return;
        }
        nativeAnalyzer = created;
        nativeCurveStale = true;
        applyNativeSettings(created);

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
                        long readAt = System.nanoTime();
                        noteSignal(buffer);
                        int fresh = created.push(buffer, size, readAt);
                        if (captureDumpUntil != 0) writeCaptureDump(buffer, size, fresh, sampleRate);
                        // The oscilloscope follows what is shown: while the microphone is drawn its
                        // own chunks fill the waveform, and two sources would make it flicker.
                        if (!micAnalysing) {
                            synchronized (waveformLock) {
                                System.arraycopy(buffer, 0, latestWaveform, 0, Math.min(size, latestWaveform.length));
                                latestWaveformLen = Math.min(size, latestWaveform.length);
                            }
                        }
                    }
                } catch (Throwable t) {
                    break;
                }
                try {
                    // Only a reference while the microphone is drawn, so no faster than for the widget.
                    Thread.sleep(hasListenerFor(NativeAnalyzer.CONSUMER_MAIN) && !micAnalysing
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
                try {
                    if (pausedForCall) {
                        Thread.sleep(50);   // interruption on stop lands in the catch below
                        continue;
                    }
                    created.process(20);
                } catch (Throwable t) {
                    break;
                }
            }
        }, "wDSP_Analysis");
        analysisThread.start();

        ensureDisplayThread();
    }

    /** Everything that analyses: the Visualizer's pipeline and the microphone's analysis. */
    private void stopNativeCapture() {
        stopMicAnalysis();
        stopVisualizerPipeline();
    }

    /**
     * Guards the lifetime of both native analysers: held while the display thread reads them and
     * while the microphone capture pushes into its one, and taken to release either. The display
     * thread is shared and outlives each analyser, and the capture outlives its analysis now that
     * the microphone is held, so joining threads alone no longer keeps a released object out of reach.
     */
    private final Object analyzerLock = new Object();

    private void stopVisualizerPipeline() {
        capturePolling = false;
        NativeAnalyzer analyzer = nativeAnalyzer;
        if (analyzer != null) analyzer.stop();
        Thread poll = pollThread;
        Thread analysis = analysisThread;
        pollThread = null;
        analysisThread = null;
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
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        synchronized (analyzerLock) {
            if (analyzer != null) analyzer.release();
            nativeAnalyzer = null;
        }
        stopDisplayIfIdle();
    }

    private void stopMicAnalysis() {
        micAnalysing = false;
        micOffsetValid = false;
        NativeAnalyzer analyzer = micAnalyzer;
        if (analyzer != null) analyzer.stop();
        Thread analysis = micAnalysisThread;
        micAnalysisThread = null;
        try {
            if (analysis != null) {
                analysis.interrupt();
                analysis.join(200);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        synchronized (analyzerLock) {
            if (analyzer != null) analyzer.release();
            micAnalyzer = null;
        }
        stopDisplayIfIdle();
    }

    /** One display thread for whichever analysers run; started by whichever starts first. */
    private void ensureDisplayThread() {
        Thread running = displayThread;
        if (running != null && running.isAlive()) return;
        displaying = true;
        Thread display = new Thread(() -> {
            while (displaying) {
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
        displayThread = display;
        display.start();
    }

    /** Stops the display thread once neither analyser is left. */
    private void stopDisplayIfIdle() {
        if (nativeAnalyzer != null || micAnalyzer != null) return;
        displaying = false;
        Thread display = displayThread;
        displayThread = null;
        if (display == null || display == Thread.currentThread()) return;
        try {
            display.interrupt();
            display.join(200);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Whether the microphone capture is actually reading - not merely whether it was started.
     * A capture whose stream died is not active, so the watchdog and start() open it again.
     */
    public boolean isRadioCaptureActive() {
        return radioMicCapture != null && radioMicCapture.isCapturing();
    }

    /** Pushes the current settings - ballistics, latency and both gain profiles - into every analyser. */
    public void applyNativeSettings() {
        applyNativeSettings(nativeAnalyzer);
        applyNativeSettings(micAnalyzer);
    }

    private void applyNativeSettings(NativeAnalyzer analyzer) {
        if (analyzer == null || !analyzer.isValid()) return;
        // What this analyser is fed, as it was told when it was made - see analysingMicrophone().
        boolean radioActive = analyzer.isAcoustic();
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
        analyzer.setAgc(NativeAnalyzer.CONSUMER_MAIN, mainGainEnabled(analyzer),
                mainGainStrength(analyzer), mainAgcFloorDb);
        analyzer.setAgc(NativeAnalyzer.CONSUMER_STATUS_BAR, barAgcEnabled, barAgcStrength,
                barFloorDb(analyzer));
        // The curve is handed over by dispatchNativeFrame, the only place that does it.
        if (analyzer.isAcoustic()) micCurveStale = true; else nativeCurveStale = true;
    }

    /**
     * Whether the main consumer's gain is on for this analyser.
     *
     * <p>For a microphone: off once it is aligned to the calculated spectrum (option (b) - then it
     * reads in the track's dBFS like the calculated one, and the preference governs both alike); on,
     * at full strength, while there is nothing to align it to (radio, a silent track, the first
     * moments) - see applyNativeSettings for why a raw microphone needs it. For the Visualizer's
     * analyser: as the preference says.
     *
     * <p>🔴 One function because the answer was given in two places that disagreed.
     * applyNativeSettings forced the gain on for a microphone, and dispatchNativeFrame then set it
     * back from the preference on every frame - so the forcing lasted until the next frame, a few
     * milliseconds, and the normalised microphone levels were never normalised by default.
     */
    private boolean mainGainEnabled(NativeAnalyzer analyzer) {
        return (analyzer.isAcoustic() && !micOffsetValid) || mainAgcEnabled;
    }

    /** The strength that goes with {@link #mainGainEnabled}: full for an unaligned microphone. */
    private float mainGainStrength(NativeAnalyzer analyzer) {
        return analyzer.isAcoustic() && !micOffsetValid ? 1.0f : mainAgcStrength;
    }

    /** Picks the frame rate from who is actually watching. */
    private void applyAnalysisProfile() {
        NativeAnalyzer mic = micAnalyzer;
        if (mic != null && mic.isValid()) mic.setHop(HOP_ACTIVE);
        NativeAnalyzer tap = nativeAnalyzer;
        if (tap != null && tap.isValid()) {
            // While the microphone is drawn the Visualizer's analyser is only its reference.
            tap.setHop(hasListenerFor(NativeAnalyzer.CONSUMER_MAIN) && !micAnalysing ? HOP_ACTIVE : HOP_IDLE);
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
        if (pausedForCall || listeners.isEmpty()) return;
        synchronized (analyzerLock) {
            NativeAnalyzer tap = nativeAnalyzer;
            NativeAnalyzer mic = micAnalysing ? micAnalyzer : null;
            if (tap != null && !tap.isValid()) tap = null;
            if (mic != null && !mic.isValid()) mic = null;
            final NativeAnalyzer analyzer = mic != null ? mic : tap;
            if (analyzer == null) return;
            // Each analyser gets its own curve: the DSP model for the tap, the microphone's
            // compensation for the microphone - and the tap keeps its curve while it is only the
            // reference, because the microphone is aligned to the calculated spectrum as drawn.
            if (tap != null && nativeCurveStale) {
                nativeCurveStale = false;   // before the read: a change during it marks it again
                tap.setDspCurve(getEffectiveSpectrumCurve(tap));
            }
            if (mic != null && micCurveStale) {
                micCurveStale = false;
                mic.setDspCurve(getEffectiveSpectrumCurve(mic));
            }

            long now = System.currentTimeMillis();
            if (lastCaptureTime != 0) {
                long observed = now - lastCaptureTime;
                if (observed > 0) captureIntervalMs = observed;
            }
            lastCaptureTime = now;

            if (mic != null) alignMicrophoneToCalculated(mic, tap);

            for (int consumer = 0; consumer <= 1; consumer++) {
                // Nobody is looking at this one - every call below is a lock taken away from the
                // measurement thread for nothing.
                if (!hasListenerFor(consumer)) continue;
                ConsumerFrames f = frames[consumer];
                // Absolute levels for the "no normalisation" view, and this consumer's gain profile
                // for the other. Two calls because the gain state is per consumer by design.
                analyzer.setAgc(consumer, false, 0f, 0f);
                analyzer.getLevels(consumer, f.level32, f.level16);
                boolean enabled = consumer == NativeAnalyzer.CONSUMER_MAIN ? mainGainEnabled(analyzer) : barAgcEnabled;
                float strength = consumer == NativeAnalyzer.CONSUMER_MAIN ? mainGainStrength(analyzer) : barAgcStrength;
                float floorDb = consumer == NativeAnalyzer.CONSUMER_MAIN ? mainAgcFloorDb : barFloorDb(analyzer);
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
        }

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

    /**
     * Option (b): moves the microphone's main consumer so that its 200-800 Hz middle equals the
     * calculated spectrum's. Display thread, under {@link #analyzerLock}.
     *
     * <p>Mean of the four bands in dB - 200, 315, 500, 800 Hz - the same reference the calibration's
     * synthesis and the stored cabin response are taken against, so what remains different between
     * the two spectra is exactly the quantity the cabin measurement describes. Taken through the one
     * native fold on both sides. Smoothed; held while either middle has nothing in it; with no tap
     * (radio) there is no calculated spectrum, and the microphone is normalised instead.
     */
    private void alignMicrophoneToCalculated(NativeAnalyzer mic, NativeAnalyzer tap) {
        if (tap == null) {
            micOffsetValid = false;
            mic.setLevelOffsetDb(NativeAnalyzer.CONSUMER_MAIN, 0f);
            return;
        }
        tap.getLevelsDb16(offsetReferenceDb16);
        mic.getLevelsDb16(offsetMicrophoneDb16);
        float reference = midbandDb(offsetReferenceDb16);
        float microphone = midbandDb(offsetMicrophoneDb16);
        if (reference > OFFSET_REFERENCE_MIN_DB && microphone > OFFSET_MICROPHONE_MIN_DB) {
            float target = reference - microphone;
            if (!micOffsetValid) {
                micOffsetDb = target;
                micOffsetValid = true;
                Log.i(TAG, String.format(java.util.Locale.US,
                        "microphone aligned to the calculated spectrum: 200-800 Hz %.1f dBFS (track) vs "
                                + "%.1f dB (microphone), offset %+.1f dB", reference, microphone, target));
            } else {
                float alpha = Math.min(1f, captureIntervalMs / MIC_OFFSET_TIME_CONSTANT_MS);
                micOffsetDb += (target - micOffsetDb) * alpha;
            }
        }
        mic.setLevelOffsetDb(NativeAnalyzer.CONSUMER_MAIN, micOffsetValid ? micOffsetDb : 0f);
    }

    /** The 200-800 Hz reference: equaliser bands 5..8, mean in dB. */
    private static float midbandDb(float[] db16) {
        return (db16[5] + db16[6] + db16[7] + db16[8]) / 4f;
    }

    /**
     * Drops the Visualizer without touching listeners or the watchdog - and without touching the
     * microphone: a resolution looks for the tap's session, and the microphone's analysis, which the
     * tap is only the reference for, goes on through it.
     */
    private synchronized void releaseCapture() {
        stopVisualizerPipeline();
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
            if (!listeners.isEmpty() && visualizer == null && !isMicPipelineRunning()) start();
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
        // A capture that has died is not "reading": isMicPipelineRunning() asks whether its read
        // loop is alive, so a dead stream still falls through and is opened again here, and by the
        // watchdog's checkSourceState(). That restart used to heal it by accident.
        if (isMicPipelineRunning() && wantsMicPipeline(isRadioSourceNow())) return;
        if (sessionResolver != null && sessionResolver.isResolving()) return;
        // Nothing starts during a call (owner, 14.09.2026: no spectrum is wanted there at all) -
        // least of all a microphone that would take the input off somebody. setCallActive(false)
        // starts it when the call is over.
        if (pausedForCall) return;
        attachKnownSessionOrResolve("capture started");
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
        // Not a root question: root is asked at start and on a person's action, never polled - a
        // wake re-applies whatever Magisk last answered.
        decideMicrophonePolicy(RootQuestion.KNOWN);
        if (!radioMicCapture.isCapturing()) return;
        radioMicCapture.forgetNoiseFloor();
        synchronized (analyzerLock) {
            NativeAnalyzer mic = micAnalyzer;
            if (mic != null) mic.forgetNoiseFloor();
        }
        Log.i(TAG, "woke up with the microphone still open: capture kept, noise floors forgotten");
    }

    /**
     * The microphone spectrum is running: an acoustic analyser with its threads, fed by a capture
     * whose read loop is alive.
     *
     * <p>🔴 Not the same as the capture being open. Since the microphone is held without root, the
     * capture is open in the calculated mode too, and every place that used "the capture is reading"
     * to mean "the microphone spectrum is running" had to ask this instead - the watchdog, the
     * resolver, the source check, the call's end.
     */
    private boolean isMicPipelineRunning() {
        return micAnalysing && micAnalyzer != null && radioMicCapture.isCapturing();
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

    /**
     * The microphone's analysis: its analyser and analysis thread, beside whatever the Visualizer's
     * pipeline is doing - that one is left alone and becomes the reference (option (b)).
     */
    private void startRadioMicPipeline() {
        stopMicAnalysis();
        // The capture is NOT closed and reopened here any more. A held microphone (no root) is
        // simply analysed from now on; and closing it only to open it again was a moment in which
        // somebody else could open the input first.
        if (appContext == null) return;

        final int captureSize = 1024;
        final NativeAnalyzer created = newAnalyzer(RadioMicCapture.SAMPLE_RATE, captureSize, true);
        if (!created.isValid()) {
            Log.w(TAG, "Native analyser did not initialise for the microphone");
            return;
        }

        boolean started = radioMicCapture.isCapturing()
                || radioMicCapture.start(appContext, this::onMicChunk);

        if (!started) {
            Log.w(TAG, "RadioMicCapture failed to start");
            created.release();
            return;
        }

        micAnalyzer = created;
        micOffsetValid = false;
        // Microphone inverse compensation curve: restores hardware capsule sub-bass and treble
        // roll-off. Handed over by dispatchNativeFrame on the first frame, like every other curve.
        micCurveStale = true;
        applyNativeSettings(created);
        micAnalysing = true;
        applyAnalysisProfile();

        micAnalysisThread = new Thread(() -> {
            while (micAnalysing) {
                try {
                    if (pausedForCall) {
                        Thread.sleep(50);   // interruption on stop lands in the catch below
                        continue;
                    }
                    created.process(20);
                } catch (Throwable t) {
                    break;
                }
            }
        }, "wDSP_MicAnalysis");
        micAnalysisThread.start();

        ensureDisplayThread();
        Log.i(TAG, "microphone analysis running" + (nativeAnalyzer != null
                ? ", aligned to the calculated spectrum running beside it"
                : " alone, normalised - no calculated spectrum on this source"));
    }

    /**
     * The one sink of the microphone capture, for as long as the capture lives - held or not, and
     * whatever is analysing it. Capture thread.
     */
    private void onMicChunk(short[] boosted, short[] raw, int len, float captureGain) {
        // Held but not analysed: the samples go nowhere, and nothing downstream is told of them -
        // signal from a microphone nobody is drawing must not look like playback to NowPlaying.
        if (!micAnalysing) return;
        if (pausedForCall) return;   // the stream stays open; the samples go nowhere
        synchronized (analyzerLock) {
            NativeAnalyzer analyzer = micAnalyzer;
            if (analyzer == null) return;
            // The raw chunk, not the boosted one. The analyser learns its noise floor in linear
            // power, and the capture side's automatic gain moves between 0.5x and 16x: every power
            // value shifts underneath a floor that stays where it was learned. In a pause that gain
            // climbs, the floor does not follow, and "power minus floor" turns cabin hiss into a
            // full-height bar - which is exactly the fault reported from the car. The display still
            // lifts quiet music, but through the analyser's own gain in getLevels(), which works in
            // decibels and leaves the floor alone.
            analyzer.pushPcm16(raw, len, 1.0f);
        }
        noteMicSignal(boosted, len);
        synchronized (waveformLock) {
            // The waveform is a picture, so it keeps the boosted samples: at volume 2-4 the raw
            // stream is a flat line on screen.
            int copyLen = Math.min(len, latestWaveform.length);
            for (int i = 0; i < copyLen; i++) {
                int val = (boosted[i] >> 8) + 128;
                latestWaveform[i] = (byte) Math.max(0, Math.min(255, val));
            }
            latestWaveformLen = copyLen;
        }
    }

    /**
     * Lets the microphone go - unless it is held, in which case nothing is closed and only its
     * analysis has stopped.
     */
    private void stopRadioMicCapture() {
        if (holdMicrophone && !micUnavailable) return;
        if (radioMicCapture.isRunning()) {
            radioMicCapture.stop();
        }
    }

    /**
     * Whether the microphone is held open from start-up for as long as the app runs.
     *
     * <p>🔴 Owner, 14.09.2026: "ми завантажилися, забрали мікрофон собі, і пішли всі лісом - хай
     * сідають ресемплом" - and then: "якщо є рут, можна і 2, це чистіший шлях для системи". So two
     * policies, chosen by root:
     * <ul>
     *   <li>no root - hold it. Whoever opens the input first sets it up for everybody, and without
     *   root there is no taking it back; switching the spectrum to the calculated mode used to close
     *   the capture, and on the return we joined the assistant's input instead (dumpsys 14.09.2026,
     *   21:17). A held capture is analysed when wanted and read into nothing otherwise.</li>
     *   <li>root - open it when it is wanted and close it when it is not; on opening, whoever is
     *   already on the input is stopped through root and both are checked - our input full band,
     *   the stopped app running again (RadioMicCapture, MicrophoneGuard.takeInputAsRoot).</li>
     * </ul>
     * Decided once a context is there, off the main thread (the root check may block); again when a
     * person switches the spectrum to the microphone, and whenever RootAccess takes a new answer. Not
     * on wake: root is not polled (owner, 14.09.2026 - every su makes Magisk toast).
     */
    private volatile boolean holdMicrophone;
    private final Handler micHoldHandler = new Handler(Looper.getMainLooper());
    /** Reopens a held capture that died - an audioserver restart, a read error. */
    private static final long MIC_HOLD_CHECK_MS = 5000;
    private final Runnable micHoldTick = new Runnable() {
        @Override
        public void run() {
            ensureMicrophoneHeld();
            if (holdMicrophone) micHoldHandler.postDelayed(this, MIC_HOLD_CHECK_MS);
        }
    };

    /** Which root question the policy is decided on - see RootAccess for when su may run. */
    private enum RootQuestion {
        /** The process is starting: Magisk is asked if it has answered this installation before. */
        START,
        /** A person has switched the microphone on: Magisk is asked once per process, prompt and all. */
        MICROPHONE,
        /** Whatever Magisk last answered; no su. */
        KNOWN,
    }

    /** RootAccess took a new answer (the root card, a first microphone use elsewhere). */
    public void onRootAnswerChanged() {
        decideMicrophonePolicy(RootQuestion.KNOWN);
    }

    /** Chooses the policy above; the second half runs on the main thread. */
    private void decideMicrophonePolicy(final RootQuestion question) {
        final Context ctx = appContext;
        if (ctx == null) return;
        new Thread(() -> {
            final boolean root;
            switch (question) {
                case START:
                    root = RootAccess.checkAtStart(ctx);
                    break;
                case MICROPHONE:
                    root = RootAccess.checkForMicrophone(ctx);
                    break;
                default:
                    root = RootAccess.hasRoot();
            }
            micHoldHandler.post(() -> {
                boolean was = holdMicrophone;
                holdMicrophone = !root;
                if (was != holdMicrophone || !policyLogged) {
                    policyLogged = true;
                    Log.i(TAG, root
                            ? "root: the microphone is opened when wanted, whoever is on the input is taken off it"
                            : "no root: the microphone is held from start-up - whoever comes later joins our input");
                }
                micHoldHandler.removeCallbacks(micHoldTick);
                if (holdMicrophone) {
                    micHoldTick.run();
                } else if (was) {
                    // Root has appeared: a held capture nobody analyses is let go.
                    synchronized (AudioSpectrumEngine.this) {
                        if (!isMicPipelineRunning()) stopRadioMicCapture();
                    }
                }
            });
        }, "wDSP_MicPolicy").start();
    }

    private boolean policyLogged;

    /** Opens the microphone if it is to be held and is not open. Main thread. */
    private synchronized void ensureMicrophoneHeld() {
        if (!holdMicrophone || appContext == null || micUnavailable || pausedForCall) return;
        if (radioMicCapture.isCapturing()) return;
        if (radioMicCapture.start(appContext, this::onMicChunk)) {
            Log.i(TAG, "microphone held open (no root)");
        }
    }

    public synchronized void checkSourceState() {
        if (pausedForCall) return;
        if (listeners.isEmpty() || appContext == null) return;
        boolean isRadio = NowPlaying.getInstance(appContext).isRadioSource();

        if (isRadio) {
            if (wantsMicPipeline(true)) {
                if (visualizer != null || !isMicPipelineRunning()) {
                    Log.i(TAG, "Source is Radio - switching to calibrated mic capture pipeline");
                    startInternal(currentSessionId);
                }
            } else {
                if (isMicPipelineRunning() || visualizer != null) {
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
                if (!isMicPipelineRunning()) {
                    Log.i(TAG, "Spectrum mode is MIC - switching to mic capture pipeline");
                    startInternal(currentSessionId);
                }
            } else {
                if (isMicPipelineRunning()) {
                    // The tap has been running under the microphone all along: stopping the
                    // microphone's analysis is the whole switch.
                    Log.i(TAG, "Source switched to AudioFlinger - restoring PCM capture");
                    stopMicAnalysis();
                    stopRadioMicCapture();
                    if (visualizer == null || nativeAnalyzer == null) {
                        attachKnownSessionOrResolve("source switched to audioflinger");
                    }
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
            // No PCM: no tap to keep, and the microphone - if it is wanted - is drawn alone.
            releaseVisualizer();
            stopVisualizerPipeline();
            if (wantMic) {
                if (!isMicPipelineRunning()) startRadioMicPipeline();
            } else {
                stopMicAnalysis();
                stopRadioMicCapture();
            }
            return;
        }

        // A PCM source: the tap runs whatever the mode. It is the calculated spectrum, and in the
        // microphone mode the reference the microphone is aligned to (owner's option (b)) - so it is
        // attached first, and a return to the calculated mode only has to stop the microphone.
        if (visualizer == null || nativeAnalyzer == null) {
            releaseVisualizer();
            attachVisualizer(sessionId);
        }
        if (wantMic) {
            if (!isMicPipelineRunning()) startRadioMicPipeline();
        } else {
            stopMicAnalysis();
            stopRadioMicCapture();
        }
    }

    private void releaseVisualizer() {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (Throwable ignored) {}
        visualizer = null;
    }

    /** Creates the Visualizer on a session and starts its pipeline; session 0 if that one fails. */
    private void attachVisualizer(int sessionId) {
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
            // 🔴 Absolute levels. Owner, 14.09.2026: the main screen's calculated spectrum is a
            // measuring instrument - it shows the track as recorded, whatever the volume, with the
            // preset's effects laid on; decorations may fill the screen but should drop when the
            // music does. The default SCALING_MODE_NORMALIZED rescales every block to full scale, so
            // no level on screen meant anything. Measured on pink noise (-25.24 dBFS RMS in the
            // file): AS_PLAYED read -25.35 at volume 6 and -25.33 at volume 4 - the file's own level,
            // independent of the head unit's volume, which the MCU applies after this tap. The price
            // is 8 bits: that pink noise used 34 of 256 levels, so very quiet passages carry
            // quantisation noise in the top bands.
            int scaling = v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            if (scaling != Visualizer.SUCCESS) {
                Log.w(TAG, "Visualizer refused SCALING_MODE_AS_PLAYED (" + scaling + ") - levels are normalised");
            }

            // 🔴 The native analyser is the only one. A Java twin used to run on Visualizer callbacks
            // when the library failed to load - a second analyser with its own band plan, floor
            // and ballistics, which drifted from the native one (owner, 14.09.2026: one source of
            // truth for the whole app; removed). Without the library nothing is measured.
            if (!NativeAnalyzer.isAvailable()) {
                Log.e(TAG, "native analyser library not loaded - no spectrum");
                v.release();
                return;
            }
            // No capture listener at all: the 20 Hz callback is exactly what we are getting away
            // from. The polling thread reads the same buffer far more often.
            v.setEnabled(true);
            visualizer = v;
            int sampleRate = visualizerSampleRateHz();
            startNativeCapture(captureSize, sampleRate);
            Log.d(TAG, "AudioSpectrumEngine attached to session " + sessionId
                    + ", captureSize=" + captureSize + ", native polled capture at " + sampleRate
                    + " Hz (Visualizer reports " + v.getSamplingRate() + " mHz)");
        } catch (Throwable t) {
            Log.w(TAG, "AudioSpectrumEngine session " + sessionId + " failed: " + t);
            if (sessionId != 0) {
                try {
                    attachVisualizer(0);
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
    }
}
