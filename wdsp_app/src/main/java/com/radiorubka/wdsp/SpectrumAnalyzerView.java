package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * Live, continuous, log-frequency spectrum analyzer (20Hz-20kHz) drawn behind
 * {@link EqVisualizerView}, sourced from Android's global output mix via
 * {@link Visualizer} (audio session 0) - the same idea as a plugin analyzer
 * (FabFilter Pro-Q etc.): a smooth curve across many closely-spaced frequency
 * samples, not a small number of discrete bars.
 *
 * READ BEFORE TOUCHING THIS FILE - important caveats:
 *
 * 1. This captures audio BEFORE it leaves Android and reaches the MCU/amp
 *    that actually applies the real EQ (see McuService.sendToHardware()).
 *    It is a PRE-EQ signal - wDSP has no way to see the true, hardware-
 *    filtered output. The gain-reactive scaling in processFft() is a
 *    synthetic visual effect (real captured level shifted by the actual
 *    calculated EQ curve at that sample's own frequency - see
 *    AudioConfig.compositeResponseDb() - converted to a dB offset before
 *    normalizing), not a measurement of the filtered signal.
 * 2. Session-0 capture normally requires the signature-level
 *    CAPTURE_AUDIO_OUTPUT permission, which most ROMs restrict to system
 *    apps. Whether this head unit's ROM enforces that is UNTESTED - start()
 *    is wrapped so any failure just leaves the view blank instead of
 *    crashing the app. Check logcat for tag "wDSP_Spectrum" to see whether
 *    it actually attached.
 * 3. Audio sources that don't pass through Android's own mixer (e.g. Radio
 *    or AUX, if this head unit routes them straight to the amp in hardware)
 *    will never show up here - only whatever Android itself is mixing
 *    (media apps, Bluetooth A2DP, etc.) is visible to Visualizer.
 * 4. REF_MIN_DB / REF_MAX_DB below are rough starting guesses for mapping
 *    the raw FFT magnitude to a 0..1 curve height. They will very likely
 *    need tuning by eye once this runs on real hardware with real music.
 * 5. Each of the CURVE_SAMPLES frequency points is read via cubic Hermite
 *    (Catmull-Rom) interpolation through the 4 nearest real FFT bins' dB
 *    values (see processWaveform()), then smoothed along the frequency axis
 *    with a light fractional-octave-style pass (see FREQ_SMOOTH_RADIUS).
 *    That smoothing is a deliberate readability choice, not just cosmetic:
 *    this view exists so the driver can see which frequency to reach for on
 *    the EQ, and raw per-bin FFT detail (especially in the treble, where
 *    many display points span genuinely different real bins) is accurate but
 *    too noisy to read at a glance - real RTA/tuning tools (REW etc.) smooth
 *    for the same reason. If you ever want to match a reference analyzer's
 *    raw look exactly instead, shrink or drop FREQ_SMOOTH_RADIUS.
 * 6. The whole curve fades to invisible during silence and fades back in once real audio
 *    resumes (see SILENCE_HIDE_DB/displayAlpha/renderAlpha) - driven by the loudest point in
 *    the captured content, independent of the EQ-reactive shift, so it hides only when nothing
 *    is actually playing rather than whenever the EQ curve happens to sit near zero.
 */
public class SpectrumAnalyzerView extends View {

    // Pre-allocated buffers for manual FFT to prevent GC stutter
    private float[] hannWindow;
    private float[] fftReal;
    private float[] fftImag;
    private float[] magnitudes;

    // --- Low-band refinement: 2x accumulation + 50% STFT overlap ---
    // A second, independent FFT at 2x the HAL's own capture size, built by concatenating the
    // previous callback's chunk with the current one (see processWaveform()). Since the window
    // advances by exactly one HAL chunk each callback, consecutive windows share half their
    // samples (50% overlap) for free, and it's recomputed every callback - same cadence as the
    // main FFT, which is what avoids the old ring buffer's sawtooth (ballistics have to track the
    // actual update rate of whatever they're smoothing, not a throttled one). Only spliced in
    // below LOW_BAND_SPLICE_HZ - see processWaveform() step 4a.
    private static final float LOW_BAND_SPLICE_HZ = 100f;
    // A longer Hann window's raw FFT magnitude scales ~N for tones (coherent gain) but only
    // ~sqrt(N) for noise (incoherent summation) - at 2x the window size those two "correct" scale
    // factors are 0.5 (tone-exact) and 1/sqrt(2)=0.7071 (noise-exact), 3dB apart. This app is used
    // with pink noise (broadband) for tuning, not just tonal program content, so neither case
    // should be favored outright - this splits the gap evenly in dB (geometric mean of the two),
    // bounding BOTH cases to +-1.5dB instead of one being exact and the other off by the full 3dB.
    private static final float LOW_BAND_MAG_SCALE = 0.5946f;
    private float[] lowBandHann;
    private float[] lowBandReal;
    private float[] lowBandImag;
    private float[] lowBandMag;
    private float[] prevChunk; // previous callback's raw (unwindowed) samples, for the 50% overlap
    private int lowBandSize;

    private static final String TAG = "wDSP_Spectrum";

    // Frequency-axis smoothing width, in display samples on each side of the point being smoothed
    // (triangular kernel) - see the class-level doc's item 5 for why this exists. CURVE_SAMPLES is
    // spread evenly across ~10 octaves (20 samples/octave), so a radius of 4 (9 taps total) is
    // about a 0.45-octave-wide smoothing window - noticeably calmer than raw FFT detail while still
    // showing real broad peaks/dips clearly (ISO bands are roughly 1 octave apart, so this doesn't
    // blur distinct bands into each other).
    private static final int FREQ_SMOOTH_RADIUS = 4;

