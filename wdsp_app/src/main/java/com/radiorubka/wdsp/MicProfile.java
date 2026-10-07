package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Locale;

/**
 * Everything this app knows about the microphone it is measuring with: what it is built into,
 * where it sits, and what each of those does to a measurement.
 *
 * <h2>Why this class exists (owner, 21.09.2026)</h2>
 *
 * "We must finally work out properly how the construction and the placement affect all of this,
 * and gather the logic in one place, following the single rule about a source of truth, going by
 * what the user said - otherwise we will circle the mathematics for ever."
 *
 * <p>Before this class the same two facts were answered in four places: a table of mounting curves
 * inside {@code sweep.cpp}, three branches patching the synthesized gains afterwards in
 * {@code RoomMeasurement}, an inference from the place in one helper, and the report's own wording
 * in another. Each was reasonable on its own; together they treated the same hole twice and
 * disagreed about what the microphone even was - on 21.09 the report printed "behind a hole in a
 * panel (assumed from the place)" while the calibration, reading the preference raw, built the
 * curve for a bare capsule.
 *
 * <h2>The doctrine, in the order the answers are trusted</h2>
 *
 * <ol>
 *   <li><b>What the person said</b> comes first, always. They can see their own car; we cannot.
 *       Construction, place, the dot on the plan and the body of the car are all answers they gave,
 *       and nothing here overrules them.</li>
 *   <li><b>What the place implies beyond doubt</b> comes second, and only where there is no doubt:
 *       the head unit's own microphone is always behind a pinhole in the fascia. An inference is
 *       marked as one everywhere it is shown.</li>
 *   <li><b>What the sweep confirms</b> bounds the first two - it never invents a third answer.
 *       The mounting curve below is what a mounting is KNOWN to do; how much of it survives into a
 *       calibration is decided in {@code estimateMicCompensation}, against the measurement, and a
 *       figure the sweep cannot see is not applied (21.09.2026).</li>
 * </ol>
 *
 * <p>Two questions, deliberately kept apart, because they are independent and were once asked as
 * one:
 * <ul>
 *   <li><b>Construction</b> is the transfer function around the capsule - a cavity, a grille, a
 *       foam windscreen. A pinhole is a pinhole on a dashboard and in a headrest alike.</li>
 *   <li><b>Placement</b> is geometry: path length, height above the ear line, and what hard
 *       surface sits a couple of centimetres away. Glass beside the capsule is a boundary, not a
 *       housing.</li>
 * </ul>
 *
 * <p>⚠️ Both lists are index-stable: measurements already made store the index, so adding at the
 * end is safe and reordering is not.
 */
public final class MicProfile {

    private static final String TAG = "wDSP_MicProfile";

    private MicProfile() {}

    // =====================================================================================
    // What the person answered
    // =====================================================================================

