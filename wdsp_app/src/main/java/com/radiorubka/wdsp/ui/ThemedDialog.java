package com.radiorubka.wdsp.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Unified dialogs that strictly follow the app's palette and ThemeManager.
 *
 * Fully unified with the QFRadio (ThemedDialogBuilder) design language:
 * - Pure android.app.Dialog with custom card-based layouts
 * - Theme-aware contrastText calculations so text is never black-on-black or white-on-white
 * - Card-based structure with rounded corners (16dp) and panel borders
 * - Consistent text hierarchies: textPrimary, textSecondary, textMuted, onAccent
 * - Touch-down accent fill for interactive option cards
 * - Theme-aware inputs without Android system underline artifacts
 */
public final class ThemedDialog {

    public static final int DANGER = 0xFFE5352B;

    private static final Map<Dialog, Runnable> sActiveDialogs = new WeakHashMap<>();

    public static synchronized void registerActiveDialog(Dialog dialog, Runnable rebinder) {
        if (dialog == null || rebinder == null) return;
        sActiveDialogs.put(dialog, rebinder);
        // Очищаємо запис при закритті діалогу, щоб лямбда-rebinder не тримала View/Dialog у WeakHashMap
        dialog.setOnDismissListener(d -> {
            synchronized (ThemedDialog.class) {
                sActiveDialogs.remove(d);
            }
        });
    }

