package com.radiorubka.wdsp;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * The control sweep: one sweep through every speaker at once, with the preset the measurement has
 * just applied, against a forecast of what it should give (owner, 13.10.2026: «контрольний свіп»,
 * run right after «Застосувати»).
 *
 * <h2>Why it exists</h2>
 *
 * The measurement sweeps each speaker on its own, so its cabin response adds their levels as
 * energy and cannot see phase. A subwoofer in antiphase with the doors at the crossover cancels
 * there - 📻 the owner's bench, 13.10.2026: his flip of the sub read polarity -1 at the crossover -
 * and the separate sweeps show nothing of it. Only the speakers playing together do.
 *
 * <h2>The forecast</h2>
 *
 * Each speaker's own sweep, moved from the settings it was recorded through to the preset's: a door
 * was swept flat (no EQ, no high-pass), so it gets the preset's door path; the subwoofer was swept at
 * {@link RoomMeasurement#SUB_PASS_GAIN_DB} through a 160 Hz low-pass, which comes out, and the preset's
 * sub path goes in ({@link DspResponse#pathsAt}, from the state McuService last published). The paths
 * are summed as energy - no phase in it. So where the sub and the doors overlap, a sum in phase
 * reads up to 3 dB ABOVE the forecast, and a sum that cancels reads below it.
 *
 * <p>The criterion, written down before the first run: a third-octave between 40 and 200 Hz where
 * both paths play within 10 dB of each other, {@link #CANCEL_DB} or more under the forecast, is a
 * cancellation. On the bench, the sub flipped should give one and the sub as wired should not; if
 * neither does, or both do, the criterion is wrong and this says nothing.
 *
 * <p>Not modelled: the fader and balance (taken as 0 dB on every door, as at their centre), and the
 * subwoofer leaking into the doors' own sweeps (on those it stays at its lowest crossover, 0 dB).
 */
final class ControlSweep {

    private static final String TAG = "wDSP_ControlSweep";

    /** The window where a subwoofer and the doors can meet. */
    private static final float OVERLAP_FROM_HZ = 40f;
    private static final float OVERLAP_TO_HZ = 200f;
    /** Further apart than this, one path alone fills the third and nothing can cancel. */
    private static final float OVERLAP_SPAN_DB = 10f;
    /** Under the forecast by this much at a shared third = the two cancel. */
    static final float CANCEL_DB = 6f;
    /** A third or a speaker's contribution below this SNR is left out. */
    private static final float MIN_SNR_DB = 10f;
    /**
     * The service reloads on a preset change and then sends every frame - the same room the
     * measurement gives its scratch preset (twice its routing settle).
     */
    private static final long PRESET_PUSH_MS = 1600;

    /** What the control sweep heard, against what it expected. */
    static final class Outcome {
        public String preset;
        public final float[] combinedThirds = new float[NativeSweep.THIRD_COUNT];
        public final float[] noiseThirds = new float[NativeSweep.THIRD_COUNT];
        public final float[] forecastThirds = new float[NativeSweep.THIRD_COUNT];
        /** Whether both paths play within {@link #OVERLAP_SPAN_DB} at that third, in the window. */
        public final boolean[] shared = new boolean[NativeSweep.THIRD_COUNT];
        public final float[] combined16 = new float[NativeSweep.BAND_COUNT];
        public final float[] noise16 = new float[NativeSweep.BAND_COUNT];
        public boolean forecastKnown;
        /** The shared third furthest under the forecast, and by how much (negative = under). */
        public float worstHz = Float.NaN;
        public float worstDb = Float.NaN;
        public float peakDbfs = Float.NEGATIVE_INFINITY;
        public int clipped;
        public String error;

        Outcome() {
            Arrays.fill(forecastThirds, Float.NaN);
        }
    }

    private ControlSweep() {
    }

    static void run(Context app, RoomMeasurement.Result measured, RoomMeasurement.Listener listener) {
        final Outcome out = new Outcome();
        measured.control = out;
        if (CallState.isActive()) {
            out.error = "phone call in progress";
            return;
        }
        progress(app, listener, 0);

        final MicrophoneGuard.Outcome mic = MicrophoneGuard.ensureOurs(app);
        if (mic.needsRestart) {
            out.error = "the microphone is held by another app: " + mic;
            return;
        }

        final SharedPreferences prefs = app.getSharedPreferences(RoomMeasurement.PREFS_NAME, Context.MODE_PRIVATE);
        out.preset = prefs.getString(RoomMeasurement.PREF_LAST_SELECTED, null);
        McuService.ensureStarted(app);
        VolumeHelper.init(app);
        final int origVolume = VolumeHelper.getVolume();
        // The same recovery record as a measurement's: if this dies half-way, the service start
        // puts the volume back (RoomMeasurement.restoreIfInterrupted).
        prefs.edit().putString(RoomMeasurement.PREF_RECOVERY, RoomMeasurement.PREF_LAST_SELECTED + "="
                + out.preset + ";saved_volume=" + origVolume).apply();

        final int sr = RoomMeasurement.SAMPLE_RATE;
        try (NativeSweep sweep = new NativeSweep(sr, RoomMeasurement.SUB_SWEEP_START_HZ,
                measured.sweepTopHz, measured.sweepSeconds);
             NativeSweep doorSweep = new NativeSweep(sr, RoomMeasurement.SWEEP_START_HZ,
                     measured.sweepTopHz, measured.sweepSeconds)) {
            if (!sweep.isValid() || !doorSweep.isValid()) {
                out.error = "the sweep was not built";
                return;
            }
            VolumeHelper.setVolume(measured.measureVolume);
            VolumeHelper.setVolumeForType("media_type", measured.measureVolume);
            // The preset just applied, through the one path every push takes.
            app.sendBroadcast(new Intent("com.radiorubka.wdsp.RESET_AUDIO_MCU").setPackage(app.getPackageName()));
            RoomMeasurement.sleep(PRESET_PUSH_MS);
            RoomMeasurement.pauseMedia(app);

            final int lead = (int) (RoomMeasurement.LEAD_SECONDS * sr);
            final int sweepLen = sweep.length();
            final int recordLen = lead + sweepLen + (int) (RoomMeasurement.TAIL_SECONDS * sr);
            final float[] mono = new float[sweepLen];
            sweep.generate(mono, measured.amplitude);
            final short[] stereo = new short[(lead + sweepLen) * 2];
            for (int i = 0; i < sweepLen; i++) {
                final short v = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, Math.round(mono[i] * Short.MAX_VALUE)));
                stereo[(lead + i) * 2] = v;
                stereo[(lead + i) * 2 + 1] = v;
            }
            final short[] captured = new short[recordLen];
            // Its own result, so the pass's focus, source and errors do not overwrite the measurement's.
            final RoomMeasurement.Result pass = new RoomMeasurement.Result();
            final int got = RoomMeasurement.playAndRecord(app, stereo, captured, pass, null,
                    done -> progress(app, listener, (int) (90L * done / recordLen)));
            if (got < 0 || pass.error != null) {
                out.error = pass.error != null ? pass.error : "the pass did not start";
                return;
            }
            RoomMeasurement.writeWav(app, "control_sweep.wav", captured, got);
            analyse(captured, got, sweep, doorSweep, measured, out);
        } finally {
            MicrophoneGuard.releaseHold();
            VolumeHelper.setVolume(origVolume);
            VolumeHelper.setVolumeForType("media_type", origVolume);
            prefs.edit().remove(RoomMeasurement.PREF_RECOVERY).apply();
        }
        judge(measured, out);
        appendToReport(measured, out);
        progress(app, listener, 100);
    }

    private static void progress(Context app, RoomMeasurement.Listener listener, int percent) {
        if (listener == null) return;
        listener.onProgress(1, 1, app.getString(R.string.room_stage_control),
                app.getString(R.string.room_stage_control_detail), percent);
    }

    private static void analyse(short[] captured, int got, NativeSweep sweep, NativeSweep doorSweep,
                                RoomMeasurement.Result measured, Outcome out) {
        final float[] signal = new float[got];
        int peak = 0;
        for (int i = 0; i < got; i++) {
            signal[i] = captured[i] / 32768f;
            final int mag = Math.abs(captured[i]);
            if (mag > peak) peak = mag;
            if (mag >= RoomMeasurement.CLIP_MAGNITUDE) out.clipped++;
        }
        out.peakDbfs = (float) (20 * Math.log10(peak / 32768f + 1e-9f));

        final float[] a = new float[NativeSweep.RESULT_SIZE];
        if (!sweep.analyse(signal, got, a)) {
            out.error = "nothing in the recording looked like the sweep";
            return;
        }
        // Its wider sweep (from 15 Hz) reads quieter than the doors' by the ratio of the spans, as the
        // sub's narrower one reads louder; on the doors' scale, the speakers' own sweeps are comparable.
        final float off = sweep.levelOffsetDbAgainst(doorSweep);
        for (int b = 0; b < NativeSweep.BAND_COUNT; b++) {
            out.combined16[b] = a[NativeSweep.BANDS + b] - off;
            out.noise16[b] = a[NativeSweep.NOISE_BANDS + b] - off;
        }
        for (int i = 0; i < NativeSweep.THIRD_COUNT; i++) {
            out.combinedThirds[i] = a[NativeSweep.THIRDS + i] - off;
            out.noiseThirds[i] = a[NativeSweep.NOISE_THIRDS + i] - off;
        }
        forecast(measured, out);
    }

    /** Each speaker's own sweep through the preset's path for it, summed as energy. */
    private static void forecast(RoomMeasurement.Result measured, Outcome out) {
        final int n = NativeSweep.THIRD_COUNT;
        final float[] hz = new float[n];
        for (int i = 0; i < n; i++) hz[i] = NativeSweep.thirdCentreHz(i);
        final float[][] path = new float[3][n];
        out.forecastKnown = AudioSpectrumEngine.getInstance().dspPathsAt(hz, path);
        if (!out.forecastKnown || measured.channels == null) return;
        final float subPassLpfHz = DspResponse.SUB_FREQS_HZ[RoomMeasurement.SUB_PASS_LPF_IDX];

        for (int i = 0; i < n; i++) {
            double doors = 0, sub = 0;
            for (RoomMeasurement.Channel ch : RoomMeasurement.Channel.values()) {
                final int ord = ch.ordinal();
                if (ord >= measured.channels.length) continue;
                final RoomMeasurement.ChannelResult c = measured.channels[ord];
                if (c == null || !c.heardAtAll) continue;
                if (c.thirdsDb[i] - c.noiseThirdsDb[i] < MIN_SNR_DB) continue;
                if (ch == RoomMeasurement.Channel.SUBWOOFER) {
                    if (Float.isInfinite(path[DspResponse.PATH_SUB][i])) continue;
                    final float recordedThrough = RoomMeasurement.SUB_PASS_GAIN_DB
                            + DspResponse.lowPass2Db(hz[i], subPassLpfHz);
                    sub += Math.pow(10.0, (c.thirdsDb[i] - recordedThrough + path[DspResponse.PATH_SUB][i]) / 10.0);
                } else {
                    final boolean front = ch == RoomMeasurement.Channel.FRONT_LEFT
                            || ch == RoomMeasurement.Channel.FRONT_RIGHT;
                    final float p = path[front ? DspResponse.PATH_FRONT : DspResponse.PATH_REAR][i];
                    doors += Math.pow(10.0, (c.thirdsDb[i] + p) / 10.0);
                }
            }
            if (doors + sub <= 0) continue;
            out.forecastThirds[i] = (float) (10.0 * Math.log10(doors + sub));
            out.shared[i] = hz[i] >= OVERLAP_FROM_HZ && hz[i] <= OVERLAP_TO_HZ && doors > 0 && sub > 0
                    && Math.abs(10.0 * Math.log10(doors / sub)) <= OVERLAP_SPAN_DB;
        }
    }

    /** The finding: a cancellation at the crossover, or a note that the two add up there. */
    private static void judge(RoomMeasurement.Result measured, Outcome out) {
        if (out.error != null || !out.forecastKnown) return;
        for (int i = 0; i < NativeSweep.THIRD_COUNT; i++) {
            if (!out.shared[i] || Float.isNaN(out.forecastThirds[i])) continue;
            if (out.combinedThirds[i] - out.noiseThirds[i] < MIN_SNR_DB) continue;
            final float diff = out.combinedThirds[i] - out.forecastThirds[i];
            if (Float.isNaN(out.worstDb) || diff < out.worstDb) {
                out.worstDb = diff;
                out.worstHz = NativeSweep.thirdCentreHz(i);
            }
        }
        if (Float.isNaN(out.worstDb)) return;   // no shared third: nothing could cancel
        final boolean cancels = out.worstDb <= -CANCEL_DB;
        measured.defects.add(new CabinDefect(cancels ? CabinDefect.Kind.SUMMED_CANCELLATION
                : CabinDefect.Kind.SUMMED_OK, CabinDefect.Certainty.LIKELY, "", 0, 0, out.worstHz, out.worstDb));
        Log.i(TAG, String.format(Locale.US, "worst shared third %.0f Hz: %+.1f dB against the forecast -> %s",
                out.worstHz, out.worstDb, cancels ? "cancellation" : "adds up"));
    }

    private static void appendToReport(RoomMeasurement.Result measured, Outcome out) {
        if (measured.reportPath == null) return;
        final StringBuilder sb = new StringBuilder("\nCONTROL SWEEP: every speaker at once through the applied "
                + "preset \"" + out.preset + "\". Forecast = each speaker's own sweep through the preset's "
                + "path for it, summed as energy - no phase in it, so a sum in phase reads up to 3 dB above "
                + "it where the sub and the doors overlap, and a cancellation reads below it.\n");
        if (out.error != null) {
            sb.append("  failed: ").append(out.error).append('\n');
        } else {
            sb.append(String.format(Locale.US, "  peak %.1f dBFS, %d samples on the rail%s\n", out.peakDbfs,
                    out.clipped, out.forecastKnown ? "" : "; no forecast - the service had published no preset"));
            sb.append("  third Hz   forecast  combined   diff   SNR  shared\n");
            for (int i = 0; i < NativeSweep.THIRD_COUNT; i++) {
                final float f = out.forecastThirds[i];
                sb.append(String.format(Locale.US, "  %8.0f  %8s  %8.1f  %5s  %4.0f  %s\n",
                        NativeSweep.thirdCentreHz(i),
                        Float.isNaN(f) ? "-" : String.format(Locale.US, "%.1f", f),
                        out.combinedThirds[i],
                        Float.isNaN(f) ? "-" : String.format(Locale.US, "%+.1f", out.combinedThirds[i] - f),
                        out.combinedThirds[i] - out.noiseThirds[i],
                        out.shared[i] ? "yes" : ""));
            }
            sb.append("  combined, 16 bands dB:");
            for (float v : out.combined16) sb.append(String.format(Locale.US, " %.1f", v));
            sb.append('\n');
            if (Float.isNaN(out.worstDb)) {
                sb.append("  no third where the subwoofer and the doors both play within ")
                        .append((int) OVERLAP_SPAN_DB).append(" dB - nothing to cancel, nothing judged\n");
            } else {
                sb.append(String.format(Locale.US, "  worst shared third %.0f Hz: %+.1f dB against the forecast"
                        + " (a cancellation at %.0f dB or more under)\n", out.worstHz, out.worstDb, CANCEL_DB));
            }
        }
        try (FileWriter w = new FileWriter(measured.reportPath, true)) {
            w.write(sb.toString());
        } catch (IOException e) {
            Log.w(TAG, "could not append to the report", e);
        }
        Log.i(TAG, sb.toString());
    }
}
