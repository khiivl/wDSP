# Handoff — wDSP, state at 26.08.2026

Read this first after a context compaction or when picking the project up cold. It says where
things stand, what is uncommitted, and what is open. Everything it refers to is written down
somewhere else in full; this is the map, not the territory.

---

## Where to start

1. **[platform/INDEX.md](platform/INDEX.md)** — the machine itself. Opens with the things most
   likely to cost a day. Everything there carries a provenance mark (🔬 read in firmware,
   📻 measured on the wire, 🧩 inferred, ❓ unverified). **If you add to it, mark what you add.**
2. **[INDEX.md](INDEX.md)** — this application's own design.
3. `../CLAUDE.md` — how the app is put together.

---

## The state of the tree

Version **0.4.7.1**, `versionCode 8`, branch `kostyfmat_mod`. Builds clean.

🔴 **A large amount is uncommitted.** Check `git status` before anything else and do not assume
this file is current about it. As of writing, uncommitted work covers: the diagnostics rework
(`AudioPolicySnapshot`, `PcmStatus`, `RootAccess`, the capture probe), the screensaver fixes, the
GALA fixes, and the measurement changes below.

⚠️ **Commits are the owner's decision.** Never commit or push unasked. Pushing is **only** through
WSL — the keys are there, and a Windows-side push fails silently.

---

## What was learned this session, and where it is written

| finding | file |
|---|---|
| `navi_volume` **is** the `STREAM_SYSTEM` index; a prompt's whole level is that one number | `platform/09-NAVIGATION-AND-BITPERFECT.md` §3 |
| `/system/config/NaviApp.ini` is a whitelist — a navigator missing from it gets **nothing** | §1 |
| On units with no AK hub the platform ducks by **stepping the volume index**, and BitPerfect's flat curve makes that a no-op. Two units. | §4-ter |
| A curve reaching 0 dB at the top index would keep unity gain **and** restore ducking. ❓ untested on a car | §4-ter |
| `sys.qf.sound.channel`: `2` is evidence, `4` is not | §4-bis |
| `AudioPlaybackCapture` hands over the **microphone**, not the stream | `platform/05-AUDIO-PATH.md` |
| `/proc/asound/…/status` is world-readable and says which device is open | `platform/05-AUDIO-PATH.md` |
| Polarity is only trustworthy where the sound arrived directly | `ROOM_CALIBRATION.md` §5-bis |
| The sweep never asked for audio focus — the likeliest cause of "the first measurement fails" | `ROOM_CALIBRATION.md` §5-bis |

The platform files are mirrored into the global skill at
`~/.claude/skills/qf-platform/references/`. **Edit the copy in `.agents/platform/` and copy it
across** — they are kept identical on purpose.

---

## Open, in rough order of value

1. **Repack the distribution.** `~/Downloads/wDSP-kostyamat-mod-0.4.7.1/` was packed before the
   25.08 contract changed the radio behaviour and before Back was restored, so **two of its texts
   are now untrue**: "on radio, the clock only" and the unqualified promise about the Back key.
2. **Test the curve fix** from §4-ter on a car with no hub. It is arithmetic, not an ear, and it
   would close the oldest complaint on this platform.
3. **Confirm the audio-focus fix** actually cures the first-measurement failure. The report now
   carries `audio focus:` either way, so the next tester's file answers it.
4. `SessionResolver` could be told which PCM device is open instead of probing session 0 blind.
   🔴 **It is the most delicate code in the app** — do not touch it without measuring first.
5. New strings exist in **en, uk, ru** only. The project has 28 locales.

---

## Working rules that cost something when forgotten

- **Argue before obeying** when there is evidence against a request, and say so first.
- **Verify on the wire yourself** when the owner is away. When the owner is *at* the unit, install
  and be quiet — no `input tap`, no screenshots, no scripted runs. They will look.
- **Heredocs eat backslashes.** Writing Java or XML through a shell heredoc has broken string
  literals three times in one session. Use the Write/Edit tools, or `chr(92)`.
- **Several project files are CRLF.** Read with normalisation and write back the same way, or the
  match silently fails.
- **A stale document is worse than none.** Two claims in `ROOM_CALIBRATION.md` contradicted the
  code this session and nearly caused a non-bug to be "fixed". If the code moves, move the file.
