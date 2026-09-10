package com.radiorubka.wdsp;

import android.content.Context;
import android.content.Intent;
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
import android.view.KeyEvent;
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
    public static final String PREF_BG_ALPHA_DAY = "ss_bg_alpha_day";
    public static final String PREF_BG_ALPHA_NIGHT = "ss_bg_alpha_night";
    /** Packages that are never covered, stored as a string set. */
    public static final String PREF_BLOCKED = "ss_blocked_pkgs";
    /** Width of the band, as a fraction of the screen. Edge to edge by default. */
    public static final String PREF_WIDTH_F = "ss_width_f";
    /** Height of the band, as a fraction of the screen height. See {@link #heightFraction()}. */
    public static final String PREF_HEIGHT_F = "ss_height_f";
    /** Brightness of the bars, kept separately for the two themes. */
    public static final String PREF_BRIGHT_DAY = "ss_bright_day";
    public static final String PREF_BRIGHT_NIGHT = "ss_bright_night";
    /** Height of the now-playing strip along the bottom, as a fraction of the screen. */
    public static final String PREF_INFO_H = "ss_info_h";

    /** Independent visualizer styling preferences for screensaver */
    public static final String PREF_STYLE  = "ss_style";
    public static final String PREF_STYLE_DAY = "ss_style_day";
    public static final String PREF_STYLE_NIGHT = "ss_style_night";
    public static final String PREF_THEME  = "ss_theme";
    public static final String PREF_PEAKS  = "ss_peaks";
    public static final String PREF_MIRROR = "ss_mirror";
    public static final String PREF_BANDS  = "ss_bands";
    public static final String PREF_HUE    = "ss_hue";
    public static final String PREF_OSC_PERSISTENCE = "ss_osc_persistence";

    public static final int DEFAULT_DELAY_S = 60;
    public static final int DEFAULT_BG_ALPHA = 85;
    public static final int DEFAULT_BG_ALPHA_DAY = 85;
    public static final int DEFAULT_BG_ALPHA_NIGHT = 85;
    public static final float DEFAULT_WIDTH_F = 1.0f;
    public static final int DEFAULT_BRIGHT_DAY = 100;
    public static final int DEFAULT_BRIGHT_NIGHT = 70;
    public static final float DEFAULT_INFO_H = 0.14f;
    /**
     * What the strip is allowed to take.
     *
     * <p>Bounded on purpose. Below the floor there is no room for a cover and a line of text at a
     * size anybody can read across a car; above the ceiling the spectrum stops being the thing on
     * the screen, and this is a screensaver, not a now-playing page.
     */
    public static final float MIN_INFO_H = 0.08f;
    public static final float MAX_INFO_H = 0.25f;

    private static final long POLL_MS = 2000L;

    /**
     * How long the music has to stay stopped before the clock takes over.
     *
     * <h2>Why it is one-sided</h2>
     *
     * A gap between tracks, a moment of buffering, a player rebuilding its session - all of them
     * report "not playing" for a second or two, and a picture that swaps every time would be
     * worse than either picture on its own. So stopping has to be believed before it is acted on.
     *
     * <p>Starting does not: sound arriving is never a mistake, and the bars belong back on screen
     * the instant it does. Waiting symmetrically would mean staring at a clock over music.
     */
    private static final long PAUSE_HOLD_MS = 8000L;

    /** The platform names the foreground activity here, as {@code package/class}. */
    /**
     * Said out loud when the screensaver appears and disappears.
     *
     * <h2>Why anyone else needs to know</h2>
     *
     * Two overlays of the same window type share one layer, and inside a layer the last one added
     * is the one on top. There is no priority to set and no sub-layer to claim - the types that
     * sit above this one have been closed to ordinary apps since Oreo. So an overlay that must
     * stay reachable, like a radio's transport buttons, cannot simply declare itself important:
     * the only move available is to remove and re-add itself, which puts it back on top.
     *
     * <p>It cannot do that at the right moment without being told when the moment is. Hence this.
     * {@code EXTRA_RADIO} says whether wDSP thinks the sound is coming from a tuner, because that
     * is the case where the screensaver is deliberately showing nothing but a clock and has least
     * business being in the way.
     *
     * <h2>🔴 Sent without a package on purpose - do not "fix" it</h2>
     *
     * Every other broadcast this application sends names its recipient. These two do not, and that
     * is the owner's decision (07.09.2026): the radio is not the only application that may want to
     * know the screen has been taken over, and others are planned. Narrowing the action to
     * {@code com.kostyamat.fmradio} would be tidier and would quietly break every one of them.
     *
     * <p>⚠️ What it costs, so nobody expects more of it than it gives: an implicit broadcast does
     * not reach a receiver declared in another application's manifest - that has been true since
     * Oreo - and it does not start an application that is not running. A listener has to be alive
     * and registered at runtime, which an overlay that draws on screen already is.
     */
    public static final String ACTION_SHOWN = "com.radiorubka.wdsp.SCREENSAVER_SHOWN";
    public static final String ACTION_HIDDEN = "com.radiorubka.wdsp.SCREENSAVER_HIDDEN";
    public static final String EXTRA_RADIO = "radio";

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
    /** When the music was first seen stopped, or 0 while it is playing. */
    private long stoppedSince = 0L;

    /**
     * Set when the pause was our own doing - the middle area of the screen was pressed while
     * something was playing.
     *
     * <p>The waiting-out above exists because we cannot tell a real pause from a gap between
     * tracks. When the press came from here there is nothing to tell apart: we know why the music
     * stopped, so the clock can come straight up. A pause from anywhere else - the wheel, the
     * player's own screen, a phone over Bluetooth - still gets waited out, because from here it
     * still looks exactly like buffering.
     */
    private boolean ownPause = false;
    private String lastForeground = "";
    private long foregroundSince = 0L;
    private boolean previewMode = false;
    private Boolean previewNight = null;
    private Integer previewStyle = null;

    /**
     * Hears every touch on the unit, so that idle means idle.
     *
     * <p>The foreground activity is the only thing the platform publishes, and it does not change
     * while somebody scrolls a list or works through a settings page - so counting from it put the
     * screensaver on top of people who were plainly using the thing. See {@link TouchWatcher}.
     */
    private TouchWatcher touchWatcher;

    private static ScreensaverManager instance;

    public static synchronized ScreensaverManager getInstance(Context context) {
        if (instance == null) instance = new ScreensaverManager(context.getApplicationContext());
        return instance;
    }

    public boolean isAttached() {
        return attached;
    }

    public boolean isPreviewMode() {
        return previewMode;
    }

    private boolean isNight() {
        return previewNight != null ? previewNight : ThemeManager.isNight(context);
    }

    public void forceShow(int s, boolean night) {
        handler.post(() -> {
            previewMode = true;
            previewNight = night;
            previewStyle = s;
            applyBackdrop();
            applyStyleToScreensaver(s);
            show();
        });
    }

    public void forceShow(int s) {
        forceShow(s, ThemeManager.isNight(context));
    }

    public void forceShow() {
        forceShow(style());
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

    /**
     * Called when preferences have been restored from backup or migrated.
     * Re-evaluates polling state, idle clock, and hides the overlay if it was switched off.
     */
    public void onPreferencesRestored() {
        handler.post(() -> {
            resetIdleClock();
            if (attached && !isEnabled()) {
                hide();
            }
            startPolling();
        });
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
        return backgroundAlpha(isNight());
    }

    public int backgroundAlpha(boolean night) {
        String key = night ? PREF_BG_ALPHA_NIGHT : PREF_BG_ALPHA_DAY;
        if (prefs.contains(key)) {
            return Math.max(0, Math.min(100, prefs.getInt(key, night ? DEFAULT_BG_ALPHA_NIGHT : DEFAULT_BG_ALPHA_DAY)));
        }
        if (prefs.contains(PREF_BG_ALPHA)) {
            return Math.max(0, Math.min(100, prefs.getInt(PREF_BG_ALPHA, DEFAULT_BG_ALPHA)));
        }
        return night ? DEFAULT_BG_ALPHA_NIGHT : DEFAULT_BG_ALPHA_DAY;
    }

    public void setBackgroundAlpha(boolean night, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        String key = night ? PREF_BG_ALPHA_NIGHT : PREF_BG_ALPHA_DAY;
        SharedPreferences.Editor ed = prefs.edit().putInt(key, clamped);
        if (!night) {
            ed.putInt(PREF_BG_ALPHA, clamped);
        }
        ed.apply();
        applyBackdrop();
    }

    public void setBackgroundAlpha(int percent) {
        setBackgroundAlpha(ThemeManager.isNight(context), percent);
    }

    public int style(boolean night) {
        if (previewStyle != null) return previewStyle;
        String key = night ? PREF_STYLE_NIGHT : PREF_STYLE_DAY;
        if (prefs.contains(key)) return prefs.getInt(key, StatusBarVisualizerManager.DEFAULT_STYLE);
        if (prefs.contains(PREF_STYLE)) return prefs.getInt(PREF_STYLE, StatusBarVisualizerManager.DEFAULT_STYLE);
        return StatusBarVisualizerManager.getInstance(context).getStyle(night);
    }

    public int style() {
        return style(isNight());
    }

    public void setStyle(boolean night, int style) {
        String key = night ? PREF_STYLE_NIGHT : PREF_STYLE_DAY;
        prefs.edit().putInt(key, style).apply();
        if (!night) prefs.edit().putInt(PREF_STYLE, style).apply();
        if (ThemeManager.isNight(context) == night) {
            handler.post(() -> {
                applyStyleToScreensaver(style);
            });
        }
    }

    public void setStyle(int style) {
        setStyle(ThemeManager.isNight(context), style);
    }

    public int visualizerStyle() {
        return style();
    }

    public void setVisualizerStyle(int style) {
        setStyle(style);
    }

    public int theme(int s, boolean night) {
        String key = (PREF_THEME + "_" + s) + (night ? "_night" : "_day");
        if (prefs.contains(key)) return prefs.getInt(key, StatusBarVisualizerManager.defaultThemeForStyle(s));
        String baseKey = PREF_THEME + "_" + s;
        if (prefs.contains(baseKey)) return prefs.getInt(baseKey, StatusBarVisualizerManager.defaultThemeForStyle(s));
        return StatusBarVisualizerManager.getInstance(context).getThemeForStyle(s, night);
    }

    public int theme(int s) {
        return theme(s, isNight());
    }

    public void setTheme(int s, boolean night, int theme) {
        String key = (PREF_THEME + "_" + s) + (night ? "_night" : "_day");
        prefs.edit().putInt(key, theme).apply();
        prefs.edit().putInt(PREF_THEME + "_" + s, theme).apply();
        if (style() == s && ThemeManager.isNight(context) == night) {
            handler.post(() -> {
                StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
                if (strip.isLentToScreensaver()) {
                    strip.setScreensaverTheme(theme);
                } else if (standIn != null) {
                    standIn.setTheme(theme);
                }
            });
        }
    }

    public void setTheme(int s, int theme) {
        setTheme(s, ThemeManager.isNight(context), theme);
    }

    public int theme() {
        return theme(style());
    }

    public void setTheme(int theme) {
        setTheme(style(), theme);
    }

    public boolean peaksEnabled(int s) {
        String key = PREF_PEAKS + "_" + s;
        if (prefs.contains(key)) return prefs.getBoolean(key, StatusBarVisualizerManager.defaultPeaksForStyle(s));
        return StatusBarVisualizerManager.getInstance(context).getPeaksForStyle(s);
    }

    public void setPeaksEnabled(int s, boolean enabled) {
        prefs.edit().putBoolean(PREF_PEAKS + "_" + s, enabled).apply();
        if (style() == s) {
            handler.post(() -> {
                StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
                if (strip.isLentToScreensaver()) {
                    strip.setScreensaverPeaks(enabled);
                } else if (standIn != null) {
                    standIn.setPeakCapsEnabled(enabled);
                }
            });
        }
    }

    public boolean peaksEnabled() {
        return peaksEnabled(style());
    }

    public void setPeaksEnabled(boolean enabled) {
        setPeaksEnabled(style(), enabled);
    }

    public boolean mirrorFrequencies(int s) {
        String key = PREF_MIRROR + "_" + s;
        if (prefs.contains(key)) return prefs.getBoolean(key, StatusBarVisualizerManager.defaultMirrorForStyle(s));
        return StatusBarVisualizerManager.getInstance(context).getMirrorForStyle(s);
    }

    public void setMirrorFrequencies(int s, boolean mirror) {
        prefs.edit().putBoolean(PREF_MIRROR + "_" + s, mirror).apply();
        if (style() == s) {
            handler.post(() -> {
                StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
                if (strip.isLentToScreensaver()) {
                    strip.setScreensaverMirror(mirror);
                } else if (standIn != null) {
                    standIn.setMirrorFrequencies(mirror);
                }
            });
        }
    }

    public boolean mirrorFrequencies() {
        return mirrorFrequencies(style());
    }

    public void setMirrorFrequencies(boolean mirror) {
        setMirrorFrequencies(style(), mirror);
    }

    public int bandCount(int s) {
        String key = PREF_BANDS + "_" + s;
        if (prefs.contains(key)) return prefs.getInt(key, StatusBarVisualizerManager.defaultBandsForStyle(s));
        return StatusBarVisualizerManager.getInstance(context).getBandsForStyle(s);
    }

    public void setBandCount(int s, int bands) {
        prefs.edit().putInt(PREF_BANDS + "_" + s, bands).apply();
        if (style() == s) {
            handler.post(() -> {
                StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
                if (strip.isLentToScreensaver()) {
                    strip.setScreensaverBands(bands);
                } else if (standIn != null) {
                    standIn.setBandCount(bands);
                }
            });
        }
    }

    public int bandCount() {
        return bandCount(style());
    }

    public void setBandCount(int bands) {
        setBandCount(style(), bands);
    }

    public int hueShift(int s, boolean night) {
        String key = (PREF_HUE + "_" + s) + (night ? "_night" : "_day");
        if (prefs.contains(key)) return prefs.getInt(key, StatusBarVisualizerManager.defaultHueForStyle(s));
        String baseKey = PREF_HUE + "_" + s;
        if (prefs.contains(baseKey)) return prefs.getInt(baseKey, StatusBarVisualizerManager.defaultHueForStyle(s));
        return StatusBarVisualizerManager.getInstance(context).getHueForStyle(s, night);
    }

    public int hueShift(int s) {
        return hueShift(s, isNight());
    }

    public void setHueShift(int s, boolean night, int hue) {
        String key = (PREF_HUE + "_" + s) + (night ? "_night" : "_day");
        prefs.edit().putInt(key, hue).apply();
        prefs.edit().putInt(PREF_HUE + "_" + s, hue).apply();
        if (style() == s && ThemeManager.isNight(context) == night) {
            handler.post(() -> {
                StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
                if (strip.isLentToScreensaver()) {
                    strip.setScreensaverHue(hue);
                } else if (standIn != null) {
                    standIn.setHueShift(hue);
                }
            });
        }
    }

    public void setHueShift(int s, int hue) {
        setHueShift(s, ThemeManager.isNight(context), hue);
    }

    public int hueShift() {
        return hueShift(style());
    }

    public void setHueShift(int hue) {
        setHueShift(style(), hue);
    }

    public int oscPersistence(int s) {
        String key = PREF_OSC_PERSISTENCE + "_" + s;
        if (prefs.contains(key)) return prefs.getInt(key, StatusBarVisualizerManager.defaultPersistenceForStyle(s));
        return StatusBarVisualizerManager.getInstance(context).getPersistenceForStyle(s);
    }

    public void setOscPersistence(int s, int persistence) {
        int clamped = Math.max(0, Math.min(100, persistence));
        prefs.edit().putInt(PREF_OSC_PERSISTENCE + "_" + s, clamped).apply();
        if (style() == s) {
            handler.post(() -> {
                StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
                if (strip.isLentToScreensaver()) {
                    strip.setScreensaverOscPersistence(clamped);
                } else if (standIn != null) {
                    standIn.setOscPersistence(clamped);
                }
            });
        }
    }

    public int oscPersistence() {
        return oscPersistence(style());
    }

    public void setOscPersistence(int persistence) {
        setOscPersistence(style(), persistence);
    }

    public boolean normalizationEnabled(int s) {
        return StatusBarVisualizerManager.getInstance(context).getNormalizationForStyle(s);
    }

    public boolean normalizationEnabled() {
        return normalizationEnabled(style());
    }

    public void applyStyleToScreensaver(int s) {
        StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
        if (strip.isLentToScreensaver()) {
            strip.setScreensaverStyle(s);
            strip.setScreensaverTheme(theme(s));
            strip.setScreensaverHue(hueShift(s));
            strip.setScreensaverBands(bandCount(s));
            strip.setScreensaverPeaks(peaksEnabled(s));
            strip.setScreensaverMirror(mirrorFrequencies(s));
            strip.setScreensaverOscPersistence(oscPersistence(s));
            strip.setScreensaverNormalization(normalizationEnabled(s));
        } else if (standIn != null) {
            standIn.setStyle(s);
            standIn.setTheme(theme(s));
            standIn.setHueShift(hueShift(s));
            standIn.setBandCount(bandCount(s));
            standIn.setPeakCapsEnabled(peaksEnabled(s));
            standIn.setMirrorFrequencies(mirrorFrequencies(s));
            standIn.setOscPersistence(oscPersistence(s));
            standIn.setNormalizationEnabled(normalizationEnabled(s));
        }
    }

    public void onConfigurationChanged() {
        handler.post(() -> {
            boolean night = ThemeManager.isNight(context);
            applyBackdrop();
            applyStyleToScreensaver(style(night));
        });
    }

    public void cycleVisualizerStyle() {
        int cur = style();
        int next = (cur + 1) % 6;
        setStyle(next);
        applyStyleToScreensaver(next);
        StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
        strip.flashTransport(StatusBarVisualizerView.GLYPH_STYLE_CYCLE);
        if (standIn != null) standIn.flashTransport(StatusBarVisualizerView.GLYPH_STYLE_CYCLE);
    }

    /** How much of the screen the now-playing strip takes, within its limits. */
    public float infoBarFraction() {
        float stored = prefs.getFloat(PREF_INFO_H, DEFAULT_INFO_H);
        return Math.max(MIN_INFO_H, Math.min(MAX_INFO_H, stored));
    }

    public void setInfoBarFraction(float fraction) {
        prefs.edit().putFloat(PREF_INFO_H,
                Math.max(MIN_INFO_H, Math.min(MAX_INFO_H, fraction))).apply();
        applyGeometry();
    }

    /** Pixels the spectrum must keep clear at the bottom. */
    private int infoBarPx() {
        return Math.round(StatusBarVisualizerManager.getInstance(context).screenHeight()
                * infoBarFraction());
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
        String key = night ? PREF_BRIGHT_NIGHT : PREF_BRIGHT_DAY;
        if (!prefs.contains(key)) {
            // Inherits from status bar visualizer (widget) brightness if not explicitly overridden
            StatusBarVisualizerManager sbm = StatusBarVisualizerManager.getInstance(context);
            return sbm.getAlphaPercent(night);
        }
        int stored = prefs.getInt(key, night ? DEFAULT_BRIGHT_NIGHT : DEFAULT_BRIGHT_DAY);
        return Math.max(10, Math.min(100, stored));
    }

    public void setBrightness(boolean night, int percent) {
        prefs.edit().putInt(night ? PREF_BRIGHT_NIGHT : PREF_BRIGHT_DAY,
                Math.max(10, Math.min(100, percent))).apply();
        applyGeometry();
        applyBrightness();
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

    /**
     * The bottom third answers taps: previous, play or pause, next.
     *
     * <h2>Why this is worth the screen it takes</h2>
     *
     * A car with no wheel controls and no buttons on the fascia leaves one way to skip a track at
     * night on a motorway: dismiss the screensaver, wait for the launcher, and aim at a widget
     * button the size of a thumbnail. Three areas that cannot be missed replace all of that.
     *
     * <p>They cost nothing that was being used. The sliders only ever act on a drag, so a tap in
     * the same place was doing nothing at all before - it just dismissed the screensaver.
     */
    private static final float TRANSPORT_FROM = 0.50f;


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
                    onTap(e.getX(), e.getY());
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
     * A tap: transport along the bottom, and anywhere else it puts the screensaver away.
     */
    private void onTap(float x, float y) {
        if (previewMode) {
            hide();
            return;
        }
        StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
        int screenW = strip.screenWidth();
        int screenH = strip.screenHeight();
        float top = strip.systemStatusBarHeight();
        float bottom = screenH - infoBarPx();
        float usableH = Math.max(1f, bottom - top);

        // Lower half boundary: evenly distributed between the statusbar and the bottom stripe
        float midY = top + usableH * TRANSPORT_FROM;

        // 1. Taps in upper area dismiss the screensaver
        if (y < midY) {
            hide();
            return;
        }

        // 2. Hit-test bottom-right style cycle button (visible sine wave icon)
        float density = context.getResources().getDisplayMetrics().density;
        float btnHitRadius = 38f * density;
        float btnCx = screenW - 32f * density;
        float btnCy = screenH - 32f * density;
        float dx = x - btnCx;
        float dy = y - btnCy;
        if ((dx * dx + dy * dy) <= (btnHitRadius * btnHitRadius)) {
            cycleVisualizerStyle();
            resetIdleClock();
            return;
        }

        // 3. Hit-test Now Playing album art / player icon (bottom-left) to launch player
        float infoH = infoBarPx();
        if (y >= (screenH - infoH) && x <= Math.max(infoH * 2.0f, 120f * density)) {
            NowPlaying np = NowPlaying.getInstance(context);
            String pkg = np.playerPackage();
            if (pkg == null || pkg.isEmpty() || "com.android.fmradio".equals(pkg)) {
                try {
                    context.getPackageManager().getPackageInfo("com.kostyamat.fmradio", 0);
                    pkg = "com.kostyamat.fmradio";
                } catch (Throwable ignored) {}
            }
            if (pkg != null && !pkg.isEmpty()) {
                try {
                    Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(launch);
                        hide();
                        return;
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "could not launch player: " + pkg, t);
                }
            }
        }

        // 4. Lower half transport controls (Track -, Play/Pause, Track +)
        // Space free from edge sliders (left and right margins defined by EDGE_F)
        float leftBound = screenW * EDGE_F;
        float rightBound = screenW * (1.0f - EDGE_F);
        float freeW = Math.max(1f, rightBound - leftBound);
        float zoneW = freeW / 3.0f;

        NowPlaying np = NowPlaying.getInstance(context);
        int glyph;
        if (x < leftBound + zoneW) {
            np.skipToPrevious();
            glyph = StatusBarVisualizerView.GLYPH_PREVIOUS;
        } else if (x > leftBound + 2.0f * zoneW) {
            np.skipToNext();
            glyph = StatusBarVisualizerView.GLYPH_NEXT;
        } else {
            boolean playing = np.isPlaying();
            np.playPause();
            glyph = playing ? StatusBarVisualizerView.GLYPH_PAUSE : StatusBarVisualizerView.GLYPH_PLAY;
            ownPause = playing;
        }
        strip.flashTransport(glyph);
        if (standIn != null) standIn.flashTransport(glyph);
        // The screensaver stays. Skipping a track is not a reason to lose the picture.
        resetIdleClock();
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
        if (liveBackdrop >= 0) {
            boolean night = ThemeManager.isNight(context);
            editor.putInt(night ? PREF_BG_ALPHA_NIGHT : PREF_BG_ALPHA_DAY, liveBackdrop);
            if (!night) {
                editor.putInt(PREF_BG_ALPHA, liveBackdrop);
            }
        }
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
                        backdropColor(), brightness(), infoBarPx(), believedStopped(), gestures(), this::hide);
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
        NowPlaying.getInstance(context).setOnStarted(() -> {
            stoppedSince = 0L;
            // Cleared here and nowhere else. "Playing again" is a transition the player reports;
            // "playing right now" is a reading, and between our key and the player obeying it
            // that reading still says yes. Clearing on the reading threw the flag away inside
            // that gap whenever a poll happened to fall in it, which is why the clock came up
            // for some presses and not others.
            ownPause = false;
            if (!attached) return;
            StatusBarVisualizerManager.getInstance(context).setScreensaverNowPlaying(false);
            if (standIn != null) standIn.setScreensaverState(true, false);
        });
        // A stop we caused ourselves is acted on the moment the player confirms it. Any other one
        // falls through to the poll, which waits it out - pushPlaybackState asks believedStopped,
        // and that still says no.
        NowPlaying.getInstance(context).setOnStopped(this::pushPlaybackState);
        startPolling();
    }

    private void resetIdleClock() {
        foregroundSince = System.currentTimeMillis();
    }

    /**
     * Somebody touched the screen, so the delay starts again from here.
     *
     * <p>Cheap on purpose - one field, no work, no waking anything. It is called once per gesture
     * and must stay that way, because it sits directly in the path of every touch on the unit.
     *
     * <p>While the screensaver is up this does nothing: the screensaver's own window is in front
     * and its taps are what decide whether it stays or goes.
     */
    private void onTouchedSomewhere() {
        if (attached) return;
        resetIdleClock();
    }

    private void startPolling() {
        stopPolling();
        if (!isEnabled() || !screenOn) return;
        if (touchWatcher == null) touchWatcher = new TouchWatcher(context, this::onTouchedSomewhere);
        touchWatcher.start();
        handler.postDelayed(poll, POLL_MS);
    }

    private void stopPolling() {
        handler.removeCallbacks(poll);
        if (touchWatcher != null) touchWatcher.stop();
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
        updatePlaybackBelief();
        String foreground = orEmpty(HardwareProfile.systemProperty(PROP_CURRENT_ACTIVITY));
        long idleMs = System.currentTimeMillis() - foregroundSince;
        Log.d(TAG, "TICK attached=" + attached + ", fg=" + foreground + ", lastFg=" + lastForeground
                + ", idleMs=" + idleMs + ", delayMs=" + (delaySeconds() * 1000L)
                + ", mayShow=" + mayShowOver(foreground));
        if (attached) {
            if (!previewMode && !foreground.isEmpty() && !mayShowOver(foreground)) {
                Log.i(TAG, "Screensaver dismissed because foreground changed to blocked: " + foreground);
                hide();
                return;
            }
            pushPlaybackState();
            return;
        }

        if (!foreground.equals(lastForeground)) {
            lastForeground = foreground;
            resetIdleClock();
            return;
        }

        if (!mayShowOver(foreground)) {
            resetIdleClock();
            return;
        }

        if (idleMs >= delaySeconds() * 1000L) show();
    }

    /**
     * Tells the screensaver what the music is doing: the clock fades in once it has stopped, and
     * the bars come back the moment it starts.
     *
     * <p>Called from the poll, and again straight after a press on the play area - waiting up to a
     * whole poll to react to something the owner just did would be the slowest part of the button.
     */
    private void pushPlaybackState() {
        if (!attached) return;
        updatePlaybackBelief();
        boolean paused = !previewMode && believedStopped();
        StatusBarVisualizerManager strip = StatusBarVisualizerManager.getInstance(context);
        strip.setScreensaverNowPlaying(paused);
        strip.setScreensaverInfoSource(infoSource());
        if (standIn != null) {
            standIn.setScreensaverState(true, paused);
            standIn.setNowPlayingSource(infoSource());
        }
    }

    /**
     * Notices when the music stopped, so that {@link #believedStopped()} can wait it out.
     *
     * <p>Runs on every tick whether or not the screensaver is showing, so that a screensaver
     * appearing over music that has been off for a while starts on the clock rather than fading
     * into it eight seconds later.
     */
    private void updatePlaybackBelief() {
        boolean playing = NowPlaying.getInstance(context).isPlaying();
        boolean hasSignal = AudioSpectrumEngine.getInstance().hasSignalNow();
        if (playing || hasSignal) {
            stoppedSince = 0L;
        } else if (stoppedSince == 0L) {
            stoppedSince = System.currentTimeMillis();
        }
    }

    private boolean believedStopped() {
        // Radio: if radio mic visualizer is enabled, the cabin microphone captures the speakers,
        // so there is live spectrum and radio is NOT stopped.
        if (NowPlaying.getInstance(context).isRadioSource()) {
            return !AudioSpectrumEngine.getInstance().isRadioMicVisualizerEnabled();
        }
        // Our own pause, and the player has confirmed it. Both halves matter: without the second
        // one a key that reached nobody - no session, or a player that ignores it - would put a
        // clock over music that never stopped.
        if (ownPause && !NowPlaying.getInstance(context).isPlaying()) return true;
        return stoppedSince != 0L && System.currentTimeMillis() - stoppedSince >= PAUSE_HOLD_MS;
    }

    /** The strip is for what we can honestly show: tracks/RDS when playing or with active metadata, or active player info. */
    private NowPlaying infoSource() {
        return NowPlaying.getInstance(context);
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
                            backdropColor(), brightness(), infoBarPx(), !previewMode && believedStopped(), gestures(), this::hide);
                } else {
                    buildOverlay();
                    windowManager.addView(overlayRoot, overlayParams());
                    lendStripOrBuildOwn();
                }
                attached = true;
                announce(ACTION_SHOWN);
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
        standIn.setScreensaverState(true, !previewMode && believedStopped());
        standIn.setNowPlayingSource(infoSource());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER;
        overlayRoot.addView(standIn, lp);
    }

    public void hide() {
        Log.i(TAG, "screensaver hide() called! attached=" + attached, new Throwable("hide-caller"));
        handler.post(() -> {
            previewMode = false;
            previewNight = null;
            previewStyle = null;
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
            announce(ACTION_HIDDEN);
            resetIdleClock();
        });
    }

    private void announce(String action) {
        try {
            Intent intent = new Intent(action);
            intent.putExtra(EXTRA_RADIO, NowPlaying.getInstance(context).isRadioSource());
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            Log.w(TAG, "could not announce the screensaver", t);
        }
    }

    private void buildOverlay() {
        overlayRoot = new FrameLayout(context);
        overlayRoot.setBackgroundColor(backdropColor());

        overlayRoot.setOnTouchListener(gestures());
    }

    private StatusBarVisualizerView buildVisualizer() {
        StatusBarVisualizerView view = new StatusBarVisualizerView(context);
        view.setTheme(theme());
        view.setHueShift(hueShift());
        view.setAlphaPercent(brightness());
        view.setBandCount(bandCount());
        view.setStyle(style());
        view.setPeakCapsEnabled(peaksEnabled());
        view.setMirrorFrequencies(mirrorFrequencies());
        view.setOscPersistence(oscPersistence());
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
