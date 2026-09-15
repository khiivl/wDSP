package com.radiorubka.wdsp.ui;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shrinks a row of controls as a whole, in proportion, when the window is too narrow for it.
 *
 * <h2>Why</h2>
 *
 * The equaliser's bottom row was laid out for a wide fascia and lost controls on anything else. A
 * tester's vertical 768x1024 panel gives the app a 768x710dp window, and there the amplifier's
 * minus was a 15dp sliver and its plus was not on screen at all (photo and report, 14.09.2026);
 * measured by emulation on the owner's unit, the plus was cut at 1024dp and a 640dp split window
 * showed neither the value nor either button. The owner's rule (15.09.2026): shrink everything at
 * once rather than lose anything - the buttons, the dropdown, the gaps, and the type, the captions'
 * more than the rest.
 *
 * <h2>How</h2>
 *
 * Everything in the row keeps its designed size in the layout, so a window with room looks exactly
 * as designed. After each change of width the row is measured at that designed size with no limit;
 * when that is wider than the room, every fixed width, horizontal margin and text size in it is
 * multiplied by one factor, and the measurement is repeated until it fits. One view is left out and
 * stretches: the slider, whose length is not a size anybody designed. It keeps at least
 * {@code flexibleMinPx}.
 *
 * <p>Only the row's own linear blocks are walked into. A {@link TextInputLayout} is scaled by its
 * width and its text, never by its insides - the end icon and the box padding belong to Material.
 */
public final class RowFit implements View.OnLayoutChangeListener {

    /** Below this the controls stop being targets; the captions ellipsize instead. */
    private static final float MIN_SCALE = 0.6f;

    private final LinearLayout row;
    private final View flexible;
    private final int flexibleMinPx;
    private final int slackPx;
    private final List<View> captions;
    private final List<Base> bases = new ArrayList<>();
    private int fittedWidth = -1;
    private float scale = 1f;

    /**
     * The designed size of one view, taken before anything was scaled.
     *
     * <p>Widths and margins come from the layout and are taken once. The text size is not: the
     * theme pass in MainActivity sets these captions and values to 16sp after inflation, and may do
     * it again on a change of theme. So the size in force is re-read before every fit, and taken as
     * the new design whenever it is not the one this class last set.
     */
    private static final class Base {
        final View view;
        final int width;
        final int marginStart;
        final int marginEnd;
        float textPx;
        float appliedTextPx = -1f;
        final int minWidth;
        final int maxWidth;
        final int paddingStart;
        final int paddingEnd;
        final boolean caption;

