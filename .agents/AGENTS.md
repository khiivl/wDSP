# AGENTS — wDSP

> 🛑 The QF platform (QianFang: QF01/QF03/QF05, K706, ums512, UIS7862/UIS8581) is **not FYT**. Never mix them up.
> QF hardware facts live in the `qf-platform` skill, not here.

## Language (owner, 28.09.2026)

Cyrillic costs about twice the tokens Latin does, so **everything an agent reads is written in English**: these rules,
the knowledge files next to them, code comments, and the memory files. **Ukrainian is only for what the owner reads**:
the conversation with him, commit messages, and the documents he opens — [TODO.md](TODO.md), [DEBT.md](DEBT.md),
[HANDOFF.md](HANDOFF.md). Keep proper names and quoted owner decisions as they were said.

An equaliser and cabin-measurement app for QF head units, driving the ROHM **BU32107** sound processor through the
MCU. The owner's second application; the repository is shared with a friend who keeps `origin/master`.
Internals — [ARCHITECTURE.md](ARCHITECTURE.md). Measurement and the microphone — [ROOM_CALIBRATION.md](ROOM_CALIBRATION.md).
Index of the rest — [INDEX.md](INDEX.md). Why a rule exists — [DECISIONS.md](DECISIONS.md).

## Build

```bash
./gradlew :wdsp_app:assembleDebug        # APK in wdsp_app/build/outputs/apk/debug/
adb -s <unit> install -r wdsp_app/build/outputs/apk/debug/wdsp_app-debug.apk
```

Gradle 9.4 + AGP 9.0.1, Java 11, `compileSdk 36`. Build **on Windows** — WSL has no Android SDK.

- **Debug builds only.** R8 in a release build breaks the hidden-API reflection, which is why `wdsp_app` sets
  `minifyEnabled false`. Both types are signed with the release config, so they install over each other.
- **There are no tests** (`src/test`, `src/androidTest` do not exist). `./gradlew test` is a no-op — never claim coverage.
- Modules: **`:wdsp_app`** (`com.radiorubka.wdsp`, `minSdk 29`, **`targetSdk 29` on purpose** — the QF framework behaves
  as Android 10; do not "modernise" it) and **`:wdsp_proxy`** (`com.qf.soundeffect`, `sharedUserId=android.uid.system`,
  installed as an update to the stock DSP app with root + PMPatch3, so the quick-settings DSP button opens wDSP).
- `rules.md` and `agents.md` in the repo root **are tracked** despite a `.gitignore` line naming them: an ignore rule
  does not untrack what is already committed. They reach everyone who clones.

## Verifying on the unit

- The **boot path is not the same as launching the app**: at boot only `McuService` starts, with no `MainActivity`.
  A fault that appears only after a reboot lives there. Re-broadcasting `BOOT_COMPLETED` does not work; start the
  service directly:

```bash
adb shell am force-stop com.radiorubka.wdsp && adb shell am start-foreground-service -n com.radiorubka.wdsp/.McuService
```

- Read the **boot-logger module** (`bootlog/<latest>/10_boot.log`, `20_run.log*`), not `logcat` after the fact: the
  buffer here rotates within a minute, so a warning is gone before you grep for it. **`logcat -c` never.**
- `uiautomator dump` returns nothing while the status-bar visualiser runs (it waits for an idle UI and that overlay
  animates). Disable the widget, or read geometry from `adb shell dumpsys activity top -a`.
- The stock `com.qf.soundeffect` writes the same hardware registers. For measurements it is disabled (`pm disable`) or
  replaced by the proxy — the owner's call, not ours.

## Prohibitions

- **Permissions are granted by the owner through the UI, never over adb.** An adb grant masks the bug: it works for
  the owner and not for testers, and afterwards nobody can read back who granted it.
