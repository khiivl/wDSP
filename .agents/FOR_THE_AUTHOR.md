# For the author's agent — a tour of `kostyfmat_mod`

You are reading the mod of Kostiantyn (the owner of this branch) on top of khiivl's `master`. This
page is the map: what the branch is, where each subsystem lives, which conventions it keeps, and
what is still open. The rules in force are in [AGENTS.md](AGENTS.md); the current state is in
[HANDOFF.md](HANDOFF.md) (Ukrainian — the owner reads it).

## 1. How this branch relates to `master`

- **All of `origin/master` is in it.** The author's work was absorbed feature by feature and then
  merged for real, so a PR `kostyfmat_mod → master` is meant to apply without conflicts.
  Per-feature decisions: [UPSTREAM_0_5.md](UPSTREAM_0_5.md) (0.5) and [UPSTREAM_1_0.md](UPSTREAM_1_0.md)
  (1.0 "Bulldozer").
- **The author's numbers stand.** He has measuring equipment; we do not. Where his values and a
  derived value of ours (research, ISO, a model) disagree, his are kept and only extended by his
  own rule.
- **Two UI styles.** *Classic* reproduces the author's interface and follows his `master`;
  *Modern* is the owner's. Every UI change is done in both styles and both themes (day/night);
  the themed views are listed by id in [ARCHITECTURE.md](ARCHITECTURE.md).
- The diff to `master` is large (~75 Java files, ~38k lines added) because the branch also carries
  what a real QF head unit needs and an emulator never shows — section 3.

## 2. Build and run

- `./gradlew :wdsp_app:assembleDebug` on Windows. **Debug builds only**: R8 breaks the hidden-API
  reflection (`McuLink`). `targetSdk 29` is deliberate — the QF framework behaves as Android 10.
- `:wdsp_proxy` (`com.qf.soundeffect`, system uid) replaces the stock DSP app so the quick-settings
  DSP button opens wDSP; the stock app writes the same chip registers. [BUILDING.md](BUILDING.md).
- No unit tests on the Java side. The native analyser has host tests in `src/main/cpp/test_*.cpp`
  (`g++ -O2 -std=c++17 ... sweep.cpp analyzer.cpp fft.cpp stitcher.cpp`, run in WSL/Linux).
- Runtime permissions are granted through the app's UI (`ui/PermissionsWizard`), never with adb.

## 3. Where things live

