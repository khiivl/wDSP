# Working on this platform without wasting runs

The rules below are not style preferences. Every one of them was written after a specific wrong
conclusion that cost hours, and the cost is stated so the rule is worth remembering.

## 1. Provenance before logic

Mark every claim with where it came from:

```
🔬 read in firmware / decompiled code (address, or file and line)
📻 measured on the wire
🧩 inference from the two above
❓ guess, not verified
```

**A guess written down as a fact is worse than no note at all.** Two examples from this project: a
"clean reference capture of the original application" turned out to have been taken on a unit whose
MCU was polluted by another app's hook, and an initialisation table was read from an address that
held code rather than data. Both were believed for a while and both sent work in the wrong
direction.

The same principle applied to logging solved a bug in one attempt that three blind guesses had
missed: put a permanent provenance tag in the log line — which method, which line, called from
where — **before** the third hypothesis, not after.

## 2. Verify on the wire yourself

🔴 **A debug broadcast and a button in the UI are different code paths.** Something that works
through one can be dead through the other; that has happened here. If a feature is going to a
person, test the path the person will use.

⚠️ **`am broadcast` without `-n` goes nowhere** and still prints `Broadcast completed: result=0`.
Android 8+ blocks implicit broadcasts to manifest receivers, `exported="true"` does not help, and
the real reason only appears in the log if you look for it:

```
W BroadcastQueue: Background execution not allowed: receiving Intent { act=... }
```

So: name the receiver explicitly, and when a command "did nothing", first check whether the
receiver logged anything at all. No line from the receiver means the command never arrived and
every other hypothesis is unnecessary. (A receiver registered at runtime by a **running service**
does not need this — but then check the service is actually alive.)

## 3. Before any tap, ask who is on screen

```bash
adb shell "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
```

Coordinates are not an address. `input tap 394 616` hits whatever is on top, and the head unit is a
shared screen — the owner works on it at the same time you do. Taps have gone into somebody else's
application in the middle of their test.

A silent log is **not** evidence that a handler is dead until you have shown the window had focus.
That mistake produced the conclusion "AF and TA are broken"; they were fine, the taps were landing
in the launcher.

## 4. What a reboot does and does not reset

| to reset | what is needed |
|---|---|
| your service, sessions, flags, UI | `adb reboot` |
| **the MCU**: tuner state, RDS buffers, region, frequency | **power only** — ACC OFF |

🔴 "I rebooted, so the state is clean" is half false, and the half that is false is the half that
usually matters. Only a person can power-cycle the unit.

## 5. Installing

- `adb install -r` **keeps the database and preferences**. A defect that lives in the "empty
  database" branch will not reproduce, and one such defect hid for days behind exactly this.
  A hard reinstall and a cold start are the only sources of truth.
- `force-stop` does not guarantee a dead service — `START_STICKY` brings it back with a null intent
  and it re-acquires everything in `onCreate`. Check with `ps -A`.
- ⛔ **Do not install while a person is testing on the device.**
- After installing, check the version code you actually installed. Working directories reset
  themselves between commands; the wrong APK has been installed from the wrong project more than
  once. Use absolute paths.

## 6. adb on Windows

💥 **`adb exec-out` corrupts binary data** — it goes through LF→CRLF conversion. 24 546 image files
were pulled once and every one of them was broken in the same place. Use `adb pull`, which is
binary-safe.

💥 **MSYS rewrites device paths** in Git Bash: `adb push x /data/local/tmp/` becomes
`C:/Program Files/Git/data/local/tmp/`. Prefix the command with `export MSYS_NO_PATHCONV=1`.
Redirection with `>` still understands `/d/...` because bash handles that itself.

💥 adb cannot write directly to some Windows paths and does not create files with Cyrillic names —
pull into a scratch directory and copy afterwards.

💥 PowerShell here writes UTF-16 by default; a log captured that way will not grep. Ask for UTF-8
explicitly.

## 7. Logs

The logcat buffer on this unit is filled by `mcu_services` printing every serial frame, and it
scrolls away in minutes. Clear it immediately before a measurement, or capture to a file in the
background while the measurement runs — a `logcat -d` afterwards may well find nothing at all,
which looks exactly like "the feature did not run".

`uiautomator dump` gets killed for memory on this unit fairly often. A screenshot pulled with
`adb shell screencap -p /sdcard/s.png` then `adb pull` is the reliable fallback.

