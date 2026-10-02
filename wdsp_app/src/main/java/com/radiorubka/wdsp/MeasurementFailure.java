package com.radiorubka.wdsp;

import android.content.Context;

import java.util.Locale;

/**
 * Why a room measurement or a microphone calibration stopped, said twice from one place: in English
 * for the log and the report a tester sends us, and in the person's language for the dialog.
 *
 * <p>The two used to be one English string in {@code Result.error}, which the dialog showed as it was
 * - so a person on any of the 30 locales read the run's log line. Both texts take the same arguments
 * in the same order, so they cannot drift apart.
 */
enum MeasurementFailure {
    NO_PRESET(R.string.room_err_no_preset,
            "no preset is selected, so there is nothing to measure through"),
    NO_NATIVE_LIBRARY(R.string.room_err_no_native,
            "the native library is not loaded"),
    /**
     * The one failure the person can fix themselves, so the dialog gives them the fix (owner,
     * 14.09.2026: without root, a sweep only after a restart). Arguments: the input rate, and
     * ", opened by it before us" or "".
     */
    MIC_HELD_BY_ANOTHER(R.string.mic_narrow_restart,
            "the microphone is held by another app (input at %1$d Hz%2$s) and could not be taken"
                    + " back - restart the head unit"),
    SWEEP_NOT_BUILT(R.string.room_err_sweep,
            "the sweep could not be built"),
    MIC_NOT_OPENED(R.string.room_err_mic_open,
            "the microphone could not be opened"),
    OUTPUT_NOT_OPENED(R.string.room_err_output_open,
            "the output could not be opened"),
    NOTHING_HEARD(R.string.room_err_nothing_heard,
            "no speaker was heard at all - check the volume and that the microphone is not covered"),
    TOO_FEW_HEARD(R.string.room_err_too_few_heard,
            "only %1$d speaker(s) were heard - nothing to align against");

    private final int textRes;
    private final String english;

    MeasurementFailure(int textRes, String english) {
        this.textRes = textRes;
        this.english = english;
    }

    /** The log and report line. */
    String english(Object... args) {
        return String.format(Locale.US, english, args);
    }

    /** What the dialog says. */
    String localized(Context context, Object... args) {
        return context.getString(textRes, args);
    }
}