    /** The same store the rest of the measurement uses, so one backup carries all of it. */
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(RoomMeasurement.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static final String PREF_PLACE = "room_mic_place";
    private static final String PREF_BODY = "room_mic_body";
    private static final String PREF_LR = "room_mic_lr";
    private static final String PREF_FR = "room_mic_fr";

    /**
     * What the microphone is fitted to, chosen from a list rather than described.
     *
     * <p>The dot on the plan gives the spot on the floor - enough for the delays, which are
     * geometry in the horizontal plane. It says nothing about height, or about what sits a couple
     * of centimetres away, and that is what decides whether the first arrival is the loudspeaker
     * or a reflection. A sun visor and a dome light can be at almost the same point and behave
     * nothing alike: one has a hard flap and the windscreen right beside the capsule, the other
     * has the roof behind it and little else.
     *
     * <p>📻 Three of the first four reports from strangers came back reflection-dominated and the
     * arrival times could not say why. A name can.
     */
    private static final int[] PLACES = {
            R.string.room_mic_place_windscreen,
            R.string.room_mic_place_visor,
            R.string.room_mic_place_pillar_top,
            R.string.room_mic_place_pillar_bottom,
            R.string.room_mic_place_mirror,
            R.string.room_mic_place_dome,
            R.string.room_mic_place_wheel,
            R.string.room_mic_place_dash,
            R.string.room_mic_place_headunit,
            R.string.room_mic_place_headrest,
            R.string.room_mic_place_armrest,
    };

    /** The head unit's own fascia microphone - the one place that implies its own construction. */
    public static final int PLACE_HEAD_UNIT = 8;
    /** The windscreen, the one place whose boundary changes what the synthesis may do. */
    public static final int PLACE_WINDSCREEN = 0;

    /**
     * What the microphone is built into - a different question from where it is.
     *
     * <p>Open in the middle of the fascia and open inside a dome fitting are not the same thing.
     * Neither is a capsule behind a 1.5 mm pinhole, which is a Helmholtz cavity with a resonance
     * of its own.
     */
    private static final int[] BODIES = {
            R.string.room_mic_body_open,
            R.string.room_mic_body_pinhole,
            R.string.room_mic_body_housing,
            R.string.room_mic_body_lavalier,
    };

    public static final int BODY_OPEN = 0;
    public static final int BODY_PINHOLE = 1;
    public static final int BODY_HOUSING = 2;
    public static final int BODY_LAVALIER = 3;

    /** The list as the person sees it, in order. */
    public static String[] placeNames(Context context) {
        String[] out = new String[PLACES.length];
        for (int i = 0; i < PLACES.length; i++) out[i] = context.getString(PLACES[i]);
        return out;
    }

    public static String[] bodyNames(Context context) {
        String[] out = new String[BODIES.length];
        for (int i = 0; i < BODIES.length; i++) out[i] = context.getString(BODIES[i]);
        return out;
    }

    /** {@code -1} when nobody has said yet, which the report prints as "not stated". */
    public static int place(Context context) {
        return context == null ? -1 : prefs(context).getInt(PREF_PLACE, -1);
    }

    public static void setPlace(Context context, int index) {
        if (context != null) prefs(context).edit().putInt(PREF_PLACE, index).apply();
    }

    /** {@code -1} when nobody has said, which is not the same as "open". */
    public static int statedBody(Context context) {
        return context == null ? -1 : prefs(context).getInt(PREF_BODY, -1);
    }

    public static void setBody(Context context, int index) {
        if (context != null) prefs(context).edit().putInt(PREF_BODY, index).apply();
    }

    /**
     * The construction to work with: what was stated, or what the place implies beyond doubt.
     *
     * <p>Only one place implies its own construction: the head unit's own microphone is always
     * behind a pinhole in the fascia - the commonest configuration of all, the one every car
     * without a separate microphone has (owner, 13.09.2026). The place list deliberately no longer
     * says so in its own wording: "Head unit front panel" is a place and nothing but a place, so
     * the two questions stop being asked twice in one screen.
     *
     * <p>Returns {@code -1} when nothing is known, which callers must treat as "no opinion" rather
     * than as "open capsule".
     */
    public static int effectiveBody(int statedBody, int place) {
        if (statedBody >= 0 && statedBody < BODIES.length) return statedBody;
        if (place == PLACE_HEAD_UNIT) return BODY_PINHOLE;
        return -1;
    }

    /** The same question, answered straight from what the person stored. */
    public static int effectiveBody(Context context) {
        return effectiveBody(statedBody(context), place(context));
    }

    // =====================================================================================
    // Where it is: geometry
    // =====================================================================================

    /**
     * How high each place sits, in centimetres, with zero on the listener's ear line.
     *
     * <p>🧩 Reasoned, not measured: these are the ordinary heights of those fittings in an
     * ordinary car, good to a few centimetres, which is the accuracy the rest of this model works
     * at. Indexed by {@link #PLACES}, whose order is frozen.
     */
    private static final float[] PLACE_HEIGHT_CM = {
            +25f,   //  0 windscreen        - high on the glass, above the eye line
            +30f,   //  1 under the visor   - at the roof edge
            +25f,   //  2 A-pillar, top
            -10f,   //  3 A-pillar, bottom  - down by the dash corner
            +30f,   //  4 rear-view mirror
            +45f,   //  5 dome light        - the roof itself
            -15f,   //  6 steering wheel    - below the ears, behind the rim
            +5f,    //  7 dashboard
            +5f,    //  8 head unit front panel
            0f,     //  9 driver headrest   - ear level, by definition
            -25f,   // 10 centre armrest
    };

    /** Ear line is the origin, so this is what it is worth when nobody has said where the mic is. */
    private static final float HEIGHT_UNKNOWN_CM = +5f;

    /** Height of the microphone above the ear line, in centimetres. */
    public static float heightCm(int place) {
        if (place >= 0 && place < PLACE_HEIGHT_CM.length) return PLACE_HEIGHT_CM[place];
        return HEIGHT_UNKNOWN_CM;
    }

    /**
     * Where the person says the microphone is, on the same −1..1 axes the balance control uses.
     *
     * <p>It changes nothing about the sweep and everything about reading the result. Four
     * measurements came back from testers before this existed; the two that failed were the two
     * whose owner had said nothing about placement, and both turned out to have the microphone
     * sitting on one speaker - which from inside the numbers looks exactly like three speakers
     * that are not working.
     */
    public static float spotLeftRight(Context context) {
        return context == null ? 0f : prefs(context).getFloat(PREF_LR, 0f);
    }

    public static float spotFrontRear(Context context) {
        return context == null ? 0f : prefs(context).getFloat(PREF_FR, 0f);
    }

    public static void setSpot(Context context, float leftRight, float frontRear) {
        if (context == null) return;
        prefs(context).edit()
                .putFloat(PREF_LR, leftRight)
                .putFloat(PREF_FR, frontRear)
                .apply();
    }

    // =====================================================================================
    // What the construction does to the sound before the capsule ever sees it
    // =====================================================================================

    /**
     * The mounting's own response, sixteen bands, in dB to be ADDED to the measurement.
     *
     * <p>🔴 Moved here from {@code sweep.cpp} on 21.09.2026 and deleted there. It is a statement
     * about the person's hardware, so it belongs beside the person's answers; the native side is
     * arithmetic and now receives this curve rather than keeping a second copy of it.
     *
     * <p>Researched by Gemini (ROOM_CALIBRATION.md §24). Claude refused it on 13.09.2026 as "a
     * model rather than our own measurement on this hardware"; the owner overruled that, and was
     * right on three counts. The physics is not in doubt - a 1.5..2 mm hole in a panel with a
     * cavity behind it is a Helmholtz resonator, a century of established acoustics. Nothing we
     * have could confirm it anyway: the bench these sweeps run on is a half-open space with a
     * cabinet on one side and a balcony on the other. And the output is coarse - sixteen bands,
     * two decibels a step, a fixed Q of 2.2 - so chasing tighter than ±2 dB is chasing resolution
     * the equaliser does not have.
     *
     * <p>🔑 The signs are easy to get backwards. The synthesis computes pressure as
     * {@code m[b] = measured[b] + compensation[b]}, so a mounting that ADDS a resonant peak is
     * corrected by a NEGATIVE entry (band 11, 3150 Hz, −5.0 dB for a pinhole - otherwise Auto-EQ
     * reads the hole's own resonance as a peak in the car and cuts the voice out of the music),
     * while a mounting that swallows treble is corrected by a POSITIVE one.
     *
     * <p>⚠️ Since 21.09.2026 these figures are a STARTING POINT and not a verdict: the calibration
     * keeps only what the sweep confirms in every channel - a shortfall against the best channel,
     * an excess against the worst. On the owner's own unit that leaves the pinhole's 3150 Hz cut
     * at zero, because no channel shows the peak it exists to undo.
     */
    private static final float[][] MOUNTING_DB = {
            // OPEN - a bare capsule, flat to ±0.5 dB across its band by datasheet.
            {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
             0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, 0.0f},
            // PINHOLE - 1.5..2 mm hole in a panel, cavity behind it. The owner's own case, and the
            // commonest one in cars without an external microphone.
            {0.0f, 0.0f, 0.0f, 2.0f, 0.0f, 0.0f, 0.0f, 0.0f,
             0.0f, 0.0f, -1.5f, -5.0f, -1.0f, 3.5f, 7.0f, 10.0f},
            // HOUSING - recessed in a fitting: a shallower version of the same thing.
            {0.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f,
             0.0f, 0.0f, 0.0f, -1.5f, -1.0f, 3.0f, 6.0f, 8.0f},
            // LAVALIER - clip-on behind foam. No cavity and so no resonance to undo; a foam
            // windscreen costs a little at the very top and nothing else. Deliberately the
            // smallest of the four: §24 did not cover this mounting, and a mild tilt is the honest
            // reading of a windscreen rather than an extrapolation of the pinhole numbers.
            {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
             0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 2.0f, 3.0f},
    };

    /**
     * The mounting curve to start a calibration from - sixteen zeros when nothing is known.
     *
     * <p>Zeros are the honest answer to "not stated": a bare capsule is a claim, and this class
     * does not make claims the person did not.
     */
    public static float[] mountingCurve(Context context) {
        final float[] out = new float[NativeSweep.BAND_COUNT];
        final int body = effectiveBody(context);
        if (body < 0 || body >= MOUNTING_DB.length) {
            Log.i(TAG, "no mounting stated and none implied by the place: the calibration starts "
                    + "from zero");
            return out;
        }
        System.arraycopy(MOUNTING_DB[body], 0, out, 0, NativeSweep.BAND_COUNT);
        return out;
    }

    // =====================================================================================
    // What the placement does to what the synthesis is allowed to do
    // =====================================================================================

    /**
     * Limits the synthesized gains for what the microphone's SURROUNDINGS did, not its housing.
     *
     * <p>One rule so far, and it is about a boundary rather than a fitting: glass a couple of
     * centimetres from the capsule reflects the top back into it, so a microphone on the
     * windscreen hears more treble than the car has, and the synthesis would answer by cutting
     * treble the driver never got. The boost is capped instead.
     *
     * <p>These are hardware indices, not decibels: index 6 is flat and one step is 2 dB.
     *
     * <p>🔴 What is NOT here any more (21.09.2026): three branches that capped and lifted bands
     * according to the CONSTRUCTION - the pinhole's cavity, the housing's and the lavalier's
     * treble. That is the mounting curve's job and it was being done twice; the hump the owner
     * heard across 2..5 kHz in the new Harman curve came out of exactly that duplication.
     */
    public static void applyPlacementLimits(Context context, int[] autoEqGains16) {
        if (context == null || autoEqGains16 == null) return;
        if (place(context) != PLACE_WINDSCREEN) return;
        boolean touched = false;
        for (int b = 10; b < autoEqGains16.length; b++) {
            if (autoEqGains16[b] > 7) {   // index 7 = +2 dB
                autoEqGains16[b] = 7;
                touched = true;
            }
        }
        if (touched) Log.i(TAG, "mic on the windscreen: boundary reflection limiting applied");
    }

    // =====================================================================================
    // The same answers in words, for the report
    // =====================================================================================

    /**
     * What the microphone is built into, in words.
     *
     * <p>Says when it was inferred rather than stated: a reader three weeks from now needs to know
     * the difference between "the owner told us" and "we assumed, because it is the head unit's
     * own microphone".
     */
    public static String bodyDescription(Context context) {
        final int stated = statedBody(context);
        final int eff = effectiveBody(stated, place(context));
        if (eff < 0) return "not stated";
        final String name = englishBody(eff);
        return stated >= 0 ? name : name + " (assumed from the place)";
    }

    /** In English regardless of the person's language: the report is read by us. */
    public static String placeDescription(Context context) {
        final int i = place(context);
        if (i < 0 || i >= PLACES.length) return "not stated";
        return englishPlace(i);
    }

    /** The spot in words - "front right", "centre", and so on. */
    public static String spotDescription(Context context) {
        final float lr = spotLeftRight(context);
        final float fr = spotFrontRear(context);
        // A third of the way out counts as "that side"; nearer the middle than that is the middle,
        // because nobody places a microphone to the centimetre and pretending otherwise would give
        // the reader more confidence than the gesture deserves.
        final String frontRear = fr > 0.33f ? "front" : fr < -0.33f ? "rear" : "middle";
        final String leftRight = lr > 0.33f ? "right" : lr < -0.33f ? "left" : "centre";
        return String.format(Locale.US, "%s %s  (lr %+.2f, fr %+.2f)", frontRear, leftRight, lr, fr);
    }

    private static String englishBody(int i) {
        switch (i) {
            case BODY_OPEN: return "open capsule";
            case BODY_PINHOLE: return "behind a hole in a panel";
            case BODY_HOUSING: return "recessed in a housing";
            case BODY_LAVALIER: return "clip-on with foam";
            default: return "not stated";
        }
    }

    private static String englishPlace(int i) {
        switch (i) {
            case 0: return "windscreen";
            case 1: return "under the sun visor";
            case 2: return "A-pillar, top";
            case 3: return "A-pillar, bottom";
            case 4: return "rear-view mirror";
            case 5: return "dome light";
            case 6: return "steering wheel";
            case 7: return "dashboard";
            case 8: return "head unit front panel";
            case 9: return "driver headrest (ear level)";
            case 10: return "centre armrest";
            default: return "not stated";
        }
    }
}
