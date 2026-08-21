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
 * Manages the Status Bar Visualizer overlay lifecycle, positioning, and audio gating.
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
    public static final String PREF_STATUS_BAR_HEIGHT_PX = "sb_vis_height_px";
    /**
     * How far below the top edge the strip is drawn, in pixels.
     *
     * Zero everywhere the bar starts at the very top, which is nearly everywhere. It exists for
     * the units where the strip on screen belongs to the launcher and does not begin at the edge.
     */
    public static final String PREF_STATUS_BAR_OFFSET_Y = "sb_vis_offset_y";
    public static final String PREF_STATUS_BAR_NORMALIZATION = "sb_vis_normalization";
    public static final String PREF_STATUS_BAR_BANDS   = "sb_vis_bands";

    public static final float DEFAULT_WIDTH_F = 0.40f;
    public static final float DEFAULT_POS_F   = 0.50f;
    public static final int DEFAULT_THEME     = StatusBarVisualizerView.THEME_SPECTRUM;
    public static final int DEFAULT_HUE       = 0;
    public static final int DEFAULT_ALPHA     = 100;
    public static final int DEFAULT_BANDS     = 32;

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
        theme = prefs.getInt(PREF_STATUS_BAR_THEME, DEFAULT_THEME);
        hueShift = prefs.getInt(PREF_STATUS_BAR_HUE, DEFAULT_HUE);
        alphaPercent = prefs.getInt(PREF_STATUS_BAR_ALPHA, DEFAULT_ALPHA);
        bandCount = prefs.getInt(PREF_STATUS_BAR_BANDS, DEFAULT_BANDS);
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

    /**
     * How far down the strip is drawn, never far enough to lose it.
     *
     * <p>The stored number used to be taken as given, and the slider's travel was the whole screen
     * height - so the last few pixels of travel pushed the strip entirely below the bottom edge,
     * where it cannot be seen and cannot be grabbed back. The clamp lives here rather than only in
     * the slider because the height can change afterwards: a strip parked at the bottom and then
     * made taller would walk off the screen on its own.
     */
    public int offsetY() {
        int stored = Math.max(0, prefs.getInt(PREF_STATUS_BAR_OFFSET_Y, 0));
        return Math.min(stored, maxOffsetY());
    }

    /** The lowest offset that still leaves the whole strip on the screen. */
    public int maxOffsetY() {
        return Math.max(0, getScreenHeight() - getStatusBarHeight());
    }

    public void setOffsetY(int px) {
        prefs.edit().putInt(PREF_STATUS_BAR_OFFSET_Y, Math.max(0, px)).apply();
        updateWindowGeometry();
    }

    /**
     * Sets the height by hand, or returns to measuring it.
     *
     * <p>Zero means automatic: the stored value is cleared and {@link #getStatusBarHeight()} goes
     * back to asking the platform. That matters because a person experimenting needs a way back -
     * and because on most units the automatic answer is the right one.
     */
    public void setManualHeight(int px) {
        if (px <= 0) {
            prefs.edit().remove(PREF_STATUS_BAR_HEIGHT_PX).apply();
        } else {
            prefs.edit().putInt(PREF_STATUS_BAR_HEIGHT_PX, px).apply();
        }
        updateWindowGeometry();
    }

    /** The height set by hand, or 0 when it is being measured automatically. */
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
        mainHandler.post(() -> {
            loadPreferences();
            if (visualizerView != null) {
                visualizerView.setTheme(theme);
                visualizerView.setHueShift(hueShift);
                visualizerView.setAlphaPercent(alphaPercent);
                visualizerView.setBandCount(bandCount);
                visualizerView.setNormalizationEnabled(prefs.getBoolean(PREF_STATUS_BAR_NORMALIZATION, false));
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
            visualizerView.setBandCount(bandCount);
            visualizerView.setNormalizationEnabled(prefs.getBoolean(PREF_STATUS_BAR_NORMALIZATION, false));
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
            layoutParams.y = offsetY();
        } else {
            layoutParams.height = getStatusBarHeight();
            layoutParams.width = calculateWidthPx();
            layoutParams.x = calculateLeftPx();
            layoutParams.y = offsetY();
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
            layoutParams.y = offsetY();
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
        // Clamped again on the way out. The arithmetic above already keeps the strip on screen,
        // but it only does so while the width used here and the width the window actually gets
        // are the same number - and this is the one place where both are known, so it is the
        // cheapest place to guarantee it rather than assume it.
        return Math.max(0, Math.min(freeSpace, (int) (freeSpace * clamped)));
    }

    private int getScreenHeight() {
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        return dm.heightPixels;
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

    public void setBandCount(int bands) {
        this.bandCount = (bands == 16) ? 16 : 32;
        prefs.edit().putInt(PREF_STATUS_BAR_BANDS, this.bandCount).apply();
        mainHandler.post(() -> {
            if (visualizerView != null) {
                visualizerView.setBandCount(this.bandCount);
            }
        });
    }

    public boolean isEnabled() { return isEnabled; }
    public float getWidthFraction() { return widthFraction; }
    public float getPosFraction() { return posFraction; }
    public int getTheme() { return theme; }
    public int getHueShift() { return hueShift; }
    public int getBandCount() { return bandCount; }
}
