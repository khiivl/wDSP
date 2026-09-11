package com.radiorubka.wdsp;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Opens the head unit's microphone and writes what it hears to a WAV, so the capture path itself
 * can be examined before anything is built on top of it.
 *
 * Why this matters on this platform: the stock {@code MIC} source is wired to PCM device 2,
 * named {@code FE_ST_CAPTURE_DSP} in {@code /proc/asound/pcm} - the microphone goes through the
 * AGDSP with its noise reduction, with a 1920x4 frame buffer. That path both delays the signal
 * and reshapes its spectrum, which would quietly ruin any room measurement.
 *
 * {@code /vendor/etc/audio_pcm.xml} offers a way around it:
 *
 * <pre>
 *   &lt;recognition channels="1" rate="48000" period_size="960" period_count="2" device="0"/&gt;
 *   &lt;mm_normal   channels="2" rate="48000" period_size="1920" period_count="4" device="2"/&gt;
 * </pre>
 *
 * Device 0 is {@code FE_ST_NORMAL_AP01}, the direct path to the AP. It looks like the way out,
 * and it is not: measured on this unit, MIC, VOICE_RECOGNITION and UNPROCESSED all land on
 * device 2 alike - the HAL picks {@code mm_normal} regardless of the source, and the
 * {@code recognition} entry is never used. Confirm it the same way this was confirmed, by
 * looking at which capture device came alive rather than at what the config promises.
 *
 * <pre>
 *   adb shell am broadcast -a com.radiorubka.wdsp.PROBE_MIC --ei src 6 --ei ms 4000
 *   adb shell "cat /proc/asound/card0/pcm0c/sub0/status /proc/asound/card0/pcm2c/sub0/status"
 * </pre>
 *
 * Sources: 1 = MIC, 5 = CAMCORDER, 6 = VOICE_RECOGNITION, 9 = UNPROCESSED.
 */
public final class MicProbe {
    private static final String TAG = "wDSP_MicProbe";

    private static final int SAMPLE_RATE = 48000;

    private MicProbe() {
    }

    /** Records for the given time, writes a WAV next to the app's files, logs what it found. */
    public static void probeAsync(final Context context, final int source, final int durationMs) {
        probeAsync(context, source, durationMs, false);
    }

    /**
     * @param lowLatency ask for the MMAP input path. {@code audio_pcm.xml} maps {@code mmap_noirq}
     *                   to PCM device 1, while everything else lands on device 2 - the one named
     *                   {@code FE_ST_CAPTURE_DSP}, where the AGDSP does its voice processing. If
     *                   the request is honoured this is the only way to the raw capsule that does
     *                   not need root.
     */
    public static void probeAsync(final Context context, final int source, final int durationMs,
                                  final boolean lowLatency) {
        new Thread(() -> {
            try {
                probe(context, source, Math.max(500, durationMs), lowLatency);
            } catch (Throwable t) {
                Log.e(TAG, "probe failed", t);
            }
        }, "wDSP_MicProbe").start();
    }

