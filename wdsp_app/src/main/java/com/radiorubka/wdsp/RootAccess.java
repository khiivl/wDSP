package com.radiorubka.wdsp;

import android.content.Context;
import android.util.Log;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

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
public final class RootAccess {

    private static final String TAG = "wDSP_RootAccess";
    public static final String PREF_ROOT_GRANTED = "pref_root_granted";

    /**
     * Below this, nobody read anything. A Magisk prompt involves a human finding the dialog,
     * reading it and pressing a button; even the fastest of those is far more than half a second.
     */
    private static final long DECIDED_WITHOUT_ASKING_MS = 500;

    /** Long enough for somebody to notice the dialog and answer it, not so long the app looks hung. */
    private static final long WAIT_SECONDS = 30;

    private static volatile Boolean sRootGranted = null;
    /** When root was last confirmed in this process; see {@link #checkAsync}. */
    private static volatile long sVerifiedAt = 0L;
    /**
     * How long a confirmed grant is taken on trust before "su" is asked again. Every "su" makes
     * Magisk toast "granted", and the main screen re-checked on every resume - the owner saw the
     * toast on each return (11.09.2026). A revocation is still noticed within this time.
     */
    private static final long REVERIFY_MS = 10 * 60 * 1000L;
    private static final Object sLock = new Object();
    private static volatile boolean sRequestInProgress = false;

    public enum Outcome {
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
     * Fast, non-blocking check whether root is already verified and available.
     * Returns true only if verified in this process session.
     * If not yet verified but was previously recorded in preferences, initiates background verification.
     */
    public static boolean hasRoot(Context context) {
        if (sRootGranted != null) return sRootGranted;
        if (context != null) {
            boolean stored = ThemeManager.prefs(context).getBoolean(PREF_ROOT_GRANTED, false);
            if (!stored) {
                sRootGranted = false;
                return false;
            }
            // Stored pref was true, but we haven't verified in this session yet.
            // Start async check to verify it has not been revoked in Magisk.
            checkAsync(context, null);
        }
        return false;
    }

    /**
     * Like {@link #hasRoot}, but for a decision that cannot be taken back: a grant recorded before
     * and not yet re-verified in this process is verified now, blocking for up to 1.5 s, instead of
     * reading as "no root".
     *
     * <p>hasRoot() answers false in a fresh process until its background check returns. The
     * microphone uses the answer to choose between healing the input and declaring the microphone
     * unavailable until a restart - so right after boot, on a rooted unit, the quick answer would
     * switch the spectrum off for the whole drive. Never raises a Magisk prompt: an app Magisk has
     * no answer for reads false, as before. Call off the main thread.
     */
    public static boolean hasRootNow(Context context) {
        if (sRootGranted != null) return sRootGranted;
        if (context == null
                || !ThemeManager.prefs(context).getBoolean(PREF_ROOT_GRANTED, false)) {
            return false;
        }
        boolean granted = alreadyGranted();
        sRootGranted = granted;
        if (granted) sVerifiedAt = System.currentTimeMillis();
        ThemeManager.prefs(context).edit().putBoolean(PREF_ROOT_GRANTED, granted).apply();
        return granted;
    }

    /**
     * Checks in background if root is available and updates cache and preferences.
     * Always re-verifies via alreadyGranted() so that root revocation in Magisk is detected.
     */
    public static void checkAsync(Context context, Runnable onFinished) {
        // "su" from an app Magisk has no answer for raises Magisk's prompt - on a fresh install
        // that was a root dialog on the very first start, over the permissions wizard (measured
        // 11.09.2026; MainActivity.onResume called this unconditionally). So only a grant this
        // app has seen before is re-verified here; the first ask belongs to the wizard's root card,
        // which a person taps.
        boolean known = context != null
                && ThemeManager.prefs(context).getBoolean(PREF_ROOT_GRANTED, false);
        if (!known) {
            sRootGranted = false;
            if (onFinished != null) {
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(onFinished);
                } else {
                    onFinished.run();
                }
            }
            return;
        }
        if (Boolean.TRUE.equals(sRootGranted)
                && System.currentTimeMillis() - sVerifiedAt < REVERIFY_MS) {
            if (onFinished != null) {
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(onFinished);
                } else {
                    onFinished.run();
                }
            }
            return;
        }
        new Thread(() -> {
            boolean granted = alreadyGranted();
            sRootGranted = granted;
            if (granted) sVerifiedAt = System.currentTimeMillis();
            if (context != null) {
                ThemeManager.prefs(context).edit().putBoolean(PREF_ROOT_GRANTED, granted).apply();
                if (granted) {
                    MicrophoneGuard.repairAssistantMicOnce(context.getApplicationContext());
                }
            }
            if (onFinished != null) {
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).runOnUiThread(onFinished);
                } else {
                    onFinished.run();
                }
            }
        }, "wDSP_RootCheckAsync").start();
    }

    /**
     * Runs the smallest possible command as root, so that Magisk shows its prompt.
     *
     * <p>Blocks, so call it off the main thread. {@code id} is used deliberately: it changes
     * nothing, costs nothing, and if an owner later looks at Magisk's log the first thing this app
     * ever asked to do was ask who it was.
     */
    public static Outcome request() {
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

    /**
     * Thread-safe single-flight root request with context update and background appops unhooking.
     */
    public static Outcome request(Context context) {
        synchronized (sLock) {
            if (sRootGranted != null && sRootGranted) return Outcome.GRANTED;
            if (sRequestInProgress) return Outcome.TIMED_OUT;
            sRequestInProgress = true;
        }
        try {
            Outcome outcome = request();
            boolean granted = (outcome == Outcome.GRANTED);
            sRootGranted = granted;
            if (context != null) {
                ThemeManager.prefs(context).edit().putBoolean(PREF_ROOT_GRANTED, granted).apply();
            }
            if (granted && context != null) {
                MicrophoneGuard.repairAssistantMicOnce(context.getApplicationContext());
            }
            return outcome;
        } finally {
            synchronized (sLock) {
                sRequestInProgress = false;
            }
        }
    }

    /** Whether root has already been granted, without raising a prompt if it has not. */
    public static boolean alreadyGranted() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            if (!p.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // A prompt is up or ignored. It was not "already" granted!
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
