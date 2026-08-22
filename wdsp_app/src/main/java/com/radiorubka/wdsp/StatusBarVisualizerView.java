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

    /** Redraw interval for the widget. It is decoration in a strip a few pixels tall; 30 is plenty. */
    private static final long WIDGET_FRAME_MS = 40;
    /** Level change, in units of the 0..1 scale, below which a redraw would not be visible. */
    private static final float VISIBLE_CHANGE = 0.004f;

    private long lastDrawTime = 0;
    private final float[] drawnLevels = new float[AudioSpectrumEngine.NUM_BANDS_32];

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameCallbackActive) return;
            long now = System.currentTimeMillis();

            // Two brakes, both measured on the K706: this callback used to invalidate on every
            // single display frame whether or not anything had changed, and redrawing the overlay
            // that often cost nearly three times as much processor as the entire measurement
            // chain behind it. The main analyser still runs at full rate; this is the status bar.
            if (now - lastDrawTime >= WIDGET_FRAME_MS) {
                long elapsed = now - lastCaptureTime;
                float t = captureIntervalMs > 0
                        ? Math.min(1f, elapsed / (float) captureIntervalMs) : 1f;
                boolean changed = false;
                for (int i = 0; i < bandCount; i++) {
                    renderLevels[i] = prevLevels[i] + (displayLevels[i] - prevLevels[i]) * t;
                    if (Math.abs(renderLevels[i] - drawnLevels[i]) > VISIBLE_CHANGE) changed = true;
                }
                if (advanceFade(now)) changed = true;
                if (changed) {
                    System.arraycopy(renderLevels, 0, drawnLevels, 0, bandCount);
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
        // The alpha is baked into the colour table, so redrawing with the old table changes
        // nothing. This asked for a repaint and not a recalculation, which is why brightness has
        // never done anything - not from the screensaver, and not from its slider in settings
        // either. It only ever appeared to work when something else happened to rebuild the table.
        recalculateColors();
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

    /**
     * A colour painted behind the bars, and how much of the view they occupy.
     *
     * <p>Both exist for the screensaver, which is this same strip stretched over the screen with
     * everything else dimmed behind it. Drawing that here rather than in a second window is what
     * lets the strip grow without being detached - and a detached visualiser has to find the audio
     * session again, which takes long enough to see.
     */
    private int backdropColor = 0;
    private float bandWidthF = 1f;
    private float bandHeightF = 1f;
    /**
     * How much of the top belongs to the system status bar and cannot be drawn on.
     *
     * <p>The band is centred in what is left rather than in the whole view. Centring on the whole
     * view is arithmetically right and looks wrong: the bar is opaque and always there, so the eye
     * measures the free space, and a band centred on 360 of 720 sits visibly high when the top 72
     * are covered. It also stopped a tall band from hiding its own top under the bar.
     */
    private int topInset = 0;
    /**
     * The strip at the bottom the now-playing bar owns.
     *
     * <p>Reserved whether or not there is a track to show, so the spectrum does not resize itself
     * every time the music pauses. A band that jumps when a song ends looks broken even though
     * nothing is wrong.
     */
    private int bottomInset = 0;

    /**
     * Where the picture is between the bars and the clock: 0 is playing, 1 is paused.
     *
     * <h2>Why a crossfade and not a swap</h2>
     *
     * Because the two are the same object as far as a person is concerned - the thing in the
     * middle of the screen - and things that are one thing do not blink out and reappear as
     * another. The bars sink into their own baseline as they go, which is what they do anyway when
     * the sound stops; all the animation adds is that they keep doing it smoothly instead of
     * freezing wherever the last frame of audio left them.
     */
    private float pauseT = 0f;
    private boolean pausedTarget = false;
    private boolean clockEnabled = false;
    private long lastFadeTick = 0;
    private String clockText = "";

    /** Long enough to read as a movement, short enough not to feel like waiting. */
    private static final long FADE_MS = 700;

    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean clockTypefaceTried = false;

    /**
     * The now-playing strip along the bottom, and how far its text has scrolled.
     *
     * <p>Read straight from {@link NowPlaying} rather than pushed in: the marquee needs a fresh
     * position on every frame anyway, and copying five fields across on a timer would only add a
     * way for the two to disagree.
     */
    private NowPlaying nowPlaying;
    private final Paint infoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float marqueeOffset = 0f;
    private long lastMarqueeTick = 0;
    private boolean marqueeRunning = false;

    /** Slow enough to read at a glance across a car, fast enough not to feel stuck. */
    private static final float MARQUEE_PX_PER_S = 55f;
    /** Blank between the end of the line and where it starts again. */
    private static final float MARQUEE_GAP_F = 0.35f;

    public static final int GLYPH_PREVIOUS = 1;
    public static final int GLYPH_PLAY = 2;
    public static final int GLYPH_PAUSE = 3;
    public static final int GLYPH_NEXT = 4;

    private int flashGlyph = 0;
    private long flashUntil = 0;
    private final android.graphics.Path glyphPath = new android.graphics.Path();

    /** Fixed length of a fade, in milliseconds - long enough to see, short enough to forget. */
    private static final long FLASH_MS = 550;

    /**
     * Confirms which of the three areas was pressed, then gets out of the way.
     *
     * <p>The areas are invisible, so without this a tap in the dark is a guess that only the music
     * can answer - and it answers a second later, by which time the hand has already tapped again.
     */
    public void flashTransport(int glyph) {
        this.flashGlyph = glyph;
        this.flashUntil = System.currentTimeMillis() + FLASH_MS;
        invalidate();
    }

    public void setNowPlayingSource(NowPlaying source) {
        this.nowPlaying = source;
        this.marqueeOffset = 0f;
    }

    /**
     * Turns the clock on and says whether the music is stopped.
     *
     * <p>Called from the screensaver's own two-second tick, so it costs nothing of its own.
     */
    public void setScreensaverState(boolean showClock, boolean paused) {
        this.clockEnabled = showClock;
        this.pausedTarget = paused;
        if (!showClock) {
            pauseT = 0f;
        }
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

    /**
     * Moves the crossfade along, and keeps the clock's own minute ticking.
     *
     * @return whether anything needs redrawing - the frame loop only invalidates when the bars
     *         have moved, and a paused screen has no bars moving at all
     */
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
        }
        return dirty;
    }

    /**
     * The seven-segment face, loaded once.
     *
     * <p>Falls back to the app's own font rather than failing: a clock in the wrong typeface is a
     * clock, and one that refuses to draw is a bug. Loaded lazily because this runs on the drawing
     * thread and the first frame is not the place to touch the resource system if it can be
     * avoided afterwards.
     */
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

    private String currentClockText() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
        int hour = h24 ? c.get(java.util.Calendar.HOUR_OF_DAY) : c.get(java.util.Calendar.HOUR);
        if (!h24 && hour == 0) hour = 12;
        return String.format(java.util.Locale.US, "%d:%02d", hour, c.get(java.util.Calendar.MINUTE));
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
        int count = this.bandCount;
        float stepX = w / (float) count;
        float barGap = stepX * (count == 32 ? 0.20f : 0.22f);
        float barWidth = stepX - barGap;
        float cornerRadius = barWidth * 0.35f;

        float bottom = offsetY + totalH;

        for (int i = 0; i < count; i++) {
            float level = renderLevels[i];
            if (level < 0.02f) level = 0.02f; // Keep a small visible baseline bar

            float barHeight = level * totalH * 0.88f * barScale;
            float left = offsetX + i * stepX + barGap / 2f;
            float right = left + barWidth;
            float top = bottom - barHeight;

            barPaint.setColor(resolvedColors[i]);
            barRect.set(left, top, right, bottom);
            canvas.drawRoundRect(barRect, cornerRadius, cornerRadius, barPaint);
        }
        }

        if (clockEnabled && pauseT > 0.01f) {
            drawClock(canvas, offsetX + w / 2f, offsetY + totalH / 2f, totalH);
        }
        if (nowPlaying != null && bottomInset > 8) {
            drawNowPlaying(canvas, viewW, viewH);
        }
        if (flashGlyph != 0 && flashUntil > 0) {
            drawTransportFlash(canvas, viewW, viewH);
        }
    }

    /** The pressed symbol, fading, drawn over everything and centred on the screen. */
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
            default:
                break;
        }
    }

    /**
     * The strip: who is playing it, its cover, the line, and how far through.
     *
     * <p>Everything is sized from the strip's own height, which the owner sets, so the whole row
     * scales together instead of a fixed icon sitting in a band twice its size.
     */
    private void drawNowPlaying(Canvas canvas, float viewW, float viewH) {
        float h = bottomInset;
        float top = viewH - h;
        float pad = h * 0.16f;
        float alpha = alphaPercent / 100f;
        float x = pad;

        android.graphics.drawable.Drawable icon = nowPlaying.playerIcon();
        if (icon != null) {
            float size = h * 0.46f;
            int left = Math.round(x);
            int iconTop = Math.round(top + (h - size) / 2f);
            icon.setBounds(left, iconTop, Math.round(left + size), Math.round(iconTop + size));
            icon.setAlpha(Math.round(255 * alpha));
            icon.draw(canvas);
            x += size + pad;
        }

        android.graphics.Bitmap art = nowPlaying.art();
        if (art != null && !art.isRecycled()) {
            float size = h * 0.72f;
            android.graphics.RectF dst = new android.graphics.RectF(
                    x, top + (h - size) / 2f, x + size, top + (h + size) / 2f);
            infoPaint.setAlpha(Math.round(255 * alpha));
            canvas.drawBitmap(art, null, dst, infoPaint);
            x += size + pad;
        }

        String line = nowPlaying.line();
        if (line == null) line = nowPlaying.playerLabel();
        float right = viewW - pad;
        if (line != null && right > x) {
            infoPaint.setTextSize(h * 0.38f);
            infoPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            infoPaint.setColor(android.graphics.Color.argb(
                    Math.round(235 * alpha), 255, 255, 255));
            infoPaint.setTextAlign(Paint.Align.LEFT);
            Paint.FontMetrics fm = infoPaint.getFontMetrics();
            float baseline = top + h / 2f - (fm.ascent + fm.descent) / 2f;
            float avail = right - x;
            float textW = infoPaint.measureText(line);

            canvas.save();
            canvas.clipRect(x, top, right, viewH);
            if (textW <= avail) {
                // It fits, so it stays still. A line that scrolls when it does not need to is
                // movement for its own sake, and this thing is meant to be glanced at.
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

    /**
     * The clock, rising into the space the bars are leaving.
     *
     * <p>Sized from the band rather than from a fixed number, so it is the same shape whatever the
     * owner has done with the sliders, and on a tall screen it does not turn into a postage stamp.
     * It grows the last few per cent as it appears, which is what makes it read as arriving rather
     * than as being switched on.
     */
    private void drawClock(Canvas canvas, float cx, float cy, float bandHeight) {
        if (clockText.isEmpty()) clockText = currentClockText();
        applyClockTypeface();
        float scale = 0.94f + 0.06f * pauseT;
        float size = Math.max(24f, bandHeight * 0.62f) * scale;
        clockPaint.setTextSize(size);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        int alpha = (int) (255f * (alphaPercent / 100f) * pauseT);
        clockPaint.setColor(android.graphics.Color.argb(Math.max(0, Math.min(255, alpha)),
                255, 255, 255));
        Paint.FontMetrics fm = clockPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(clockText, cx, baseline, clockPaint);
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
