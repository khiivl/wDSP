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

    /**
     * Door high-pass cut-offs by the code the chip receives in {@code 0x88} byte 3 (front high nibble,
     * rear low nibble), registers {@code 0703}/{@code 0704}; code 0 is Through - no filter at all.
     * Second order, 12 dB/octave: the MCU writes the order bit as 0. Read from the datasheet and the
     * MCU reverse, {@code .agents/platform/03-SOUND-PROCESSOR.md} §5.
     *
     * <p>The one table: until 15.09.2026 the slider label ({@code MainActivity.BASS_FILTER_FREQS}) and
     * the measurement ({@code RoomMeasurement.BASS_FILTER_FREQS_HZ}) each had a copy that called code 0
     * "20 Hz" and code 2 "31". A model of the chip has to follow the chip, and so does its label.
     */
    static final float[] DOOR_HPF_HZ = {0f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f};

    /**
     * 🔴 The MCU firmware forces Q = 2.2 on every equaliser band whatever the Q switches say (bit 5
     * ORed into every write in FUN_080050d4, 03-SOUND-PROCESSOR.md §7). The switches stay in the
     * interface for a firmware byte patch (owner, 12.09 and 14.09.2026); until that patch is on the
     * unit the model must draw what the chip does. Set to false once the firmware honours Q.
     */
    static final boolean FIRMWARE_FORCES_WIDE_Q = true;

    private DspResponse() {
    }

    /**
     * Full DSP response in dB per band - what the preset makes the hardware do, the "target" the
     * calculated spectrum shows (owner, 14.09.2026), with nothing of the car in it.
     *
     * <p>The chip's order: 16-band EQ, then the split - doors through their high-pass, subwoofer
     * through its low-pass. Both outputs carry the equalised signal, so the doors are the EQ response
     * through the door high-pass (front and rear power-averaged) and the subwoofer is the EQ response
     * at its own gain through the low-pass; the two are summed as energy, as two sources playing the
     * same band do.
     *
     * @param gains        per-band index 0..12, where 6 is flat and each step is 2 dB
     * @param qNarrow      the Q switches; ignored while {@link #FIRMWARE_FORCES_WIDE_Q}
     * @param fmOffsets    Fletcher-Munson / fatigue offsets already in dB, may be null
     * @param subFreqIdx   index into {@link #SUB_FREQS_HZ}, negative when there is no subwoofer
     * @param subGainIdx   subwoofer gain 0..12, in dB
     * @param hpfFrontCode door high-pass code for the front pair, see {@link #DOOR_HPF_HZ}
     * @param hpfRearCode  the same for the rear pair
     * @param sampleRate   capture sample rate in Hz
     * @param out          16-element destination
     */
    public static void compute(int[] gains, boolean[] qNarrow, float[] fmOffsets,
                               int subFreqIdx, int subGainIdx, int hpfFrontCode, int hpfRearCode,
                               float sampleRate, float[] out) {
        final int bands = AudioConfig.NUM_BANDS;
        if (out == null || out.length < bands) return;
        if (sampleRate <= 0) sampleRate = 48000f;

        final float frontHz = doorHpfHz(hpfFrontCode);
        final float rearHz = doorHpfHz(hpfRearCode);
        final boolean hasSub = subFreqIdx >= 0 && subFreqIdx < SUB_FREQS_HZ.length;
        final float crossoverHz = hasSub ? SUB_FREQS_HZ[subFreqIdx] : 0f;
        final float subGainDb = Math.max(0, Math.min(12, subGainIdx));

        for (int i = 0; i < bands; i++) {
            // 1. Equaliser: the magnitude responses of all 16 peaking filters, in cascade.
            float eqDb = 0f;
            float probeHz = BAND_CENTERS_HZ[i];
            for (int j = 0; j < bands; j++) {
                float gainDb = gains != null ? (gains[j] - 6) * 2.0f : 0f;
                if (gainDb == 0f) continue;
                boolean narrow = !FIRMWARE_FORCES_WIDE_Q && qNarrow != null && qNarrow[j];
                eqDb += peakingResponseDb(probeHz, BAND_CENTERS_HZ[j], narrow ? Q_NARROW : Q_WIDE,
                        gainDb, sampleRate);
            }
            if (fmOffsets != null && inRange(i, fmOffsets.length)) {
                eqDb += fmOffsets[i];
            }

            // 2. Doors: front and rear through their own high-pass, averaged as power.
            double front = Math.pow(10.0, highPass2Db(probeHz, frontHz) / 10.0);
            double rear = Math.pow(10.0, highPass2Db(probeHz, rearHz) / 10.0);
            float doorsDb = eqDb + (float) (10.0 * Math.log10((front + rear) / 2.0));

            // 3. Subwoofer: the same equalised signal, at its gain, through its low-pass.
            if (hasSub) {
                float lowPassDb = lowPass2Db(probeHz, crossoverHz);
                if (lowPassDb >= -30f) { // negligible this far above the crossover
                    out[i] = powerSumDb(doorsDb, eqDb + subGainDb + lowPassDb);
                    continue;
                }
            }
            out[i] = doorsDb;
        }
    }

    /** Hz for a door high-pass code; 0 for Through or an unknown code. */
    static float doorHpfHz(int code) {
        return code > 0 && code < DOOR_HPF_HZ.length ? DOOR_HPF_HZ[code] : 0f;
    }

    /** Second-order Butterworth high-pass magnitude in dB - the door crossover slope. 0 dB when off. */
    public static float highPass2Db(float freqHz, float cutoffHz) {
        if (cutoffHz <= 0 || freqHz <= 0) return 0f;
        double r4 = Math.pow(freqHz / cutoffHz, 4);
        return (float) (10.0 * Math.log10(r4 / (1.0 + r4)));
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
