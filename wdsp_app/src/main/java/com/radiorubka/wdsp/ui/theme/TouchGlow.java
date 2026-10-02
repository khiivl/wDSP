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
                if (!(mb.getTag(R.id.tag_glow_color) instanceof ButtonLook)) {
                    mb.setTag(R.id.tag_glow_color, new ButtonLook(mb));
                }
                ButtonLook look = (ButtonLook) mb.getTag(R.id.tag_glow_color);
                look.glowBackground = ColorStateList.valueOf(accent);
                look.glowIcon = ColorStateList.valueOf(onAccent);
                look.glowText = ColorStateList.valueOf(onAccent);
                look.glowStroke = ColorStateList.valueOf(accent);
                mb.setBackgroundTintList(look.glowBackground);
                mb.setIconTint(look.glowIcon);
                mb.setTextColor(look.glowText);
                mb.setStrokeColor(look.glowStroke);
            } else {
                // Back to exactly what the theme painted, the way the TextView branch below does
                // it. The release used to paint its own colours - a fixed dark #20121820 behind,
                // the accent on the text and icon - so on a light theme every button sank into
                // dark after a touch, and a checked button lost its checked look.
                Object saved = mb.getTag(R.id.tag_glow_color);
                if (saved instanceof ButtonLook) {
                    ((ButtonLook) saved).restore(mb);
                    mb.setTag(R.id.tag_glow_color, null);
                } else {
                    mb.setStrokeColor(ColorStateList.valueOf(border));
                }
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
            return;
        }

        // Everything else - a SeekBar, a card, a whole row - has no colour of its own to flash,
        // so it dims instead. Without this the Settings screen loses touch feedback entirely on
        // those views: they are neither MaterialButton, ImageView nor TextView.
        v.setAlpha(on ? 0.55f : 1f);
    }

    /**
     * A button's own colours, kept from the press to the release, and the glow's, so the release
     * gives back only what is still the glow's. The click runs between the two: a handler that
     * restyles the button there (it became the active one) must not be undone 150 ms later.
     */
    private static final class ButtonLook {
        final ColorStateList background, icon, text, stroke;
        ColorStateList glowBackground, glowIcon, glowText, glowStroke;

        ButtonLook(MaterialButton mb) {
            background = mb.getBackgroundTintList();
            icon = mb.getIconTint();
            text = mb.getTextColors();
            stroke = mb.getStrokeColor();
        }

        void restore(MaterialButton mb) {
            if (mb.getBackgroundTintList() == glowBackground) mb.setBackgroundTintList(background);
            if (mb.getIconTint() == glowIcon) mb.setIconTint(icon);
            if (mb.getTextColors() == glowText && text != null) mb.setTextColor(text);
            if (mb.getStrokeColor() == glowStroke) mb.setStrokeColor(stroke);
        }
    }
}
