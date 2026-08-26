# Navigation prompts, and what BitPerfect does to them

Everything here was measured on 22–23.08.2026 on a K706 (Android 10), most of it while the car was
being driven with a logger attached to the mixer. The complaint it explains is the oldest one on
this platform: *"with BitPerfect the navigation is too quiet and the radio too loud"*.

---

## 1. The platform keeps a whitelist, and that decides everything

🔬 `/system/config/NaviApp.ini`

```
#navi package name   |  allow adjust navi volume  |  volume mix(0-100), default value: appset
com.waze                                    true    appset
com.nng.igo.primong.igoworld                false   appset
com.autonavi.*                              true    appset
```

🔴 **A navigator that is not in this file gets nothing at all** — no ducking of the music, no
navigation volume, no mixing slider. It plays at full scale straight into the music, and its owner
reports that it "went silent".

- **Column 2** decides whether the platform's navigation volume applies to that app. iGO ships with
  it `false`, which is why turning the slider does nothing for it.
- **Column 3** is a per-app mix ratio; `appset` means use the global one.
- Wildcards are supported (`com.autonavi.*`, `cld.navi.*`).

📻 Measured: `pl.aqurat.automapa` is **absent** from the factory list. Five prompts in a row, one of
them five seconds long, and the music did not move a decibel. After the package was added, the
platform ducked for it on the first try.

### The rest of that folder

🔬 `/system/config/` also holds `VoiceApp.ini` (voice assistants), `SkipAppWhenAudioStart.ini`
(`system`, `system_server` — apps whose audio must not count as playback), `HideApps.ini`,
`NotKillAppsBeforeSleep.ini`, `RestoreAppsWhenWakeup.ini`, `HideNaviBarApps.config`. Worth reading
before assuming any of that behaviour is hard-coded.

---

## 2. Every navigator travels differently, and the "correct" one loses

📻 Measured with a one-second sampler over `dumpsys media.audio_flinger`, columns `Usg` (**hex**)
and `G db`:

| app | stream | usage | asks for focus | music while it speaks |
|---|---|---|---|---|
| **Waze** | `SYSTEM` (1) | `SONIFICATION` (**13**) | no | −23 dB @ratio 82 · −37 dB with mixing off |
| **iGO** | `SYSTEM` (1) | `SONIFICATION` (**13**) | no | **−23 dB** |
| **AutoMapa** | `MUSIC` (3) | `NAVIGATION_GUIDANCE` (**12**) | **no** | **0 dB — never moves** |

🧩 The platform recognises the Waze pattern (`SYSTEM` + `SONIFICATION`). AutoMapa does it the way
Android documents — and gets nothing, because nobody is looking for that, and it does not request
audio focus either, so Android will not duck for it on its own.

⚠️ Our own `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` test tone lands in `AUDIO_STREAM_MUSIC` on both
policy sets, alongside the media track. **Navigation and media share one output thread**, so
nothing below AudioFlinger can separate them — no VBC tap, no submix. See
[05-AUDIO-PATH.md](05-AUDIO-PATH.md).

---

## 3. The prompt's level is one number: `navi_volume`

```
persist.sys.navi_volume         0..15, the prompt's own volume
persist.sys.navi_remix          duck the music at all
persist.sys.navi_remix_ratio    how deep, 0..100
persist.sys.backcar_remix_ratio the same for the reversing camera
```

📻 **`navi_volume` is the `STREAM_SYSTEM` index.** The report from a running unit shows both at
10, and `SYSTEM` as the only stream on the speaker not sitting at full scale:

```
persist.sys.navi_volume = 10
SYSTEM   -50..0 dB   index 10/15 ->  -16.7 dB
MUSIC / TTS / NOTIFICATION / ALARM / RING / VOICE_CALL   all 0.0 dB
```

And `ASSISTANCE_SONIFICATION` maps to `SYSTEM`, which is the route Waze and iGO take. So the whole
of a prompt's level is that one index.

🪤 Setting `STREAM_SYSTEM` by hand looks like it does nothing — I raised it to 15 and the next
prompt still measured −14 dB. 🧩 The platform re-applies `navi_volume` to the stream when
navigation starts (`QFAudioService.setOtherStreamVolume`), so a manual change is overwritten before
the prompt plays. **Change `persist.sys.navi_volume`, not the Android stream.**

