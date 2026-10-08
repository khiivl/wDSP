package com.radiorubka.wdsp;

import android.content.SharedPreferences;

/**
 * The live RTA's fixed zero: the level pink noise reads, taken once and kept (owner, 08.10.2026:
 * calibrate the RTA by pink noise, a fixed zero rather than the frame's floating average - see
 * {@link RtaCurve}).
 *
 * <p>One zero for both analysers: the calculated one taps the digital stream before the volume, and
 * the microphone is held on it by our AGC ({@code AudioSpectrumEngine.alignMicrophoneToCalculated}),
 * so both curves are in the track's units and neither moves with the volume (owner, 08.10.2026: "тут
 * має працювати власний AGC").
 *
 * <p>Kept in the UI preferences, not in a preset: it describes this unit and its microphone, not a
 * sound, and the presets export must not carry it to another car.
 */
final class RtaReference {

    /** How long pink noise is averaged for the zero. */
    static final long CAPTURE_MS = 3000;
    /** A capture with fewer frames than this heard too little to trust. */
    private static final int MIN_FRAMES = 20;

    private static final String KEY_ZERO_DB = "rta_zero_db";

    private final SharedPreferences prefs;

    RtaReference(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** The zero to draw against, or NaN when none was taken. */
    float zeroDb() {
        return prefs.getFloat(KEY_ZERO_DB, Float.NaN);
    }

    void store(float db) {
        prefs.edit().putFloat(KEY_ZERO_DB, db).apply();
    }

    /** Forgets the zero: the curve goes back to the floating average. */
    void clear() {
        prefs.edit().remove(KEY_ZERO_DB).apply();
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
