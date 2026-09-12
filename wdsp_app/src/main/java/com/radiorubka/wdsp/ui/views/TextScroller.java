package com.radiorubka.wdsp.ui.views;

import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;

/**
 * Slides one line of text sideways when it is too long for its box, and stands still when it fits.
 *
 * <p>This exists because Android's own marquee is unavailable on the view that needs it. The text
 * field of a Material exposed dropdown menu is an {@link android.widget.EditText}, and
 * {@code EditText.setEllipsize()} throws {@code IllegalArgumentException} for
 * {@code TruncateAt.MARQUEE} from inside the constructor. Setting it in the layout therefore does
 * not degrade - it kills the activity during inflation, before any of our code runs. Measured on
 * the unit 12.09.2026.
 *
 * <p>So the movement is done by hand: the view is put into horizontal scrolling and its scroll
 * position is stepped once a frame, at the speed the status bar visualiser uses for its own
 * marquee, holding still at each end. Nothing is ellipsized and nothing wraps - a long name is
 * shown whole, a piece at a time.
 *
 * <p>Overflow is re-measured every cycle rather than once, because the text changes underneath: the
 * preset name is replaced whenever another preset is selected, and a name that fits must not keep
 * a scroll offset from the one before it.
 */
public final class TextScroller implements Runnable {

    /** Pixels per second. Same speed as StatusBarVisualizerView's marquee, so the two agree. */
    private static final float PX_PER_S = 55f;
    /** How long each end of the line is held still, so it can actually be read. */
    private static final long PAUSE_MS = 1500L;
    /** One frame at 60 Hz. */
    private static final long FRAME_MS = 16L;

    private final TextView view;

    private float offset;
    private long pausedUntil;
    private long lastFrame;
    /** Set at the far end: the next move is the jump back to the beginning, not another step. */
    private boolean rewinding;

    private TextScroller(TextView view) {
        this.view = view;
    }

    /**
     * Starts scrolling {@code view}'s text whenever it does not fit. Safe to call once, early -
     * the view needs neither focus nor selection, which matters here because the dropdown must
     * stay unfocusable so that a tap opens the menu instead of placing a caret.
     */
    public static void attach(final TextView view) {
        if (view == null) return;
        view.setHorizontallyScrolling(true);
        final TextScroller scroller = new TextScroller(view);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                scroller.restart();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                v.removeCallbacks(scroller);
            }
        });
        if (view.isAttachedToWindow()) scroller.restart();
    }

    private void restart() {
        offset = 0f;
        pausedUntil = 0L;
        lastFrame = 0L;
        rewinding = false;
        view.scrollTo(0, 0);
        view.removeCallbacks(this);
        view.postDelayed(this, PAUSE_MS);
    }

    @Override
    public void run() {
        if (!view.isAttachedToWindow()) return;

        final float room = view.getWidth()
                - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
        final float textWidth = (view.getLayout() != null) ? view.getLayout().getLineWidth(0) : 0f;
        final float overflow = textWidth - room;
        final long now = SystemClock.uptimeMillis();

        if (overflow <= 0f) {
            // It fits. Stand at the beginning and keep looking, because the name will change.
            if (offset != 0f) {
                offset = 0f;
                view.scrollTo(0, 0);
            }
            lastFrame = 0L;
            rewinding = false;
            view.postDelayed(this, PAUSE_MS);
            return;
        }

        if (now < pausedUntil) {
            lastFrame = 0L;                     // do not count the pause as travelled time
            view.postDelayed(this, FRAME_MS);
            return;
        }

        if (rewinding) {
            rewinding = false;
            offset = 0f;
            view.scrollTo(0, 0);
            pausedUntil = now + PAUSE_MS;
            view.postDelayed(this, FRAME_MS);
            return;
        }

        if (lastFrame == 0L) lastFrame = now;
        offset += (now - lastFrame) * (PX_PER_S / 1000f);
        lastFrame = now;

        if (offset >= overflow) {
            offset = overflow;                  // hold the tail in view before going back
            rewinding = true;
            pausedUntil = now + PAUSE_MS;
        }

        view.scrollTo((int) offset, 0);
        view.postDelayed(this, FRAME_MS);
    }
}
