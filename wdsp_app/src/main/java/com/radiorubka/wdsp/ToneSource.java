package com.radiorubka.wdsp;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * A sine, played as if it were something in particular.
 *
 * <h2>Why the probe needs to make its own noise</h2>
 *
 * Playback capture is supposed to hand over only what is allowed to be handed over: media, games
 * and sound with no stated purpose. Navigation, alerts and notifications are meant to be held
 * back at the source, whatever the capturing app asks for. On this unit the asking was measured
 * and found to be ignored entirely - so the only question left is whether the holding back
 * happens either, and that cannot be answered by watching, because navigation speaks when it
 * feels like it and not while a probe is running.
 *
 * <p>So the probe supplies its own navigation: a tone declared with
 * {@code USAGE_ASSISTANCE_NAVIGATION_GUIDANCE}, played on purpose while the capture listens. If
 * it turns up in the recording, every recording made in this car will have the satnav in it.
 */
final class ToneSource {

    private static final String TAG = "CaptureProbe";
    private static final int SAMPLE_RATE = 48000;

    /**
     * Well away from anything music does by accident, and easy to find afterwards.
     */
    private static final double FREQUENCY = 1000.0;

    /** Quiet. This plays out loud in a real car and is only ever meant to be detectable. */
    private static final double AMPLITUDE = 0.1;

    private AudioTrack track;
    private volatile boolean running;
    private Thread thread;

    void start(int usage) {
        int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) minBytes = SAMPLE_RATE;
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(minBytes * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();
        running = true;
        thread = new Thread(() -> {
            short[] buf = new short[SAMPLE_RATE / 10 * 2];
            long n = 0;
            while (running) {
                for (int i = 0; i < buf.length; i += 2) {
                    short s = (short) (Math.sin(2 * Math.PI * FREQUENCY * n / SAMPLE_RATE)
                            * AMPLITUDE * 32767);
                    buf[i] = s;
                    buf[i + 1] = s;
                    n++;
                }
                if (track.write(buf, 0, buf.length) < 0) break;
            }
        }, "probe-tone");
        thread.start();
        Log.i(TAG, "tone playing with usage " + usage);
    }

    void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (track != null) {
            try {
                track.stop();
            } catch (Throwable ignored) {
            }
            track.release();
            track = null;
        }
    }
}
