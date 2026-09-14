# The head unit as a computer

What the machine is, what is true of it that is not true of an ordinary Android device, and which
of those things will cost you a day if you assume otherwise.

## 1. The hardware

Measured with `getprop` and `dumpsys SurfaceFlinger` on a real unit — not read from a spec sheet:

```
ro.board.platform   ums512            Unisoc
ro.product.board    uis7862s_1h10
ro.hardware.egl     mali
GPU                 Mali-G52, OpenGL ES 3.2
Android             10
```

Variants in the wild run UIS7862, UIS7862S and the weaker UIS8581. **They are not flagships but
they are not slow, and the GPU is real.** An architectural argument of the form "too expensive for
this CPU" or "scaling a bitmap will stutter here" is very likely wrong, and has been wrong before:
a whole set of design decisions once got built on an invented premise of "Cortex-A53, no GPU".

Two processors, not one. **The MCU is a separate ARM Cortex-M with its own power** — see
[02-MCU.md](02-MCU.md). Android is only its conversational partner over a serial line.

### Where the sources for all of this actually are

✍️ *Gemini, 11.09.2026.* Four layers sit under the screen — Linux 4.14 + u-boot, AOSP 10
(`android-10.0.0_r41`), the Unisoc IDH BSP, and QF's own car layer — and the middle two are public.

