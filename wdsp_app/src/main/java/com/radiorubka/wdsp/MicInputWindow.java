package com.radiorubka.wdsp;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Waits for the moment the microphone input is ours to take again.
 *
 * <p>Used after {@link RadioMicCapture} found the input held narrow by another app and could not
 * take it back (no root). Asking for a restart instead was the first answer, and the owner turned
 * it down on 14.09.2026: "якщо відпускати, повідомляючи людину, що потрібен ребут для мікрофона -
 * людина так ніколи його і не ввімкне на всю ширину".
 *
 * <p>What the platform tells a non-system app is enough to see a gap: every active recording, with
 * the owner anonymised but the client session and the device format kept - the same fact
 * {@link RadioMicCapture} decides narrowness by. The assistant does leave gaps: measured 14.09.2026,
 * it released its recorder at 06:34:47.650 and started the next one at 06:34:49.463, and it lets go
 * before sleep. When it comes back after we are there, it rides our 48 kHz input as a 16 kHz client,
 * as it does after a cold boot.
 *
 * <h2>A gap is not yet a closed input</h2>
 *
 * <p>🔴 Measured 14.09.2026 by stopping the assistant from adb: the list of active recordings was
 * empty 86 ms later, but the 16 kHz input thread was still open; it closed between 86 and 209 ms,
 * and the assistant was recording again at about 1.9 s. The first version of this class opened the
 * capture 75 ms after the empty list, landed on the input that was still open, and got 16 kHz. A
 * recording stops before its recorder is released, and an input is closed only with its last
 * client - until then a new client is given the open input at its old rate. So a gap is left to
 * {@link #SETTLE_MS} to become a closed input, looked at again, and only then is the capture opened;
 * one more try follows if that first one still came up narrow.
 *
 * <p>🔴 It must not loop. A gap only counts after a narrow recording by someone else has been seen
 * while we held no recorder of our own, and each gap gets {@link #ATTEMPTS_PER_GAP} tries. Were the
 * holder invisible to us, a gap would appear the moment our own failed try let go, and the capture
 * would open, hear 16 kHz, close and open again twice a second. With the rule, an invisible holder
 * means waiting quietly for ever.
 *
 * <p>Main thread only.
 */
final class MicInputWindow {
    private static final String TAG = "MicInputWindow";
    private static final int FULL_BAND_RATE = 48000;
    /** From the empty list to the try: the input closed within 209 ms of the holder dying. */
    private static final long SETTLE_MS = 300;
    /** From a try that came up narrow to the next one; the assistant was back after ~1.9 s. */
    private static final long RETRY_MS = 400;
    private static final int ATTEMPTS_PER_GAP = 2;
    /** A failed try is followed by watch() within a second; anything later is a new episode. */
    private static final long RETRY_WINDOW_MS = 3000;

    interface Listener {
        void onInputFree();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private AudioManager audioManager;
    private AudioManager.AudioRecordingCallback callback;
    private BooleanSupplier ownRecorderOpen;
    private int ownSessionId;
    private Listener listener;
    /** A narrow recording by someone else has been seen, and no gap after it yet. */
    private boolean heldSeen;
    private int attemptsLeft;
    private long lastAttemptAt;
    private final Runnable attempt = this::attempt;

    /**
     * Start waiting. Replaces any wait already in progress. Called again after a try that came up
     * narrow, and then it gives the same gap its remaining try.
     *
     * @param ownRecorderOpen whether our capture holds a recorder right now; while it does, the list
     *                        contains it and says nothing about anybody else
     * @param ownSessionId    the session of the recorder just released - the platform may still
     *                        list it for a moment after the release
     */
    void watch(Context context, BooleanSupplier ownRecorderOpen, int ownSessionId, Listener listener) {
        stop();
        if (context == null || listener == null) return;
        audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        this.ownRecorderOpen = ownRecorderOpen;
        this.ownSessionId = ownSessionId;
        this.listener = listener;
        heldSeen = false;
        callback = new AudioManager.AudioRecordingCallback() {
            @Override
            public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
                evaluate(configs);
            }
        };
        try {
            audioManager.registerAudioRecordingCallback(callback, main);
        } catch (Throwable t) {
            Log.w(TAG, "recording callback unavailable - the microphone waits for a restart: " + t);
            callback = null;
            this.listener = null;
            return;
        }
        boolean retry = attemptsLeft > 0
                && SystemClock.uptimeMillis() - lastAttemptAt < RETRY_WINDOW_MS;
        if (!retry) attemptsLeft = 0;
        Log.i(TAG, retry ? "the try came up narrow - one more in " + RETRY_MS + " ms"
                : "waiting for the input to be free");
        evaluate(activeConfigs());
        if (retry && attemptsLeft > 0) main.postDelayed(attempt, RETRY_MS);
    }

    void stop() {
        main.removeCallbacks(attempt);
        if (callback != null && audioManager != null) {
            try {
                audioManager.unregisterAudioRecordingCallback(callback);
            } catch (Throwable ignored) {
            }
        }
        callback = null;
        listener = null;
    }

    private void evaluate(List<AudioRecordingConfiguration> configs) {
        if (listener == null || configs == null) return;
        if (ownRecorderOpen != null && ownRecorderOpen.getAsBoolean()) return;

        int heldAt = narrowRateOfOthers(configs);
        if (heldAt > 0) {
            main.removeCallbacks(attempt);
            attemptsLeft = 0;
            if (!heldSeen) Log.i(TAG, "input held at " + heldAt + " Hz by another app - waiting for a gap");
            heldSeen = true;
            return;
        }
        if (!heldSeen) return;
        heldSeen = false;
        attemptsLeft = ATTEMPTS_PER_GAP;
        Log.i(TAG, "gap: no narrow recording left (" + configs.size() + " active) - trying in "
                + SETTLE_MS + " ms, once the input has had time to close");
        main.removeCallbacks(attempt);
        main.postDelayed(attempt, SETTLE_MS);
    }

    private void attempt() {
        Listener l = listener;
        if (l == null || attemptsLeft <= 0) return;
        int heldAt = narrowRateOfOthers(activeConfigs());
        if (heldAt > 0) {
            attemptsLeft = 0;
            heldSeen = true;
            Log.i(TAG, "gap closed before the try: input held at " + heldAt + " Hz again");
            return;
        }
        attemptsLeft--;
        lastAttemptAt = SystemClock.uptimeMillis();
        Log.i(TAG, "taking the microphone back (" + attemptsLeft + " more tries for this gap)");
        stop();
        l.onInputFree();
    }

    private List<AudioRecordingConfiguration> activeConfigs() {
        try {
            return audioManager != null ? audioManager.getActiveRecordingConfigurations() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The rate of a recording below full band that is not ours, or 0 when there is none. */
    private int narrowRateOfOthers(List<AudioRecordingConfiguration> configs) {
        if (configs == null) return 0;
        for (AudioRecordingConfiguration config : configs) {
            if (config.getClientAudioSessionId() == ownSessionId) continue;
            AudioFormat device = config.getFormat();
            int rate = device != null ? device.getSampleRate() : 0;
            if (rate > 0 && rate < FULL_BAND_RATE) return rate;
        }
        return 0;
    }
}
