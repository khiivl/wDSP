package com.radiorubka.wdsp;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.Set;

/**
 * The service preset for phone calls. It is an array in the program, not a stored preset, and it is
 * meant to stay that way.
 *
 * <p>The owner's specification, 14.09.2026: its only purpose is a neutral acoustic path for the
 * echo canceller and the noise reduction of whatever handles the call. It is not renamed, not bound
 * to a player, never the default, never deleted, and not edited - no delays, no subwoofer, no
 * surround, no loudness curve, and the equaliser flat. "Effects at zero, fader to the front."
 *
 * <p>Why the fader is not a matter of taste: a tablet-class AEC is a stereo algorithm and cannot
 * model the rear of a car - the extra distance, and on some cars a second DSP and one or two more
 * amplifiers in the path, arrive as delays and echoes it physically cannot cancel. Rear passengers
 * do not need the call anyway. So the rear is taken out, not turned down.
 *
 * <h3>What this replaced</h3>
 *
 * <p>Until now "Call" existed only as a name added to the spinner. No value for it was defined
 * anywhere, so every read of {@code Call_*} fell through to the reader's default - measured on the
 * wire 13.09.2026 during a Bluetooth call as {@code 81 0C 0C 00}: the fader at centre. The screen
 * showed centre too, because it read the same defaults. Flat EQ and zero effects happened to come
 * out right by accident; the one thing that makes this preset different from defaults did not.
 *
 * <h3>How it is enforced</h3>
 *
 * <p>{@link #readView} wraps a preferences store so that every {@code Call_*} read returns the value
 * from this class, whatever is on disk. Both McuService and MainActivity read presets through it,
 * so the chip and the screen cannot disagree, and a {@code Call_*} key arriving from an old backup,
 * an import or a stray write changes nothing. Writes are blocked separately, in the UI.
 */
public final class CallPreset {

    public static final String NAME = "Call";

    /** Door channels front only: 12 is centre, 24 is the front end of the scale, which mutes the rear. */
    public static final int FADER_FR = 24;
    public static final int FADER_LR = 12;
    /** Flat: gain index 6 is 0 dB. */
    public static final int GAIN_IDX = 6;
    /**
     * The subwoofer as far down as the hardware goes. ⚠️ 0x8B has no negative gain - index 0 is
     * 0 dB, not a cut - so the lever that actually takes the sub out of the voice band is its
     * low-pass: index 0 is the lowest crossover, 25 Hz, where nothing a voice contains remains.
     */
    public static final int SUB_GAIN_IDX = 0;
    public static final int SUB_FREQ_IDX = 0;
    /** RSSE neutral - the byte the surround frame is built from when surround is off. */
    public static final int RSSE_NEUTRAL = 10;

    private static final String PREFIX = NAME + "_";

    private CallPreset() {
    }

    public static boolean is(@Nullable String presetName) {
        return NAME.equals(presetName);
    }

    /**
     * The array itself. Returns the service preset's value for a key suffix such as {@code "_f_fr"}
     * or {@code "_g7"}, or {@code null} when the suffix is not one the preset defines - GALA and the
     * power-amp pre-volume are left to the ordinary store, because the specification does not touch
     * them.
     */
    @Nullable
    static Integer intValue(String suffix) {
        if (suffix.startsWith("_g") && !suffix.startsWith("_gala")) return GAIN_IDX;
        switch (suffix) {
            case "_sub_g":    return SUB_GAIN_IDX;
            case "_sub_f":    return SUB_FREQ_IDX;
            case "_f_fr":     return FADER_FR;
            case "_f_lr":     return FADER_LR;
            case "_bb_f":
            case "_bb_r":
            case "_bb_frq_f":
            case "_bb_frq_r":
            case "_bf_f":
            case "_bf_r":
            case "_d_fl":
            case "_d_fr":
            case "_d_rl":
            case "_d_rr":
            case "_d_sub":
            case "_d1_fl":
            case "_d1_fr":
            case "_d1_rl":
            case "_d1_rr":    return 0;
            // The curve is off through _fm_en. These two are sane rather than zero so that the
            // loudness verdict does not report the service preset as a "dead curve" to fix.
            case "_fm_str":   return 100;
            case "_fm_cal":   return RoomMeasurement.MEASURE_VOLUME;
            case "_rsse_val": return RSSE_NEUTRAL;
            default:          return null;
        }
    }

    @Nullable
    static Boolean boolValue(String suffix) {
        if (suffix.startsWith("_q")) return false;
        switch (suffix) {
            case "_loud":
            case "_fm_en":
            case "_fat_en":
            case "_sub_comp":
            case "_d_en":
            case "_d1_en":    return false;
            default:          return null;
        }
    }

    /** Wraps a store so that reads of the service preset come from the array above. */
    public static SharedPreferences readView(SharedPreferences base) {
        return base instanceof View ? base : new View(base);
    }

    private static final class View implements SharedPreferences {
        private final SharedPreferences base;

        View(SharedPreferences base) {
            this.base = base;
        }

        @Override
        public int getInt(String key, int defValue) {
            if (key != null && key.startsWith(PREFIX)) {
                Integer v = intValue(key.substring(NAME.length()));
                if (v != null) return v;
            }
            return base.getInt(key, defValue);
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            if (key != null && key.startsWith(PREFIX)) {
                Boolean v = boolValue(key.substring(NAME.length()));
                if (v != null) return v;
            }
            return base.getBoolean(key, defValue);
        }

        @Override
        public Map<String, ?> getAll() {
            return base.getAll();
        }

        @Nullable
        @Override
        public String getString(String key, @Nullable String defValue) {
            return base.getString(key, defValue);
        }

        @Nullable
        @Override
        public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
            return base.getStringSet(key, defValues);
        }

        @Override
        public long getLong(String key, long defValue) {
            return base.getLong(key, defValue);
        }

        @Override
        public float getFloat(String key, float defValue) {
            return base.getFloat(key, defValue);
        }

        @Override
        public boolean contains(String key) {
            return base.contains(key);
        }

        @Override
        public Editor edit() {
            return base.edit();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
            base.registerOnSharedPreferenceChangeListener(l);
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {
            base.unregisterOnSharedPreferenceChangeListener(l);
        }
    }
}
