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
 * <p>Which of the two was right is not a matter of taste: the constants themselves say so. The
 * loudness table was documented as the offset <em>at volume 1</em> and the fatigue table as the
 * offset <em>at volume 32</em> - and the author's 0.5 tables that replaced them
 * ({@code AudioConfig.ISO_RAW_TARGET_BY_FREQ}, {@code FATIGUE_RAW_TARGET}) say the same. Only the
 * preview's formula actually reaches those values at those volumes, so it is the one kept here: his
 * 0.5 service still gates on {@code vol < cal - 1} and stops at {@code (cal-2)/(cal-1)} of the curve
 * at volume 1, which these ratios do not copy.
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

    /**
     * How far a curve that lives above a start volume has travelled: 0 at the start, 1 at the top
     * of the scale. Trim Highs ({@code _fat_start_vol}, no longer the calibration point since the
     * author's 0.5) and Ultra Bass ({@code _ultra_bass_start_vol}) both ride it.
     */
    public static float rampAbove(int vol, int startVol) {
        if (startVol >= VOL_MAX || vol <= startVol) return 0f;
        float r = (float) (vol - startVol) / (float) (VOL_MAX - startVol);
        return r < 0f ? 0f : (r > 1f ? 1f : r);
    }

    /** Where Trim Highs starts in a preset that never chose: the author's 0.5 default. */
    public static final int FATIGUE_START_DEFAULT = 25;

    /** Ultra Bass defaults, the author's 0.5: from volume 16, up to +6 at volume 32. */
    public static final int ULTRA_BASS_START_DEFAULT = 16;
    public static final int ULTRA_BASS_MAX_DB_DEFAULT = 6;

    /**
     * Ultra Bass, the author's 0.5: extra subwoofer gain that grows with the volume - 0 at its start
     * volume, {@code maxDb} at 32 - for when the bass gets thin as the music gets loud. It has
     * nothing to do with loudness: no calibration point, no strength, acts with loudness off.
     */
    public static float ultraBassOffset(int vol, boolean enabled, int startVol, int maxDb) {
        return enabled ? maxDb * rampAbove(vol, startVol) : 0f;
    }

    /**
     * Fills {@code out} with the 16 band offsets in dB - the target, before the pre-warp
     * ({@link #eqDriveDb}).
     *
     * <p><b>Loudness</b>, the author's 0.5: below the calibration point the ISO 226 boost is split
     * between the equaliser and the doors' bass shelf ({@link #bassShelf}). The equaliser gets the
     * residual row for the shelf frequency the front pair is on
     * ({@link AudioConfig#isoRawTargetForFreqIdx}), the shelf the rest; both grow by the same ratio,
     * so together they make the whole curve at every volume.
     *
     * <p><b>Trim Highs</b>, his 0.5 too: above its own start volume, a dip at 3.15-5 kHz
     * ({@link AudioConfig#FATIGUE_RAW_TARGET}), where the ear is most sensitive and where loud
     * listening tires it - in place of the old cut that deepened towards 20 kHz, the band the ear
     * hears least.
     *
     * <p>Loudness wins where both could act - a start volume set below the calibration point - as
     * in his.
     *
     * @param fatigueStartVol  the preset's {@code _fat_start_vol}
     * @param bassFreqIdxFront the front shelf frequency the preset chose, {@code _bb_frq_f}, 0 = off
     * @param out              16 floats, overwritten in full
     */
    public static void offsets(int vol, int cal, int strengthPct, boolean fmEnabled,
                               boolean fatigueEnabled, int fatigueStartVol, int bassFreqIdxFront,
                               float[] out) {
        Arrays.fill(out, 0f);
        if (out.length < AudioConfig.NUM_BANDS) return;
        float str = strength(strengthPct);
        if (str <= 0f) return;

        if (fmEnabled) {
            float r = loudnessRatio(vol, cal);
            if (r > 0f) {
                float[] row = AudioConfig.isoRawTargetForFreqIdx(shelfFreqIdx(bassFreqIdxFront));
                for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                    out[i] = row[i] * r * str;
                }
                return;
            }
        }
        if (fatigueEnabled) {
            float r = rampAbove(vol, fatigueStartVol);
            if (r > 0f) {
                for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                    out[i] = AudioConfig.FATIGUE_RAW_TARGET[i] * r * str;
                }
            }
        }
    }

    /**
     * What the equaliser is driven with, per band in dB, before the chip's 2 dB rounding and
     * +/-12 dB clamp ({@link #gainIndex}): the sliders plus the offsets, pre-warped together while
     * an offset is active ({@link AudioConfig#prewarpEq}, the author's 0.5), so the 16 overlapping
     * bells sum to that target at every band centre. With no offset it is the sliders, exactly.
     * The one place this is computed: the service sends it, {@link LoudnessCheck} measures its
     * headroom.
     *
     * @param gains   16 stored gain indices, 6 = flat
     * @param offsets {@link #offsets} in dB, or null
     */
    public static float[] eqDriveDb(int[] gains, float[] offsets) {
        float[] target = new float[AudioConfig.NUM_BANDS];
        boolean hasOffset = false;
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float off = offsets != null && i < offsets.length ? offsets[i] : 0f;
            target[i] = AudioConfig.eqGainDb(gains[i]) + off;
            if (off != 0f) hasOffset = true;
        }
        return hasOffset ? AudioConfig.prewarpEq(target) : target;
    }

    /** The chip's gain index for a drive in dB: 2 dB steps around index 6, clamped to 0..12. */
    public static int gainIndex(float driveDb) {
        return Math.max(0, Math.min(12, Math.round(driveDb / 2f + 6f)));
    }

    /** The doors' bass shelf (command 0x88, the P2Bass stage) at one moment: front and rear. */
    public static final class BassShelf {
        /** Index into the shelf frequencies, 0 = off, 1.. = {@link AudioConfig#BASS_BOOST_FREQS_HZ}. */
        public final int freqIdxFront, freqIdxRear;
        /** Gain 0..12, as the low nibble of the command carries it. */
        public final int gainFront, gainRear;

        BassShelf(int freqIdxFront, int gainFront, int freqIdxRear, int gainRear) {
            this.freqIdxFront = freqIdxFront;
            this.gainFront = gainFront;
            this.freqIdxRear = freqIdxRear;
            this.gainRear = gainRear;
        }

        /** The front shelf's frequency in Hz, 0 when off - for the models that draw it. */
        public float frontHz() {
            return hzOf(freqIdxFront);
        }

        public float rearHz() {
            return hzOf(freqIdxRear);
        }

        private static float hzOf(int freqIdx) {
            return freqIdx >= 1 && freqIdx <= AudioConfig.BASS_BOOST_FREQS_HZ.length
                    ? AudioConfig.BASS_BOOST_FREQS_HZ[freqIdx - 1] : 0f;
        }
    }

    /**
     * The shelf as the author's 0.5 sends it. With loudness off: the person's own front and rear
     * settings, untouched. With loudness on: the front shelf carries its share of the curve -
     * {@link AudioConfig#LOUDNESS_BASS_SHELF_MAX_DB} at volume 1, grown by the same ratio as the
     * equaliser's share - at the frequency the person chose and on top of the person's own gain; with
     * the shelf off, at the tuned 86 Hz and without a manual gain. The assist tops out at 10 so a
     * manual boost still adds something below the shelf's 12. The rear pair follows the front for
     * as long as loudness is on, because the equaliser's residual is solved against the front's
     * frequency only; the rear's own settings stay stored and come back when loudness goes off.
     */
    public static BassShelf bassShelf(int vol, int cal, int strengthPct, boolean fmEnabled,
                                      int freqIdxFront, int gainFront, int freqIdxRear, int gainRear) {
        if (!fmEnabled) return new BassShelf(freqIdxFront, gainFront, freqIdxRear, gainRear);
        float assist = AudioConfig.LOUDNESS_BASS_SHELF_MAX_DB * loudnessRatio(vol, cal)
                * strength(strengthPct);
        int freq = shelfFreqIdx(freqIdxFront);
        int manual = freq == freqIdxFront ? gainFront : 0;
        int gain = Math.max(0, Math.min(12, Math.round(assist + manual)));
        return new BassShelf(freq, gain, freq, gain);
    }

    /**
     * The shelf frequency loudness works with: the one the person chose, or the tuned default when
     * the shelf is off or holds an index outside AudioConfig.BASS_BOOST_FREQS_HZ (a stale or
     * corrupt preset). The shelf and the residual always name the same frequency.
     */
    private static int shelfFreqIdx(int chosenIdx) {
        return chosenIdx >= 1 && chosenIdx <= AudioConfig.BASS_BOOST_FREQS_HZ.length
                ? chosenIdx : AudioConfig.LOUDNESS_BASS_SHELF_FREQ_IDX;
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
        // No subwoofer in the preset, nothing to compensate on it - hzOf would read 80 Hz.
        if (DspResponse.isSubOff(subFreqIdx)) return 0f;
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
