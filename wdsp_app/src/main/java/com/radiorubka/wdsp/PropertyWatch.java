package com.radiorubka.wdsp;

import android.util.Log;

import java.util.Arrays;

/**
 * Wakes a listener when any system property changes - a subscription where Android has none.
 *
 * <p>Java has no listener for system properties, and inotify sees nothing: the values live in the
 * shared memory under {@code /dev/__properties__}, changed in place, not written as files
 * (📻 checked as root 12.10.2026 - {@code sys.call.vol} 10 → 9 → 10 raised no event; only the
 * {@code persist.*} file did). bionic's {@code __system_property_wait} sleeps on a futex over the
 * area's global serial, which every property set bumps, and needs no root.
 *
 * <p>The futex wakes on every property set in the system, the same value rewritten included:
 * 📻 473 wakes in 10 minutes on the bench (12.10.2026, ~0.8/s), with no value changing over a
 * 10-second diff of the whole list. So each wake re-reads only the keys asked for - plain
 * property reads, no binder - and the listener hears only when one of their values moved. It is
 * called on this watch's own thread.
 */
final class PropertyWatch {

    private static final String TAG = "wDSP_PropertyWatch";
    /** Wakes this often even when nothing changes, only to notice {@link #stop}. */
    private static final int WAIT_SLICE_MS = 5000;
    private static final long RATE_LOG_MS = 10 * 60 * 1000L;

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

    /** @return the running watch, or null when the native wait cannot be had on this unit. */
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

    private static String[] read(String[] keys) {
        String[] values = new String[keys.length];
        for (int i = 0; i < keys.length; i++) values[i] = HardwareProfile.systemProperty(keys[i]);
        return values;
    }

    private void loop(String[] keys, Runnable onChange) {
        long serial = nativeSerial();
        String[] last = read(keys);
        long wakes = 0;
        long changes = 0;
        long since = System.currentTimeMillis();
        try {
            while (running) {
                long next = nativeWait(serial, WAIT_SLICE_MS);
                if (next >= 0) {
                    serial = next;
                    wakes++;
                    String[] values = read(keys);
                    if (!Arrays.equals(values, last)) {
                        last = values;
                        changes++;
                        onChange.run();
                    }
                }
                long now = System.currentTimeMillis();
                if (now - since >= RATE_LOG_MS) {
                    Log.i(TAG, wakes + " property sets, " + changes + " of them ours, in the last "
                            + (now - since) / 1000 + " s");
                    wakes = 0;
                    changes = 0;
                    since = now;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "property watch died; levels set elsewhere will be overwritten", t);
        } finally {
            running = false;
        }
    }
}
