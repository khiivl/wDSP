package com.radiorubka.wdsp;

import android.util.Log;

/**
 * Java side of the native spectrum analyser.
 *
 * Everything measurement-related lives in C++: stitching the polled Visualizer blocks back into a
 * continuous stream, the windowed transforms, the third-octave band maths, ballistics, the
 * playback-latency delay and the automatic gain. Java only feeds it blocks and reads levels out.
 *
 * Consumers are numbered because the two visualizers want different behaviour from the automatic
 * gain: the one in the main screen is an instrument and has to tell the truth, while the status bar
 * widget is decoration and may flatter quiet music. Each keeps its own gain state.
 */
public final class NativeAnalyzer {
    private static final String TAG = "wDSP_NativeAnalyzer";

    public static final int BANDS_32 = 32;
    public static final int BANDS_16 = 16;

    public static final int CONSUMER_MAIN = 0;
    public static final int CONSUMER_STATUS_BAR = 1;

    private static boolean available;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("wdsp_native");
            loaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "Native analyser unavailable, falling back to the Java path: " + t);
        }
        available = loaded;
    }

    /** False when the shared library could not be loaded; callers must keep the Java path alive. */
    public static boolean isAvailable() {
        return available;
    }

    private long handle;

    public NativeAnalyzer(int sampleRate, int captureSize) {
        if (available) {
            handle = nativeCreate(sampleRate, captureSize);
        }
    }

    public boolean isValid() {
        return handle != 0;
    }

    public void release() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    /** Feeds one polled block of unsigned 8-bit samples. Returns how many samples were new. */
    public int push(byte[] waveform, int length) {
        return handle == 0 ? 0 : nativePush(handle, waveform, length);
    }

    /**
     * Runs analysis for whatever has been captured, blocking until there is enough or the timeout
     * expires. Call from a thread of its own: keeping it off the capture thread means a transform
     * can never delay a poll, and a late poll is how the stitcher loses its place.
     */
    public void process(int timeoutMs) {
        if (handle != 0) nativeProcess(handle, timeoutMs);
    }

    /** Unblocks process() so its thread can exit. */
    public void stop() {
        if (handle != 0) nativeStop(handle);
    }

    /** Samples between frames: 512 gives 94 frames a second at 48 kHz, 1024 gives 47. */
    public void setHop(int hop) {
        if (handle != 0) nativeSetHop(handle, hop);
    }

    public void setConfig(float attackMs, float releaseMs, float latencyMs,
                          float refMaxDb, float rangeDb) {
        if (handle != 0) nativeSetConfig(handle, attackMs, releaseMs, latencyMs, refMaxDb, rangeDb);
    }

    public void setAgc(int consumer, boolean enabled, float strength, float minRefDb) {
        if (handle != 0) nativeSetAgc(handle, consumer, enabled, strength, minRefDb);
    }

    /** The response the hardware DSP adds, on the 16 hardware bands. See DspResponse. */
    public void setDspCurve(float[] curve16) {
        if (handle != 0) nativeSetDspCurve(handle, curve16);
    }

    /** Display levels, 0..1, for one consumer. out16 may be null. */
    public void getLevels(int consumer, float[] out32, float[] out16) {
        if (handle != 0) nativeGetLevels(handle, consumer, out32, out16);
    }

    /** Raw band levels in dB, before normalisation - used by the diagnostics dump. */
    public void getLevelsDb(float[] out32) {
        if (handle != 0) nativeGetLevelsDb(handle, out32);
    }

    /** Blocks that could not be aligned. A rising count means the poll rate is too low. */
    public int discontinuities() {
        return handle == 0 ? 0 : nativeDiscontinuities(handle);
    }

    public int frames() {
        return handle == 0 ? 0 : nativeFrames(handle);
    }

    private static native long nativeCreate(int sampleRate, int captureSize);

    private static native void nativeDestroy(long handle);

    private static native int nativePush(long handle, byte[] block, int len);

    private static native void nativeProcess(long handle, int timeoutMs);

    private static native void nativeStop(long handle);

    private static native void nativeSetHop(long handle, int hop);

    private static native void nativeSetConfig(long handle, float attackMs, float releaseMs,
                                               float latencyMs, float refMaxDb, float rangeDb);

    private static native void nativeSetAgc(long handle, int consumer, boolean enabled,
                                            float strength, float minRefDb);

    private static native void nativeSetDspCurve(long handle, float[] curve16);

    private static native void nativeGetLevels(long handle, int consumer,
                                               float[] out32, float[] out16);

    private static native void nativeGetLevelsDb(long handle, float[] out32);

    private static native int nativeDiscontinuities(long handle);

    private static native int nativeFrames(long handle);
}