        Base(View view, boolean textOnly, boolean caption) {
            this.paddingStart = view instanceof TextView ? view.getPaddingStart() : -1;
            this.paddingEnd = view instanceof TextView ? view.getPaddingEnd() : -1;
            this.view = view;
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            this.width = !textOnly && lp != null && lp.width > 0 ? lp.width : -1;
            if (!textOnly && lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                this.marginStart = mlp.getMarginStart();
                this.marginEnd = mlp.getMarginEnd();
            } else {
                this.marginStart = -1;
                this.marginEnd = -1;
            }
            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                this.textPx = tv.getTextSize();
                this.minWidth = textOnly ? -1 : tv.getMinWidth();
                this.maxWidth = textOnly ? -1 : tv.getMaxWidth();
            } else {
                this.textPx = -1f;
                this.minWidth = -1;
                this.maxWidth = -1;
            }
            this.caption = caption;
        }
    }

    public static RowFit attach(LinearLayout row, View flexible, int flexibleMinPx, View... captions) {
        if (row == null || flexible == null) return null;
        RowFit fit = new RowFit(row, flexible, flexibleMinPx, captions);
        row.addOnLayoutChangeListener(fit);
        return fit;
    }

    private RowFit(LinearLayout row, View flexible, int flexibleMinPx, View[] captions) {
        this.row = row;
        this.flexible = flexible;
        this.flexibleMinPx = flexibleMinPx;
        // A value that grows from "0" to "-12" after the fit must not push the last button out.
        this.slackPx = Math.round(8 * row.getResources().getDisplayMetrics().density);
        this.captions = Arrays.asList(captions);
        collect(row);
    }

    private void collect(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == flexible) {
                bases.add(new Base(child, false, false));
                continue;
            }
            if (child instanceof TextInputLayout) {
                bases.add(new Base(child, false, false));
                TextView edit = ((TextInputLayout) child).getEditText();
                if (edit != null) bases.add(new Base(edit, true, captions.contains(edit)));
                continue;
            }
            bases.add(new Base(child, false, captions.contains(child)));
            if (child instanceof LinearLayout) collect((ViewGroup) child);
        }
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                               int oldLeft, int oldTop, int oldRight, int oldBottom) {
        int width = right - left;
        if (width <= 0) return;
        if (width == fittedWidth && !textChangedBehindUs()) return;
        fittedWidth = width;
        // Not inside the layout pass that told us: this measures the row and changes sizes.
        row.post(this::fit);
    }

    /** Somebody else set a text size in the row since the last fit - the theme pass does. */
    private boolean textChangedBehindUs() {
        for (Base b : bases) {
            if (b.appliedTextPx > 0f && b.view instanceof TextView
                    && Math.abs(((TextView) b.view).getTextSize() - b.appliedTextPx) > 0.5f) {
                return true;
            }
        }
        return false;
    }

    private void fit() {
        int room = row.getWidth() - row.getPaddingLeft() - row.getPaddingRight() - slackPx;
        if (room <= 0) return;
        for (Base b : bases) {
            if (!(b.view instanceof TextView)) continue;
            float current = ((TextView) b.view).getTextSize();
            if (b.appliedTextPx < 0f || Math.abs(current - b.appliedTextPx) > 0.5f) b.textPx = current;
        }
        float s = 1f;
        for (int pass = 0; pass < 6; pass++) {
            apply(s);
            int need = naturalWidth();
            if (need <= room) break;
            float next = Math.min(s * room / (float) need, s - 0.01f);
            if (next <= MIN_SCALE) {
                s = MIN_SCALE;
                apply(s);
                break;
            }
            s = next;
        }
        scale = s;
        row.requestLayout();
    }

    /** The row's width at the current sizes with nothing limiting it, the slider at its minimum. */
    private int naturalWidth() {
        int unlimited = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        row.measure(unlimited, unlimited);
        return row.getMeasuredWidth() - row.getPaddingLeft() - row.getPaddingRight()
                - flexible.getMeasuredWidth() + flexibleMinPx;
    }

    private void apply(float s) {
        // Captions take the whole factor; the rest of the type only half of it - "a little".
        float otherText = 1f - (1f - s) * 0.5f;
        for (Base b : bases) {
            View v = b.view;
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            boolean sized = false;
            if (b.width > 0 && lp != null) {
                // A dropdown's arrow is a fixed 48dp target inside its box; shrinking the whole box
                // took all of it from the text, and "Гц" was cut at 640dp. Only the part that holds
                // the text is scaled.
                int fixed = v instanceof TextInputLayout ? Math.min(b.width, endIconWidth((TextInputLayout) v)) : 0;
                lp.width = Math.max(1, fixed + Math.round((b.width - fixed) * s));
                sized = true;
            }
            if (b.marginStart >= 0 && lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                mlp.setMarginStart(Math.round(b.marginStart * s));
                mlp.setMarginEnd(Math.round(b.marginEnd * s));
                sized = true;
            }
            if (sized) v.setLayoutParams(lp);
            if (v instanceof TextView && b.textPx > 0f) {
                TextView tv = (TextView) v;
                tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, b.textPx * (b.caption ? s : otherText));
                b.appliedTextPx = tv.getTextSize();
                if (b.paddingStart > 0 || b.paddingEnd > 0) {
                    tv.setPaddingRelative(Math.round(b.paddingStart * s), tv.getPaddingTop(),
                            Math.round(b.paddingEnd * s), tv.getPaddingBottom());
                }
                if (b.minWidth > 0) tv.setMinWidth(Math.round(b.minWidth * s));
                if (b.maxWidth > 0 && b.maxWidth < Integer.MAX_VALUE) {
                    tv.setMaxWidth(Math.round(b.maxWidth * s));
                }
            }
        }
    }

    /** The width the box's end icon takes, laid out or not: 52dp is what Material gives it here. */
    private static int endIconWidth(TextInputLayout box) {
        View icon = box.findViewById(com.google.android.material.R.id.text_input_end_icon);
        if (icon != null && icon.getParent() instanceof View && ((View) icon.getParent()).getWidth() > 0) {
            return ((View) icon.getParent()).getWidth();
        }
        return Math.round(52 * box.getResources().getDisplayMetrics().density);
    }

    /** What the row was last shrunk to, 1 when it fits as designed. */
    public float scale() {
        return scale;
    }
}
