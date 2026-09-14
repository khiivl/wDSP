package com.radiorubka.wdsp;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.SystemClock;
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
 * The input is claimed first and then the platform is asked what rate the device under our own
 * recording runs at ({@code AudioRecordingConfiguration.getFormat()}) - the same fact
 * {@link RadioMicCapture} decides by. Our recorder's own {@code getSampleRate()} is the wrong
 * question (it reports what was asked for), and listening for content above 8 kHz was the wrong
 * answer too: a quiet cabin has none, and on 14.09.2026 a full-band 48 kHz input read -30.4 dB
 * against the -30 dB this class used to require.
 *
 * <p>Owner, 14.09.2026: for a sweep there is no waiting and no polite asking - with root the
 * hotword listeners are force-stopped and the input claimed at once; without root, or when that did
 * not take, the measurement does not start and the person is told to restart the head unit, after
 * which wDSP opens the input first. {@code killBackgroundProcesses} used to be tried before root; it
 * never worked on a unit where the assistant is a system app, which is the unit this is for.
 */
public final class MicrophoneGuard {
    private static final String TAG = "wDSP_MicGuard";

    private static final int SAMPLE_RATE = 48000;
    /** How long the platform is given to list a recording that has just started. */
    private static final long RATE_WAIT_MS = 500;
    /**
     * From a force-stop returning to claiming the input again. Measured 14.09.2026: the killed
     * assistant's 16 kHz input thread was still open 86 ms after its death and closed by 209 ms, and
     * a recorder opened before that inherits 16 kHz (platform/05-AUDIO-PATH.md). The assistant came
     * back after about 1.9 s.
     */
    private static final long INPUT_CLOSE_MS = 300;

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
        /** Device rate under our recording before anything was done, in Hz; 0 when not listed. */
        public int rateBefore;
        /** Device rate afterwards. Equal to {@link #rateBefore} when nothing needed doing. */
        public int rateAfter;
        public final List<String> stopped = new ArrayList<>();
        public boolean wasHeld;
        public boolean freed;
        /** True when root was used to stop the holder. */
        public boolean usedRoot;
        /**
         * Held narrow and not taken back: no root, or root did not help. The measurement must not
         * start; the person is told to restart the head unit. The guard holds nothing in this case.
         */
        public boolean needsRestart;
        /**
         * True when the platform did not describe our recording, so nothing is known about it.
         *
         * This is not the same as "it is ours", and the difference used to be invisible: every
         * failure of the old listening probe returned 0 dB, which sailed past its threshold and was
         * reported as a free microphone. A sweep then ran to 20 kHz through a microphone nobody had
         * checked, and the report a tester sent back said the microphone was fine.
         */
        public boolean unknown;

