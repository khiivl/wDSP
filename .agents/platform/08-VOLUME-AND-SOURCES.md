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

📻 **Measured firing on a source switch, 27.08.2026** — both properties sampled every 400 ms:

```
01:27:18   media=1  radio=4  radio_type
01:28:47   media=4  radio=4  radio_type    ← made equal on purpose
01:29:21   media=1  radio=1  media_type    ← a source switch: BOTH wiped
```

`persist.sys.main_volume` is `1` on this unit, and both landed exactly there.

🔴 **The trap this sets for anyone synchronising volume between sources.** Two sources holding the
same number is the condition this routine keys on. Before anything synchronises them the sources
rarely match, so at most one is ever wiped and nobody notices. Carry a level from one source to
another — which is the whole point of synchronising — and you have **manufactured the condition
yourself**: the next source switch throws away the level the owner chose, on both.

It also fires from paths that look unrelated: a radio taking the audio path may write
`radio.vol = media.vol` as part of acquiring it, which equalises them without anybody asking for
synchronisation at all.

🔑 **The way out is not to fight it.** The platform writes last and will win. Keep the per-source
levels in your own process, where no property reset can reach them, and write the level back after
a source change — wDSP restores it on the next 100 ms poll (`McuService`, the source-change block).
The owner hears one step at the moment of switching, where the sound is changing anyway, instead
of silently losing the level they set.

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

### 🎚️ Reproducing a real knob turn from adb — `keyevent 293` / `294`

📻 Measured 27–29.08.2026. This closes a hole that had blocked testing on both projects: anything
keyed on `com.qf.action.VOLUME_CHANGED` could only be exercised by a human physically turning the
encoder, so it stayed unverified whenever the owner was away.

```sh
adb shell input keyevent 293   # volume up   — raises com.qf.action.VOLUME_CHANGED
adb shell input keyevent 294   # volume down — likewise
```

- 🔴 **Android `keyevent 24` / `25` do NOT work here.** They move `STREAM_MUSIC` inside Android and
  the platform never learns of it, so no `com.qf.action.VOLUME_CHANGED` is emitted and every
  listener stays silent. Testing with 24/25 produces a convincing false negative: the volume
  visibly changes, and the feature under test looks broken.
- 🔬 Provenance: `293`/`294` are the **Android key codes** the platform's own `hid_daemon.sh` feeds
  to `input keyevent` (`trigger_action 293/294`) when the physical encoder moves — i.e. this is the
  same path the hardware takes, not an imitation of it. They are key codes, **not** raw scancodes;
  do not look for them in `getevent` output.
- ⚠️ If `input` reports `No service published for input`, that is not this mechanism failing — it is
  a symptom of `system_server` having crashed. Reboot and re-check before blaming the key codes.

📌 Consequence worth carrying between projects: a volume-knob path can now be proven end-to-end
without the owner present. The wDSP↔radio audio-ownership contract had this listed as
"only the owner can produce this" — that limitation is lifted.

#### 🔴 …and the same two keys corrupt a user setting, through a vendor bug

🔬 Found 31.08.2026 in the decompiled framework, `McuManagerService.java:1536-1544`. While the
current source is `radio_type`, the framework **unconditionally rewrites
`persist.sys.radio_volume` on every 293/294 press** — the user's boot-default volume, which is
supposed to be theirs alone and to change only from the factory settings app.

Two things follow, and both have bitten:

- **Do not read `persist.sys.radio_volume` as "what the user configured".** After any session of
  turning the knob on FM it holds the last live level instead. It is not a stable preference any
  more, whatever the settings screen implies.
- **This is not our applications doing it.** Neither the radio nor wDSP writes `persist.*` — that
  is a standing rule on both sides. When this value moves on its own, the vendor framework moved
  it. Do not go looking for a bug in our code, and do not "fix" it by writing the property back:
  the next knob press overwrites it again.

⚠️ This corrects an earlier claim in these notes (11 §volume) that `persist.sys.main_volume` and
`persist.sys.radio_volume` are written *only* by `QF_CarSettings`. That holds for
`main_volume`; it does **not** hold for `radio_volume` on the FM source.

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

### What the unit is actually doing, and how often

🔴 It is not searching. Inquiry — the broadcast "who is out there" — happens only while the
pairing screen is open. Auto-connect calls `connect(address)` on **one** remembered address:
`persist.sys.qf.last_bt_addr`, or the one recorded in `HFP_CONNECT_MAC_BEFORE_ACC_OFF` just before
the last ACC-off. That is a **page**, directed at a single device. Paging a phone that is not in
the car fails on the page timeout and costs nothing but the failure — except for what the failure
does to the audio path here.

