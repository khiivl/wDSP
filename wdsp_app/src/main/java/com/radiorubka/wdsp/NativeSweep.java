package com.radiorubka.wdsp;

import android.util.Log;

/**
 * Java side of the sweep measurement in {@code cpp/sweep.cpp}.
 *
 * One instance holds one sweep and the inverse filter that goes with it, so the same object is
 * used to play every channel and to analyse every recording - which matters, because two
 * measurements made with different sweeps are not comparable.
 */
public final class NativeSweep implements AutoCloseable {
    private static final String TAG = "wDSP_NativeSweep";

    /** Index of the arrival, in samples from the start of the impulse response. */
    public static final int ARRIVAL = 0;
    /** How far the arrival stood above the rest of the response. Below ten is not a loudspeaker. */
    public static final int PROMINENCE = 1;
    /** +1 or -1; negative means the loudspeaker is wired the wrong way round. */
    public static final int POLARITY = 2;
    /**
      * How far the direct sound stands above the room answering it, in decibels.
      *
      * Above about 8 dB the arrival is a real one. Near zero the microphone never heard the
      * speaker directly, only the cabin, and the arrival time is repeatable without being true.
      */
     public static final int CLARITY = 3;
     /** Sixteen band levels in dB start here, on the hardware equaliser's grid. */
     public static final int BANDS = 4;
    public static final int BAND_COUNT = 16;
    /** Sixteen band levels of deconvolved noise floor in dB start here. */
    public static final int NOISE_BANDS = BANDS + BAND_COUNT;
    public static final int RESULT_SIZE = NOISE_BANDS + BAND_COUNT;

    private static boolean available;

    static {
        try {
            System.loadLibrary("wdsp_native");
            available = true;
        } catch (Throwable t) {
            Log.e(TAG, "native library missing, room measurement is unavailable", t);
            available = false;
        }
    }

    public static boolean isAvailable() {
        return available;
    }

    private long handle;

    public NativeSweep(int sampleRate, float startHz, float endHz, float seconds) {
        handle = available ? nativeCreate(sampleRate, startHz, endHz, seconds) : 0;
    }

    public boolean isValid() {
        return handle != 0;
    }

    /** Length of the sweep in samples. */
    public int length() {
        return handle == 0 ? 0 : nativeLength(handle);
    }

    /** Fills the buffer with the sweep at the given amplitude. The buffer must be long enough. */
    public void generate(float[] out, float amplitude) {
        if (handle != 0) nativeGenerate(handle, out, amplitude);
    }

    /**
     * Deconvolves one recording and fills {@code result} with {@link #RESULT_SIZE} numbers.
     *
     * @return false when the recording was too short, or held nothing that looks like the sweep
     */
    public boolean analyse(float[] recorded, int length, float[] result) {
        return handle != 0 && nativeAnalyse(handle, recorded, length, result);
    }

    /**
     * How much of a recording sits above 8 kHz, relative to the band below, in decibels.
     *
     * The one reliable way to catch a microphone that is really running at 16 kHz: the platform
     * reports the rate that was requested regardless, so only the content itself tells the truth.
     *
     * ⚠️ Call {@link #isAvailable()} first, and do not record if it says no. Without the native
     * library this returns 0 dB - a number that sails past every threshold the callers compare
     * against, so "there is no analyser" would read as "the microphone is fine". Both callers check
     * first for exactly that reason; a third one must too.
     */
    public static float bandwidthRatioDb(float[] recorded, int length, int sampleRate) {
        return isAvailable() ? nativeBandwidth(recorded, length, sampleRate) : 0f;
    }

    /**
     * Calculates the 16-band energy spectrum (in dB) of an ambient noise signal slice.
     */
    public void noiseFloor(float[] signal, int length, float[] out16) {
        if (handle != 0 && out16 != null && out16.length >= BAND_COUNT) {
            nativeNoiseFloor(handle, signal, length, out16);
        }
    }

    /**
     * Performs spectral subtraction band-by-band:
     *   clean_power = max(sweep_power - noise_power, 1e-12)
     *   snr_db = sweep_db - noise_db
     */
    public static void subtractNoise(float[] sweepDb16, float[] noiseDb16,
                                     float[] outCleanDb16, float[] outSnrDb16) {
        if (isAvailable() && sweepDb16 != null && noiseDb16 != null) {
            nativeSubtractNoise(sweepDb16, noiseDb16, outCleanDb16, outSnrDb16);
        }
    }

    /**
     * Estimates the 16-band microphone inverse compensation curve.
     *
     * <p>{@code envelope16} is a SHAPE, not a level: the best channel in each band, each channel
     * measured against its own midrange. The low bands are read against the cabin gain a sealed
     * car should be producing, and the shortfall - bounded by what the microphone input's own
     * two-stage high-pass can do - is the path's attenuation. The high bands are read against
     * 5 kHz.
     *
     * <p>{@code mountingDb16} is what the microphone's mounting is known to do, from
     * {@link MicProfile#mountingCurve}. It seeds the curve, which is the only thing that speaks
     * for the midband, since a sweep takes the midband as its own reference and can say nothing
     * about it - and it is a starting point, not a verdict: the estimate keeps only what the
     * measurement confirms.
     *
     * <p>{@code snr16} gates it: bands measured close to the noise are corrected proportionally
     * less and bands below the ramp's floor not at all, so a unit whose bottom really does sink
     * under the converter's noise gets zeros from its own measurement rather than from a
     * hard-coded band index. May be null, which leaves the estimate ungated.
     *
     * <p>{@code worstEnvelope16} is the same shape taken from the WORST channel per band, and it
     * is what confirms the mounting table's cuts: a peak in one channel is the room, a peak in
     * all of them is the capsule. May be null, which leaves those figures unchecked.
     *
     * <p>{@code outStatus16} says what happened to each band - {@link #MIC_BAND_MEASURED},
     * {@link #MIC_BAND_UNKNOWN}, {@link #MIC_BAND_TRIMMED} - so the report can print a refusal as
     * a refusal instead of letting it pass for a measurement. May be null.
     */
    public static void estimateMicCompensation(float[] envelope16, float[] worstEnvelope16,
                                               float[] snr16,
                                               float[] mountingDb16, float[] outCompensation16,
                                               int[] outStatus16) {
        if (isAvailable() && envelope16 != null && outCompensation16 != null) {
            nativeEstimateMicCompensation(envelope16, worstEnvelope16, snr16, mountingDb16,
                    outCompensation16, outStatus16);
        }
    }

