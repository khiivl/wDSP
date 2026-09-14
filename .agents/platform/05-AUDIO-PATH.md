# The audio path on QF head units, as measured

Everything here was measured on a K706 (Unisoc UIS7862, SC2730 codec) with a wire attached, not
read from documentation. Where a figure is quoted, it came off that unit. Where something is
believed but unproven, it says so.

The reason this file exists: almost every number Android reports about audio on this platform is a
*declaration* rather than an observation, and several of them are wrong by a factor of seven.

---

## 1. Media does not always go where you think

There are two output threads. Which one carries the music depends on the audio policies installed,
and that changes what an app can do:

| policies | media plays on | consequence |
|---|---|---|
| factory | `AudioOut_15`, `AUDIO_OUTPUT_FLAG_FAST` | **`Visualizer(0)` is dead** |
| custom (the BitPerfect module) | `AudioOut_D`, `AUDIO_OUTPUT_FLAG_PRIMARY` | `Visualizer(0)` works |

A `Visualizer` created on session 0 is an *output mix* effect, and
`AudioPolicyManager::getOutputForEffect()` hard-prefers the primary output. On factory policies
the primary output sits in standby with zero tracks, so a session-0 effect faithfully measures
silence — for ever, without an error.

This is why `SessionResolver` exists: it finds the session id the music is actually on and attaches
the effect there. **Do not "simplify" it back to session 0.** It works on one head unit and not on
the next, and the difference is not in our code.

### Attaching an effect is a race

`getOutputForEffect()` looks for an output that already carries the session, and falls back to
primary when it finds none. So an effect created *before* its track starts playing lands on the
idle thread and hears nothing.

Both `LatencyProbe` and the analyser deal with this the same way: start the track, write a few
hundred milliseconds of silence, and only then create the `Visualizer`. Even that is not certain —
about one attempt in three still lost the race with custom policies — so `LatencyProbe` also checks
whether anything was heard after two bursts and re-attaches if not.

🪤 Re-attaching throws `IllegalStateException: setCaptureSize() called in wrong state: 2`, because
the previous effect is still enabled; releasing it does not take effect at once. Check
`getEnabled()` first and keep whatever capture size is already configured.

---

## 2. The capture path

### The assistant holds the microphone, and that silently caps everyone

`com.google.android.googlequicksearchbox` opens the microphone at boot and never lets go:
`session:25, source=MIC, 1ch 16000Hz`. The platform opens **one** input stream, so another app's
request does not open a second one — it is attached to the existing stream and gets 16 kHz
resampled up to 48.

```
                        assistant holding   microphone free
8.5 kHz                     -91.9 dB            -57.1 dB
energy above 8.1 kHz        -30.0 dB            +1.3 dB
```

🔴 **`AudioRecord.getSampleRate()` lies** — it returns 48000 either way. The only honest test is
to listen: record half a second and compare the energy above 8 kHz with the band below it. Free
microphone, about **−15 dB**; shared with a 16 kHz client, **−69 to −85 dB**. Two orders of
magnitude apart, so the threshold does not need to be precise.
`SweepMeasurement::bandwidthRatioDb` does the measuring, `MicrophoneGuard` does the deciding.

### Getting it back, and what does not work

| approach | outcome on a K706 |
|---|---|
| ask the user to turn the hotword off | they will not, and should not have to |
| `AudioManager.getActiveRecordingConfigurations()` to find the culprit | the package name behind a recording is hidden from ordinary apps |
| `ActivityManager.killBackgroundProcesses()` | 🔴 **no effect** — the Google app is a system app here and is not a "background process" |
| `su -c "am force-stop <pkg>"` | 🟢 works, and needs a Magisk grant for the app |
| `su -c "cmd appops set <pkg> RECORD_AUDIO ignore"`, left in place | 🔴 **the assistant goes deaf — it does not "share".** 📻 11.09.2026, owner's unit, the Google app at `ignore` for a day and a half: no active recording at all, and the audio event log since morning holds only wDSP's own sessions — on 20.08 the same unit had Google on `MIC 16000Hz` from boot. 🧩 AOSP's `startRecording` refuses a client whose op is not `allow`. The mode survives reboot and our own uninstall, and nothing in the app ever restores it. Put in by 0.4.9.x (`8afeecb`, `cf3a53f`, 10.09) on the claim that it was harmless. ❓ a voice query from the button not tried |
| choosing a different `AudioSource` | no effect, see below |

`killBackgroundProcesses` needs only a normal permission and is worth trying first, because on a
head unit where the assistant is an ordinary app it is enough. It was not enough on the one this
was written for.

🪤 When testing this, do not launch the assistant and measure straight away: a process in the
foreground cannot be stopped by either route, and that will look like the method failing when it
is the test that is wrong.

