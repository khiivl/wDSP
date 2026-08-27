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

Version **0.4.7.2**, `versionCode 9`, branch `kostyfmat_mod`. Builds clean, signed release
installed and measured on the owner's unit, **committed and pushed** (`91a5ff5`, `569ca75`).
The distribution is packed at `~/Downloads/wDSP-kostyamat-mod-0.4.7.2/` with texts in English and
Russian.

⚠️ Check `git status` before anything else anyway, and do not assume this file is current about
it — it describes the tree at the moment it was written, and nothing keeps it honest.

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

## 🔴 Uncommitted right now — the night of 26-27.08

`McuService.java`, `VolumeHelper.java`, `build.gradle`, and three `.agents/` files carry
**`0.4.7.4` / `versionCode 11`**, installed on the unit and proven on the wire, **not committed**.

Beyond the volume-sync architecture described below, tonight added four fixes, and two of them are
faults that would have reached people and shown up **only while driving**:

| | |
|---|---|
| carry from the announce handler too | it silenced its own detector; 1 of 5 steps carried |
| subtract GALA's boost from the base | the level still contains it now that the radio no longer writes levels |
| **suppress our own echo** | GALA's own writes came back as announces and were eaten into the base — the volume climbed at speed and sank under braking; 5/5 became 2/2 |
| **restore the level after a source switch** | the platform wipes every source standing on the same number to `persist.sys.main_volume`, and synchronising *creates* that condition |

Both of the last two were found with `SIMULATE_SPEED`, not by testing at a standstill, where they
are invisible. See `platform/08-VOLUME-AND-SOURCES.md` and the shared memory
`wdsp-audio-contract-proven.md`.

⚠️ The synchronisation is proven **on the properties**, not by ear — the media path was silent that
night for an unrelated reason (a stuck HAL fd, see the radio session's notes). "The level is
carried" is proven; "it sounds the same" is not.

## 🔴 Uncommitted right now, on purpose

`McuService.java`, `VolumeHelper.java`, `build.gradle` carry **`0.4.7.4` / `versionCode 11`**: the
volume-sync architecture the owner asked for on 26.08 — wDSP carries the base level between
`media_type` and `radio_type` through `VolumeManager.findVolumeStateByType`, and the radio falls
back only when wDSP is absent or older. It builds clean and is **deliberately not committed, not
pushed and not installed**.

Not because it is unfinished. `versionCode 11` together with the radio's gate switches the whole
volume architecture **live**, and the owner was driving when it was written — nobody could hear the
result. Both sides are held ready and activate together, in front of him. Commits are his decision;
this note exists so the work is not mistaken for a stray edit.

The design and its laws are written up in the shared memory under
`wdsp-audio-contract-proven.md` → "Синк-архітектура vCode 11", and the protocol itself in
[AUDIO_OWNERSHIP_CONTRACT.md](AUDIO_OWNERSHIP_CONTRACT.md).

---

## Open, in rough order of value

1. **Test the curve fix** from §4-ter on a car with no hub. It is arithmetic, not an ear, and it
   would close the oldest complaint on this platform.
2. **Confirm the audio-focus fix cures the first-measurement failure on somebody else's car.**
   The request itself is fixed and verified here — `audio focus: granted`, 26.08 — but that it
   cures the old complaint is still a hypothesis, and only a tester's report answers it.
3. Nothing else outstanding from this cycle. Measurements are now directed to the Telegram group
   (<https://t.me/wDSPapp>, collection post <https://t.me/wDSPapp/79>) or a forum PM rather than a
   direct message — by the owner's instruction, because lone testers' files get lost in DMs.
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
