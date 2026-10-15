# HANDOFF — wDSP slice

**15.10.2026 evening, Claude session "wDSP mod".** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- Pushed: `origin/kostyfmat_mod` = `6fa768d`. **Unpushed since:** `05d587c`..`8e113b4` (no noise
  refusal; EQ on the doors only in the control forecast, sub pass with the EQ at its floor; both RTA
  zeros in one tap at the mic's calibration step; mic overload -> AGC + red notice over the Q row,
  on >= 4 % of samples at full scale for 1 s, off under 2 %) + docs/measurements.
- Bench `192.168.1.138:9876`: `8e113b4`, BitPerfect v5.5.15 on. If adb gives 10013, ClearVPN blocks
  the LAN - the owner turns it off. ❓ `0102[5:4]`. Argon chat: 32890fb409d8df41.
- Board #1813 → BitPerfect line (other account, no reply expected here): a disabled module leaves
  `car.config music_volume=15` and its `/data` AGDSP config.
- **The BU32107 EQ never reaches the sub** (author 15.10, MCU + datasheet); model, synthesis, control
  forecast and docs follow it now - memory `bu32107-eq-never-reaches-sub`.

## ✅ Seen on the bench 15.10 (owner present)

Measurement volume (step 7, clean; table 63.1 dB), loudness by ear 10..25 («все норм на слух»), sub
delay 4.2 -> 2.4 ms after the sub-pass fix («стала більш вірогідна»), mic calibration stores its step
(7), RTA zeros: channel -30.3, mic -32.0 at step 7 in one tap, volume put back; overload notice: on at
13, off at 12 on the owner's preset («13 встало, 12 зняло»; depends on the preset - by design, the
threshold reads the microphone, not the step).

## ⏳ Next

1. Translations of `rta_mic_overloaded` (en/uk only) - Gemini's job.
2. Release (owner 15.10: «поки день зробимо всі проби. І зарелізимо»).
3. Waiting for the author: the 20 Hz rule (mic calibration's 20 Hz swings +0.6..+16 dB run to run,
   SNR there 1..12 dB), Argon's model findings (TODO «Ближнє» 5, 6).
4. Unchecked: call takes the platform's level (`698830f`), wake from ACC OFF volumes (`4d1006d`),
   radio sync / screensaver normalisation (`TESTS.md`).

## ❓ For the owner

- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
