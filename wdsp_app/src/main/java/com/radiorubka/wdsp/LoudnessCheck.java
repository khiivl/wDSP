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
        /** Fatigue trim is on but its start volume leaves no room above it. */
        FATIGUE_NO_ROOM,
        /** The preset plus the curve exceeds what the equaliser can deliver; the shape collapses. */
        CEILING_CLIPS,
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
     * @param fatigueStartVol  where Trim Highs starts, the preset's {@code _fat_start_vol}
     * @param bassFreqIdxFront the front bass shelf frequency, {@code _bb_frq_f}, 0 = off
     * @param bassGainFront    the front bass shelf's own gain, {@code _bb_f}
     * @param carMeasured  whether this car has an auto-EQ measurement at all
     */
    public static Result inspect(int[] gains, int subGainIdx, int subFreqIdx, int bassBoost,
                                 boolean fmEnabled, boolean fatigueEnabled, boolean subComp,
                                 int cal, int strengthPct, int fatigueStartVol,
                                 int bassFreqIdxFront, int bassGainFront, boolean carMeasured) {
        Result r = new Result();
        r.recommendedCal = carMeasured ? RoomMeasurement.MEASURE_VOLUME : 0;
        r.recommendedStrength = maxDeliverableStrength(gains, subGainIdx, subFreqIdx, subComp,
                bassFreqIdxFront, bassGainFront);

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

        if (fatigueEnabled && fatigueStartVol >= LoudnessCurve.VOL_MAX) {
            r.findings.add(new Finding(Code.FATIGUE_NO_ROOM, Level.WARN, fatigueStartVol));
        }

        // 2. The ceiling. The equaliser clamps each band to +/-12 dB, so a preset that already
        //    raises the bottom leaves the curve nowhere to go: the low bands pin flat against the
        //    rail while the ones with headroom keep rising, and what reaches the ear is a shelf
        //    with a step in it rather than the shape that was asked for. Measured on the drive the
        //    chip actually gets - sliders and curve pre-warped together (the author's 0.5) - not on
        //    their plain sum.
        if (fmEnabled && !calDead && !strDead) {
            Clipping c = firstClipping(gains, cal, strengthPct, bassFreqIdxFront);
            if (c != null) {
                r.findings.add(new Finding(Code.CEILING_CLIPS, Level.WARN,
                        c.volume, c.bandsAtMin, Math.round(c.worstOverflowDb)));
            }
        }

        // 3. A separate bass boost under a curve that is already lifting 20-80 Hz.
        //
        //    ⚠️ There used to be a sibling of this check, "subwoofer compensation plus the band
        //    offsets raise the same bass twice", and it was removed on 14.09.2026 at the owner's
        //    request. It was reasoning, not measurement, and it was wrong by construction:
        //    subwoofer compensation does nothing at all unless loudness is on
        //    (LoudnessCurve.subOffset returns 0 otherwise), so it fired on the one configuration
        //    in which that feature works as designed - and it did so beneath a fix button that
        //    could not change it. A warning that condemns the intended use of a feature is noise.
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

    /**
     * Where a drive stops being delivered. The chip rounds to 2 dB steps, so a drive less than
     * 1 dB above the +12 dB top still lands on the top step; past that a step is lost.
     */
    private static final float RAIL_DB = LoudnessCurve.CEILING_DB + 1f;

    private static Clipping firstClipping(int[] gains, int cal, int strengthPct,
                                          int bassFreqIdxFront) {
        float[] offs = new float[AudioConfig.NUM_BANDS];
        Clipping found = null;
        for (int vol = cal - 1; vol >= LoudnessCurve.VOL_MIN; vol--) {
            LoudnessCurve.offsets(vol, cal, strengthPct, true, false,
                    LoudnessCurve.VOL_MAX, bassFreqIdxFront, offs);
            float[] drive = LoudnessCurve.eqDriveDb(gains, offs);
            int bands = 0;
            float worst = 0f;
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                float over = drive[i] - RAIL_DB;
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
     * {@link LoudnessCurve#VOL_MIN}, where the ratio is 1, so one pass settles it. Since the
     * author's 0.5 three things carry the curve, each under its own top:
     * <ul>
     *   <li>the equaliser, driven with the sliders and the residual row pre-warped together. The
     *       pre-warp is linear, so at strength s the drive is {@code a + s*b} - {@code a} the
     *       pre-warped sliders, {@code b} the pre-warped row - and each band's limit is where that
     *       line meets the rail;</li>
     *   <li>the front bass shelf, whose share at strength s is {@code s * 10} on top of the
     *       person's own gain, under the shelf's 12;</li>
     *   <li>the subwoofer, when it is being compensated.</li>
     * </ul>
     */
    public static int maxDeliverableStrength(int[] gains, int subGainIdx, int subFreqIdx,
                                             boolean subComp, int bassFreqIdxFront,
                                             int bassGainFront) {
        float limit = 1f;
        float[] sliders = new float[AudioConfig.NUM_BANDS];
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) sliders[i] = bandDb(gains, i);
        float[] a = AudioConfig.prewarpEq(sliders);
        float[] row = new float[AudioConfig.NUM_BANDS];
        LoudnessCurve.offsets(LoudnessCurve.VOL_MIN, LoudnessCurve.VOL_MAX, 100, true, false,
                LoudnessCurve.VOL_MAX, bassFreqIdxFront, row);
        float[] b = AudioConfig.prewarpEq(row);
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            if (b[i] > 0.01f) {
                float headroom = RAIL_DB - a[i];
                limit = Math.min(limit, headroom <= 0f ? 0f : headroom / b[i]);
            } else if (b[i] < -0.01f) {
                float headroom = a[i] + RAIL_DB;
                limit = Math.min(limit, headroom <= 0f ? 0f : headroom / -b[i]);
            }
        }
        // The shelf with no curve on it is the person's own setting, as the service would send it.
        LoudnessCurve.BassShelf own = LoudnessCurve.bassShelf(LoudnessCurve.VOL_MAX,
                LoudnessCurve.VOL_MAX, 100, true, bassFreqIdxFront, bassGainFront, 0, 0);
        float shelfRoom = 12f - own.gainFront;
        limit = Math.min(limit, shelfRoom <= 0f ? 0f
                : shelfRoom / AudioConfig.LOUDNESS_BASS_SHELF_MAX_DB);
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
        return AudioConfig.eqGainDb(gains[i]);
    }
}
