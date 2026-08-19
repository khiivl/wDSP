package com.radiorubka.wdsp.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.radiorubka.wdsp.ui.theme.ThemeManager;

/**
 * Dialogs that follow the app's own palette.
 *
 * The dialogs used to be built straight from MaterialAlertDialogBuilder, which meant their text
 * colours came from Theme.Material3.DayNight while the background was pinned to a dark drawable in
 * the style. In day mode that produced dark text on a dark panel - unreadable - and the reverse
 * could happen with a light custom palette at night. Neither ever knew about the accent, text and
 * background colours the user actually chose.
 *
 * This applies the same colours the rest of the interface is tinted with, after the dialog is laid
 * out, and gives every dialog the rounded panel look of the main screen.
 */
public final class ThemedDialog {

    private ThemedDialog() {
    }

    public static MaterialAlertDialogBuilder builder(Context context) {
        return new MaterialAlertDialogBuilder(context);
    }

    /** Builds, shows and themes the dialog. Always cancelable, so BACK closes it. */
    public static AlertDialog show(MaterialAlertDialogBuilder builder) {
        AlertDialog dialog = builder.create();
        apply(dialog);
        dialog.show();
        // Colouring has to happen twice: the title and buttons only exist once the dialog is
        // shown, while the window background must be set before it is measured.
        paintContent(dialog);
        return dialog;
    }

    private static void apply(AlertDialog dialog) {
        Context context = dialog.getContext();
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window == null) return;

        int background = ThemeManager.cardBackground(context);
        int border = ThemeManager.panelBorder(context);

        GradientDrawable panel = new GradientDrawable();
        panel.setShape(GradientDrawable.RECTANGLE);
        panel.setCornerRadius(dp(context, 16));
        // Slightly opaque rather than solid, matching the cards on the main screen.
        panel.setColor(ColorUtils.setAlphaComponent(background, 0xF2));
        panel.setStroke((int) dp(context, 1.2f), border);
        window.setBackgroundDrawable(panel);
    }

    private static void paintContent(AlertDialog dialog) {
        Context context = dialog.getContext();
        int primary = ThemeManager.textPrimary(context);
        int secondary = ThemeManager.textSecondary(context);
        int accent = ThemeManager.accent(context);

        tint(dialog.findViewById(androidx.appcompat.R.id.alertTitle), primary);
        tint(dialog.findViewById(android.R.id.title), primary);
        tint(dialog.findViewById(android.R.id.message), secondary);

        for (int which : new int[]{DialogInterface.BUTTON_POSITIVE,
                DialogInterface.BUTTON_NEGATIVE, DialogInterface.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) {
                button.setTextColor(accent);
                button.setAllCaps(false);
            }
        }

        // Anything the caller put in via setView: colour plain labels too, so a custom body does
        // not end up as the one unreadable part of an otherwise themed dialog.
        View custom = dialog.findViewById(androidx.appcompat.R.id.custom);
        if (custom instanceof ViewGroup) {
            tintChildren((ViewGroup) custom, primary);
        }
    }

    private static void tintChildren(ViewGroup group, int color) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                tintChildren((ViewGroup) child, color);
            } else if (child instanceof TextView && !(child instanceof Button)) {
                ((TextView) child).setTextColor(color);
                ((TextView) child).setHintTextColor(
                        ColorUtils.setAlphaComponent(color, 0x80));
            }
        }
    }

    private static void tint(View view, int color) {
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(color);
        }
    }

    private static float dp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    /** Convenience for the common "title, message, one or more buttons" case. */
    public static AlertDialog message(Context context, CharSequence title, CharSequence message,
                                      CharSequence positive,
                                      DialogInterface.OnClickListener onPositive,
                                      CharSequence negative) {
        MaterialAlertDialogBuilder b = builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, onPositive);
        if (negative != null) {
            b.setNegativeButton(negative, null);
        }
        return show(b);
    }

    /** Kept so callers do not have to import Color just to build a translucent panel. */
    public static int translucent(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
