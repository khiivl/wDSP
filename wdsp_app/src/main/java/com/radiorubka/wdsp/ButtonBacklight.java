package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The head unit's button backlight in wDSP's accent while the main screen is shown, and the unit's
 * own colour back when it is not - the author's 0.5 ({@code setButtonRgbCyan}). His always took the
 * night variant of his cyan; ours takes the accent of the theme on screen, day or night, as the
 * person set it in the theme settings (owner, 03.10.2026: «нашими акцентними кольорами згідно тем
 * дня і ночі»).
 *
 * <p>The accent is scaled to the luminance of the colour the unit had, so the buttons do not get
 * brighter or dimmer than the person set them - only the hue changes. The unit's own colour is read
 * from {@code persist.sys.color.light.value} on every {@link #apply}, so {@link #restore} gives back
 * what it actually was, not a value from first launch; when it cannot be read nothing is touched.
 *
 * <p>MCU message 0x18, sub-command 0x09: {@code [0x09, R, G, B, mode]}, mode 0 = a static colour.
 * Sent on one background thread, so an apply and the restore after it go out in that order.
 */
final class ButtonBacklight {

    /** Off by default: not everyone wants their buttons recoloured (owner, 03.10.2026). */
    static final String PREF_ENABLED = "pref_button_backlight";

    private static final String TAG = "wDSP_Backlight";
    private static final String PROP_UNIT_COLOUR = "persist.sys.color.light.value";
    private static final byte MSG_SETTINGS = 24;
    private static final byte SUB_BUTTON_RGB = 0x09;

    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private boolean captured;
    private int unitR, unitG, unitB;

    /** Paints the buttons in the accent of the theme now on screen. */
    void apply(Context ctx) {
        if (!ThemeManager.prefs(ctx).getBoolean(PREF_ENABLED, false)) return;
        String raw = HardwareProfile.systemProperty(PROP_UNIT_COLOUR);
        if (raw == null) return;                // unreadable: leave the backlight alone
        int packed;
        try {
            packed = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return;                             // malformed: the same
        }
        int r = (packed >> 16) & 0xFF, g = (packed >> 8) & 0xFF, b = packed & 0xFF;
        // Our own colour still on the buttons (a resume without a stop in between): keep the
        // unit's colour captured before, do not learn ours as the unit's.
        if (!captured || packed != lastSentPacked) {
            unitR = r;
            unitG = g;
            unitB = b;
            captured = true;
        }

        int accent = ThemeManager.accent(ctx);
        float unitLuma = luma(unitR, unitG, unitB);
        float accentLuma = luma(Color.red(accent), Color.green(accent), Color.blue(accent));
        float scale = accentLuma > 0f ? unitLuma / accentLuma : 0f;
        send(clamp(Color.red(accent) * scale), clamp(Color.green(accent) * scale), clamp(Color.blue(accent) * scale));
    }

    /** Gives the buttons back the colour the unit had before {@link #apply}. */
    void restore() {
        if (!captured) return;
        send(unitR, unitG, unitB);
    }

    private int lastSentPacked = -1;

    private void send(int r, int g, int b) {
        lastSentPacked = (r << 16) | (g << 8) | b;
        final byte[] payload = {SUB_BUTTON_RGB, (byte) r, (byte) g, (byte) b, 0};
        sender.execute(() -> {
            try {
                if (!McuLink.sendMsg(MSG_SETTINGS, payload)) Log.w(TAG, "mcu_service not available");
            } catch (Exception e) {
                Log.w(TAG, "button RGB not sent: " + e);
            }
        });
    }

    /** Perceived brightness, standard luma weights. */
    private static float luma(int r, int g, int b) {
        return 0.299f * r + 0.587f * g + 0.114f * b;
    }

    private static int clamp(float v) {
        return Math.round(Math.max(0f, Math.min(255f, v)));
    }
}
