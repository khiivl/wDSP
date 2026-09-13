# Research request: level-dependent bass for a car head unit with quantised DSP controls

> Written 14.09.2026 for the owner to commission. Context for a reader with no memory of this
> project: wDSP is an Android app driving the sound processor of Chinese QF/K706 car head units
> through the MCU. Everything below about the hardware was read from firmware, the chip datasheet
> or measured on a unit — provenance is marked where it matters.

---

## 1. What we want to know, in one paragraph

Our loudness compensation raises the bass (and a little treble) as the volume goes down, from a
fixed table scaled linearly with the volume step. The owner's hypothesis — explicitly a hypothesis,
possibly the reverse of reality — is that **door midbass drivers become "lazier" at low frequencies
when driven with less power**, so the bass compensation and the midbass high-pass should both
**track the listening level continuously** rather than follow a table, applied silently at runtime
and never written into the user's preset. We need to know whether that is physically true, what is
perception and what is the driver, and — most importantly — **what the best achievable algorithm is
given the fixed, coarse controls listed in section 3**. A continuous-filter answer we cannot set is
of no use to us.

---

## 2. Competing explanations to separate — please address each, with sources

| # | mechanism | direction at LOW listening level | notes |
|---|---|---|---|
| H1 | **Perception** — equal-loudness contours (ISO 226:2003 / :2023) | ear loses bass (and some treble) | this is what our table already models; is the shape/magnitude right? |
| H2 | **Driver small-signal behaviour** — suspension creep, viscoelastic / hysteretic Kms, stick-slip at very small excursion | could reduce LF output at very low drive | the owner's "lazier at low power" would live here — is it real and how many dB? |
| H3 | **Driver large-signal behaviour** — power compression (voice-coil heating raising Re), Bl(x), Kms(x), Le(x) | LF output compresses at HIGH drive, not low | if this dominates, the picture is reversed: relative bass is lost at loud levels |
| H4 | **Amplifier / supply limits** — head-unit class-AB/D amps at ~13 V, clipping, protection | at high drive | several owners add external amplifiers and DSPs |
| H5 | **Cabin pressure gain** below roughly 60–100 Hz in a sealed car cabin | level-independent | must not be counted as a level effect |

For each: expected magnitude in dB, at 50 / 80 / 125 Hz, per 10 dB change of drive, for a typical
**6.5" (16.5 cm) door midbass** (Fs ≈ 50–80 Hz, Qts ≈ 0.4–0.8, Xmax a few mm) in a door that is a
leaky, poorly damped enclosure, over the range from quiet (~55 dBA at the listener) to loud
(~95 dBA). Say which effects are negligible at car listening levels.

---

## 3. The hardware we have — every control is quantised, and nothing else can be set

### Signal chain (from the register map of the ROHM BU32107; ⚠️ the order below should be verified against the datasheet block diagram)

```
I2S in (stereo, 48 kHz)
  → 16-band parametric EQ        shared by front and rear (mono-linked)
  → P2Bass                       "bass synthesiser" — see below
  → split:  front HPF | rear HPF | subwoofer LPF
  → time alignment delays
  → digital volume → analogue fader → outputs
```

⚠️ If this order is right, **the EQ is upstream of the subwoofer split**, so any EQ boost at
20–80 Hz reaches the subwoofer as well as the doors. That matters for section 4, question 5.

### Controls and their exact steps

| control | values we can send | step | notes |
|---|---|---|---|
| **EQ band centres** | 20, 31.5, 50, 80, 125, 200, 315, 500, 800, 1.25k, 2k, 3.15k, 5k, 8k, 12.5k, 20k Hz | fixed | 16 bands, cannot be moved |
| **EQ gain** | −12 … +12 dB | **2 dB** | 13 values per band |
| **EQ Q** | **2.2, fixed** | — | firmware forces it; a per-band Q switch exists in the protocol but is ignored |
| **Door HPF** (front and rear, separately) | through/off, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250 Hz | fixed list | **2nd order, −12 dB/oct**; order and phase not selectable through the MCU |
| **Subwoofer LPF** | 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250 Hz | fixed list | 2nd order, −12 dB/oct |
| **Subwoofer gain** | **0 … +12 dB** | 1 dB | **no cut below 0 dB**; phase inversion and subsonic HPF exist in the chip but are not reachable |
| **P2Bass** (front and rear) | off, 54, 68, 86, 108, 134, 172, 214 Hz × 0 … +12 dB | 1 dB | described as a "bass synthesiser / harmonic enhancer" — **is it a psychoacoustic (virtual bass) processor, a shelf, or a peaking boost?** This decides whether it can help small drivers at low level |
| **Fader / balance** | 0 … 24 steps, centre 12 | non-linear, ~1.5–2 dB near centre, mute at the ends | |
| **Delays** | 0 … 20 ms per channel | 0.5 ms | |
| **Volume** | **0 … 32 steps**, per audio source | **dB per step UNKNOWN** | managed by the platform framework; one bench run gave the same converter peak at steps 12 and 16 — cause not established |

