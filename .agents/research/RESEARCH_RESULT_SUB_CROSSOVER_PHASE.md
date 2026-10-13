# Result: subwoofer-to-doors phase at the crossover (digest)

> Source: the owner's research, 13.10.2026 — `D:\Downloads\Узгодження Фази Сабвуфера Та Дверей.md`
> (735 KB; every formula and number is an embedded image — transcribed here). Request:
> `RESEARCH_REQUEST_SUB_CROSSOVER_PHASE.md`.

## ⚠️ The source's wiring labels are swapped

It was ordered against the second draft of the request, whose table had the wiring rows swapped. The
true bench data (owner, 13.10, repeated at 20:11) is: **+ to + → step-2 polarity +1, 80 Hz −11.2 dB;
+ to − → −1, −4.8 dB.** The source pairs the polarity readings with the levels correctly, so its
mechanism stands. Its narrative ("the manual wiring is constructive, the amp adds ~180°") is backwards.
The truth is the textbook case below: no exotic amp lag is needed.

## What holds (theoretical / simulated)

- **BU32107 crossovers are single 2nd-order biquads** with MCU-table coefficients (datasheet). Butterworth
  (Q 0.707) vs LR2 (Q 0.5) is unknown and **does not matter for phase**: a 2nd-order HP and LP sharing fc
  and Q differ by exactly **180° at every frequency** (the shared denominator cancels). With equal
  arrival times, + to + gives a **null at fc**, so one side must be inverted. Q affects only the level
  at fc (−3 dB BW / −6 dB LR2).
- **The measurement state → preset rotation** at 80 Hz is +136.7° (BW) / +126.9° (LR2): the door HPF
  goes from off to 80 Hz, and the sub LPF from 160 to 80 Hz. So the sign step 2 reads (no door HPF, sub
  through 160 Hz) **flips** in the preset. That is our bench exactly: +1 in step 2 → cancellation in the
  preset.
- **Band-limited correlation is ambiguous by construction:** the lobes alternate sign every half period
  (6.25 ms at 80 Hz). Inversion and a half-period shift match at fc but differ in phase slope across
  the octave.

## The fix it prescribes

1. **Project step 2 into the preset's state.** Filter the door IRs with the preset's door HPF biquad.
   Filter the sub IR with `H_LPF,preset(z) / H_LPF,160(z)` (de-embed the measurement's 160 Hz LPF;
   stable, minimum-phase). Then judge phase and delay.
2. **Polarity and delay solver:** maximise the complex summed energy `|H_doors + p·e^{-jωτ}·H_sub|²` over
   the crossover octave **56.6–113.1 Hz**, with a dip penalty, over p ∈ {+1, −1} and τ on the chip's
   **0.5 ms** grid within **±3.0 ms** of the Hilbert-envelope arrival. This removes the half-period skip.
3. **Control sweep:** forecast the combined response as a **complex** sum for **both** polarities, plus
   the energy sum. Criteria from the source (rules of thumb, not validated):
   - `ΔL_synergy(f) = L_combined − L_energy`: PASS > +1.5 dB, WARN −1.5…+0.5 dB, FAIL < −1.5 dB at 80 Hz;
     the worst of 63/80/100 Hz FAIL ≤ −2.0 dB. ⚠️ These were set after seeing our −2.4 / −1.1, so they
     are fitted, not pre-registered — the margin (−2.2 vs −1.1 on the repeat) is too thin to trust.
   - **Counterfactual (the strong one):** the combined response matches the opposite-polarity complex
     forecast within ±1.5 dB and trails the active-polarity forecast by 4–8 dB → cancellation confirmed.
   - Model residual: the complex forecast within ±1.5 dB of the combined response in the overlap = the
     filter model is valid.
   - A room mode is already in each speaker's own sweep. Cancellation is energy present in the
     separate sweeps that vanishes together (ΔL_dom: combined below the louder speaker alone).
4. **The person's rule:** wire + to +, play bass with the final preset, flip the amp's 0/180 switch and
   keep the position that is fuller and measures louder in the crossover band (the source says
   "at least" a number; 4–8 dB between positions on our bench).

## Our verdict

The mechanism matches the bench with no free parameter, so steps 1–2 are prior knowledge, not a
hypothesis. Step 3's energy thresholds are fitted and stay unused; the counterfactual complex forecast
is the criterion, written down before the next test: **the combined 80 Hz band within ±1.5 dB of one
polarity's complex forecast and ≥ 4 dB from the other's names the polarity the sub is in.**