🪤 📻 *(11.09.2026, owner's unit, fresh install)* The opposite case is just as real: here Magisk's
default for a new app is **prompt**, with a countdown and "Forever" pre-selected. So `su -c id` is
**not** a silent check — from an app Magisk has no answer for, it raises the root dialog. wDSP did
it from `MainActivity.onResume` on the very first start, over its own permissions wizard. A test
script's Back key lands on that dialog and answers "deny"; with "Forever" selected that deny can
stick. Only re-verify a grant the app has already seen; let the first ask come from a tap.

🪤 A `su` request from an app is refused **silently** until it is granted. Magisk stores a policy
per uid, and the default on some units is deny rather than prompt:

```bash
adb shell su -c 'magisk --sqlite "SELECT uid,policy FROM policies"'   # 1 = deny, 2 = allow
```

### 🟢 Open first, and the assistant rides along — measured 11.09.2026

📻 "The first client sets the rate" works in both directions. On the owner's unit, read from
`dumpsys audio` (RecordActivityMonitor) and from the probes' own recordings:

```
17:51:16  Google alone         dev=1ch 16000Hz               our probe above 9 kHz: -76.1 dB  (16k)
17:51:22  force-stop Google;   wDSP opens UNPROCESSED 48 kHz  dev=1ch 48000Hz
17:51:26  Google back in 2 s   same patch:719, dev 48000Hz, client 16000Hz, silenced:false (both)
          our probe on the shared input                     above 9 kHz: -18.8 dB  (48k)
```

A 48 kHz stream that is already open when the assistant arrives is **shared, not taken**: the
assistant gets its 16 kHz by resampling and keeps listening, and we keep the full band. The price is
one stop of the assistant at the moment we open while it holds 16 kHz; it came back on its own in two
seconds.

📻 The whole 300-second hold stayed full-band — above 9 kHz between −0.1 and −31 dB, against −76 with
the assistant first. And when our client left at 17:56, the assistant **stayed on the same 48 kHz
input** (`patch:719`, client still 16 kHz): an input keeps the rate it was opened at until it is
closed. So the stop is needed only when the stream is at 16 kHz — after boot, or after the assistant
itself restarts — not on every open of ours.

⚠️ The assistant brings its pre-processing with it: once it attached, our client line showed
`dev='Noise Suppression'` — an effect on the shared input. ❓ Whether it alters our samples is not
measured yet (music was playing, so the noise floor moved with the track); for a sweep it would
matter, since a noise suppressor removes exactly the steady signal a test tone is. Measure in a
silent cabin. 📻 **"Ok Google" answered** while attached to our 48 kHz stream — the owner's own test,
11.09.2026 ~17:58. So "shared, not taken" holds for the assistant's actual job, not only on paper.

🔴 Consequence for the app: the order is the whole trick. Whatever holds the microphone must be ours
**before** the assistant reopens, and it came back in two seconds here — so stop it and open
immediately, with no measuring or sleeping in between.

#### After a real cold boot the order is ours without doing anything — 14.09.2026

📻 PC and head unit rebooted by the owner; read afterwards from `dumpsys audio`, whose recording
event log does not roll the way logcat does:

```
00:52:54      boot
00:53:50.315  wDSP        rec start   (+56 s)
00:54:21.367  assistant   rec start   (+87 s, 31 s after us, as a second client)
```

So on this unit, with wDSP starting from `BOOT_COMPLETED`, the service opens the input half a minute
before the Google assistant does. No stop, no root. ❓ Whether the first probe came up full band is
not recoverable — logcat had rolled by the time it was read. ❓ One boot, one unit; the assistant's
start time is not guaranteed.

🔴 **And the next boot proved it is not.** The same unit, booted at about 06:10 the same day (uptime
8:47 at 14:57), `dumpsys audio` event log:

```
06:10:50.329  assistant   rec start   riid 39      <- first, about +41 s
06:10:51.765  assistant + wDSP rec update           <- ours joined its 16 kHz input
06:10:52.330  wDSP        rec stop    riid 47      (root denied for the test: "no root", let go)
```

So after a cold boot the order is a race won by half a minute once and lost by 1.4 s the next time.
Nothing about the app can be built on being first at boot.

📻 **Why it is a race, read from a whole boot** (`adb reboot` 15:23, the boot logger module
`tools/wdsp_bootlog_module`, timestamps in seconds since boot):

```
32.390  wDSP BootReceiver gets LOCKED_BOOT_COMPLETED, starts McuService
32.399  ActivityManager: Unable to start service McuService U=0: not found
        (BootReceiver is directBootAware, McuService is not: before unlock it does not exist)
32.587  assistant's :interactor process starts
32.604  user unlocked (am_user_state_changed [0,3]); USER_UNLOCKED broadcast at 32.638
45.392  assistant AudioRecord start, 16 kHz        <- 12.8 s of its own initialisation
45.530  wDSP BootReceiver gets BOOT_COMPLETED       <- 12.9 s after unlock, ordered broadcast queue
45.539  McuService created;  46.030 our AudioRecord start - 0.64 s too late
```

Both sides wait about thirteen seconds after unlock for unrelated reasons and arrive within a second
of each other. wDSP loses those seconds only because it starts from `BOOT_COMPLETED`: the process is
alive from 32.39 and the user is unlocked at 32.60. 🟢 **Done by the owner's order, `1a287d0`: the service starts at unlock** - audioserver checked and
the microphone taken on one thread, the MCU service checked and the presets applied on another.
Measured the same day: after `adb reboot` our capture opened at 34.49 s and the assistant's at 46.07;
after a sudden power loss at 36.98 and 48.39. Both times the input came up at 48000 Hz with the
assistant riding it at 16000 Hz, 11.6 and 11.4 s behind us. The two boots side by side:
01-SYSTEM.md §8a.

#### A stopped recording is not yet a closed input — 14.09.2026

📻 Measured to find out when a newcomer can set the input's rate. wDSP stopped, the assistant alone on
a 16 kHz input, then `am force-stop` of the assistant and `dumpsys` in a loop:

```
+70 ms    force-stop returns
+86 ms    active recordings 0, input threads 1      <- the list is already empty
+209 ms   active recordings 0, input threads 0      <- the input is closed
+1920 ms  active recordings 1, input threads 1      <- the assistant is back
```

And what a capture opened inside that window gets: wDSP waiting for a free input opened at
14:58:55.289, 75 ms after the recording callback reported the list empty (the assistant's
`rec release` is logged at .213) - and the platform reported **16000 Hz** under it 2 ms later.
The assistant's new recording did not start until 14:58:57.102, so nobody else was there: the capture
was handed the input that was still open, at its old rate.

So a recording that has stopped - even one whose `rec release` is already in AudioService's log -
leaves its input open until the native side lets go of the last client, and a client that arrives
in between inherits the rate. Waiting a few hundred milliseconds after the list empties is what makes
a gap usable (`MicInputWindow`). ⚠️ Read from a killed process; an assistant that releases its
recorder on its own may close the input faster or slower.

📻 The assistant does leave such gaps on its own, not only when killed: `rec release` 15:00:55.673 →
next `rec start` 15:00:57.478 (1.8 s), ~2 minutes after its previous start; the same 1.8 s at
06:34:47.650 → 06:34:49.463. ⚠️ But not reliably: after 15:00:57 it held one recording for over ten
minutes without a gap.

🟢 **Taking the input back through a gap works, with the wait** (`MicInputWindow`, root denied for
wDSP), 14.09.2026 - the whole chain, on the wire:

```
15:29:24      setprop ctl.restart audioserver
15:29:26.715  recording callback: our input restored at 16000 Hz -> microphone unavailable,
              spectrum calculated, window waiting ("input held at 16000 Hz by another app")
15:29:29.759  assistant rec release (force-stop from adb)
15:29:29.760  window: gap - trying in 300 ms
15:29:30.101  wDSP rec start
15:29:30.861  input device at 48000 Hz - microphone back at full band
15:29:31.695  assistant rec start: client 16000 Hz on our dev 48000 Hz
```

The same at 15:25:56 → 57.923 after a reboot the assistant had won. Without the wait (first build,
75 ms) the capture got 16 kHz.

#### After hibernation the assistant reopens first — onto our input, which never closed — 14.09.2026

📻 The owner's unit put to sleep for ~15 minutes with the wDSP main screen **closed**, so only the
service held the microphone (a first run with the screen open was discarded: the screen started a
capture of its own on wake). Read from a root shell daemon on the unit, which survives sleep and
wakes before anything else (`07-PRACTICE.md` §7), plus `dumpsys audio`:

```
02:11:39.601  ACC_OFF reaches wDSP; our capture keeps logging
02:12:18.084  assistant   rec stop  (its last client before sleep)
              wDSP        no rec stop - riid 383 stays "started" through the whole sleep
02:12:17.676  last line from our process          ---- unit frozen ~14.5 min ----
02:26:50.090  first line from our process: the pre-sleep capture is still delivering samples
02:26:50.172  assistant   rec start  <- the first client to open anything after wake
02:26:50.454  ACC_ON reaches wDSP (282 ms after the assistant)
02:26:50.574  wDSP        rec stop   (riid 383 - our own restart, see below)
02:26:50.738  wDSP        capture started again, 48000 Hz UNPROCESSED      (164 ms gap)
02:26:51.257  own stream: -3.5 dB above 8 kHz - full band
02:28:17      dumpsys: both clients on patch 69, dev=1ch 48000Hz;
              assistant client=1ch 16000Hz (resampled), wDSP client=1ch 48000Hz
```

So "who is first after sleep" is the wrong question on this unit: **the input does not close for
sleep** — the process holding it is frozen and thawed whole, not restarted (`07-PRACTICE.md` §7). A capture that was open when the unit froze is still open when it thaws, and the
assistant, which does reopen and does so before `ACC_ON` is even delivered, joins a 48 kHz input
that already exists — the "shared, not taken" case above. The owner saw it independently on the
screen: the 12.5 kHz and 20 kHz bars moving.

⚠️ The only window this wake had was **ours**: the app closed its own capture on `ACC_ON` and
reopened it 164 ms later, with the assistant alone on the input in between. It came back full band,
consistent with "an input keeps the rate it was opened at until it is closed" — but that is one
wake on one unit, and a unit whose assistant is first *and alone* for longer than that is exactly
the case that ends at 16 kHz. ❓ Whether the input's rate was reconfigured inside those 164 ms is not
visible: both clients got a `rec update` at .739, the moment ours rejoined, and a snapshot every
3 s cannot say what changed.

📻 **Repeated with that restart removed** (wDSP `6ba2839`), same night, ~22 minutes asleep:

```
02:44:07.218  wDSP        rec start  riid 415
02:47:42.101  assistant   rec start, 02:47:53.932 rec stop   (its short listen before sleep)
              ---- unit asleep; 2 more successful suspends counted ----
03:09:42.138  assistant   rec start  <- first again
03:09:42.380  ACC_ON reaches wDSP (242 ms later)
03:09:42.402  wDSP: "woke up with the microphone still open: capture kept, noise floors forgotten"
              wDSP        no rec stop, no rec start - riid 415 still active at 03:09:59
03:09:59      both clients on patch 69, dev=1ch 48000Hz; assistant client 16000Hz
```

Same pid before and after. The assistant still reopens first, and there is no longer any moment at
which it is alone on the input.

#### An audioserver restart does not kill a capture — it narrows it, silently — 14.09.2026

📻 `su -c 'setprop ctl.restart audioserver'` at 03:13:05, with our 48 kHz capture (riid 415) and the
assistant's (riid 447) both on a 48 kHz input, YouTube Music playing:

```
03:13:05.563  our process: W/AudioRecord restoreRecord_l: dead IAudioRecord, creating a new one
              E/AudioRecord createRecord_l: status -32 (audioserver not up yet), retries 3
03:13:06.565  AudioService: "Audioserver started."
03:13:06.874  assistant   rec update   <- its record restored first
03:13:07.356  assistant + wDSP rec update
03:13:32      new input thread AudioIn_2E: Sample rate 16000 Hz, patch 18
              wDSP  riid 415 (same id): client 48000Hz, dev=1ch 16000Hz
              assistant riid 447:       client 16000Hz, dev=1ch 16000Hz
```

What this means:
- **The app never learns that anything happened.** `libaudioclient` re-creates the record inside the
  same `AudioRecord` object; `read()` returned no error, our read loop logged on without a gap, the
  recording id did not change. Code that waits for a read error to reopen waits for ever.
- **The rate of the re-created input is set by whichever client restores first** — the same "first
  client sets the rate" rule as at boot, replayed in a race nobody controls. Here the assistant won,
  so our 48 kHz client is now fed from a 16 kHz device: nothing above 8 kHz, while every number the
  capture itself can see (its format, its id, its state) still says 48 kHz.
- The only signal is the platform's own recording-configuration change — the `rec update` lines,
  which `AudioManager.AudioRecordingCallback` delivers to an app with the device format in
  `AudioRecordingConfiguration`. ❓ Not yet used or tested by wDSP.
- 📻 **And our session's noise suppression came back ON.** Our effect handles died with the server
  (`W/AudioEffect IEffect died`), so the "switched off" that wDSP set at open was lost. Read at
  03:16 from `dumpsys media.audio_flinger`, effect chains on the new 16 kHz input:

  ```
  session 265 (wDSP)       Noise Suppression (AOSP)        Enabled y  Suspended n   <- acting on our samples
  session 289 (assistant)  Acoustic Echo Canceler (sprd)   Enabled y  Suspended y
                           Noise Suppression (AOSP)        Enabled y  Suspended y
  ```

  So after a restore our capture is narrow **and** noise-suppressed, and nothing in the app knows.
  The input reports `Audio source: 1 (AUDIO_SOURCE_MIC)` for both clients, although wDSP asks for
  `UNPROCESSED`.
- 📻 The capture itself stayed alive: samples kept flowing (the gate log at 1 s intervals, rms up to
  1919), and the status bar bars moved — with the top five of 32 bands flat, i.e. nothing above 8 kHz.
- 📻 **The recording callback does see it** — repeated at 03:43 with wDSP `ed42121`, which listens to
  `AudioRecordingCallback` for its own session's device rate (on-unit logger, so logd could not
  prune it):

  ```
  03:43:02      setprop ctl.restart audioserver
  03:43:04.706  assistant rec update, 04.707 wDSP rec update   (restored; assistant 1 ms first)
  03:43:04.708  wDSP: "recording callback: the input under our recorder runs at 16000 Hz"
  03:43:04.786  wDSP: "input narrow (...) - taking it back through root"
  03:43:04.941  root force-stop googlequicksearchbox, 05.047 googleassistant
  03:43:05.079  wDSP rec start (new record): "reopened the microphone: ok"
  03:43:05.753  wDSP: "own stream: -0.0 dB above 8 kHz - full band"
  03:43:06.998  assistant rec start, joins: patch 24, dev 48000 Hz, client 16000 Hz
  ```

  From the restart to a full-band capture: under 3 s; the device rate arrived 2 ms after the
  restore. The owner saw the top bands move again. The effect chains afterwards repeat the
  last-starter rule below: wDSP's AEC+NS suspended, the assistant's NS acting.