### Practical limits

- A change is pushed through the MCU; EQ and subwoofer writes are throttled to **2 per second**.
- The chip has "Advanced Switch" soft-transition settings (0.7–5.3 ms and 0.7–23.3 ms), ❓ unknown
  whether the MCU uses them for EQ/filter changes — so **zipper noise / clicks on live changes are a
  real risk** to be designed around.
- No way to know the listener's absolute SPL. The only sensor is an **uncalibrated cabin MEMS
  microphone**: capsule flat within ±0.5 dB from ~100 Hz to ~6 kHz, but an input high-pass in the
  head unit estimated at ~70–150 Hz, so the bottom octaves read low. The app can play test signals
  (sweeps, tones, noise) at any volume step and record them; measured round-trip latency ≈ 53 ms.
- Presets are stored per player; the owner wants any level-dependent behaviour applied **live and
  silently**, never written back into a preset.

---

## 4. Questions

1. **Physics.** Which of H1–H5 dominate at car listening levels, with what magnitude and sign, and
   is the owner's H2 real? If the answer is "the ear, not the driver", say so plainly.
2. **Our current table.** The boost at volume step 1 relative to the calibration point is
   `20 Hz +12, 31.5 +10, 50 +8, 80 +6, 125 +4, 200 +2, 315 +1, 500 +0.5, 800 0, 1.25k +0.5, 2k +1,
   3.15k +2, 5k +3, 8k +4, 12.5k +6, 20k +8 dB`, scaled by `(cal − step) / (cal − 1)`, zero at and
   above the calibration step. Critique it against ISO 226:2023: shape, magnitude, and whether a
   linear-in-steps scaling is defensible when the dB-per-step of the volume control is unknown.
3. **Level tracking.** What should drive the curve — volume step, estimated SPL, programme level
   (signal RMS, which the app can measure), or a combination? What reference level should the
   calibration point represent?
4. **Running HPF.** Is it established practice (automotive dynamic bass, excursion-limiting,
   "dynamic EQ", Bose AudioPilot, Harman/Dirac style) to move the midbass high-pass with level —
   lower when quiet to extend bass, higher when loud to protect excursion? With **our 12-value HPF
   list and 12 dB/oct slopes**, what switching rule makes sense, and at which volume steps?
5. **Subwoofer.** Given the EQ sits before the subwoofer split: does adding the loudness curve's
   bass to the subwoofer gain on top of the EQ boost **double-count** the bass in the overlap? If
   so, how should the boost be divided between EQ bands, subwoofer gain (0…+12 dB only) and the
   subwoofer LPF list, so the door/sub hand-over stays coherent when both move?
6. **Quantisation.** With 2 dB EQ steps and 1 dB sub/P2Bass steps, is a level-tracking curve
   perceptibly better than the table? Where do the steps become audible as the volume knob moves,
   and what hysteresis prevents toggling around a threshold?
7. **Artefacts.** How often, and how, can EQ/HPF/LPF values be changed live without clicks, given a
   2-writes-per-second limit and unknown soft-switching? Is changing a crossover frequency during
   playback audibly worse than changing a gain?
8. **Per-car calibration with what we have.** Can the uncalibrated cabin microphone measure (a) the
   relative dB-per-volume-step of this car, and (b) any level-dependent change in the midbass
   response (H2/H3)? Give a measurement protocol a non-expert can run in a parked car, and state
   what it cannot resolve.

---

## 5. What the answer must look like to be usable

- **Every number expressed in our steps**: EQ band + gain in 2 dB steps, HPF/LPF as a value from
  the lists above, subwoofer and P2Bass gain in whole dB, volume as a step 0…32. No ±1 Hz, no
  arbitrary Q, no filters we do not have.
- A **recommended algorithm in pseudo-code**: `volume step (and optionally measured programme
  level) → 16 EQ offsets, subwoofer gain, subwoofer LPF, door HPF front/rear, P2Bass`, including
  hysteresis and a rate limit.
- **Provenance for every claim**: standard (with clause), peer-reviewed paper, textbook,
  manufacturer application note, or reasoning. Mark anything unverified. Marketing material from
  car-audio vendors counts as a claim, not evidence.
- **What not to do**: if level-tracking is not worth it at this resolution, or if the owner's
  hypothesis is backwards, say so first.

---

## 6. Where to put the result

Save it next to this file as `RESEARCH_RESULT_DYNAMIC_BASS.md`, with the source list, and add a
line to `.agents/INDEX.md`. Nothing from it goes into code before it is checked against the
hardware facts in section 3 and, where possible, measured on a unit in a car — the owner's bench is
not a cabin.
