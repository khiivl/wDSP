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

import com.radiorubka.wdsp.R;

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
        title.setTextColor(open ? accent : textPrimary);
    }

    public static void repaint(LinearLayout column, int accent) {
        repaint(column, com.radiorubka.wdsp.ui.theme.ThemeManager.textPrimary(column.getContext()), accent);
    }

    public static void repaint(LinearLayout column, int textPrimary, int accent) {
        sTextPrimary = textPrimary;
        sAccent = accent;
        for (int i = 0; i < column.getChildCount(); i++) {
            View v = column.getChildAt(i);
            if (v instanceof TextView && TAG_HEADER.equals(v.getTag())) {
                View body = (i + 1 < column.getChildCount()) ? column.getChildAt(i + 1) : null;
                boolean isOpen = (body != null && body.getVisibility() == View.VISIBLE);
                setHeaderState((TextView) v, body, isOpen, accent, textPrimary);
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

            final String key = PREF_PREFIX + title.getId();
            boolean open = prefs.getBoolean(key, false);
            setHeaderState(title, body, open, accent, textPrimary);
            title.setTag(TAG_HEADER);
            title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

            int padH = Math.round(10 * ctx.getResources().getDisplayMetrics().density);
            int padV = Math.round(12 * ctx.getResources().getDisplayMetrics().density);
            title.setPadding(padH, padV, padH, padV);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
            title.setTypeface(null, Typeface.BOLD);
            TouchGlow.attach(title);

            final LinearLayout section = body;
            title.setOnClickListener(b -> {
                boolean nowOpen = section.getVisibility() != View.VISIBLE;
                setHeaderState(title, section, nowOpen, sAccent, sTextPrimary);
                prefs.edit().putBoolean(key, nowOpen).apply();
            });

            titles.add(title);
            bodies.add(body);
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
