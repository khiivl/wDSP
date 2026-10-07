# MEASURED — do not measure again

Numbers taken on the unit and marked "do not re-measure" by the sessions that took them. Rescued from
the 11–15.09 memory snapshots before those were deleted; the snapshots themselves were superseded
state, these figures were the only part worth keeping.

Provenance marks as elsewhere in `.agents/`: 📻 measured on the wire, 🔬 read in firmware, 🧩 inferred.

## The capture chain

- 📻 **Visualizer runs at 48 kHz while declaring 44.1.** Its window moves in whole milliseconds and
  the overlap is bit-for-bit. The stitched stream is flat to **±1.5 dB**; 8-bit, `NORMALIZED` by
  default. `AS_PLAYED` gives the file's own level (−25.35 against −25.24 dBFS) and does **not** follow
  the MCU volume.
- 📻 **When we are first on the input, AEC/NS do not touch our samples** — a 30 s tone came back ±0 dB.
  The effects listed in a dump are not processing; the tone is the proof
  ([[mic-input-order-decides-retake-before-measuring]]).
- 📻 **The microphone can be held from boot without root**: we take it about **11 s before the
  assistant**, and switching sources does not reopen it. Cold start: wDSP opens the microphone at
  **+56 s**, the assistant at **+87 s**.
- 📻 **Sleep does not close the input** (clean test). An earlier test that suggested otherwise was
  dirty — the main screen was open.
- 📻 **Level offset of path (b): +2.1 dB** — track −47.9 dBFS against microphone −50.0 on the bench.
- 📻 **Host sweep on a flat path: the 20 kHz band reads −6.6 dB.** That is the analysis, not the
  hardware, and it is why a band-edge figure alone never proved a rolled-off tweeter.
- 📻 Reference material: EMMA "12 Pink Noise.wav" is 44.1 kHz, −29 dBFS RMS, with −4 dB at 20 Hz and
  −4.5 dB at 20 kHz. Subtract that before blaming the room.
- 📻 **The author's calibration files** (audiocheck.net, given 07.10.2026; he calls them "0 dB"). All
  mono 44.1 kHz MP3, none at 0 dBFS — "0 dB" is only roughly true of the peaks, and the RMS spread is 9 dB:
  - `pinknoise` 10 s: peak −2.0, **RMS −15.2 dBFS**; 1/3-octave bands flat ±0.3 dB 50 Hz–12.5 kHz,
    +0.7…0.8 at 31.5 and 16–20 kHz. Usable as is (unlike the EMMA file above).
  - `whitenoise` 10 s: peak −0.5, **RMS −10.5 dBFS**; rises +1.0 dB per third-octave, as white must.
  - `sweep20-20klog` 20 s: 20 Hz → 20 kHz exponential, 0.498 oct/s (fit residual 0.001 oct); constant
    envelope **−2.9 dBFS** peak (RMS −5.9), flat ±0.03 dB across all ten octaves.
  A level calibrated "by these files" is calibrated by ONE of them — name which.

## The platform around us

- 📻 `sys.boot.reason` reads `reboot,adb` or `reboot` (the latter when power was cut). **The assistant
  opens the microphone immediately after `BOOT_COMPLETED`.**
- 📻 `sys.qf.last_audio_src` is **sticky after a reboot**, which had the session resolver spinning
  every 4–5 s; the back-off is now 60 s.
- 📻 **Shell daemons survive sleep and wake first**, and the power manager clears logcat — which is
  why the logger has to live on the unit as a module (`platform/07-PRACTICE.md` §7) rather than being
  read after the fact ([[read-the-boot-logger-not-logcat]]).
- 🧩 **Toppal / TXZ are vendor assistants with priority on the microphone.** Haiwai builds have no
  Toppal; Jitu2 has the lot. Which one a unit carries decides how hard the input is to hold.
- 📻 BitPerfect v5.3 leaves `pcmC0D3p` busy with a live HAL
  ([[bitperfect-v53-pcm-busy-and-silent-tts]]); `com.txznet.weather` was removed.

## Git, on this branch

- ⚠️ **`kostyfmat_mod` has no upstream configured**, so `git rev-list …@{u}` fails, and a bare
  `origin/master...HEAD` answers with the distance from **the friend's** branch (324 at the time), not
  ours. Count against `origin/kostyfmat_mod` explicitly.

Related: [[calc-is-target-mic-is-actual]], [[sweep-top-from-channel-probe]],
[[cabin-average-in-power-not-db]], and the maths history in [CALIBRATION_HISTORY.md](CALIBRATION_HISTORY.md).
