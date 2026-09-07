package com.radiorubka.wdsp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

/**
 * Manages the Status Bar Visualizer overlay lifecycle, positioning, styling, and audio gating.
 * Follows the non-intrusive status bar overlay design from TopBarWidget.
 */
public class StatusBarVisualizerManager {
    private static final String TAG = "wDSP_StatusBarVisMgr";

    public static final String PREF_STATUS_BAR_ENABLED = "sb_vis_enabled";
    public static final String PREF_STATUS_BAR_WIDTH_F = "sb_vis_width_f";
    public static final String PREF_STATUS_BAR_POS_F   = "sb_vis_pos_f";
    public static final String PREF_STATUS_BAR_THEME   = "sb_vis_theme";
    public static final String PREF_STATUS_BAR_HUE     = "sb_vis_hue";
    public static final String PREF_STATUS_BAR_ALPHA   = "sb_vis_alpha";
    public static final String PREF_STATUS_BAR_ALPHA_DAY   = "sb_vis_alpha_day";
    public static final String PREF_STATUS_BAR_ALPHA_NIGHT = "sb_vis_alpha_night";
    public static final String PREF_STATUS_BAR_HEIGHT_PX = "sb_vis_height_px";
    /**
     * How far below the top edge the strip is drawn, in pixels.
     * Zero everywhere the bar starts at the very top.
     */
    public static final String PREF_STATUS_BAR_OFFSET_Y = "sb_vis_offset_y";
    public static final String PREF_STATUS_BAR_NORMALIZATION = "sb_vis_normalization";
    public static final String PREF_STATUS_BAR_BANDS   = "sb_vis_bands";

    // New preferences adapted from FireLamp EffectVU
    public static final String PREF_STATUS_BAR_STYLE   = "sb_vis_style";
    public static final String PREF_STATUS_BAR_STYLE_DAY = "sb_vis_style_day";
    public static final String PREF_STATUS_BAR_STYLE_NIGHT = "sb_vis_style_night";
    public static final String PREF_STATUS_BAR_PEAKS   = "sb_vis_peaks";
    public static final String PREF_STATUS_BAR_MIRROR  = "sb_vis_mirror";
    public static final String PREF_STATUS_BAR_OSC_PERSISTENCE = "sb_vis_osc_persistence";

    public static final float DEFAULT_WIDTH_F = 0.40f;
    public static final float DEFAULT_POS_F   = 0.50f;
    public static final int DEFAULT_THEME     = StatusBarVisualizerView.THEME_SPECTRUM;
    public static final int DEFAULT_HUE       = 0;
    public static final int DEFAULT_ALPHA     = 100;
    public static final int DEFAULT_ALPHA_DAY   = 100;
    public static final int DEFAULT_ALPHA_NIGHT = 70;
    public static final int DEFAULT_BANDS     = 32;

    public static final int STYLE_CLASSIC_BARS     = StatusBarVisualizerView.STYLE_CLASSIC_BARS;
    public static final int STYLE_OUTRUN_PEAKS     = StatusBarVisualizerView.STYLE_OUTRUN_PEAKS;
    public static final int STYLE_PALETTE_GRADIENT = StatusBarVisualizerView.STYLE_PALETTE_GRADIENT;
    public static final int STYLE_CENTER_BARS      = StatusBarVisualizerView.STYLE_CENTER_BARS;
    public static final int STYLE_VU_GRADIENT      = StatusBarVisualizerView.STYLE_VU_GRADIENT;
    public static final int STYLE_OSCILLOSCOPE     = StatusBarVisualizerView.STYLE_OSCILLOSCOPE;

    public static final int DEFAULT_STYLE      = STYLE_CLASSIC_BARS;
    public static final boolean DEFAULT_PEAKS  = true;
    public static final boolean DEFAULT_MIRROR = false;
    public static final int DEFAULT_OSC_PERSISTENCE = 60;

    private final Context context;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private StatusBarVisualizerView visualizerView;
    private WindowManager.LayoutParams layoutParams;
    private boolean isViewAttached = false;

    private boolean isEnabled = false;
    private float widthFraction = DEFAULT_WIDTH_F;
    private float posFraction = DEFAULT_POS_F;
    private int theme = DEFAULT_THEME;
    private int hueShift = DEFAULT_HUE;
    private int alphaPercent = DEFAULT_ALPHA;
    private int bandCount = DEFAULT_BANDS;

