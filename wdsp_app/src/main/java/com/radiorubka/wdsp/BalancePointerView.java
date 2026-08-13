package com.radiorubka.wdsp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * A small draggable Material-style dot meant to be overlaid directly on top of the car
 * image in the Fader (Positioning) screen. It renders the current L/R (x-axis) and
 * Front/Rear (y-axis) balance as a single point, and lets the user drag it to set both
 * axes at once instead of operating the two separate sliders.
 * This view owns no state of its own beyond the normalized position it's told to show;
 * MainActivity is still the source of truth (the two Slider values). Call setBalance()
 * whenever those sliders change from any source, and set an OnBalanceChangeListener to
 * be notified when the user drags the dot.
 */
public class BalancePointerView extends View {

    public interface OnBalanceChangeListener {
        // Both values in [-1, 1], 0 = center. lr: -1 = full left, +1 = full right.
        // fr: -1 = full rear, +1 = full front.
        void onBalanceChanged(float lrNorm, float frNorm);
    }

    private float lrNorm = 0f;
    private float frNorm = 0f;

    private Paint dotPaint;
    private Paint ringPaint;
    private Paint haloPaint;

    private float dotRadius;
    private float haloRadius;
    private float grabRadius;
    private float edgeInset;

    private boolean dragging = false;
    private OnBalanceChangeListener listener;

    public BalancePointerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getContext().getResources().getDisplayMetrics().density;
        int accent = ContextCompat.getColor(getContext(), R.color.cyan_custom);
        int ring = ContextCompat.getColor(getContext(), R.color.stroke_color);

        dotRadius = 7 * density;
        float ringWidth = 2 * density;
        haloRadius = 18 * density;
        grabRadius = 28 * density; // generous touch target for a car head unit screen
        edgeInset = 12 * density;  // keeps the dot from visually clipping at the image edge

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(accent);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setShadowLayer(4 * density, 0, 2 * density, Color.argb(120, 0, 0, 0));
        // Paint.setShadowLayer only renders with a software layer
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ringPaint.setColor(ring);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(ringWidth);

        haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        haloPaint.setColor(ring);
        haloPaint.setStyle(Paint.Style.FILL);
        haloPaint.setAlpha(40);
    }

    /** Update the shown position. Ignored while the user is actively dragging the dot. */
    public void setBalance(float lr, float fr) {
        if (dragging) return;
        float newLr = clamp(lr);
        float newFr = clamp(fr);
        if (newLr == lrNorm && newFr == frNorm) return;
        lrNorm = newLr;
        frNorm = newFr;
        invalidate();
    }

    public void setOnBalanceChangeListener(OnBalanceChangeListener l) {
        this.listener = l;
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    // This view is always laid out to exactly match the car ImageView's bounds (see
    // activity_main.xml / layout-port/activity_main.xml), and ImageView's fitCenter
    // scale type always centers the drawable within those bounds — so the view's own
    // center IS the image's visual center, letterboxing included. Centralized here so
    // every position calc (drawing, dead position, and touch handling) agrees; don't
    // recompute getWidth()/2f / getHeight()/2f separately elsewhere.
    private float centerX() {
        return getWidth() / 2f;
    }

    private float centerY() {
        return getHeight() / 2f;
    }

    // Max horizontal/vertical travel distance from the image center, in pixels.
    // Combines the fixed cosmetic edgeInset with the tunable rangeX/YPercent above.
    private float halfRangeX() {
        // --- Tweak these to match the car artwork ---
        // How far the dot is allowed to travel from the image center, as a fraction of the
        // available half-width/half-height (after edgeInset). 1.0 = can reach all the way out
        // to edgeInset from the view's edge (the old, unconstrained behavior). Lower this per
        // axis to pull the travel range in to roughly the car's actual body/cabin footprint.
        // These are percentages of the view size, not fixed dp, so they hold up across
        // different screen sizes/orientations without retuning.
        float rangeXPercent = 0.9f;
        return Math.max(0f, (getWidth() / 2f - edgeInset) * rangeXPercent);
    }

    private float halfRangeY() {
        float rangeYPercent = 0.5f;
        return Math.max(0f, (getHeight() / 2f - edgeInset) * rangeYPercent);
    }

    private float dotCx() {
        return centerX() + lrNorm * halfRangeX();
    }

    private float dotCy() {
        // Front (+1) renders toward the top of the image, matching the vertical Fr
        // slider's rotation="270" (its valueTo/"Front" end lands at the top on screen).
        // Flip the sign here if that ever changes.
        return centerY() - frNorm * halfRangeY();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) return;
        float cx = dotCx();
        float cy = dotCy();

        if (dragging) {
            canvas.drawCircle(cx, cy, haloRadius, haloPaint);
        }
        canvas.drawCircle(cx, cy, dotRadius, dotPaint);
        canvas.drawCircle(cx, cy, dotRadius, ringPaint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float dx = event.getX() - dotCx();
                float dy = event.getY() - dotCy();
                if (Math.hypot(dx, dy) > grabRadius) return false; // let it pass through
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                updateFromTouch(event);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                updateFromTouch(event);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return false;
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
            default:
                return false;
        }
    }

    private void updateFromTouch(MotionEvent event) {
        float halfW = halfRangeX();
        float halfH = halfRangeY();
        if (halfW <= 0 || halfH <= 0) return;

        lrNorm = clamp((event.getX() - centerX()) / halfW);
        frNorm = clamp(-(event.getY() - centerY()) / halfH);
        invalidate();
        if (listener != null) listener.onBalanceChanged(lrNorm, frNorm);
    }
}