- **Never mask an error**, and never "fix" what has not been proven.
- **Never delete source data.**
- **Push only on a direct order**, and only from WSL (the key belongs to user `user`, not `root`). Commit yourself,
  after every verified step.
- **Never install during a live test.** When the owner is at the unit: install, then be quiet — no `input tap`, no
  screenshots, no scripted runs. He will look.
- **No sound tests at night** without permission.
- Do not touch or commit anyone else's `.idea/*`. **Gemini has no git rights** in this repository.
- Tester measurements go to the group <https://t.me/wDSPapp> (collection post <https://t.me/wDSPapp/79>) or a forum
  PM — **never** a direct message, where lone files get lost.

## Working with the owner

- **Argue before obeying** when there is evidence against a request, and say so first.
- **Name the thing, not the index.** "Debt #4" tells the reader nothing; the same goes for a commit hash, a version
  code or a path offered without a sentence saying what is in it.
- **Verify on the wire yourself** when the owner is away.
- **The owner's bench is not a cabin**: open shelving to the left, a balcony to the right, glass 1.35 m away, a lamp in
  front of the left speaker. Draw no acoustic conclusion from it — take only what does not depend on the room (converter
  levels, clipping, determinism, whether a preset actually reached the chip).

## Engineering rules

- **A matching number is a reason to look, not a finding.**
- **A criterion that cannot fail proves nothing.** Write down the number you expect before an acceptance test; if the
  honest answer is "some", it is not a test yet.
- **Name the state by reading it, never by remembering what you did.** The write may not have landed, or may have gone
  to a different source of truth from the one the reader consults.
- **Fix one side of a transition, then go and look at its pair.** Taking a channel pairs with releasing it; a gate on
  the way out pairs with the answer to a query.
- **One fact, one function.** The second source of truth is often a **widget**: a default belongs to the reader
  deciding what to do without a value, never to the writer inventing one.
- **Do not re-measure settled physics.** A Helmholtz resonance, cabin gain, a high-pass on a coupling capacitor are
  prior knowledge, not hypotheses awaiting our confirmation. And do not chase precision the hardware lacks: 16 bands,
  2 dB a step, a fixed `Q = 2.2`.
- **A stale document is worse than none.** If the code moves, move the file.
- Read a file before editing it; preserve existing code verbatim; propagate a signature change to **every** call site
  in the same change. The owner is the architect — ask rather than invent a design.

## Traps of this machine

- **Heredocs eat escaping**, and Git Bash mangles `/mnt/...` paths. Write scripts to a file with the editing tool and
  run them as `wsl -u root python3 <path>` **from PowerShell**.
- **`git add` stops at the first path that does not exist** and adds nothing after it, while the commit still succeeds.
  Run `git show --stat` after every commit and check what actually went in.
- `core.autocrlf` is `true` for Windows git and was unset for WSL git, so `git status` from WSL listed ~170 files as
  modified when nothing had changed. Pushing finished commits from WSL is safe; `git add -A` from there is not.
- Several project files are CRLF; `.agents/` itself is LF. Read with normalisation and write back the same way.

## Dependencies

| direction | with | contract |
|---|---|---|
| wDSP → radio | `kostyamat_fmradio` | audio ownership: `C:\APPS_Contacts\`, mirror in [AUDIO_OWNERSHIP_CONTRACT.md](AUDIO_OWNERSHIP_CONTRACT.md) |
| wDSP → radio | screensaver | [SCREENSAVER_RADIO_CONTRACT.md](SCREENSAVER_RADIO_CONTRACT.md) |
| wDSP → Magisk modules | BitPerfect, `qf_cellular_calling_master` | both overlay `primary_audio_policy_configuration.xml`; the Gemini session leads them |

**wDSP owns the system audio path** — the register of platform "axes" is in `platform/08-VOLUME-AND-SOURCES.md`,
section "own axes". Change a contract → update the file in `C:\APPS_Contacts\` and write to the dependent project's
line by address, not by broadcast.
