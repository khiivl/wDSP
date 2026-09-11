package com.radiorubka.wdsp;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

import java.lang.reflect.Method;

/**
 * Transparent status bar overlay visualizer view.
 * Renders frequency bars seamlessly fitting the vehicle's top status bar and full-screen screensaver.
 *
 * <p>Supports:
 * <ul>
 *   <li>5 distinct visualizer styles adapted from FireLamp EffectVU (Classic, Outrun Peaks, Dynamic Palette, Symmetrical Center, VU-Meter)</li>
 *   <li>13 rich color themes and FastLED gradient palettes (Spectrum, Fire, Neon, Purple Synthwave, Rainbow Sherbet, Ocean Breeze, Real Sunset, Warm VU, etc.)</li>
 *   <li>Floating peak caps with realistic peak-hold and gravity decay physics</li>
 *   <li>Symmetrical frequency layout (center bass shimmers to treble at edges)</li>
 *   <li>Dynamic palette sampling: bars and peaks dynamically traverse palettes based on height or position</li>
 *   <li>Seamless crossfade with 7-segment digital clock and now-playing metadata on screensaver</li>
 * </ul>
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

    // Ported FastLED palettes from FireLamp (color_palette.cpp / effects.h)
    public static final int THEME_PURPLE_SYNTHWAVE = 8;  // purple_gp / outrun_gp (Electric Cyan -> Violet -> Deep Purple)
    public static final int THEME_RAINBOW_SHERBET = 9;   // rainbowsherbet_gp (Lime -> Red/Orange -> Pink -> Cyan)
    public static final int THEME_OCEAN_BREEZE = 10;     // es_ocean_breeze_068_gp (Deep Navy -> Aqua -> Seafoam)
    public static final int THEME_SUNSET_REAL = 11;      // Sunset_Real_gp (Indigo -> Crimson -> Sunset Orange -> Golden Amber)
    public static final int THEME_WARM_VU = 12;          // redyellow_gp (Soft White -> Golden Yellow -> Intense Red)
    public static final int THEME_COLORFULL = 13;        // Colorfull_gp (Teal -> Green -> Lime -> Orange -> Yellow -> Teal)

    // Visualizer rendering styles adapted from FireLamp EffectVU
    public static final int STYLE_CLASSIC_BARS = 0;
    public static final int STYLE_OUTRUN_PEAKS = 1;
    public static final int STYLE_PALETTE_GRADIENT = 2;
    public static final int STYLE_CENTER_BARS = 3;
    public static final int STYLE_VU_GRADIENT = 4;
    public static final int STYLE_OSCILLOSCOPE = 5;

    // 16-band base colors following the physical optical spectrum (700 nm Red -> 390 nm Violet)
    private static final int[] SPECTRUM_BASE_COLORS = {
            0xFFD50000, 0xFFFF1744, 0xFFFF3D00, 0xFFFF6D00,
            0xFFFF9100, 0xFFFFC400, 0xFFFFEA00, 0xFFAEEA00,
            0xFF00E676, 0xFF00BFA5, 0xFF00E5FF, 0xFF00B0FF,
            0xFF2979FF, 0xFF3D5AFE, 0xFF651FFF, 0xFF6200EA
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

    // Palette definition structures: array of [pos (0..255), R, G, B]
    private static final int[][] PALETTE_PURPLE_SYNTHWAVE = {
            {0, 0, 212, 255}, {128, 141, 0, 200}, {255, 179, 0, 255}
    };
    private static final int[][] PALETTE_RAINBOW_SHERBET = {
            {0, 87, 255, 65}, {43, 255, 68, 25}, {86, 255, 7, 25},
            {127, 255, 82, 103}, {170, 255, 255, 242}, {209, 42, 255, 22}, {255, 87, 255, 65}
    };
    private static final int[][] PALETTE_OCEAN_BREEZE = {
            {0, 1, 10, 10}, {51, 1, 99, 137}, {104, 35, 142, 168},
            {178, 0, 180, 217}, {255, 200, 245, 255}
    };
    private static final int[][] PALETTE_SUNSET_REAL = {
            {0, 0, 0, 160}, {51, 179, 22, 0}, {100, 255, 90, 0},
            {160, 255, 190, 20}, {210, 100, 0, 103}, {255, 16, 0, 130}
    };
    private static final int[][] PALETTE_WARM_VU = {
            {0, 200, 200, 200}, {64, 255, 218, 0}, {128, 231, 0, 0},
            {192, 255, 218, 0}, {255, 200, 200, 200}
    };
    private static final int[][] PALETTE_COLORFULL = {
            {0, 22, 121, 174}, {1, 10, 85, 5}, {25, 29, 109, 18},
            {60, 59, 138, 42}, {93, 83, 99, 52}, {106, 110, 66, 64},
            {109, 123, 49, 65}, {113, 139, 35, 66}, {116, 192, 117, 98},
            {124, 255, 255, 137}, {168, 100, 180, 155}, {255, 22, 121, 174}
    };

    public static final int MAX_BANDS = 32;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final RectF peakRect = new RectF();
    private final float[] hsvBuffer = new float[3];

    // Style & peak configurations
    private int style = STYLE_CLASSIC_BARS;
    private boolean peakCapsEnabled = true;
    private boolean mirrorFrequencies = false;

    // Oscilloscope rendering structures (Real Time-Domain Analog Trace)
    private static final int OSC_POINTS = 128;
    private static final int MAX_OSC_TRAILS = 5;
    private final float[] oscYPoints = new float[OSC_POINTS + 1];
    private final float[][] oscHistory = new float[MAX_OSC_TRAILS][OSC_POINTS + 1];
    private final boolean[] oscHistoryValid = new boolean[MAX_OSC_TRAILS];
    private int oscHistoryHead = 0;
    private final byte[] rawWaveform = new byte[1024];
    private final android.graphics.Path oscPath = new android.graphics.Path();
    private final android.graphics.Path oscHistoryPath = new android.graphics.Path();
    private final Paint oscPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint oscGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int oscPersistence = 60; // 0..100% CRT phosphor afterglow persistence

    // Peak tracking arrays
    private final float[] peakLevels = new float[MAX_BANDS];
    private final int[] peakHoldFrames = new int[MAX_BANDS];
    private static final int PEAK_HOLD_COUNT = 9; // ~360 ms hold at 40ms frame interval
    private static final float PEAK_DECAY_STEP = 0.025f; // Gravity fall-off speed

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

    /** Redraw interval for the widget. It is decoration in a strip a few pixels tall; 40 ms is plenty. */
    private static final long WIDGET_FRAME_MS = 40;
    /** Level change, in units of the 0..1 scale, below which a redraw would not be visible. */
    private static final float VISIBLE_CHANGE = 0.004f;

    private long lastDrawTime = 0;
    private final float[] drawnLevels = new float[AudioSpectrumEngine.NUM_BANDS_32];
    private String lastDrawnTitleLine;
    private String lastDrawnArtist;
    private android.graphics.Bitmap lastDrawnArt;
    private long lastColonStep = 0;

    // Dynamic signal energy tracking (FireLamp peak breathing)
    private float dynamicEnergy = 0f;
    private float lastDrawnEnergy = 0f;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameCallbackActive) return;
            long now = System.currentTimeMillis();

            if (now - lastDrawTime >= WIDGET_FRAME_MS) {
                long elapsed = now - lastCaptureTime;
                float t = captureIntervalMs > 0
                        ? Math.min(1f, elapsed / (float) captureIntervalMs) : 1f;
                boolean changed = false;
                float framePeak = 0f;
                for (int i = 0; i < bandCount; i++) {
                    renderLevels[i] = prevLevels[i] + (displayLevels[i] - prevLevels[i]) * t;
                    if (Math.abs(renderLevels[i] - drawnLevels[i]) > VISIBLE_CHANGE) changed = true;

                    if (renderLevels[i] > framePeak) framePeak = renderLevels[i];

                    // Update peak hold and gravity decay
                    float lvl = renderLevels[i];
                    if (lvl >= peakLevels[i]) {
                        peakLevels[i] = lvl;
                        peakHoldFrames[i] = PEAK_HOLD_COUNT;
                    } else {
                        if (peakHoldFrames[i] > 0) {
                            peakHoldFrames[i]--;
                        } else {
                            peakLevels[i] -= PEAK_DECAY_STEP;
                            if (peakLevels[i] < lvl) peakLevels[i] = lvl;
                            if (peakLevels[i] > 0.001f) changed = true;
                        }
                    }
                }

                // If in oscilloscope mode and spectrum hasn't registered level, inspect raw PCM
                if (style == STYLE_OSCILLOSCOPE && framePeak < 0.04f) {
                    int wLen = rawWaveform.length;
                    for (int k = 0; k < Math.min(wLen, 256); k++) {
                        float dev = Math.abs((rawWaveform[k] & 0xFF) - 128) / 128.0f;
                        if (dev > framePeak) framePeak = dev;
                    }
                }

                // Smooth dynamic energy with fast punchy attack and analog decay
                if (framePeak > dynamicEnergy) {
                    dynamicEnergy = dynamicEnergy * 0.35f + framePeak * 0.65f;
                } else {
                    dynamicEnergy = dynamicEnergy * 0.91f + framePeak * 0.09f;
                }

                if (Math.abs(dynamicEnergy - lastDrawnEnergy) > 0.005f) changed = true;

                if (advanceFade(now)) changed = true;
                if (changed) {
                    System.arraycopy(renderLevels, 0, drawnLevels, 0, bandCount);
                    lastDrawnEnergy = dynamicEnergy;
                    lastDrawTime = now;
                    invalidate();
                }
            }
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
        peakPaint.setStyle(Paint.Style.FILL);
        oscPaint.setStyle(Paint.Style.STROKE);
        oscPaint.setStrokeCap(Paint.Cap.ROUND);
        oscPaint.setStrokeJoin(Paint.Join.ROUND);
        oscGlowPaint.setStyle(Paint.Style.STROKE);
        oscGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        oscGlowPaint.setStrokeJoin(Paint.Join.ROUND);
        setBackgroundColor(Color.TRANSPARENT);
        recalculateColors();
    }

    public void setStyle(int style) {
        if (this.style != style) {
            this.style = style;
            invalidate();
        }
    }

    public int getStyle() {
        return this.style;
    }

    public void setPeakCapsEnabled(boolean enabled) {
        if (this.peakCapsEnabled != enabled) {
            this.peakCapsEnabled = enabled;
            invalidate();
        }
    }

    public boolean isPeakCapsEnabled() {
        return this.peakCapsEnabled;
    }

    public void setMirrorFrequencies(boolean mirror) {
        if (this.mirrorFrequencies != mirror) {
            this.mirrorFrequencies = mirror;
            invalidate();
        }
    }

    public boolean isMirrorFrequencies() {
        return this.mirrorFrequencies;
    }

    public void setOscPersistence(int persistence) {
        int clamped = Math.max(0, Math.min(100, persistence));
        if (this.oscPersistence != clamped) {
            this.oscPersistence = clamped;
            invalidate();
        }
    }

    public int getOscPersistence() {
        return this.oscPersistence;
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
        recalculateColors();
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        recalculateColors();
        invalidate();
    }

    public boolean isStatusBarLight() {
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
            return false; // Dark background
        } else if (nightMode == Configuration.UI_MODE_NIGHT_NO) {
            return true;  // Light background
        }

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

    public boolean isNightMode() {
        try {
            if (ThemeManager.isNight(getContext())) return true;
        } catch (Throwable ignored) {}
        return !isStatusBarLight();
    }

    public void setBandCount(int count) {
        this.bandCount = (count == 16) ? 16 : 32;
        recalculateColors();
        invalidate();
    }

    public int getBandCount() {
        return this.bandCount;
    }

    /**
     * Interpolates color within a multi-stop FastLED palette.
     */
    private static int samplePalette(int[][] stops, float fraction, int baseAlpha, int hueShift) {
        float frac = Math.max(0f, Math.min(1f, fraction));
        float targetPos = frac * 255f;
        int r = stops[stops.length - 1][1];
        int g = stops[stops.length - 1][2];
        int b = stops[stops.length - 1][3];

        for (int i = 0; i < stops.length - 1; i++) {
            int p1 = stops[i][0];
            int p2 = stops[i + 1][0];
            if (p1 <= targetPos && targetPos <= p2) {
                float span = p2 - p1;
                float t = span > 0 ? (targetPos - p1) / span : 0f;
                r = Math.round(stops[i][1] + (stops[i + 1][1] - stops[i][1]) * t);
                g = Math.round(stops[i][2] + (stops[i + 1][2] - stops[i][2]) * t);
                b = Math.round(stops[i][3] + (stops[i + 1][3] - stops[i][3]) * t);
                break;
            }
        }

        if (hueShift != 0) {
            float[] hsv = new float[3];
            Color.RGBToHSV(r, g, b, hsv);
            hsv[0] = (hsv[0] + hueShift) % 360f;
            int c = Color.HSVToColor(hsv);
            return Color.argb(baseAlpha, Color.red(c), Color.green(c), Color.blue(c));
        }
        return Color.argb(baseAlpha, r, g, b);
    }

    /**
     * Samples the active theme or palette at a given normalized fraction (0.0 to 1.0)
     * using current default alpha.
     */
    public int sampleCurrentTheme(float fraction) {
        return sampleCurrentTheme(fraction, (int) ((alphaPercent / 100f) * 255));
    }

    /**
     * Samples the active theme or palette at a given normalized fraction (0.0 to 1.0)
     * with an explicit alpha channel value (0..255).
     */
    public int sampleCurrentTheme(float fraction, int baseAlpha) {
        baseAlpha = Math.max(0, Math.min(255, baseAlpha));
        boolean isLightBar = isStatusBarLight();

        switch (theme) {
            case THEME_PURPLE_SYNTHWAVE:
                return samplePalette(PALETTE_PURPLE_SYNTHWAVE, fraction, baseAlpha, hueShift);

            case THEME_RAINBOW_SHERBET:
                return samplePalette(PALETTE_RAINBOW_SHERBET, fraction, baseAlpha, hueShift);

            case THEME_OCEAN_BREEZE:
                return samplePalette(PALETTE_OCEAN_BREEZE, fraction, baseAlpha, hueShift);

            case THEME_SUNSET_REAL:
                return samplePalette(PALETTE_SUNSET_REAL, fraction, baseAlpha, hueShift);

            case THEME_WARM_VU:
                return samplePalette(PALETTE_WARM_VU, fraction, baseAlpha, hueShift);

            case THEME_COLORFULL:
                return samplePalette(PALETTE_COLORFULL, fraction, baseAlpha, hueShift);

            case THEME_AUTO_DAY_NIGHT: {
                int autoColor = isLightBar ? 0xFF1A1A1A : 0xFFFFFFFF;
                return Color.argb(baseAlpha, Color.red(autoColor), Color.green(autoColor), Color.blue(autoColor));
            }

            case THEME_MONOCHROME_WHITE:
                return Color.argb(baseAlpha, 255, 255, 255);

            case THEME_MONOCHROME_BLACK:
                return Color.argb(baseAlpha, 26, 26, 26);

            case THEME_SOLID_HUE: {
                hsvBuffer[0] = (float) hueShift;
                hsvBuffer[1] = 1.0f;
                hsvBuffer[2] = 1.0f;
                int solidColor = Color.HSVToColor(hsvBuffer);
                return Color.argb(baseAlpha, Color.red(solidColor), Color.green(solidColor), Color.blue(solidColor));
            }

            case THEME_EQ_GROUPS: {
                int group = Math.min(5, (int) (fraction * 6f));
                int groupBase = GROUP_BASE_COLORS[group];
                return Color.argb(baseAlpha, Color.red(groupBase), Color.green(groupBase), Color.blue(groupBase));
            }

            case THEME_FIRE: {
                float fireHue = fraction * 55f; // 0 (Red) -> 55 (Yellow)
                if (hueShift != 0) fireHue = (fireHue + hueShift) % 360f;
                hsvBuffer[0] = fireHue;
                hsvBuffer[1] = 1.0f;
                hsvBuffer[2] = 1.0f;
                int fc = Color.HSVToColor(hsvBuffer);
                return Color.argb(baseAlpha, Color.red(fc), Color.green(fc), Color.blue(fc));
            }

            case THEME_NEON: {
                float neonHue = 180f + fraction * 140f; // 180 (Cyan) -> 320 (Magenta/Pink)
                if (hueShift != 0) neonHue = (neonHue + hueShift) % 360f;
                hsvBuffer[0] = neonHue;
                hsvBuffer[1] = 1.0f;
                hsvBuffer[2] = 1.0f;
                int nc = Color.HSVToColor(hsvBuffer);
                return Color.argb(baseAlpha, Color.red(nc), Color.green(nc), Color.blue(nc));
            }

            case THEME_SPECTRUM:
            default: {
                float specHue = fraction * 295f; // 0 (Red) -> 295 (Deep Violet)
                if (hueShift != 0) specHue = (specHue + hueShift) % 360f;
                hsvBuffer[0] = specHue;
                hsvBuffer[1] = 1.0f;
                hsvBuffer[2] = 1.0f;
                int sc = Color.HSVToColor(hsvBuffer);
                return Color.argb(baseAlpha, Color.red(sc), Color.green(sc), Color.blue(sc));
            }
        }
    }

    public void recalculateColors() {
        int bands = this.bandCount;
        for (int i = 0; i < bands; i++) {
            float frac = i / (float) Math.max(1, bands - 1);
            resolvedColors[i] = sampleCurrentTheme(frac, 255);
        }
    }

    private boolean normalizationEnabled = false;

    public void setNormalizationEnabled(boolean enabled) {
        this.normalizationEnabled = enabled;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        AudioSpectrumEngine.getInstance().registerListener(this, NativeAnalyzer.CONSUMER_STATUS_BAR);
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
            peakLevels[i] = 0f;
            peakHoldFrames[i] = 0;
        }
        dynamicEnergy = 0f;
        lastDrawnEnergy = 0f;
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

    private int backdropColor = 0;
    private float bandWidthF = 1f;
    private float bandHeightF = 1f;
    private int topInset = 0;
    private int bottomInset = 0;

    private float pauseT = 0f;
    private boolean pausedTarget = false;
    private boolean clockEnabled = false;
    private long lastFadeTick = 0;
    private String clockText = "";

    private static final long FADE_MS = 700;

    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean clockTypefaceTried = false;

    private NowPlaying nowPlaying;
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float marqueeOffset = 0f;

    private String marqueeLine = null;
    private boolean marqueeRunning = false;

    private static final float MARQUEE_PX_PER_S = 55f;
    private static final float MARQUEE_GAP_F = 0.35f;
    private static final float ARTIST_MAX_F = 0.34f;

    public static final int GLYPH_PREVIOUS = 1;
    public static final int GLYPH_PLAY = 2;
    public static final int GLYPH_PAUSE = 3;
    public static final int GLYPH_NEXT = 4;
    public static final int GLYPH_STYLE_CYCLE = 5;

    private int flashGlyph = 0;
    private long flashUntil = 0;
    private final android.graphics.Path glyphPath = new android.graphics.Path();
    private static final long FLASH_MS = 550;

    private boolean screensaverMode = false;
    private final Paint btnBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path sineIconPath = new android.graphics.Path();

    public void flashTransport(int glyph) {
        this.flashGlyph = glyph;
        this.flashUntil = System.currentTimeMillis() + FLASH_MS;
        invalidate();
    }

    public void setNowPlayingSource(NowPlaying source) {
        this.nowPlaying = source;
    }

    public void setScreensaverState(boolean showClock, boolean paused) {
        this.clockEnabled = showClock;
        this.screensaverMode = showClock;
        this.pausedTarget = paused;
        if (!showClock) {
            pauseT = 0f;
        } else if (paused) {
            pauseT = 1f;
        }
    }

    public boolean isScreensaverMode() {
        return screensaverMode;
    }

    public boolean isPointInStyleCycleButton(float x, float y) {
        if (!screensaverMode) return false;
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() - 32f * density;
        float cy = getHeight() - 32f * density;
        float hitRadius = 38f * density;
        float dx = x - cx;
        float dy = y - cy;
        return (dx * dx + dy * dy) <= (hitRadius * hitRadius);
    }

    public boolean isPointInNowPlayingArt(float x, float y) {
        if (nowPlaying == null) return false;
        float h = bottomInset;
        if (h <= 8) return false;
        float top = getHeight() - h;
        if (y < top - h * 0.5f || y > getHeight()) return false;
        float artRightBound = h * 2.8f;
        return x >= 0 && x <= artRightBound;
    }

    public void setInsets(int top, int bottom) {
        this.topInset = Math.max(0, top);
        this.bottomInset = Math.max(0, bottom);
        invalidate();
    }

    public void setTopInset(int px) {
        setInsets(px, bottomInset);
    }

    public void setBackdrop(int color) {
        this.backdropColor = color;
        invalidate();
    }

    public void setBandFractions(float widthFraction, float heightFraction) {
        this.bandWidthF = Math.max(0.01f, Math.min(1f, widthFraction));
        this.bandHeightF = Math.max(0.01f, Math.min(1f, heightFraction));
        invalidate();
    }

    private boolean advanceFade(long now) {
        if (!clockEnabled) return false;
        boolean dirty = false;
        long since = lastFadeTick == 0 ? 0 : now - lastFadeTick;
        lastFadeTick = now;
        float target = pausedTarget ? 1f : 0f;
        if (pauseT != target && since > 0) {
            float step = since / (float) FADE_MS;
            if (pauseT < target) {
                pauseT = Math.min(target, pauseT + step);
            } else {
                pauseT = Math.max(target, pauseT - step);
            }
            dirty = true;
        }
        if (flashUntil > 0) {
            if (now >= flashUntil) {
                flashUntil = 0;
                flashGlyph = 0;
            }
            dirty = true;
        }
        if (marqueeRunning) {
            float step = since * (MARQUEE_PX_PER_S / 1000f);
            if (step > 0f) {
                marqueeOffset += step;
                dirty = true;
            }
        }
        if (pauseT > 0f) {
            String text = currentClockText();
            if (!text.equals(clockText)) {
                clockText = text;
                dirty = true;
            }
            long colonStep = now / 100;
            if (colonStep != lastColonStep) {
                lastColonStep = colonStep;
                dirty = true;
            }
        }
        if (nowPlaying != null) {
            String curTitleLine = nowPlaying.titleLine();
            String curArtist = nowPlaying.artist();
            android.graphics.Bitmap curArt = nowPlaying.art();
            boolean metaChanged = false;
            if (curTitleLine == null ? lastDrawnTitleLine != null : !curTitleLine.equals(lastDrawnTitleLine)) {
                metaChanged = true;
            }
            if (curArtist == null ? lastDrawnArtist != null : !curArtist.equals(lastDrawnArtist)) {
                metaChanged = true;
            }
            if (curArt != lastDrawnArt) {
                metaChanged = true;
            }
            if (metaChanged) {
                lastDrawnTitleLine = curTitleLine;
                lastDrawnArtist = curArtist;
                lastDrawnArt = curArt;
                dirty = true;
            }
        }
        return dirty;
    }

    private void applyClockTypeface() {
        if (clockTypefaceTried) return;
        clockTypefaceTried = true;
        try {
            android.graphics.Typeface face =
                    androidx.core.content.res.ResourcesCompat.getFont(getContext(), R.font.digital);
            if (face != null) clockPaint.setTypeface(face);
        } catch (Throwable ignored) {
        }
    }

    private String[] currentClockParts() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
        int hour = h24 ? c.get(java.util.Calendar.HOUR_OF_DAY) : c.get(java.util.Calendar.HOUR);
        if (!h24 && hour == 0) hour = 12;
        return new String[]{
                String.valueOf(hour),
                String.format(java.util.Locale.US, "%02d", c.get(java.util.Calendar.MINUTE))
        };
    }

    private String currentClockText() {
        String[] parts = currentClockParts();
        return parts[0] + ":" + parts[1];
    }

    /**
     * Maps physical column index {@code i} to frequency band index.
     * When {@code mirrorFrequencies} is active, bass (20 Hz) is placed in the center,
     * while treble (20 kHz) shimmers toward both outer edges.
     */
    private int getBandIndex(int i, int count) {
        if (!mirrorFrequencies) return i;
        int half = count / 2;
        if (i < half) {
            float frac = (half - 1 - i) / (float) Math.max(1, half - 1);
            return Math.min(count - 1, Math.max(0, Math.round(frac * (count - 1))));
        } else {
            float frac = (i - half) / (float) Math.max(1, half - 1);
            return Math.min(count - 1, Math.max(0, Math.round(frac * (count - 1))));
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float viewW = getWidth();
        float viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;
        if (backdropColor != 0) canvas.drawColor(backdropColor);

        float usableTop = Math.min(topInset, viewH);
        float usableH = Math.max(1f, viewH - usableTop - Math.min(bottomInset, viewH - usableTop));
        float w = viewW * bandWidthF;
        float totalH = usableH * bandHeightF;
        float offsetX = (viewW - w) / 2f;
        float offsetY = usableTop + (usableH - totalH) / 2f;
        if (w <= 0 || totalH <= 0) return;

        // The bars sink as the clock arrives, and stop being drawn once they have nothing left.
        float barScale = 1f - pauseT;
        if (barScale > 0.01f) {
            float bottom = offsetY + totalH;
            float centerY = offsetY + totalH / 2f;

            // Dynamic Peak Breathing (FireLamp peak breathing)
            // Scales dynamically from resting baseline (20%) up to 100% of user ceiling (alphaPercent)
            float masterCeiling = (alphaPercent / 100f) * barScale;
            float minFloor = 0.20f;
            float dynamicFactor = minFloor + (1.0f - minFloor) * Math.min(1.0f, dynamicEnergy);
            int currentAlpha = Math.max(0, Math.min(255, Math.round(masterCeiling * dynamicFactor * 255f)));

            if (style == STYLE_OSCILLOSCOPE) {
                drawOscilloscope(canvas, offsetX, w, centerY, totalH, barScale, currentAlpha);
            } else {
                int count = this.bandCount;
                float stepX = w / (float) count;
                float barGap = stepX * (count == 32 ? 0.20f : 0.22f);
                float barWidth = stepX - barGap;
                float cornerRadius = barWidth * 0.35f;

                float peakCapHeight = Math.max(2.5f, barWidth * 0.32f);
                float peakCapRadius = cornerRadius * 0.5f;

                for (int i = 0; i < count; i++) {
                    int srcIdx = getBandIndex(i, count);
                    float level = renderLevels[srcIdx];
                    if (level < 0.02f) level = 0.02f; // Keep a small visible baseline bar

                    float peakLevel = peakLevels[srcIdx];
                    if (peakLevel < level) peakLevel = level;

                    float left = offsetX + i * stepX + barGap / 2f;
                    float right = left + barWidth;

                    switch (style) {
                        case STYLE_OUTRUN_PEAKS: {
                            // Outrun mode (FireLamp outrunPeak): Only floating peak caps bounce!
                            // Color is dynamically sampled from the active theme/palette by peak height
                            int outrunColor = sampleCurrentTheme(peakLevel, currentAlpha);
                            float peakY = bottom - peakLevel * totalH * 0.88f * barScale;
                            float capTop = peakY - peakCapHeight * 1.5f;
                            float capBtm = peakY;
                            peakPaint.setColor(outrunColor);
                            peakRect.set(left, capTop, right, capBtm);
                            canvas.drawRoundRect(peakRect, peakCapRadius, peakCapRadius, peakPaint);
                            break;
                        }

                        case STYLE_CENTER_BARS: {
                            // Center-out symmetrical bars (FireLamp centerBars): expand up and down from centerY
                            float barHeight = level * totalH * 0.88f * barScale;
                            float halfH = barHeight / 2f;
                            float top = centerY - halfH;
                            float btm = centerY + halfH;

                            // Symmetrical dynamic gradient from center line outward
                            int centerCol = sampleCurrentTheme(0.05f, currentAlpha);
                            int edgeCol = sampleCurrentTheme(Math.max(0.12f, level), currentAlpha);
                            Shader centerShader = new LinearGradient(
                                    left, top, left, btm,
                                    new int[]{edgeCol, centerCol, edgeCol},
                                    new float[]{0.0f, 0.5f, 1.0f},
                                    Shader.TileMode.CLAMP
                            );
                            barPaint.setShader(centerShader);
                            barRect.set(left, top, right, btm);
                            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);
                            barPaint.setShader(null);

                            if (peakCapsEnabled) {
                                float peakHalfH = (peakLevel * totalH * 0.88f * barScale) / 2f;
                                int capAlpha = Math.max(10, Math.min(255, Math.round(currentAlpha * 0.95f)));
                                peakPaint.setColor(0x00FFFFFF | (capAlpha << 24));

                                // Top peak cap
                                float topPeakY = centerY - peakHalfH;
                                peakRect.set(left, topPeakY - peakCapHeight, right, topPeakY);
                                canvas.drawRoundRect(peakRect, peakCapRadius, peakCapRadius, peakPaint);

                                // Bottom peak cap
                                float btmPeakY = centerY + peakHalfH;
                                peakRect.set(left, btmPeakY, right, btmPeakY + peakCapHeight);
                                canvas.drawRoundRect(peakRect, peakCapRadius, peakCapRadius, peakPaint);
                            }
                            break;
                        }

                        case STYLE_PALETTE_GRADIENT: {
                            // Dynamic vertical gradient per bar from baseline to peak (FireLamp paletteBars)
                            float barHeight = level * totalH * 0.88f * barScale;
                            float top = bottom - barHeight;

                            int btmColor = sampleCurrentTheme(0.02f, currentAlpha);
                            int topColor = sampleCurrentTheme(Math.max(0.15f, level), currentAlpha);
                            Shader gradient = new LinearGradient(
                                    left, bottom, left, top,
                                    btmColor, topColor,
                                    Shader.TileMode.CLAMP
                            );
                            barPaint.setShader(gradient);
                            barRect.set(left, top, right, bottom);
                            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);
                            barPaint.setShader(null);

                            if (peakCapsEnabled) {
                                float peakY = bottom - peakLevel * totalH * 0.88f * barScale;
                                int capAlpha = Math.max(10, Math.min(255, Math.round(currentAlpha * 0.95f)));
                                peakPaint.setColor(0x00FFFFFF | (capAlpha << 24));
                                peakRect.set(left, peakY - peakCapHeight, right, peakY);
                                canvas.drawRoundRect(peakRect, peakCapRadius, peakCapRadius, peakPaint);
                            }
                            break;
                        }

                        case STYLE_VU_GRADIENT: {
                            // Studio VU meter: vertical gradient across full height scale (FireLamp verticalColoredBars)
                            float barHeight = level * totalH * 0.88f * barScale;
                            float top = bottom - barHeight;

                            int c0 = sampleCurrentTheme(0.0f, currentAlpha);
                            int c1 = sampleCurrentTheme(0.65f, currentAlpha);
                            int c2 = sampleCurrentTheme(1.0f, currentAlpha);
                            Shader vuShader = new LinearGradient(
                                    0, bottom, 0, offsetY,
                                    new int[]{c0, c1, c2},
                                    new float[]{0.0f, 0.65f, 1.0f},
                                    Shader.TileMode.CLAMP
                            );
                            barPaint.setShader(vuShader);
                            barPaint.setAlpha(currentAlpha);
                            barRect.set(left, top, right, bottom);
                            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);
                            barPaint.setShader(null);

                            if (peakCapsEnabled) {
                                float peakY = bottom - peakLevel * totalH * 0.88f * barScale;
                                int capAlpha = Math.max(10, Math.min(255, Math.round(currentAlpha * 0.95f)));
                                peakPaint.setColor(0x00FFFFFF | (capAlpha << 24));
                                peakRect.set(left, peakY - peakCapHeight, right, peakY);
                                canvas.drawRoundRect(peakRect, peakCapRadius, peakCapRadius, peakPaint);
                            }
                            break;
                        }

                        case STYLE_CLASSIC_BARS:
                        default: {
                            // Classic solid bars (FireLamp horizontalColoredBars)
                            float barHeight = level * totalH * 0.88f * barScale;
                            float top = bottom - barHeight;

                            barPaint.setShader(null);
                            int barColor = (resolvedColors[srcIdx] & 0x00FFFFFF) | (currentAlpha << 24);
                            barPaint.setColor(barColor);
                            barRect.set(left, top, right, bottom);
                            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);

                            if (peakCapsEnabled) {
                                float peakY = bottom - peakLevel * totalH * 0.88f * barScale;
                                int capAlpha = Math.max(10, Math.min(255, Math.round(currentAlpha * 0.95f)));
                                peakPaint.setColor(0x00FFFFFF | (capAlpha << 24));
                                peakRect.set(left, peakY - peakCapHeight, right, peakY);
                                canvas.drawRoundRect(peakRect, peakCapRadius, peakCapRadius, peakPaint);
                            }
                            break;
                        }
                    }
                }
            }
        }

        if (clockEnabled && pauseT > 0.01f) {
            drawClock(canvas, offsetX + w / 2f, offsetY + totalH / 2f, totalH);
        }
        if (nowPlaying != null && bottomInset > 8) {
            drawNowPlaying(canvas, viewW, viewH);
        }
        if (screensaverMode) {
            drawStyleCycleButton(canvas, viewW, viewH);
        }
        if (flashGlyph != 0 && flashUntil > 0) {
            drawTransportFlash(canvas, viewW, viewH);
        }
    }

    private void drawOscilloscope(Canvas canvas, float left, float width, float centerY, float totalH, float barScale, int currentAlpha) {
        if (width <= 0 || totalH <= 0) return;
        float maxAmp = (totalH / 2f) * 0.92f * barScale;

        // 1. Fetch genuine live PCM audio waveform from AudioSpectrumEngine
        int waveLen = AudioSpectrumEngine.getInstance().getLatestWaveform(rawWaveform);

        // 2. Hardware-like Zero-Crossing Trigger
        // Finds positive slope zero-crossing (<= 128 to > 130) to freeze periodic musical waves in phase
        int triggerOffset = 0;
        if (waveLen > 32) {
            int searchLimit = Math.min(waveLen / 2, 256);
            for (int i = 1; i < searchLimit; i++) {
                int prev = (rawWaveform[i - 1] & 0xFF);
                int curr = (rawWaveform[i] & 0xFF);
                if (prev <= 128 && curr > 130) {
                    triggerOffset = i;
                    break;
                }
            }
        }

        // 3. Timebase: sweep across ~512 samples (~10.7 ms at 48kHz, optimal scope division)
        int sweepSamples = Math.min(waveLen - triggerOffset, 512);
        if (sweepSamples < OSC_POINTS) sweepSamples = Math.max(1, waveLen - triggerOffset);

        float stepX = width / (float) OSC_POINTS;
        oscPath.reset();

        float strokeW = Math.max(2.2f, totalH * 0.055f);
        float glowW = strokeW * 2.5f;

        // Build horizontal linear gradient shader matching the active theme/palette
        int startColor = sampleCurrentTheme(0.0f, currentAlpha);
        int midColor = sampleCurrentTheme(0.5f, currentAlpha);
        int endColor = sampleCurrentTheme(1.0f, currentAlpha);
        Shader oscShader = new LinearGradient(
                left, centerY, left + width, centerY,
                new int[]{startColor, midColor, endColor},
                new float[]{0.0f, 0.5f, 1.0f},
                Shader.TileMode.CLAMP
        );

        float maxPeakY = centerY;
        float minPeakY = centerY;
        float maxPeakX = left;
        float minPeakX = left;

        float gain = normalizationEnabled ? 1.4f : 1.1f;

        // Smooth CRT phosphor persistence decay & dynamic beam deflection inertia
        // 0% persistence = 0.05 inertia (ultra fast, crisp digital trace)
        // 100% persistence = 0.35 inertia (viscous analog CRT deflection with physical inertia)
        float inertia = 0.05f + (oscPersistence / 100f) * 0.30f;
        float currentWeight = 1.0f - inertia;

        for (int i = 0; i <= OSC_POINTS; i++) {
            float u = i / (float) OSC_POINTS;
            float px = left + i * stepX;

            float sampleAmp = 0f;
            if (waveLen > 0 && sweepSamples > 0) {
                int sampleIdx = triggerOffset + (int) (u * (sweepSamples - 1));
                sampleIdx = Math.max(0, Math.min(sampleIdx, waveLen - 1));
                int raw = (rawWaveform[sampleIdx] & 0xFF) - 128; // -128..+127
                sampleAmp = (raw / 128.0f) * gain;
            }

            // Gentle cosine edge taper on the outer 3% to anchor cleanly at centerY margins
            float edgeTaper = 1.0f;
            if (u < 0.03f) {
                edgeTaper = (float) (0.5 * (1.0 - Math.cos(Math.PI * (u / 0.03f))));
            } else if (u > 0.97f) {
                edgeTaper = (float) (0.5 * (1.0 - Math.cos(Math.PI * ((1.0 - u) / 0.03f))));
            }

            float targetY = centerY - sampleAmp * maxAmp * edgeTaper;
            oscYPoints[i] = oscYPoints[i] * inertia + targetY * currentWeight;
            float py = oscYPoints[i];

            if (py < minPeakY) {
                minPeakY = py;
                minPeakX = px;
            }
            if (py > maxPeakY) {
                maxPeakY = py;
                maxPeakX = px;
            }
        }

        float persistenceFrac = oscPersistence / 100.0f;
        int activeTrails = persistenceFrac > 0.05f
                ? Math.min(MAX_OSC_TRAILS, Math.max(1, Math.round(persistenceFrac * MAX_OSC_TRAILS)))
                : 0;

        oscGlowPaint.setShader(oscShader);
        oscPaint.setShader(oscShader);

        // Render previous CRT sweep passes fading into the phosphor background
        if (activeTrails > 0) {
            for (int t = activeTrails; t >= 1; t--) {
                int histIdx = (oscHistoryHead - t + MAX_OSC_TRAILS) % MAX_OSC_TRAILS;
                if (!oscHistoryValid[histIdx]) continue;

                // Exponential phosphor decay curve
                float decay = (float) Math.pow(0.55f + 0.35f * persistenceFrac, t);
                int trailAlpha = Math.max(0, Math.min(255, (int) (currentAlpha * 0.70f * decay)));
                if (trailAlpha <= 4) continue;

                oscHistoryPath.reset();
                oscHistoryPath.moveTo(left, oscHistory[histIdx][0]);
                for (int i = 0; i < OSC_POINTS; i++) {
                    float x1 = left + i * stepX;
                    float y1 = oscHistory[histIdx][i];
                    float x2 = left + (i + 1) * stepX;
                    float y2 = oscHistory[histIdx][i + 1];
                    float midX = (x1 + x2) / 2f;
                    oscHistoryPath.cubicTo(midX, y1, midX, y2, x2, y2);
                }

                // Phosphor bloom spreads slightly wider on lingering trails
                float trailGlowW = glowW * (1.0f + 0.12f * t);
                float trailStrokeW = strokeW * (1.0f + 0.06f * t);

                oscGlowPaint.setStrokeWidth(trailGlowW);
                oscGlowPaint.setAlpha(Math.max(4, (int) (trailAlpha * 0.32f)));
                canvas.drawPath(oscHistoryPath, oscGlowPaint);

                oscPaint.setStrokeWidth(trailStrokeW);
                oscPaint.setAlpha(trailAlpha);
                canvas.drawPath(oscHistoryPath, oscPaint);
            }
        }

        // Connect points for the active sweep using smooth cubic Bezier spline
        oscPath.moveTo(left, oscYPoints[0]);
        for (int i = 0; i < OSC_POINTS; i++) {
            float x1 = left + i * stepX;
            float y1 = oscYPoints[i];
            float x2 = left + (i + 1) * stepX;
            float y2 = oscYPoints[i + 1];
            float midX = (x1 + x2) / 2f;
            oscPath.cubicTo(midX, y1, midX, y2, x2, y2);
        }

        // Draw outer CRT phosphor glow for current beam
        oscGlowPaint.setStrokeWidth(glowW);
        oscGlowPaint.setAlpha(Math.max(10, (int) (currentAlpha * 0.38f)));
        canvas.drawPath(oscPath, oscGlowPaint);

        // Draw crisp central beam
        oscPaint.setStrokeWidth(strokeW);
        oscPaint.setAlpha(currentAlpha);
        canvas.drawPath(oscPath, oscPaint);

        oscPaint.setShader(null);
        oscGlowPaint.setShader(null);

        // Record current sweep to historical ring buffer
        System.arraycopy(oscYPoints, 0, oscHistory[oscHistoryHead], 0, OSC_POINTS + 1);
        oscHistoryValid[oscHistoryHead] = true;
        oscHistoryHead = (oscHistoryHead + 1) % MAX_OSC_TRAILS;

        // Optional: draw floating phosphor peak beads at wave extrema
        if (peakCapsEnabled && (Math.abs(minPeakY - centerY) > 3f || Math.abs(maxPeakY - centerY) > 3f)) {
            int beadAlpha = Math.max(10, Math.min(255, (int) (currentAlpha * 0.85f)));
            peakPaint.setColor(0x00FFFFFF | (beadAlpha << 24));
            float beadRadius = strokeW * 0.9f;
            if (Math.abs(minPeakY - centerY) > 3f) {
                canvas.drawCircle(minPeakX, minPeakY, beadRadius, peakPaint);
            }
            if (Math.abs(maxPeakY - centerY) > 3f) {
                canvas.drawCircle(maxPeakX, maxPeakY, beadRadius, peakPaint);
            }
        }
    }

    private float drawArtist(Canvas canvas, String artist, float x, float right, float baseline,
                             float alpha) {
        float room = (right - x) * ARTIST_MAX_F;
        if (room <= 0) return x;

        infoPaint.setColor(android.graphics.Color.argb(
                Math.round(150 * alpha), 255, 255, 255));
        float width = infoPaint.measureText(artist);
        if (width > room) {
            int fits = infoPaint.breakText(artist, true, room - infoPaint.measureText("…"), null);
            if (fits <= 0) return x;
            artist = artist.substring(0, fits).trim() + "…";
            width = infoPaint.measureText(artist);
        }
        canvas.drawText(artist, x, baseline, infoPaint);

        infoPaint.setColor(android.graphics.Color.argb(
                Math.round(235 * alpha), 255, 255, 255));
        return x + width + infoPaint.measureText("   ");
    }

    private void drawTransportFlash(Canvas canvas, float viewW, float viewH) {
        float left = Math.max(0f, (flashUntil - System.currentTimeMillis()) / (float) FLASH_MS);
        if (left <= 0f) return;
        float size = Math.min(viewW, viewH) * 0.18f;
        float cx = viewW / 2f;
        float cy = viewH / 2f;
        int alpha = Math.round(210 * left * (alphaPercent / 100f));
        infoPaint.setColor(android.graphics.Color.argb(alpha, 255, 255, 255));
        infoPaint.setStyle(Paint.Style.FILL);

        float h = size;
        float w = size * 0.62f;
        glyphPath.reset();
        switch (flashGlyph) {
            case GLYPH_PLAY:
                glyphPath.moveTo(cx - w / 2f, cy - h / 2f);
                glyphPath.lineTo(cx + w / 2f, cy);
                glyphPath.lineTo(cx - w / 2f, cy + h / 2f);
                glyphPath.close();
                canvas.drawPath(glyphPath, infoPaint);
                break;
            case GLYPH_PAUSE:
                float bar = w * 0.34f;
                canvas.drawRect(cx - w / 2f, cy - h / 2f, cx - w / 2f + bar, cy + h / 2f, infoPaint);
                canvas.drawRect(cx + w / 2f - bar, cy - h / 2f, cx + w / 2f, cy + h / 2f, infoPaint);
                break;
            case GLYPH_PREVIOUS:
            case GLYPH_NEXT:
                float dir = flashGlyph == GLYPH_NEXT ? 1f : -1f;
                for (int i = 0; i < 2; i++) {
                    float ox = cx + dir * (i * w * 0.55f - w * 0.35f);
                    glyphPath.reset();
                    glyphPath.moveTo(ox - dir * w * 0.28f, cy - h / 2f);
                    glyphPath.lineTo(ox + dir * w * 0.28f, cy);
                    glyphPath.lineTo(ox - dir * w * 0.28f, cy + h / 2f);
                    glyphPath.close();
                    canvas.drawPath(glyphPath, infoPaint);
                }
                float barX = cx + dir * (w * 0.72f);
                canvas.drawRect(Math.min(barX, barX + dir * w * 0.14f), cy - h / 2f,
                        Math.max(barX, barX + dir * w * 0.14f), cy + h / 2f, infoPaint);
                break;
            case GLYPH_STYLE_CYCLE:
                float sineW = size * 1.1f;
                float sineH = size * 0.42f;
                glyphPath.reset();
                glyphPath.moveTo(cx - sineW / 2f, cy);
                glyphPath.cubicTo(
                        cx - sineW * 0.25f, cy - sineH,
                        cx - sineW * 0.25f, cy - sineH,
                        cx, cy
                );
                glyphPath.cubicTo(
                        cx + sineW * 0.25f, cy + sineH,
                        cx + sineW * 0.25f, cy + sineH,
                        cx + sineW / 2f, cy
                );
                infoPaint.setStyle(Paint.Style.STROKE);
                infoPaint.setStrokeWidth(Math.max(3f, size * 0.08f));
                infoPaint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawPath(glyphPath, infoPaint);
                infoPaint.setStyle(Paint.Style.FILL);
                break;
            default:
                break;
        }
    }

    private void drawStyleCycleButton(Canvas canvas, float viewW, float viewH) {
        float density = getResources().getDisplayMetrics().density;
        float radius = 22f * density;
        float cx = viewW - 32f * density;
        float cy = viewH - 32f * density;

        // Background disc
        btnBgPaint.setColor(0x44000000);
        btnBgPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, radius, btnBgPaint);

        // Subtle border
        btnBorderPaint.setColor(0x66FFFFFF);
        btnBorderPaint.setStyle(Paint.Style.STROKE);
        btnBorderPaint.setStrokeWidth(1.2f * density);
        canvas.drawCircle(cx, cy, radius, btnBorderPaint);

        // Oscilloscope sine wave icon
        btnIconPaint.setColor(0xEEFFFFFF);
        btnIconPaint.setStyle(Paint.Style.STROKE);
        btnIconPaint.setStrokeCap(Paint.Cap.ROUND);
        btnIconPaint.setStrokeJoin(Paint.Join.ROUND);
        btnIconPaint.setStrokeWidth(2.2f * density);

        sineIconPath.reset();
        float w = 24f * density;
        float amp = 7f * density;
        float startX = cx - w / 2f;
        sineIconPath.moveTo(startX, cy);
        sineIconPath.cubicTo(
                startX + w * 0.25f, cy - amp * 1.3f,
                startX + w * 0.25f, cy - amp * 1.3f,
                startX + w * 0.5f, cy
        );
        sineIconPath.cubicTo(
                startX + w * 0.75f, cy + amp * 1.3f,
                startX + w * 0.75f, cy + amp * 1.3f,
                startX + w, cy
        );
        canvas.drawPath(sineIconPath, btnIconPaint);
    }

    private void drawNowPlaying(Canvas canvas, float viewW, float viewH) {
        if (nowPlaying == null) return;
        String pkg = nowPlaying.playerPackage();
        boolean hasContent = nowPlaying.hasTrack() || nowPlaying.isPlaying() || (pkg != null && !pkg.isEmpty());
        if (!hasContent) return;

        float h = bottomInset;
        float top = viewH - h;
        float pad = h * 0.16f;
        float alpha = alphaPercent / 100f;
        float x = pad;

        android.graphics.drawable.Drawable icon = nowPlaying.playerIcon();
        if (icon != null) {
            float size = h * 0.54f;
            int left = Math.round(x);
            int iconTop = Math.round(top + (h - size) / 2f);
            icon.setBounds(left, iconTop, Math.round(left + size), Math.round(iconTop + size));
            icon.setAlpha(Math.round(255 * alpha));
            icon.draw(canvas);
            x += size + pad;
        }

        android.graphics.Bitmap art = nowPlaying.art();
        if (art != null && !art.isRecycled()) {
            float size = h * 0.76f;
            android.graphics.RectF dst = new android.graphics.RectF(
                    x, top + (h - size) / 2f, x + size, top + (h + size) / 2f);
            infoPaint.setAlpha(Math.round(255 * alpha));
            canvas.drawBitmap(art, null, dst, infoPaint);
            x += size + pad;
        }

        String line = nowPlaying.titleLine();
        String artist = nowPlaying.artist();
        if (line == null) {
            line = nowPlaying.line();
            artist = null;
        }
        if (line == null || line.trim().isEmpty()) {
            line = nowPlaying.playerLabel();
            artist = null;
        }
        if (line == null) line = "";
        float right = viewW - pad;
        if (line != null && right > x) {
            infoPaint.setTextSize(h * 0.38f);
            infoPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            infoPaint.setColor(android.graphics.Color.argb(
                    Math.round(235 * alpha), 255, 255, 255));
            infoPaint.setTextAlign(Paint.Align.LEFT);
            Paint.FontMetrics fm = infoPaint.getFontMetrics();
            float baseline = top + h / 2f - (fm.ascent + fm.descent) / 2f;

            if (artist != null && !artist.trim().isEmpty()) {
                x = drawArtist(canvas, artist.trim(), x, right, baseline, alpha);
            }
            float avail = right - x;
            float textW = infoPaint.measureText(line);

            if (!line.equals(marqueeLine)) {
                marqueeLine = line;
                marqueeOffset = 0f;
            }

            canvas.save();
            canvas.clipRect(x, top, right, viewH);
            if (textW <= avail) {
                marqueeRunning = false;
                marqueeOffset = 0f;
                canvas.drawText(line, x, baseline, infoPaint);
            } else {
                marqueeRunning = true;
                float span = textW + avail * MARQUEE_GAP_F;
                if (marqueeOffset > span) marqueeOffset -= span;
                canvas.drawText(line, x - marqueeOffset, baseline, infoPaint);
                canvas.drawText(line, x - marqueeOffset + span, baseline, infoPaint);
            }
            canvas.restore();
        } else {
            marqueeRunning = false;
        }

        float p = nowPlaying.progress();
        if (p >= 0f) {
            float lineH = Math.max(2f, h * 0.05f);
            float y = viewH - lineH;
            progressPaint.setColor(android.graphics.Color.argb(
                    Math.round(60 * alpha), 255, 255, 255));
            canvas.drawRect(0f, y, viewW, viewH, progressPaint);
            progressPaint.setColor(android.graphics.Color.argb(
                    Math.round(230 * alpha), 255, 255, 255));
            canvas.drawRect(0f, y, viewW * p, viewH, progressPaint);
        }
    }

    private void drawClock(Canvas canvas, float cx, float cy, float bandHeight) {
        String[] parts = currentClockParts();
        String hourStr = parts[0];
        String minStr = parts[1];
        applyClockTypeface();
        float scale = 0.94f + 0.06f * pauseT;
        float size = Math.max(24f, bandHeight * 0.62f) * scale;
        clockPaint.setTextSize(size);
        clockPaint.setTextAlign(Paint.Align.LEFT);

        int baseAlpha = (int) (255f * (alphaPercent / 100f) * pauseT);
        baseAlpha = Math.max(0, Math.min(255, baseAlpha));

        long now = System.currentTimeMillis();
        float t = (now % 1000) / 1000f;
        float colonFade = (float) (0.5f * (1.0 + Math.cos(2.0 * Math.PI * t)));
        int colonAlpha = Math.round(baseAlpha * (0.15f + 0.85f * colonFade));

        Paint.FontMetrics fm = clockPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;

        float hourW = clockPaint.measureText(hourStr);
        float colonW = clockPaint.measureText(":");
        float minW = clockPaint.measureText(minStr);
        float totalW = hourW + colonW + minW;
        float startX = cx - totalW / 2f;

        // Digits: baseAlpha
        clockPaint.setColor(android.graphics.Color.argb(baseAlpha, 255, 255, 255));
        canvas.drawText(hourStr, startX, baseline, clockPaint);

        // Colon: colonAlpha (smooth 10fps sine fade)
        clockPaint.setColor(android.graphics.Color.argb(colonAlpha, 255, 255, 255));
        canvas.drawText(":", startX + hourW, baseline, clockPaint);

        // Minutes: baseAlpha
        clockPaint.setColor(android.graphics.Color.argb(baseAlpha, 255, 255, 255));
        canvas.drawText(minStr, startX + hourW + colonW, baseline, clockPaint);
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
