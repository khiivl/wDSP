package com.radiorubka.wdsp.ui.theme;

import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.radiorubka.wdsp.R;

/**
 * ✨ ПІДТВЕРДЖЕННЯ ДОТИКУ — СПАЛАХ АКЦЕНТНИМ КОЛЬОРОМ З ПІСЛЯСВІЧЕННЯМ 150 мс.
 * При натисканні іконка, обводка та текст спалахують яскравим акцентним кольором палітри
 * без жодного засірення або втрати меж кнопки.
 */
public final class TouchGlow {

    private static final long AFTERGLOW_MS = 150;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private TouchGlow() {
    }

    @SuppressWarnings("ClickableViewAccessibility")
    public static void attach(View v) {
        if (v == null) return;
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    flash(view, true);
                    break;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    view.performClick();
                    flash(view, false);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    flash(view, false);
                    break;
                default:
                    break;
            }
            return true;
        });
    }

    public static void flash(View v, boolean down) {
        if (v == null) return;
        float density = v.getResources().getDisplayMetrics().density;
        float shift = 2.0f * density;
        if (down) {
            UI.removeCallbacksAndMessages(v);
            v.setTranslationX(shift);
            v.setTranslationY(shift);
            applyGlow(v, true);
            return;
        }
        UI.postAtTime(() -> {
            v.setTranslationX(0f);
            v.setTranslationY(0f);
            applyGlow(v, false);
        }, v, android.os.SystemClock.uptimeMillis() + AFTERGLOW_MS);
    }

    private static void applyGlow(View v, boolean on) {
        if (v.getBackground() instanceof FrostedGlassDrawable) {
            // FrostedGlassDrawable handles its own glass texture, bevel, and 3-pass shadow drop on press.
            // Do not override its background tint, stroke, or checked styling!
            return;
        }

        int accent = ThemeManager.accent(v.getContext());
        int border = ThemeManager.panelBorder(v.getContext());

        if (v instanceof MaterialButton) {
            MaterialButton mb = (MaterialButton) v;
            int onAccent = ThemeManager.onAccent(v.getContext());
            if (on) {
                mb.setBackgroundTintList(ColorStateList.valueOf(accent));
                mb.setIconTint(ColorStateList.valueOf(onAccent));
                mb.setTextColor(onAccent);
                mb.setStrokeColor(ColorStateList.valueOf(accent));
            } else {
                mb.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.parseColor("#20121820")));
                mb.setIconTint(ColorStateList.valueOf(accent));
                mb.setTextColor(accent);
                mb.setStrokeColor(ColorStateList.valueOf(border));
            }
            return;
        }

        if (v instanceof ImageView) {
            ImageView iv = (ImageView) v;
            if (on) {
                iv.setColorFilter(accent);
            } else {
                iv.clearColorFilter();
            }
            return;
        }

        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (on) {
                if (tv.getTag(R.id.tag_glow_color) == null) {
                    tv.setTag(R.id.tag_glow_color, tv.getCurrentTextColor());
                }
                tv.setTextColor(accent);
            } else {
                Object saved = tv.getTag(R.id.tag_glow_color);
                if (saved instanceof Integer) {
                    tv.setTextColor((Integer) saved);
                    tv.setTag(R.id.tag_glow_color, null);
                }
            }
        }
    }
}
