package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Remembers whether the last attempt to put a window over other apps worked.
 *
 * <p>The screensaver and the status-bar strip are both overlay windows, and both used to fail in
 * silence: the exception was caught and logged, and the owner saw only that the feature "does
 * nothing". On this platform the grant behind them can read as given and still not work
 * ({@code platform/01-SYSTEM.md} §7), so the one honest witness is the attempt itself. It is kept
 * here so the permissions wizard can paint the overlay card by it and the screen report can say it.
 *
 * <p>Stored with the unit's own state, which backups do not carry.
 */
public final class OverlayHealth {
    private static final String PREFS = "wdsp_device_state";
    private static final String KEY_REFUSED = "overlay_last_refused";
    private static final String KEY_WHO = "overlay_last_who";
    private static final String KEY_WHY = "overlay_last_why";
    private static final String KEY_AT = "overlay_last_at";

    private OverlayHealth() {
    }

    public static void recordOk(Context context, String who) {
        write(context, false, who, "ok");
    }

    public static void recordRefused(Context context, String who, Throwable t) {
        write(context, true, who, t == null ? "unknown"
                : t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    /** True when the last window we tried to put over other apps was refused. */
    public static boolean lastAttemptRefused(Context context) {
        return context != null && prefs(context).getBoolean(KEY_REFUSED, false);
    }

    /** One line for the screen report. */
    public static String describe(Context context) {
        if (context == null) return "OVERLAY: unknown";
        SharedPreferences p = prefs(context);
        long at = p.getLong(KEY_AT, 0L);
        boolean api = Settings.canDrawOverlays(context);
        if (at == 0L) return "OVERLAY: no window attempted yet, canDrawOverlays=" + api;
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(at));
        return "OVERLAY: last attempt by " + p.getString(KEY_WHO, "?") + " at " + when + " - "
                + (p.getBoolean(KEY_REFUSED, false) ? "REFUSED (" + p.getString(KEY_WHY, "") + ")" : "ok")
                + ", canDrawOverlays=" + api;
    }

    private static void write(Context context, boolean refused, String who, String why) {
        if (context == null) return;
        prefs(context).edit()
                .putBoolean(KEY_REFUSED, refused)
                .putString(KEY_WHO, who)
                .putString(KEY_WHY, why)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
