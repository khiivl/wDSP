# The microphone-calibration maths: what was tried, where we turned back, where each piece lives

> Every turn with its commits, so nothing found is reinvented and nothing gained is lost. What holds
> now is the canon, [`CABIN_MODEL.md`](CABIN_MODEL.md); this file is how we got there. The owner's
> decision of 02.10.2026 is the last section.

One subject: the **microphone compensation curve** (`estimateMicCompensation` in `cpp/sweep.cpp`) and
what the synthesis (`synthesizeAutoEq16`) does with it. The formula that makes a sign error expensive:

```
m[b] = avgClean16[b] + micComp16[b]        // the pressure the synthesis believes is real
deltaDb = target − (m[b] − refMid)         // the EQ correction
```

⇒ **An overstated compensation makes the equaliser CUT.** That is how "the microphone is deaf at the
bottom, give it more" turns into bass being cut.

---

## Attempt 1 — "the cabin-gain anchor" (10–12.09)

**Commits:** `8ebfed9` (mic calibration, spectral subtraction, GCC-PHAT in C++, 10.09) → `ec3089d`,
`9d51ba5`, `b66a604`, `202236e`, `ef7e8c6`, `31f9558`, `148e252`, `cb8dae8` (all 10.09) → `c70517b`
(12.09, restore point).

**What it did.** It expected the cabin's low-frequency pressure to **rise** below 80 Hz and charged
every decibel of shortfall to the capsule. The compensation ceiling was derived from **one** stage of
the input filter (0.47 µF into 2.2 kΩ, first order, 154 Hz). (Sources disagree on the slope it used —
6 dB/oct here, +12 dB/oct with a +15 dB cap in the 10.09 roadmap; the code of `c70517b` is the
authority.)

**What came out.** `+16 +16 +16 …` — the three lowest bands **at the clamp**. A saturated estimate is
not a measurement. The synthesis read it as "excess bass in the cabin" and **cut the real bass**.

**Why it broke.** The calibration swept **the doors with the sub silent**, so the doors' natural
roll-off at 20–80 Hz was charged to the microphone. The cause was the **measurement**, not the physics.

---

## Attempt 2 — "physics plus a correct measurement" (13.09)

**Commits, in order:** `7110bb9` → `a31a78d` → `549e2a2`. Also `2edc44a`, `983fb8f`, `cd449c1`,
`fed45ee`, `98be8ad`, `d5eb117`, `555c4b9`, `fff1481`, `77b0e2c`, `624fc66`, `e2a4569` — the report,
sub geometry, holding the microphone.

**Three steps, the middle one carrying the load:**

1. `7110bb9` **"Measure the car instead of the correction"** — removed the cabin-gain expectation,
   zeroed bands 0–2, capped 3–4 at 8 dB. ⚠️ **This is where we turned the wrong way**: it removed the
   physics instead of fixing the measurement.
2. `a31a78d` **"Sweep the subwoofer during microphone calibration, and take the best channel"** — the
   sub plays during calibration, the estimate takes the **maximum over channels**, each normalised to
   its own midband. Without this, step 3 would have been cosmetic: the mean of four doors and one sub
   dilutes the only source able to deliver the bottom.
3. `549e2a2` **"Use the physics we already have"** — the construction matrix became the **basis** of
   the curve; the cabin-gain anchor came back; the ceiling was computed from **both** filter stages
   (1st order 154 Hz + 2nd order 120 Hz ≈ −14.5 dB at 80 Hz against a measured −14.1); refusal by the
   **measured SNR**, not by band number; the **measured top estimate removed** (it took 5 kHz as the
   reference and charged the capsule with everything missing above).

**What came out.** `+29.8 +24.7 +18.7 +16.2 +8.9 0 0 0 0 0 −1.5 −5.0 −1.0 +3.5 +7.0 +10.0`,
**reproduced four passes in a row within 0.3 dB** — including a pass with a mangled microphone,
because the deciding bands do not depend on the top.

**A premise disproved.** The research section claimed that 20–31.5 Hz sinks below the ADC's thermal
noise (SNR ≤ 0 dB). Our report: **50 dB**. The bands were not dead — **they were declared dead**.

---

## Attempt 3 — "trust only what the sweep confirmed" (15 and 21.09) — in code until the 02.10 plan lands

**Commits 15.09:** `1369335` (the impulse window opens before the first arrival — its cut was eating
the doors' deepest bands), `2d5717c` (each band measured over what the sweep excited in it; 20 Hz and
20 kHz stopped reading flat).

**Commits 21.09:** `4d0a77c` (saturation = **unknown**, not "write the ceiling"; the matrix's top only
when confirmed) → `f5aaae3` (one place handles the construction) → `e13f38a` (class `MicProfile`:
construction + placement) → `5ad92ae` (**"the cabin is no longer assumed"** — the cabin-gain anchor
removed a **second** time; the capsule gets only what is **narrow and common** to all channels).

**Why — the reasons are real and cannot simply be dropped:**

- On the 20.09 pass **every** low band sat exactly on its ceiling (`+52.8` against a ceiling of 52.8,
  `+40.5` against 41.1). That is not "the microphone loses 52.8 dB at 20 Hz", it is "the deficit is
  larger than anything the input path can explain". Writing the ceiling down provokes bass cutting
  again.