### Watching sleep and wake-up: a shell daemon on the unit, not adb and not logcat afterwards

*(owner, 14.09.2026)* Two things make sleep invisible from the PC: **the power manager clears the
logcat buffer**, and adb over Wi-Fi drops while the unit sleeps. What does work:

- **Shell daemons survive sleep.** A process started in the background from a shell - root or not -
  keeps running through ACC off and on, and it is **among the very first things to wake**, so it can
  write down anything from the first moments after wake-up. It does not survive a reboot.
- 📻 **An app process survives it the same way, native code and open audio streams included**
  (owner's suspicion, confirmed 14.09.2026). wDSP across ~15 minutes of sleep: the same pid, started
  01:48:18 and still running at 02:33; its worker, render and GPU threads carry start times from
  before the sleep; and the capture thread - an `AudioRecord` read loop - logged a sample at
  02:26:50.090, **82 ms before the Google assistant opened anything and 364 ms before `ACC_ON` was
  delivered**. Nothing was restarted; everything was frozen and thawed. It is a real
  suspend-to-RAM, not a screen-off: `/sys/power/mem_sleep` is `s2idle [deep]` and
  `/d/suspend_stats` counted 4 successful suspends since that boot. ⇒ Code that "restarts things on
  ACC_ON so they come back" is restarting things that never went away.
- So start the observer on the unit, detached, writing to the card, and read the files afterwards:

```sh
# as root, detached from adb:  su -c 'setsid nohup /data/local/tmp/watch.sh >/dev/null 2>&1 </dev/null &'
logcat -c
nohup logcat -v time -f /sdcard/Download/wDSP/sleep_logcat.txt <tags>:V *:S >/dev/null 2>&1 &
while true; do
  { echo "=== $(date +%H:%M:%S)"; <snapshot commands>; } >> /sdcard/Download/wDSP/sleep_audio.txt
  sleep 3
done
```

`logcat -f` keeps what it has already read even when the buffer is cleared under it. For state that
logcat does not carry, snapshot it in the loop - e.g. `dumpsys media.audio_flinger` for the input
sample rate and `dumpsys audio | grep 'rec '` for who opened the microphone. The recording event log
in `dumpsys audio` itself lives in system_server and does not roll the way logcat does.
Remember to kill the daemon afterwards (`pkill -f watch.sh; pkill logcat`). ⚠️ Not as
`adb shell "su -c 'pkill -f watch.sh; …'"`: `-f` matches the full command line, and that line is
the `su -c` itself - it kills its own shell (exit 143) and may stop before the rest runs. Check with
`ps -A -o PID,ARGS | grep watch` afterwards.

⚠️ **After a cold boot the main log buffer is 256 KiB and our process fills it in under a minute.**
`logcat -g` on the owner's unit: `main` 256 KiB. The platform's `android.qf.os.VolumeState`, called
from wDSP's 100 ms poll, logs three `D/VolumeState` lines per call inside our pid; with that, the
capture's decision 0.8 s after it opened at boot was already gone when read 12 s later. What
survived and settled it: `dumpsys media.audio_flinger` keeps closed input threads with their
`Local log` (`AT::add` / `AT::remove` with pid, session and rate) — our capture was the only client
of a **48000 Hz** input from 03:53:50.765 to 03:53:51.567.

⚠️ **Our own once-a-second log gets our important lines pruned.** logd trims the chattiest uid
first; with the capture's gate line every second, wDSP is that uid, and 40 seconds after an install
its start-up lines ("started", "own stream", the heal) were already gone while system lines from the
same second remained (14.09.2026). Read such events from `dumpsys audio` (`rec start/stop`) and from
system tags (`ActivityManager: Force stopping`), or run the on-unit logger before the event.

### 🟢 Every boot logged from its first seconds: the Magisk module in `tools/wdsp_bootlog_module`

*(owner's order, 14.09.2026)* Installed on the owner's unit. Magisk's late_start service starts it
about 19 s into a boot, and logcat hands over its whole buffer first - the first boot read back from
2.455 s. One folder per boot, `/data/local/tmp/bootlog/NNNN` (`latest` names the current one):
header with `sys.boot.reason` and uptime / wall-clock pairs, `dmesg`, **every buffer unfiltered with
seconds-since-boot timestamps until boot_completed + 240 s** (boot order reads directly, also after a
power removal when the clock is set late), then a rotated run log with `VolumeState` / `VolumeManager`
/ `chatty` silenced, and `dumpsys audio` / `audio_flinger` when the boot window closes. Newest 15
boots kept; about 10 MB per boot window. Being a shell daemon, it keeps logging through sleep.

It already paid for itself: a `logcat -d` from a root script found nothing 20 s after an audioserver
restart, while the module's run log held every line. Build only in WSL (`build.sh` refuses CR bytes;
`.gitattributes` pins LF, since `core.autocrlf` is on in this repo). Install:
`adb push out/wDSP_bootlog.zip /data/local/tmp/` then `su -c 'magisk --install-module …'`, reboot.
Reason strings seen so far: `reboot,adb` for `adb reboot`, `reboot` for others; a power removal -
not yet seen.

⚠️ `dmesg` on this unit is useless for the suspend timeline: a vendor `system_rescue` process runs
`ps` every 5 s and floods the ring with SELinux audit lines, so `PM: suspend entry/exit` has rolled
out within minutes. `/d/suspend_stats` keeps the count.

### 🪤 adbd writes every shell command into logcat — a grep over logcat finds itself

📻 18.09.2026 (QFPlayer_fork). This unit's adbd logs the full text of each `adb shell` command:
`I adbd : Tianyuan Current sh name:<the whole command line>`. So `logcat -d | grep "station dead"` run through
`adb shell` always "finds" a match — its own command line. Filter by tag (`logcat -d -s MyTag:D`) or exclude
`adbd`, never by a phrase that also appears in the command.

### Is the app actually making sound? Read its AudioTrack in audio_flinger, not the app's own state

📻 18.09.2026 (QFPlayer_fork). A player can report "playing" everywhere — its own events, `MediaSession`
`state=3`, even stream metadata arriving — while producing no sound at all (there it was a seek on a live
stream: LibVLC said `Playing` and created no audio output for 2 min 43 s). The honest instrument:

```sh
# every 0.2 s, as root on the unit; column 18 is "Server" (frames played, hex), 22 is "Underruns"
dumpsys media.audio_flinger | grep -E "^ +[0-9]+ +(yes|no) +$(pidof <package>) "
```

- No line for the pid = no audio output exists. `Server` advancing ~48 000/s (at 48 kHz) = sound is really
  being played; `Underruns` rising = the app is starving the track.
- 📻 Works with the unit muted (the mute here is on the MCU side, after the mixer) — the track still plays.
- 🔬 The same dump keeps a short history of track add/remove with wall-clock times (`AT::add`, `AT::remove`).

### Cutting Wi-Fi for a network test without losing the unit

📻 18.09.2026 (QFPlayer_fork, owner's order). adb here runs over Wi-Fi (`192.168.1.146:9876`), so a cut must be
done by a script **on the unit**, detached from adb: `su -c "setsid nohup sh script.sh > /dev/null 2>&1 < /dev/null &"`,
with `svc wifi disable/enable` inside and `log -t Mark "…"` marks so the bootlog module has them next to the app's
lines. Measured on K706 (no SIM, so Wi-Fi is the only network):
- `svc wifi enable` → IPv4 on `wlan0` in **7–8 s**, every time (5 runs); keep a fallback in the script (no IPv4 in
  60 s → off/on again) — the WCN chip has crashed on this unit before.
- 🔴 **`NET_CAPABILITY_VALIDATED` can arrive before `wlan0` has IPv4** (the network here is dual-stack; validation
  evidently passes over IPv6 — 🧩): a connection to an IPv4-only host made at that instant fails with
  `Network is unreachable`. A race — 3 reconnections out of 5.
- A dropped Wi-Fi either resets an open TCP connection at once (end of stream within 0.5 s) or leaves it hanging
  silently (no error for 10 s+); both were seen within minutes of each other.

### A red crossed speaker in the status bar while `sys.mute.state` is false

📻 18.09.2026. The status bar showed the red crossed speaker (no number) while `sys.mute.state=false`,
`sys.media.vol=4` and every Android stream was unmuted. One `input keyevent 294` (knob one step down) brought the
number back (`3`) and the sound with it. ❓ Where that mute lived (MCU or SystemUI's own flag) — not found.

### 🔴 A flash drive is invisible while QF "USB debug" is on — the port stays in DEVICE mode

📻 18.09.2026 (QFPlayer_fork, owner's SanDisk stick). With `persist.sys.qf.usb.debug=true` the musb controller sat in
`b_idle`, `/sys/devices/platform/soc/soc:aon/5fff0000.usb/musb_mode` = `device`, `/sys/bus/usb/devices` held only the root
hub `usb1`, `sm list-disks` was empty — **no filesystem question is even reached**: the kernel never sees the stick.
- 🔬 `QFSleepWakeup.setUsbMode` (framework, `android/os/QFSleepWakeup.java:234–262`) switches the port to `host` on wake
  (`:784`) and to `device` on sleep (`:822`), **but returns early when `persist.sys.qf.usb.debug` is true** — "is in usb debug
  mode, NOT set usb mode". It also refuses `device` while ACC is on.
- 🔬 The switch lives in QF_CarSettings `FactoryPasswordManager` (`onDevice`): it sets `persist.sys.qf.usb.debug`, turns developer
  options on/off and writes `peripheral`/`device` or `host` into the mode node (8581 also `unisoc-usbswitch/usbswitch_state`).
- 📻 After it was off (and a reboot, `reboot,adb`): mode `host`, the unit's internal hub `1a40:0101` (USB 2.0) on `1-1`, the stick
  on `1-1.3` at 480 Mbit/s, `vold` mounted it within seconds.
- 🔬 Kernel (`/proc/config.gz`): `CONFIG_MSDOS_PARTITION=y`, `CONFIG_EFI_PARTITION=y` (MBR and GPT), `CONFIG_VFAT_FS`,
  `CONFIG_EXFAT_FS` ("exFAT Core 1.2.24" at boot), `CONFIG_NTFS_FS`, `CONFIG_FUSE_FS`, `CONFIG_USB_STORAGE=y`, no UAS;
  userland `mount.exfat`, `fsck.exfat`, `ntfs-3g`, `mkntfs`. 📻 An NTFS stick mounts **read-write through ntfs-3g (fuse)** at
  `/mnt/media_rw/<16-hex serial>` → `/storage/<serial>`, and QF adds the alias **`/storage/udisk1` → `<serial>`** (a symlink).

So "the stick does not mount" on this platform first means: check `musb_mode` and `persist.sys.qf.usb.debug`, then `vold`.

📻 Mount timing and what vold does (`sm unmount public:8,1` / `sm mount public:8,1`, 256 GB NTFS, 18.09 19:34): unmount
0.5 s; mount **0.55 s** — `blkid` → **`ntfsfix -b` on every mount** (it writes to the volume: "un-mark the bad clusters", MFT
check) → `ntfs3g` → FUSE → `mounted` broadcast; the QF scanner `carnetos.usbmedia` finished a 3-file folder 0.1 s later and the
QF music player got `onUsbDiskMounted`. At boot the same stick was seen at 31 s but mounted only at 106 s (and remounted once at
once) — 🧩 vold waits for the boot to finish, not the driver being slow.
⚠️ The logcat `main` buffer here holds only ~2 minutes (flooded by `mcu_services`/`VolumeState`), and the bootlog module stopped
writing `20_run.log` ~4 min into boot 0015 — for an event you will provoke, start your own filtered `logcat -T 1 | grep` capture
on the unit first.

## 8. Host tests for anything measured

Two C++ harnesses in `wdsp_app/src/main/cpp` build with plain `g++` and are deliberately not part
of the library:

```bash
g++ -O2 -std=c++17 -o /tmp/t_analyzer test_analyzer.cpp analyzer.cpp fft.cpp stitcher.cpp
g++ -O2 -std=c++17 -o /tmp/t_sweep    test_sweep.cpp    sweep.cpp analyzer.cpp fft.cpp stitcher.cpp
```

They exist because **a wrong answer from an audio measurement looks exactly as plausible as a right
one**, and in a car there is nothing to check it against. On a synthetic signal there is. Between
them they have caught third-octave band centres a semitone out, an arrival detector biased by a
constant 202 samples, an inverse filter with its envelope upside down, and a band-power rule that
added 6 dB per octave of pure bookkeeping to every response.

## 9. Politeness beats reliability

The owner's rule, and it settles a whole class of design arguments:

> "Better not to be pushy than to be guaranteed to wake up."

Waking after sleep only sometimes is an **acceptable** price. Holding somebody else's audio path,
taking the channel back "just in case", keeping a track running while another app has focus,
grabbing the media keys — not acceptable, even in exchange for working every time. A person who
wants the radio will open it. A person whose music started must not get the radio on top of it.

## 10. Where the artefacts live

```
C:\MCU\                     MCU images, decompilation, patch harness
D:\De-compiled\             every decompiled APK and jar, plus jadx
```

Put new decompilations in `D:\De-compiled` and write down what was put there. Everything has been
extracted at least once already; extracting it again is the second most common way to waste an
afternoon on this platform.

## 11. Never grant a permission from adb

🔴 *(owner, 11.09.2026)* "One of the reasons it works for me and not for the testers is that you
agents grant permissions through adb, around the app, to make your own work easier — and the bugs
get masked." Binding on every agent, Claude and Gemini alike.

And the same day, the reason in full: *"Every permission the app needs must be obtained legally — on
the bench, and even more so for people. Never mask errors artificially. What works on the bench then
does not work for people, and that is the worst case: from people's descriptions it is sometimes
impossible to tell what is wrong, and what is wrong is that on my bench the permission was granted
artificially."*

`pm grant`, `appops set … allow`, `cmd notification allow_listener`, `dumpsys deviceidle whitelist
+…`, `settings put secure enabled_notification_listeners` — each skips the one path a tester has:
the app finding out what is missing and walking the person to it. Done by an agent, that path is
never exercised on the development unit, and every defect in it lives only in the field.

🧩 It cannot be undone by reading, either. A grant through the system dialog does not leave
`USER_SET` behind (AOSP 10 clears it on grant — ❓ not checked on this unit), so `dumpsys package`
looks the same whichever way a permission arrived. Once an agent has granted something, nobody can
later tell how the unit got it; the only cure is to withdraw it and let a person grant it again
through the app.

Reading is allowed, and it is what to do instead:

```bash
adb shell "dumpsys package <pkg> | grep granted="      # runtime AND install permissions
adb shell cmd appops get <pkg>                          # app-op modes; a rejectTime is not a refusal
adb shell settings get secure enabled_notification_listeners
adb shell dumpsys deviceidle whitelist
```

📻 11.09.2026, the owner's unit — a lesson in reading, paid for twice in one hour. `cmd appops get`
showed `SYSTEM_ALERT_WINDOW: default; … rejectTime=…`, read first as "refused" — wrong, a reject is
logged for any mode but `allow`. Then `dumpsys package` showed `SYSTEM_ALERT_WINDOW: granted=true`,
read as "the fingerprint of an adb grant" — wrong again: every third-party app on the unit has it,
Telegram and Waze included ([01-SYSTEM.md](01-SYSTEM.md) §7). 🔴 **Before calling any state a
fingerprint, read the same thing on an app nobody has touched.** One comparison would have prevented
both mistakes.

What the transcripts do prove (grep of both agents' logs, 11.09.2026): Claude, 22.08 —
`cmd notification allow_listener …wdsp/.NotificationAccess` and `cmd appops set com.radiorubka.wdsp
PROJECT_MEDIA allow`, the second even recommended in a code comment; Gemini, 17.08 — `appops set …wdsp
SYSTEM_ALERT_WINDOW allow` and `pm grant` of the microphone and all three locations; both agents —
`pm grant` to the radio. That is the masking the owner describes, from both sides.

When a test needs a permission, ask the owner to grant it **through the app**, with a finger. That
is a test in itself.

## 12. Checking a layout under emulation: read bounds, not pixels

📻 *(radio session's method, 30.07.2026; handed over 11.09.2026)* `screencap` under `wm size` lies on
this panel whenever the emulated height differs or the size exceeds the panel
([01-SYSTEM.md](01-SYSTEM.md) §2). **`uiautomator dump` does not**: its `bounds` are in the logical
coordinates of the overridden display, wherever the compositor puts the picture.

```
wm size WxH  [wm density D]      wait 3 s
am force-stop <pkg>; am start …   the activity must restart, or it keeps the old layout
wait 5 s
uiautomator dump <file>           retry if empty; check the app's own ids are in it
parse: resource-id="<pkg>:id/(…)" … bounds="[x1,y1][x2,y2]"
```

Then check with arithmetic, not eyes: overlaps, what sits under the floating navigation, what is off
screen and not inside a `scrollable="true"` ancestor. Run it all as one script on the unit with the
reset in a `trap`.

### 🪤 The bounds are CLIPPED to what is visible — a scrolling row reads as a crushed one

📻 *(12.09.2026, measured on the unit; cost a wrong diagnosis and two reverted edits)*

A dump reports each node's **visible** rectangle. An element that has partly scrolled out appears as
a sliver at the edge of its container, and one that has scrolled out completely **is not in the dump
at all**. So a row that scrolls correctly looks exactly like a row whose last children were squeezed
to nothing.

Tell them apart by arithmetic, never by the shape of the numbers:

| | it scrolls | it is being squeezed |
|---|---|---|
| children's widths summed | ≈ the container's width (the end ones are clipped) | **less** than the container |
| the last child | flush against the container's edge | has a gap after it |
| number of children | fewer than the layout declares | all present |
| the check that settles it | compute the width the layout NEEDS and compare | measure the same element on the reference geometry |

Worked example: the navigation pill declares six tabs at 130dp plus five 12dp gaps = 840dp. The
viewport on the Tesla square is 584dp, so 256dp is cut — 128dp from each side, because the content
was centred — and the end tabs show as 2dp. The same arithmetic predicted 102dp at 800x480 and 22dp
in split screen, and both matched. **Three agreeing predictions are the proof; one screenshot is
not.**

⚠️ Clipped symmetrically on both sides means the content is **centred inside a scroll view**. That
is a defect of its own: a horizontally centred child places part of itself left of scroll position
zero, where no scrolling can reach it. `Gravity.CENTER_VERTICAL`, never `CENTER`.

### 🪤 Gestures land wrong as soon as the override is TALLER than the panel

📻 *(12.09.2026, four measured geometries)* `input swipe` with coordinates taken straight from the
dump scrolls correctly under some overrides and does nothing under others. The dividing line is not
width and not the Tesla square: it is whether the override fits inside the physical panel
(**1280x720** here, physically portrait with `hwrotation=90`).

| override | swipe from dump coordinates | settings pass |
|---|---|---|
| `800x480` @160 | works | 5 screenfuls, whole screen seen |
| `640x480` @160 | works | 5 screenfuls, whole screen seen |
| `600x900` @160 | does nothing | 1 screenful, the rest never measured |
| `1200x1200` @320 (Tesla) | does nothing | 1 screenful |

Under a taller-than-panel override the window sits as a strip inside the buffer (`scroll_settings`
reported at y=320..1200 px on the square), and what `input` addresses is not the space the dump
describes.

⇒ Anything that needs scrolling or tapping must be emulated **within the panel**: to cover a narrow
panel take `640x480` rather than `600x900`. Keep the out-of-panel overrides for measurements that
need only `am start` and a dump. A pass that swipes under such an override and reports "clean" has
measured one screenful and nothing else — this is exactly how the settings screen was almost
signed off unseen.

### 🪤 Compare an element with ITSELF on the reference geometry, not with its neighbours

A detector that flags "taller than the median row" cries wolf: a Material outlined box is legitimately
taller than a caption next to it. Normalise to dp (divide by density/160 — the Tesla profile runs at
320) and compare the same `resource-id` against the reference 1280x720@160. And filter by package:
the launcher's own top bar (`com.android.launcher.dreamMountain:btn_apps`, `tv_week`, …) lands in the
dump of any activity that does not cover it, and it is not ours to fix.

🪤 A dump taken right after `wm size` can come back empty (no nodes at all) — retry or wait longer,
and never conclude "zero width" from it. 🪤 Git Bash rewrites `/sdcard/u.xml` into a Windows path and
`cat` then returns the *previous* run's dump: `MSYS_NO_PATHCONV=1`, `rm -f` before dumping, and read
the `dumped to:` line. 🪤 Driving an app by resource id (an `--ei target_tab <id>` extra) — take the
numbers from `R.txt` of the build **actually installed**: removing one layout file shifted every id
by −4 (11.09.2026), and the old "equaliser" id then opened Settings — a whole run of "missing" tabs
that was the test's fault, not the app's. Better still, verify each screen by a marker id in the
dump before trusting it. 📻 Density 320 emulation works as `1200x1200` + `wm density 320` → the app's
root is 1200×880 px = 600×440 dp (Tesla-like); the "960×600" group was checked as `960x600` @160.
