package com.radiorubka.wdsp;

import android.util.Log;

/**
 * Asks Magisk for root out loud, instead of finding out in silence that it was never coming.
 *
 * <h2>Why this exists as its own thing</h2>
 *
 * The cabin measurement needs root for exactly one action: stopping the background process that
 * holds the microphone open at 16 kHz. Without it the sweep is recorded through a stream that
 * throws away everything above 8 kHz, and nothing in the audio API admits to it -
 * {@code AudioRecord.getSampleRate()} cheerfully answers 48000 either way.
 *
 * <p>The trap is in how a refusal arrives. A {@code su} call from an app that has never been
 * granted is <b>refused silently</b>: on some units Magisk's default policy for a new uid is deny
 * rather than prompt, so the process returns at once, with nothing on screen and nothing in the
 * log that a person would see. Every failure looks identical to every other failure, and the owner
 * is left believing the app simply does not work.
 *
 * <p>So this measures how long the request took. A prompt a human had to read and tap cannot come
 * back in a few milliseconds; a policy that says no always does. That difference is the only
 * signal available, and it is enough to tell an owner which of the two happened - and, when it is
 * the second, that the answer is in Magisk's own settings and not in this app.
 */
final class RootAccess {

    private static final String TAG = "wDSP_RootAccess";

    /**
     * Below this, nobody read anything. A Magisk prompt involves a human finding the dialog,
     * reading it and pressing a button; even the fastest of those is far more than half a second.
     */
    private static final long DECIDED_WITHOUT_ASKING_MS = 500;

    /** Long enough for somebody to notice the dialog and answer it, not so long the app looks hung. */
    private static final long WAIT_SECONDS = 30;

    enum Outcome {
        /** Root is ours. */
        GRANTED,
        /** A prompt appeared and the owner said no. */
        REFUSED,
        /** Answered before any human could have: Magisk is set to deny this app without asking. */
        DENIED_BY_POLICY,
        /** No {@code su} at all - the unit is not rooted. */
        NOT_ROOTED,
        /** The prompt was never answered. */
        TIMED_OUT,
    }

    private RootAccess() {
    }

    /**
     * Runs the smallest possible command as root, so that Magisk shows its prompt.
     *
     * <p>Blocks, so call it off the main thread. {@code id} is used deliberately: it changes
     * nothing, costs nothing, and if an owner later looks at Magisk's log the first thing this app
     * ever asked to do was ask who it was.
     */
    static Outcome request() {
        long started = System.currentTimeMillis();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            boolean finished = p.waitFor(WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            long took = System.currentTimeMillis() - started;
            if (!finished) {
                p.destroy();
                Log.w(TAG, "root prompt was never answered");
                return Outcome.TIMED_OUT;
            }
            if (p.exitValue() == 0) {
                Log.i(TAG, "root granted in " + took + " ms");
                return Outcome.GRANTED;
            }
            if (took < DECIDED_WITHOUT_ASKING_MS) {
                Log.w(TAG, "root refused in " + took + " ms - too fast for a prompt, so Magisk is "
                        + "set to deny this app without asking");
                return Outcome.DENIED_BY_POLICY;
            }
            Log.i(TAG, "root refused by the owner after " + took + " ms");
            return Outcome.REFUSED;
        } catch (Throwable t) {
            // No su binary, or the exec itself was rejected. Either way there is nothing to grant.
            Log.i(TAG, "no root on this unit: " + t);
            return Outcome.NOT_ROOTED;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Whether root has already been granted, without raising a prompt if it has not. */
    static boolean alreadyGranted() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                // A prompt is up. It was not "already" granted, and the caller should ask properly
                // rather than leave a dialog hanging behind whatever it does next.
                p.destroy();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
