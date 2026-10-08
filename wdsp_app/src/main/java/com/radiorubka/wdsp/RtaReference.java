package com.radiorubka.wdsp;

import android.content.SharedPreferences;

/**
 * The live RTA's fixed zero: the level pink noise reads, taken once and kept (owner, 08.10.2026:
 * calibrate the RTA by pink noise, a fixed zero rather than the frame's floating average - see
 * {@link RtaCurve}).
 *
 * <p>One zero per analyser kind, because they hear different things:
 * <ul>
 *   <li>the <b>calculated</b> analyser taps the digital stream before the volume, so its zero holds
 *       at any volume;</li>
 *   <li>the <b>microphone</b> hears the cabin, which is louder with the volume. Its zero is kept with
 *       the volume step it was taken at, and at any other step it is not used - the curve goes back to
 *       the floating average rather than draw against a zero that is no longer true.</li>
 * </ul>
 *
 * <p>Kept in the UI preferences, not in a preset: it describes this unit and its microphone, not a
 * sound, and the presets export must not carry it to another car.
 */
final class RtaReference {

    /** How long pink noise is averaged for the zero. */
    static final long CAPTURE_MS = 3000;
    /** A capture with fewer frames than this heard too little to trust. */
    private static final int MIN_FRAMES = 20;

    private static final String KEY_TAP_DB = "rta_zero_tap_db";
    private static final String KEY_MIC_DB = "rta_zero_mic_db";
    private static final String KEY_MIC_VOLUME = "rta_zero_mic_volume";

    private final SharedPreferences prefs;

    RtaReference(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /**
     * The zero to draw against, or NaN for none: never taken, or - for the microphone - taken at
     * another volume step.
     */
    float zeroDb(boolean acoustic, int volume) {
        if (!acoustic) return prefs.getFloat(KEY_TAP_DB, Float.NaN);
        if (prefs.getInt(KEY_MIC_VOLUME, -1) != volume) return Float.NaN;
        return prefs.getFloat(KEY_MIC_DB, Float.NaN);
    }

    void store(boolean acoustic, float db, int volume) {
        SharedPreferences.Editor e = prefs.edit();
        if (acoustic) {
            e.putFloat(KEY_MIC_DB, db).putInt(KEY_MIC_VOLUME, volume);
        } else {
            e.putFloat(KEY_TAP_DB, db);
        }
        e.apply();
    }

    /** Forgets both zeros: the curve goes back to the floating average. */
    void clear() {
        prefs.edit().remove(KEY_TAP_DB).remove(KEY_MIC_DB).remove(KEY_MIC_VOLUME).apply();
    }

    /** Averages the levels of the frames a capture saw; NaN when it heard too little. */
    static final class Capture {
        private double sum;
        private int frames;

        void offer(float levelDb) {
            if (Float.isNaN(levelDb)) return;
            sum += levelDb;
            frames++;
        }

        float resultDb() {
            return frames < MIN_FRAMES ? Float.NaN : (float) (sum / frames);
        }
    }
}