    private static void probe(Context context, int source, int durationMs,
                              boolean lowLatency) throws IOException {
        int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) {
            Log.e(TAG, "getMinBufferSize returned " + minBytes + " - format refused");
            return;
        }
        int bufferBytes = minBytes * 4;

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(lowLatency ? minBytes : bufferBytes);
        // A small buffer is all Java can ask for. There is no setPerformanceMode on the record
        // builder - the MMAP input (device 1) is reachable only through AAudio from native code
        // with EXCLUSIVE sharing, so this flag narrows the buffer and nothing more.
        AudioRecord record = builder.build();
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord refused source=" + source
                    + " (missing RECORD_AUDIO, or the source is unsupported)");
            record.release();
            return;
        }

        Log.i(TAG, "opened source=" + sourceName(source)
                + " lowLatency=" + lowLatency
                + " rate=" + record.getSampleRate()
                + " channels=" + record.getChannelCount()
                + " session=" + record.getAudioSessionId()
                + " minBytes=" + minBytes);
        Suspension effects = suspendCapturePreprocessing(record.getAudioSessionId(), TAG);

        File out = new File(context.getExternalFilesDir(null),
                "mic_" + sourceName(source).toLowerCase(Locale.US) + "_" + SAMPLE_RATE + ".wav");
        short[] buffer = new short[bufferBytes / 4];
        long wanted = (long) SAMPLE_RATE * durationMs / 1000;
        long got = 0;
        double sumSquares = 0;
        int peak = 0;

        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(new byte[44]);   // placeholder, filled in once the length is known
            record.startRecording();
            while (got < wanted) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    Log.w(TAG, "read returned " + read);
                    break;
                }
                byte[] bytes = new byte[read * 2];
                for (int i = 0; i < read; i++) {
                    short s = buffer[i];
                    bytes[i * 2] = (byte) (s & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                    sumSquares += (double) s * s;
                    int magnitude = Math.abs(s);
                    if (magnitude > peak) peak = magnitude;
                }
                fos.write(bytes);
                got += read;
            }
            record.stop();
        } finally {
            effects.restore();
            record.release();
        }

        writeWavHeader(out, got);

        double rms = got > 0 ? Math.sqrt(sumSquares / got) : 0;
        double rmsDb = rms > 0 ? 20 * Math.log10(rms / Short.MAX_VALUE) : -120;
        double peakDb = peak > 0 ? 20 * Math.log10((double) peak / Short.MAX_VALUE) : -120;
        Log.i(TAG, String.format(Locale.US,
                "done source=%s frames=%d rms=%.1f dBFS peak=%.1f dBFS file=%s",
                sourceName(source), got, rmsDb, peakDb, out.getAbsolutePath()));
    }

    /**
     * Reports which platform pre-processing sits on the recording session.
     *
     * {@code audio_effects.xml} on this unit attaches only AEC, and only to voice_communication,
     * so all three are expected to be absent - but a HAL is free to add its own, and a quiet
     * noise suppressor would be indistinguishable from a room that has no bass.
     */
    /**
     * Switches off whatever pre-processing sits on a capture session, remembering what it found.
     *
     * Shared with {@link LatencyProbe}: any measurement that plays a sound and listens for it is
     * ruined by an echo canceller, which exists to remove precisely that, and by noise
     * suppression, which exists to remove steady signals - which is what a test tone is.
     *
     * The caller must {@link Suspension#restore()} when finished. These effects belong to the
     * platform, not to us; on a unit with the BitPerfect module they are switched on deliberately
     * so that phone calls are intelligible, and handing the microphone back in a different state
     * than we borrowed it in would be a change nobody asked for.
     */
    static Suspension suspendCapturePreprocessing(int session, String tag) {
        Log.i(tag, "effects on session " + session
                + ": AEC available=" + AcousticEchoCanceler.isAvailable()
                + " NS available=" + NoiseSuppressor.isAvailable()
                + " AGC available=" + AutomaticGainControl.isAvailable());
        Suspension suspension = new Suspension(tag);
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                suspension.take(AcousticEchoCanceler.create(session), "AEC");
            }
            if (NoiseSuppressor.isAvailable()) {
                suspension.take(NoiseSuppressor.create(session), "NS");
            }
            if (AutomaticGainControl.isAvailable()) {
                suspension.take(AutomaticGainControl.create(session), "AGC");
            }
        } catch (Throwable t) {
            Log.w(tag, "effect inspection failed: " + t);
        }
        return suspension;
    }

    /** Effects switched off for the duration of a measurement, and the state to put back. */
    static final class Suspension {
        private final String tag;
        private final java.util.List<android.media.audiofx.AudioEffect> effects =
                new java.util.ArrayList<>();
        private final java.util.List<String> names = new java.util.ArrayList<>();
        private final java.util.List<Boolean> previous = new java.util.ArrayList<>();

        Suspension(String tag) {
            this.tag = tag;
        }

        void take(android.media.audiofx.AudioEffect effect, String name) {
            if (effect == null) return;
            try {
                boolean was = effect.getEnabled();
                effect.setEnabled(false);
                effects.add(effect);
                names.add(name);
                previous.add(was);
                Log.i(tag, name + " was " + (was ? "ENABLED" : "off") + ", now "
                        + (effect.getEnabled() ? "STILL ON" : "off"));
            } catch (Throwable t) {
                Log.w(tag, name + " could not be switched off: " + t);
                try {
                    effect.release();
                } catch (Throwable ignored) {
                }
            }
        }

        /** Puts every effect back the way it was and lets go of it. */
        void restore() {
            for (int i = 0; i < effects.size(); i++) {
                android.media.audiofx.AudioEffect effect = effects.get(i);
                try {
                    if (previous.get(i)) {
                        effect.setEnabled(true);
                        Log.i(tag, names.get(i) + " restored to enabled");
                    }
                } catch (Throwable t) {
                    Log.w(tag, names.get(i) + " could not be restored: " + t);
                }
                try {
                    effect.release();
                } catch (Throwable ignored) {
                }
            }
            effects.clear();
            names.clear();
            previous.clear();
        }
    }

    /** Canonical 44-byte PCM header, patched in once the payload length is known. */
    private static void writeWavHeader(File file, long frames) throws IOException {
        long dataBytes = frames * 2;
        byte[] header = new byte[44];
        putAscii(header, 0, "RIFF");
        putLe32(header, 4, (int) (36 + dataBytes));
        putAscii(header, 8, "WAVE");
        putAscii(header, 12, "fmt ");
        putLe32(header, 16, 16);
        putLe16(header, 20, 1);
        putLe16(header, 22, 1);
        putLe32(header, 24, SAMPLE_RATE);
        putLe32(header, 28, SAMPLE_RATE * 2);
        putLe16(header, 32, 2);
        putLe16(header, 34, 16);
        putAscii(header, 36, "data");
        putLe32(header, 40, (int) dataBytes);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.seek(0);
            raf.write(header);
        }
    }

    private static void putAscii(byte[] target, int offset, String text) {
        for (int i = 0; i < text.length(); i++) target[offset + i] = (byte) text.charAt(i);
    }

    private static void putLe32(byte[] target, int offset, int value) {
        for (int i = 0; i < 4; i++) target[offset + i] = (byte) ((value >> (8 * i)) & 0xFF);
    }

    private static void putLe16(byte[] target, int offset, int value) {
        for (int i = 0; i < 2; i++) target[offset + i] = (byte) ((value >> (8 * i)) & 0xFF);
    }

    private static String sourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.UNPROCESSED: return "UNPROCESSED";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            default: return "SOURCE_" + source;
        }
    }
}