- **A third witness:** live pink noise on 14.09 (`compare_modes.py`, microphone against calculated),
  after the switch to power averaging, differed at the top by **+0.9 / +1.4 / +2.1 dB**. The 7–10 dB
  deficit the matrix prescribed **is not there**. The method is independent of the sweep — strong
  evidence.
- The owner, 21.09 (paraphrased at the time): *«мікрофон чує бас добре, навіть коли він не в салоні, а
  Harman виходить трохи басовитим — можливо, мікрофон заслуговує трохи більше довіри»*.
- Cabin gain holds only in a **sealed** cabin: the bench is open space, a cabriolet is open, a bus is
  not the sedan of the reference. We did not ask what the person has, so we could not assume it.

---

## Diagnosis: why the profile came out FLAT

Two independent limits met.

**Bottom (bands 0–4), `sweep.cpp` ~690:**
```cpp
if (deficit >= ceiling) { outStatus16[b] = kMicBandUnknown; continue; }  // keeps the matrix value
```
A band whose deficit reaches the ceiling is declared **unknown** and stays at the construction's
number (for pinhole `0 0 0 +2 0`). On passes with a weak bottom this fires on **every** low band.

**Middle and top (bands 5–15), `sweep.cpp` ~820:**
```cpp
const bool capsuleCanSpeakHere = kHwCenters[b] >= 10000.0f;
if (table == 0.0f)                         value = capsuleCanSpeakHere ? measured : 0.0f;
else if (sign(measured) == sign(table))    value = min(|measured|, |table|);
else                                       value = 0.0f;
```
- Where the matrix says `0` (bands 5–9) — **below 10 kHz the measurement may not speak at all** → `0`.
- Where the matrix says something (10–15) — applied **only if the measurement confirms the same sign**,
  and clipped to the **smaller** of the two.
- Not confirmed — `0`.

⇒ Bottom "unknown", middle forbidden, top clipped to zero unless the envelope confirms it. **Flat
profile; the acoustics are not compensated.**

---

## The advice recorded on 28.09 (where to turn back, where to go on)

**Do not roll back wholesale to attempt 2.** Two things in it are proven wrong: writing the ceiling as
a measurement (`+52.8`) and the top lift `+3.5 / +7 / +10`, independently disproved by pink noise.

**Worth bringing back from attempt 2:**
1. **The cabin-gain anchor — by asking, not assuming.** That is the condition written in `5ad92ae`:
   ask the **space** (closed/open, and its length), since the transition ≈ `565 / longest dimension in
   feet`. Body type is a seating question, not whether there is a roof.
2. **"Below 10 kHz the measurement is silent" is too broad.** It killed bands 5–9 for good, though the
   reasoning under it is right (a cabin mode at the microphone is also common to all channels).
   ⇒ Split by **width**, not frequency: a narrow common dip is the capsule, a broad one the cabin.
3. **"Unknown" must be visible.** Today the band silently keeps the matrix value and writes a status
   into the report; the person sees a flat profile and cannot tell a refusal from a result.

**Do not bring back:** the top `+3.5 / +7 / +10` unconfirmed; the ceiling written as a measurement.

---

## The code of those days

```bash
git show 549e2a2:wdsp_app/src/main/cpp/sweep.cpp   # attempt 2, the full curve
git show c70517b:wdsp_app/src/main/cpp/sweep.cpp   # attempt 1, before the turn
git log --date=short --pretty="%h %ad %s" -- wdsp_app/src/main/cpp/sweep.cpp
```

The former `ROOM_CALIBRATION.md` §24…24-quinquies (construction, input path, matrix limits) moved into
`CABIN_MODEL.md` §5–§6 on 02.10; their text is in git (`git show 0889a2d:.agents/ROOM_CALIBRATION.md`).

---

## Decision of 02.10.2026 — the cabin model and the list of defects

The owner, in three messages:
- *«Звести обидва документи про калібрування та компенсацію басу. Врахувати в моделі повний набір
  параметрів: мікрофон / конструктив мікрофона, наявність неповної комплектації … повзунки навколо
  машинки — довжина / ширина / висота салону … Взяти до уваги системи на відкритому стенді /
  кабріолети.»*
- *«Q 2.2 незмінний, роздільна здатність еквалайзера 2 дБ, зріз грубий, немає роздільного
  еквалайзера зад/перед і по каналах … досягати нерівномірності 0–1 дБ немає ні змісту, ні
  можливості. Максимум — список параметрів, де чітко по пунктах вказати вади акустики.»*
- *«За основу по дефолту береш стандартний седан класу D, решту повзунками людина виставляє під себе.»*

What it settles from the advice above:
1. **Cabin gain returns — by answer, not assumption** (advice 1): closed / open space and the cabin's
   length. Default — a closed D-class sedan; the owner's bench — open.
2. **"Below 10 kHz the measurement is silent"** is replaced by a test by width and by the axial modes
   computed from the cabin's dimensions (advice 2).
3. **"Unknown" in words** (advice 3), together with the whole list of defects.
4. **The goal changed**: not a flat curve but a list of defects plus a coarse global correction. The
   microphone curve is needed only to "broad, ±2 dB".

Steps and state — `TODO.md`, first section.