⚠️ `navi_remix_ratio` runs **backwards** from intuition: a lower percentage means the music is
pushed further down, which makes the prompt clearer. 90 % is the *worst* setting for audibility.

### The slider is two different implementations

🔬 `com/qf/framework/volume/AK7738VolumeManager.java`

```java
int ratio = SystemProperties.getInt("persist.sys.navi_remix_ratio", 60);
if (volumeType.equals(VOLUME_TYPE_AUX) || volumeType.equals(VOLUME_TYPE_RADIO)) {
    if (!SystemProperties.getBoolean("persist.sys.navi_remix", true)) ratio = 0;
    DspJni.setMixerRatio(analogCompress, digitalCompress);
}
```

The **hardware** half runs only for `AUX` and `RADIO`. For Android media it is never called — there
the music is pushed down in software by `QFAudioService`. Two implementations under one slider,
which is why it does not behave consistently.

🔬 Reached from `McuManagerService` when the framework sends `CMD_ARM2MCU_MIX_AUDIO = 134` (`0x86`),
bit 7 of byte 0.

🔬 `android/qf/os/QFAudioService.java` keeps `mNaviInfoList` of `{sessionId, start_time,
package_name}` and drops an entry after 20 s, on the assumption that no prompt runs longer.

🧩 This whole class is `AK7738VolumeManager` — on units **without** that hub it does not apply at
all. That is the likeliest reason owners of other variants report the problem as much worse.
❓ Not verified: no access to such a unit.

---

## 4. What BitPerfect actually breaks — one thing

🔬 The module replaces `/vendor/etc/audio_policy_volumes.xml`:

| stream on the speaker | factory | with BitPerfect |
|---|---|---|
| `AUDIO_STREAM_MUSIC` | speaker curve | **`FULL_SCALE`** |
| `AUDIO_STREAM_TTS` | **`FULL_SCALE`** | curve 1→−4 dB |
| `AUDIO_STREAM_NOTIFICATION` | 1→−29.7 dB | 1→−4 dB |

🔬 `DEFAULT_DEVICE_CATEGORY_SPEAKER_VOLUME_CURVE` = 1→−49.5, 33→−33.5, 66→−17.0, 100→0.
`FULL_SCALE_VOLUME_CURVE` = 0 dB at every index.

📻 Confirmed on the wire: at index 9/15 (60 %) the curve interpolates to **−20.0 dB**, and the
mixer dump shows `G db = -20` on the media track. With BitPerfect the same track shows `G db = 0`.

### The arithmetic

| | music | prompt | prompt relative to music |
|---|---|---|---|
| factory, index 9/15 | −20 dB | −14 dB | **+6** |
| BitPerfect | **0 dB** | −14 dB | **−14** |

🔴 **The navigation did not get quieter. The music got louder**, and the prompt stayed where it was,
because its level comes from `navi_volume` and not from any Android curve. The owner then turns the
MCU volume down by about the same 20 dB to make the music bearable, and the prompt goes down with
it.

~~🧩 **This cannot be fixed by curves while media is at unity gain.**~~ **Wrong, and corrected on
24.08.2026 - see the section that follows.** It *is* fixable by a curve, because the platform ducks
by moving the volume *index*, and a curve that reaches 0 dB at the top index gives unity gain and
working ducking at the same time.

0 dBFS is still the ceiling and the prompt still cannot be raised above the music. What was missed
is that it does not have to be: the music gets out of the way by itself, if the curve lets it.

⚠️ And "duck only while the prompt speaks" **cannot be expressed in the policy XML at all**. Volume
curves are indexed by the position of the knob, not by whether anything is speaking. Ducking is
`AudioService` reacting to `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, and it only happens if the app
asks — which none of the three measured navigators do.

---

## 4-bis. `sys.qf.sound.channel` is evidence one way only

📻 The channel reads reliably on a unit carrying the BitPerfect policies. **On a factory one it
wanders**, so it cannot be trusted to say what is playing - only to confirm the tuner.

🧩 The asymmetry is what makes it still usable. The two mistakes do not cost the same:

- a stray **2** while music really plays is harmless, because anything asking this has already
  asked the spectrum engine, and there is signal;
- a stray **4** while the tuner really plays is caught by **nothing** - the signal veto is silent
  exactly when there is no audio to hear.

⇒ Treat `2` as decisive, treat `4` as no information at all. Both `NowPlaying.isRadioSource()` and
the widget gate in `McuService` were written the other way at first and had to be corrected.

---

## 4-ter. On a unit with no hub, ducking is done by the volume INDEX - and a flat curve kills it

📻 From the event timelines of system reports, 24-25.08.2026. **Two different owners**, both
MCU `004121`, BU32107, `hub = none`, BitPerfect installed (`NoiseSuppressor.isAvailable()` = true).
The same thing on both:

```
10:56:47  NAVI_SOUND_START
          MUSIC 15->14->13->12->11->10->9->8->7      eight steps in about a second
