package com.radiorubka.wdsp;

/**
 * Shared audio configuration and constants for wDSP.
 * Centralizing these allows easy tuning of the Fletcher-Munson curve 
 * and fatigue trim offsets.
 */
public class AudioConfig {
    public static final int NUM_BANDS = 16;
    
    // Index-to-MCU Gain value mapping
    public static final int[] GAIN_MAP = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};

    // Loudness compensation's bass-shelf assist: below the calibration volume, part of the ISO
    // 226 bass boost is delivered through the front/rear "Bass Boost" hardware shelf (see
    // McuService.applyBassBoost()/AudioConfig.bassShapingResponseDb()) instead of the 16-band EQ
    // alone. That shelf is a single continuous 1st-order filter with genuine 1dB resolution -
    // twice the EQ's 2dB-stepped gain index, and no cross-talk-cancellation-vs-quantization
    // tension the way 16 overlapping Q=2.2 bands have - so offloading the steep, low-frequency
    // part of the curve onto it measurably smooths out the bass region (worst-case ripple dropped
    // from about -1.8dB to -0.2dB in testing). LOUDNESS_BASS_SHELF_FREQ_IDX must stay in sync with
    // MainActivity.BASS_BOOST_FREQS[idx] == "86" - it's a separate array (UI spinner entries) with
    // no shared source of truth, so if that array's order ever changes this needs updating too.
    // Capped at 10 (not the shelf's full 12dB range) to leave headroom for whatever the user has
    // manually dialed into their own Boost slider to still add something on top while this is active.
    public static final float LOUDNESS_BASS_SHELF_FREQ_HZ = 86f;
    public static final int LOUDNESS_BASS_SHELF_FREQ_IDX = 3;
    public static final float LOUDNESS_BASS_SHELF_MAX_DB = 10f;

    // The full, un-split ISO 226 target curve (before subtracting the bass-shelf's own
    // contribution to get ISO_MAX_OFFSETS' residual below) - kept separately so
    // McuService.getMaxBassBoost() (the dedicated SUBWOOFER channel's own compensation boost, a
    // completely separate hardware output from the 16-band EQ and the front/rear Bass Boost
    // shelf, so it isn't part of the EQ-vs-shelf split at all) still targets the real intended
    // boost at each low band instead of silently shrinking whenever the EQ/shelf split changes.
    public static final float[] ISO_FULL_TARGET_DB = {
            12f, 10f, 8f, 6f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 4f, 6f, 8f
    };

    // ISO 226 raw target curve (dB) at full strength/Volume 1 relative to calibration point -
    // NOT pre-warped. One row per Bass Boost shelf frequency the user can actually pick
    // (BASS_BOOST_FREQS_HZ), since the EQ's residual job changes depending on which frequency
    // the shelf is carrying the low end at right now: each row is ISO_FULL_TARGET_DB (itself
    // already snapped to EVEN integers, since gain is stored as an index 0..12 where each step
    // is 2dB) minus that row's shelf response (bassShapingResponseDb's boost term,
    // LOUDNESS_BASS_SHELF_MAX_DB at that frequency), re-snapped to even dB. The dip around
    // 80-315Hz (a real, not a rounding artifact) widens and deepens as the shelf frequency
    // climbs, since a higher shelf's reach overshoots the flat target further up into the
    // midrange - 214Hz's -4dB dip is the most extreme of these, and the EQ ripple that comes
    // with correcting it is an accepted tradeoff for users who want that frequency specifically,
    // not an oversight - see isoRawTargetForFreqHz()'s doc for how a row is picked.
    //
    // This feeds prewarpEq() below (combined with whatever the manual sliders are set to,
    // see calculateFmOffsets()/McuService.updateFmOffsets()) rather than being driven to
    // hardware directly - a band driven at this raw value alone would overshoot once 16
    // overlapping Q=2.2 bells' real neighbor leakage sums in. (Previously this held the
    // already-solved drive values directly, back when the offset was pre-warped in isolation
    // instead of jointly with the sliders - solving against an already-solved input a second
    // time doesn't reproduce the same curve, since M^-1 isn't idempotent.)
    public static final float[][] ISO_RAW_TARGET_BY_FREQ = {
            // 54 Hz
            {4f, 2f, 2f, 2f, 2f, 2f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 4f, 6f, 8f},
            // 68 Hz
            {2f, 2f, 2f, 2f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 4f, 6f, 8f},
            // 86 Hz - the default/most-optimal frequency (also used whenever the manual
            // selection is "off" or an unrecognized/stale value - see isoRawTargetForFreqHz()).
            {2f, 2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 4f, 6f, 8f},
            // 108 Hz
            {2f, 0f, 0f, 0f, 0f, 0f, -2f, 0f, 0f, 0f, 0f, 2f, 4f, 4f, 6f, 8f},
            // 134 Hz
            {2f, 0f, 0f, -2f, -2f, -2f, -2f, 0f, 0f, 0f, 0f, 2f, 4f, 4f, 6f, 8f},
            // 172 Hz
            {2f, 0f, -2f, -2f, -2f, -2f, -2f, -2f, 0f, 0f, 0f, 2f, 4f, 4f, 6f, 8f},
            // 214 Hz
            {2f, 0f, -2f, -2f, -4f, -4f, -4f, -2f, 0f, 0f, 0f, 2f, 4f, 4f, 6f, 8f},
    };

    // The Bass Boost shelf frequencies a user can actually pick manually (MainActivity's
    // BASS_BOOST_FREQS[0] is "off"; indices 1.. map 1:1 to this array). Single source of truth
    // for index<->Hz conversion so McuService (which only has the saved pref index) and
    // MainActivity (which reads Hz off the spinner text) agree on what each index means.
    public static final float[] BASS_BOOST_FREQS_HZ = {54f, 68f, 86f, 108f, 134f, 172f, 214f};

    /** BASS_BOOST_FREQS[0] ("off") or any index outside BASS_BOOST_FREQS_HZ falls back to the
     * tuned default/most-optimal frequency, LOUDNESS_BASS_SHELF_FREQ_HZ. */
    public static float bassBoostFreqHzForIdx(int bassBoostFreqIdx) {
        int i = bassBoostFreqIdx - 1;
        return (i >= 0 && i < BASS_BOOST_FREQS_HZ.length) ? BASS_BOOST_FREQS_HZ[i] : LOUDNESS_BASS_SHELF_FREQ_HZ;
    }

    /** Picks the ISO_RAW_TARGET_BY_FREQ row matching a shelf frequency in Hz, falling back to
     * the 86Hz row (also the "off"/unrecognized default) when nothing matches closely enough. */
    public static float[] isoRawTargetForFreqHz(float freqHz) {
        for (int i = 0; i < BASS_BOOST_FREQS_HZ.length; i++) {
            if (Math.abs(freqHz - BASS_BOOST_FREQS_HZ[i]) < 0.5f) return ISO_RAW_TARGET_BY_FREQ[i];
        }
        return ISO_RAW_TARGET_BY_FREQ[2];
    }

    public static float[] isoRawTargetForFreqIdx(int bassBoostFreqIdx) {
        return isoRawTargetForFreqHz(bassBoostFreqHzForIdx(bassBoostFreqIdx));
    }

    // Fatigue Trim raw target curve (dB) at Volume 32 relative to calibration point, NOT
    // pre-warped (see ISO_RAW_TARGET_BY_FREQ's doc above for why). Targets the ear's actual
    // sensitivity/resonance peak (ISO 226 equal-loudness contours, ear-canal resonance) around
    // 3.15-5kHz - a dip centered there, tapering off both toward the mids and the extreme top
    // end - instead of the old monotonic high-shelf cut (which put its deepest cut at 20kHz,
    // the band the ear is LEAST sensitive to, and barely touched the 3-5kHz region actually
    // responsible for listening fatigue/resonance at high volume). Snapped to EVEN dB only,
    // same reasoning as ISO_RAW_TARGET_BY_FREQ. The center of the dip (3.15k/5k) is a real
    // -4dB here rather than splitting the difference at -3, which would have rounded down to
    // just -2dB in practice anyway.
    public static final float[] FATIGUE_RAW_TARGET = {
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -2f, -2f, -4f, -4f, -2f, -2f, 0f
    };

    // Precomputed inverse of the same Q=2.2 cross-talk matrix M[i][j] = 1/(1+(Q*(r-1/r))^2)
    // used to offline-solve the raw targets above (numpy.linalg.inv(M), a fixed 16x16 since M
    // only depends on Q and the fixed band centers). prewarpEq() multiplies by this instead of
    // running a live solver on-device - mathematically identical to solving M*x=target.
    public static final float[][] EQ_CROSSTALK_INVERSE = {
            {1.037378f, -0.194558f, -0.009735f, -0.004621f, -0.001443f, -0.000499f, -0.000169f, -0.000059f, -0.000020f, -0.000007f, -0.000002f, -0.000001f, -0.000000f, -0.000000f, -0.000000f, -0.000000f},
            {-0.194558f, 1.071660f, -0.186635f, -0.008960f, -0.004350f, -0.001360f, -0.000460f, -0.000159f, -0.000054f, -0.000019f, -0.000006f, -0.000002f, -0.000001f, -0.000000f, -0.000000f, -0.000000f},
            {-0.009735f, -0.186635f, 1.067463f, -0.180477f, -0.008840f, -0.004496f, -0.001374f, -0.000475f, -0.000159f, -0.000055f, -0.000019f, -0.000007f, -0.000002f, -0.000001f, -0.000000f, -0.000000f},
            {-0.004621f, -0.008960f, -0.180477f, 1.072100f, -0.199419f, -0.008804f, -0.004392f, -0.001371f, -0.000459f, -0.000158f, -0.000055f, -0.000019f, -0.000007f, -0.000002f, -0.000001f, -0.000000f},
            {-0.001443f, -0.004350f, -0.008840f, -0.199419f, 1.072100f, -0.180432f, -0.008865f, -0.004427f, -0.001342f, -0.000460f, -0.000159f, -0.000054f, -0.000019f, -0.000006f, -0.000002f, -0.000001f},
            {-0.000499f, -0.001360f, -0.004496f, -0.008804f, -0.180432f, 1.069681f, -0.192781f, -0.008886f, -0.004362f, -0.001354f, -0.000468f, -0.000159f, -0.000055f, -0.000019f, -0.000006f, -0.000002f},
            {-0.000169f, -0.000460f, -0.001374f, -0.004392f, -0.008865f, -0.192781f, 1.071776f, -0.186583f, -0.008944f, -0.004344f, -0.001358f, -0.000459f, -0.000159f, -0.000054f, -0.000019f, -0.000007f},
            {-0.000059f, -0.000159f, -0.000475f, -0.001371f, -0.004427f, -0.008886f, -0.186583f, 1.067487f, -0.180469f, -0.008838f, -0.004495f, -0.001373f, -0.000475f, -0.000159f, -0.000055f, -0.000021f},
            {-0.000020f, -0.000054f, -0.000159f, -0.000459f, -0.001342f, -0.004362f, -0.008944f, -0.180469f, 1.072102f, -0.199418f, -0.008804f, -0.004392f, -0.001371f, -0.000459f, -0.000158f, -0.000059f},
            {-0.000007f, -0.000019f, -0.000055f, -0.000158f, -0.000460f, -0.001354f, -0.004344f, -0.008838f, -0.199418f, 1.072101f, -0.180432f, -0.008865f, -0.004427f, -0.001342f, -0.000461f, -0.000170f},
            {-0.000002f, -0.000006f, -0.000019f, -0.000055f, -0.000159f, -0.000468f, -0.001358f, -0.004495f, -0.008804f, -0.180432f, 1.069681f, -0.192781f, -0.008886f, -0.004363f, -0.001356f, -0.000501f},
            {-0.000001f, -0.000002f, -0.000007f, -0.000019f, -0.000054f, -0.000159f, -0.000459f, -0.001373f, -0.004392f, -0.008865f, -0.192781f, 1.071776f, -0.186584f, -0.008946f, -0.004350f, -0.001452f},
            {-0.000000f, -0.000001f, -0.000002f, -0.000007f, -0.000019f, -0.000055f, -0.000159f, -0.000475f, -0.001371f, -0.004427f, -0.008886f, -0.186584f, 1.067485f, -0.180477f, -0.008854f, -0.004776f},
            {-0.000000f, -0.000000f, -0.000001f, -0.000002f, -0.000006f, -0.000019f, -0.000054f, -0.000159f, -0.000459f, -0.001342f, -0.004363f, -0.008946f, -0.180477f, 1.072079f, -0.199469f, -0.009695f},
            {-0.000000f, -0.000000f, -0.000000f, -0.000001f, -0.000002f, -0.000006f, -0.000019f, -0.000055f, -0.000158f, -0.000461f, -0.001356f, -0.004350f, -0.008854f, -0.199469f, 1.071986f, -0.182309f},
            {-0.000000f, -0.000000f, -0.000000f, -0.000000f, -0.000001f, -0.000002f, -0.000007f, -0.000021f, -0.000059f, -0.000170f, -0.000501f, -0.001452f, -0.004776f, -0.009695f, -0.182309f, 1.033061f},
    };

    /**
     * Jointly pre-warps a 16-band target curve (dB, neutral-relative) so the real composite
     * response of 16 overlapping Q=2.2 bells hits targetDb[i] exactly at every band i's own
     * center - same principle as ISO_RAW_TARGET_BY_FREQ's offline solve, but computed live
     * against whatever targetDb actually is (e.g. the manual slider's own dB PLUS the loudness
     * offset, combined), not just an isolated offset. This is what lets the manual EQ's own
     * shape get cross-talk-cancelled too, instead of only ever being true for the loudness
     * offset in isolation while the user's raw slider ripple sits uncorrected on top of it.
     * With a flat/all-equal targetDb (nothing for 16 overlapping bells to fight over), the
     * result is targetDb unchanged - callers should still skip calling this when there's no
     * loudness offset active, though, both as an optimization and to avoid introducing
     * floating-point drift into an otherwise-exact passthrough.
     */
    public static float[] prewarpEq(float[] targetDb) {
        float[] result = new float[NUM_BANDS];
        for (int i = 0; i < NUM_BANDS; i++) {
            float sum = 0f;
            float[] row = EQ_CROSSTALK_INVERSE[i];
            for (int j = 0; j < NUM_BANDS; j++) sum += row[j] * targetDb[j];
            result[i] = sum;
        }
        return result;
    }

    // Frequency labels for UI and Logging
    public static final String[] BAND_LABELS = {
            "20", "31.5", "50", "80", "125", "200", "315", "500",
            "800", "1.25k", "2k", "3.15k", "5k", "8k", "12.5k", "20k"
    };

    // Numeric center frequency (Hz) for each of the 16 bands - same values as BAND_LABELS,
    // just usable in the actual filter-response math (EqVisualizerView's composite curve,
    // SpectrumAnalyzerView's bin-to-band mapping).
    public static final float[] BAND_CENTER_HZ = {
            20f, 31.5f, 50f, 80f, 125f, 200f, 315f, 500f,
            800f, 1250f, 2000f, 3150f, 5000f, 8000f, 12500f, 20000f
    };

    // The hardware's fixed Q for every band right now - the per-band Q toggle is hidden in the
    // UI (non-functional until an MCU firmware update, see MainActivity.setupEqBands()), so
    // every band actually behaves like a 2.2 Q peaking filter regardless of its stored Q byte.
    public static final float DEFAULT_Q = 2.2f;

    /**
     * Composite dB response at a given frequency of all 16 bands' peaking (bell) filters
     * summed together, each with its own gain (0..12, 6 = 0dB) and DEFAULT_Q. Uses the
     * standard Q-controlled bell approximation G / (1 + (Q*(f/f0 - f0/f))^2), which is exact
     * at each band's own center frequency and rolls off on both sides at a rate set by Q.
     * Shared by EqVisualizerView (the EQ curve) and SpectrumAnalyzerView (the gain-reactive
     * scaling of the live spectrum bars) so both reflect the same real, calculated curve
     * rather than each reading a band's own slider value in isolation.
     */
    /** One band's own Q=2.2 peaking-filter contribution (dB) at freqHz, g0 its gain in dB at its
     * own center. Shared by both compositeResponseDb() overloads below and by
     * bassShapingResponseDb()'s boost-peak term, so the one bell formula only lives in one place. */
    private static float singleBandDb(float g0, float freqHz, float centerHz, float q) {
        if (g0 == 0f) return 0f;
        float ratio = freqHz / centerHz;
        float x = ratio - 1f / ratio;
        return g0 / (1f + (q * x) * (q * x));
    }

    public static float compositeResponseDb(int[] gains, float freqHz) {
        // Not delegated through the float[] overload below - this runs per-sample in hot draw
        // loops (FmVisualizerView, SpectrumAnalyzerView), so it stays allocation-free rather than
        // boxing into a temporary float[] every call.
        float totalDb = 0f;
        for (int i = 0; i < NUM_BANDS; i++) {
            totalDb += singleBandDb((gains[i] - 6) * 2f, freqHz, BAND_CENTER_HZ[i], DEFAULT_Q);
        }
        return totalDb;
    }

    /** Same as compositeResponseDb(int[], float) but for continuously-animated (non-integer)
     * gain values - see EqVisualizerView's animatedGains, which glides toward the real (integer)
     * slider gains each frame instead of snapping the curve directly to them. Also used for its
     * loudnessCorrectionGains overlay curve, which glides the same way. */
    public static float compositeResponseDb(float[] gains, float freqHz) {
        float totalDb = 0f;
        for (int i = 0; i < NUM_BANDS; i++) {
            totalDb += singleBandDb((gains[i] - 6) * 2f, freqHz, BAND_CENTER_HZ[i], DEFAULT_Q);
        }
        return totalDb;
    }

    /**
     * Maps a continuous band position (0 = band 0's center, NUM_BANDS-1 = the last band's
     * center, fractional values in between) to a frequency in Hz, log-interpolating between the
     * two neighboring bands' real center frequencies. Matches how the ISO band centers
     * themselves are spaced, so a curve sampled at fractional t values reads as a proper
     * log-frequency axis - and, critically, this exact same mapping is what the real per-band
     * Slider columns in eq_container effectively use too (16 evenly-weighted columns), so any
     * view drawing a continuous curve over frequency needs to go through this, not an
     * independent 20Hz-20kHz log sweep, or it will visibly drift out of x-axis alignment with
     * the sliders/EQ curve the further a point sits from a band's own center - see
     * SpectrumAnalyzerView's centerHz[] for a case where using a plain log sweep instead of this
     * caused exactly that drift.
     */
    // The BU32107's Sub LPF is a cascade of two 2nd-order IIR filters, usable as a 2nd (12dB/oct)
    // or 4th (24dB/oct) order filter in Table Mode - per its datasheet's "[Sub HPF/LPF/IIR]"
    // section. The datasheet doesn't publish the per-stage Q, so subFilterResponseDb() below
    // assumes standard Butterworth/maximally-flat alignment, the conventional choice for a fixed-
    // table crossover filter like this. Which order this unit is actually configured for isn't
    // visible anywhere in the app<->MCU protocol (see McuService.updateSubwoofer(), which only
    // sends frequency index + gain, no order bit) - 2 is the best available guess; flip to 4 if it
    // turns out wrong.
    public static final int SUB_FILTER_ORDER = 2;

    /**
     * BU32107 Sub LPF magnitude response (dB) at a given frequency - see SUB_FILTER_ORDER's
     * declaration for the filter model this assumes. passbandGainDb is the sub output's own gain
     * stage (0 to +12dB) applied on top of the filter's own response.
     */
    public static float subFilterResponseDb(float freqHz, float cutoffHz, int order, float passbandGainDb) {
        double ratio = freqHz / cutoffHz;
        double attenDb = 10.0 * Math.log10(1.0 + Math.pow(ratio, 2 * order));
        return (float) (passbandGainDb - attenDb);
    }

    /**
     * Front/rear "Bass Boost" hardware stage response (dB) at freqHz - see
     * McuService.applyBassBoost()'s 0x88 packet (per-channel boost freq/gain nibbles + filter
     * freq nibbles). No protocol doc for this stage (unlike the RGB control's QF_RGB_control.md),
     * so - same best-guess-but-documented spirit as SUB_FILTER_ORDER above - this models it as
     * two cascaded stages, dB-summed:
     *  - the "Filter" control (MainActivity's seekBassFilterFront/Rear, BASS_FILTER_FREQS) as a
     *    high-pass cutoff, using the same 2nd-order Butterworth-ish magnitude model as
     *    subFilterResponseDb() above but mirrored (attenuates BELOW cutoffHz instead of above it);
     *  - the "Boost" control (seekBassBoostFront/Rear's dB + the boost-freq spinner's Hz,
     *    BASS_BOOST_FREQS) as a low SHELF centered at that frequency, not a peak: it stays at
     *    (close to) the full boostGainDb for everything below boostFreqHz and rolls off above it,
     *    rather than rolling back down on both sides like the main 16-band bands do. Modeled the
     *    same low-pass-shaped way as the HPF term above (just gain-scaled instead of attenuating),
     *    with its own gentle BASS_BOOST_SHELF_ORDER = 1 (6dB/oct) - a true peaking bell was the
     *    original (wrong) guess here; a shelf is the right shape for a "bass boost" control.
     * boostFreqHz <= 0 means the boost spinner is on "off" (BASS_BOOST_FREQS[0]) - no shelf term.
     */
    public static final int BASS_BOOST_SHELF_ORDER = 1;

    public static float bassShapingResponseDb(float freqHz, float filterCutoffHz, float boostFreqHz, float boostGainDb) {
        double hpfRatio = filterCutoffHz / freqHz;
        float hpfAttenDb = (float) (10.0 * Math.log10(1.0 + Math.pow(hpfRatio, 2 * SUB_FILTER_ORDER)));
        float shelfDb;
        if (boostFreqHz > 0f) {
            double shelfRatio = freqHz / boostFreqHz;
            shelfDb = (float) (boostGainDb / (1.0 + Math.pow(shelfRatio, 2 * BASS_BOOST_SHELF_ORDER)));
        } else {
            shelfDb = 0f;
        }
        return shelfDb - hpfAttenDb;
    }

    /**
     * Fixed, non-adjustable acoustic rolloff approximating a typical mid-size (5.25"-6.5") car
     * door/dash speaker's natural low-frequency extension - independent of whatever the user has
     * dialed into bassShapingResponseDb() above, since no HPF/shelf setting changes what the
     * driver+cabin system actually does. Deliberately gentle, for a specific reason: the driver
     * itself rolls off close to 12dB/octave below its own resonance (standard infinite-baffle
     * behavior), but real in-cabin response is also shaped by "cabin gain" - the cabin
     * pressurizing as a whole below roughly 70-90Hz, adding back ~7-9dB/octave in almost that same
     * range - which cancels most of the driver's own rolloff right where it would otherwise bite.
     * This models only what's left after that cancellation (1st order/6dB-oct, corner pushed well
     * below the driver's own ~60-80Hz Fs) rather than the driver's raw anechoic response, so it
     * stays negligible through the cabin-gain-dominated range and only shows up once that
     * compensation has run out: ~-1dB at 80Hz, ~-2dB at 60Hz, ~-5dB at 30Hz, ~-8dB at 20Hz.
     */
    public static final float TYPICAL_SPEAKER_FLOOR_HZ = 40f;
    public static final int TYPICAL_SPEAKER_FLOOR_ORDER = 1;

    public static float typicalCarSpeakerFloorDb(float freqHz) {
        double ratio = TYPICAL_SPEAKER_FLOOR_HZ / freqHz;
        return (float) (-10.0 * Math.log10(1.0 + Math.pow(ratio, 2 * TYPICAL_SPEAKER_FLOOR_ORDER)));
    }

    /**
     * Maps a continuous band position to Hz - see this method's original doc above for the
     * in-range (0..NUM_BANDS-1) mapping, which is unchanged. For t outside that range, extends
     * the SAME log-per-step spacing past the nearest real edge (band 0/1's ratio below band 0,
     * band 14/15's ratio above band 15) instead of clamping to a flat frequency, so callers that
     * sample past the first/last band (see EqVisualizerView's curve padding) get a continued
     * log-frequency axis rather than every out-of-range t collapsing onto one edge frequency.
     */
    public static float frequencyAt(float t) {
        int lo, hi;
        if (t < 0f) { lo = 0; hi = 1; }
        else if (t > NUM_BANDS - 1) { lo = NUM_BANDS - 2; hi = NUM_BANDS - 1; }
        else { lo = Math.min(NUM_BANDS - 2, (int) Math.floor(t)); hi = lo + 1; }
        float loHz = BAND_CENTER_HZ[lo];
        float hiHz = BAND_CENTER_HZ[hi];
        float frac = t - lo;
        return (float) (loHz * Math.pow(hiHz / loHz, frac));
    }
}