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
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import java.util.Locale;

public class FmVisualizerView extends View {
    private Paint linePaint;
    private Paint fillPaint;
    private Paint gridPaint;
    private Paint textPaint;
    private Paint warningPaint;

    private final int[] gains = new int[AudioConfig.NUM_BANDS];
    private float[] offsets = null;
    private float[] warnings = null;

    private float[] xCoords;
    private float[] yCoords;

    // Same real-composite-response curve technique as EqVisualizerView (see its onDraw() step 4
    // for the full rationale) instead of a generic spline through the 16 band points - so this
    // loudness-curve preview shows bands bleeding into their neighbors the same way the real
    // hardware Q=2.2 filters (and the main EQ screen's curve) do, not an idealized per-band shape.
    private static final int CURVE_SAMPLES = 160;
    private static final float PLATEAU_BLEND_THRESHOLD_DB = 4f;
    // Per-SEGMENT (not per-band) flatness - see EqVisualizerView.flatness's declaration for the
    // full rationale (fixes a flat pair of bands displaying too high when the band on the OTHER
    // side of one of them differs). NUM_BANDS-1 entries, one per gap between consecutive bands.
    private final float[] flatness = new float[AudioConfig.NUM_BANDS - 1];

    // "True 2.2 Display" toggle - see EqVisualizerView.setTrueQDisplay()'s identical field/method.
    private boolean trueQDisplay = false;

    // Same band grouping as EqVisualizerView, since this view plots the same 16 bands
    private final int[][] GROUP_RANGES = {{0,2}, {3,4}, {5,6}, {7,9}, {10,12}, {13,15}};
    private int[] GROUP_COLORS;

    // Pre-allocated Path objects
    private final Path fullPath = new Path();
    private final Path fillPath = new Path();

    private final Path bgPath = new Path();

    // Sub LPF overlay curve - same as EqVisualizerView's (see its setSubFilter()/onDraw() step 4b
    // for the full rationale), so the loudness screen shows the same sub-filter preview as the
    // front page.
    private Paint subLinePaint;
    private final Path subPath = new Path();
    private float subCutoffHz = 80f;
    private float subGainDb = 0f;

    // Front "Bass Boost" stage (see AudioConfig.bassShapingResponseDb()) - baked straight into
    // the main curve below, same as EqVisualizerView's front bass shaping on the main screen (no
    // rear overlay here, this view only ever shows one curve). Not animated/glided like
    // EqVisualizerView's - this view snaps straight to new values like it always has for gains[].
    private float frontBassFilterHz = 20f, frontBassBoostFreqHz = 0f, frontBassBoostGainDb = 0f;


    @SuppressWarnings("FieldCanBeLocal")
    private final float TOP_OFFSET_RATIO = 0.23f;
    @SuppressWarnings("FieldCanBeLocal")
    private final float DRAW_HEIGHT_RATIO = 0.90f;

    private int colorFill;
    private float thumbRadiusOffset;
    private float pointRadius;

    // Cache for gradient parameters to avoid reallocation
    private float lastDrawStartY = -1;
    private float lastGridBottom = -1;
    private float lastLineGradLeft = -1;
    private float lastLineGradRight = -1;

    private Drawable customBackground;

    public FmVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        customBackground = ContextCompat.getDrawable(getContext(), R.drawable.ui_bg_layer);
        float density = getContext().getResources().getDisplayMetrics().density;

        int colorLine = ContextCompat.getColor(getContext(), R.color.visualizer_line);
        colorFill = ContextCompat.getColor(getContext(), R.color.visualizer_fill);
        int colorGrid = ContextCompat.getColor(getContext(), R.color.visualizer_grid);

        // Same reversed order as EqVisualizerView's GROUP_COLORS: low bass -> upper treble
        // goes warm (red) to cool (blue/teal).
        GROUP_COLORS = new int[]{
                ContextCompat.getColor(getContext(), R.color.btn_delete_bg),
                ContextCompat.getColor(getContext(), R.color.btn_import_bg),
                ContextCompat.getColor(getContext(), R.color.btn_export_bg),
                ContextCompat.getColor(getContext(), R.color.btn_rename_bg),
                ContextCompat.getColor(getContext(), R.color.btn_add_bg),
                ContextCompat.getColor(getContext(), R.color.btn_auto_bg)
        };

