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
     * Estimates the 16-band microphone inverse compensation curve from 4-channel average clean response
     * using the Cabin Gain Anchor (+12 dB/oct below 80 Hz) and high-frequency acoustic port roll-off correction.
     */
    public static void estimateMicCompensation(float[] avgClean16, float[] outCompensation16) {
        if (isAvailable() && avgClean16 != null && outCompensation16 != null) {
            nativeEstimateMicCompensation(avgClean16, outCompensation16);
        }
    }

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

    /**
     * Synthesizes 16-band Harman Auto-EQ gains and subwoofer settings.
     */
    public static void synthesizeHarmanEq16(float[] avgClean16, float[] micComp16,
                                            int hpfCutoffIdx, boolean hasSub,
                                            int[] outGains16, int[] outSubSettings2) {
        if (isAvailable() && avgClean16 != null && outGains16 != null) {
            nativeSynthesizeHarmanEq16(avgClean16, micComp16, hpfCutoffIdx, hasSub,
                    outGains16, outSubSettings2);
        }
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

    private static native void nativeEstimateMicCompensation(float[] avgClean16,
                                                            float[] outCompensation16);

    private static native int nativeDeconvolve(long handle, float[] recorded, int length,
                                               float[] outImpulse);

    private static native float nativeGccPhatDelay(float[] hRef, int refLen, float[] hCh,
                                                   int chLen, float[] outProminence1);

    private static native int nativeDetectMidbassRollOff(float[] avgClean16);

    private static native void nativeSynthesizeHarmanEq16(float[] avgClean16, float[] micComp16,
                                                         int hpfCutoffIdx, boolean hasSub,
                                                         int[] outGains16, int[] outSubSettings2);
}

