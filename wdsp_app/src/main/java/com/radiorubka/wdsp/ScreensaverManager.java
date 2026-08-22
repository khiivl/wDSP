package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * A full-screen visualiser that appears when the head unit has been left alone.
 *
 * <h2>What counts as "left alone"</h2>
 *
 * An ordinary app cannot see touches that land in other apps - that needs an accessibility
 * service, which is a large permission to ask for a decoration. What it can see is which activity
 * is in front, and the platform publishes exactly that in {@code sys.qf.current.activity} - the
 * package and class, updated as it changes. So idle here means <b>the foreground has not changed
 * for the chosen number of seconds</b>. Somebody reading a map, scrolling a list or watching a
 * video is not touching anything either, which is why the two guards below exist.
 *
 * <p>Any touch on the screensaver dismisses it, and the clock starts again from that moment - so
 * a person who does not want it can always push it away and it will not fight them.
 *
 * <h2>What keeps it out of the way</h2>
 *
 * <ul>
 *   <li><b>Navigation.</b> Never over a live map. The platform says whether navigation is speaking
 *       ({@code sys.qf.navi_state}) and whether the floating navigation bar or a floating video
 *       window is up; any of those and the screensaver stays down.</li>
 *   <li><b>The owner's own list.</b> Whatever packages they choose are simply never covered.</li>
 *   <li><b>The screen being off</b>, and the overlay permission not being granted.</li>
 * </ul>
 *
 * <h2>Cost</h2>
 *
 * One property read every two seconds while it is enabled and the screen is on, and nothing at all
 * when it is off. The visualiser view is the same one the status-bar strip uses, so the spectrum is
 * already being computed for it; drawing it larger costs nothing extra to produce.
 */
public final class ScreensaverManager {

    private static final String TAG = "wDSP_Screensaver";

    public static final String PREF_ENABLED = "ss_enabled";
    /** Seconds of an unchanging foreground before it appears. */
    public static final String PREF_DELAY_S = "ss_delay_s";
    /** How black the backdrop is, 0..100. The visualiser is drawn on top of it. */
    public static final String PREF_BG_ALPHA = "ss_bg_alpha";
    /** Packages that are never covered, stored as a string set. */
    public static final String PREF_BLOCKED = "ss_blocked_pkgs";
    /** Width of the band, as a fraction of the screen. Edge to edge by default. */
    public static final String PREF_WIDTH_F = "ss_width_f";
    /** Height of the band, as a fraction of the screen height. See {@link #heightFraction()}. */
    public static final String PREF_HEIGHT_F = "ss_height_f";
    /** Brightness of the bars, kept separately for the two themes. */
    public static final String PREF_BRIGHT_DAY = "ss_bright_day";
    public static final String PREF_BRIGHT_NIGHT = "ss_bright_night";

    public static final int DEFAULT_DELAY_S = 60;
    public static final int DEFAULT_BG_ALPHA = 85;
    public static final float DEFAULT_WIDTH_F = 1.0f;
    public static final int DEFAULT_BRIGHT_DAY = 100;
    public static final int DEFAULT_BRIGHT_NIGHT = 70;

    private static final long POLL_MS = 2000L;

    /** The platform names the foreground activity here, as {@code package/class}. */
    private static final String PROP_CURRENT_ACTIVITY = "sys.qf.current.activity";
    private static final String PROP_NAVI_SPEAKING = "sys.qf.navi_state";
    private static final String PROP_FLOAT_NAVI_BAR = "persist.sys.float_navi_bar";
    private static final String PROP_FLOAT_VIDEO = "persist.sys.has.float.video";

    private final Context context;
    private final WindowManager windowManager;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout overlayRoot;
    /** Only built when the owner has the status-bar strip switched off. */
    private StatusBarVisualizerView standIn;
    private boolean attached = false;

    private boolean screenOn = true;
    private String lastForeground = "";
    private long foregroundSince = 0L;

    private static ScreensaverManager instance;

    public static synchronized ScreensaverManager getInstance(Context context) {
        if (instance == null) instance = new ScreensaverManager(context.getApplicationContext());
        return instance;
    }

    private ScreensaverManager(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = ThemeManager.prefs(context);
    }

