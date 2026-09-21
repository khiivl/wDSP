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

    /**
     * How small the group may be scaled before the parent is asked to scroll instead.
     *
     * <p>Scaling alone is not enough on a short panel: at 0.47 - which is what the Tesla square
     * leaves for a 640x648dp group - the arrows around the car come out 17dp and the numbers around
     * 8sp, measured on the unit 12.09.2026. The owner's rule for this tab is the one the card on its
     * left already follows: shrink to a point, then scroll. Below the floor this view reports the
     * height the group actually needs, and a parent ScrollView takes over.
     */
    private static final float MIN_SCALE = 0.8f;

    private float mScale = 1f;

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int wMode = MeasureSpec.getMode(widthMeasureSpec);
        int hMode = MeasureSpec.getMode(heightMeasureSpec);
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int padH = getPaddingLeft() + getPaddingRight();
        int padV = getPaddingTop() + getPaddingBottom();
        int roomW = Math.max(0, w - padH);
        int roomH = Math.max(0, h - padV);

        int needH = 0;
        float scale = 1f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int cw = lp.width > 0 ? lp.width : roomW;
            int ch = lp.height > 0 ? lp.height : roomH;
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));

            // Width is a HARD constraint - there is nowhere for the group to go sideways, and a
            // floor applied to it simply pushed the side arrows off the pane (8dp of the left arrow
            // visible on the Tesla square, measured 12.09.2026). The floor exists for the HEIGHT
            // only: instead of shrinking further to fit a short panel, the group keeps its size and
            // the parent scrolls.
            float byW = (cw > 0 && roomW > 0) ? roomW / (float) cw : 1f;
            float s = Math.min(1f, byW);
            if (ch > 0 && roomH > 0 && hMode != MeasureSpec.UNSPECIFIED) {
                float byH = roomH / (float) ch;
                s = Math.min(s, Math.max(byH, MIN_SCALE));
            }
            scale = Math.min(scale, s);
            needH = Math.max(needH, Math.round(ch * s));
        }
        mScale = scale;

        int height = (hMode == MeasureSpec.EXACTLY) ? h : needH + padV;
        if (hMode == MeasureSpec.AT_MOST) height = Math.min(height, h);
        setMeasuredDimension(wMode == MeasureSpec.UNSPECIFIED ? Math.round(roomW * scale) + padH : w,
                height);
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
            // The same scale onMeasure settled on, so the height reported to a scrolling parent and
            // the drawing agree. Recomputing it here is how the two used to drift apart.
            float scale = mScale;
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
