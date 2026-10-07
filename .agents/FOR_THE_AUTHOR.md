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

## 4. What the cabin measurement is for

The chip gives one global EQ of 16 bands, 2 dB steps, a fixed Q of 2.2, no per-channel EQ. Chasing a
flat ±1 dB response is neither possible nor useful. So the measurement's main output is **a list
of acoustic defects** (`CabinDefect`: a speaker declared but not heard or heard but not declared,
swapped channels, an inverted speaker, a channel heard only by reflection, delay beyond the chip,
mid-bass roll-off, a noisy cabin) with what to do about each, plus a coarse correction: wide deviations only,
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

## 6. Open, as of 08.10.2026

The queue is [TODO.md](TODO.md), known defects [DEBT.md](DEBT.md), the current step [HANDOFF.md](HANDOFF.md).
Things the author may care about:
- the subwoofer pass now really plays the sub only (sweep to 200 Hz, LPF 150 Hz) and aligns its
  delay by phase at the crossover; the sub sits at +13.4 ms or +0.9 ms after alignment — the owner
  is choosing by ear;
- MCU fact (🔬 firmware 002121): sub payload `8B 00` is LPF 25 Hz at −24 dB, **not** mute; full
  silence needs an MCU patch;
- the audiocheck.net calibration files in the repo are not at 0 dBFS (RMS: sweep −5.9, white −10.5,
  pink −15.2 dBFS) — [MEASURED_FACTS.md](MEASURED_FACTS.md).