    // -------------------------------------------------------------------------------------------
    // settings
    // -------------------------------------------------------------------------------------------

    /**
     * On unless it has been switched off.
     *
     * <p>A screensaver nobody knows about is a screensaver nobody turns on. It costs one property
     * read every two seconds, it never covers navigation, and one touch puts it away - so the
     * failure mode of being wrong about this is a person tapping the screen once and finding the
     * switch that stops it happening again.
     */
    public boolean isEnabled() {
        return prefs.getBoolean(PREF_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled) {
            resetIdleClock();
            startPolling();
        } else {
            stopPolling();
            hide();
        }
    }

    public int delaySeconds() {
        return Math.max(5, prefs.getInt(PREF_DELAY_S, DEFAULT_DELAY_S));
    }

    public void setDelaySeconds(int seconds) {
        prefs.edit().putInt(PREF_DELAY_S, Math.max(5, seconds)).apply();
        resetIdleClock();
    }

    public int backgroundAlpha() {
        if (liveBackdrop >= 0) return liveBackdrop;
        return Math.max(0, Math.min(100, prefs.getInt(PREF_BG_ALPHA, DEFAULT_BG_ALPHA)));
    }

    public void setBackgroundAlpha(int percent) {
        prefs.edit().putInt(PREF_BG_ALPHA, Math.max(0, Math.min(100, percent))).apply();
        handler.post(() -> {
            if (overlayRoot != null) overlayRoot.setBackgroundColor(backdropColor());
        });
    }

    public float widthFraction() {
        if (liveWidthF > 0f) return liveWidthF;
        return clamp01(prefs.getFloat(PREF_WIDTH_F, DEFAULT_WIDTH_F), 0.10f);
    }

    public void setWidthFraction(float fraction) {
        prefs.edit().putFloat(PREF_WIDTH_F, clamp01(fraction, 0.10f)).apply();
        applyGeometry();
    }

    /**
     * How tall the band is, as a fraction of the screen height.
     *
     * <h2>Where the default comes from</h2>
     *
     * From the strip the owner has already set up, scaled to the width of the screen. Take its
     * height and its width, stretch it edge to edge, and keep the shape: a 512 by 72 strip on a
     * 1280 wide screen becomes 1280 by 180. That is the "same thing, bigger" a person expects
     * before they touch anything, and it is why this is not simply a fixed number.
     *
     * <p>After the slider is moved the two are independent - moving the width does not drag the
     * height around behind it, because a control that changes something you did not ask it to
     * change is worse than one that needs two movements.
     */
    public float heightFraction() {
        if (liveHeightF > 0f) return liveHeightF;
        float stored = prefs.getFloat(PREF_HEIGHT_F, -1f);
        if (stored > 0f) return clamp01(stored, 0.03f);
        return clamp01(proportionalHeightFraction(), 0.03f);
    }

    private float proportionalHeightFraction() {
        StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
        int screenH = Math.max(1, strip.screenHeight());
        float stripWidthF = Math.max(0.05f, prefs.getFloat(
                StatusBarVisualizerManager.PREF_STATUS_BAR_WIDTH_F,
                StatusBarVisualizerManager.DEFAULT_WIDTH_F));
        float scaledHeightPx = strip.getStatusBarHeight() / stripWidthF;
        return scaledHeightPx / screenH;
    }

    public void setHeightFraction(float fraction) {
        prefs.edit().putFloat(PREF_HEIGHT_F, clamp01(fraction, 0.03f)).apply();
        applyGeometry();
    }

    /** Bar brightness for the theme in force, 10..100. */
    public int brightness() {
        if (liveBrightness > 0) return liveBrightness;
        return brightness(ThemeManager.isNight(context));
    }

    /** Repaints the backdrop alone, for the fourth drag. */
    private void applyBackdrop() {
        handler.post(() -> {
            if (!attached) return;
            StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
            if (strip.isLentToScreensaver()) {
                strip.setScreensaverBackdrop(backdropColor());
            } else if (overlayRoot != null) {
                overlayRoot.setBackgroundColor(backdropColor());
            }
        });
    }

    /** Repaints the bars without resizing anything, for the brightness drag. */
    private void applyBrightness() {
        handler.post(() -> {
            if (!attached) return;
            StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
            if (strip.isLentToScreensaver()) {
                strip.setScreensaverBrightness(brightness());
            } else if (standIn != null) {
                standIn.setAlphaPercent(brightness());
            }
        });
    }

