package com.radiorubka.wdsp;

/**
 * Models what the BU32107 actually does to the signal, as a per-band dB curve.
 *
 * The spectrum we capture is the player's output BEFORE the hardware DSP - the MCU sits
 * downstream of AudioFlinger - so to show what the listener hears we have to add the DSP's own
 * response on top of the measured content.
 *
 * The filter shapes are the author's, in {@link AudioConfig} since his 0.5 (02.10.2026): the
 * Q = 2.2 bell ({@code compositeResponseDb}), the subwoofer low-pass ({@code subFilterResponseDb})
 * and the door high-pass ({@code bassShapingResponseDb}). This class composes them into what the
 * chip does to the signal and keeps no second copy of any of them: until then it had its own RBJ
 * biquad evaluated at the capture sample rate - a rate the chip knows nothing about - which agreed
 * with his bell to 0.3 dB at the neighbouring band centres and drifted only near Nyquist, while his
 * EQ pre-warp ({@code AudioConfig.prewarpEq}) is solved against his bell. One bell, so the curve
 * drawn and the curve pre-warped are the same curve. The band centres are his
 * {@code AudioConfig.BAND_CENTER_HZ}; the native analyser keeps its own ({@code kHwCenters},
 * analyzer.cpp), the one other copy two languages cannot avoid.
 *
 * The whole curve only changes when a slider moves, so it is computed on demand and cached by the
 * caller rather than recalculated per frame.
 */
public final class DspResponse {

    /** Subwoofer crossover frequencies, index order as sent to the MCU in command 0x8B. */
    public static final int[] SUB_FREQS_HZ = {25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250};

    /**
     * The crossover a preset has before anybody chose one: 80 Hz. One constant - the screen, the
     * service and the measurement each had their own literal, and the measurement's said 63 Hz.
     */
    public static final int SUB_LPF_DEFAULT_IDX = 5;

