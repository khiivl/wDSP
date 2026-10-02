# CABIN_MODEL — what the cabin measurement models, what it reports, and why

**The canonical document for the cabin/microphone model and for everything called "bass
compensation".** Two research documents were digested into it and are kept only as sources — do not
re-read them, read this:

- `research/blind-calibration-reference.md` — "Joint blind calibration of an uncalibrated
  microphone and car acoustics … on Unisoc UIS7862" (the owner's reference, Ukrainian);
- `research/RESEARCH_REQUEST_DYNAMIC_BASS.md` + `research/RESEARCH_RESULT_DYNAMIC_BASS.md`
  (Gemini deep research on level-dependent bass, 14.09, with our check of 15.09).

How the maths got here, attempt by attempt, with the commits: `CALIBRATION_HISTORY.md` (Ukrainian,
decision material for the owner). The measurement pipeline itself (sweep, deconvolution, capture,
field cautions): `ROOM_CALIBRATION.md`.

Status marks: ✅ in code and verified · 🟡 in code, unverified on a car · 📋 approved, not built
(queue: `TODO.md`, first section) · ❌ disproved or rejected.

---

## 1. The goal: a list of defects, and only a coarse correction

The owner, 02.10.2026: *«Q 2.2 незмінний, крок еквалайзера 2 дБ, зріз грубий, немає роздільного
еквалайзера зад/перед і по каналах … досягати нерівномірності 0–1 дБ немає ні змісту, ні можливості.
Максимум — список параметрів, де чітко по пунктах вказати вади акустики: переплюсовки
твітерів/мідбасів, небажані резонанси тощо.»*

So the product of a measurement is, in this order:
1. **a list of defects** — what, where (which speaker, which band), how sure, what the person can do;
2. **a coarse global correction** — only what this hardware can actually change.

What the hardware gives us to correct with (all of it global — one EQ for every channel):

| control | resolution | consequence |
|---|---|---|
| EQ | 16 fixed third-octave bands, **Q 2.2 fixed**, **2 dB steps**, ±12 dB | anything finer than ~±2 dB is arithmetic, not sound; no narrow notch/peak can be treated |
| EQ placement | **before** the sub crossover, the same EQ for front, rear and sub | a band raised for one speaker is raised for all; a weak channel cannot be fixed by EQ |
| door high-pass / sub low-pass | fixed frequency lists, 12 dB/oct | coarse handover only |
| fader / balance | 0..24 | the only per-zone level control (front ↔ rear, left ↔ right) |
| delays | 0.5 ms steps, ≤ 40 steps | time alignment is the one thing measured and applied precisely |
| sub gain | 0..+12 dB, 1 dB steps | — |

Consequences for the model: the microphone curve is needed only to "broad, ±2 dB" — narrow capsule
peaks no longer matter; a defect that the EQ cannot fix is **reported**, not corrected.

## 2. What one measurement is, and what is exact

`measured(f) = speaker(f) + cabin(f) + microphone(f)` in dB, per channel, from an exponential sweep
deconvolved into an impulse response (`ROOM_CALIBRATION.md` §5).

