package com.radiorubka.wdsp;

import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.util.Locale;

/**
 * Answers the two questions that decide how much this head unit can be trusted to sound the same
 * as the next one: which sound processor is fitted, and whether the audio policies are the stock
 * ones.
 *
 * Both answers are read at runtime and cached, because neither can change without a reboot.
 */
public final class HardwareProfile {
    private static final String TAG = "wDSP_Hardware";

    /**
     * The MCU firmware version carries the hardware code in its last group.
     *
     * Example from a unit with the BU32107: {@code QF05.V02.13.20251124.002121}. The trailing
     * {@code 002121} is the code, and the pair at its end names the sound processor - {@code 21}
     * for the BU32107. The two digits in the middle vary between builds and mean nothing here.
     */
    private static final String PROP_MCU_VERSION = "persist.sys.qf.mcu.version";

    private static Boolean bu32107;
    private static String mcuCode;

    private HardwareProfile() {
    }

    /** The hardware code from the MCU version string, or null if the property is not there. */
    public static synchronized String mcuCode() {
        if (mcuCode == null) {
            String version = systemProperty(PROP_MCU_VERSION);
            if (version != null && version.contains(".")) {
                mcuCode = version.substring(version.lastIndexOf('.') + 1).trim();
            }
            Log.i(TAG, "MCU version=" + version + " code=" + mcuCode);
        }
        return mcuCode;
    }

    /**
     * Which AKM audio hub sits between the MCU and the amplifier, if any.
     *
     * <h2>Why this matters more than it looks</h2>
     *
     * Some units carry a second DSP - an AK7738 or an AK7604 - in front of the sound processor.
     * On those, the radio and the second Bluetooth module are analogue inputs of that hub, Android
     * is a digital input, and the MCU cross-fades between them. wDSP never talks to it, but almost
     * every "the volume did something strange" report comes from units that have one: the hub's
     * master volume is re-pushed on every source change, and the value pushed is whatever the
     * platform's per-source volume property says at that instant.
     *
     * <h2>How the platform decides, and why this copies it exactly</h2>
     *
     * Not by probing. The framework reads the MCU version string, takes the part after the last
     * dot, and looks at its <b>second character</b>: 2 means AK7738, 3 means AK7604, anything else
     * means no hub at all. A letter counts on from 9. That is the whole test, and it is reproduced
     * here character for character - a report that disagrees with the platform about which DSP is
     * fitted is worse than no report.
     *
     * @return "AK7738", "AK7604" or "none"
     */
    public static synchronized String audioHub() {
        if (audioHub == null) {
            audioHub = "none";
            String code = mcuCode();
            if (code != null && code.length() >= 6) {
                char c = code.charAt(1);
                int type = Character.isDigit(c) ? c - '0' : c + 10 - 'a';
                if (type == 2) audioHub = "AK7738";
                else if (type == 3) audioHub = "AK7604";
            }
            Log.i(TAG, "audio hub=" + audioHub + " from MCU code=" + code);
        }
        return audioHub;
    }

    private static String audioHub;

    /**
     * True when the unit carries the ROHM BU32107, false when it is the cut-down BD37544.
     *
     * The MCU speaks one command set to both chips and makes the lesser one look complete, so the
     * commands cannot tell them apart - only the firmware code can. When the answer is not known
     * at all, the caller gets false: claiming the better chip on a unit that does not have it
     * would promise the user something the hardware cannot do.
     */
    public static synchronized boolean hasBu32107() {
        if (bu32107 == null) {
            String code = mcuCode();
            bu32107 = code != null && code.length() == 6
                    && code.startsWith("00") && code.endsWith("21");
            Log.i(TAG, "sound processor: " + (bu32107 ? "BU32107" : "not BU32107 (BD37544?)")
                    + ", code=" + code);
        }
        return bu32107;
    }