    /** The chip's code for the subwoofer low-pass nearest to {@code hz}. */
    public static int nearestSubLpfIndex(float hz) {
        int best = SUB_LPF_DEFAULT_IDX;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < SUB_FREQS_HZ.length; i++) {
            float diff = Math.abs(SUB_FREQS_HZ[i] - hz);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

    /**
     * The chip's code for the door high-pass nearest to {@code hz}; 0 Hz is code 0, Through. The
     * native analysis speaks hertz and keeps no copy of this table (until 02.10.2026 it had one that
     * called code 0 "20 Hz" and code 2 "31").
     */
    public static int doorHpfIndexOf(float hz) {
        int best = 0;
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < DOOR_HPF_HZ.length; i++) {
            float diff = Math.abs(DOOR_HPF_HZ[i] - hz);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = i;
            }
        }
        return best;
    }

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
     * @param qNarrow      the Q switches - 🔴 not modelled: the MCU firmware forces Q = 2.2 on every
     *                     band whatever they say (bit 5 ORed into every write in FUN_080050d4,
     *                     03-SOUND-PROCESSOR.md §7), and the bell is built for that Q. The switches
     *                     and this parameter stay for a firmware byte patch (owner, 12.09 and
     *                     14.09.2026); the day it is on the unit, the bell needs a per-band Q.
     * @param fmOffsets    Fletcher-Munson / fatigue offsets already in dB, may be null
     * @param subFreqIdx   index into {@link #SUB_FREQS_HZ}, negative when there is no subwoofer
     * @param subGainIdx   subwoofer gain 0..12, in dB
     * @param hpfFrontCode door high-pass code for the front pair, see {@link #DOOR_HPF_HZ}
     * @param hpfRearCode  the same for the rear pair
     * @param shelf        the doors' bass shelf as the chip gets it (LoudnessCurve.bassShelf, the
     *                     person's own boost and loudness's share), or null for none. Until
     *                     02.10.2026 the model had no shelf at all, so once loudness started
     *                     carrying up to +10 dB of bass on it the calculated bars under-read the
     *                     bass by as much, while the main curve above them already showed it.
     * @param out          16-element destination
     */
    public static void compute(int[] gains, boolean[] qNarrow, float[] fmOffsets,
                               int subFreqIdx, int subGainIdx, int hpfFrontCode, int hpfRearCode,
                               LoudnessCurve.BassShelf shelf, float[] out) {
        final int bands = AudioConfig.NUM_BANDS;
        if (out == null || out.length < bands) return;

        for (int i = 0; i < bands; i++) {
            final float probeHz = AudioConfig.BAND_CENTER_HZ[i];
            float eqDb = gains != null ? AudioConfig.compositeResponseDb(gains, probeHz) : 0f;
            if (fmOffsets != null && inRange(i, fmOffsets.length)) {
                eqDb += fmOffsets[i];
            }
            out[i] = chainDb(probeHz, eqDb, subFreqIdx, subGainIdx, hpfFrontCode, hpfRearCode, shelf);
        }
    }

    /**
     * The same response as {@link #compute}, at any frequencies instead of the band centres - what
     * the live RTA curve adds in the calculated mode, one point per frequency. The loudness offsets
     * are per band and read between bands by {@link AudioConfig#bandValueAt}, so at a band centre the
     * two agree exactly.
     */
    public static void computeAt(float[] freqsHz, int[] gains, float[] fmOffsets,
                                 int subFreqIdx, int subGainIdx, int hpfFrontCode, int hpfRearCode,
                                 LoudnessCurve.BassShelf shelf, float[] out) {
        if (freqsHz == null || out == null) return;
        final boolean offsets = fmOffsets != null && fmOffsets.length >= AudioConfig.NUM_BANDS;
        for (int j = 0; j < freqsHz.length && j < out.length; j++) {
            final float hz = freqsHz[j];
            float eqDb = gains != null ? AudioConfig.compositeResponseDb(gains, hz) : 0f;
            if (offsets) eqDb += AudioConfig.bandValueAt(fmOffsets, hz);
            out[j] = chainDb(hz, eqDb, subFreqIdx, subGainIdx, hpfFrontCode, hpfRearCode, shelf);
        }
    }

    /**
     * The chip's chain at one frequency, given the equaliser's response there: the split into the
     * doors and the subwoofer, in the chip's order.
     */
    private static float chainDb(float probeHz, float eqDb, int subFreqIdx, int subGainIdx,
                                 int hpfFrontCode, int hpfRearCode, LoudnessCurve.BassShelf shelf) {
        final boolean hasSub = subFreqIdx >= 0 && subFreqIdx < SUB_FREQS_HZ.length;

        // Doors: front and rear through their own high-pass and bass shelf (the author's model,
        // AudioConfig.bassShapingResponseDb), averaged as power.
        double front = Math.pow(10.0, doorDb(probeHz, doorHpfHz(hpfFrontCode),
                shelf != null ? shelf.frontHz() : 0f, shelf != null ? shelf.gainFront : 0) / 10.0);
        double rear = Math.pow(10.0, doorDb(probeHz, doorHpfHz(hpfRearCode),
                shelf != null ? shelf.rearHz() : 0f, shelf != null ? shelf.gainRear : 0) / 10.0);
        float doorsDb = eqDb + (float) (10.0 * Math.log10((front + rear) / 2.0));

        // Subwoofer: the same equalised signal, at its gain, through its low-pass.
        if (hasSub) {
            float lowPassDb = lowPass2Db(probeHz, SUB_FREQS_HZ[subFreqIdx]);
            if (lowPassDb >= -30f) { // negligible this far above the crossover
                return powerSumDb(doorsDb, eqDb + Math.max(0, Math.min(12, subGainIdx)) + lowPassDb);
            }
        }
        return doorsDb;
    }

    /** One door pair: its high-pass (0 = through) and its bass shelf (0 Hz = off). */
    private static float doorDb(float freqHz, float hpfHz, float shelfHz, float shelfDb) {
        return AudioConfig.bassShapingResponseDb(freqHz, hpfHz > 0 ? hpfHz : 0f, shelfHz, shelfDb);
    }

    /** Hz for a door high-pass code; 0 for Through or an unknown code. */
    static float doorHpfHz(int code) {
        return code > 0 && code < DOOR_HPF_HZ.length ? DOOR_HPF_HZ[code] : 0f;
    }

    /**
     * The door high-pass in dB, 0 when off - the author's model with its boost shelf left out (the
     * shelf is the separate P2Bass control, not the crossover).
     */
    public static float highPass2Db(float freqHz, float cutoffHz) {
        if (cutoffHz <= 0 || freqHz <= 0) return 0f;
        return AudioConfig.bassShapingResponseDb(freqHz, cutoffHz, 0f, 0f);
    }

    private static boolean inRange(int index, int length) {
        return index >= 0 && index < length;
    }

    /** The subwoofer low-pass in dB, the author's model at his filter order, 0 when off. */
    public static float lowPass2Db(float freqHz, float cutoffHz) {
        if (cutoffHz <= 0) return 0f;
        return AudioConfig.subFilterResponseDb(freqHz, cutoffHz, AudioConfig.SUB_FILTER_ORDER, 0f);
    }

    /** Adds two levels as energy rather than as numbers. */
    public static float powerSumDb(float aDb, float bDb) {
        double total = Math.pow(10.0, aDb / 10.0) + Math.pow(10.0, bDb / 10.0);
        return (float) (10.0 * Math.log10(total));
    }
}
