package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.util.Locale;

/**
 * How many decibels each volume step is, measured through the microphone (owner, 13.10.2026: «один
 * раз, швидко»). The loudness curve needs it: hearing loses the bass by the drop in level, not by the
 * number of steps, and the steps are not equal (CABIN_MODEL.md §11, "owed").
 *
 * <p>A 1 kHz tone plays without a break while the volume walks down, and its level is read at 1 kHz
 * alone (Goertzel), so the cabin's noise hardly reaches it. Only the relative level matters, so the
 * bench is good enough - the room is the same at every step. Two halves, each with its own tone
 * level, so neither is loud and neither drowns:
 * <ul>
 *   <li>{@link #TOP} down to {@link #SPLIT}, half a second a step - about 1 dB each, quick to settle;</li>
 *   <li>{@link #SPLIT} down to 1, a second a step, the tone some 30 dB stronger - here a step is 2..6 dB
 *   and the MCU ramps the volume: 📻 13.10.2026 a 0.3 s walk read steps 1-3, 4-7 and 8-12 as flat
 *   plateaus, a 1 s walk read 5-6 dB a step; and at the top half's level step 1 sat in the noise.</li>
 * </ul>
 * Each half's tone comes from a quiet probe at its first step, scaled to {@link #TOP_TARGET_DBFS}, so
 * no step plays louder than a measurement sweep. The halves meet at {@link #SPLIT}. 📻 13.10.2026 two
 * separate walks joined there agreed within 0.2 dB down to step 5.
 *
 * <p>Taken by the wizard (owner, 13.10.2026: the program measures the person's hardware itself - their
 * amplifier, the optical link after the BU32107): a stage of every microphone calibration, and of the
 * first room measurement on a unit that has no table yet - {@link #inMeasurement}. The broadcast
 * {@code com.radiorubka.wdsp.MEASURE_VOLUME_STEPS} re-runs it on the bench. Writes {@link #reportFile}
 * next to the microphone report (the measurement archive carries it) and keeps the table in the
 * preference {@link #PREF_TABLE} for the loudness maths.
 */
final class VolumeSteps {

    private static final String TAG = "wDSP_VolumeSteps";
    /** run()'s answer when the walk went through and a step was too close to the noise. */
    private static final String STEP_NOT_READ = "a step was not read";
    static final int TOP = 32;
    static final int SPLIT = 13;
    private static final float TOP_TARGET_DBFS = -12f;
    private static final float PROBE_AMPLITUDE = 0.005f;
    /** The loudest the walk's tone gets - the measurement sweep's own cap (RoomMeasurement.LEVEL_MAX). */
    private static final double MAX_AMPLITUDE = 0.5;
    private static final float TONE_HZ = 1000f;
    private static final float SLOT_TOP_S = 0.5f, SLOT_LOW_S = 1.0f;
    /** Where in each slot the level is read, as shares of it: after the step has reached the speakers. */
    private static final float READ_FROM = 0.6f, READ_TO = 0.93f;
    private static final float MIN_SNR_DB = 10f;
    /** dB of each step against {@link #TOP}, index 0 = step 1, comma separated; NaN = not read. */
    static final String PREF_TABLE = "volume_step_db";

    private VolumeSteps() {
    }

