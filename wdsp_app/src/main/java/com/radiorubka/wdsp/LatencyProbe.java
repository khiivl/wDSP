package com.radiorubka.wdsp;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Measures, rather than guesses, how far the spectrum analyser runs ahead of the sound.
 *
 * What the Visualizer hands us has not reached the speakers yet, so the bars have to be delayed
 * to match what the ear hears. Until now that delay came from {@code getOutputLatency()}, a
 * number the framework simply declares - and on this head unit it disagrees with the track's own
 * dump by a factor of three (125 ms against 558 ms). Neither is a measurement.
 *
 * The trick used here needs no microphone. We play our own quiet burst on our own session, so:
 *
 *   - {@link AudioTrack#getTimestamp} tells us the monotonic clock time at which a specific frame
 *     was presented to the audio hardware. That is the platform's own bookkeeping, not a claim;
 *   - a {@link Visualizer} on the same session tells us when we saw that same burst.
 *
 * The difference between the two is the honest delay across everything Android does. What it
 * cannot see is the last stretch - the outboard DSP, the amplifier and the air - which is a few
 * milliseconds and stays as a constant, plus the user's trim.
 *
 * Nothing here runs unless explicitly triggered:
 *
 * <pre>
 *   adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_LATENCY
 * </pre>
 */
public final class LatencyProbe {
    private static final String TAG = "wDSP_LatencyProbe";

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;

    /** Silence before the first burst, so the track is settled and timestamps are already valid. */
    private static final int LEAD_IN_FRAMES = SAMPLE_RATE * 3 / 4;
    /** Far enough apart that one burst can never be mistaken for its neighbour. */
    private static final int BURST_PERIOD_FRAMES = SAMPLE_RATE * 6 / 5;
    private static final int BURSTS = 8;

    /** 3 ms of 2 kHz under a Hann envelope: sharp enough to time, soft enough not to startle. */
    private static final int BURST_FRAMES = SAMPLE_RATE * 3 / 1000;
    private static final int BURST_FRAMES_ACOUSTIC = SAMPLE_RATE * 10 / 1000;
    private static final double BURST_TONE_HZ = 2000.0;
    private static final double BURST_AMPLITUDE = 0.25;
    private static final double BURST_AMPLITUDE_ACOUSTIC = 0.6;

    /** Set for the duration of one measurement so signal generation matches the mode. */
    private static volatile int burstLen = SAMPLE_RATE * 3 / 1000;
    private static volatile double burstGain = 0.25;

    private static final int CHUNK_FRAMES = 2400;
    /** Silence written before the effect is attached, so the track is placed first. */
    private static final int PRIME_FRAMES = SAMPLE_RATE * 2 / 5;
    /** By this point two bursts have played; an effect that heard neither is on the wrong
     *  output and is worth throwing away and building again. */
    private static final int RETRY_AFTER_FRAMES = LEAD_IN_FRAMES + 2 * BURST_PERIOD_FRAMES;
    private static final int POLL_PERIOD_MS = 6;
    /** Waveform is unsigned 8-bit around 128; our burst peaks near 32 even at low volume. */
    private static final int TRIGGER_MAGNITUDE = 4;
    private static final long COOLDOWN_NS = 400_000_000L;

    private static volatile boolean running;

    private LatencyProbe() {
    }

    /** Callback for the UI; every field is already formatted for display. */
    public interface Listener {
        void onLatencyResult(Result result);
    }

    public static boolean isRunning() {
        return running;
    }

    /** Runs the whole measurement on its own thread and logs the outcome. */
    public static void measureAsync(final AudioManager audioManager, final Listener listener) {
        measureAsync(audioManager, false, listener);
    }

    /**
     * @param lowLatency ask for the low-latency output instead of the deep buffer one. Media on
     *                   this head unit is routed to the "fast" mixPort, so the two paths can
     *                   carry different amounts of buffering and the difference is worth knowing.
     */
    public static void measureAsync(final AudioManager audioManager, final boolean lowLatency,
                                    final Listener listener) {
        measureAsync(audioManager, lowLatency, false, listener);
    }

    /**
     * @param acoustic also listen with the microphone. The presentation time above is what the
     *                 HAL claims; the microphone is the only witness of what actually left the
     *                 speakers, and although its own input delay is unknown, it puts a ceiling
     *                 on the answer.
     */
    public static void measureAsync(final AudioManager audioManager, final boolean lowLatency,
                                    final boolean acoustic, final Listener listener) {
        if (running) {
            Log.w(TAG, "measurement already in progress, ignoring");
            return;
        }
        new Thread(() -> {
            running = true;
            Result result;
            try {
                result = measure(audioManager, lowLatency, acoustic);
            } catch (Throwable t) {
                result = new Result();
                result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            } finally {
                running = false;
            }
            Log.i(TAG, "RESULT " + result);
            if (listener != null) listener.onLatencyResult(result);
        }, "wDSP_LatencyProbe").start();
    }

    /** The measurement itself. Blocks for about twelve seconds. Never throws. */
    public static Result measure(AudioManager audioManager, boolean lowLatency,
                                 boolean acoustic) {
        Result result = new Result();
        result.frameworkMs = frameworkClaimMs(audioManager);

        int sessionId = 0;
        if (audioManager != null) sessionId = audioManager.generateAudioSessionId();
        if (sessionId <= 0) {
            result.error = "generateAudioSessionId failed";
            return result;
        }
        result.sessionId = sessionId;

        burstLen = acoustic ? BURST_FRAMES_ACOUSTIC : BURST_FRAMES;
        burstGain = acoustic ? BURST_AMPLITUDE_ACOUSTIC : BURST_AMPLITUDE;

        AudioTrack track = null;
        Capture capture = null;
        MicWatcher mic = null;
        try {
            if (acoustic) {
                mic = new MicWatcher();
                mic.start();
            }
            track = buildTrack(sessionId, lowLatency);
            result.lowLatency = lowLatency;

            List<long[]> timestamps = new ArrayList<>();
            long[] burstFrames = new long[BURSTS];
            for (int k = 0; k < BURSTS; k++) {
                burstFrames[k] = LEAD_IN_FRAMES + (long) k * BURST_PERIOD_FRAMES;
            }
            long totalFrames = LEAD_IN_FRAMES + (long) BURSTS * BURST_PERIOD_FRAMES
                    + SAMPLE_RATE / 4;

            track.play();

            short[] chunk = new short[CHUNK_FRAMES * CHANNELS];
            AudioTimestamp ts = new AudioTimestamp();
            boolean retried = false;
            for (long frame = 0; frame < totalFrames; frame += CHUNK_FRAMES) {
                fill(chunk, frame, burstFrames);
                int written = track.write(chunk, 0, chunk.length);
                if (written < 0) {
                    result.error = "AudioTrack.write returned " + written;
                    break;
                }
                if (track.getTimestamp(ts)) {
                    timestamps.add(new long[]{ts.framePosition, ts.nanoTime});
                }
                // The effect must not be attached before the track is actually playing.
                // AudioPolicyManager::getOutputForEffect() looks for an output that already
                // carries this session and falls back to PRIMARY when it finds none - and on
                // this head unit PRIMARY is the idle thread, so an effect placed there measures
                // silence for ever. Priming first makes the policy put it where the sound is.
                if (capture == null && frame >= PRIME_FRAMES) {
                    capture = new Capture(sessionId);
                    capture.start();
                }
                // Priming usually puts the effect on the right thread, but it is a race, and it
                // is lost often enough to matter - especially when custom audio policies move
                // media to a different output. Silence at this point does not mean the track is
                // silent, it means we are listening to the wrong one, so start over rather than
                // return a confident nothing.
                if (capture != null && !retried && frame >= RETRY_AFTER_FRAMES
                        && capture.hits().isEmpty()) {
                    Log.w(TAG, "no bursts heard by " + frame + " frames - the effect landed on the "
                            + "wrong output, attaching again");
                    capture.release();
                    capture = new Capture(sessionId);
                    capture.start();
                    retried = true;
                }
            }

            // Let the tail drain, otherwise the last burst is still sitting in the buffer.
            Thread.sleep(600);
            capture.stop();

            result.timestampsSeen = timestamps.size();
            if (timestamps.isEmpty()) {
                if (result.error == null) {
                    result.error = "getTimestamp never succeeded - the HAL does not report "
                            + "presentation time, so this method cannot work here";
                }
                return result;
            }

            if (capture == null) {
                result.error = "the track never reached the priming point";
                return result;
            }
            List<Long> hits = capture.hits();
            result.burstsSeen = hits.size();
            result.retried = retried;
            // After a retry the first bursts were missed, so hit k is not burst k any more. Line
            // them up from the end, where both lists are certainly describing the same events.
            int skipped = retried ? Math.max(0, BURSTS - hits.size()) : 0;

            List<Float> deltas = new ArrayList<>();
            int pairs = Math.min(hits.size(), BURSTS - skipped);
            for (int k = 0; k < pairs; k++) {
                long presentedNs = presentationNs(timestamps, burstFrames[k + skipped]);
                float ms = (presentedNs - hits.get(k)) / 1_000_000f;
                deltas.add(ms);
                Log.i(TAG, String.format(Locale.US, "burst %d: frame=%d presented=%d captured=%d "
                        + "delta=%.1f ms", k, burstFrames[k], presentedNs, hits.get(k), ms));
            }
            if (deltas.isEmpty()) {
                result.error = "no bursts were captured - the effect most likely landed on the "
                        + "idle PRIMARY output instead of the thread carrying the track";
                return result;
            }

            if (mic != null) {
                mic.stop();
                Log.i(TAG, mic.stats());
                List<Float> acousticDeltas = new ArrayList<>();
                for (int k = 0; k < pairs; k++) {
                    long presentedNs = presentationNs(timestamps, burstFrames[k + skipped]);
                    MicWatcher.Hit heard = mic.find(presentedNs);
                    if (heard == null) continue;
                    float vsPresented = (heard.whenNs - presentedNs) / 1_000_000f;
                    float vsCaptured = (heard.whenNs - hits.get(k)) / 1_000_000f;
                    Log.i(TAG, String.format(Locale.US,
                            "burst %d heard: %.1f ms after the HAL said it was presented, "
                                    + "%.1f ms after we saw it (prominence %.1f)",
                            k, vsPresented, vsCaptured, heard.prominence));
                    // A match that barely stands out of its own search window is the room, not
                    // the burst, and must not be allowed to vote.
                    if (heard.prominence >= 3.0) acousticDeltas.add(vsCaptured);
                }
                result.micHits = acousticDeltas.size();
                result.micTimestamps = mic.stampCount();
                if (!acousticDeltas.isEmpty()) {
                    Collections.sort(acousticDeltas);
                    result.acousticMedianMs =
                            acousticDeltas.get(acousticDeltas.size() / 2);
                }
            }

            Collections.sort(deltas);
            result.medianMs = deltas.get(deltas.size() / 2);
            result.spreadMs = deltas.get(deltas.size() - 1) - deltas.get(0);
            result.ok = true;
        } catch (Throwable t) {
            result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (mic != null) mic.release();
            if (capture != null) capture.release();
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                }
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    /**
     * Presentation time of one frame, extrapolated from the nearest timestamp reading.
     *
     * The clock is the same monotonic one {@link System#nanoTime} reads, and the sample rate is
     * nominal, so over the second or so between a reading and its burst the drift is far below
     * the accuracy we are after.
     */
    private static long presentationNs(List<long[]> readings, long frame) {
        long[] best = readings.get(0);
        long bestDistance = Math.abs(best[0] - frame);
        for (long[] reading : readings) {
            long distance = Math.abs(reading[0] - frame);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = reading;
            }
        }
        return best[1] + (frame - best[0]) * 1_000_000_000L / SAMPLE_RATE;
    }

    /** Writes one chunk of the test signal: silence everywhere except inside a burst. */
    private static void fill(short[] chunk, long firstFrame, long[] burstFrames) {
        java.util.Arrays.fill(chunk, (short) 0);
        for (long start : burstFrames) {
            long from = Math.max(start, firstFrame);
            long to = Math.min(start + burstLen, firstFrame + chunk.length / CHANNELS);
            for (long f = from; f < to; f++) {
                int inBurst = (int) (f - start);
                double envelope = 0.5 - 0.5 * Math.cos(2 * Math.PI * inBurst / burstLen);
                double value = burstGain * envelope
                        * Math.sin(2 * Math.PI * BURST_TONE_HZ * inBurst / SAMPLE_RATE);
                short sample = (short) Math.round(value * Short.MAX_VALUE);
                int offset = (int) (f - firstFrame) * CHANNELS;
                for (int c = 0; c < CHANNELS; c++) chunk[offset + c] = sample;
            }
        }
    }

    private static AudioTrack buildTrack(int sessionId, boolean lowLatency) {
        int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = lowLatency ? minBytes
                : Math.max(minBytes * 2, CHUNK_FRAMES * CHANNELS * 2 * 4);
        AudioTrack.Builder builder = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        // Deliberately the media path: the delay we are after is the one the
                        // music takes, and a different usage could be routed somewhere else.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(sessionId);
        if (lowLatency) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        AudioTrack track = builder.build();
        Log.i(TAG, "track built, session=" + sessionId + " bufferBytes=" + bufferBytes
                + " minBytes=" + minBytes + " lowLatency=" + lowLatency
                + " performanceMode=" + track.getPerformanceMode());
        return track;
    }

    /** What the framework claims, kept only so the log can show both numbers side by side. */
    private static float frameworkClaimMs(AudioManager audioManager) {
        try {
            if (audioManager != null) {
                java.lang.reflect.Method m = audioManager.getClass()
                        .getMethod("getOutputLatency", int.class);
                Object value = m.invoke(audioManager, AudioManager.STREAM_MUSIC);
                if (value instanceof Integer) return (Integer) value;
            }
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    /** Watches our own session and records the moment each burst appears. */
    private static final class Capture implements Runnable {
        private final Visualizer visualizer;
        private final byte[] waveform;
        private final List<Long> hits = new ArrayList<>();
        private volatile boolean active = true;
        private Thread thread;
        private long lastHitNs;
        private long lastSignature = Long.MIN_VALUE;

        Capture(int sessionId) {
            visualizer = new Visualizer(sessionId);
            // The capture size can only be set while the effect is disabled, and after a retry
            // the previous one may still be enabled - releasing it does not take effect at once.
            // Taking whatever size is already configured is better than throwing.
            if (!visualizer.getEnabled()) {
                int[] range = Visualizer.getCaptureSizeRange();
                visualizer.setCaptureSize(range[1]);
                visualizer.setEnabled(true);
            }
            waveform = new byte[visualizer.getCaptureSize()];
        }

        void start() {
            thread = new Thread(this, "wDSP_LatencyCapture");
            thread.start();
        }

        void stop() {
            active = false;
            if (thread != null) {
                try {
                    thread.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        List<Long> hits() {
            synchronized (hits) {
                return new ArrayList<>(hits);
            }
        }

        void release() {
            stop();
            try {
                visualizer.setEnabled(false);
            } catch (Throwable ignored) {
            }
            try {
                visualizer.release();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void run() {
            while (active) {
                long now = System.nanoTime();
                if (visualizer.getWaveForm(waveform) == Visualizer.SUCCESS) {
                    long signature = 0;
                    int peak = 0;
                    int peakAt = 0;
                    for (int i = 0; i < waveform.length; i++) {
                        int centred = (waveform[i] & 0xFF) - 128;
                        signature = signature * 31 + centred;
                        int magnitude = Math.abs(centred);
                        if (magnitude > peak) {
                            peak = magnitude;
                            peakAt = i;
                        }
                    }
                    // Polling faster than the mixer refills the capture buffer means the same
                    // block comes back several times. Timing a stale block would report the delay
                    // as shorter than it is, so only a block we have not seen before counts.
                    boolean fresh = signature != lastSignature;
                    lastSignature = signature;

                    if (fresh && peak >= TRIGGER_MAGNITUDE && now - lastHitNs > COOLDOWN_NS) {
                        // The block ends at "now"; the peak sits this far back inside it.
                        long backNs = (long) ((waveform.length - 1 - peakAt)
                                * 1_000_000_000L / SAMPLE_RATE);
                        synchronized (hits) {
                            hits.add(now - backNs);
                        }
                        lastHitNs = now;
                    }
                }
                try {
                    Thread.sleep(POLL_PERIOD_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Listens to the cabin and notes when each burst actually arrives.
     *
     * {@link AudioRecord#getTimestamp} is the mirror image of the one on the playback side: it
     * reports when a captured frame was taken at the input, which folds the recording buffer out
     * of the answer. Where the HAL declines to provide it we fall back to the wall clock at the
     * moment {@code read} returns, and that can only ever make the arrival look later than it was
     * - an honest ceiling rather than a flattering guess.
     *
     * One caveat that belongs in the log rather than in a comment: if anything else already holds
     * the microphone - the assistant hotword does, from boot - the platform hands us its stream
     * instead of opening ours, and the timing then belongs to that stream, not to this one.
     */
    private static final class MicWatcher implements Runnable {
        /** Energy is accumulated in short windows; 64 frames is 1.3 ms, finer than we need. */
        private static final int ENV_WINDOW = 64;
        private static final int ENV_CAPACITY = SAMPLE_RATE * 20 / ENV_WINDOW;
        /** How far either side of the expected arrival the burst is looked for. */
        private static final int SEARCH_MS = 400;

        private final AudioRecord record;
        private final short[] buffer;
        private final List<long[]> stamps = new ArrayList<>();
        private final float[] envelope = new float[ENV_CAPACITY];
        private volatile int envelopeLength;
        private volatile boolean active = true;
        private Thread thread;
        private long totalFrames;
        private double carry;
        private int carryCount;
        private volatile double peakRms;
        private final MicProbe.Suspension effects;
        private volatile int realStamps;
        private volatile int fallbackStamps;

        MicWatcher() {
            int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBytes * 4);
            buffer = new short[minBytes];
            Log.i(TAG, "mic opened state=" + record.getState() + " rate=" + record.getSampleRate()
                    + " minBytes=" + minBytes);
            // Echo cancellation and noise suppression are not merely inaccurate here, they are
            // actively harmful: an echo canceller exists to remove exactly the sound we are
            // playing, and it adds algorithmic delay of its own, which would land in the answer
            // as if it belonged to the loudspeakers. On stock policies these are absent; the
            // user's BitPerfect module switches them on, so the timing must not depend on it.
            effects = MicProbe.suspendCapturePreprocessing(record.getAudioSessionId(), TAG);
        }

        void start() {
            record.startRecording();
            thread = new Thread(this, "wDSP_LatencyMic");
            thread.start();
        }

        void stop() {
            active = false;
            if (thread != null) {
                try {
                    thread.join(800);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void release() {
            stop();
            // The microphone goes back exactly as it was borrowed - see suspendCapturePreprocessing.
            if (effects != null) effects.restore();
            try {
                record.stop();
            } catch (Throwable ignored) {
            }
            try {
                record.release();
            } catch (Throwable ignored) {
            }
        }

        int stampCount() {
            synchronized (stamps) {
                return stamps.size();
            }
        }

        String stats() {
            return String.format(Locale.US,
                    "mic stats: envelope=%d windows stamps=%d (%d from the HAL, "
                            + "%d guessed) peak=%.5f (%.1f dBFS)",
                    envelopeLength, stampCount(), realStamps, fallbackStamps,
                    peakRms, 20 * Math.log10(peakRms + 1e-9));
        }

        /**
         * Finds the burst nearest the moment it was expected and returns when it was heard.
         *
         * The score is a normalised correlation against the burst's own energy envelope, so what
         * is being matched is the shape of the event rather than its loudness. A weak match is
         * reported rather than hidden - a number that only looks like an answer is worse than
         * none.
         */
        Hit find(long expectedNs) {
            int reference = Math.max(2, burstLen / ENV_WINDOW);
            int searchWindows = SEARCH_MS * SAMPLE_RATE / 1000 / ENV_WINDOW;
            Long expectedFrame = frameAt(expectedNs);
            if (expectedFrame == null) return null;

            int centre = (int) (expectedFrame / ENV_WINDOW);
            int from = Math.max(0, centre - searchWindows);
            int to = Math.min(envelopeLength - reference, centre + searchWindows);
            if (to <= from) return null;

            // Hann squared: the burst was shaped by a Hann window, so its energy follows its
            // square.
            double[] shape = new double[reference];
            double shapeNorm = 0;
            for (int i = 0; i < reference; i++) {
                double h = 0.5 - 0.5 * Math.cos(2 * Math.PI * (i + 0.5) / reference);
                shape[i] = h * h;
                shapeNorm += shape[i] * shape[i];
            }
            shapeNorm = Math.sqrt(shapeNorm);

            double bestScore = -1;
            int bestIndex = -1;
            double sumAll = 0;
            for (int i = from; i < to; i++) {
                double dot = 0;
                double norm = 0;
                for (int k = 0; k < reference; k++) {
                    double v = envelope[i + k];
                    dot += v * shape[k];
                    norm += v * v;
                }
                double score = dot / (Math.sqrt(norm) * shapeNorm + 1e-12);
                double weighted = score * Math.sqrt(norm);
                sumAll += weighted;
                if (weighted > bestScore) {
                    bestScore = weighted;
                    bestIndex = i;
                }
            }
            if (bestIndex < 0) return null;

            long frame = (long) bestIndex * ENV_WINDOW + burstLen / 2L;
            Long when = wallTime(frame);
            if (when == null) return null;
            double average = sumAll / (to - from);
            return new Hit(when, bestScore / (average + 1e-12));
        }

        /** A found burst: when it was heard, and how far it stood above the rest of the search. */
        static final class Hit {
            final long whenNs;
            final double prominence;

            Hit(long whenNs, double prominence) {
                this.whenNs = whenNs;
                this.prominence = prominence;
            }
        }

        private Long frameAt(long whenNs) {
            long[] best = nearestStamp(whenNs, true);
            if (best == null) return null;
            return best[0] + (whenNs - best[1]) * SAMPLE_RATE / 1_000_000_000L;
        }

        private Long wallTime(long frame) {
            long[] best = nearestStamp(frame, false);
            if (best == null) return null;
            return best[1] + (frame - best[0]) * 1_000_000_000L / SAMPLE_RATE;
        }

        private long[] nearestStamp(long key, boolean byTime) {
            List<long[]> readings;
            synchronized (stamps) {
                readings = new ArrayList<>(stamps);
            }
            if (readings.isEmpty()) return null;
            long[] best = readings.get(0);
            long bestDistance = Long.MAX_VALUE;
            for (long[] reading : readings) {
                long distance = Math.abs((byTime ? reading[1] : reading[0]) - key);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = reading;
                }
            }
            return best;
        }

        @Override
        public void run() {
            AudioTimestamp ts = new AudioTimestamp();
            while (active) {
                int read = record.read(buffer, 0, buffer.length);
                long now = System.nanoTime();
                if (read <= 0) continue;

                boolean stamped = record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC)
                        == AudioRecord.SUCCESS;
                synchronized (stamps) {
                    if (stamped) {
                        realStamps++;
                        stamps.add(new long[]{ts.framePosition, ts.nanoTime});
                    } else {
                        // Ceiling, not a measurement: the newest frame is treated as if it had
                        // just been taken, which can only push the arrival later.
                        fallbackStamps++;
                        stamps.add(new long[]{totalFrames + read, now});
                    }
                }

                for (int i = 0; i < read; i++) {
                    double v = buffer[i] / 32768.0;
                    carry += v * v;
                    if (++carryCount == ENV_WINDOW) {
                        double rms = Math.sqrt(carry / ENV_WINDOW);
                        if (rms > peakRms) peakRms = rms;
                        if (envelopeLength < ENV_CAPACITY) {
                            envelope[envelopeLength++] = (float) (rms * rms);
                        }
                        carry = 0;
                        carryCount = 0;
                    }
                }
                totalFrames += read;
            }
        }
    }

    /** Outcome of one measurement, formatted for logcat and for the settings screen. */
    public static final class Result {
        public boolean ok;
        public int sessionId;
        public int burstsSeen;
        public int timestampsSeen;
        public float medianMs;
        public float spreadMs;
        public float frameworkMs = -1f;
        public boolean lowLatency;
        public boolean retried;
        public int micHits;
        public int micTimestamps;
        public float acousticMedianMs = Float.NaN;
        public String error;

        @Override
        public String toString() {
            if (!ok) {
                return "FAILED session=" + sessionId + " bursts=" + burstsSeen
                        + " timestamps=" + timestampsSeen + " error=" + error;
            }
            return String.format(Locale.US,
                    "session=%d lowLatency=%b bursts=%d timestamps=%d median=%.1f ms "
                            + "spread=%.1f ms (framework claims %.0f ms)",
                    sessionId, lowLatency, burstsSeen, timestampsSeen, medianMs,
                    spreadMs, frameworkMs)
                    + (Float.isNaN(acousticMedianMs) ? ""
                       : String.format(Locale.US, " | microphone heard it %.1f ms after capture "
                           + "(%d convincing matches, %d input timestamps)",
                           acousticMedianMs, micHits, micTimestamps));
        }
    }
}