    // Visualizer style options
    private int style = DEFAULT_STYLE;
    private boolean peaksEnabled = DEFAULT_PEAKS;
    private boolean mirrorFrequencies = DEFAULT_MIRROR;
    private int oscPersistence = DEFAULT_OSC_PERSISTENCE;
    private boolean normalizationEnabled = false;

    public static int defaultThemeForStyle(int s) {
        switch (s) {
            case STYLE_OUTRUN_PEAKS:
                return StatusBarVisualizerView.THEME_NEON;
            case STYLE_PALETTE_GRADIENT:
                return StatusBarVisualizerView.THEME_RAINBOW_SHERBET;
            case STYLE_CENTER_BARS:
                return StatusBarVisualizerView.THEME_PURPLE_SYNTHWAVE;
            case STYLE_VU_GRADIENT:
                return StatusBarVisualizerView.THEME_WARM_VU;
            case STYLE_OSCILLOSCOPE:
                return StatusBarVisualizerView.THEME_NEON;
            case STYLE_CLASSIC_BARS:
            default:
                return StatusBarVisualizerView.THEME_SPECTRUM;
        }
    }

    public static boolean defaultPeaksForStyle(int s) {
        switch (s) {
            case STYLE_PALETTE_GRADIENT:
            case STYLE_OSCILLOSCOPE:
                return false;
            default:
                return true;
        }
    }

    public static boolean defaultMirrorForStyle(int s) {
        return s == STYLE_CENTER_BARS;
    }

    public static int defaultBandsForStyle(int s) {
        return 32;
    }

    public static int defaultHueForStyle(int s) {
        return 0;
    }

    public static int defaultPersistenceForStyle(int s) {
        return 60;
    }

    public static boolean defaultNormalizationForStyle(int s) {
        return s == STYLE_OSCILLOSCOPE;
    }

    public void loadStyleParameters(int s, boolean night) {
        this.theme = getThemeForStyle(s, night);
        this.hueShift = getHueForStyle(s, night);
        this.bandCount = getBandsForStyle(s);
        this.peaksEnabled = getPeaksForStyle(s);
        this.mirrorFrequencies = getMirrorForStyle(s);
        this.oscPersistence = getPersistenceForStyle(s);
        this.normalizationEnabled = getNormalizationForStyle(s);
    }

    public void loadStyleParameters(int s) {
        loadStyleParameters(s, ThemeManager.isNight(context));
    }

    public void applyCurrentStyleToView(StatusBarVisualizerView v) {
        if (v == null) return;
        v.setStyle(this.style);
        v.setTheme(this.theme);
        v.setHueShift(this.hueShift);
        v.setBandCount(this.bandCount);
        v.setPeakCapsEnabled(this.peaksEnabled);
        v.setMirrorFrequencies(this.mirrorFrequencies);
        v.setOscPersistence(this.oscPersistence);
        v.setNormalizationEnabled(this.normalizationEnabled);
    }

    // Audio gating: Channel 4 (Media) = Active, Channel 2 (Radio) = Inactive
    private int currentChannel = 4; // Default to Media
    private boolean isMuted = false;
    private boolean isScreenOn = true;

    private static StatusBarVisualizerManager instance;

    public static synchronized StatusBarVisualizerManager getInstance(Context context) {
        if (instance == null) {
            instance = new StatusBarVisualizerManager(context.getApplicationContext());
        }
        return instance;
    }

    public StatusBarVisualizerManager(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = ThemeManager.prefs(this.context);

        // Migrate from old EqPresets if present
        SharedPreferences oldPrefs = this.context.getSharedPreferences("EqPresets", Context.MODE_PRIVATE);
        if (oldPrefs.contains(PREF_STATUS_BAR_ENABLED) && !prefs.contains(PREF_STATUS_BAR_ENABLED)) {
            prefs.edit()
                    .putBoolean(PREF_STATUS_BAR_ENABLED, oldPrefs.getBoolean(PREF_STATUS_BAR_ENABLED, false))
                    .putFloat(PREF_STATUS_BAR_WIDTH_F, oldPrefs.getFloat(PREF_STATUS_BAR_WIDTH_F, DEFAULT_WIDTH_F))
                    .putFloat(PREF_STATUS_BAR_POS_F, oldPrefs.getFloat(PREF_STATUS_BAR_POS_F, DEFAULT_POS_F))
                    .putInt(PREF_STATUS_BAR_THEME, oldPrefs.getInt(PREF_STATUS_BAR_THEME, DEFAULT_THEME))
                    .putInt(PREF_STATUS_BAR_HUE, oldPrefs.getInt(PREF_STATUS_BAR_HUE, DEFAULT_HUE))
                    .apply();
        }

        loadPreferences();
    }

