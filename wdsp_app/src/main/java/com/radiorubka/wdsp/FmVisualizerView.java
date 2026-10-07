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

import com.radiorubka.wdsp.ui.theme.ThemeManager;

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

    // 16-band base colors following the physical optical spectrum (700 nm Red -> 390 nm Violet)
    private static final int[] SPECTRUM_BASE_COLORS = {
            0xFFD50000, // 20 Hz   (700 nm - Deep Red)
            0xFFFF1744, // 31.5 Hz (680 nm - Bright Red)
            0xFFFF3D00, // 50 Hz   (650 nm - Red-Orange)
            0xFFFF6D00, // 80 Hz   (620 nm - Orange)
            0xFFFF9100, // 125 Hz  (600 nm - Amber-Orange)
            0xFFFFC400, // 200 Hz  (585 nm - Amber-Yellow)
            0xFFFFEA00, // 315 Hz  (570 nm - Yellow)
            0xFFAEEA00, // 500 Hz  (550 nm - Lime)
            0xFF00E676, // 800 Hz  (530 nm - Pure Green)
            0xFF00BFA5, // 1.25 kHz (510 nm - Teal / Spring Green)
            0xFF00E5FF, // 2 kHz   (490 nm - Cyan)
            0xFF00B0FF, // 3.15 kHz (475 nm - Sky Blue)
            0xFF2979FF, // 5 kHz   (460 nm - Pure Blue)
            0xFF3D5AFE, // 8 kHz   (440 nm - Deep Blue/Indigo)
            0xFF651FFF, // 12.5 kHz (420 nm - Violet)
            0xFF6200EA  // 20 kHz  (390 nm - Pure Deep Violet)
    };

    // Pre-allocated Path objects
    private final Path fullPath = new Path();
    private final Path fillPath = new Path();
    private final Path bgPath = new Path();
    private final Path subPath = new Path();

    // The author's 0.5: this tab shows what the chip is sent - the composite response of the 16
    // Q = 2.2 bells at the indices sent, the front bass shelf as the service sends it, and the
    // subwoofer's low-pass dashed - the same functions as EqVisualizerView, so the two curves agree.
    private static final int CURVE_SAMPLES = 160;
    private Paint subLinePaint;
    private float subCutoffHz = 0f, subGainDb = 0f;
    private float frontBassFilterHz = 0f, frontBassBoostHz = 0f, frontBassBoostDb = 0f;

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

    public FmVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getContext().getResources().getDisplayMetrics().density;

        int colorLine = ContextCompat.getColor(getContext(), R.color.visualizer_line);
        colorFill = ContextCompat.getColor(getContext(), R.color.visualizer_fill);
        int colorGrid = ContextCompat.getColor(getContext(), R.color.visualizer_grid);

        thumbRadiusOffset = 10 * density;
        pointRadius = 6 * density;

        // Pre-allocate coordinate arrays based on config
        xCoords = new float[AudioConfig.NUM_BANDS];
        yCoords = new float[AudioConfig.NUM_BANDS];

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(colorLine);
        linePaint.setStrokeWidth(2.8f * density);
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
        subLinePaint.setStrokeWidth(2f * density);
        subLinePaint.setStyle(Paint.Style.STROKE);
        subLinePaint.setStrokeCap(Paint.Cap.ROUND);
        subLinePaint.setPathEffect(new DashPathEffect(new float[]{10f * density, 6f * density}, 0f));
    }

    /** The EQ indices 0..12 as sent to the chip (slider plus correction, pre-warped and rounded). */
    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
        invalidate();
    }

    /** The subwoofer's low-pass in Hz (0 = no subwoofer, nothing drawn) and its gain in dB. */
    public void setSubFilter(float cutoffHz, float gainDb) {
        if (cutoffHz == subCutoffHz && gainDb == subGainDb) return;
        subCutoffHz = cutoffHz;
        subGainDb = gainDb;
        invalidate();
    }

    /** The front doors' high-pass and bass shelf, as AudioConfig.bassShapingResponseDb takes them. */
    public void setBassShaping(float filterHz, float boostHz, float boostDb) {
        if (filterHz == frontBassFilterHz && boostHz == frontBassBoostHz && boostDb == frontBassBoostDb) return;
        frontBassFilterHz = filterHz;
        frontBassBoostHz = boostHz;
        frontBassBoostDb = boostDb;
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

        // 3. Grid lines with rounded plot bounds
        canvas.save(); // paired with the unconditional restore at the end (the author's 0.5 fix)
        bgPath.reset();
        bgPath.addRoundRect(bgLeft, bgTop, bgRight, bgBottom, cornerRadius, cornerRadius, Path.Direction.CW);
        canvas.clipPath(bgPath);

        boolean isNight = ThemeManager.isNight(getContext());
        int gridColor = isNight ? Color.parseColor("#20FFFFFF") : Color.parseColor("#25000000");
        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(1f * density);

        // Loop from 0 to 12 to create a line at every 1dB increment
        for (int i = 0; i <= 12; i++) {
            // Calculate the Y position for this specific dB level
            // (i / MAX_GAIN) * drawHeight gives the relative height for that level
            float y = drawStartY + drawHeight - (i / MAX_GAIN) * drawHeight;

            // Draw from the left edge of the rounded BG to the right edge
            canvas.drawLine(bgLeft, y, bgRight, y, gridPaint);
        }
        // ------------------------------------

        // 4. The curves, sampled on the bands' own log-frequency axis (extrapolated into the
        //    padding, so the edges keep the real roll-off instead of a flat shelf)
        fullPath.reset();
        subPath.reset();
        boolean drawSub = subCutoffHz > 0f;
        float spanStart = xCoords[0];
        float spanEnd = xCoords[AudioConfig.NUM_BANDS - 1];
        for (int s = 0; s <= CURVE_SAMPLES; s++) {
            float x = bgLeft + (bgRight - bgLeft) * (s / (float) CURVE_SAMPLES);
            float t = (x - spanStart) / (spanEnd - spanStart) * (AudioConfig.NUM_BANDS - 1);
            float hz = AudioConfig.frequencyAt(t);
            float db = AudioConfig.compositeResponseDb(gains, hz)
                    + AudioConfig.bassShapingResponseDb(hz, frontBassFilterHz, frontBassBoostHz, frontBassBoostDb);
            float y = yOfDb(db, drawStartY, drawHeight, MAX_GAIN);
            if (s == 0) fullPath.moveTo(x, y); else fullPath.lineTo(x, y);
            if (drawSub) {
                float subDb = AudioConfig.subFilterResponseDb(hz, subCutoffHz, AudioConfig.SUB_FILTER_ORDER, subGainDb);
                float ys = yOfDb(Math.min(subDb, MAX_GAIN), drawStartY, drawHeight, MAX_GAIN);
                if (s == 0) subPath.moveTo(x, ys); else subPath.lineTo(x, ys);
            }
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

        // 7. Update gradients (horizontal optical spectrum for line & fill, vertical fade for fill) & Draw
        if (bgLeft != lastLineGradLeft || bgRight != lastLineGradRight
                || drawStartY != lastDrawStartY || gridBottom != lastGridBottom) {
            float totalW = bgRight - bgLeft;
            float[] positions = new float[AudioConfig.NUM_BANDS];
            int[] fillColors = new int[AudioConfig.NUM_BANDS];
            int fillAlpha = 70; // Soft translucent glow fill

            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                positions[i] = Math.max(0f, Math.min(1f, (xCoords[i] - bgLeft) / totalW));
                int c = SPECTRUM_BASE_COLORS[i];
                fillColors[i] = Color.argb(fillAlpha, Color.red(c), Color.green(c), Color.blue(c));
            }

            // Horizontal gradient for the curve line, following physical optical frequency spectrum
            linePaint.setShader(new LinearGradient(bgLeft, 0, bgRight, 0,
                    SPECTRUM_BASE_COLORS, positions, Shader.TileMode.CLAMP));

            // Horizontal gradient for the fill, same band colors at the fill's own alpha,
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

        // Clipped to the plot: a curve leaving it exits at the edge instead of drawing past it.
        canvas.save();
        canvas.clipRect(bgLeft, drawStartY, bgRight, gridBottom);
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(fullPath, linePaint);
        if (drawSub) {
            int subColor = ThemeManager.contrastText(ThemeManager.textSecondary(getContext(), isNight),
                    isNight ? 0xFF12161B : 0xFFFFFFFF);
            subLinePaint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(subColor, 210));
            canvas.drawPath(subPath, subLinePaint);
        }
        canvas.restore();

        // 8. Draw Text/Warnings
        int colorLine = ThemeManager.getThemedColor(getContext(), isNight, R.color.visualizer_line);
        textPaint.setColor(colorLine);
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

    /** dB to the plot's y on the slider scale, 0..12 with 6 at 0 dB. */
    private static float yOfDb(float db, float drawStartY, float drawHeight, float maxGain) {
        return drawStartY + drawHeight - ((6f + db / 2f) / maxGain) * drawHeight;
    }
}