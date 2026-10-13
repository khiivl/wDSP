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
 * there and the separate sweeps show nothing of it. Only the speakers playing together do - 📻 the
 * owner's bench, 13.10.2026: the sub wired + to + gave a combined 80 Hz band 6 dB under the sub
 * inverted.
 *
 * <h2>The forecasts</h2>
 *
 * Each speaker's own sweep, moved from the settings it was recorded through to the preset's: a door
 * was swept flat (no EQ, no high-pass), the subwoofer at {@link RoomMeasurement#SUB_PASS_GAIN_DB}
 * through a 160 Hz low-pass. Two sums of them:
 * <ul>
 *   <li><b>energy</b> - the preset's paths ({@link DspResponse#pathsAt}, from the state McuService last
 *   published) on each speaker's level, summed as power: no phase. 📻 13.10.2026 it matched the
 *   combined sound within ±0.7 dB from 800 Hz up, so the levels and the EQ model hold;</li>
 *   <li><b>complex</b>, for the sub as it is and for the sub inverted - each impulse in the preset's
 *   state ({@link RoomMeasurement#toPresetState}), delayed as the preset delays it, summed as waves
 *   (research/RESEARCH_RESULT_SUB_CROSSOVER_PHASE.md). Up to 400 Hz, where the sub plays.</li>
 * </ul>
 *
 * <h2>The criterion, written down before the test (13.10.2026)</h2>
 *
 * At the third nearest the crossover: the combined sound within {@link #MODEL_TOLERANCE_DB} of the
 * complex forecast for the sub as it is says the model holds; then the inverted forecast
 * {@link #FLIP_GAIN_DB} or more above it says the sub and the doors cancel and the phase switch should
 * turn. Outside the tolerance there is no verdict. The energy criterion it replaces (6 dB under the
 * energy sum) told the bench's two wirings apart in neither (−2.4 and −1.1 dB).
 * Expected on the bench: + to + → cancels, flip gains ≥ 4 dB; + to − → adds up.
 *
 * <p>Not modelled: the fader and balance (taken as 0 dB on every door, as at their centre), and the
 * subwoofer leaking into the doors' own sweeps (on those it stays at its lowest crossover, 0 dB).
 */
final class ControlSweep {

    private static final String TAG = "wDSP_ControlSweep";

    /** The complex forecast for the sub as it is this close to the combined sound = the model holds. */
    static final float MODEL_TOLERANCE_DB = 1.5f;
    /** The inverted sub's forecast this far above the sub as it is = the two cancel now. */
    static final float FLIP_GAIN_DB = 4f;
    /** The complex forecast stops here: above it the subwoofer does not play. */
    private static final float COMPLEX_TOP_HZ = 400f;
    /** Frequencies per third the complex sums are averaged over. */
    private static final int POINTS_PER_THIRD = 8;
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
        /** The complex forecasts: the sub as it is, and inverted. NaN where not computed. */
        public final float[] complexAsIs = new float[NativeSweep.THIRD_COUNT];
        public final float[] complexFlipped = new float[NativeSweep.THIRD_COUNT];
        public final float[] combined16 = new float[NativeSweep.BAND_COUNT];
        public final float[] noise16 = new float[NativeSweep.BAND_COUNT];
        public boolean forecastKnown;
        /** The verdict's third, the combined sound against the as-is forecast, and what a flip gains. */
        public float verdictHz = Float.NaN;
        public float residualDb = Float.NaN;
        public float flipGainDb = Float.NaN;
        public float peakDbfs = Float.NEGATIVE_INFINITY;
        /** The combined sweep's impulse on the same time base as the speakers', for the dump. */
        float[] combinedImpulse;
        /** How much louder the combined (15 Hz) sweep reads than the doors' (20 Hz). */
        float combinedOffsetDb;
        public int clipped;
        public String error;

        Outcome() {
            Arrays.fill(forecastThirds, Float.NaN);
            Arrays.fill(complexAsIs, Float.NaN);
            Arrays.fill(complexFlipped, Float.NaN);
        }
    }

    private ControlSweep() {
    }

    /** @param restorePreset the person's own preset - what the recovery record puts back if this dies */
    static void run(Context app, RoomMeasurement.Result measured, RoomMeasurement.Listener listener,
                    String restorePreset) {
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
                + restorePreset + ";saved_volume=" + origVolume).apply();

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
            // The measured preset, through the one path every push takes.
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
            complexForecast(measured, out, prefs);
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
        final float[] imp = new float[got];
        final int impLen = sweep.deconvolve(signal, got, imp);
        out.combinedImpulse = impLen > 0 ? Arrays.copyOf(imp, impLen) : null;
        out.combinedOffsetDb = off;
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
        }
    }

    /**
     * The sub as it is and inverted, summed as waves with the doors: each heard speaker's impulse in the
     * preset's state, delayed as the preset delays it, the sub at the preset's gain over its pass's,
     * evaluated as a DFT at {@link #POINTS_PER_THIRD} frequencies per third. Written as the energy
     * forecast plus the complex sum's ratio to the energy sum of the same spectra, so it inherits the
     * energy forecast's calibrated level. The EQ is common to every speaker and leaves the ratio alone.
     */
    private static void complexForecast(RoomMeasurement.Result m, Outcome out, SharedPreferences prefs) {
        if (out.error != null || !out.forecastKnown || m.impulses == null || m.channels == null) return;
        final int subOrd = RoomMeasurement.Channel.SUBWOOFER.ordinal();
        if (subOrd >= m.channels.length || m.channels[subOrd] == null || !m.channels[subOrd].heardAtAll
                || subOrd >= m.impulses.length || m.impulses[subOrd] == null) return;
        final int[] x = AudioSpectrumEngine.getInstance().dspCrossover();
        if (x == null || x[2] < 0 || x[2] >= DspResponse.SUB_FREQS_HZ.length) return;
        final float hpfFront = DspResponse.doorHpfHz(x[0]);
        final float hpfRear = DspResponse.doorHpfHz(x[1]);
        final float subLpf = DspResponse.SUB_FREQS_HZ[x[2]];
        // The preset's gain over the pass's, and the sub's narrower sweep's extra level out of its impulse.
        final double subGain = Math.pow(10.0, (Math.max(0, Math.min(12, x[3])) - RoomMeasurement.SUB_PASS_GAIN_DB
                - m.subImpulseOffsetDb) / 20.0);
        final boolean delays = out.preset != null && prefs.getBoolean(out.preset + "_d_en", false);
        final int sr = RoomMeasurement.SAMPLE_RATE;

        // One stretch of the common time base for every speaker: from 100 ms before the earliest
        // impulse peak to 500 ms after the latest - the sub's ringing included.
        int first = Integer.MAX_VALUE, last = 0, len = Integer.MAX_VALUE;
        final RoomMeasurement.Channel[] all = RoomMeasurement.Channel.values();
        for (RoomMeasurement.Channel ch : all) {
            final int o = ch.ordinal();
            if (!usable(m, o)) continue;
            final float[] h = m.impulses[o];
            int p = 0;
            for (int n = 1; n < h.length; n++) if (Math.abs(h[n]) > Math.abs(h[p])) p = n;
            first = Math.min(first, p);
            last = Math.max(last, p);
            len = Math.min(len, h.length);
        }
        if (first == Integer.MAX_VALUE) return;
        final int from = Math.max(0, first - sr / 10);
        final int to = Math.min(len, last + sr / 2);
        if (to - from < sr / 10) return;

        final int n = NativeSweep.THIRD_COUNT;
        final double[][] reD = new double[n][POINTS_PER_THIRD], imD = new double[n][POINTS_PER_THIRD];
        final double[][] reS = new double[n][POINTS_PER_THIRD], imS = new double[n][POINTS_PER_THIRD];
        final double[][] energy = new double[n][POINTS_PER_THIRD];
        final java.util.List<Object[]> dump = new java.util.ArrayList<>();
        for (RoomMeasurement.Channel ch : all) {
            final int o = ch.ordinal();
            if (!usable(m, o)) continue;
            final boolean isSub = ch == RoomMeasurement.Channel.SUBWOOFER;
            final boolean front = ch == RoomMeasurement.Channel.FRONT_LEFT || ch == RoomMeasurement.Channel.FRONT_RIGHT;
            final float[] seg = Arrays.copyOfRange(m.impulses[o], from, to);
            RoomMeasurement.toPresetState(seg, isSub, front ? hpfFront : hpfRear, subLpf);
            final double gain = isSub ? subGain : 1.0;
            final double delaySec = delays ? prefs.getInt(out.preset + "_d_" + delayKey(ch), 0)
                    * RoomMeasurement.DELAY_STEP_MS / 1000.0 : 0.0;
            dump.add(new Object[]{ch.name(), seg, gain, delaySec});
            for (int i = 0; i < n; i++) {
                final double fc = NativeSweep.thirdCentreHz(i);
                if (fc > COMPLEX_TOP_HZ) break;
                for (int k = 0; k < POINTS_PER_THIRD; k++) {
                    final double f = fc * Math.pow(2.0, ((k + 0.5) / POINTS_PER_THIRD - 0.5) / 3.0);
                    final double w = 2 * Math.PI * f / sr;
                    // A rotating phasor instead of a sine and a cosine per sample.
                    final double cw = Math.cos(w), sw = -Math.sin(w);
                    double pr = 1, pi = 0, re = 0, im = 0;
                    for (float v : seg) {
                        re += v * pr;
                        im += v * pi;
                        final double t = pr * cw - pi * sw;
                        pi = pr * sw + pi * cw;
                        pr = t;
                    }
                    final double ph = -2 * Math.PI * f * delaySec;
                    final double dr = Math.cos(ph), di = Math.sin(ph);
                    final double r = gain * (re * dr - im * di), q = gain * (re * di + im * dr);
                    if (isSub) {
                        reS[i][k] += r;
                        imS[i][k] += q;
                    } else {
                        reD[i][k] += r;
                        imD[i][k] += q;
                    }
                    energy[i][k] += r * r + q * q;
                }
            }
        }
        if (out.combinedImpulse != null && out.combinedImpulse.length >= to) {
            dump.add(new Object[]{"COMBINED", Arrays.copyOfRange(out.combinedImpulse, from, to),
                    Math.pow(10.0, -out.combinedOffsetDb / 20.0), 0.0});
        }
        writeDump(m, dump, from);
        for (int i = 0; i < n; i++) {
            if (NativeSweep.thirdCentreHz(i) > COMPLEX_TOP_HZ || Float.isNaN(out.forecastThirds[i])) continue;
            double plus = 0, minus = 0, e = 0;
            for (int k = 0; k < POINTS_PER_THIRD; k++) {
                final double ar = reD[i][k] + reS[i][k], ai = imD[i][k] + imS[i][k];
                final double br = reD[i][k] - reS[i][k], bi = imD[i][k] - imS[i][k];
                plus += ar * ar + ai * ai;
                minus += br * br + bi * bi;
                e += energy[i][k];
            }
            if (e <= 0 || plus <= 0 || minus <= 0) continue;
            out.complexAsIs[i] = out.forecastThirds[i] + (float) (10.0 * Math.log10(plus / e));
            out.complexFlipped[i] = out.forecastThirds[i] + (float) (10.0 * Math.log10(minus / e));
        }
    }

    /**
     * Each speaker's impulse in the preset's state, and the combined one, for checking the complex
     * forecast off the unit: control_impulses.txt names them, control_impulses.f32 holds them
     * (float32 little-endian, one after another, already in the preset's state but before gain and delay).
     */
    private static void writeDump(RoomMeasurement.Result m, java.util.List<Object[]> dump, int from) {
        if (m.reportPath == null) return;
        final java.io.File dir = new java.io.File(m.reportPath).getParentFile();
        final StringBuilder txt = new StringBuilder("# name samples gain delay_s (from sample " + from
                + " of the common base, 48000 Hz)\n");
        try (java.io.DataOutputStream bin = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                new java.io.FileOutputStream(new java.io.File(dir, "control_impulses.f32"))))) {
            for (Object[] d : dump) {
                final float[] seg = (float[]) d[1];
                txt.append(String.format(Locale.US, "%s %d %.6f %.6f\n", d[0], seg.length, (double) d[2], (double) d[3]));
                for (float v : seg) bin.writeInt(Integer.reverseBytes(Float.floatToIntBits(v)));
            }
        } catch (IOException e) {
            Log.w(TAG, "could not write the impulse dump", e);
            return;
        }
        try (FileWriter w = new FileWriter(new java.io.File(dir, "control_impulses.txt"))) {
            w.write(txt.toString());
        } catch (IOException e) {
            Log.w(TAG, "could not write the impulse dump", e);
        }
    }

    private static boolean usable(RoomMeasurement.Result m, int ord) {
        return ord < m.channels.length && ord < m.impulses.length && m.channels[ord] != null
                && m.channels[ord].heardAtAll && m.impulses[ord] != null;
    }

    /** The preset's delay key for a channel ({@code <preset>_d_<key>}). */
    private static String delayKey(RoomMeasurement.Channel ch) {
        switch (ch) {
            case FRONT_LEFT: return "fl";
            case FRONT_RIGHT: return "fr";
            case REAR_LEFT: return "rl";
            case REAR_RIGHT: return "rr";
            default: return "sub";
        }
    }

    /** The verdict at the crossover, by the criterion in the class comment. */
    private static void judge(RoomMeasurement.Result measured, Outcome out) {
        if (out.error != null) return;
        final int[] x = AudioSpectrumEngine.getInstance().dspCrossover();
        if (x == null || x[2] < 0 || x[2] >= DspResponse.SUB_FREQS_HZ.length) return;
        final float crossoverHz = DspResponse.SUB_FREQS_HZ[x[2]];
        int at = 0;
        for (int i = 1; i < NativeSweep.THIRD_COUNT; i++) {
            if (Math.abs(Math.log(NativeSweep.thirdCentreHz(i) / crossoverHz))
                    < Math.abs(Math.log(NativeSweep.thirdCentreHz(at) / crossoverHz))) at = i;
        }
        if (Float.isNaN(out.complexAsIs[at]) || out.combinedThirds[at] - out.noiseThirds[at] < MIN_SNR_DB) return;
        out.verdictHz = NativeSweep.thirdCentreHz(at);
        out.residualDb = out.combinedThirds[at] - out.complexAsIs[at];
        out.flipGainDb = out.complexFlipped[at] - out.complexAsIs[at];
        final CabinDefect.Kind kind = Math.abs(out.residualDb) > MODEL_TOLERANCE_DB ? CabinDefect.Kind.SUMMED_NO_VERDICT
                : out.flipGainDb >= FLIP_GAIN_DB ? CabinDefect.Kind.SUMMED_CANCELLATION : CabinDefect.Kind.SUMMED_OK;
        measured.defects.add(new CabinDefect(kind, CabinDefect.Certainty.LIKELY, "", 0, 0, out.verdictHz,
                kind == CabinDefect.Kind.SUMMED_NO_VERDICT ? out.residualDb : out.flipGainDb));
        Log.i(TAG, String.format(Locale.US, "at %.0f Hz: combined %+.1f dB against the as-is forecast, "
                + "a flip would change it by %+.1f dB -> %s", out.verdictHz, out.residualDb, out.flipGainDb, kind));
    }

    private static String dB(float v) {
        return Float.isNaN(v) ? "-" : String.format(Locale.US, "%.1f", v);
    }

    private static void appendToReport(RoomMeasurement.Result measured, Outcome out) {
        if (measured.reportPath == null) return;
        final StringBuilder sb = new StringBuilder("\nCONTROL SWEEP: every speaker at once through the applied "
                + "preset \"" + out.preset + "\". Forecasts from each speaker's own sweep in the preset's state: "
                + "energy = summed as power (no phase); sub as is / inverted = summed as waves, with the "
                + "preset's filters and delays.\n");
        if (out.error != null) {
            sb.append("  failed: ").append(out.error).append('\n');
        } else {
            sb.append(String.format(Locale.US, "  peak %.1f dBFS, %d samples on the rail%s\n", out.peakDbfs,
                    out.clipped, out.forecastKnown ? "" : "; no forecast - the service had published no preset"));
            sb.append("  third Hz    energy  sub as is  inverted  combined  comb-as-is   SNR\n");
            for (int i = 0; i < NativeSweep.THIRD_COUNT; i++) {
                sb.append(String.format(Locale.US, "  %8.0f  %8s  %9s  %8s  %8.1f  %10s  %4.0f\n",
                        NativeSweep.thirdCentreHz(i), dB(out.forecastThirds[i]), dB(out.complexAsIs[i]),
                        dB(out.complexFlipped[i]), out.combinedThirds[i],
                        Float.isNaN(out.complexAsIs[i]) ? "-"
                                : String.format(Locale.US, "%+.1f", out.combinedThirds[i] - out.complexAsIs[i]),
                        out.combinedThirds[i] - out.noiseThirds[i]));
            }
            sb.append("  combined, 16 bands dB:");
            for (float v : out.combined16) sb.append(String.format(Locale.US, " %.1f", v));
            sb.append('\n');
            if (Float.isNaN(out.verdictHz)) {
                sb.append("  no verdict on the phase: no subwoofer path, no forecast, or the crossover third too noisy\n");
            } else {
                sb.append(String.format(Locale.US, "  at %.0f Hz the combined sound is %+.1f dB against the sub-as-is "
                        + "forecast (the model holds within %.1f) and inverting the sub would change it by %+.1f dB "
                        + "(it cancels now at +%.0f or more)\n", out.verdictHz, out.residualDb, MODEL_TOLERANCE_DB,
                        out.flipGainDb, FLIP_GAIN_DB));
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
