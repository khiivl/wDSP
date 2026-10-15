# HANDOFF — wDSP slice

**15.10.2026 ~02:40, Claude session "wDSP mod".** Rewritten, never appended; history is git.

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

**Messengers cannot record voice (owner 15.10 ~02:15) — cause not found, not wDSP's microphone.**
📻 Held press: Telegram/WhatsApp get ACTION_DOWN, then ACTION_CANCEL 11–20 ms later → recording
cancelled (Telegram 0.64 s, WhatsApp input closed in 50–100 ms). Mic free (wDSP not recording since
02:03:55). wDSP injects nothing; `TouchWatcher` 1×1 only gets ACTION_OUTSIDE; screensaver not shown then.
Suspects: QFassistant `RokoAccessibilityService` (canPerformGestures=true → system touch filter; no
dispatchGesture in RokoAi code), notificationcenter, wDSP. **Next:** elimination with the owner at the
unit — assistant first (his call: disable service in UI or `am force-stop`), then notifier, then wDSP.
Also found: wDSP crash 02:02:58 (DEBT «Код», first item); board #1810 to BitPerfect (EffectHAL SET_CONFIG).

**Item 7 (measurement volume, `MeasureVolume`; step table first in the wizard; `_fm_cal` = the
person's volume) builds and has never been seen on the unit.**
**Next, on the bench, in this order:** a measurement pass (the report's
`measurement volume:` line says which step and why), the refusal with the fan on, `_fm_cal` in a
fresh auto preset against the owner's usual volume, loudness audible at 10..25. The thresholds are
Argon's, not measured in a cabin — TODO «Ближнє» item 7 lists them.

Also unchecked: mic analyser brought to step 32 by the table (`AudioSpectrumEngine.micVolumeDb`).

Then, in order: **TODO «Ближнє — по порядку»** items 5 (the 20 Hz rule) and 6 wait for the author;
item 4's two ear checks are daytime.

1. **Mic calibration:** the 20 Hz rule (open space: charge nothing at 20/31.5 Hz) **waits for the
   author** (owner asked him 14.10). Do not ask the owner again.
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
