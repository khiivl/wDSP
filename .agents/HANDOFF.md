# HANDOFF — wDSP slice

**14.10.2026 ~23:xx, Claude session "wDSP mod".** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- Pushed: `origin/kostyfmat_mod` = everything up to this slice (14.10 ~23:xx), incl. the manuals
  (`docs/manual_{uk,en,es,ru}.html`; uk published: https://claude.ai/artifact/33rbFe8XPCeWQqJ1MbEGLz)
  and the 28-language strings.
- Bench `192.168.1.138:9876`: `a74218d` (progress by stages) — bench checks 14.10 passed except two
  daytime ones by ear (TODO «Ближнє» item 4). Sub wired + to −.
- Argon: review chat 228743237ea71d21 is dead — never address it; current chat 32890fb409d8df41.

## ⏳ Open

**Next, in order: TODO «Ближнє — по порядку»**: item 7 (the measurement volume chosen by the app, the
loudness anchor separated from it — Argon's plan) awaits the owner's go; items 5 (20 Hz) and 6 wait
for the author; item 4's two ear checks are daytime. Done 14.10, unchecked on the unit: the volume table is a wizard stage
(`VolumeSteps.inMeasurement`: every mic calibration, the first measurement without a table;
`volume_steps.txt` in the archive); the mic analyser is brought to step 32 by the table
(`AudioSpectrumEngine.micVolumeDb`) before the AGC, and the mic RTA zero keeps its step. Owner 14.10:
calculated RTA and bars must not depend on the volume; the mic follows the table «щоб плюс мінус
накладатися на цільову» — so no "absolute calc mode".


1. **Sub phase at the crossover — done and verified on the bench 13.10 21:2x** (`8a5f758`, `7fdc770`,
   resolver gate `SessionResolver.sweep`): + to + → −1, advice, control sweep SUMMED_CANCELLATION
   (+8.4 dB if flipped); + to − → +1, SUMMED_OK. Fact in MEASURED_FACTS "Subwoofer timing". Left: the
   `control_impulses.*` dump stays as a tester diagnostic (owner may drop it); the sub sits + to − now.
2. **Mic calibration:** at the usual volume the input overloaded (treble noise −34 dB); two steps
   quieter it is clean. The 20 Hz correction (+16.6) is suspect — the speakers' roll-off booked to the
   microphone. The rule (open space: charge nothing at 20/31.5 Hz) **waits for the author** — the owner
   asked him 14.10 and wants him in on it (TODO «Ближнє» item 5). Do not ask the owner again.
3. **Call takes the platform's level** (`698830f`, #1704 BitPerfect): on the bench, not checked with a
   live call — `setprop sys.call.vol 10` outside a call → call → stays 10.
4. **Source volumes** (`4d1006d`): the owner's live call remains; wake from ACC OFF unchecked (if the
   platform sets 0 → main_volume there, wDSP takes it as the person's change — owner decides).
5. **Event-driven service:** `TESTS.md` closed 13.10; unchecked: sync with the owner's radio, the
   "microphone" mode after `83bf476`, the screensaver normalisation.
6. RTA: two zeros + one-button calibration (`f8e0fb3`, `3abcd22`) not checked on the bench. Delays
   "for the listener" — DEBT «Вимір», first item.
6b. **Loudness by dB, not steps** (13.10, on the bench, not yet heard by the owner): `LoudnessCurve.loudnessRatio`
   reads the measured step table (`VolumeSteps`, MEASURED_FACTS "Volume steps"); the mic level probe
   before sweeps is on the bench too, unverified.
7. Software EQ — postponed by the owner 13.10 (TODO «Разом із сесією Дж» item 15).
8. Gemini: translate the new strings into 26 languages — `room_finding_sub_antiphase`,
   `room_finding_summed_*`, `room_stage_control*`, `room_control_failed`, `toast_rta_zero_listening`.

## ❓ For the owner

- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
- Unproven: during the door sweeps the scratch preset leaves the sub at 0 dB with a 25 Hz low-pass
  rather than off — whether it leaks into the doors' bottom end is unchecked.
