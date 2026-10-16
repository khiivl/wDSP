package com.radiorubka.wdsp;

import com.radiorubka.wdsp.RoomMeasurement.Channel;

import java.util.Locale;

/**
 * Do the speakers arrive in the order the cabin says they should - or only with two of them swapped?
 *
 * <h2>Why (owner's plan, 02.10.2026, step 8)</h2>
 *
 * A harness that puts the rear pair on the front outputs, or left on right, is an ordinary
 * installation fault, and every per-speaker finding after it names the wrong speaker. This
 * measurement made exactly that mistake itself until 20.08.2026: its fader table had front and rear
 * the other way round, and every result was labelled mirror-image front to back until testers said
 * which speaker played the first sweep. A check like this one would have said it on the first run.
 *
 * <h2>How</h2>
 *
 * Distance is the one thing about a speaker no microphone can get wrong. The geometry (CabinGeometry:
 * the microphone dot, the cabin's size, the doors) predicts each speaker's time of flight; the
 * arrivals hold the same flight plus a constant every channel shares. So each wiring - as stated, and
 * every way of swapping door speakers in pairs - is fitted with its own constant, and the RMS misfit
 * says how well it explains what was heard. A swap is reported only when it fits within what the
 * geometry can promise and beats the stated wiring by a clear margin; a microphone near the centre
 * line cannot tell left from right, and then nothing is said.
 *
 * <p>Only direct arrivals take part - a reflection arrives late and reads as "further". The
 * subwoofer never does: its low-pass delays the peak and its place is the least known of all.
 * Broadband level is not used: speaker sensitivity and aiming differ by as much as the 3 dB a
 * nearer door adds.
 *
 * <p>When the stated wiring misfits and no swap fits either, nothing is reported as a fault: the
 * microphone was not where the dot says, or the speakers are not where a car keeps them (the
 * owner's bench). The numbers still go to the report.
 */
final class ChannelSwapCheck {

    /**
     * What the geometry can promise, RMS: about 15 cm of path a speaker - a tweeter on the pillar
     * against a woofer low in the door, a dot dragged by a finger, a door card thicker than assumed.
     */
    static final float FIT_MS = 0.45f;

    /**
     * How much better a swap must explain the arrivals than the stated wiring: about 12 cm of path.
     * The two hypotheses differ by twice a pair's own difference, so a decisive geometry clears it
     * easily, and a microphone on the centre line (no difference to read) never does.
     */
    static final float MARGIN_MS = 0.35f;

    /** Door speakers only; the subwoofer never takes part (see the class comment). */
    private static final Channel[] DOORS = {
            Channel.FRONT_LEFT, Channel.FRONT_RIGHT, Channel.REAR_LEFT, Channel.REAR_RIGHT,
    };

    /**
     * Every way door speakers can trade places in pairs: each single pair, and each pair of pairs.
     * Order is the tie-break, simplest first.
     */
    private static final Channel[][][] SWAPS = {
            {{Channel.FRONT_LEFT, Channel.FRONT_RIGHT}},
            {{Channel.REAR_LEFT, Channel.REAR_RIGHT}},
            {{Channel.FRONT_LEFT, Channel.REAR_LEFT}},
            {{Channel.FRONT_RIGHT, Channel.REAR_RIGHT}},
            {{Channel.FRONT_LEFT, Channel.REAR_RIGHT}},
            {{Channel.FRONT_RIGHT, Channel.REAR_LEFT}},
            {{Channel.FRONT_LEFT, Channel.FRONT_RIGHT}, {Channel.REAR_LEFT, Channel.REAR_RIGHT}},
            {{Channel.FRONT_LEFT, Channel.REAR_LEFT}, {Channel.FRONT_RIGHT, Channel.REAR_RIGHT}},
            {{Channel.FRONT_LEFT, Channel.REAR_RIGHT}, {Channel.FRONT_RIGHT, Channel.REAR_LEFT}},
    };

    private static final Channel[][] NO_SWAP = {};

    /** What the check found. */
    static final class Verdict {
        /** The speakers that play in each other's place, in pairs; empty when the stated wiring stands. */
        final Channel[][] swapped;
        /** How far the arrivals miss the geometry as wired, RMS milliseconds. */
        final float asWiredMs;
        /** The best-fitting swap, whether or not it won, and its misfit; empty and NaN when none applied. */
        final Channel[][] bestSwap;
        final float bestSwapMs;
        /** How many door speakers were compared. */
        final int speakers;

