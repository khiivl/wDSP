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

🪤 A dump taken right after `wm size` can come back empty (no nodes at all) — retry or wait longer,
and never conclude "zero width" from it. 🪤 Git Bash rewrites `/sdcard/u.xml` into a Windows path and
`cat` then returns the *previous* run's dump: `MSYS_NO_PATHCONV=1`, `rm -f` before dumping, and read
the `dumped to:` line. 🪤 Driving an app by resource id (an `--ei target_tab <id>` extra) — take the
numbers from `R.txt` of the build **actually installed**: removing one layout file shifted every id
by −4 (11.09.2026), and the old "equaliser" id then opened Settings — a whole run of "missing" tabs
that was the test's fault, not the app's. Better still, verify each screen by a marker id in the
dump before trusting it. 📻 Density 320 emulation works as `1200x1200` + `wm density 320` → the app's
root is 1200×880 px = 600×440 dp (Tesla-like); the "960×600" group was checked as `960x600` @160.
