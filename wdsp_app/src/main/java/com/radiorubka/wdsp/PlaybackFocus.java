package com.radiorubka.wdsp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Audio focus for sound this app plays itself - a sweep, a test tone, a test track - held while it
 * plays, so the platform treats wDSP as the player that owns the sound.
 *
 * <p>GAIN rather than one of the transient kinds: a transient grant tells everyone else to duck and
 * come back, and what is wanted is for this to be the player for as long as the test lasts. Whatever
 * was playing should stop, not lower itself under the test. On QF that is also what moves the sound
 * off the radio's channel onto Android's - a test played under the radio is not heard at all.
 *
 * <p>🔴 The listener is not optional decoration. {@code setWillPauseWhenDucked} makes
 * {@code build()} throw IllegalStateException unless a listener was set, and the throw is what the
 * cabin measurement's first version did: every report carried "could not ask:
 * java.lang.IllegalStateException", the pass ran with no focus at all (measured on the owner's own
 * unit, 26.08.2026).
 *
 * <p>One request per instance; the cabin measurement and Audio Check each hold their own.
 */
final class PlaybackFocus {

    /** Told about every focus change while the request stands, on the main thread. */
    interface Listener {
        void onFocusChange(int change);
    }

    private final String tag;
    private AudioFocusRequest request;

    PlaybackFocus(String tag) {
        this.tag = tag;
    }

    /** Whether a request is standing - granted or not, until {@link #abandon} or a loss. */
    boolean isRequested() {
        return request != null;
    }

    /**
     * Asks for GAIN focus for media.
     *
     * @return what the platform answered, in words, for a log or a report
     */
    String request(Context context, Listener listener) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "no AudioManager";
        try {
            request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(change -> {
                        Log.i(tag, "audio focus changed: " + change);
                        if (listener != null) listener.onFocusChange(change);
                    }, new Handler(Looper.getMainLooper()))
                    .build();
            int answer = am.requestAudioFocus(request);
            switch (answer) {
                case AudioManager.AUDIOFOCUS_REQUEST_GRANTED: return "granted";
                case AudioManager.AUDIOFOCUS_REQUEST_DELAYED: return "delayed";
                case AudioManager.AUDIOFOCUS_REQUEST_FAILED: return "REFUSED";
                default: return "answer " + answer;
            }
        } catch (Throwable t) {
            request = null;
            return "could not ask: " + t;
        }
    }

    /** Gives the focus back. Safe to call when nothing was requested. */
    void abandon(Context context) {
        AudioFocusRequest standing = request;
        request = null;
        if (standing == null) return;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocusRequest(standing);
        } catch (Throwable t) {
            Log.w(tag, "could not give the focus back", t);
        }
    }
}
