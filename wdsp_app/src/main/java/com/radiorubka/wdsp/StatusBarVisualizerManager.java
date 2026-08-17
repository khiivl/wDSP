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

/**
 * Manages the Status Bar Visualizer overlay lifecycle, positioning, and audio gating.
 * Follows the non-intrusive status bar overlay design from TopBarWidget.
 */
public class StatusBarVisualizerManager {
    private static final String TAG = "wDSP_StatusBarVisMgr";

    public static final String PREFS_NAME = "EqPresets";
    public static final String PREF_STATUS_BAR_ENABLED = "sb_vis_enabled";
    public static final String PREF_STATUS_BAR_WIDTH_F = "sb_vis_width_f";
    public static final String PREF_STATUS_BAR_POS_F   = "sb_vis_pos_f";
    public static final String PREF_STATUS_BAR_THEME   = "sb_vis_theme";
    public static final String PREF_STATUS_BAR_HUE     = "sb_vis_hue";
    public static final String PREF_STATUS_BAR_ALPHA   = "sb_vis_alpha";
    public static final String PREF_STATUS_BAR_HEIGHT_PX = "sb_vis_height_px";

    public static final float DEFAULT_WIDTH_F = 0.40f;
    public static final float DEFAULT_POS_F   = 0.50f;
    public static final int DEFAULT_THEME     = StatusBarVisualizerView.THEME_SPECTRUM;
    public static final int DEFAULT_HUE       = 0;
    public static final int DEFAULT_ALPHA     = 100;

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
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadPreferences();
    }

    public void loadPreferences() {
        isEnabled = prefs.getBoolean(PREF_STATUS_BAR_ENABLED, false);
        widthFraction = prefs.getFloat(PREF_STATUS_BAR_WIDTH_F, DEFAULT_WIDTH_F);
        posFraction = prefs.getFloat(PREF_STATUS_BAR_POS_F, DEFAULT_POS_F);
        theme = prefs.getInt(PREF_STATUS_BAR_THEME, DEFAULT_THEME);
        hueShift = prefs.getInt(PREF_STATUS_BAR_HUE, DEFAULT_HUE);
        alphaPercent = prefs.getInt(PREF_STATUS_BAR_ALPHA, DEFAULT_ALPHA);
    }

    public boolean canDrawOverlays() {
        return Settings.canDrawOverlays(context);
    }

    public int getStatusBarHeight() {
        // 1. Check if already measured and saved
        int customHeight = prefs.getInt(PREF_STATUS_BAR_HEIGHT_PX, 0);
        if (customHeight > 0) {
            return customHeight;
        }

        // 2. Read directly from system resources dimen and cache permanently
        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            int h = context.getResources().getDimensionPixelSize(resId);
            if (h > 0) {
                prefs.edit().putInt(PREF_STATUS_BAR_HEIGHT_PX, h).apply();
                return h;
            }
        }

        // 3. Fallback based on density and save
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int fallback = (int) (28 * dm.density);
        prefs.edit().putInt(PREF_STATUS_BAR_HEIGHT_PX, fallback).apply();
        return fallback;
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

    private int getScreenWidth() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return dm.widthPixels;
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
        if (key == null) return;
        mainHandler.post(() -> {
            loadPreferences();
            if (visualizerView != null) {
                visualizerView.setTheme(theme);
                visualizerView.setHueShift(hueShift);
                visualizerView.setAlphaPercent(alphaPercent);
            }
            updateWindowGeometry();
            evaluateVisibility();
        });
    }

    public void evaluateVisibility() {
        mainHandler.post(() -> {
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
            visualizerView.setTheme(theme);
            visualizerView.setHueShift(hueShift);
            visualizerView.setAlphaPercent(alphaPercent);
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
            layoutParams.x = calculateLeftPx();
            layoutParams.y = 0;
        } else {
            layoutParams.height = getStatusBarHeight();
            layoutParams.width = calculateWidthPx();
            layoutParams.x = calculateLeftPx();
            layoutParams.y = 0;
        }

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
            layoutParams.height = getStatusBarHeight();
            layoutParams.width = calculateWidthPx();
            layoutParams.x = calculateLeftPx();
            layoutParams.y = 0;
            try {
                windowManager.updateViewLayout(visualizerView, layoutParams);
            } catch (Throwable ignored) {}
        }
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
        return (int) (freeSpace * clamped);
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

    public void setTheme(int theme) {
        this.theme = theme;
        prefs.edit().putInt(PREF_STATUS_BAR_THEME, theme).apply();
        mainHandler.post(() -> {
            if (visualizerView != null) {
                visualizerView.setTheme(theme);
            }
        });
    }

    public void setHueShift(int hue) {
        this.hueShift = hue;
        prefs.edit().putInt(PREF_STATUS_BAR_HUE, hue).apply();
        mainHandler.post(() -> {
            if (visualizerView != null) {
                visualizerView.setHueShift(hue);
            }
        });
    }

    public boolean isEnabled() { return isEnabled; }
    public float getWidthFraction() { return widthFraction; }
    public float getPosFraction() { return posFraction; }
    public int getTheme() { return theme; }
    public int getHueShift() { return hueShift; }
}
