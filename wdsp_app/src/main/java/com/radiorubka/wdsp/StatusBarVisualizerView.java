package com.radiorubka.wdsp;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

/**
 * Transparent status bar overlay visualizer view.
 * Renders 16 solid frequency bars seamlessly fitting the vehicle's top status bar.
 * Supports HUE spectrum rotation, custom uniform HUE color, and Auto Day/Night (white on dark / black on white).
 */
public class StatusBarVisualizerView extends View implements AudioSpectrumEngine.OnSpectrumDataListener {

    public static final int THEME_SPECTRUM = 0;
    public static final int THEME_SOLID_HUE = 1;
    public static final int THEME_AUTO_DAY_NIGHT = 2;
    public static final int THEME_EQ_GROUPS = 3;
    public static final int THEME_MONOCHROME_WHITE = 4;
    public static final int THEME_MONOCHROME_BLACK = 5;
    public static final int THEME_FIRE = 6;
    public static final int THEME_NEON = 7;

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

    // 6-group color ranges matching wDSP EQ styling
    private static final int[][] GROUP_RANGES = {{0, 2}, {3, 4}, {5, 6}, {7, 9}, {10, 12}, {13, 15}};
    private static final int[] GROUP_BASE_COLORS = {
            0xFFE53935, // Low bass (Red)
            0xFFFB8C00, // Bass (Orange)
            0xFFFDD835, // Low Mid (Yellow)
            0xFF43A047, // Mid (Green)
            0xFF00ACC1, // High Mid (Cyan)
            0xFF3949AB  // Treble (Royal Blue)
    };

    // Fire gradient (Red -> Orange -> Yellow)
    private static final int[] FIRE_COLORS = {
            0xFFD50000, 0xFFE53935, 0xFFFF1744, 0xFFFF3D00,
            0xFFFF5722, 0xFFFF6E40, 0xFFFF9100, 0xFFFF9800,
            0xFFFFA726, 0xFFFFB74D, 0xFFFFC107, 0xFFFFCA28,
            0xFFFFD54F, 0xFFFFE082, 0xFFFFFF00, 0xFFFFFF8D
    };

    // Neon gradient (Neon Cyan -> Electric Violet -> Magenta)
    private static final int[] NEON_COLORS = {
            0xFF00E5FF, 0xFF00E5FF, 0xFF00B0FF, 0xFF0091EA,
            0xFF2979FF, 0xFF3D5AFE, 0xFF651FFF, 0xFF7C4DFF,
            0xFFB388FF, 0xFFE040FB, 0xFFD500F9, 0xFFAA00FF,
            0xFFFF007F, 0xFFFF1744, 0xFFF50057, 0xFFFF4081
    };

    public static final int MAX_BANDS = 32;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final float[] hsvBuffer = new float[3];

    private int theme = THEME_SPECTRUM;
    private int hueShift = 0; // 0..360
    private int alphaPercent = 100; // 0..100
    private int bandCount = 32; // Default to 32 bands
    private final int[] resolvedColors = new int[MAX_BANDS];

    private final float[] displayLevels = new float[MAX_BANDS];
    private final float[] prevLevels = new float[MAX_BANDS];
    private final float[] renderLevels = new float[MAX_BANDS];

