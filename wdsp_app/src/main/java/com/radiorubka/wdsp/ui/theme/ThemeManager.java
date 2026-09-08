package com.radiorubka.wdsp.ui.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.textfield.TextInputLayout;
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

    public static final int DEFAULT_ACCENT_COLOR_NIGHT = 0xFF1FE7C4;
    public static final int DEFAULT_ACCENT_COLOR_DAY = 0xFF1FE7C4; // Єдиний фірмовий ціан радіо (Radio Standard)
    public static final int DEFAULT_ACCENT_COLOR = DEFAULT_ACCENT_COLOR_NIGHT;
    public static final int DEFAULT_PRIMARY_TEXT_COLOR_NIGHT = 0xFFFFFFFF;
    public static final int DEFAULT_PRIMARY_TEXT_COLOR_DAY = 0xFF11171D; // Глибокий вугільний (15:1 контраст)
    public static final int DEFAULT_SECONDARY_TEXT_COLOR_NIGHT = 0xFF8B9198;
    public static final int DEFAULT_SECONDARY_TEXT_COLOR_DAY = 0xFF455A64; // Шляхетний графітово-сірий (6.5:1 контраст)
    public static final int DEFAULT_ON_ACCENT_TEXT_COLOR_NIGHT = 0xFF000000;
    public static final int DEFAULT_ON_ACCENT_TEXT_COLOR_DAY = 0xFF000000; // Контрастний чорний на яскравому ціані

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

    /**
     * Визначає, чи є колір темним за формулою відносної яскравості (WCAG / ITU-R BT.601).
     */
    public static boolean isColorDark(int color) {
        double darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0;
        return darkness >= 0.5;
    }

    /**
     * Повертає гарантовано контрастний колір (білий або темний #11171D) для заданого фону.
     */
    public static int getContrastColor(int background) {
        return isColorDark(background) ? Color.WHITE : Color.parseColor("#11171D");
    }

    /**
     * Перевіряє контраст тексту до фону. Якщо контраст занизький (< 2.8),
     * повертає автоматично підібраний контрастний білий/чорний, інакше повертає обраний textColor.
     */
    public static int contrastText(int textColor, int background) {
        float lum1 = (float) ColorUtils.calculateLuminance(textColor);
        float lum2 = (float) ColorUtils.calculateLuminance(background);
        float ratio = (Math.max(lum1, lum2) + 0.05f) / (Math.min(lum1, lum2) + 0.05f);
        if (ratio >= 2.8f) {
            return textColor;
        }
        return getContrastColor(background);
    }

    public static int accent(Context ctx) {
        return accent(ctx, isNight(ctx));
    }

    public static int accent(Context ctx, boolean night) {
        String key = PREF_ACCENT_PREFIX + (night ? "night" : "day");
        int def = night ? DEFAULT_ACCENT_COLOR_NIGHT : DEFAULT_ACCENT_COLOR_DAY;
        return prefs(ctx).getInt(key, def);
    }

    public static void setAccent(Context ctx, boolean night, int color) {
        String key = PREF_ACCENT_PREFIX + (night ? "night" : "day");
        prefs(ctx).edit().putInt(key, color).apply();
    }

    public static void resetPalette(Context ctx, boolean night) {
        String suffix = night ? "night" : "day";
        prefs(ctx).edit()
                .remove(PREF_ACCENT_PREFIX + suffix)
                .remove(PREF_PRIMARY_TEXT_PREFIX + suffix)
                .remove(PREF_SECONDARY_TEXT_PREFIX + suffix)
                .remove(PREF_ON_ACCENT_TEXT_PREFIX + suffix)
                .apply();
    }

    public static int textPrimary(Context ctx) {
        return textPrimary(ctx, isNight(ctx));
    }

    public static int textPrimary(Context ctx, boolean night) {
        String key = PREF_PRIMARY_TEXT_PREFIX + (night ? "night" : "day");
        int def = night ? DEFAULT_PRIMARY_TEXT_COLOR_NIGHT : DEFAULT_PRIMARY_TEXT_COLOR_DAY;
        int color = prefs(ctx).getInt(key, def);
        return contrastText(color, background(night));
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
        int color = prefs(ctx).getInt(key, def);
        return contrastText(color, background(night));
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
        int userOnAccent = prefs(ctx).getInt(key, def);
        int accentColor = accent(ctx, night);
        return contrastText(userOnAccent, accentColor);
    }

    public static void setOnAccent(Context ctx, boolean night, int color) {
        String key = PREF_ON_ACCENT_TEXT_PREFIX + (night ? "night" : "day");
        prefs(ctx).edit().putInt(key, color).apply();
    }

    public static int textMuted(Context ctx) {
        return textMuted(ctx, isNight(ctx));
    }

    public static int textMuted(Context ctx, boolean night) {
        return textSecondary(ctx, night);
    }

    public static int background(Context ctx) {
        return background(isNight(ctx));
    }

    public static int background(boolean night) {
        return night ? Color.parseColor("#0a0d10") : Color.parseColor("#eef1f3");
    }

    public static int cardBackground(Context ctx) {
        return cardBackground(ctx, isNight(ctx));
    }

    public static int cardBackground(Context ctx, boolean night) {
        return night ? Color.parseColor("#12161b") : Color.parseColor("#ffffff");
    }

    public static int panelBorder(Context ctx) {
        return panelBorder(ctx, isNight(ctx));
    }

    public static int panelBorder(Context ctx, boolean night) {
        return night ? Color.parseColor("#1b2126") : Color.parseColor("#d7dde1");
    }

    public static int panelBorder(boolean night) {
        return night ? Color.parseColor("#1b2126") : Color.parseColor("#d7dde1");
    }

    public static int sliderInactiveColor(boolean night) {
        return night ? Color.parseColor("#2A343D") : Color.parseColor("#B0BEC5");
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

    public static Drawable dockBackground(Context ctx) {
        return dockBackground(ctx, isNight(ctx));
    }

    public static Drawable dockBackground(Context ctx, boolean night) {
        int bg = night ? Color.parseColor("#E612161B") : Color.parseColor("#EBF0F4F8");
        int border = panelBorder(ctx, night);
        float d = ctx.getResources().getDisplayMetrics().density;
        float r = 16f * d;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        gd.setColor(bg);
        gd.setStroke(Math.max(1, (int) (1.2f * d)), border);
        return gd;
    }

    public static Drawable cardDrawable(Context ctx) {
        return cardDrawable(ctx, isNight(ctx));
    }

    public static Drawable cardDrawable(Context ctx, boolean night) {
        int bg = night ? Color.parseColor("#D912161B") : Color.parseColor("#E6FFFFFF");
        int border = panelBorder(ctx, night);
        return roundedDrawable(ctx, 16f, bg, border, 1.2f);
    }

    public static Drawable buttonDrawable(Context ctx) {
        return buttonDrawable(ctx, isNight(ctx));
    }

    public static Drawable buttonDrawable(Context ctx, boolean night) {
        int bg = night ? Color.parseColor("#18FFFFFF") : Color.parseColor("#0D000000");
        int border = panelBorder(ctx, night);
        return roundedDrawable(ctx, 10f, bg, border, 1.2f);
    }

    public static ColorStateList bottomNavColorStateList(Context ctx) {
        return bottomNavColorStateList(ctx, isNight(ctx));
    }

    public static ColorStateList bottomNavColorStateList(Context ctx, boolean night) {
        int accent = accent(ctx, night);
        int secondary = textSecondary(ctx, night);
        int[][] states = new int[][]{
            new int[]{android.R.attr.state_checked},
            new int[]{-android.R.attr.state_checked}
        };
        int[] colors = new int[]{
            accent,
            secondary
        };
        return new ColorStateList(states, colors);
    }

    public static Drawable dropdownBackground(Context ctx) {
        return dropdownBackground(ctx, isNight(ctx));
    }

    public static Drawable dropdownBackground(Context ctx, boolean night) {
        int bg = night ? Color.parseColor("#F012161B") : Color.parseColor("#F8FFFFFF");
        int border = panelBorder(ctx, night);
        return roundedDrawable(ctx, 14f, bg, border, 1.2f);
    }

    public static class ThemedDropdownAdapter<T> extends android.widget.ArrayAdapter<T> {
        private final Context context;

        public ThemedDropdownAdapter(Context context, java.util.List<T> objects) {
            super(context, R.layout.item_dropdown_spinner, objects);
            this.context = context;
        }

        public ThemedDropdownAdapter(Context context, T[] objects) {
            super(context, R.layout.item_dropdown_spinner, objects);
            this.context = context;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            TextView tv = (TextView) super.getView(position, convertView, parent);
            boolean night = ThemeManager.isNight(context);
            int cardBg = ThemeManager.cardBackground(context, night);
            tv.setTextColor(ThemeManager.contrastText(ThemeManager.textPrimary(context, night), cardBg));
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            return tv;
        }

        @Override
        public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
            TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
            boolean night = ThemeManager.isNight(context);
            int dropBg = night ? Color.parseColor("#F012161B") : Color.parseColor("#F8FFFFFF");
            tv.setTextColor(ThemeManager.contrastText(ThemeManager.textPrimary(context, night), dropBg));
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            int padH = Math.round(16 * context.getResources().getDisplayMetrics().density);
            int padV = Math.round(10 * context.getResources().getDisplayMetrics().density);
            tv.setPadding(padH, padV, padH, padV);
            return tv;
        }
    }

    public static void tintTextInputLayout(TextInputLayout layout, AutoCompleteTextView spinner, int accent, int secondaryText, int primaryText) {
        Context ctx = layout != null ? layout.getContext() : (spinner != null ? spinner.getContext() : null);
        if (ctx == null) return;
        boolean night = isNight(ctx);
        int cardBg = cardBackground(ctx, night);
        int border = panelBorder(ctx, night);

        if (layout != null) {
            layout.setBoxBackgroundColor(cardBg);
            layout.setBoxStrokeColor(accent);
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_focused},
                    new int[]{}
            };
            int[] colors = new int[]{
                    accent,
                    border
            };
            layout.setBoxStrokeColorStateList(new ColorStateList(states, colors));
            layout.setHintTextColor(ColorStateList.valueOf(secondaryText));
            layout.setDefaultHintTextColor(ColorStateList.valueOf(secondaryText));
            layout.setEndIconTintList(ColorStateList.valueOf(secondaryText));
            float radius = 12 * layout.getResources().getDisplayMetrics().density;
            layout.setBoxCornerRadii(radius, radius, radius, radius);
        }
        if (spinner != null) {
            int textColor = contrastText(primaryText, cardBg);
            spinner.setTextColor(textColor);
            spinner.setBackgroundColor(Color.TRANSPARENT);
            spinner.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
            spinner.setDropDownBackgroundDrawable(dropdownBackground(ctx, night));
        }
    }

    public static void tintTextInputLayout(TextInputLayout layout, AutoCompleteTextView spinner, int accent, int secondaryText) {
        Context ctx = layout != null ? layout.getContext() : (spinner != null ? spinner.getContext() : null);
        int primary = ctx != null ? textPrimary(ctx) : DEFAULT_PRIMARY_TEXT_COLOR_NIGHT;
        tintTextInputLayout(layout, spinner, accent, secondaryText, primary);
    }

    public static int getContrastingTextColor(int backgroundColor) {
        double luminance = ColorUtils.calculateLuminance(backgroundColor);
        return luminance > 0.45 ? 0xFF101418 : 0xFFFFFFFF;
    }

    public static Drawable whiteThumbDrawable(Context ctx) {
        return coloredThumbDrawable(ctx, Color.WHITE);
    }

    public static Drawable coloredThumbDrawable(Context ctx, int color) {
        float d = ctx.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(color);
        thumb.setStroke((int) Math.max(2, 2f * d), Color.WHITE);
        int size = (int) (20 * d);
        thumb.setSize(size, size);
        return thumb;
    }

    public static android.graphics.drawable.GradientDrawable hueGradientDrawable(Context ctx, float radiusDp, int heightDp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int[] colors = new int[361];
        for (int i = 0; i <= 360; i++) {
            colors[i] = Color.HSVToColor(new float[]{i, 1f, 1f});
        }
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, colors);
        g.setCornerRadius(radiusDp * d);
        if (heightDp > 0) {
            g.setSize(-1, (int) (heightDp * d));
        }
        return g;
    }

    public static android.graphics.drawable.GradientDrawable brightnessGradientDrawable(Context ctx, int color, float radiusDp, int heightDp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.BLACK, color});
        g.setCornerRadius(radiusDp * d);
        if (heightDp > 0) {
            g.setSize(-1, (int) (heightDp * d));
        }
        return g;
    }

    public static void styleCustomSeekBar(SeekBar sb, int accent, boolean isNight) {
        if (sb == null) return;
        Context ctx = sb.getContext();
        float d = ctx.getResources().getDisplayMetrics().density;

        int inactiveColor = sliderInactiveColor(isNight);

        android.graphics.drawable.GradientDrawable bgTrack = new android.graphics.drawable.GradientDrawable();
        bgTrack.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bgTrack.setCornerRadius(2.5f * d);
        bgTrack.setColor(inactiveColor);
        bgTrack.setSize(-1, (int) (5 * d));

        android.graphics.drawable.GradientDrawable progressTrack = new android.graphics.drawable.GradientDrawable();
        progressTrack.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        progressTrack.setCornerRadius(2.5f * d);
        progressTrack.setColor(accent);
        progressTrack.setSize(-1, (int) (5 * d));

        android.graphics.drawable.ClipDrawable clipProgress = new android.graphics.drawable.ClipDrawable(
                progressTrack, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);

        android.graphics.drawable.LayerDrawable trackDrawable = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{bgTrack, clipProgress});
        trackDrawable.setId(0, android.R.id.background);
        trackDrawable.setId(1, android.R.id.progress);

        sb.setProgressDrawable(trackDrawable);

        android.graphics.drawable.GradientDrawable thumb = new android.graphics.drawable.GradientDrawable();
        thumb.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        thumb.setColor(accent);
        thumb.setStroke((int) Math.max(1, 1.2f * d), Color.parseColor("#40000000"));
        int thumbSize = (int) (20 * d);
        thumb.setSize(thumbSize, thumbSize);

        sb.setThumb(thumb);
        sb.setSplitTrack(false);
    }

    public static void tintSeekBar(SeekBar sb, int accent) {
        if (sb == null) return;
        styleCustomSeekBar(sb, accent, isNight(sb.getContext()));
    }
}