Exact with any microphone, because they do not depend on its response: arrival times (delays), the
sign of a direct arrival (polarity), left ↔ right matching inside a pair (same mic, mirrored path),
narrow resonances below ~200 Hz (Q > 3). Not separable from one fixed microphone without priors:
a broad tilt of the speaker vs of the microphone vs of the cabin. The priors are §4 (physics) and §5
(the microphone's construction).

## 3. The parameters of the model

Everything the person tells us about the car and the microphone. One class owns each group: 📋
`CabinProfile` (the car — new), ✅ `MicProfile` (the microphone, 21.09).

| parameter | values | default | owner |
|---|---|---|---|
| speaker layout | front pair / rear pair / sub — any combination with at least one pair: 2 front, 2 rear, 2 + sub, 4, 4 + sub | 4 + sub | 📋 `CabinProfile` (today only `room_has_subwoofer`) |
| cabin length × width × height (floor to roof), cm | sliders around the car picture | **standard D-class sedan**: ~200 × 145 × 120 (owner 02.10: *«за основу по дефолту береш стандартний седан класу D, решту повзунками людина виставляє під себе»*) | 📋 `CabinProfile` |
| space | closed cabin / open (bench, cabriolet roof down) | closed (a sedan); the owner's bench is set to open | 📋 `CabinProfile` |
| microphone construction | open / pinhole / housing / lavalier | unknown → head-unit place implies pinhole | ✅ `MicProfile.effectiveBody` |
| microphone place + spot | 11 named places, a dot on the car plan | — | ✅ `MicProfile` |
| sub place | boot / parcel shelf / under seat | — | ✅ `RoomMeasurement.subPlace` |
| seating (listening) distance | 40..120 cm, seeded by body type | 75 | ✅ `RoomMeasurement` |

Gap to close with `CabinProfile`: 📋 the stored microphone curve must record the profile it was
measured under — today a changed answer silently keeps the old curve.

## 4. Physics the model uses

Settled physics is prior knowledge, not a hypothesis to re-measure (`AGENTS.md`). Speed of sound
`c = 343 m/s`.

**4.1 Cabin gain (pressure zone).** Below the frequency whose half-wavelength exceeds the longest
cabin dimension, a sealed cabin stops behaving as a room and becomes a pressure vessel: pressure rises
towards low frequencies. Transition `f_t = c / (2·L)` — the reference's `565 / L_feet` is the same
formula in feet. Ideal slope **12 dB/oct below `f_t`**; real cabins leak (panels, glass, vents) and
rarely reach it, and measured roll-on often starts higher, 70–90 Hz. D-sedan default: `L = 2.0 m →
f_t ≈ 86 Hz`.
- **Open space** (bench, cabriolet roof down): no pressure zone, no rise. The owner's bench is
  half-open (cabinet one side, balcony the other) — never anchor against it.
- Use: the expected low-band rise in the microphone estimate (§6) and the line between "bass deficit
  is a defect" and "bass deficit is the open space". 📋 slope 12 dB/oct below `f_t` from the person's
  length, closed only.
- History: fixed 12 → 6 dB/oct below 80 Hz → removed 21.09 (`5ad92ae`) because we never asked
  whether the cabin is closed or how long it is. The parameters of §3 are that question.

**4.2 Axial modes.** Standing waves at `f = n · c / (2·d)` for each dimension `d` (L, W, H). With the
D-sedan default: length 86 / 172 Hz, width 118 / 237 Hz, height 143 / 286 Hz. A narrow peak or dip
below ~300 Hz that sits on one of these is the cabin, not a speaker — report it as "cabin mode, not
treatable", not as a speaker defect. 📋

**4.3 Comb filtering.** A reflection with path difference `d` cancels at `f_null = c / (2·d)` and its
odd harmonics (tweeter 5 cm from glass → 3.4 kHz, 10 kHz, 17 kHz; door speaker off the floor, 15 cm →
1.1 kHz; console, 30 cm → 563 Hz; opposite door, 46 cm → 375 Hz). The notches are deep, narrow and
move when the microphone moves a few centimetres; a capsule resonance does not move. No EQ fills them
— ignore them in the correction (✅ the boost cap), name them in the list only as "reflection, not
treatable". Separating them needs a second pass with the microphone moved ~15 cm (📋 `TODO.md`,
comb filtering).

**4.4 Diffuse-field treble loss.** Every car loses treble — seats, carpet, clothing, a tweeter rolling
off, the angle to the microphone. None of it is the microphone. The owner, 21.09: a front-left that
reads −31 dB at 12.5 kHz on the bench is the angle to a microphone behind the fascia, not a dead
tweeter (he hears no left/right difference).

**4.5 Loudness is hearing, not the speaker.** At low listening levels bass is lost by the ear
(ISO 226), not by the speaker — a door woofer is linear at small excursions. Level-dependent bass is
therefore a loudness curve by volume step (§9), never a measured cabin property.

## 5. The microphone

- **Capsule**: flat ±0.5 dB from ~100 Hz to ~6 kHz (MEMS datasheets: Knowles SPH0645, ST MP34DT01;
  electret WM-61A 40 Hz–4 kHz). There is nothing to estimate in the midband.
- **Input path**: a coupling capacitor 0.47–1.0 µF into ~2.2 kΩ (first-order high-pass 72–154 Hz)
  plus the handsfree DSP's voice high-pass (2nd order, 100–150 Hz). Its worst case is the ceiling
  `pathMaxAttenDb(f)` (`sweep.cpp:547-556`): ~52.8 / 41.1 / 29.3 / 18.0 / 8.9 dB at 20 / 31.5 / 50 / 80 /
  125 Hz. (The "14.5 dB at 80 Hz" in its comment does not match the formula — 📋 fix in the island pass.)
