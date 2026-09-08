package com.radiorubka.wdsp.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import com.radiorubka.wdsp.R;
import com.radiorubka.wdsp.ui.theme.ThemeManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 🪗 Акордеон для екрана налаштувань wDSP — секції динамічно згортаються/розгортаються
 * зі збереженням стану в SharedPreferences.
 */
public final class SettingsAccordion {

    private static final String PREF_PREFIX = "settings_section_open_";
    public static final String TAG_HEADER = "accordion_header";

    private static final int[] ORDER = {
            R.id.label_theme_section,
            R.id.label_statusbar_section,
            R.id.label_vis_effects_section,
            R.id.label_eq_vis_section,
            R.id.label_analyzer_section,
            R.id.label_permissions_section,
            R.id.label_screensaver_section,
            R.id.label_debug_section,
    };

    private static int orderOf(int id) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == id) {
                return i;
            }
        }
        return ORDER.length;
    }

    private SettingsAccordion() {
    }

    private static int sTextPrimary = 0xFFFFFFFF;
    private static int sAccent = 0xFF1FE7C4;

    private static void setHeaderState(TextView title, View body, boolean open, int accent, int textPrimary) {
        if (body != null) {
            body.setVisibility(open ? View.VISIBLE : View.GONE);
        }
        CharSequence current = title.getText();
        if (current != null) {
            String s = current.toString().trim();
            while (s.startsWith("▾") || s.startsWith("▸")) {
                s = s.substring(1).trim();
            }
            title.setText((open ? "▾ " : "▸ ") + s);
        }
        Context ctx = title.getContext();
        boolean night = ThemeManager.isNight(ctx);
        int padH = Math.round(12 * ctx.getResources().getDisplayMetrics().density);
        int padV = Math.round(8 * ctx.getResources().getDisplayMetrics().density);
        if (open) {
            int openBg = ColorUtils.setAlphaComponent(accent, night ? 38 : 42);
            title.setBackground(ThemeManager.roundedDrawable(ctx, 10f, openBg, accent, 1.2f));
            int openText = night ? accent : 0xFF004D40;
            title.setTextColor(openText);
        } else {
            title.setBackground(null);
            title.setTextColor(textPrimary);
        }
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(null, Typeface.BOLD);
        title.setPadding(padH, padV, padH, padV);
    }

    public static void repaint(LinearLayout column, int accent) {
        repaint(column, ThemeManager.textPrimary(column.getContext()), accent);
    }

    public static void repaint(LinearLayout column, int textPrimary, int accent) {
        repaint(column, textPrimary, accent,
                ThemeManager.cardBackground(column.getContext()),
                ThemeManager.panelBorder(column.getContext()));
    }

    public static void repaint(LinearLayout column, int textPrimary, int accent, int cardBg, int border) {
        sTextPrimary = textPrimary;
        sAccent = accent;
        Context ctx = column.getContext();
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            if (v instanceof TextView && TAG_HEADER.equals(v.getTag())) {
                View body = (i + 1 < column.getChildCount()) ? column.getChildAt(i + 1) : null;
                boolean isOpen = (body != null && body.getVisibility() == View.VISIBLE);
                setHeaderState((TextView) v, body, isOpen, accent, textPrimary);
            } else if (v instanceof LinearLayout) {
                LinearLayout body = (LinearLayout) v;
                for (int j = 0; j < body.getChildCount(); j++) {
                    View child = body.getChildAt(j);
                    int cId = child.getId();
                    if (cId == R.id.card_settings_wallpaper ||
                        cId == R.id.card_settings_statusbar ||
                        cId == R.id.card_settings_effects ||
                        cId == R.id.card_settings_eq ||
                        cId == R.id.card_settings_analyzer ||
                        cId == R.id.card_settings_permissions ||
                        cId == R.id.card_settings_screensaver ||
                        cId == R.id.card_settings_debug) {
                        child.setBackground(ThemeManager.roundedDrawable(ctx, 14f, cardBg, border, 1.2f));
                    }
                }
            }
        }
    }

    public static void build(LinearLayout column, int accent) {
        build(column, com.radiorubka.wdsp.ui.theme.ThemeManager.textPrimary(column.getContext()), accent);
    }

    public static void build(LinearLayout column, int textPrimary, int accent) {
        Context ctx = column.getContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        sAccent = accent;
        sTextPrimary = textPrimary;

        List<View> children = new ArrayList<>();
        for (int i = 0; i < column.getChildCount(); i++) {
            children.add(column.getChildAt(i));
        }
        column.removeAllViews();

        List<TextView> titles = new ArrayList<>();
        List<LinearLayout> bodies = new ArrayList<>();
        LinearLayout body = null;

        for (int i = 0; i < children.size(); i++) {
            View v = children.get(i);
            boolean header = i > 0 && isHeader(v);
            if (!header) {
                if (body == null) {
                    column.addView(v);
                } else {
                    body.addView(v);
                }
                continue;
            }

            final TextView title = (TextView) v;
            body = new LinearLayout(ctx);
            body.setOrientation(LinearLayout.VERTICAL);

            title.setTag(TAG_HEADER);
            title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

            int padH = Math.round(10 * ctx.getResources().getDisplayMetrics().density);
            int padV = Math.round(12 * ctx.getResources().getDisplayMetrics().density);
            title.setPadding(padH, padV, padH, padV);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            title.setTypeface(null, Typeface.BOLD);
            TouchGlow.attach(title);

            titles.add(title);
            bodies.add(body);
        }

        // Exclusive accordion: at most ONE section open on initial build
        int openIdx = -1;
        for (int i = 0; i < titles.size(); i++) {
            String key = PREF_PREFIX + titles.get(i).getId();
            if (prefs.getBoolean(key, false)) {
                if (openIdx == -1) {
                    openIdx = i;
                } else {
                    prefs.edit().putBoolean(key, false).apply();
                }
            }
        }

        for (int i = 0; i < titles.size(); i++) {
            final TextView title = titles.get(i);
            final LinearLayout section = bodies.get(i);
            final String key = PREF_PREFIX + title.getId();
            boolean open = (i == openIdx);
            setHeaderState(title, section, open, accent, textPrimary);

            title.setOnClickListener(b -> {
                boolean nowOpen = section.getVisibility() != View.VISIBLE;
                if (nowOpen) {
                    // Collapse all other sections
                    for (int j = 0; j < titles.size(); j++) {
                        TextView otherTitle = titles.get(j);
                        if (otherTitle != title) {
                            LinearLayout otherSection = bodies.get(j);
                            setHeaderState(otherTitle, otherSection, false, sAccent, sTextPrimary);
                            prefs.edit().putBoolean(PREF_PREFIX + otherTitle.getId(), false).apply();
                        }
                    }
                }
                setHeaderState(title, section, nowOpen, sAccent, sTextPrimary);
                prefs.edit().putBoolean(key, nowOpen).apply();
            });
        }

        Integer[] idx = new Integer[titles.size()];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> {
            int oa = orderOf(titles.get(a).getId());
            int ob = orderOf(titles.get(b).getId());
            return oa != ob ? Integer.compare(oa, ob) : Integer.compare(a, b);
        });

        for (int i : idx) {
            column.addView(titles.get(i));
            column.addView(bodies.get(i));
        }
    }

    public static void expandAndScroll(android.widget.ScrollView scrollView, LinearLayout column, int headerId) {
        if (scrollView == null || column == null || headerId == 0) return;
        Context ctx = column.getContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        for (int i = 0; i < column.getChildCount(); i++) {
            View titleView = column.getChildAt(i);
            if (titleView instanceof TextView && titleView.getId() == headerId && (i + 1) < column.getChildCount()) {
                View targetBody = column.getChildAt(i + 1);
                int acc = sAccent != 0 ? sAccent : com.radiorubka.wdsp.ui.theme.ThemeManager.accent(ctx, false);
                int prim = sTextPrimary != 0 ? sTextPrimary : com.radiorubka.wdsp.ui.theme.ThemeManager.textPrimary(ctx, false);

                for (int j = 0; j < column.getChildCount(); j++) {
                    View otherTitle = column.getChildAt(j);
                    if (otherTitle instanceof TextView && TAG_HEADER.equals(otherTitle.getTag()) && otherTitle != titleView) {
                        View otherBody = (j + 1 < column.getChildCount()) ? column.getChildAt(j + 1) : null;
                        setHeaderState((TextView) otherTitle, otherBody, false, acc, prim);
                        prefs.edit().putBoolean(PREF_PREFIX + otherTitle.getId(), false).apply();
                    }
                }

                setHeaderState((TextView) titleView, targetBody, true, acc, prim);
                prefs.edit().putBoolean(PREF_PREFIX + headerId, true).apply();
                scrollView.post(() -> scrollView.smoothScrollTo(0, titleView.getTop()));
                return;
            }
        }
    }

    private static String mark(boolean open) {
        return open ? "▾" : "▸";
    }

    private static boolean isHeader(View v) {
        if (!(v instanceof TextView)) {
            return false;
        }
        int id = v.getId();
        for (int hId : ORDER) {
            if (id == hId) {
                return true;
            }
        }
        return false;
    }
}
