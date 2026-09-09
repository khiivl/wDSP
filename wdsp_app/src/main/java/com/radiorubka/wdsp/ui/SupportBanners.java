package com.radiorubka.wdsp.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.radiorubka.wdsp.R;
import com.radiorubka.wdsp.ui.theme.ThemeManager;

/**
 * The two credit-and-support capsules at the top of Settings.
 *
 * <h2>Why this is a class and not markup</h2>
 *
 * <p>The rows differ only in three strings, the styling is imperative on this project anyway
 * (see {@link ThemeManager} — colours are applied to views at runtime, not through theme
 * resources), and the same capsule has to be rebuilt whenever day and night swap. Written as
 * markup it would be sixty near-identical XML blocks that nothing keeps in step, in a layout file
 * that already exists in more than one copy.
 *
 * <h2>🔴 The order is deliberate and is the owner's instruction</h2>
 *
 * <p>The upstream author's capsule comes <b>first</b>, above this fork's own. wDSP is his project;
 * everything here stands on it, and the fork is called "wDSP kostyamat mod" precisely so that the
 * two are not confused. Do not reorder them to put the fork on top.
 *
 * <h2>One tap does both things</h2>
 *
 * <p>Every row copies its value to the clipboard <b>and</b> opens it, in that order. Opening is an
 * ordinary {@code ACTION_VIEW}, so the platform decides: with Telegram installed a {@code t.me}
 * link opens Telegram, with PayPal installed its link opens PayPal, and with neither, the browser.
 * A crypto address has nothing to open and only copies — which is why the copy happens first and
 * unconditionally, rather than as a fallback when opening fails.
 */
public final class SupportBanners {

    private SupportBanners() {}

    /** One row: what is shown, what is copied, and what is opened (null for nothing to open). */
    private static final class Item {
        final int    labelRes;
        final String value;
        final String uri;
        Item(int labelRes, String value, String uri) {
            this.labelRes = labelRes;
            this.value    = value;
            this.uri      = uri;
        }
    }

    private static final Item[] UPSTREAM = {
            new Item(R.string.banner_github,    "https://github.com/khiivl/wDSP",       "https://github.com/khiivl/wDSP"),
            new Item(R.string.banner_telegram,  "https://t.me/wDSPapp",                 "https://t.me/wDSPapp"),
            new Item(R.string.banner_coffee,    "https://buymeacoffee.com/radiorubka",  "https://buymeacoffee.com/radiorubka"),
            new Item(R.string.banner_paypal,    "https://paypal.me/wDSPApp",            "https://paypal.me/wDSPApp"),
    };

    private static final Item[] MOD = {
            new Item(R.string.banner_mod_discussion, "https://t.me/kostyamat_dev/92",            "https://t.me/kostyamat_dev/92"),
            new Item(R.string.banner_mod_support,   "https://t.me/kostyamat_dev/10",            "https://t.me/kostyamat_dev/10"),
            new Item(R.string.banner_paypal,         "https://www.paypal.com/paypalme/kostyamat","https://www.paypal.com/paypalme/kostyamat"),
            new Item(R.string.banner_revolut,        "https://revolut.me/kostyamat",             "https://revolut.me/kostyamat"),
            new Item(R.string.banner_trc20,          "TCSvCEyX1Zxb9E97oTpxHsBCLiDZLPTvzk",       null),
            new Item(R.string.banner_ton,            "UQCqracCOo_DEwVWQjPVYkKak-gSZx1TXQOxICi_9frpdr5j", null),
    };

    /**
     * Builds both capsules into {@code host}, replacing whatever was there.
     *
     * <p>Rebuilding rather than re-tinting is deliberate: it is a dozen views, it happens only when
     * the theme changes or the screen opens, and it cannot leave a colour behind from the other
     * half of the day — which re-tinting by hand has managed to do elsewhere in this application.
     */
    public static void build(Activity a, ViewGroup host, boolean night) {
        if (host == null) return;
        host.removeAllViews();
        host.addView(capsule(a, night, R.string.banner_upstream_title, R.string.banner_upstream_note, UPSTREAM));
        host.addView(capsule(a, night, R.string.banner_mod_title,      R.string.banner_mod_note,      MOD));
    }

    private static View capsule(Activity a, boolean night, int titleRes, int noteRes, Item[] items) {
        float d = a.getResources().getDisplayMetrics().density;
        int pad = (int) (14 * d);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(ThemeManager.cardDrawable(a, night, 22f));

        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.bottomMargin = (int) (10 * d);
        box.setLayoutParams(boxLp);

        int accent    = ThemeManager.accent(a, night);
        int border    = ThemeManager.panelBorder(a, night);
        int substrate = ThemeManager.dockSubstrateColor(a, night);
        int primary   = ThemeManager.contrastText(ThemeManager.textPrimary(a, night), substrate);
        int muted     = ThemeManager.contrastText(ThemeManager.textSecondary(a, night), substrate);

        TextView title = new TextView(a);
        title.setText(titleRes);
        title.setTextColor(accent);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.getPaint().setFakeBoldText(true);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView note = new TextView(a);
        note.setText(noteRes);
        note.setTextColor(muted);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.bottomMargin = (int) (8 * d);
        note.setLayoutParams(noteLp);
        box.addView(note);

        for (Item it : items) {
            box.addView(row(a, night, it, accent, border, primary, muted, d));
        }
        return box;
    }

    private static View row(Activity a, boolean night, Item it,
                            int accent, int border, int primary, int muted, float d) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(ThemeManager.pillDrawable(a, false, night, 14f, accent, border));
        int px = (int) (12 * d);
        int py = (int) (8 * d);
        row.setPadding(px, py, px, py);
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (6 * d);
        row.setLayoutParams(lp);

        TextView label = new TextView(a);
        label.setText(it.labelRes);
        label.setTextColor(primary);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.getPaint().setFakeBoldText(true);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        row.addView(label);

        // The value is shown as well as copied: a person deciding whether to tap a payment address
        // wants to see it, and an address that only lives in the clipboard cannot be checked
        // against anything. Truncated in the middle because the beginning and the end are what a
        // wallet address is recognised by; the whole of it is what gets copied.
        TextView value = new TextView(a);
        value.setText(it.value);
        value.setTextColor(muted);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(value);

        TouchGlow.attach(row);
        row.setOnClickListener(v -> tap(a, it));
        return row;
    }

    private static void tap(Activity a, Item it) {
        copy(a, it.value);

        if (it.uri == null) {
            toast(a, a.getString(R.string.banner_copied));
            return;
        }
        try {
            Intent open = new Intent(Intent.ACTION_VIEW, Uri.parse(it.uri));
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(open);
        } catch (ActivityNotFoundException | SecurityException e) {
            // No browser and no application that claims this link - the copy still happened, so
            // say that rather than failing silently.
            toast(a, a.getString(R.string.banner_copied));
        } catch (Throwable t) {
            toast(a, a.getString(R.string.banner_copied));
        }
    }

    private static void copy(Context ctx, String value) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("wDSP", value));
        } catch (Throwable ignored) {
        }
    }

    private static void toast(Context ctx, String text) {
        try {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }
}