    /**
     * Whether the capture path carries voice processing - noise suppression in particular.
     *
     * This is what separates a unit with custom audio policies (the BitPerfect module) from a
     * factory one, and it was measured both ways on the same head unit: with stock policies
     * {@code NoiseSuppressor.isAvailable()} is false, with the module it is true and the effect
     * comes up already enabled.
     *
     * It matters for anything that measures sound rather than plays it. Noise suppression exists
     * to remove steady signals, which is exactly what a test tone is, and echo cancellation exists
     * to remove what the speakers are playing, which is exactly what we want to hear.
     */
    public static boolean captureHasVoiceProcessing() {
        return NoiseSuppressor.isAvailable();
    }

    /** One line for the log and for the diagnostics screen. */
    public static String describe() {
        return String.format(Locale.US,
                "MCU code=%s, sound processor=%s, capture effects: AEC=%b NS=%b AGC=%b",
                mcuCode(), hasBu32107() ? "BU32107" : "BD37544 or unknown",
                AcousticEchoCanceler.isAvailable(), NoiseSuppressor.isAvailable(),
                AutomaticGainControl.isAvailable());
    }

    /**
     * The board, so a report says which machine it came from.
     *
     * <p>Two units with the same MCU code can still be different computers - the platform ships on
     * UIS7862, UIS7862S and the weaker UIS8581 - and when somebody reports that something is slow
     * or stutters, this is the first thing worth knowing.
     */
    public static String describeBoard() {
        return String.format(Locale.US, "board=%s platform=%s model=%s android=%s",
                orUnknown(systemProperty("ro.product.board")),
                orUnknown(systemProperty("ro.board.platform")),
                orUnknown(systemProperty("ro.product.model")),
                android.os.Build.VERSION.RELEASE);
    }

