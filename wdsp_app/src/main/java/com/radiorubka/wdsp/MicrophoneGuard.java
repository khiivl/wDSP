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
    private static final float BANDWIDTH_OK_DB = -30f;

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

        @Override
        public String toString() {
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
        if (!outcome.freed && !outcome.stopped.isEmpty()) {
            for (String pkg : HOTWORD_PACKAGES) {
                if (!isInstalled(pm, pkg)) continue;
                if (forceStopAsRoot(pkg)) outcome.usedRoot = true;
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
        return outcome;
    }

    /** Records briefly and reports how much of it lives above 8 kHz. */
    private static float measureBandwidth() {
        int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) return 0f;

        AudioRecord record = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBytes * 4);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) return 0f;

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
            if (got < SAMPLE_RATE / 8) return 0f;
            return NativeSweep.bandwidthRatioDb(all, got, SAMPLE_RATE);
        } catch (Throwable t) {
            Log.w(TAG, "could not listen to the microphone: " + t);
            return 0f;
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
     * Force-stops a package through root, if root is there.
     *
     * Deliberately narrow: one command, one package, a short timeout, and no shell left open. If
     * there is no root the call fails immediately and the measurement carries on with whatever
     * microphone it has.
     */
    private static boolean forceStopAsRoot(String pkg) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + pkg});
            boolean done = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.destroy();
                Log.w(TAG, "root force-stop of " + pkg + " did not finish in time - a root prompt may be waiting for somebody to tap it");
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
