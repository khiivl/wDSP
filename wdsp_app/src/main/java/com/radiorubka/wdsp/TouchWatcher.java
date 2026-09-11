package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Notices that somebody touched the screen, anywhere.
 *
 * <h2>Why this is needed at all</h2>
 *
 * The screensaver used to count idle time from the last change of foreground activity, because
 * that is the one thing the platform publishes. It is the wrong clock: a person reading a long
 * page, scrolling a list, or working through a settings screen never changes the foreground, so
 * the count kept running and the screensaver arrived on top of somebody who was plainly using the
 * unit. Idle has to mean "nobody has touched it", and only touches can say that.
 *
 * <h2>How, without root and without an accessibility service</h2>
 *
 * A window one pixel across, transparent, and touchable, carrying
 * {@link WindowManager.LayoutParams#FLAG_WATCH_OUTSIDE_TOUCH}. The system then sends it
 * {@link MotionEvent#ACTION_OUTSIDE} whenever a gesture starts anywhere outside those few pixels -
 * and, crucially, sends it as a copy: the touch still reaches whatever was under the finger.
 * Nothing is intercepted and nothing is consumed.
 *
 * <p>One report per gesture, on the way down. That is exactly what is wanted here - the question
 * is whether a person is present, not how far they dragged.
 *
 * <p>⚠️ This works on this platform, which is Android 10. From Android 12 the system stopped
 * reporting outside touches that landed on another application's windows, so on a newer platform
 * this would only ever hear touches on our own. If wDSP is ever built for one, the clock has to
 * come from somewhere else - there is no quiet fallback here, it would simply go deaf.
 */
final class TouchWatcher {

    private static final String TAG = "wDSP_TouchWatcher";

    interface Listener {
        void onTouchedSomewhere();
    }

    private final Context context;
    private final Listener listener;
    private View view;

    TouchWatcher(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    boolean isRunning() {
        return view != null;
    }

    void start() {
        if (view != null) return;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) return;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;

        View v = new View(context);
        v.setOnTouchListener((ignored, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                listener.onTouchedSomewhere();
            }
            // Never true. Returning true would claim the event, and the whole point is that
            // this window is a bystander.
            return false;
        });

        try {
            wm.addView(v, params);
            view = v;
        } catch (Throwable t) {
            Log.w(TAG, "could not watch for touches", t);
        }
    }

    void stop() {
        if (view == null) return;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        try {
            if (wm != null) wm.removeView(view);
        } catch (Throwable t) {
            Log.w(TAG, "could not stop watching", t);
        }
        view = null;
    }
}
