package com.radiorubka.wdsp;

import android.content.Context;
import android.util.Log;

import java.util.Locale;

/**
 * Which volume step a measurement runs at, chosen by the app rather than by the person.
 *
 * <p>Owner, 14.10.2026: «рекомендована гучність ніде не вказана… на моєму апараті оптимально 8, а у
 * людей як ставити? Чи ти б якось сам би визначав». Until now the step was
 * {@code min(16, whatever is set)} and the sweep's digital amplitude was trimmed to suit it
 * ({@code RoomMeasurement.probeLevel}), which is the wrong half of the gain stage to move: on a
 * sensitive chain the amplitude fell to the converter's floor and the analogue path was turned up
 * instead, raising the DAC's and the BU32107's own noise by 15-25 dB
 * (research/ARGON_MEASURE_VOLUME_14_10.md, finding 1). So the digital level stays high and the
 * <em>step</em> is what this class picks.
 *
 * <p>About four seconds, before the sweeps, through the flat scratch preset with every speaker
 * routed as the loudest pass will have them:
 * <ol>
 *   <li><b>Silence.</b> {@link #SILENCE_S} with nothing playing. Broadband over
 *   {@link #NOISE_BROADBAND_DBFS}, or 63/80 Hz over {@link #NOISE_BASS_DBFS}, and the run refuses
 *   with the one fix the person has: the engine and the fan off.</li>
 *   <li><b>A dual tone</b>, 80 Hz and 1 kHz together at {@link #TONE_AMPLITUDE}, on
 *   {@link #PROBE_STEP}. One tone in the bass because that is where the amplifier and the door
 *   woofers run out first, one in the midrange because that is where the chain is honest.</li>
 *   <li><b>The step</b> that would put a sweep at {@link #SWEEP_AMPLITUDE} at
 *   {@link RoomMeasurement#LEVEL_TARGET_DBFS}, read off the measured dB-per-step table
 *   ({@link VolumeSteps}), held in {@link #MIN_STEP}..{@link #MAX_STEP} and raised while the 80 Hz
 *   tone stands less than {@link #MIN_BASS_SNR_DB} over the cabin.</li>
 *   <li><b>A check on that step</b> (approached through {@code v-2}, so nothing jumps): the
 *   harmonics of both tones, and whether 80 Hz actually gained what the table promised. A chain
 *   that distorts or stops gaining is one or two steps down, and the level probe makes the
 *   difference up digitally - it is amplifier clipping and door woofers past their excursion that
 *   this catches, neither of which the digital probe can see.</li>
 * </ol>
 *
 * <p>What it cannot cover is written down rather than implied: cabin rattles, and - at a low step -
 * the cabin's own bottom end. A transient during the tone reads as distortion, so it costs a step
 * or two rather than failing the run.
 */
final class MeasureVolume {

    private static final String TAG = "wDSP_MeasureVolume";

    /** The step the dual tone plays on: low enough to be safe anywhere, high enough to be heard. */
    static final int PROBE_STEP = 8;
    /** The band the choice is held to: below 6 the cabin's own noise wins, above 18 nothing is gained. */
    static final int MIN_STEP = 6, MAX_STEP = 18;
    static final float SILENCE_S = 0.4f;
    static final float TONE_S = 0.35f;
    static final float TONE_AMPLITUDE = 0.10f;
    /** The amplitude the step is chosen for - what the level probe then trims around. */
    static final float SWEEP_AMPLITUDE = 0.22f;
    private static final float BASS_HZ = 80f, MID_HZ = 1000f;
    /** The cabin is not quiet enough to measure in. */
    private static final float NOISE_BROADBAND_DBFS = -42f;
    private static final float NOISE_BASS_DBFS = -38f;
    /** How far the 80 Hz tone must stand over the cabin before deconvolution. */
    private static final float MIN_BASS_SNR_DB = 20f;
    /** H2+H3 against the tone: the bass runs out first, so it is allowed more. */
    private static final float MAX_BASS_THD_DB = -18f;
    private static final float MAX_MID_THD_DB = -28f;
    /** How far 80 Hz may fall short of what the table promised before the chain is held to be out. */
    private static final float TABLE_TOLERANCE_DB = 1.5f;
    /** Where in each tone the level is read: after the step has reached the speakers. */
    private static final float READ_FROM = 0.45f, READ_TO = 0.95f;
    private static final float LEAD_S = 0.3f;