#### Whose noise suppressor is acting: the last client to start — 14.09.2026

📻 `/vendor/etc/audio_effects.xml` on the owner's unit attaches default pre-processing per source:
`mic`, `voice_communication` and `voice_recognition` get **AEC + NS**; `unprocessed` and
`camcorder` get nothing. But wDSP's `UNPROCESSED` capture is reported as `src:MIC` everywhere, and
opened with **no effect handles of our own** (wDSP `6ba2839`+, 03:36) its session carried the
platform's AEC and NS all the same. Read from the effect chains, same input, both clients at 48 kHz:

```
03:16 (after the audioserver restore; wDSP restored LAST)
  wDSP      NS  Enabled y  Suspended n   <- acting
  assistant AEC, NS  Enabled y  Suspended y
03:36 (wDSP opened, root stopped the assistant, assistant came back 2 s LATER)
  wDSP      AEC, NS  Enabled y  Suspended y
  assistant NS  Enabled y  Suspended n   <- acting
```

So on a shared input exactly one session's chain acts, and here it was the client that started last
(both are `MIC`, so priority does not separate them). 📚 Pre-processing effects are attached to the
HAL input stream, so the acting chain processes what **every** client reads — AOSP design, not
re-measured here. ⇒ Whether wDSP's spectrum is noise-suppressed by the platform is decided by the
start order, not by wDSP: after a wake the assistant reopens after us, so its NS would be the one
acting. ❓ What that does to our spectrum has not been measured; the owner's mid-band dip that night
was our own gate, not this (see the audioserver section above).