    // How many points across the width this curve is sampled at - the "resolution" of the
    // analyzer. High enough to look like a continuous curve rather than a bar graph.
    private static final int CURVE_SAMPLES = 200;

    // Each sample's frequency, precomputed once in init() via AudioConfig.frequencyAt() - the
    // exact same band-position-to-Hz mapping EqVisualizerView's curve uses, NOT an independent
    // 20Hz-20kHz log sweep. Those two are subtly different: the real per-band Slider columns in
    // eq_container are 16 evenly-weighted columns, so band 0's center sits at x=0.5/16 of the
    // width and band 15's at x=15.5/16, not at the true 0%/100% a plain log(20..20000) sweep
    // would place them at. Sampling via frequencyAt() at the same "(x/width)*16 - 0.5" band
    // position keeps this curve's x-axis pixel-aligned with the EQ curve/grid/sliders drawn
    // above it - a plain log sweep looked close but visibly drifted out of alignment with them
    // away from the center of the view.
    private final float[] centerHz = new float[CURVE_SAMPLES]; // precomputed once in init()

    // --- Easter egg: radiation icon when the bass level goes nuts ---
    // Fades in near the bass frequencies whenever the loudest bass sample rises past
    private static final float BASS_NUKE_CUTOFF_HZ = 100f; // samples below this count as "bass" for this check
    private static final float BASS_NUKE_THRESHOLD = 1.25f;
    private static final float BASS_NUKE_ALPHA_RISE = 0.3f;
    private static final float BASS_NUKE_ALPHA_FALL = 0.05f;
    private int bassNukeSampleEnd; // last curve sample index under BASS_NUKE_CUTOFF_HZ, computed once in init()
    private float bassNukeAlpha = 0f;
    private Bitmap bassNukeIcon;
    private Paint bassNukePaint;
    private final Rect bassNukeSrcRect = new Rect();
    private final RectF bassNukeDstRect = new RectF();

    // --- Pink noise tilt compensation (mode switch) ---
    // Real music approximates pink noise: roughly equal energy per OCTAVE,
    // which - because this view measures FFT-bin magnitude, i.e. energy per
    // Hz, not per octave - shows up as a downward slope of about 3dB/octave
    // toward the treble (confirmed: a white-noise test signal, which IS flat
    // per Hz, renders as flat; real music does not). That's a genuine
    // property of the signal, not a measurement bug. Enabling PINK_NOISE_TILT
    // adds a compensating UPWARD slope of TILT_DB_PER_OCTAVE per octave
    // above/below TILT_REF_HZ, so pink-noise-like content (most real music)
    // reads roughly flat across the curve instead of tapering off after the
    // mid-bass. Trade-off: with this on, a true white-noise signal will now
    // read as tilting UP toward the treble instead of flat, since the display
    // is calibrated against the pink reference instead of the Hz-linear one -
    // this is a deliberate choice, not a bug, matching how RTA/tuning tools
    // (REW etc.) treat pink noise as the "flat" reference rather than white
    // noise. Compile-time switch for now - flip to true and rebuild to try
    // it, no UI toggle yet.
    private static final boolean PINK_NOISE_TILT = true;
    private static final float TILT_DB_PER_OCTAVE = 3f; // the standard pink-noise correction figure
    private static final float TILT_REF_HZ = 1000f; // pivot frequency - doesn't change the overall look much, ATTENUATION_DB absorbs any net offset

    private static final float REF_MIN_DB = -20f;   // magnitude at/below this reads as silence
    private static final float REF_MAX_DB = 60f;  // magnitude at/above this reads as full-height
    private static final float RISE_SMOOTHING = 1f; // attack
    private static final float FALL_SMOOTHING = 0.15f; // release
    // Both apply only to smoothedContentDb[] (real captured audio), not to
    // the gain/attenuation offset - see processFft() for why that split
    // exists.

    // Uniformly pulls every sample down by this many dB before normalization,
    // independent of REF_MAX_DB. The difference: raising REF_MAX_DB also
    // widens the REF_MIN_DB..REF_MAX_DB window, which compresses/stretches
    // the whole visible dynamic range as a side effect. ATTENUATION_DB just
    // shifts everything down by a flat amount without touching that window's
    // size - use this when the curve is simply too loud/tall overall but the
    // existing compression (how much a given dB change moves it) looks right
    // as-is.
    private static final float ATTENUATION_DB = 0f;

    // How many dB above REF_MIN_DB a sample's gain reactivity fades in over,
    // instead of switching on the instant rawDb ticks above REF_MIN_DB. A
    // hard on/off cutoff there flickers: the underlying magnitude is an 8-bit
    // integer scale, so a near-silent bin bounces between reading exactly 0
    // and reading 1 from one capture to the next just from quantization
    // noise, and each bounce would snap the gain shift fully on/off. With a
    // fade, that same noise only nudges the shift's strength by a hair
    // instead of popping it from 0% to 100%.
    private static final float SILENCE_FADE_DB = 3f;

    // Peak captured content (dB, real signal only - before EQ shift/tilt/attenuation) below
    // which the whole curve fades toward invisible, over SILENCE_HIDE_FADE_DB of range - see
    // displayAlpha/renderAlpha for how that fade itself is smoothed over time. Uses the loudest
    // point across the whole curve each capture, so a single quiet-but-present tone still
    // counts as "not silent" even if most of the spectrum is empty.
    private static final float SILENCE_HIDE_DB = 2f;
    private static final float SILENCE_HIDE_FADE_DB = 4f;
    // Deliberately asymmetric, same spirit as RISE_SMOOTHING/FALL_SMOOTHING above but paced for
    // an on/off visibility fade rather than per-sample bar motion: appear quickly once real
    // audio starts, but fade out slowly (over roughly a second) so a brief pause between tracks
    // or a quiet passage in a song doesn't flicker the whole curve in and out.
    private static final float ALPHA_RISE_SMOOTHING = 0.4f;
    private static final float ALPHA_FALL_SMOOTHING = 0.08f;

