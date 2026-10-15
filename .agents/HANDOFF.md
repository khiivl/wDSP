# HANDOFF — wDSP slice

**15.10.2026 day, Claude session "wDSP mod".** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- Pushed (15.10, owner's order): `origin/kostyfmat_mod` = HEAD. Latest code: `a374aa7` (Bluetooth
  without BitPerfect: the fact reads `isMusicActive()`, the sweep walks the first 64 sessions -
  gocsdk_zj's native track sits on 41), `a46c425` (the spectrum watchdog asks
  `SessionResolver.mediaTrackStarted`; the MCU send log says sent/unchanged/REFUSED). If adb gives 10013, ClearVPN
  (WireGuard) is on and blocks the LAN - the owner turns it off. 📻 Owed: sub −10 dB at 31.5 Hz
  test; ❓ `0102[5:4]`.
- Bench `192.168.1.138:9876`: `a374aa7`; BitPerfect v5.5.15 re-enabled and reinstalled from the same
  zip (owner, 15.10 17:50), rebooted: bus `WD_24BIT`, AGDSP blobs rebuilt 17:51. Without BitPerfect
  (15.10 17:20) the resolver found Bluetooth on session 41 and followed YouTube Music to 65. Sub + to −.
- Board #1813 → BitPerfect line: a disabled module leaves `car.config music_volume=15` (distorts on
  the factory path) and its `/data` AGDSP config; owner: «будемо шукати вирішення з нею».
- Argon chat: 32890fb409d8df41 (228743237ea71d21 is dead).

## ⏳ Open

Messengers record again (owner 15.10 16:20) - the touch cancel was QFassistant's, fixed by the owner
overnight; not wDSP. Board #1810 to BitPerfect (EffectHAL SET_CONFIG) still open on their side.

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
