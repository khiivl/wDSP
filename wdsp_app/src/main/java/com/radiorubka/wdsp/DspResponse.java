package com.radiorubka.wdsp;

/**
 * Models what the BU32107 actually does to the signal, as a per-band dB curve.
 *
 * The spectrum we capture is the player's output BEFORE the hardware DSP - the MCU sits
 * downstream of AudioFlinger - so to show what the listener hears we have to add the DSP's own
 * response on top of the measured content.
 *
 * The previous implementation approximated each equaliser band with three hand-picked weights
 * (dist 1 -> 0.36, dist 2 -> 0.08 for wide Q), which made Q 2.2 and Q 4.7 look nearly identical
 * and had no notion of the subwoofer at all. Here every band is evaluated as the real magnitude
 * response of a peaking biquad, which is what the hardware implements, so the two Q values differ
 * because they physically differ.
 *
 * The whole curve only changes when a slider moves, so it is computed on demand and cached by the
 * caller rather than recalculated per frame.
 */
public final class DspResponse {

    /** Centre frequency of each of the 16 equaliser bands, matching AudioConfig.BAND_LABELS. */
    public static final float[] BAND_CENTERS_HZ = {
            20f, 31.5f, 50f, 80f, 125f, 200f, 315f, 500f,
            800f, 1250f, 2000f, 3150f, 5000f, 8000f, 12500f, 20000f
    };

    /** The two Q factors the hardware offers per band. */
    public static final float Q_WIDE = 2.2f;
    public static final float Q_NARROW = 4.7f;

    /** Subwoofer crossover frequencies, index order as sent to the MCU in command 0x8B. */
    public static final int[] SUB_FREQS_HZ = {25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250};

    private DspResponse() {
    }

    /**
     * Full DSP response in dB per band.
     *
     * @param gains      per-band index 0..12, where 6 is flat and each step is 2 dB
     * @param qNarrow    true where the band uses Q 4.7 instead of Q 2.2
     * @param fmOffsets  Fletcher-Munson / fatigue offsets already in dB, may be null
     * @param subFreqIdx index into {@link #SUB_FREQS_HZ}, negative to disable the subwoofer path
     * @param subGainIdx subwoofer gain 0..12, in dB
     * @param sampleRate capture sample rate in Hz
     * @param out        16-element destination
     */
    public static void compute(int[] gains, boolean[] qNarrow, float[] fmOffsets,
                               int subFreqIdx, int subGainIdx, float sampleRate, float[] out) {
        final int bands = AudioConfig.NUM_BANDS;
        if (out == null || out.length < bands) return;
        if (sampleRate <= 0) sampleRate = 48000f;

        // 1. Equaliser: sum the magnitude responses of all 16 peaking filters at each band centre.
        for (int i = 0; i < bands; i++) {
            float totalDb = 0f;
            float probeHz = BAND_CENTERS_HZ[i];
            for (int j = 0; j < bands; j++) {
                float gainDb = gains != null ? (gains[j] - 6) * 2.0f : 0f;
                if (gainDb == 0f) continue;
                float q = (qNarrow != null && qNarrow[j]) ? Q_NARROW : Q_WIDE;
                totalDb += peakingResponseDb(probeHz, BAND_CENTERS_HZ[j], q, gainDb, sampleRate);
            }
            if (fmOffsets != null && inRange(i, fmOffsets.length)) {
                totalDb += fmOffsets[i];
            }
            out[i] = totalDb;
        }

        // 2. Subwoofer: a second-order low-passed path at its own gain, power-summed with the
        //    main path. Two sources reproducing the same band add energy, which is why a sub at
        //    0 dB gain still lifts the bottom end slightly rather than doing nothing.
        if (subFreqIdx >= 0 && subFreqIdx < SUB_FREQS_HZ.length) {
            float crossoverHz = SUB_FREQS_HZ[subFreqIdx];
            float subGainDb = Math.max(0, Math.min(12, subGainIdx));
            for (int i = 0; i < bands; i++) {
                float f = BAND_CENTERS_HZ[i];
                float lowPassDb = lowPass2Db(f, crossoverHz);
                if (lowPassDb < -30f) continue; // negligible this far above the crossover
                float mainDb = out[i];
                float subDb = out[i] + subGainDb + lowPassDb;
                out[i] = powerSumDb(mainDb, subDb);
            }
        }
    }

    private static boolean inRange(int index, int length) {
        return index >= 0 && index < length;
    }

    /**
     * Magnitude response, in dB, of one RBJ peaking-EQ biquad evaluated at an arbitrary frequency.
     * This is the same filter form the hardware uses, so band interaction and the difference
     * between Q 2.2 and Q 4.7 come out of the maths instead of being guessed.
     */
    public static float peakingResponseDb(float probeHz, float centerHz, float q, float gainDb,
                                          float sampleRate) {
        double nyquist = sampleRate / 2.0;
        if (centerHz >= nyquist) centerHz = (float) (nyquist * 0.99);
        if (probeHz >= nyquist) probeHz = (float) (nyquist * 0.99);

        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * centerHz / sampleRate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cosW0 = Math.cos(w0);

        double b0 = 1.0 + alpha * a;
        double b1 = -2.0 * cosW0;
        double b2 = 1.0 - alpha * a;
        double a0 = 1.0 + alpha / a;
        double a1 = -2.0 * cosW0;
        double a2 = 1.0 - alpha / a;

        double w = 2.0 * Math.PI * probeHz / sampleRate;
        double cosW = Math.cos(w), sinW = Math.sin(w);
        double cos2W = Math.cos(2 * w), sin2W = Math.sin(2 * w);

        double numRe = b0 + b1 * cosW + b2 * cos2W;
        double numIm = -(b1 * sinW + b2 * sin2W);
        double denRe = a0 + a1 * cosW + a2 * cos2W;
        double denIm = -(a1 * sinW + a2 * sin2W);

        double numMag = Math.hypot(numRe, numIm);
        double denMag = Math.hypot(denRe, denIm);
        if (denMag <= 1e-12) return 0f;

        return (float) (20.0 * Math.log10(numMag / denMag));
    }

    /** Second-order Butterworth low-pass magnitude in dB - the subwoofer crossover slope. */
    public static float lowPass2Db(float freqHz, float cutoffHz) {
        if (cutoffHz <= 0) return 0f;
        double ratio = freqHz / cutoffHz;
        double magSquared = 1.0 / (1.0 + Math.pow(ratio, 4));
        return (float) (10.0 * Math.log10(magSquared));
    }

    /** Adds two levels as energy rather than as numbers. */
    public static float powerSumDb(float aDb, float bDb) {
        double total = Math.pow(10.0, aDb / 10.0) + Math.pow(10.0, bDb / 10.0);
        return (float) (10.0 * Math.log10(total));
    }
}
