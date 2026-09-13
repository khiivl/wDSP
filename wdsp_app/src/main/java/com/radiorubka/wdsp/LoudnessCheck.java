package com.radiorubka.wdsp;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks a preset's loudness curve and says what is wrong with it.
 *
 * <p>The two sliders on the loudness tab - the calibration point and the strength - are the only
 * numbers in this app a person is asked to choose with no reference of any kind. Everything else is
 * either measured or has an obvious meaning; these two decide, between them, whether the curve acts
 * at all, and nothing on screen says whether the chosen pair is sensible. The owner's instruction
 * on 13.09.2026 was that the person should not have to guess: the app is to check the curve itself
 * and say what is wrong with it.
 *
 * <p>Everything here is arithmetic on the preset as stored. No microphone, no sound, nothing to
 * wait for - so it can run while the tab is being drawn, and it can answer about volumes nobody is
 * currently playing at, which is the whole point: a curve that behaves at volume 20 and collapses
 * at volume 4 is exactly the failure a person cannot see by listening at one level.
 *
 * <h3>Where the recommended calibration point comes from, and what it is not</h3>
 *
 * <p>It is {@link RoomMeasurement#MEASURE_VOLUME} - the volume the sweep runs at - and it is
 * recommended only once the car has actually been measured. The reasoning is not that 16 is a
 * magic level. It is that the preset's band gains are the correction measured <em>at that volume</em>
 * and are honest only there; below it the ear loses the bottom and the correction stops matching
 * what is heard, which is precisely the gap loudness compensation exists to fill. So the curve is
 * anchored where the measurement was taken, and grows below it.
 *
 * <p>⚠️ This is an anchor with a reason, not a sound-pressure measurement. ISO 226 is stated
 * against absolute SPL, and this app has no calibrated microphone and therefore no absolute SPL -
 * it cannot know whether volume 16 in this car is 70 dB or 90. Nothing here should be described to
 * anyone as a loudness calibration in the metrological sense. It ties two of the app's own numbers
 * together so that they stop contradicting each other, and that is all it claims.
 */
public final class LoudnessCheck {

    /** What the check found. The UI maps these onto strings; the engine stays free of resources. */
    public enum Code {
        /** Loudness is switched on but the settings make it incapable of doing anything. */
        CURVE_INERT,
        /** The calibration point leaves no room below it, so the curve can never act. */
        CAL_TOO_LOW,
        /** Strength is zero: the switch is on and the curve is multiplied away. */
        STRENGTH_ZERO,
        /** Fatigue trim is on but the calibration point leaves no room above it. */
        FATIGUE_NO_ROOM,
        /** The preset plus the curve exceeds what the equaliser can deliver; the shape collapses. */
        CEILING_CLIPS,
        /** Subwoofer compensation and the band offsets raise the same bass twice. */
        SUB_DOUBLE_BASS,
        /** A separate bass boost is stacked under a curve that is already raising the bottom. */
        BASS_BOOST_STACKS,
        /** The curve is anchored at a volume other than the one the car was measured at. */
        CAL_NOT_AT_MEASURED
    }

    /** How loudly to say it. NOTE is worth knowing; WARN means the curve is not doing its job. */
    public enum Level { NOTE, WARN }

    public static final class Finding {
        public final Code code;
        public final Level level;
        /** Numbers the message needs, in the order the message uses them. */
        public final int[] args;

        Finding(Code code, Level level, int... args) {
            this.code = code;
            this.level = level;
            this.args = args;
        }
    }

    public static final class Result {
        public final List<Finding> findings = new ArrayList<>();
        /**
         * The calibration point to use, or 0 when this car has never been measured and the app
         * therefore has no anchor to offer. 0 means "no opinion", never "set it to zero".
         */
        public int recommendedCal;
        /**
         * The strongest curve the hardware can actually deliver on top of these band gains, as a
         * percentage. Above it the loud bands pin at the ceiling and the curve stops being a curve.
         */
        public int recommendedStrength;

        public boolean isClean() {
            return findings.isEmpty();
        }

        public boolean hasWarning() {
            for (Finding f : findings) if (f.level == Level.WARN) return true;
            return false;
        }
    }

    private LoudnessCheck() {
    }

    /**
     * @param gains        16 gain indices 0..12 as stored in the preset
     * @param subGainIdx   subwoofer gain index 0..12
     * @param subFreqIdx   index into {@link DspResponse#SUB_FREQS_HZ}
     * @param bassBoost    the larger of the front and rear bass boost levels, 0 when unused
     * @param carMeasured  whether this car has an auto-EQ measurement at all
     */
    public static Result inspect(int[] gains, int subGainIdx, int subFreqIdx, int bassBoost,
                                 boolean fmEnabled, boolean fatigueEnabled, boolean subComp,
                                 int cal, int strengthPct, boolean carMeasured) {
        Result r = new Result();
        r.recommendedCal = carMeasured ? RoomMeasurement.MEASURE_VOLUME : 0;
        r.recommendedStrength = maxDeliverableStrength(gains, subGainIdx, subFreqIdx, subComp);

        // 1. The curve cannot act, whatever the switch says.
        //
        //    ⚠️ This is checked with the switch OFF as well, and that is deliberate. The first
        //    version returned early when nothing was enabled - and on the owner's unit it then drew
        //    "this curve fits the preset, nothing to change" underneath two sliders sitting at zero
        //    and zero, which is the exact state in which switching loudness on does nothing at all.
        //    Telling somebody their dead configuration is fine, one tap before they discover it is
        //    not, is worse than saying nothing. The switch is one tap; the two numbers are what
        //    need to be right before that tap.
        //
        //    The state itself came from the measurement: until 13.09.2026 it wrote cal=0 and
        //    strength=0 explicitly, beside a comment promising the curve was "one switch away".
        boolean calDead = cal <= LoudnessCurve.VOL_MIN;
        boolean strDead = strengthPct <= 0;
        //    One line, not three. Emitting the general sentence and both specific ones together
        //    printed the same fact three times over - the screen said the curve does nothing, then
        //    that the calibration point is too low, then that the strength is zero. Saying it once,
        //    as precisely as the state allows, is what a person can act on.
        if (calDead && strDead) {
            r.findings.add(new Finding(Code.CURVE_INERT, Level.WARN, cal, strengthPct));
        } else if (calDead) {
            r.findings.add(new Finding(Code.CAL_TOO_LOW, Level.WARN, cal));
        } else if (strDead) {
            r.findings.add(new Finding(Code.STRENGTH_ZERO, Level.WARN));
        }

        // Everything below describes what the curve would do once it is running, so it is only
        // worth saying when something is actually switched on.
        if (!fmEnabled && !fatigueEnabled) return r;

        if (fatigueEnabled && cal >= LoudnessCurve.VOL_MAX) {
            r.findings.add(new Finding(Code.FATIGUE_NO_ROOM, Level.WARN, cal));
        }

        // 2. The ceiling. The equaliser clamps each band to +/-12 dB, so a preset that already
        //    raises the bottom leaves the curve nowhere to go: the low bands pin flat against the
        //    rail while the ones with headroom keep rising, and what reaches the ear is a shelf
        //    with a step in it rather than the shape that was asked for.
        if (fmEnabled && !calDead && !strDead) {
            Clipping c = firstClipping(gains, cal, strengthPct);
            if (c != null) {
                r.findings.add(new Finding(Code.CEILING_CLIPS, Level.WARN,
                        c.volume, c.bandsAtMin, Math.round(c.worstOverflowDb)));
            }
        }

        // 3. Two bass controls under one curve. Neither of these is wrong on its own; together
        //    with a curve that is already lifting 20-80 Hz they are the usual way a preset ends up
        //    with a bottom nobody chose.
        if (fmEnabled && subComp && LoudnessCurve.maxSubBoost(subFreqIdx) > 0f) {
            r.findings.add(new Finding(Code.SUB_DOUBLE_BASS, Level.NOTE,
                    LoudnessCurve.hzOf(subFreqIdx)));
        }
        if (fmEnabled && bassBoost > 0) {
            r.findings.add(new Finding(Code.BASS_BOOST_STACKS, Level.NOTE, bassBoost));
        }

        // 4. The anchor. Only said once the car has been measured, because only then does the app
        //    know a volume at which the band gains are true.
        if (fmEnabled && carMeasured && !calDead && cal != r.recommendedCal) {
            r.findings.add(new Finding(Code.CAL_NOT_AT_MEASURED, Level.NOTE,
                    cal, r.recommendedCal));
        }
        return r;
    }

    /** Where the curve first runs out of headroom, walking down from the calibration point. */
    private static final class Clipping {
        int volume;
        int bandsAtMin;
        float worstOverflowDb;
    }

    private static Clipping firstClipping(int[] gains, int cal, int strengthPct) {
        float[] offs = new float[AudioConfig.NUM_BANDS];
        Clipping found = null;
        for (int vol = cal - 1; vol >= LoudnessCurve.VOL_MIN; vol--) {
            LoudnessCurve.offsets(vol, cal, strengthPct, true, false, offs);
            int bands = 0;
            float worst = 0f;
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                float total = bandDb(gains, i) + offs[i];
                float over = total - LoudnessCurve.CEILING_DB;
                if (over > 0.001f) {
                    bands++;
                    if (over > worst) worst = over;
                }
            }
            if (bands > 0) {
                if (found == null) {
                    found = new Clipping();
                    found.volume = vol;          // the loudest volume at which it already clips
                }
                found.bandsAtMin = bands;        // keeps being overwritten down to VOL_MIN
                found.worstOverflowDb = worst;
            }
        }
        return found;
    }

    /**
     * The strongest curve these band gains leave room for. The worst case is always volume
     * {@link LoudnessCurve#VOL_MIN}, where the ratio is 1, so one pass over the bands settles it -
     * and the subwoofer is included when it is being compensated, because it shares the ceiling.
     */
    public static int maxDeliverableStrength(int[] gains, int subGainIdx, int subFreqIdx,
                                             boolean subComp) {
        float limit = 1f;
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float iso = AudioConfig.ISO_MAX_OFFSETS[i];
            if (iso <= 0f) continue;
            float headroom = LoudnessCurve.CEILING_DB - bandDb(gains, i);
            limit = Math.min(limit, headroom <= 0f ? 0f : headroom / iso);
        }
        if (subComp) {
            float iso = LoudnessCurve.maxSubBoost(subFreqIdx);
            if (iso > 0f) {
                // The subwoofer's own gain is an index 0..12 where 12 is the top, not a dB value
                // centred on 6 the way the band gains are.
                float headroom = 12f - subGainIdx;
                limit = Math.min(limit, headroom <= 0f ? 0f : headroom / iso);
            }
        }
        int pct = (int) Math.floor(limit * 100f);
        return Math.max(0, Math.min(100, pct));
    }

    /** A stored gain index 0..12 as decibels: index 6 is flat, each step is 2 dB. */
    private static float bandDb(int[] gains, int i) {
        if (gains == null || i >= gains.length) return 0f;
        return (gains[i] - 6) * 2f;
    }
}
