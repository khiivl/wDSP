package com.radiorubka.wdsp;

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

    private static final String CALL_TYPE = "btcall_type";
    private static volatile boolean callAnnounced = false;

    private CallState() {
    }

    public static void setCallAnnounced(boolean announced) {
        callAnnounced = announced;
    }

    public static boolean isCallAnnounced() {
        return callAnnounced;
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
