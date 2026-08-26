package com.radiorubka.wdsp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds out what an application's audio actually looks like from outside it.
 *
 * <h2>Why this exists before the recorder does</h2>
 *
 * Everything the recorder is meant to do rests on one assumption that cannot be checked by
 * reading documentation: that the players the owner listens to hand their sound over. An app is
 * capturable by default only if it targets 29 or later and has not called
 * {@code setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)} - and that call is made at runtime, so
 * nothing in a manifest or a package dump will admit to it. The only way to know is to try, and
 * to look at what arrives.
 *
 * <p>What arrives also answers two further questions the plan is blocked on: whether the ducking
 * applied when navigation speaks reaches this side of the mixer, and what an hour of this would
 * cost on a unit already running the analyser. So the probe measures rather than merely
 * succeeding: a level per second, and its own processor time.
 */
public final class CaptureProbe {

    private static final String TAG = "CaptureProbe";

    /**
     * Asked for rather than assumed.
     *
     * <p>Playback capture resamples to whatever is requested, so this is not free: asking for 48
     * when the stream is 44.1 buys nothing and costs a conversion. It is still the right default,
     * because the mixer on this platform runs at 48 - asking for anything else moves the
     * resampling rather than removing it.
     */
    private static final int SAMPLE_RATE = 48000;

    private static final int CHANNELS = 2;
    private static final int BYTES_PER_SAMPLE = 2;

    /** How much of a second has to be non-zero before the second counts as carrying sound. */
    private static final float NONZERO_FRACTION = 0.01f;

    /**
     * The tone {@link ToneSource} plays, looked for by {@link #TONE_HZ} in the capture.
     *
     * <p>A single frequency is picked out with a Goertzel rather than a transform, because one bin
     * is all that is wanted and the loop is already walking every sample for the level anyway.
     */
    static final double TONE_HZ = 1000.0;

    public static final class Second {
        public final float peakDb;
        public final float rmsDb;
        public final float nonZeroFraction;
        public final float toneDb;

        Second(float peakDb, float rmsDb, float nonZeroFraction, float toneDb) {
            this.peakDb = peakDb;
            this.rmsDb = rmsDb;
            this.nonZeroFraction = nonZeroFraction;
            this.toneDb = toneDb;
        }
    }

    public static final class Result {
        public String targetPackage = "";
        public int targetUid = -1;
        public boolean opened = false;
        public String failure = null;
        public long framesRead = 0;
        public final List<Second> seconds = new ArrayList<>();
        public File wav = null;
        public long cpuMillis = 0;
        public long wallMillis = 0;

        /**
         * Whether the application handed anything over at all.
         *
         * <p>A refusal does not fail: the capture opens, reads happily, and returns silence for
         * as long as you care to ask. So "did it work" has to be put to the samples, not to the
         * API.
         */
        public boolean carriedSound() {
            for (Second s : seconds) {
                if (s.nonZeroFraction > NONZERO_FRACTION) return true;
            }
            return false;
        }

        public String verdict() {
            if (!opened) return "COULD NOT OPEN: " + failure;
            if (!carriedSound()) return "SILENT - the app refuses capture";
            return "SOUND";
        }
    }

    private CaptureProbe() {
    }

    /**
     * Records one application for a while and reports what came out.
     *
     * @param uid the only application whose sound is wanted. Navigation, chimes and the assistant
     *            live in other applications with other uids, so naming one here excludes them
     *            outright rather than quietly - which is the whole point of recording this way.
     *            Zero or less asks for everything, which is only useful for comparison.
     */
    public static Result run(Context context, MediaProjection projection, String pkg, int uid,
                             int seconds) {
        return run(context, projection, pkg, uid, null, seconds);
    }

