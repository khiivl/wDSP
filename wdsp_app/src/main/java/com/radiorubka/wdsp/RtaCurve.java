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
 * equaliser's grid relative to the average of what has sound, so "+4" means 4 dB above the
 * average and the slider under it would go to -4 - the same reading the bars give.
 *
 * <p>🔴 The average is taken in dB, as the bars take theirs (SpectrumAnalyzerView.onDraw). Until
 * 07.10.2026 it was a power average, and a power average sits near the loudest points: on music
 * that is the bass, so the whole curve read several dB low against the bars over the same sound
 * (owner, 07.10: "RTA shows levels too low"). Power is right for adding speakers that play
 * together; a reference line across frequency is a dB question, like the equaliser's own grid.
 *
 * <p>🔴 A floating average puts any sound's own tilt on the grid: music is not pink, its bass sits
 * 10-20 dB over the average of all its points and ran into the top of the view (owner, 08.10.2026:
 * "живий спектр зашкалює, типу кліпінг"). So the zero can be fixed instead, by pink noise
 * ({@link RtaReference}): the level pink noise reads becomes the grid's 0, and from then on the
 * curve is drawn against it - the equaliser's correction on top, as before.
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
        return update(contentDb, correctionDb, rangeDb, nowNanos, Float.NaN);
    }

    /**
     * One display frame against a fixed zero.
     *
     * @param referenceDb the grid's 0 in the measured curve's dB (pink noise's level, see
     *                    {@link RtaReference}); NaN draws against the frame's own average
     */
    boolean update(float[] contentDb, float[] correctionDb, float rangeDb, long nowNanos, float referenceDb) {
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

        // 3-4. Presence-gated correction, then the dB average of what has sound. Built in its
        //    own array, so a frame without enough sound leaves the last drawn curve as it was.
        final float floorDb = peak - rangeDb;
        double sumDb = 0.0;
        int active = 0;
        for (int j = 0; j < POINTS; j++) {
            float presence = (smoothedDb[j] - floorDb) / PRESENCE_FADE_DB;
            presence = Math.max(0f, Math.min(1f, presence));
            corrected[j] = smoothedDb[j] + presence * correctionDb[j];
            if (presence >= 1f) {
                sumDb += corrected[j];
                active++;
            }
        }
        if (active < MIN_POINTS_FOR_AVERAGE) return false;
        final float averageDb = Float.isNaN(referenceDb) ? (float) (sumDb / active) : referenceDb;
        for (int j = 0; j < POINTS; j++) relativeDb[j] = corrected[j] - averageDb;
        return true;
    }

    /**
     * The level of a measured curve, as the fixed zero is taken: the dB average of its points that
     * have sound - within {@code rangeDb} of the loudest - without any correction, so the equaliser
     * still moves the curve after the zero is fixed. NaN with too few such points. One rule with the
     * presence gate above, so pink noise reads 0 on the grid it was calibrated on.
     */
    static float contentLevelDb(float[] contentDb, float rangeDb) {
        float peak = -Float.MAX_VALUE;
        for (int j = 0; j < POINTS; j++) peak = Math.max(peak, contentDb[j]);
        final float floorDb = peak - rangeDb;
        double sum = 0.0;
        int active = 0;
        for (int j = 0; j < POINTS; j++) {
            if (contentDb[j] - floorDb >= PRESENCE_FADE_DB) {
                sum += contentDb[j];
                active++;
            }
        }
        return active < MIN_POINTS_FOR_AVERAGE ? Float.NaN : (float) (sum / active);
    }

    /** The last frame, dB relative to the zero: the average of what had sound, or the fixed one. */
    float[] relativeDb() {
        return relativeDb;
    }

    /** Forgets the ballistics, as after a stop: the next frame is taken as it is. */
    void reset() {
        primed = false;
    }
}
