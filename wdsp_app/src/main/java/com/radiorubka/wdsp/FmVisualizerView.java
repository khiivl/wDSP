package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
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

    // Same band grouping as EqVisualizerView, since this view plots the same 16 bands
    private final int[][] GROUP_RANGES = {{0,2}, {3,4}, {5,6}, {7,9}, {10,12}, {13,15}};
    private int[] GROUP_COLORS;

    // Pre-allocated Path objects
    private final Path fullPath = new Path();
    private final Path fillPath = new Path();

    private final Path bgPath = new Path();


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
    }

    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
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

        // 3. Draw Background with Rounded Corners
        if (customBackground != null) {
            canvas.save();
            // Create a rounded path for the background
            bgPath.reset();
            bgPath.addRoundRect(bgLeft, bgTop, bgRight, bgBottom, cornerRadius, cornerRadius, Path.Direction.CW);
            canvas.clipPath(bgPath); // This "cuts" the drawable into a rounded shape

            customBackground.setBounds((int)bgLeft, 0, (int)bgRight, (int)bgBottom);
            customBackground.draw(canvas);
            canvas.restore();
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

        // 4. Update EQ Line to start and end at the NEW background edges
        fullPath.reset();
        fullPath.moveTo(bgLeft, yCoords[0]); // Start at the wide edge
        fullPath.lineTo(xCoords[0], yCoords[0]); // Draw to first slider center

        for (int i = 1; i < AudioConfig.NUM_BANDS; i++) {
            float cp1x = xCoords[i-1] + (xCoords[i] - xCoords[i-1]) / 2f;
            fullPath.cubicTo(cp1x, yCoords[i-1], cp1x, yCoords[i], xCoords[i], yCoords[i]);
        }

        fullPath.lineTo(bgRight, yCoords[AudioConfig.NUM_BANDS - 1]); // End at the wide edge

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
    }
}