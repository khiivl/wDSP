package com.radiorubka.wdsp.ui.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.radiorubka.wdsp.R;

/**
 * HSV-диск вибору кольору: відтінок (hue) — по колу, насиченість — від білого центру (0)
 * до чистого кольору на краю (1).
 */
public class HueWheelView extends View {

    public interface Listener {
        void onColorChanged(float hue, float sat, boolean finished);
    }

    private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float hue = 170f;
    private float sat = 0.85f;
    private Listener listener;
    private boolean dragging;

    public HueWheelView(Context context) {
        super(context);
        init();
    }

    public HueWheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        thumbPaint.setStyle(Paint.Style.FILL);
        thumbBorderPaint.setStyle(Paint.Style.STROKE);
        thumbBorderPaint.setColor(Color.WHITE);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int max = getResources().getDimensionPixelSize(R.dimen.wheel_max_size);
        int size = Math.min(width, max);
        setMeasuredDimension(size, size);
    }

    public void setColor(float h, float s) {
        this.hue = h;
        this.sat = Math.max(0f, Math.min(1f, s));
        invalidate();
    }

    public float getHue() {
        return hue;
    }

    public float getSat() {
        return sat;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(cx, cy);
        int n = 36;
        int[] colors = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            colors[i] = Color.HSVToColor(new float[]{i * 360f / n, 1f, 1f});
        }
        Shader sweep = new SweepGradient(cx, cy, colors, null);
        Shader whiteCenter = new RadialGradient(cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP);
        discPaint.setShader(new ComposeShader(sweep, whiteCenter, PorterDuff.Mode.SRC_OVER));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float radius = Math.min(cx, cy) - 2;

        canvas.drawCircle(cx, cy, radius, discPaint);

        double rad = Math.toRadians(hue);
        float dist = sat * radius;
        float tx = cx + (float) Math.cos(rad) * dist;
        float ty = cy + (float) Math.sin(rad) * dist;
        float thumbR = radius * 0.11f;
        thumbPaint.setColor(Color.HSVToColor(new float[]{hue, sat, 0.90f}));
        thumbBorderPaint.setStrokeWidth(thumbR * 0.3f);
        canvas.drawCircle(tx, ty, thumbR, thumbPaint);
        canvas.drawCircle(tx, ty, thumbR, thumbBorderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float dx = event.getX() - getWidth() / 2f;
        float dy = event.getY() - getHeight() / 2f;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                updateColor(dx, dy, false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    updateColor(dx, dy, false);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    updateColor(dx, dy, true);
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void updateColor(float dx, float dy, boolean finished) {
        float deg = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (deg < 0) {
            deg += 360f;
        }
        float radius = Math.min(getWidth(), getHeight()) / 2f;
        hue = deg;
        sat = Math.max(0f, Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy) / radius));
        invalidate();
        if (listener != null) {
            listener.onColorChanged(hue, sat, finished);
        }
    }
}
