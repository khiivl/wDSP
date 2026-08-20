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

    private static String systemProperty(String key) {
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