        @Override
        public String toString() {
            if (unknown) {
                return "microphone could not be checked - the platform did not list our recording; "
                        + "nothing was stopped and nothing is claimed about it";
            }
            if (!wasHeld) {
                return "microphone was already ours (input device at " + rateBefore + " Hz)";
            }
            return "microphone was held by another app (input device at " + rateBefore + " Hz); "
                    + "stopped " + (stopped.isEmpty() ? "nothing" : stopped.toString())
                    + "; now " + rateAfter + " Hz - "
                    + (freed ? "released through root" : "STILL HELD, restart needed");
        }
    }

    /**
     * Claims the microphone and checks, by the device rate, that it is full band; if another app
     * holds it narrow, takes it back through root or says a restart is needed.
     *
     * <p>On return the input is HELD (see {@link #holder}) unless {@link Outcome#needsRestart}; the
     * caller lets it go with {@link #releaseHold()} once its own capture is open.
     */
    public static Outcome ensureOurs(Context context) {
        Outcome outcome = new Outcome();
        // Claim first, then ask: the verdict is about the recorder that stays open until the
        // measurement's own capture replaces it, not about a probe that closes again and leaves
        // the input free for a moment.
        holdOpen();
        outcome.rateBefore = heldDeviceRate(context);
        outcome.rateAfter = outcome.rateBefore;

        // "Did not measure" is not "measured and fine". Killing somebody else's process on an
        // unknown answer would be worse than doing nothing, so it stops here - and says so.
        if (outcome.rateBefore <= 0) {
            outcome.unknown = true;
            Log.w(TAG, outcome.toString());
            return outcome;
        }
        if (outcome.rateBefore >= SAMPLE_RATE) {
            Log.i(TAG, outcome.toString());
            return outcome;
        }
        outcome.wasHeld = true;

        // Root: force-stop does not disable or uninstall anything, and the assistant comes back the
        // next time the system starts it - onto our input, which is by then open at 48 kHz.
        // A measurement is a person switching the microphone on - the moment Magisk is asked, once
        // per process (RootAccess.checkForMicrophone).
        if (RootAccess.checkForMicrophone(context)) {
            releaseHold();
            // The holders as AudioFlinger lists them, not a list of suspects - and takeInputAsRoot
            // waits for the input to close under them before we claim it.
            outcome.stopped.addAll(takeInputAsRoot(context, ROOT_WAIT_DELIBERATE_S));
            outcome.usedRoot = !outcome.stopped.isEmpty();
            holdOpen();
            outcome.rateAfter = heldDeviceRate(context);
            outcome.freed = outcome.rateAfter >= SAMPLE_RATE;
            checkCameBackAsync(context, outcome.stopped);
        }
        outcome.needsRestart = !outcome.freed;
        if (outcome.needsRestart) {
            releaseHold();
            Log.w(TAG, outcome + " - the measurement will not start");
        } else {
            Log.i(TAG, outcome.toString());
        }
        return outcome;
    }

    /**
     * The device rate under the held recorder, as the platform lists it, or 0 when it does not
     * list it within {@link #RATE_WAIT_MS}.
     */
    private static int heldDeviceRate(Context context) {
        final AudioRecord held = holder;
        if (held == null || context == null) return 0;
        final AudioManager am = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return 0;
        final int session = held.getAudioSessionId();
        final long until = SystemClock.uptimeMillis() + RATE_WAIT_MS;
        do {
            try {
                for (AudioRecordingConfiguration config : am.getActiveRecordingConfigurations()) {
                    if (config.getClientAudioSessionId() != session) continue;
                    AudioFormat device = config.getFormat();
                    if (device != null && device.getSampleRate() > 0) return device.getSampleRate();
                }
            } catch (Throwable ignored) {
            }
            sleep(20);
        } while (SystemClock.uptimeMillis() < until);
        return 0;
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
     * Stops whoever records from a microphone input right now, through root, and waits for the
     * input to close under them - so that whoever opens next opens it fresh and first. For the live
     * capture, which reopens the moment this returns: the assistant comes back within two seconds
     * and whoever opens the input first sets it up for everybody (measured 11.09 and 14.09.2026).
     * It replaces stopAssistantsAsRoot, which stopped a list of suspects without waiting.
     *
     * <p>Owner, 14.09.2026: "якщо програма має доступ рут - то це прямий шлях до мікрофону, в любому
     * випадку: знайти хто зайняв, зігнати, перевірити чи піднявся мікрофон та чи піднялася програма,
     * що сиділа до нього". Found, not guessed: the packages behind AudioFlinger's active record
     * tracks ({@link #inputHoldersAsRoot}); the known hotword listeners only when that cannot be
     * read. Whether they came back is {@link #checkCameBackAsync}'s business; whether our own input is
     * full band is the caller's.
     *
     * @return the packages that were stopped
     */
    static List<String> takeInputAsRoot(Context context) {
        return takeInputAsRoot(context, ROOT_WAIT_LIVE_S);
    }

    /** @param timeoutSeconds per force-stop - see {@link #ROOT_WAIT_DELIBERATE_S} and {@link #ROOT_WAIT_LIVE_S} */
    private static List<String> takeInputAsRoot(Context context, long timeoutSeconds) {
        List<String> targets = inputHoldersAsRoot(context);
        final boolean found = !targets.isEmpty();
        if (!found) {
            PackageManager pm = context.getPackageManager();
            for (String pkg : HOTWORD_PACKAGES) {
                if (isInstalled(pm, pkg)) targets.add(pkg);
            }
        }
        List<String> stopped = new ArrayList<>();
        for (String pkg : targets) {
            if (forceStopAsRoot(pkg, timeoutSeconds)) stopped.add(pkg);
        }
        Log.i(TAG, "input holders " + (found ? "read from AudioFlinger: " : "unreadable, known hotword listeners: ")
                + targets + " - stopped " + stopped);
        if (!stopped.isEmpty()) sleep(INPUT_CLOSE_MS);
        return stopped;
    }

    /**
     * Installed packages other than ours whose recorders are active on a microphone input, read
     * through root from AudioFlinger's record tracks (the client pid of every active one, and the
     * process behind it). A call's recorder belongs to a native process, not a package, and is
     * never among them. Empty when there is no root or nothing can be read.
     */
    static List<String> inputHoldersAsRoot(Context context) {
        List<String> holders = new ArrayList<>();
        // A record track's row starts with its Active flag; a playback track's starts with its id.
        String out = runAsRoot("for p in $(dumpsys media.audio_flinger"
                + " | grep -E '^ +yes +[0-9]+ +[0-9]+ +[0-9]+ ' | awk '{print $3}'); do"
                + " echo \"$p $(tr '\\0' ' ' < /proc/$p/cmdline)\"; done");
        if (out == null) return holders;
        PackageManager pm = context.getPackageManager();
        String own = context.getPackageName();
        int ownPid = android.os.Process.myPid();
        for (String line : out.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) continue;
            try {
                if (Integer.parseInt(parts[0]) == ownPid) continue;
            } catch (NumberFormatException e) {
                continue;
            }
            String pkg = parts[1].split(":")[0];
            if (pkg.equals(own) || holders.contains(pkg) || !isInstalled(pm, pkg)) continue;
            holders.add(pkg);
        }
        return holders;
    }

    /** How long a stopped package is given to be running again before that is reported. */
    private static final long CAME_BACK_WAIT_MS = 15000;

    /**
     * Reports, off the caller's thread, whether each stopped package is running again - the second
     * half of the owner's order. Nothing is started on anyone's behalf: the assistant restarts
     * itself (about 1.9 s, measured 14.09.2026), and one that does not is told in the log.
     */
    static void checkCameBackAsync(Context context, List<String> packages) {
        if (context == null || packages == null || packages.isEmpty()) return;
        final List<String> watched = new ArrayList<>(packages);
        new Thread(() -> {
            final long from = android.os.SystemClock.elapsedRealtime();
            for (String pkg : watched) {
                boolean back = false;
                while (android.os.SystemClock.elapsedRealtime() - from < CAME_BACK_WAIT_MS) {
                    String count = runAsRoot("ps -A -o NAME | grep -cE '^" + pkg.replace(".", "\\.")
                            + "(:.*)?$'");
                    if (count != null && !count.trim().equals("0")) {
                        back = true;
                        break;
                    }
                    sleep(500);
                }
                long ms = android.os.SystemClock.elapsedRealtime() - from;
                if (back) {
                    Log.i(TAG, pkg + " is running again, " + ms + " ms after it was stopped"
                            + (inputHoldersAsRoot(context).contains(pkg) ? ", and recording" : ""));
                } else {
                    Log.w(TAG, pkg + " has NOT come back within " + (CAME_BACK_WAIT_MS / 1000)
                            + " s of being stopped");
                }
            }
        }, "wDSP_MicCameBack").start();
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
