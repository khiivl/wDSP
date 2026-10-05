package com.radiorubka.wdsp;

/**
 * The live RTA's display model - the author's, from his 1.0 {@code SpectrumAnalyzerView} - applied to
 * this branch's measurement ({@link AudioSpectrumEngine#readCurve}). His numbers, his order:
 *
 * <ol>
 *   <li>a triangular smoothing over +-{@value #FREQ_SMOOTH_RADIUS} points along the log-frequency
 *       axis - fractional-octave smoothing, "real RTA/tuning tools (REW etc.) smooth for the same
 *       reason" - left out for a narrow-band test tone, which it would smear;</li>
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

    /** The author's FREQ_SMOOTH_RADIUS. */
    private static final int FREQ_SMOOTH_RADIUS = 4;
    /** The author's FALL_SMOOTHING 0.15 per capture at 20 captures a second, as a time constant. */
    private static final float RELEASE_TAU_MS = (float) (-50.0 / Math.log(1.0 - 0.15));
    /** The author's SILENCE_FADE_DB. */
    private static final float PRESENCE_FADE_DB = 3f;
    /** Fewer points with sound than this and there is no average worth drawing against. */
    private static final int MIN_POINTS_FOR_AVERAGE = 10;

    private final float[] spatialDb = new float[POINTS];
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
     * @param narrowband   a test tone is playing: no frequency smoothing
     * @return false when there is not enough sound to draw against; {@link #relativeDb()} then keeps
     *         its last values, for a fade-out
     */
    boolean update(float[] contentDb, float[] correctionDb, float rangeDb, boolean narrowband,
                   long nowNanos) {
        // 1. Frequency smoothing - a fixed triangular kernel over log-spaced points is a constant
        //    fraction of an octave, as in the author's view.
        if (narrowband) {
            System.arraycopy(contentDb, 0, spatialDb, 0, POINTS);
        } else {
            for (int j = 0; j < POINTS; j++) {
                float sum = 0f, weight = 0f;
                for (int d = -FREQ_SMOOTH_RADIUS; d <= FREQ_SMOOTH_RADIUS; d++) {
                    int idx = j + d;
                    if (idx < 0 || idx >= POINTS) continue;
                    float w = FREQ_SMOOTH_RADIUS + 1 - Math.abs(d);
                    sum += contentDb[idx] * w;
                    weight += w;
                }
                spatialDb[j] = sum / weight;
            }
        }

        // 2. Ballistics.
        float release = 1f;
        if (primed) {
            float dtMs = (nowNanos - lastNanos) / 1_000_000f;
            release = dtMs > 0f ? (float) (1.0 - Math.exp(-dtMs / RELEASE_TAU_MS)) : 0f;
        }
        lastNanos = nowNanos;
        float peak = -Float.MAX_VALUE;
        for (int j = 0; j < POINTS; j++) {
            float s = spatialDb[j];
            if (!primed || s > smoothedDb[j]) smoothedDb[j] = s;
            else smoothedDb[j] += (s - smoothedDb[j]) * release;
            peak = Math.max(peak, smoothedDb[j]);
        }
        primed = true;

        // 3-4. Presence-gated correction, then the power average of what has sound. Built in the
        //    smoothing's scratch array, which step 2 has finished with, so a frame without enough
        //    sound leaves the last drawn curve as it was.
        final float[] corrected = spatialDb;
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
