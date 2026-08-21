# Volume, source switching, and the second DSP

Read this before touching anything that makes sound get louder, quieter, or briefly disappear.

Almost every "the volume did something strange" report on this platform comes back to three
mechanisms that are not Android's, are not documented, and are not the same on every unit. Two
units running what looks like the same firmware can behave completely differently, and this file
is mostly about *why*, because the answer decides whether a bug report is even about your code.

Sources: the decompiled platform framework — `android.qf.os.VolumeState`,
`android.qf.os.VolumeManager`, `android.qf.os.QFAudioService`,
`com.qf.framework.volume.AK7738VolumeManager`, `com.qf.framework.mcu.McuManagerService` — and the
vendor Bluetooth app `com.qf.bluetooth` (`/system/priv-app/QF_Bluetooth/QF_Bluetooth.apk`).
Everything marked 🔬 was read there; 📻 was measured on a unit.

---

## 1. The platform does not use Android's volume model

🔬 There is one `VolumeState` per **source**, not per stream. Four of them, built at boot by
`VolumeManager.initVolumeManager`:

| type | live value | mute flag | default when the live value is unset |
|---|---|---|---|
| `media_type` | `sys.media.vol` | `sys.media.mute.state` | `persist.sys.main_volume` (12) |
| `radio_type` | `sys.radio.vol` | `sys.radio.mute.state` | `persist.sys.radio_volume` (12) |
| `btcall_type` | `sys.call.vol` | `sys.call.mute.state` | `persist.sys.phone_volume` (12) |
| `aux_type` | `sys.aux.vol` | `sys.aux.mute.state` | `persist.sys.aux_volume` (12) |

Which one is live is `sys.current.vol.type`. There is also a **single global** mute flag,
`sys.mute.state`, shared by all four — the per-source ones above are passed to the constructor and
then, in this framework version, never read.

Three consequences, and they cause most of the reports:

**The live values are not persistent properties.** `sys.*.vol` — no `persist.` prefix. They do not
survive a power cycle, and they can simply not exist. 🔬 `getVolumeVal()` is
`SystemProperties.getInt(propSave, defVol)` evaluated *on every call*, so a property that was never
written reads back as the factory default, silently and forever. **A volume that "resets to the
factory value" is a property that stopped existing** — that is the whole mechanism, there is no
reset code anywhere.

**A volume only reaches the hardware if its type is the current one.** 🔬 `setVolumeVal(i)` always
writes the property, but only calls `RPC_SetVolume` when `volType.equals(sys.current.vol.type)`.
So an app that changes the volume while the platform thinks another source is live writes a
property that nothing acts on — and, worse, leaves the property of the source it *meant* to change
untouched, which sets up the previous paragraph.

**`resetDefValIfNeed(i)` is a blunt instrument.** 🔬 It walks all four states and sets every one
whose value equals `i` to `persist.sys.main_volume`. Not "the one you asked for" — every one that
happens to be at that number.

### The 0..32 to 0..15 rescale

🔬 `changeVolForBtA2dpVol` only runs when `persist.sys.double_bt` is true. It then mirrors the
hardware volume onto Android's `STREAM_MUSIC` as `i * 15 / 32` — and as `i * 11 / 32` on the
`cayenne_zh_2000x1200` product. 🧩 On a unit where that property is set, Android's own volume is a
*consequence* of the hardware volume, so anything that writes `STREAM_MUSIC` directly is fighting
the platform and will be overwritten at the next source change.

---

## 2. Source switching: `RPC_SetChannel`

🔬 Everything above is driven from one method in the MCU service.

| channel | volume type it selects |
|---|---|
| 1 | `aux_type` |
| 2 | `radio_type` |
| 4 | `media_type` |
| 5 | `btcall_type` |
| other | unchanged |

It sets `sys.current.vol.type`, then — **only if `sys.mute.state` is false** — reconfigures the
second DSP and re-pushes the volume, then sends the channel to the MCU and broadcasts
`com.qf.action.VOLUME_CHANGED` with `EXTRA_VOLUME_VALUE`.

🔴 That mute guard is a landmine. If the global mute flag happens to be true at the moment the
source changes, the DSP keeps the *previous* source's mixer configuration and the *previous*
volume. Nothing re-applies it later.

