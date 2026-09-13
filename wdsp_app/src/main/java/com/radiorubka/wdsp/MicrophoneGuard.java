package com.radiorubka.wdsp;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gets the microphone back from whatever is holding it, so a measurement is not made through half
 * of one.
 *
 * <h2>The problem</h2>
 *
 * An assistant hotword listener opens the microphone at boot and never lets go. The platform then
 * opens exactly one input stream, and every other app is attached to that one rather than being
 * given its own - so a request for 48 kHz quietly returns the assistant's 16 kHz stream, resampled.
 * Half the sweep is missing and nothing says so: {@code AudioRecord.getSampleRate()} reports the
 * rate that was asked for either way.
 *
 * Measured on a head unit: with the assistant holding the microphone, a recording had −71 to −85 dB
 * of energy above 8 kHz. With it released, −15 dB. The weakest channels stopped being measurable
 * at all, which is precisely what testers reported as "the rest failed".
 *
 * <h2>Why the app has to do this itself</h2>
 *
 * Because nobody else will. Asking a driver to find the hotword setting and turn it off before
 * every measurement, then turn it back on, is asking for the measurement not to happen.
 *
 * <h2>How</h2>
 *
 * {@link ActivityManager#killBackgroundProcesses} needs only a normal permission and stops a
 * background process. The assistant comes back on its own the next time the system wants it -
 * there is nothing to restore afterwards, and nothing is uninstalled, disabled or configured. A
 * process that was in the foreground is not touched at all.
 *
 * The microphone is tested by listening to it rather than by asking about it, because asking
 * returns the wrong answer.
 */
public final class MicrophoneGuard {
    private static final String TAG = "wDSP_MicGuard";

    private static final int SAMPLE_RATE = 48000;
    /** Half a second is plenty to tell a 16 kHz stream from a 48 kHz one. */
    private static final int PROBE_MS = 500;
    /**
     * Above this, the recording has a top end and the microphone is ours.
     *
     * Measured: −15 dB with the microphone free, −71 dB and below when it is being shared with a
     * 16 kHz client. The gap is enormous, so the threshold does not need to be precise.
     */
    static final float BANDWIDTH_OK_DB = -30f;

    /**
     * Below this the probe heard silence, and silence says nothing about a stream's rate: the ratio
     * of two bands of noise is noise. Same value as {@code RadioMicCapture.PROBE_MIN_RMS}, for the
     * same reason.
     */
    private static final double PROBE_MIN_RMS = 1e-4;

    /**
     * Known hotword listeners, most likely first.
     *
     * A list is not elegant, but the alternative is not available: the package name behind an
     * active recording is hidden from ordinary apps, and guessing from the whole installed set
     * would mean killing things at random. Anything not installed is skipped.
     */
    private static final String[] HOTWORD_PACKAGES = {
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googleassistant",
            "ru.yandex.searchplugin",
            "com.samsung.android.bixby.agent",
    };

    private MicrophoneGuard() {
    }

    /** What the guard did, for the log and for the report a tester sends back. */
    public static final class Outcome {
        /** Bandwidth before anything was done, in dB above 8 kHz relative to the band below. */
        public float before;
        /** Bandwidth afterwards. Equal to {@link #before} when nothing needed doing. */
        public float after;
        public final List<String> stopped = new ArrayList<>();
        public boolean wasHeld;
        public boolean freed;
        /** True when the polite request failed and root was needed to finish the job. */
        public boolean usedRoot;
        /**
         * True when the microphone could not be listened to at all, so nothing is known about it.
         *
         * This is not the same as "it is ours", and the difference used to be invisible: every
         * failure returned 0 dB, which sails past a threshold of -30 dB and was reported as a free
         * microphone. A sweep then ran to 20 kHz through a microphone nobody had checked, and the
         * report a tester sent back said the microphone was fine.
         */
        public boolean unknown;

        @Override
        public String toString() {
            if (unknown) {
                return "microphone could not be checked - the probe did not record anything usable; "
                        + "nothing was stopped and nothing is claimed about it";
            }
            if (!wasHeld) {
                return String.format(Locale.US, "microphone was already ours (%.1f dB above 8 kHz)",
                        before);
            }
            return String.format(Locale.US,
                    "microphone was held by another app (%.1f dB above 8 kHz); stopped %s; "
                            + "now %.1f dB - %s",
                    before, stopped.isEmpty() ? "nothing" : stopped.toString(), after,
                    freed ? (usedRoot ? "released, root was needed" : "released") : "STILL HELD");
        }
    }

    /**
     * Checks the microphone and, if something else has it, asks the system to stop that something.
     *
     * Safe to call when nothing is wrong: it costs half a second of listening and does nothing.
     */
    public static Outcome ensureOurs(Context context) {
        Outcome outcome = new Outcome();
        outcome.before = measureBandwidth();
        outcome.after = outcome.before;

        // "Did not measure" is not "measured and fine". Killing somebody else's process on a
        // failed probe would be worse than doing nothing, so an unknown answer stops here - and
        // says so, instead of letting a caller read silence as a healthy microphone.
        if (Float.isNaN(outcome.before)) {
            outcome.unknown = true;
            Log.w(TAG, outcome.toString());
            return outcome;
        }

        if (outcome.before >= BANDWIDTH_OK_DB) {
            Log.i(TAG, outcome.toString());
            return outcome;
        }
        outcome.wasHeld = true;

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = context.getPackageManager();
        if (am == null) {
            Log.w(TAG, "no activity manager, cannot free the microphone");
            return outcome;
        }

        for (String pkg : HOTWORD_PACKAGES) {
            if (!isInstalled(pm, pkg)) continue;
            try {
                am.killBackgroundProcesses(pkg);
                outcome.stopped.add(pkg);
                Log.i(TAG, "asked the system to stop " + pkg);
            } catch (Throwable t) {
                // Missing permission, or the process is in the foreground and protected. Either
                // way the measurement can still go ahead, just through a narrower microphone.
                Log.w(TAG, "could not stop " + pkg + ": " + t);
            }
        }

        if (!outcome.stopped.isEmpty()) {
            sleep(700);   // the stream has to close before ours can be opened at full width
            outcome.after = measureBandwidth();
        }
        outcome.freed = outcome.after >= BANDWIDTH_OK_DB;

        // The polite request does not work on a head unit where the assistant is a system app:
        // killBackgroundProcesses will not touch one, and measured on such a unit the microphone
        // stayed at 16 kHz however many times it was asked. Most of these head units are rooted,
        // so if root is there, use it - a force-stop does what the polite request could not.
        //
        // Still nothing to restore: force-stop does not disable or uninstall anything, and the
        // assistant comes back the next time the system starts it.
        if (!outcome.freed && !outcome.stopped.isEmpty() && RootAccess.hasRoot(context)) {
            for (String pkg : HOTWORD_PACKAGES) {
                if (!isInstalled(pm, pkg)) continue;
                if (forceStopAsRoot(pkg, ROOT_WAIT_DELIBERATE_S)) outcome.usedRoot = true;
            }
            if (outcome.usedRoot) {
                sleep(900);
                outcome.after = measureBandwidth();
                outcome.freed = outcome.after >= BANDWIDTH_OK_DB;
            }
        }
        Log.i(TAG, outcome.toString());
        if (!outcome.freed) {
            Log.w(TAG, "the microphone is still limited. Whatever holds it is not in the list, or "
                    + "is running in the foreground where it cannot be stopped. The measurement "
                    + "will go ahead, but everything above 8 kHz is missing from it.");
        }
        holdOpen();
        return outcome;
    }

    /**
     * A recorder that exists only to own the input until the caller's real capture is open.
     *
     * 🔴 The reason this class holds it, rather than each caller doing so for itself: winning the
     * microphone is decided by WHO HAS IT OPEN, not by who kills whom. The owner found that out -
     * with the spectrum analyser in microphone mode this app has the input open continuously and
     * the assistant hotword simply cannot take it; he switched the analyser to calculated mode
     * before a sweep, the capture stopped, and the hotword had the input at 16 kHz before the
     * measurement ever asked for it. Force-stopping the hotword does not settle it either: it
     * restarts a moment later and finds a free microphone.
     *
     * <p>Between this verdict and the caller's own open there is real time - a scratch preset, a
     * volume lock, a push to the sound processor and its settle delay. Better part of a second of
     * an idle input, on every pass. So the device is claimed here and let go only once the caller
     * says it has its own, which leaves no instant in which the input is free.
     *
     * <p>It is never read from; owning it is the whole point.
     */
    private static AudioRecord holder;

    private static void holdOpen() {
        releaseHold();
        AudioRecord candidate = openCaptureRecord();
        if (candidate == null) return;
        try {
            candidate.startRecording();
            holder = candidate;
            Log.i(TAG, "holding the input open so nothing can take it back before the capture");
        } catch (Throwable t) {
            Log.w(TAG, "could not hold the input open: " + t);
            try { candidate.release(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Lets the placeholder go. Call it AFTER the real capture is open, never before: two clients
     * on one input for a moment is fine, an input with none is what loses it.
     *
     * <p>Idempotent, and safe to call when nothing was ever held.
     */
    public static void releaseHold() {
        final AudioRecord held = holder;
        holder = null;
        if (held == null) return;
        try { held.stop(); } catch (Throwable ignored) {}
        try { held.release(); } catch (Throwable ignored) {}
    }

    /**
     * Opens a capture the way this app measures with - one place, so that what the guard holds and
     * what the measurement records are the same kind of stream.
     *
     * <p>UNPROCESSED first because it is absent from the preprocess list in
     * {@code audio_effects.xml} and so carries no echo cancellation or noise suppression;
     * VOICE_RECOGNITION as a fallback, because a unit that cannot offer the first is better
     * measured imperfectly than not at all. Callers ask the returned record which one they got.
     */
    public static AudioRecord openCaptureRecord() {
        int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) return null;
        AudioRecord record = tryOpenCapture(MediaRecorder.AudioSource.UNPROCESSED, minBytes);
        if (record == null) {
            Log.w(TAG, "UNPROCESSED unavailable, falling back to VOICE_RECOGNITION - "
                    + "the platform will attach AEC/NS to this capture");
            record = tryOpenCapture(MediaRecorder.AudioSource.VOICE_RECOGNITION, minBytes);
        }
        return record;
    }

    private static AudioRecord tryOpenCapture(int source, int minBytes) {
        try {
            AudioRecord record = new AudioRecord(source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBytes * 8);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                return null;
            }
            Log.i(TAG, "capture opened with source " + source
                    + (source == MediaRecorder.AudioSource.UNPROCESSED
                       ? " (UNPROCESSED - no policy preprocessing)" : ""));
            return record;
        } catch (Throwable t) {
            Log.w(TAG, "could not open capture source " + source + ": " + t);
            return null;
        }
    }

    /**
     * Records briefly and reports how much of it lives above 8 kHz.
     *
     * @return {@code Float.NaN} whenever the answer is not known - no native analyser, no input,
     *         too little recorded, or silence, because silence says nothing about a stream's rate.
     *         Never 0 dB for a failure: 0 passes every threshold this class compares against, and
     *         that is how an unchecked microphone came to be reported as a free one.
     *         {@code RadioMicCapture.bandwidthDb} has always done it this way; this method was the
     *         odd one out.
     */
    private static float measureBandwidth() {
        if (!NativeSweep.isAvailable()) {
            Log.w(TAG, "no native analyser - the microphone cannot be checked");
            return Float.NaN;
        }
        int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) return Float.NaN;

        AudioRecord record = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBytes * 4);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) return Float.NaN;

            final int wanted = SAMPLE_RATE * PROBE_MS / 1000;
            short[] buffer = new short[minBytes];
            float[] all = new float[wanted];
            int got = 0;
            record.startRecording();
            while (got < wanted) {
                int read = record.read(buffer, 0, Math.min(buffer.length, wanted - got));
                if (read <= 0) break;
                for (int i = 0; i < read; i++) all[got + i] = buffer[i] / 32768f;
                got += read;
            }
            record.stop();
            if (got < SAMPLE_RATE / 8) return Float.NaN;

            // Silence carries no evidence either way: a quiet cabin has nothing above 8 kHz and
            // nothing below it, and the ratio of the two is noise divided by noise. The same floor
            // as RadioMicCapture uses for its own probe.
            double sum = 0;
            for (int i = 0; i < got; i++) sum += all[i] * all[i];
            if (Math.sqrt(sum / got) < PROBE_MIN_RMS) {
                Log.w(TAG, "the probe recorded silence - nothing can be said about the microphone");
                return Float.NaN;
            }
            return NativeSweep.bandwidthRatioDb(all, got, SAMPLE_RATE);
        } catch (Throwable t) {
            Log.w(TAG, "could not listen to the microphone: " + t);
            return Float.NaN;
        } finally {
            if (record != null) {
                try {
                    record.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * How long a deliberate, user-initiated attempt waits: long enough for somebody to tap a root
     * prompt that has appeared on screen.
     */
    private static final long ROOT_WAIT_DELIBERATE_S = 20;
    /**
     * How long the live capture waits. Short on purpose: the assistant is back within two seconds,
     * so a slow force-stop has already lost the race and waiting only keeps the spectrum frozen.
     * With four packages in the list, the old twenty seconds each could hold the capture thread for
     * over a minute - and there is nobody at the screen to tap a prompt while music is playing.
     */
    private static final long ROOT_WAIT_LIVE_S = 2;

    /**
     * Force-stops a package through root, if root is there.
     *
     * Deliberately narrow: one command, one package, a bounded wait, and no shell left open. If
     * there is no root the call fails immediately and the caller carries on with whatever
     * microphone it has.
     *
     * @param timeoutSeconds how long to wait for the command - see the two constants above; the
     *                       right value depends on whether a person is standing at the screen
     */
    private static boolean forceStopAsRoot(String pkg, long timeoutSeconds) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + pkg});
            boolean done = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                Log.w(TAG, "root force-stop of " + pkg + " did not finish in " + timeoutSeconds
                        + "s - a root prompt may be waiting for somebody to tap it");
                return false;
            }
            final boolean ok = p.exitValue() == 0;
            Log.i(TAG, "root force-stop of " + pkg + (ok ? " succeeded" : " was refused"));
            return ok;
        } catch (Throwable t) {
            Log.i(TAG, "no root available for stopping " + pkg + " (" + t.getClass().getSimpleName()
                    + ")");
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * Stops every known hotword listener through root - without the polite attempt first and
     * without waiting afterwards. For the live capture only: the caller reopens its own stream the
     * moment this returns, because the assistant comes back within two seconds and whoever opens
     * the input first sets its rate for everybody (measured 11.09.2026).
     *
     * @return how many packages were stopped
     */
    static int stopAssistantsAsRoot(Context context) {
        PackageManager pm = context.getPackageManager();
        int stopped = 0;
        for (String pkg : HOTWORD_PACKAGES) {
            if (isInstalled(pm, pkg) && forceStopAsRoot(pkg, ROOT_WAIT_LIVE_S)) stopped++;
        }
        return stopped;
    }

    /** Preferences that describe this unit rather than the owner's settings - backups leave them out. */
    private static final String DEVICE_STATE_PREFS = "wdsp_device_state";
    private static final String PREF_ASSISTANT_MIC_REPAIRED = "assistant_mic_repaired";
    private static final String ASSISTANT = "com.google.android.googlequicksearchbox";

    /**
     * Gives the assistant its microphone back, once, on units where 0.4.9.x took it away.
     *
     * <p>Versions 0.4.9 to 0.4.9.6 set this app-op to {@code ignore} through root, on the claim
     * that the assistant would then share the microphone. It does not share - it goes deaf.
     * Measured 11.09.2026 on the owner's unit: with the op at {@code ignore} the assistant recorded
     * nothing at all for a day and a half, and "Ok Google" answered again once it was back at
     * {@code allow}. The mode survives reboots and even the uninstall of the app that set it, so
     * the app has to put it back itself. The owner's order, and not optional.
     *
     * <p>Only {@code ignore} is undone - any other mode is somebody's own choice. Runs on a
     * background thread, uses root the app already holds, never asks for it, and is marked done
     * only once {@code allow} has been read back.
     */
    static void repairAssistantMicOnce(Context context) {
        if (context == null) return;
        android.content.SharedPreferences state =
                context.getSharedPreferences(DEVICE_STATE_PREFS, Context.MODE_PRIVATE);
        if (state.getBoolean(PREF_ASSISTANT_MIC_REPAIRED, false)) return;
        if (!isInstalled(context.getPackageManager(), ASSISTANT)) {
            state.edit().putBoolean(PREF_ASSISTANT_MIC_REPAIRED, true).apply();
            return;
        }
        String mode = runAsRoot("cmd appops get " + ASSISTANT + " RECORD_AUDIO");
        if (mode == null) return;   // no answer - try again next time
        if (mode.contains("ignore")) {
            String after = runAsRoot("cmd appops set " + ASSISTANT + " RECORD_AUDIO allow; "
                    + "cmd appops get " + ASSISTANT + " RECORD_AUDIO");
            if (after == null || !after.contains("allow")) {
                Log.w(TAG, "could not give the assistant its microphone back: " + after);
                return;             // not marked - try again next time
            }
            Log.w(TAG, "the assistant's microphone had been left at 'ignore' by 0.4.9.x - set back to allow");
        } else {
            Log.i(TAG, "the assistant's microphone is not ours to touch (" + mode.trim() + ")");
        }
        state.edit().putBoolean(PREF_ASSISTANT_MIC_REPAIRED, true).apply();
    }

    /** One root command and its output, or null when there was no answer within five seconds. */
    private static String runAsRoot(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            // Wait first: the answer is one line, so it fits the pipe, and a read would block
            // forever on a prompt nobody is going to tap.
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) return null;
            if (p.exitValue() != 0) return null;
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            return out.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
