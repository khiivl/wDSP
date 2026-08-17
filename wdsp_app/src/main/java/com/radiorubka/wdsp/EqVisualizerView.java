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
import androidx.core.content.res.ResourcesCompat;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

public class EqVisualizerView extends View {
    private Paint linePaint;
    private Paint fillPaint;
    private Paint gridPaint;
    private Paint textPaint;

    private final int[] gains = new int[AudioConfig.NUM_BANDS];

    private float[] xCoords;
    private float[] yCoords;

    private final Path fullPath = new Path();
    private final Path fillPath = new Path();

    // 0.14f top offset for Q (0.07) + dB (0.07), 0.78f for seekBox
    private static final float TOP_OFFSET_RATIO = 0.14f;
    private static final float DRAW_HEIGHT_RATIO = 0.78f;

    private float thumbRadiusOffset;

    // dB scale labels matching 13 levels from 0 to 12 (0 is -12dB, 6 is 0dB, 12 is +12dB)
    private static final String[] DB_LABELS = {
            "-12", "-10", "-8", "-6", "-4", "-2", "0", "+2", "+4", "+6", "+8", "+10", "+12"
    };

    public EqVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getContext().getResources().getDisplayMetrics().density;
        thumbRadiusOffset = 10 * density;

        xCoords = new float[AudioConfig.NUM_BANDS];
        yCoords = new float[AudioConfig.NUM_BANDS];

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStrokeWidth(2.8f * density);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(Color.parseColor("#18FFFFFF"));
        gridPaint.setStrokeWidth(1.2f * density);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(9.5f * density);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        try {
            textPaint.setTypeface(ResourcesCompat.getFont(getContext(), R.font.main_font));
        } catch (Exception ignored) {
        }
    }

    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float totalH = getHeight();
        if (w == 0 || totalH == 0) return;

        float density = getResources().getDisplayMetrics().density;
        int accent = ThemeManager.accent(getContext());

        float leftMargin = 32 * density; // Space for left dB scale numbers
        float activeWidth = w - leftMargin;

        float topArea = totalH * TOP_OFFSET_RATIO;
        float sliderAreaH = totalH * DRAW_HEIGHT_RATIO;
        float drawStartY = topArea + thumbRadiusOffset;
        float drawHeight = sliderAreaH - (thumbRadiusOffset * 2);
        float gridBottom = drawStartY + drawHeight;

        float stepX = activeWidth / (float) AudioConfig.NUM_BANDS;
        float MAX_GAIN = 12f;

        // 1. Calculate slider thumb coordinates
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            xCoords[i] = leftMargin + (i + 0.5f) * stepX;
            yCoords[i] = drawStartY + drawHeight - (gains[i] / MAX_GAIN) * drawHeight;
        }

        // 2. Draw Horizontal Grid Lines and Left dB Labels
        for (int i = 0; i <= 12; i++) {
            float y = drawStartY + drawHeight - (i / MAX_GAIN) * drawHeight;

            // Highlight 0 dB line (i == 6) with slightly brighter line
            if (i == 6) {
                gridPaint.setColor(Color.parseColor("#4D1FE7C4"));
                gridPaint.setStrokeWidth(1.4f * density);
            } else {
                gridPaint.setColor(Color.parseColor("#18FFFFFF"));
                gridPaint.setStrokeWidth(1.0f * density);
            }

            // Draw horizontal line across active area
            canvas.drawLine(leftMargin, y, w, y, gridPaint);

            // Draw left dB text
            if (i == 6) {
                textPaint.setColor(accent);
                textPaint.setFakeBoldText(true);
            } else {
                textPaint.setColor(Color.parseColor("#808B9198"));
                textPaint.setFakeBoldText(false);
            }
            canvas.drawText(DB_LABELS[i], leftMargin - (6 * density), y + (textPaint.getTextSize() / 3f), textPaint);
        }

        // 3. Draw Vertical Slider Center Grid Tracks
        gridPaint.setColor(Color.parseColor("#10FFFFFF"));
        gridPaint.setStrokeWidth(1.0f * density);
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            canvas.drawLine(xCoords[i], drawStartY, xCoords[i], gridBottom, gridPaint);
        }

        // 4. Build Smooth Spline Curve for EQ Response
        fullPath.reset();
        fullPath.moveTo(leftMargin, yCoords[0]);
        fullPath.lineTo(xCoords[0], yCoords[0]);

        for (int i = 1; i < AudioConfig.NUM_BANDS; i++) {
            float cp1x = xCoords[i - 1] + (xCoords[i] - xCoords[i - 1]) / 2f;
            fullPath.cubicTo(cp1x, yCoords[i - 1], cp1x, yCoords[i], xCoords[i], yCoords[i]);
        }

        fullPath.lineTo(w, yCoords[AudioConfig.NUM_BANDS - 1]);

        // 5. Fill Path (from curve down to zero-line / bottom)
        fillPath.set(fullPath);
        fillPath.lineTo(w, gridBottom);
        fillPath.lineTo(leftMargin, gridBottom);
        fillPath.close();

        // 6. Apply Gradient Fill & Neon Line Paint
        int fillTopColor = Color.argb(55, Color.red(accent), Color.green(accent), Color.blue(accent));
        int fillBottomColor = Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent));
        fillPaint.setShader(new LinearGradient(0, drawStartY, 0, gridBottom, fillTopColor, fillBottomColor, Shader.TileMode.CLAMP));

        linePaint.setColor(accent);

        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(fullPath, linePaint);
    }
}