    private static final float band_mult = 2f;

    // Same measured-not-hardcoded slider bounds as EqVisualizerView (see its setSliderBounds()
    // javadoc) - kept in sync by MainActivity so the curve is bounded to the same plot area as
    // the EQ curve/grid drawn on top and can never grow up into the frequency-label row above
    // it, regardless of eq_container's actual per-band row weights.
    private float topOffsetRatio = 0.17391304f;
    private float drawHeightRatio = 0.82608696f;

    private Visualizer visualizer;

    // On stock QF/K706 policies (no BitPerfect-style Magisk module reconfiguring primary vs fast
    // output routing - see SessionResolver's doc), SessionResolver has to resolve a per-track
    // session instead of the global output mix (session 0). That pre-mix capture point measures
    // noticeably quieter than the post-mix session-0 tap - this boost compensates, applied only
    // when attached to a non-zero session (see attachVisualizer()), so session-0 capture is
    // unaffected. Starting estimate from eyeballing a real K706 without BitPerfect (level sat
    // near the bottom of the REF_MIN_DB..REF_MAX_DB window instead of its center) - tune by eye.
    private static final float NON_PRIMARY_SESSION_GAIN_DB = 30f;
    private float sessionGainLinear = 1f;

    private final float[] rawLevels = new float[CURVE_SAMPLES];     // this capture's final level (content ballistics + instant gain/attenuation), 0..1
    private final float[] displayLevels = new float[CURVE_SAMPLES]; // == rawLevels as of the last audio capture; kept separate only for the prevLevels/frameCallback interpolation below
    private final int[] gains = new int[AudioConfig.NUM_BANDS];     // raw EQ slider values, 0..12 (6 = 0dB) - fed into AudioConfig.compositeResponseDb()

    // --- Loudness/sub reactive shift (independently togglable, no UI switches yet) ---
    // Whether the RTA's gain-reactive shift (see eqDb in processWaveform()) also reflects the
    // loudness-correction preview and the current sub level, on top of the raw manual EQ curve it
    // already reacts to. Two separate flags (not one) since they're conceptually independent -
    // e.g. you might want the sub level always reflected but the loudness preview only sometimes.
    // The data below is fed in unconditionally by MainActivity either way; these flags only gate
    // whether processWaveform() actually uses it. Edit the defaults here, or call
    // setLoudnessReactive()/setSubReactive() once real UI switches exist for each.
    private boolean loudnessReactiveEnabled = true;
    private boolean subReactiveEnabled = true;
    private boolean bassReactiveEnabled = true;
    private final float[] loudnessCorrectionGains = new float[AudioConfig.NUM_BANDS]; // same baseline-6 delta convention as EqVisualizerView's
    private boolean loudnessCorrectionActive = false;
    private float subLevelCutoffHz = 80f;
    private float subLevelGainDb = 0f;
    // Front "Bass Boost" stage (see AudioConfig.bassShapingResponseDb()) - same data
    // EqVisualizerView/FmVisualizerView's front curve already gets from MainActivity.
    private float bassFilterHz = 20f, bassBoostFreqHz = 0f, bassBoostGainDb = 0f;
    // Fast-attack/slow-release ballistics live HERE now, applied to the raw
    // captured dB per sample before gain/attenuation are added - see the
    // ballistics comment inside processFft() for why they moved off the
    // final (gain-inclusive) value.
    private final float[] smoothedContentDb = new float[CURVE_SAMPLES];

    // Working buffers for the frequency-axis smoothing pass (see FREQ_SMOOTH_RADIUS) - instDb
    // holds each sample's raw Catmull-Rom-interpolated dB before smoothing, spatialDb the result
    // after the triangular kernel pass, which then feeds the ballistics below.
    private final float[] instDb = new float[CURVE_SAMPLES];
    private final float[] spatialDb = new float[CURVE_SAMPLES];

