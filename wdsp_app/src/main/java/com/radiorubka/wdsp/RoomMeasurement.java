package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
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
 * Everything is logged under the tag {@code wDSP_RoomMeasure}, and the recordings are kept as WAV
 * files next to the app's data, so a measurement made in somebody else's car can be sent back and
 * looked at properly rather than described over chat.
 */
public final class RoomMeasurement {
    private static final String TAG = "wDSP_RoomMeasure";

    private static final int SAMPLE_RATE = 48000;
    /** 20 Hz is below anything a car door can reproduce, but the sweep costs nothing down there. */
    private static final float SWEEP_START_HZ = 20f;
    private static final float SWEEP_END_HZ = 20000f;
    private static final float DEFAULT_SECONDS = 3f;
    /**
     * Amplitude of the sweep, not of the head unit.
     *
     * This class never touches the volume control: how loud the car plays is the user's decision
     * and their neighbours' business. A quarter of full scale is loud enough to measure at a
     * normal listening volume and quiet enough not to frighten anybody.
     */
    private static final float DEFAULT_AMPLITUDE = 0.25f;
    /** Recording continues past the sweep so that the room's decay is captured too. */
    private static final float TAIL_SECONDS = 1.0f;
    /** The fader and balance sliders run 0..24 with 12 in the middle. */
    private static final int FADER_MIN = 0;
    private static final int FADER_CENTRE = 12;
    private static final int FADER_MAX = 24;
    /** Equaliser gain indices run 0..12, and 6 is flat - see McuService.applyEqualizer(). */
    private static final int EQ_FLAT_INDEX = 6;
    /** Time for the MCU to act on a routing change before the sweep starts. */
    private static final long ROUTING_SETTLE_MS = 800;

    /**
     * How far the arrival has to stand above the rest of the impulse response to be believed.
     *
     * Measured on a bench where two of the four routings drove nothing: the silent ones came back
     * with 19 and 24, the ones that actually played gave 2779 and 13798. Two orders of magnitude
     * apart, which is what makes this a usable test rather than a guess - but the figure is from
     * one desk, not from a fleet of cars, so it is deliberately generous and every measurement
     * logs its own prominence. If real cars come back nearer the threshold, raise it with data.
     */
    private static final float MIN_PROMINENCE = 200f;
    /**
     * The recording also has to contain something. A channel that was never driven still produces
     * an impulse response - of the room noise - and it can look convincing on its own.
     */
    private static final float MIN_PEAK = 0.01f;      // -40 dBFS
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

    private static final String PREFS_NAME = "EqPresets";
    /** Holds everything a running measurement has changed, so it can be undone after a crash. */
    private static final String PREF_RECOVERY = "room_measure_recovery";

    private static volatile boolean running;

    private RoomMeasurement() {
    }

    /**
     * The four speakers, and how to steer the sound to each one on its own.
     *
     * <p><b>Caution:</b> which end of each slider is left and which is front has been taken from
     * the obvious reading of the labels and is <b>not</b> confirmed on a car. The measurement
     * itself does not depend on it - the arrival times and their differences are right either way
     * - but the names attached to them might be mirrored. A single test in a real car settles it:
     * play the channel this class calls FRONT_LEFT and see which speaker sounds. Until somebody
     * does that, treat the names as provisional and the numbers as sound.
     */
    private enum Channel {
        FRONT_LEFT("front left", FADER_MIN, FADER_MIN),
        FRONT_RIGHT("front right", FADER_MAX, FADER_MIN),
        REAR_LEFT("rear left", FADER_MIN, FADER_MAX),
        REAR_RIGHT("rear right", FADER_MAX, FADER_MAX);

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
        /** Sample at which the sound arrived, counted from the start of the impulse response. */
        public int arrivalSamples;
        /** Arrival in milliseconds, which is what the delay sliders are calibrated in. */
        public float arrivalMs;
        /** How far the arrival stood above everything else. Under ten means nothing was heard. */
        public float prominence;
        /** +1 normal, -1 wired backwards, 0 not determined. */
        public int polarity;
        /** Sixteen band levels in dB, on the hardware equaliser's grid. */
        public final float[] bandsDb = new float[NativeSweep.BAND_COUNT];
        /** Loudest sample in the recording, so a tester can see at once if it was too quiet. */
        public float recordedPeak;
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

        // Everything below this point may change the head unit, so from here on the original
        // values are recoverable even if the process dies.
        String saved = saveCurrentSettings(prefs, preset);
        prefs.edit().putString(PREF_RECOVERY, saved).apply();
        Log.i(TAG, "saved for restoring afterwards: " + saved);

