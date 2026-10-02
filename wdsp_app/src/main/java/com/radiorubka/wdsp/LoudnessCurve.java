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
     * The subwoofer has no band of its own, so it borrows the offset of an equaliser band - see
     * {@link #maxSubBoost} for which one, at every crossover.
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

    /**
     * The subwoofer's share of the loudness curve at volume 1, by crossover: the ISO 226 boost
     * ({@link AudioConfig#ISO_FULL_TARGET_DB}) of the highest equaliser band the subwoofer plays on
     * its own - one band below the band its crossover sits in, since at the crossover itself it is
     * 3 dB down and shares the band with the doors.
     *
     * <pre>
     * crossover, Hz  25  32  40  50  63  80 | 100 125 160 200 250
     * boost, dB      12  12  12  10  10   8 |   8   6   6   4   4
     * </pre>
     *
     * <p>Left of the bar is the author's 0.5 table ({@code McuService.getMaxBassBoost}) exactly; it
     * answered zero from 100 Hz. Right of it is the same rule carried on - the owner, 02.10.2026:
     * <em>«ти ж маєш дослідження, продовж таблицю»</em>, and on what that means: <em>«тобто, це буде
     * його, але доповнена нами таблиця?»</em>. Ours until then took the band at the crossover (80 Hz
     * -> +6) and had stopped at 100 Hz, which was added on the owner's instruction 14.09. Why his
     * numbers stand where both tables have one: <em>«суть, автор має обладнання для замірів, ми
     * ні.»</em> The mean ISO boost over all the bands the subwoofer plays was weighed as well (12 11
     * 11 10 10 9 9 8 8 7 7) and set aside: it is only derived, and it moves three of his numbers by
     * 1 dB.
     *
     * <p>⚠️ The dynamic-bass research's own verdict on this switch is "compensate through the
     * equaliser only": the equaliser sits before the subwoofer split, so the subwoofer already
     * receives the loudness boost band by band and this adds to it (.agents/CABIN_MODEL.md §10-11).
     * It stays as a deliberate extra for when the bass is short - the owner, 15.09.2026.
     */
    public static float maxSubBoost(int subFreqIdx) {
        float crossoverHz = hzOf(subFreqIdx);
        int atCrossover = -1;
        for (int i = 0; i < AudioConfig.NUM_BANDS && AudioConfig.BAND_CENTER_HZ[i] <= crossoverHz; i++) {
            atCrossover = i;
        }
        if (atCrossover < 0) return 0f;
        return AudioConfig.ISO_FULL_TARGET_DB[Math.max(0, atCrossover - 1)];
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
