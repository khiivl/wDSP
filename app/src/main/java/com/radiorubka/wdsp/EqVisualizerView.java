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
import androidx.core.content.ContextCompat;
import java.util.Locale;

public class EqVisualizerView extends View {
    private Paint linePaint;
    private Paint fillPaint;
    private Paint pointPaint;
    private Paint gridPaint;
    private Paint textPaint;
    private Paint warningPaint;
    private int[] gains = new int[AudioConfig.NUM_BANDS];
    private float[] offsets = null;
    private float[] warnings = null;
    
    private final float MAX_GAIN = 12f;
    // PERFECT OFFSET VALUES - DO NOT CHANGE
//    private final float TOP_OFFSET_RATIO = 0.235f;

    private final float TOP_OFFSET_RATIO = 0.237f;
    private final float DRAW_HEIGHT_RATIO = 0.760f;

    private int colorLine;
    private int colorFill;
    private int colorPoints;
    private int colorGrid;
    
    private float thumbRadiusOffset;
    private float pointRadius;

    public EqVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getContext().getResources().getDisplayMetrics().density;
        
        colorLine = ContextCompat.getColor(getContext(), R.color.visualizer_line);
        colorFill = ContextCompat.getColor(getContext(), R.color.visualizer_fill);
        colorPoints = ContextCompat.getColor(getContext(), R.color.visualizer_points);
        colorGrid = ContextCompat.getColor(getContext(), R.color.visualizer_grid);
        
        thumbRadiusOffset = 10 * density;
        pointRadius = 6 * density;

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(colorLine);
        linePaint.setStrokeWidth(3.5f * density);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pointPaint.setColor(colorPoints);
        pointPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(colorGrid);
        gridPaint.setStrokeWidth(1 * density);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(colorLine);
        textPaint.setTextSize(12 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);

        warningPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        warningPaint.setColor(Color.RED);
        warningPaint.setTextSize(12 * density);
        warningPaint.setTextAlign(Paint.Align.CENTER);
        warningPaint.setFakeBoldText(true);
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

    public void setQStates(boolean[] newQStates) {
        // Kept for compatibility with MainActivity call, but logic removed
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float totalH = getHeight();
        if (w == 0 || totalH == 0) return;

        float topArea = totalH * TOP_OFFSET_RATIO;
        float sliderAreaH = totalH * DRAW_HEIGHT_RATIO;

        float drawStartY = topArea + thumbRadiusOffset;
        float drawHeight = sliderAreaH - (thumbRadiusOffset * 2);

        float stepX = w / (float)AudioConfig.NUM_BANDS;

        // Grid
        for (int i = 0; i <= 12; i++) {
            float y = drawStartY + drawHeight - (i / MAX_GAIN) * drawHeight;
            canvas.drawLine(0, y, w, y, gridPaint);
        }
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float x = (i + 0.5f) * stepX;
            canvas.drawLine(x, drawStartY, x, drawStartY + drawHeight, gridPaint);
        }

        // Calculate cubic spline curve that passes exactly through the points
        float[] xCoords = new float[AudioConfig.NUM_BANDS];
        float[] yCoords = new float[AudioConfig.NUM_BANDS];
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            xCoords[i] = (i + 0.5f) * stepX;
            yCoords[i] = drawStartY + drawHeight - (gains[i] / MAX_GAIN) * drawHeight;
        }

        // Create the main line path
        Path fullPath = new Path();
        fullPath.moveTo(0, yCoords[0]); // Start at the left edge
        fullPath.lineTo(xCoords[0], yCoords[0]); // Straight line to the first band

        // Draw the curves between bands
        for (int i = 1; i < AudioConfig.NUM_BANDS; i++) {
            float cp1x = xCoords[i-1] + (xCoords[i] - xCoords[i-1]) / 2f;
            fullPath.cubicTo(cp1x, yCoords[i-1], cp1x, yCoords[i], xCoords[i], yCoords[i]);
        }

        // Extend the line to the right edge
        fullPath.lineTo(w, yCoords[AudioConfig.NUM_BANDS - 1]);

        // Create the fill area
        Path fillPath = new Path(fullPath);
        float gridBottom = drawStartY + drawHeight; // The exact bottom line of the grid
        fillPath.lineTo(w, gridBottom);
        fillPath.lineTo(0, gridBottom);
        fillPath.close();

        // Update the gradient to match the grid exactly
        fillPaint.setShader(new LinearGradient(0, drawStartY, 0, gridBottom,
                colorFill, Color.TRANSPARENT, Shader.TileMode.CLAMP));

        // Draw the visual elements
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(fullPath, linePaint);

        // Draw Anchor Points
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            //canvas.drawCircle(xCoords[i], yCoords[i], pointRadius, pointPaint);

            // Draw offset text if available
            if (offsets != null) {
                float val = offsets[i];
                if (Math.abs(val) > 0.05f) { // Only draw if significant
                    String label = String.format(Locale.getDefault(), "%s%.1f", (val > 0 ? "+" : ""), val);
                    canvas.drawText(label, xCoords[i], yCoords[i] - pointRadius - 8, textPaint);
                }
            }

            // Draw warning text if available
            if (warnings != null && Math.abs(warnings[i]) > 0.05f) {
                String warningLabel = String.format(Locale.getDefault(), "-%.1f", warnings[i]);
                // Draw it below the offset text or just above the point if no offset text
                float yOffset = (offsets != null && Math.abs(offsets[i]) > 0.05f) ? 24 : 8;
                canvas.drawText(warningLabel, xCoords[i], yCoords[i] - pointRadius - yOffset - 8, warningPaint);
            }
        }
    }

    private void fullLineToEdge(Path p, float x, float y, float[] xs, float[] ys) {
        p.lineTo(x, y);
    }
}