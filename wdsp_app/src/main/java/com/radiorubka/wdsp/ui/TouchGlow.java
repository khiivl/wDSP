package com.radiorubka.wdsp.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.radiorubka.wdsp.R;
import com.radiorubka.wdsp.ui.theme.ThemeManager;

/**
 * Візуальне підтвердження дотику — спалах кольором акценту з післясвіченням 150мс.
 */
public final class TouchGlow {

    private static final long AFTERGLOW_MS = 150;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private TouchGlow() {
    }

    @SuppressWarnings("ClickableViewAccessibility")
    public static void attach(View v) {
        if (v == null) {
            return;
        }
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    flash(view, true);
                    break;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    flash(view, false);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    flash(view, false);
                    break;
                default:
                    break;
            }
            return true;
        });
    }

    public static void flash(View v, boolean down) {
        if (v == null) {
            return;
        }
        if (down) {
            UI.removeCallbacksAndMessages(v);
            applyGlow(v, true);
            return;
        }
        UI.postAtTime(() -> applyGlow(v, false), v,
                android.os.SystemClock.uptimeMillis() + AFTERGLOW_MS);
    }

    private static void applyGlow(View v, boolean on) {
        int accent = ThemeManager.accent(v.getContext());
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
        v.setAlpha(on ? 0.55f : 1f);
    }
}
