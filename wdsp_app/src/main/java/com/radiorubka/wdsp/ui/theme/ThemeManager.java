package com.radiorubka.wdsp.ui.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.radiorubka.wdsp.R;

import java.io.InputStream;

/**
 * Керує обчисленням палітри кольорів та шпалер у runtime.
 */
public final class ThemeManager {

    private static final String TAG = "ThemeManager";

    public static final int THEME_MODE_AUTO = 0;
    public static final int THEME_MODE_DAY = 1;
    public static final int THEME_MODE_NIGHT = 2;

    public static final String PREF_THEME_MODE = "theme_mode";
    public static final String PREF_ACCENT_PREFIX = "theme_accent_color_";
    public static final String PREF_PRIMARY_TEXT_PREFIX = "theme_primary_text_color_";
    public static final String PREF_SECONDARY_TEXT_PREFIX = "theme_secondary_text_color_";
    public static final String PREF_ON_ACCENT_TEXT_PREFIX = "theme_on_accent_text_color_";
    public static final String PREF_WALLPAPER_DAY = "theme_wallpaper_day";
    public static final String PREF_WALLPAPER_NIGHT = "theme_wallpaper_night";
    public static final String PREF_SOLID_PREFIX = "theme_solid_enabled_";
    public static final String PREF_SOLID_COLOR_PREFIX = "theme_solid_color_";

    public static final int DEFAULT_ACCENT_COLOR = 0xFF1FE7C4;
    public static final int DEFAULT_PRIMARY_TEXT_COLOR_NIGHT = 0xFFFFFFFF;
    public static final int DEFAULT_PRIMARY_TEXT_COLOR_DAY = 0xFF000000;
    public static final int DEFAULT_SECONDARY_TEXT_COLOR_NIGHT = 0xFF8B9198;
    public static final int DEFAULT_SECONDARY_TEXT_COLOR_DAY = 0xFF56626C;
    public static final int DEFAULT_ON_ACCENT_TEXT_COLOR_NIGHT = 0xFF000000;
    public static final int DEFAULT_ON_ACCENT_TEXT_COLOR_DAY = 0xFFFFFFFF;

    private static String cachedWallpaperKey;
    private static Bitmap cachedWallpaper;

    private ThemeManager() {
    }

