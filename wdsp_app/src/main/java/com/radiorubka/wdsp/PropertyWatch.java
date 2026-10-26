package com.radiorubka.wdsp;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Wakes a listener when any system property changes - a subscription where Android has none.
 *
 * <p>Java has no listener for system properties, and inotify sees nothing: the values live in
 * shared memory under {@code /dev/__properties__}, changed in place, not written as files.
 * bionic's {@code __system_property_wait} sleeps on a futex over the area's global serial, which
 * every property set bumps, and needs no root.
 *
 * <p>The futex wakes on every property set in the system, the same value rewritten included - so
 * each wake re-reads only the keys asked for (plain property reads, no binder) and the listener
 * hears only when one of those specific values actually moved. Called on this watch's own thread.
 */
final class PropertyWatch {

    private static final String TAG = "wDSP_PropertyWatch";
    /** Wakes this often even when nothing changes, only to notice {@link #stop}. */
    private static final int WAIT_SLICE_MS = 5000;

    private static final boolean available;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("wdsp_native");
            nativeSerial();
            loaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "property wait unavailable: " + t);
        }
        available = loaded;
    }

    private static native long nativeSerial();
    private static native long nativeWait(long oldSerial, int timeoutMs);

    private volatile boolean running;
    private Thread thread;

    /** @return the running watch, or null when the native wait isn't available on this unit. */
    static PropertyWatch start(String[] keys, Runnable onChange) {
        if (!available) return null;
        PropertyWatch watch = new PropertyWatch();
        watch.running = true;
        watch.thread = new Thread(() -> watch.loop(keys, onChange), "wDSP-property-watch");
        watch.thread.setDaemon(true);
        watch.thread.start();
        return watch;
    }

    boolean isRunning() {
        return running;
    }

    void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    // android.os.SystemProperties is a hidden (non-SDK) class - reflection is the standard way
    // an app reads a raw property by name, same pattern already used for mcu_service/IMcuManager
    // access elsewhere in this app.
    private static Method getMethod;

    private static String systemProperty(String key) {
        try {
            if (getMethod == null) {
                getMethod = Class.forName("android.os.SystemProperties")
                        .getMethod("get", String.class, String.class);
            }
            return (String) getMethod.invoke(null, key, "");
        } catch (Throwable t) {
            return "";
        }
    }

    private static String[] read(String[] keys) {
        String[] values = new String[keys.length];
        for (int i = 0; i < keys.length; i++) values[i] = systemProperty(keys[i]);
        return values;
    }

    private void loop(String[] keys, Runnable onChange) {
        long serial = nativeSerial();
        String[] last = read(keys);
        try {
            while (running) {
                long next = nativeWait(serial, WAIT_SLICE_MS);
                if (next >= 0) {
                    serial = next;
                    String[] values = read(keys);
                    if (!Arrays.equals(values, last)) {
                        last = values;
                        onChange.run();
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "property watch died; levels set elsewhere may not be noticed", t);
        } finally {
            running = false;
        }
    }
}
