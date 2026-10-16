package com.radiorubka.wdsp;

import android.content.SharedPreferences;

/**
 * The live RTA's fixed zero: the level pink noise reads, taken once and kept (owner, 08.10.2026:
 * calibrate the RTA by pink noise, a fixed zero rather than the frame's floating average - see
 * {@link RtaCurve}).
 *
 * <p>Two zeros, one per analyser (owner, 09.10.2026: "роживий нуль різні речі для обох каналів"):
 * the channel's is the level of our digital chain, the microphone's the level the cabin and the
 * capsule give that noise at the volume it was taken at - kept with it, so the volume table can move
 * the curve to the volume playing now (AudioSpectrumEngine.addCurveOffset). Until the microphone has its own, it falls
 * back on the channel's zero, held there by our AGC ({@code AudioSpectrumEngine
 * .alignMicrophoneToCalculated}) - the single zero of 08.10.2026 ("тут має працювати власний AGC").
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
    private static final String KEY_MIC_ZERO_DB = "rta_zero_mic_db";
    private static final String KEY_MIC_ZERO_VOLUME = "rta_zero_mic_volume";

    private final SharedPreferences prefs;

    RtaReference(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    /** The zero of the channel's or the microphone's curve, or NaN when none was taken. */
    float zeroDb(boolean microphone) {
        return prefs.getFloat(microphone ? KEY_MIC_ZERO_DB : KEY_ZERO_DB, Float.NaN);
    }

    /** The volume step the microphone's zero was taken at; 0 for a zero older than 14.10.2026. */
    int micZeroVolume() {
        return prefs.getInt(KEY_MIC_ZERO_VOLUME, 0);
    }

    /** @param volume the step playing during the capture - kept for the microphone's zero only */
    void store(boolean microphone, float db, int volume) {
        final SharedPreferences.Editor e = prefs.edit().putFloat(microphone ? KEY_MIC_ZERO_DB : KEY_ZERO_DB, db);
        if (microphone) e.putInt(KEY_MIC_ZERO_VOLUME, volume);
        e.apply();
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
