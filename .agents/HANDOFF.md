# Handoff — wDSP, state at 07.09.2026

Read this first after a context compaction or when picking the project up cold. It says where
things stand, what is committed, and what is open. Everything it refers to is written down
somewhere else in full; this is the map, not the territory.

---

## Where to start

1. **[platform/INDEX.md](platform/INDEX.md)** — the machine itself. Opens with the eleven things
   most likely to cost a day. Everything there carries a provenance mark (🔬 read in firmware,
   📻 measured on the wire, 🧩 inferred, ❓ unverified). **If you add to it, mark what you add.**
2. **[INDEX.md](INDEX.md)** — this application's own design.
3. `../CLAUDE.md` — how the app is put together.

---

## The state of the tree

Version **0.4.7.4**, `versionCode 11`, branch `kostyfmat_mod`, HEAD **`b7ae94d`** (03.09.2026).
**Committed and pushed** — `origin/kostyfmat_mod` stands on the same commit, nothing ahead and
nothing behind. Nothing is being held back any more: the whole volume-sync cycle that this file
used to describe as "uncommitted on purpose" went in with `4eea344` on 27.08.

The working tree carries two `.idea/` files and three untracked helpers from the localisation pass
— `translations.json`, `translate_instructions.txt`, `apply_and_sync.ps1`. They are the source data
for that pass — 27 locales × 16 keys, the 26 translated ones plus the hand-written `ru-rUA` — and
they are kept deliberately. They are not stray edits.

⚠️ **What is built is older than what is committed.** The release APK under
`wdsp_app/build/outputs/apk/release/` was produced on 28.08 and predates both 03.09 commits; the
newest packed distribution is still `~/Downloads/wDSP-kostyamat-mod-0.4.7.2/`. **Nothing after
0.4.7.2 has been handed to testers**, so a report arriving from the field describes 0.4.7.2 unless
its author says otherwise — and in particular it does *not* contain the audio-focus fix or the
`UNPROCESSED` capture.

⚠️ Check `git status` before anything else anyway, and do not assume this file is current about
it — it describes the tree at the moment it was written, and nothing keeps it honest.

⚠️ **Commits are the owner's decision.** Never commit or push unasked. Pushing is **only** through
WSL — the keys are there, and a Windows-side push fails silently.

---

## What landed between 26.08 and 03.09

| commit | what |
|---|---|
| `91a5ff5` (26.08) | the field report grew enough to diagnose a car nobody here has seen; `HardwareProfile`; **the 16 room-measurement strings, in all 29 locale folders at once** |
| `2b7355c` … `634f527` (26.08) | the wDSP half of the audio-ownership contract: ordered by the clock rather than the radio's counter, a live property saying who owns the path, and the query that wakes nobody |
| `4eea344` (27.08) | **0.4.7.4 / `versionCode 11`** — the two faults that exist only while the car is moving: GALA's own writes returning as announcements, and the platform wiping the level on a source switch |
| `f2a5d3a` (03.09) | the sweep records on `UNPROCESSED`, because `/vendor/etc/audio_effects.xml` binds AEC and NS to `VOICE_RECOGNITION` **by name**; and the volume sync now obeys `persist.sys.qf.radio.sync_vol` |
| `b7ae94d` (03.09) | the MCU version suffix decoded from factory code; five new platform files; Gemini's three sessions imported and attributed |

Two of those are worth carrying as rules rather than as commits:

- 🔴 **Suspending an audio effect from the app does not suspend the policy's copy.**
  `NoiseSuppressor.create(session)` hands back *our* handle, so disabling it leaves the one the
  policy attached still running. The app logged "NS was off, now off" while AudioFlinger reported
  the chain ACTIVE — both true, about different objects. Choose the capture source instead.
- 🔴 **`[1]` of the MCU code is the sound processor; `[4]` is the control panel.** A detector built
  on the trailing pair reads true on every unit and distinguishes nothing — that is how BitPerfect
  hands a BD37534 the 24-bit I2S profile. Table in
  [platform/13-MCU-FIRMWARE-VARIANTS.md](platform/13-MCU-FIRMWARE-VARIANTS.md) §1.

