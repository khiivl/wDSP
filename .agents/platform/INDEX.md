# The QF / K706 platform — what is known

A head unit on this platform is two computers. Android runs on a Unisoc SoC; everything physical —
tuner, sound processor, amplifier, ignition, buttons — belongs to a separate microcontroller that
Android can only talk to over a serial line. Almost every surprising thing about working here
follows from that split.

These notes were gathered across two applications and several months, and **everything in them was
measured on real hardware or read out of the firmware**, because on this platform the
documentation, the framework's own source and the actual behaviour disagree often enough that only
the wire can be trusted.

## 🔴 Правило запису, чинне для будь-якої сесії на цій машині

> Наказ власника, 11.09.2026. Стосується **всіх** агентів однаково — і Claude, і Antigravity/Gemini.

**Кожну знахідку про платформу вносити в це знання ОДРАЗУ, а не наприкінці задачі.** Уточнення,
виміряне число, поведінка, якої немає в документації, карта регістрів, будь-який реверс-інжиніринг —
у файл у момент, коли воно стало відоме.

🔴 **Дозволено і треба обривати поточну задачу заради запису, повертаючись до неї потім.** Незаписана
знахідка живе рівно до кінця контексту сесії, а в агентів із біжучим компактом — і того менше. Ціна
запису — хвилини; ціна втрати — тижні, бо відновлювати доведеться тим самим способом, яким знайшли.

Куди саме:

| що | куди |
|---|---|
| знання **про машину** (MCU, регістри, аудіотракт, гучність, тюнер, екрани) | окремий файл у `platform/`, рядок в `INDEX.md`, і копія в дзеркало — вони тримаються побайтово однаковими |
| знання **про застосунок** | документація відповідного проєкту, не сюди |
| домовленість **між двома застосунками** | `C:\APPS_Contacts\`, і тільки там канон |

⚠️ **Кожен рядок із міткою походження**: `🔬` прочитано у прошивці · `📻` виміряно на дроті ·
`🧩` виведено міркуванням · `❓` не перевірено. Рядок без мітки вважається непідтвердженим.
Змішування «прочитано» з «здається» вже коштувало хибного декодування коду заліза, через яке
апаратам із BD37534 віддавали 24-бітний I2S-профіль.

📌 Неповний запис із чесними `❓` завжди кращий за повний, якого не буде.

⚖️ **Відновлено 12.09.2026 з копії скіла за наказом власника.** Правило зникло з цього файлу разом
із переробкою покажчика вранці 12.09; у дзеркалі `~/.claude/skills/qf-platform/references/INDEX.md`
воно вціліло. Решту вмісту цього файлу не змінювано. ⚠️ Те саме видалення сталося і в покажчику
дерева радіо — там файл побайтово той самий, і правило треба повернути так само.

## Active Repositories on Developer Workstation

All active projects are strictly located in `C:\Users\kosty\AndroidStudioProjects\`:

| Project | Disk Path | Description |
|---|---|---|
| **`kostyamat_fmradio`** | `C:\Users\kosty\AndroidStudioProjects\kostyamat_fmradio` | Main QF FM Radio app repository (`main` branch). |
| **`wDSP`** | `C:\Users\kosty\AndroidStudioProjects\wDSP` | Sound Processor, GALA speed volume, 16-band EQ, wDSP Screensaver, Room Calibration. |
| **`Radio-KM`** | `C:\Users\kosty\AndroidStudioProjects\Radio-KM_any_radio_proxy_for_QF` | System micro-proxy (`com.android.fmradio`, UID 1000) for radio intercept & CAN cluster. |
| **`SmartRDS`** | `C:\Users\kosty\AndroidStudioProjects\SmartRDS` | Background service for geo-enrichment, GPS transmitter matching, OpenRadioFM Supabase. |
| **`QF-system-Radio-app-rebuild`** | `C:\Users\kosty\AndroidStudioProjects\QF-system-Radio-app-rebuild` | Decompiled factory radio app (reference ground truth for MCU protocol). |
| **`Music-KM`** | `C:\Users\kosty\AndroidStudioProjects\Music-KM_any_player_proxy_for_QF` | Media player proxy for QF platform. |
| **`BitPerfectControl`** | `C:\Users\kosty\AndroidStudioProjects\BitPerfectControl` | BitPerfect audio output bypass module. |
| **`DialerKM`** | `C:\Users\kosty\AndroidStudioProjects\DialerKM` | Bluetooth call and audio routing integration. |

## Provenance marks

Used throughout, and they are not decoration:

```
🔬 read in firmware or decompiled code (address, or file and line)
📻 measured on the wire
🧩 inference drawn from those
❓ guess, not verified
```

A guess written down as a fact has cost this project a day more than once. If you add to these
files, mark what you add.

## The files

| file | read it when |
|---|---|
| [01-SYSTEM.md](01-SYSTEM.md) | anything about the Android side: hardware, screens, properties, hidden API, release builds, sleep, and special permissions that read "granted" but do not work (§7) |
| [02-MCU.md](02-MCU.md) | talking to the microcontroller: framing, the command map, send discipline, volume |
| [03-SOUND-PROCESSOR.md](03-SOUND-PROCESSOR.md) | equaliser, delays, crossovers (12 dB/oct), subwoofer handover, fader/balance (0A00-0A05), DVol, Advanced Switch — the ROHM BU32107 register map, MCU code translation and signal path topology |
| [04-FIRMWARE-PATCHING.md](04-FIRMWARE-PATCHING.md) | inside the MCU image: memory map, the dispatcher, the settings structure, and what changing it would take |
| [05-AUDIO-PATH.md](05-AUDIO-PATH.md) | recording, playback, latency, the microphone, audio policies, the player role |
| [06-TUNER.md](06-TUNER.md) | the radio side — mostly relevant to other projects, but several MCU facts live here |
| [07-PRACTICE.md](07-PRACTICE.md) | how to work here without wasting runs: adb traps, testing discipline, what a reboot really resets, and why a permission is never granted from adb (§11) |
| [08-VOLUME-AND-SOURCES.md](08-VOLUME-AND-SOURCES.md) | anything that changes how loud something is: the per-source volume model, source switching, the optional second DSP, and the vendor Bluetooth app breaking the radio |
| [09-NAVIGATION-AND-BITPERFECT.md](09-NAVIGATION-AND-BITPERFECT.md) | why a navigator is inaudible: the firmware's whitelist, how each navigator actually travels, and the one thing BitPerfect changes that causes it |
| [GEMINI_HANDOFF_2026-08-28.md](GEMINI_HANDOFF_2026-08-28.md) | ✍️ *Gemini*, three sessions in one file — kernel/VBC routing, the SC2730 truncation answer, microphone levers, AGDSP, and the HAL traps. Raw transfer, not edited into these notes yet |
| [10-BITPERFECT-MODULE.md](10-BITPERFECT-MODULE.md) | **the map of the module itself** — which file to edit for the microphone, for playback gain, for call sidetone, for policies; what it changes against factory; and the install traps that have already cost time |
| [11-AUDIO-TRACT-AND-TUNER-CHIPS.md](11-AUDIO-TRACT-AND-TUNER-CHIPS.md) | ✍️ *Gemini* — the MCU switching matrix (channels 1–5: AUX, FM, NAVI, MPU, BT_CALL), the two-level volume architecture, and why a TDA7708 at 0.5–0.7 V and an NXP TEF6686 at 1.0–1.2 V make navigation behave differently |
| [12-BLUETOOTH-AUTOCONNECT-AND-FOCUS.md](12-BLUETOOTH-AUTOCONNECT-AND-FOCUS.md) | ✍️ *Gemini* — the 40-second Bluetooth autoconnect poll, and how the vendor's Bluetooth service arbitrates audio focus through `sys.qf.last_audio_src` |
| [13-MCU-FIRMWARE-VARIANTS.md](13-MCU-FIRMWARE-VARIANTS.md) | **before assuming the firmware adapts itself** — it does not: one build per chipset, four decoded, plus the version-suffix decode table, the `0x08003800` load base, and where every image lives |
| [14-LAUNCHER-ICONPACKS-AND-THEMING.md](14-LAUNCHER-ICONPACKS-AND-THEMING.md) | ✍️ *Gemini* — QF launcher icon packs (`/data/QF/.icons`, mode 0777 — writable without root), the `icons.config` mapping, and how to give an app a full-bleed icon instead of the shrunken framed one |
| [15-BU32107-REGISTERS.md](15-BU32107-REGISTERS.md) | ✍️ *Gemini* & *Kostyamat* — complete ROHM BU32107EFV-M register map, MCU translation (`FUN_08004a58`, shadow buffer `0x200000E2`, flusher table `0x0800CFD7`), crossover slopes (12 dB/oct), delay RAM limits, and EQ topology |
| [15-UNISOC-UMS512-ANDROID10-BSP-SOURCES.md](15-UNISOC-UMS512-ANDROID10-BSP-SOURCES.md) | повна архітектура та вихідні коди Android 10 (AOSP + Unisoc SharkL5Pro BSP релізу W21.24.3, HAL whale, hwcomposer v2, u-boot, IDH build) |
| [16-ROHM-BD37534-BD37544-REVERSE-ENGINEERING.md](16-ROHM-BD37534-BD37544-REVERSE-ENGINEERING.md) | повний реверс аналогового звукового процесора ROHM BD37534FV / BD37544FV, декомпіляція прошивки MCU QF05 (011021), розвінчання фейку 16 смуг та затримок, карта I2C регістрів, MCU командний диспетчер, архітектура та хак MCU |
| [17-TSC4745-SI4745-TUNER-REVERSE-ENGINEERING.md](17-TSC4745-SI4745-TUNER-REVERSE-ENGINEERING.md) | дослідження та реверс-інжиніринг FM-тюнера TSC4745 (Silicon Labs Si4745), декомпіляція драйвера в mcu.bin (0x08003800), виявлення відсутності аудіоналаштувань у MCU, фізика деемфазінгу (75µs vs 50µs, зріз -3.5dB), аналіз динамічного Hi-Cut (зріз 8 кГц), карта регістрів AN332 та інженерний план патчу прошивки |

Application-specific design lives outside this folder — for wDSP that is
[../ROOM_CALIBRATION.md](../ROOM_CALIBRATION.md).

## The eleven things most likely to waste a day

1. **`Visualizer(0)` measures silence.** The platform's audio policy puts media on the *fast*
   output while the *primary* one sits idle, and AOSP hard-codes an output-mix effect onto primary.
   Attach to the track's session instead. (05 §1)
2. **The numbers Android reports about latency are wrong here by a factor of three.** Measure.
   (05 §3)
3. **An assistant holds the microphone open at 16 kHz and nothing in the API says so.** Half of
   every recording is missing, and `getSampleRate()` reports what you asked for either way. (05 §2)
4. **The volume you read is not the volume you wrote.** It lags, and under music it can differ
   permanently. Keep "sent" and "reported" apart or an algorithm will read its own echo as a
   person. (02 §7)
5. **Rebooting Android does not reset the MCU.** The tuner keeps playing, buffers keep their
   contents. Only power does. (07 §4)
6. **R8 silently deletes hidden-API calls.** Debug works, signed release does nothing.
   (01 §5)
7. **`am broadcast` without an explicit receiver goes nowhere and reports success.** (07 §2)
8. **Volume is per source, not per Android stream, and the live values are throwaway properties.**
   An unset `sys.radio.vol` reads back as `persist.sys.radio_volume` on every call — that *is* the
   "volume reset itself" bug, there is no reset code. And a volume only reaches the hardware when
   its source is the current one. (08 §1)
9. **The MCU firmware does not adapt to the board — it is compiled for it.** Four chipsets, four
   different binaries; builds of the same date share 16 % of their bytes. Looking for a runtime
   branch on the tuner or the audio chip is wasted effort. The archive filename already names the
   chipset. (13)
10. **Half the fleet has a second DSP and half does not, and one character decides which.** The
   platform reads the second character of the MCU code: 2 → AK7738, 3 → AK7604, anything else →
   none. Units with one re-push the master volume on every source change; units without one never
   do. Check it before reproducing, explaining or promising anything about volume. (08 §3)
11. **`input keyevent 24/25` cannot test anything volume-related here.** They move `STREAM_MUSIC`
    inside Android; the platform never learns of it and never broadcasts
    `com.qf.action.VOLUME_CHANGED`, so every listener stays silent while the volume visibly
    changes — a convincing false negative. The physical encoder's own codes are **293 / 294**
    (what `hid_daemon.sh` feeds to `input keyevent`), and with them a knob turn can be reproduced
    from adb without the owner present. (08 §2)

⚖️ Пункти 8–11 відновлено 12.09.2026 з копії скіла за наказом власника: вони зникли разом із
переробкою покажчика вранці 12.09, і всі чотири стосуються гучності та ідентифікації заліза —
рівно того, чим два дні розплутували заморожену гучність. Решту списку не змінювано.

## Diagnostics available at runtime in wDSP

None of these run unless asked for; each logs under its own tag.

```bash
adb shell am broadcast -a com.radiorubka.wdsp.PROBE_SESSION  --ei sid -1      # which session carries audio
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_LATENCY --ei mic 1      # picture-to-sound delay
adb shell am broadcast -a com.radiorubka.wdsp.PROBE_MIC       --ei src 6      # what the microphone really delivers
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM    --ef amp 0.25   # sweep every speaker
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM    --ei same 1     # same routing four times: checks the instrument
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM    --ei delaytest 1  # positional delay line
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM    --ei delaytest 2  # surround delay line
adb shell am broadcast -a com.radiorubka.wdsp.SET_VOLUME      --ei vol 12     # move the volume the way a person does
adb shell am broadcast -a com.radiorubka.wdsp.SIMULATE_SPEED  --ef speed 110  # pretend to be driving
```

`MEASURE_ROOM` leaves its recordings and a readable report in the app's external files directory,
so a measurement made in somebody else's car can be examined rather than described. Settings has a
button that zips the lot into the system share sheet.