📻 The broadcast is useful: it marks the exact instant a source changed and carries the value that
was pushed. wDSP's diagnostic timeline listens for it.

---

## 3. The second DSP, and why two identical units behave differently

Some units carry an AKM audio hub — an **AK7738** or an **AK7604** — between the MCU and the sound
processor. On those, the radio and the second Bluetooth module are **analogue** inputs of the hub
and Android is the **digital** one, and the MCU cross-fades between them.

### How to tell, without probing

🔬 The platform decides from the **MCU version string**: take the part after the last dot, require
at least six characters, and look at the **second character**. `2` → AK7738, `3` → AK7604,
anything else → no hub. A letter continues from 9 (`c - 'a' + 10`).

```
QF05.V02.13.20251124.002121  ->  "002121"  ->  '0'  ->  no hub
QF05.V02.13.2025xxxx.0x2xxx  ->  '2'       ->  AK7738
```

📻 Confirmed on a hubless unit: code `002121`, and `/sys/ak7738/pm_suspend` does not exist.
`/sys/ak7738/pm_suspend` and `/sys/ak7604/pm_suspend` are world-readable on units that have one, so
they are a free second opinion — the framework itself reads the first to decide whether the hub is
powered.

🔴 **This single character decides whether a whole class of bugs is possible.** Reproduce nothing,
explain nothing, and promise nothing across that line without checking it first.

### What the hub does on every source change

🔬 `setAK7738AdcMode(channel)`:

| channel | mixer ratio (analogue, digital) | ADC mode |
|---|---|---|
| 1, 2 — AUX, radio | `setMixerRatio(10, 0)` | 0 or 2 |
| 4, 5 — media, BT call | `setMixerRatio(0, 10)` | 1 |

and then `setAK7738Volume(type, getVolumeVal())` → `DspJni.setMainVolume(...)`.

🧩 So on a hub unit the master volume is **re-pushed from the property on every source change**,
which is exactly what turns §1's "the property does not exist" from harmless into audible. On a
hubless unit nothing re-pushes it, which is why the same firmware misbehaves on one and not the
other.

### The mute that is never lifted

🔬 `handleRadioDatas` mutes the hub on radio activity, and the **only** thing that unmutes it is a
delayed handler message:

```java
if (!z || i <= 0 || handler == null) return;   // no unmute is scheduled
```

| radio command | call | unmuted after |
|---|---|---|
| tune, fine ±, preset select, next/prev key | `setAK7738Mute(true, 10)` | 200 ms ✔ |
| band switch | `setAK7738Mute(true, 50)` | 1000 ms ✔ |
| **seek up, seek down, autostore, RDS-TA, RDS-PTY≠0** | `setAK7738Mute(true, 0)` | 🔴 **never** |

🧩 On a hub unit, a seek can leave the DSP at volume 0 until something else changes the source or
the volume. The only rescue is the MCU's `0xB0` status frame: when the seek or scan flag falls from
1 to 0 the framework schedules `setAK7738Mute(true, 50)`, which does carry an unmute. If the MCU
does not report that flag, the silence is permanent.

❓ Not yet confirmed on hardware — we have no hub unit. It is the first thing to test on one, and
it would hit our own radio app hard, because seek and autostore are its bread and butter.

### Navigation mixing bails out for software players

🔬 `setMixAudio(state)` — the whole body is inside:

> if the current volume type is `aux_type` **or** `radio_type` …

and then sets the mixer to `(ratio/10, 10)` while navigation speaks and `(10, 0)` when it stops,
where `ratio` is `persist.sys.navi_remix_ratio` (default 60), or 0 when `persist.sys.navi_remix` is
false.

🧩 **For `media_type` it does nothing at all.** The platform assumes music is always analogue. So:

- **over the radio:** the analogue side is ducked to 6/10 while the digital navigation prompt stays
  at 10 → navigation is loud;
- **over a software player:** the hub is never told anything → nothing ducks → navigation is just
  whatever Android mixed → navigation is quiet.

📻 This is precisely the asymmetry reported by users running the BitPerfect audio module: quiet
navigation with software players, and a radio that is too loud. BitPerfect removes Android's own
ducking policy, and the platform's replacement only ever worked for analogue sources.

