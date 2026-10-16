package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
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
 *
 * <h2>The one place that knows whether we have root</h2>
 *
 * 🔴 Owner, 14.09.2026: root lives in Magisk and can change at any moment, so the answer is not kept
 * in a preference - until then {@code pref_root_granted} was the answer, and a grant given back in
 * Magisk stayed invisible ("no root" at every boot) until somebody tapped the root card. The answer
 * now lives in this process only ({@link #hasRoot()}, which never runs {@code su}), and is asked for at
 * three moments and no others:
 * <ul>
 *   <li>the process starts - boot, or the first start after an update ({@link #checkAtStart});</li>
 *   <li>a person switches the microphone on for the first time in this process - the spectrum's
 *   microphone mode, a measurement ({@link #checkForMicrophone});</li>
 *   <li>a person taps the root card ({@link #request(Context)}).</li>
 * </ul>
 * Never polled: every {@code su} makes Magisk toast, and it used to be asked on every return to a
 * screen (the owner saw the toast each time, 11.09.2026).
 *
 * <p>The start check runs only when Magisk has answered this installation before
 * ({@link #PREF_MAGISK_ANSWERED} - that Magisk was asked, not what it said). An app Magisk has no
 * policy for gets a prompt on {@code su}, and that prompt has to come from something a person did
 * (owner, 15.09.2026), not appear over the permissions wizard at first start as it did on 11.09.2026.
 */
public final class RootAccess {

    private static final String TAG = "wDSP_RootAccess";

    /**
     * Describes this unit and this installation, not the owner's settings: a backup does not carry
     * it, and a restore does not wipe it (a restore clears the default preferences) - Magisk's
     * answer belongs to the unit the backup came from.
     */
    private static final String DEVICE_STATE_PREFS = "wdsp_device_state";
    /**
     * Magisk has answered a {@code su} from this installation, granted or refused. Not the answer
     * itself - that can change in Magisk at any moment and is asked for again at the next start.
     * Goes with the app's data, which is also when Magisk forgets an uninstalled app.
     */
    private static final String PREF_MAGISK_ANSWERED = "magisk_answered";
    /**
     * Until 15.09.2026 the answer itself was stored here. It was written only after a {@code su} had
     * run, so its presence says Magisk was asked; its value is stale and is not read.
     */
    private static final String LEGACY_PREF_ROOT_GRANTED = "pref_root_granted";

    /**
     * Below this, nobody read anything. A Magisk prompt involves a human finding the dialog,
     * reading it and pressing a button; even the fastest of those is far more than half a second.
     */
    private static final long DECIDED_WITHOUT_ASKING_MS = 500;

    /** Long enough for somebody to notice the dialog and answer it, not so long the app looks hung. */
    private static final long PROMPT_WAIT_MS = 30_000;

    /**
     * The start check runs only for an installation Magisk has a policy for, so it is answered
     * without a prompt - "Magisk спрацьовує швидко" (owner). Slower than this is not an answer to
     * start a drive on.
     */
    private static final long START_WAIT_MS = 3_000;

    /** The answer in this process; null until Magisk has been asked. */
    private static volatile Boolean sGranted = null;
    /** Held while the start or the microphone check runs, so a second caller waits for the answer. */
    private static final Object sCheckLock = new Object();
    private static boolean sStartChecked;
    private static boolean sMicrophoneChecked;
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

    /** Whether this process has root, as Magisk last answered. Never runs {@code su}, never blocks. */
    public static boolean hasRoot() {
        return Boolean.TRUE.equals(sGranted);
    }

    /**
     * The start check: once per process, and only when Magisk has answered this installation before.
     *
     * <p>Blocks for up to {@link #START_WAIT_MS}, so call it off the main thread. The service and the
     * spectrum engine both call it as they start; whoever comes second waits for the first one's
     * answer, so a start costs one {@code su} and one toast.
     */
    public static boolean checkAtStart(Context context) {
        synchronized (sCheckLock) {
            if (sStartChecked) return hasRoot();
            sStartChecked = true;
            if (context == null || !magiskAnswered(context)) {
                Log.i(TAG, "start: Magisk has not answered this installation - root is not asked for "
                        + "until a person does something that needs it");
                return hasRoot();
            }
            record(context, ask(START_WAIT_MS), "start");
            return hasRoot();
        }
    }

    /**
     * The first time in this process that a person switches the microphone on: asks Magisk, and may
     * raise its prompt - that is the point of asking here, on an action, rather than at start.
     *
     * <p>Once per process; later calls return the answer without {@code su}. Blocks while a prompt
     * is up (up to {@link #PROMPT_WAIT_MS}), so call it off the main thread.
     */
    public static boolean checkForMicrophone(Context context) {
        synchronized (sCheckLock) {
            if (sMicrophoneChecked) return hasRoot();
            sMicrophoneChecked = true;
            record(context, ask(PROMPT_WAIT_MS), "first microphone use");
            return hasRoot();
        }
    }

    /**
     * The root card: a person asked for root, so Magisk is asked whatever the last answer was, and
     * its prompt may appear. Single-flight. Blocks, so call it off the main thread.
     */
    public static Outcome request(Context context) {
        synchronized (sLock) {
            if (sRequestInProgress) return Outcome.TIMED_OUT;
            sRequestInProgress = true;
        }
        try {
            Outcome outcome = ask(PROMPT_WAIT_MS);
            record(context, outcome, "root card");
            return outcome;
        } finally {
            synchronized (sLock) {
                sRequestInProgress = false;
            }
        }
    }

    private static SharedPreferences deviceState(Context context) {
        return context.getSharedPreferences(DEVICE_STATE_PREFS, Context.MODE_PRIVATE);
    }

    private static boolean magiskAnswered(Context context) {
        SharedPreferences legacy = ThemeManager.prefs(context);
        if (legacy.contains(LEGACY_PREF_ROOT_GRANTED)) {
            deviceState(context).edit().putBoolean(PREF_MAGISK_ANSWERED, true).apply();
            legacy.edit().remove(LEGACY_PREF_ROOT_GRANTED).apply();
            return true;
        }
        return deviceState(context).getBoolean(PREF_MAGISK_ANSWERED, false);
    }

    /**
     * Takes Magisk's answer as the one this process goes by, and tells whoever acts on it.
     *
     * <p>"Answered" is kept only for a granted or refused request: with no {@code su} there is no
     * Magisk to have answered, and a prompt nobody tapped has not been answered either - asking such
     * an installation at start would raise the prompt again, at every boot.
     */
    private static void record(Context context, Outcome outcome, String when) {
        boolean granted = outcome == Outcome.GRANTED;
        boolean before = hasRoot();
        sGranted = granted;
        Log.i(TAG, when + ": " + outcome);
        if (context == null) return;
        boolean answered = granted || outcome == Outcome.REFUSED || outcome == Outcome.DENIED_BY_POLICY;
        deviceState(context).edit().putBoolean(PREF_MAGISK_ANSWERED, answered).apply();
        if (granted) {
            MicrophoneGuard.repairAssistantMicOnce(context.getApplicationContext());
        }
        if (before != granted) {
            AudioSpectrumEngine.getInstance().onRootAnswerChanged();
        }
    }

    /**
     * Runs the smallest possible command as root, so that Magisk answers - or shows its prompt.
     *
     * <p>Blocks, so call it off the main thread. {@code id} is used deliberately: it changes
     * nothing, costs nothing, and if an owner later looks at Magisk's log the first thing this app
     * ever asked to do was ask who it was.
     */
    private static Outcome ask(long waitMs) {
        long started = System.currentTimeMillis();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            boolean finished = p.waitFor(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            long took = System.currentTimeMillis() - started;
            if (!finished) {
                p.destroy();
                Log.w(TAG, "root prompt was never answered (" + waitMs + " ms)");
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
}
