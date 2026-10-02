package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

public class EqVisualizerView extends View {
    private Paint linePaint;
    private Paint fillPaint;
    private Paint gridPaint;
    private Paint textPaint;

    private final int[] gains = new int[AudioConfig.NUM_BANDS];

    // --- Animated curve motion ---
    // setGains()/setSubFilter() used to snap the curve straight to the new target every time,
    // which looked jumpy once the curve started updating live (e.g. the loudness-preview toggle
    // reacting to volume changes) instead of only on a deliberate slider drag. animatedGains/
    // animatedSubCutoffHz/animatedSubGainDb glide toward the real target values (gains[]/
    // subCutoffHz/subGainDb) once per frame via stepAnimation() below, and onDraw() reads the
    // animated values instead of the raw targets - the curve itself is just a deterministic
    // function of these values, so animating them is all that's needed for the whole curve
    // (main + sub) to glide smoothly, no path-level interpolation required.
    private static final float GAIN_ANIM_SMOOTHING = 0.25f; // per-frame blend toward target
    private static final float GAIN_ANIM_EPS = 0.02f;       // stop animating once this close (gain units)
    private static final float SUB_HZ_ANIM_EPS = 0.5f;      // stop animating once this close (Hz)
    private static final float SUB_DB_ANIM_EPS = 0.02f;     // stop animating once this close (dB)
    private boolean gainAnimInitialized = false;
    private boolean subAnimInitialized = false;
    private boolean loudnessAnimInitialized = false;
    private boolean frontBassAnimInitialized = false;
    private boolean rearBassAnimInitialized = false;
    private final float[] animatedGains = new float[AudioConfig.NUM_BANDS];
    private float animatedSubCutoffHz = 80f;
    private float animatedSubGainDb = 0f;
    private final float[] animatedLoudnessGains = new float[AudioConfig.NUM_BANDS];

