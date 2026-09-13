package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Measures the car, one loudspeaker at a time.
 *
 * <h2>What it does</h2>
 *
 * For each of the four speakers in turn it steers the sound to that speaker alone, plays a sweep,
 * listens with the microphone, and turns the recording back into an impulse response. From that
 * impulse response three things fall out:
 *
 * <ol>
 *   <li><b>when the sound arrived</b> - the difference between speakers is the time alignment the
 *       delay sliders exist to correct;</li>
 *   <li><b>whether it arrived the right way up</b> - a negative first peak means that speaker is
 *       wired with its terminals swapped, which is the usual reason a subwoofer sounds thin;</li>
 *   <li><b>how loud each band was</b> - the frequency response of speaker plus cabin plus
 *       microphone.</li>
 * </ol>
 *
 * <h2>Why the first two are trustworthy and the third is not</h2>
 *
 * The head unit's microphone is not a measurement microphone and nobody knows its response. That
 * does not matter for timing: an arrival is found by <i>when</i> energy appeared, and a microphone
 * that is six decibels down at 4 kHz still hears the arrival at the same instant. The same goes
 * for polarity, which is a sign, not a level.
 *
 * The frequency response is a different matter. Whatever error the microphone has is added to the
 * measurement in decibels, and if the equaliser were set to flatten what the microphone reports,
 * that error would be inverted straight into the sound. So this class <b>measures</b> the response
 * and writes it to the log, and deliberately stops there. Turning it into equaliser settings needs
 * a way to separate the microphone from the room, which is a separate problem with its own answer
 * (see {@code .agents/ROOM_CALIBRATION.md}).
 *
 * <h2>Why an exponential sweep rather than pink noise</h2>
 *
 * A sweep that rises exponentially spends the same amount of time in every octave, so the bottom
 * of the range - where a single cycle lasts fifty milliseconds - gets as much signal as the top.
 * Deconvolving the recording against the sweep's inverse filter collapses it back to an impulse,
 * and the loudspeaker's harmonic distortion lands at negative times, ahead of the impulse, where
 * it is simply discarded. Noise gives none of that: no impulse response, no arrival time, and
 * distortion mixed into the answer.
 *
 * <h2>What it borrows and gives back</h2>
 *
 * Measuring requires changing the head unit: the sound has to be steered to one speaker, the delay
 * lines have to be off (they would be measured as part of the room), and the equaliser has to be
 * flat (or it would be measured as part of the speaker). All of that belongs to the user, so every
 * value is written to a recovery preference before it is touched and restored afterwards. If the
 * app dies half way through, {@link #restoreIfInterrupted(Context)} puts it back at next start -
 * a measurement must never be able to leave somebody's car sounding wrong.
 *
 * The microphone is borrowed the same way: echo cancellation and noise suppression have to be off
 * while measuring - one exists to remove exactly the sound we are playing - but on head units with
 * custom audio policies they are switched on deliberately, so they go back on afterwards.
 *
 * <h2>How to run it</h2>
 *
 * <pre>
 *   adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM
 *   adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM --ef amp 0.35 --ef sec 3
 * </pre>
 *
 * Everything is logged under the tag {@code wDSP_RoomMeasure}, and the report and recordings are
 * written to {@link #outputDir(Context)} so that a measurement made in somebody else's car can be
 * sent back and examined properly rather than described over chat. Settings has a button that zips
 * the lot and hands it to the system share sheet.
 */
public final class RoomMeasurement {
    private static final String TAG = "wDSP_RoomMeasure";

    private static final int SAMPLE_RATE = 48000;
    /** 20 Hz is below anything a car door can reproduce, but the sweep costs nothing down there. */
    private static final float SWEEP_START_HZ = 20f;
    private static final float SWEEP_END_HZ = 20000f;
    /**
     * Where the sweep stops when the microphone is stuck at 16 kHz.
     *
     * An assistant hotword listener holds the microphone open on many head units and cannot always
     * be stopped - on the one this was written for it is a system app, and
     * {@code killBackgroundProcesses} does not touch it. Every other recording is then served from
     * its 16 kHz stream, resampled, and nothing above 8 kHz survives.
     *
     * Sweeping to 20 kHz in that state wastes more than half the signal: the energy is emitted,
     * never recorded, and the deconvolution has nothing to match it against. Sweeping to 7 kHz
     * instead puts all of it where the microphone can hear it.
     *
     * The cost is only sharpness, and not much of it: a 7 kHz band gives an impulse whose main
     * lobe is about 0.14 ms wide, while the delay sliders move in steps of 0.5 ms. What is lost is
     * the top of the frequency response, which this measurement does not act on anyway.
     */
    private static final float SWEEP_END_NARROW_HZ = 7000f;
    /**
     * How long one sweep lasts. Six seconds, raised from three on 13.09.2026.
     *
     * <p>Owner: "свіп надто швидкий... динамік має встигнути перейти в плато". The plateau argument
     * belongs to stepped-sine measurement rather than to this one - a swept sine is deconvolved, and
     * the impulse response that comes out carries the full response whether or not any single
     * frequency reached steady state. So the sweep is not wrong at three seconds. It is, however,
     * needlessly poor at the bottom, and that is worth the arithmetic:
     *
     * <p>20 Hz to 20 kHz is log2(1000) = 9.97 octaves. At three seconds that is <b>301 ms per
     * octave</b>, and one cycle of 20 Hz lasts 50 ms - so the sweep spends about <b>six cycles</b>
     * inside the lowest band. Six cycles of energy against a cabin whose noise is loudest exactly
     * there is what produced the two symptoms already measured: a +-10 dB scatter across the bottom
     * three bands between two identical empty channels, and a microphone compensation estimate that
     * saturated its +16 dB cap in three bands at once.
     *
     * <p>Doubling the duration doubles the energy in every band: <b>+3 dB of signal-to-noise</b>,
     * uniformly, for nothing but time. Five channels at six seconds with 1.5 s gaps plus lead and
     * tail is about forty seconds of sound. If the bottom bands still scatter after this, the next
     * step is twelve seconds (+6 dB) - and then the wizard text promising "близько півхвилини" has
     * to change with it, which it does not yet at forty seconds.
     */
    private static final float DEFAULT_SECONDS = 6f;
    /**
     * Amplitude of the sweep, not of the head unit.
     *
     * This class never touches the volume control: how loud the car plays is the user's decision
     * and their neighbours' business. A quarter of full scale is loud enough to measure at a
     * normal listening volume and quiet enough not to frighten anybody.
     */
    private static final float DEFAULT_AMPLITUDE = 0.25f;
    /** Recording continues past the last sweep so that the room's decay is captured too. */
    private static final float TAIL_SECONDS = 1.0f;
    /** Silence before the first sweep (ambient noise floor capture), and inside every window. */
    private static final float LEAD_SECONDS = 1.0f;
    public static final String PREF_MIC_COMPENSATION = "pref_mic_compensation";
    /**
     * What the car itself does to the sound, in dB relative to its own midband.
     *
     * <p>Not a level - a shape. It is {@code avgClean16[b] + micCompensation16[b]} minus the mean of
     * bands 5..8, which is exactly the quantity {@code synthesizeAutoEq16} compares against the
     * target curve when it decides the corrections. Stored so the CALCULATED spectrum can stop
     * lying: that mode reads PCM before the DSP and draws it with the DSP's own response applied,
     * so it shows what leaves the amplifier and knows nothing about the speakers or the cabin. Where
     * the car has a hole at 80 Hz, the bar stood level. Owner, 13.09.2026: "ми маємо враховувати
     * віддачу акустики в розрахунковому спектрі… інакше він буде показувати неправду".
     */
    public static final String PREF_CABIN_RESPONSE = "pref_cabin_response";
    /**
     * Silence between sweeps.
     *
     * Long enough for two things at once: the cabin to stop ringing, and the MCU to act on the
     * routing change that is sent half way through it.
     */
    private static final float GAP_SECONDS = 1.5f;
    /**
     * Below this, the recording has no top end and the sweep is being measured through half a
     * microphone. Normal is around -15 dB; a stream that is really 16 kHz gives -70 or worse.
     */
    private static final float BANDWIDTH_WARN_DB = -30f;
    /** The fader and balance sliders run 0..24 with 12 in the middle. */
    private static final int FADER_MIN = 0;
    private static final int FADER_CENTRE = 12;
    private static final int FADER_MAX = 24;
    /** Equaliser gain indices run 0..12, and 6 is flat - see McuService.applyEqualizer(). */
    private static final int EQ_FLAT_INDEX = 6;
    /** Time for the MCU to act on a routing change before the sweep starts. */
    private static final long ROUTING_SETTLE_MS = 800;

    /**
     * Reported, no longer used to decide anything.
     *
     * It looked like a good test on a bench - silent channels gave 19 and 24, driven ones gave
     * thousands - and then the same driven speaker came back with 65 on one run and 2889 on the
     * next, with its arrival time unchanged to the sample. The average it divides by depends on
     * what else fell inside the window, which has nothing to do with whether a speaker was heard.
     * Kept in the report as a second opinion; see MIN_CLARITY_DB for what actually decides.
     */
    private static final float MIN_PROMINENCE = 200f;
    /**
     * The recording also has to contain something. A channel that was never driven still produces
     * an impulse response - of the room noise - and it can look convincing on its own.
     */
    private static final float MIN_PEAK = 0.01f;      // -40 dBFS
    /**
     * How far the direct sound has to stand above the room before its arrival time is believed.
     *
     * A speaker the microphone cannot see still produces an impulse response, and its peak is
     * still repeatable to the sample - it is simply the loudest moment of a diffuse smear rather
     * than the instant the sound arrived. Measured on a bench: the speaker facing the microphone
     * gave +14 dB, the one behind it +1 dB, and only the first of the two described a distance.
     */
    private static final float MIN_CLARITY_DB = 9f;
    /**
     * How far a channel's own response must stand above its own noise before we believe a speaker
     * was driven there at all.
     *
     * <p>{@link #MIN_PEAK} asks the wrong question. It asks how loud the recording was, and a car
     * answers that with its own noise: on the owner's bench, with no rear speakers fitted at all,
     * both rear passes recorded -27.3 dBFS against a -40 dBFS bar, so both "passed". What was in
     * those recordings was cabin noise and the front pair leaking - which is exactly the owner's
     * point: the maths has to tell noise from a speaker's output by itself.
     *
     * <p>The honest test was already computed in the same run and read by nobody:
     * {@code subtractNoise} produces a per-band signal-to-noise ratio. From that measurement:
     * <pre>
     *   front left   43.5 41.1 37.1 27.6 ... 30.5 15.2
     *   front right  44.9 46.6 45.7 32.5 ... 35.4 35.0
     *   subwoofer    44.1 40.8 42.8 33.9 ... 35.6 35.3
     *   rear left     7.8  4.2  3.9 -5.6 -0.6 -1.5 -0.4 4.1 0.8 0.0 ...
     *   rear right  -11.3 -6.5 -3.7 -2.7 -1.0 -0.0  0.3 0.5 ...
     * </pre>
     * A driven speaker stands 25 to 45 dB over its own noise; an empty channel sits at zero or
     * below. Ten decibels separates them with three times the margin on either side, and it is the
     * same figure whatever the cabin noise is, because it is a ratio rather than a level.
     */
    private static final float MIN_SNR_PRESENT_DB = 10f;
    /** Bands the test is taken over: 80 Hz to 5 kHz, where any loudspeaker must produce something. */
    private static final int SNR_TEST_FIRST_BAND = 3;
    private static final int SNR_TEST_LAST_BAND = 12;

    /**
     * The median signal-to-noise ratio over a band range, in dB.
     *
     * <p>Median rather than mean: one band sitting on a cabin resonance, or one that the speaker
     * genuinely cannot produce, should not decide whether the speaker exists.
     */
    private static float medianSnrDb(float[] snrDb, int firstBand, int lastBand) {
        if (snrDb == null) return Float.NEGATIVE_INFINITY;
        final int from = Math.max(0, firstBand);
        final int to = Math.min(snrDb.length - 1, lastBand);
        if (to < from) return Float.NEGATIVE_INFINITY;
        float[] window = new float[to - from + 1];
        System.arraycopy(snrDb, from, window, 0, window.length);
        Arrays.sort(window);
        final int mid = window.length / 2;
        return (window.length % 2 == 1)
                ? window[mid]
                : 0.5f * (window[mid - 1] + window[mid]);
    }
    /**
     * The largest difference in arrival times a vehicle cabin can physically produce.
     * In passenger cars and vans, the distance between any two speakers is under 3.5 metres (< 10 ms).
     * For subwoofers placed in the trunk (with sub amplifier/DSP latency), up to 18 ms (~6.2 m) is allowed.
     */
    private static final float MAX_PLAUSIBLE_SPREAD_MS = 10.0f;
    private static final float MAX_PLAUSIBLE_SUB_SPREAD_MS = 18.0f;

    /**
     * The largest delay that can be entered, in slider steps.
     *
     * <p>Forty, and that figure is the hardware's rather than the interface's. It was measured
     * with {@code --ei delaytest 1}, which holds the routing still and moves the delay line
     * between sweeps instead, so the shift it produces is read off directly:
     *
     * <pre>
     *   10 steps (register 50)  -> +4.979 ms    0.4990 ms per step
     *   25 steps (register 125) -> +12.458 ms   0.4983 ms per step
     *   30 steps (register 150) -> +14.979 ms   0.4993 ms per step
     *   40 steps (register 200) -> +19.958 ms   0.4990 ms per step
     *   45 steps (register 225) -> +21.271 ms   saturated
     * </pre>
     *
     * So the register really is a tenth of a millisecond per unit and the slider really is half a
     * millisecond per step — the labels inherited from the original firmware are right, to within
     * two parts in a thousand. And the delay line runs linearly to <b>20 ms, about 6.9 metres</b>,
     * saturating just above 21.
     *
     * <p>Until this was measured the sliders stopped at ten, five milliseconds, a metre and
     * seventy. Nobody chose that: it came down from the factory app and it is still what the
     * upstream branch has. It is fine for a saloon and useless for a van, where a rear speaker
     * really can be five metres away, so the sliders now go to forty as well — the two limits are
     * raised together, because a suggestion that cannot be typed in is no suggestion at all.
     * Presets written under the old range stay valid; nothing about the labelling changes.
     */
    private static final int MAX_DELAY_STEPS = 40;
    /** Measured, not assumed: 0.4990 ms per step across the linear range. */
    private static final float DELAY_STEP_MS = 0.5f;
    /** Where the delay line itself stops. Beyond this it saturates; the slider now matches it. */
    private static final int HARDWARE_DELAY_STEPS = 40;

    /**
     * Where a measurement leaves its report and recordings.
     *
     * External cache rather than files, for two reasons: the system may reclaim it when space runs
     * short, which is right for something a tester sends once and forgets, and it is the path
     * declared in {@code res/xml/file_paths.xml} so that FileProvider is allowed to hand it to
     * Telegram.
     */
    private static final String OUTPUT_DIR = "measurements";

    /**
      * The preset a measurement runs through.
      *
      * Nothing is measured through the user's own preset any more. Their preset has an equaliser
      * curve, and very likely delay lines, loudness, bass boost and a high-pass - and a high-pass
      * is a real group delay at the bottom, which would be measured as if the loudspeaker were
      * further away than it is. Every user would then get a different and slightly wrong answer,
      * for reasons invisible in the result.
      *
      * So the measurement copies their preset, neutralises everything that colours or delays the
      * sound, switches to the copy, and switches back afterwards. Their own settings are never
      * written to at all, which also means a crash half way through cannot damage them.
      */
     private static final String SCRATCH_PRESET = "wDSP Flat";

     public static final String PREFS_NAME = "EqPresets";
     public static final String PREF_LAST_SELECTED = "last_selected_preset";
     public static final String PREF_PRESET_NAMES = "preset_names";

     /**
      * Centre frequency of each of the sixteen hardware equaliser bands.
      *
      * <p>The same numbers live in {@code kHwCenters} on the native side. Until 13.09.2026 the Java
      * side had them only as a literal inside the report's "Band centres:" line, so any code that
      * needed to know which band is which frequency had nowhere to ask.
      */
     public static final float[] BAND_CENTRES_HZ = {
             20f, 31.5f, 50f, 80f, 125f, 200f, 315f, 500f,
             800f, 1250f, 2000f, 3150f, 5000f, 8000f, 12500f, 20000f
     };

     public static final int[] BASS_FILTER_FREQS_HZ = {20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250};
     public static final int[] SUB_FREQS_HZ = {25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250};

     public enum SoundstageMode {
         DRIVER(0, "Водій", "Водій"),
         FRONT_CENTER(1, "По центру спереду", "Центр"),
         CABIN_CENTER(2, "По центру салону", "Всі"),
         OFF(3, "Без затримок", "Вимкн");

         public final int id;
         public final String title;
         public final String shortTag;

         SoundstageMode(int id, String title, String shortTag) {
             this.id = id;
             this.title = title;
             this.shortTag = shortTag;
         }

         public String getTag() {
             return "(" + shortTag + ")";
         }

         public static SoundstageMode fromId(int id) {
             for (SoundstageMode m : values()) {
                 if (m.id == id) return m;
             }
             return DRIVER;
         }
     }

     public enum TargetCurve {
         HARMAN(0, "Harman Reference", "AutoEQ Harman"),
         DOLBY_ATMOS(1, "Dolby Atmos 3D Cinema", "AutoEQ Dolby Atmos"),
         BASS_HEAVY(2, "Club / Bass Heavy", "AutoEQ Club Bass"),
         VOCAL_SPEECH(3, "Vocal / Podcast", "AutoEQ Vocal"),
         FLAT_STUDIO(4, "Studio Flat", "AutoEQ Flat");

         public final int id;
         public final String title;
         public final String presetName;

         TargetCurve(int id, String title, String presetName) {
             this.id = id;
             this.title = title;
             this.presetName = presetName;
         }

         public static TargetCurve fromId(int id) {
             for (TargetCurve c : values()) {
                 if (c.id == id) return c;
             }
             return HARMAN;
         }
     }

     public enum CarBodyType {
         HATCHBACK(0, "Близька посадка (Хетчбек — 60 см)", 60),
         SEDAN(1, "Середня посадка (Седан / SUV — 75 см)", 75),
         MINIVAN(2, "Далека посадка (Мінівен / Бус — 90 см)", 90),
         CUSTOM(3, "Користувацька", 75);

         public final int id;
         public final String title;
         public final int defaultDistanceCm;

         CarBodyType(int id, String title, int defaultDistanceCm) {
             this.id = id;
             this.title = title;
             this.defaultDistanceCm = defaultDistanceCm;
         }

         public static CarBodyType fromId(int id) {
             for (CarBodyType b : values()) {
                 if (b.id == id) return b;
             }
             return SEDAN;
         }
     }

    /**
     * Where the owner says the microphone is, on the same −1..1 axes the balance control uses:
     * left/right and rear/front, 0 being the middle of the car.
     *
     * <h2>Why the answer has to be asked for</h2>
     *
     * It changes nothing about the sweep. It changes everything about reading the result. Four
     * measurements came back from testers before this existed; the two that failed were the two
     * whose owner had said nothing about placement, and both turned out to have the microphone
     * sitting on one speaker. From inside the numbers that looks exactly like three speakers that
     * are not working — the only way to tell was to compare arrival times afterwards by hand and
     * notice they fitted a corner. Asked once, with a finger, it is known.
     */
    private static final String PREF_MIC_LR = "room_mic_lr";
    private static final String PREF_MIC_FR = "room_mic_fr";

    /**
     * What the microphone is fitted to, chosen from a list rather than described.
     *
     * <h2>Why a name and not just the dot</h2>
     *
     * The dot gives the spot on the floor plan, and that is enough for the delays - they are
     * geometry in the horizontal plane. It says nothing about height, or about what sits a couple
     * of centimetres away, and that is what decides whether the first arrival is the loudspeaker
     * or a reflection. A sun visor and a dome light can be at almost the same point on the plan
     * and behave nothing alike: one has a hard flap and the windscreen right beside the capsule,
     * the other has the roof behind it and little else.
     *
     * <p>📻 Three of the first four reports from strangers came back reflection-dominated, and the
     * arrival times could not say why. A name can: it carries the expected height and the nearest
     * reflector, which is exactly what reading a clarity figure needs - and what an automatic
     * version of this will need before it can decide anything on its own.
     *
     * <p>Stored as the index into this array, so the report and any later analysis agree on what
     * the owner meant. Adding to the end is safe; reordering is not.
     */
    private static final int[] MIC_PLACES = {
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

    private static final String PREF_MIC_PLACE = "room_mic_place";

    /** The list as the owner sees it, in order. */
    public static String[] micPlaceNames(Context context) {
        String[] out = new String[MIC_PLACES.length];
        for (int i = 0; i < MIC_PLACES.length; i++) out[i] = context.getString(MIC_PLACES[i]);
        return out;
    }

    /** {@code -1} when nobody has said yet, which the report prints as "not stated". */
    public static int micPlace(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_MIC_PLACE, -1);
    }

    public static void setMicPlace(Context context, int index) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PREF_MIC_PLACE, index).apply();
    }

    // =====================================================================================
    // Height, and what the microphone is built into - two axes the dot cannot carry
    // =====================================================================================

    /**
     * How high each place sits, in centimetres, **with zero on the listener's ear line**.
     *
     * <p>The scene is built for a head, not for a microphone, so the ear line is the natural
     * origin: the one number that matters most is exact by construction and everything else is a
     * deviation from it. A dome light is nearly half a metre above the ears and an armrest a
     * quarter of a metre below them; treating both as the same point - which is what a flat plan
     * does - throws away a real difference in path length and in what the first arrival even is.
     *
     * <p>🧩 **Reasoned, not measured.** Nobody is asked for centimetres and nobody should be: these
     * are the ordinary heights of those fittings in an ordinary car, good to a few centimetres,
     * which is the accuracy the rest of this model works at anyway. The index is
     * {@link #MIC_PLACES}, and that array's order is frozen - see its own note.
     */
    private static final float[] MIC_PLACE_HEIGHT_CM = {
            +25f,   //  0 windscreen        - high on the glass, above the eye line
            +30f,   //  1 under the visor   - at the roof edge
            +25f,   //  2 A-pillar, top
            -10f,   //  3 A-pillar, bottom  - down by the dash corner
            +30f,   //  4 rear-view mirror
            +45f,   //  5 dome light        - the roof itself
            -15f,   //  6 steering wheel    - below the ears, behind the rim
            +5f,    //  7 dashboard
            +5f,    //  8 built-in head unit mic
            0f,     //  9 driver headrest   - ear level, by definition: the string says so
            -25f,   // 10 centre armrest
    };

    /** Ear line is the origin, so this is what it is worth when nobody has said where the mic is. */
    private static final float MIC_HEIGHT_UNKNOWN_CM = +5f;

    /**
     * Half the cabin width used to turn the dragged dot into centimetres: the door card is about
     * this far from the centre line, and the dot's ±1 means "against the door".
     */
    private static final float CABIN_HALF_WIDTH_CM = 80f;

    /** Loudspeaker heights, same ear-line origin. 🧩 Door cards sit well below the ears. */
    private static final float SPEAKER_Z_DOOR_CM = -25f;
    private static final float SPEAKER_Z_SUB_CM = -35f;

    /**
     * What the microphone is built into - a different question from where it is.
     *
     * <p>The owner's words: open in the middle of the fascia and open inside a dome fitting are not
     * the same thing. Neither is a capsule behind a 1.5 mm pinhole, which is a Helmholtz cavity
     * with a resonance of its own. Place decides path length; construction decides the transfer
     * function of the housing, and the two are independent - a pinhole exists on a dashboard and in
     * a headrest alike.
     *
     * <p>Index-stable exactly like {@link #MIC_PLACES}: add at the end, never reorder.
     */
    private static final int[] MIC_BODIES = {
            R.string.room_mic_body_open,
            R.string.room_mic_body_pinhole,
            R.string.room_mic_body_housing,
            R.string.room_mic_body_lavalier,
    };

    public static final int MIC_BODY_OPEN = 0;
    public static final int MIC_BODY_PINHOLE = 1;
    public static final int MIC_BODY_HOUSING = 2;
    public static final int MIC_BODY_LAVALIER = 3;

    private static final String PREF_MIC_BODY = "room_mic_body";

    public static String[] micBodyNames(Context context) {
        String[] out = new String[MIC_BODIES.length];
        for (int i = 0; i < MIC_BODIES.length; i++) out[i] = context.getString(MIC_BODIES[i]);
        return out;
    }

    /** {@code -1} when nobody has said, which is not the same as "open". */
    public static int micBody(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_MIC_BODY, -1);
    }

    public static void setMicBody(Context context, int index) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PREF_MIC_BODY, index).apply();
    }

    /**
     * The construction to work with, falling back on what the place implies when it was not asked.
     *
     * <p>Only one place implies its own construction beyond doubt: the head unit's own microphone
     * is always behind a pinhole in the fascia. That inference keeps every measurement already made
     * on this unit behaving as it did, instead of silently losing its cavity correction the day a
     * second question appeared on the screen.
     *
     * <p>🔴 It carries more weight since 13.09.2026, because the place list no longer says it out
     * loud. Place 8 used to read "Built-in head unit mic (front panel hole)" - a name that answered
     * the construction question inside the place question, so the same fact was asked twice and the
     * owner, whose microphone is exactly that, could not tell which list to answer and picked
     * "Dashboard". The name is now "Head unit front panel": a place, and nothing but a place. This
     * line is therefore the only remaining place that knows a fascia microphone sits behind a
     * pinhole. Owner, 13.09.2026: this is the commonest configuration of all, the one every car
     * without a separate microphone has.
     *
     * <p>⚠️ The index 8 is load-bearing and frozen: {@link #MIC_PLACES} and
     * {@link #MIC_PLACE_HEIGHT_CM} are indexed by the same number and measurements already made
     * store it. Renaming an entry is safe; reordering the list is not.
     */
    public static int effectiveMicBody(int micBody, int micPlace) {
        if (micBody >= 0 && micBody < MIC_BODIES.length) return micBody;
        if (micPlace == 8) return MIC_BODY_PINHOLE;
        return -1;
    }

    /**
     * Where the subwoofer is, which is a question about path length rather than about tone.
     *
     * <p>🧩 The owner's own reading, and it is right: at these frequencies the cabin is smaller
     * than the wavelength, so the response barely cares where the box stands - the air in the car
     * moves as one. What does care is **when** the sound arrives, and the delay line is one of the
     * three things we can actually set without patching the MCU. A boot and an under-seat enclosure
     * are more than a metre apart, which is three milliseconds - six steps of the delay slider.
     *
     * <p>Index-stable, like the other two lists: add at the end, never reorder.
     */
    private static final int[] SUB_PLACES = {
            R.string.room_sub_place_boot,
            R.string.room_sub_place_shelf,
            R.string.room_sub_place_underseat,
    };

    public static final int SUB_PLACE_BOOT = 0;
    public static final int SUB_PLACE_SHELF = 1;
    public static final int SUB_PLACE_UNDER_SEAT = 2;

    private static final String PREF_SUB_PLACE = "room_sub_place";

    public static String[] subPlaceNames(Context context) {
        String[] out = new String[SUB_PLACES.length];
        for (int i = 0; i < SUB_PLACES.length; i++) out[i] = context.getString(SUB_PLACES[i]);
        return out;
    }

    /** {@code -1} when nobody has said; the boot is then assumed, as it always was. */
    public static int subPlace(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_SUB_PLACE, -1);
    }

    public static void setSubPlace(Context context, int index) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PREF_SUB_PLACE, index).apply();
    }

    private static String englishSubPlace(int i) {
        switch (i) {
            case SUB_PLACE_BOOT: return "boot";
            case SUB_PLACE_SHELF: return "rear parcel shelf";
            case SUB_PLACE_UNDER_SEAT: return "under a seat";
            default: return "not stated (assumed boot)";
        }
    }

    /**
     * Slope a first-order high-pass produces between two frequencies, in dB per octave.
     *
     * <p>Magnitude of a single RC section is {@code f / sqrt(f^2 + fc^2)}; the slope is the
     * difference in dB divided by the number of octaves. Used only to print a yardstick next to a
     * measured slope, never to correct anything.
     */
    private static float firstOrderSlopeDbPerOct(float fromHz, float toHz, float cornerHz) {
        final double lo = 20.0 * Math.log10(fromHz / Math.sqrt(fromHz * fromHz + cornerHz * cornerHz));
        final double hi = 20.0 * Math.log10(toHz / Math.sqrt(toHz * toHz + cornerHz * cornerHz));
        final double octaves = Math.log(toHz / fromHz) / Math.log(2.0);
        return (float) ((hi - lo) / octaves);
    }

    /** Height of the microphone above the ear line, in centimetres. */
    private static float micHeightCm(int micPlace) {
        if (micPlace >= 0 && micPlace < MIC_PLACE_HEIGHT_CM.length) {
            return MIC_PLACE_HEIGHT_CM[micPlace];
        }
        return MIC_HEIGHT_UNKNOWN_CM;
    }

    private static String micPlaceDescription(Context context) {
        int i = micPlace(context);
        if (i < 0 || i >= MIC_PLACES.length) return "not stated";
        // In English regardless of the owner's language: the report is read by us, and a place
        // name in a language nobody on this end reads is worse than no name at all.
        return englishPlace(i);
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

    public static float micSpotLeftRight(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(PREF_MIC_LR, 0f);
    }

    public static float micSpotFrontRear(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(PREF_MIC_FR, 0f);
    }

    public static void setMicSpot(Context context, float leftRight, float frontRear) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putFloat(PREF_MIC_LR, leftRight)
                .putFloat(PREF_MIC_FR, frontRear)
                .apply();
    }

    /**
     * What the microphone is built into, in words, for the report.
     *
     * <p>Reports the **effective** answer, and says when it was inferred rather than stated: a
     * reader three weeks from now needs to know the difference between "the owner told us" and
     * "we assumed, because it is the head unit's own microphone".
     */
    private static String micBodyDescription(Context context) {
        int stated = micBody(context);
        int eff = effectiveMicBody(stated, micPlace(context));
        if (eff < 0) return "not stated";
        String name = englishBody(eff);
        return stated >= 0 ? name : name + " (assumed from the place)";
    }

    private static String englishBody(int i) {
        switch (i) {
            case MIC_BODY_OPEN: return "open capsule";
            case MIC_BODY_PINHOLE: return "behind a hole in a panel";
            case MIC_BODY_HOUSING: return "recessed in a housing";
            case MIC_BODY_LAVALIER: return "clip-on with foam";
            default: return "not stated";
        }
    }

    /** The spot in words, for the report - "front right", "centre", and so on. */
    private static String micSpotDescription(Context context) {
        float lr = micSpotLeftRight(context);
        float fr = micSpotFrontRear(context);
        // A third of the way out counts as "that side"; nearer the middle than that is the middle,
        // because nobody places a microphone to the centimetre and pretending otherwise would give
        // the reader more confidence than the gesture deserves.
        String frontRear = fr > 0.33f ? "front" : fr < -0.33f ? "rear" : "middle";
        String leftRight = lr > 0.33f ? "right" : lr < -0.33f ? "left" : "centre";
        return String.format(Locale.US, "%s %s  (lr %+.2f, fr %+.2f)", frontRear, leftRight, lr, fr);
    }

    public static final String PREF_ROOM_BODY_TYPE = "room_body_type";
    public static final String PREF_ROOM_LISTENING_DIST_CM = "room_listening_dist_cm";
    public static final int DEFAULT_LISTENING_DIST_CM = 75;

    public static CarBodyType getBodyType(Context context) {
        if (context == null) return CarBodyType.SEDAN;
        int id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_ROOM_BODY_TYPE, CarBodyType.SEDAN.id);
        return CarBodyType.fromId(id);
    }

    public static void setBodyType(Context context, CarBodyType type) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PREF_ROOM_BODY_TYPE, type != null ? type.id : CarBodyType.SEDAN.id)
                .apply();
    }

    public static int getListeningDistanceCm(Context context) {
        if (context == null) return DEFAULT_LISTENING_DIST_CM;
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_ROOM_LISTENING_DIST_CM, DEFAULT_LISTENING_DIST_CM);
    }

    public static void setListeningDistanceCm(Context context, int distCm) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PREF_ROOM_LISTENING_DIST_CM, Math.max(40, Math.min(120, distCm)))
                .apply();
    }

    public static final String PREF_ROOM_HAS_SUBWOOFER = "room_has_subwoofer";
    public static final String PREF_ROOM_SOUNDSTAGE = "room_soundstage";
    public static final String PREF_ROOM_TARGET_CURVE = "room_target_curve";
    public static final String PREF_LAST_AUTOEQ_PRESET = "room_last_autoeq_preset";

    public static boolean hasSubwoofer(Context context) {
        if (context == null) return true;
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_ROOM_HAS_SUBWOOFER, true);
    }

    public static void setHasSubwoofer(Context context, boolean hasSub) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_ROOM_HAS_SUBWOOFER, hasSub)
                .apply();
    }

    public static SoundstageMode getSoundstageMode(Context context) {
        if (context == null) return SoundstageMode.DRIVER;
        int id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_ROOM_SOUNDSTAGE, SoundstageMode.DRIVER.id);
        return SoundstageMode.fromId(id);
    }

    public static void setSoundstageMode(Context context, SoundstageMode mode) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PREF_ROOM_SOUNDSTAGE, mode != null ? mode.id : SoundstageMode.DRIVER.id)
                .apply();
    }

    public static TargetCurve getTargetCurve(Context context) {
        if (context == null) return TargetCurve.HARMAN;
        String name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_ROOM_TARGET_CURVE, TargetCurve.HARMAN.name());
        try {
            return TargetCurve.valueOf(name);
        } catch (Exception e) {
            return TargetCurve.HARMAN;
        }
    }

    public static void setTargetCurve(Context context, TargetCurve curve) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_ROOM_TARGET_CURVE, curve != null ? curve.name() : TargetCurve.HARMAN.name())
                .apply();
    }

    public static String getLastAutoEqPreset(Context context) {
        if (context == null) return null;
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_LAST_AUTOEQ_PRESET, null);
    }

    public static void setLastAutoEqPreset(Context context, String presetName) {
        if (context == null) return;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_LAST_AUTOEQ_PRESET, presetName)
                .apply();
    }

    /**
     * Pauses any active media player using KEYCODE_MEDIA_PAUSE before the sweep.
     */
    public static void pauseMedia(Context context) {
        if (context == null) return;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long now = android.os.SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                am.dispatchMediaKeyEvent(new android.view.KeyEvent(now, now,
                        android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                Log.i(TAG, "sent KEYCODE_MEDIA_PAUSE before acoustic sweep");
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not send media pause key", t);
        }
    }

    /**
     * Reads the calibrated 16-band microphone inverse compensation curve from SharedPreferences.
     */
    public static float[] getMicCompensationCurve(Context context) {
        float[] curve = new float[NativeSweep.BAND_COUNT];
        if (context == null) return curve;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String s = prefs.getString(PREF_MIC_COMPENSATION, null);
        if (s == null || s.isEmpty()) return curve;
        String[] parts = s.split(",");
        for (int i = 0; i < Math.min(parts.length, curve.length); i++) {
            try {
                curve[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ignored) {}
        }
        // 🔴 A "legacy upgrade" used to stand here. It recognised a curve saved by an older build
        // (6, 6, 6, 0, 0...) and rewrote it in place as 16, 16, 16, 13, 8 - manufacturing precisely
        // the fiction this project spent the night dismantling. A capsule is flat to +-0.5 dB across
        // its band; below the microphone input's own high-pass there is no signal to restore, only
        // the converter's thermal noise. Promoting an old curve to those numbers made the synthesis
        // believe the car had a huge bass excess and cut real bass in reply. Deleted.
        //
        // What replaces it is a refusal rather than a rewrite. Bands 0..2 are zeroed in the value
        // HANDED OUT, and the stored preference is left exactly as the owner's calibration wrote it:
        // it is his measurement, and quietly editing someone's data is how a disagreement becomes
        // invisible. The zeroing is the same rule estimateMicCompensation now applies, so a curve
        // measured before 13.09.2026 cannot do damage that a curve measured after it could not.
        if (curve[0] != 0f || curve[1] != 0f || curve[2] != 0f) {
            Log.w(TAG, String.format(Locale.US,
                    "stored mic curve has %.1f/%.1f/%.1f dB at 20/31.5/50 Hz - it was measured "
                            + "before 13.09.2026, when the low bands were still being estimated. "
                            + "Nothing there is recoverable (the input high-pass sits at 72..154 Hz), "
                            + "so those three bands are ignored. RE-RUN THE MICROPHONE CALIBRATION "
                            + "before trusting a cabin measurement - the rest of this curve was fitted "
                            + "against the same wrong assumption.",
                    curve[0], curve[1], curve[2]));
            curve[0] = 0f;
            curve[1] = 0f;
            curve[2] = 0f;
        }
        return curve;
    }

    /**
     * Checks if a calibrated microphone compensation curve exists in SharedPreferences.
     */
    public static boolean hasMicCompensation(Context context) {
        if (context == null) return false;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String s = prefs.getString(PREF_MIC_COMPENSATION, null);
        return s != null && !s.trim().isEmpty();
    }

    /**
     * Persists the calibrated 16-band microphone inverse compensation curve to SharedPreferences.
     */
    public static void setMicCompensationCurve(Context context, float[] curve) {
        if (context == null || curve == null || curve.length < NativeSweep.BAND_COUNT) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NativeSweep.BAND_COUNT; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US, "%.2f", curve[i]));
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_MIC_COMPENSATION, sb.toString())
                .apply();
        Log.i(TAG, "saved mic compensation curve to preferences: " + sb);
    }

    /** True when a cabin measurement has left a response curve behind - see {@link #PREF_CABIN_RESPONSE}. */
    public static boolean hasCabinResponse(Context context) {
        if (context == null) return false;
        String s = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_CABIN_RESPONSE, null);
        return s != null && !s.trim().isEmpty();
    }

    /**
     * The car's own response shape, or sixteen zeros when nothing has been measured.
     *
     * <p>Zeros are the honest answer for an unmeasured car: they add nothing to the calculated
     * spectrum, which then shows exactly what it showed before - the signal at the DSP output, and
     * no pretence of knowing the room.
     */
    public static float[] getCabinResponseCurve(Context context) {
        float[] curve = new float[NativeSweep.BAND_COUNT];
        if (context == null) return curve;
        String s = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_CABIN_RESPONSE, null);
        if (s == null || s.isEmpty()) return curve;
        String[] parts = s.split(",");
        for (int i = 0; i < Math.min(parts.length, curve.length); i++) {
            try {
                curve[i] = Float.parseFloat(parts[i].trim());
            } catch (NumberFormatException ignored) {}
        }
        return curve;
    }

    public static void setCabinResponseCurve(Context context, float[] curve) {
        if (context == null || curve == null || curve.length < NativeSweep.BAND_COUNT) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < NativeSweep.BAND_COUNT; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US, "%.2f", curve[i]));
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_CABIN_RESPONSE, sb.toString())
                .apply();
        Log.i(TAG, "saved cabin response shape to preferences: " + sb);
    }
    /** Holds everything a running measurement has changed, so it can be undone after a crash. */
    private static final String PREF_RECOVERY = "room_measure_recovery";

    /**
     * How long the chip is given to receive the flat scratch preset before the first tone.
     *
     * <p>The write goes through a preference listener on a background handler and an EQ throttler,
     * so the registers do not change on the same instruction. A sweep that started earlier would
     * measure the first fraction of a second through the user's curve.
     */
    private static final long PRESET_SETTLE_MS = 450;

    private static volatile boolean running;
    /**
     * Diagnostic: play every sweep through the same routing.
     *
     * With the acoustics held identical, anything that still differs between the four windows
     * belongs to the measurement rather than to the car - which is the only way to tell a real
     * arrival difference from a drift between the recording clock and the playback clock.
     */
    private static volatile boolean sameRouting;

    public static void setSameRouting(boolean same) {
        sameRouting = same;
    }

    /**
     * Diagnostic: hold the routing still and change the delay line between sweeps instead.
     *
     * The sliders are labelled in milliseconds, and those labels came from reading somebody else's
     * code rather than from measuring anything. Here the label is not needed: the four sweeps go to
     * the same loudspeaker with the delay set to a known series of slider values, and because they
     * share one recording the constant cancels, so the differences between their arrivals are
     * exactly what the hardware added.
     *
     * <pre>
     *   adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM --ei delaytest 1  # positional
     *   adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM --ei delaytest 2  # surround
     * </pre>
     *
     * Mode 1 tests the positional line, {@code _d_fr}, against its half-millisecond step; it has
     * been run, and the label was right. Mode 2 tests the surround line, {@code _d1_fr}, against
     * its one-millisecond step, which the firmware suggests is wrong by a factor of 2.125.
     */
    private static volatile int delayTest;
    /** Slider values used by the positional delay test ({@code delaytest 1}), one per sweep. */
    private static final int[] DELAY_TEST_STEPS = {0, 10, 25, 40};
    /**
     * Slider values used by the surround delay test ({@code delaytest 2}), one per sweep.
     *
     * <p>Smaller numbers, because the question is different. The positional test asked how big the
     * step is; this one asks whether the step is what the label says at all. The MCU firmware
     * multiplies these slider values by 102 before they reach the chip, and the chip counts delay
     * in samples at 48 kHz, which would make one step 2.125 ms rather than the 1.0 ms printed
     * beside the slider. Ten steps therefore lands either at 10 ms or at 21.25 ms, and no
     * measurement error confuses those two.
     */
    private static final int[] SURROUND_TEST_STEPS = {0, 3, 6, 10};

    public static void setDelayTest(int mode) {
        delayTest = mode;
    }

    /** The slider values this run is stepping through, whichever delay line is being tested. */
    private static int[] delayTestSteps() {
        return delayTest == 2 ? SURROUND_TEST_STEPS : DELAY_TEST_STEPS;
    }

    /**
     * What a step is worth on the line under test.
     *
     * <p>This used to return 1.0 for the surround line, because 1.0 was what the interface printed
     * and the whole point of {@code delaytest 2} was to find out whether that was true. It was not:
     * the MCU multiplies the slider by 102 and the chip counts samples at 48 kHz, and three runs
     * measured 2.1181, 2.1146 and 2.1167 ms per step against 102/48 = 2.125. The interface was
     * corrected then ({@code MainActivity.SURROUND_DELAY_STEP_MS}); this label was left behind, so
     * a re-run would now compare against a number we know is wrong.
     */
    private static final float SURROUND_STEP_MS = 102f / 48f;

    private static float delayTestLabelMs() {
        return delayTest == 2 ? SURROUND_STEP_MS : DELAY_STEP_MS;
    }

    private RoomMeasurement() {
    }

    /**
     * The four speakers, and how to steer the sound to each one on its own.
     *
     * <p>Confirmed on real cars, 20.08.2026: testers ran the measurement and reported which
     * speaker played the first sweep. It was the <b>rear left</b>, from balance 0 and fader 0 - so
     * balance 0 is the left side as assumed, but fader 0 is the <b>rear</b>, not the front. The
     * table below is the corrected one; before this it named every result mirror-image front to
     * back. The arrival times themselves were never affected, only the labels on them.
     */
    private enum Channel {
        REAR_LEFT("rear left", FADER_MIN, FADER_MIN),
        REAR_RIGHT("rear right", FADER_MAX, FADER_MIN),
        FRONT_LEFT("front left", FADER_MIN, FADER_MAX),
        FRONT_RIGHT("front right", FADER_MAX, FADER_MAX),
        SUBWOOFER("subwoofer", FADER_CENTRE, FADER_CENTRE);

        final String label;
        /** Balance: the value written to {@code <preset>_f_lr}. */
        final int leftRight;
        /** Fader: the value written to {@code <preset>_f_fr}. */
        final int frontRear;

        Channel(String label, int leftRight, int frontRear) {
            this.label = label;
            this.leftRight = leftRight;
            this.frontRear = frontRear;
        }
    }

    /** What one speaker's measurement found. */
    public static final class ChannelResult {
        public String label;
        /** Sample at which the sound arrived, counted from the start of the recording. */
        public int arrivalSamples;
        /**
         * Time of flight in milliseconds, measured on the monotonic clock.
         *
         * Not simply the arrival sample divided by the sample rate. Each channel gets its own
         * recording and its own playback, and the gap between "recording started" and "the first
         * sample of the sweep actually left" is different every time - measured at up to seven
         * milliseconds of variation between runs on a bench where nothing moved. A car is only
         * nine milliseconds wide, so that jitter would have swamped the answer.
         *
         * Both ends therefore report through the platform's own timestamps, which were shown to be
         * honest on this hardware while the picture-to-sound delay was being measured. What
         * remains is the sound's own journey plus a constant that every channel shares, and the
         * delays are differences, so the constant falls out.
         */
        public float arrivalMs;
        /** True when the timestamps were available; without them the delays are not trustworthy. */
        public boolean clockLocked;
        /** How far the arrival stood above everything else. Under ten means nothing was heard. */
        public float prominence;
        /** +1 normal, -1 wired backwards, 0 not determined. */
        public int polarity;
        /** Sixteen band levels in dB, on the hardware equaliser's grid. */
        public final float[] bandsDb = new float[NativeSweep.BAND_COUNT];
        /** Clean response in dB (after ambient noise floor spectral subtraction). */
        public final float[] cleanBandsDb = new float[NativeSweep.BAND_COUNT];
        /** Deconvolved noise floor in dB across the 16 bands. */
        public final float[] noiseBandsDb = new float[NativeSweep.BAND_COUNT];
        /** Signal-to-noise ratio in dB across the 16 bands. */
        public final float[] snrDb = new float[NativeSweep.BAND_COUNT];
        /** Fractional delay from reference channel using GCC-PHAT in milliseconds. */
        public float gccPhatDelayMs;
        /** Prominence of the GCC-PHAT peak. */
        public float gccPhatProminence;
        /** Loudest sample in the recording, so a tester can see at once if it was too quiet. */
        public float recordedPeak;
        /** Level of the whole recording, which separates "quiet" from "one loud click". */
        public float recordedRms;
        /** Energy above 8 kHz against the band below it; far below -25 dB means a 16 kHz stream. */
        public float bandwidthDb;
        /**
         * How far the direct sound stood above the room, in decibels.
         *
         * This is what separates a speaker the microphone can see from one it cannot. Measured on
         * a bench: the speaker facing the microphone gave a sharp impulse and near silence after
         * it; the one sitting behind it gave a smear eleven times weaker whose level was still
         * within seven decibels of the peak a millisecond later. Both arrival times were
         * repeatable to the sample; only one of them meant a distance.
         */
        public float clarityDb;
        /**
         * The speaker was heard and its arrival is physically possible, so it takes part in the
         * alignment. This is deliberately a low bar - see {@link #confident}.
         */
        public boolean ok;
        /**
         * The direct sound stood clearly above what followed it, so this arrival is the speaker's
         * own and not the cabin repeating it.
         *
         * <p>When this is false the channel is still used, because a delay computed from a
         * reflection is far closer to the truth than no delay at all - but it may be optimistic,
         * and the report says so rather than quietly presenting it as fact.
         */
        public boolean confident;
        /**
         * There was signal at all. Below this nothing can be said about the channel.
         *
         * <p>⚠️ Not the same question as "is there a speaker here". A car with no rear speakers still
         * records something on the rear passes - the front pair leaking into the cabin - and it can
         * be well above this bar: on the owner's bench both rears read -27.3 dBFS against a MIN_PEAK
         * of -40 dBFS. What finally rejected them was the arrival time, 57 ms and 1224 ms from the
         * anchor, which no cabin can produce. Both paths end in {@code ok == false}, and everything
         * downstream - the average for the synthesis, the delay projection, the wiring verdict -
         * already treats that as "this speaker does not exist". Owner, 13.09.2026: "не чує = немає".
         */
        public boolean heardAtAll;
    }

    /** Everything a full measurement produced, ready to be logged or shown. */
    public static final class Result {
        public ChannelResult[] channels = new ChannelResult[0];
        /** Delay in milliseconds to add to each channel so that all four arrive together. */
        public final float[] suggestedDelayMs = new float[4];
        /** The same delays in slider steps; the hardware moves in half-millisecond increments. */
        public final int[] suggestedDelaySteps = new int[4];
        /**
         * Deconvolved noise floor of the anchor channel (16 bands) in dB.
         *
         * <p>Named "noise floor" and reported as "ambient" for a long time, and it is neither: it
         * comes out of the impulse response, not out of the cabin. The real cabin silence is
         * {@link #ambientNoiseDb16}, which this array used to overwrite.
         */
        public final float[] noiseFloorDb16 = new float[NativeSweep.BAND_COUNT];
        /**
         * Which channel the deconvolved floor above was taken from. It is one channel's, not an
         * average, and the report used to print the numbers without saying so - which reads as a
         * property of the room and is a property of the anchor.
         */
        public String noiseFloorChannel = "";
        /**
         * The cabin's own silence, measured from the lead-in second before the first sweep tone.
         *
         * <p>🔴 Until 12.09.2026 this was measured into {@code noiseFloorDb16} and then overwritten
         * a few hundred lines later by the deconvolved figure, so the one quantity that describes
         * the car the driver is sitting in was taken and thrown away in the same run. Nothing
         * consumed it, and the report printed the other number under its name.
         *
         * <p>It is kept separate now. It is still not subtracted from anything - the spectral
         * subtraction at the channel level uses the deconvolved floor, deliberately, because that
         * is the one that shares the sweep's own domain. What this is for is the live analyser and
         * the noise question the owner raised: the floor drifts while driving and wants
         * re-measuring, and none of that can start from a number that does not survive the run.
         */
        public final float[] ambientNoiseDb16 = new float[NativeSweep.BAND_COUNT];
        /**
         * Signal-to-noise ratio per band, averaged over the channels the synthesis actually used.
         *
         * <p>This is how far above its own noise each band's measurement stood, and it decides how
         * much of the correction for that band is believed - a ramp from nothing at 6 dB to full
         * trust at 18 dB, applied in synthesizeAutoEq16. It was computed per channel from the
         * beginning ({@code subtractNoise} fills {@code ChannelResult.snrDb}) and read by nothing
         * but the report, so a band measured three decibels above the noise was corrected exactly
         * as confidently as one measured forty above - and that happens most at the bottom, where
         * the cabin is loudest and the corrections are biggest.
         */
        public final float[] avgSnrDb16 = new float[NativeSweep.BAND_COUNT];
        /**
         * What the car does to the sound, in dB about its own midband - see
         * {@link #PREF_CABIN_RESPONSE} for why this is kept rather than only reported.
         */
        public final float[] cabinResponseDb16 = new float[NativeSweep.BAND_COUNT];
        /**
         * Whether {@link #cabinResponseDb16} holds a measurement or is still sixteen zeros.
         *
         * <p>A microphone calibration pass never reaches analyzeAcousticsAndSynthesize, so its
         * report would otherwise print a flat cabin response and a slope of zero - a picture of a
         * perfectly neutral car that nobody measured.
         */
        public boolean cabinResponseMeasured;
        /** 16-band microphone inverse compensation curve in dB. */
        public final float[] micCompensation16 = new float[NativeSweep.BAND_COUNT];
        /**
         * Whether the curve above was a calibrated one, or sixteen zeros standing in for it.
         *
         * <p>The run answers this itself, at the one line where it loads the curve, by asking the
         * one function that owns the question ({@link #hasMicCompensation}). No caller passes it in
         * and no screen decides it: a dialog can be skipped, reworded or added on a second path
         * into the wizard, and this stays true regardless. Without it the difference between a
         * measurement of the car and a measurement of the car plus the microphone's own colouring
         * left no trace anywhere in the result.
         */
        public boolean micCalibrated;
        public String error;
        public String reportPath;
        /** What the microphone guard found and did, in one line for the report. */
        public String microphone;
        /** What the platform answered when the sweep asked to be the player. */
        public String focus;
        /** Where the sweep stopped - lower than usual when the microphone could not be freed. */
        public float sweepTopHz = SWEEP_END_HZ;
        /** True when a channel needs more delay than the hardware can apply - a long vehicle. */
        public boolean beyondHardware;
        public boolean reflectionDominated;

        // Auto-EQ & Soundstage extensions
        public boolean hasSubwoofer;
        public SoundstageMode soundstageMode = SoundstageMode.DRIVER;
        public TargetCurve targetCurve = TargetCurve.HARMAN;
        public CarBodyType bodyType = CarBodyType.SEDAN;
        public int listeningDistanceCm = DEFAULT_LISTENING_DIST_CM;
        public int midbassHpfIdx = 0; // default Through
        public int midbassHpfFreqHz = 0;
        public int subLpfIdx = 4; // default 63 Hz
        public int subLpfFreqHz = 63;
        public int subGain = 2; // default +2 dB (slider 0..12, 0 is 0 dB)
        public int suggestedSubDelaySteps = 0;
        public float suggestedSubDelayMs = 0f;
        public final int[] autoEqGains16 = new int[NativeSweep.BAND_COUNT];
        public boolean hasPolarityInversion = false;
        public String wiringWarning = null;

        // Microphone placement & cavity awareness
        public float micSpotLr = -0.5f;
        public float micSpotFr = 0.5f;
        public int micPlace = -1;
        /** What the capsule is built into - independent of where it is. -1 = nobody has said. */
        public int micBody = -1;
        /** Where the subwoofer stands. -1 = nobody has said, and the boot is assumed. */
        public int subPlace = -1;

        public boolean isUsable() {
            if (channels == null || channels.length == 0) return false;
            for (ChannelResult c : channels) {
                if (c == null || !c.ok) return false;
            }
            return true;
        }
    }

    public interface Listener {
        default void onProgress(String stage) {}
        default void onProgress(int step, int totalSteps, String stageTitle, String stageDetail, int percent) {
            onProgress(stageTitle + (stageDetail != null && !stageDetail.isEmpty() ? ": " + stageDetail : ""));
        }
        void onFinished(Result result);
    }

    public static boolean isRunning() {
        return running;
    }

    /** Runs a full measurement on its own thread with subwoofer, soundstage mode, target curve, body type and listening distance. */
    public static void measureAsync(final Context context, final float amplitude,
                                    final float seconds, final boolean hasSubwoofer,
                                    final SoundstageMode soundstageMode,
                                    final TargetCurve targetCurve,
                                    final CarBodyType bodyType,
                                    final int listeningDistanceCm,
                                    final Listener listener) {
        if (running) {
            Log.w(TAG, "a measurement is already running, ignoring this request");
            return;
        }
        new Thread(() -> {
            running = true;
            Result result;
            try {
                result = measure(context, amplitude, seconds, hasSubwoofer, soundstageMode, targetCurve, bodyType, listeningDistanceCm, listener, false);
            } catch (Throwable t) {
                result = new Result();
                result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.e(TAG, "measurement failed", t);
            } finally {
                running = false;
            }
            if (listener != null) listener.onFinished(result);
        }, "wDSP_RoomMeasure").start();
    }

    public static void calibrateMicAsync(final Context context, final Listener listener) {
        if (running) {
            Log.w(TAG, "a measurement is already running, ignoring mic calibration request");
            return;
        }
        new Thread(() -> {
            running = true;
            Result result;
            try {
                // The three literals here are deliberate, not defaults left lying about: a
                // calibration pass ends at the capsule curve and never reaches synthesizeAutoEq16,
                // so stage and target curve cannot affect its result, and `false` is what keeps it
                // sweeping the four main speakers instead of five - the subwoofer has nothing to
                // say about a microphone's own response. The car's body and the listening distance
                // are read from the one place that owns them, as everywhere else.
                result = measure(context, DEFAULT_AMPLITUDE, DEFAULT_SECONDS, false,
                        SoundstageMode.DRIVER, TargetCurve.HARMAN,
                        getBodyType(context), getListeningDistanceCm(context), listener, true);
            } catch (Throwable t) {
                result = new Result();
                result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
                Log.e(TAG, "mic calibration failed", t);
            } finally {
                running = false;
            }
            if (listener != null) listener.onFinished(result);
        }, "wDSP_MicCalibration").start();
    }

    /**
     * The short way in, used by the debug broadcast: what the car is gets read here, once, from the
     * place that owns it.
     *
     * <p>This used to hand the run {@code false, SoundstageMode.DRIVER, TargetCurve.HARMAN},
     * invented on the spot, and three further overloads did the same in different combinations. The
     * same five facts are also written to preferences by the wizard and passed as arguments by the
     * settings screen, so which car a measurement ran against depended on which door it came
     * through: a sweep started from {@code MEASURE_ROOM} measured a car with no subwoofer, driver
     * stage and a curve nobody had chosen, while the identical button in Settings measured the real
     * one. The overloads that could invent an answer are gone; this is the only short form left,
     * and it asks.
     */
    public static void measureAsync(final Context context, final float amplitude,
                                    final float seconds, final Listener listener) {
        measureAsync(context, amplitude, seconds,
                hasSubwoofer(context), getSoundstageMode(context), getTargetCurve(context),
                getBodyType(context), getListeningDistanceCm(context), listener);
    }
    public static void measureAsync(final Context context, final boolean hasSubwoofer,
                                    final SoundstageMode soundstageMode, final TargetCurve targetCurve,
                                    final CarBodyType bodyType, final int listeningDistanceCm,
                                    final Listener listener) {
        measureAsync(context, DEFAULT_AMPLITUDE, DEFAULT_SECONDS, hasSubwoofer, soundstageMode, targetCurve, bodyType, listeningDistanceCm, listener);
    }


    /**
     * Puts back anything a measurement changed but did not manage to restore.
     *
     * Called from the service at start-up. A measurement writes what it is about to change into a
     * single preference and clears it when it has finished; anything left there means the app died
     * with somebody's equaliser flattened and their sound coming out of one door.
     */
    public static void restoreIfInterrupted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(PREF_RECOVERY, null);
        if (saved == null || saved.isEmpty()) return;

        VolumeHelper.init(context);
        McuService.ensureStarted(context);
        Log.w(TAG, "a previous measurement did not finish; restoring what it changed: " + saved);
        SharedPreferences.Editor editor = prefs.edit();
        applySaved(editor, saved);
        editor.remove(PREF_RECOVERY);
        editor.apply();

        context.sendBroadcast(new Intent("com.radiorubka.wdsp.RESET_AUDIO_MCU").setPackage(context.getPackageName()));
    }

    // ---------------------------------------------------------------------------------------
    // the measurement itself
    // ---------------------------------------------------------------------------------------

    private static Result measure(Context context, float amplitude, float seconds,
                                  boolean hasSubwoofer, SoundstageMode soundstageMode,
                                  TargetCurve targetCurve,
                                  CarBodyType bodyType, int listeningDistanceCm,
                                  Listener listener, boolean isMicCalibrationOnly) {
        Result result = new Result();
        result.hasSubwoofer = hasSubwoofer;
        result.soundstageMode = soundstageMode != null ? soundstageMode : SoundstageMode.DRIVER;
        result.targetCurve = targetCurve != null ? targetCurve : TargetCurve.HARMAN;
        result.bodyType = bodyType != null ? bodyType : CarBodyType.SEDAN;
        result.listeningDistanceCm = listeningDistanceCm > 0 ? listeningDistanceCm : DEFAULT_LISTENING_DIST_CM;
        result.channels = new ChannelResult[hasSubwoofer ? 5 : 4];

        Context app = context.getApplicationContext();
        result.micSpotLr = micSpotLeftRight(app);
        result.micSpotFr = micSpotFrontRear(app);
        result.micPlace = micPlace(app);
        result.micBody = micBody(app);
        result.subPlace = subPlace(app);

        SharedPreferences prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String preset = prefs.getString("last_selected_preset", null);
        if (preset == null) {
            result.error = "no preset is selected, so there is nothing to measure through";
            Log.e(TAG, result.error);
            return result;
        }
        if (!NativeSweep.isAvailable()) {
            result.error = "the native library is not loaded";
            Log.e(TAG, result.error);
            return result;
        }

        if (listener != null) {
            listener.onProgress(1, 5, "Замір фону тиші", "Вимір фонового шуму в тиші та фіксація гучності (16 од.)...", 5);
        }

        Log.i(TAG, "=== room measurement starting ===");
        Log.i(TAG, "preset=" + preset + " amplitude=" + amplitude + " sweep=" + seconds + " s sub=" + hasSubwoofer + " stage=" + result.soundstageMode);
        Log.i(TAG, HardwareProfile.describe());

        // Take the microphone back if something else has it, then sweep only as high as whatever
        // we ended up with can actually hear.
        MicrophoneGuard.Outcome mic = MicrophoneGuard.ensureOurs(app);
        result.microphone = mic.toString();
        final float topHz = (mic.wasHeld && !mic.freed) ? SWEEP_END_NARROW_HZ : SWEEP_END_HZ;
        if (topHz < SWEEP_END_HZ) {
            Log.w(TAG, "the microphone is limited to 16 kHz and could not be freed, so the sweep "
                    + "stops at " + (int) topHz + " Hz instead of " + (int) SWEEP_END_HZ
                    + " - delays are unaffected, the top of the response is not measured");
        } else if (mic.unknown) {
            // The full sweep still runs: a probe that failed is not evidence of a narrow
            // microphone, and cutting a good measurement short on a guess would be worse. But it
            // is said out loud, here and in result.microphone, because until now this case was
            // indistinguishable from a microphone that had been checked and found free.
            Log.w(TAG, "the microphone could not be checked, so the sweep runs to the full "
                    + (int) SWEEP_END_HZ + " Hz on trust - if the top of the response looks empty, "
                    + "that is the first thing to suspect");
        }

        // Touch only: preset (switched to flat scratch preset) and volume (locked to 16).
        McuService.ensureStarted(app);
        VolumeHelper.init(app);
        int origVolume = VolumeHelper.getVolume();
        Log.i(TAG, "locking volume for measurement: " + origVolume + " -> 16");
        String saved = "last_selected_preset=" + preset + ";saved_volume=" + origVolume;
        prefs.edit().putString(PREF_RECOVERY, saved).apply();
        VolumeHelper.setVolume(16);
        VolumeHelper.setVolumeForType("media_type", 16);
        int readbackVol = VolumeHelper.getVolume();
        Log.i(TAG, "locked volume for measurement: " + origVolume + " -> 16 (readback=" + readbackVol
                + ", activeType=" + VolumeHelper.getActivePlayerType() + ")");
        buildScratchPreset(prefs, preset);

        // And now actually switch to it. Until 13.09.2026 this line did not exist, and the whole
        // idea above was a preference nobody read: McuService pushes one preset to the chip, the one
        // named by last_selected_preset, and its preference listener only reacts to keys that start
        // with that name (McuService.prefListener, the `key.startsWith(currentPresetName)` branch).
        // The scratch preset's keys start with "wDSP Flat", so writing them changed nothing in the
        // hardware while the user's own preset stayed loaded - with its equaliser curve, its delay
        // lines, its bass boost and its loudness. Every sweep measured the correction on top of the
        // car, which is exactly what the comment above promises not to do. The owner spotted it from
        // the other end: "свіпити ж треба у повному флат режимі".
        //
        // Order matters: the copy is finished first, then selected, or the listener would push a
        // half-built preset. RESET_AUDIO_MCU is sent rather than trusting the listener, because it
        // clears the MCU cache and calls syncPreset() itself - one path, whatever the timing.
        prefs.edit().putString(PREF_LAST_SELECTED, SCRATCH_PRESET).apply();
        app.sendBroadcast(new Intent("com.radiorubka.wdsp.RESET_AUDIO_MCU").setPackage(app.getPackageName()));
        sleep(PRESET_SETTLE_MS);
        Log.i(TAG, "measuring through " + SCRATCH_PRESET + " (selected and pushed to the chip),"
                + " copied from " + preset + "; the user's selection is in " + PREF_RECOVERY
                + " and is restored in the finally block and by restoreIfInterrupted()");

        try (NativeSweep sweep = new NativeSweep(SAMPLE_RATE, SWEEP_START_HZ, topHz, seconds)) {
            if (!sweep.isValid()) {
                result.error = "the sweep could not be built";
                return result;
            }
            runOnePass(app, prefs, SCRATCH_PRESET, sweep, amplitude, result, listener, isMicCalibrationOnly);

            if (!isMicCalibrationOnly) {
                if (listener != null) {
                    listener.onProgress(3, 5, "Аналіз затримок", "Розрахунок часового вирівнювання (GCC-PHAT)...", 85);
                }
                computeDelays(result, result.soundstageMode);

                if (listener != null) {
                    listener.onProgress(4, 5, "Синтез Auto-EQ", "Аналіз спаду мідбасів, сабвуфера та 16 смуг...", 92);
                }
                analyzeAcousticsAndSynthesize(app, result);

                result.sweepTopHz = topHz;
                result.reportPath = writeReport(app, result, preset, amplitude, seconds);

                if (listener != null) {
                    listener.onProgress(5, 5, "Готово", "Калібрування завершено успішно", 100);
                }
            } else {
                result.sweepTopHz = topHz;
                result.reportPath = writeReport(app, result, preset, amplitude, seconds);

                if (listener != null) {
                    listener.onProgress(5, 5, "Готово", "Калібрування мікрофона успішно завершено", 100);
                }
            }
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            applySaved(editor, saved);
            editor.remove(PREF_RECOVERY);
            editor.apply();

            // Refresh preset in McuService and sync all DSP registers
            app.sendBroadcast(new Intent("com.radiorubka.wdsp.RESET_AUDIO_MCU").setPackage(app.getPackageName()));
            Log.i(TAG, "restored settings and synced preset to " + preset);

            Log.i(TAG, "restoring volume to " + origVolume);
            VolumeHelper.setVolume(origVolume);
            VolumeHelper.setVolumeForType("media_type", origVolume);
            Log.i(TAG, "switched back to " + preset);
        }


        logResult(result);
        return result;
    }

    /**
     * Plays all four sweeps in one go and records them in one go.
     *
     * <h3>Why it has to be one pass</h3>
     *
     * The first version opened a fresh recording and a fresh playback for every speaker. That
     * looks tidier and it is wrong: the gap between "recording started" and "the first sample of
     * the sweep actually left the hardware" is different every time a stream is opened. Measured
     * on a bench where nothing moved, the same pair of speakers came out 6.4 ms apart, then
     * 2.7 ms, then 4.5 ms <i>the other way round</i>. A whole car is only nine milliseconds wide,
     * so that jitter was larger than the thing being measured.
     *
     * Platform timestamps did not rescue it either - a single reading taken early in playback is
     * not accurate enough to extrapolate back to the first frame.
     *
     * With one stream in each direction the skew between them is a single unknown constant for
     * the whole measurement. It appears identically in all four arrivals, and the delays are
     * differences, so it cancels exactly. Nothing has to be known about it at all.
     *
     * The routing is switched during the silence between sweeps, which is also where the cabin is
     * given time to stop ringing.
     */
    private static void runOnePass(Context context, SharedPreferences prefs, String preset,
                                   NativeSweep sweep, float amplitude, Result result,
                                   Listener listener, boolean isMicCalibrationOnly) {
        final Channel[] channels = result.hasSubwoofer ? Channel.values() : new Channel[]{
                Channel.REAR_LEFT, Channel.REAR_RIGHT, Channel.FRONT_LEFT, Channel.FRONT_RIGHT
        };
        final int sweepLen = sweep.length();
        final int gap = (int) (GAP_SECONDS * SAMPLE_RATE);
        final int lead = (int) (LEAD_SECONDS * SAMPLE_RATE);
        final int period = sweepLen + gap;
        final int totalFrames = lead + channels.length * period;
        final int recordLen = totalFrames + (int) (TAIL_SECONDS * SAMPLE_RATE);

        float[] mono = new float[sweepLen];
        sweep.generate(mono, amplitude);

        // One long track: quiet, sweep, quiet, sweep, and so on.
        short[] stereo = new short[totalFrames * 2];
        for (int k = 0; k < channels.length; k++) {
            final int at = lead + k * period;
            for (int i = 0; i < sweepLen; i++) {
                short v = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, Math.round(mono[i] * Short.MAX_VALUE)));
                stereo[(at + i) * 2] = v;
                stereo[(at + i) * 2 + 1] = v;
            }
        }
        Log.i(TAG, "one pass: sweep " + sweepLen + " samples, gap " + gap + ", period " + period
                + ", total " + totalFrames + " frames (" + (totalFrames / (float) SAMPLE_RATE)
                + " s)");

        AudioTrack track = null;
        AudioRecord record = null;
        MicProbe.Suspension effects = null;
        short[] captured = new short[recordLen];
        int got = 0;

        try {
            if (delayTest != 0) {
                // One loudspeaker, chosen once, and the delay line is what changes. The front
                // right is used because on the bench this was written against it is the one the
                // microphone hears directly - a smeared arrival would blur the very shift being
                // measured.
                Log.i(TAG, "delay test: routing fixed to the front right, delay steps "
                        + java.util.Arrays.toString(delayTestSteps()));
                prefs.edit()
                        .putInt(preset + "_f_lr", FADER_MAX)
                        .putInt(preset + "_f_fr", FADER_MAX)
                        .apply();
                sleep(ROUTING_SETTLE_MS);
            }
            // 🔴 Everything from the previous measurement goes first, because the archive is
            // built by sweeping this folder and it cannot tell an old file from a new one. The
            // owner's archive of 26.08 proved the cost: it carried per-speaker recordings from
            // the 20th, written by a version that still produced them, and a zip stamps every
            // entry with the moment it was packed - so six-day-old recordings of a different
            // measurement arrived looking exactly as fresh as the report beside them. Whoever
            // reads that archive is diagnosing two cars at once without being told.
            clearPreviousRun(context);

            // The first speaker is selected before anything starts, so its sweep is not the one
            // that has to wait for the routing to take effect.
            applyRouting(prefs, preset, channels[0]);
            sleep(ROUTING_SETTLE_MS);

            // Pause media player before sweep
            pauseMedia(context);

            // Ask to be the player before making a sound.
            //
            // 🔴 This was missing, and it is the best explanation anybody has for the oldest
            // complaint about this feature: the first measurement on a unit fails, and then it
            // works after a Bluetooth call, or simply the next day. Both of those force the
            // platform to re-establish who owns the audio.
            //
            // On this platform the volume only reaches the amplifier for the source named in
            // sys.current.vol.type, and one failing tester's report had that property **unset**.
            // A track that never asked for focus never makes the platform decide it is the
            // player, so the sweep can be written, mixed, and never actually amplified - and from
            // the microphone that looks exactly like three speakers that are not connected.
            //
            // Held for the whole pass rather than per sweep: dropping and retaking it four times
            // would invite the platform to re-route between channels, which is the one thing this
            // measurement must not have happen in the middle of it.
            result.focus = requestFocus(context);
            Log.i(TAG, "audio focus for the sweep: " + result.focus);

            record = openMicrophone();
            if (record == null) {
                result.error = "the microphone could not be opened";
                Log.e(TAG, result.error);
                return;
            }
            effects = MicProbe.suspendCapturePreprocessing(record.getAudioSessionId(), TAG);

            track = openTrack(stereo.length);
            if (track == null) {
                result.error = "the output could not be opened";
                Log.e(TAG, result.error);
                return;
            }

            record.startRecording();
            track.play();
            final long playStartedMs = System.currentTimeMillis();

            final AudioTrack playing = track;
            Thread writer = new Thread(() -> {
                int offset = 0;
                while (offset < stereo.length) {
                    int written = playing.write(stereo, offset, stereo.length - offset);
                    if (written <= 0) break;
                    offset += written;
                }
            }, "wDSP_RoomSweepOut");
            writer.start();

            // Routing is switched in the silence before each sweep. The timing comes from the
            // wall clock rather than from frames written, because what matters is when the MCU
            // acts, and it acts on its own schedule - the gap is long enough to absorb both.
            Thread router = new Thread(() -> {
                for (int k = 1; k < channels.length; k++) {
                    // Tied to ROUTING_SETTLE_MS rather than to a fraction of the gap. It used to be
                    // gap/2 - 750 ms ahead of the sweep - while the code's own figure for how long
                    // the MCU needs to act on a routing change is 800 ms. Two numbers about the same
                    // thing, fifty milliseconds apart, and nothing connecting them: the first tones
                    // of each sweep could still be leaving the previous speaker. Now the switch is
                    // sent exactly one settle-time before the sweep, which with a 1.5 s gap still
                    // leaves 700 ms for the cabin to stop ringing after the previous one.
                    final long settleFrames = ROUTING_SETTLE_MS * SAMPLE_RATE / 1000L;
                    final long switchAtMs = (long) ((lead + k * period - settleFrames)
                            * 1000L / SAMPLE_RATE);
                    long waitMs = switchAtMs - (System.currentTimeMillis() - playStartedMs);
                    if (waitMs > 0) sleep(waitMs);
                    int pct = 15 + (k * 65) / channels.length;
                    if (listener != null) {
                        listener.onProgress(2, 5, "Замір динаміків", "Відтворення свіпу: " + channels[k].label, pct);
                    }
                    applyRouting(prefs, preset, channels[k]);
                }
            }, "wDSP_RoomRouting");
            if (listener != null) {
                listener.onProgress(2, 5, "Замір динаміків", "Відтворення свіпу: " + channels[0].label, 15);
            }

            router.start();

            while (got < recordLen) {
                int read = record.read(captured, got, recordLen - got);
                if (read <= 0) {
                    Log.w(TAG, "read returned " + read);
                    break;
                }
                got += read;
            }
            try {
                writer.join(2000);
                router.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Throwable t) {
            result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "the pass failed", t);
            return;
        } finally {
            if (effects != null) effects.restore();
            closeQuietly(track);
            closeQuietly(record);
            // Reset scratch routing and filters back to neutral
            prefs.edit()
                    .putInt(preset + "_f_lr", FADER_CENTRE)
                    .putInt(preset + "_f_fr", FADER_CENTRE)
                    .putInt(preset + "_sub_g", 0)
                    .putInt(preset + "_bf_f", 0)
                    .putInt(preset + "_bf_r", 0)
                    .apply();
            abandonFocus(context);
        }

        // The whole recording is kept as one file. Four separate ones would have to be lined up
        // again by whoever looks at them, and lining them up is the entire difficulty.
        writeWav(context, "room_measurement.wav", captured, got);

        float[] asFloat = new float[got];
        int peak = 0;
        double sumSquares = 0;
        for (int i = 0; i < got; i++) {
            asFloat[i] = captured[i] / 32768f;
            if (Math.abs(captured[i]) > peak) peak = Math.abs(captured[i]);
            sumSquares += (double) asFloat[i] * asFloat[i];
        }
        final float passPeak = peak / 32768f;
        final float passRms = got > 0 ? (float) Math.sqrt(sumSquares / got) : 0f;
        final float passBandwidth = NativeSweep.bandwidthRatioDb(asFloat, got, SAMPLE_RATE);
        Log.i(TAG, String.format(Locale.US,
                "pass: %d frames, peak %.1f dBFS, rms %.1f dBFS, above 8 kHz %.1f dB",
                got, 20 * Math.log10(passPeak + 1e-9f), 20 * Math.log10(passRms + 1e-9f),
                passBandwidth));
        if (passBandwidth < BANDWIDTH_WARN_DB) {
            Log.w(TAG, "the recording has nothing above 8 kHz. The microphone is running at "
                    + "16 kHz because something else has it open - an assistant hotword is the "
                    + "usual cause, and the platform will not admit it.");
        }

        // Live ambient noise floor measured directly from the physical cabin silence (first lead-in
        // seconds). Into its own array: this used to be written into noiseFloorDb16, which the
        // anchor channel's deconvolved floor overwrites later in the same run.
        if (got >= lead) {
            sweep.noiseFloor(asFloat, lead, result.ambientNoiseDb16);
            float noisePeak = 0f;
            double noiseSumSq = 0;
            for (int i = 0; i < lead; i++) {
                float a = Math.abs(asFloat[i]);
                if (a > noisePeak) noisePeak = a;
                noiseSumSq += (double) a * a;
            }
            float noiseRms = (float) Math.sqrt(noiseSumSq / lead);
            int noisePeakInt = Math.round(noisePeak * 32768f);
            Log.i(TAG, String.format(Locale.US,
                    "live ambient cabin noise measured: peak=%d (%.1f dBFS), rms=%.1f dBFS",
                    noisePeakInt, 20 * Math.log10(noisePeak + 1e-9), 20 * Math.log10(noiseRms + 1e-9)));
            prefs.edit()
                    .putInt("room_calibrated_noise_peak", noisePeakInt)
                    .putFloat("room_calibrated_noise_rms_db", (float) (20 * Math.log10(noiseRms + 1e-9)))
                    .apply();
        }

        // Each sweep is cut out with a generous margin. The window is short enough that the next
        // sweep cannot fall inside it, so the strongest peak in each window belongs to the sweep
        // that window was cut for.
        final int windowLen = lead + sweepLen + (int) (1.0f * SAMPLE_RATE);
        float[] analysis = new float[NativeSweep.RESULT_SIZE];
        float[][] channelImpulses = new float[channels.length][];

        for (int k = 0; k < channels.length; k++) {
            ChannelResult cr = new ChannelResult();
            cr.label = channels[k].label;
            cr.recordedPeak = passPeak;
            cr.recordedRms = passRms;
            cr.bandwidthDb = passBandwidth;
            result.channels[k] = cr;

            final int from = k * period;
            final int len = Math.min(windowLen, got - from);
            if (len < sweepLen) {
                Log.w(TAG, cr.label + ": the recording ended before this sweep");
                continue;
            }
            float[] window = new float[len];
            System.arraycopy(asFloat, from, window, 0, len);

            int windowPeak = 0;
            double windowSum = 0;
            for (float v : window) {
                windowSum += (double) v * v;
                if (Math.abs(v) > windowPeak / 32768f) windowPeak = Math.round(Math.abs(v) * 32768f);
            }
            cr.recordedPeak = windowPeak / 32768f;
            cr.recordedRms = (float) Math.sqrt(windowSum / len);

            if (!sweep.analyse(window, len, analysis)) {
                Log.w(TAG, cr.label + ": nothing in this part of the recording looked like the "
                        + "sweep");
                continue;
            }
            cr.arrivalSamples = Math.round(analysis[NativeSweep.ARRIVAL]);
            // Sub-sample arrival time in ms
            cr.arrivalMs = analysis[NativeSweep.ARRIVAL] * 1000f / SAMPLE_RATE;
            cr.clockLocked = true;
            cr.prominence = analysis[NativeSweep.PROMINENCE];
            cr.polarity = (int) analysis[NativeSweep.POLARITY];
            cr.clarityDb = analysis[NativeSweep.CLARITY];
            System.arraycopy(analysis, NativeSweep.BANDS, cr.bandsDb, 0, NativeSweep.BAND_COUNT);
            // Deconvolved impulse response silence noise floor (sample 48), perfectly matching bandsDb domain
            System.arraycopy(analysis, NativeSweep.NOISE_BANDS, cr.noiseBandsDb, 0, NativeSweep.BAND_COUNT);

            // Spectral subtraction: clean = max(sweep - noise, 1e-12), snr = sweep - noise
            NativeSweep.subtractNoise(cr.bandsDb, cr.noiseBandsDb, cr.cleanBandsDb, cr.snrDb);

            // Deconvolve impulse response for GCC-PHAT
            float[] impBuf = new float[len];
            int impLen = sweep.deconvolve(window, len, impBuf);
            if (impLen > 0) {
                channelImpulses[k] = new float[impLen];
                System.arraycopy(impBuf, 0, channelImpulses[k], 0, impLen);
            }

            // Clarity decides, not prominence. Prominence compares the loudest instant of the
            // impulse response with its average, and the average moves with whatever else landed
            // in the window - measured on a bench, the same speaker gave 2889 on one run and 65
            // on the next while its arrival time stayed put to the sample. Clarity asks a
            // physical question instead: did the microphone hear this speaker directly, or only
            // the room repeating it. Prominence is still reported, because it costs nothing and a
            // second opinion is useful when a measurement looks odd.
            // Two different questions, and conflating them threw away good measurements.
            //
            // "Was this speaker heard at all" is answered by the level: below MIN_PEAK there is
            // nothing to work with. That is the bar for taking part in the alignment.
            //
            // "Is this arrival the speaker's own, or the cabin repeating it" is answered by
            // clarity, and in a real car the answer is often no - which does not make the
            // measurement useless. A microphone standing on the instrument binnacle sits a
            // hand's width from the windscreen and from the top of the binnacle itself, so
            // every speaker arrives with two strong reflections a fraction of a millisecond
            // behind it. Measured in one: 15.7 dB for the nearest speaker and 3.9 to 4.7 dB for
            // the other three. On a bench in the open air the same code gave 23 to 33 dB, and
            // that is where the nine-decibel threshold came from - the least representative
            // place it could have been calibrated.
            // Two tests, and the second is the one that means anything. The level says the recording
            // was not empty; the ratio says what was in it belonged to a loudspeaker rather than to
            // the car. A channel with nothing connected still records cabin noise and the other
            // speakers leaking, and it can clear the level bar by thirteen decibels while standing
            // nowhere at all above its own noise floor - see MIN_SNR_PRESENT_DB for the measurement.
            final float midSnrDb = medianSnrDb(cr.snrDb, SNR_TEST_FIRST_BAND, SNR_TEST_LAST_BAND);
            final boolean loudEnough = cr.recordedPeak >= MIN_PEAK;
            final boolean aboveOwnNoise = midSnrDb >= MIN_SNR_PRESENT_DB;
            cr.heardAtAll = loudEnough && aboveOwnNoise;
            cr.confident = cr.clarityDb >= MIN_CLARITY_DB;
            cr.ok = cr.heardAtAll;
            Log.i(TAG, String.format(Locale.US,
                    "%s: peak %.1f dBFS (%s), median SNR %.1f dB over bands %d-%d (%s) -> %s",
                    cr.label, 20 * Math.log10(cr.recordedPeak + 1e-9f),
                    loudEnough ? "above the level bar" : "below the level bar",
                    midSnrDb, SNR_TEST_FIRST_BAND, SNR_TEST_LAST_BAND,
                    aboveOwnNoise ? "a speaker was driven" : "noise, not a speaker",
                    cr.ok ? "counted" : "not present"));
            if (delayTest != 0 && k > 0 && result.channels[0] != null) {
                // What the hardware actually did, against what the slider claims it would do.
                final float moved = cr.arrivalMs - result.channels[0].arrivalMs;
                final int steps = delayTestSteps()[k];
                Log.i(TAG, String.format(Locale.US,
                        "delay test: %2d steps moved the arrival by %+.3f ms  (%.4f ms per step; "
                                + "the slider is labelled %.1f)",
                        steps, moved, moved / steps, delayTestLabelMs()));
            }

            Log.i(TAG, String.format(Locale.US,
                    "%s: arrival %.2f ms in its window (sample %d), clarity %.1f dB, "
                            + "prominence %.0f, polarity %+d, peak %.1f dBFS, rms %.1f dBFS%s",
                    cr.label, cr.arrivalMs, cr.arrivalSamples, cr.clarityDb, cr.prominence,
                    cr.polarity,
                    20 * Math.log10(cr.recordedPeak + 1e-9f),
                    20 * Math.log10(cr.recordedRms + 1e-9f),
                    cr.ok ? "" : "  <-- TOO WEAK TO TRUST"));
        }

        // 2. GCC-PHAT high-precision delay estimation
        // 2. Select anchor channel with HIGHEST clarityDb among real speakers
        int refIdx = -1;
        float maxClarity = -100f;
        for (int k = 0; k < channels.length; k++) {
            ChannelResult cr = result.channels[k];
            if (cr != null && cr.ok && cr.clarityDb > maxClarity) {
                maxClarity = cr.clarityDb;
                refIdx = k;
            }
        }

        // The deconvolved floor reported is that of the anchor channel. This no longer destroys the
        // measured cabin silence: that lives in result.ambientNoiseDb16 and the two are different
        // quantities - one comes from the impulse response, the other from the car.
        if (refIdx >= 0 && result.channels[refIdx] != null) {
            System.arraycopy(result.channels[refIdx].noiseBandsDb, 0, result.noiseFloorDb16, 0, NativeSweep.BAND_COUNT);
            result.noiseFloorChannel = result.channels[refIdx].label;
        }
        StringBuilder nfLog = new StringBuilder("deconvolved noise floor (16 bands):");
        for (float v : result.noiseFloorDb16) {
            nfLog.append(String.format(Locale.US, " %.1f", v));
        }
        Log.i(TAG, nfLog.toString());

        // Disqualify phantom arrivals that are physically too far from the anchor (> 30 ms)
        if (refIdx >= 0) {
            ChannelResult anchor = result.channels[refIdx];
            for (int k = 0; k < channels.length; k++) {
                ChannelResult cr = result.channels[k];
                if (cr == null || k == refIdx) continue;
                final float apart = Math.abs(cr.arrivalMs - anchor.arrivalMs);
                if (apart > MAX_PLAUSIBLE_SPREAD_MS) {
                    cr.ok = false;
                    Log.w(TAG, String.format(Locale.US,
                            "%s: arrived %.1f ms from anchor %s (> %.0f ms) - disqualified phantom",
                            cr.label, apart, anchor.label, MAX_PLAUSIBLE_SPREAD_MS));
                }
            }
        }

        // GCC-PHAT high-precision delay estimation relative to the anchor
        if (refIdx >= 0 && channelImpulses[refIdx] != null) {
            ChannelResult anchor = result.channels[refIdx];
            float[] refImp = channelImpulses[refIdx];
            for (int k = 0; k < channels.length; k++) {
                ChannelResult cr = result.channels[k];
                if (cr == null || channelImpulses[k] == null) continue;
                if (k == refIdx) {
                    cr.gccPhatDelayMs = 0.0f;
                    cr.gccPhatProminence = 1000.0f;
                    continue;
                }
                if (!cr.ok) {
                    cr.gccPhatDelayMs = 0.0f;
                    cr.gccPhatProminence = 0.0f;
                    continue;
                }
                // Precise relative delay based on sub-sample arrivals
                cr.gccPhatDelayMs = cr.arrivalMs - anchor.arrivalMs;
                cr.gccPhatProminence = cr.prominence;
                Log.i(TAG, String.format(Locale.US,
                        "Delay relative to %s: %s delay = %+.3f ms (%.1f us), prom = %.1f",
                        anchor.label, cr.label, cr.gccPhatDelayMs,
                        cr.gccPhatDelayMs * 1000f, cr.gccPhatProminence));
            }
        }

        if (isMicCalibrationOnly) {
            // 3. Microphone calibration pass: estimate and persist hardware capsule response curve!
            float[] avgClean16 = new float[NativeSweep.BAND_COUNT];
            for (int b = 0; b < NativeSweep.BAND_COUNT; b++) {
                double sumP = 0.0;
                int validCh = 0;
                for (int k = 0; k < channels.length; k++) {
                    ChannelResult cr = result.channels[k];
                    if (cr != null && cr.ok && cr.confident && cr.cleanBandsDb != null) {
                        sumP += Math.pow(10.0, cr.cleanBandsDb[b] * 0.1);
                        validCh++;
                    }
                }
                avgClean16[b] = validCh > 0 ? (float) (10.0 * Math.log10(sumP / validCh)) : -120f;
            }
            NativeSweep.estimateMicCompensation(avgClean16, result.micCompensation16);
            setMicCompensationCurve(context, result.micCompensation16);
            result.micCalibrated = true; // this pass is the calibration

            StringBuilder mcLog = new StringBuilder("estimated & saved mic compensation (16 bands):");
            for (float v : result.micCompensation16) {
                mcLog.append(String.format(Locale.US, " %+.1f", v));
            }
            Log.i(TAG, mcLog.toString());
            AudioSpectrumEngine.getInstance().onMeasuredCurvesChanged();
        } else {
            // Standard cabin Auto-EQ pass: load calibrated microphone compensation curve (Hardware constant, kept intact!)
            // Asked here, next to the load, because this is where "calibrated" and "sixteen zeros"
            // actually differ - not in whatever screen happened to start the run.
            result.micCalibrated = hasMicCompensation(context);
            float[] savedMicComp = getMicCompensationCurve(context);
            System.arraycopy(savedMicComp, 0, result.micCompensation16, 0, NativeSweep.BAND_COUNT);
            StringBuilder mcLog = new StringBuilder("using calibrated mic compensation (16 bands):");
            for (float v : result.micCompensation16) {
                mcLog.append(String.format(Locale.US, " %+.1f", v));
            }
            Log.i(TAG, mcLog.toString());
        }
    }

    /** Steers the sound to one speaker by pushing balance and fader to their extremes. */
    private static void applyRouting(SharedPreferences prefs, String preset, Channel channel) {
        if (delayTest != 0) {
            // The routing was set once before the pass and stays put; what moves is the delay.
            final int steps = delayTestSteps()[channel.ordinal()];
            Log.i(TAG, "--- delay test: " + steps + " steps on the front right ("
                    + (delayTest == 2 ? "surround line, _d1_fr" : "positional line, _d_fr") + ") ---");
            SharedPreferences.Editor e = prefs.edit();
            if (delayTest == 2) {
                // Surround mode is not a second set of sliders on top of the first: the firmware
                // picks one source or the other for the same seven delay registers, on a flag that
                // this frame carries. So the positional line has to be off, or the frame that
                // arrives last decides which numbers the chip sees.
                e.putBoolean(preset + "_d_en", false)
                 .putBoolean(preset + "_d1_en", true)
                 .putInt(preset + "_d1_fr", steps);
            } else {
                e.putBoolean(preset + "_d1_en", false)
                 .putBoolean(preset + "_d_en", true)
                 .putInt(preset + "_d_fr", steps);
            }
            e.apply();
            return;
        }
        if (sameRouting) {
            Log.i(TAG, "--- " + channel.label + ": routing held for the drift test ---");
            return;
        }
        Log.i(TAG, "--- " + channel.label + ": balance=" + channel.leftRight
                + " fader=" + channel.frontRear + " ---");
        SharedPreferences.Editor ed = prefs.edit();
        if (channel == Channel.SUBWOOFER) {
            ed.putInt(preset + "_f_lr", FADER_CENTRE)
              .putInt(preset + "_f_fr", FADER_CENTRE)
              .putInt(preset + "_sub_g", 12)  // Subwoofer active (+6 dB)
              .putInt(preset + "_sub_f", 8)   // 160 Hz LPF
              .putInt(preset + "_bf_f", 11)  // 250 Hz HPF on front (attenuates main door speakers)
              .putInt(preset + "_bf_r", 11); // 250 Hz HPF on rear
        } else {
            ed.putInt(preset + "_f_lr", channel.leftRight)
              .putInt(preset + "_f_fr", channel.frontRear)
              .putInt(preset + "_sub_g", 0)   // Subwoofer muted during door speaker sweeps
              .putInt(preset + "_bf_f", 0)   // Through HPF on front
              .putInt(preset + "_bf_r", 0);  // Through HPF on rear
        }
        ed.apply();
    }

    private static float getSpeakerX(Channel ch) {
        switch (ch) {
            case FRONT_LEFT:
            case REAR_LEFT:
                return -70f;
            case FRONT_RIGHT:
            case REAR_RIGHT:
                return 70f;
            case SUBWOOFER:
            default:
                return 0f;
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
    private static float getSpeakerZ(Channel ch) {
        switch (ch) {
            case SUBWOOFER:
                return SPEAKER_Z_SUB_CM;
            case FRONT_LEFT:
            case FRONT_RIGHT:
            case REAR_LEFT:
            case REAR_RIGHT:
            default:
                return SPEAKER_Z_DOOR_CM;
        }
    }

    private static float getSpeakerY(Channel ch, float distListen) {
        switch (ch) {
            case FRONT_LEFT:
            case FRONT_RIGHT:
                return 15f;
            case REAR_LEFT:
            case REAR_RIGHT:
                return distListen + 95f;
            case SUBWOOFER:
                return distListen + 165f;
            default:
                return 15f;
        }
    }

    /**
     * Turns arrival times into delay settings.
     *
     * The speaker that is furthest away is heard last, so it needs no delay at all; every other
     * speaker is held back until it arrives at the same moment. This is the one result that is
     * exact no matter what the microphone's response is, because it comes entirely from timing.
     */
    private static void computeDelays(Result result, SoundstageMode mode) {
        if (mode == null) mode = SoundstageMode.DRIVER;
        ChannelResult anchor = null;
        for (int i = 0; i < Math.min(4, result.channels.length); i++) {
            ChannelResult c = result.channels[i];
            if (c == null || !c.ok) continue;
            if (anchor == null || c.clarityDb > anchor.clarityDb) anchor = c;
        }
        if (anchor == null) {
            result.error = "no speaker was heard at all - check the volume and that the "
                    + "microphone is not covered";
            Log.w(TAG, result.error);
            return;
        }

        for (int i = 0; i < result.channels.length; i++) {
            ChannelResult c = result.channels[i];
            if (c == null || !c.ok || c == anchor) continue;
            final float apart = Math.abs(c.arrivalMs - anchor.arrivalMs);
            final float maxSpread = (i == Channel.SUBWOOFER.ordinal())
                    ? MAX_PLAUSIBLE_SUB_SPREAD_MS : MAX_PLAUSIBLE_SPREAD_MS;
            if (apart > maxSpread) {
                c.ok = false;
                Log.w(TAG, String.format(Locale.US,
                        "%s: arrived %.1f ms from anchor %s (> %.1f ms) - disqualified phantom/reflection",
                        c.label, apart, anchor.label, maxSpread));
            }
        }

        float latest = Float.NEGATIVE_INFINITY;
        float earliest = Float.POSITIVE_INFINITY;
        int heard = 0;
        int confidentCount = 0;
        for (ChannelResult c : result.channels) {
            if (c == null || !c.ok) continue;
            if (c.confident || c.clarityDb >= MIN_CLARITY_DB) {
                latest = Math.max(latest, c.arrivalMs);
                earliest = Math.min(earliest, c.arrivalMs);
                confidentCount++;
            }
        }
        if (confidentCount == 0) {
            for (ChannelResult c : result.channels) {
                if (c == null || !c.ok) continue;
                latest = Math.max(latest, c.arrivalMs);
                earliest = Math.min(earliest, c.arrivalMs);
            }
        }
        for (ChannelResult c : result.channels) {
            if (c != null && c.ok) heard++;
        }
        if (heard < 2) {
            result.error = "only " + heard + " speaker(s) were heard - nothing to align against";
            Log.w(TAG, result.error);
            return;
        }
        int confident = 0;
        for (ChannelResult c : result.channels) {
            if (c != null && c.ok && c.confident) confident++;
        }
        result.reflectionDominated = confident < heard;
        Log.i(TAG, String.format(Locale.US,
                "%d speakers agree, spread %.2f ms, reference is the %s at %.1f dB clarity; "
                        + "%d of them heard directly; stage mode=%s",
                heard, latest - earliest, anchor.label, anchor.clarityDb, confident, mode));

        if (mode == SoundstageMode.OFF) {
            Arrays.fill(result.suggestedDelayMs, 0f);
            Arrays.fill(result.suggestedDelaySteps, 0);
            result.suggestedSubDelayMs = 0f;
            result.suggestedSubDelaySteps = 0;
            Log.i(TAG, "delays disabled by mode " + mode);
            return;
        }

        // ===================================================================================
        // Cabin Geometry & Ray Tracing: Re-project from microphone to listening position
        // ===================================================================================
        final float distListen = result.listeningDistanceCm > 0 ? (float) result.listeningDistanceCm : (float) DEFAULT_LISTENING_DIST_CM;
        final float speedOfSoundCmMs = 34.3f; // 343 m/s = 34.3 cm/ms

        // 1. Physical microphone position in cabin coordinates. Origin (0,0) is the head unit on
        //    the dash for the plan, and the listener's EAR LINE for height - the scene is built for
        //    a head, not for a microphone, so the number that matters most is zero by construction.
        //
        //    Until 12.09.2026 this threw the owner's answer away: every place except the headrest
        //    collapsed to (0,0), so the dot dragged across the car picture changed the report and
        //    nothing else. It is read properly now - see micSpotLr/micSpotFr and MIC_PLACE_HEIGHT_CM.
        final float micX;
        final float micY;
        final float micZ;
        if (result.micPlace == 9) {
            // Driver headrest: the microphone was put where the ears are, which is the one case
            // where no re-projection is needed at all.
            micX = (result.micSpotLr > 0.2f) ? 35f : -35f;
            micY = distListen;
            micZ = 0f;
        } else {
            // The dot, in centimetres: +1 is against the door, +1 front is the dash, -1 front is
            // the back seat. Clamped, because a saved value from an older build may be anything.
            float lr = Math.max(-1f, Math.min(1f, result.micSpotLr));
            float fr = Math.max(-1f, Math.min(1f, result.micSpotFr));
            micX = lr * CABIN_HALF_WIDTH_CM;
            micY = (1f - fr) * 0.5f * (distListen + 95f);
            micZ = micHeightCm(result.micPlace);
        }

        // 2. Determine target listener listening position (Xt, Yt)
        final float targetX;
        final float targetY;
        if (mode == SoundstageMode.DRIVER) {
            targetX = (result.micSpotLr > 0.2f) ? 35f : -35f;
            targetY = distListen;
        } else if (mode == SoundstageMode.CABIN_CENTER) {
            targetX = 0f;
            targetY = distListen + 40f; // center of cabin between rows
        } else {
            // FRONT_CENTER
            targetX = 0f;
            targetY = distListen; // centered between driver and passenger
        }

        // 3. Re-project each channel arrival to listener position
        float[] projectedArrivalMs = new float[result.channels.length];
        Channel[] allChannels = Channel.values();

        for (int i = 0; i < result.channels.length; i++) {
            ChannelResult c = result.channels[i];
            if (c == null || !c.ok) {
                projectedArrivalMs[i] = Float.NEGATIVE_INFINITY;
                continue;
            }
            Channel ch = allChannels[i];
            float sx = getSpeakerX(ch);
            float sy = getSpeakerY(ch, distListen);
            float sz = getSpeakerZ(ch);

            // Three dimensions, not two. A dome-light microphone is 45 cm above the ears and a door
            // woofer 25 cm below them: in plan those are the same point and the height is the whole
            // of the difference. The ear line is z = 0, so the listener's own z is zero as well.
            double dMic = Math.sqrt((sx - micX) * (sx - micX) + (sy - micY) * (sy - micY)
                    + (sz - micZ) * (sz - micZ));
            double dTarget = Math.sqrt((sx - targetX) * (sx - targetX) + (sy - targetY) * (sy - targetY)
                    + sz * sz);
            float deltaDistCm = (float) (dTarget - dMic);
            float deltaTMs = deltaDistCm / speedOfSoundCmMs;

            // Acoustic Ray Tracing & Subwoofer Phase Alignment:
            // For front speakers: sound travels rearward towards the listener, hitting mic at (0,0) first,
            // then listener ears at (Xt, Yt), so dTarget > dMic (deltaDist > 0, deltaTMs > 0).
            // For subwoofer in trunk: sound travels forward towards the front of the car,
            // passing listener ears FIRST, then traveling another distListen cm to reach the dash mic!
            // Therefore: deltaDist = dTarget - dMic ≈ -distListen cm, deltaTMs ≈ -(distListen / 34.3) ms.
            // Arrival at listener ears = Arrival at mic - (Distance from mic to ears / speed of sound).
            projectedArrivalMs[i] = c.arrivalMs + deltaTMs;
            Log.i(TAG, String.format(Locale.US,
                    "Ray Tracing [%s]: measured=%.2f ms, dMic=%.1f cm, dTarget=%.1f cm, delta=%.1f cm (%+.2f ms) -> listener arrival=%.2f ms",
                    c.label, c.arrivalMs, dMic, dTarget, deltaDistCm, deltaTMs, projectedArrivalMs[i]));
        }

        // 4. Calculate delays according to soundstage mode
        if (mode == SoundstageMode.FRONT_CENTER) {
            // Front Left and Front Right are aligned with each other at the center front line
            int flIdx = Channel.FRONT_LEFT.ordinal();
            int frIdx = Channel.FRONT_RIGHT.ordinal();
            ChannelResult flCr = (result.channels.length > flIdx) ? result.channels[flIdx] : null;
            ChannelResult frCr = (result.channels.length > frIdx) ? result.channels[frIdx] : null;

            float flArr = (flCr != null && flCr.ok) ? projectedArrivalMs[flIdx] : Float.NEGATIVE_INFINITY;
            float frArr = (frCr != null && frCr.ok) ? projectedArrivalMs[frIdx] : Float.NEGATIVE_INFINITY;
            float frontLatest = Math.max(flArr, frArr);

            // Find global latest arrival to ensure all speakers (sub, rear) are integrated
            float globalLatest = frontLatest;
            for (int i = 0; i < result.channels.length; i++) {
                if (result.channels[i] != null && result.channels[i].ok) {
                    globalLatest = Math.max(globalLatest, projectedArrivalMs[i]);
                }
            }

            // Front speakers delay (if sub or rear is later, front waits for it; and FL/FR align together)
            float frontBaseDelay = Math.max(0f, globalLatest - frontLatest);
            if (flCr != null && flCr.ok) {
                result.suggestedDelayMs[flIdx] = frontBaseDelay + Math.max(0f, frontLatest - flArr);
                result.suggestedDelaySteps[flIdx] = Math.min(MAX_DELAY_STEPS, Math.round(result.suggestedDelayMs[flIdx] / DELAY_STEP_MS));
            } else {
                result.suggestedDelayMs[flIdx] = 0f;
                result.suggestedDelaySteps[flIdx] = 0;
            }

            if (frCr != null && frCr.ok) {
                result.suggestedDelayMs[frIdx] = frontBaseDelay + Math.max(0f, frontLatest - frArr);
                result.suggestedDelaySteps[frIdx] = Math.min(MAX_DELAY_STEPS, Math.round(result.suggestedDelayMs[frIdx] / DELAY_STEP_MS));
            } else {
                result.suggestedDelayMs[frIdx] = 0f;
                result.suggestedDelaySteps[frIdx] = 0;
            }

            // Rear speakers: provide ambient Haas fill (arrive ~5 ms after direct front sound at listener)
            for (Channel ch : new Channel[]{Channel.REAR_LEFT, Channel.REAR_RIGHT}) {
                int idx = ch.ordinal();
                ChannelResult rCr = (result.channels.length > idx) ? result.channels[idx] : null;
                if (rCr != null && rCr.ok) {
                    float rearArr = projectedArrivalMs[idx];
                    // Desired rear arrival at listener is at least frontLatest + 5.0 ms + frontBaseDelay
                    float neededDelay = (frontLatest + 5.0f) - rearArr + frontBaseDelay;
                    result.suggestedDelayMs[idx] = Math.max(0f, neededDelay);
                    result.suggestedDelaySteps[idx] = Math.min(MAX_DELAY_STEPS, Math.round(result.suggestedDelayMs[idx] / DELAY_STEP_MS));
                } else {
                    result.suggestedDelayMs[idx] = 0f;
                    result.suggestedDelaySteps[idx] = 0;
                }
            }

            // Subwoofer: align with front stage
            int subIdx = Channel.SUBWOOFER.ordinal();
            if (result.hasSubwoofer && result.channels.length > subIdx) {
                ChannelResult subCr = result.channels[subIdx];
                if (subCr != null && subCr.ok) {
                    float subArr = projectedArrivalMs[subIdx];
                    float subDelay = Math.max(0f, globalLatest - subArr);
                    result.suggestedSubDelayMs = subDelay;
                    result.suggestedSubDelaySteps = Math.min(MAX_DELAY_STEPS, Math.round(subDelay / DELAY_STEP_MS));
                } else {
                    result.suggestedSubDelayMs = 0f;
                    result.suggestedSubDelaySteps = 0;
                }
            } else {
                result.suggestedSubDelayMs = 0f;
                result.suggestedSubDelaySteps = 0;
            }

        } else {
            // DRIVER or CABIN_CENTER:
            // Find speaker heard latest at the target position; every other speaker waits for it
            float latestAtTarget = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < result.channels.length; i++) {
                ChannelResult c = result.channels[i];
                if (c != null && c.ok) {
                    latestAtTarget = Math.max(latestAtTarget, projectedArrivalMs[i]);
                }
            }

            for (int i = 0; i < Math.min(4, result.channels.length); i++) {
                ChannelResult c = result.channels[i];
                if (c == null || !c.ok) {
                    result.suggestedDelayMs[i] = 0f;
                    result.suggestedDelaySteps[i] = 0;
                    continue;
                }
                result.suggestedDelayMs[i] = Math.max(0f, latestAtTarget - projectedArrivalMs[i]);
                final int wanted = Math.round(result.suggestedDelayMs[i] / DELAY_STEP_MS);
                result.suggestedDelaySteps[i] = Math.min(wanted, MAX_DELAY_STEPS);
                if (wanted > MAX_DELAY_STEPS) {
                    result.beyondHardware = true;
                    Log.w(TAG, String.format(Locale.US,
                            "%s needs %.1f ms (%d steps) but hardware delay line stops at %d steps",
                            c.label, result.suggestedDelayMs[i], wanted, MAX_DELAY_STEPS));
                }
            }

            // Subwoofer
            int subIdx = Channel.SUBWOOFER.ordinal();
            if (result.hasSubwoofer && result.channels.length > subIdx) {
                ChannelResult subCr = result.channels[subIdx];
                if (subCr != null && subCr.ok) {
                    result.suggestedSubDelayMs = Math.max(0f, latestAtTarget - projectedArrivalMs[subIdx]);
                    final int subWanted = Math.round(result.suggestedSubDelayMs / DELAY_STEP_MS);
                    result.suggestedSubDelaySteps = Math.min(subWanted, MAX_DELAY_STEPS);
                    Log.i(TAG, String.format(Locale.US,
                            "Subwoofer arrival %.2f ms -> suggested delay %.1f ms (%d steps)",
                            subCr.arrivalMs, result.suggestedSubDelayMs, result.suggestedSubDelaySteps));
                } else {
                    result.suggestedSubDelayMs = 0f;
                    result.suggestedSubDelaySteps = 0;
                }
            } else {
                result.suggestedSubDelayMs = 0f;
                result.suggestedSubDelaySteps = 0;
            }
        }
    }

    private static void analyzeAcousticsAndSynthesize(Context context, Result result) {
        // 1. Check wiring polarity
        int positive = 0, negative = 0;
        StringBuilder inverted = new StringBuilder();
        for (int i = 0; i < Math.min(4, result.channels.length); i++) {
            ChannelResult c = result.channels[i];
            if (c == null || !c.ok || !c.confident) continue;
            if (c.polarity < 0) {
                negative++;
                if (inverted.length() > 0) inverted.append(", ");
                inverted.append(c.label);
            } else {
                positive++;
            }
        }
        if (positive > 0 && negative > 0) {
            result.hasPolarityInversion = true;
            result.wiringWarning = "На динаміку (" + inverted + ") виявлено переплутану полярність (+/-)! Динамік підключений у протифазі та гасить баси в салоні. Рекомендуємо перевірити дроти на клемах акустики.";
            Log.w(TAG, "POLARITY WARNING: " + result.wiringWarning);
        }

        // 2. Average clean spectrum of confident channels (Front Left & Front Right prioritized),
        //    and the signal-to-noise ratio alongside it, averaged the same way. The ratio decides
        //    how far the synthesis is allowed to trust each band: it was measured all along and
        //    nothing read it, so every band was corrected as confidently as the best one.
        float[] avgClean = new float[NativeSweep.BAND_COUNT];
        float[] avgSnr = new float[NativeSweep.BAND_COUNT];
        int usedCount = 0;
        for (int chIdx : new int[]{Channel.FRONT_LEFT.ordinal(), Channel.FRONT_RIGHT.ordinal(),
                                   Channel.REAR_LEFT.ordinal(), Channel.REAR_RIGHT.ordinal()}) {
            ChannelResult c = result.channels[chIdx];
            if (c != null && c.ok && c.confident) {
                for (int b = 0; b < NativeSweep.BAND_COUNT; b++) {
                    avgClean[b] += c.cleanBandsDb[b];
                    avgSnr[b] += c.snrDb[b];
                }
                usedCount++;
            }
        }
        if (usedCount > 0) {
            for (int b = 0; b < NativeSweep.BAND_COUNT; b++) {
                avgClean[b] /= usedCount;
                avgSnr[b] /= usedCount;
            }
        }
        System.arraycopy(avgSnr, 0, result.avgSnrDb16, 0, NativeSweep.BAND_COUNT);


        // 3. Detect midbass roll-off HPF cutoff index
        if (result.hasSubwoofer) {
            result.midbassHpfIdx = NativeSweep.detectMidbassRollOff(avgClean);
            if (result.midbassHpfIdx >= 0 && result.midbassHpfIdx < BASS_FILTER_FREQS_HZ.length) {
                result.midbassHpfFreqHz = BASS_FILTER_FREQS_HZ[result.midbassHpfIdx];
            } else {
                result.midbassHpfIdx = 5;
                result.midbassHpfFreqHz = 63;
            }
        } else {
            // No subwoofer installed: keep door speakers full-range (Through / 20 Hz, idx 0)
            result.midbassHpfIdx = 0;
            result.midbassHpfFreqHz = 0; // Displayed as Through (0 Hz / 20 Hz)
        }

        // 4. Synthesize 16-band Auto-EQ & Sub settings matching chosen TargetCurve
        int[] subSettings = new int[2];
        NativeSweep.synthesizeAutoEq16(avgClean, result.micCompensation16, avgSnr,
                result.midbassHpfIdx, result.hasSubwoofer,
                result.targetCurve != null ? result.targetCurve.id : NativeSweep.TARGET_HARMAN,
                result.autoEqGains16, subSettings);

        result.subLpfIdx = subSettings[0];
        if (result.subLpfIdx >= 0 && result.subLpfIdx < SUB_FREQS_HZ.length) {
            result.subLpfFreqHz = SUB_FREQS_HZ[result.subLpfIdx];
        } else {
            result.subLpfIdx = 4;
            result.subLpfFreqHz = 63;
        }
        result.subGain = subSettings[1];

        // 4-bis. What the car does to the sound, kept for the spectrum analyser.
        //
        // Why it is stored at all: the CALCULATED spectrum reads PCM before the DSP and draws it
        // with the DSP curve applied - it shows what leaves the amplifier. What the loudspeakers and
        // the cabin then do to it was measured right here and went nowhere, so a car with a hole at
        // 80 Hz drew a level bar. Owner, 13.09.2026: "інакше він буде показувати неправду".
        //
        // The quantity is the one the synthesis itself judges: m[b] = avgClean[b] + micComp[b],
        // about refMid over bands 5..8, with the same -70 dB guard - so the picture and the
        // correction cannot disagree about what the car is doing. A shape, not a level.
        //
        // 🔴 And it is crossover-aware, which the first version was not. avgClean averages the four
        // door channels; the subwoofer is deliberately excluded from it, because it plays the same
        // low frequencies from somewhere else in the car and would smear the arrival. But below the
        // midbass high-pass the doors are exactly what does NOT play - the subwoofer does. Storing
        // the doors' own roll-off down there would tell the analyser the car has no bass, on a car
        // that has a subwoofer. So below the crossover the subwoofer's measured response is used
        // instead, against the same midband reference, which is what the listener actually hears.
        //
        // No double counting with the DSP curve: DspResponse.compute is given the sub's frequency
        // and gain indices, so the preset's own contribution is already in the other half of the
        // sum, while what is stored here was measured through the flat scratch preset (sub gain 0).
        float refMidSum = 0f;
        int refMidCount = 0;
        for (int b = 5; b <= 8; b++) {
            float m = avgClean[b] + result.micCompensation16[b];
            if (m > -70f) {
                refMidSum += m;
                refMidCount++;
            }
        }
        final float refMid = refMidCount > 0 ? refMidSum / refMidCount : -20f;

        final ChannelResult sub = result.channels.length > Channel.SUBWOOFER.ordinal()
                ? result.channels[Channel.SUBWOOFER.ordinal()] : null;
        final boolean subUsable = result.hasSubwoofer && sub != null && sub.ok && sub.confident;
        final float crossoverHz = result.midbassHpfFreqHz > 0 ? result.midbassHpfFreqHz : 0f;

        int fromSub = 0;
        for (int b = 0; b < NativeSweep.BAND_COUNT; b++) {
            final boolean belowCrossover = subUsable && crossoverHz > 0f
                    && b < BAND_CENTRES_HZ.length && BAND_CENTRES_HZ[b] < crossoverHz;
            final float measured = belowCrossover ? sub.cleanBandsDb[b] : avgClean[b];
            if (belowCrossover) fromSub++;
            result.cabinResponseDb16[b] = (measured + result.micCompensation16[b]) - refMid;
        }
        Log.i(TAG, String.format(Locale.US,
                "cabin response for the spectrum: %d of %d bands taken from the subwoofer "
                        + "(crossover %.0f Hz, sub usable=%b)",
                fromSub, NativeSweep.BAND_COUNT, crossoverHz, subUsable));
        result.cabinResponseMeasured = true;
        setCabinResponseCurve(context, result.cabinResponseDb16);
        // Pushed into the running analyser now. A preference nobody re-reads is precisely how the
        // flat scratch preset spent months never reaching the chip: the value existed, the wire
        // did not.
        AudioSpectrumEngine.getInstance().onMeasuredCurvesChanged();

        // 5. Two corrections, from two different questions.
        //
        //    Construction first: what surrounds the capsule has a response of its own, and it is
        //    the same response wherever that capsule is fitted. Until 12.09.2026 this was keyed on
        //    the PLACE being "head unit", which meant an identical pinhole anywhere else got
        //    nothing, and an open capsule sitting on the dash got a cavity correction it has no
        //    cavity for. effectiveMicBody() still answers "pinhole" for the head unit's own
        //    microphone, so nothing changes for a measurement already made on this unit.
        final int micBodyEff = effectiveMicBody(result.micBody, result.micPlace);
        if (micBodyEff == MIC_BODY_PINHOLE) {
            // 🧩 A 1.5-2 mm hole in front of the capsule is a Helmholtz cavity: it lifts roughly
            // 2.8-3.2 kHz by +4..+6 dB, so the synthesis reads that lift as the room and cuts it.
            // Give the speech presence back in bands 11 and 12 (2.5 and 4 kHz).
            // These are hardware indices, not decibels: index 6 is flat and one step is 2 dB. The
            // comments here used to name dB figures that did not match the arithmetic - "+2" is two
            // steps, which is 4 dB, and a cap at 8 is +4 dB rather than the +3 it claimed.
            for (int b : new int[]{11, 12}) {
                if (result.autoEqGains16[b] < 6) {
                    result.autoEqGains16[b] = Math.min(6, result.autoEqGains16[b] + 2); // give back up to 4 dB
                }
            }
            // And the same hole rolls the bottom off, which reads as a room that needs bass.
            for (int b = 0; b < 4; b++) {
                if (result.autoEqGains16[b] > 8) {
                    result.autoEqGains16[b] = 8; // cap the boost at index 8 = +4 dB
                }
            }
            Log.i(TAG, "Mic construction: pinhole - cavity lift returned to bands 11-12, sub-bass boost capped");
        } else if (micBodyEff == MIC_BODY_HOUSING) {
            // 🧩 A capsule recessed in a fitting - a dome light, a mirror pod - is shadowed rather
            // than resonant: a broad loss at the top instead of a peak in the middle. Cap the
            // treble boost so the synthesis does not try to correct the housing with the speakers.
            for (int b = 13; b < NativeSweep.BAND_COUNT; b++) {
                if (result.autoEqGains16[b] > 7) {
                    result.autoEqGains16[b] = 7;
                }
            }
            Log.i(TAG, "Mic construction: recessed in a housing - treble boost capped above 8 kHz");
        } else if (micBodyEff == MIC_BODY_LAVALIER) {
            // 🧩 Foam costs a little air and nothing else.
            for (int b = 14; b < NativeSweep.BAND_COUNT; b++) {
                if (result.autoEqGains16[b] > 7) {
                    result.autoEqGains16[b] = 7;
                }
            }
            Log.i(TAG, "Mic construction: foam-covered clip-on - top boost capped");
        }

        //    Placement second, and it is a different matter: glass beside the capsule is a boundary,
        //    not a housing. This one stays keyed on the place, because that is what it is about.
        if (result.micPlace == 0) { // Windscreen
            for (int b = 10; b < NativeSweep.BAND_COUNT; b++) {
                if (result.autoEqGains16[b] > 7) {
                    result.autoEqGains16[b] = 7; // index 7 = +2 dB (the comment here said +1.5)
                }
            }
            Log.i(TAG, "Mic placement: windscreen - boundary reflection limiting applied");
        }

        Log.i(TAG, String.format(Locale.US,
                "Auto-EQ (%s) synthesized: HPF cutoff %d Hz (idx %d), Sub LPF %d Hz (idx %d, gain %d), hasSub=%b",
                result.targetCurve != null ? result.targetCurve.title : "Harman",
                result.midbassHpfFreqHz, result.midbassHpfIdx,
                result.subLpfFreqHz, result.subLpfIdx, result.subGain, result.hasSubwoofer));
    }

    /**
     * Applies the synthesized Auto-EQ preset directly to SharedPreferences and broadcasts to McuService.
     */
    public static void applyAutoEqPreset(Context context, Result result, String presetName) {
        if (context == null || result == null) return;
        if (presetName == null || presetName.trim().isEmpty()) {
            String base = result.targetCurve != null ? result.targetCurve.presetName : "AutoEQ Harman";
            String tag = result.soundstageMode != null ? result.soundstageMode.getTag() : "(Водій)";
            presetName = base + " " + tag;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = prefs.edit();

        // 1. Equalizer 16 bands
        for (int b = 0; b < NativeSweep.BAND_COUNT; b++) {
            e.putInt(presetName + "_g" + b, result.autoEqGains16[b]);
            e.putBoolean(presetName + "_q" + b, false); // Fixed Q=2.2
        }

        // 2. High-pass filter for midbasses
        e.putInt(presetName + "_bf_f", result.midbassHpfIdx);
        e.putInt(presetName + "_bf_r", result.midbassHpfIdx);

        // 3. Subwoofer LPF & Gain
        e.putInt(presetName + "_sub_f", result.hasSubwoofer ? result.subLpfIdx : 5);
        e.putInt(presetName + "_sub_g", result.hasSubwoofer ? result.subGain : 0);
        e.putBoolean(presetName + "_sub_comp", false);

        // 4. Delays & Surround - Enforce mutual exclusivity!
        // BU32107 / AK7604 share the same internal delay RAM registers 0400-040D for both
        // Time Alignment (_d_en / 0x8C) and Surround Expansion (_d1_en / 0x89).
        // They CANNOT run concurrently.
        if (result.targetCurve == TargetCurve.DOLBY_ATMOS) {
            // Dolby Atmos preset uses Surround Expansion (_d1_en = true), so positional delays are disabled (_d_en = false)
            e.putBoolean(presetName + "_d_en", false);
            e.putBoolean(presetName + "_d1_en", true);
            e.putInt(presetName + "_rsse_val", 14); // Rear Space Sound Expander (+4 dB)
            e.putInt(presetName + "_d1_rl", 6);    // ~12.75 ms surround delay rear-left
            e.putInt(presetName + "_d1_rr", 6);    // ~12.75 ms surround delay rear-right
            e.putInt(presetName + "_d1_fl", 0);
            e.putInt(presetName + "_d1_fr", 0);
        } else {
            boolean enableDelays = result.soundstageMode != SoundstageMode.OFF;
            e.putBoolean(presetName + "_d_en", enableDelays);
            e.putBoolean(presetName + "_d1_en", false);
            e.putInt(presetName + "_rsse_val", 10);
            e.putInt(presetName + "_d1_rl", 0);
            e.putInt(presetName + "_d1_rr", 0);
            e.putInt(presetName + "_d1_fl", 0);
            e.putInt(presetName + "_d1_fr", 0);
        }

        e.putInt(presetName + "_d_rl", result.suggestedDelaySteps[Channel.REAR_LEFT.ordinal()]);
        e.putInt(presetName + "_d_rr", result.suggestedDelaySteps[Channel.REAR_RIGHT.ordinal()]);
        e.putInt(presetName + "_d_fl", result.suggestedDelaySteps[Channel.FRONT_LEFT.ordinal()]);
        e.putInt(presetName + "_d_fr", result.suggestedDelaySteps[Channel.FRONT_RIGHT.ordinal()]);
        e.putInt(presetName + "_d_sub", result.hasSubwoofer ? result.suggestedSubDelaySteps : 0);

        // 5. Centered balance
        e.putInt(presetName + "_f_lr", FADER_CENTRE);
        e.putInt(presetName + "_f_fr", FADER_CENTRE);
        e.putBoolean(presetName + "_loud", false);

        // 6. Everything else this preset owns, written explicitly rather than left as it was.
        //
        // Until 13.09.2026 these keys were simply not touched, so an auto-preset was only as
        // determined as its own history: create it once, change loudness by hand, create it again
        // under the same name, and the second one plays differently from the first while claiming to
        // be the same measurement. On this unit the effect was live - the owner's
        // "AutoEQ Harman (Центр)" carried _fm_en=true, _fm_cal=16, _fm_str=100, so McuService was
        // adding the Fletcher-Munson curve on top of the synthesized gains at low volume
        // (updateEqWithFm: (cachedGains-6)*2 + fmOffsets). What was measured and what played were
        // two different curves.
        //
        // Tone compensation is switched OFF here on purpose. This preset is the answer to "what does
        // this car need"; loudness is an answer to "how loud am I listening", and it belongs to the
        // person, not to the measurement. It is one switch away on the Loudness tab.
        e.putBoolean(presetName + "_fm_en", false);
        e.putBoolean(presetName + "_fat_en", false);
        e.putInt(presetName + "_fm_cal", 0);
        e.putInt(presetName + "_fm_str", 0);
        // Bass boost is a second bass control on top of the one just synthesized; two of them
        // fighting is how a preset ends up with a bottom nobody asked for.
        for (String k : new String[]{"_bb_f", "_bb_r", "_bb_frq_f", "_bb_frq_r"}) {
            e.putInt(presetName + k, 0);
        }

        // 6. Add to preset list
        Set<String> presetNames = new HashSet<>(prefs.getStringSet(PREF_PRESET_NAMES, new HashSet<>()));
        presetNames.add(presetName);
        e.putStringSet(PREF_PRESET_NAMES, presetNames);

        // 7. Activate preset
        e.putString(PREF_LAST_SELECTED, presetName);
        e.putString(PREF_LAST_AUTOEQ_PRESET, presetName);
        e.apply();

        // Broadcast to McuService
        Intent intent = new Intent("com.radiorubka.wdsp.PRESET_CHANGED");
        intent.putExtra("preset", presetName);
        context.sendBroadcast(intent);
        Log.i(TAG, "Applied and broadcast Auto-EQ preset: " + presetName);
    }


    // ---------------------------------------------------------------------------------------
    // borrowing and returning the head unit's settings
    // ---------------------------------------------------------------------------------------

    private static void applySaved(SharedPreferences.Editor editor, String saved) {
        for (String pair : saved.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = pair.substring(0, eq);
            String value = pair.substring(eq + 1);
            if ("saved_volume".equals(key)) {
                try {
                    int vol = Integer.parseInt(value);
                    Log.i(TAG, "restoring saved volume from recovery: " + vol);
                    VolumeHelper.setVolume(vol);
                    VolumeHelper.setVolumeForType("media_type", vol);
                } catch (Throwable ignored) {}
            } else {
                editor.putString(key, value);
            }
        }
    }

    /**
     * Puts the sound processor into a state where what is measured is the car, not the settings.
     *
     * The delay lines have to go: they are there to compensate for the very distances being
     * measured, so leaving them on would measure the correction rather than the problem. The
     * equaliser goes flat for the same reason - its curve would otherwise be indistinguishable
     * from the loudspeaker's own response.
     */
    /**
      * Copies the user's preset and neutralises everything that would be measured by mistake.
      *
      * What is switched off, and why each one matters:
      *
      * <ul>
      *   <li><b>delay lines and surround</b> - they exist to compensate for the very distances
      *       being measured, so leaving them on measures the correction instead of the problem;
      *   <li><b>high-pass and bass boost</b> - a high-pass is a real group delay at the bottom of
      *       the range, and it would look exactly like a loudspeaker standing further away;
      *   <li><b>equaliser and loudness</b> - otherwise the preset's curve is measured as though it
      *       were the loudspeaker's own;
      *   <li><b>subwoofer</b> - it plays the same low frequencies as the speaker being measured,
      *       from somewhere else in the car, and smears the arrival;
      *   <li><b>GALA</b> - it changes the volume according to speed, and a volume change during a
      *       sweep would be measured as part of the room.
      * </ul>
      *
      * The power amplifier setting is copied rather than reset: it decides how loud the car is
      * capable of being, and a measurement has no business changing that.
      */
     private static void buildScratchPreset(SharedPreferences prefs, String from) {
         SharedPreferences.Editor e = prefs.edit();

         // The type of every key matters and nothing enforces it: these preferences have no
         // schema, and a value written as the wrong type crashes the service the moment it reads
         // the preset. The gains are numbers; the Q flags are booleans, one bit per band, because
         // the hardware only offers a wide setting and a narrow one.
         for (int b = 0; b < 16; b++) {
             e.putInt(SCRATCH_PRESET + "_g" + b, EQ_FLAT_INDEX);
             e.putBoolean(SCRATCH_PRESET + "_q" + b, false);
         }
         e.putInt(SCRATCH_PRESET + "_f_lr", FADER_CENTRE);
         e.putInt(SCRATCH_PRESET + "_f_fr", FADER_CENTRE);
         e.putBoolean(SCRATCH_PRESET + "_loud", false);

         e.putBoolean(SCRATCH_PRESET + "_d_en", false);
         e.putBoolean(SCRATCH_PRESET + "_d1_en", false);
         for (String ch : new String[]{"fl", "fr", "rl", "rr", "sub"}) {
             e.putInt(SCRATCH_PRESET + "_d_" + ch, 0);
         }
         for (String ch : new String[]{"fl", "fr", "rl", "rr"}) {
             e.putInt(SCRATCH_PRESET + "_d1_" + ch, 0);
         }
         e.putInt(SCRATCH_PRESET + "_rsse_val", 10);

         e.putInt(SCRATCH_PRESET + "_sub_g", 0);
         e.putInt(SCRATCH_PRESET + "_sub_f", 0);
         e.putBoolean(SCRATCH_PRESET + "_sub_comp", false);

         for (String k : new String[]{"_bb_f", "_bb_r", "_bf_f", "_bf_r",
                                      "_bb_frq_f", "_bb_frq_r"}) {
             e.putInt(SCRATCH_PRESET + k, 0);
         }

         e.putBoolean(SCRATCH_PRESET + "_fm_en", false);
         e.putBoolean(SCRATCH_PRESET + "_fat_en", false);
         e.putInt(SCRATCH_PRESET + "_fm_cal", 0);
         e.putInt(SCRATCH_PRESET + "_fm_str", 0);
         e.putBoolean(SCRATCH_PRESET + "_gala_enabled", false);

         // Carried over rather than reset - see the note above.
         e.putInt(SCRATCH_PRESET + "_power_vol", prefs.getInt(from + "_power_vol", 0));

         e.putString("last_selected_preset", SCRATCH_PRESET);
         e.apply();

         // The service reloads on the preset change and then sends every frame; give it room.
         sleep(ROUTING_SETTLE_MS * 2);
     }

    // ---------------------------------------------------------------------------------------
    // audio plumbing
    // ---------------------------------------------------------------------------------------

    /**
     * Opens the microphone for a measurement, choosing the source that the platform leaves alone.
     *
     * 🔴 The source is not a detail here. {@code /vendor/etc/audio_effects.xml} binds echo
     * cancellation and noise suppression to capture sources by name:
     *
     * <pre>
     *   &lt;preprocess&gt;
     *     &lt;stream type="mic"&gt;                 &lt;apply effect="aec"/&gt; &lt;apply effect="ns"/&gt;
     *     &lt;stream type="voice_communication"&gt; &lt;apply effect="aec"/&gt; &lt;apply effect="ns"/&gt;
     *     &lt;stream type="voice_recognition"&gt;   &lt;apply effect="aec"/&gt; &lt;apply effect="ns"/&gt;
     *   &lt;/preprocess&gt;
     * </pre>
     *
     * {@code unprocessed} is absent from that list, and that is the whole reason to use it.
     * Measured on the wire, {@code dumpsys media.audio_flinger} during a capture:
     *
     * <pre>
     *   VOICE_RECOGNITION   Noise Suppression   State 003 (ACTIVE)   Enabled=y
     *   UNPROCESSED         AEC + NS            State 000 (INIT)     Enabled=n
     * </pre>
     *
     * 🪤 Suspending the effects from here does not help, and it is worth knowing why: our
     * {@code NoiseSuppressor.create(session)} hands back our own handle, and disabling it leaves
     * the one the policy attached still running. The app logged "NS was off, now off" while
     * AudioFlinger reported the chain ACTIVE — both true, about different objects.
     *
     * A suppressor adapts to steady content and does so unevenly across the spectrum; a swept
     * sine is steady content by construction. So it removes signal, mostly where the signal is
     * already weak. {@code suspendCapturePreprocessing} is still called by the caller, because on
     * a unit whose policy differs it may be the thing that works.
     *
     * ⚠️ The source changes nothing else: 📻 the capture gain stays at the same {@code
     * VBC ADC0 DG Set} either way — the {@code UnprocessRecord} block in {@code audio_pga.xml},
     * with its {@code 0x18}, is dead and the HAL never applies it. Levels measured 0.2 dB apart.
     */
    private static AudioRecord openMicrophone() {
        int minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) return null;

        AudioRecord record = tryOpen(MediaRecorder.AudioSource.UNPROCESSED, minBytes);
        if (record == null) {
            // Not every unit offers it. Falling back is better than refusing to measure, and the
            // report says which source was used so a result can be read in that light.
            Log.w(TAG, "UNPROCESSED unavailable, falling back to VOICE_RECOGNITION - "
                    + "the platform will attach AEC/NS to this capture");
            record = tryOpen(MediaRecorder.AudioSource.VOICE_RECOGNITION, minBytes);
        }
        return record;
    }

    private static AudioRecord tryOpen(int source, int minBytes) {
        try {
            AudioRecord record = new AudioRecord(source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBytes * 8);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                return null;
            }
            Log.i(TAG, "microphone opened with source " + source
                    + (source == MediaRecorder.AudioSource.UNPROCESSED
                       ? " (UNPROCESSED - no policy preprocessing)" : ""));
            return record;
        } catch (Throwable t) {
            Log.w(TAG, "could not open capture source " + source + ": " + t);
            return null;
        }
    }

    /**
     * Held for the whole measurement, so the platform treats us as the player that owns the sound.
     *
     * <p>Static because the pass is static, and there is only ever one measurement at a time -
     * {@link #isRunning()} guarantees it.
     */
    private static android.media.AudioFocusRequest focusRequest;

    /**
     * What happened to the focus while the sweep ran, or null if nothing did.
     *
     * <p>Worth a line in the report on its own. A measurement that was interrupted by a navigation
     * prompt, a Bluetooth call or the vendor's own chime looks exactly like a measurement of a
     * badly behaved car, and nothing else in the file distinguishes the two.
     */
    private static volatile String focusLostDuringPass;

    /** @return what the platform answered, in words, for the report. */
    private static String requestFocus(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "no AudioManager";
        focusLostDuringPass = null;
        try {
            // 🔴 The listener is not optional decoration. setWillPauseWhenDucked - and
            // setAcceptsDelayedFocusGain with it - make build() throw IllegalStateException
            // unless a listener was set, and the throw is what the first version of this did:
            // every report carried "audio focus: could not ask: java.lang.IllegalStateException:
            // Can't use delayed focus or pause on duck without a listener", the pass ran with no
            // focus at all, and the fix for "the first measurement fails" was never once in
            // effect. Measured on the owner's own unit, 26.08.2026.
            //
            // GAIN rather than one of the transient kinds: a transient grant tells everyone else
            // to duck and come back, and what is wanted here is for this to be the player for the
            // next half minute. Anything that was playing should stop, not lower itself into the
            // measurement.
            AudioManager.OnAudioFocusChangeListener listener = change -> {
                String what;
                switch (change) {
                    case AudioManager.AUDIOFOCUS_LOSS: what = "taken away"; break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: what = "taken briefly"; break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: what = "asked to duck"; break;
                    case AudioManager.AUDIOFOCUS_GAIN: what = null; break;
                    default: what = "change " + change; break;
                }
                if (what != null && isRunning()) focusLostDuringPass = what;
                Log.i(TAG, "audio focus changed during the pass: " + change);
            };
            focusRequest = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(listener,
                            new Handler(Looper.getMainLooper()))
                    .build();
            int answer = am.requestAudioFocus(focusRequest);
            switch (answer) {
                case AudioManager.AUDIOFOCUS_REQUEST_GRANTED: return "granted";
                case AudioManager.AUDIOFOCUS_REQUEST_DELAYED: return "delayed";
                case AudioManager.AUDIOFOCUS_REQUEST_FAILED: return "REFUSED";
                default: return "answer " + answer;
            }
        } catch (Throwable t) {
            focusRequest = null;
            return "could not ask: " + t;
        }
    }

    private static void abandonFocus(Context context) {
        android.media.AudioFocusRequest request = focusRequest;
        focusRequest = null;
        if (request == null) return;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) am.abandonAudioFocusRequest(request);
        } catch (Throwable t) {
            Log.w(TAG, "could not give the focus back", t);
        }
    }

    /**
     * Says out loud when a speaker looks wired backwards - and stays quiet when it cannot tell.
     *
     * <h2>Why this is worth printing and why it is fenced</h2>
     *
     * Polarity is a sign rather than a level, so no microphone can get it wrong - but only if the
     * sign it read belongs to the direct sound. On a channel heard mainly through the cabin the
     * detector locks onto a reflection, and a reflection off glass arrives inverted. Two testers'
     * reports came back with exactly one channel marked -1, and in both cases it was a channel the
     * same report had already called "mostly reflections". Told plainly, that sends somebody under
     * the dashboard looking for wiring nobody crossed.
     *
     * <p>So the comparison is made only among channels heard directly, and only when there are at
     * least two of them to disagree.
     */
    private static String wiringVerdict(Result result) {
        int positive = 0;
        int negative = 0;
        StringBuilder inverted = new StringBuilder();
        for (int i = 0; i < Math.min(4, result.channels.length); i++) {
            ChannelResult c = result.channels[i];
            if (c == null || !c.ok || !c.confident) continue;
            if (c.polarity < 0) {
                negative++;
                if (inverted.length() > 0) inverted.append(", ");
                inverted.append(c.label);
            } else {
                positive++;
            }
        }
        if (positive + negative < 2) {
            return "";
        }
        if (negative == 0) {
            return "WIRING: the main channels heard directly are in phase with each other. "
                    + "The subwoofer is not compared: it plays through a 12 dB/oct low-pass that "
                    + "turns its own phase, and plenty of them are wired inverted on purpose, so "
                    + "its sign here says nothing about anybody's wiring.\n";
        }
        if (positive == 0) {
            // All of them inverted is not a fault in the car: it is one convention against
            // another, somewhere between the amplifier and the measurement, and it sounds the
            // same. Say so rather than send four speakers to be rewired.
            return "WIRING: every main channel heard directly reads inverted. That is a "
                    + "convention, not a fault - all four together sound identical to all four the "
                    + "other way round. Nothing to do.\n";
        }
        return "WIRING: " + inverted + " reads inverted while the others do not - that speaker is "
                + "most likely connected the wrong way round, and it will thin out the bass in "
                + "the middle of the car. Worth checking the two wires at that speaker.\n";
    }

    private static AudioTrack openTrack(int samples) {
        int minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBytes <= 0) return null;
        // Deliberately the media path: the measurement has to travel the same route the music
        // does, through the same mixer and the same outboard processor.
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBytes * 2, samples))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    /** The same signal in both channels; which speaker actually sounds is the DSP's decision. */
    private static short[] toStereoPcm16(float[] mono) {
        short[] out = new short[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            short v = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(mono[i] * Short.MAX_VALUE)));
            out[i * 2] = v;
            out[i * 2 + 1] = v;
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // what the tester sends back
    // ---------------------------------------------------------------------------------------

    /** The folder holding the last measurement, created if it is not there yet. */
    /**
     * Empties the output folder so an archive can only ever describe one measurement.
     *
     * <p>Deliberately not selective about which names it knows: the folder has already collected
     * files written by versions that no longer exist, and a list of names to delete would go stale
     * the same way. Anything here belongs to a measurement that is being replaced.
     */
    private static void clearPreviousRun(Context context) {
        File[] files = outputDir(context).listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            //noinspection ResultOfMethodCallIgnored
            boolean gone = f.delete();
            if (!gone) Log.w(TAG, "could not remove the previous " + f.getName());
        }
    }

    public static File outputDir(Context context) {
        File dir = new File(context.getExternalCacheDir(), OUTPUT_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** True when there is a measurement on disk worth sending. */
    public static boolean hasResult(Context context) {
        File report = new File(outputDir(context), "room_measurement.txt");
        return report.isFile() && report.length() > 0;
    }

    private static String writeReport(Context context, Result result, String preset,
                                      float amplitude, float seconds) {
        File file = new File(outputDir(context), "room_measurement.txt");
        try (FileOutputStream out = new FileOutputStream(file)) {
            StringBuilder sb = new StringBuilder();
            sb.append("wDSP room measurement\n");
            sb.append(HardwareProfile.describe()).append('\n');
            // The machine and the screen. A report is evidence about one particular head unit, and
            // two units with the same MCU code can still be different computers. The screen line
            // carries the system-bar insets as well - the only way to work out where the bar really
            // is on the Tesla-style units, where the overlay currently lands in the wrong place.
            sb.append(HardwareProfile.describeBoard()).append('\n');
            sb.append(HardwareProfile.screenDescription(context)).append('\n');
            if (result.microphone != null) sb.append(result.microphone).append('\n');
            sb.append("microphone placed: ").append(micSpotDescription(context))
                    .append(", on the ").append(micPlaceDescription(context))
                    .append(", built in: ").append(micBodyDescription(context)).append('\n');
            if (result.focus != null) {
                sb.append("audio focus: ").append(result.focus);
                String lost = focusLostDuringPass;
                if (lost != null) sb.append(" - then ").append(lost).append(" DURING the pass");
                sb.append('\n');
            }
            sb.append("preset=").append(preset)
                    .append(" amplitude=").append(amplitude)
                    .append(" sweep=").append(seconds).append(" s")
                    .append(" up to ").append((int) result.sweepTopHz).append(" Hz\n");
            sb.append("soundstage: ").append(result.soundstageMode.title).append('\n');
            sb.append("cabin body: ").append(result.bodyType.title)
                    .append(", listening distance: ").append(result.listeningDistanceCm).append(" cm\n");
            // The screen shows the user "measurement failed" and nothing else. If they send the
            // archive anyway - and they do - the report has to say what went wrong, or the
            // failure has to be diagnosed by reading the recordings, which is what happened the
            // first time somebody sent one.
            if (result.error != null) {
                sb.append("THE APP REPORTED A FAILURE: ").append(result.error).append('\n');
            }
            sb.append('\n');

            for (int i = 0; i < result.channels.length; i++) {
                ChannelResult c = result.channels[i];
                if (c == null) continue;
                // Polarity is a sign rather than a level, so no microphone can get it wrong - but
                // only if the sign it read belongs to the direct sound. On a channel heard mainly
                // through the cabin the detector locks onto a reflection, and a reflection off
                // glass inverts. Two testers' reports came back with exactly one channel marked
                // -1, both of them channels the same report had already called "mostly
                // reflections", and printing that as flatly as a 24 dB one sends people looking
                // for wiring nobody crossed. Say which readings can be trusted.
                String polarity = String.format(Locale.US, "polarity %+d", c.polarity);
                if (c.ok && !c.confident) polarity += "?";
                sb.append(String.format(Locale.US,
                        "%-12s arrival %8.2f ms  clarity %5.1f dB  prominence %8.0f  "
                                + "%-12s peak %6.1f dBFS%s\n",
                        c.label, c.arrivalMs, c.clarityDb, c.prominence, polarity,
                        20 * Math.log10(c.recordedPeak + 1e-9f),
                        !c.ok ? "   NOT HEARD"
                              : c.confident ? "   heard directly"
                                            : "   mostly reflections"));
                float delayMs = (i == Channel.SUBWOOFER.ordinal()) ? result.suggestedSubDelayMs : result.suggestedDelayMs[i];
                int delaySteps = (i == Channel.SUBWOOFER.ordinal()) ? result.suggestedSubDelaySteps : result.suggestedDelaySteps[i];
                sb.append("             suggested delay ")
                        .append(String.format(Locale.US, "%.1f ms (%d steps)",
                                delayMs, delaySteps))
                        .append('\n');
                sb.append("             response dB:");
                for (float band : c.bandsDb) {
                    sb.append(String.format(Locale.US, " %.1f", band));
                }
                sb.append("\n");
                sb.append("             noise dB:   ");
                for (float band : c.noiseBandsDb) {
                    sb.append(String.format(Locale.US, " %.1f", band));
                }
                sb.append("\n");
                sb.append("             clean dB:   ");
                for (float band : c.cleanBandsDb) {
                    sb.append(String.format(Locale.US, " %.1f", band));
                }
                sb.append("\n");
                sb.append("             SNR dB:     ");
                for (float band : c.snrDb) {
                    sb.append(String.format(Locale.US, " %.1f", band));
                }
                sb.append("\n");
                if (c.gccPhatProminence > 0f) {
                    sb.append(String.format(Locale.US,
                            "             GCC-PHAT:   %+.3f ms (%.1f us, prominence %.1f)\n",
                            c.gccPhatDelayMs, c.gccPhatDelayMs * 1000f, c.gccPhatProminence));
                }
                sb.append("\n");
            }
            sb.append("Cabin silence dB:     ");
            for (float band : result.ambientNoiseDb16) {
                sb.append(String.format(Locale.US, " %.1f", band));
            }
            sb.append("\n");
            sb.append(result.noiseFloorChannel.isEmpty()
                    ? "Deconv. noise dB:     "
                    : String.format(Locale.US, "%-22s",
                            "Deconv. noise (" + result.noiseFloorChannel + "):"));
            for (float band : result.noiseFloorDb16) {
                sb.append(String.format(Locale.US, " %.1f", band));
            }
            sb.append("\n");
            sb.append("Mic compensation dB:  ");
            for (float band : result.micCompensation16) {
                sb.append(String.format(Locale.US, " %+.1f", band));
            }
            sb.append("\n");
            if (result.cabinResponseMeasured) {
            sb.append("Cabin response dB:    ");
            for (float band : result.cabinResponseDb16) {
                sb.append(String.format(Locale.US, " %+.1f", band));
            }
            sb.append("\n");
            // What the bottom two octaves are doing, and what they would be doing if the input
            // high-pass were the only thing happening there.
            //
            // Deliberately NOT an estimate of the corner frequency. The measured fall at 20-80 Hz is
            // the product of three things - the microphone input's high-pass, the doors' own
            // roll-off, and the cabin's compression gain pushing the other way - and one sweep
            // cannot separate them. Printing "fc = 96 Hz" would be a fourth invented number. The
            // slope is measured; the two reference figures are what a first-order RC high-pass at
            // the two credible corner frequencies would produce on its own (1.0 uF and 0.47 uF into
            // 2.2 kOhm); the reader compares.
            //
            // A measured slope near the references means the input filter explains the whole fall.
            // Steeper means the doors are rolling off as well. Shallower means the cabin gain is
            // filling it back in - the effect a sealed car has below about 80 Hz.
            final float lowSlope = (result.cabinResponseDb16[3] - result.cabinResponseDb16[0]) / 2f;
            sb.append(String.format(Locale.US,
                    "LF slope 20-80 Hz:     %+.1f dB/oct measured  (an input RC high-pass alone "
                            + "would give %+.1f at fc=72 Hz, %+.1f at fc=154 Hz)\n",
                    lowSlope, firstOrderSlopeDbPerOct(20f, 80f, 72f),
                    firstOrderSlopeDbPerOct(20f, 80f, 154f)));
            }
            sb.append("Avg SNR dB:           ");
            for (float band : result.avgSnrDb16) {
                sb.append(String.format(Locale.US, " %.1f", band));
            }
            sb.append("\n");
            sb.append("EQ trust (0..1):      ");
            for (float band : result.avgSnrDb16) {
                float trust = (band - 6.0f) / 12.0f;
                sb.append(String.format(Locale.US, " %.2f", Math.max(0f, Math.min(1f, trust))));
            }
            sb.append("\n");
            sb.append("Mic calibrated:       ")
              .append(result.micCalibrated
                      ? "yes"
                      : "NO - curve above is zeros, the capsule was taken to be flat and its own "
                        + "colouring was charged to the car")
              .append("\n\n");
            if (result.hasPolarityInversion && result.wiringWarning != null) {
                sb.append("⚠️ УВАГА: ПОЛЯРНІСТЬ ДИНАМІКІВ!\n");
                sb.append(result.wiringWarning).append("\n\n");
            }
            sb.append(wiringVerdict(result));
            sb.append("Soundstage mode: ").append(result.soundstageMode != null ? result.soundstageMode.title : "Default").append("\n");
            sb.append("Target sound curve: ").append(result.targetCurve != null ? result.targetCurve.title : "Harman Reference").append("\n");
            if (result.targetCurve == TargetCurve.DOLBY_ATMOS) {
                sb.append("Dolby Atmos 3D Surround: Active (RSSE +4 dB, Rear Surround Delay 12.7 ms)\n");
            }
            sb.append(String.format(Locale.US, "Midbass HPF: %d Hz (idx %d)\n", result.midbassHpfFreqHz, result.midbassHpfIdx));
            if (result.hasSubwoofer) {
                sb.append(String.format(Locale.US, "Subwoofer: Installed, LPF %d Hz (idx %d), Gain %+d dB, Delay %.1f ms (%d steps)\n",
                        result.subLpfFreqHz, result.subLpfIdx, result.subGain, result.suggestedSubDelayMs, result.suggestedSubDelaySteps));
            } else {
                sb.append("Subwoofer: None (natural roll-off / infrasonic protection)\n");
            }
            // Printed twice on purpose. The line used to say "dB" and print the indices, so a
            // Harman preset read as "+6 +6 +6 +7" where the chip was actually being told
            // "0 0 0 +2" - the hardware grid is 2 dB a step with index 6 meaning flat. Anyone
            // comparing this line against the measured response was comparing two different
            // quantities, and the label was the one lying.
            sb.append("Synthesized Auto-EQ (").append(result.targetCurve != null ? result.targetCurve.title : "Harman").append(" target), dB:");
            for (int g : result.autoEqGains16) {
                sb.append(String.format(Locale.US, " %+d", (g - EQ_FLAT_INDEX) * 2));
            }
            sb.append("\n");
            sb.append("  same as hardware indices (6 = 0 dB, 2 dB a step):");
            for (int g : result.autoEqGains16) {
                sb.append(String.format(Locale.US, " %d", g));
            }
            sb.append("\n\n");
            // From the array rather than from a literal: the two used to be maintained separately,
            // and a report that names the wrong frequency for a band is worse than one that names
            // none - every column in every line above is read against this row.
            sb.append("Band centres:");
            for (float hz : BAND_CENTRES_HZ) {
                sb.append(hz >= 1000f
                        ? String.format(Locale.US, " %.0f", hz)
                        : String.format(Locale.US, " %s", hz == Math.rint(hz)
                                ? String.valueOf((int) hz) : String.valueOf(hz)));
            }
            sb.append(" Hz\n");
            if (result.reflectionDominated) {
                sb.append("NOTE: some speakers were heard mainly through the cabin rather than "
                        + "directly - the clarity figure says which. That is normal with the "
                        + "microphone on the dashboard, where the windscreen sits a hand's width "
                        + "away. Their delays are still much better than none, but they may be "
                        + "short, and their polarity is marked with a question mark because a "
                        + "reflection off glass can arrive inverted - do not go looking for "
                        + "crossed wiring on the strength of one of those. Where the microphone "
                        + "is fitted matters here: please say.\n");
            }
            if (result.beyondHardware) {
                sb.append("NOTE: at least one speaker needs more delay than the hardware can "
                        + "apply. The delay line was measured to run linearly to 40 steps (20 ms, "
                        + "about 6.9 m) and to saturate just above that, so the suggestion is "
                        + "capped there. Please say what vehicle this is.\n");
            }
            sb.append("The response includes the microphone's own curve and is NOT a calibration.\n");
            out.write(sb.toString().getBytes("UTF-8"));
            Log.i(TAG, "report written to " + file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.w(TAG, "the report could not be written", e);
            return null;
        }
    }

    private static void writeWav(Context context, String name, short[] samples, int count) {
        File file = new File(outputDir(context), name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[44]);
            byte[] bytes = new byte[count * 2];
            for (int i = 0; i < count; i++) {
                bytes[i * 2] = (byte) (samples[i] & 0xFF);
                bytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
            }
            out.write(bytes);
        } catch (IOException e) {
            Log.w(TAG, "could not write " + name, e);
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long dataBytes = (long) count * 2;
            byte[] header = new byte[44];
            ascii(header, 0, "RIFF");
            le32(header, 4, (int) (36 + dataBytes));
            ascii(header, 8, "WAVE");
            ascii(header, 12, "fmt ");
            le32(header, 16, 16);
            le16(header, 20, 1);
            le16(header, 22, 1);
            le32(header, 24, SAMPLE_RATE);
            le32(header, 28, SAMPLE_RATE * 2);
            le16(header, 32, 2);
            le16(header, 34, 16);
            ascii(header, 36, "data");
            le32(header, 40, (int) dataBytes);
            raf.seek(0);
            raf.write(header);
        } catch (IOException e) {
            Log.w(TAG, "could not finish the header of " + name, e);
        }
    }

    private static void logResult(Result result) {
        Log.i(TAG, "=== room measurement finished ===");
        if (result.error != null) {
            Log.e(TAG, "error: " + result.error);
            return;
        }
        for (int i = 0; i < result.channels.length; i++) {
            ChannelResult c = result.channels[i];
            if (c == null) continue;
            if (!c.ok) {
                // Never print a delay for a channel that was not heard: a zero here reads as
                // "nothing to correct", which is the opposite of "we do not know".
                Log.i(TAG, String.format(Locale.US,
                        "%-12s NOT MEASURED (prominence %.0f, peak %.1f dBFS)",
                        c.label, c.prominence, 20 * Math.log10(c.recordedPeak + 1e-9f)));
                continue;
            }
            float delayMs = (i == Channel.SUBWOOFER.ordinal()) ? result.suggestedSubDelayMs : result.suggestedDelayMs[i];
            int delaySteps = (i == Channel.SUBWOOFER.ordinal()) ? result.suggestedSubDelaySteps : result.suggestedDelaySteps[i];
            Log.i(TAG, String.format(Locale.US,
                    "%-12s arrival %7.2f ms -> delay %4.1f ms (%d steps)  polarity %+d",
                    c.label, c.arrivalMs, delayMs,
                    delaySteps, c.polarity));
        }
        if (!result.isUsable()) {
            Log.w(TAG, "at least one channel was not heard clearly. Turn the volume up a little, "
                    + "make sure the engine is off and the doors are shut, and check that nothing "
                    + "else is holding the microphone - an assistant hotword will take it and cap "
                    + "it at 16 kHz without saying so.");
        }
        if (result.reportPath != null) Log.i(TAG, "report: " + result.reportPath);
    }

    private static void ascii(byte[] target, int at, String text) {
        for (int i = 0; i < text.length(); i++) target[at + i] = (byte) text.charAt(i);
    }

    private static void le32(byte[] target, int at, int value) {
        for (int i = 0; i < 4; i++) target[at + i] = (byte) ((value >> (8 * i)) & 0xFF);
    }

    private static void le16(byte[] target, int at, int value) {
        for (int i = 0; i < 2; i++) target[at + i] = (byte) ((value >> (8 * i)) & 0xFF);
    }

    private static void closeQuietly(AudioTrack track) {
        if (track == null) return;
        try {
            track.stop();
        } catch (Throwable ignored) {
        }
        try {
            track.release();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(AudioRecord record) {
        if (record == null) return;
        try {
            record.stop();
        } catch (Throwable ignored) {
        }
        try {
            record.release();
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Convenience for the defaults, used by the debug broadcast. */
    public static void measureAsync(Context context, Listener listener) {
        measureAsync(context, DEFAULT_AMPLITUDE, DEFAULT_SECONDS, listener);
    }
}
