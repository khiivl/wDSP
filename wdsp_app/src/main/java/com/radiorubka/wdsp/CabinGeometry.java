package com.radiorubka.wdsp;

import com.radiorubka.wdsp.RoomMeasurement.Channel;

/**
 * Where the microphone and the loudspeakers stand in the cabin - the measurement's one geometry.
 *
 * <p>The delays re-project each arrival from the microphone to the listener through it, and the
 * wiring check asks it which speaker the microphone should hear first; both must ask the same model,
 * or one of them argues with a car the other never saw.
 *
 * <p>Coordinates are centimetres: x across the car (negative is left), y back from the head unit on
 * the dash, z up from the listener's EAR LINE - the scene is built for a head, not for a microphone,
 * so the number that matters most is zero by construction. The figures are approximate on purpose:
 * they move a path by a few centimetres, which is a few hundredths of a millisecond.
 */
final class CabinGeometry {

    /** 343 m/s, in the units the arrivals come in. */
    static final float SOUND_CM_PER_MS = 34.3f;

    /** The driver's ear off the centre line: a headrest microphone and the driver's stage sit here. */
    static final float DRIVER_EAR_X_CM = 35f;

    /** The place index of the driver's headrest in {@link MicProfile}: the microphone is at the ears. */
    private static final int MIC_PLACE_HEADREST = 9;

    /**
     * How far a door speaker sits inside the cabin's own half-width: the door card's thickness. The
     * half-width itself is the person's answer (CabinProfile), measured at shoulder height; until
     * 06.10.2026 it was two constants that disagreed - 80 cm for the microphone's dot and 70 cm for
     * the doors - in a cabin nobody had been asked about.
     */
    private static final float DOOR_CARD_INSET_CM = 5f;

    /** The front doors' speakers, just behind the dash. */
    private static final float FRONT_SPEAKER_Y_CM = 15f;

    /**
     * The rear doors' speakers, this far behind the ear line. The microphone dot's "-1 front" is the
     * same row: the back seat.
     */
    private static final float REAR_ROW_BEHIND_EAR_CM = 95f;

    /** Loudspeaker heights, same ear-line origin. 🧩 Door cards sit well below the ears. */
    private static final float SPEAKER_Z_DOOR_CM = -25f;
    private static final float SPEAKER_Z_SUB_CM = -35f;
    /** A parcel shelf is about at window height, not down on the floor. */
    private static final float SPEAKER_Z_SHELF_CM = -5f;

    private CabinGeometry() {
    }

    /**
     * Where the microphone was, as {x, y, z}.
     *
     * <p>Until 12.09.2026 this threw the owner's answer away: every place except the headrest
     * collapsed to (0,0), so the dot dragged across the car picture changed the report and nothing
     * else. The dot is read now: +1 is against the door, +1 front is the dash, -1 front is the back
     * seat; clamped, because a saved value from an older build may be anything.
     */
    static float[] mic(int micPlace, float spotLr, float spotFr, float halfWidthCm, float distListen) {
        if (micPlace == MIC_PLACE_HEADREST) {
            // The microphone was put where the ears are, which is the one case where no
            // re-projection is needed at all.
            return new float[]{(spotLr > 0.2f) ? DRIVER_EAR_X_CM : -DRIVER_EAR_X_CM, distListen, 0f};
        }
        final float lr = Math.max(-1f, Math.min(1f, spotLr));
        final float fr = Math.max(-1f, Math.min(1f, spotFr));
        return new float[]{lr * halfWidthCm, (1f - fr) * 0.5f * (distListen + REAR_ROW_BEHIND_EAR_CM),
                MicProfile.heightCm(micPlace)};
    }

    /** Where a loudspeaker is, as {x, y, z}. */
    static float[] speaker(Channel ch, float halfWidthCm, float distListen, int subPlace) {
        return new float[]{speakerX(ch, halfWidthCm), speakerY(ch, distListen, subPlace), speakerZ(ch, subPlace)};
    }

    /** Straight-line distance between two points, in centimetres. */
    static double distanceCm(float[] a, float[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])
                + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static float speakerX(Channel ch, float halfWidthCm) {
        final float door = Math.max(0f, halfWidthCm - DOOR_CARD_INSET_CM);
        switch (ch) {
            case FRONT_LEFT:
            case REAR_LEFT:
                return -door;
            case FRONT_RIGHT:
            case REAR_RIGHT:
                return door;
            case SUBWOOFER:
            default:
                return 0f;
        }
    }

    private static float speakerY(Channel ch, float distListen, int subPlace) {
        switch (ch) {
            case FRONT_LEFT:
            case FRONT_RIGHT:
                return FRONT_SPEAKER_Y_CM;
            case REAR_LEFT:
            case REAR_RIGHT:
                return distListen + REAR_ROW_BEHIND_EAR_CM;
            case SUBWOOFER:
                return subwooferY(distListen, subPlace);
            default:
                return FRONT_SPEAKER_Y_CM;
        }
    }

    /**
     * Loudspeaker height above the listener's ear line, in centimetres.
     *
     * 🧩 Door cards put a woofer well below the ears in every ordinary car, and a boot subwoofer
     * lower still. The values are approximate on purpose: they change a path length by a few
     * centimetres, which is a few hundredths of a millisecond - small, but it is the difference
     * between a model that knows the speaker is under the window and one that thinks it is in it.
     */
    private static float speakerZ(Channel ch, int subPlace) {
        switch (ch) {
            case SUBWOOFER:
                // A parcel shelf sits at about window height; a boot floor and an under-seat
                // enclosure are both down near the carpet.
                return (subPlace == RoomMeasurement.SUB_PLACE_SHELF) ? SPEAKER_Z_SHELF_CM : SPEAKER_Z_SUB_CM;
            case FRONT_LEFT:
            case FRONT_RIGHT:
            case REAR_LEFT:
            case REAR_RIGHT:
            default:
                return SPEAKER_Z_DOOR_CM;
        }
    }

    /**
     * How far behind the listener the subwoofer is, by where its owner said it is.
     *
     * <p>🔴 Until 13.09.2026 this was the single expression {@code distListen + 165}, applied
     * whatever the answer. The setting was offered in Settings, saved, copied into the result
     * and printed in the report - and read by nothing. The comment above the list of places
     * spelled out why it mattered ("a boot and an under-seat enclosure are more than a metre
     * apart, which is three milliseconds - six steps of the delay slider") and then the wire
     * to the geometry was never run. Asking somebody a question and discarding the answer is
     * worse than not asking: they believe the measurement knows.
     *
     * <p>The figures are approximate on the same terms as the rest of this model, and they
     * are measured from the ear line, so they follow the seat when the listening distance
     * changes. What matters is that they differ from each other in the right direction and
     * by roughly the right amount; the old behaviour is preserved exactly for the boot, which
     * is what an unanswered setting still means.
     */
    private static float subwooferY(float distListen, int subPlace) {
        switch (subPlace) {
            case RoomMeasurement.SUB_PLACE_UNDER_SEAT:
                // Under the front seats: just ahead of the ear line, not behind it at all.
                // This is the case the old constant got most wrong - by about two and a half
                // metres, which is seven milliseconds of delay applied to a box that needed
                // almost none.
                return distListen - 15f;
            case RoomMeasurement.SUB_PLACE_SHELF:
                // Parcel shelf: behind the rear seat backs, nearer than the boot floor.
                return distListen + 135f;
            case RoomMeasurement.SUB_PLACE_BOOT:
            default:
                return distListen + 165f;
        }
    }
}
