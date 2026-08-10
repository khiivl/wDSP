package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
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
    private final float[] offsets = null;
    private final float[] warnings = null;

    private float[] xCoords;
    private float[] yCoords;

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

    private final Path bgPath = new Path();


    @SuppressWarnings("FieldCanBeLocal")
    private final float TOP_OFFSET_RATIO = 0.25555555555555f;
    @SuppressWarnings("FieldCanBeLocal")
    private final float DRAW_HEIGHT_RATIO = 0.72222222222222f;

    private int colorFill;
    private float thumbRadiusOffset;
    private float pointRadius;

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
//    public void setFreqLabels(String[] labels) {
//        this.freqLabels = labels;
//        invalidate();
//    }


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

        boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(1 * density);
    }

    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
        invalidate();
    }

//    public void setOffsets(float[] newOffsets) {
//        if (newOffsets == null) {
//            this.offsets = null;
//        } else {
//            if (this.offsets == null) this.offsets = new float[AudioConfig.NUM_BANDS];
//            System.arraycopy(newOffsets, 0, this.offsets, 0, AudioConfig.NUM_BANDS);
//        }
//        invalidate();
//    }

//    public void setWarnings(float[] newWarnings) {
//        if (newWarnings == null) {
//            this.warnings = null;
//        } else {
//            if (this.warnings == null) this.warnings = new float[AudioConfig.NUM_BANDS];
//            System.arraycopy(newWarnings, 0, this.warnings, 0, AudioConfig.NUM_BANDS);
//        }
//        invalidate();
//    }

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

            customBackground.setBounds((int)bgLeft, (int)bgTop, (int)bgRight, (int)bgBottom);
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

            // CONSTRAINT: Ensure the first and last boxes don't touch the screen edges
            if (g == 0) {
                left = Math.max(left, edgeMargin);
            }
            if (g == GROUP_RANGES.length - 1) {
                right = Math.min(right, w - edgeMargin);
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