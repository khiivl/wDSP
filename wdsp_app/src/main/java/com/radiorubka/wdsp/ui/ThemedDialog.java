package com.radiorubka.wdsp.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.radiorubka.wdsp.ui.theme.ThemeManager;

/**
 * Unified dialogs that strictly follow the app's palette and ThemeManager.
 *
 * Fully unified with the QFRadio (ThemedDialogBuilder) design language:
 * - Card-based structure with rounded corners and panel borders
 * - Consistent text hierarchies: textPrimary, textSecondary, textMuted, onAccent
 * - Touch-down accent fill for interactive option cards
 * - Theme-aware inputs without Android system underline artifacts
 * - Automatic tinting of AlertDialog components including ListViews and Checkboxes
 */
public final class ThemedDialog {

    private static final int DANGER = 0xFFE5352B;

    public interface OnInputListener {
        void onInput(Dialog dialog, String text);
    }

    public interface OnItemClickListener {
        void onClick(Dialog dialog, int which);
    }

    public static final class Option {
        public final String glyph;
        public final String title;
        public final String description;

        public Option(String glyph, String title, String description) {
            this.glyph = glyph;
            this.title = title;
            this.description = description;
        }
    }

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
        paintContent(dialog);
        return dialog;
    }

    /**
     * A dialog that says one thing and waits to be dismissed.
     */
    public static AlertDialog notice(Context context, CharSequence title, CharSequence message) {
        return show(builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, (d, w) -> d.dismiss()));
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
        panel.setColor(ColorUtils.setAlphaComponent(background, 0xF8));
        panel.setStroke((int) Math.max(1, dp(context, 1.2f)), border);
        window.setBackgroundDrawable(panel);

        int screen = context.getResources().getDisplayMetrics().widthPixels;
        window.setLayout(Math.min((int) (screen * 0.86f), (int) dp(context, 520)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void paintContent(AlertDialog dialog) {
        Context context = dialog.getContext();
        int primary = ThemeManager.textPrimary(context);
        int secondary = ThemeManager.textSecondary(context);
        int accent = ThemeManager.accent(context);
        int border = ThemeManager.panelBorder(context);

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

        View custom = dialog.findViewById(androidx.appcompat.R.id.custom);
        if (custom instanceof ViewGroup) {
            tintChildren((ViewGroup) custom, primary);
        }

        ListView listView = dialog.getListView();
        if (listView != null) {
            listView.setDivider(new ColorDrawable(border));
            listView.setDividerHeight(1);
            listView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                for (int i = 0; i < listView.getChildCount(); i++) {
                    View child = listView.getChildAt(i);
                    if (child instanceof CheckedTextView) {
                        CheckedTextView ctv = (CheckedTextView) child;
                        ctv.setTextColor(primary);
                        ctv.setCheckMarkTintList(ColorStateList.valueOf(accent));
                    } else if (child instanceof TextView) {
                        ((TextView) child).setTextColor(primary);
                    }
                }
            });
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

    public static float dp(Context context, float value) {
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

    public static int translucent(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    // =========================================================================================
    // UNIFIED CARD DIALOGS (MATCHING QFRADIO ThemedDialogBuilder)
    // =========================================================================================

    /**
     * Unified text input dialog (renaming presets, slots, etc.).
     * Eliminates Android system underline and ensures perfect contrast in light and dark modes.
     */
    public static void showInput(Context context, String title, String currentText,
                                 String positiveBtn, String negativeBtn, OnInputListener listener) {
        showInput(context, title, null, currentText, positiveBtn, negativeBtn, listener);
    }

    public static void showInput(Context context, String title, String subtitle, String currentText,
                                 String positiveBtn, String negativeBtn, OnInputListener listener) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final int accent = ThemeManager.accent(context);
        final int onAccent = ThemeManager.onAccent(context);
        final int textPrimary = ThemeManager.textPrimary(context);
        final int textMuted = ThemeManager.textMuted(context);
        final int cardBg = ThemeManager.cardBackground(context);
        final int border = ThemeManager.panelBorder(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(context, 20);
        root.setPadding(pad, pad, pad, (int) dp(context, 14));

        if (title != null && !title.isEmpty()) {
            TextView tvTitle = new TextView(context);
            tvTitle.setText(title);
            tvTitle.setTextColor(textPrimary);
            tvTitle.setTextSize(20);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);
        }

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(context);
            tvSub.setText(subtitle);
            tvSub.setTextColor(textMuted);
            tvSub.setTextSize(14);
            LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subParams.topMargin = (int) dp(context, 6);
            root.addView(tvSub, subParams);
        }

        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(currentText != null ? currentText : "");
        input.setSelection(input.getText().length());
        input.setTextColor(textPrimary);
        input.setHintTextColor(textMuted);
        input.setTextSize(17);
        input.setSingleLine(true);
        input.setBackground(ThemeManager.roundedDrawable(context, 12, cardBg, border, 1.5f));
        int ipad = (int) dp(context, 14);
        input.setPadding(ipad, ipad, ipad, ipad);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = (int) dp(context, 14);
        root.addView(input, inputParams);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowParams.topMargin = (int) dp(context, 18);

        TextView btnCancel = new TextView(context);
        btnCancel.setText(negativeBtn);
        btnCancel.setTextColor(textMuted);
        btnCancel.setTextSize(16);
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setPadding((int) dp(context, 16), (int) dp(context, 10), (int) dp(context, 16), (int) dp(context, 10));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(btnCancel);

        TextView btnOk = new TextView(context);
        btnOk.setText(positiveBtn);
        btnOk.setTextColor(onAccent);
        btnOk.setTextSize(16);
        btnOk.setTypeface(null, Typeface.BOLD);
        btnOk.setGravity(Gravity.CENTER);
        btnOk.setPadding((int) dp(context, 24), (int) dp(context, 10), (int) dp(context, 24), (int) dp(context, 10));
        btnOk.setBackground(ThemeManager.roundedDrawable(context, 12, accent, accent, 0));
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okParams.leftMargin = (int) dp(context, 10);
        btnOk.setOnClickListener(v -> {
            if (listener != null) {
                listener.onInput(dialog, input.getText().toString());
            }
            dialog.dismiss();
        });
        buttons.addView(btnOk, okParams);

        root.addView(buttons, btnRowParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min((int) (screen * 0.86f), (int) dp(context, 480)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            root.setBackground(ThemeManager.roundedDrawable(context, 16,
                    ThemeManager.cardBackground(context), border, 1.2f));
        }
        dialog.show();
    }

    /**
     * Unified confirmation dialog (preset deletion, reset actions, etc.).
     */
    public static void showConfirmation(Context context, String title, String message,
                                        String positiveBtn, String negativeBtn, Runnable onConfirm) {
        showConfirmation(context, title, message, positiveBtn, negativeBtn, false, onConfirm);
    }

    public static void showConfirmation(Context context, String title, String message,
                                        String positiveBtn, String negativeBtn, boolean isDestructive,
                                        Runnable onConfirm) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final int accent = isDestructive ? DANGER : ThemeManager.accent(context);
        final int onAccent = isDestructive ? Color.WHITE : ThemeManager.onAccent(context);
        final int textPrimary = ThemeManager.textPrimary(context);
        final int textSecondary = ThemeManager.textSecondary(context);
        final int textMuted = ThemeManager.textMuted(context);
        final int border = ThemeManager.panelBorder(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(context, 20);
        root.setPadding(pad, pad, pad, (int) dp(context, 14));

        if (title != null && !title.isEmpty()) {
            TextView tvTitle = new TextView(context);
            tvTitle.setText(title);
            tvTitle.setTextColor(textPrimary);
            tvTitle.setTextSize(20);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);
        }

        if (message != null && !message.isEmpty()) {
            TextView tvMsg = new TextView(context);
            tvMsg.setText(message);
            tvMsg.setTextColor(textSecondary);
            tvMsg.setTextSize(15);
            tvMsg.setLineSpacing(dp(context, 2), 1.15f);
            LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            msgParams.topMargin = (int) dp(context, 10);
            root.addView(tvMsg, msgParams);
        }

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowParams.topMargin = (int) dp(context, 20);

        TextView btnCancel = new TextView(context);
        btnCancel.setText(negativeBtn);
        btnCancel.setTextColor(textMuted);
        btnCancel.setTextSize(16);
        btnCancel.setGravity(Gravity.CENTER);
        btnCancel.setPadding((int) dp(context, 16), (int) dp(context, 10), (int) dp(context, 16), (int) dp(context, 10));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(btnCancel);

        TextView btnOk = new TextView(context);
        btnOk.setText(positiveBtn);
        btnOk.setTextColor(onAccent);
        btnOk.setTextSize(16);
        btnOk.setTypeface(null, Typeface.BOLD);
        btnOk.setGravity(Gravity.CENTER);
        btnOk.setPadding((int) dp(context, 24), (int) dp(context, 10), (int) dp(context, 24), (int) dp(context, 10));
        btnOk.setBackground(ThemeManager.roundedDrawable(context, 12, accent, accent, 0));
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okParams.leftMargin = (int) dp(context, 10);
        btnOk.setOnClickListener(v -> {
            dialog.dismiss();
            if (onConfirm != null) {
                onConfirm.run();
            }
        });
        buttons.addView(btnOk, okParams);

        root.addView(buttons, btnRowParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min((int) (screen * 0.86f), (int) dp(context, 480)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            root.setBackground(ThemeManager.roundedDrawable(context, 16,
                    ThemeManager.cardBackground(context), isDestructive ? DANGER : border, 1.2f));
        }
        dialog.show();
    }

    /**
     * Unified interactive option cards with touch feedback.
     */
    public static void showOptions(Context context, String title, String subtitle,
                                   Option[] options, String cancelLabel,
                                   OnItemClickListener listener) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final int accent = ThemeManager.accent(context);
        final int onAccent = ThemeManager.onAccent(context);
        final int textPrimary = ThemeManager.textPrimary(context);
        final int textSecondary = ThemeManager.textSecondary(context);
        final int cardBg = ThemeManager.cardBackground(context);
        final int border = ThemeManager.panelBorder(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(context, 20);
        root.setPadding(pad, pad, pad, (int) dp(context, 12));

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextColor(textPrimary);
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(null, Typeface.BOLD);
        root.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(context);
            tvSub.setText(subtitle);
            tvSub.setTextColor(textSecondary);
            tvSub.setTextSize(14);
            tvSub.setPadding(0, (int) dp(context, 4), 0, 0);
            root.addView(tvSub);
        }

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, (int) dp(context, 14), 0, 0);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (int i = 0; i < options.length; i++) {
            final int index = i;
            Option opt = options[i];

            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            int cardPad = (int) dp(context, 14);
            card.setPadding(cardPad, cardPad, cardPad, cardPad);
            card.setBackground(ThemeManager.roundedDrawable(context, 14, cardBg, border, 1.2f));

            TextView tvGlyph = new TextView(context);
            tvGlyph.setText(opt.glyph);
            tvGlyph.setTextColor(accent);
            tvGlyph.setTextSize(24);
            tvGlyph.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams glyphParams =
                    new LinearLayout.LayoutParams((int) dp(context, 40), ViewGroup.LayoutParams.WRAP_CONTENT);
            card.addView(tvGlyph, glyphParams);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);

            TextView tvName = new TextView(context);
            tvName.setText(opt.title);
            tvName.setTextColor(textPrimary);
            tvName.setTextSize(17);
            tvName.setTypeface(null, Typeface.BOLD);
            texts.addView(tvName);

            if (opt.description != null && !opt.description.isEmpty()) {
                TextView tvDesc = new TextView(context);
                tvDesc.setText(opt.description);
                tvDesc.setTextColor(textSecondary);
                tvDesc.setTextSize(13);
                tvDesc.setLineSpacing(0f, 1.15f);
                tvDesc.setPadding(0, (int) dp(context, 3), 0, 0);
                texts.addView(tvDesc);
            }

            card.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            card.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setBackground(ThemeManager.roundedDrawable(context, 14, accent, accent, 1.2f));
                        tvGlyph.setTextColor(onAccent);
                        tvName.setTextColor(onAccent);
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setBackground(ThemeManager.roundedDrawable(context, 14, cardBg, border, 1.2f));
                        tvGlyph.setTextColor(accent);
                        tvName.setTextColor(textPrimary);
                        return false;
                    default:
                        return false;
                }
            });

            card.setOnClickListener(v -> {
                dialog.dismiss();
                if (listener != null) {
                    listener.onClick(dialog, index);
                }
            });

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                cardParams.topMargin = (int) dp(context, 10);
            }
            list.addView(card, cardParams);
        }

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, (int) dp(context, 8), 0, 0);

        Button btnCancel = new Button(context);
        btnCancel.setText(cancelLabel);
        btnCancel.setTextColor(accent);
        btnCancel.setBackgroundColor(Color.TRANSPARENT);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(btnCancel);
        root.addView(buttons);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min((int) (screen * 0.86f), (int) dp(context, 520)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            root.setBackground(ThemeManager.roundedDrawable(context, 16,
                    ThemeManager.cardBackground(context), border, 1.2f));
        }
        dialog.show();
    }

    /**
     * Unified modal dialog for custom view layouts.
     */
    public static Dialog showCustom(Context context, String title, View customView,
                                    String positiveBtn, Runnable onPositive,
                                    String negativeBtn, Runnable onNegative) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final int accent = ThemeManager.accent(context);
        final int onAccent = ThemeManager.onAccent(context);
        final int textPrimary = ThemeManager.textPrimary(context);
        final int textMuted = ThemeManager.textMuted(context);
        final int border = ThemeManager.panelBorder(context);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(context, 20);
        root.setPadding(pad, pad, pad, (int) dp(context, 14));

        if (title != null && !title.isEmpty()) {
            TextView tvTitle = new TextView(context);
            tvTitle.setText(title);
            tvTitle.setTextColor(textPrimary);
            tvTitle.setTextSize(20);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);
        }

        if (customView != null) {
            LinearLayout.LayoutParams cvParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            cvParams.topMargin = (int) dp(context, 12);
            root.addView(customView, cvParams);
        }

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams btnRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnRowParams.topMargin = (int) dp(context, 16);

        if (negativeBtn != null) {
            TextView btnCancel = new TextView(context);
            btnCancel.setText(negativeBtn);
            btnCancel.setTextColor(textMuted);
            btnCancel.setTextSize(16);
            btnCancel.setGravity(Gravity.CENTER);
            btnCancel.setPadding((int) dp(context, 16), (int) dp(context, 10), (int) dp(context, 16), (int) dp(context, 10));
            btnCancel.setOnClickListener(v -> {
                dialog.dismiss();
                if (onNegative != null) onNegative.run();
            });
            buttons.addView(btnCancel);
        }

        if (positiveBtn != null) {
            TextView btnOk = new TextView(context);
            btnOk.setText(positiveBtn);
            btnOk.setTextColor(onAccent);
            btnOk.setTextSize(16);
            btnOk.setTypeface(null, Typeface.BOLD);
            btnOk.setGravity(Gravity.CENTER);
            btnOk.setPadding((int) dp(context, 24), (int) dp(context, 10), (int) dp(context, 24), (int) dp(context, 10));
            btnOk.setBackground(ThemeManager.roundedDrawable(context, 12, accent, accent, 0));
            LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            okParams.leftMargin = (int) dp(context, 10);
            btnOk.setOnClickListener(v -> {
                dialog.dismiss();
                if (onPositive != null) onPositive.run();
            });
            buttons.addView(btnOk, okParams);
        }

        root.addView(buttons, btnRowParams);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min((int) (screen * 0.88f), (int) dp(context, 540)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            root.setBackground(ThemeManager.roundedDrawable(context, 16,
                    ThemeManager.cardBackground(context), border, 1.2f));
        }
        dialog.show();
        return dialog;
    }
}
