package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

public class SpectrumAnalyzerView extends View implements AudioSpectrumEngine.OnSpectrumDataListener {

    private static final String TAG = "wDSP_Spectrum";

    public static final int MODE_SPECTRUM = 0;
    public static final int MODE_MONOCHROME = 1;

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

    private static final float TOP_OFFSET_RATIO = 0.14f;
    private static final float DRAW_HEIGHT_RATIO = 0.78f;

    private final float[] displayLevels = new float[AudioConfig.NUM_BANDS];
    private final float[] prevLevels = new float[AudioConfig.NUM_BANDS];
    private final float[] renderLevels = new float[AudioConfig.NUM_BANDS];
    private long lastCaptureTime = 0;
    private long captureIntervalMs = 50;
    private boolean frameCallbackActive = false;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameCallbackActive) return;
            long elapsed = System.currentTimeMillis() - lastCaptureTime;
            float t = captureIntervalMs > 0 ? Math.min(1f, elapsed / (float) captureIntervalMs) : 1f;
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                renderLevels[i] = prevLevels[i] + (displayLevels[i] - prevLevels[i]) * t;
            }
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private Paint barPaint;
    private final RectF barRect = new RectF();

    public SpectrumAnalyzerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);
    }

    public void setGains(int[] newGains) {
        // Gain handling delegated to AudioSpectrumEngine
    }

    public void start() {
        boolean enabled = ThemeManager.prefs(getContext()).getBoolean("pref_eq_visualizer_enabled", true);
        if (!enabled) return;
        AudioSpectrumEngine.getInstance().registerListener(this);
        AudioSpectrumEngine.getInstance().start();
        if (!frameCallbackActive) {
            frameCallbackActive = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    public void stop() {
        AudioSpectrumEngine.getInstance().unregisterListener(this);
        frameCallbackActive = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            renderLevels[i] = 0f;
            displayLevels[i] = 0f;
            prevLevels[i] = 0f;
        }
        invalidate();
    }

    @Override
    public void onSpectrumCapture(float[] displayLevels16, float[] displayLevels16Norm,
                                   float[] prevLevels16, float[] prevLevels16Norm,
                                   float[] displayLevels32, float[] displayLevels32Norm,
                                   float[] prevLevels32, float[] prevLevels32Norm,
                                   long lastCaptureTime, long captureIntervalMs) {
        // Absolute levels only: the bars are drawn relative to their own average (onDraw), which
        // takes any gain common to all bands away, so there is no normalised variant to choose.
        System.arraycopy(prevLevels16, 0, this.prevLevels, 0, AudioConfig.NUM_BANDS);
        System.arraycopy(displayLevels16, 0, this.displayLevels, 0, AudioConfig.NUM_BANDS);
        this.lastCaptureTime = lastCaptureTime;
        this.captureIntervalMs = captureIntervalMs;
    }

    /** Below this a band is silent: it is not drawn and does not count toward the average. */
    private static final float SILENT_LEVEL = 0.005f;
    /** Fewer bands with sound than this and there is no average worth drawing against. */
    private static final int MIN_BANDS_FOR_AVERAGE = 3;
    /** The equaliser grid's range, ±12 dB, as EqVisualizerView draws it. */
    private static final float GRID_HALF_RANGE_DB = 12f;

    /**
     * 🔴 Owner, 15.09.2026 (option В): the bars are drawn on the equaliser's grid in the equaliser's
     * own decibels, relative to the frame's average. The top of a bar at "+4" means that band is 4 dB
     * above the average of the bands that have sound - the slider under it would go to −4 to level
     * it. Until then a bar's height was its absolute level over a 60 dB range, so the grid's "0" meant
     * −30 dBFS and one grid dB meant 2.5 dB of signal: a −23 dBFS test tone stood at "+2" and read as
     * two decibels of equaliser. The geometry is EqVisualizerView's, thumb inset included, so a bar
     * and a slider at the same number are at the same height.
     */
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float totalH = getHeight();
        if (w == 0 || totalH == 0) return;

        boolean enabled = ThemeManager.prefs(getContext()).getBoolean("pref_eq_visualizer_enabled", true);
        if (!enabled) return;

        int mode = ThemeManager.prefs(getContext()).getInt("pref_eq_visualizer_mode", MODE_SPECTRUM);

        float density = getResources().getDisplayMetrics().density;
        float leftMargin = 32 * density;
        float activeWidth = w - leftMargin;

        float thumbInset = 10 * density;
        float drawStartY = totalH * TOP_OFFSET_RATIO + thumbInset;
        float drawHeight = totalH * DRAW_HEIGHT_RATIO - 2 * thumbInset;
        float gridBottom = drawStartY + drawHeight;

        // The average of the bands that have sound, in display-level units; a difference of levels
        // times the display range is a difference in decibels.
        float sum = 0f;
        int active = 0;
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            if (renderLevels[i] > SILENT_LEVEL) {
                sum += renderLevels[i];
                active++;
            }
        }
        if (active < MIN_BANDS_FOR_AVERAGE) return;
        final float average = sum / active;
        final float rangeDb = AudioSpectrumEngine.getInstance().getDisplayRangeDb();

        float stepX = activeWidth / (float) AudioConfig.NUM_BANDS;
        float barGap = stepX * 0.22f;
        float barWidth = stepX - barGap;
        // Capsule pill corner radius matching status bar visualizer
        float barCornerRadius = barWidth * 0.35f;

        int accent = ThemeManager.accent(getContext());

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float level = renderLevels[i];
            if (level <= SILENT_LEVEL) continue; // Clean when idle

            float relativeDb = (level - average) * rangeDb;
            float clamped = Math.max(-GRID_HALF_RANGE_DB, Math.min(GRID_HALF_RANGE_DB, relativeDb));
            float barHeight = drawHeight * (0.5f + clamped / (2f * GRID_HALF_RANGE_DB));
            if (barHeight <= 0.5f) continue;
            float left = leftMargin + i * stepX + barGap / 2f;
            float right = left + barWidth;
            float top = gridBottom - barHeight;

            if (mode == MODE_MONOCHROME) {
                barPaint.setColor(accent);
                barPaint.setAlpha(70);
            } else {
                int c = SPECTRUM_BASE_COLORS[i];
                barPaint.setColor(c);
                barPaint.setAlpha(115);
            }

            barRect.set(left, top, right, gridBottom);
            canvas.drawRoundRect(barRect, barCornerRadius, barCornerRadius, barPaint);
        }
    }
}