🔬 Related, for completeness: `QFAudioService` parks the ducked music volume in
`sys.qf.lower.volume.by.navi` to restore it afterwards, and maps the ratio to the MCU as
`ratio * 32 / 100`.

---

## 4. The vendor Bluetooth app interferes with the radio

📻 Measured 26.07.2026 on our own unit, before it was fixed there: `com.qf.bluetooth` threw audio
focus **24 times in 110 seconds**, in bursts of four every 35 seconds, and never once requested it.
Each burst reset the MCU channel, audible as the radio stuttering for about 0.2 s. The source was
failed auto-connection to a phone that was not in the car.

### The auto-connect property is a mirror, not a switch

🔴 This one has cost time. `persist.sys.qf.bt_auto_connect` looks like the setting. It is not.

🔬 `TechBTSettingManager.setAutoConnectState(i)` does three things: writes that property, sends the
command to the Bluetooth module, and stores the value in the app's own preferences.
`getAutoConnectState()` reads **only the preferences**. Nothing in the vendor app ever reads that
property back. Writing it from outside changes precisely nothing.

🔬 The real precedence, from `TechBTConfiguration.initSettingsConfig`, evaluated when the Bluetooth
service starts:

1. the app's own preference (`GlobalTool`, key `auto_connect`), seeded once from the build's
   default;
2. **overridden**, if present, by `/great/protect_dir/btsetting.conf` — a small JSON file:

```json
{"auto_connect":"0","btName":"Magnitola"}
```

📻 On our unit that file exists, is `-rwxrwxrwx` inside a `drwxrwxrwx` directory, and already says
`"0"`. **That**, not the property, is why auto-connect is off here — and why flipping the property
from the radio app appeared to work when it was doing nothing.

🧩 So the file is writable without root and is the only lever that actually moves the setting. But
it is read at service start only, so it is a persistent preference, not a runtime toggle: it cannot
be used to disable auto-connect while the radio plays and restore it afterwards.

❓ Whether the Bluetooth service can be made to re-read it without a reboot is unknown.

### The channel-breaking mute, and what gates it

🔬 `supportDefaultBluetooth()` is called on every Bluetooth music focus transition, and calls
`Function.setMcuMute((byte) 60)` — an MCU mute with a timed release — before broadcasting play or
stop to `com.android.bluetooth`.

🔴 The entire method is wrapped in:

> if the current Bluetooth module is `ANDROID_DEFAULT` …

🔬 The module comes from `persist.sys.btmodel.choose`, with values `default`, `cq8761`, `sd8761`,
`sd936`. 📻 Our unit reports `sd8761` → **not** `default` → the method is inert here.

🧩 Two independent reasons, then, why this problem is invisible on our unit and real on others:
the conf file already disables auto-connect, **and** the external Bluetooth module keeps the
mute path from running at all. A unit with `btmodel.choose=default` routes Bluetooth audio through
AudioFlinger and takes the mute on every focus flap.

### One hardcoded package name

🔬 In `requestBTMusicAudioFocus`:

```java
if ("com.android.fmradio.ext".equals(SystemProperties.get("sys.qf.last_audio_src", ""))) {
    Function.setMcuMuteForBtMusic((byte) 37);
}
```

🧩 The vendor app special-cases the factory radio by name. A third-party radio never takes that
branch, whatever it does. Another instance of the platform's habit of granting privileges by
package identity rather than by behaviour.

---

## 5. Collect before theorising

wDSP has a **System report** button (Settings → Diagnostics) that writes all of the above to
`Download/wDSP/` without needing a measurement to succeed: the hub verdict, the whole volume model
with each property explained, the navigation mixer, routing, devices, who is holding the
microphone, and whether each audio source can be opened at all.

It also keeps a **timeline**: a recorder armed by the service listens for the platform's own
`PHONE_CALL_START/END`, `NAVI_SOUND_START/STOP`, `com.qf.action.VOLUME_CHANGED` and the Android
volume and SCO broadcasts, and writes one line per event carrying the volume model as it stood at
that instant. Ask a tester for two reports with one short Bluetooth call between them and the
before and after are side by side.

🔴 Do not diagnose any of this from a description. The properties are cheap to read and the
difference between the two hardware classes is one character.