🔬 The Unisoc IDH BSP release **`W21.24.3`** (Android 10 Q, SDK 29) for SharkL5Pro is on GitHub as
**[jingpad-bsp](https://github.com/jingpad-bsp)**, 421 repositories, JingPad A1 being the same
UMS512. The ones worth knowing by name:

| what | repository |
|---|---|
| device tree (`ums512_1h10`, `_2h10`, `_20c10`) | `device_sprd_sharkl5pro`, `bsp_device_sharkl5pro` |
| **Whale audio HAL** — `audio_hw.c`, `fm.c`, `audio_offload.c`, `agdsp.c` | `vendor_sprd_modules_audio` |
| display: DPU and HWComposer v1/v2 | `vendor_sprd_modules_dpu`, `vendor_sprd_modules_hwcomposer` |
| broadcast radio HIDL 2.0, RIL | `vendor_sprd_interfaces_broadcastradio`, `vendor_sprd_modules_radiointeractor` |
| bootloader | `bsp_bootloader_u-boot15`, `iscle/ums512_chipram` |
| PAC packaging (`build_pac.sh`, `mkpac.pl`) | `vendor_sprd_release_IDH_Script` |

A monolithic tarball of the lot exists as a torrent in `qwqlemon2333/source_code_for_jingpad`;
community trees live under `sprd-oss-devs`, and the kernel at `iscle/android_kernel_unisoc_ums512`.
Local copies on this machine: `D:\UIS_android\` (kernel, hardware trees, and the decompiled
`qf_platform_framework` — `McuManagerService`, `BackCarService`, `QF_Framework.apk`).

🧩 Why it matters: the audio HAL that decides where a stream goes, and the `fm.c` that carries the
tuner's analogue path, are both readable C rather than something to be inferred from behaviour. When
a question is "what does the platform do with this stream", there is a file that answers it.

## 2. Screens: 132 panels, and the base layout must be the tightest one

The factory panel table lists 132 models. What matters:

- most MIPI panels are physically portrait (`720x1280`) because they are phone matrices; the head
  unit rotates them with `persist.sys.qf.sf.hwrotation=90`. LVDS panels are already landscape;
- **density is only ever 160 or 320**, so in dp every 2K panel collapses onto the same numbers as
  an ordinary one: `1200x1920 @320` = `600x960dp` = the same group as `1024x600`;
- commonest geometries: **1280×720** (43 models), **1024×600** (18), **1920×720** (16). Heights of
  480 exist and are appearing in *new* hardware (`1600×480`);
- Tesla-style portrait units give a working area of about 600×440dp — the top is an icon bar and
  the bottom is climate control;
- **split-screen is stock Android 10 here and gives 640dp to any unit.** A narrow width is not an
  exotic panel, it is a mode every machine has.

🔴 *(owner, 11.09.2026)* **There are no real vertical screens on this platform.** Even the Tesla-style
units are pseudo-vertical: Android is given the strip between the top icon bar and the climate panel,
which is landscape (≈600×440dp). The one place a portrait-shaped *window* appears is split screen
(640×648dp). So a layout chosen by **orientation** (`layout-port`) is the wrong axis: it fires for
split screen and square emulation, never for a real portrait panel. Build from the **window size
WindowManager gives the app** (`screenWidthDp` / `screenHeightDp`, width/height qualifiers), not from
the panel's resolution props and not from orientation. 📻 wDSP's `layout-port` copy had drifted 37
ids behind the landscape one and showed four tabs empty in split screen (uiautomator audit,
11.09.2026).

➡️ **Write `values/` and `layout/` for the tightest case and add the luxuries in qualifiers.** Then
an unknown panel gets a layout that fits rather than one that falls apart. Android merges resource
sets per key, so the axes stay independent.

Emulation is allowed and is the only honest way to check: `wm size 1600x480` / `wm density 160`,
then `wm size reset` and `wm density reset` — always both, always after every run.

📻 *(11.09.2026)* On the 1280×720 panel `wm size 1024x600` renders the UI at 1024×600 and the
compositor scales it up with black bars at the sides — `screencap` returns **1228×720**, not 1024×600.
The layout is the emulated one; just do not read pixel sizes off the screenshot as dp.
⚠️ That is the only case `screencap` gets right. With a **different height** (`1280x480`) it
returns 1280×480 taken from the top of the panel buffer, where the UI sits ~120 px lower — the
bottom of the screen, navigation included, is cut off. With a size **larger than the panel**
(`1920x1200` @320, `1200x1200` @320) the picture is cropped. Same height (`640x720`) is honest.
For the rest, capture from inside the app or emulate the same dp at density 160
(`960x600`, `600x440`) — the radio session's method is being asked for (11.09.2026). Run the whole
emulation as one script **on the unit**, detached (`nohup sh … &`), with the reset in a `trap … EXIT`:
if the session on the PC dies mid-run, the owner's screen still comes back.

## 3. Properties: `sys.*` is now, `persist.*` is settings

Confusing the two has cost real bugs — reading `persist.sys.qf.last_audio_src` (which said
`nothing`) instead of the live `sys.qf.last_audio_src` made a guard fire against nobody.

📻 **A `persist.` property set by `resetprop` is not persistent.** Magisk's `resetprop` writes the
property in memory only; without `-p` it never reaches `/data/property/`, so the value is gone at
the next boot. Measured 27.08.2026 on a unit where a module sets two of them at every boot:

```
persist.qf.arm.default.volume   live = 15   /data/property/… = no such file
persist.sys.main_volume         live = 1    /data/property/… = no such file
```

That is why the BitPerfect module re-sets its properties in a loop on every boot rather than once —
and why a "persistent" value written by a module quietly disappears after a reboot while a value
written by `setprop` from a system context stays. Two consequences worth holding on to:

- a module's damage to a `persist.` property **heals itself at the next boot**, which is good;
- and a module's *fix* to one does too, which is not. Anything a module needs to survive a reboot
  has to be re-applied at every boot, or written with `-p`.

📻 **A live property says who owns the path, not who is alive.** Measured 26.08.2026 with the radio
application's process **dead** — no `pidof`, nothing running:

```
sys.qf.sound.channel = 2      sys.current.vol.type = radio_type      sys.radio.vol = 4
```

Channel 2 survived the death of the process that took it. So `channel == 2` is evidence that the
tuner owns the MCU mixer input, and **not** evidence that the radio is playing, or even installed
and running. Any guard shaped like "channel 2, therefore the radio is active" reads as true from
the moment a radio crashes until somebody switches source by hand.

The same family as `last_audio_src` above, and the same correction: these properties describe the
state of the path, not the liveness of an application. Where liveness matters, measure the signal —
in wDSP that is `AudioSpectrumEngine.hasSignalNow()`, which vetoes the tuner outright when real PCM
is moving.

| property | meaning |
|---|---|
| `sys.qf.last_audio_src` | **the package that owns audio right now** — written by the platform's focus control |
| `sys.qf.sound.channel` | the MCU mixer input: **2 = tuner, 4 = MPU** (everything else). 4 is the factory default |
| `sys.qf.radio.status` | "the radio is active" |
| `persist.sys.qf.mcu.version` | firmware version — **and the chip identity, see [02-MCU.md](02-MCU.md)** |
| `persist.sys.qf.sf.hwrotation` | panel rotation |
| `persist.sys.qf.bt_auto_connect` | Bluetooth auto-connect; a source of audio-focus storms |
| `sys.qf.is.acc.on` | ignition, set from the MCU's own frame |

**An ordinary app can write `sys.*` here.** `getenforce` returns **Permissive** on every unit of
this platform — the vendor ships it that way and Enforcing QF units do not exist in the field. So
`SystemProperties.set()` works, and you should not build fallbacks for a case that never happens.

⚠️ `adb shell` runs as **root** on these units, so `setprop` from the console proves nothing about
what an app can do. Test from code.

## 4. Hidden API: read the interface, don't guess the transaction

Everything interesting on this platform lives in `android.qf.*`, which is on the bootclasspath and
therefore inside `framework.jar`, not in any APK you can find. The recipe takes five minutes:

```bash
adb shell service list                       # names the service AND its interface
MSYS_NO_PATHCONV=1 adb pull /system/framework/framework.jar .
jadx -d out --no-res -q --single-class android.qf.util.IUtilEventManager framework.jar
```

`service list` prints, for example, `util_service: [android.qf.util.IUtilEventManager]` — that one
line is the entry point. `--single-class` takes seconds instead of decompiling the whole jar.

Then either add the signature to a `compileOnly` stub module, or implement the `Binder` by hand and
read only the fields you need out of the `Parcel`.

Useful decompilation targets, all obtainable without root:

```
/system/framework/framework.jar          android.qf.* interfaces
/system/framework/services.jar           MediaFocusControl, AudioService (QF-modified!)
/system/priv-app/QF_Framework/           McuManagerService - the serial gateway
/system/priv-app/QF_FMRadioExt/          the factory radio app
```

## 5. Two build traps that only bite in release

🔴 **R8 deletes hidden-API calls.** Reflection and `compileOnly` stubs look side-effect-free to the
optimiser, so it removes them. The debug build works, the signed release does nothing at all, and
the failure is silent. This cost days once. `minifyEnabled false` and `shrinkResources false` in
release are deliberate. Turning R8 back on means a complete `-keep` set for every reflected class
*and* a live test on hardware — not just a build that compiles.

🔴 **`targetSdk 29` is deliberate.** Above it the hidden-API blocklist gets stricter.

A third, smaller one: this platform is a fork magnet, and forked projects carry dead resources that
reference each other, so a grep says "used" while nothing reaches them from the live layout.

## 6. Sleep is not Doze

**When the head unit sleeps, Android stops completely.** No Wi-Fi, no adb, no network, 15 mA.

- ⛔ nothing can be installed, dumped or measured while it sleeps — it is not slow to answer, it is
  absent. Retrying `adb connect` is pointless;
- ✅ the only thing that works is a watcher that waits for the device to appear and grabs the
  logcat buffer in the first seconds, before `mcu_services` scrolls the wake-up out of it;
- ✅ **a person wakes it, with the ignition.**

The chain is visible on the wire: MCU frame `0x24 01` → `ACC_OFF`, `0x24 00` → `ACC_ON`, and on the
Android side `QFSleepWakeup.start()` plus a broadcast.

📻 **Who survives it, and who is brought back** — read on the owner's Haiwai unit, 14.09.2026:

| file | what it holds | effect |
|---|---|---|
| `/system/config/RestoreAppsWhenWakeup.ini` | exactly five factory apps: `com.qf.musicplayer`, `com.qf.videoplayer`, `com.android.fmradio`, `com.android.fmradio.ext`, `com.zjinnova.zlink` | the only players the platform restarts after sleep |
| `/system/config/NotKillAppsBeforeSleep.ini` | system services, vendor apps, Google services — no third-party player | everything else is a candidate for being killed |
| `/great/sleep/sleep_whitelist` (system, 0600) | `com.navioverlay.car`, `com.radiorubka.wdsp`, `com.huautobrightness.controller`, `com.kostyamat.fmradio` | why wDSP and the radio keep their process through sleep on this unit |

Measured the same night: YouTube Music, playing before a 22-minute sleep, came back with a new pid
started after wake — killed, not frozen — while wDSP kept its pid. Why third-party players are not
restored, read from firmware by the Gemini session `60ce423d-…` (`QFSleepWakeup.storeAppBeforeSleep`,
`killAppsBeforeSleep`) and not re-read here: a package not in `RestoreAppsWhenWakeup.ini` is stored
as `nothing`. ❓ The property that document names, `persist.sys.qf.last_src_before_sleep`, does not
appear in `getprop` on this unit.

Related: [07-PRACTICE.md](07-PRACTICE.md) for how to work with all of this without wasting runs.

## 7. A special permission can read "granted" and not work

📻 *(owner, field, 11.09.2026)* **The platform withdraws permissions on its own, without any
notice**, and they have to be confirmed again — notification access is the named example, and "any
permission" in the owner's words. Nothing tells the app or the person that it happened.

📻 *(owner, field, 11.09.2026)* The overlay permission — `SYSTEM_ALERT_WINDOW`, "display over other
apps" — also **goes stale** without being withdrawn: Settings shows it switched on, and overlays still
do not appear. Switching it off and on again in Settings brings it back. Seen on more than one unit;
not a one-off.

🔴 Rule that follows *(owner, 11.09.2026)*: a permission screen must read the **real** state every
time it is shown, never a remembered one, and must lead the person to the switch **even for an item
it believes is granted** — the only remedy for a stale grant is the off/on toggle, and a green card
that refuses to be tapped hides it.

🧩 Consequence for any permission screen: an item painted green from the API is not proof that the
thing works. `Settings.canDrawOverlays()` reads the recorded grant, so a stale grant is expected to
come back `true` (❓ not measured in the stale state yet). The only honest check for an overlay is
the attempt itself — add the window, catch the failure — and the honest remedy to offer the person
is the toggle, not "grant it".

📻 *(11.09.2026, owner's unit)* **The platform grants the overlay to every app at install.** Of 15
third-party packages that request `SYSTEM_ALERT_WINDOW` — Telegram, Waze, MX Player, Poweramp and the
rest, none of them ever touched by an agent — every one has `SYSTEM_ALERT_WINDOW: granted=true` under
*install permissions*, with the app-op left at `default`. 🧩 `canDrawOverlays()` is true when the
app-op is `allow`, or when it is `default` and the permission is granted — so on QF it is true for
everybody out of the box, and a permission screen's overlay item is green before the person has done
anything. What can take it away is the Settings toggle, which writes the app-op (❓ whether that is
what the "stale" state above is). In stock AOSP 10 an ordinary app does not get this permission (it is
`development`-level there — 🧩 from the source, ❓ not re-read), so this is a QF modification.

📻 *(11.09.2026 18:29, owner's unit)* **Runtime permissions are granted at install, with no dialog.**
wDSP (targetSdk 29) was uninstalled, its leftovers removed, and installed with plain `pm install` —
no `-g`. Straight after: `ACCESS_FINE_LOCATION`, `RECORD_AUDIO` and `ACCESS_BACKGROUND_LOCATION` all
`granted=true`, app-ops "No operations", nobody had touched the screen. On stock Android 10 a
dangerous permission is never granted by an install without `-g`. ❓ Whether the same happens
through the ordinary package installer a tester uses, and which component does it — not checked.
Consequence: on this platform an app's runtime-permission request may never show a dialog at all,
and "the person granted it" cannot be assumed from `granted=true`.

📻 *(same run)* **Uninstalling does not take everything with it.** After `adb uninstall`, the app's
entry was still in `enabled_notification_listeners`, and Magisk still held `policy=2` (allow) for its
old uid. Here the reinstall got a new uid (10404 → 10405), but a reused uid would have inherited root
silently, and a listener entry naming the same component inherits notification access. For a clean
test both have to be removed by hand (`cmd notification disallow_listener …`,
`magisk --sqlite "DELETE FROM policies WHERE uid=…"`) — removing, never granting.

⚠️ Two wrong readings of this were made here within one hour, and corrected — worth knowing so nobody
repeats them: a `rejectTime` in `cmd appops get` is **not** a refusal (it is logged for any mode other
than `allow`, even when the window is then admitted through the permission), and `default` +
`granted=true` is **not** an adb fingerprint — it is the platform's normal state.

📻 *(13.09.2026, owner's unit, measured on two apps and then on ours)* **Storage behaves the same way
— and the permission alone is only half of the request.**

The owner's statement that started this: *«на цій системі доступ дає автоматично, якщо правильно
сформований його запит в маніфесті»*. Both halves check out, and the second half is the one that is
easy to get wrong.

Evidence, from `dumpsys package` on two apps with **identical** `minSdk 29 / targetSdk 29`:

| | `com.kostyamat.fmradio` (radio) | `com.radiorubka.wdsp` before |
|---|---|---|
| `PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE` | present | **absent** |
| `READ/WRITE_EXTERNAL_STORAGE` | `granted=true` | not declared at all |
| flags on the grant | `RESTRICTION_INSTALLER_EXEMPT`, **no `USER_SET`** | — |

No `USER_SET` among the flags means nobody ever tapped a dialog, and `RESTRICTION_INSTALLER_EXEMPT`
is the soft-restriction exemption, which is an **installer-side** decision rather than a person's.

🔬 **Why the permission by itself is not enough.** An app targeting API 29 gets scoped storage by
default. Under it a raw `/sdcard/...` path is not the app's to open **no matter which app created
the file** — ownership lives in the MediaStore row, not in the folder. `requestLegacyExternalStorage`
on `<application>` is what turns that off; the radio carries it, which is why the radio's raw-path
code works and ours did not. ⚠️ The flag is honoured up to Android 10 only, and every unit in this
family is Android 10 — on anything newer this stops working and the MediaStore road is the only one.

📻 **Before and after on our own app, same command both times.** `ACTION_BACKUP` with
`--es path /sdcard/Download/wDSP/prefs_probe.json`: refused before the manifest change (and reported
as a *dialog* that stayed on screen), wrote 24 495 bytes after it. The grant arrived on a plain
`adb install -r`, with no dialog and no `-g`.

🧩 Rule that follows: the app may **use** this, and must not **depend** on it. Every user-facing file
path stays on the MediaStore or the system picker, both of which need no permission, so a unit whose
firmware grants nothing behaves as before. A raw path may never become the only road to a file.

❓ Not established: which other special grants behave the same (notification listener, battery
optimisation, `su` policies), and what makes one stale — reinstall over another signature, a
restore, a firmware update, or time.

---

## 8. The boot timeline, and why `BOOT_COMPLETED` is the wrong trigger for hardware

📻 *(measured on the unit by the Antigravity/Gemini session; relayed by the owner 13.09.2026.
Unisoc UIS7862, MCU APM32, Android 10. Times are from ignition.)*

| t | what happens |
|---|---|
| 0.00 s | ACC applied: the MCU powers the SoC through the PMIC. SPL → U-Boot → kernel 4.14 → init; Magisk mounts its overlays at `post-fs-data` |
| +17.99 s | `boot_progress_start` — Zygote, `system_server` |
| +27.91 s | `boot_progress_ams_ready` |
| +30.60 s | `boot_progress_enable_screen` |
| +32.60 s | `framework_locked_boot_completed` → `LOCKED_BOOT_COMPLETED`. **Only device-protected storage exists; ordinary SharedPreferences do not** |
| +32.79 s | ⚠️ `QFSleepWakeupThread.initState()` **resets `STREAM_MUSIC` to 9** (`persist.qf.arm.default.volume`) |
| +33.18 s | `framework_boot_completed` — user 0 unlocked, storage decrypted |
| +33.19 s | `android.intent.action.BOOT_COMPLETED` |
| +33.50 s | 🔌 **the UART opens** — `QFInitServer` / `McuManagerService` bring up `/dev/ttyS*` at 115200 |
| +34.20 s | 📻 MCU frame `0x24 00` (ACC ON) arrives over that UART |
| +34.25 s | 📡 `com.qf.action.ACC_ON` broadcast by `DefaultMcuStateListener` |
| +35.00 s | `CarSettingService` starts its timers and the 2-second `update_battery_power` broadcasts |

🔴 **Three consequences, and the first one is a trap this project is standing in.**

1. **`BOOT_COMPLETED` arrives about a second BEFORE anything can reach the MCU.** A command sent on
   that broadcast goes into a UART that is not open yet. The correct trigger for touching hardware
   is **`com.qf.action.ACC_ON`**, at +34.25 s.
   ⚠️ And the failure is silent in the worst possible way if the sender de-duplicates: a frame that
   was "sent" before the link existed can be recorded as sent and never repeated. Anything caching
   what it wrote to the MCU **must drop that cache on `ACC_ON`**, or the first write of the session
   is the one that is lost and nothing ever notices.
2. **Direct Boot, before +33.18 s**: `SharedPreferences` are empty or the app crashes on them. Early
   state has to come from `DeviceProtectedStorage`, from SQLite, or from `persist.sys.qf.*`.
3. **The platform resets music volume to 9 at +32.79 s**, from `persist.qf.arm.default.volume`, in
   `QFSleepWakeupThread`. Anything that wants a different level after a boot has to restore it in
   the window after that — the BitPerfect daemon does it between roughly 33 s and 40 s. This is the
   boot-time twin of the volume behaviour in `08-VOLUME-AND-SOURCES.md`.

🧩 Trust note: relayed rather than re-measured here. The times are one unit's, and the ordering is
what matters — `BOOT_COMPLETED` before UART before `ACC_ON` — not the exact seconds.