🔴 Consequence, owner's decision 14.09.2026: **wDSP does not operate these effects at all.** Switching
our own off, as the capture did until then, switched off the chain that happened to be acting — for
the assistant too — and "did it work" became a function of who started last. Capture sits on the
stream as the platform gives it. A cabin sweep, which does need a clean stream, is a separate
question.
- *(owner, the same night, before the restart)* After the sleep, at volume 2 with the air
  conditioning running on wake, the microphone spectrum read hot at both ends with a clear dip in
  the middle — his explanation: **wDSP's own noise gate** ("шумодав": the capture's floor and the
  analyser's floor, which the wake handler forgets on purpose since `6ba2839`) took the air
  conditioner as the cabin's floor and pressed down the band it fills. That is our analyser doing
  what it was built to do, **not a platform effect**. ⚠️ The first version of this paragraph read
  "шумодав" as the platform's noise suppressor and tied the observation to it — a misreading of the
  owner's word; in this project the word means our gate (see HANDOFF, the gate-threshold slider).
- Playback came back by itself (`restoreTrack_l`), media on `AudioOut_D` again.

#### A phone call sits beside our capture, not instead of it — 13.09.2026

📻 Four Bluetooth calls with our 48 kHz `UNPROCESSED` capture held throughout. The call's recorder
appears as `uid:0`, empty package name, its own session; our capture logged without a gap for the
whole call; **the owner heard no echo and no noise at the far end, on two separate calls.** Effects
are per session and the BT stack brings its own. ⇒ The microphone does not have to be released for
calls (owner's decision, 14.09.2026: "не треба віддавати мікрофон, дріт доказав"). Full call trace:
`08-VOLUME-AND-SOURCES.md`, "A Bluetooth call, measured end to end".

#### ⚠️ What can actually take the microphone: the vendor assistants, and they depend on the ROM

*(owner, 14.09.2026)* The Google assistant shares. The ones that **do not** are the vendor's own:
the Chinese "Toppal" assistant and the **TXZ** voice service that is built into these head units —
both run with **priority** over the microphone. On his own unit the owner forbade them the
microphone. ❓ The priority mechanism itself has not been measured here.

*(owner, 14.09.2026)* **TXZ and Toppal are one product, not two:** Toppal is the assistant, TXZ
(`com.txznet.*`) is its service, and it talks to the system through a bridge APK —
`com.qf.ailit.bridge`, which `NotKillAppsBeforeSleep.ini` on the owner's unit lists (so the table
above should be read as one assistant, not two columns). From his unit when it ran Jitu2: **Google
Assistant did not work until he denied the microphone to the TXZ service through adb** — and **the
assistant still heard**: denying TXZ's own permission did not deafen it, so its microphone arrives by
another path, presumably through that bridge. ❓ The path itself was not traced. ⇒ On a Jitu2 unit, do not expect a free-input window from these two; a measurement
that waits for one needs a time limit and then plain advice to restart the head unit.

Which of them exist depends on the Android ROM build, not the hardware:

| ROM project | Toppal | TXZ |
|---|---|---|
| **Haiwai** (overseas; the owner's unit, latest build) | absent — possibly removed by the vendor for good | 📻 only `com.txznet.debugtool` and `com.txznet.weather` present; no core service package, visible or hidden (`pm list packages -u` adds only `com.navimods.radio`) |
| **Jitu2** | present | present — "all that junk is there" |

🔴 Consequence: a tester on a Jitu2 ROM can have the microphone taken by a vendor assistant that the
owner's unit simply does not carry, and "full band on the owner's unit" says nothing about them.
Ask which ROM before reading a tester's capture report. ❓ The package names of Toppal and of the
TXZ core service are not known on this machine — find them on a Jitu2 unit before writing any
detection for them.

### When the microphone cannot be freed, sweep only where it hears

Sweeping to 20 kHz through a 16 kHz stream throws away more than half the signal: the energy is
emitted, never recorded, and the deconvolution has nothing to match it against. Stopping the sweep
at 7 kHz instead puts all of it inside the microphone's range.

Measured on a bench with an assistant that could not be stopped: before narrowing, the weaker
channels could not be measured at all; after, clarity of 13.8 and 20.6 dB and a normal result.

⚠️ The cost is not only sharpness. On a channel whose arrival is clean the answer does not move,
but on a smeared one it does: the same bench gave 1.32 ms full-band and 2.0 ms narrowed. Where
clarity is low, the bandwidth becomes part of the answer - one more reason to treat a low-clarity
delay as measured rather than known.

### 🔴 AudioPlaybackCapture hands over the microphone, not the stream

`AudioPlaybackCapture` exists here — the platform is API 29, exactly the version that introduced
it — and it opens, reads happily, and returns audio. That audio is **the built-in microphone**.

📻 Four measurements, any one of them enough:

1. `dumpsys media.audio_flinger` during a capture: our track has `Source 1` (MIC) and sits on
   `Input device: 0x80000004 (AUDIO_DEVICE_IN_BUILTIN_MIC)`. A `REMOTE_SUBMIX` input thread exists
   but stays inactive.
2. The level follows the **speakers**, not the digital side. MCU volume 6 → −13 dB; MCU volume 1 →
   −31 dB, with Android's `STREAM_MUSIC` pinned at 15/15 both times.
3. The mixer volume is not adjustable on this platform at all — the MCU and the external DSP do it
   — so the digital level could not have changed. The capture changed by 18 dB anyway.
4. With nothing playing it returns −30 dB of broadband noise at 99 % non-zero samples. That is a
   cabin, not dither.

Consequences:

- `addMatchingUid()` and `addMatchingUsage()` **do nothing**, and cannot: there is no mix to
  filter, only a microphone. Aiming at a package with no process at all still returns the music.
- 📻 Same with and without the BitPerfect module (measured with the module disabled and the unit
  rebooted), so it is not a policy the module replaces.
- 📻 Freeing the microphone first does not help either. The assistant was stopped with root — the
  bandwidth went from −64.6 dB to −5.8 dB above 8 kHz, so the microphone genuinely became ours —
  and the capture still routed to `BUILTIN_MIC`. The "one input stream" rule is not the cause here.
- 🪤 A tone your own app plays is always capturable by your own app, whatever its usage. Do not use
  a self-played tone to conclude anything about whether another app's audio would be withheld.

🧩 `r_submix` **is** present in `/vendor/etc/audio_policy_configuration.xml` and in the policy dump,
so the cause is not a missing module. ❓ Where exactly the request is turned into a microphone open
was not found.

### What the kernel will tell you for free

📻 `/proc/asound/card0/pcm<N>p/sub0/status` is `-r--r--r--` — **no root needed**, so it works on a
stranger's unit. A stream that is open reads:

```
state: RUNNING
owner_pid   : 9378
delay       : 1840
```

🔴 The useful part is **which device**. Media travels on `pcm3p` (`FE_ST_FAST`) with the factory
policies and on the primary one with the module installed — and that single fact says whether a
session-0 effect can hear anything, which is otherwise discovered by attaching one and waiting for
silence.

🪤 Two things it will not tell you, both learned the hard way:

- **Not who is playing.** `owner_pid` is the audio HAL — measured,
  `/vendor/bin/hw/android.hardware.audio@2.0-service` — never the application. Same pid whoever
  plays.
- **Open is not audible.** The radio app writes PCM silence to hold the player role, so a device
  reads `RUNNING` while what you hear is the analogue tuner. 🧩 Not fatal, because the radio
  announces itself in its own properties: *open and not radio* is a sound answer to "is anything
  really playing", and a cheaper one than waiting for an effect.

Read by `PcmStatus`, and printed in wDSP's system report next to the analyser block, so that
"nothing open", "fast open while the effect sits on session 0" and "open and sounding but silent to
us" can be told apart from a stranger's file.

### The hardware has its own taps, and they are switched off

🔬 `/proc/asound/pcm` lists `00-16: FE_ST_DUMP` (capture only) and `00-10: FE_ST_LOOP`.
`tinymix` exposes:

```
VBC_DUMP_POS        DUMP_POS_DAC0_E | DAC1_E | A1..A4 | V1..V2
S_VBC_DUMP SWITCH   Off
VBC_MUX_LOOP_DAC0   DAC0_SMTHDG_OUT | DAC0_MIX1_OUT | DAC0_EQ4_OUT | DAC0_MBDRC_OUT
```

⚠️ Both taps sit **after** the AudioFlinger mix, so neither can separate media from navigation —
those share one output thread and one stream type. See
[09-NAVIGATION-AND-BITPERFECT.md](09-NAVIGATION-AND-BITPERFECT.md).

🪤 `tinymix` is present in `/system/bin`; `tinycap` is **not**. Reading `/dev/snd/pcmC0D16c` needs
tinyalsa built into the app and either root or a module, because the node is `system:audio`.

### There is no way past the AGDSP with stock policies

`/vendor/etc/audio_pcm.xml` promises a `recognition` path on PCM `device=0`
(`FE_ST_NORMAL_AP01`, straight to the AP). Measured: `MIC`, `VOICE_RECOGNITION` and `UNPROCESSED`
all land on `device=2` (`FE_ST_CAPTURE_DSP`, `HAL frame count: 1920`). The HAL picks `mm_normal`
regardless of source.

⚠️ This contradicts the Android CDD and most advice on the internet, which state that
`VOICE_RECOGNITION` and `UNPROCESSED` bypass processing. On this platform they do not.

📌 What `/vendor/etc/audio_effects.xml` does and does not promise is measured further down, in
*"Suspending it from the app does not work, and measuring proves it does not matter"* — including why
`UNPROCESSED` escapes the chain in a bare capture and stops escaping it the moment something plays.

The remaining route is `mmap_noirq` on `device=1`, reachable only through AAudio with
`EXCLUSIVE` sharing from native code. Not tried yet.

### Capture effects can be switched off from an app — and must be put back

| | factory policies | with the BitPerfect module |
|---|---|---|
| `AcousticEchoCanceler.isAvailable()` | true | true |
| `NoiseSuppressor.isAvailable()` | **false** | **true** |
| `AutomaticGainControl.isAvailable()` | false | false |
| state on the session | off | **on** |

```java
AcousticEchoCanceler aec = AcousticEchoCanceler.create(sessionId);
aec.setEnabled(false);   // returns 0 = SUCCESS, and getEnabled() confirms it
```

Any measurement that plays a sound and listens for it has to switch these off: an echo canceller
exists to remove exactly the sound we are playing, and noise suppression exists to remove steady
signals, which is what a test tone is.

🔴 **Put them back.** They belong to the platform, and on a unit with custom policies they are on
deliberately so that phone calls are intelligible. `MicProbe.suspendCapturePreprocessing()` records
the previous state and `Suspension.restore()` returns it.

`NoiseSuppressor.isAvailable()` doubles as a free test for custom audio policies — see
`HardwareProfile.captureHasVoiceProcessing()`.

---

## 3. Latency: measured, not declared

`getOutputLatency()` says **125 ms**. The track's own dump says **558 ms**. The measured distance
from the moment a sample is seen by the analyser to the moment it reaches the cabin is **53 ms**.

The declared figure counts buffering that has already elapsed by the time an effect sees the
samples, which is why it is so far out.

### How it is measured (`LatencyProbe`)

Eight quiet 2 kHz bursts on our own session, timed three ways:

| what | from | gives |
|---|---|---|
| when a frame reached the hardware | `AudioTrack.getTimestamp()` | the Android side, exactly |
| when we saw that frame | `Visualizer` on the same session | our measurement point |
| when it came back through the cabin | `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` | a ceiling on the answer |

🟢 The input timestamps here are genuine: 208 of 208 came from the HAL, so the recording buffer is
already accounted for and does not inflate the result.

### Measured, four runs in each configuration

| configuration | capture → DAC | capture → ear |
|---|---|---|
| factory policies | 14.4 – 15.8 ms | **52.8 – 54.0 ms** |
| BitPerfect module | 8.4 – 12.3 ms | **55.3 – 61.0 ms** |

The difference at the ear is about 4 ms, smaller than the spread between bursts, so **there is no
need to branch on the configuration** — one measurement per head unit covers both. The result is
stored in `spec_latency_base_ms` and the ±250 ms trim sits on top of it.

⚠️ `PERFORMANCE_MODE_LOW_LATENCY` is refused (`getPerformanceMode()` returns 0), and the minimum
`AudioTrack` buffer here is 23080 bytes = 120 ms. The "fast" output is assigned by policy; an app
cannot ask for it.

### Traps found while building this

1. Attaching the effect before the track plays — see §1.
2. `lastHitFrame = Long.MIN_VALUE` in `frame - lastHitFrame > COOLDOWN` overflows, so the condition
   is never true and the microphone appears deaf while the peak is −22 dBFS.
3. A threshold of "N times above the floor" does not work in a cabin: 44 triggers for 8 bursts,
   two of them *before* the sound was played. Replaced by a matched filter — correlation against
   the burst's own energy envelope — which gave a prominence of ~50 and a spread of 5 ms.

---

## 4-bis. What the Visualizer tap really delivers — measured on pink noise, 14.09.2026

📻 The owner's unit, `com.qf.musicplayer` looping the EMMA 2018 pink-noise WAV, flat preset. wDSP dumped
2.7 s of raw `Visualizer.getWaveForm()` blocks and the stitched stream its analyser reads
(`PROBE_SESSION --ei wav 2700`), analysed on the PC:

- **The tap runs at 44 100 Hz** (`getSamplingRate()` 44100000 mHz), capture size 1024,
  scaling mode 0 = `SCALING_MODE_NORMALIZED`, measurement mode none; session 0.
- **The stitched stream is flat**: third-octave bands within ±1.5 dB from 18 Hz to 14 kHz, computed
  both independently (Welch) and exactly as the native analyser does. 17.8 kHz reads −5.4 dB (the
  44.1 kHz path's own top end), 22.4 kHz −25 dB (above Nyquist).
- Raw blocks: a poll every 13 ms took 576–720 new samples; overlaps matched byte for byte; the signal
  used 226 of 256 levels; 0 discontinuities.

So neither the tap, the 8-bit normalisation nor the stitching shapes the spectrum. A shape seen on
screen in the calculated mode comes from what is added after the bands: the DSP model and the cabin
curve (wDSP 14.09.2026: the model's curve reached the analyser only when a capture started, so a
preset change was drawn with the previous preset's curve).

⚠️ Consequence for any 32-band display built on this tap: a band above ~20 kHz has nothing in it.

## 4. Capture does not give a continuous stream

```
getMaxCaptureRate()   = 20000 mHz  → 20 callbacks per second, the platform's ceiling
getCaptureSizeRange() = [128, 1024]
1024 samples at 48 kHz = 21 ms of audio every 50 ms
```

29 ms out of every 50 simply do not exist. Concatenating the blocks gives a signal with time
compressed by a factor of 2.3, and any analysis below the block rate is meaningless.

The cure is to poll *faster* than a block lasts — every 9–12 ms — so consecutive reads overlap, and
to find the overlap by normalised cross-correlation. Measured: 0 discontinuities over 1126 frames
synthetically, 2 over thirteen minutes of real music (both at track changes, where the stream
really did break).

⚠️ Polling and analysis must live in different threads. While the FFT ran in the polling thread,
the long window delayed the next poll, and a late poll is a missed piece of the ring buffer — a
discontinuity the stitcher then had to repair.

---

## 5. Small things that cost hours

- **Our own package name contains "radio".** A substring test for the tuner decided the radio was
  playing whenever our own UI came to the front. Compare with `startsWith(getPackageName())` first.
- **`getVolumeStateMute()` is honest, but gating on it is wrong.** The status bar widget
  unregisters from the engine when hidden, and it is the only listener once the main window is
  closed — so muting the amplifier tore down the whole measurement chain.
- **Bluetooth audio is invisible to the Java API.** It is produced by the native `gocsdk_zj`
  daemon through libmedia with no `PlayerBase`, so `getActivePlaybackConfigurations()` is empty
  while music plays. The session sweep finds it anyway, because it attaches by session number.
- **The boot path is not the launch path.** At boot the service comes up with no activity, so a
  fault that only appears after a reboot lives there. `BOOT_COMPLETED` cannot be replayed from the
  shell (`Background execution not allowed`); use
  `am start-foreground-service -n com.radiorubka.wdsp/.McuService`.
- **`uiautomator dump` returns "could not get idle state"** while the analyser is animating. Use
  `dumpsys activity top -a`, but note its coordinates are relative to the parent, not the screen —
  a screenshot is more reliable for finding something to tap.
- **CPU has to be measured per thread.** Drawing cost three times as much as the measurement
  (RenderThread 1142 ticks against Capture 425).

---

## 6. The player role is four independent things

This section comes from the other application on this platform — a replacement radio — and it is
platform knowledge rather than radio knowledge. Anything here that plays sound, or wants the media
keys, or wants to appear in the launcher's media card, runs into it.

📻 Being "the player" on this machine is **four separate mechanisms**, and none of them can be
derived from the others:

| what | who grants it | what breaks if you confuse it |
|---|---|---|
| **audio focus** | Android's `MediaFocusControl` | you get mode 4 (MPU) or mode 2 (radio) *by package name* — the radio channel cannot be asked for |
| **the MCU mixer channel** | the MCU, on command | radio sound exists only on channel 2; the platform will undo `setChannel(2)`, but not a `tune` |
| **the media session** | you | an active session is the claim on the buttons and the launcher card |
| **a PCM stream that is not silent-looking** | you | without it the system ducks you after about 30 seconds, because a player that outputs nothing is treated as muted |

🔴 **Do not derive one from another.** Three separate bugs in one day grew out of exactly that:
`isPlaying()` implemented as "do I hold focus" left the play button stuck forever; session state
computed from focus made the widget show the opposite of reality; and a watchdog with the rule
"focus held, therefore the stream must be running" **cancelled the user's own pause** six seconds
after they pressed it.

Ask what you mean: *am I playing* is `playerActive && !userPaused`; *do I claim the buttons* is the
session state; *is the radio audible* is the MCU channel.

🔬 **The launcher chooses which widget to show purely by package name**, in one receiver, with no
other logic anywhere:

```java
if (pkg.startsWith("com.qf.bluetooth") && streamType == 3) → Bluetooth widget
else if (pkg.startsWith("com.android.fmradio"))            → radio widget
else if (pkg.startsWith("com.qf.musicplayer"))             → the native music widget
else if (checkAppIsThirdPartyMedia(pkg))                   → third-party media widget, with your icon
// nothing matched → the widget does not change
```

🔬 And the audio path is granted the same way: `MediaFocusControl` compares
`startsWith("com.android.fmradio")` in five places. A package that does not match gets channel 4
put back under it every time anyone else releases focus.

❌ **Faking the package name does not work.** Passing `com.android.fmradio` to the focus request
technically reaches the platform, but `AppOpsManager` one level deeper throws
`SecurityException: not allowed to perform TAKE_AUDIO_FOCUS` and the service dies at creation. The
lesson is about method rather than code: the check was absent from `AudioService`, which was read
first, and present in `MediaFocusControl`, which was not. **Read the chain to the end.**

📻 **Bluetooth is a source of focus storms**: `com.qf.bluetooth` released focus 24 times in 110
seconds during one scan. The platform then correctly moves the channel; an app that fights back on
every *request* rather than on the *fact* of a change becomes the source of the spam itself. There
is a proper event to listen for — the platform broadcasts `com.qf.action.VOLUME_CHANGED` **after**
the channel actually changes.

🤝 And when in doubt, yield. The owner's rule: *"better not to be pushy than to be guaranteed to
wake up"*. Restore playback after ignition only if the path is free; do not take the channel back
"just in case"; report `STOPPED` rather than `PAUSED` when somebody else is playing, because paused
means "resume me" and makes you the target of the media keys.

---

## 7. The same head unit has two audio configurations, and you can tell them apart

Scattered facts about this are in the sections above; this is the summary, because it decides how a
measurement should be interpreted and because the owner wants a utility that manages the module.

**BitPerfect Audio** is a Magisk module some owners install. It replaces the audio policies. It is
not a small cosmetic change — it moves where media plays, and it turns on capture processing that
is off from the factory.

| | factory policies | with BitPerfect |
|---|---|---|
| media output | `AudioOut_15`, **fast** | `AudioOut_D`, **primary** |
| `Visualizer(0)` | 📻 measures silence | 📻 works |
| `AcousticEchoCanceler.isAvailable()` | true | true |
| **`NoiseSuppressor.isAvailable()`** | 📻 **false** | 📻 **true** |
| `AutomaticGainControl.isAvailable()` | false | false |
| capture effects on a fresh session | 📻 off | 📻 **on** |
| capture → DAC | 📻 14.4 – 15.8 ms | 📻 8.4 – 12.3 ms |
| capture → ear | 📻 52.8 – 54.0 ms | 📻 55.3 – 61.0 ms |

🧩 **`NoiseSuppressor.isAvailable()` is a ready-made detector for custom policies** — no root, no
permission, one call. Nothing else distinguishes the two configurations that cheaply.

🧩 The module most likely removes or narrows the *fast* mixPort, leaving media nowhere to go but
primary. That is why an output-mix effect, which AOSP hard-codes onto primary, suddenly starts
seeing signal — it is a side effect, not a feature.

### What follows for anything that measures

🔴 **Do not branch the code on which configuration is present.** The difference by ear is about
4 ms, smaller than the spread between runs. One measurement on the actual unit settles it; two code
paths would be two things to maintain for nothing.

🔴 **Do branch on the capture effects.** With BitPerfect the echo canceller and noise suppressor are
**on** by default, and an echo canceller exists precisely to remove the sound you are playing. Any
frequency-response or arrival measurement must disable them explicitly on its own session — and put
them back afterwards, because on these units they were switched on deliberately:

```java
AcousticEchoCanceler aec = AcousticEchoCanceler.create(sessionId);
aec.setEnabled(false);   // returns SUCCESS; getEnabled() confirms false
```

📻 Verified in both directions on a real unit: `AEC was ENABLED, now off` … `AEC restored to
enabled`. This is what makes a utility to manage the module feasible at all — within your own
session, the processing is yours to control.

### Two things that are true in both configurations

🔬 **`VOICE_RECOGNITION` does not reach PCM device 0** the way `/vendor/etc/audio_pcm.xml` promises.
The HAL takes `mm_normal`, device 2, `FE_ST_CAPTURE_DSP`, through the AGDSP — visible as
`HAL frame count: 1920`. Getting past the AGDSP is not available with stock policies.

🔬 **`PERFORMANCE_MODE_LOW_LATENCY` is refused** — `getPerformanceMode()` returns 0 and the minimum
`AudioTrack` buffer here is 23 080 bytes, about 120 ms. "Fast" is assigned by the policy, not
requested by the application.

⚠️ And on units fitted with the lesser sound processor, BitPerfect is essentially never installed —
it produces digital noise instead of sound there. So a BD37544 unit is almost certainly on factory
policies. 🧩 The likely mechanism is now known — see
[10-BITPERFECT-MODULE.md](10-BITPERFECT-MODULE.md) §7.

---

## The audio path can be dead while everything reports healthy

📻 Measured 28.08.2026, after a normal reboot: players silent, radio fine, `audioserver` alive and
`dumpsys media.audio_policy` showing the expected policies. Nothing looked wrong. The stream itself
told the truth:

```
$ cat /proc/asound/card0/pcm3p/sub0/status
state: SETUP
owner_pid   : 24823        ← this process no longer exists
trigger_time: 0.000000000  ← never started
hw_ptr      : 0
```

`SETUP` with a zero trigger time means the stream was opened, configured, and abandoned. A dead
owner keeps the substream, and every later playback lands in a device that will never run. The cure
is one line, and it is instant:

```bash
setprop ctl.restart audioserver     # then state: RUNNING, trigger_time non-zero
```

🔬 **And the restart that strands it comes from the platform itself**, not from any module —
`McuManagerService.onVersionInfoChanged`, three lines above the hardware decode:

```java
if (TextUtils.isEmpty(oldMcuVersion) || !TextUtils.equals(str, oldMcuVersion)) {
    SystemProperties.set("ctl.restart", "audioserver");
}
```

On a cold boot the old version is empty, so this fires **every time**, at whatever moment the MCU
gets round to reporting. If the HAL has a stream open by then, that stream is what gets stranded.

⚠️ **A watchdog that checks policies cannot see this.** BitPerfect's does exactly that, prints
`SUCCESS: audioserver is running with BitPerfect policies!` and goes to sleep — the policies really
are loaded; it is the path underneath them that is dead. Check `state:` in
`/proc/asound/card0/pcm3p/sub0/status`, not `dumpsys`.

## The capture source is ignored; the effect on the session is not

📻 28.08.2026, two probes back to back on the same unit, watching the mixer *during* the capture:

| source | `VBC ADC0 DG Set` while recording | rms | peak |
|---|---|---|---|
| `VOICE_RECOGNITION` (6) | `4 4` | −31.2 dBFS | −20.2 |
| `UNPROCESSED` (9) | `4 4` | −31.0 dBFS | −19.4 |

Identical. 🔬 `audio_pga.xml` promises `UnprocessRecord/VBC_ADC0_DG = 0x18` (24) against `Record`'s
`0x04`, but the control never moves — **the HAL does not honour the source**, and that XML block is
dead. Choosing `UNPROCESSED` to get a raw microphone achieves nothing here.

What *does* sit on the path is a **pre-processing effect**, and it is visible in
`dumpsys media.audio_flinger` while recording:

```
session 4657   48 kHz   client = com.radiorubka.wdsp        ← ours
    Noise Suppression        Enabled=y   Suspended=n         ← running

session 4649   16 kHz   client = the assistant
    Acoustic Echo Canceler (sprd cvs) + Noise Suppression
                             Enabled=y   Suspended=y         ← not ours, parked
```

🔑 **Read the sample rate to tell whose session is whose.** A 16 kHz input session belongs to the
assistant or telephony; ours is 48 kHz. The hardware AEC ("sprd cvs") attaches only to the 16 kHz
side and never to a 48 kHz capture — so on this platform the effect worth removing before a
measurement is **NS**, not AEC.

### Suspending it from the app does not work, and measuring proves it does not matter

🔬 `/vendor/etc/audio_effects.xml` binds the preprocessing **by source name**:

```xml
<preprocess>
  <stream type="mic">                 <apply effect="aec"/> <apply effect="ns"/>
  <stream type="voice_communication"> <apply effect="aec"/> <apply effect="ns"/>
  <stream type="voice_recognition">   <apply effect="aec"/> <apply effect="ns"/>
</preprocess>
```

`unprocessed` is absent — so `UNPROCESSED` should escape it, and in a bare capture it does:
📻 `State 000` (INIT), `Enabled=n`. **But not while something is playing.** During a sweep the same
source gives `State 003` (ACTIVE) on our own session, exactly as `VOICE_RECOGNITION` does. The
policy attaches the chain when an input and an output are live together, and the source does not
override that.

🪤 And suspending from the app is an illusion: `NoiseSuppressor.create(session)` returns *our*
handle. Disabling it leaves the one the policy attached running — the app logs "NS was off, now
off" while AudioFlinger reports the chain ACTIVE. Both statements are true, about different
objects. `MicProbe` and `RoomMeasurement` both call `suspendCapturePreprocessing`; neither can
switch off what the policy owns.

📻 **So does it spoil a measurement? No — measured, not assumed.** Two cabin sweeps, same car, same
volume, minutes apart:

```
                20    31    50    80   125   200   315   500   800  1.25k   2k  3.15k   5k    8k  12.5k  20k
VOICE_RECOG.  −30.0 −29.6 −28.4 −28.4 −30.6 −14.4 −8.0  −8.9  −7.9  −8.2  −8.2  −8.3  −8.6 −14.2 −54.2 −57.3
UNPROCESSED   −30.6 −30.2 −29.1 −29.2 −31.1 −14.6 −7.9  −8.9  −8.0  −8.1  −8.2  −8.2  −8.5 −14.2 −55.5 −58.8
```

0.0–0.2 dB across the working band, and the low end moved the *wrong way* for the "NS eats the
bass" theory — without it the bass reads slightly **lower**. Arrival 1.6 vs 1.7 ms, clarity 26.6 vs
27.5 dB, peak −6.8 vs −6.7. All within what two consecutive sweeps of the same cabin differ by
anyway.

⚖️ **The same config, read alone, has produced the opposite conclusion elsewhere — do not let it back
in.** Gemini's own knowledge tree carries a section (`12-MICROPHONE-PATH-HAL` §5, 10.09.2026) built
on this very file: `unprocessed` is absent from `<preprocess>`, therefore — it argues — the source is
a clean full-range channel needing no root. The evidence is right (verified on the unit 12.09.2026:
`grep -c unprocessed /vendor/etc/audio_effects.xml` → 0) and the conclusion is wrong here, because
the policy attaches the chain when an input and an output are live together, and because every source
lands on `FE_ST_CAPTURE_DSP` anyway. What actually wins the microphone is **being first on the
stream** — open at 48 kHz before the assistant and it attaches to ours (see *"Open first, and the
assistant rides along"*, 11.09.2026); root buys exactly one action, stopping the assistant when the
stream is already held at 16 kHz. That is why importing that section as a second document was
dropped on the owner's decision, 12.09.2026: one subject, one place.

🧩 The low-end roll-off is the microphone on the dashboard and the absent subwoofer, not the
suppressor. `UNPROCESSED` is kept in `RoomMeasurement` because it costs nothing, falls back
cleanly, and may matter on a unit with a different policy — **not** because it was shown to improve
anything here.

## A volume written while no source is current never reaches the hardware

📻 Same session. `sys.media.vol` was set to 5, read back as 5, and the cabin stayed silent — because
`sys.current.vol.type` was **empty**: after a reboot the platform publishes no current source until
something has actually behaved like a player. `VolumeState.setVolumeVal` only calls the MCU when
`volType.equals(sys.current.vol.type)`, so the write went into a property and stopped there.

Starting and stopping a player once fixes it for good:

```
before:  sys.current.vol.type = []            → writes are inert
after:   sys.current.vol.type = media_type    → writes reach the MCU
```

and only then does the wire show what it should:

```
mcu_services: audio channel change to MPU, current_audio_source=com.radiorubka.wdsp
APP2MCU - writeToUart: [ff fd fe 1a 01 40 5a a5 13 e4 …]
VolumeState: getVolumeVal - volume:6 - propSave: sys.media.vol - defVol: 1
```

🔴 **Corrected 28.08 — it is not the media session.** 📻 `dumpsys media_session` during a sweep
shows wDSP with **no session of its own**; it appears only under *"Audio playback (lastly played
comes first)"*, which is where the launcher's media widget takes its icon from. So playing audio is
already enough to become the current source, and adding a `MediaSession` would fix nothing.

What actually publishes `sys.current.vol.type` is a **source switch on the MCU** — starting the
radio does it, playing Android audio does not. 📻 Verified twice: with music playing and the PCM
running at `S24_LE`, `sys.current.vol.type` stayed empty; starting and pausing the radio filled it
with `media_type`, and only then did a written volume reach the wire.

🧩 So the four independent parts of "being a player" hold, but the missing one here was the **MCU
channel/source state**, not a session object.

Note `defVol: 1` in that log line. 🔬 `persist.sys.main_volume` is **the start-up volume slider in
CarSettings** — a normal user-facing setting, not a stray value. On this unit it is deliberately set
to 1 so that an autonomous session cannot startle the household with a loud reboot.

🔴 Which makes BitPerfect's heal block a defect: it rewrites `15` to `12`, and on a 0…32 scale `15`
is well inside what a person can pick. Not to be confused with `persist.qf.arm.default.volume`
(0…15, the Android mixer at unity) — that one is deliberate, dates from 4.18, and stays. See [10-BITPERFECT-MODULE.md](10-BITPERFECT-MODULE.md) §4.