    private MeasureVolume() {
    }

    /** The chosen step, and everything the report needs to disagree with it. */
    static final class Outcome {
        /** The step the measurement should run at. */
        int step;
        /** Set when the cabin was too noisy to measure in at all; the run stops. */
        boolean tooNoisy;
        /** The cabin in silence: everything at once, and its bass. */
        float noiseDbfs = Float.NaN, noiseBassDbfs = Float.NaN;
        /** One line for the report and the log - what was heard and why this step. */
        String said = "";

        /** The noisier of the two readings, for the refusal the person sees. */
        int noiseForPerson() {
            return Math.round(Math.max(noiseDbfs, noiseBassDbfs));
        }
    }

    /**
     * Chooses the step. The scratch preset must already be selected and pushed, and the microphone
     * already ours: this plays sound.
     *
     * @param fallbackStep the step to keep when the probe cannot answer (what the person has set,
     *                     held to the band above) - a probe that fails is not a reason to refuse
     */
    static Outcome choose(Context app, int fallbackStep) {
        final Outcome out = new Outcome();
        out.step = Math.max(MIN_STEP, Math.min(MAX_STEP, fallbackStep));
        final StringBuilder said = new StringBuilder("measurement volume:");
        final int sr = RoomMeasurement.SAMPLE_RATE;

        RoomMeasurement.pauseMedia(app);

        // 1. Silence. Nothing is played, but it goes out through playAndRecord all the same, so the
        //    input is opened, set up and held exactly as it is for every other pass here.
        final short[] quiet = new short[(int) (SILENCE_S * sr) * 2];
        final short[] quietRec = new short[quiet.length / 2 + sr / 4];
        final RoomMeasurement.Result pass = new RoomMeasurement.Result();
        int got = RoomMeasurement.playAndRecord(app, quiet, quietRec, pass, null, null, false);
        if (got <= 0 || pass.error != null) {
            said.append(" the silence could not be recorded (").append(pass.error)
                    .append(") - step ").append(out.step).append(" kept");
            out.said = said.toString();
            Log.w(TAG, out.said);
            return out;
        }
        final int qTo = Math.min(got, (int) (SILENCE_S * sr));
        out.noiseDbfs = (float) SignalLevel.broadbandDb(quietRec, 0, qTo);
        out.noiseBassDbfs = (float) Math.max(SignalLevel.toneDb(quietRec, 0, qTo, 63f),
                SignalLevel.toneDb(quietRec, 0, qTo, BASS_HZ));
        said.append(String.format(Locale.US, " cabin %.1f dBFS broadband, %.1f at 63/80 Hz;",
                out.noiseDbfs, out.noiseBassDbfs));
        if (out.noiseDbfs > NOISE_BROADBAND_DBFS || out.noiseBassDbfs > NOISE_BASS_DBFS) {
            out.tooNoisy = true;
            said.append(" too noisy to measure in (limits ")
                    .append((int) NOISE_BROADBAND_DBFS).append(" / ")
                    .append((int) NOISE_BASS_DBFS).append(" dBFS)");
            out.said = said.toString();
            Log.w(TAG, out.said);
            return out;
        }

        // 2. The dual tone on PROBE_STEP.
        final Tone low = play(app, PROBE_STEP, said);
        if (low == null) {
            said.append(" - step ").append(out.step).append(" kept");
            out.said = said.toString();
            Log.w(TAG, out.said);
            return out;
        }

        // 3. The step that puts a sweep at the target. A tone at TONE_AMPLITUDE and a sweep at
        //    SWEEP_AMPLITUDE differ by a known number of decibels of digital level, so the step has
        //    to make up the rest.
        final float digitalGap = (float) (20 * Math.log10(SWEEP_AMPLITUDE / TONE_AMPLITUDE));
        final float needed = RoomMeasurement.LEVEL_TARGET_DBFS - (low.peakDbfs + digitalGap);
        final float[] table = VolumeSteps.table(app);
        int v;
        if (table != null) {
            v = PROBE_STEP;
            float best = Float.MAX_VALUE;
            for (int s = MIN_STEP; s <= MAX_STEP; s++) {
                final float miss = Math.abs(table[s] - table[PROBE_STEP] - needed);
                if (miss < best) {
                    best = miss;
                    v = s;
                }
            }
            said.append(String.format(Locale.US, " a sweep at %.2f needs %+.1f dB -> step %d (table %+.1f dB);",
                    SWEEP_AMPLITUDE, needed, v, table[v] - table[PROBE_STEP]));
        } else {
            // No table on this unit yet (the walk failed, or it is an old installation). The step
            // the person set is not overridden on a guess.
            v = out.step;
            said.append(String.format(Locale.US, " a sweep at %.2f needs %+.1f dB, but there is no"
                    + " volume-step table - step %d kept;", SWEEP_AMPLITUDE, needed, v));
        }
        // The bass has to stand over the cabin before deconvolution, or the bottom of the response
        // is the car's own noise however good the step looks for the peak.
        final float bassSnr = low.bassDbfs - out.noiseBassDbfs;
        while (v < MAX_STEP && bassSnr + stepGain(table, v, PROBE_STEP) < MIN_BASS_SNR_DB) v++;
        said.append(String.format(Locale.US, " 80 Hz stands %.1f dB over the cabin at %d%s;",
                bassSnr + stepGain(table, v, PROBE_STEP), v,
                bassSnr + stepGain(table, v, PROBE_STEP) < MIN_BASS_SNR_DB
                        ? " - under " + (int) MIN_BASS_SNR_DB + " dB even at the top of the band" : ""));

        // 4. The chain's own answer on that step. Approached from below so nothing jumps.
        if (v > 10) {
            setStep(Math.max(MIN_STEP, v - 2));
            RoomMeasurement.sleep(RoomMeasurement.PRESET_SETTLE_MS);
        }
        final Tone high = play(app, v, said);
        if (high == null) {
            out.step = v;
            said.append(" - nothing was checked, step ").append(v).append(" taken as chosen");
            out.said = said.toString();
            Log.w(TAG, out.said);
            return out;
        }
        int down = 0;
        if (high.bassThdDb > MAX_BASS_THD_DB) {
            down = Math.max(down, high.bassThdDb > MAX_BASS_THD_DB + 6 ? 2 : 1);
            said.append(String.format(Locale.US, " 80 Hz H2+H3 %.1f dB over the limit of %.0f;",
                    high.bassThdDb - MAX_BASS_THD_DB, MAX_BASS_THD_DB));
        }
        if (high.midThdDb > MAX_MID_THD_DB) {
            down = Math.max(down, 1);
            said.append(String.format(Locale.US, " 1 kHz H2+H3 %.1f dB over the limit of %.0f;",
                    high.midThdDb - MAX_MID_THD_DB, MAX_MID_THD_DB));
        }
        if (table != null) {
            final float promised = table[v] - table[PROBE_STEP];
            final float got80 = high.bassDbfs - low.bassDbfs;
            if (got80 < promised - TABLE_TOLERANCE_DB) {
                down = Math.max(down, promised - got80 > 3f ? 2 : 1);
                said.append(String.format(Locale.US, " 80 Hz gained %+.1f dB of the %+.1f the table"
                        + " promised from %d to %d;", got80, promised, PROBE_STEP, v));
            }
        }
        out.step = Math.max(MIN_STEP, v - down);
        said.append(down > 0
                ? String.format(Locale.US, " %d step(s) down -> %d", down, out.step)
                : String.format(Locale.US, " clean -> %d", out.step));
        out.said = said.toString();
        Log.i(TAG, out.said);
        return out;
    }

