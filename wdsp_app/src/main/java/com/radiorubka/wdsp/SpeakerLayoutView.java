package com.radiorubka.wdsp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

/**
 * Which speakers the car has, drawn over the measurement's car picture and changed by a tap
 * (owner's plan, 02.10.2026: «комплектація — тап по динаміках»). A door speaker stands for its pair -
 * the cabin model knows pairs, not single doors - and the subwoofer for itself.
 *
 * <p>It owns no state: SettingsActivity reads CabinProfile into {@link #setLayout} and writes the tap
 * back. It sits under the microphone dot (BalancePointerView), which takes a touch only near the dot
 * and passes the rest through, so the dot is still dragged and a speaker still tapped.
 */
public class SpeakerLayoutView extends View {

    /** What a tap asks to change. */
    public static final int FRONT_PAIR = 0, REAR_PAIR = 1, SUBWOOFER = 2;

    public interface OnSpeakerTapListener {
        void onSpeakerTap(int which);
    }

    /** Speaker centres in the car drawable's own viewport (ic_car_cabriolet, 300 x 520): FL FR RL RR sub. */
    private static final float VIEWPORT_W = 300f, VIEWPORT_H = 520f;
    private static final float[][] SPEAKERS = {{66, 230}, {234, 230}, {68, 350}, {232, 350}, {150, 430}};
    private static final int[] GROUP = {FRONT_PAIR, FRONT_PAIR, REAR_PAIR, REAR_PAIR, SUBWOOFER};

    private final boolean[] present = {true, true, true};
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint absent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float markRadius;
    private final float hitRadius;
    private int downSpeaker = -1;
    @Nullable
    private OnSpeakerTapListener listener;

    public SpeakerLayoutView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        final float density = getResources().getDisplayMetrics().density;
        markRadius = 13 * density;
        hitRadius = 26 * density;   // a finger on a car screen, as the dot's own grab radius
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * density);
        absent.setStyle(Paint.Style.STROKE);
        absent.setStrokeWidth(2 * density);
        absent.setPathEffect(new DashPathEffect(new float[]{4 * density, 3 * density}, 0));
        plate.setStyle(Paint.Style.FILL);
        final int card = ThemeManager.cardBackground(context);
        setColors(ThemeManager.accent(context),
                ThemeManager.contrastText(ThemeManager.textSecondary(context), card), card);
    }

    /**
     * Accent for a speaker the car has. One it lacks gets a dashed ring and a stroke through it in
     * {@code ink}, on a {@code plate} disc: the car picture's own colours do not follow the theme, and
     * measured on the bench a muted ring straight on its dark body all but vanished (07.10.2026).
     */
    public void setColors(int accent, int ink, int plateColor) {
        fill.setColor(accent);
        fill.setAlpha(70);
        ring.setColor(accent);
        absent.setColor(ink);
        plate.setColor(plateColor);
        plate.setAlpha(210);
        invalidate();
    }

    public void setLayout(boolean frontPair, boolean rearPair, boolean subwoofer) {
        present[FRONT_PAIR] = frontPair;
        present[REAR_PAIR] = rearPair;
        present[SUBWOOFER] = subwoofer;
        invalidate();
    }

    public void setOnSpeakerTapListener(@Nullable OnSpeakerTapListener l) {
        listener = l;
    }

    /** fitCenter, as the car picture under this view: one scale, centred. */
    private float scale() {
        return Math.min(getWidth() / VIEWPORT_W, getHeight() / VIEWPORT_H);
    }

    private float sx(int i) {
        return (getWidth() - VIEWPORT_W * scale()) / 2f + SPEAKERS[i][0] * scale();
    }

    private float sy(int i) {
        return (getHeight() - VIEWPORT_H * scale()) / 2f + SPEAKERS[i][1] * scale();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) return;
        for (int i = 0; i < SPEAKERS.length; i++) {
            final float x = sx(i), y = sy(i);
            final float r = i == SPEAKERS.length - 1 ? markRadius * 1.5f : markRadius;
            if (present[GROUP[i]]) {
                canvas.drawCircle(x, y, r, fill);
                canvas.drawCircle(x, y, r, ring);
            } else {
                canvas.drawCircle(x, y, r, plate);
                canvas.drawCircle(x, y, r, absent);
                final float d = r * 0.7f;
                canvas.drawLine(x - d, y + d, x + d, y - d, absent);
            }
        }
    }

    private int speakerAt(float x, float y) {
        int best = -1;
        double bestD = hitRadius;
        for (int i = 0; i < SPEAKERS.length; i++) {
            final double d = Math.hypot(x - sx(i), y - sy(i));
            if (d <= bestD) {
                best = i;
                bestD = d;
            }
        }
        return best;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downSpeaker = speakerAt(event.getX(), event.getY());
                return downSpeaker >= 0;   // anywhere else is not ours
            case MotionEvent.ACTION_UP:
                final int up = speakerAt(event.getX(), event.getY());
                if (downSpeaker >= 0 && up == downSpeaker && listener != null) {
                    listener.onSpeakerTap(GROUP[up]);
                    performClick();
                }
                downSpeaker = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                downSpeaker = -1;
                return true;
            default:
                return downSpeaker >= 0;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