    public int brightness(boolean night) {
        int stored = prefs.getInt(night ? PREF_BRIGHT_NIGHT : PREF_BRIGHT_DAY,
                night ? DEFAULT_BRIGHT_NIGHT : DEFAULT_BRIGHT_DAY);
        return Math.max(10, Math.min(100, stored));
    }

    public void setBrightness(boolean night, int percent) {
        prefs.edit().putInt(night ? PREF_BRIGHT_NIGHT : PREF_BRIGHT_DAY,
                Math.max(10, Math.min(100, percent))).apply();
        applyGeometry();
        handler.post(() -> {
            if (standIn != null) standIn.setAlphaPercent(brightness());
            if (overlayRoot != null) overlayRoot.setBackgroundColor(backdropColor());
        });
    }

    // -------------------------------------------------------------------------------------------
    // resizing it by hand, with nothing drawn to show for it
    //
    // Drag anywhere. Up and down is height, left and right is width, and the direction of the
    // first few millimetres decides which. There is no track, no thumb and no zone, because there
    // is nothing drawn on a screensaver to aim at - and an invisible target is one you miss.
    //
    // The first attempt did use zones, a strip down the left and one along the bottom, and it was
    // unusable for two compounding reasons. The strips were sized in dp, and this head unit
    // reports a density of exactly 1.0, so "72dp, wide enough for a thumb" came out as 72 physical
    // pixels. And a hand reaching for an edge control rides the edge itself: the touches came in
    // at eight to twenty-eight pixels from it, under even that.
    //
    // Direction has neither problem. It also cannot run out at the edge of the screen, because the
    // value moves with the distance travelled rather than with where the finger is.
    // -------------------------------------------------------------------------------------------

    /**
     * A vertical drag starting right of this is brightness rather than height.
     *
     * <p>A third of the screen, not a strip. The lesson from the first attempt at these gestures
     * is that an invisible target has to be one you cannot miss - and a hand reaching for an edge
     * rides the edge, so the zone runs all the way to it with nothing held back.
     */
    /**
     * How deep each edge reaches in, as a share of the screen.
     *
     * <p>Generous on purpose. Nothing is drawn to aim at, so the target has to be one you cannot
     * miss - and a hand reaching for an edge rides the edge itself, so each zone runs all the way
     * out with nothing held back.
     */
    private static final float EDGE_F = 0.18f;


    private static final int GRAB_NONE = 0;
    private static final int GRAB_HEIGHT = 1;
    private static final int GRAB_WIDTH = 2;
    private static final int GRAB_UNDECIDED = 3;
    private static final int GRAB_BRIGHT = 4;
    private static final int GRAB_BACKDROP = 5;

    private int grabbed = GRAB_NONE;
    private float grabValue;
    private float liveWidthF = -1f, liveHeightF = -1f;
    private int liveBrightness = -1;
    private int liveBackdrop = -1;
    private View.OnTouchListener touchListener;

    /**
     * The whole gesture, handed to the framework.
     *
     * <p>This was written by hand first - own slop, own axis test, own idea of how far is far
     * enough - and every one of those was wrong at least once. The system already knows the size
     * of the screen, how the touch panel is oriented on it and what counts as a drag on this
     * device; {@link GestureDetector} and {@link ViewConfiguration} are where those answers live,
     * so the only things left here are which axis means what and how fast it moves.
     *
     * <p>Displacement is measured from the down event to the current one, so the sign is plain and
     * there is no running total to get out of step: negative Y is upward, positive X is rightward.
     */
    private GestureDetector detector;

