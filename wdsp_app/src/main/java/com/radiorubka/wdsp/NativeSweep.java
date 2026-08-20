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
    /** Sixteen band levels in dB start here, on the hardware equaliser's grid. */
    public static final int BANDS = 3;
    public static final int BAND_COUNT = 16;
    public static final int RESULT_SIZE = BANDS + BAND_COUNT;

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
}