    public static synchronized void refreshActiveDialogs(Context context) {
        Iterator<Map.Entry<Dialog, Runnable>> it = sActiveDialogs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Dialog, Runnable> entry = it.next();
            Dialog d = entry.getKey();
            Runnable r = entry.getValue();
            if (d == null || !d.isShowing()) {
                it.remove();
                continue;
            }
            if (r != null) {
                try {
                    r.run();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static final class MultiChoiceRowHolder {
        final View row;
        final CheckBox cb;
        final TextView tv;
        MultiChoiceRowHolder(View row, CheckBox cb, TextView tv) {
            this.row = row;
            this.cb = cb;
            this.tv = tv;
        }
    }

    private static final class OptionCardHolder {
        final View card;
        final TextView tvGlyph;
        final TextView tvName;
        final TextView tvDesc;
        OptionCardHolder(View card, TextView tvGlyph, TextView tvName, TextView tvDesc) {
            this.card = card;
            this.tvGlyph = tvGlyph;
            this.tvName = tvName;
            this.tvDesc = tvDesc;
        }
    }

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

    public static float dp(Context context, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }

    public static Builder builder(Context context) {
        return new Builder(context);
    }

    public static Dialog show(Builder builder) {
        return builder.show();
    }

    /**
     * A dialog that says one thing and waits to be dismissed.
     */
    public static Dialog notice(Context context, CharSequence title, CharSequence message) {
        return builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * Convenience for the common "title, message, one or more buttons" case.
     */
    public static Dialog message(Context context, CharSequence title, CharSequence message,
                                 CharSequence positive,
                                 DialogInterface.OnClickListener onPositive,
                                 CharSequence negative) {
        Builder b = builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, onPositive);
        if (negative != null) {
            b.setNegativeButton(negative, null);
        }
        return b.show();
    }

    public static class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private CharSequence positiveText;
        private DialogInterface.OnClickListener positiveListener;
        private boolean isPositiveDanger = false;
        private CharSequence negativeText;
        private DialogInterface.OnClickListener negativeListener;
        private CharSequence neutralText;
        private DialogInterface.OnClickListener neutralListener;
        private boolean cancelable = true;
        private boolean canceledOnTouchOutside = true;
        private DialogInterface.OnDismissListener dismissListener;
        private View customView;
        private CharSequence[] multiChoiceItems;
        private boolean[] checkedItems;
        private DialogInterface.OnMultiChoiceClickListener multiChoiceListener;
        private int maxWidthDp = 500;

        public Builder(@NonNull Context context) {
            this.context = context;
        }

        public Builder setTitle(int resId) {
            this.title = context.getString(resId);
            return this;
        }

        public Builder setTitle(CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder setMessage(int resId) {
            this.message = context.getString(resId);
            return this;
        }

        public Builder setMessage(CharSequence message) {
            this.message = message;
            return this;
        }

        public Builder setPositiveButton(int resId, DialogInterface.OnClickListener listener) {
            this.positiveText = context.getString(resId);
            this.positiveListener = listener;
            return this;
        }

        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
            this.positiveText = text;
            this.positiveListener = listener;
            return this;
        }

        public Builder setPositiveButton(CharSequence text, boolean isDanger, DialogInterface.OnClickListener listener) {
            this.positiveText = text;
            this.isPositiveDanger = isDanger;
            this.positiveListener = listener;
            return this;
        }

        public Builder setNegativeButton(int resId, DialogInterface.OnClickListener listener) {
            this.negativeText = context.getString(resId);
            this.negativeListener = listener;
            return this;
        }

        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
            this.negativeText = text;
            this.negativeListener = listener;
            return this;
        }

        public Builder setNeutralButton(int resId, DialogInterface.OnClickListener listener) {
            this.neutralText = context.getString(resId);
            this.neutralListener = listener;
            return this;
        }

        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
            this.neutralText = text;
            this.neutralListener = listener;
            return this;
        }

        public Builder setCancelable(boolean cancelable) {
            this.cancelable = cancelable;
            this.canceledOnTouchOutside = cancelable;
            return this;
        }

        public Builder setCanceledOnTouchOutside(boolean cancel) {
            this.canceledOnTouchOutside = cancel;
            return this;
        }

        public Builder setOnDismissListener(DialogInterface.OnDismissListener listener) {
            this.dismissListener = listener;
            return this;
        }

        public Builder setView(View view) {
            this.customView = view;
            return this;
        }

        public Builder setMultiChoiceItems(CharSequence[] items, boolean[] checkedItems,
                                           DialogInterface.OnMultiChoiceClickListener listener) {
            this.multiChoiceItems = items;
            this.checkedItems = checkedItems;
            this.multiChoiceListener = listener;
            return this;
        }

        public Builder setMaxWidthDp(int dp) {
            this.maxWidthDp = dp;
            return this;
        }

        public Dialog create() {
            Dialog dialog = new Dialog(context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(cancelable);
            dialog.setCanceledOnTouchOutside(canceledOnTouchOutside);
            if (dismissListener != null) {
                dialog.setOnDismissListener(dismissListener);
            }

            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int screenW = context.getResources().getDisplayMetrics().widthPixels;
                int maxW = (int) dp(context, maxWidthDp);
                int dialogW = Math.min((int) (screenW * 0.92f), maxW);
                window.setLayout(dialogW, ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            int cardBg = ThemeManager.cardBackground(context);
            int border = ThemeManager.panelBorder(context);
            int textPrimary = ThemeManager.contrastText(ThemeManager.textPrimary(context), cardBg);
            int textSecondary = ThemeManager.contrastText(ThemeManager.textSecondary(context), cardBg);
            int textMuted = ThemeManager.textMuted(context);
            int accent = ThemeManager.accent(context);
            int onAccent = ThemeManager.onAccent(context);

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) dp(context, 20);
            root.setPadding(pad, pad, pad, pad);
            root.setBackground(ThemeManager.roundedDrawable(context, 16, cardBg, border, 1.2f));

            // Title
            TextView tvTitle = null;
            if (title != null && title.length() > 0) {
                tvTitle = new TextView(context);
                tvTitle.setText(title);
                tvTitle.setTextColor(textPrimary);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
                tvTitle.setTypeface(null, Typeface.BOLD);
                tvTitle.setPadding(0, 0, 0, (int) dp(context, 10));
                root.addView(tvTitle);
            }

            // Message
            TextView tvMsg = null;
            if (message != null && message.length() > 0) {
                tvMsg = new TextView(context);
                tvMsg.setText(message);
                tvMsg.setTextColor(textSecondary);
                tvMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tvMsg.setLineSpacing(0f, 1.18f);
                tvMsg.setPadding(0, 0, 0, (int) dp(context, 12));
                root.addView(tvMsg);
            }

            // Custom View
            if (customView != null) {
                if (customView.getParent() instanceof ViewGroup) {
                    ((ViewGroup) customView.getParent()).removeView(customView);
                }
                root.addView(customView, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            // Multi-choice list
            final List<MultiChoiceRowHolder> multiChoiceHolders = new ArrayList<>();
            if (multiChoiceItems != null && multiChoiceItems.length > 0) {
                ScrollView sv = new ScrollView(context);
                LinearLayout list = new LinearLayout(context);
                list.setOrientation(LinearLayout.VERTICAL);

                for (int i = 0; i < multiChoiceItems.length; i++) {
                    final int idx = i;
                    CharSequence itemText = multiChoiceItems[i];
                    boolean isChecked = checkedItems != null && i < checkedItems.length && checkedItems[i];

                    LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    int rPad = (int) dp(context, 10);
                    row.setPadding(rPad, rPad, rPad, rPad);
                    row.setBackground(ThemeManager.roundedDrawable(context, 10, cardBg, border, 1f));

                    CheckBox cb = new CheckBox(context);
                    cb.setChecked(isChecked);
                    cb.setButtonTintList(ColorStateList.valueOf(accent));
                    cb.setClickable(false);
                    cb.setFocusable(false);
                    row.addView(cb);

                    TextView tv = new TextView(context);
                    tv.setText(itemText);
                    tv.setTextColor(textPrimary);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    tv.setPadding((int) dp(context, 8), 0, 0, 0);
                    row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    multiChoiceHolders.add(new MultiChoiceRowHolder(row, cb, tv));

                    row.setOnClickListener(v -> {
                        boolean next = !cb.isChecked();
                        cb.setChecked(next);
                        if (checkedItems != null && idx < checkedItems.length) {
                            checkedItems[idx] = next;
                        }
                        if (multiChoiceListener != null) {
                            multiChoiceListener.onClick(dialog, idx, next);
                        }
                    });

                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (i > 0) rp.topMargin = (int) dp(context, 6);
                    list.addView(row, rp);
                }

                sv.addView(list);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(context, 260));
                sp.bottomMargin = (int) dp(context, 12);
                root.addView(sv, sp);
            }

            // Buttons row
            TextView btnNeutral = null;
            TextView btnNegative = null;
            TextView btnPositive = null;
            boolean hasButtons = positiveText != null || negativeText != null || neutralText != null;
            if (hasButtons) {
                LinearLayout btnRow = new LinearLayout(context);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                btnRow.setPadding(0, (int) dp(context, 8), 0, 0);

                if (neutralText != null) {
                    btnNeutral = new TextView(context);
                    btnNeutral.setText(neutralText);
                    btnNeutral.setTextColor(textMuted);
                    btnNeutral.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    btnNeutral.setPadding((int) dp(context, 14), (int) dp(context, 10),
                            (int) dp(context, 14), (int) dp(context, 10));
                    btnNeutral.setOnClickListener(v -> {
                        if (neutralListener != null) neutralListener.onClick(dialog, DialogInterface.BUTTON_NEUTRAL);
                        dialog.dismiss();
                    });
                    btnRow.addView(btnNeutral);
                }

                if (negativeText != null) {
                    btnNegative = new TextView(context);
                    btnNegative.setText(negativeText);
                    btnNegative.setTextColor(textSecondary);
                    btnNegative.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    btnNegative.setPadding((int) dp(context, 16), (int) dp(context, 10),
                            (int) dp(context, 16), (int) dp(context, 10));
                    btnNegative.setOnClickListener(v -> {
                        if (negativeListener != null) negativeListener.onClick(dialog, DialogInterface.BUTTON_NEGATIVE);
                        dialog.dismiss();
                    });
                    LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    np.leftMargin = (int) dp(context, 6);
                    btnRow.addView(btnNegative, np);
                }

                if (positiveText != null) {
                    btnPositive = new TextView(context);
                    btnPositive.setText(positiveText);
                    btnPositive.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    btnPositive.setTypeface(null, Typeface.BOLD);
                    btnPositive.setGravity(Gravity.CENTER);
                    int posBg = isPositiveDanger ? DANGER : accent;
                    int posText = isPositiveDanger ? Color.WHITE : onAccent;
                    btnPositive.setTextColor(posText);
                    btnPositive.setBackground(ThemeManager.roundedDrawable(context, 10, posBg, posBg, 0));
                    btnPositive.setPadding((int) dp(context, 20), (int) dp(context, 10),
                            (int) dp(context, 20), (int) dp(context, 10));
                    btnPositive.setOnClickListener(v -> {
                        if (positiveListener != null) positiveListener.onClick(dialog, DialogInterface.BUTTON_POSITIVE);
                        dialog.dismiss();
                    });
                    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    pp.leftMargin = (int) dp(context, 10);
                    btnRow.addView(btnPositive, pp);
                }

                root.addView(btnRow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            final TextView finalTvTitle = tvTitle;
            final TextView finalTvMsg = tvMsg;
            final TextView finalBtnNeutral = btnNeutral;
            final TextView finalBtnNegative = btnNegative;
            final TextView finalBtnPositive = btnPositive;
            final boolean finalIsPositiveDanger = isPositiveDanger;

            registerActiveDialog(dialog, () -> {
                Context c = root.getContext();
                boolean night = ThemeManager.isNight(c);
                int cBg = ThemeManager.cardBackground(c, night);
                int bdr = ThemeManager.panelBorder(c, night);
                int tPrimary = ThemeManager.contrastText(ThemeManager.textPrimary(c, night), cBg);
                int tSecondary = ThemeManager.contrastText(ThemeManager.textSecondary(c, night), cBg);
                int tMuted = ThemeManager.textMuted(c, night);
                int acc = ThemeManager.accent(c, night);
                int onAcc = ThemeManager.onAccent(c, night);

                root.setBackground(ThemeManager.roundedDrawable(c, 16, cBg, bdr, 1.2f));
                if (finalTvTitle != null) finalTvTitle.setTextColor(tPrimary);
                if (finalTvMsg != null) finalTvMsg.setTextColor(tSecondary);
                for (MultiChoiceRowHolder h : multiChoiceHolders) {
                    h.row.setBackground(ThemeManager.roundedDrawable(c, 10, cBg, bdr, 1f));
                    h.cb.setButtonTintList(ColorStateList.valueOf(acc));
                    h.tv.setTextColor(tPrimary);
                }
                if (finalBtnNeutral != null) finalBtnNeutral.setTextColor(tMuted);
                if (finalBtnNegative != null) finalBtnNegative.setTextColor(tSecondary);
                if (finalBtnPositive != null) {
                    int posBg = finalIsPositiveDanger ? DANGER : acc;
                    int posText = finalIsPositiveDanger ? Color.WHITE : onAcc;
                    finalBtnPositive.setTextColor(posText);
                    finalBtnPositive.setBackground(ThemeManager.roundedDrawable(c, 10, posBg, posBg, 0));
                }
            });

            dialog.setContentView(root);
            return dialog;
        }

        public Dialog show() {
            Dialog d = create();
            d.show();
            return d;
        }
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

        TextView tvTitle = null;
        if (title != null && !title.isEmpty()) {
            tvTitle = new TextView(context);
            tvTitle.setText(title);
            tvTitle.setTextColor(textPrimary);
            tvTitle.setTextSize(20);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);
        }

        TextView tvSub = null;
        if (subtitle != null && !subtitle.isEmpty()) {
            tvSub = new TextView(context);
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

        final TextView finalInputTitle = tvTitle;
        final TextView finalInputSub = tvSub;
        registerActiveDialog(dialog, () -> {
            boolean night = ThemeManager.isNight(context);
            int acc = ThemeManager.accent(context, night);
            int onAcc = ThemeManager.onAccent(context, night);
            int tPrimary = ThemeManager.textPrimary(context, night);
            int tMuted = ThemeManager.textMuted(context, night);
            int cBg = ThemeManager.cardBackground(context, night);
            int bdr = ThemeManager.panelBorder(context, night);

            root.setBackground(ThemeManager.roundedDrawable(context, 16, cBg, bdr, 1.2f));
            if (finalInputTitle != null) finalInputTitle.setTextColor(tPrimary);
            if (finalInputSub != null) finalInputSub.setTextColor(tMuted);
            input.setTextColor(tPrimary);
            input.setHintTextColor(tMuted);
            input.setBackground(ThemeManager.roundedDrawable(context, 12, cBg, bdr, 1.5f));
            btnCancel.setTextColor(tMuted);
            btnOk.setTextColor(onAcc);
            btnOk.setBackground(ThemeManager.roundedDrawable(context, 12, acc, acc, 0));
        });

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

        TextView tvTitle = null;
        if (title != null && !title.isEmpty()) {
            tvTitle = new TextView(context);
            tvTitle.setText(title);
            tvTitle.setTextColor(textPrimary);
            tvTitle.setTextSize(20);
            tvTitle.setTypeface(null, Typeface.BOLD);
            root.addView(tvTitle);
        }

        TextView tvMsg = null;
        if (message != null && !message.isEmpty()) {
            tvMsg = new TextView(context);
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

        final TextView finalConfTitle = tvTitle;
        final TextView finalConfMsg = tvMsg;
        registerActiveDialog(dialog, () -> {
            boolean night = ThemeManager.isNight(context);
            int acc = isDestructive ? DANGER : ThemeManager.accent(context, night);
            int onAcc = isDestructive ? Color.WHITE : ThemeManager.onAccent(context, night);
            int tPrimary = ThemeManager.textPrimary(context, night);
            int tSecondary = ThemeManager.textSecondary(context, night);
            int tMuted = ThemeManager.textMuted(context, night);
            int cBg = ThemeManager.cardBackground(context, night);
            int bdr = isDestructive ? DANGER : ThemeManager.panelBorder(context, night);

            root.setBackground(ThemeManager.roundedDrawable(context, 16, cBg, bdr, 1.2f));
            if (finalConfTitle != null) finalConfTitle.setTextColor(tPrimary);
            if (finalConfMsg != null) finalConfMsg.setTextColor(tSecondary);
            btnCancel.setTextColor(tMuted);
            btnOk.setTextColor(onAcc);
            btnOk.setBackground(ThemeManager.roundedDrawable(context, 12, acc, acc, 0));
        });

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

        TextView tvSub = null;
        if (subtitle != null && !subtitle.isEmpty()) {
            tvSub = new TextView(context);
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

        final List<OptionCardHolder> cardHolders = new ArrayList<>();
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

            TextView tvDesc = null;
            if (opt.description != null && !opt.description.isEmpty()) {
                tvDesc = new TextView(context);
                tvDesc.setText(opt.description);
                tvDesc.setTextColor(textSecondary);
                tvDesc.setTextSize(13);
                tvDesc.setLineSpacing(0f, 1.15f);
                tvDesc.setPadding(0, (int) dp(context, 3), 0, 0);
                texts.addView(tvDesc);
            }

            cardHolders.add(new OptionCardHolder(card, tvGlyph, tvName, tvDesc));

            card.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            card.setOnTouchListener((v, event) -> {
                boolean curNight = ThemeManager.isNight(context);
                int curCardBg = ThemeManager.cardBackground(context, curNight);
                int curBorder = ThemeManager.panelBorder(context, curNight);
                int curAccent = ThemeManager.accent(context, curNight);
                int curOnAccent = ThemeManager.onAccent(context, curNight);
                int curPrimary = ThemeManager.textPrimary(context, curNight);
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setBackground(ThemeManager.roundedDrawable(context, 14, curAccent, curAccent, 1.2f));
                        tvGlyph.setTextColor(curOnAccent);
                        tvName.setTextColor(curOnAccent);
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setBackground(ThemeManager.roundedDrawable(context, 14, curCardBg, curBorder, 1.2f));
                        tvGlyph.setTextColor(curAccent);
                        tvName.setTextColor(curPrimary);
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

        TextView btnCancel = new TextView(context);
        btnCancel.setText(cancelLabel);
        btnCancel.setTextColor(ThemeManager.contrastText(ThemeManager.textSecondary(context), cardBg));
        btnCancel.setTextSize(15);
        btnCancel.setPadding((int) dp(context, 16), (int) dp(context, 10), (int) dp(context, 16), (int) dp(context, 10));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        buttons.addView(btnCancel);
        root.addView(buttons);

        final TextView finalOptTitle = tvTitle;
        final TextView finalOptSub = tvSub;
        registerActiveDialog(dialog, () -> {
            boolean night = ThemeManager.isNight(context);
            int acc = ThemeManager.accent(context, night);
            int tPrimary = ThemeManager.textPrimary(context, night);
            int tSecondary = ThemeManager.textSecondary(context, night);
            int cBg = ThemeManager.cardBackground(context, night);
            int bdr = ThemeManager.panelBorder(context, night);

            root.setBackground(ThemeManager.roundedDrawable(context, 16, cBg, bdr, 1.2f));
            finalOptTitle.setTextColor(tPrimary);
            if (finalOptSub != null) finalOptSub.setTextColor(tSecondary);
            for (OptionCardHolder ch : cardHolders) {
                ch.card.setBackground(ThemeManager.roundedDrawable(context, 14, cBg, bdr, 1.2f));
                ch.tvGlyph.setTextColor(acc);
                ch.tvName.setTextColor(tPrimary);
                if (ch.tvDesc != null) ch.tvDesc.setTextColor(tSecondary);
            }
            btnCancel.setTextColor(ThemeManager.contrastText(tSecondary, cBg));
        });

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min((int) (screen * 0.86f), (int) dp(context, 520)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            root.setBackground(ThemeManager.roundedDrawable(context, 16,
                    cardBg, border, 1.2f));
        }
        dialog.show();
    }

    /**
     * Unified card-based list dialog (matching QFRadio ThemedDialogBuilder.showList).
     * Every item is a rounded card with touch feedback and WCAG contrast.
     */
    public static void showList(Context context, String title, String[] items, OnItemClickListener listener) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final int accent = ThemeManager.accent(context);
        final int onAccent = ThemeManager.onAccent(context);
        final int cardBg = ThemeManager.cardBackground(context);
        final int border = ThemeManager.panelBorder(context);
        final int textPrimary = ThemeManager.contrastText(ThemeManager.textPrimary(context), cardBg);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(context, 20);
        root.setPadding(pad, pad, pad, pad);

        TextView tvTitle = null;
        if (title != null && !title.isEmpty()) {
            tvTitle = new TextView(context);
            tvTitle.setText(title);
            tvTitle.setTextColor(textPrimary);
            tvTitle.setTextSize(20);
            tvTitle.setTypeface(null, Typeface.BOLD);
            tvTitle.setGravity(Gravity.CENTER);
            tvTitle.setPadding(0, 0, 0, (int) dp(context, 14));
            root.addView(tvTitle);
        }

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, scrollParams);

        final List<TextView> itemButtons = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView btn = new TextView(context);
            btn.setText(items[i]);
            btn.setTextColor(textPrimary);
            btn.setTextSize(16);
            btn.setPadding(pad, (int) dp(context, 11), pad, (int) dp(context, 11));
            btn.setBackground(ThemeManager.roundedDrawable(context, 12, cardBg, border, 1.2f));

            btn.setOnTouchListener((v, event) -> {
                boolean curNight = ThemeManager.isNight(context);
                int curCardBg = ThemeManager.cardBackground(context, curNight);
                int curBorder = ThemeManager.panelBorder(context, curNight);
                int curAccent = ThemeManager.accent(context, curNight);
                int curOnAccent = ThemeManager.onAccent(context, curNight);
                int curPrimary = ThemeManager.contrastText(ThemeManager.textPrimary(context, curNight), curCardBg);
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setBackground(ThemeManager.roundedDrawable(context, 12, curAccent, curAccent, 1.2f));
                        btn.setTextColor(curOnAccent);
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setBackground(ThemeManager.roundedDrawable(context, 12, curCardBg, curBorder, 1.2f));
                        btn.setTextColor(curPrimary);
                        return false;
                    default:
                        return false;
                }
            });

            btn.setOnClickListener(v -> {
                dialog.dismiss();
                if (listener != null) listener.onClick(dialog, index);
            });

            itemButtons.add(btn);

            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) ip.topMargin = (int) dp(context, 8);
            list.addView(btn, ip);
        }

        final TextView finalListTitle = tvTitle;
        registerActiveDialog(dialog, () -> {
            boolean night = ThemeManager.isNight(context);
            int curCardBg = ThemeManager.cardBackground(context, night);
            int curBorder = ThemeManager.panelBorder(context, night);
            int curTextPrimary = ThemeManager.contrastText(ThemeManager.textPrimary(context, night), curCardBg);

            root.setBackground(ThemeManager.roundedDrawable(context, 16, curCardBg, curBorder, 1.2f));
            if (finalListTitle != null) finalListTitle.setTextColor(curTextPrimary);
            for (TextView b : itemButtons) {
                b.setTextColor(curTextPrimary);
                b.setBackground(ThemeManager.roundedDrawable(context, 12, curCardBg, curBorder, 1.2f));
            }
        });

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            int screen = context.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min((int) (screen * 0.86f), (int) dp(context, 480)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            root.setBackground(ThemeManager.roundedDrawable(context, 16, cardBg, border, 1.2f));
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

        TextView tvTitle = null;
        if (title != null && !title.isEmpty()) {
            tvTitle = new TextView(context);
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

        TextView btnCancel = null;
        if (negativeBtn != null) {
            btnCancel = new TextView(context);
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

        TextView btnOk = null;
        if (positiveBtn != null) {
            btnOk = new TextView(context);
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

        final TextView finalCustomTitle = tvTitle;
        final TextView finalCustomCancel = btnCancel;
        final TextView finalCustomOk = btnOk;
        registerActiveDialog(dialog, () -> {
            boolean night = ThemeManager.isNight(context);
            int acc = ThemeManager.accent(context, night);
            int onAcc = ThemeManager.onAccent(context, night);
            int curPrimary = ThemeManager.textPrimary(context, night);
            int curMuted = ThemeManager.textMuted(context, night);
            int curCardBg = ThemeManager.cardBackground(context, night);
            int curBorder = ThemeManager.panelBorder(context, night);

            root.setBackground(ThemeManager.roundedDrawable(context, 16, curCardBg, curBorder, 1.2f));
            if (finalCustomTitle != null) finalCustomTitle.setTextColor(curPrimary);
            if (finalCustomCancel != null) finalCustomCancel.setTextColor(curMuted);
            if (finalCustomOk != null) {
                finalCustomOk.setTextColor(onAcc);
                finalCustomOk.setBackground(ThemeManager.roundedDrawable(context, 12, acc, acc, 0));
            }
        });

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
