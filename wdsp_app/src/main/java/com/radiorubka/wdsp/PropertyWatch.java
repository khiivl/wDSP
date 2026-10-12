package com.radiorubka.wdsp;

import android.util.Log;

/**
 * Wakes a listener when any system property changes - a subscription where Android has none.
 *
 * <p>Java has no listener for system properties, and inotify sees nothing: the values live in the
 * shared memory under {@code /dev/__properties__}, changed in place, not written as files
 * (📻 checked as root 12.10.2026 - {@code sys.call.vol} 10 → 9 → 10 raised no event; only the
 * {@code persist.*} file did). bionic's {@code __system_property_wait} sleeps on a futex over the
 * area's global serial, which every property set bumps, and needs no root.
 *
 * <p>It wakes on every property in the system, so the listener must be cheap and must not mind
 * being called for changes that are not its own; it is called on this watch's own thread.
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
    static PropertyWatch start(Runnable onChange) {
        if (!available) return null;
        PropertyWatch watch = new PropertyWatch();
        watch.running = true;
        watch.thread = new Thread(() -> watch.loop(onChange), "wDSP-property-watch");
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

    private void loop(Runnable onChange) {
        long serial = nativeSerial();
        long wakes = 0;
        long since = System.currentTimeMillis();
        try {
            while (running) {
                long next = nativeWait(serial, WAIT_SLICE_MS);
                if (next >= 0) {
                    serial = next;
                    wakes++;
                    onChange.run();
                }
                long now = System.currentTimeMillis();
                if (now - since >= RATE_LOG_MS) {
                    Log.i(TAG, wakes + " property changes in the last " + (now - since) / 1000 + " s");
                    wakes = 0;
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