        Verdict(Channel[][] swapped, float asWiredMs, Channel[][] bestSwap, float bestSwapMs, int speakers) {
            this.swapped = swapped;
            this.asWiredMs = asWiredMs;
            this.bestSwap = bestSwap;
            this.bestSwapMs = bestSwapMs;
            this.speakers = speakers;
        }

        /** One line for the English report: the numbers, whatever the verdict. */
        String describe() {
            final String best = bestSwap.length == 0 ? "no swap applies"
                    : String.format(Locale.US, "best swap %s %.2f ms", pairs(bestSwap), bestSwapMs);
            final String verdict = swapped.length == 0 ? "the stated wiring stands" : "SWAPPED: " + pairs(swapped);
            return String.format(Locale.US, "arrival order vs the cabin's geometry, %d door speakers heard directly: "
                    + "as wired %.2f ms RMS off, %s -> %s (fit %.2f ms, margin %.2f ms)",
                    speakers, asWiredMs, best, verdict, FIT_MS, MARGIN_MS);
        }
    }

    private ChannelSwapCheck() {
    }

    /** "front left <-> front right" for a pair. */
    static String pairLabel(Channel[] pair) {
        return pair[0].label + " <-> " + pair[1].label;
    }

    private static String pairs(Channel[][] swap) {
        StringBuilder sb = new StringBuilder();
        for (Channel[] pair : swap) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(pairLabel(pair));
        }
        return sb.toString();
    }

    /**
     * @param arrivalMs by {@link Channel#ordinal()}: when the direct sound arrived, NaN when it was not
     *                  heard directly (absent, not declared, or heard only through the cabin)
     * @param flightMs  by {@link Channel#ordinal()}: the time of flight the geometry predicts from the
     *                  microphone to that speaker
     * @return null when fewer than two door speakers can be compared
     */
    static Verdict judge(float[] arrivalMs, float[] flightMs) {
        int speakers = 0;
        for (Channel ch : DOORS) {
            if (usable(arrivalMs, ch)) speakers++;
        }
        if (speakers < 2) return null;

        final float asWired = misfitMs(arrivalMs, flightMs, NO_SWAP);
        Channel[][] best = NO_SWAP;
        float bestMs = Float.NaN;
        for (Channel[][] swap : SWAPS) {
            if (!applies(arrivalMs, swap)) continue;
            final float ms = misfitMs(arrivalMs, flightMs, swap);
            if (best.length == 0 || ms < bestMs) {
                best = swap;
                bestMs = ms;
            }
        }
        final boolean swapped = best.length > 0 && bestMs <= FIT_MS && asWired - bestMs >= MARGIN_MS;
        return new Verdict(swapped ? best : NO_SWAP, asWired, best, bestMs, speakers);
    }

    private static boolean usable(float[] arrivalMs, Channel ch) {
        return ch.ordinal() < arrivalMs.length && !Float.isNaN(arrivalMs[ch.ordinal()]);
    }

    /** A swap can be judged only when every speaker it moves was heard. */
    private static boolean applies(float[] arrivalMs, Channel[][] swap) {
        for (Channel[] pair : swap) {
            if (!usable(arrivalMs, pair[0]) || !usable(arrivalMs, pair[1])) return false;
        }
        return true;
    }

    /** Where a speaker's sound would come from under this swap. */
    private static Channel placeOf(Channel ch, Channel[][] swap) {
        for (Channel[] pair : swap) {
            if (pair[0] == ch) return pair[1];
            if (pair[1] == ch) return pair[0];
        }
        return ch;
    }

    /**
     * RMS of what is left after the shared constant: each heard door speaker's arrival against the
     * flight from the place it would really play from under this swap.
     */
    private static float misfitMs(float[] arrivalMs, float[] flightMs, Channel[][] swap) {
        double sum = 0;
        int n = 0;
        final double[] rest = new double[DOORS.length];
        for (Channel ch : DOORS) {
            if (!usable(arrivalMs, ch)) continue;
            rest[n] = arrivalMs[ch.ordinal()] - flightMs[placeOf(ch, swap).ordinal()];
            sum += rest[n];
            n++;
        }
        final double shared = sum / n;
        double sq = 0;
        for (int i = 0; i < n; i++) sq += (rest[i] - shared) * (rest[i] - shared);
        return (float) Math.sqrt(sq / n);
    }
}