- ❌ **"20–31.5 Hz sinks below the ADC noise (SNR ≤ 0)"** and ❌ **"a MEMS cut at 70–150 Hz makes it
  useless for bass"** — both disproved: the 13.09 report shows 50 dB SNR there and a 20–80 Hz slope of
  +0.4 dB/oct (an RC cut at 72 Hz would give +4.4); the sub at 31.5 Hz is heard with SNR ~20 dB.
- **Construction** (`MicProfile.MOUNTING_DB`, dB **added** to the measurement — a mounting that adds a
  resonance is compensated with a minus):

| body | 80 Hz | 2k | 3.15k | 5k | 8k | 12.5k | 20k |
|---|---|---|---|---|---|---|---|
| open | 0 | 0 | 0 | 0 | 0 | −1.0 | 0 |
| pinhole (1.5–2 mm port, Helmholtz) | +2.0 | −1.5 | −5.0 | −1.0 | +3.5 | +7.0 | +10.0 |
| housing (niche, light grille) | +1.0 | 0 | −1.5 | −1.0 | +3.0 | +6.0 | +8.0 |
| lavalier (foam) | 0 | 0 | 0 | 0 | +1.0 | +2.0 | +3.0 |

  The table is what the construction *can* do; the measurement decides how much of it survives (§6).
  ❌ the blind top `+3.5/+7/+10` applied unconditionally — it cut the top by 4 dB on 20.09 and a
  pink-noise test (14.09) found the real top within +0.9/+1.4/+2.1 dB.