    /** What one step is worth against another, from the table; 0 without one. */
    private static float stepGain(float[] table, int to, int from) {
        return table == null ? 0f : table[to] - table[from];
    }

    /** One dual tone: what came back. */
    private static final class Tone {
        float peakDbfs, bassDbfs, midDbfs, bassThdDb, midThdDb;
    }

    /** Sets the step, plays the dual tone, reads it back. Null when the pass did not run. */
    private static Tone play(Context app, int step, StringBuilder said) {
        final int sr = RoomMeasurement.SAMPLE_RATE;
        setStep(step);
        RoomMeasurement.sleep(RoomMeasurement.PRESET_SETTLE_MS);
        final int lead = (int) (LEAD_S * sr);
        final int len = (int) (TONE_S * sr);
        final short[] out = new short[(lead + len) * 2];
        final double wl = 2 * Math.PI * BASS_HZ / sr, wm = 2 * Math.PI * MID_HZ / sr;
        for (int i = 0; i < len; i++) {
            // Half the amplitude each, so the pair together asks for TONE_AMPLITUDE and no more.
            final double s = TONE_AMPLITUDE / 2 * (Math.sin(wl * i) + Math.sin(wm * i));
            final short v = (short) Math.round(s * Short.MAX_VALUE);
            out[(lead + i) * 2] = v;
            out[(lead + i) * 2 + 1] = v;
        }
        final short[] rec = new short[lead + len + sr / 4];
        final RoomMeasurement.Result pass = new RoomMeasurement.Result();
        final int got = RoomMeasurement.playAndRecord(app, out, rec, pass, null, null, false);
        if (got <= 0 || pass.error != null) {
            said.append(" the tone on step ").append(step).append(" did not play (")
                    .append(pass.error).append(")");
            return null;
        }
        final int a = lead + (int) (READ_FROM * len), b = Math.min(got, lead + (int) (READ_TO * len));
        if (b - a < sr / 100) {
            said.append(" the tone on step ").append(step).append(" was recorded too short");
            return null;
        }
        final Tone t = new Tone();
        int peak = 0;
        for (int i = a; i < b; i++) peak = Math.max(peak, Math.abs(rec[i]));
        t.peakDbfs = (float) (20 * Math.log10(peak / 32768.0 + 1e-9));
        t.bassDbfs = (float) SignalLevel.toneDb(rec, a, b, BASS_HZ);
        t.midDbfs = (float) SignalLevel.toneDb(rec, a, b, MID_HZ);
        t.bassThdDb = harmonicsDb(rec, a, b, BASS_HZ, t.bassDbfs);
        t.midThdDb = harmonicsDb(rec, a, b, MID_HZ, t.midDbfs);
        said.append(String.format(Locale.US, " step %d: peak %.1f dBFS, 80 Hz %.1f (H2+H3 %.1f),"
                        + " 1 kHz %.1f (H2+H3 %.1f);",
                step, t.peakDbfs, t.bassDbfs, t.bassThdDb, t.midDbfs, t.midThdDb));
        return t;
    }

    /** H2 and H3 together, against the tone itself, in dB. */
    private static float harmonicsDb(short[] rec, int from, int to, double hz, float fundamentalDb) {
        final double h2 = Math.pow(10.0, SignalLevel.toneDb(rec, from, to, hz * 2) / 10.0);
        final double h3 = Math.pow(10.0, SignalLevel.toneDb(rec, from, to, hz * 3) / 10.0);
        return (float) (10 * Math.log10(h2 + h3 + 1e-12) - fundamentalDb);
    }

    private static void setStep(int v) {
        VolumeHelper.setVolume(v);
        VolumeHelper.setVolumeForType("media_type", v);
    }
}
