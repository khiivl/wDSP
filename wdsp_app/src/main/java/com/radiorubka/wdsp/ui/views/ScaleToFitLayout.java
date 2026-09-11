package com.radiorubka.wdsp.ui.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Holds its child at the child's designed size and scales it, whole and uniformly, to fit the
 * room it is given - never larger than designed.
 *
 * <p>For groups whose parts only make sense together, such as the car on the fader tab with the
 * arrows and numbers around it. On a 7-inch panel (1024x600, 528dp of content) that group ran
 * under the navigation pill: nothing scaled it and nothing scrolled it. Scrolling would put the
 * rear of the car off screen, and shrinking the car alone would leave the arrows where it used to
 * be. Scaling keeps the picture and its controls in proportion, and touches follow the drawing,
 * because a scaled child receives its events through the same transform.
 *
 * <p>The child declares a fixed width and height: that is its design size. Padding is honoured, so
 * room kept free for something floating over the content is simply padding.
 */
public class ScaleToFitLayout extends FrameLayout {

    public ScaleToFitLayout(Context context) {
        super(context);
    }

    public ScaleToFitLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ScaleToFitLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
        int roomW = Math.max(0, w - getPaddingLeft() - getPaddingRight());
        int roomH = Math.max(0, h - getPaddingTop() - getPaddingBottom());
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int cw = lp.width > 0 ? lp.width : roomW;
            int ch = lp.height > 0 ? lp.height : roomH;
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int roomW = getWidth() - getPaddingLeft() - getPaddingRight();
        int roomH = getHeight() - getPaddingTop() - getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            float scale = (cw <= 0 || ch <= 0 || roomW <= 0 || roomH <= 0) ? 1f
                    : Math.min(1f, Math.min(roomW / (float) cw, roomH / (float) ch));
            // Laid out at full design size around the centre of the room, then scaled about its
            // own centre - so it lands centred and inside the room whatever the scale.
            int l = getPaddingLeft() + (roomW - cw) / 2;
            int t = getPaddingTop() + (roomH - ch) / 2;
            child.layout(l, t, l + cw, t + ch);
            child.setPivotX(cw / 2f);
            child.setPivotY(ch / 2f);
            child.setScaleX(scale);
            child.setScaleY(scale);
        }
    }
}