    private long lastCaptureTime = 0;
    private long captureIntervalMs = 50;
    private boolean frameCallbackActive = false;
    private boolean isRunning = false;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameCallbackActive) return;
            long elapsed = System.currentTimeMillis() - lastCaptureTime;
            float t = captureIntervalMs > 0 ? Math.min(1f, elapsed / (float) captureIntervalMs) : 1f;
            for (int i = 0; i < bandCount; i++) {
                renderLevels[i] = prevLevels[i] + (displayLevels[i] - prevLevels[i]) * t;
            }
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public StatusBarVisualizerView(Context context) {
        super(context);
        init();
    }

    public StatusBarVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint.setStyle(Paint.Style.FILL);
        setBackgroundColor(Color.TRANSPARENT);
        recalculateColors();
    }

    public void setTheme(int theme) {
        this.theme = theme;
        recalculateColors();
        invalidate();
    }

    public void setHueShift(int hueShift) {
        this.hueShift = (hueShift % 360 + 360) % 360;
        recalculateColors();
        invalidate();
    }

    public void setAlphaPercent(int alphaPercent) {
        this.alphaPercent = Math.max(0, Math.min(100, alphaPercent));
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        recalculateColors();
        invalidate();
    }

    /**
     * Determines whether the status bar background is currently light (Day) or dark (Night).
     */
    public boolean isStatusBarLight() {
        // 1. Check system night mode configuration
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
            return false; // Dark background
        } else if (nightMode == Configuration.UI_MODE_NIGHT_NO) {
            return true;  // Light background
        }

        // 2. Check QF head unit system property persist.sys.day_night (0 = Day, 1 = Night)
        try {
            // noinspection PrivateApi
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method getInt = sp.getMethod("getInt", String.class, int.class);
            int dayNight = (int) getInt.invoke(null, "persist.sys.day_night", -1);
            if (dayNight == 0) return true;  // Day / Light bar
            if (dayNight == 1) return false; // Night / Dark bar
        } catch (Throwable ignored) {}

        return false; // Default to dark background
    }

    public void setBandCount(int count) {
        this.bandCount = (count == 16) ? 16 : 32;
        recalculateColors();
        invalidate();
    }

    public int getBandCount() {
        return this.bandCount;
    }

    public void recalculateColors() {
        int baseAlpha = (int) ((alphaPercent / 100f) * 255);
        boolean isLightBar = isStatusBarLight();
        int bands = this.bandCount;

        for (int i = 0; i < bands; i++) {
            float frac = i / (float) Math.max(1, bands - 1);
            switch (theme) {
                case THEME_AUTO_DAY_NIGHT:
                    int autoColor = isLightBar ? 0xFF1A1A1A : 0xFFFFFFFF;
                    resolvedColors[i] = Color.argb(baseAlpha, Color.red(autoColor), Color.green(autoColor), Color.blue(autoColor));
                    break;

                case THEME_MONOCHROME_WHITE:
                    resolvedColors[i] = Color.argb(baseAlpha, 255, 255, 255);
                    break;

                case THEME_MONOCHROME_BLACK:
                    resolvedColors[i] = Color.argb(baseAlpha, 26, 26, 26);
                    break;

                case THEME_SOLID_HUE:
                    hsvBuffer[0] = (float) hueShift;
                    hsvBuffer[1] = 1.0f;
                    hsvBuffer[2] = 1.0f;
                    int solidColor = Color.HSVToColor(hsvBuffer);
                    resolvedColors[i] = Color.argb(baseAlpha, Color.red(solidColor), Color.green(solidColor), Color.blue(solidColor));
                    break;

                case THEME_EQ_GROUPS:
                    int group = (int) (i * 6f / bands);
                    int groupBase = GROUP_BASE_COLORS[Math.min(5, group)];
                    if (hueShift != 0) {
                        Color.colorToHSV(groupBase, hsvBuffer);
                        hsvBuffer[0] = (hsvBuffer[0] + hueShift) % 360f;
                        int c = Color.HSVToColor(hsvBuffer);
                        resolvedColors[i] = Color.argb(baseAlpha, Color.red(c), Color.green(c), Color.blue(c));
                    } else {
                        resolvedColors[i] = Color.argb(baseAlpha, Color.red(groupBase), Color.green(groupBase), Color.blue(groupBase));
                    }
                    break;

                case THEME_FIRE:
                    float fireHue = frac * 55f; // 0 (Red) -> 55 (Yellow)
                    if (hueShift != 0) fireHue = (fireHue + hueShift) % 360f;
                    hsvBuffer[0] = fireHue;
                    hsvBuffer[1] = 1.0f;
                    hsvBuffer[2] = 1.0f;
                    int fc = Color.HSVToColor(hsvBuffer);
                    resolvedColors[i] = Color.argb(baseAlpha, Color.red(fc), Color.green(fc), Color.blue(fc));
                    break;

                case THEME_NEON:
                    float neonHue = 180f + frac * 140f; // 180 (Cyan) -> 320 (Magenta/Pink)
                    if (hueShift != 0) neonHue = (neonHue + hueShift) % 360f;
                    hsvBuffer[0] = neonHue;
                    hsvBuffer[1] = 1.0f;
                    hsvBuffer[2] = 1.0f;
                    int nc = Color.HSVToColor(hsvBuffer);
                    resolvedColors[i] = Color.argb(baseAlpha, Color.red(nc), Color.green(nc), Color.blue(nc));
                    break;

                case THEME_SPECTRUM:
                default:
                    float specHue = frac * 295f; // 0 (Red) -> 295 (Deep Violet)
                    if (hueShift != 0) specHue = (specHue + hueShift) % 360f;
                    hsvBuffer[0] = specHue;
                    hsvBuffer[1] = 1.0f;
                    hsvBuffer[2] = 1.0f;
                    int sc = Color.HSVToColor(hsvBuffer);
                    resolvedColors[i] = Color.argb(baseAlpha, Color.red(sc), Color.green(sc), Color.blue(sc));
                    break;
            }
        }
    }

    private int groupForBand(int band) {
        for (int g = 0; g < GROUP_RANGES.length; g++) {
            if (band >= GROUP_RANGES[g][0] && band <= GROUP_RANGES[g][1]) return g;
        }
        return 0;
    }

    private boolean normalizationEnabled = false;

    public void setNormalizationEnabled(boolean enabled) {
        this.normalizationEnabled = enabled;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        AudioSpectrumEngine.getInstance().registerListener(this);
        if (!frameCallbackActive) {
            frameCallbackActive = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        AudioSpectrumEngine.getInstance().unregisterListener(this);
        frameCallbackActive = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        for (int i = 0; i < MAX_BANDS; i++) {
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
        int count = this.bandCount;
        if (count == 16) {
            float[] srcDisplay = normalizationEnabled ? displayLevels16Norm : displayLevels16;
            float[] srcPrev = normalizationEnabled ? prevLevels16Norm : prevLevels16;
            System.arraycopy(srcPrev, 0, this.prevLevels, 0, 16);
            System.arraycopy(srcDisplay, 0, this.displayLevels, 0, 16);
        } else {
            float[] srcDisplay = normalizationEnabled ? displayLevels32Norm : displayLevels32;
            float[] srcPrev = normalizationEnabled ? prevLevels32Norm : prevLevels32;
            System.arraycopy(srcPrev, 0, this.prevLevels, 0, 32);
            System.arraycopy(srcDisplay, 0, this.displayLevels, 0, 32);
        }
        this.lastCaptureTime = lastCaptureTime;
        this.captureIntervalMs = captureIntervalMs;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float totalH = getHeight();
        if (w <= 0 || totalH <= 0) return;

        int count = this.bandCount;
        float stepX = w / (float) count;
        float barGap = stepX * (count == 32 ? 0.20f : 0.22f);
        float barWidth = stepX - barGap;
        float cornerRadius = barWidth * 0.35f;

        float bottom = totalH;

        for (int i = 0; i < count; i++) {
            float level = renderLevels[i];
            if (level < 0.02f) level = 0.02f; // Keep a small visible baseline bar

            float barHeight = level * totalH * 0.88f;
            float left = i * stepX + barGap / 2f;
            float right = left + barWidth;
            float top = bottom - barHeight;

            barPaint.setColor(resolvedColors[i]);
            barRect.set(left, top, right, bottom);
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) {
            start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) {
            start();
        } else {
            stop();
        }
    }
}