    /** The table's report, next to the microphone calibration report. */
    static File reportFile(Context context) {
        final File dir = context.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "volume_steps.txt");
    }

    static boolean hasTable(Context context) {
        return context.getSharedPreferences(RoomMeasurement.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_TABLE, null) != null;
    }

    /**
     * The table as a stage of a measurement that already holds the slot, after its own sweeps. A walk
     * that fails fails nothing else: the loudness maths keeps the table it had (or counts steps), and
     * the report file says why this one was not taken.
     */
    static void inMeasurement(Context app, RoomMeasurement.Listener listener) {
        if (listener != null) {
            listener.onProgress(1, 1, app.getString(R.string.room_stage_volume_steps),
                    app.getString(R.string.room_stage_volume_steps_detail), 0);
        }
        String failed;
        try {
            failed = run(app);
        } catch (Throwable t) {
            failed = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "volume steps failed", t);
        }
        // A step not read has its own report already - the table with the gap in it; keep that.
        if (failed != null && !STEP_NOT_READ.equals(failed)) writeReport(app, "volume steps not measured: " + failed + "\ntable in use: "
                + app.getSharedPreferences(RoomMeasurement.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_TABLE, "none - the loudness curve counts steps") + "\n");
    }

    static void measureAsync(Context context) {
        final Context app = context.getApplicationContext();
        if (!RoomMeasurement.begin()) {
            Log.w(TAG, "a measurement is running - not started");
            return;
        }
        new Thread(() -> {
            try {
                run(app);
            } catch (Throwable t) {
                Log.e(TAG, "volume steps failed", t);
            } finally {
                RoomMeasurement.end();
            }
        }, "wDSP_VolumeSteps").start();
    }

    /** One half of the walk: its levels by step (NaN outside it), its tone's amplitude, the noise. */
    private static final class Half {
        final double[] level = new double[TOP + 1];
        double amplitude, noiseDb;
        /** Why the half was not read; null when it was. */
        String error;

        Half() {
            java.util.Arrays.fill(level, Double.NaN);
        }
    }

    /** The walk, both halves; null when the table was taken, else why not. */
    private static String run(Context app) {
        if (CallState.isActive()) {
            Log.w(TAG, "phone call in progress - not measured");
            return "phone call in progress";
        }
        final MicrophoneGuard.Outcome mic = MicrophoneGuard.ensureOurs(app);
        if (mic.needsRestart) {
            Log.w(TAG, "the microphone is held by another app: " + mic);
            return "the microphone is held by another app: " + mic;
        }
        final SharedPreferences prefs = app.getSharedPreferences(RoomMeasurement.PREFS_NAME, Context.MODE_PRIVATE);
        McuService.ensureStarted(app);
        VolumeHelper.init(app);
        final int origVolume = VolumeHelper.getVolume();
        prefs.edit().putString(RoomMeasurement.PREF_RECOVERY, RoomMeasurement.PREF_LAST_SELECTED + "="
                + prefs.getString(RoomMeasurement.PREF_LAST_SELECTED, null) + ";saved_volume=" + origVolume).apply();
        try {
            RoomMeasurement.pauseMedia(app);
            final Half upper = walk(app, TOP, SPLIT, SLOT_TOP_S, false);
            if (upper.error != null) return upper.error;
            final Half lower = walk(app, SPLIT, 1, SLOT_LOW_S, true);
            if (lower.error != null) return lower.error;
            return report(app, prefs, upper, lower) ? null : STEP_NOT_READ;
        } finally {
            MicrophoneGuard.releaseHold();
            setVolume(origVolume);
            prefs.edit().remove(RoomMeasurement.PREF_RECOVERY).apply();
        }
    }

    /**
     * A quiet probe at {@code from} sets the tone's level, then the tone plays unbroken while the
     * volume walks {@code from} .. {@code to}, {@code slotS} a step. {@link Half#error} says why a
     * half was not read.
     */
    private static Half walk(Context app, int from, int to, float slotS, boolean releaseHold) {
        final int sr = RoomMeasurement.SAMPLE_RATE;
        final Half h = new Half();
        setVolume(from);
        RoomMeasurement.sleep(600);
        final short[] probe = tone(sr, PROBE_AMPLITUDE, 0);
        final short[] probeRec = new short[sr + sr / 2];
        final RoomMeasurement.Result pass = new RoomMeasurement.Result();
        int got = RoomMeasurement.playAndRecord(app, probe, probeRec, pass, null, null, false);
        if (got <= 0 || pass.error != null) {
            h.error = "probe at step " + from + " failed: " + pass.error;
            Log.w(TAG, h.error);
            return h;
        }
        final double probeDb = levelDb(probeRec, (int) (0.4f * sr), (int) (0.9f * sr));
        final double needed = PROBE_AMPLITUDE * Math.pow(10.0, (TOP_TARGET_DBFS - probeDb) / 20.0);
        Log.i(TAG, String.format(Locale.US, "probe at step %d: %.1f dBFS at 1 kHz with amplitude %.4f -> %.4f",
                from, probeDb, PROBE_AMPLITUDE, needed));
        // At the top of the scale a microphone that hardly hears the probe (unplugged, muted, the
        // amplifier off) would get the loudest tone the walk can make, at volume 32. Refused there;
        // lower down the cap is what a measurement sweep plays at, and the noise test below judges it.
        if (needed > MAX_AMPLITUDE && from == TOP) {
            h.error = String.format(Locale.US, "the microphone hardly hears the probe at step %d "
                    + "(%.1f dBFS at 1 kHz) - not played louder", from, probeDb);
            Log.w(TAG, h.error);
            return h;
        }
        h.amplitude = Math.max(0.002, Math.min(MAX_AMPLITUDE, needed));

        final int steps = from - to + 1;
        final int lead = (int) (0.3f * sr);
        final int slot = (int) (slotS * sr);
        final short[] walk = tone(lead + steps * slot, h.amplitude, lead);
        final short[] rec = new short[lead + steps * slot + sr / 2];
        final Thread[] walker = new Thread[1];
        got = RoomMeasurement.playAndRecord(app, walk, rec, pass, startMs -> {
            walker[0] = new Thread(() -> {
                for (int k = 1; k < steps; k++) {
                    final long at = (long) ((lead + (long) k * slot) * 1000L / sr);
                    final long wait = at - (android.os.SystemClock.elapsedRealtime() - startMs);
                    if (wait > 0) RoomMeasurement.sleep(wait);
                    // The pass ended early (a call, an abort): the volume is being put back - leave it.
                    if (Thread.currentThread().isInterrupted()) return;
                    setVolume(from - k);
                }
            }, "wDSP_VolumeWalk");
            walker[0].start();
        }, null, releaseHold);
        // Stopped and waited for before anyone restores the volume, or a walk cut short would go on
        // turning it down afterwards - in the middle of a phone call, if that is what cut it.
        if (walker[0] != null) {
            walker[0].interrupt();
            try {
                walker[0].join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (got <= 0 || pass.error != null) {
            h.error = "walk " + from + ".." + to + " failed: " + pass.error;
            Log.w(TAG, h.error);
            return h;
        }
        h.noiseDb = levelDb(rec, 0, lead);
        for (int k = 0; k < steps; k++) {
            final int a = lead + k * slot + (int) (READ_FROM * slot);
            final int b = Math.min(got, lead + k * slot + (int) (READ_TO * slot));
            final double l = b > a ? levelDb(rec, a, b) : Double.NaN;
            h.level[from - k] = l - h.noiseDb >= MIN_SNR_DB ? l : Double.NaN;
        }
        return h;
    }

    private static void setVolume(int v) {
        VolumeHelper.setVolume(v);
        VolumeHelper.setVolumeForType("media_type", v);
    }

    /** A 1 kHz tone in both channels after {@code silence} frames of nothing, {@code frames} in all. */
    private static short[] tone(int frames, double amplitude, int silence) {
        final short[] out = new short[frames * 2];
        final double w = 2 * Math.PI * TONE_HZ / RoomMeasurement.SAMPLE_RATE;
        for (int i = silence; i < frames; i++) {
            final short v = (short) Math.round(amplitude * Short.MAX_VALUE * Math.sin(w * (i - silence)));
            out[i * 2] = v;
            out[i * 2 + 1] = v;
        }
        return out;
    }

    /** The level of the 1 kHz component in dBFS (a full-scale sine reads 0), Goertzel over [from, to). */
    private static double levelDb(short[] x, int from, int to) {
        final double w = 2 * Math.PI * TONE_HZ / RoomMeasurement.SAMPLE_RATE, c = 2 * Math.cos(w);
        double s1 = 0, s2 = 0;
        for (int n = from; n < to; n++) {
            final double s = x[n] / 32768.0 + c * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        final double power = s1 * s1 + s2 * s2 - c * s1 * s2;
        final double amp = 2 * Math.sqrt(Math.max(power, 0)) / Math.max(1, to - from);
        return 20 * Math.log10(amp + 1e-12);
    }

    /**
     * The two halves joined at {@link #SPLIT}, against {@link #TOP}: the file, the log, the preference.
     * True when the table was whole and kept.
     */
    private static boolean report(Context app, SharedPreferences prefs, Half upper, Half lower) {
        final double[] rel = new double[TOP + 1];
        java.util.Arrays.fill(rel, Double.NaN);
        final double top = upper.level[TOP], split = upper.level[SPLIT];
        for (int v = SPLIT; v <= TOP; v++) rel[v] = upper.level[v] - top;
        for (int v = 1; v < SPLIT; v++) rel[v] = lower.level[v] - lower.level[SPLIT] + (split - top);
        final StringBuilder txt = new StringBuilder(String.format(Locale.US, "volume steps: 1 kHz tone; steps %d..%d "
                        + "at amplitude %.4f (noise %.1f dBFS), %d..1 at %.4f (noise %.1f dBFS); dB against step %d\n",
                TOP, SPLIT, upper.amplitude, upper.noiseDb, SPLIT, lower.amplitude, lower.noiseDb, TOP));
        final StringBuilder csv = new StringBuilder();
        boolean whole = true;
        for (int v = 1; v <= TOP; v++) {
            final double drop = v < TOP ? rel[v + 1] - rel[v] : Double.NaN;
            txt.append(String.format(Locale.US, "  step %2d  %7s dB  %6s dB/step\n", v,
                    Double.isNaN(rel[v]) ? "-" : String.format(Locale.US, "%+.1f", rel[v]),
                    Double.isNaN(drop) ? "-" : String.format(Locale.US, "%.1f", drop)));
            if (v > 1) csv.append(',');
            csv.append(Double.isNaN(rel[v]) ? "NaN" : String.format(Locale.US, "%.2f", rel[v]));
            whole &= !Double.isNaN(rel[v]);
        }
        // Only a whole table is kept: the loudness maths reads it step by step.
        if (whole) {
            prefs.edit().putString(PREF_TABLE, csv.toString()).apply();
            LoudnessCurve.setStepTable(csv.toString());
        }
        txt.append(whole ? "kept as " + PREF_TABLE + "\n" : "not kept - " + STEP_NOT_READ
                + " (under " + MIN_SNR_DB + " dB above that half's noise); the table in use is the previous one\n");
        Log.i(TAG, txt.toString());
        writeReport(app, txt.toString());
        return whole;
    }

    private static void writeReport(Context app, String text) {
        final File file = reportFile(app);
        if (file == null) return;
        try (FileWriter w = new FileWriter(file)) {
            w.write(text);
        } catch (Exception e) {
            Log.w(TAG, "could not write " + file.getName(), e);
        }
    }
}
