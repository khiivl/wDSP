package com.radiorubka.wdsp;

import android.os.SystemClock;
import android.util.Log;

/**
 * Whether a phone call is in progress. One predicate, used by everything that has to behave
 * differently during a call: the service preset switch, the spectrum analyser (paused & microphone released),
 * and the screensaver (removed).
 *
 * <p>Contract mandate (AUDIO_PATH_AND_MODULE_CONTRACT.md, Ledger Item 7):
 * Nobody holds the microphone during a call. On PHONE_CALL_START / sys.qf.call_state=true every app
 * closes its own AudioRecord immediately. An ordinary capture (pcm device:2) alongside cellular voice
 * stream (pcm device:5) crashes the Unisoc AGDSP (AGDSP_CMD_TIMEOUT / dsp_assert).
 *
 * <p>Signs of active call:
 * 1) sys.qf.call_state = true (SIM dialer / telephony)
 * 2) sys.current.vol.type = btcall_type (Bluetooth call / factory dialer)
 * 3) broadcast com.qf.action.PHONE_CALL_START / _END
 */
public final class CallState {
    private static final String TAG = "wDSP_CallState";

    private static final String CALL_TYPE = "btcall_type";
    /**
     * How long an announcement stands without the hardware confirming a call. Past it the broadcast
     * is taken as orphaned (a missed PHONE_CALL_END, a dialer crash, a test command).
     */
    private static final long ANNOUNCED_WATCHDOG_MS = 6000;

    /**
     * Set by {@code com.qf.action.PHONE_CALL_START}, cleared by {@code _END} or the watchdog. Written
     * on the main thread, read by the poll and the capture threads, hence volatile. It only ever
     * adds to the hardware signs: an announced call is a call, and so is one seen without a broadcast.
     */
    private static volatile boolean callAnnounced = false;
    /** Uptime of the announcement, 0 when none stands. */
    private static volatile long announcedAt = 0;

    private CallState() {
    }

    /** The vendor broadcast: PHONE_CALL_START ({@code true}) or PHONE_CALL_END ({@code false}). */
    public static void announce(boolean start) {
        announcedAt = start ? SystemClock.uptimeMillis() : 0;
        callAnnounced = start;
    }

    public static boolean isCallAnnounced() {
        return callAnnounced;
    }

    /**
     * Clears an announcement the hardware has not confirmed within {@link #ANNOUNCED_WATCHDOG_MS}.
     * Called from the service poll with the volume type it already read this tick.
     */
    public static void expireOrphanedAnnouncement(String activeVolumeType) {
        if (!callAnnounced || isCallType(activeVolumeType) || isPhysicalCallActive()) return;
        long now = SystemClock.uptimeMillis();
        if (announcedAt == 0) {
            announcedAt = now;
        } else if (now - announcedAt > ANNOUNCED_WATCHDOG_MS) {
            Log.w(TAG, "Watchdog: callAnnounced timed out (" + ANNOUNCED_WATCHDOG_MS
                    + " ms) without active call state. Clearing callAnnounced.");
            announce(false);
        }
    }

    /** The predicate itself, for a caller that has already read the active volume type this poll. */
    public static boolean isCallType(String activeVolumeType) {
        return CALL_TYPE.equals(activeVolumeType);
    }

    /** Checks whether a call is physically active on hardware via sys.qf.call_state or active volume type. */
    public static boolean isPhysicalCallActive() {
        if ("true".equalsIgnoreCase(HardwareProfile.systemProperty("sys.qf.call_state"))) {
            return true;
        }
        return isCallType(VolumeHelper.getActivePlayerType());
    }

    /** Checks whether a call is active via broadcast announcement or hardware state. */
    public static boolean isActive() {
        if (callAnnounced) {
            return true;
        }
        return isPhysicalCallActive();
    }
}