🔬 Why it exists at all: this platform hibernates completely at ACC-off. On wake, the unit has to
re-establish the link itself, because nothing on the phone side necessarily notices that the car
came back. 🧩 Which is also why the phone reconnecting on its own — as it does on units where all
of this is switched off — is not evidence that the feature is pointless; it is evidence that *that*
phone is willing to page first.

### The 40-second retry loop is a SEPARATE, hidden setting

🔴 This is the one that causes the reports, and it is **not** `auto_connect`.

🔬 `TechBTSettingManager.pollConnectRun`:

```java
if (isCarplayConnected())                       return;   // stops the loop
if (hfpConnected || a2dpConnected)              return;   // stops the loop
connectRun.run();                                         // page the last address
mH.postDelayed(pollConnectRun, 40000L);                   // ... and again in 40 s
```

No backoff, no attempt limit. **While the phone is absent it pages every 40 seconds forever**, and
the only thing that ends the loop is a successful connection.

🔬 It is gated on its own key, `BT_AUTO_POLL_CONNECT`, default **0**, and there is no visible
control for it. It is toggled by a **long press on the "Settings" tab button** inside the Bluetooth
app, which shows a toast:

```
Open Bt poll connect per 40s!     /  Close Bt poll connect per 40s!
Увімкнути опитування з'єднання Bluetooth кожні 40с!
```

🧩 So a unit that stutters every 40 seconds has that hidden flag on — shipped that way, or
long-pressed by somebody who did not know what they had done. **Ask an affected owner to long-press
the Settings tab in the Bluetooth app and read the toast.** That is a one-minute test that needs
nothing from us, and it is the first thing to try.

📻 Measured 26.07.2026 on our unit: bursts of four focus events every ~35 s. 🧩 One page attempt
raises several profile connections — HFP, A2DP, AVRCP, PBAP — and each can flap the focus, which
matches the burst shape against a 40-second period.

🔬 With `auto_connect` off but the poll flag on, the loop still ticks: `connectRun` calls `loadBT()`
*before* it checks the auto-connect flag, then returns without connecting. 🧩 Harmless on our unit,
because `loadBT()` only does anything for the ChengQian module.

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

### Measured, because "mirror" deserved proof

📻 21.08.2026, three reboots, touching **only** the conf file and never the property:

| conf file | `persist.sys.qf.bt_auto_connect` after the reboot |
|---|---|
| `"auto_connect":"0"` | `false` |
| `"auto_connect":"1"` | `true` |
| `"auto_connect":"0"` | `false` |

Nothing else writes that property, so the app wrote it — from the conf value, in
`initSettingsConfig`. 🔴 **The direction is conf → app → property, and the property is the output.**
It changes at reboot because that is when the app re-reads the file and re-publishes the mirror,
not because anything reads the property back.

🔬 Searched the whole decompiled corpus — platform framework, launcher, OTA, CAN bus, both radio
apps, the Bluetooth app: the string `persist.sys.qf.bt_auto_connect` appears in exactly one place,
`MiscConstants`, and the only code that touches it writes it. Setting it from outside is
overwritten at the next boot and read by nobody in between.

### What the app's own toggle does, and what it does not

🔴 The settings toggle takes effect immediately, which looks like proof that something re-reads
the property. It is not. 🔬 `SettingFragment` does exactly two things:

```java
TechBTSettingManager.getInstance().setAutoConnectState(i ^ 1);   // AT command, in-process
ImportAndExportUtil.writeBtAutoConnect(i ^ 1);                   // the conf file above
```

and `setAutoConnectState` ends at `sendATCommand("AT#MG")` / `"AT#MH"` on the module itself
(`/dev/goc_serial`, which on our unit is a symlink to `/dev/pts/0`). **The instant effect is an AT
command, not a re-read.** The conf file is simply where that toggle keeps its answer, and
`confMap` is loaded in a static initialiser - once per process, at class load.

🧩 So writing the file from outside performs the *persistent* half of what the toggle does. The
instant half is unreachable.

### The external surface, enumerated - auto-connect is not on it

Checked, so nobody has to check again. 🔬 Two binders are published in `ServiceManager` and 📻 both
are live on our unit:

```
btBinderPool:      com.qf.btsdk.IBTBinderPool
bluetooth_server:  com.qf.bluetoothsdk.aidl.BluetoothBinder
```

- `btBinderPool` serves exactly **one** sub-binder, `"btSettingBinder"` → `TechBTSettingServer`,
  with five transactions: `1024` setBtEnable(boolean), `2048` connected name, `4096` isConnected,
  `8192` address, `16384` enabled state;