    private View.OnTouchListener gestures() {
        if (touchListener == null) {
            detector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    grabbed = GRAB_UNDECIDED;
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    hide();
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent down, MotionEvent now,
                                        float distanceX, float distanceY) {
                    if (down == null || now == null) return true;
                    onDrag(now.getX() - down.getX(), now.getY() - down.getY(),
                            down.getX(), down.getY());
                    return true;
                }
            });
            detector.setIsLongpressEnabled(false);
            touchListener = (view, event) -> {
                detector.onTouchEvent(event);
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    commitDrag();
                }
                return true;
            };
        }
        return touchListener;
    }

    /**
     * @param dx  how far right of the starting point the finger is now
     * @param dy  how far below it; upward is negative
     */
    private void onDrag(float dx, float dy, float downX, float downY) {
        StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
        int screenW = strip.screenWidth();
        int screenH = strip.screenHeight();

        if (grabbed == GRAB_UNDECIDED) {
            int slop = ViewConfiguration.get(context).getScaledTouchSlop();
            if (Math.abs(dx) < slop && Math.abs(dy) < slop) return;
            // Which edge the finger started nearest decides what is being changed. Four edges,
            // four values, and no need to remember whether this one wants an up-and-down or a
            // left-and-right - the drag can go either way and the larger component drives it.
            //
            // Deciding by direction alone came first and was worse: two of the four values had no
            // direction left to claim, and the two that shared an axis had to be told apart by a
            // zone anyway. An edge is a thing you can point at, even in the dark.
            //
            // In a corner two edges both claim the touch, and the winner used to be whichever
            // test came first - an answer nobody can predict by looking at the screen. The
            // nearest edge wins instead, so the corners split along their diagonals.
            //
            // Distances are a share of their own dimension. Measured in pixels the top and bottom
            // are always closer on a screen wider than it is tall, and most of the top-left
            // quarter would end up belonging to the top edge.
            float usableTop = strip.systemStatusBarHeight();
            float usableH = Math.max(1f, screenH - usableTop);
            float toTop = (downY - usableTop) / usableH;
            float toBottom = (screenH - downY) / usableH;
            float toLeft = downX / screenW;
            float toRight = (screenW - downX) / screenW;
            float nearest = Math.min(Math.min(toTop, toBottom), Math.min(toLeft, toRight));

            if (nearest > EDGE_F) {
                // Nowhere near an edge. The middle keeps the obvious meanings, so a drag that
                // starts nowhere in particular still does something sensible.
                if (Math.abs(dy) >= Math.abs(dx)) {
                    grabbed = GRAB_HEIGHT;
                    grabValue = heightFraction();
                } else {
                    grabbed = GRAB_WIDTH;
                    grabValue = widthFraction();
                }
            } else if (nearest == toTop) {
                grabbed = GRAB_BACKDROP;
                grabValue = backgroundAlpha();
            } else if (nearest == toBottom) {
                grabbed = GRAB_WIDTH;
                grabValue = widthFraction();
            } else if (nearest == toLeft) {
                grabbed = GRAB_HEIGHT;
                grabValue = heightFraction();
            } else {
                grabbed = GRAB_BRIGHT;
                grabValue = brightness();
            }
        }

        // Up is more and right is more, whichever way the finger actually went.
        float amount = Math.abs(dy) >= Math.abs(dx) ? -dy / screenH : dx / screenW;
        switch (grabbed) {
            case GRAB_HEIGHT:
                liveHeightF = clamp01(grabValue + amount, 0.03f);
                applyGeometry();
                break;
            case GRAB_WIDTH:
                liveWidthF = clamp01(grabValue + amount, 0.10f);
                applyGeometry();
                break;
            case GRAB_BRIGHT:
                liveBrightness = clampPercent(Math.round(grabValue + amount * 100f), 10);
                applyBrightness();
                break;
            case GRAB_BACKDROP:
                // All the way to solid black at 100, so the screensaver can hide the screen
                // completely if that is what somebody wants at night.
                liveBackdrop = clampPercent(Math.round(grabValue + amount * 100f), 0);
                applyBackdrop();
                break;
            default:
                break;
        }
    }

    private void commitDrag() {
        if (grabbed == GRAB_UNDECIDED || grabbed == GRAB_NONE) {
            grabbed = GRAB_NONE;
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (liveWidthF > 0f) editor.putFloat(PREF_WIDTH_F, liveWidthF);
        if (liveHeightF > 0f) editor.putFloat(PREF_HEIGHT_F, liveHeightF);
        if (liveBrightness > 0) {
            editor.putInt(ThemeManager.isNight(context) ? PREF_BRIGHT_NIGHT : PREF_BRIGHT_DAY,
                    liveBrightness);
        }
        if (liveBackdrop >= 0) editor.putInt(PREF_BG_ALPHA, liveBackdrop);
        editor.apply();
        liveWidthF = -1f;
        liveHeightF = -1f;
        liveBrightness = -1;
        liveBackdrop = -1;
        grabbed = GRAB_NONE;
        resetIdleClock();
    }

    private static int clampPercent(int value, int min) {
        return Math.max(min, Math.min(100, value));
    }

    private static float clamp01(float value, float min) {
        return Math.max(min, Math.min(1.0f, value));
    }

    /** Re-sizes it in place, so a slider moves it while it is on screen. */
    private void applyGeometry() {
        handler.post(() -> {
            if (!attached) return;
            StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
            int screenW = strip.screenWidth();
            int screenH = strip.screenHeight();
            int w = Math.max(1, Math.round(screenW * widthFraction()));
            int h = Math.max(1, Math.round(screenH * heightFraction()));
            if (strip.isLentToScreensaver()) {
                strip.lendToScreensaver(widthFraction(), heightFraction(),
                        backdropColor(), brightness(), gestures());
                return;
            }
            if (standIn == null) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) standIn.getLayoutParams();
            if (lp == null) return;
            lp.width = w;
            lp.height = h;
            standIn.setLayoutParams(lp);
        });
    }

    /** Packages the screensaver is never shown over. */
    public Set<String> blockedPackages() {
        Set<String> stored = prefs.getStringSet(PREF_BLOCKED, null);
        return stored == null ? new TreeSet<>() : new TreeSet<>(stored);
    }

    public void setBlockedPackages(Set<String> packages) {
        prefs.edit().putStringSet(PREF_BLOCKED, new HashSet<>(packages)).apply();
        resetIdleClock();
    }

    public boolean canDrawOverlays() {
        return Settings.canDrawOverlays(context);
    }

    // -------------------------------------------------------------------------------------------
    // the idle clock
    // -------------------------------------------------------------------------------------------

    /** Called from the service, once, and whenever the screen goes on or off. */
    public void setScreenState(boolean on) {
        this.screenOn = on;
        if (on) {
            resetIdleClock();
            startPolling();
        } else {
            stopPolling();
            hide();
        }
    }

    public void start() {
        resetIdleClock();
        startPolling();
    }

    private void resetIdleClock() {
        foregroundSince = System.currentTimeMillis();
    }

    private void startPolling() {
        stopPolling();
        if (!isEnabled() || !screenOn) return;
        handler.postDelayed(poll, POLL_MS);
    }

    private void stopPolling() {
        handler.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Log.w(TAG, "screensaver tick failed", t);
            }
            if (isEnabled() && screenOn) handler.postDelayed(this, POLL_MS);
        }
    };

    private void tick() {
        String foreground = orEmpty(HardwareProfile.systemProperty(PROP_CURRENT_ACTIVITY));
        if (!foreground.equals(lastForeground)) {
            lastForeground = foreground;
            resetIdleClock();
            // Something moved. If the screensaver was up it is no longer wanted - whatever put a
            // new activity in front did so for a reason.
            if (attached) hide();
            return;
        }
        if (attached) return;
        if (!mayShowOver(foreground)) {
            resetIdleClock();
            return;
        }
        long idleMs = System.currentTimeMillis() - foregroundSince;
        if (idleMs >= delaySeconds() * 1000L) show();
    }

    /**
     * Whether the screensaver is allowed on top of what is currently in front.
     *
     * <p>The navigation checks are deliberately generous: three different properties, any one of
     * which vetoes. Covering a map in traffic is the one failure that would matter, so the cost of
     * being wrong is not symmetric and neither is the test.
     */
    private boolean mayShowOver(String foreground) {
        if (!isEnabled() || !screenOn || !canDrawOverlays()) return false;
        if (isTrue(HardwareProfile.systemProperty(PROP_NAVI_SPEAKING))) return false;
        if (isTrue(HardwareProfile.systemProperty(PROP_FLOAT_NAVI_BAR))) return false;
        if (isTrue(HardwareProfile.systemProperty(PROP_FLOAT_VIDEO))) return false;
        String pkg = packageOf(foreground);
        if (pkg.isEmpty()) return false;
        // Never over our own settings screen: somebody is in there adjusting this very thing.
        if (pkg.equals(context.getPackageName()) && foreground.contains("SettingsActivity")) {
            return false;
        }
        return !blockedPackages().contains(pkg);
    }

    /** {@code com.example/.MainActivity} -> {@code com.example} */
    public static String packageOf(String currentActivity) {
        if (currentActivity == null) return "";
        int slash = currentActivity.indexOf('/');
        return slash > 0 ? currentActivity.substring(0, slash) : currentActivity;
    }

    /** What the platform says is in front right now, for the settings screen to offer as a hint. */
    public String currentForegroundPackage() {
        return packageOf(orEmpty(HardwareProfile.systemProperty(PROP_CURRENT_ACTIVITY)));
    }

    // -------------------------------------------------------------------------------------------
    // the overlay
    // -------------------------------------------------------------------------------------------

    private void show() {
        handler.post(() -> {
            if (attached) return;
            try {
                StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
                if (strip.isAttached()) {
                    // No window of our own: the strip grows to fill the screen and paints the
                    // backdrop itself. One window, and - the point of it - no detach, so the bars
                    // keep running instead of freezing while the audio session is found again.
                    strip.lendToScreensaver(widthFraction(), heightFraction(),
                            backdropColor(), brightness(), gestures());
                } else {
                    buildOverlay();
                    windowManager.addView(overlayRoot, overlayParams());
                    lendStripOrBuildOwn();
                }
                attached = true;
                Log.i(TAG, "screensaver shown over " + lastForeground);
            } catch (Throwable t) {
                Log.w(TAG, "could not show the screensaver", t);
                attached = false;
            }
        });
    }

    /**
     * Stretches the owner's own strip across the screen, or draws a stand-in if there is none.
     *
     * <p>The stand-in matters: somebody can have the status-bar strip switched off and still want
     * a screensaver, and refusing to appear in that case would look like the feature is broken.
     */
    private void lendStripOrBuildOwn() {
        StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
        int w = Math.max(1, Math.round(strip.screenWidth() * widthFraction()));
        int h = Math.max(1, Math.round(strip.screenHeight() * heightFraction()));
        standIn = buildVisualizer();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        overlayRoot.addView(standIn, lp);
    }

    public void hide() {
        handler.post(() -> {
            if (!attached) return;
            // The strip goes back first: if removing the backdrop threw, the thing the owner
            // actually looks at every day is still the one that gets restored.
            StatusBarVisualizerManager.getInstance(context).takeBackFromScreensaver();
            standIn = null;
            if (overlayRoot != null) {
                try {
                    windowManager.removeView(overlayRoot);
                } catch (Throwable ignored) {
                }
                overlayRoot = null;
            }
            attached = false;
            resetIdleClock();
        });
    }

    private void buildOverlay() {
        overlayRoot = new FrameLayout(context);
        overlayRoot.setBackgroundColor(backdropColor());

        overlayRoot.setOnTouchListener(gestures());
    }

    private StatusBarVisualizerView buildVisualizer() {
        StatusBarVisualizerView view = new StatusBarVisualizerView(context);
        view.setTheme(prefs.getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_THEME,
                StatusBarVisualizerManager.DEFAULT_THEME));
        view.setHueShift(prefs.getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_HUE,
                StatusBarVisualizerManager.DEFAULT_HUE));
        view.setAlphaPercent(brightness());
        view.setBandCount(prefs.getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_BANDS,
                StatusBarVisualizerManager.DEFAULT_BANDS));
        view.setNormalizationEnabled(true);
        return view;
    }

    private int backdropColor() {
        int alpha = Math.round(255f * backgroundAlpha() / 100f);
        return Color.argb(alpha, 0, 0, 0);
    }

    private WindowManager.LayoutParams overlayParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean isTrue(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    /** For the settings screen: the packages worth offering, newest-looking first. */
    public static Set<String> launchablePackages(Context context) {
        Set<String> out = new TreeSet<>();
        try {
            android.content.Intent main = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            main.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo info :
                    context.getPackageManager().queryIntentActivities(main, 0)) {
                if (info.activityInfo != null) out.add(info.activityInfo.packageName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not list launchable packages", t);
        }
        return out;
    }
}
