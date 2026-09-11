package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates, repairs, and migrates the wDSP EQ & audio presets database ({@code EqPresets.xml}).
 *
 * <p>Ensures that databases created by older app versions (with lower {@code versionCode}),
 * or restored from legacy JSON backups/files:
 * <ul>
 *   <li>Have all missing keys populated with safe, hardware-valid defaults.</li>
 *   <li>Have all slider and parameter values strictly clamped within current hardware and UI bounds
 *       (preventing {@link IllegalArgumentException} crashes in {@code Slider.setValue()}).</li>
 *   <li>Enforce mutual exclusion between Precise Delays (Time Alignment, {@code _d_en}) and
 *       Surround / RSSE ({@code _d1_en}) due to shared delay RAM on BU32107 DSP.</li>
 *   <li>Recover orphaned presets that exist in the XML keys but are missing from the preset name list.</li>
 *   <li>Track database schema versioning ({@link #PREF_DB_VERSION_CODE}).</li>
 * </ul>
 */
public final class PresetsDatabaseValidator {

    private static final String TAG = "wDSP_PresetsValidator";

    public static final String PREFS_NAME = "EqPresets";
    public static final String PREF_PRESET_NAMES = "preset_names";
    public static final String PREF_LAST_SELECTED = "last_selected_preset";
    public static final String PREF_DEFAULT_PRESET = "default_preset_name";

    public static final String PREF_DB_VERSION_CODE = "pref_presets_db_version_code";
    public static final String PREF_DB_VERSION_NAME = "pref_presets_db_version_name";
    public static final String PREF_DB_MIGRATED_AT = "pref_presets_db_migrated_at";

    // Constraints matching hardware DSP BU32107 & UI Sliders
    public static final int BAND_GAIN_MIN = 0;
    public static final int BAND_GAIN_MAX = 12;
    public static final int BAND_GAIN_DEFAULT = 6; // 0 dB flat

    public static final int SUB_GAIN_MIN = 0;
    public static final int SUB_GAIN_MAX = 12;
    public static final int SUB_GAIN_DEFAULT = 0;

    public static final int SUB_FREQ_MIN = 0;
    public static final int SUB_FREQ_MAX = 10;
    public static final int SUB_FREQ_DEFAULT = 5; // 80 Hz

    public static final int POWER_VOL_MIN = 0;
    public static final int POWER_VOL_MAX = 48;
    public static final int POWER_VOL_DEFAULT = 0;

    public static final int BASS_FILTER_MIN = 0;
    public static final int BASS_FILTER_MAX = 11;
    public static final int BASS_FILTER_DEFAULT = 0;

    public static final int BASS_BOOST_MIN = 0;
    public static final int BASS_BOOST_MAX = 12;
    public static final int BASS_BOOST_DEFAULT = 0;

    public static final int BASS_BOOST_FREQ_MIN = 0;
    public static final int BASS_BOOST_FREQ_MAX = 7;
    public static final int BASS_BOOST_FREQ_DEFAULT = 0;

    public static final int FADER_MIN = 0;
    public static final int FADER_MAX = 24;
    public static final int FADER_DEFAULT = 12; // center

    public static final int FM_CAL_VOL_MIN = 0;
    public static final int FM_CAL_VOL_MAX = 32;
    public static final int FM_CAL_VOL_DEFAULT = 25;

    public static final int FM_STRENGTH_MIN = 0;
    public static final int FM_STRENGTH_MAX = 100;
    public static final int FM_STRENGTH_DEFAULT = 100;

    public static final int DELAY_MIN = 0;
    public static final int DELAY_MAX = 40;
    public static final int DELAY_DEFAULT = 0;

    public static final int DELAY_RSSE_MIN = 0;
    public static final int DELAY_RSSE_MAX = 20;
    public static final int DELAY_RSSE_DEFAULT = 10;

    public static final int GALA_INC_MIN = 0;
    public static final int GALA_INC_MAX = 45;
    public static final int GALA_INC_DEFAULT = 15;

    public static final int GALA_MIN_SPEED_MIN = 0;
    public static final int GALA_MIN_SPEED_MAX = 40; // 40 * 5 = 200 km/h ceiling
    public static final int GALA_MIN_SPEED_DEFAULT = 0;

    public static final int GALA_MAX_ADJ_MIN = 0;
    public static final int GALA_MAX_ADJ_MAX = 32;
    public static final int GALA_MAX_ADJ_DEFAULT = 12;

    public static final int GALA_FADE_MS_MIN = 0;
    public static final int GALA_FADE_MS_MAX = 2000;
    public static final int GALA_FADE_MS_DEFAULT = 100;

    public static final int GALA_HOLD_MS_MIN = 0;
    public static final int GALA_HOLD_MS_MAX = 10000;
    public static final int GALA_HOLD_MS_DEFAULT = 1000;

    public static class SanitizeResult {
        public boolean modified = false;
        public int clampedFields = 0;
        public boolean fixedMutualExclusion = false;
    }

    private PresetsDatabaseValidator() {}

    public static int getAppVersionCode(Context context) {
        if (context == null) return 23;
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 23;
        }
    }

    public static String getAppVersionName(Context context) {
        if (context == null) return "0.4.9.6";
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0.4.9.6";
        }
    }

    /**
     * Inspects the entire presets database, migrating any legacy presets, repairing
     * corrupted or out-of-range values, recovering orphaned presets, and updating the DB version.
     */
    public static synchronized void validateAndMigrate(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            int lastVersion = prefs.getInt(PREF_DB_VERSION_CODE, 0);

            // Gather all preset names
            Set<String> savedNames = prefs.getStringSet(PREF_PRESET_NAMES, null);
            Set<String> allPresetNames = new HashSet<>();
            if (savedNames != null) {
                allPresetNames.addAll(savedNames);
            }

            // Scan for orphaned presets in keys (e.g. key ending with "_g0" or "_power_vol")
            Map<String, ?> allEntries = prefs.getAll();
            for (String key : allEntries.keySet()) {
                if (key.endsWith("_g0") && key.length() > 3) {
                    allPresetNames.add(key.substring(0, key.length() - 3));
                } else if (key.endsWith("_power_vol") && key.length() > 10) {
                    allPresetNames.add(key.substring(0, key.length() - 10));
                }
            }

            // If empty, ensure default exists
            if (allPresetNames.isEmpty()) {
                String defaultName = context.getString(R.string.default_preset_name);
                allPresetNames.add(defaultName);
            }

            SharedPreferences.Editor editor = prefs.edit();
            int migratedCount = 0;
            int totalClamped = 0;
            int totalConflicts = 0;

            for (String pName : allPresetNames) {
                if (pName == null || pName.trim().isEmpty()) continue;
                SanitizeResult res = sanitizePreset(editor, prefs, pName);
                if (res.modified) migratedCount++;
                totalClamped += res.clampedFields;
                if (res.fixedMutualExclusion) totalConflicts++;
            }

            // Ensure preset list is saved and sorted
            List<String> sortedNames = new ArrayList<>(allPresetNames);
            Collections.sort(sortedNames);
            editor.putStringSet(PREF_PRESET_NAMES, new HashSet<>(sortedNames));

            // Ensure last selected preset points to a valid preset
            String lastSelected = prefs.getString(PREF_LAST_SELECTED, null);
            if (lastSelected == null || !allPresetNames.contains(lastSelected)) {
                editor.putString(PREF_LAST_SELECTED, sortedNames.get(0));
            }

            int currentVerCode = getAppVersionCode(context);
            String currentVerName = getAppVersionName(context);

            // Update database version metadata
            editor.putInt(PREF_DB_VERSION_CODE, currentVerCode);
            editor.putString(PREF_DB_VERSION_NAME, currentVerName);
            editor.putLong(PREF_DB_MIGRATED_AT, System.currentTimeMillis());

            editor.apply();

            Log.i(TAG, "Presets DB migration complete: " + allPresetNames.size() + " presets (" +
                    migratedCount + " migrated, " + totalClamped + " fields clamped, " +
                    totalConflicts + " delay/surround conflicts resolved). DB version " +
                    lastVersion + " -> " + currentVerCode);

        } catch (Throwable t) {
            Log.e(TAG, "Failed to validate/migrate presets database", t);
        }
    }

    /**
     * Sanitizes a single preset by name, ensuring all required parameters exist, are within
     * valid hardware ranges, and mutual exclusion rules are respected.
     */
    public static SanitizeResult sanitizePreset(SharedPreferences.Editor editor, SharedPreferences prefs, String pName) {
        SanitizeResult res = new SanitizeResult();
        if (editor == null || prefs == null || pName == null || pName.trim().isEmpty()) {
            return res;
        }

        // 1. 16 EQ Bands & Q Switches
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            clampInt(editor, prefs, pName + "_g" + i, BAND_GAIN_MIN, BAND_GAIN_MAX, BAND_GAIN_DEFAULT, res);
            ensureBoolean(editor, prefs, pName + "_q" + i, false, res);
        }

        // 2. Subwoofer
        clampInt(editor, prefs, pName + "_sub_g", SUB_GAIN_MIN, SUB_GAIN_MAX, SUB_GAIN_DEFAULT, res);
        clampInt(editor, prefs, pName + "_sub_f", SUB_FREQ_MIN, SUB_FREQ_MAX, SUB_FREQ_DEFAULT, res);
        ensureBoolean(editor, prefs, pName + "_sub_comp", false, res);

        // 3. Power Volume
        clampInt(editor, prefs, pName + "_power_vol", POWER_VOL_MIN, POWER_VOL_MAX, POWER_VOL_DEFAULT, res);

        // 4. Bass Filter & Boost
        clampInt(editor, prefs, pName + "_bf_f", BASS_FILTER_MIN, BASS_FILTER_MAX, BASS_FILTER_DEFAULT, res);
        clampInt(editor, prefs, pName + "_bb_f", BASS_BOOST_MIN, BASS_BOOST_MAX, BASS_BOOST_DEFAULT, res);
        clampInt(editor, prefs, pName + "_bf_r", BASS_FILTER_MIN, BASS_FILTER_MAX, BASS_FILTER_DEFAULT, res);
        clampInt(editor, prefs, pName + "_bb_r", BASS_BOOST_MIN, BASS_BOOST_MAX, BASS_BOOST_DEFAULT, res);
        clampInt(editor, prefs, pName + "_bb_frq_f", BASS_BOOST_FREQ_MIN, BASS_BOOST_FREQ_MAX, BASS_BOOST_FREQ_DEFAULT, res);
        clampInt(editor, prefs, pName + "_bb_frq_r", BASS_BOOST_FREQ_MIN, BASS_BOOST_FREQ_MAX, BASS_BOOST_FREQ_DEFAULT, res);

        // 5. Faders
        clampInt(editor, prefs, pName + "_f_lr", FADER_MIN, FADER_MAX, FADER_DEFAULT, res);
        clampInt(editor, prefs, pName + "_f_fr", FADER_MIN, FADER_MAX, FADER_DEFAULT, res);

        // 6. Loudness
        ensureBoolean(editor, prefs, pName + "_loud", false, res);

        // 7. FM Curve & Radio compensation
        ensureBoolean(editor, prefs, pName + "_fm_en", false, res);
        ensureBoolean(editor, prefs, pName + "_fat_en", false, res);
        clampInt(editor, prefs, pName + "_fm_cal", FM_CAL_VOL_MIN, FM_CAL_VOL_MAX, FM_CAL_VOL_DEFAULT, res);
        clampInt(editor, prefs, pName + "_fm_str", FM_STRENGTH_MIN, FM_STRENGTH_MAX, FM_STRENGTH_DEFAULT, res);

        // 8. Time Alignment (Precise Delays)
        ensureBoolean(editor, prefs, pName + "_d_en", false, res);
        clampInt(editor, prefs, pName + "_d_fl", DELAY_MIN, DELAY_MAX, DELAY_DEFAULT, res);
        clampInt(editor, prefs, pName + "_d_fr", DELAY_MIN, DELAY_MAX, DELAY_DEFAULT, res);
        clampInt(editor, prefs, pName + "_d_rl", DELAY_MIN, DELAY_MAX, DELAY_DEFAULT, res);
        clampInt(editor, prefs, pName + "_d_rr", DELAY_MIN, DELAY_MAX, DELAY_DEFAULT, res);
        clampInt(editor, prefs, pName + "_d_sub", DELAY_MIN, DELAY_MAX, DELAY_DEFAULT, res);

        // 9. Surround (Legacy Delays & RSSE)
        ensureBoolean(editor, prefs, pName + "_d1_en", false, res);
        clampInt(editor, prefs, pName + "_d1_fl", DELAY_MIN, DELAY_MAX, DELAY_DEFAULT, res);
        clampInt(editor, prefs, pName + "_d1_fr", DELAY_MIN, DELAY_MAX, DELAY_DEFAULT, res);
        clampInt(editor, prefs, pName + "_d1_rl", DELAY_MIN, DELAY_MAX, DELAY_DEFAULT, res);
        clampInt(editor, prefs, pName + "_d1_rr", DELAY_MIN, DELAY_MAX, DELAY_DEFAULT, res);
        clampInt(editor, prefs, pName + "_rsse_val", DELAY_RSSE_MIN, DELAY_RSSE_MAX, DELAY_RSSE_DEFAULT, res);

        // 10. Hardware Mutual Exclusion (BU32107 delay RAM conflict)
        boolean dEn = getBooleanValue(prefs, pName + "_d_en", false);
        boolean d1En = getBooleanValue(prefs, pName + "_d1_en", false);
        if (dEn && d1En) {
            res.fixedMutualExclusion = true;
            res.modified = true;
            int rsseVal = getIntValue(prefs, pName + "_rsse_val", DELAY_RSSE_DEFAULT);
            String lowerName = pName.toLowerCase(Locale.ROOT);
            if (rsseVal > 10 || lowerName.contains("surround") || lowerName.contains("dolby")) {
                editor.putBoolean(pName + "_d_en", false);
                editor.putBoolean(pName + "_d1_en", true);
            } else {
                editor.putBoolean(pName + "_d_en", true);
                editor.putBoolean(pName + "_d1_en", false);
            }
        }

        // 11. GALA
        ensureBoolean(editor, prefs, pName + "_gala_enabled", false, res);
        clampInt(editor, prefs, pName + "_gala_increment", GALA_INC_MIN, GALA_INC_MAX, GALA_INC_DEFAULT, res);
        // Crucial clamp for legacy presets that had speed > 200 km/h (> 40 steps)
        clampInt(editor, prefs, pName + "_gala_min_speed", GALA_MIN_SPEED_MIN, GALA_MIN_SPEED_MAX, GALA_MIN_SPEED_DEFAULT, res);
        clampInt(editor, prefs, pName + "_gala_max_adj", GALA_MAX_ADJ_MIN, GALA_MAX_ADJ_MAX, GALA_MAX_ADJ_DEFAULT, res);
        clampInt(editor, prefs, pName + "_gala_fade_ms", GALA_FADE_MS_MIN, GALA_FADE_MS_MAX, GALA_FADE_MS_DEFAULT, res);
        clampInt(editor, prefs, pName + "_gala_hold_ms", GALA_HOLD_MS_MIN, GALA_HOLD_MS_MAX, GALA_HOLD_MS_DEFAULT, res);

        return res;
    }

    private static void clampInt(SharedPreferences.Editor editor, SharedPreferences prefs,
                                 String key, int min, int max, int defaultVal, SanitizeResult res) {
        Object obj = prefs.getAll().get(key);
        if (obj == null) {
            editor.putInt(key, defaultVal);
            res.modified = true;
            return;
        }

        int current;
        if (obj instanceof Integer) {
            current = (Integer) obj;
        } else if (obj instanceof Number) {
            current = ((Number) obj).intValue();
        } else if (obj instanceof String) {
            try {
                current = (int) Double.parseDouble((String) obj);
            } catch (Exception e) {
                current = defaultVal;
            }
        } else {
            current = defaultVal;
        }

        int clamped = Math.max(min, Math.min(max, current));
        if (clamped != current || !(obj instanceof Integer)) {
            editor.putInt(key, clamped);
            res.modified = true;
            res.clampedFields++;
        }
    }

    private static void ensureBoolean(SharedPreferences.Editor editor, SharedPreferences prefs,
                                      String key, boolean defaultVal, SanitizeResult res) {
        Object obj = prefs.getAll().get(key);
        if (obj == null) {
            editor.putBoolean(key, defaultVal);
            res.modified = true;
        } else if (!(obj instanceof Boolean)) {
            boolean b = Boolean.parseBoolean(String.valueOf(obj));
            editor.putBoolean(key, b);
            res.modified = true;
        }
    }

    private static boolean getBooleanValue(SharedPreferences prefs, String key, boolean fallback) {
        Object obj = prefs.getAll().get(key);
        if (obj instanceof Boolean) return (Boolean) obj;
        if (obj != null) return Boolean.parseBoolean(String.valueOf(obj));
        return fallback;
    }

    private static int getIntValue(SharedPreferences prefs, String key, int fallback) {
        Object obj = prefs.getAll().get(key);
        if (obj instanceof Integer) return (Integer) obj;
        if (obj instanceof Number) return ((Number) obj).intValue();
        if (obj instanceof String) {
            try { return (int) Double.parseDouble((String) obj); } catch (Exception ignored) {}
        }
        return fallback;
    }
}