    public void loadPreferences() {
        isEnabled = prefs.getBoolean(PREF_STATUS_BAR_ENABLED, true);
        widthFraction = prefs.getFloat(PREF_STATUS_BAR_WIDTH_F, DEFAULT_WIDTH_F);
        posFraction = prefs.getFloat(PREF_STATUS_BAR_POS_F, DEFAULT_POS_F);
        boolean night = ThemeManager.isNight(context);
        alphaPercent = getAlphaPercent(night);
        style = getStyle(night);
        loadStyleParameters(style, night);
    }

    public boolean canDrawOverlays() {
        return Settings.canDrawOverlays(context);
    }

    public int getStatusBarHeight() {
        int customHeight = prefs.getInt(PREF_STATUS_BAR_HEIGHT_PX, 0);
        if (customHeight > 0) {
            return customHeight;
        }

        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            int h = context.getResources().getDimensionPixelSize(resId);
            if (h > 0) {
                prefs.edit().putInt(PREF_STATUS_BAR_HEIGHT_PX, h).apply();
                return h;
            }
        }

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int fallback = (int) (28 * dm.density);
        prefs.edit().putInt(PREF_STATUS_BAR_HEIGHT_PX, fallback).apply();
        return fallback;
    }

    public int offsetY() {
        int stored = Math.max(0, prefs.getInt(PREF_STATUS_BAR_OFFSET_Y, 0));
        return Math.min(stored, maxOffsetY());
    }

    public int systemStatusBarHeight() {
        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            int h = context.getResources().getDimensionPixelSize(resId);
            if (h > 0) return h;
        }
        return 0;
    }

    public int maxOffsetY() {
        return Math.max(0, screenHeight() - getStatusBarHeight());
    }

    public void setOffsetY(int px) {
        prefs.edit().putInt(PREF_STATUS_BAR_OFFSET_Y, Math.max(0, px)).apply();
        updateWindowGeometry();
    }

    public void setManualHeight(int px) {
        if (px <= 0) {
            prefs.edit().remove(PREF_STATUS_BAR_HEIGHT_PX).apply();
        } else {
            prefs.edit().putInt(PREF_STATUS_BAR_HEIGHT_PX, px).apply();
        }
        updateWindowGeometry();
    }

    public int manualHeight() {
        return prefs.getInt(PREF_STATUS_BAR_HEIGHT_PX, 0);
    }

    public void updateStatusBarHeight(int heightPx) {
        if (heightPx <= 0) return;
        int current = prefs.getInt(PREF_STATUS_BAR_HEIGHT_PX, 0);
        if (current != heightPx) {
            Log.i(TAG, "Calibrating status bar height: " + heightPx + "px (was " + current + "px)");
            prefs.edit().putInt(PREF_STATUS_BAR_HEIGHT_PX, heightPx).apply();
            mainHandler.post(this::updateWindowGeometry);
        }
    }

    public int screenWidth() {
        return realDisplaySize()[0];
    }

    public int screenHeight() {
        return realDisplaySize()[1];
    }

    private int[] realDisplaySize() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Rect bounds =
                        windowManager.getCurrentWindowMetrics().getBounds();
                if (bounds.width() > 0 && bounds.height() > 0) {
                    return new int[]{bounds.width(), bounds.height()};
                }
            } else {
                android.graphics.Point point = new android.graphics.Point();
                windowManager.getDefaultDisplay().getRealSize(point);
                if (point.x > 0 && point.y > 0) return new int[]{point.x, point.y};
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not read the display size, falling back to resources", t);
        }
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private int getScreenWidth() {
        return screenWidth();
    }

    public void setAudioGating(int channel, boolean muted) {
        this.currentChannel = channel;
        this.isMuted = muted;
        mainHandler.post(this::evaluateVisibility);
    }

    public void setScreenState(boolean screenOn) {
        this.isScreenOn = screenOn;
        mainHandler.post(this::evaluateVisibility);
    }

    public void onPreferenceChanged(String key) {
        mainHandler.post(() -> {
            loadPreferences();
            if (visualizerView != null && !isLentToScreensaver()) {
                visualizerView.setAlphaPercent(alphaPercent);
                applyCurrentStyleToView(visualizerView);
            }
            updateWindowGeometry();
            evaluateVisibility();
        });
    }

    public void evaluateVisibility() {
        mainHandler.post(() -> {
            if (isLentToScreensaver()) {
                // When lent to screensaver, visibility is controlled exclusively by ScreensaverManager!
                return;
            }
            boolean shouldShow = isEnabled
                    && canDrawOverlays()
                    && isScreenOn
                    && !isMuted
                    && (currentChannel != 2); // Explicitly hide when Channel 2 (Radio) is active

            if (shouldShow) {
                ensureViewAttached();
                if (visualizerView != null) {
                    visualizerView.setVisibility(View.VISIBLE);
                }
            } else {
                if (visualizerView != null) {
                    visualizerView.setVisibility(View.GONE);
                }
            }
        });
    }

    private void ensureViewAttached() {
        if (!canDrawOverlays()) return;

        if (visualizerView == null) {
            visualizerView = new StatusBarVisualizerView(context);
            visualizerView.setAlphaPercent(alphaPercent);
            applyCurrentStyleToView(visualizerView);
        }

        if (layoutParams == null) {
            int windowType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                //noinspection deprecation
                windowType = WindowManager.LayoutParams.TYPE_PHONE;
            }

            int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

            layoutParams = new WindowManager.LayoutParams(
                    calculateWidthPx(),
                    getStatusBarHeight(),
                    windowType,
                    flags,
                    PixelFormat.TRANSLUCENT
            );
            layoutParams.gravity = Gravity.TOP | Gravity.START;
        }
        fillGeometry();

        if (!isViewAttached) {
            try {
                windowManager.addView(visualizerView, layoutParams);
                isViewAttached = true;
                Log.i(TAG, "StatusBarVisualizer attached to WindowManager.");
            } catch (Throwable t) {
                Log.w(TAG, "Failed to add StatusBarVisualizer view", t);
                isViewAttached = false;
            }
        } else {
            try {
                windowManager.updateViewLayout(visualizerView, layoutParams);
            } catch (Throwable ignored) {}
        }
    }

    private void updateWindowGeometry() {
        if (isViewAttached && visualizerView != null && layoutParams != null) {
            fillGeometry();
            try {
                windowManager.updateViewLayout(visualizerView, layoutParams);
            } catch (Throwable ignored) {}
        }
    }

    private void fillGeometry() {
        if (layoutParams == null) return;
        if (screensaverBounds != null) {
            layoutParams.width = screensaverBounds[0];
            layoutParams.height = screensaverBounds[1];
            layoutParams.x = screensaverBounds[2];
            layoutParams.y = screensaverBounds[3];
            return;
        }
        layoutParams.height = getStatusBarHeight();
        layoutParams.width = calculateWidthPx();
        layoutParams.x = calculateLeftPx();
        layoutParams.y = offsetY();
    }

    // -------------------------------------------------------------------------------------------
    // lending the strip to the screensaver
    // -------------------------------------------------------------------------------------------

    private int[] screensaverBounds;
    private Runnable onBack;

    public boolean isAttached() {
        return isViewAttached && visualizerView != null;
    }

    public void lendToScreensaver(float widthFraction, float heightFraction, int backdrop,
                                 int alphaPercent, int bottomInset, boolean screensaverPaused,
                                 View.OnTouchListener touchHandler, Runnable onBack) {
        this.onBack = onBack;
        if (!isAttached()) {
            ensureViewAttached();
        }
        if (!isAttached()) return;
        screensaverBounds = new int[]{screenWidth(), screenHeight(), 0, 0};
        visualizerView.setAlphaPercent(alphaPercent);
        visualizerView.setBackdrop(backdrop);
        visualizerView.setBandFractions(widthFraction, heightFraction);
        visualizerView.setInsets(systemStatusBarHeight(), bottomInset);
        ScreensaverManager ssMgr = ScreensaverManager.getInstance(context);
        int ssStyle = ssMgr.style();
        visualizerView.setStyle(ssStyle);
        visualizerView.setTheme(ssMgr.theme(ssStyle));
        visualizerView.setHueShift(ssMgr.hueShift(ssStyle));
        visualizerView.setBandCount(ssMgr.bandCount(ssStyle));
        visualizerView.setPeakCapsEnabled(ssMgr.peaksEnabled(ssStyle));
        visualizerView.setMirrorFrequencies(ssMgr.mirrorFrequencies(ssStyle));
        visualizerView.setOscPersistence(ssMgr.oscPersistence(ssStyle));
        visualizerView.setNormalizationEnabled(ssMgr.normalizationEnabled(ssStyle));

        NowPlaying np = NowPlaying.getInstance(context);
        np.refresh();
        boolean isRadio = np.isRadioSource();
        visualizerView.setScreensaverState(true, isRadio || screensaverPaused);
        visualizerView.setNowPlayingSource((np.hasTrack() || np.isPlaying()) ? np : null);
        np.setMetadataListener(visualizerView::postInvalidate);

        layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        if (isRadio) {
            layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            visualizerView.setFocusableInTouchMode(false);
            visualizerView.setOnKeyListener(null);
        } else {
            layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            visualizerView.setFocusableInTouchMode(true);
            visualizerView.setOnKeyListener((view, keyCode, event) -> {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    Runnable back = onBack;
                    if (back != null) back.run();
                    return true;
                }
                return false;
            });
            visualizerView.requestFocus();
        }
        if (touchHandler != null) visualizerView.setOnTouchListener(touchHandler);
        visualizerView.setVisibility(View.VISIBLE);
        visualizerView.start();
        updateWindowGeometry();
    }

    public void setScreensaverStyle(int style) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setStyle(style);
    }

    public void setScreensaverTheme(int theme) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setTheme(theme);
    }

    public void setScreensaverPeaks(boolean enabled) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setPeakCapsEnabled(enabled);
    }

    public void setScreensaverMirror(boolean mirror) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setMirrorFrequencies(mirror);
    }

    public void setScreensaverOscPersistence(int persistence) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setOscPersistence(persistence);
    }

    public void setScreensaverNormalization(boolean norm) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setNormalizationEnabled(norm);
    }

    public void setScreensaverBands(int bands) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setBandCount(bands);
    }

    public void setScreensaverHue(int hue) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setHueShift(hue);
    }

    public void setScreensaverBrightness(int alphaPercent) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setAlphaPercent(alphaPercent);
    }

    public void flashTransport(int glyph) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.flashTransport(glyph);
    }

    public void setScreensaverInfoSource(NowPlaying source) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setNowPlayingSource(source);
    }

    public void setScreensaverNowPlaying(boolean paused) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setScreensaverState(true, paused);
    }

    public void setScreensaverBackdrop(int color) {
        if (!isAttached() || screensaverBounds == null) return;
        visualizerView.setBackdrop(color);
    }

    public void takeBackFromScreensaver() {
        NowPlaying.getInstance(context).setMetadataListener(null);
        screensaverBounds = null;
        if (!isAttached()) return;
        visualizerView.setAlphaPercent(alphaPercent);
        visualizerView.setBackdrop(0);
        visualizerView.setBandFractions(1f, 1f);
        visualizerView.setInsets(0, 0);
        loadStyleParameters(style, ThemeManager.isNight(context));
        applyCurrentStyleToView(visualizerView);
        visualizerView.setScreensaverState(false, false);
        visualizerView.setNowPlayingSource(null);
        visualizerView.setOnTouchListener(null);
        visualizerView.setOnKeyListener(null);
        visualizerView.setFocusableInTouchMode(false);
        onBack = null;
        layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        updateWindowGeometry();
        evaluateVisibility();
    }

    public boolean isLentToScreensaver() {
        return screensaverBounds != null;
    }

    private int calculateWidthPx() {
        int screenW = getScreenWidth();
        float clamped = Math.max(0.10f, Math.min(1.0f, widthFraction));
        return Math.max(1, (int) (screenW * clamped));
    }

    private int calculateLeftPx() {
        int screenW = getScreenWidth();
        int width = calculateWidthPx();
        int freeSpace = Math.max(0, screenW - width);
        float clamped = Math.max(0.0f, Math.min(1.0f, posFraction));
        return Math.max(0, Math.min(freeSpace, (int) (freeSpace * clamped)));
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
        prefs.edit().putBoolean(PREF_STATUS_BAR_ENABLED, enabled).apply();
        evaluateVisibility();
    }

    public void setWidthFraction(float fraction) {
        this.widthFraction = Math.max(0.10f, Math.min(1.0f, fraction));
        prefs.edit().putFloat(PREF_STATUS_BAR_WIDTH_F, this.widthFraction).apply();
        mainHandler.post(this::updateWindowGeometry);
    }

    public void setPosFraction(float fraction) {
        this.posFraction = Math.max(0.0f, Math.min(1.0f, fraction));
        prefs.edit().putFloat(PREF_STATUS_BAR_POS_F, this.posFraction).apply();
        mainHandler.post(this::updateWindowGeometry);
    }

    // --- Per-Style Preferences (EffectVU / FireLamp Architecture) ------------------------------

    public int getThemeForStyle(int s, boolean night) {
        String key = (PREF_STATUS_BAR_THEME + "_" + s) + (night ? "_night" : "_day");
        if (prefs.contains(key)) return prefs.getInt(key, defaultThemeForStyle(s));
        String baseKey = PREF_STATUS_BAR_THEME + "_" + s;
        if (prefs.contains(baseKey)) return prefs.getInt(baseKey, defaultThemeForStyle(s));
        if (s == DEFAULT_STYLE && prefs.contains(PREF_STATUS_BAR_THEME)) {
            return prefs.getInt(PREF_STATUS_BAR_THEME, defaultThemeForStyle(s));
        }
        return defaultThemeForStyle(s);
    }

    public int getThemeForStyle(int s) {
        return getThemeForStyle(s, ThemeManager.isNight(context));
    }

    public void setThemeForStyle(int s, boolean night, int theme) {
        String key = (PREF_STATUS_BAR_THEME + "_" + s) + (night ? "_night" : "_day");
        prefs.edit().putInt(key, theme).apply();
        prefs.edit().putInt(PREF_STATUS_BAR_THEME + "_" + s, theme).apply();
        if (s == DEFAULT_STYLE && !night) {
            prefs.edit().putInt(PREF_STATUS_BAR_THEME, theme).apply();
        }
        if (this.style == s && ThemeManager.isNight(context) == night) {
            this.theme = theme;
            mainHandler.post(() -> {
                if (visualizerView != null && !isLentToScreensaver()) {
                    visualizerView.setTheme(theme);
                }
            });
        }
    }

    public void setThemeForStyle(int s, int theme) {
        setThemeForStyle(s, ThemeManager.isNight(context), theme);
    }

    public int getHueForStyle(int s, boolean night) {
        String key = (PREF_STATUS_BAR_HUE + "_" + s) + (night ? "_night" : "_day");
        if (prefs.contains(key)) return prefs.getInt(key, defaultHueForStyle(s));
        String baseKey = PREF_STATUS_BAR_HUE + "_" + s;
        if (prefs.contains(baseKey)) return prefs.getInt(baseKey, defaultHueForStyle(s));
        if (s == DEFAULT_STYLE && prefs.contains(PREF_STATUS_BAR_HUE)) {
            return prefs.getInt(PREF_STATUS_BAR_HUE, defaultHueForStyle(s));
        }
        return defaultHueForStyle(s);
    }

    public int getHueForStyle(int s) {
        return getHueForStyle(s, ThemeManager.isNight(context));
    }

    public void setHueForStyle(int s, boolean night, int hue) {
        int clamped = Math.max(0, Math.min(360, hue));
        String key = (PREF_STATUS_BAR_HUE + "_" + s) + (night ? "_night" : "_day");
        prefs.edit().putInt(key, clamped).apply();
        prefs.edit().putInt(PREF_STATUS_BAR_HUE + "_" + s, clamped).apply();
        if (s == DEFAULT_STYLE && !night) {
            prefs.edit().putInt(PREF_STATUS_BAR_HUE, clamped).apply();
        }
        if (this.style == s && ThemeManager.isNight(context) == night) {
            this.hueShift = clamped;
            mainHandler.post(() -> {
                if (visualizerView != null && !isLentToScreensaver()) {
                    visualizerView.setHueShift(clamped);
                }
            });
        }
    }

    public void setHueForStyle(int s, int hue) {
        setHueForStyle(s, ThemeManager.isNight(context), hue);
    }

    public int getBandsForStyle(int s) {
        String key = PREF_STATUS_BAR_BANDS + "_" + s;
        if (prefs.contains(key)) return prefs.getInt(key, defaultBandsForStyle(s));
        if (s == DEFAULT_STYLE && prefs.contains(PREF_STATUS_BAR_BANDS)) {
            return prefs.getInt(PREF_STATUS_BAR_BANDS, defaultBandsForStyle(s));
        }
        return defaultBandsForStyle(s);
    }

    public void setBandsForStyle(int s, int bands) {
        int count = (bands == 16) ? 16 : 32;
        prefs.edit().putInt(PREF_STATUS_BAR_BANDS + "_" + s, count).apply();
        if (this.style == s) {
            this.bandCount = count;
            mainHandler.post(() -> {
                if (visualizerView != null && !isLentToScreensaver()) {
                    visualizerView.setBandCount(count);
                }
            });
        }
    }

    public boolean getPeaksForStyle(int s) {
        String key = PREF_STATUS_BAR_PEAKS + "_" + s;
        if (prefs.contains(key)) return prefs.getBoolean(key, defaultPeaksForStyle(s));
        if (s == DEFAULT_STYLE && prefs.contains(PREF_STATUS_BAR_PEAKS)) {
            return prefs.getBoolean(PREF_STATUS_BAR_PEAKS, defaultPeaksForStyle(s));
        }
        return defaultPeaksForStyle(s);
    }

    public void setPeaksForStyle(int s, boolean enabled) {
        prefs.edit().putBoolean(PREF_STATUS_BAR_PEAKS + "_" + s, enabled).apply();
        if (this.style == s) {
            this.peaksEnabled = enabled;
            mainHandler.post(() -> {
                if (visualizerView != null && !isLentToScreensaver()) {
                    visualizerView.setPeakCapsEnabled(enabled);
                }
            });
        }
    }

    public boolean getMirrorForStyle(int s) {
        String key = PREF_STATUS_BAR_MIRROR + "_" + s;
        if (prefs.contains(key)) return prefs.getBoolean(key, defaultMirrorForStyle(s));
        if (s == DEFAULT_STYLE && prefs.contains(PREF_STATUS_BAR_MIRROR)) {
            return prefs.getBoolean(PREF_STATUS_BAR_MIRROR, defaultMirrorForStyle(s));
        }
        return defaultMirrorForStyle(s);
    }

    public void setMirrorForStyle(int s, boolean mirror) {
        prefs.edit().putBoolean(PREF_STATUS_BAR_MIRROR + "_" + s, mirror).apply();
        if (this.style == s) {
            this.mirrorFrequencies = mirror;
            mainHandler.post(() -> {
                if (visualizerView != null && !isLentToScreensaver()) {
                    visualizerView.setMirrorFrequencies(mirror);
                }
            });
        }
    }

    public int getPersistenceForStyle(int s) {
        String key = PREF_STATUS_BAR_OSC_PERSISTENCE + "_" + s;
        if (prefs.contains(key)) return prefs.getInt(key, defaultPersistenceForStyle(s));
        if (s == STYLE_OSCILLOSCOPE && prefs.contains(PREF_STATUS_BAR_OSC_PERSISTENCE)) {
            return prefs.getInt(PREF_STATUS_BAR_OSC_PERSISTENCE, defaultPersistenceForStyle(s));
        }
        return defaultPersistenceForStyle(s);
    }

    public void setPersistenceForStyle(int s, int persistence) {
        int clamped = Math.max(0, Math.min(100, persistence));
        prefs.edit().putInt(PREF_STATUS_BAR_OSC_PERSISTENCE + "_" + s, clamped).apply();
        if (this.style == s) {
            this.oscPersistence = clamped;
            mainHandler.post(() -> {
                if (visualizerView != null && !isLentToScreensaver()) {
                    visualizerView.setOscPersistence(clamped);
                }
            });
        }
    }

    public boolean getNormalizationForStyle(int s) {
        String key = PREF_STATUS_BAR_NORMALIZATION + "_" + s;
        if (prefs.contains(key)) return prefs.getBoolean(key, defaultNormalizationForStyle(s));
        if (s == DEFAULT_STYLE && prefs.contains(PREF_STATUS_BAR_NORMALIZATION)) {
            return prefs.getBoolean(PREF_STATUS_BAR_NORMALIZATION, defaultNormalizationForStyle(s));
        }
        return defaultNormalizationForStyle(s);
    }

    public void setNormalizationForStyle(int s, boolean enabled) {
        prefs.edit().putBoolean(PREF_STATUS_BAR_NORMALIZATION + "_" + s, enabled).apply();
        if (this.style == s) {
            this.normalizationEnabled = enabled;
            mainHandler.post(() -> {
                if (visualizerView != null && !isLentToScreensaver()) {
                    visualizerView.setNormalizationEnabled(enabled);
                }
            });
        }
    }

    // --- Active Style Delegation ---------------------------------------------------------------

    public void setTheme(int theme) {
        setThemeForStyle(this.style, theme);
    }

    public void setHueShift(int hue) {
        setHueForStyle(this.style, hue);
    }

    public void setBandCount(int bands) {
        setBandsForStyle(this.style, bands);
    }

    public void setPeaksEnabled(boolean enabled) {
        setPeaksForStyle(this.style, enabled);
    }

    public void setMirrorFrequencies(boolean mirror) {
        setMirrorForStyle(this.style, mirror);
    }

    public void setOscPersistence(int persistence) {
        setPersistenceForStyle(this.style, persistence);
    }

    public void setNormalizationEnabled(boolean enabled) {
        setNormalizationForStyle(this.style, enabled);
    }

    public int getStyle(boolean night) {
        String key = night ? PREF_STATUS_BAR_STYLE_NIGHT : PREF_STATUS_BAR_STYLE_DAY;
        if (prefs.contains(key)) return prefs.getInt(key, DEFAULT_STYLE);
        return prefs.getInt(PREF_STATUS_BAR_STYLE, DEFAULT_STYLE);
    }

    public void setStyle(boolean night, int style) {
        String key = night ? PREF_STATUS_BAR_STYLE_NIGHT : PREF_STATUS_BAR_STYLE_DAY;
        prefs.edit().putInt(key, style).apply();
        if (!night) {
            prefs.edit().putInt(PREF_STATUS_BAR_STYLE, style).apply();
        }
        if (ThemeManager.isNight(context) == night) {
            this.style = style;
            loadStyleParameters(style, night);
            mainHandler.post(() -> {
                if (visualizerView != null && !isLentToScreensaver()) {
                    applyCurrentStyleToView(visualizerView);
                }
            });
        }
    }

    public void setStyle(int style) {
        setStyle(ThemeManager.isNight(context), style);
    }

    public void removeOverlay() {
        mainHandler.post(() -> {
            if (isViewAttached && visualizerView != null) {
                try {
                    visualizerView.stop();
                    windowManager.removeView(visualizerView);
                } catch (Throwable ignored) {}
                isViewAttached = false;
            }
        });
    }

    public int getAlphaPercent() {
        return getAlphaPercent(ThemeManager.isNight(context));
    }

    public int getAlphaPercent(boolean night) {
        String key = night ? PREF_STATUS_BAR_ALPHA_NIGHT : PREF_STATUS_BAR_ALPHA_DAY;
        int def = night ? DEFAULT_ALPHA_NIGHT : DEFAULT_ALPHA_DAY;
        if (prefs.contains(key)) {
            return prefs.getInt(key, def);
        }
        if (prefs.contains(PREF_STATUS_BAR_ALPHA)) {
            int legacy = prefs.getInt(PREF_STATUS_BAR_ALPHA, DEFAULT_ALPHA);
            return night ? Math.min(legacy, DEFAULT_ALPHA_NIGHT) : legacy;
        }
        return def;
    }

    public void setAlphaPercent(boolean night, int percent) {
        int clamped = Math.max(10, Math.min(100, percent));
        String key = night ? PREF_STATUS_BAR_ALPHA_NIGHT : PREF_STATUS_BAR_ALPHA_DAY;
        SharedPreferences.Editor editor = prefs.edit().putInt(key, clamped);
        if (!night) {
            editor.putInt(PREF_STATUS_BAR_ALPHA, clamped);
        }
        editor.apply();
        if (ThemeManager.isNight(context) == night) {
            this.alphaPercent = clamped;
            mainHandler.post(() -> {
                if (visualizerView != null && !isLentToScreensaver()) {
                    visualizerView.setAlphaPercent(clamped);
                }
            });
        }
    }

    public void setAlphaPercent(int percent) {
        setAlphaPercent(ThemeManager.isNight(context), percent);
    }

    public boolean isEnabled() { return isEnabled; }
    public float getWidthFraction() { return widthFraction; }
    public float getPosFraction() { return posFraction; }
    public int getTheme() { return theme; }
    public int getHueShift() { return hueShift; }
    public int getBandCount() { return bandCount; }
    public int getStyle() { return getStyle(ThemeManager.isNight(context)); }
    public boolean isPeaksEnabled() { return peaksEnabled; }
    public boolean isMirrorFrequencies() { return mirrorFrequencies; }
    public int getOscPersistence() { return oscPersistence; }
    public int oscPersistence() { return oscPersistence; }
    public boolean isNormalizationEnabled() { return normalizationEnabled; }
}
