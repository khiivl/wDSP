package com.radiorubka.wdsp;

/**
 * The live RTA's display model - the author's, from his 1.0 {@code SpectrumAnalyzerView} - applied to
 * this branch's measurement ({@link AudioSpectrumEngine#readCurve}). His numbers, his order:
 *
 * <ol>
 *   <li>his frequency smoothing is not here, on purpose. He reads each point from a few FFT bins and
 *       then smooths +-4 points in dB to stand in for fractional-octave smoothing ("real RTA/tuning
 *       tools (REW etc.) smooth for the same reason"). The native curve already is that smoothing,
 *       done in power: every point is the energy of a third of an octave, the bands' own function
 *       (analyzer.cpp, planEnergy) - a point on a band centre reads the band to 0.3 dB on pink noise.
 *       Smoothing it again would blur it to two thirds of an octave;</li>
 *   <li>ballistics per point: an instant attack and a release of 0.15 per 50 ms capture, here as
 *       its time constant so it does not depend on the display rate;</li>
 *   <li>a presence gate: the correction is added in full only where there is content, fading in
 *       over {@value #PRESENCE_FADE_DB} dB - so the equaliser's curve does not draw itself onto
 *       silence;</li>
 *   <li>the correction after the ballistics, so a slider that moves shows at once.</li>
 * </ol>
 *
 * <p>Then this branch's scale (owner, 15.09.2026, the bars' "option В"): the curve is drawn on the
 * equaliser's grid relative to the power average of what has sound, so "+4" means 4 dB above the
 * average and the slider under it would go to -4 - the same reading the bars give.
 */
final class RtaCurve {

    static final int POINTS = NativeAnalyzer.CURVE_POINTS;

    /** The author's FALL_SMOOTHING 0.15 per capture at 20 captures a second, as a time constant. */
    private static final float RELEASE_TAU_MS = (float) (-50.0 / Math.log(1.0 - 0.15));
    /** The author's SILENCE_FADE_DB. */
    private static final float PRESENCE_FADE_DB = 3f;
    /** Fewer points with sound than this and there is no average worth drawing against. */
    private static final int MIN_POINTS_FOR_AVERAGE = 10;

    private final float[] corrected = new float[POINTS];
    private final float[] smoothedDb = new float[POINTS];
    private final float[] relativeDb = new float[POINTS];
    private long lastNanos;
    private boolean primed;

    /**
     * One display frame.
     *
     * @param contentDb    the measured curve, dB
     * @param correctionDb what the mode adds, dB
     * @param rangeDb      how far below the frame's loudest point still counts as content - the
     *                     display range the bars use
     * @return false when there is not enough sound to draw against; {@link #relativeDb()} then keeps
     *         its last values, for a fade-out
     */
    boolean update(float[] contentDb, float[] correctionDb, float rangeDb, long nowNanos) {
        // 2. Ballistics.
        float release = 1f;
        if (primed) {
            float dtMs = (nowNanos - lastNanos) / 1_000_000f;
            release = dtMs > 0f ? (float) (1.0 - Math.exp(-dtMs / RELEASE_TAU_MS)) : 0f;
        }
        lastNanos = nowNanos;
        float peak = -Float.MAX_VALUE;
        for (int j = 0; j < POINTS; j++) {
            float s = contentDb[j];
            if (!primed || s > smoothedDb[j]) smoothedDb[j] = s;
            else smoothedDb[j] += (s - smoothedDb[j]) * release;
            peak = Math.max(peak, smoothedDb[j]);
        }
        primed = true;

        // 3-4. Presence-gated correction, then the power average of what has sound. Built in its
        //    own array, so a frame without enough sound leaves the last drawn curve as it was.
        final float floorDb = peak - rangeDb;
        double sumPower = 0.0;
        int active = 0;
        for (int j = 0; j < POINTS; j++) {
            float presence = (smoothedDb[j] - floorDb) / PRESENCE_FADE_DB;
            presence = Math.max(0f, Math.min(1f, presence));
            corrected[j] = smoothedDb[j] + presence * correctionDb[j];
            if (presence >= 1f) {
                sumPower += Math.pow(10.0, corrected[j] / 10.0);
                active++;
            }
        }
        if (active < MIN_POINTS_FOR_AVERAGE) return false;
        final float averageDb = (float) (10.0 * Math.log10(sumPower / active));
        for (int j = 0; j < POINTS; j++) relativeDb[j] = corrected[j] - averageDb;
        return true;
    }

    /** The last frame, dB relative to the average of what had sound. */
    float[] relativeDb() {
        return relativeDb;
    }

    /** Forgets the ballistics, as after a stop: the next frame is taken as it is. */
    void reset() {
        primed = false;
    }
}
