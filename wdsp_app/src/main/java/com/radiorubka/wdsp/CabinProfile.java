package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Everything this app knows about the car it measures in, and the physics that follows from it - the
 * cabin's counterpart of {@link MicProfile}, and like it the one place that answers.
 *
 * <h2>Why this class exists (owner, 02.10.2026)</h2>
 *
 * The model has to take the full set of parameters: which speakers the car has (two rear; front and a
 * subwoofer; front and rear; four without a subwoofer; four and a subwoofer), sliders around the car
 * for the cabin's length, width and height, and an open bench or a cabriolet. Cabin gain was taken out
 * of the microphone estimate twice - the second time on 21.09 ({@code 5ad92ae}) - because nobody had
 * asked whether the cabin is closed or how long it is. These are those questions. Canon:
 * {@code .agents/CABIN_MODEL.md} §3-§4.
 *
 * <p>The answers live in the measurement's store ({@link RoomMeasurement#PREFS_NAME}), so one backup
 * carries all of it. An unanswered question takes the canon's default: four speakers and a subwoofer,
 * a D-class sedan of 290 x 150 x 120 cm, closed.
 */
public final class CabinProfile {

    private CabinProfile() {}

    // =====================================================================================
    // What the person answered
    // =====================================================================================

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(RoomMeasurement.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Kept from before this class, so the answer people already gave stays theirs. */
    static final String PREF_HAS_SUBWOOFER = "room_has_subwoofer";
    static final String PREF_HAS_FRONT_PAIR = "room_has_front_pair";
    static final String PREF_HAS_REAR_PAIR = "room_has_rear_pair";
    static final String PREF_LENGTH_CM = "room_cabin_length_cm";
    static final String PREF_WIDTH_CM = "room_cabin_width_cm";
    static final String PREF_HEIGHT_CM = "room_cabin_height_cm";
    static final String PREF_CLOSED = "room_cabin_closed";

    /** The canon's default cabin: a D-class sedan, the acoustic length running into the boot (§3, §14). */
    public static final int DEFAULT_LENGTH_CM = 290;
    public static final int DEFAULT_WIDTH_CM = 150;
    public static final int DEFAULT_HEIGHT_CM = 120;

    /** What the sliders offer - a B-class hatchback to a minibus with room to spare (§14). */
    public static final int MIN_LENGTH_CM = 150, MAX_LENGTH_CM = 450;
    public static final int MIN_WIDTH_CM = 100, MAX_WIDTH_CM = 220;
    public static final int MIN_HEIGHT_CM = 80, MAX_HEIGHT_CM = 200;

    /**
     * Whether a subwoofer is connected. True when unset: sweeping a subwoofer output that is not
     * connected costs one silent channel, while skipping one that is costs the microphone calibration
     * its only source below 160 Hz.
     */
    public static boolean hasSubwoofer(Context context) {
        if (context == null) return true;
        return prefs(context).getBoolean(PREF_HAS_SUBWOOFER, true);
    }

    /**
     * Whether anybody has actually answered the subwoofer question, as opposed to inheriting the
     * default - "true because nobody said" and "true because somebody said" are different facts. Used
     * to ask before a calibration pass rather than after it.
     */
    public static boolean isSubwooferAnswered(Context context) {
        if (context == null) return false;
        return prefs(context).contains(PREF_HAS_SUBWOOFER);
    }

    public static void setHasSubwoofer(Context context, boolean hasSub) {
        if (context == null) return;
        prefs(context).edit().putBoolean(PREF_HAS_SUBWOOFER, hasSub).apply();
    }

    public static boolean hasFrontPair(Context context) {
        if (context == null) return true;
        // At least one pair: a car stored with neither reads as front, the most common single pair.
        return prefs(context).getBoolean(PREF_HAS_FRONT_PAIR, true) || !hasRearPairRaw(context);
    }

    public static boolean hasRearPair(Context context) {
        if (context == null) return true;
        return hasRearPairRaw(context);
    }

    private static boolean hasRearPairRaw(Context context) {
        return prefs(context).getBoolean(PREF_HAS_REAR_PAIR, true);
    }

    /**
     * Which door pairs the car has. The model needs at least one pair - a subwoofer alone is not a
     * layout - so a request for neither is refused and leaves the answer as it was.
     *
     * @return whether the answer was stored
     */
    public static boolean setPairs(Context context, boolean front, boolean rear) {
        if (context == null || (!front && !rear)) return false;
        prefs(context).edit().putBoolean(PREF_HAS_FRONT_PAIR, front).putBoolean(PREF_HAS_REAR_PAIR, rear).apply();
        return true;
    }

    public static int lengthCm(Context context) {
        return context == null ? DEFAULT_LENGTH_CM
                : clamp(prefs(context).getInt(PREF_LENGTH_CM, DEFAULT_LENGTH_CM), MIN_LENGTH_CM, MAX_LENGTH_CM);
    }

    public static int widthCm(Context context) {
        return context == null ? DEFAULT_WIDTH_CM
                : clamp(prefs(context).getInt(PREF_WIDTH_CM, DEFAULT_WIDTH_CM), MIN_WIDTH_CM, MAX_WIDTH_CM);
    }

    public static int heightCm(Context context) {
        return context == null ? DEFAULT_HEIGHT_CM
                : clamp(prefs(context).getInt(PREF_HEIGHT_CM, DEFAULT_HEIGHT_CM), MIN_HEIGHT_CM, MAX_HEIGHT_CM);
    }

    public static void setDimensionsCm(Context context, int length, int width, int height) {
        if (context == null) return;
        prefs(context).edit()
                .putInt(PREF_LENGTH_CM, clamp(length, MIN_LENGTH_CM, MAX_LENGTH_CM))
                .putInt(PREF_WIDTH_CM, clamp(width, MIN_WIDTH_CM, MAX_WIDTH_CM))
                .putInt(PREF_HEIGHT_CM, clamp(height, MIN_HEIGHT_CM, MAX_HEIGHT_CM))
                .apply();
    }

    /** A closed cabin; false for open space - the bench, a cabriolet with the roof down. */
    public static boolean isClosed(Context context) {
        return context == null || prefs(context).getBoolean(PREF_CLOSED, true);
    }

    public static void setClosed(Context context, boolean closed) {
        if (context == null) return;
        prefs(context).edit().putBoolean(PREF_CLOSED, closed).apply();
    }

    // =====================================================================================
    // The physics that follows (CABIN_MODEL.md §4) - pure functions, testable off the device
    // =====================================================================================

    /** Speed of sound, m/s. */
    public static final float SPEED_OF_SOUND = 343f;
    /** The pressure zone never starts above this, however short the cabin. */
    public static final float MAX_TRANSITION_HZ = 80f;
    /** The ideal pressure-zone slope below the transition; real cabins leak and rarely reach it. */
    public static final float CABIN_GAIN_DB_PER_OCTAVE = 12f;
    /** Axial modes are listed up to here; above it the field in a furnished cabin is diffuse. */
    public static final float MODES_UP_TO_HZ = 200f;

    /**
     * Where a sealed cabin stops behaving as a room and becomes a pressure vessel: the frequency whose
     * half-wavelength is the cabin's acoustic length, {@code c / (2L)}, never above
     * {@link #MAX_TRANSITION_HZ}.
     */
    public static float transitionHz(float lengthCm) {
        float hz = SPEED_OF_SOUND / (2f * Math.max(lengthCm, 1f) / 100f);
        return Math.min(hz, MAX_TRANSITION_HZ);
    }

    /** Standing waves along one dimension, {@code n * c / (2d)}, up to {@link #MODES_UP_TO_HZ}. */
    public static float[] axialModesHz(float dimensionCm) {
        float first = SPEED_OF_SOUND / (2f * Math.max(dimensionCm, 1f) / 100f);
        int n = (int) Math.floor(MODES_UP_TO_HZ / first);
        float[] out = new float[Math.max(n, 0)];
        for (int i = 0; i < out.length; i++) out[i] = first * (i + 1);
        return out;
    }

    /**
     * What the cabin itself adds at {@code hz}: the pressure-zone rise below the transition in a
     * closed cabin, nothing in open space or above the transition. The ideal slope - the estimate it
     * feeds decides how much of it the measurement confirms.
     */
    public static float expectedRiseDb(float hz, float transitionHz, boolean closed) {
        if (!closed || hz <= 0f || hz >= transitionHz) return 0f;
        return CABIN_GAIN_DB_PER_OCTAVE * (float) (Math.log(transitionHz / hz) / Math.log(2.0));
    }

    /** {@link #expectedRiseDb(float, float, boolean)} for the car the person described. */
    public static float expectedRiseDb(Context context, float hz) {
        return expectedRiseDb(hz, transitionHz(lengthCm(context)), isClosed(context));
    }

    /**
     * {@link #expectedRiseDb(Context, float)} at the sixteen band centres - what the microphone
     * estimate expects the cabin itself to add there; zeros in open space.
     */
    public static float[] expectedRiseCurve(Context context) {
        final float[] out = new float[AudioConfig.NUM_BANDS];
        final float transition = transitionHz(lengthCm(context));
        final boolean closed = isClosed(context);
        for (int b = 0; b < out.length; b++) {
            out[b] = expectedRiseDb(AudioConfig.BAND_CENTER_HZ[b], transition, closed);
        }
        return out;
    }

    /** For the measurement report, in English as the rest of it. */
    public static String describe(Context context) {
        StringBuilder layout = new StringBuilder();
        if (hasFrontPair(context)) layout.append("front pair");
        if (hasRearPair(context)) layout.append(layout.length() > 0 ? " + " : "").append("rear pair");
        if (hasSubwoofer(context)) layout.append(" + subwoofer");
        String space = isClosed(context)
                ? String.format(java.util.Locale.US, "closed, cabin gain below %.0f Hz", transitionHz(lengthCm(context)))
                : "open space, no cabin gain";
        return String.format(java.util.Locale.US, "%s; %d x %d x %d cm, %s",
                layout, lengthCm(context), widthCm(context), heightCm(context), space);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