- `bluetooth_server` is call control - accept, reject, hang up, dial, name, state, callbacks - plus
  `getBinderServerByName` and a generic `onCallMethodByName`. That dispatcher only reaches methods
  annotated `@ServerMethod`, and those are all inter/extra Bluetooth device management: open,
  close, reset, rename, discovery, pair, connect, PBAP sync;
- no broadcast reaches auto-connect either: the app's dynamically registered actions are Android
  Bluetooth profile events plus `com.qf.action.ACC_*`, `SCREEN_*`, `PHONE_CALL_*`, `READY_GO_SLEEP`
  and `connect_pan`/`disconnect_pan`.

🔴 **Auto-connect is not exposed anywhere.** The only caller outside `initSettingsConfig` is the
app's own settings screen.

### What that leaves

🧩 Write `/great/protect_dir/btsetting.conf` and the setting is correct from the next time the
Bluetooth SDK initialises, which is a process start. ❓ Whether ACC-off hibernation restarts that
process - and therefore whether the file takes effect every drive - is untested and is the next
thing to measure. Otherwise it is a reboot, or asking the owner to flip the toggle once, which
writes the same file.

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

### Which of the two radios does this, and why the module gate is only half a shield

🔴 There are **two** Bluetooth radios on these units and they must not be confused:

- the **Android one** (`com.android.bluetooth`), a normal master that does inquiry and carries
  things like an ELM327 OBD dongle and PAN;
- a **second module** on a serial link (`persist.sys.btmodel.choose` = `sd8761`, `cq8761`, `sd936`),
  driven by `com.qf.bluetooth` over AT commands. **Calls and A2DP streaming go through this one**,
  and it is the one auto-connect pages.

🔴 The focus churn comes from the platform app attached to the **second** module, and the
`ANDROID_DEFAULT` gate does not stop it. The gate protects only the MCU mute; the focus abandon is
ungated. 🔬 The chain, for a failed page on an external-module unit:

```
pollConnectRun / ACC_ON  ->  connect(lastAddress)  ->  AT command to the module
module reports HFP disconnected
  TechBTSettingManager: setHfpConnectState(0)
    abandonAllAudioFocusWhenHfpDisconnected()
      -> AudioFocusMessage type 4  -> abandonBTMusicAudioFocus()
      -> AudioFocusMessage type 2  -> abandonBTPhoneAudioFocus()
        both end at audioManager.abandonAudioFocus(...)   <- NOT gated on the module
```

🔴 And the matching **requests** never happen on such a unit: `requestBTPhoneAudioFocus()` *is*
wrapped in the `ANDROID_DEFAULT` check, and `requestBTMusicAudioFocus()` only runs when Bluetooth
music actually plays. 📻 Which is exactly what was measured — 24 abandons, zero requests. **The app
releases a focus it never took**, several times per failed attempt, and Android re-evaluates the
focus stack each time; the platform's audio service turns that re-evaluation into an
`RPC_SetChannel`, and channel 2 drops for about 0.2 s.

### Reproduced on our own unit, both ways

📻 21.08.2026. Wrote `"auto_connect":"1"` into the conf file, rebooted with no phone in range,
captured the log for 200 s:

```
14  abandonAudioFocus() from uid/pid 1000/8467   (com.qf.bluetooth, running as system)
 0  requestAudioFocus  from that pid
 7  onHfpStateChanged: state = 0   - and NEVER any other value
 1  "resume connect 88B951F88F62"
```

Restored `"0"`, rebooted, captured 120 s: **0 abandons, 0 connect attempts, 0 HFP state reports.**

🔴 The decisive line is the third one. All seven HFP reports are **state 0**; there is no
transition anywhere in the capture. The module keeps re-reporting "disconnected", and
`setHfpConnectState(0)` runs the whole disconnect path every single time — **there is no
already-in-this-state check** - so each repeat abandons both focuses again. It even does so with no
listeners attached (`onHfpStateChanged: map size = 0`).

📻 The cadence, from one page attempt: two abandons at +0.2 s, then eight more between +4.0 and
+4.6 s, then four more at +37 s. That is the "bursts of four every ~35 seconds" measured back in
July, and it comes from **one** connect attempt, not from repeated ones.

🧩 So this is not a design trade-off to be worked around. It is a missing equality check in a
state setter, in a process running as `system`, whose fallout the platform's audio service turns
into channel switching.

🔬 `abandonBTPhoneAudioFocus()` is worth seeing: it is an `if (module == ANDROID_DEFAULT) { ... }`
whose two branches are **character-for-character identical**. Somebody meant to gate it and did
not.