    /** The band's compensation is what the calibration measured. */
    public static final int MIC_BAND_MEASURED = 0;
    /**
     * The estimate ran into the bound of what the microphone INPUT could plausibly be doing, so
     * the measured part was left out entirely: the microphone's share of that band is unknown,
     * and the curve carries only what the mounting is known to do.
     */
    public static final int MIC_BAND_UNKNOWN = 1;
    /**
     * The mounting table wanted more treble boost than the sweep saw in every channel, so it was
     * cut back to the part all channels confirm.
     */
    public static final int MIC_BAND_TRIMMED = 2;

    /**
     * Deconvolves one recording into its impulse response.
     * Returns the impulse response length. If outImpulse is non-null, copies into outImpulse.
     */
    public int deconvolve(float[] recorded, int length, float[] outImpulse) {
        return handle != 0 ? nativeDeconvolve(handle, recorded, length, outImpulse) : 0;
    }

    /**
     * Estimates the time difference of arrival (TDOA) in fractional samples between a channel
     * impulse response and a reference channel impulse response using GCC-PHAT.
     */
    public static float gccPhatDelay(float[] hRef, int refLen, float[] hCh, int chLen,
                                     float[] outProminence1) {
        return isAvailable() ? nativeGccPhatDelay(hRef, refLen, hCh, chLen, outProminence1) : 0f;
    }

    /**
     * Detects midbass roll-off index (into kBassFilterFreqs: 0..11) from 16-band clean response.
     */
    public static int detectMidbassRollOff(float[] avgClean16) {
        return isAvailable() && avgClean16 != null ? nativeDetectMidbassRollOff(avgClean16) : 5;
    }

    public static final int TARGET_HARMAN = 0;
    public static final int TARGET_DOLBY_ATMOS = 1;
    public static final int TARGET_BASS_HEAVY = 2;
    public static final int TARGET_VOCAL_SPEECH = 3;
    public static final int TARGET_FLAT_STUDIO = 4;

    /**
     * Synthesizes 16-band Auto-EQ gains and subwoofer settings matching the chosen TargetCurve.
     */
    public static void synthesizeAutoEq16(float[] avgClean16, float[] micComp16, float[] snr16,
                                          int hpfCutoffIdx, boolean hasSub, int targetCurveType,
                                          int[] outGains16, int[] outSubSettings2) {
        if (isAvailable() && avgClean16 != null && outGains16 != null) {
            nativeSynthesizeAutoEq16(avgClean16, micComp16, snr16, hpfCutoffIdx, hasSub,
                    targetCurveType, outGains16, outSubSettings2);
        }
    }

    /**
     * Synthesizes 16-band Harman Auto-EQ gains and subwoofer settings.
     */
    public static void synthesizeHarmanEq16(float[] avgClean16, float[] micComp16, float[] snr16,
                                            int hpfCutoffIdx, boolean hasSub,
                                            int[] outGains16, int[] outSubSettings2) {
        synthesizeAutoEq16(avgClean16, micComp16, snr16, hpfCutoffIdx, hasSub, TARGET_HARMAN,
                outGains16, outSubSettings2);
    }

    @Override
    public void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native long nativeCreate(int sampleRate, float startHz, float endHz,
                                            float seconds);

    private static native void nativeDestroy(long handle);

    private static native int nativeLength(long handle);

    private static native void nativeGenerate(long handle, float[] out, float amplitude);

    private static native boolean nativeAnalyse(long handle, float[] recorded, int length,
                                                float[] result);

    private static native float nativeBandwidth(float[] recorded, int length, int sampleRate);

    private static native void nativeNoiseFloor(long handle, float[] signal, int length,
                                                float[] out16);

    private static native void nativeSubtractNoise(float[] sweepDb16, float[] noiseDb16,
                                                   float[] outCleanDb16, float[] outSnrDb16);

    private static native void nativeEstimateMicCompensation(float[] envelope16,
                                                            float[] worstEnvelope16,
                                                            float[] snr16,
                                                            float[] mountingDb16,
                                                            float[] outCompensation16,
                                                            int[] outStatus16);

    private static native int nativeDeconvolve(long handle, float[] recorded, int length,
                                               float[] outImpulse);

    private static native float nativeGccPhatDelay(float[] hRef, int refLen, float[] hCh,
                                                   int chLen, float[] outProminence1);

    private static native int nativeDetectMidbassRollOff(float[] avgClean16);

    private static native void nativeSynthesizeAutoEq16(float[] avgClean16, float[] micComp16,
                                                        float[] snr16,
                                                        int hpfCutoffIdx, boolean hasSub, int targetCurveType,
                                                        int[] outGains16, int[] outSubSettings2);

    private static native void nativeSynthesizeHarmanEq16(float[] avgClean16, float[] micComp16,
                                                          float[] snr16,
                                                          int hpfCutoffIdx, boolean hasSub,
                                                          int[] outGains16, int[] outSubSettings2);
}

