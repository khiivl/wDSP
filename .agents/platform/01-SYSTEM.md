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

➡️ **Write `values/` and `layout/` for the tightest case and add the luxuries in qualifiers.** Then
an unknown panel gets a layout that fits rather than one that falls apart. Android merges resource
sets per key, so the axes stay independent.

Emulation is allowed and is the only honest way to check: `wm size 1600x480` / `wm density 160`,
then `wm size reset` and `wm density reset` — always both, always after every run.

## 3. Properties: `sys.*` is now, `persist.*` is settings

Confusing the two has cost real bugs — reading `persist.sys.qf.last_audio_src` (which said
`nothing`) instead of the live `sys.qf.last_audio_src` made a guard fire against nobody.

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

Related: [07-PRACTICE.md](07-PRACTICE.md) for how to work with all of this without wasting runs.
