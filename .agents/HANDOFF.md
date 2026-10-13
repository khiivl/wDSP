# HANDOFF — wDSP slice

**13.10.2026 ~21:30, Claude session "wDSP mod".** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- Pushed: `origin/kostyfmat_mod` = `76b5089` (13.10 ~20:15); only slice/rules commits after it.
- Bench `192.168.1.138:9876`: wDSP at the loudness-by-dB commit (13.10 ~22:10; adb install -r + service by hand): the control sweep runs
  right after the measurement, before «Застосувати» (preset «wDSP Control»); untested on the unit. Owner,
  13.10: *«поточний хардман не погано грає, і затримки ніби правильні»*.
  Sub wired + to − (better with this preset). Bench taken for sweeps (board #1715). The owner rebooted the
  unit at 21:15 after I hung adb with two parallel installs.

## ⏳ Open

1. **Sub phase at the crossover — done and verified on the bench 13.10 21:2x** (`8a5f758`, `7fdc770`,
   resolver gate `SessionResolver.sweep`): + to + → −1, advice, control sweep SUMMED_CANCELLATION
   (+8.4 dB if flipped); + to − → +1, SUMMED_OK. Fact in MEASURED_FACTS "Subwoofer timing". Left: the
   `control_impulses.*` dump stays as a tester diagnostic (owner may drop it); the sub sits + to − now.
2. **Mic calibration:** at the usual volume the input overloaded (treble noise −34 dB); two steps
   quieter it is clean. The 20 Hz correction (+16.6) is suspect — the speakers' roll-off booked to the
   microphone. My rule "below 50 Hz charge nothing to the mic in open space" **awaits the owner**.
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

- The 20 Hz rule for mic calibration (item 2) — yes or no?
- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
- Unproven: during the door sweeps the scratch preset leaves the sub at 0 dB with a 25 Hz low-pass
  rather than off — whether it leaks into the doors' bottom end is unchecked.
