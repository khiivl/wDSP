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
 * <p>A 1 kHz tone plays without a break while the volume walks from {@link #TOP} down to 1, one step
 * {@link #SLOT_S} each (owner: «32 значення в кілька секунд»); its level is read at 1 kHz alone
 * (Goertzel), so the cabin's noise hardly reaches it and the quiet steps still read. Only the relative
 * level matters, so the bench is good enough for it - the room is the same at every step. The top step
 * is not loud: the tone's amplitude is cut so that step plays no louder than a measurement sweep.
 *
 * <p>The tone's amplitude comes from a first, quiet probe at {@link #TOP}, scaled so that step lands at
 * {@link #TOP_TARGET_DBFS} - well under the input's knee (RoomMeasurement.LEVEL_TARGET_DBFS).
 *
 * <p>Diagnostic, started by {@code com.radiorubka.wdsp.MEASURE_VOLUME_STEPS}. Writes
 * {@code volume_steps.txt} next to the microphone report and keeps the table in the preference
 * {@link #PREF_TABLE} for the loudness maths.
 */
final class VolumeSteps {

    private static final String TAG = "wDSP_VolumeSteps";
    static final int TOP = 32;
    private static final float TOP_TARGET_DBFS = -12f;
    private static final float PROBE_AMPLITUDE = 0.005f;
    private static final float TONE_HZ = 1000f;
    private static final float SLOT_S = 0.3f;
    /** Where in each slot the level is read: after the step has reached the speakers. */
    private static final float READ_FROM_S = 0.18f, READ_TO_S = 0.28f;
    private static final float MIN_SNR_DB = 10f;
    /** dB of each step against {@link #TOP}, index 0 = step 1, comma separated; NaN = not read. */
    static final String PREF_TABLE = "volume_step_db";

    private VolumeSteps() {
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

    private static void run(Context app) {
        if (CallState.isActive()) {
            Log.w(TAG, "phone call in progress - not measured");
            return;
        }
        final MicrophoneGuard.Outcome mic = MicrophoneGuard.ensureOurs(app);
        if (mic.needsRestart) {
            Log.w(TAG, "the microphone is held by another app: " + mic);
            return;
        }
        final SharedPreferences prefs = app.getSharedPreferences(RoomMeasurement.PREFS_NAME, Context.MODE_PRIVATE);
        McuService.ensureStarted(app);
        VolumeHelper.init(app);
        final int origVolume = VolumeHelper.getVolume();
        prefs.edit().putString(RoomMeasurement.PREF_RECOVERY, RoomMeasurement.PREF_LAST_SELECTED + "="
                + prefs.getString(RoomMeasurement.PREF_LAST_SELECTED, null) + ";saved_volume=" + origVolume).apply();
        final int sr = RoomMeasurement.SAMPLE_RATE;
        try {
            RoomMeasurement.pauseMedia(app);
            // 1. A quiet probe at the top step: how loud this microphone hears it.
            setVolume(TOP);
            RoomMeasurement.sleep(600);
            final int probeLen = sr;
            final short[] probe = tone(probeLen, PROBE_AMPLITUDE, 0);
            final short[] probeRec = new short[probeLen + sr / 2];
            final RoomMeasurement.Result pass = new RoomMeasurement.Result();
            int got = RoomMeasurement.playAndRecord(app, probe, probeRec, pass, null, null, false);
            if (got <= 0 || pass.error != null) {
                Log.w(TAG, "probe failed: " + pass.error);
                return;
            }
            final double probeDb = levelDb(probeRec, (int) (0.4f * sr), (int) (0.9f * sr));
            final double amplitude = Math.max(0.002, Math.min(0.5,
                    PROBE_AMPLITUDE * Math.pow(10.0, (TOP_TARGET_DBFS - probeDb) / 20.0)));
            Log.i(TAG, String.format(Locale.US, "probe at step %d: %.1f dBFS at 1 kHz with amplitude %.4f -> %.4f",
                    TOP, probeDb, PROBE_AMPLITUDE, amplitude));

            // 2. The walk: TOP .. 1, one step per slot, the tone unbroken.
            final int lead = (int) (0.3f * sr);
            final int slot = (int) (SLOT_S * sr);
            final short[] walk = tone(lead + TOP * slot, amplitude, lead);
            final short[] rec = new short[lead + TOP * slot + sr / 2];
            got = RoomMeasurement.playAndRecord(app, walk, rec, pass, startMs -> new Thread(() -> {
                for (int k = 1; k < TOP; k++) {
                    final long at = (long) ((lead + (long) k * slot) * 1000L / sr);
                    final long wait = at - (System.currentTimeMillis() - startMs);
                    if (wait > 0) RoomMeasurement.sleep(wait);
                    setVolume(TOP - k);
                }
            }, "wDSP_VolumeWalk").start(), null, true);
            if (got <= 0 || pass.error != null) {
                Log.w(TAG, "walk failed: " + pass.error);
                return;
            }
            final double noiseDb = levelDb(rec, 0, lead);
            final double[] step = new double[TOP + 1];
            for (int k = 0; k < TOP; k++) {
                final int from = lead + k * slot + (int) (READ_FROM_S * sr);
                final int to = Math.min(got, lead + k * slot + (int) (READ_TO_S * sr));
                step[TOP - k] = to > from ? levelDb(rec, from, to) : Double.NaN;
            }
            report(app, prefs, step, noiseDb, amplitude);
        } finally {
            MicrophoneGuard.releaseHold();
            setVolume(origVolume);
            prefs.edit().remove(RoomMeasurement.PREF_RECOVERY).apply();
        }
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

    private static void report(Context app, SharedPreferences prefs, double[] step, double noiseDb, double amplitude) {
        final StringBuilder txt = new StringBuilder(String.format(Locale.US,
                "volume steps: 1 kHz tone, amplitude %.4f, noise at 1 kHz %.1f dBFS; dB against step %d"
                        + " and the drop from the step above\n", amplitude, noiseDb, TOP));
        final StringBuilder csv = new StringBuilder();
        for (int v = 1; v <= TOP; v++) {
            final boolean ok = !Double.isNaN(step[v]) && step[v] - noiseDb >= MIN_SNR_DB;
            final double rel = ok ? step[v] - step[TOP] : Double.NaN;
            final double drop = ok && v < TOP && !Double.isNaN(step[v + 1]) ? step[v + 1] - step[v] : Double.NaN;
            txt.append(String.format(Locale.US, "  step %2d  %7.1f dBFS  %7s dB  %6s dB/step\n", v, step[v],
                    ok ? String.format(Locale.US, "%+.1f", rel) : "-",
                    Double.isNaN(drop) ? "-" : String.format(Locale.US, "%.1f", drop)));
            if (v > 1) csv.append(',');
            csv.append(ok ? String.format(Locale.US, "%.2f", rel) : "NaN");
        }
        prefs.edit().putString(PREF_TABLE, csv.toString()).apply();
        Log.i(TAG, txt.toString());
        final File dir = app.getExternalFilesDir(null);
        if (dir == null) return;
        try (FileWriter w = new FileWriter(new File(dir, "volume_steps.txt"))) {
            w.write(txt.toString());
        } catch (Exception e) {
            Log.w(TAG, "could not write volume_steps.txt", e);
        }
    }
}
