# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository location

The repo lives at `C:\Users\kosty\AndroidStudioProjects\wDSP` (an *additional* working directory in
this session — the primary cwd `qf_fmradio` is an empty leftover folder). Always use absolute paths
into `wDSP`. Working branch: `kostyfmat_mod` — work only there. Upstream is https://github.com/khiivl/wDSP.

`rules.md` and `agents.md` at the repo root are **gitignored local docs** — they exist on disk but are
not tracked. `agents.md` is a hand-written knowledge base and is partly stale (see "Known
discrepancies" below); the Java source is the source of truth.

## Build

Gradle 9.4 + AGP 9.0.1, Java 11 source/target, `compileSdk 36`.

```bash
./gradlew :wdsp_app:assembleRelease
```

```bash
./gradlew :wdsp_app:assembleDebug
```

```bash
./gradlew :wdsp_proxy:assembleRelease
```

APKs land in `wdsp_app/build/outputs/apk/<type>/`.

There are **no unit or instrumented tests** in the repo (`src/test` / `src/androidTest` do not exist),
despite `testInstrumentationRunner` being declared. `./gradlew test` is a no-op; do not claim test
coverage.

Signing: `wdsp_app/build.gradle` reads `keystore.file` / `keystore.password` / `key.alias` /
`key.password` from `local.properties` (gitignored). **Both `release` and `debug` use the release
signing config**, so debug and release APKs install over each other. `minifyEnabled false` for
`wdsp_app` (R8 would break the hidden-API reflection); `wdsp_proxy` does minify, but it has no
reflection.

## Modules

- **`:wdsp_app`** — the actual DSP app. `applicationId com.radiorubka.wdsp`, `minSdk 29`,
  **`targetSdk 29` on purpose** (the QF framework and its hidden APIs behave as Android 10; do not
  "modernize" the target SDK or add Android 11+ code paths).
- **`:wdsp_proxy`** — a 20-line stub with `applicationId com.qf.soundeffect` and
  `sharedUserId="android.uid.system"`, installed *as an update to the stock DSP app* (root +
  PMPatch3 only). Its single translucent `SoundActivity` just launches `com.radiorubka.wdsp` and
  finishes, so the head unit quick-settings DSP button opens wDSP.

## Architecture

### SharedPreferences is the bus

There is no service binding, no observer interfaces, no repository layer. **The UI writes
SharedPreferences; `McuService` listens via `OnSharedPreferenceChangeListener` and pushes bytes to the
MCU.** Understanding the key naming scheme is the fastest way to understand the app.

Two stores:

| Store | Accessed via | Holds |
|---|---|---|
| `EqPresets` | `getSharedPreferences("EqPresets", MODE_PRIVATE)` | every preset's DSP values, `preset_names`, `last_selected_preset`, `player_preset_map`, GALA globals, `sb_vis_*` |
| `com.radiorubka.wdsp_preferences` | `PreferenceManager.getDefaultSharedPreferences()` (via `ThemeManager.prefs()`) | theme mode, 4x2 day/night colors, wallpapers |

Every DSP value is a **flat key prefixed with the preset name**: `<preset>_g0`..`<preset>_g15` (band
gains 0..12), `_q0`..`_q15` (per-band Q booleans), `_sub_g` / `_sub_f`, `_bb_f` / `_bb_r` / `_bb_frq_f` /
`_bb_frq_r` / `_bf_f` / `_bf_r`, `_f_lr` / `_f_fr` / `_loud`, `_d_en` / `_d_fl`..`_d_sub`, `_d1_en` /
`_d1_fl`..`_d1_rr` / `_rsse_val`, `_fm_cal` / `_fm_str`, `_power_vol`, `_gala_*`.

`McuService.prefListener` dispatches on **substring matches of the key** (`key.contains("_sub")`,
`_g` && !`_gala`, `_d_`, `_d1_`/`_rsse_`, `_bb_`/`_bf_`, `_f_`/`_loud`, `_power_vol`) to decide which
hardware command to re-send. Consequence: **naming a new preference is a wiring decision.** A new key
containing e.g. `_f_` will silently re-trigger the fader command; a key that matches nothing is written
but never reaches the MCU.

Renaming/importing presets therefore means rewriting every prefixed key — that is what the prefix
normalization in `MainActivity.loadPresetFromFile()` and the rename path (~`MainActivity.java:1554`) do.

### `McuService` — the only thing that talks to hardware

Foreground service (`foregroundServiceType="connectedDevice"`), started from `BootReceiver` and from
`MainActivity.onCreate()`. All hardware work runs on a single `HandlerThread("wDSP_Worker")` at
priority `-16` (THREAD_PRIORITY_AUDIO).

Hidden-API access is entirely reflective, no root:

- `android.os.ServiceManager.getService("mcu_service")` -> `android.qf.mcu.IMcuManager$Stub.asInterface()`
  -> `RPC_SetEQData(byte[])` for DSP payloads and `RPC_SendMcuMsgData(byte, byte[], int)` for MCU messages.
- `android.qf.os.VolumeManager` / `android.qf.os.VolumeState` (`VolumeHelper`) for hardware volume,
  mute state, and the active player type (`media_type` / `radio_type` / `btcall_type` / `aux_type`).
  The class name `android.qf.os.VolumeState` is Base64-obfuscated in source to survive Play Store
  scanning — keep that pattern if you touch it.
- `android.os.SystemProperties` for `sys.qf.last_audio_src` (active player package) and
  `persist.sys.day_night` (illumination, read in `StatusBarVisualizerView`).

**Actual command map (from the code, verified against `sendToHardware`/`apply*`):**

| Cmd | Length | Meaning | Encoding |
|---|---|---|---|
| `0x80` | 12 | 16-band EQ | 8 packed bytes, 2 bands per byte (`idx2<<4 \| idx1`), gain index 0..12 = -12..+12 dB in 2 dB steps; then 2 Q-factor bit-mask bytes (bit set = 4.7, clear = 2.2); trailing `0x00` |
| `0x8B` | 2 | Subwoofer | `(freqIdx << 4) \| gainIdx`, freqs `{25,32,40,50,63,80,100,125,160,200,250}` Hz, gain 0..12 |
| `0x88` | 4 | Bass boost + high-pass, front & rear | `((boostFreqIdx+8)<<4) \| boostLevel` per channel, then `(hpfFront<<4) \| hpfRear` |
| `0x81` | 4 | Fader / balance / loudness | L-R step, F-R step (12 = center), loudness flag |
| `0x8C` | 6 | Positional time alignment | FL, FR, RL, RR, Sub — each stored value x5; all-zero payload when `_d_en` is off |
| `0x89` | 6 | Surround / Haas + RSSE | `138 + (rsse-10)`, then FL, FR, RL, RR; all-zero payload when `_d1_en` is off |
| msg `24` | 2 | Power-amp pre-volume | sub-ID `2` + value, sent through `RPC_SendMcuMsgData`, **not** `RPC_SetEQData` |

`sendToHardware()` de-duplicates per command byte via `mcuCache`, and EQ (`0x80`) and sub (`0x8B`)
additionally go through a 500 ms throttle (`THROTTLE_MS`) with a trailing write, because dragging a
slider would otherwise flood the MCU.

### The 100 ms polling loop

`pollingRunnable` re-posts itself every 100 ms and does three things:

1. `checkVolumeAndGala()` — reads hardware volume; recomputes the Fletcher-Munson / fatigue EQ offsets
   and the sub compensation whenever volume changed; runs GALA (speed-dependent volume) with a
   hold-timer plus a +/-1-step fade, driven by GPS `LocationListener` speed or a simulated-speed broadcast.
2. `checkPlayer()` — reads `sys.qf.last_audio_src` and the active volume type, auto-switches presets
   through `player_preset_map` (with the special `Call` preset that remembers `presetBeforeCall`),
   and gates the status-bar visualizer (channel 2 = hardware radio, so no PCM to visualize).
3. `checkForBug()` — firmware workaround: volume 0 while *not* muted is forced to 1, because that
   state can destroy the subwoofer on jitu/haiwai firmware. Do not remove this.

### Broadcasts

Service -> UI (all `setPackage(getPackageName())`): `VOLUME_CHANGED`, `PRESET_CHANGED`, `GALA_UPDATE`,
`SUB_GAIN_CHANGED`. UI -> service: `UI_ACTIVE` / `UI_INACTIVE` (the service only broadcasts UI updates
and suppresses toasts based on `isUiVisible`), `SIMULATE_SPEED`, `SET_POWER`, `SETTINGS_RESTORED`.
System/vendor in: `com.qf.action.ACC_ON` / `ACC_OFF`, boot actions.

External control (works without the Activity, receiver is registered dynamically — there is **no**
manifest receiver class, so `-n .../.SubGainUpReceiver` will not work):

```bash
adb shell am broadcast -a com.radiorubka.wdsp.SUB_GAIN_UP
```

Backup/restore can also be driven headlessly by starting `SettingsActivity` with
`com.radiorubka.wdsp.ACTION_BACKUP` / `ACTION_RESTORE` and a `path` string extra. The backup JSON is
`{version: 2, app, timestamp, default_preferences, eq_preferences}` — i.e. both stores — and restore
ends with a `SETTINGS_RESTORED` broadcast that hot-reloads the UI and the service.

### UI

`MainActivity` (~2100 lines) is a single activity holding **five sections in one layout**
(`layout_eq`, `layout_fm_curve`, `layout_delays`, `layout_filters`, `layout_gala`) toggled by
visibility from `BottomNavigationView`. `SettingsActivity` reuses the same nav menu; picking a
non-settings tab there routes back to `MainActivity` with that section open.

`ThemeManager` is a static holder, not a theme resource system: colors are read from prefs per
day/night (`*_day` / `*_night` key suffixes) and applied **imperatively** to views at runtime
(`tintTextInputLayout`, nav bar tinting, label/value coloring). A new control is not themed until
someone tints it explicitly — the XML colors are only the pre-theme defaults.

`AudioSpectrumEngine` is a singleton over `android.media.audiofx.Visualizer`. All spectrum consumers
(`SpectrumAnalyzerView`, `FmVisualizerView`, `StatusBarVisualizerView`) register as listeners on that
one engine — **never open a second `Visualizer` session.** Which session it attaches to is decided by
`SessionResolver`, not assumed; see below.

### Native analyzer (`src/main/cpp`)

The measurement chain is C++ (`libwdsp_native.so`, built by CMake, arm64 + armeabi-v7a). Java feeds
it blocks and reads levels; everything else happens in native. Two things about it are load-bearing:

**Capture is polled, not callback-driven.** `getMaxCaptureRate()` is 20 Hz on this platform and each
callback carries 1024 samples — 21 ms of audio out of every 50, with the rest missing. `Stitcher`
polls every 9 ms so consecutive reads overlap, then aligns them by normalised cross-correlation and
appends only the new tail. `discontinuities()` counts failures; a rising count means the poll rate is
too low. Without this there is no continuous stream, and no transform below the block rate means
anything.

**32 third-octave bands are measured and folded down to 16, never interpolated up.** Each analysis
band is exactly half a hardware band (`HW/2^(1/6)` and `HW*2^(1/6)`), so folding pairs is an exact
energy sum. Band energy is mean bin power times the number of bins the band *should* hold at that
resolution — a plain sum biases narrow bands, an average per bin biases wide ones. Two window
lengths run at once: 8192 below 800 Hz where resolution is needed, 1024 above where speed is.

`test_analyzer.cpp` is a host-side harness, excluded from the app build. Run it after touching the
band plan, the transforms or the stitcher:

```bash
g++ -O2 -std=c++17 -o /tmp/wdsp_test test_analyzer.cpp fft.cpp stitcher.cpp analyzer.cpp && /tmp/wdsp_test
```

It checks that pink noise reads flat with an empty correction table, that tones land in the right
band, and that the stitcher loses nothing.

⚠️ Draw rate is not measurement rate. `StatusBarVisualizerView` drives its own redraws through
`Choreographer`; left unthrottled it costs nearly three times as much CPU as the whole analyzer. `StatusBarVisualizerManager` puts `StatusBarVisualizerView` into a
`TYPE_APPLICATION_OVERLAY` window (needs `SYSTEM_ALERT_WINDOW`) sized and positioned by the `sb_vis_*`
prefs.

## Working constraints (from `rules.md`, the project owner's rules)

- Read a file before editing it; the on-disk version may be newer than what you remember.
- Preserve existing code verbatim. Never stub out or empty a function you are not asked to change.
- A change to a data type, signature, or dependency must be propagated to **every** call site in the
  same change — no new functionality may break existing functionality.
- The owner is the architect; ask when in doubt rather than inventing a design.

## Known discrepancies and traps

- **`agents.md` command IDs are wrong**: it lists fader `0x82`, positional delays `0x84`, surround
  `0x85`. The code sends `0x81`, `0x8C`, and `0x89` respectively. Trust the code.
- **Two divergent `TouchGlow` classes** exist and are both live: `ui/TouchGlow.java` (used by
  `SettingsActivity`) and `ui/theme/TouchGlow.java` (used fully-qualified by `MainActivity`). Fixing
  one does not fix the other.
- **Two full copies of the main layout**: `layout/activity_main.xml` (landscape head units, ~2600
  lines) and `layout-port/activity_main.xml` (vertical and near-square units, ~1970 lines). The
  portrait file is the more dangerous of the two because it is edited far less often. A missing id
  costs a null check; **the same id declared as a different widget type costs a crash in
  `onCreate`** — `findViewById` returns whatever was inflated and the field it is assigned to has
  the other type. Eight switches and six preset buttons had drifted apart exactly like that, and
  the app died on every portrait and Tesla-shaped screen. Run this after touching either file:

```bash
python tools/layout_diff.py wdsp_app/src/main/res/layout/activity_main.xml wdsp_app/src/main/res/layout-port/activity_main.xml
```

- **Screen geometries**: the platform matrix lives in
  `kostyamat_fmradio/.agents/SCREEN_MATRIX.md` — 132 panels, and the real set of UI geometries is
  much smaller than the list of resolutions because density is only ever 160 or 320. Emulate with
  `adb shell wm size WxH` + `wm density N`, and **always** `wm size reset` + `wm density reset`
  afterwards. Known trouble: content overflows to the right below about 1100dp of width and
  overlaps the bottom navigation below about 500dp of height. `values-h500dp` / `values-h580dp` /
  `values-w1000dp` exist but 15 of their 20 dimens are dead leftovers from QFRadio — the main
  screen still uses hardcoded dp and does not scale.
- ⚠️ `uiautomator dump` returns nothing while the status bar visualizer is running: it waits for the
  UI to go idle and that overlay animates continuously. Disable the widget first or use screenshots.
- 30 locale folders (`values-uk` ... `values-pt-rBR`). Any new user-visible string needs a `values/`
  entry at minimum; some UI arrays (e.g. `SUB_FREQS` in `MainActivity`) are still hardcoded Ukrainian
  strings rather than resources.
- The stock DSP app (`com.qf.soundeffect`) overwrites the same hardware registers. Testing against a
  unit where it can be launched will produce contradictory readings; it is normally disabled with
  `adb shell pm disable com.qf.soundeffect`.
