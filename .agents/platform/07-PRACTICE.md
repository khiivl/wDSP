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