    // Front/rear "Bass Boost" stage (see AudioConfig.bassShapingResponseDb()) - front is baked
    // directly into the main curve below (it's the channel most people tune against), rear is
    // only drawn as its own overlay (rearBassPath) when it actually differs from front - see
    // setBassShaping()/MainActivity's "Sync Front/Rear Bass" toggle, which is exactly when these
    // two diverge. Target values in frontBass*/rearBass*, animated (glide) versions in
    // animatedFrontBass*/animatedRearBass*, same smoothing pattern as the sub curve above.
    private float frontBassFilterHz = 20f, frontBassBoostFreqHz = 0f, frontBassBoostGainDb = 0f;
    private float animatedFrontBassFilterHz = 20f, animatedFrontBassBoostFreqHz = 0f, animatedFrontBassBoostGainDb = 0f;
    private float rearBassFilterHz = 20f, rearBassBoostFreqHz = 0f, rearBassBoostGainDb = 0f;
    private float animatedRearBassFilterHz = 20f, animatedRearBassBoostFreqHz = 0f, animatedRearBassBoostGainDb = 0f;
    private boolean rearBassDiffers = false;
    private boolean frameCallbackActive = false;
    private final Choreographer.FrameCallback animCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            boolean stillMoving = stepAnimation();
            invalidate();
            if (stillMoving) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                frameCallbackActive = false;
            }
        }
    };

    private float[] xCoords;

    // How many points to sample across the curve's width when computing the composite
    // filter-response line below - fine enough to look smooth as a polyline, cheap enough
    // to recompute every frame (16 bands x this many samples is nothing).
    private static final int CURVE_SAMPLES = 160;

    // How much two adjacent bands' gains are allowed to differ (in dB) before that neighborhood
    // counts as a real transition rather than a plateau - see computeFlatness()/the blend in
    // onDraw() step 4.
    private static final float PLATEAU_BLEND_THRESHOLD_DB = 4f;

    // Per-SEGMENT (not per-band) "how flat is this segment" score: 1 = bands s and s+1 have the
    // same gain, 0 = they differ by PLATEAU_BLEND_THRESHOLD_DB or more. NUM_BANDS-1 entries, one
    // per gap between consecutive bands - see computeFlatnessInto(). sampleBellCurve() uses a
    // segment's own score only strictly BETWEEN its two band endpoints (a smooth bump that's zero
    // right at each endpoint), never blended/averaged with a neighboring segment's score - two
    // earlier versions of this either averaged a band's two (possibly very different) neighbor
    // gaps into one shared score, or interpolated between adjacent segments' scores; both let a
    // plateau's edge band (right next to a real transition) read slightly high, since some of the
    // neighboring transition's lower flatness leaked into that exact band's own position. Forcing
    // exactly 1 at every band position by construction fixes that, and still can't kink where two
    // segments meet since both independently compute exactly 1 there.
    private final float[] flatness = new float[AudioConfig.NUM_BANDS - 1];
    // Same idea, but for the loudness-correction overlay curve below - kept separate since it's a
    // different gains array (animatedLoudnessGains, not animatedGains).
    private final float[] loudnessFlatness = new float[AudioConfig.NUM_BANDS - 1];

    // "True 2.2 Display" toggle - see setTrueQDisplay()/sampleBellCurve()'s trueQDisplay branch.
    private boolean trueQDisplay = false;

    private final RectF rect = new RectF();

    // Define the labels and grouping logic
    private final String[] FREQ_LABELS = {
            "20", "31.5", "50", "80", "125", "200", "315", "500",
            "800", "1.25k", "2k", "3.15k", "5k", "8k", "12.5k", "20k"
    };

    private final String[] GROUP_NAMES = {"low bass", "bass", "mid-bass", "mids", "lower treble", "upper treble"};
    private int[] GROUP_COLORS;
    // Defines which band indices belong to which group
    private final int[][] GROUP_RANGES = {{0,2}, {3,4}, {5,6}, {7,9}, {10,12}, {13,15}};

     // Pre-allocated Path objects
    private final Path fullPath = new Path();
    private final Path fillPath = new Path();

    // Sub LPF overlay curve (see AudioConfig.subFilterResponseDb()) - a dashed line, distinct from
    // the main gradient-colored EQ curve, showing what the subwoofer output's own filter is doing
    // at its current cutoff/gain. Fed via setSubFilter(), defaults to a no-op flat line until then.
    private Paint subLinePaint;
    private final Path subPath = new Path();
    private float subCutoffHz = 80f;
    private float subGainDb = 0f;

    // Rear "Bass Boost" overlay - a dash-dot line (distinct from the sub curve's plain dash and
    // the loudness curve's dots), only ever drawn while rearBassDiffers is true (see
    // setBassShaping()). The front side of the same stage isn't a separate line at all - it's
    // baked straight into the main curve/fullPath, see sampleBellCurve()'s applyBassShaping param.
    private Paint rearBassLinePaint;
    private final Path rearBassPath = new Path();

    // Loudness-correction preview overlay - a dotted line, distinct from the sub curve's dashed
    // one, showing the same correction shape as FmVisualizerView's own curve (built the same way:
    // full bell+flatness-blend composite, not the sub curve's simpler LPF formula) but overlaid on
    // the real/raw EQ curve instead of being combined into it. Built/drawn whenever loudnessAlpha
    // is above zero - see setLoudnessCorrection() - which fades toward loudnessCorrectionActive's
    // target instead of popping on/off. While inactive, animatedLoudnessGains keeps easing toward
    // flat/neutral (see stepAnimation()) instead of freezing, so the curve visibly relaxes back to
    // flat as it fades rather than vanishing as a static shape. loudnessCorrectionGains holds each
    // band's target curve-scale value (6 + offsetDb/2, clamped 0..12), matching what
    // FmVisualizerView computes for itself.
    private static final float LOUDNESS_ALPHA_RISE = 0.03f;  // fade in
    private static final float LOUDNESS_ALPHA_FALL = 0.03f; // fade out - slower, so it reads as a fade not a flicker
    private Paint loudnessLinePaint;
    private int loudnessBaseAlpha = 255; // the resource color's own alpha (R.color.loudness_line) - loudnessAlpha fades on top of this
    private final Path loudnessPath = new Path();
    private final float[] loudnessCorrectionGains = new float[AudioConfig.NUM_BANDS];
    private boolean loudnessCorrectionActive = false;
    private float loudnessAlpha = 0f;

    private final Path bgPath = new Path();


    // Where the real Slider track sits within eq_container, as a fraction of this view's
    // own height - this view is drawn as a separate overlay on top of eq_container's real
    // per-band Slider columns (see activity_main.xml), so its curve/grid have to line up
    // with wherever those sliders actually are. These start as a reasonable default (current
    // per-band layout: dB label + freq label rows above an 0.76-weighted slider row, Q row
    // collapsed/GONE) and get corrected to the real measured position via setSliderBounds()
    // as soon as MainActivity has laid out eq_container - see its syncVisualizerGeometry().
    // Doing it this way (measured, not hardcoded) means this view can't silently drift out of
    // alignment again if eq_container's per-band row weights ever change.
    private float topOffsetRatio = 0.17391304f;
    private float drawHeightRatio = 0.82608696f;

    private int colorFill;
    private float thumbRadiusOffset;

    // Cache for gradient parameters to avoid reallocation
    private float lastDrawStartY = -1;
    private float lastGridBottom = -1;
    private float lastLineGradLeft = -1;
    private float lastLineGradRight = -1;

    //private String[] freqLabels = null;

    private Drawable customBackground;

    private Paint boxPaint;

    public EqVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }


    private void init() {
        customBackground = ContextCompat.getDrawable(getContext(), R.drawable.ui_bg_layer);
        float density = getContext().getResources().getDisplayMetrics().density;

        // Reversed vs. the button palette order so bass reads warm (red) and treble reads cool (blue/teal),
        // matching the GROUP_NAMES/GROUP_RANGES order which goes low-bass -> upper-treble left to right.
        GROUP_COLORS = new int[]{
                ContextCompat.getColor(getContext(), R.color.btn_delete_bg),
                ContextCompat.getColor(getContext(), R.color.btn_import_bg),
                ContextCompat.getColor(getContext(), R.color.btn_export_bg),
                ContextCompat.getColor(getContext(), R.color.btn_rename_bg),
                ContextCompat.getColor(getContext(), R.color.btn_add_bg),
                ContextCompat.getColor(getContext(), R.color.btn_auto_bg)
        };

        int colorLine = ContextCompat.getColor(getContext(), R.color.visualizer_line);
        colorFill = ContextCompat.getColor(getContext(), R.color.visualizer_fill);
        int colorGrid = ContextCompat.getColor(getContext(), R.color.visualizer_grid);

        thumbRadiusOffset = 16 * density;

        // Pre-allocate coordinate arrays based on config
        xCoords = new float[AudioConfig.NUM_BANDS];

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(colorLine);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(colorGrid);
        gridPaint.setStrokeWidth(1 * density);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(colorLine);
        textPaint.setTextSize(12 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);

        textPaint.setTypeface(ResourcesCompat.getFont(getContext(), R.font.main_font));

        boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(1 * density);

        // Colors come from R.color (day/night variants, see values(-night)/colors.xml) rather than
        // a hardcoded Color.WHITE + runtime alpha, so each line can be tuned per-theme and
        // independently of the other - the alpha is baked into each resource's own ARGB value.
        subLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subLinePaint.setColor(ContextCompat.getColor(getContext(), R.color.sub_line));
        subLinePaint.setStrokeWidth(2f * density);
        subLinePaint.setStyle(Paint.Style.STROKE);
        subLinePaint.setStrokeCap(Paint.Cap.ROUND);
        subLinePaint.setPathEffect(new DashPathEffect(new float[]{10f * density, 6f * density}, 0f));

        rearBassLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rearBassLinePaint.setColor(ContextCompat.getColor(getContext(), R.color.rear_bass_line));
        rearBassLinePaint.setStrokeWidth(2f * density);
        rearBassLinePaint.setStyle(Paint.Style.STROKE);
        rearBassLinePaint.setStrokeCap(Paint.Cap.ROUND);
        // Dash-dot, distinct from the sub curve's plain dash and the loudness curve's dots.
        rearBassLinePaint.setPathEffect(new DashPathEffect(new float[]{14f * density, 5f * density, 2f * density, 5f * density}, 0f));

        loudnessLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        loudnessLinePaint.setColor(ContextCompat.getColor(getContext(), R.color.loudness_line));
        loudnessBaseAlpha = loudnessLinePaint.getAlpha();
        loudnessLinePaint.setStrokeWidth(4f * density);
        loudnessLinePaint.setStyle(Paint.Style.STROKE);
        loudnessLinePaint.setStrokeCap(Paint.Cap.ROUND);
        // Dotted, not dashed - a near-zero segment length with a round cap draws round dots
        // instead of dashes, distinguishing this from the sub curve's dashed line.
        loudnessLinePaint.setPathEffect(new DashPathEffect(new float[]{0f, 7f * density}, 0f));
    }

    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
        if (!gainAnimInitialized) {
            // Snap straight to the first real values instead of animating up from the all-zero
            // (flat -12dB) default the gains[]/animatedGains arrays start at.
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) animatedGains[i] = gains[i];
            gainAnimInitialized = true;
            invalidate();
        } else {
            startAnimIfNeeded();
        }
    }

    /** "True 2.2 Display" - see trueQDisplay's declaration. */
    public void setTrueQDisplay(boolean enabled) {
        if (enabled == trueQDisplay) return;
        trueQDisplay = enabled;
        invalidate();
    }

    /**
     * Feeds the loudness-correction overlay curve - curveGains[i] should be each band's target
     * curve-scale value (6 + offsetDb/2, clamped 0..12), the same thing FmVisualizerView computes
     * for its own curve. Only drawn while active is true (see loudnessCorrectionActive) - the
     * real/raw EQ curve is never affected by this, it's purely an overlay.
     */
    public void setLoudnessCorrection(float[] curveGains, boolean active) {
        loudnessCorrectionActive = active;
        if (active) {
            System.arraycopy(curveGains, 0, this.loudnessCorrectionGains, 0, AudioConfig.NUM_BANDS);
            if (!loudnessAnimInitialized) {
                System.arraycopy(curveGains, 0, this.animatedLoudnessGains, 0, AudioConfig.NUM_BANDS);
                loudnessAnimInitialized = true;
            }
        }
        // Always kick the animation loop, active or not - it also drives loudnessAlpha's fade
        // in/out, which needs to keep running even while active is false (fading out).
        startAnimIfNeeded();
        invalidate();
    }

    /** Feeds the sub output's current cutoff (Hz) and gain (dB, 0..+12) into the overlay curve. */
    public void setSubFilter(float cutoffHz, float gainDb) {
        if (cutoffHz == subCutoffHz && gainDb == subGainDb) return;
        subCutoffHz = cutoffHz;
        subGainDb = gainDb;
        if (!subAnimInitialized) {
            animatedSubCutoffHz = cutoffHz;
            animatedSubGainDb = gainDb;
            subAnimInitialized = true;
            invalidate();
        } else {
            startAnimIfNeeded();
        }
    }

    /**
     * Feeds the front/rear "Bass Boost" stage (see AudioConfig.bassShapingResponseDb()) -
     * boostFreqHz <= 0 means that side's boost spinner is on "off". Front is always baked into
     * the main curve; rear only gets its own dash-dot overlay line when it actually differs from
     * front (rearBassDiffers) - e.g. MainActivity.updateBassVisualizer() with the "Sync
     * Front/Rear Bass" toggle off and the two sides set to different values.
     */
    public void setBassShaping(float frontFilterHz, float frontBoostFreqHz, float frontBoostGainDb,
                                float rearFilterHz, float rearBoostFreqHz, float rearBoostGainDb) {
        frontBassFilterHz = frontFilterHz; frontBassBoostFreqHz = frontBoostFreqHz; frontBassBoostGainDb = frontBoostGainDb;
        rearBassFilterHz = rearFilterHz; rearBassBoostFreqHz = rearBoostFreqHz; rearBassBoostGainDb = rearBoostGainDb;
        rearBassDiffers = (frontFilterHz != rearFilterHz) || (frontBoostFreqHz != rearBoostFreqHz) || (frontBoostGainDb != rearBoostGainDb);
        if (!frontBassAnimInitialized) {
            animatedFrontBassFilterHz = frontFilterHz; animatedFrontBassBoostFreqHz = frontBoostFreqHz; animatedFrontBassBoostGainDb = frontBoostGainDb;
            frontBassAnimInitialized = true;
            invalidate();
        }
        if (!rearBassAnimInitialized) {
            animatedRearBassFilterHz = rearFilterHz; animatedRearBassBoostFreqHz = rearBoostFreqHz; animatedRearBassBoostGainDb = rearBoostGainDb;
            rearBassAnimInitialized = true;
            invalidate();
        }
        startAnimIfNeeded();
    }

    private void startAnimIfNeeded() {
        if (!frameCallbackActive) {
            frameCallbackActive = true;
            Choreographer.getInstance().postFrameCallback(animCallback);
        }
    }

    /** Eases animatedGains/animatedSubCutoffHz/animatedSubGainDb one step toward their real
     * targets. Returns true while still visibly moving, so the caller knows whether to keep the
     * per-frame animation loop running or let it stop. */
    private boolean stepAnimation() {
        boolean moving = false;
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float diff = gains[i] - animatedGains[i];
            if (Math.abs(diff) > GAIN_ANIM_EPS) moving = true;
            animatedGains[i] += diff * GAIN_ANIM_SMOOTHING;
        }
        float cutoffDiff = subCutoffHz - animatedSubCutoffHz;
        if (Math.abs(cutoffDiff) > SUB_HZ_ANIM_EPS) moving = true;
        animatedSubCutoffHz += cutoffDiff * GAIN_ANIM_SMOOTHING;

        float subDbDiff = subGainDb - animatedSubGainDb;
        if (Math.abs(subDbDiff) > SUB_DB_ANIM_EPS) moving = true;
        animatedSubGainDb += subDbDiff * GAIN_ANIM_SMOOTHING;

        float ffDiff = frontBassFilterHz - animatedFrontBassFilterHz;
        if (Math.abs(ffDiff) > SUB_HZ_ANIM_EPS) moving = true;
        animatedFrontBassFilterHz += ffDiff * GAIN_ANIM_SMOOTHING;
        float fbfDiff = frontBassBoostFreqHz - animatedFrontBassBoostFreqHz;
        if (Math.abs(fbfDiff) > SUB_HZ_ANIM_EPS) moving = true;
        animatedFrontBassBoostFreqHz += fbfDiff * GAIN_ANIM_SMOOTHING;
        float fbgDiff = frontBassBoostGainDb - animatedFrontBassBoostGainDb;
        if (Math.abs(fbgDiff) > SUB_DB_ANIM_EPS) moving = true;
        animatedFrontBassBoostGainDb += fbgDiff * GAIN_ANIM_SMOOTHING;

        float rfDiff = rearBassFilterHz - animatedRearBassFilterHz;
        if (Math.abs(rfDiff) > SUB_HZ_ANIM_EPS) moving = true;
        animatedRearBassFilterHz += rfDiff * GAIN_ANIM_SMOOTHING;
        float rbfDiff = rearBassBoostFreqHz - animatedRearBassBoostFreqHz;
        if (Math.abs(rbfDiff) > SUB_HZ_ANIM_EPS) moving = true;
        animatedRearBassBoostFreqHz += rbfDiff * GAIN_ANIM_SMOOTHING;
        float rbgDiff = rearBassBoostGainDb - animatedRearBassBoostGainDb;
        if (Math.abs(rbgDiff) > SUB_DB_ANIM_EPS) moving = true;
        animatedRearBassBoostGainDb += rbgDiff * GAIN_ANIM_SMOOTHING;

        // While inactive (fading out - see loudnessAlpha below), keep easing toward flat/neutral
        // (6 = 0dB, no correction) instead of freezing in place, so the curve visibly relaxes back
        // to flat as it fades rather than holding a static shape until it disappears.
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float target = loudnessCorrectionActive ? loudnessCorrectionGains[i] : 6f;
            float diff = target - animatedLoudnessGains[i];
            if (Math.abs(diff) > GAIN_ANIM_EPS) moving = true;
            animatedLoudnessGains[i] += diff * GAIN_ANIM_SMOOTHING;
        }

        // Fades toward loudnessCorrectionActive's target regardless of that flag's own value, so
        // it keeps running while fading OUT (active just went false) as well as fading in.
        float alphaTarget = loudnessCorrectionActive ? 1f : 0f;
        float alphaDiff = alphaTarget - loudnessAlpha;
        if (Math.abs(alphaDiff) > 0.01f) {
            moving = true;
            loudnessAlpha += alphaDiff * (alphaTarget > loudnessAlpha ? LOUDNESS_ALPHA_RISE : LOUDNESS_ALPHA_FALL);
        } else {
            loudnessAlpha = alphaTarget;
        }

        return moving;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        frameCallbackActive = false;
        Choreographer.getInstance().removeFrameCallback(animCallback);
    }

    /**
     * Tells this view exactly where the real Slider track sits within eq_container, as
     * fractions of the shared container height. Called by MainActivity once it has actually
     * measured a live band column, so this overlay's geometry always matches reality instead
     * of relying on a hardcoded guess.
     */
    public void setSliderBounds(float topRatio, float heightRatio) {
        if (topRatio == topOffsetRatio && heightRatio == drawHeightRatio) return;
        topOffsetRatio = topRatio;
        drawHeightRatio = heightRatio;
        invalidate();
    }

    /**
     * Fills out[] (NUM_BANDS-1 entries) from g[] - see flatness's declaration. out[s] is how flat
     * the segment between band s and band s+1 is, on its own, independent of either band's OTHER
     * neighbor. Shared by the main curve (animatedGains -> flatness) and the loudness overlay
     * (animatedLoudnessGains -> loudnessFlatness).
     */
    private static void computeFlatnessInto(float[] g, float[] out) {
        for (int s = 0; s < AudioConfig.NUM_BANDS - 1; s++) {
            float diffDb = Math.abs(g[s + 1] - g[s]) * 2f;
            out[s] = Math.max(0f, 1f - diffDb / PLATEAU_BLEND_THRESHOLD_DB);
        }
    }

    /**
     * The EQ-only part of sampleBellCurve()'s per-sample math: the composite Q=2.2 bell response,
     * blended toward a flat interpolation between neighbors wherever they're already similar
     * (flat[]) so a plateau doesn't kink where it meets a real transition. Split out from
     * sampleBellCurve() so the rear "Bass Boost" overlay (see buildRearBassPath()) can get the
     * same EQ value a second time without duplicating this logic, to compare two different bass-
     * shaping terms against the same EQ baseline.
     */
    private float eqCurveDbAt(float[] g, float[] flat, float t, float freqHz) {
        boolean inBandSpan = t >= 0f && t <= AudioConfig.NUM_BANDS - 1;
        int lo = Math.max(0, Math.min(AudioConfig.NUM_BANDS - 1, (int) Math.floor(t)));
        int hi = Math.min(AudioConfig.NUM_BANDS - 1, lo + 1);
        float frac = t - lo; // 0 at band lo, 1 at band hi
        float bellDb = AudioConfig.compositeResponseDb(g, freqHz);

        if (trueQDisplay || !inBandSpan) {
            // "True 2.2 Display" - the raw composite Q=2.2 response, completely bypassing the
            // flatness blend below. Shows exactly what the real hardware filters are doing,
            // including the natural ripple/scalloping between plateau bands that the flatness
            // blend otherwise smooths away for readability. Also always used outside the band
            // span (t/inBandSpan above) - the flatness blend only means something strictly
            // between two real bands, so past the first/last one there's nothing to blend toward
            // and the true bell response is the only sensible thing to draw.
            return bellDb;
        }

        float easedFrac = 0.5f - 0.5f * (float) Math.cos(Math.PI * frac);
        float loDb = (g[lo] - 6f) * 2f;
        float hiDb = (g[hi] - 6f) * 2f;
        float flatDb = loDb + (hiDb - loDb) * easedFrac;

        // Blend weight: exactly 1 (fully flat, zero bell contribution) at BOTH of this segment's
        // own band endpoints, dipping toward flat[]'s own segment score only in the middle. "dip"
        // is a smooth bump - zero value AND zero slope at frac=0 and frac=1 - so this is
        // guaranteed accurate at every band position by construction, not by averaging with a
        // neighboring segment (which is what let a plateau's edge band, right next to a real
        // transition, read slightly high - the neighboring segment's lower flatness used to leak
        // in there). Two segments sharing a boundary both independently compute exactly 1 there,
        // so this still can't kink either.
        int segIdx = Math.min(AudioConfig.NUM_BANDS - 2, lo);
        float dip = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * frac);
        float blendToFlat = 1f - (1f - flat[segIdx]) * dip;
        return bellDb + (flatDb - bellDb) * blendToFlat;
    }

    /**
     * Samples eqCurveDbAt() (+ an optional bass-shaping term) across the chart width into
     * outPath - shared by the main curve (applyBassShaping=true, front's values) and the
     * loudness overlay (applyBassShaping=false), which otherwise use identical math over
     * different gains arrays.
     */
    private void sampleBellCurve(Path outPath, float[] g, float[] flat, float bgLeft, float bgRight,
                                  float bandSpanStart, float bandSpanEnd, float drawStartY, float drawHeight,
                                  float maxGain, boolean applyBassShaping,
                                  float bassFilterHz, float bassBoostFreqHz, float bassBoostGainDb) {
        outPath.reset();
        for (int s = 0; s <= CURVE_SAMPLES; s++) {
            float x = bgLeft + (bgRight - bgLeft) * (s / (float) CURVE_SAMPLES);

            // Unclamped now (used to hold t at 0/NUM_BANDS-1 outside the band span, which pinned
            // the curve flat across the padded lead-in/lead-out instead of letting it keep
            // rolling off/rising) - AudioConfig.frequencyAt() extrapolates the same log-frequency
            // spacing for t outside [0, NUM_BANDS-1], so the composite response below just keeps
            // following the real Q=2.2 math into the padding instead of flattening there.
            float t = (x - bandSpanStart) / (bandSpanEnd - bandSpanStart) * (AudioConfig.NUM_BANDS - 1);
            float freqHz = AudioConfig.frequencyAt(t);
            float db = eqCurveDbAt(g, flat, t, freqHz);

            // The "Bass Boost" stage is a real, always-on filter on top of the 16-band EQ (not an
            // alternate/overlay view of the same data like trueQDisplay above), so it's added
            // regardless of what eqCurveDbAt() just returned.
            if (applyBassShaping) {
                db += AudioConfig.bassShapingResponseDb(freqHz, bassFilterHz, bassBoostFreqHz, bassBoostGainDb);
            }

            // No top/bottom clamp here any more - callers now clip the actual canvas draw to the
            // plot rect (see onDraw()'s clipRect) instead of pinning the curve flat once it goes
            // past what the chart can show.
            float value = 6f + db / 2f; // dB -> slider-value scale
            float y = drawStartY + drawHeight - (value / maxGain) * drawHeight;

            if (s == 0) outPath.moveTo(x, y); else outPath.lineTo(x, y);
        }
    }

    // How far rear's own "Bass Boost" term has to diverge from front's (in dB, before the shared
    // EQ curve is even added) before the rear overlay actually draws a visible line there - see
    // buildRearBassPath(). Below this, the two channels' curves overlap closely enough on screen
    // that tracing a second line over the main curve is just visual noise, not information.
    private static final float REAR_BASS_VISIBLE_THRESHOLD_DB = 0.1f;

    /**
     * Builds rearBassPath as one or more disconnected segments (moveTo starts a new one, never
     * closed) covering only the x-ranges where rear's own bass-shaping term differs from front's
     * by more than REAR_BASS_VISIBLE_THRESHOLD_DB - everywhere else the two channels' curves
     * would overlap the main curve almost exactly, so the line simply isn't drawn there instead
     * of tracing on top of fullPath. Reuses eqCurveDbAt() against the SAME animatedGains/flatness
     * as the main curve (see setBassShaping()'s doc - rear is affected by the shared EQ exactly
     * like front is), so the visible segments still glide and blend identically to the main curve,
     * just offset by rear's own bass-shaping delta.
     */
    private void buildRearBassPath(float bgLeft, float bgRight, float bandSpanStart, float bandSpanEnd,
                                    float drawStartY, float drawHeight, float maxGain) {
        rearBassPath.reset();
        boolean penDown = false;
        for (int s = 0; s <= CURVE_SAMPLES; s++) {
            float x = bgLeft + (bgRight - bgLeft) * (s / (float) CURVE_SAMPLES);
            float t = (x - bandSpanStart) / (bandSpanEnd - bandSpanStart) * (AudioConfig.NUM_BANDS - 1);
            float freqHz = AudioConfig.frequencyAt(t);

            float frontBassDb = AudioConfig.bassShapingResponseDb(freqHz, animatedFrontBassFilterHz, animatedFrontBassBoostFreqHz, animatedFrontBassBoostGainDb);
            float rearBassDb = AudioConfig.bassShapingResponseDb(freqHz, animatedRearBassFilterHz, animatedRearBassBoostFreqHz, animatedRearBassBoostGainDb);

            if (Math.abs(rearBassDb - frontBassDb) > REAR_BASS_VISIBLE_THRESHOLD_DB) {
                float db = eqCurveDbAt(animatedGains, flatness, t, freqHz) + rearBassDb;
                float value = 6f + db / 2f;
                float y = drawStartY + drawHeight - (value / maxGain) * drawHeight;
                if (!penDown) { rearBassPath.moveTo(x, y); penDown = true; } else { rearBassPath.lineTo(x, y); }
            } else {
                penDown = false; // lift the pen - leaves a gap instead of jumping straight there
            }
        }
    }

    /**
     * Builds loudnessPath as one continuous curve, always fully drawn (no gap-hiding where it
     * matches the main curve, unlike buildRearBassPath() below) - this overlay represents what
     * loudness correction is doing to THIS channel, so tracing it right along the main curve in
     * untouched regions is itself useful information (confirms nothing's happening there), not
     * redundant noise the way an identical front/rear comparison would be.
     */
    private void buildLoudnessPath(float bgLeft, float bgRight, float bandSpanStart, float bandSpanEnd,
                                    float drawStartY, float drawHeight, float maxGain) {
        loudnessPath.reset();
        for (int s = 0; s <= CURVE_SAMPLES; s++) {
            float x = bgLeft + (bgRight - bgLeft) * (s / (float) CURVE_SAMPLES);
            float t = (x - bandSpanStart) / (bandSpanEnd - bandSpanStart) * (AudioConfig.NUM_BANDS - 1);
            float freqHz = AudioConfig.frequencyAt(t);

            float loudDb = eqCurveDbAt(animatedLoudnessGains, loudnessFlatness, t, freqHz);
            float value = 6f + loudDb / 2f;
            float y = drawStartY + drawHeight - (value / maxGain) * drawHeight;
            if (s == 0) loudnessPath.moveTo(x, y); else loudnessPath.lineTo(x, y);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float totalH = getHeight();
        if (w == 0 || totalH == 0) return;

        float density = getResources().getDisplayMetrics().density;

        // ADJUST THESE VALUES
        float bgPadding = 25 * density;   // How much wider than the sliders (in dp)
        float cornerRadius = 15 * density; // How rounded the corners are (in dp)
        int shiftUp = 6;                  // Vertical alignment shift

        float topArea = totalH * topOffsetRatio;
        float sliderAreaH = totalH * drawHeightRatio;
        float drawStartY = topArea + thumbRadiusOffset;
        float drawHeight = sliderAreaH - (thumbRadiusOffset * 2);
        float gridBottom = drawStartY + drawHeight;

        float stepX = w / (float)AudioConfig.NUM_BANDS;
        float MAX_GAIN = 12f;

        // 1. Calculate slider x coordinates (y is now driven by the composite filter
        // response computed below, not a direct per-band value - see step 4).
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            xCoords[i] = (i + 0.5f) * stepX;
        }

        // 2. Define the Background Area
        float bgLeft = xCoords[0] - bgPadding;
        float bgRight = xCoords[AudioConfig.NUM_BANDS - 1] + bgPadding;
        float bgTop = -shiftUp;
        float bgBottom = totalH - shiftUp;

        // 3. Draw Background with Rounded Corners
//        if (customBackground != null) {
//            canvas.save();
//            // Create a rounded path for the background
//            bgPath.reset();
//            bgPath.addRoundRect(bgLeft, bgTop, bgRight, bgBottom, cornerRadius, cornerRadius, Path.Direction.CW);
//            canvas.clipPath(bgPath); // This "cuts" the drawable into a rounded shape
//
//            customBackground.setBounds((int)bgLeft, (int)bgTop, (int)bgRight, (int)bgBottom);
//            customBackground.draw(canvas);
//            canvas.restore();
//        }

        gridPaint.setAlpha(8);

        // Loop from 0 to 12 to create a line at every 1dB increment
        for (int i = 0; i <= 12; i++) {
            // Calculate the Y position for this specific dB level
            // (i / MAX_GAIN) * drawHeight gives the relative height for that level
            float y = drawStartY + drawHeight - (i / MAX_GAIN) * drawHeight;

            // Draw from the left edge of the rounded BG to the right edge
            canvas.drawLine(bgLeft, y, bgRight, y, gridPaint);
        }

        // --- Frequency Band Labels and Group Boxes ---
        float boxHeight = 20 * density;
        float marginToGrid = 10 * density;
        float groupNameBottomY = drawStartY - marginToGrid;

        // ADJUST THIS: Increase to pull boxes away from the screen edges
        float edgeMargin = 15 * density;

        // Dynamic padding between sliders
        float stepX2 = w / (float)AudioConfig.NUM_BANDS;
        float boxPaddingDynamic = stepX2 * 0.42f; // Slightly reduced to prevent overlap

        float boxBottom = groupNameBottomY - (14 * density);
        float boxTop = boxBottom - boxHeight;
        float boxCornerRadius = getResources().getDimension(R.dimen.button_radius);

        for (int g = 0; g < GROUP_RANGES.length; g++) {
            int startIdx = GROUP_RANGES[g][0];
            int endIdx = GROUP_RANGES[g][1];
            int color = GROUP_COLORS[g];

            // Calculate boundaries
            float left = xCoords[startIdx] - boxPaddingDynamic;
            float right = xCoords[endIdx] + boxPaddingDynamic;

            // CONSTRAINT: pull the first/last box in from the screen edge by edgeMargin, but never
            // by so much that it eats into most of the box's own natural padding around its edge
            // label - on a wide landscape view edgeMargin never bites into that padding anyway, but
            // on a narrow portrait one the two can conflict, and a label crowding its own box
            // border is worse than the box sitting a little closer to the screen edge than
            // edgeMargin alone would put it. 0.88 keeps most of the pull-in effect while guaranteeing
            // at least 88% of the natural padding stays around the label.
            if (g == 0) {
                left = Math.min(Math.max(left, edgeMargin), xCoords[startIdx] - boxPaddingDynamic * 0.88f);
            }
            if (g == GROUP_RANGES.length - 1) {
                right = Math.max(Math.min(right, w - edgeMargin), xCoords[endIdx] + boxPaddingDynamic * 0.88f);
            }

            int alpha = 200;
            boxPaint.setColor(color);
            boxPaint.setAlpha(alpha);
            rect.set(left, boxTop, right, boxBottom);
            canvas.drawRoundRect(rect, boxCornerRadius, boxCornerRadius, boxPaint);

            // 3. Draw individual frequency labels (Grey)
            textPaint.setColor(ContextCompat.getColor(getContext(), R.color.band_label));
            textPaint.setTextSize(getResources().getDimension(R.dimen.text_size_small));
            textPaint.setFakeBoldText(false);
            float labelY = boxTop + (boxHeight / 2f) + (textPaint.getTextSize() / 3f);

            for (int i = startIdx; i <= endIdx; i++) {
                canvas.drawText(FREQ_LABELS[i], xCoords[i], labelY, textPaint);
            }

            // 4. Draw group name
            textPaint.setColor(color);
            textPaint.setAlpha(alpha);
            textPaint.setFakeBoldText(true);
            float groupNamePaddingTop = 15 * density; // Increase this to move "low bass" further down
            float groupNameY = boxBottom + groupNamePaddingTop;

            canvas.drawText(GROUP_NAMES[g], (left + right) / 2f, groupNameY, textPaint);
            textPaint.setFakeBoldText(false);
        }
        // ----------------------------------------------

        // 4. Build the EQ curve from the actual composite response of 16 overlapping Q=2.2
        // peaking filters instead of a generic spline through the gain points - see
        // sampleBellCurve()'s doc for the full rationale. Always the real/raw slider values - the
        // loudness-correction preview (step 4c) is a separate overlay, never blended into this.
        computeFlatnessInto(animatedGains, flatness);
        float bandSpanStart = xCoords[0];
        float bandSpanEnd = xCoords[AudioConfig.NUM_BANDS - 1];
        sampleBellCurve(fullPath, animatedGains, flatness, bgLeft, bgRight, bandSpanStart, bandSpanEnd, drawStartY, drawHeight, MAX_GAIN,
                true, animatedFrontBassFilterHz, animatedFrontBassBoostFreqHz, animatedFrontBassBoostGainDb);

        // 4b. Sub LPF overlay curve (see AudioConfig.subFilterResponseDb()) - same x/frequency
        // sampling as the main curve above, just a different response formula and drawn as a
        // separate dashed line instead of being blended into the 16-band curve/fill.
        subPath.reset();
        for (int s = 0; s <= CURVE_SAMPLES; s++) {
            float x = bgLeft + (bgRight - bgLeft) * (s / (float) CURVE_SAMPLES);
            // See sampleBellCurve()'s own t comment - unclamped so frequencyAt() can extrapolate
            // past the first/last band instead of this pinning flat in the padded lead-in/out.
            float t = (x - bandSpanStart) / (bandSpanEnd - bandSpanStart) * (AudioConfig.NUM_BANDS - 1);

            float freqHz = AudioConfig.frequencyAt(t);
            float db = AudioConfig.subFilterResponseDb(freqHz, animatedSubCutoffHz, AudioConfig.SUB_FILTER_ORDER, animatedSubGainDb);
            // Only the top is clamped - once the LPF has rolled off past what the chart can show,
            // let it keep going down and get clipped by the plot rect below instead of pinning flat
            // along the bottom grid line for the rest of the curve.
            float value = Math.min(MAX_GAIN, 6f + db / 2f);
            float y = drawStartY + drawHeight - (value / MAX_GAIN) * drawHeight;

            if (s == 0) subPath.moveTo(x, y); else subPath.lineTo(x, y);
        }

        // 4c'. Rear "Bass Boost" overlay (see buildRearBassPath()) - only built while
        // rearBassDiffers (front/rear set to different raw values at all), and even then only
        // actually drawn across the x-ranges where the two sides' curves visibly diverge.
        if (rearBassDiffers) {
            buildRearBassPath(bgLeft, bgRight, bandSpanStart, bandSpanEnd, drawStartY, drawHeight, MAX_GAIN);
        }

        // 4c. Loudness-correction overlay curve (see setLoudnessCorrection()) - built whenever
        // loudnessAlpha is above zero (not just while active), so it keeps rendering its last
        // shape (animatedLoudnessGains stops updating once inactive) while fading out. Only the
        // x-ranges that actually diverge from the main curve get drawn - see buildLoudnessPath().
        if (loudnessAlpha > 0.004f) {
            computeFlatnessInto(animatedLoudnessGains, loudnessFlatness);
            buildLoudnessPath(bgLeft, bgRight, bandSpanStart, bandSpanEnd, drawStartY, drawHeight, MAX_GAIN);
        }

        // 5. Prepare Fill Path (Aligned to wide edges)
        fillPath.set(fullPath);
        fillPath.lineTo(bgRight, gridBottom);
        fillPath.lineTo(bgLeft, gridBottom);
        fillPath.close();

        // 6. Grid, Shader, and Path Drawing
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            canvas.drawLine(xCoords[i], drawStartY, xCoords[i], gridBottom, gridPaint);
        }

        // 7. Update gradients (horizontal band-color for line & fill, vertical fade for fill) & Draw
        if (bgLeft != lastLineGradLeft || bgRight != lastLineGradRight
                || drawStartY != lastDrawStartY || gridBottom != lastGridBottom) {
            float totalW = bgRight - bgLeft;
            float[] positions = new float[GROUP_COLORS.length];
            int[] fillColors = new int[GROUP_COLORS.length];
            int fillAlpha = Color.alpha(colorFill);
            for (int g = 0; g < GROUP_RANGES.length; g++) {
                int startIdx = GROUP_RANGES[g][0];
                int endIdx = GROUP_RANGES[g][1];
                float midX = (xCoords[startIdx] + xCoords[endIdx]) / 2f;
                positions[g] = (midX - bgLeft) / totalW;

                int c = GROUP_COLORS[g];
                fillColors[g] = Color.argb(fillAlpha, Color.red(c), Color.green(c), Color.blue(c));
            }

            // Horizontal gradient for the EQ line, following band group colors
            linePaint.setShader(new LinearGradient(bgLeft, 0, bgRight, 0,
                    GROUP_COLORS, positions, Shader.TileMode.CLAMP));

            // Horizontal gradient for the fill, same band group colors at the fill's own alpha,
            // composited with a vertical opaque->transparent mask so it still fades out downward.
            Shader fillColorShader = new LinearGradient(bgLeft, 0, bgRight, 0,
                    fillColors, positions, Shader.TileMode.CLAMP);
            Shader fadeMaskShader = new LinearGradient(0, drawStartY, 0, gridBottom,
                    Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP);
            fillPaint.setShader(new ComposeShader(fillColorShader, fadeMaskShader, PorterDuff.Mode.DST_IN));

            lastLineGradLeft = bgLeft;
            lastLineGradRight = bgRight;
            lastDrawStartY = drawStartY;
            lastGridBottom = gridBottom;
        }

        // All curve paths can now extend past the plot rect (sampleBellCurve()/the sub curve loop
        // no longer clamp their values to [0, maxGain]) - clip to the plot rect so every one of
        // them cleanly exits at whichever edge it crosses instead of pinning flat once it goes
        // past what the chart can show.
        canvas.save();
        canvas.clipRect(bgLeft, drawStartY, bgRight, gridBottom);
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(fullPath, linePaint);
        canvas.drawPath(subPath, subLinePaint);
        if (rearBassDiffers) canvas.drawPath(rearBassPath, rearBassLinePaint);
        if (loudnessAlpha > 0.004f) {
            loudnessLinePaint.setAlpha(Math.round(loudnessBaseAlpha * loudnessAlpha));
            canvas.drawPath(loudnessPath, loudnessLinePaint);
        }
        canvas.restore();
    }
}