10:56:53  NAVI_SOUND_STOP
          MUSIC 7->8->9->10->11->12->13->14->15      and back
```

The platform ducks the music **by stepping the Android stream volume index down and up again**.
`STREAM_11`, `STREAM_12`, `ACCESSIBILITY` and `NOTIFICATION` are stepped alongside it.

🔴 And from the same report: `MUSIC full scale (0 dB always) index 15/15 -> 0.0 dB`. The curve is
flat, so **not one of those steps changes anything**. The platform does the work and the sound does
not move. That is the whole failure, and it is why owners of these units report it as much worse.

🧩 Why worse than on a unit with the hub: there `QFAudioService` *also* attenuates the track itself
- measured, `G db` fell to -23 while a prompt played. Here the index is the only mechanism there
is, and the module has neutralised it. This confirms the guess left open in section 3, though not
by the route guessed: it is not the missing hardware mixer, it is the flattened curve.

### The fix, and why it costs nothing

🔬 The factory speaker curve ends at `100,0` - **at the top index it already gives exactly 0 dB**.
And BitPerfect pins `persist.qf.arm.default.volume=15`, so the index sits at that ceiling anyway.

| | at index 15 | during a prompt (index 7) |
|---|---|---|
| `FULL_SCALE`, what the module ships | 0 dB | **0 dB** - ducking dead |
| the factory curve | **0 dB** | **about -20 dB** - ducking alive |

Putting `AUDIO_STREAM_MUSIC` on the speaker back onto a curve that reaches 0 dB at the top keeps
unity gain and restores ducking at the same time.

❓ Not tested on a car. The arithmetic comes from the curve tables and the timeline, not from an
ear, and it rests on the index really staying at 15 - which is what the module's own property is
for, but has not been watched over a whole drive.

---

## 5. Writing a Magisk module for this platform

🪤 **`system.prop` is CRLF** in the shipped module, and Magisk strips the `\r` — proved through
`aaudio.mmap_policy`, which exists only there and reads back clean. Do not "fix" it to LF without
a reason.

🪤 🔴 **`MODPATH=${0%/*}` at the top of `customize.sh` is wrong and was silently breaking things.**
Magisk sources the script, so `$0` is the *installer*, not the script; the line overwrote the
correct `$MODPATH` Magisk exports, and everything written through it landed outside the module.
Use `: "${MODPATH:=${0%/*}}"` so Magisk's value wins and the fallback only serves a manual run.

🧩 Prefer **merging at install** over shipping a copy of a vendor file. `customize.sh` runs before
the module is mounted, so it can read the live `/system/config/NaviApp.ini` and append to it.
Shipping a whole copy would freeze the vendor's list, and a later firmware's new navigators would
be hidden for as long as the module stays installed.

⚠️ Do not pin somebody's volume in `system.prop`. `navi_volume`, `navi_remix` and
`navi_remix_ratio` are sliders in the platform's own settings; a module that writes them every boot
takes the choice away.

---

## 6. What to ask a stranger for

wDSP's system report (**Settings → Diagnostics → Collect**) carries all of the above without root:
which stream each usage lands in, every volume curve and what it comes to in dB at the current
index, the policy's accepted formats (which separates a factory unit from a modified one without
asking), the installed navigators checked against `NaviApp.ini`, and the audio hub.

Related: [08-VOLUME-AND-SOURCES.md](08-VOLUME-AND-SOURCES.md) · [05-AUDIO-PATH.md](05-AUDIO-PATH.md)
· [02-MCU.md](02-MCU.md)
