# wDSP "Bulldozer" mod by kostyamat — 1.1

An equaliser, cabin-measurement and visualiser app for **QF / K706** Android head units with the
ROHM **BU32107** sound processor. It drives the processor through the unit's MCU.

This branch (`kostyfmat_mod`) is **Kostiantyn Matviyevskyy's modification** of
[wDSP by Volodymyr Chebanenko (khiivl)](https://github.com/khiivl/wDSP). It contains all of the
author's work up to his version 1.0.2 ("Bulldozer"); the author's own app and description are on
the [`master` branch](https://github.com/khiivl/wDSP/tree/master).

----------------------------------------------------------------------------------------------------

## Manuals

A manual for every function, in nine languages. Download the file and open it in any browser; no
internet is needed. The same files are in the release archive.

| | | |
|---|---|---|
| [English](docs/manual_en.html) | [Українська](docs/manual_uk.html) | [Español](docs/manual_es.html) |
| [Deutsch](docs/manual_de.html) | [Français](docs/manual_fr.html) | [Italiano](docs/manual_it.html) |
| [Português](docs/manual_pt.html) | [Polski](docs/manual_pl.html) | [Русский](docs/manual_ru.html) |

Release notes for 1.1: [docs/release_notes/](docs/release_notes/). Screensaver gesture map:
[docs/screensaver_map.svg](docs/screensaver_map.svg).

----------------------------------------------------------------------------------------------------

## How the mod differs from the original

- **It measures the car.** The original is tuned by ear: you set the equaliser, crossovers and
  delays yourself, and its spectrum shows the music being played. The mod also plays test sweeps
  through the speakers, records them with the unit's microphone, and builds a preset, the delays
  and a list of problems from what it heard.
- **Its spectrum can listen to the cabin**, through the microphone, not only show the signal.
- **It looks after the unit's sound beyond the equaliser**: the volume of each source, calls (the
  microphone is released, the call preset applied), resuming the player after a restart or sleep,
  and a full-screen screensaver with playback controls.
- **Both interfaces.** The author's interface is kept as "Classic", next to the mod's "Modern".
- Root is optional; with it the mod gets the microphone before other apps.

## What else the mod adds

- **Cabin measurement report** that starts with problems in plain words (a weak speaker, a
  tweeter in the wrong polarity, swapped channels, a subwoofer playing against the doors) and
  only then corrects what the equaliser can. A control sweep after applying shows whether it
  worked.
- **Microphone calibration** for the built-in microphone.
- **Spectrum** in three modes: Off, Music (what the preset makes of the music), Microphone (what
  the cabin actually sounds like).
- **Screensaver gestures**: track and play/pause zones, a cover that opens the playing player,
  volume and display settings by swiping.
- **Loudness** that follows the real volume steps of the unit, measured by the app.
- **Diagnostics**: system report, screen topology, measurement archive.
- **30 interface languages.**

----------------------------------------------------------------------------------------------------

## Installing

1. Install the APK from the release archive. Open wDSP once, grant the permissions it asks for, and
   add it to the unit's sleep whitelist (the app opens it: Settings → "Let wDSP stay alive through
   sleep").
2. The mod is signed with its own key, not the author's. Android does not install one over the
   other: to switch from the author's build to the mod (or back), save your presets with the
   app's backup, uninstall the other build, then install.
3. Disabling the stock DSP app is recommended, since it writes the same processor registers:
   `adb shell pm disable com.qf.soundeffect` (back: `adb shell pm enable com.qf.soundeffect`).
4. Root (optional) gives the microphone priority over other apps. With root and the
   [PMPatch3](https://github.com/vova7878-modules/PMPatch/releases) Magisk module, the wDSP proxy
   (`wdsp_proxy`) can be installed as an update of the stock DSP app, so the quick-settings DSP
   button opens wDSP.

----------------------------------------------------------------------------------------------------

## Reports

Measurements (the archive saved after a cabin measurement plus the system report), impressions of
the presets, and bugs of this mod: [discussion of the mod](https://t.me/kostyamat_dev/92). Both
files are saved to `Download/wDSP` on the unit.

The original project, its author and its users: [wDSP Telegram group](https://t.me/wDSPapp),
[GitHub](https://github.com/khiivl/wDSP).

----------------------------------------------------------------------------------------------------

## Building

```bash
./gradlew :wdsp_app:assembleRelease
```

Gradle and the JDK are provisioned by the wrapper; see [.agents/BUILDING.md](.agents/BUILDING.md).
Java, `targetSdk 29` on purpose (the QF framework behaves as Android 10); the MCU protocol was
reverse-engineered and is reached through the framework service by reflection.

----------------------------------------------------------------------------------------------------

## Support

The same two blocks as at the top of the app's Settings, in the same order: the original project
first, then this mod.

**wDSP — the original project.** This build is a mod based on it. The base, and the thanks for it,
are the author's.

- GitHub — wDSP discussion: <https://github.com/khiivl/wDSP>
- Telegram group: <https://t.me/wDSPapp>
- Buy me a coffee: <https://buymeacoffee.com/radiorubka>
- PayPal: <https://paypal.me/wDSPApp>

**wDSP kostyamat mod.** This build. It is free: no licences, no unlocks — only voluntary thanks.

- Discussion of the mod: <https://t.me/kostyamat_dev/92>
- Buy me a coffee: <https://t.me/kostyamat_dev/10>
- PayPal: <https://www.paypal.com/paypalme/kostyamat>
- Revolut: <https://revolut.me/kostyamat>
- Crypto — TRC-20 (Tron): `TCSvCEyX1Zxb9E97oTpxHsBCLiDZLPTvzk`
- Crypto — TON (Telegram wallet): `UQCqracCOo_DEwVWQjPVYkKak-gSZx1TXQOxICi_9frpdr5j`

----------------------------------------------------------------------------------------------------

## Credits and license

To **Volodymyr Chebanenko (khiivl)**, the author of wDSP: for the application this mod is built on,
the Classic interface, version 1.0, and his knowledge of sound. To the testers who send
measurements and reports.

The app is licensed under **GPLv3**: whoever improves it and shares it shares the source too.
License rights belong to Volodymyr Chebanenko, the creator and maintainer of wDSP.
