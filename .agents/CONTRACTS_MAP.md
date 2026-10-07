# Contracts this branch keeps — a map for the author and his agent

On a real QF head unit wDSP does not run alone. A radio app, an audio-path Magisk module (BitPerfect), a voice
assistant (RokoAi) and the phone stack share one MCU, one microphone and one audio policy, and none of that
exists on an emulator. The agreements below were made with the other side and written down; a change that
crosses one of these lines needs checking against it on the unit. Canonical texts of the agreements between
applications live in the owner's `C:\APPS_Contacts\`; the copies in `.agents/` are mirrors.

## Between applications

| contract | parties | the gist for wDSP | text |
|---|---|---|---|
| Audio ownership | wDSP ↔ QF Radio | wDSP owns the volume level (a base per source + the GALA offset) and the preset on the chip; the MCU channel belongs to whoever plays, and the radio declares its route once | [AUDIO_OWNERSHIP_CONTRACT.md](AUDIO_OWNERSHIP_CONTRACT.md) |
| Screensaver | wDSP ↔ QF Radio | the status-bar visualiser is lent to the radio's screensaver; now-playing metadata comes through the media session | [SCREENSAVER_RADIO_CONTRACT.md](SCREENSAVER_RADIO_CONTRACT.md) |
| Audio path and module | wDSP ↔ BitPerfect | the module owns the audio policy files, PCM descriptions, routes, bit depth and rate; wDSP owns the equaliser, delays, crossover, the preset on the chip, the volume and the capture effects on its own session, and reports what it sees on the wire | [BITPERFECT_MODULE_CONTRACT.md](BITPERFECT_MODULE_CONTRACT.md) |
| Boot audio state | wDSP · BitPerfect · RokoAi | who restores what after boot and wake; each side checks whether the job is already done before acting, so that any combination of installed components keeps working | `C:\APPS_Contacts\wDSP--BitPerfect\BOOT_AUDIO_STATE_CONTRACT.md` |

## Inside the app

1. **One writer for the chip.** Only `McuService` sends the sound-processor packets: `0x80` the equaliser
   (pre-warped, `AudioConfig.prewarpEq`), `0x81` the fader with the built-in loudness bit, `0x88` the door
   high-pass codes (front in the high nibble, rear in the low), `0x8B` the subwoofer, and the delay packets.
   `0x8B` has no "off" bit, so "No Sub" (index 11) goes out as the lowest crossover at 0 dB. The stock
   `com.qf.soundeffect` writes the same registers: disable it, or install `wdsp_proxy` in its place.
2. **The chip model is one function.** `DspResponse` is what the preset makes the chip do; the bands, the
   loudness verdict (`LoudnessCheck`) and the RTA all read it. Acoustic facts — the door speaker's roll-off,
   the cabin — are added on top where a view needs them, never inside the chip model.
3. **Calls.** `CallState` is the only answer to "is a call on". At `PHONE_CALL_START` the microphone is released
   first, synchronously, before anything that can block; Audio Check stops; test overrides are cleared; the
   `Call` preset applies. An announced call that is never confirmed expires after 6 s.
4. **The microphone.** Whoever opens the input first sets its source and effects. wDSP suspends AEC/NS/AGC on its
   own capture session only while nobody else is recording, and restores them as soon as another recorder
   appears or when it lets go (`RadioMicCapture`). When the preferred source cannot be had it falls back to
   `VOICE_RECOGNITION`, and the log says which source it actually got (`MicrophoneGuard`).
5. **Presets.** Stored in `EqPresets` as `<preset>_<key>` and read through `PresetsDatabaseValidator`, which
   clamps every value to its range: the subwoofer index is 0..11 with "No Sub"; GALA keeps this branch's units
   (threshold in 5 km/h steps up to 200 km/h, step = stored value + 5 km/h). Interface state never goes into
   `EqPresets` — the export walks that file — it lives in `ThemeManager.prefs`. Audio Check's overrides are
   runtime only, by broadcast: `com.radiorubka.wdsp.AUDIOCHECK_FADER` (`fr`, `lr`; −1 clears) and
   `AUDIOCHECK_ONLY_SUB` (`enabled`).
6. **The spectrum.** Never `Visualizer(0)`: on a real QF the output mix it taps carries silence.
   `SessionResolver` finds the player's own session, trying an announced one
   (`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`) first. The sample rate comes from
   `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` (48 kHz), not from `Visualizer.getSamplingRate()` (44.1 kHz there).
   The native analyser (`NativeAnalyzer`, `analyzer.cpp`) gives 16 bands and a 200-point curve of third-octave
   points (1/24 octave while a test tone plays), delayed by the measured playback latency.
7. **Our own sound.** Whatever wDSP plays itself (Audio Check, the cabin sweep) holds audio focus through
   `PlaybackFocus` and stops when the focus is lost or a call starts; Audio Check also announces its session,
   so the spectrum follows the test at once.
8. **The interface.** One layout, two styles: Modern (this branch) and Classic (an emulation of the author's
   look). Colours come from `ThemeManager` through the id lists in `MainActivity.applyAppTheme` — a new view
   goes into those lists, never with a colour typed into the XML. A UI change is done when it has been looked at
   on a QF screen (the bench is 1280×720 landscape; the platform has more, `SCREEN_MATRIX.md`) in both styles, by day and by night. Folding sections: `ui/Disclosure`;
   rows that must fit a narrow window: `ui/RowFit`.
9. **The platform.** `targetSdk 29` on purpose: the QF framework behaves as Android 10. Debug builds only — R8
   breaks the hidden-API reflection. Permissions are granted by the user in the UI, never with adb: an adb grant
   works for the developer and hides the bug from every tester. Platform facts: `.agents/platform/`.

Why each rule exists: [DECISIONS.md](DECISIONS.md). What came from 1.0 and how: [UPSTREAM_1_0.md](UPSTREAM_1_0.md).
