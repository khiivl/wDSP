> ❌ Correction, 15.10.2026 (Claude): wherever this file says the EQ sits before the subwoofer split, it
> is wrong - the EQ comes after the sub branch and has no sub channel (`platform/03-SOUND-PROCESSOR.md`,
> "Hardware signal path"). Kept as written for history.

> 📦 Answered 13.10.2026 — digest `RESEARCH_RESULT_SUB_CROSSOVER_PHASE.md`.

# Research request: subwoofer-to-doors phase at the crossover, from per-speaker sweeps

**Answer in English. Tables and formulas first, prose second. Every claim needs a source and a mark:
datasheet / measured / simulated / theoretical / rule of thumb.**

## Context

An Android car head unit (QF/K706, Unisoc) measures its own cabin with its built-in microphone and
corrects it through a ROHM **BU32107** sound processor driven by a microcontroller. The hardware:
one 16-band EQ for every channel (Q 2.2, 2 dB steps) placed before the crossover; a door high-pass
from a fixed list `{off, …, 80 Hz, …, 250 Hz}`; a subwoofer low-pass from
`{25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250} Hz`; sub gain 0..+12 dB; per-channel delay in
0.5 ms steps. Our code models both filters as "12 dB/oct" (2nd order). Whether they are Butterworth,
Linkwitz-Riley or something else, and what their phase is, **we do not know**. The sub is fed after
the time-alignment block (register `0206[7:6]` = 00).

The car's subwoofer has its own amplifier (analog class-D, op-amp low-pass, bass boost, a 0/180
phase switch). Wired + to +, − to −, as its manual says.

### What the measurement does

1. Every speaker gets its own exponential sweep inside one recording. A temporary flat preset is used:
   - **door passes:** EQ flat, door high-pass **off**, fader/balance at the extreme for that door;
   - **subwoofer pass:** sub low-pass **160 Hz**, sub gain **+12 dB**, door high-pass **250 Hz**.
2. Each pass is deconvolved into an impulse response. Sub-to-door alignment: both impulses go through
   the same octave-wide band-pass at the crossover the measurement chose (80 Hz). The lag of their
   cross-correlation peak nearest to the tape-measured geometry gives the delay, and its sign gives
   the sub's "polarity".
3. The final preset is applied: door high-pass **80 Hz**, sub low-pass **80 Hz**, EQ, delays.
4. **Control sweep:** one sweep through every speaker at once, with that preset. The forecast is each
   speaker's own response, moved onto the preset's paths (our filter model), and summed as energy.

### What we saw (13.10.2026, one bench, open room, not a car)

| sub wiring | polarity read in step 2 | combined, 80 Hz band | combined, 100 Hz third | combined − energy forecast, worst of 63/80/100 Hz |
|---|---|---|---|---|
| as the manual says (+ to +) | +1 | −11.2 dB | −6.9 dB | −2.4 dB |
| inverted (+ to −) | −1 | **−4.8 dB** | **−1.1 dB** | −1.1 dB |

Above 800 Hz the energy forecast matched the combined sweep within ±0.7 dB, so the levels and the
EQ model hold. Step 2 reads the wiring correctly (+1 as the manual says, −1 inverted), and the report tells the person
with inverted wiring to flip it back. Yet the inverted wiring plays ~6 dB louder at the crossover
with the preset applied: the advice brings them to the worse of the two. Our hypothesis: step 2 reads the phase in the temporary state (doors without
a high-pass, sub through 160 Hz), and the preset's 80 Hz high-pass and low-pass add a relative
rotation of about 180°. It is untested.

## Questions

1. **BU32107 filters.** The type, order, Q and phase response of the door high-pass and the sub
   low-pass (datasheet, application notes, register descriptions). Is the pair a Linkwitz-Riley
   alignment that needs one side inverted, or something else? Group delay at the crossover.
2. **Predicting the sum from separate sweeps.** The standard method for forecasting sub + mains under
   the target crossover from impulse responses measured under different filters: apply the target
   filters digitally to the measured IRs, add the delays, sum them complex. What REW's alignment tool,
   Dirac, Audyssey or car DSP tuning software do. Which filter model is good enough, and what breaks
   when it is wrong.
3. **Choosing polarity and delay.** Given those filtered IRs, how to pick the sub's polarity and delay:
   phase match at fc, maximum summed energy over the overlap band, or something else. How to avoid
   the half-period ambiguity of a band-limited correlation, which we hit: an inverted sub reads the
   same as one moved by half a period.
4. **Wiring vs acoustic phase.** With correct wiring (+ to +), when and why does a car subwoofer need
   its phase switch at 180°: the order of the crossover, the amplifier's own low-pass, the box and
   its distance. A practical rule an end user can follow.
5. **A criterion from the control sweep.** How to detect cancellation at the crossover from the
   combined sweep against a forecast. Should the forecast be the complex sum from question 2? Which
   thresholds are used in practice, and how deep a dip counts as cancellation and not room ripple?
   We want to write the number down before the next test.

## What the answer should let us do

Fix step 2 so that the polarity and delay are judged in the state the person listens to. Give the
control sweep a criterion that tells the two rows of the table apart. And tell the person in one
sentence whether to flip the switch.