        thumbRadiusOffset = 10 * density;
        pointRadius = 6 * density;

        // Pre-allocate coordinate arrays based on config
        xCoords = new float[AudioConfig.NUM_BANDS];
        yCoords = new float[AudioConfig.NUM_BANDS];

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

        warningPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        warningPaint.setColor(Color.RED);
        warningPaint.setTextSize(12 * density);
        warningPaint.setTextAlign(Paint.Align.CENTER);
        warningPaint.setFakeBoldText(true);

        warningPaint.setTypeface(ResourcesCompat.getFont(getContext(), R.font.main_font));

        subLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        subLinePaint.setColor(Color.WHITE);
        subLinePaint.setAlpha(140);
        subLinePaint.setStrokeWidth(2f * density);
        subLinePaint.setStyle(Paint.Style.STROKE);
        subLinePaint.setStrokeCap(Paint.Cap.ROUND);
        subLinePaint.setPathEffect(new DashPathEffect(new float[]{10f * density, 6f * density}, 0f));
    }

    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
        invalidate();
    }

    /** "True 2.2 Display" - see EqVisualizerView.setTrueQDisplay(). */
    public void setTrueQDisplay(boolean enabled) {
        if (enabled == trueQDisplay) return;
        trueQDisplay = enabled;
        invalidate();
    }

    /** Feeds the sub output's current cutoff (Hz) and gain (dB, 0..+12) into the overlay curve. */
    public void setSubFilter(float cutoffHz, float gainDb) {
        if (cutoffHz == subCutoffHz && gainDb == subGainDb) return;
        subCutoffHz = cutoffHz;
        subGainDb = gainDb;
        invalidate();
    }

    /** Front "Bass Boost" stage - see frontBassFilterHz's declaration. */
    public void setBassShaping(float filterHz, float boostFreqHz, float boostGainDb) {
        if (filterHz == frontBassFilterHz && boostFreqHz == frontBassBoostFreqHz && boostGainDb == frontBassBoostGainDb) return;
        frontBassFilterHz = filterHz;
        frontBassBoostFreqHz = boostFreqHz;
        frontBassBoostGainDb = boostGainDb;
        invalidate();
    }

    public void setOffsets(float[] newOffsets) {
        if (newOffsets == null) {
            this.offsets = null;
        } else {
            if (this.offsets == null) this.offsets = new float[AudioConfig.NUM_BANDS];
            System.arraycopy(newOffsets, 0, this.offsets, 0, AudioConfig.NUM_BANDS);
        }
        invalidate();
    }

    public void setWarnings(float[] newWarnings) {
        if (newWarnings == null) {
            this.warnings = null;
        } else {
            if (this.warnings == null) this.warnings = new float[AudioConfig.NUM_BANDS];
            System.arraycopy(newWarnings, 0, this.warnings, 0, AudioConfig.NUM_BANDS);
        }
        invalidate();
    }

    /** Same as EqVisualizerView.computeFlatnessInto() - see there for the full rationale. */
    private void computeFlatness() {
        for (int s = 0; s < AudioConfig.NUM_BANDS - 1; s++) {
            float diffDb = Math.abs(gains[s + 1] - gains[s]) * 2f;
            flatness[s] = Math.max(0f, 1f - diffDb / PLATEAU_BLEND_THRESHOLD_DB);
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
        int shiftUp = 0;                  // Vertical alignment shift

        float topArea = totalH * TOP_OFFSET_RATIO;
        float sliderAreaH = totalH * DRAW_HEIGHT_RATIO;
        float drawStartY = topArea + thumbRadiusOffset;
        float drawHeight = sliderAreaH - (thumbRadiusOffset * 2);
        float gridBottom = drawStartY + drawHeight;

        float stepX = w / (float)AudioConfig.NUM_BANDS;
        float MAX_GAIN = 12f;

        // 1. Calculate slider coordinates
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            xCoords[i] = (i + 0.5f) * stepX;
            yCoords[i] = drawStartY + drawHeight - (gains[i] / MAX_GAIN) * drawHeight;
        }

        // 2. Define the Background Area
        float bgLeft = xCoords[0] - bgPadding;
        float bgRight = xCoords[AudioConfig.NUM_BANDS - 1] + bgPadding;
        float bgTop = -shiftUp;
        float bgBottom = totalH - shiftUp;

        // 3. Draw Background with Rounded Corners
        canvas.save(); // always paired with the unconditional canvas.restore() at the end of this method
        if (customBackground != null) {
            // Create a rounded path for the background
            bgPath.reset();
            bgPath.addRoundRect(bgLeft, bgTop, bgRight, bgBottom, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.clipPath(bgPath); // This "cuts" the drawable into a rounded shape

            customBackground.setBounds((int)bgLeft, 0, (int)bgRight, (int)bgBottom);
            customBackground.draw(canvas);
        }

        gridPaint.setAlpha(8);

        // Loop from 0 to 12 to create a line at every 1dB increment
        for (int i = 0; i <= 12; i++) {
            // Calculate the Y position for this specific dB level
            // (i / MAX_GAIN) * drawHeight gives the relative height for that level
            float y = drawStartY + drawHeight - (i / MAX_GAIN) * drawHeight;

            // Draw from the left edge of the rounded BG to the right edge
            canvas.drawLine(bgLeft, y, bgRight, y, gridPaint);
        }
        // ------------------------------------

        // 4. Build the curve from the actual composite response of 16 overlapping Q=2.2 peaking
        // filters, exactly like EqVisualizerView's onDraw() step 4 - see its comment there for the
        // full rationale. `gains[]` here already has the loudness/Sub Comp offset baked in per
        // band (see MainActivity.updateFmVisualizer()), so this reads as "what the real EQ curve
        // would look like with the loudness curve applied," not an idealized per-band shape.
        computeFlatness();
        fullPath.reset();
        for (int s = 0; s <= CURVE_SAMPLES; s++) {
            float x = bgLeft + (bgRight - bgLeft) * (s / (float) CURVE_SAMPLES);

            // Unclamped (used to hold t at 0/NUM_BANDS-1 outside the band span, which pinned the
            // curve flat across the padded lead-in/lead-out - looked like a shelf at the very top
            // and bottom of the frequency range instead of the real Q=2.2 rolloff/rise continuing
            // into it) - see EqVisualizerView.sampleBellCurve()'s identical fix. AudioConfig.
            // frequencyAt() extrapolates the same log-frequency spacing for t outside
            // [0, NUM_BANDS-1], so the composite response below just keeps following the real
            // math into the padding instead of flattening there.
            float t = (x - xCoords[0]) / (xCoords[AudioConfig.NUM_BANDS - 1] - xCoords[0]) * (AudioConfig.NUM_BANDS - 1);
            boolean inBandSpan = t >= 0f && t <= AudioConfig.NUM_BANDS - 1;

            int lo = Math.max(0, Math.min(AudioConfig.NUM_BANDS - 1, (int) Math.floor(t)));
            int hi = Math.min(AudioConfig.NUM_BANDS - 1, lo + 1);
            float frac = t - lo; // 0 at band lo, 1 at band hi
            float easedFrac = 0.5f - 0.5f * (float) Math.cos(Math.PI * frac);
            float loDb = (gains[lo] - 6) * 2f;
            float hiDb = (gains[hi] - 6) * 2f;

            float freqHz = AudioConfig.frequencyAt(t);
            float bellDb = AudioConfig.compositeResponseDb(gains, freqHz);

            float db;
            if (trueQDisplay || !inBandSpan) {
                // "True 2.2 Display" - see EqVisualizerView.sampleBellCurve()'s identical branch.
                // Also always used outside the band span - the flatness blend only means something
                // strictly between two real bands, so past the first/last one the true bell
                // response is the only sensible thing to draw.
                db = bellDb;
            } else {
                float flatDb = loDb + (hiDb - loDb) * easedFrac;

                // Blend weight: exactly 1 at both of this segment's own band endpoints, dipping
                // toward flatness[]'s own segment score only in the middle - see
                // EqVisualizerView.sampleBellCurve()'s identical technique for the full rationale
                // (guarantees accuracy at every band position by construction instead of averaging
                // with a neighboring segment, which let a plateau's edge band read slightly high).
                int segIdx = Math.min(AudioConfig.NUM_BANDS - 2, lo);
                float dip = 0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * frac);
                float blendToFlat = 1f - (1f - flatness[segIdx]) * dip;
                db = bellDb + (flatDb - bellDb) * blendToFlat;
            }

            // Front "Bass Boost" stage - a real, always-on filter on top of the 16-band curve, not
            // an alternate view of the same data like trueQDisplay above, so it's added regardless
            // of which branch just ran - see EqVisualizerView.sampleBellCurve()'s identical logic.
            db += AudioConfig.bassShapingResponseDb(freqHz, frontBassFilterHz, frontBassBoostFreqHz, frontBassBoostGainDb);

            float value = Math.max(0f, Math.min(MAX_GAIN, 6f + db / 2f)); // dB -> slider-value scale
            float y = drawStartY + drawHeight - (value / MAX_GAIN) * drawHeight;

            if (s == 0) fullPath.moveTo(x, y); else fullPath.lineTo(x, y);
        }

        // 4b. Sub LPF overlay curve (see AudioConfig.subFilterResponseDb()) - same x/frequency
        // sampling as the main curve above, drawn as a separate dashed line.
        subPath.reset();
        for (int s = 0; s <= CURVE_SAMPLES; s++) {
            float x = bgLeft + (bgRight - bgLeft) * (s / (float) CURVE_SAMPLES);
            // See the main curve loop's own t comment above - unclamped so frequencyAt() can
            // extrapolate past the first/last band instead of this pinning flat at the edges.
            float t = (x - xCoords[0]) / (xCoords[AudioConfig.NUM_BANDS - 1] - xCoords[0]) * (AudioConfig.NUM_BANDS - 1);

            float freqHz = AudioConfig.frequencyAt(t);
            float db = AudioConfig.subFilterResponseDb(freqHz, subCutoffHz, AudioConfig.SUB_FILTER_ORDER, subGainDb);
            // Only the top is clamped - let a fully rolled-off point keep going down and get
            // clipped by the plot rect below instead of pinning flat along the bottom grid line.
            float value = Math.min(MAX_GAIN, 6f + db / 2f);
            float y = drawStartY + drawHeight - (value / MAX_GAIN) * drawHeight;

            if (s == 0) subPath.moveTo(x, y); else subPath.lineTo(x, y);
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

            // Horizontal gradient for the curve line, following band group colors
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

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(fullPath, linePaint);

        // Sub curve's path geometry can extend below gridBottom now (see the value calc above) -
        // clip to the plot rect so it cleanly exits at the bottom edge instead of drawing past it.
        canvas.save();
        canvas.clipRect(bgLeft, drawStartY, bgRight, gridBottom);
        canvas.drawPath(subPath, subLinePaint);
        canvas.restore();

        // 8. Draw Text/Warnings (unchanged)
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            if (offsets != null) {
                float val = offsets[i];
                if (Math.abs(val) > 0.05f) {
                    // Note: String.format still allocates, but it's necessary for dynamic text.
                    // To optimize further, one could use a StringBuilder or specialized formatter.
                    String label = String.format(Locale.getDefault(), "%s%.1f", (val > 0 ? "+" : ""), val);
                    canvas.drawText(label, xCoords[i], yCoords[i] - pointRadius - 8, textPaint);
                }
            }

            if (warnings != null && Math.abs(warnings[i]) > 0.05f) {
                String warningLabel = String.format(Locale.getDefault(), "-%.1f", warnings[i]);
                float yOffset = (offsets != null && Math.abs(offsets[i]) > 0.05f) ? 24 : 8;
                canvas.drawText(warningLabel, xCoords[i], yCoords[i] - pointRadius - yOffset - 8, warningPaint);
            }
        }
        canvas.restore();
    }
}