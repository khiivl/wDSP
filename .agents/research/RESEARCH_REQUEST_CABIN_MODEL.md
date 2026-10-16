> 📦 Answered 02.10.2026 — result `RESEARCH_RESULT_CABIN_MODEL.md`, digested into
> [`../CABIN_MODEL.md`](../CABIN_MODEL.md) §14.

# Research request: car-cabin acoustics for a defect-finding auto-EQ with one built-in microphone

**Answer in English. Tables with numbers first, prose second. Every number needs a source and a mark:
measured (in real cars) / simulated / theoretical / rule of thumb.**

## Context (what the system is and is not)

An Android car head unit (Unisoc UIS7862, QF/K706 platform) measures its own cabin and speakers with
the head unit's **built-in, uncalibrated MEMS microphone** (usually behind a 1.5–2 mm pinhole in the
fascia; sometimes on the A-pillar, sun visor, roof light or headliner). Per speaker channel it plays an
exponential sine sweep (20 Hz–20 kHz; subwoofer from 15 Hz), records it and deconvolves an **impulse
response per channel** (48 kHz). From that it has: arrival times (GCC-PHAT), the sign of the direct
arrival, and levels in **16 fixed third-octave bands** (20, 31.5, 50, 80, 125, 200, 315, 500, 800, 1250,
2000, 3150, 5000, 8000, 12500, 20000 Hz) with per-band SNR.

The correction hardware (ROHM BU32107 via a microcontroller) is coarse and **global**:
- one 16-band EQ for all channels together, **Q fixed at 2.2, 2 dB steps**, ±12 dB, placed **before**
  the subwoofer crossover — no per-channel and no front/rear EQ;
- door high-pass and sub low-pass from fixed frequency lists, 12 dB/oct;
- fader and balance (front↔rear, left↔right levels), delays in 0.5 ms steps up to ~20 ms, sub gain
  0..+12 dB.

Therefore the goal is **not** a flat response. The product is (1) a **list of acoustic defects** in
plain language — what, which speaker, which band, how sure, what the owner can do — and (2) only a
coarse global correction. Speaker layouts vary: 2 front only, 2 rear only, either pair + subwoofer,
4 without sub, 4 + sub. The space may be a closed cabin or open (a bench in a room, a cabriolet with
the roof down). The user enters the cabin's **length, width and height (floor to roof)** with sliders;
the default is a standard **D-class sedan**.

## Questions

1. **Cabin dimensions for the defaults.** Typical interior length (dashboard to rear window; for a
   hatchback/estate/SUV/van also to the tailgate), width (door to door at shoulder height) and height
   (floor to headliner) in cm for: D-class sedan (e.g. VW Passat, Toyota Camry, Ford Mondeo, BMW 3),
   C-class hatchback, B-class hatchback, compact SUV, minivan, panel van/minibus. Give ranges and the
   source of each.
2. **Cabin gain in real cars.** Measured onset frequency and slope of the low-frequency pressure rise
   per body type. Is `f_t = c / (2·L_longest)` a usable predictor, and which length counts: does a
   sedan's boot couple through the rear seat / pass-through; does a hatchback's cargo area count? How
   much do leaks, open windows, a sunroof and a soft top reduce the slope? What happens with a
   cabriolet roof down — is there any pressure-zone gain left? Typical total gain at 20–30 Hz in a
   sealed sedan.
3. **Low-frequency modes in a cabin with seats and occupants.** Are axial modes (`n·c / 2d` for length,
   width, height) actually discernible below ~300 Hz, or damped into nothing? Typical first
   longitudinal / lateral / vertical mode frequencies and Q values measured in cars. How, from one
   fixed microphone and several speakers, to tell a cabin mode (present for every speaker at the same
   frequency) from a door-panel or speaker-mounting resonance (present for one speaker or one side).
4. **Door and mounting resonances.** Typical frequencies and Q of OEM door-panel resonances and speaker
   mounting (baffle, adapter ring) problems; what a single-point measurement shows for each.
5. **Tweeter-versus-woofer polarity inversion in a two-way component set.** Typical OEM and
   aftermarket passive crossover frequencies (2–5 kHz?). What the inversion looks like in one channel's
   impulse response and in third-octave bands: depth and width of the crossover notch, phase/sign
   behaviour below vs above the crossover. How to distinguish it from a comb-filter notch caused by a
   windscreen or door reflection (which moves with microphone position, while the crossover notch does
   not), when the microphone cannot be moved. A concrete detection rule that uses the impulse response
   is wanted, with its false-positive risks.
6. **Left/right matching inside a pair.** What level difference per third-octave band between the left
   and right speaker of a pair is normal for a microphone at the driver's seat or at the fascia centre
   (path-length and angle asymmetry), and above what difference it indicates a defect (dead or weak
   tweeter, blocked or damaged woofer, wrong wiring). Separate answers for below 500 Hz, 500 Hz–5 kHz
   and above 5 kHz.
7. **Front versus rear.** Typical level and tonal differences between front doors and rear doors/parcel
   shelf in a sedan at the driver's position, so the report can say what is **normal**.
8. **Target curves for cars.** The Harman in-car target (or similar published targets): exact shape in
   dB at the 16 band centres above, and whether its bass shelf **includes** cabin gain (i.e. is meant to
   be compared with an in-cabin measurement) — and what to target in an open space.

## What makes an answer unusable

- home-studio or living-room acoustics presented as car facts without saying so;
- numbers without a source, or sources that are forum posts / videos without measurements;
- advice that needs per-channel EQ, narrow-Q filters, free biquads, or a movable reference microphone —
  the hardware above cannot do it;
- generic "use a calibrated microphone" — the whole point is that there is none.

## Answer format

1. For each question: a table (quantity · value or range · measured/simulated/theoretical/rule ·
   source), then at most a short paragraph.
2. A final table "**defect → signature in one channel's impulse response and in third-octave bands →
   how to tell it from a cabin effect → confidence**".
3. Sources list with links.