    public static SharedPreferences prefs(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static int getThemeMode(Context ctx) {
        return prefs(ctx).getInt(PREF_THEME_MODE, THEME_MODE_AUTO);
    }

    public static void setThemeMode(Context ctx, int mode) {
        prefs(ctx).edit().putInt(PREF_THEME_MODE, mode).apply();
    }

    public static boolean isNight(Context ctx) {
        int mode = getThemeMode(ctx);
        if (mode == THEME_MODE_DAY) {
            return false;
        }
        if (mode == THEME_MODE_NIGHT) {
            return true;
        }
        int uiMode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int accent(Context ctx) {
        return accent(ctx, isNight(ctx));
    }

    public static int accent(Context ctx, boolean night) {
        String key = PREF_ACCENT_PREFIX + (night ? "night" : "day");
        return prefs(ctx).getInt(key, DEFAULT_ACCENT_COLOR);
    }

    public static void setAccent(Context ctx, boolean night, int color) {
        String key = PREF_ACCENT_PREFIX + (night ? "night" : "day");
        prefs(ctx).edit().putInt(key, color).apply();
    }

    public static int textPrimary(Context ctx) {
        return textPrimary(ctx, isNight(ctx));
    }

    public static int textPrimary(Context ctx, boolean night) {
        String key = PREF_PRIMARY_TEXT_PREFIX + (night ? "night" : "day");
        int def = night ? DEFAULT_PRIMARY_TEXT_COLOR_NIGHT : DEFAULT_PRIMARY_TEXT_COLOR_DAY;
        return prefs(ctx).getInt(key, def);
    }

    public static void setTextPrimary(Context ctx, boolean night, int color) {
        String key = PREF_PRIMARY_TEXT_PREFIX + (night ? "night" : "day");
        prefs(ctx).edit().putInt(key, color).apply();
    }

    public static int textSecondary(Context ctx) {
        return textSecondary(ctx, isNight(ctx));
    }

    public static int textSecondary(Context ctx, boolean night) {
        String key = PREF_SECONDARY_TEXT_PREFIX + (night ? "night" : "day");
        int def = night ? DEFAULT_SECONDARY_TEXT_COLOR_NIGHT : DEFAULT_SECONDARY_TEXT_COLOR_DAY;
        return prefs(ctx).getInt(key, def);
    }

    public static void setTextSecondary(Context ctx, boolean night, int color) {
        String key = PREF_SECONDARY_TEXT_PREFIX + (night ? "night" : "day");
        prefs(ctx).edit().putInt(key, color).apply();
    }

    public static int onAccent(Context ctx) {
        return onAccent(ctx, isNight(ctx));
    }

    public static int onAccent(Context ctx, boolean night) {
        String key = PREF_ON_ACCENT_TEXT_PREFIX + (night ? "night" : "day");
        int def = night ? DEFAULT_ON_ACCENT_TEXT_COLOR_NIGHT : DEFAULT_ON_ACCENT_TEXT_COLOR_DAY;
        return prefs(ctx).getInt(key, def);
    }

    public static void setOnAccent(Context ctx, boolean night, int color) {
        String key = PREF_ON_ACCENT_TEXT_PREFIX + (night ? "night" : "day");
        prefs(ctx).edit().putInt(key, color).apply();
    }

    public static int background(Context ctx) {
        return background(isNight(ctx));
    }

    public static int background(boolean night) {
        return night ? Color.parseColor("#0a0d10") : Color.parseColor("#eef1f3");
    }

    public static int cardBackground(Context ctx) {
        return isNight(ctx) ? Color.parseColor("#12161b") : Color.parseColor("#ffffff");
    }

    public static int panelBorder(Context ctx) {
        return panelBorder(ctx, isNight(ctx));
    }

    public static int panelBorder(Context ctx, boolean night) {
        int acc = accent(ctx, night);
        return androidx.core.graphics.ColorUtils.setAlphaComponent(acc, 110);
    }

    public static boolean isSolidWallpaper(Context ctx, boolean night) {
        String key = PREF_SOLID_PREFIX + (night ? "night" : "day");
        return prefs(ctx).getBoolean(key, false);
    }

    public static void setSolidWallpaper(Context ctx, boolean night, boolean solid) {
        String key = PREF_SOLID_PREFIX + (night ? "night" : "day");
        prefs(ctx).edit().putBoolean(key, solid).apply();
    }

    public static int getSolidWallpaperColor(Context ctx, boolean night) {
        String key = PREF_SOLID_COLOR_PREFIX + (night ? "night" : "day");
        int def = night ? Color.parseColor("#101418") : Color.parseColor("#e0e4e8");
        return prefs(ctx).getInt(key, def);
    }

    public static void setSolidWallpaperColor(Context ctx, boolean night, int color) {
        String key = PREF_SOLID_COLOR_PREFIX + (night ? "night" : "day");
        prefs(ctx).edit().putInt(key, color).apply();
    }

    public static String getWallpaperUri(Context ctx, boolean night) {
        return prefs(ctx).getString(night ? PREF_WALLPAPER_NIGHT : PREF_WALLPAPER_DAY, null);
    }

    public static void setWallpaperUri(Context ctx, boolean night, String uri) {
        prefs(ctx).edit().putString(night ? PREF_WALLPAPER_NIGHT : PREF_WALLPAPER_DAY, uri).apply();
    }

    public static Drawable wallpaperBackground(Context ctx) {
        return wallpaperBackground(ctx, isNight(ctx));
    }

    public static Drawable wallpaperBackground(Context ctx, boolean night) {
        if (isSolidWallpaper(ctx, night)) {
            return new ColorDrawable(getSolidWallpaperColor(ctx, night));
        }

        String uriStr = getWallpaperUri(ctx, night);
        if (uriStr == null) {
            int resId = night ? R.drawable.bg_night : R.drawable.bg_day;
            try {
                return ContextCompat.getDrawable(ctx, resId);
            } catch (Exception e) {
                return new ColorDrawable(background(night));
            }
        }
        Bitmap bmp = loadWallpaper(ctx, uriStr);
        if (bmp == null) {
            int resId = night ? R.drawable.bg_night : R.drawable.bg_day;
            try {
                return ContextCompat.getDrawable(ctx, resId);
            } catch (Exception e) {
                return new ColorDrawable(background(night));
            }
        }
        BitmapDrawable img = new BitmapDrawable(ctx.getResources(), bmp);
        int scrim = (background(night) & 0x00FFFFFF) | 0x8C000000;
        return new LayerDrawable(new Drawable[]{img, new ColorDrawable(scrim)});
    }

    private static synchronized Bitmap loadWallpaper(Context ctx, String uriStr) {
        if (uriStr.equals(cachedWallpaperKey) && cachedWallpaper != null && !cachedWallpaper.isRecycled()) {
            return cachedWallpaper;
        }
        try {
            Uri uri = Uri.parse(uriStr);
            int targetW = ctx.getResources().getDisplayMetrics().widthPixels;
            int targetH = ctx.getResources().getDisplayMetrics().heightPixels;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in != null) {
                    BitmapFactory.decodeStream(in, null, opts);
                }
            }
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= targetW && opts.outHeight / (sample * 2) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, decodeOpts);
            }
            if (bmp != null) {
                cachedWallpaperKey = uriStr;
                cachedWallpaper = bmp;
                return bmp;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load wallpaper: " + uriStr, e);
        }
        return null;
    }

    public static Drawable roundedDrawable(Context ctx, float radiusDp, int fillColor, int strokeColor, float strokeWidthDp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusDp * density);
        d.setColor(fillColor);
        if (strokeColor != 0 && strokeWidthDp > 0) {
            d.setStroke(Math.max(1, (int) Math.ceil(strokeWidthDp * density)), strokeColor);
        }
        return d;
    }
}