| area | classes | knowledge |
|---|---|---|
| Hardware path | `McuService` (the only writer to the chip, 100 ms loop), `McuLink` (MCU manager by reflection), `DspResponse` (chip codes ↔ Hz/dB, the one table), `AudioConfig` | [ARCHITECTURE.md](ARCHITECTURE.md), [platform/INDEX.md](platform/INDEX.md), [platform/03-SOUND-PROCESSOR.md](platform/03-SOUND-PROCESSOR.md), [platform/15-BU32107-REGISTERS.md](platform/15-BU32107-REGISTERS.md) |
| Presets | SharedPreferences as the bus, `LivePreset` (unsaved edits), `PresetsDatabaseValidator`, `CallPreset` (call EQ, an array in code) | ARCHITECTURE.md §"SharedPreferences is the bus" |
| Cabin measurement | `RoomMeasurement` (the sweep pass), `NativeSweep` + `cpp/sweep.cpp` (deconvolution, bands, synthesis), `CabinProfile`/`CabinGeometry` (the car), `MicProfile` (the microphone), `CabinDefect` (the list of defects shown to the user), `ChannelSwapCheck`, `LoudnessCheck` | [CABIN_MODEL.md](CABIN_MODEL.md) (canon), [ROOM_CALIBRATION.md](ROOM_CALIBRATION.md), [CALIBRATION_HISTORY.md](CALIBRATION_HISTORY.md) |
| Microphone | `MicrophoneGuard` (takes the mic back before measuring), `MicProbe`, `RootAccess`, capture effects off | ARCHITECTURE.md §"The microphone" |
| Spectrum / RTA | `AudioSpectrumEngine`, `NativeAnalyzer` + `cpp/analyzer.cpp`, `RtaCurve` (the author's display model on our analyser), `SpectrumAnalyzerView`, `LatencyProbe` | UPSTREAM_1_0.md §"The live RTA" |
| Audio Check (author's 1.0) | `AudioCheck`, `AudioCheckPanel`, `ToneSource`, `PlaybackFocus` | UPSTREAM_1_0.md |
| Living with other apps | `PlayerResume`, `NowPlaying`, `ScreensaverManager`, `StatusBarVisualizerManager`, `RadioMicCapture`, `CallState` | [CONTRACTS_MAP.md](CONTRACTS_MAP.md) |
| UI helpers | `ui/` — `Disclosure` (collapsible groups), `RowFit` (narrow screens), `ThemedDialog`, `theme/` | ARCHITECTURE.md §"UI" |

## 3a. Roadmap: the main flows, from a tap to the chip

Every class with what it offers: [CLASS_MAP.md](CLASS_MAP.md) (generated from the code's comments by
`tools/classmap.py` - regenerate it after a change). The paths that tie the classes together:

1. **Boot.** `BootReceiver` starts `McuService` as a foreground service; no activity is created. The
   service reads the current preset from SharedPreferences and sends it through `McuLink` (the MCU
   manager by reflection) to the BU32107. A fault seen only after a reboot lives on this path.
2. **A slider moves.** `MainActivity` writes SharedPreferences (unsaved edits through `LivePreset`).
   `McuService`'s preference listener picks the change up, folds in the loudness curve for the current
   volume (`LoudnessCurve`: FM curve, fatigue, bass shelf, Ultra Bass), quantises to 2 dB steps and
   sends the frame. It then hands the values it really sent to `AudioSpectrumEngine.setDspState`, so
   the calculated spectrum draws what the chip does, not what the screen asked for.
3. **The spectrum.** `AudioSpectrumEngine` finds the player's audio session (`SessionResolver`), taps it
   with a Visualizer (session 0 is dead on QF), and feeds `NativeAnalyzer` (`cpp/analyzer.cpp`, 32
   third-octave bands + a 200-point curve). The microphone mode runs a second analyser on the
   microphone, held on the calculated one by our AGC (`alignMicrophoneToCalculated`).
   `SpectrumAnalyzerView` draws bars or the RTA curve (`RtaCurve`), against the frame's average or the
   fixed zero taken from pink noise (`RtaReference`).
4. **The cabin measurement.** `SettingsActivity` asks the questions (the cabin card: `CabinProfile`,
   `MicProfile`) and calls `RoomMeasurement.measureAsync`. One pass: route one speaker at a time through
   the fader and EQ (`McuService` lends them), play the sweep (`NativeSweep` / `cpp/sweep.cpp`), record,
   deconvolve per channel, find arrivals, align the sub at the crossover, judge polarity and wiring
   (`ChannelSwapCheck`), collect the defects (`CabinDefect`: clipping, missing or extra speakers,
   swapped channels, polarity, tweeter against its mid-bass, resonances and cabin modes, a quiet side,
   reflections only, noise), and synthesise a coarse preset (16 bands, 2 dB, no zigzag). The
   microphone calibration is the same pass with `estimateMicCompensation` instead of the synthesis.
5. **Audio Check.** `AudioCheck` plays the test signals (stems, pink noise, sine, sweep, one speaker);
   `AudioCheckPanel` binds the buttons. The fader and "only sub" overrides go to `McuService` as
   runtime actions and are cleared when a call starts.

6. **Events instead of clocks (12–13.10.2026).** Nothing polls while the car is parked: the service's
   check, the analyser's watchdog and the screensaver look when `StateEvents` rings (a watched property
   changes value via `PropertyWatch` / `__system_property_wait`, a media session plays or stops,
   playback or the captured signal starts or ends). Only GALA keeps a 100 ms timer, and only while it
   works, from its own speed threshold - a parked unit's GPS drifts 1-6 km/h and the old "> 0" ran the
   check ten times a second. Time-only moments are single delayed looks (the screensaver's idle delay,
   the clock after a pause, the call announcement's watchdog, the session re-resolve back-off).
   Details: [ARCHITECTURE.md](ARCHITECTURE.md), "The volume and player check".
7. **Rules that came out of it** (owner's decisions, 12–13.10): wDSP never rewrites `persist.*`
   volumes; the per-source memory follows changes made in QF car settings (`SourceLevelWatch`); no GALA
   on a call; on radio the microphone analysis is decided by the settings toggle only, not by the
   display mode or calibration, and the analyser does not trust the factory FM app's session (it says
   "not playing" while the tuner sounds); the screensaver clock comes ~1 s after a player reports
   PAUSED, the 4 s + 8 s wait stays for ambiguous stops. The re-resolve while paused is kept on purpose
   (Bluetooth music plays past AudioFlinger's list).
   Accepted on the unit with the owner, row by row: [TESTS.md](TESTS.md).

Native code has host tests with known answers, run with plain g++ in WSL or Linux:
`cpp/test_sweep.cpp`, `test_analyzer.cpp`, `test_miccal.cpp` (replays the owner's 20.09 run),
`test_micwidth.cpp`, `test_crossover.cpp`, `test_resonance.cpp`, `test_pair.cpp`, `test_zigzag.cpp`
(command lines in `cpp/CMakeLists.txt`). There are no Java tests.

## 4. What the cabin measurement is for

The chip gives one global EQ of 16 bands, 2 dB steps, a fixed Q of 2.2, no per-channel EQ. Chasing a
flat ±1 dB response is neither possible nor useful. So the measurement's main output is **a list
of acoustic defects** (`CabinDefect`: clipping, a speaker declared but not heard or heard but not
declared, swapped channels, an inverted speaker, a tweeter against its own mid-bass, a quiet side,
a speaker's own resonance or the cabin's mode below 200 Hz, a channel heard only by reflection,
delay beyond the chip, mid-bass roll-off, a noisy cabin) with what to do about each, plus a coarse correction: wide deviations only,
cuts preferred, boost ≤ +3 dB. The owner's bench is an open desk, not a car — no acoustic
conclusion is drawn from it.

## 5. Conventions

- Agent-facing files (`.agents/*`, code comments) are **English**; `HANDOFF.md`, `TODO.md`,
  `DEBT.md` and commit messages are **Ukrainian** — the owner reads those. His quoted decisions
  stay verbatim.
- One commit per verified step, body: what / why / touches / how verified / related. Code,
  resources and docs in separate commits.
- Platform facts carry provenance marks: 🔬 read in firmware, 📻 measured on the wire, 🧩 inferred,
  ❓ unverified. An unmarked claim about QF is not usable.
- Contracts with other apps are canonical in the owner's `C:\APPS_Contacts\`; copies here are
  mirrors — [CONTRACTS_MAP.md](CONTRACTS_MAP.md).

## 6. Open, as of 09.10.2026

The queue is [TODO.md](TODO.md), known defects [DEBT.md](DEBT.md), the current step [HANDOFF.md](HANDOFF.md).
Things the author may care about:
- **not yet heard on the unit** (written at night, host-tested only): the sub aligned at the crossover
  the measurement chose and on the in-phase peak nearest the geometry; the RTA zero from pink noise;
  the defect detectors on the third-octave grid; the zigzag limit;
- the front/rear fader trim waits for the MCU's fader table (index 0..24 -> dB);
- MCU fact (🔬 firmware 002121): sub payload `8B 00` is LPF 25 Hz at -24 dB, **not** mute; full
  silence needs an MCU patch;
- the audiocheck.net calibration files in the repo are not at 0 dBFS (RMS: sweep -5.9, white -10.5,
  pink -15.2 dBFS) - [MEASURED_FACTS.md](MEASURED_FACTS.md);
- open after 13.10: the volume synchronisation with the owner's own radio app was not re-tested (it
  was not installed); after a full reboot wDSP's main window sometimes opens and nobody is found doing
  it (the platform's restore list `/system/config/RestoreAppsWhenWakeup.ini` does not contain wDSP);
  `PlayerResume` once remembered the factory player as "playing" while Bluetooth played;
- an idea left for the author, not started: a software 32-band EQ inserted after the mixer when
  BitPerfect is on (question to that line on the board, #1628-#1631) - the hardware keeps the
  crossover, sub, filters, delays and fader; calls and radio bypass it.