        try (NativeSweep sweep = new NativeSweep(SAMPLE_RATE, SWEEP_START_HZ, SWEEP_END_HZ,
                seconds)) {
            if (!sweep.isValid()) {
                result.error = "the sweep could not be built";
                return result;
            }

            prepareForMeasurement(prefs, preset);

            final int sweepLen = sweep.length();
            final int tail = (int) (TAIL_SECONDS * SAMPLE_RATE);
            final int recordLen = sweepLen + tail;

            float[] mono = new float[sweepLen];
            sweep.generate(mono, amplitude);
            short[] stereo = toStereoPcm16(mono);
            Log.i(TAG, "sweep is " + sweepLen + " samples (" + (sweepLen / (float) SAMPLE_RATE)
                    + " s), recording window " + recordLen + " samples");

            float[] analysis = new float[NativeSweep.RESULT_SIZE];
            Channel[] channels = Channel.values();

            for (int i = 0; i < channels.length; i++) {
                Channel channel = channels[i];
                if (listener != null) listener.onProgress(channel.label);

                // Steering is done by the outboard sound processor, not by Android: the head unit
                // gives us two channels and the DSP splits them four ways, so the only way to
                // reach one speaker is to push the balance and fader to their extremes.
                Log.i(TAG, "--- " + channel.label + ": balance=" + channel.leftRight
                        + " fader=" + channel.frontRear + " ---");
                prefs.edit()
                        .putInt(preset + "_f_lr", channel.leftRight)
                        .putInt(preset + "_f_fr", channel.frontRear)
                        .apply();
                sleep(ROUTING_SETTLE_MS);

                ChannelResult cr = measureOne(app, sweep, stereo, recordLen, analysis, channel);
                result.channels[i] = cr;
            }

            computeDelays(result);
            result.reportPath = writeReport(app, result, preset, amplitude, seconds);
        } finally {
            // Restore before anything else can go wrong, and only then clear the recovery note.
            SharedPreferences.Editor editor = prefs.edit();
            applySaved(editor, saved);
            editor.remove(PREF_RECOVERY);
            editor.apply();
            Log.i(TAG, "settings restored");
        }

        logResult(result);
        return result;
    }

