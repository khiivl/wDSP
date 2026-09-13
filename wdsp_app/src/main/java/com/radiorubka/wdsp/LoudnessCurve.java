package com.radiorubka.wdsp;

import java.util.Arrays;

/**
 * The Fletcher-Munson loudness curve and the fatigue trim, in one place.
 *
 * <p>Until 13.09.2026 this arithmetic lived twice: {@code McuService.updateFmOffsets} computed what
 * was pushed to the chip, and {@code MainActivity.calculateFmOffsets} computed what the preview drew
 * underneath the two sliders. They were not the same function. The service gated on
 * {@code vol < cal - 1} and divided by {@code cal - 1}; the preview gated on {@code vol < cal} and
 * divided by {@code cal - 1} from a numerator one step larger. The person therefore set the curve by
 * a picture that was consistently one volume step ahead of the curve they got, and at volume 1 the
 * preview showed the full offsets while the chip received {@code (cal-2)/(cal-1)} of them.
 *
 * <p>Which of the two was right is not a matter of taste: the constants themselves say so.
 * {@code ISO_MAX_OFFSETS} is documented as the offset <em>at volume 1</em> and
 * {@code FATIGUE_MAX_OFFSETS} as the offset <em>at volume 32</em>. Only the preview's formula
 * actually reaches those values at those volumes, so the preview's formula is the one kept here and
 * the service now follows it. The chip therefore receives up to
 * {@code ISO_MAX_OFFSETS[i] / (cal - 1)} more than before - half a decibel at the default
 * calibration point of 25, three decibels if somebody has set it to 5.
 *
 * <p>Nothing in here reads preferences or touches hardware: it is arithmetic, so that
 * {@link LoudnessCheck} can ask what the curve would do at a volume nobody is currently playing at.
 */
public final class LoudnessCurve {

    /** The platform's volume range. Step 0 is mute and never carries a curve. */
    public static final int VOL_MIN = 1;
    public static final int VOL_MAX = 32;

    /** What the 16-band equaliser can actually deliver: gain index 0..12, i.e. -12..+12 dB. */
    public static final float CEILING_DB = 12f;
    public static final float FLOOR_DB = -12f;

    private LoudnessCurve() {
    }

    /**
     * How far the loudness curve has travelled at this volume: 0 where it does nothing, 1 at the
     * bottom of the scale. A calibration point of 0 or 1 leaves no room below it, which is why it
     * answers 0 rather than dividing by zero - and why {@link LoudnessCheck} reports that setting
     * as a curve that cannot act rather than letting it fail silently.
     */
    public static float loudnessRatio(int vol, int cal) {
        if (cal <= VOL_MIN || vol >= cal) return 0f;
        float r = (float) (cal - vol) / (float) (cal - VOL_MIN);
        return r < 0f ? 0f : (r > 1f ? 1f : r);
    }

    /** The same for the fatigue trim, which lives above the calibration point instead of below. */
    public static float fatigueRatio(int vol, int cal) {
        if (cal >= VOL_MAX || vol <= cal) return 0f;
        float r = (float) (vol - cal) / (float) (VOL_MAX - cal);
        return r < 0f ? 0f : (r > 1f ? 1f : r);
    }

    /**
     * Fills {@code out} with the 16 band offsets in dB. The two curves cannot both act at one
     * volume - one lives below the calibration point and the other above it - so this is a choice,
     * not a sum.
     *
     * @param out 16 floats, overwritten in full
     */
    public static void offsets(int vol, int cal, int strengthPct,
                               boolean fmEnabled, boolean fatigueEnabled, float[] out) {
        Arrays.fill(out, 0f);
        if (out.length < AudioConfig.NUM_BANDS) return;
        float str = strength(strengthPct);
        if (str <= 0f) return;

        if (fmEnabled) {
            float r = loudnessRatio(vol, cal);
            if (r > 0f) {
                for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                    out[i] = AudioConfig.ISO_MAX_OFFSETS[i] * r * str;
                }
                return;
            }
        }
        if (fatigueEnabled) {
            float r = fatigueRatio(vol, cal);
            if (r > 0f) {
                for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                    out[i] = AudioConfig.FATIGUE_MAX_OFFSETS[i] * r * str;
                }
            }
        }
    }

    /**
     * The extra subwoofer gain the loudness curve asks for, in gain-index steps of the 0x8B command.
     * The subwoofer has no band of its own, so it borrows the offset of whichever equaliser band its
     * crossover sits in - above 100 Hz there is no band low enough to borrow from and the answer is
     * zero.
     *
     * @param subFreqIdx index into {@link DspResponse#SUB_FREQS_HZ}
     */
    public static float subOffset(int vol, int cal, int strengthPct,
                                  boolean fmEnabled, boolean subComp, int subFreqIdx) {
        if (!fmEnabled || !subComp) return 0f;
        float r = loudnessRatio(vol, cal);
        if (r <= 0f) return 0f;
        return maxSubBoost(subFreqIdx) * r * strength(strengthPct);
    }

    /** The subwoofer's share of {@link AudioConfig#ISO_MAX_OFFSETS}, by crossover frequency. */
    public static float maxSubBoost(int subFreqIdx) {
        int hz = hzOf(subFreqIdx);
        if (hz == 80) return AudioConfig.ISO_MAX_OFFSETS[3];
        if (hz == 63 || hz == 50) return AudioConfig.ISO_MAX_OFFSETS[2];
        if (hz == 40 || hz == 32) return AudioConfig.ISO_MAX_OFFSETS[1];
        if (hz == 25) return AudioConfig.ISO_MAX_OFFSETS[0];
        return 0f;
    }

    /**
     * The crossover in hertz. {@link DspResponse#SUB_FREQS_HZ} is the one table; McuService used to
     * carry a second literal copy of it and MainActivity a third, as strings.
     */
    public static int hzOf(int subFreqIdx) {
        if (subFreqIdx < 0 || subFreqIdx >= DspResponse.SUB_FREQS_HZ.length) return 80;
        return DspResponse.SUB_FREQS_HZ[subFreqIdx];
    }

    private static float strength(int strengthPct) {
        if (strengthPct <= 0) return 0f;
        if (strengthPct >= 100) return 1f;
        return strengthPct / 100f;
    }
}
