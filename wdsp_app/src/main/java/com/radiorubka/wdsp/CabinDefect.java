package com.radiorubka.wdsp;

import java.util.Locale;

/**
 * One fault the cabin measurement found: what it is, where, how sure we are, and what to do about it.
 *
 * <h2>Why the list is the answer (owner, 02.10.2026)</h2>
 *
 * "Q 2.2 is fixed, the EQ steps by 2 dB, the crossover is coarse, there is no separate front/rear or
 * per-channel equaliser. Setting the acoustics perfectly, reaching 0-1 dB flatness, makes neither
 * sense nor is it possible. The most there is - a list of parameters that names the acoustic faults
 * point by point." So the measurement's main result is this list; the correction stays coarse and
 * global. Canon: {@code .agents/CABIN_MODEL.md}.
 *
 * <p>Detectors keep deciding what they decide; {@code RoomMeasurement.collectDefects} turns their
 * verdicts into records here, in one place, and the report and the wizard read the records rather
 * than each detector on its own. A record carries the speaker's name resource as well as its English
 * label: the report stays English, the screen speaks the person's language.
 */
public final class CabinDefect {

    /** What kind of fault. Order is the order of the list: the surest and most actionable first. */
    public enum Kind {
        /** The recording reached the converter's rail - everything else from this pass is suspect. */
        CLIPPING,
        /** A speaker the person said the car has stayed silent. */
        DECLARED_NOT_HEARD,
        /** A door nobody declared answered its probe: a speaker plays there. */
        HEARD_NOT_DECLARED,
        /** Two speakers arrive as if each stood in the other's place: swapped wires, or a wrong dot. */
        CHANNELS_SWAPPED,
        /** A main speaker reads inverted while the others do not. */
        POLARITY,
        /** A channel needs more delay than the chip can apply. */
        DELAY_BEYOND_HARDWARE,
        /** Where the doors' midbass rolls off - the preset puts the door high-pass there (a note, not a fault). */
        MIDBASS_ROLLOFF,
        /** A channel heard mainly through the cabin, not directly. */
        REFLECTIONS_ONLY,
        /** The cabin was loud where the correction lives (a blower, the engine): part of it was not trusted. */
        NOISY_CABIN,
    }

    /** How sure the measurement is - said to the person as plainly as the fault itself. */
    public enum Certainty { SURE, LIKELY, POSSIBLE }

    public final Kind kind;
    public final Certainty certainty;
    /** Where, in English for the report: a speaker's label, or "" for the car as a whole. */
    public final String where;
    /** The speaker's name for the screen, 0 when the fault is not one speaker's. */
    public final int whereRes;
    /** The second speaker's name when the fault is between two, else 0. */
    public final int pairRes;
    /** The number that goes with it - samples, Hz, ms - or NaN when none. */
    public final float value;

    public CabinDefect(Kind kind, Certainty certainty, String where, int whereRes, float value) {
        this(kind, certainty, where, whereRes, 0, value);
    }

    public CabinDefect(Kind kind, Certainty certainty, String where, int whereRes, int pairRes, float value) {
        this.kind = kind;
        this.certainty = certainty;
        this.where = where != null ? where : "";
        this.whereRes = whereRes;
        this.pairRes = pairRes;
        this.value = value;
    }

    /** One line for the English report: what, where, how sure, and what to do. */
    public String reportLine() {
        final String at = where.isEmpty() ? "" : where + ": ";
        final String sure = "[" + certainty.name().toLowerCase(Locale.US) + "] ";
        switch (kind) {
            case CLIPPING:
                return sure + String.format(Locale.US, "the recording clipped (%.0f samples on the converter "
                        + "rail) - the loudest channel reads too well at the bottom. Lower the volume and "
                        + "measure again.", value);
            case DECLARED_NOT_HEARD:
                return sure + at + "declared but not heard. Check that the speaker is connected and plays; "
                        + "if the car has none there, untick it in the speaker layout.";
            case HEARD_NOT_DECLARED:
                return sure + at + "heard although not declared - a speaker plays on this output. If the car "
                        + "has one there, tick it in the speaker layout and measure again; if not, find what "
                        + "is wired to it.";
            case CHANNELS_SWAPPED:
                return sure + at + String.format(Locale.US, "the arrival times fit the cabin only with these two "
                        + "swapped (%.2f ms off as wired). If the microphone stood where the dot says, their "
                        + "wires are swapped - at the speakers or in the harness. Fix this first: every other "
                        + "finding names a speaker by the output that drives it.", value);
            case POLARITY:
                return sure + at + "reads inverted while the other main speakers do not - most likely "
                        + "connected the wrong way round. Check the two wires at that speaker.";
            case DELAY_BEYOND_HARDWARE:
                return sure + "a speaker needs more delay than the chip can apply - a long vehicle. The "
                        + "furthest one cannot be fully aligned; the rest is.";
            case MIDBASS_ROLLOFF:
                return sure + String.format(Locale.US, "the doors' midbass rolls off below about %.0f Hz - the "
                        + "preset puts the door high-pass there, and the subwoofer takes over below it.", value);
            case REFLECTIONS_ONLY:
                return sure + at + "heard mainly through the cabin, not directly - its delay and polarity "
                        + "are less certain. Check that nothing blocks it, or move the microphone.";
            case NOISY_CABIN:
                return sure + String.format(Locale.US, "the speakers stood only %.1f dB above the cabin's noise "
                        + "at 80 Hz - 1.25 kHz, so the correction there was applied only in part. An air-con "
                        + "blower or a running engine does this: switch them off and measure again.", value);
            default:
                return sure + at + kind.name();
        }
    }

    /** A note rather than a fault: the screen shows the list calmly when nothing else was found. */
    public boolean isNote() {
        return kind == Kind.MIDBASS_ROLLOFF;
    }

    /** The same finding for the screen, in the person's language: how sure, then what and what to do. */
    public String screenLine(android.content.Context context) {
        final String name = whereRes != 0 ? context.getString(whereRes) : where;
        final String text;
        switch (kind) {
            case CLIPPING:
                text = context.getString(R.string.room_finding_clipping, Math.round(value));
                break;
            case DECLARED_NOT_HEARD:
                text = context.getString(R.string.room_finding_declared_not_heard, name);
                break;
            case HEARD_NOT_DECLARED:
                text = context.getString(R.string.room_finding_heard_not_declared, name);
                break;
            case CHANNELS_SWAPPED:
                text = context.getString(R.string.room_finding_channels_swapped, name,
                        pairRes != 0 ? context.getString(pairRes) : "");
                break;
            case POLARITY:
                text = context.getString(R.string.room_finding_polarity, name);
                break;
            case DELAY_BEYOND_HARDWARE:
                text = context.getString(R.string.room_finding_delay_beyond_hardware);
                break;
            case MIDBASS_ROLLOFF:
                text = context.getString(R.string.room_finding_midbass_rolloff, Math.round(value));
                break;
            case REFLECTIONS_ONLY:
                text = context.getString(R.string.room_finding_reflections_only, name);
                break;
            case NOISY_CABIN:
                text = context.getString(R.string.room_finding_noisy_cabin, value);
                break;
            default:
                text = kind.name();
        }
        final int sureRes = certainty == Certainty.SURE ? R.string.room_certainty_sure
                : certainty == Certainty.LIKELY ? R.string.room_certainty_likely : R.string.room_certainty_possible;
        return context.getString(sureRes) + ": " + text;
    }
}
