package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Measures the car, one loudspeaker at a time.
 *
 * <h2>What it does</h2>
 *
 * For each of the four speakers in turn it steers the sound to that speaker alone, plays a sweep,
 * listens with the microphone, and turns the recording back into an impulse response. From that
 * impulse response three things fall out:
 *
 * <ol>
 *   <li><b>when the sound arrived</b> - the difference between speakers is the time alignment the
 *       delay sliders exist to correct;</li>
 *   <li><b>whether it arrived the right way up</b> - a negative first peak means that speaker is
 *       wired with its terminals swapped, which is the usual reason a subwoofer sounds thin;</li>
 *   <li><b>how loud each band was</b> - the frequency response of speaker plus cabin plus
 *       microphone.</li>
 * </ol>
 *
 * <h2>Why the first two are trustworthy and the third is not</h2>
 *
 * The head unit's microphone is not a measurement microphone and nobody knows its response. That
 * does not matter for timing: an arrival is found by <i>when</i> energy appeared, and a microphone
 * that is six decibels down at 4 kHz still hears the arrival at the same instant. The same goes
 * for polarity, which is a sign, not a level.
 *
 * The frequency response is a different matter. Whatever error the microphone has is added to the
 * measurement in decibels, and if the equaliser were set to flatten what the microphone reports,
 * that error would be inverted straight into the sound. So this class <b>measures</b> the response
 * and writes it to the log, and deliberately stops there. Turning it into equaliser settings needs
 * a way to separate the microphone from the room, which is a separate problem with its own answer
 * (see {@code .agents/ROOM_CALIBRATION.md}).
 *
 * <h2>Why an exponential sweep rather than pink noise</h2>
 *
 * A sweep that rises exponentially spends the same amount of time in every octave, so the bottom
 * of the range - where a single cycle lasts fifty milliseconds - gets as much signal as the top.
 * Deconvolving the recording against the sweep's inverse filter collapses it back to an impulse,
 * and the loudspeaker's harmonic distortion lands at negative times, ahead of the impulse, where
 * it is simply discarded. Noise gives none of that: no impulse response, no arrival time, and
 * distortion mixed into the answer.
 *
 * <h2>What it borrows and gives back</h2>
 *
 * Measuring requires changing the head unit: the sound has to be steered to one speaker, the delay
 * lines have to be off (they would be measured as part of the room), and the equaliser has to be
 * flat (or it would be measured as part of the speaker). All of that belongs to the user, so every
 * value is written to a recovery preference before it is touched and restored afterwards. If the
 * app dies half way through, {@link #restoreIfInterrupted(Context)} puts it back at next start -
 * a measurement must never be able to leave somebody's car sounding wrong.
 *
 * The microphone is borrowed the same way: echo cancellation and noise suppression have to be off
 * while measuring - one exists to remove exactly the sound we are playing - but on head units with
 * custom audio policies they are switched on deliberately, so they go back on afterwards.
 *
 * <h2>How to run it</h2>
 *
 * <pre>
 *   adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM
 *   adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM --ef amp 0.35 --ef sec 3
 * </pre>
 *
 * Everything is logged under the tag {@code wDSP_RoomMeasure}, and the report and recordings are
 * written to {@link #outputDir(Context)} so that a measurement made in somebody else's car can be
 * sent back and examined properly rather than described over chat. Settings has a button that zips
 * the lot and hands it to the system share sheet.
 */
public final class RoomMeasurement {
    private static final String TAG = "wDSP_RoomMeasure";

    private static final int SAMPLE_RATE = 48000;
    /** 20 Hz is below anything a car door can reproduce, but the sweep costs nothing down there. */
    private static final float SWEEP_START_HZ = 20f;
    private static final float SWEEP_END_HZ = 20000f;
    /**
     * Where the sweep stops when the microphone is stuck at 16 kHz.
     *
     * An assistant hotword listener holds the microphone open on many head units and cannot always
     * be stopped - on the one this was written for it is a system app, and
     * {@code killBackgroundProcesses} does not touch it. Every other recording is then served from
     * its 16 kHz stream, resampled, and nothing above 8 kHz survives.
     *
     * Sweeping to 20 kHz in that state wastes more than half the signal: the energy is emitted,
     * never recorded, and the deconvolution has nothing to match it against. Sweeping to 7 kHz
     * instead puts all of it where the microphone can hear it.
     *
     * The cost is only sharpness, and not much of it: a 7 kHz band gives an impulse whose main
     * lobe is about 0.14 ms wide, while the delay sliders move in steps of 0.5 ms. What is lost is
     * the top of the frequency response, which this measurement does not act on anyway.
     */
    private static final float SWEEP_END_NARROW_HZ = 7000f;
    private static final float DEFAULT_SECONDS = 3f;
    /**
     * Amplitude of the sweep, not of the head unit.
     *
     * This class never touches the volume control: how loud the car plays is the user's decision
     * and their neighbours' business. A quarter of full scale is loud enough to measure at a
     * normal listening volume and quiet enough not to frighten anybody.
     */
    private static final float DEFAULT_AMPLITUDE = 0.25f;
    /** Recording continues past the last sweep so that the room's decay is captured too. */
    private static final float TAIL_SECONDS = 1.0f;
    /** Silence before the first sweep, and inside every window, so nothing starts abruptly. */
    private static final float LEAD_SECONDS = 0.5f;
    /**
     * Silence between sweeps.
     *
     * Long enough for two things at once: the cabin to stop ringing, and the MCU to act on the
     * routing change that is sent half way through it.
     */
    private static final float GAP_SECONDS = 1.5f;
    /**
     * Below this, the recording has no top end and the sweep is being measured through half a
     * microphone. Normal is around -15 dB; a stream that is really 16 kHz gives -70 or worse.
     */
    private static final float BANDWIDTH_WARN_DB = -30f;
    /** The fader and balance sliders run 0..24 with 12 in the middle. */
    private static final int FADER_MIN = 0;
    private static final int FADER_CENTRE = 12;
    private static final int FADER_MAX = 24;
    /** Equaliser gain indices run 0..12, and 6 is flat - see McuService.applyEqualizer(). */
    private static final int EQ_FLAT_INDEX = 6;
    /** Time for the MCU to act on a routing change before the sweep starts. */
    private static final long ROUTING_SETTLE_MS = 800;

    /**
     * Reported, no longer used to decide anything.
     *
     * It looked like a good test on a bench - silent channels gave 19 and 24, driven ones gave
     * thousands - and then the same driven speaker came back with 65 on one run and 2889 on the
     * next, with its arrival time unchanged to the sample. The average it divides by depends on
     * what else fell inside the window, which has nothing to do with whether a speaker was heard.
     * Kept in the report as a second opinion; see MIN_CLARITY_DB for what actually decides.
     */
    private static final float MIN_PROMINENCE = 200f;
    /**
     * The recording also has to contain something. A channel that was never driven still produces
     * an impulse response - of the room noise - and it can look convincing on its own.
     */
    private static final float MIN_PEAK = 0.01f;      // -40 dBFS
    /**
     * How far the direct sound has to stand above the room before its arrival time is believed.
     *
     * A speaker the microphone cannot see still produces an impulse response, and its peak is
     * still repeatable to the sample - it is simply the loudest moment of a diffuse smear rather
     * than the instant the sound arrived. Measured on a bench: the speaker facing the microphone
     * gave +14 dB, the one behind it +1 dB, and only the first of the two described a distance.
     */
    private static final float MIN_CLARITY_DB = 9f;
    /**
     * The largest difference in arrival times a car can physically produce.
     *
     * Sound covers about thirty-four centimetres in a millisecond, so even a long estate car
     * cannot separate its speakers by more than ten milliseconds or so. Thirty is generous to the
     * point of absurdity, which is the point: anything beyond it did not come from geometry, it
     * came from one of the channels not being heard, and offering it as a delay setting would
     * mean putting a second of silence into somebody's front speakers.
     */
    private static final float MAX_PLAUSIBLE_SPREAD_MS = 30f;

    /**
     * Where a measurement leaves its report and recordings.
     *
     * External cache rather than files, for two reasons: the system may reclaim it when space runs
     * short, which is right for something a tester sends once and forgets, and it is the path
     * declared in {@code res/xml/file_paths.xml} so that FileProvider is allowed to hand it to
     * Telegram.
     */
    private static final String OUTPUT_DIR = "measurements";

    /**
      * The preset a measurement runs through.
      *
      * Nothing is measured through the user's own preset any more. Their preset has an equaliser
      * curve, and very likely delay lines, loudness, bass boost and a high-pass - and a high-pass
      * is a real group delay at the bottom, which would be measured as if the loudspeaker were
      * further away than it is. Every user would then get a different and slightly wrong answer,
      * for reasons invisible in the result.
      *
      * So the measurement copies their preset, neutralises everything that colours or delays the
      * sound, switches to the copy, and switches back afterwards. Their own settings are never
      * written to at all, which also means a crash half way through cannot damage them.
      */
     private static final String SCRATCH_PRESET = "wDSP Flat";

     private static final String PREFS_NAME = "EqPresets";
    /** Holds everything a running measurement has changed, so it can be undone after a crash. */
    private static final String PREF_RECOVERY = "room_measure_recovery";

    private static volatile boolean running;
    /**
     * Diagnostic: play every sweep through the same routing.
     *
     * With the acoustics held identical, anything that still differs between the four windows
     * belongs to the measurement rather than to the car - which is the only way to tell a real
     * arrival difference from a drift between the recording clock and the playback clock.
     */
    private static volatile boolean sameRouting;

    public static void setSameRouting(boolean same) {
        sameRouting = same;
    }

    private RoomMeasurement() {
    }

    /**
     * The four speakers, and how to steer the sound to each one on its own.
     *
     * <p>Confirmed on real cars, 20.08.2026: testers ran the measurement and reported which
     * speaker played the first sweep. It was the <b>rear left</b>, from balance 0 and fader 0 - so
     * balance 0 is the left side as assumed, but fader 0 is the <b>rear</b>, not the front. The
     * table below is the corrected one; before this it named every result mirror-image front to
     * back. The arrival times themselves were never affected, only the labels on them.
     */
    private enum Channel {
        REAR_LEFT("rear left", FADER_MIN, FADER_MIN),
        REAR_RIGHT("rear right", FADER_MAX, FADER_MIN),
        FRONT_LEFT("front left", FADER_MIN, FADER_MAX),
        FRONT_RIGHT("front right", FADER_MAX, FADER_MAX);

        final String label;
        /** Balance: the value written to {@code <preset>_f_lr}. */
        final int leftRight;
        /** Fader: the value written to {@code <preset>_f_fr}. */
        final int frontRear;

        Channel(String label, int leftRight, int frontRear) {
            this.label = label;
            this.leftRight = leftRight;
            this.frontRear = frontRear;
        }
    }

    /** What one speaker's measurement found. */
    public static final class ChannelResult {
        public String label;
        /** Sample at which the sound arrived, counted from the start of the recording. */
        public int arrivalSamples;
        /**
         * Time of flight in milliseconds, measured on the monotonic clock.
         *
         * Not simply the arrival sample divided by the sample rate. Each channel gets its own
         * recording and its own playback, and the gap between "recording started" and "the first
         * sample of the sweep actually left" is different every time - measured at up to seven
         * milliseconds of variation between runs on a bench where nothing moved. A car is only
         * nine milliseconds wide, so that jitter would have swamped the answer.
         *
         * Both ends therefore report through the platform's own timestamps, which were shown to be
         * honest on this hardware while the picture-to-sound delay was being measured. What
         * remains is the sound's own journey plus a constant that every channel shares, and the
         * delays are differences, so the constant falls out.
         */
        public float arrivalMs;
        /** True when the timestamps were available; without them the delays are not trustworthy. */
        public boolean clockLocked;
        /** How far the arrival stood above everything else. Under ten means nothing was heard. */
        public float prominence;
        /** +1 normal, -1 wired backwards, 0 not determined. */
        public int polarity;
        /** Sixteen band levels in dB, on the hardware equaliser's grid. */
        public final float[] bandsDb = new float[NativeSweep.BAND_COUNT];
        /** Loudest sample in the recording, so a tester can see at once if it was too quiet. */
        public float recordedPeak;
        /** Level of the whole recording, which separates "quiet" from "one loud click". */
        public float recordedRms;
        /** Energy above 8 kHz against the band below it; far below -25 dB means a 16 kHz stream. */
        public float bandwidthDb;
        /**
         * How far the direct sound stood above the room, in decibels.
         *
         * This is what separates a speaker the microphone can see from one it cannot. Measured on
         * a bench: the speaker facing the microphone gave a sharp impulse and near silence after
         * it; the one sitting behind it gave a smear eleven times weaker whose level was still
         * within seven decibels of the peak a millisecond later. Both arrival times were
         * repeatable to the sample; only one of them meant a distance.
         */
        public float clarityDb;
        public boolean ok;
    }

    /** Everything a full measurement produced, ready to be logged or shown. */
    public static final class Result {
        public final ChannelResult[] channels = new ChannelResult[4];
        /** Delay in milliseconds to add to each channel so that all four arrive together. */
        public final float[] suggestedDelayMs = new float[4];
        /** The same delays in slider steps; the hardware moves in half-millisecond increments. */
        public final int[] suggestedDelaySteps = new int[4];
        public String error;
        public String reportPath;
        /** What the microphone guard found and did, in one line for the report. */
        public String microphone;
        /** Where the sweep stopped - lower than usual when the microphone could not be freed. */
        public float sweepTopHz = SWEEP_END_HZ;

        public boolean isUsable() {
            for (ChannelResult c : channels) {
                if (c == null || !c.ok) return false;
            }
            return true;
        }
    }

    public interface Listener {
        void onProgress(String stage);

        void onFinished(Result result);
    }

    public static boolean isRunning() {
        return running;
    }

    /** Runs a full measurement on its own thread. Takes roughly half a minute. */
    public static void measureAsync(final Context context, final float amplitude,
                                    final float seconds, final Listener listener) {
        if (running) {
            Log.w(TAG, "a measurement is already running, ignoring this request");
            return;
        }
        new Thread(() -> {
            running = true;
            Result result;
            try {
                result = measure(context, amplitude, seconds, listener);
            } catch (Throwable t) {
                result = new Result();
                result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.e(TAG, "measurement failed", t);
            } finally {
                running = false;
            }
            if (listener != null) listener.onFinished(result);
        }, "wDSP_RoomMeasure").start();
    }

    /**
     * Puts back anything a measurement changed but did not manage to restore.
     *
     * Called from the service at start-up. A measurement writes what it is about to change into a
     * single preference and clears it when it has finished; anything left there means the app died
     * with somebody's equaliser flattened and their sound coming out of one door.
     */
    public static void restoreIfInterrupted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(PREF_RECOVERY, null);
        if (saved == null || saved.isEmpty()) return;

        Log.w(TAG, "a previous measurement did not finish; restoring what it changed: " + saved);
        SharedPreferences.Editor editor = prefs.edit();
        applySaved(editor, saved);
        editor.remove(PREF_RECOVERY);
        editor.apply();
    }

    // ---------------------------------------------------------------------------------------
    // the measurement itself
    // ---------------------------------------------------------------------------------------

    private static Result measure(Context context, float amplitude, float seconds,
                                  Listener listener) {
        Result result = new Result();
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String preset = prefs.getString("last_selected_preset", null);
        if (preset == null) {
            result.error = "no preset is selected, so there is nothing to measure through";
            Log.e(TAG, result.error);
            return result;
        }
        if (!NativeSweep.isAvailable()) {
            result.error = "the native library is not loaded";
            Log.e(TAG, result.error);
            return result;
        }

        Log.i(TAG, "=== room measurement starting ===");
        Log.i(TAG, "preset=" + preset + " amplitude=" + amplitude + " sweep=" + seconds + " s");
        Log.i(TAG, HardwareProfile.describe());

        // Take the microphone back if something else has it, then sweep only as high as whatever
        // we ended up with can actually hear.
        MicrophoneGuard.Outcome mic = MicrophoneGuard.ensureOurs(app);
        result.microphone = mic.toString();
        final float topHz = (mic.wasHeld && !mic.freed) ? SWEEP_END_NARROW_HZ : SWEEP_END_HZ;
        if (topHz < SWEEP_END_HZ) {
            Log.w(TAG, "the microphone is limited to 16 kHz and could not be freed, so the sweep "
                    + "stops at " + (int) topHz + " Hz instead of " + (int) SWEEP_END_HZ
                    + " - delays are unaffected, the top of the response is not measured");
        }

        // Only one thing of the user's is touched: which preset is selected. Everything else
        // happens inside a copy.
        String saved = "last_selected_preset=" + preset;
        prefs.edit().putString(PREF_RECOVERY, saved).apply();
        buildScratchPreset(prefs, preset);
        Log.i(TAG, "measuring through " + SCRATCH_PRESET + ", copied from " + preset);

        try (NativeSweep sweep = new NativeSweep(SAMPLE_RATE, SWEEP_START_HZ, topHz, seconds)) {
            if (!sweep.isValid()) {
                result.error = "the sweep could not be built";
                return result;
            }
            runOnePass(app, prefs, SCRATCH_PRESET, sweep, amplitude, result, listener);
            computeDelays(result);
            result.sweepTopHz = topHz;
            result.reportPath = writeReport(app, result, preset, amplitude, seconds);
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            applySaved(editor, saved);
            editor.remove(PREF_RECOVERY);
            editor.apply();
            Log.i(TAG, "switched back to " + preset);
        }

        logResult(result);
        return result;
    }

    /**
     * Plays all four sweeps in one go and records them in one go.
     *
     * <h3>Why it has to be one pass</h3>
     *
     * The first version opened a fresh recording and a fresh playback for every speaker. That
     * looks tidier and it is wrong: the gap between "recording started" and "the first sample of
     * the sweep actually left the hardware" is different every time a stream is opened. Measured
     * on a bench where nothing moved, the same pair of speakers came out 6.4 ms apart, then
     * 2.7 ms, then 4.5 ms <i>the other way round</i>. A whole car is only nine milliseconds wide,
     * so that jitter was larger than the thing being measured.
     *
     * Platform timestamps did not rescue it either - a single reading taken early in playback is
     * not accurate enough to extrapolate back to the first frame.
     *
     * With one stream in each direction the skew between them is a single unknown constant for
     * the whole measurement. It appears identically in all four arrivals, and the delays are
     * differences, so it cancels exactly. Nothing has to be known about it at all.
     *
     * The routing is switched during the silence between sweeps, which is also where the cabin is
     * given time to stop ringing.
     */
    private static void runOnePass(Context context, SharedPreferences prefs, String preset,
                                   NativeSweep sweep, float amplitude, Result result,
                                   Listener listener) {
        final Channel[] channels = Channel.values();
        final int sweepLen = sweep.length();
        final int gap = (int) (GAP_SECONDS * SAMPLE_RATE);
        final int lead = (int) (LEAD_SECONDS * SAMPLE_RATE);
        final int period = sweepLen + gap;
        final int totalFrames = lead + channels.length * period;
        final int recordLen = totalFrames + (int) (TAIL_SECONDS * SAMPLE_RATE);

        float[] mono = new float[sweepLen];
        sweep.generate(mono, amplitude);

        // One long track: quiet, sweep, quiet, sweep, and so on.
        short[] stereo = new short[totalFrames * 2];
        for (int k = 0; k < channels.length; k++) {
            final int at = lead + k * period;
            for (int i = 0; i < sweepLen; i++) {
                short v = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, Math.round(mono[i] * Short.MAX_VALUE)));
                stereo[(at + i) * 2] = v;
                stereo[(at + i) * 2 + 1] = v;
            }
        }
        Log.i(TAG, "one pass: sweep " + sweepLen + " samples, gap " + gap + ", period " + period
                + ", total " + totalFrames + " frames (" + (totalFrames / (float) SAMPLE_RATE)
                + " s)");

        AudioTrack track = null;
        AudioRecord record = null;
        MicProbe.Suspension effects = null;
        short[] captured = new short[recordLen];
        int got = 0;

        try {
            // The first speaker is selected before anything starts, so its sweep is not the one
            // that has to wait for the routing to take effect.
            applyRouting(prefs, preset, channels[0]);
            sleep(ROUTING_SETTLE_MS);

            record = openMicrophone();
            if (record == null) {
                result.error = "the microphone could not be opened";
                Log.e(TAG, result.error);
                return;
            }
            effects = MicProbe.suspendCapturePreprocessing(record.getAudioSessionId(), TAG);

            track = openTrack(stereo.length);
            if (track == null) {
                result.error = "the output could not be opened";
                Log.e(TAG, result.error);
                return;
            }

            record.startRecording();
            track.play();
            final long playStartedMs = System.currentTimeMillis();

            final AudioTrack playing = track;
            Thread writer = new Thread(() -> {
                int offset = 0;
                while (offset < stereo.length) {
                    int written = playing.write(stereo, offset, stereo.length - offset);
                    if (written <= 0) break;
                    offset += written;
                }
            }, "wDSP_RoomSweepOut");
            writer.start();

            // Routing is switched in the silence before each sweep. The timing comes from the
            // wall clock rather than from frames written, because what matters is when the MCU
            // acts, and it acts on its own schedule - the gap is long enough to absorb both.
            Thread router = new Thread(() -> {
                for (int k = 1; k < channels.length; k++) {
                    final long switchAtMs = (long) ((lead + k * period - gap / 2)
                            * 1000L / SAMPLE_RATE);
                    long waitMs = switchAtMs - (System.currentTimeMillis() - playStartedMs);
                    if (waitMs > 0) sleep(waitMs);
                    if (listener != null) listener.onProgress(channels[k].label);
                    applyRouting(prefs, preset, channels[k]);
                }
            }, "wDSP_RoomRouting");
            if (listener != null) listener.onProgress(channels[0].label);
            router.start();

            while (got < recordLen) {
                int read = record.read(captured, got, recordLen - got);
                if (read <= 0) {
                    Log.w(TAG, "read returned " + read);
                    break;
                }
                got += read;
            }
            try {
                writer.join(2000);
                router.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Throwable t) {
            result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "the pass failed", t);
            return;
        } finally {
            if (effects != null) effects.restore();
            closeQuietly(track);
            closeQuietly(record);
        }

        // The whole recording is kept as one file. Four separate ones would have to be lined up
        // again by whoever looks at them, and lining them up is the entire difficulty.
        writeWav(context, "room_measurement.wav", captured, got);

        float[] asFloat = new float[got];
        int peak = 0;
        double sumSquares = 0;
        for (int i = 0; i < got; i++) {
            asFloat[i] = captured[i] / 32768f;
            if (Math.abs(captured[i]) > peak) peak = Math.abs(captured[i]);
            sumSquares += (double) asFloat[i] * asFloat[i];
        }
        final float passPeak = peak / 32768f;
        final float passRms = got > 0 ? (float) Math.sqrt(sumSquares / got) : 0f;
        final float passBandwidth = NativeSweep.bandwidthRatioDb(asFloat, got, SAMPLE_RATE);
        Log.i(TAG, String.format(Locale.US,
                "pass: %d frames, peak %.1f dBFS, rms %.1f dBFS, above 8 kHz %.1f dB",
                got, 20 * Math.log10(passPeak + 1e-9f), 20 * Math.log10(passRms + 1e-9f),
                passBandwidth));
        if (passBandwidth < BANDWIDTH_WARN_DB) {
            Log.w(TAG, "the recording has nothing above 8 kHz. The microphone is running at "
                    + "16 kHz because something else has it open - an assistant hotword is the "
                    + "usual cause, and the platform will not admit it.");
        }

        // Each sweep is cut out with a generous margin. The window is short enough that the next
        // sweep cannot fall inside it, so the strongest peak in each window belongs to the sweep
        // that window was cut for.
        final int windowLen = lead + sweepLen + (int) (1.0f * SAMPLE_RATE);
        float[] analysis = new float[NativeSweep.RESULT_SIZE];

        for (int k = 0; k < channels.length; k++) {
            ChannelResult cr = new ChannelResult();
            cr.label = channels[k].label;
            cr.recordedPeak = passPeak;
            cr.recordedRms = passRms;
            cr.bandwidthDb = passBandwidth;
            result.channels[k] = cr;

            final int from = k * period;
            final int len = Math.min(windowLen, got - from);
            if (len < sweepLen) {
                Log.w(TAG, cr.label + ": the recording ended before this sweep");
                continue;
            }
            float[] window = new float[len];
            System.arraycopy(asFloat, from, window, 0, len);

            int windowPeak = 0;
            double windowSum = 0;
            for (float v : window) {
                windowSum += (double) v * v;
                if (Math.abs(v) > windowPeak / 32768f) windowPeak = Math.round(Math.abs(v) * 32768f);
            }
            cr.recordedPeak = windowPeak / 32768f;
            cr.recordedRms = (float) Math.sqrt(windowSum / len);

            if (!sweep.analyse(window, len, analysis)) {
                Log.w(TAG, cr.label + ": nothing in this part of the recording looked like the "
                        + "sweep");
                continue;
            }
            cr.arrivalSamples = (int) analysis[NativeSweep.ARRIVAL];
            // Every window starts an exact number of periods into the same recording, so the
            // arrival inside it is directly comparable with the others. The unknown skew between
            // the recording and the playback is the same for all four and drops out of the
            // differences.
            cr.arrivalMs = cr.arrivalSamples * 1000f / SAMPLE_RATE;
            cr.clockLocked = true;
            cr.prominence = analysis[NativeSweep.PROMINENCE];
            cr.polarity = (int) analysis[NativeSweep.POLARITY];
            cr.clarityDb = analysis[NativeSweep.CLARITY];
            System.arraycopy(analysis, NativeSweep.BANDS, cr.bandsDb, 0, NativeSweep.BAND_COUNT);
            // Clarity decides, not prominence. Prominence compares the loudest instant of the
            // impulse response with its average, and the average moves with whatever else landed
            // in the window - measured on a bench, the same speaker gave 2889 on one run and 65
            // on the next while its arrival time stayed put to the sample. Clarity asks a
            // physical question instead: did the microphone hear this speaker directly, or only
            // the room repeating it. Prominence is still reported, because it costs nothing and a
            // second opinion is useful when a measurement looks odd.
            cr.ok = cr.clarityDb >= MIN_CLARITY_DB && cr.recordedPeak >= MIN_PEAK;

            Log.i(TAG, String.format(Locale.US,
                    "%s: arrival %.2f ms in its window (sample %d), clarity %.1f dB, "
                            + "prominence %.0f, polarity %+d, peak %.1f dBFS, rms %.1f dBFS%s",
                    cr.label, cr.arrivalMs, cr.arrivalSamples, cr.clarityDb, cr.prominence,
                    cr.polarity,
                    20 * Math.log10(cr.recordedPeak + 1e-9f),
                    20 * Math.log10(cr.recordedRms + 1e-9f),
                    cr.ok ? "" : "  <-- TOO WEAK TO TRUST"));
        }
    }

    /** Steers the sound to one speaker by pushing balance and fader to their extremes. */
    private static void applyRouting(SharedPreferences prefs, String preset, Channel channel) {
        if (sameRouting) {
            Log.i(TAG, "--- " + channel.label + ": routing held for the drift test ---");
            return;
        }
        Log.i(TAG, "--- " + channel.label + ": balance=" + channel.leftRight
                + " fader=" + channel.frontRear + " ---");
        prefs.edit()
                .putInt(preset + "_f_lr", channel.leftRight)
                .putInt(preset + "_f_fr", channel.frontRear)
                .apply();
    }

    /**
     * Turns arrival times into delay settings.
     *
     * The speaker that is furthest away is heard last, so it needs no delay at all; every other
     * speaker is held back until it arrives at the same moment. This is the one result that is
     * exact no matter what the microphone's response is, because it comes entirely from timing.
     */
    private static void computeDelays(Result result) {
        // The clearest channel is the reference. Every sweep is played at the same offset inside
        // its own window, so a channel that was genuinely heard must arrive within a few
        // milliseconds of it - the width of a car. A channel that was not driven at all still
        // produces an impulse response, of room noise, and its loudest moment lands wherever it
        // pleases: measured on a bench with the rear pair disconnected, the reference sat at
        // 590 ms and the two phantoms at 1267 and 1309.
        //
        // This catches them whatever their clarity happens to be, which matters because clarity
        // alone does not separate the two cleanly enough - the noisiest phantom measured 7.7 dB
        // against 10.5 dB for the quietest real speaker.
        ChannelResult anchor = null;
        for (ChannelResult c : result.channels) {
            if (c == null || !c.ok) continue;
            if (anchor == null || c.clarityDb > anchor.clarityDb) anchor = c;
        }
        if (anchor == null) {
            result.error = "no channel was heard clearly enough to measure";
            Log.w(TAG, result.error);
            return;
        }

        for (ChannelResult c : result.channels) {
            if (c == null || !c.ok || c == anchor) continue;
            final float apart = Math.abs(c.arrivalMs - anchor.arrivalMs);
            if (apart > MAX_PLAUSIBLE_SPREAD_MS) {
                c.ok = false;
                Log.w(TAG, String.format(Locale.US,
                        "%s: arrived %.0f ms from the clearest channel, which no car can do "
                                + "(%.0f ms is already ten metres) - not a real arrival",
                        c.label, apart, apart));
            }
        }

        float latest = Float.NEGATIVE_INFINITY;
        float earliest = Float.POSITIVE_INFINITY;
        int heard = 0;
        for (ChannelResult c : result.channels) {
            if (c == null || !c.ok) continue;
            latest = Math.max(latest, c.arrivalMs);
            earliest = Math.min(earliest, c.arrivalMs);
            heard++;
        }
        if (heard < 2) {
            result.error = "only " + heard + " channel(s) were heard clearly - nothing to align";
            Log.w(TAG, result.error);
            return;
        }
        Log.i(TAG, String.format(Locale.US,
                "%d channels agree, spread %.2f ms, reference is the %s at %.1f dB clarity",
                heard, latest - earliest, anchor.label, anchor.clarityDb));

        for (int i = 0; i < result.channels.length; i++) {
            ChannelResult c = result.channels[i];
            if (c == null || !c.ok) continue;
            // The speaker heard last needs no delay; every other one waits for it.
            result.suggestedDelayMs[i] = latest - c.arrivalMs;
            // The hardware moves in half-millisecond steps, which is about seventeen centimetres
            // of air - finer than that would be pretending.
            result.suggestedDelaySteps[i] = Math.round(result.suggestedDelayMs[i] / 0.5f);
        }
    }

    // ---------------------------------------------------------------------------------------
    // borrowing and returning the head unit's settings
    // ---------------------------------------------------------------------------------------

    private static void applySaved(SharedPreferences.Editor editor, String saved) {
        for (String pair : saved.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            // Only one key is ever recorded now - which preset was selected - and putting it back
            // makes the service reload everything that belongs to it.
            editor.putString(key, value);
        }
    }

    /**
     * Puts the sound processor into a state where what is measured is the car, not the settings.
     *
     * The delay lines have to go: they are there to compensate for the very distances being
     * measured, so leaving them on would measure the correction rather than the problem. The
     * equaliser goes flat for the same reason - its curve would otherwise be indistinguishable
     * from the loudspeaker's own response.
     */
    /**
      * Copies the user's preset and neutralises everything that would be measured by mistake.
      *
      * What is switched off, and why each one matters:
      *
      * <ul>
      *   <li><b>delay lines and surround</b> - they exist to compensate for the very distances
      *       being measured, so leaving them on measures the correction instead of the problem;
      *   <li><b>high-pass and bass boost</b> - a high-pass is a real group delay at the bottom of
      *       the range, and it would look exactly like a loudspeaker standing further away;
      *   <li><b>equaliser and loudness</b> - otherwise the preset's curve is measured as though it
      *       were the loudspeaker's own;
      *   <li><b>subwoofer</b> - it plays the same low frequencies as the speaker being measured,
      *       from somewhere else in the car, and smears the arrival;
      *   <li><b>GALA</b> - it changes the volume according to speed, and a volume change during a
      *       sweep would be measured as part of the room.
      * </ul>
      *
      * The power amplifier setting is copied rather than reset: it decides how loud the car is
      * capable of being, and a measurement has no business changing that.
      */
     private static void buildScratchPreset(SharedPreferences prefs, String from) {
         SharedPreferences.Editor e = prefs.edit();

         // The type of every key matters and nothing enforces it: these preferences have no
         // schema, and a value written as the wrong type crashes the service the moment it reads
         // the preset. The gains are numbers; the Q flags are booleans, one bit per band, because
         // the hardware only offers a wide setting and a narrow one.
         for (int b = 0; b < 16; b++) {
             e.putInt(SCRATCH_PRESET + "_g" + b, EQ_FLAT_INDEX);
             e.putBoolean(SCRATCH_PRESET + "_q" + b, false);
         }
         e.putInt(SCRATCH_PRESET + "_f_lr", FADER_CENTRE);
         e.putInt(SCRATCH_PRESET + "_f_fr", FADER_CENTRE);
         e.putBoolean(SCRATCH_PRESET + "_loud", false);

         e.putBoolean(SCRATCH_PRESET + "_d_en", false);
         e.putBoolean(SCRATCH_PRESET + "_d1_en", false);
         for (String ch : new String[]{"fl", "fr", "rl", "rr", "sub"}) {
             e.putInt(SCRATCH_PRESET + "_d_" + ch, 0);
         }
         for (String ch : new String[]{"fl", "fr", "rl", "rr"}) {
             e.putInt(SCRATCH_PRESET + "_d1_" + ch, 0);
         }
         e.putInt(SCRATCH_PRESET + "_rsse_val", 10);

         e.putInt(SCRATCH_PRESET + "_sub_g", 0);
         e.putInt(SCRATCH_PRESET + "_sub_f", 0);
         e.putBoolean(SCRATCH_PRESET + "_sub_comp", false);

         for (String k : new String[]{"_bb_f", "_bb_r", "_bf_f", "_bf_r",
                                      "_bb_frq_f", "_bb_frq_r"}) {
             e.putInt(SCRATCH_PRESET + k, 0);
         }

         e.putBoolean(SCRATCH_PRESET + "_fm_en", false);
         e.putBoolean(SCRATCH_PRESET + "_fat_en", false);
         e.putInt(SCRATCH_PRESET + "_fm_cal", 0);
         e.putInt(SCRATCH_PRESET + "_fm_str", 0);
         e.putBoolean(SCRATCH_PRESET + "_gala_enabled", false);

         // Carried over rather than reset - see the note above.
         e.putInt(SCRATCH_PRESET + "_power_vol", prefs.getInt(from + "_power_vol", 0));

         e.putString("last_selected_preset", SCRATCH_PRESET);
         e.apply();

         // The service reloads on the preset change and then sends every frame; give it room.
         sleep(ROUTING_SETTLE_MS * 2);
     }

    // ---------------------------------------------------------------------------------------
    // audio plumbing
    // ---------------------------------------------------------------------------------------

    private static AudioRecord openMicrophone() {
        int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) return null;
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBytes * 8);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            return null;
        }
        return record;
    }

    private static AudioTrack openTrack(int samples) {
        int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) return null;
        // Deliberately the media path: the measurement has to travel the same route the music
        // does, through the same mixer and the same outboard processor.
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBytes * 2, samples))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    /** The same signal in both channels; which speaker actually sounds is the DSP's decision. */
    private static short[] toStereoPcm16(float[] mono) {
        short[] out = new short[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            short v = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(mono[i] * Short.MAX_VALUE)));
            out[i * 2] = v;
            out[i * 2 + 1] = v;
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // what the tester sends back
    // ---------------------------------------------------------------------------------------

    /** The folder holding the last measurement, created if it is not there yet. */
    public static File outputDir(Context context) {
        File dir = new File(context.getExternalCacheDir(), OUTPUT_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** True when there is a measurement on disk worth sending. */
    public static boolean hasResult(Context context) {
        File report = new File(outputDir(context), "room_measurement.txt");
        return report.isFile() && report.length() > 0;
    }

    private static String writeReport(Context context, Result result, String preset,
                                      float amplitude, float seconds) {
        File file = new File(outputDir(context), "room_measurement.txt");
        try (FileOutputStream out = new FileOutputStream(file)) {
            StringBuilder sb = new StringBuilder();
            sb.append("wDSP room measurement\n");
            sb.append(HardwareProfile.describe()).append('\n');
            if (result.microphone != null) sb.append(result.microphone).append('\n');
            sb.append("preset=").append(preset)
                    .append(" amplitude=").append(amplitude)
                    .append(" sweep=").append(seconds).append(" s")
                    .append(" up to ").append((int) result.sweepTopHz).append(" Hz\n\n");

            for (int i = 0; i < result.channels.length; i++) {
                ChannelResult c = result.channels[i];
                if (c == null) continue;
                sb.append(String.format(Locale.US,
                        "%-12s arrival %8.2f ms  clarity %5.1f dB  prominence %8.0f  "
                                + "polarity %+d  peak %6.1f dBFS%s\n",
                        c.label, c.arrivalMs, c.clarityDb, c.prominence, c.polarity,
                        20 * Math.log10(c.recordedPeak + 1e-9f), c.ok ? "" : "   NOT TRUSTED"));
                sb.append("             suggested delay ")
                        .append(String.format(Locale.US, "%.1f ms (%d steps)",
                                result.suggestedDelayMs[i], result.suggestedDelaySteps[i]))
                        .append('\n');
                sb.append("             response dB:");
                for (float band : c.bandsDb) {
                    sb.append(String.format(Locale.US, " %.1f", band));
                }
                sb.append("\n\n");
            }
            sb.append("Band centres: 20 31.5 50 80 125 200 315 500 800 1250 2000 3150 5000 "
                    + "8000 12500 20000 Hz\n");
            sb.append("The response includes the microphone's own curve and is NOT a calibration.\n");
            out.write(sb.toString().getBytes("UTF-8"));
            Log.i(TAG, "report written to " + file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.w(TAG, "the report could not be written", e);
            return null;
        }
    }

    private static void writeWav(Context context, String name, short[] samples, int count) {
        File file = new File(outputDir(context), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[44]);
            byte[] bytes = new byte[count * 2];
            for (int i = 0; i < count; i++) {
                bytes[i * 2] = (byte) (samples[i] & 0xFF);
                bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
            }
            out.write(bytes);
        } catch (IOException e) {
            Log.w(TAG, "could not write " + name, e);
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long dataBytes = (long) count * 2;
            byte[] header = new byte[44];
            ascii(header, 0, "RIFF");
            le32(header, 4, (int) (36 + dataBytes));
            ascii(header, 8, "WAVE");
            ascii(header, 12, "fmt ");
            le32(header, 16, 16);
            le16(header, 20, 1);
            le16(header, 22, 1);
            le32(header, 24, SAMPLE_RATE);
            le32(header, 28, SAMPLE_RATE * 2);
            le16(header, 32, 2);
            le16(header, 34, 16);
            ascii(header, 36, "data");
            le32(header, 40, (int) dataBytes);
            raf.seek(0);
            raf.write(header);
        } catch (IOException e) {
            Log.w(TAG, "could not finish the header of " + name, e);
        }
    }

    private static void logResult(Result result) {
        Log.i(TAG, "=== room measurement finished ===");
        if (result.error != null) {
            Log.e(TAG, "error: " + result.error);
            return;
        }
        for (int i = 0; i < result.channels.length; i++) {
            ChannelResult c = result.channels[i];
            if (c == null) continue;
            if (!c.ok) {
                // Never print a delay for a channel that was not heard: a zero here reads as
                // "nothing to correct", which is the opposite of "we do not know".
                Log.i(TAG, String.format(Locale.US,
                        "%-12s NOT MEASURED (prominence %.0f, peak %.1f dBFS)",
                        c.label, c.prominence, 20 * Math.log10(c.recordedPeak + 1e-9f)));
                continue;
            }
            Log.i(TAG, String.format(Locale.US,
                    "%-12s arrival %7.2f ms -> delay %4.1f ms (%d steps)  polarity %+d",
                    c.label, c.arrivalMs, result.suggestedDelayMs[i],
                    result.suggestedDelaySteps[i], c.polarity));
        }
        if (!result.isUsable()) {
            Log.w(TAG, "at least one channel was not heard clearly. Turn the volume up a little, "
                    + "make sure the engine is off and the doors are shut, and check that nothing "
                    + "else is holding the microphone - an assistant hotword will take it and cap "
                    + "it at 16 kHz without saying so.");
        }
        if (result.reportPath != null) Log.i(TAG, "report: " + result.reportPath);
    }

    private static void ascii(byte[] target, int at, String text) {
        for (int i = 0; i < text.length(); i++) target[at + i] = (byte) text.charAt(i);
    }

    private static void le32(byte[] target, int at, int value) {
        for (int i = 0; i < 4; i++) target[at + i] = (byte) ((value >> (8 * i)) & 0xFF);
    }

    private static void le16(byte[] target, int at, int value) {
        for (int i = 0; i < 2; i++) target[at + i] = (byte) ((value >> (8 * i)) & 0xFF);
    }

    private static void closeQuietly(AudioTrack track) {
        if (track == null) return;
        try {
            track.stop();
        } catch (Throwable ignored) {
        }
        try {
            track.release();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(AudioRecord record) {
        if (record == null) return;
        try {
            record.stop();
        } catch (Throwable ignored) {
        }
        try {
            record.release();
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Convenience for the defaults, used by the debug broadcast. */
    public static void measureAsync(Context context, Listener listener) {
        measureAsync(context, DEFAULT_AMPLITUDE, DEFAULT_SECONDS, listener);
    }
}