- **Capture — a fight we have largely won, on three fronts** (owner 02.10: *«ми стартуємо раніше
  асистента, і мікрофон встигаємо брати, або використовуємо рут, якщо дали … ми активно боролися за
  мікрофон, навіть на магнітолах без BTA»*):
  1. **First on the input.** Whoever opens the single shared input first sets its rate and effects;
     wDSP holds it from boot without root (`MicrophoneGuard`, `MEASURED_FACTS.md`), so it is up
     before the assistant can pin it at 16 kHz. After a reinstall or force-stop the assistant may
     be first — retake before measuring (memory `mic-input-order-decides-retake-before-measuring`).
  2. **Root, when granted**: the direct path — evict the holder, then check both us and the evicted.
  3. **BitPerfect v5.4.x** (the other account's line): routes normal and recognition capture to the
     unprocessed ALSA device 0 without a DSP lock, so the platform's own `VOICE_RECOGNITION` → AEC/NS
     attachment stops mattering — *if it holds on the wire*, which the contract
     (`C:\APPS_Contacts\wDSP--BitPerfect\`) says to verify, not assume.
  We request `UNPROCESSED` and the report names the source actually obtained
  (`RoomMeasurement.describeCaptureSource`); capture effects on our own session are suspended for
  the pass and restored. The reference's "`VOICE_RECOGNITION` is clean by CDD" does not hold on a
  stock QF unit — that is why the three fronts exist.
- **Level**: MEMS capsules go non-linear at high SPL; the sweep plays at volume 16
  (`MEASURE_VOLUME`) — 📋 a pass at a lower volume is owed (open since 20.09).

## 6. Separating the microphone from the cabin

The reference's core claim, and ours: the system is multiple-input single-output — every speaker
reaches the one microphone, so **the only thing common to every channel is the microphone**. Average
the channels' log spectra and the speaker- and position-dependent parts shrink; subtract the expected
cabin envelope (§4.1 low, §4.4 high) and what is left is the microphone. What we do with it:

| rule | status |
|---|---|
| log (dB) average of every confident channel, none discarded | ✅ 21.09 (`5ad92ae`) |
| expected envelope = the measurement's own smooth part (±2 bands) above the midband | ✅ 21.09 |
| a dip in every channel is the microphone, a dip in one channel is the cabin (best channel keeps common dips, worst keeps common peaks) | ✅ 21.09 |
| below 10 kHz the measurement alone does not speak for the capsule — "common to all channels" also describes a cabin mode at the microphone | ✅ in code; 📋 replace by width + the mode test of §4.2 (narrow common dip off a mode = capsule; on a mode or broad = cabin) |
| bands 0–4: deficit against the midband is charged to the input path up to `pathMaxAttenDb`; a deficit at or beyond that ceiling is **unknown**, not a number | ✅ 21.09 |
| expected low rise = cabin gain of §4.1 | 📋 from the person's length, closed space only (open → as today) |
| "unknown" shown to the person in words, not as a silently flat band | 📋 |
| subspace / cross-relation / SVD methods, CNN / VAE, acoustic SLAM / MDS (reference) | ❌ not used: heavy, noise-sensitive, and our output is 16 coarse bands |

Why the profile came out flat after 21.09: `CALIBRATION_HISTORY.md`, last section.

## 7. Speaker layouts

📋 The person states the layout; the sweep plays only what is fitted, and the channel array keeps its
order (absent = `null` / `ok=false` — results are read back by position). What changes per layout:
- **front-centre / driver soundstage** needs the front pair; without it those modes are unavailable
  (today: a `NaN` delay when both fronts are missing);
- **"common to all channels"** is weaker with one pair (two channels) — the worst-channel envelope
  needs ≥ 2 confident channels;
- **no sub**: door high-pass to Through, a gentler bass shelf (✅ `synthesizeAutoEq16`);
- **stated but not heard** is a defect; **heard but not stated** is a defect (wiring or the answer).

Today (✅): `runOnePass` sweeps 5 channels with a sub and 4 without; a missing speaker is only inferred
(`heardAtAll`: peak ≥ −40 dBFS and median SNR ≥ 10 dB) and `isUsable()` still demands all four.

## 8. The list of defects (the product)

Each entry: what · where (speaker / band) · how sure · what to do. Localised; the English report for
us stays as it is.

| defect | how it is found | status |
|---|---|---|
| a channel wired inverted | sign of the direct arrival, only among confident direct channels, only when some read in phase and some not (all inverted = a convention, not a fault; the sub is never compared — its low-pass turns its phase) | ✅ `judgePolarity` / `wiringVerdict` → 📋 into the list |
| a tweeter inverted against its own woofer | deep narrow dip at the passive crossover (2.5–5 kHz) in one channel and not in its mirror, plus a sign change of the impulse below/above | 📋 (`TODO.md` §5–6) |
| mismatch inside a pair | left vs right per band beyond ~3 dB over ≥ 2 bands: weak/dead tweeter, a woofer quieter by N dB; front vs rear differences are **normal** and are said to be | 📋 |
| resonance | narrow peak (Q > 3) below ~300 Hz; on an axial mode (§4.2) → cabin mode, not treatable; off every mode → panel/door resonance, check fixings | 📋 |
| layout mismatch | stated not heard; heard not stated; arrival order contradicting the geometry → channels swapped | 📋 |
| midbass roll-off | where the doors stop delivering (→ door high-pass) | ✅ `detectMidbassRollOff` |
| sub | polarity / phase at the crossover, delay | 🟡 |
| clipping, channel heard only by reflection, delay beyond the hardware, noise (air-con +12..15 dB in 80 Hz–1.25 kHz) | ✅ in the report; 📋 into the list, noise warning before the sweep (`TODO.md`) |

## 9. The coarse correction

✅ Cut freely, boost at most +3 dB; ignore deep narrow notches; aim at a target curve, not flat;
flatten the DSP before measuring (`ROOM_CALIBRATION.md` §4). With a sub, EQ bands below the door
high-pass stay flat and the sub's shelf comes from the sub gain only. The sub gain per target
(Harman +2, Atmos +3, Bass +5, Vocal 0, Flat 0) is a **constant, not measured** — the owner finds +2
slightly too much (21.09).
📋 A limit on zigzag between neighbouring bands (Q 2.2 cannot make 4 dB steps); front ↔ rear level
difference as a fader trim in the preset (only when the rear pair is really heard).

## 10. "Bass compensation" — four different things

| meaning | what it is | owner / where |
|---|---|---|
| **loudness** (тонкомпенсація) | EQ raised by volume step below the calibration step (ISO 226 shape), applied after the knob stops | ✅ `LoudnessCurve`, `McuService.applyVolumeDependentSettings`; prefs `_fm_en/_fm_cal/_fm_str` |
| **"Компенсація саба"** | the sub gain raised by the loudness offset of the band at the crossover, only with loudness on | ✅ `LoudnessCurve.subOffset/maxSubBoost`; pref `_sub_comp`. Keep as is (owner 15.09: switched on deliberately when bass is short) |
| **low end of the microphone curve** | bands 0–4 of `estimateMicCompensation` (§6) | ✅ / 📋 cabin gain |
| **sub in the auto-EQ** | handover below the door high-pass, sub gain constants (§9) | ✅ `synthesizeAutoEq16` |

## 11. Verdicts — the dynamic-bass research (14.09, checked 15.09)

| claim | verdict | evidence |
|---|---|---|
| low-level bass loss is hearing (ISO 226); small-signal speaker is linear | ✅ physics; the owner's "lazy midbass" not supported | Klippel papers (sources 1–2); sources 3–4 (YouTube, XDA) irrelevant |
| drive loudness by volume step, not program RMS; keep HPF static; apply after the knob stops | ✅ matches the code | `LoudnessCurve`; 500 ms throttle with a trailing write |
| EQ sits before the sub split, so "Sub compensation" doubles the boost | ✅ very likely — register `0206` = 00 after reset (`RESEARCH_BU32107_SUB_SOURCE.md`); acoustic proof owed | kept anyway by the owner's decision (§10) |
| "the chip takes 2 commands a second" | ❌ our own `McuService.THROTTLE_MS = 500` | — |
| "MEMS input cut at 70–150 Hz, useless for bass" | ❌ disproved by measurement | §5 |
| `CAL_STEP = 24` ≈ 80 dBA | ❌ unfounded (SPL never measured) | our calibration step defaults to 25; the curve ends where the cabin was measured |
| linear scaling between steps | ✅ a compromise until the dB per volume step is measured (1 kHz tone, steps 1..32 — owed, needs sound) | — |
| cabin gain +12 dB/oct below 60–80 Hz, level-independent | ✅ physics (§4.1) — not a dynamic effect | — |

## 12. Verdicts — the blind-calibration reference

| reference pipeline / claim | verdict |
|---|---|
| reset the DSP (EQ, delays, crossovers) before measuring | ✅ a scratch preset during the pass |
| record 48 kHz mono with `VOICE_RECOGNITION` or `UNPROCESSED` | ✅ `UNPROCESSED`, plus being first on the input / root / BitPerfect routing (§5); "`VOICE_RECOGNITION` is clean by CDD" ❌ on a stock QF unit |
| exponential sweep 20 Hz–22 kHz through FL, FR, RL, RR | ✅ 20 Hz–20 kHz, sub from 15 Hz as a 5th channel; 📋 the top from a channel probe, not an assumption |
| FFT deconvolution into impulse responses | ✅ native `sweep.cpp` |
| window by an RT60 (MLE) estimate, keep 30–50 ms | ❌ as stated — 50 ms is one period of 20 Hz; 📋 a frequency-dependent window instead (`TODO.md`) |
| GCC-PHAT time differences → DSP delays | ✅ |
| log-average all channels; HPF found as the shortfall from +12 dB/oct at 20–80 Hz | ✅ averaging; 📋 the anchor from the person's length and space (§4.1) |
| sharp high-Q peaks above 10 kHz are the capsule — cut them | ✅ above 10 kHz the measurement may speak for the capsule |
| least-squares IIR synthesis, prefer cuts, ignore comb notches | ✅ in spirit; our "filters" are 16 fixed bands, no free biquads |
| reach the BU32107 over I2C | ❌ on QF it is driven through the MCU (`qf-platform` skill) |
| "AKM" as the DSP | only on units with the optional AK7738/AK7604 hub (`qf-platform`) |
| multi-position averaging separates comb notches from the capsule | ✅ true; impossible for a microphone built into the fascia — 📋 a second pass with an external microphone moved 15 cm |
| MEMS non-linear at high SPL | ✅ plausible; 📋 measure at a lower volume |

## 13. Where it lives in the code

| what | where |
|---|---|
| the pass, channels, envelopes, delays, synthesis call, report | `RoomMeasurement.java` (`measure`, `runOnePass`, `bestChannelEnvelope`, `computeDelays`, `analyzeAcousticsAndSynthesize`, `writeReport`) |
| microphone estimate and synthesis | `cpp/sweep.cpp` (`estimateMicCompensation`, `synthesizeAutoEq16`, `detectMidbassRollOff`, `pathMaxAttenDb`) via `NativeSweep.java` / `wdsp_jni.cpp` |
| the microphone's construction and place | `MicProfile.java` |
| the car (layout, dimensions, space) | 📋 `CabinProfile.java` |
| loudness and sub compensation | `LoudnessCurve.java`, `LoudnessCheck.java`, `McuService.applyVolumeDependentSettings` |
| offline replay of the owner's 20.09 report | `cpp/test_miccal.cpp` (build: `g++ -O2 -std=c++17 -o miccal test_miccal.cpp sweep.cpp analyzer.cpp fft.cpp stitcher.cpp`) |
