# HANDOFF — wDSP slice

**15.10.2026 ~01:55, Claude session "wDSP mod".** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- Pushed: `origin/kostyfmat_mod` = `91d758c` (15.10 ~01:55, on the owner's order) — `8c122ed` docs: the
  EQ sits AFTER the sub branch (datasheet); `8f4431a` `DspResponse`: no EQ on the sub path. 📻 Owed:
  −10 dB at 31.5 Hz, doors muted, sub level unchanged; ❓ `0102[5:4]`.
- Bench `192.168.1.138:9876`: `8f4431a` (15.10 01:53, adb install -r + service by hand; v0.4.9.8, no
  crash; the calculated spectrum below the crossover not yet looked at). Sub + to −.
- Argon chat: 32890fb409d8df41 (228743237ea71d21 is dead).

## ⏳ Open

**Item 7 (measurement volume, `MeasureVolume`; step table first in the wizard; `_fm_cal` = the
person's volume) builds and has never been seen on the unit.**
**Next, on the bench, in this order:** a measurement pass (the report's
`measurement volume:` line says which step and why), the refusal with the fan on, `_fm_cal` in a
fresh auto preset against the owner's usual volume, loudness audible at 10..25. The thresholds are
Argon's, not measured in a cabin — TODO «Ближнє» item 7 lists them.

Also unchecked on the unit: the mic analyser brought to step 32 by the table
(`AudioSpectrumEngine.micVolumeDb`) before the AGC. Owner 14.10: calculated RTA and bars must not
depend on the volume; the mic follows the table «щоб плюс мінус накладатися на цільову».

Then, in order: **TODO «Ближнє — по порядку»** items 5 (the 20 Hz rule) and 6 wait for the author;
item 4's two ear checks are daytime.

1. **Mic calibration:** at the usual volume the input overloaded (treble noise −34 dB); two steps
   quieter it is clean. The 20 Hz correction (+16.6) is suspect — the speakers' roll-off booked to
   the microphone. The rule (open space: charge nothing at 20/31.5 Hz) **waits for the author** —
   the owner asked him 14.10 and wants him in on it. Do not ask the owner again.
2. **Call takes the platform's level** (`698830f`): bench only — `setprop sys.call.vol 10` outside a
   call → call → stays 10. **Source volumes** (`4d1006d`): the live call remains; wake from ACC OFF
   unchecked (if the platform sets 0 → main_volume, wDSP reads it as the person — owner decides).
3. **Event-driven service:** `TESTS.md` closed 13.10; unchecked: sync with the owner's radio, the
   "microphone" mode after `83bf476`, the screensaver normalisation.
4. RTA: two zeros + one-button calibration (`f8e0fb3`, `3abcd22`) unchecked on the bench. Delays
   "for the listener" — DEBT «Вимір», first item.
5. **Loudness by dB, not steps** (13.10, bench, not yet heard by the owner):
   `LoudnessCurve.loudnessRatio` reads the step table (MEASURED_FACTS "Volume steps").

## ❓ For the owner

- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
- Unproven: during the door sweeps the scratch preset leaves the sub at 0 dB with a 25 Hz low-pass
  rather than off — whether it leaks into the doors' bottom end is unchecked.