    /** Plays the sweep through whichever speaker is currently selected and listens to it. */
    private static ChannelResult measureOne(Context context, NativeSweep sweep, short[] stereo,
                                            int recordLen, float[] analysis, Channel channel) {
        ChannelResult cr = new ChannelResult();
        cr.label = channel.label;

        AudioTrack track = null;
        AudioRecord record = null;
        MicProbe.Suspension effects = null;
        try {
            record = openMicrophone();
            if (record == null) {
                Log.e(TAG, channel.label + ": the microphone could not be opened");
                return cr;
            }
            effects = MicProbe.suspendCapturePreprocessing(record.getAudioSessionId(), TAG);

            track = openTrack(stereo.length);
            if (track == null) {
                Log.e(TAG, channel.label + ": the output could not be opened");
                return cr;
            }

            // Recording starts first, so that nothing of the sweep can be missed. The deconvolution
            // finds the sweep wherever it happens to sit in the recording, so a little silence at
            // the front costs nothing.
            record.startRecording();
            track.play();

            final short[] captured = new short[recordLen];
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

            int got = 0;
            while (got < recordLen) {
                int read = record.read(captured, got, recordLen - got);
                if (read <= 0) {
                    Log.w(TAG, channel.label + ": read returned " + read);
                    break;
                }
                got += read;
            }
            try {
                writer.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            float[] asFloat = new float[got];
            int peak = 0;
            for (int i = 0; i < got; i++) {
                asFloat[i] = captured[i] / 32768f;
                if (Math.abs(captured[i]) > peak) peak = Math.abs(captured[i]);
            }
            cr.recordedPeak = peak / 32768f;

            // The raw recording is kept. A number in a log can only be argued about; the recording
            // can be re-analysed once the analysis itself improves, and by somebody who was not in
            // the car at the time.
            writeWav(context, "room_" + channel.name().toLowerCase(Locale.US) + ".wav",
                    captured, got);

            if (!sweep.analyse(asFloat, got, analysis)) {
                Log.e(TAG, channel.label + ": nothing in the recording looked like the sweep");
                return cr;
            }

            cr.arrivalSamples = (int) analysis[NativeSweep.ARRIVAL];
            cr.arrivalMs = cr.arrivalSamples * 1000f / SAMPLE_RATE;
            cr.prominence = analysis[NativeSweep.PROMINENCE];
            cr.polarity = (int) analysis[NativeSweep.POLARITY];
            System.arraycopy(analysis, NativeSweep.BANDS, cr.bandsDb, 0, NativeSweep.BAND_COUNT);

            // Two independent reasons to disbelieve a result, and both have to be ruled out.
            // A channel that was never driven still yields an impulse response - of the room noise
            // - and its loudest moment still looks like an arrival.
            cr.ok = cr.prominence >= MIN_PROMINENCE && cr.recordedPeak >= MIN_PEAK;
            Log.i(TAG, String.format(Locale.US,
                    "%s: arrival %d samples (%.2f ms), prominence %.0f, polarity %+d, "
                            + "recorded peak %.1f dBFS%s",
                    channel.label, cr.arrivalSamples, cr.arrivalMs, cr.prominence, cr.polarity,
                    20 * Math.log10(cr.recordedPeak + 1e-9f),
                    cr.ok ? "" : "  <-- TOO WEAK TO TRUST"));
        } catch (Throwable t) {
            Log.e(TAG, channel.label + ": measurement failed", t);
        } finally {
            if (effects != null) effects.restore();
            closeQuietly(track);
            closeQuietly(record);
        }
        return cr;
    }

    /**
     * Turns arrival times into delay settings.
     *
     * The speaker that is furthest away is heard last, so it needs no delay at all; every other
     * speaker is held back until it arrives at the same moment. This is the one result that is
     * exact no matter what the microphone's response is, because it comes entirely from timing.
     */
    private static void computeDelays(Result result) {
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

        // Sound travels about a third of a metre in a millisecond, so four speakers in one car
        // cannot be more than a few milliseconds apart. A larger spread is not a wide car, it is
        // a channel that was not heard, and turning it into a delay setting would be worse than
        // offering nothing.
        final float spread = latest - earliest;
        if (spread > MAX_PLAUSIBLE_SPREAD_MS) {
            result.error = String.format(Locale.US,
                    "arrivals span %.0f ms, which no car can do (%.0f ms is already ten metres) - "
                            + "at least one channel was not really heard", spread, spread);
            Log.w(TAG, result.error);
            return;
        }

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

    /**
     * Records every setting the measurement is about to change, as {@code key=value} pairs.
     *
     * A flat string rather than JSON on purpose: it has to survive being read back by a version of
     * the app that may have changed in the meantime, and it has to be readable in a bug report.
     */
    private static String saveCurrentSettings(SharedPreferences prefs, String preset) {
        StringBuilder sb = new StringBuilder();
        append(sb, preset + "_f_lr", prefs.getInt(preset + "_f_lr", FADER_CENTRE));
        append(sb, preset + "_f_fr", prefs.getInt(preset + "_f_fr", FADER_CENTRE));
        append(sb, preset + "_d_en", prefs.getBoolean(preset + "_d_en", false) ? 1 : 0);
        append(sb, preset + "_d1_en", prefs.getBoolean(preset + "_d1_en", false) ? 1 : 0);
        for (int b = 0; b < 16; b++) {
            append(sb, preset + "_g" + b, prefs.getInt(preset + "_g" + b, EQ_FLAT_INDEX));
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, int value) {
        if (sb.length() > 0) sb.append(';');
        sb.append(key).append('=').append(value);
    }

    private static void applySaved(SharedPreferences.Editor editor, String saved) {
        for (String pair : saved.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq);
            int value;
            try {
                value = Integer.parseInt(pair.substring(eq + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            // The two switches were stored as 0/1 and have to go back as booleans, or the
            // preference listener will read the wrong type and the setting will be lost.
            if (key.endsWith("_d_en") || key.endsWith("_d1_en")) {
                editor.putBoolean(key, value != 0);
            } else {
                editor.putInt(key, value);
            }
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
    private static void prepareForMeasurement(SharedPreferences prefs, String preset) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(preset + "_d_en", false);
        editor.putBoolean(preset + "_d1_en", false);
        for (int b = 0; b < 16; b++) {
            editor.putInt(preset + "_g" + b, EQ_FLAT_INDEX);
        }
        editor.apply();
        Log.i(TAG, "delay lines off and equaliser flattened for the duration of the measurement");
        sleep(ROUTING_SETTLE_MS);
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

    private static String writeReport(Context context, Result result, String preset,
                                      float amplitude, float seconds) {
        File file = new File(context.getExternalFilesDir(null), "room_measurement.txt");
        try (FileOutputStream out = new FileOutputStream(file)) {
            StringBuilder sb = new StringBuilder();
            sb.append("wDSP room measurement\n");
            sb.append(HardwareProfile.describe()).append('\n');
            sb.append("preset=").append(preset)
                    .append(" amplitude=").append(amplitude)
                    .append(" sweep=").append(seconds).append(" s\n\n");

            for (int i = 0; i < result.channels.length; i++) {
                ChannelResult c = result.channels[i];
                if (c == null) continue;
                sb.append(String.format(Locale.US,
                        "%-12s arrival %8.2f ms  prominence %8.0f  polarity %+d  peak %6.1f dBFS%s\n",
                        c.label, c.arrivalMs, c.prominence, c.polarity,
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
        File file = new File(context.getExternalFilesDir(null), name);
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