    // --- 60fps render interpolation ---
    // Audio captures only arrive at ~20Hz (or whatever the device reports),
    // so onDraw() would otherwise visibly step between values instead of
    // animating smoothly. A Choreographer loop redraws every vsync and
    // interpolates between the last two captured value-sets based on how far
    // through the current capture interval we are, independent of the audio
    // capture rate.
    private final float[] prevLevels = new float[CURVE_SAMPLES];   // displayLevels as of the previous capture
    private final float[] renderLevels = new float[CURVE_SAMPLES]; // interpolated values onDraw() actually reads
    // Whole-curve visibility fade (0 = fully hidden, 1 = fully shown) - same prev/display/
    // render + Choreographer-interpolation pattern as the level arrays above, just a single
    // scalar instead of one per sample. Computed/smoothed once per capture in processFft() (see
    // SILENCE_HIDE_DB), then interpolated at 60fps here like everything else.
    private float prevAlpha = 0f;
    private float displayAlpha = 0f;
    private float renderAlpha = 0f;
    private long lastCaptureTime = 0;
    private long captureIntervalMs = 50; // running estimate of the gap between captures, self-adjusts in processFft()
    private boolean frameCallbackActive = false;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameCallbackActive) return;
            long elapsed = System.currentTimeMillis() - lastCaptureTime;
            float t = captureIntervalMs > 0 ? Math.min(1f, elapsed / (float) captureIntervalMs) : 1f;
            for (int i = 0; i < CURVE_SAMPLES; i++) {
                renderLevels[i] = prevLevels[i] + (displayLevels[i] - prevLevels[i]) * t;
            }
            renderAlpha = prevAlpha + (displayAlpha - prevAlpha) * t;
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    // Same 6-color warm(bass)->cool(treble) palette used across EqVisualizerView/
    // FmVisualizerView, spread evenly left-to-right here since there are no discrete bands to
    // anchor gradient positions to anymore.
    private int[] spectrumColors;
    private int colorFill;

    private Paint linePaint;
    private Paint fillPaint;
    private final Path fullPath = new Path();
    private final Path fillPath = new Path();

    // Cache for gradient parameters to avoid reallocation every frame - same idea as
    // EqVisualizerView.
    private float lastGradW = -1;
    private float lastDrawStartY = -1;
    private float lastGridBottom = -1;

    public SpectrumAnalyzerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getContext().getResources().getDisplayMetrics().density;

        spectrumColors = new int[]{
                ContextCompat.getColor(getContext(), R.color.btn_delete_bg),
                ContextCompat.getColor(getContext(), R.color.btn_import_bg),
                ContextCompat.getColor(getContext(), R.color.btn_export_bg),
                ContextCompat.getColor(getContext(), R.color.btn_rename_bg),
                ContextCompat.getColor(getContext(), R.color.btn_add_bg),
                ContextCompat.getColor(getContext(), R.color.btn_auto_bg)

//                ContextCompat.getColor(getContext(), R.color.text_theme_aware_3),
//                ContextCompat.getColor(getContext(), R.color.text_theme_aware_3),
//                ContextCompat.getColor(getContext(), R.color.text_theme_aware_3),
//                ContextCompat.getColor(getContext(), R.color.text_theme_aware_3),
//                ContextCompat.getColor(getContext(), R.color.text_theme_aware_3),
//                ContextCompat.getColor(getContext(), R.color.text_theme_aware_3)
        };
        colorFill = ContextCompat.getColor(getContext(), R.color.visualizer_fill);

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        // Alpha (base 170, further scaled by the silence-hide fade) is set fresh every frame in
        // onDraw() instead of here.

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        // Sample center frequencies - see centerHz's declaration for why this goes through
        // AudioConfig.frequencyAt() (matching the EQ curve/sliders' x-axis) rather than an
        // independent log sweep. Fixed for the view's lifetime, computed once here.
        for (int j = 0; j < CURVE_SAMPLES; j++) {
            float t = (j + 0.5f) / CURVE_SAMPLES * AudioConfig.NUM_BANDS - 0.5f;
            // Extrapolate frequencies for values outside [0, 15] range to avoid clamping shelf at the edge
            if (t < 0f) {
                float ratio = AudioConfig.BAND_CENTER_HZ[1] / AudioConfig.BAND_CENTER_HZ[0];
                centerHz[j] = (float) (AudioConfig.BAND_CENTER_HZ[0] * Math.pow(ratio, t));
            } else if (t > AudioConfig.NUM_BANDS - 1) {
                // Get the frequency of your very last EQ band
                float lastBandFreq = AudioConfig.BAND_CENTER_HZ[AudioConfig.NUM_BANDS - 1];

                // Define the maximum frequency you want the right edge of the graph to display
                float maxFreq = 22000f; // Change this to 32000f, 48000f, etc. depending on your hardware/needs

                // Calculate where this specific sample falls in the "overhang" region
                float tStart = AudioConfig.NUM_BANDS - 1;
                float tEnd = ((CURVE_SAMPLES - 0.5f) / CURVE_SAMPLES) * AudioConfig.NUM_BANDS - 0.5f;
                float fraction = (t - tStart) / (tEnd - tStart);

                // Smoothly extrapolate up to maxFreq logarithmically to match the rest of the curve style
                centerHz[j] = (float) (lastBandFreq * Math.pow(maxFreq / lastBandFreq, fraction));
            } else {
                centerHz[j] = AudioConfig.frequencyAt(t);
            }
        }

        // nothing important :-]
        bassNukeSampleEnd = 0;
        for (int j = 0; j < CURVE_SAMPLES; j++) {
            if (centerHz[j] < BASS_NUKE_CUTOFF_HZ) bassNukeSampleEnd = j; else break;
        }
        bassNukeIcon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_bass_radiation);
        bassNukePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) gains[i] = 6; // default: 0 dB on every band
    }

    /**
     * Feed the current 16 EQ band gain slider values (0..12, 6 = 0dB) in for the EQ-reactive
     * scaling - see AudioConfig.compositeResponseDb() usage in processFft().
     */
    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
        // No invalidate() here on purpose - the Choreographer frame callback
        // (see frameCallback field) already redraws every vsync while
        // running; no need to force an extra one on every slider drag tick.
    }

    /** Enables/disables the RTA's loudness-correction reactive shift - see loudnessReactiveEnabled's declaration. */
    public void setLoudnessReactive(boolean enabled) {
        loudnessReactiveEnabled = enabled;
    }

    /** Enables/disables the RTA's sub-level reactive shift, independent of setLoudnessReactive() - see subReactiveEnabled's declaration. */
    public void setSubReactive(boolean enabled) {
        subReactiveEnabled = enabled;
    }

    /** Enables/disables the RTA's front Bass Boost reactive shift, independent of the other two - see bassReactiveEnabled's declaration. */
    public void setBassReactive(boolean enabled) {
        bassReactiveEnabled = enabled;
    }

    /**
     * Feeds the same loudness-correction curve data as EqVisualizerView.setLoudnessCorrection()
     * (baseline-6 delta convention) - only applied to the RTA's own shift while
     * loudnessReactiveEnabled is true, but accepted unconditionally so that's a one-flag switch.
     */
    public void setLoudnessCorrection(float[] correctionGains, boolean active) {
        loudnessCorrectionActive = active;
        if (active) System.arraycopy(correctionGains, 0, this.loudnessCorrectionGains, 0, AudioConfig.NUM_BANDS);
    }

    /** Feeds the same sub cutoff/gain as EqVisualizerView.setSubFilter() - see subReactiveEnabled. */
    public void setSubLevel(float cutoffHz, float gainDb) {
        subLevelCutoffHz = cutoffHz;
        subLevelGainDb = gainDb;
    }

    /** Feeds the same front Bass Boost data as EqVisualizerView/FmVisualizerView's setBassShaping() - see bassReactiveEnabled. */
    public void setBassShaping(float filterHz, float boostFreqHz, float boostGainDb) {
        bassFilterHz = filterHz;
        bassBoostFreqHz = boostFreqHz;
        bassBoostGainDb = boostGainDb;
    }

    /** See EqVisualizerView.setSliderBounds() - same idea, kept in sync with the same measurement. */
    public void setSliderBounds(float topRatio, float heightRatio) {
        topOffsetRatio = topRatio;
        drawHeightRatio = heightRatio;
        // No invalidate() here either, for the same reason as setGains() above.
    }

    /**
     * Resolves the real audio session (see SessionResolver's doc - session 0 is silent on stock
     * QF/K706 policies, which route media to the "fast" mixPort instead of "primary") and attaches
     * to it asynchronously. Safe to call repeatedly (no-ops if already running or already
     * resolving). Call from onResume() once RECORD_AUDIO is granted.
     */
    public void start() {
        if (visualizer != null) return;
        SessionResolver resolver = SessionResolver.getInstance(getContext());
        resolver.start();
        resolver.resolveAsync(null, sessionId -> post(() -> attachVisualizer(sessionId <= 0 ? 0 : sessionId)));
    }

    /**
     * Attempts to attach to the given audio session and start FFT capture. Any failure (missing
     * permission, ROM restriction, session gone stale between resolve and attach, etc.) is caught
     * and logged - the view simply stays blank rather than taking the app down with it. Falls back
     * to session 0 once if the resolved session fails to attach at all.
     */
    private void attachVisualizer(int sessionId) {
        if (visualizer != null) return;
        sessionGainLinear = (sessionId == 0) ? 1f : (float) Math.pow(10.0, NON_PRIMARY_SESSION_GAIN_DB / 20.0);
        try {
            Visualizer v = new Visualizer(sessionId);

            // Absolute levels, not Android's default per-block auto-normalization (which rescales
            // every capture block to full scale regardless of the track's actual level) - without
            // this the displayed spectrum's height says nothing about how loud the content is.
            int scaling = v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            if (scaling != Visualizer.SUCCESS) {
                Log.w(TAG, "Visualizer refused SCALING_MODE_AS_PLAYED (" + scaling + ") - levels are normalized");
            }

            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = range[1]; // use the device max for best low-frequency bin resolution
            if (captureSize < range[0]) captureSize = range[0];
            v.setCaptureSize(captureSize);

            // --- Initialize windowing and FFT structures ---
            hannWindow = new float[captureSize];
            for (int i = 0; i < captureSize; i++) {
                // Compute Hann window coefficients: 0.5 * (1 - cos(2*pi*i / (N-1)))
                hannWindow[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (captureSize - 1))));
            }
            fftReal = new float[captureSize];
            fftImag = new float[captureSize];
            magnitudes = new float[captureSize / 2 + 1];

            // --- Low-band refinement buffers (see LOW_BAND_SPLICE_HZ's doc) ---
            lowBandSize = captureSize * 2;
            lowBandHann = new float[lowBandSize];
            for (int i = 0; i < lowBandSize; i++) {
                lowBandHann[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (lowBandSize - 1))));
            }
            lowBandReal = new float[lowBandSize];
            lowBandImag = new float[lowBandSize];
            lowBandMag = new float[lowBandSize / 2 + 1];
            prevChunk = new float[captureSize];

            int rate = Visualizer.getMaxCaptureRate();
            if (rate <= 0) rate = 20000;

            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    // Route the raw PCM waveform to our custom processing pipeline
                    processWaveform(waveform, samplingRate);
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    // Unused now that we are windowing manually
                }
            }, rate, true, false); // Crucial: true for waveform, false for FFT

            v.setEnabled(true);
            visualizer = v;
            Log.d(TAG, "Spectrum analyzer attached to session " + sessionId + ", captureSize=" + captureSize);

            if (!frameCallbackActive) {
                frameCallbackActive = true;
                Choreographer.getInstance().postFrameCallback(frameCallback);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Spectrum analyzer session " + sessionId + " unavailable: " + t);
            visualizer = null;
            if (sessionId != 0) attachVisualizer(0);
        }
    }

    // Small floor so a true-zero bin reads as a large-but-finite negative dB instead of -Infinity,
    // without flattening genuinely quiet (but present) content into a near-linear response the way
    // a "+1" additive floor would - see processWaveform()'s dB-domain quadratic interpolation.
    private static final float DB_FLOOR_MAG = 0.1f;

    private static float dbMag(float mag) {
        return (float) (20 * Math.log10(mag + DB_FLOOR_MAG));
    }

    private void computeFft(float[] real, float[] imag) {
        int n = real.length;

        // Bit-reversal permutation
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i < j) {
                float temp = real[i]; real[i] = real[j]; real[j] = temp;
                temp = imag[i]; imag[i] = imag[j]; imag[j] = temp;
            }
            int m = n >> 1;
            while (m >= 1 && j >= m) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }

        // Cooley-Tukey decimation-in-time
        for (int size = 2; size <= n; size *= 2) {
            int halfSize = size / 2;
            double tabStep = 2.0 * Math.PI / size;
            for (int i = 0; i < n; i += size) {
                for (int k = 0; k < halfSize; k++) {
                    double angle = -k * tabStep;
                    float wr = (float) Math.cos(angle);
                    float wi = (float) Math.sin(angle);

                    int edge = i + k + halfSize;
                    float tr = real[edge] * wr - imag[edge] * wi;
                    float ti = real[edge] * wi + imag[edge] * wr;

                    real[edge] = real[i + k] - tr;
                    imag[edge] = imag[i + k] - ti;
                    real[i + k] += tr;
                    imag[i + k] += ti;
                }
            }
        }
    }

    /** Stops capture and releases the Visualizer. Call from onPause()/onDestroy(). */
    public void stop() {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (Throwable t) {
            Log.w(TAG, "Error releasing spectrum analyzer: " + t);
        } finally {
            visualizer = null;
        }

        frameCallbackActive = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    // Cubic Hermite (Catmull-Rom) interpolation through the 4 nearest real FFT bins at freqHz -
    // factored out so both the main and low-band FFTs (see lowBandMag's doc) can share it,
    // parameterized only by which magnitude array/size they're reading from.
    private float hermiteDb(float[] mags, int numBins, int fftSize, float sampleRateHz, float freqHz) {
        float binPos = freqHz * fftSize / sampleRateHz;
        binPos = Math.max(1f, Math.min(numBins - 1, binPos));
        int k = Math.max(1, Math.min(numBins - 2, (int) Math.floor(binPos)));
        float t = binPos - k;

        float p0 = dbMag(mags[Math.max(0, k - 1)]);
        float p1 = dbMag(mags[k]);
        float p2 = dbMag(mags[Math.min(numBins - 1, k + 1)]);
        float p3 = dbMag(mags[Math.min(numBins - 1, k + 2)]);

        float m1 = (p2 - p0) * 0.5f; // tangent at p1
        float m2 = (p3 - p1) * 0.5f; // tangent at p2

        float t2 = t * t;
        float t3 = t2 * t;
        float h00 = 2f * t3 - 3f * t2 + 1f;
        float h10 = t3 - 2f * t2 + t;
        float h01 = -2f * t3 + 3f * t2;
        float h11 = t3 - t2;

        return h00 * p1 + h10 * m1 + h01 * p2 + h11 * m2;
    }

    private void processWaveform(byte[] waveform, int samplingRateMilliHz) {
        int captureLen = waveform.length;
        if (captureLen < 4 || fftReal == null || fftReal.length != captureLen) return;
        int n = captureLen;
        int numBins = n / 2;
        float sampleRateHz = samplingRateMilliHz / 1000f;

        // 1. Prepare Data: Convert PCM bytes to Windowed Floats
        for (int i = 0; i < n; i++) {
            // Convert unsigned 8-bit PCM (0..255) to normalized float (-1..1)
            float sample = ((float) (waveform[i] & 0xFF) - 128f) / 128f;
            // Apply Hann window to eliminate spectral leakage (the "jumps")
            fftReal[i] = sample * hannWindow[i];
            fftImag[i] = 0f;
        }

        // 2. Execute the Cooley-Tukey FFT
        computeFft(fftReal, fftImag);

        // 3. Calculate Magnitudes
        for (int bin = 0; bin <= numBins; bin++) {
            float r = fftReal[bin];
            float im = fftImag[bin];
            magnitudes[bin] = (float) Math.sqrt(r * r + im * im);
        }

        // See NON_PRIMARY_SESSION_GAIN_DB's doc - a no-op (sessionGainLinear == 1f) whenever
        // attached to session 0.
        if (sessionGainLinear != 1f) {
            for (int bin = 0; bin <= numBins; bin++) magnitudes[bin] *= sessionGainLinear;
        }

        // 3b. Low-band refinement: build the 2x-length overlapped window from the previous
        // callback's raw samples + this callback's raw samples (see lowBandReal's doc), run its
        // own FFT, and compute its magnitudes - spliced in below LOW_BAND_SPLICE_HZ in step 4a.
        int lowBandNumBins = lowBandSize / 2;
        for (int i = 0; i < n; i++) {
            float oldSample = prevChunk[i];
            lowBandReal[i] = oldSample * lowBandHann[i];
            lowBandImag[i] = 0f;

            float newSample = ((float) (waveform[i] & 0xFF) - 128f) / 128f;
            lowBandReal[n + i] = newSample * lowBandHann[n + i];
            lowBandImag[n + i] = 0f;

            prevChunk[i] = newSample;
        }
        computeFft(lowBandReal, lowBandImag);
        for (int bin = 0; bin <= lowBandNumBins; bin++) {
            float r = lowBandReal[bin];
            float im = lowBandImag[bin];
            lowBandMag[bin] = (float) Math.sqrt(r * r + im * im) * LOW_BAND_MAG_SCALE;
        }
        if (sessionGainLinear != 1f) {
            for (int bin = 0; bin <= lowBandNumBins; bin++) lowBandMag[bin] *= sessionGainLinear;
        }

        float peakContentDb = -999f;

        // 4a. Sample the spectral envelope at each curve point via cubic Hermite (Catmull-Rom)
        // interpolation through the 4 nearest real bins, evaluated at that sample's fractional bin
        // position. This passes exactly through the real bin values with a continuously-matching
        // slope across every bin boundary - unlike fitting a separate parabola per bin (which
        // disagreed in slope at the handoff between segments, producing small overshoot "ears" next
        // to real peaks that then needed a post-hoc clamp, which in turn clipped genuine peak
        // reconstruction too), this is smooth by construction and needs no clamp.
        for (int j = 0; j < CURVE_SAMPLES; j++) {
            float freqHz = centerHz[j];
            instDb[j] = (freqHz < LOW_BAND_SPLICE_HZ)
                    ? hermiteDb(lowBandMag, lowBandNumBins, lowBandSize, sampleRateHz, freqHz)
                    : hermiteDb(magnitudes, numBins, n, sampleRateHz, freqHz);
        }

        // 4b. Frequency-axis (fractional-octave-style) smoothing for readability - see
        // FREQ_SMOOTH_RADIUS's declaration for why. CURVE_SAMPLES is uniformly log-spaced, so a
        // fixed-width triangular kernel over neighboring samples approximates constant-fractional-
        // octave smoothing for free.
        for (int j = 0; j < CURVE_SAMPLES; j++) {
            float sum = 0f, weight = 0f;
            for (int d = -FREQ_SMOOTH_RADIUS; d <= FREQ_SMOOTH_RADIUS; d++) {
                int idx = j + d;
                if (idx < 0 || idx >= CURVE_SAMPLES) continue;
                float w = FREQ_SMOOTH_RADIUS + 1 - Math.abs(d); // triangular kernel
                sum += instDb[idx] * w;
                weight += w;
            }
            spatialDb[j] = sum / weight;
        }

        // 4c. Ballistics, EQ reactivity and normalization per sample.
        for (int j = 0; j < CURVE_SAMPLES; j++) {
            float freqHz = centerHz[j];
            float rawDb = spatialDb[j];

            // --- Apply Ballistics ---
            float prevContentDb = smoothedContentDb[j];
            float contentSmoothing = rawDb > prevContentDb ? RISE_SMOOTHING : FALL_SMOOTHING;
            smoothedContentDb[j] = prevContentDb + (rawDb - prevContentDb) * contentSmoothing;
            peakContentDb = Math.max(peakContentDb, smoothedContentDb[j]);

            // --- Presence Gate (CRUCIAL FIX: Use smoothed value instead of rawDb) ---
            // This prevents raw frame-to-frame noise from modulating the EQ and Tilt gains downstream.
            float presence = (smoothedContentDb[j] - REF_MIN_DB) / SILENCE_FADE_DB;
            presence = Math.max(0f, Math.min(1f, presence));

            // --- EQ and Compensation ---
            float eqDb = AudioConfig.compositeResponseDb(gains, freqHz);
            if (loudnessReactiveEnabled && loudnessCorrectionActive) {
                // loudnessCorrectionGains is already delta-encoded (baseline 6 = no change), so
                // this composes directly on top of the raw manual EQ's own shift above.
                eqDb += AudioConfig.compositeResponseDb(loudnessCorrectionGains, freqHz);
            }
            if (subReactiveEnabled) {
                // Clamped to non-negative: subFilterResponseDb() models the LPF's actual rolloff
                // (deeply negative well above cutoff), which is correct for drawing the sub's own
                // dedicated curve but wrong to add directly here - a turned-up sub doesn't SUPPRESS
                // treble in real life, it just has no effect there, so this should taper to exactly
                // 0 above the passband instead of subtracting tens of dB from the whole RTA.
                eqDb += Math.max(0f, AudioConfig.subFilterResponseDb(freqHz, subLevelCutoffHz, AudioConfig.SUB_FILTER_ORDER, subLevelGainDb));
            }
            if (bassReactiveEnabled) {
                // NOT clamped, unlike the sub term above - that clamp exists because
                // subFilterResponseDb()'s rolloff extends (incorrectly, for this purpose) into
                // frequencies the sub has no real effect on. Here both terms are already 0
                // wherever the filter/shelf has no real effect (well above the HPF cutoff or the
                // boost's own corner), and the HPF's cut is a genuine attenuation of the real
                // signal at low frequencies - it should suppress the RTA there, not be floored.
                eqDb += AudioConfig.bassShapingResponseDb(freqHz, bassFilterHz, bassBoostFreqHz, bassBoostGainDb);
            }
            float db = smoothedContentDb[j] + presence * eqDb * band_mult;

            if (PINK_NOISE_TILT) {
                float octaves = (float) (Math.log(freqHz / TILT_REF_HZ) / Math.log(2));
                db += presence * TILT_DB_PER_OCTAVE * octaves;
            }

            db -= ATTENUATION_DB;

            // --- Final Normalization ---
            // Neither end is clamped here - a hot transient can now rise above the nominal 1.0
            // "plot top" line into the label-row headroom above it (see onDraw()'s clip rect,
            // extended up to y=0 so this is actually visible instead of being cut off immediately)
            // instead of flattening into a hard ceiling, the same way quiet content already dips
            // below the baseline instead of flattening at 0.
            float normalized = (db - REF_MIN_DB) / (REF_MAX_DB - REF_MIN_DB);
            rawLevels[j] = normalized;
        }

        // 5. Update Visibility Alpha
        float targetAlpha = (peakContentDb - SILENCE_HIDE_DB) / SILENCE_HIDE_FADE_DB;
        targetAlpha = Math.max(0f, Math.min(1f, targetAlpha));
        float alphaSmoothing = targetAlpha > displayAlpha ? ALPHA_RISE_SMOOTHING : ALPHA_FALL_SMOOTHING;
        prevAlpha = displayAlpha;
        displayAlpha = displayAlpha + (targetAlpha - displayAlpha) * alphaSmoothing;

        // 6. Visual Interpolation Timing
        long now = System.currentTimeMillis();
        if (lastCaptureTime != 0) {
            long observed = now - lastCaptureTime;
            if (observed > 0) captureIntervalMs = observed;
        }
        System.arraycopy(displayLevels, 0, prevLevels, 0, CURVE_SAMPLES);
        lastCaptureTime = now;
        System.arraycopy(rawLevels, 0, displayLevels, 0, CURVE_SAMPLES);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float totalH = getHeight();
        if (w == 0 || totalH == 0) return;

        // Nothing to draw while faded out for silence (see SILENCE_HIDE_DB) - skip the path
        // build entirely rather than drawing fully-transparent geometry every frame.
        int globalAlpha = Math.round(255 * Math.max(0f, Math.min(1f, renderAlpha)));
        if (globalAlpha <= 0) return;

        float topArea = totalH * topOffsetRatio;
        float drawHeight = totalH * drawHeightRatio;
        float gridBottom = topArea + drawHeight;

        fullPath.reset();
        for (int j = 0; j < CURVE_SAMPLES; j++) {
            float x = (j + 0.5f) / CURVE_SAMPLES * w;
            float y = gridBottom - renderLevels[j] * drawHeight;
            if (j == 0) fullPath.moveTo(x, y); else fullPath.lineTo(x, y);
        }

        fillPath.set(fullPath);
        fillPath.lineTo(w, gridBottom);
        fillPath.lineTo(0, gridBottom);
        fillPath.close();

        // Update gradients (horizontal band-color for line & fill, vertical fade for fill) &
        // draw - same technique as EqVisualizerView, cached so it's only rebuilt when the
        // geometry actually changes.
        if (w != lastGradW || topArea != lastDrawStartY || gridBottom != lastGridBottom) {
            int fillAlpha = Color.alpha(colorFill);
            int[] fillColors = new int[spectrumColors.length];
            for (int c = 0; c < spectrumColors.length; c++) {
                int col = spectrumColors[c];
                fillColors[c] = Color.argb(fillAlpha, Color.red(col), Color.green(col), Color.blue(col));
            }

            linePaint.setShader(new LinearGradient(0, 0, w, 0, spectrumColors, null, Shader.TileMode.CLAMP));

            Shader fillColorShader = new LinearGradient(0, 0, w, 0, fillColors, null, Shader.TileMode.CLAMP);
            Shader fadeMaskShader = new LinearGradient(0, topArea, 0, gridBottom, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            fillPaint.setShader(new ComposeShader(fillColorShader, fadeMaskShader, PorterDuff.Mode.DST_IN));

            lastGradW = w;
            lastDrawStartY = topArea;
            lastGridBottom = gridBottom;
        }

        // Base alphas (170 for the line, 255/opaque for the fill - its actual per-pixel alpha
        // comes from colorFill's own alpha baked into the shader above) scaled by the silence
        // fade - Paint alpha multiplies with whatever the shader outputs, so this dims both
        // uniformly without needing to touch the gradients themselves.
        linePaint.setAlpha(globalAlpha * 170 / 255);
        fillPaint.setAlpha(globalAlpha);

        // Clip before drawing - rawLevels[] is deliberately unclamped on both ends now (see the
        // normalization comment above), so quiet content trails off below the baseline and loud
        // transients rise above the nominal plot top, both needing an explicit clip rather than
        // flattening at a hard line. The bottom still stops at gridBottom (the view's own bounds
        // don't reliably end exactly there - there can be padding/other chrome below it - so
        // without this it was visibly drawing outside the EQ container's plot area). The top now
        // goes all the way to this view's own edge (y=0) instead of the old topArea boundary,
        // giving a hot transient room to rise up underneath the frequency-label row drawn on top
        // by EqVisualizerView instead of clipping flat right at the plot's nominal ceiling.
        canvas.save();
        canvas.clipRect(0, 0, w, gridBottom);
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(fullPath, linePaint);
        canvas.restore();

        // also nothing important
        float bassPeak = -999f;
        for (int j = 0; j <= bassNukeSampleEnd; j++) bassPeak = Math.max(bassPeak, renderLevels[j]);
        float bassNukeTarget = bassPeak > BASS_NUKE_THRESHOLD ? 1f : 0f;
        bassNukeAlpha += (bassNukeTarget - bassNukeAlpha) * (bassNukeTarget > bassNukeAlpha ? BASS_NUKE_ALPHA_RISE : BASS_NUKE_ALPHA_FALL);
        if (bassNukeAlpha > 0.01f && bassNukeIcon != null) {
            float iconSize = totalH * 0.22f;
            float bassMidX = ((bassNukeSampleEnd / 2f) + 0.5f) / CURVE_SAMPLES * w;
            bassNukeSrcRect.set(0, 0, bassNukeIcon.getWidth(), bassNukeIcon.getHeight());
            bassNukeDstRect.set(bassMidX - iconSize / 2f, topArea - iconSize / 2f,
                    bassMidX + iconSize / 2f, topArea + iconSize / 2f);
            bassNukePaint.setAlpha(Math.round(255 * Math.min(1f, bassNukeAlpha)));
            canvas.drawBitmap(bassNukeIcon, bassNukeSrcRect, bassNukeDstRect, bassNukePaint);
        }
        // No explicit invalidate() needed here - the Choreographer frameCallback already redraws
        // every vsync while the visualizer is active, same as the rest of this view.
    }
}