    /**
     * The screen, and where the system says its bars are.
     *
     * <h2>Why the insets and not just the size</h2>
     *
     * The status-bar visualiser draws a strip across the top of the screen, as tall as the
     * platform's own {@code status_bar_height}. That works on an ordinary head unit and fails on
     * the Tesla-style portrait ones, where the bar along the edge belongs to the launcher rather
     * than to Android: the resource then describes something that is not where the bar is, and the
     * overlay lands in the wrong place or with the wrong height.
     *
     * <p>Guessing is not going to fix that. What is needed is the real geometry from the units that
     * have the problem, so this records the resource value <b>and</b> what the window system
     * actually reports - the system-bar insets on all four edges, which is what shows a bar that
     * is not on top at all - and the launcher that draws it.
     *
     * @param view any attached view, for the real insets; may be null, and then only the
     *             resource-derived numbers are available
     */
    public static String describeScreen(android.content.Context context, android.view.View view) {
        StringBuilder sb = new StringBuilder();
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        sb.append(String.format(Locale.US,
                "screen=%dx%d px, density=%.2f (%d dpi), %dx%d dp",
                dm.widthPixels, dm.heightPixels, dm.density, dm.densityDpi,
                Math.round(dm.widthPixels / dm.density), Math.round(dm.heightPixels / dm.density)));

        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        int declared = resId > 0 ? context.getResources().getDimensionPixelSize(resId) : -1;
        sb.append(String.format(Locale.US, ", status_bar_height=%d px", declared));

        String rotation = systemProperty("persist.sys.qf.sf.hwrotation");
        if (rotation != null) sb.append(", hwrotation=").append(rotation);

        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) context.getSystemService(android.content.Context.WINDOW_SERVICE);
            if (wm != null) {
                sb.append(", rotation=").append(wm.getDefaultDisplay().getRotation());
            }
        } catch (Throwable ignored) {
        }

        int insetTop = -1;
        if (view != null && view.getRootWindowInsets() != null) {
            android.view.WindowInsets insets = view.getRootWindowInsets();
            // The deprecated accessors are used on purpose: this app targets API 29, where
            // getInsets(Type.systemBars()) does not exist yet.
            insetTop = insets.getSystemWindowInsetTop();
            sb.append(String.format(Locale.US,
                    ", system bars: top=%d bottom=%d left=%d right=%d px",
                    insetTop, insets.getSystemWindowInsetBottom(),
                    insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetRight()));
            if (insets.getDisplayCutout() != null) {
                sb.append(", cutout present");
            }
        } else {
            sb.append(", system bars: not sampled");
        }

        // What the overlay would actually be told, and what it is currently using.
        int visibleTop = -1;
        if (view != null) {
            android.graphics.Rect frame = new android.graphics.Rect();
            view.getWindowVisibleDisplayFrame(frame);
            visibleTop = frame.top;
            sb.append(", visible frame top=").append(visibleTop);
        }
        int stored = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_HEIGHT_PX, 0);
        sb.append(", overlay height in use=").append(stored > 0 ? stored : declared);

        // 🔴 The Tesla case, stated rather than left to be worked out from four numbers.
        //
        // The status-bar visualiser is placed from the height Android reserves at the top. That is
        // right wherever Android really owns a bar there - including this platform's own heavily
        // customised one, which reports 72 px and works. It is wrong where the strip along the top
        // belongs to the launcher instead: Android then reserves nothing, the calibration in
        // MainActivity does nothing because it only acts when the visible frame starts below zero,
        // and the overlay falls back to a resource that describes a bar which is not there.
        //
        // Some of these units carry a vendor modification that gives Android a real bar again, and
        // on those the app is fine - which is exactly why the fault looks random from outside.
        if (visibleTop == 0 && insetTop == 0) {
            sb.append("  <-- NO ANDROID STATUS BAR: the strip on screen belongs to the launcher, "
                    + "so no automatic height can be right here");
        } else if (visibleTop >= 0 && declared > 0 && Math.abs(visibleTop - declared) > 4) {
            sb.append(String.format(Locale.US,
                    "  <-- MISMATCH: the resource says %d px and the window system says %d",
                    declared, visibleTop));
        }

        String launcher = launcherPackage(context);
        if (launcher != null) sb.append(", launcher=").append(launcher);
        return sb.toString();
    }

    private static String sampledScreen;

    /**
     * Takes the screen description while a window exists, so a report written later can use it.
     *
     * <p>The insets are the point of this: they can only be read from an attached view, and the
     * measurement writes its report from a service where there is none. Called from Settings just
     * before a measurement starts.
     */
    public static void sampleScreen(android.content.Context context, android.view.View view) {
        sampledScreen = describeScreen(context, view);
        Log.i(TAG, sampledScreen);
    }

    /** The last sampled screen description, or a fresh one without insets if none was taken. */
    public static String screenDescription(android.content.Context context) {
        return sampledScreen != null ? sampledScreen : describeScreen(context, null);
    }

    /**
     * Which launcher is drawing the bar the overlay has to share the screen with.
     *
     * <p>Resolving the home intent can answer {@code android}, which is not a launcher at all but
     * the chooser the platform shows when no default is set. That answer is useless here - the
     * whole point is to learn which shell owns the bar - so when it comes back the installed home
     * activities are listed instead.
     */
    private static String launcherPackage(android.content.Context context) {
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.Intent home = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo info =
                    pm.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            String resolved = info != null && info.activityInfo != null
                    ? info.activityInfo.packageName : null;
            if (resolved != null && !"android".equals(resolved)) return resolved;

            StringBuilder all = new StringBuilder();
            for (android.content.pm.ResolveInfo candidate : pm.queryIntentActivities(home, 0)) {
                if (candidate.activityInfo == null) continue;
                if (all.length() > 0) all.append('|');
                all.append(candidate.activityInfo.packageName);
            }
            return all.length() > 0 ? "none set, installed: " + all : resolved;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String orUnknown(String value) {
        return value == null || value.isEmpty() ? "?" : value;
    }

    /** Package-visible: {@link SystemDiagnostics} reads a long list of these for its report. */
    static String systemProperty(String key) {
        try {
            @SuppressWarnings("PrivateApi")
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Object value = systemProperties.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            if (value instanceof String && !((String) value).isEmpty()) return (String) value;
        } catch (Throwable t) {
            Log.w(TAG, "could not read " + key + ": " + t);
        }
        return null;
    }
}