    /**
     * @param usages when given, ask by what the sound is <em>for</em> instead of by who made it.
     *               The two kinds of rule cannot be mixed in one configuration, and on this
     *               platform they are not equally respected - which is the thing worth finding
     *               out, since excluding navigation from a recording depends on one of them
     *               actually being obeyed.
     */
    public static Result run(Context context, MediaProjection projection, String pkg, int uid,
                             int[] usages, int seconds) {
        Result r = new Result();
        r.targetPackage = pkg;
        r.targetUid = uid;

        AudioPlaybackCaptureConfiguration config;
        try {
            AudioPlaybackCaptureConfiguration.Builder b =
                    new AudioPlaybackCaptureConfiguration.Builder(projection);
            if (usages != null && usages.length > 0) {
                for (int u : usages) b.addMatchingUsage(u);
            } else if (uid > 0) {
                b.addMatchingUid(uid);
            } else {
                b.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
                b.addMatchingUsage(AudioAttributes.USAGE_GAME);
                b.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
            }
            config = b.build();
        } catch (Throwable t) {
            r.failure = "config: " + t;
            Log.w(TAG, "could not configure the capture", t);
            return r;
        }

        AudioRecord record = null;
        try {
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) minBytes = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE / 4;
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBytes * 4)
                    .setAudioPlaybackCaptureConfig(config)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                r.failure = "AudioRecord state " + record.getState();
                record.release();
                return r;
            }
            record.startRecording();
            r.opened = true;
        } catch (Throwable t) {
            r.failure = "open: " + t;
            Log.w(TAG, "could not open the capture", t);
            if (record != null) record.release();
            return r;
        }

        File wav = new File(probeDir(context),
                sanitise(pkg) + "-" + System.currentTimeMillis() + ".wav");
        long cpuStart = cpuMillis();
        long wallStart = System.currentTimeMillis();

        try (FileOutputStream out = new FileOutputStream(wav)) {
            writeWavHeader(out, 0);

            int frameBytes = CHANNELS * BYTES_PER_SAMPLE;
            byte[] buf = new byte[SAMPLE_RATE * frameBytes / 10];
            long framesInBucket = 0;
            long nonZero = 0;
            double sumSquares = 0;
            int peak = 0;

            double goertzelCoeff = 2 * Math.cos(2 * Math.PI * TONE_HZ / SAMPLE_RATE);
            double g1 = 0;
            double g2 = 0;
            long goertzelN = 0;

            long wanted = (long) seconds * SAMPLE_RATE;
            while (r.framesRead < wanted) {
                int read = record.read(buf, 0, buf.length);
                if (read <= 0) {
                    r.failure = "read returned " + read;
                    break;
                }
                out.write(buf, 0, read);

                ByteBuffer bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN);
                int samples = read / BYTES_PER_SAMPLE;
                for (int i = 0; i < samples; i++) {
                    int s = bb.getShort();
                    int a = Math.abs(s);
                    if (a > peak) peak = a;
                    if (a > 0) nonZero++;
                    sumSquares += (double) s * s;
                    if ((i & 1) == 0) {
                        // Left channel only: the interleaved pair would halve the effective rate
                        // and put the tone in the wrong bin.
                        double g0 = s / 32768.0 + goertzelCoeff * g1 - g2;
                        g2 = g1;
                        g1 = g0;
                        goertzelN++;
                    }
                }
                long frames = samples / CHANNELS;
                r.framesRead += frames;
                framesInBucket += frames;

                if (framesInBucket >= SAMPLE_RATE) {
                    long n = framesInBucket * CHANNELS;
                    double power = g1 * g1 + g2 * g2 - goertzelCoeff * g1 * g2;
                    double amplitude = 2 * Math.sqrt(Math.max(0, power)) / Math.max(1, goertzelN);
                    r.seconds.add(new Second(
                            db(peak / 32768.0),
                            db(Math.sqrt(sumSquares / Math.max(1, n)) / 32768.0),
                            (float) nonZero / Math.max(1, n),
                            db(amplitude)));
                    framesInBucket = 0;
                    nonZero = 0;
                    sumSquares = 0;
                    peak = 0;
                    g1 = 0;
                    g2 = 0;
                    goertzelN = 0;
                }
            }
        } catch (Throwable t) {
            if (r.failure == null) r.failure = "read: " + t;
            Log.w(TAG, "the capture stopped early", t);
        } finally {
            try {
                record.stop();
            } catch (Throwable ignored) {
            }
            record.release();
        }

        r.cpuMillis = cpuMillis() - cpuStart;
        r.wallMillis = System.currentTimeMillis() - wallStart;

        try {
            patchWavLength(wav);
            r.wav = wav;
        } catch (Throwable t) {
            Log.w(TAG, "could not finish the wav", t);
        }
        Log.i(TAG, describe(r));
        return r;
    }

    public static String describe(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.targetPackage).append("  uid=").append(r.targetUid)
                .append("  ").append(r.verdict()).append('\n');
        sb.append(String.format(Locale.US,
                "  frames=%d  cpu=%d ms over %d ms wall (%.1f%% of one core)\n",
                r.framesRead, r.cpuMillis, r.wallMillis,
                r.wallMillis == 0 ? 0f : 100f * r.cpuMillis / r.wallMillis));
        for (int i = 0; i < r.seconds.size(); i++) {
            Second s = r.seconds.get(i);
            sb.append(String.format(Locale.US,
                    "  %2ds  peak %6.1f dB  rms %6.1f dB  nonzero %5.1f%%  1kHz %6.1f dB\n",
                    i + 1, s.peakDb, s.rmsDb, 100 * s.nonZeroFraction, s.toneDb));
        }
        if (r.wav != null) sb.append("  wav ").append(r.wav.getAbsolutePath()).append('\n');
        return sb.toString();
    }

    public static File probeDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), "probe");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static float db(double linear) {
        if (linear <= 0) return -120f;
        return (float) (20 * Math.log10(linear));
    }

    private static String sanitise(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Own processor time, in milliseconds.
     *
     * <p>From {@code /proc/self/stat} rather than a timer, because the question is what the work
     * costs a unit that is already running the analyser, and wall time cannot tell a busy core
     * from a waiting one.
     */
    private static long cpuMillis() {
        try (RandomAccessFile f = new RandomAccessFile("/proc/self/stat", "r")) {
            String line = f.readLine();
            // The command name sits in brackets and may itself contain spaces, so the fields are
            // counted from after the closing bracket. utime and stime are the 12th and 13th.
            int close = line.lastIndexOf(')');
            String[] parts = line.substring(close + 2).split(" ");
            long ticks = Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
            return ticks * 1000 / 100;
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------------------------------
    // a wav, because the probe is for listening to as well as for measuring
    // ------------------------------------------------------------------------------------------

    private static void writeWavHeader(FileOutputStream out, int dataBytes) throws Exception {
        int byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE;
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes("US-ASCII"));
        h.putInt(36 + dataBytes);
        h.put("WAVE".getBytes("US-ASCII"));
        h.put("fmt ".getBytes("US-ASCII"));
        h.putInt(16);
        h.putShort((short) 1);
        h.putShort((short) CHANNELS);
        h.putInt(SAMPLE_RATE);
        h.putInt(byteRate);
        h.putShort((short) (CHANNELS * BYTES_PER_SAMPLE));
        h.putShort((short) (BYTES_PER_SAMPLE * 8));
        h.put("data".getBytes("US-ASCII"));
        h.putInt(dataBytes);
        out.write(h.array());
    }

    private static void patchWavLength(File wav) throws Exception {
        long dataBytes = wav.length() - 44;
        try (RandomAccessFile f = new RandomAccessFile(wav, "rw")) {
            f.seek(4);
            f.write(intLe((int) (36 + dataBytes)));
            f.seek(40);
            f.write(intLe((int) dataBytes));
        }
    }

    private static byte[] intLe(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
