package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import java.util.Locale;

public class EqVisualizerView extends View {
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

    // Pre-allocated Path objects
    private final Path fullPath = new Path();
    private final Path fillPath = new Path();

    @SuppressWarnings("FieldCanBeLocal")
    private final float TOP_OFFSET_RATIO = 0.257f;
    @SuppressWarnings("FieldCanBeLocal")
    private final float DRAW_HEIGHT_RATIO = 0.725f;

    private int colorFill;
    private float thumbRadiusOffset;
    private float pointRadius;

    // Cache for gradient parameters to avoid reallocation
    private float lastDrawStartY = -1;
    private float lastGridBottom = -1;

    public EqVisualizerView(Context context, AttributeSet attrs) {
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
        linePaint.setStrokeWidth(3.5f * density);
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
        super.onDraw(canvas);
        float w = getWidth();
        float totalH = getHeight();
        if (w == 0 || totalH == 0) return;

        float topArea = totalH * TOP_OFFSET_RATIO;
        float sliderAreaH = totalH * DRAW_HEIGHT_RATIO;

        float drawStartY = topArea + thumbRadiusOffset;
        float drawHeight = sliderAreaH - (thumbRadiusOffset * 2);
        float gridBottom = drawStartY + drawHeight;

        float stepX = w / (float)AudioConfig.NUM_BANDS;
        float MAX_GAIN = 12f;

        // 1. Draw Grid
        for (int i = 0; i <= 12; i++) {
            float y = drawStartY + drawHeight - (i / MAX_GAIN) * drawHeight;
            canvas.drawLine(0, y, w, y, gridPaint);
        }
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float x = (i + 0.5f) * stepX;
            canvas.drawLine(x, drawStartY, x, gridBottom, gridPaint);

            // Calculate coordinates into pre-allocated arrays
            xCoords[i] = x;
            yCoords[i] = drawStartY + drawHeight - (gains[i] / MAX_GAIN) * drawHeight;
        }

        // 2. Prepare Path
        fullPath.reset();
        fullPath.moveTo(0, yCoords[0]);
        fullPath.lineTo(xCoords[0], yCoords[0]);

        for (int i = 1; i < AudioConfig.NUM_BANDS; i++) {
            float cp1x = xCoords[i-1] + (xCoords[i] - xCoords[i-1]) / 2f;
            fullPath.cubicTo(cp1x, yCoords[i-1], cp1x, yCoords[i], xCoords[i], yCoords[i]);
        }
        fullPath.lineTo(w, yCoords[AudioConfig.NUM_BANDS - 1]);

        // 3. Prepare Fill (Reuse fullPath)
        fillPath.set(fullPath);
        fillPath.lineTo(w, gridBottom);
        fillPath.lineTo(0, gridBottom);
        fillPath.close();

        // 4. Update Shader only if dimensions changed
        if (drawStartY != lastDrawStartY || gridBottom != lastGridBottom) {
            fillPaint.setShader(new LinearGradient(0, drawStartY, 0, gridBottom,
                    colorFill, Color.TRANSPARENT, Shader.TileMode.CLAMP));
            lastDrawStartY = drawStartY;
            lastGridBottom = gridBottom;
        }

        // 5. Draw the paths
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(fullPath, linePaint);

        // 6. Draw Text
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