The measurements behind the volume work were also written to the agent bridge as
`wdsp-audio-contract-proven.md` (`read_doc`), for the radio side to read. That store is outside
this repository and nothing here keeps it alive; [AUDIO_OWNERSHIP_CONTRACT.md](AUDIO_OWNERSHIP_CONTRACT.md)
and [platform/08-VOLUME-AND-SOURCES.md](platform/08-VOLUME-AND-SOURCES.md) are the copies that stay.

Localisation is closed: the room-measurement strings exist in **the base `values/` and all 29
locale folders**, 16 keys in each, verified key by key on 07.09 rather than taken from the commit
message. `\n\n` survived as a literal and the apostrophes are escaped.

The platform files are mirrored into the global skill at
`~/.claude/skills/qf-platform/references/`. Compared 07.09: every file byte-identical, `INDEX.md`
differing only by the skill's own `from-gemini/` section. **Edit the copy in `.agents/platform/`
and copy it across** — they are kept identical on purpose.

---

## Open, in rough order of value

1. **Test the curve fix** from `platform/09-NAVIGATION-AND-BITPERFECT.md` §4-ter on a car with no
   AK hub. It is arithmetic, not an ear, and it would close the oldest complaint on this platform.
2. **Confirm the audio-focus fix cures the first-measurement failure in somebody else's car.** The
   request itself is fixed and verified here — `audio focus: granted`, 26.08 — but that it cures
   the old complaint is still a hypothesis. ⚠️ No tester has a build containing it: see the packing
   warning above.
3. **`UNPROCESSED` is kept on argument, not on evidence.** 📻 Two cabin sweeps minutes apart differ
   by 0.0–0.2 dB from 315 Hz to 8 kHz, and the low end moves the wrong way for the "NS eats the
   bass" theory. It costs nothing and may matter on a unit whose policy differs — ❓ unmeasured.
4. **`HardwareProfile.hasBu32107()` is right for the wrong reason.** It tests
   `startsWith("00") && endsWith("21")`; only the first half looks at the sound processor, and the
   javadoc explains the answer by the trailing `21` — the belief `platform/13-` disproved. It gives
   the correct answer on all four known firmwares and errs towards "not BU32107", which is the safe
   direction, so this is a documentation and robustness matter rather than a live fault. Note also
   that the factory tables name the lesser chip **BD37534** while our code and `CLAUDE.md` call it
   `BD37544`.
5. **The `QUERY` reply repeating `at` is still untested** — when it was tried the radio had never
   announced, so there was no `at` to repeat. Last row still marked ⏳ in
   [AUDIO_OWNERSHIP_CONTRACT.md](AUDIO_OWNERSHIP_CONTRACT.md).
6. `SessionResolver` could be told which PCM device is open instead of probing session 0 blind.
   🔴 **It is the most delicate code in the app** — do not touch it without measuring first.

📌 Standing instruction, not an open item: measurements go to the Telegram group
(<https://t.me/wDSPapp>, collection post <https://t.me/wDSPapp/79>) or a forum PM, never to a
direct message — by the owner's instruction, because lone testers' files get lost in DMs.

---

## Working rules that cost something when forgotten

- **Argue before obeying** when there is evidence against a request, and say so first.
- **Verify on the wire yourself** when the owner is away. When the owner is *at* the unit, install
  and be quiet — no `input tap`, no screenshots, no scripted runs. They will look.
- **Heredocs eat backslashes.** Writing Java or XML through a shell heredoc has broken string
  literals three times in one session. Use the Write/Edit tools, or `chr(92)`.
- **Several project files are CRLF.** Read with normalisation and write back the same way, or the
  match silently fails. (The `.agents/` files themselves are LF.)
- **A stale document is worse than none.** Two claims in `ROOM_CALIBRATION.md` contradicted the
  code and nearly caused a non-bug to be "fixed"; this file spent ten days describing work as
  uncommitted that had been pushed. If the code moves, move the file.
