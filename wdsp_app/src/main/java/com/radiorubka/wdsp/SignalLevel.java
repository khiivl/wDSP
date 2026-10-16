package com.radiorubka.wdsp;

/**
 * Levels read out of a recording: one tone's own level, and the whole band's.
 *
 * <p>Both answers are in dBFS against a full-scale sine, so a tone played at amplitude 1.0 and
 * recorded without loss reads 0. The recordings are the measurement's own - mono, 16-bit,
 * {@link RoomMeasurement#SAMPLE_RATE} - and every caller passes a half-open window {@code [from, to)}
 * of it.
 *
 * <p>This exists because the same eight lines of Goertzel were about to be written a second time.
 * {@link VolumeSteps} reads a 1 kHz tone while the volume walks down; {@link MeasureVolume} reads a
 * dual tone, its harmonics and the cabin's own noise. One tone, one function.
 */
final class SignalLevel {

    private SignalLevel() {
    }

    /**
     * The level of one frequency alone, Goertzel over {@code [from, to)}.
     *
     * <p>Reading one bin rather than the whole band is what lets these probes run in a car: the
     * engine, the fan and the road are broadband, and a tone's own bin hardly hears them.
     */
    static double toneDb(short[] x, int from, int to, double hz) {
        final int a = Math.max(0, from), b = Math.min(x.length, to);
        if (b - a < 2) return Double.NEGATIVE_INFINITY;
        final double w = 2 * Math.PI * hz / RoomMeasurement.SAMPLE_RATE, c = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int n = a; n < b; n++) {
            final double s = x[n] / 32768.0 + c * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        final double power = s1 * s1 + s2 * s2 - c * s1 * s2;
        final double amp = 2 * Math.sqrt(Math.max(power, 0)) / Math.max(1, b - a);
        return 20 * Math.log10(amp + 1e-12);
    }

    /**
     * Everything in the window at once, as a sine of the same power would read: the level the
     * silence test judges, because a cabin that is not quiet is not quiet at one frequency.
     */
    static double broadbandDb(short[] x, int from, int to) {
        final int a = Math.max(0, from), b = Math.min(x.length, to);
        if (b <= a) return Double.NEGATIVE_INFINITY;
        double sum = 0;
        for (int n = a; n < b; n++) {
            final double v = x[n] / 32768.0;
            sum += v * v;
        }
        // RMS * sqrt(2) - a full-scale sine has RMS 1/sqrt(2) and must read 0 dBFS.
        return 20 * Math.log10(Math.sqrt(2 * sum / (b - a)) + 1e-12);
    }
}
