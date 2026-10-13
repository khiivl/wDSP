package com.radiorubka.wdsp;

/**
 * Second-order filters as the BU32107 runs its crossovers: one biquad each, bilinear transform (RBJ
 * cookbook), applied causally - forward only, so the phase is the chip's and not zero.
 *
 * <p>Used to move a measured impulse response from the state it was recorded in to the state the
 * preset plays in ({@code research/RESEARCH_RESULT_SUB_CROSSOVER_PHASE.md}). The chip's Q comes from a
 * table in the MCU firmware nobody has read; {@link #Q} is Butterworth. It does not matter for the
 * phase between the doors and the sub: a high-pass and a low-pass sharing fc and Q are 180° apart at
 * every frequency whatever the Q. It moves the level at fc by up to 3 dB.
 *
 * <p>Coefficients are {b0, b1, b2, a1, a2}, normalised to a0 = 1.
 */
final class Biquad {

    /** Butterworth. */
    static final double Q = Math.sqrt(0.5);

    private Biquad() {
    }

    static double[] lowPass(double fcHz, double q, double fs) {
        final double w = 2 * Math.PI * fcHz / fs, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
        final double a0 = 1 + alpha, k = (1 - cos) / 2 / a0;
        return new double[]{k, 2 * k, k, -2 * cos / a0, (1 - alpha) / a0};
    }

    static double[] highPass(double fcHz, double q, double fs) {
        final double w = 2 * Math.PI * fcHz / fs, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
        final double a0 = 1 + alpha, k = (1 + cos) / 2 / a0;
        return new double[]{k, -2 * k, k, -2 * cos / a0, (1 - alpha) / a0};
    }

    /**
     * A low-pass at {@code fromHz} taken out and one at {@code toHz} put in, as one stable filter: the
     * two share the numerator (1 + z⁻¹)², which cancels, leaving the old denominator over the new one.
     * Dividing by the old low-pass alone would put poles on z = -1.
     */
    static double[] retuneLowPass(double fromHz, double toHz, double q, double fs) {
        final double[] from = lowPass(fromHz, q, fs), to = lowPass(toHz, q, fs);
        final double g = to[0] / from[0];
        return new double[]{g, g * from[3], g * from[4], to[3], to[4]};
    }

    /** Runs the filter over {@code x} in place, from rest (transposed direct form II). */
    static void filter(float[] x, double[] c) {
        double z1 = 0, z2 = 0;
        for (int n = 0; n < x.length; n++) {
            final double in = x[n];
            final double out = c[0] * in + z1;
            z1 = c[1] * in - c[3] * out + z2;
            z2 = c[2] * in - c[4] * out;
            x[n] = (float) out;
        }
    }
}
