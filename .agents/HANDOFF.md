# HANDOFF — wDSP slice

**13.10.2026 ~21:10, Claude session "wDSP mod".** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- Pushed: `origin/kostyfmat_mod` = `76b5089` (13.10 ~20:15); only slice/rules commits after it.
- Bench `192.168.1.138:9876`: wDSP **`8a5f758`** (13.10 20:45, adb install -r + service by hand, after the
  other session's reboot). Sub wired + to +. Bench taken for sweeps (board).

## ⏳ Open

1. **Sub phase at the crossover** — the main item. Research answered and digested
   (`research/RESEARCH_RESULT_SUB_CROSSOVER_PHASE.md`): a 2nd-order HP/LP pair is 180° apart, so step 2
   read the sub in the wrong state. **`8a5f758` (on the bench, untested):** the sub is aligned in the
   preset's filter state (`Biquad`, `toPresetState`, ±3 ms of geometry); the control sweep forecasts the
   sum as waves for the sub as is and inverted. Pre-registered: as-is forecast within ±1.5 dB of the
   combined sound, inverted ≥ 4 dB above → SUMMED_CANCELLATION. **Bench test:** + to + → polarity −1
   and SUMMED_CANCELLATION; + to − → +1 and SUMMED_OK — otherwise it is wrong. Old data: DEBT «саб із
   ФНЧ підсилювача на максимумі».
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
7. Software EQ — postponed by the owner 13.10 (TODO «Разом із сесією Дж» item 15).
8. Gemini: translate the new strings into 26 languages — `room_finding_sub_antiphase`,
   `room_finding_summed_*`, `room_stage_control*`, `room_control_failed`, `toast_rta_zero_listening`.

## ❓ For the owner

- The 20 Hz rule for mic calibration (item 2) — yes or no?
- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
- Unproven: during the door sweeps the scratch preset leaves the sub at 0 dB with a 25 Hz low-pass
  rather than off — whether it leaks into the doors' bottom end is unchecked.