🧩 So on an external-module unit the two effects separate: the MCU mute does not fire, but the
focus abandon does. Killing the source — the 40-second poll, or auto-connect itself — is the only
lever that removes both.

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

---

## The AK hub is in the software on every unit, and in the hardware on some

📻 28.08.2026, on a unit whose MCU code is `002121` — that is, **no hub** according to the platform.
Everything in software says the opposite:

```
/sys/bus/i2c/devices/0-001c/driver  → bus/i2c/drivers/ak7738      driver bound
/sys/bus/i2c/devices/0-001c/of_node → .../i2c@70300000/ak7604@1c  declared in the device tree
debugfs .../codec:ak7738.0-001c/dapm                              AIF1…AIF5, a complete codec
```

The bus tells the truth:

```
regmap/0-001c/access      000: y y y n     readable, writable, volatile
regmap/0-001c/cache_only  N                reads really reach the wire
regmap/0-001c/registers   000: XX  001: XX  … all 274
```

`regmap_debugfs` prints `XX` only when `regmap_read()` **fails**, and with `volatile=y` and
`cache_only=N` every read goes to the chip rather than a cache. The chip is addressed and does not
answer.

🧩 The device tree and the drivers are identical across the fleet — one ROM — so **the AK hub shows
up in `/sys`, in debugfs and in the codec list on every head unit**, fitted or not. That is exactly
why its presence looked like "either everywhere or nowhere".

🔴 **Detect the hub from the MCU code and nothing else.** The i2c device, the codec list and the
kernel log will all report an AK7738 on a unit that never had one. The platform does this too:
`IS_AK7738_DSP` is derived from the MCU version string, not from the driver.

❓ A fitted chip held in reset or unpowered would be equally silent; only probing the board
separates those. Here the MCU code agrees with the bus, so two independent sources settle it in
practice.

---

## Ducking: the platform computes it, and only for two sources on some units

🔬 `com.qf.framework.volume.AK7738VolumeManager.setMixAudio` (and the identical
`setAK7604MixAudio`). This is where a navigation prompt is mixed over the radio — not in the MCU,
not in an audio policy:

```java
int ratio = SystemProperties.getInt("persist.sys.navi_remix_ratio", 60);

if (volumeType.equals(VOLUME_TYPE_AUX) || volumeType.equals(VOLUME_TYPE_RADIO)) {
    if (!SystemProperties.getBoolean("persist.sys.navi_remix", true)) ratio = 0;
    int digitalCompress = 10;
    if (naviSpeaking) analogCompress = ratio / 10;        // 60 → 6
    else            { analogCompress = 10; digitalCompress = 0; }
    DspJni.setMixerRatio(analogCompress, digitalCompress);
}
```

**The pair is the level of the hub's two inputs, on a 0…10 scale** — established from the source
switch right above it:

```java
mode 1 or 2  (analogue: radio, AUX) → setMixerRatio(10, 0)
mode 4 or 5  (digital: Android)     → setMixerRatio(0, 10)
```

So during a prompt on the radio the hub is told: analogue down to `ratio/10`, digital up to `10`.

🔴 **The slider runs opposite to intuition.** `navi_remix_ratio` sets *how much radio survives*, not
how much it is cut:

| ratio | analogue during a prompt | what the driver hears |
|---|---|---|
| 100 | 10/10 | radio not ducked at all |
| 60 (default) | 6/10 | moderate duck |
| 0, or `navi_remix=false` | 0/10 | radio silenced completely |

Somebody who wants "duck harder" and turns the number **up** gets the opposite. This — not the
tuner — is why two owners on nominally identical hardware report opposite behaviour.

🔴 **Two conditions gate the whole thing, and both are easy to miss:**

1. **Only `AUX` and `RADIO`.** The block does not run for `media_type` at all — a prompt over music
   from a player gets no hub mixing whatsoever. That is the real reason behind "navi gain 7-8 on FM
   against 15 on media": two different mixing paths, not a hotter tuner.
2. **Only with an AK hub** (`IS_AK7738_DSP` / `IS_AK7604_DSP`). On units without one the method
   does nothing, and ducking falls back to whatever Android's policies do.

❓ Previously this folder carried `ducking_step = (100 - ratio) / 10` as a firmware fact. It is
wrong on both counts: the arithmetic is `ratio / 10`, and it lives in the framework, not the MCU.

🧩 And this is consistent with a wider point Kostyantyn makes about the platform: the command
language toward the MCU is **unified across chipsets** — a three-band BD37534 accepts the same
commands as a sixteen-band BU32107, and the MCU translates. So a difference in how something sounds
between two boards is more likely to come from coefficients the framework computed *before*
sending, than from the commands themselves. ❓ What else the framework scales this way is not yet
mapped.
