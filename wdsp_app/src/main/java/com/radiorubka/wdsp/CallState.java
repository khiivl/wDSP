package com.radiorubka.wdsp;

/**
 * Whether a phone call is in progress. One predicate, used by everything that has to behave
 * differently during a call: the service preset switch, the spectrum analyser (paused), and the
 * screensaver (removed).
 *
 * <p>📻 Measured over four Bluetooth calls on 13.09.2026: {@code sys.current.vol.type} reads
 * {@code btcall_type} for the whole call, steady for 237 consecutive samples, and returns to the
 * previous type on hang-up. The Android audio mode does NOT change - it stays {@code NORMAL}
 * throughout - so {@code AudioManager.getMode()} cannot be used here, however natural it looks.
 *
 * <p>❓ SIM calls and third-party dialers are not covered by that measurement. Whether they set
 * {@code btcall_type} or only the audio mode is untested; if the answer is the mode, it belongs in
 * {@link #isCallType} or next to it - here, once - and not in any of the callers.
 */
public final class CallState {

    private static final String CALL_TYPE = "btcall_type";

    private CallState() {
    }

    /** The predicate itself, for a caller that has already read the active volume type this poll. */
    public static boolean isCallType(String activeVolumeType) {
        return CALL_TYPE.equals(activeVolumeType);
    }

    /** Reads the active volume type and applies {@link #isCallType}. */
    public static boolean isActive() {
        return isCallType(VolumeHelper.getActivePlayerType());
    }
}
