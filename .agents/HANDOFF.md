# Handoff — wDSP, state at 07.09.2026

Read this first after a context compaction or when picking the project up cold. It says where
things stand, what is committed, and what is open. Everything it refers to is written down
somewhere else in full; this is the map, not the territory.

---

## Where to start

0. **[🚀 Cold start](#-cold-start--for-a-session-on-another-machine-under-another-account)**, just
   below — if this machine, this account or this pair of sessions is new to you. It carries the
   things that would not survive us: which build is where, what is deliberate and must not be
   "fixed", and which of our own conclusions turned out to be wrong.
1. **[platform/INDEX.md](platform/INDEX.md)** — the machine itself. Opens with the eleven things
   most likely to cost a day. Everything there carries a provenance mark (🔬 read in firmware,
   📻 measured on the wire, 🧩 inferred, ❓ unverified). **If you add to it, mark what you add.**
2. **[INDEX.md](INDEX.md)** — this application's own design.
3. `../CLAUDE.md` — how the app is put together.
4. `C:\APPS_Contacts\wDSP--QFRadio\` — **anything agreed with QF Radio**, and the ledger where each
   side marks what it has actually done. The copies in this folder are mirrors of it.

---

## 🚀 Cold start — for a session on another machine, under another account

Written 09.09.2026 by the owner's instruction, together with the QF Radio session, which wrote its
own half in `kostyamat_fmradio/.agents/HANDOFF.md`. The two halves do not repeat each other, and
neither repeats `C:\APPS_Contacts\wDSP--QFRadio\`, which is the single source of truth for anything
agreed **between** the two applications.

🔴 **The selection rule for everything below: it is here because it would not survive us.** What
lives in the code is not repeated here; what lived only in a conversation is written down or lost.

### 🗺️ Where everything is kept — verified on this machine, 11.09.2026

The other machine will have different paths. What matters is **what each place is for**; the paths
below are this machine's, given as the example to look for the equivalent of. Everything listed was
checked to exist, not remembered.

| what | where on this machine | note |
|---|---|---|
| **Platform knowledge, global** | `C:\Users\kosty\.claude\skills\qf-platform\` — `SKILL.md` plus `references\` | The one that matters most. Reachable from **any** project on the machine, which is why MCU and register findings go here rather than into a repository. `references\` is a byte-identical mirror of `wDSP\.agents\platform\`: edit the repository copy, then copy across, never the reverse |
| **The board skill** | `C:\Users\kosty\.claude\skills\agent-bridge\` | How to talk to the other sessions, and the P0 discipline |
| **The board itself** | `C:\repos\agent-bridge\` — `agent_bridge.db` (SQLite), `docs\`, `watch_board.py`, `AGENT_BRIDGE_PROTOCOL.md` | Also holds `WATCHMAN_BLIND_SPOT_2026-09-07.md`, the write-up of why the watchman needs `--session` |
| **Agreements between applications** | `C:\APPS_Contacts\` | Canon. `README.md` carries the folder's own rules, including the distribution rule; `wDSP--QFRadio\` holds the two contracts, the ledger and the test scenario. The copies under `.agents\` are mirrors |
| **Memory of this project** | `C:\Users\kosty\.claude\projects\C--Users-kosty-AndroidStudioProjects-wDSP\memory\` | `MEMORY.md` is the index loaded each session; one file per fact beside it. Currently two, both about how to report to the owner |
| **Session transcripts** | the same `projects\…\` folder, `*.jsonl` | Where a lost detail can still be dug out of. ⚠️ The owner's standing instruction is **not to delete source data** |
| **Hooks and settings** | `C:\Users\kosty\.claude\settings.json` | Carries the `SessionStart` hook that prints the board state before the first thought |
| **The Antigravity/Gemini side** | `C:\Users\kosty\.gemini\config\` | Its own skills and MCP configuration. Its global rules live here, not in this repository, and it writes them in English |
| **Repositories** | `C:\Users\kosty\AndroidStudioProjects\wDSP` (this) · `…\kostyamat_fmradio` (the radio, its half of the handover) · `D:\gemini\wdsp_test` (the interface session's worktree of **this** repository, branch `gemini_ui_dev`) | The sandbox is a git worktree, not a clone: merging from it is a fast-forward |
| **Release packs** | `C:\Users\kosty\Downloads\wDSP-kostyamat-mod-*` | One folder and one zip per version. A pack existing here is not evidence it was distributed — see the distribution ledger |
| **Tools that must be called by full path** | `C:\Program Files\Python312\python.exe` · `…\AppData\Local\Android\Sdk\platform-tools\adb.exe` | 🪤 `python3` resolves to a Windows Store stub that exits 127 silently. This killed the board watchman twice |

### The numbers that will mislead you first

| | wDSP | QF Radio |
|---|---|---|
| in testers' hands | **0.4.7.2** | **`versionCode 83`** |
| on the test unit 192.168.1.146 | **0.4.8 / `versionCode 15`** | **`versionCode 91`** (since 09.09 05:22) |
| in the tree | 0.4.8 / 15, pushed (`8e0c82a`) | `versionCode 91` |

🪤 The radio calls **all three** of its builds `RC2.2` — its `versionName` has not moved since
August. **Do not trust `versionName`, read `versionCode`.** The same disease cost a day on this
side once: 0.4.7.6 existed on the unit in two different shapes.

🔴 **A consequence nobody would derive on their own: the testers' radio is `83`, below both
thresholds that matter.** Under `84` the radio's volume-sync switch defaulted to *on*, so those
units have it enabled without anybody choosing it. Under `86` wDSP now refuses the bargain and
reports `syncOwner=false`, which puts that radio into its own fallback — and that fallback
equalises `sys.radio.vol` with `sys.media.vol`, which is exactly the condition the platform's
`resetDefValIfNeed` keys on. Why the level is nevertheless not lost, and the race that is still
open, are in the two rows below.

### The version gate: why it stays at 86

Radio 91 is on the unit and 91 fixes real faults, so raising the gate to 91 is tempting and would
be wrong. The two numbers answer different questions:

- **86** — the oldest radio with which the bargain *means* anything. That is what a gate is for.
- **91** — the oldest radio with which the bargain works *without known faults*. That is a
  recommendation, not a condition, and enforcing it would cut off units that are behaving perfectly
  well and would leave their volume to nobody.

✍️ The QF Radio session, which had every reason to argue for its own newest build, advised against
raising it. Keep 86 until a radio changes the bargain itself — not merely fixes things inside it.

🪤 One detail about reading the log: `volume sync allowed: … versionCode NN` is printed **only when
the answer changes**, so the number in it is the version at the moment of the decision, not the
version installed now. On 09.09 the last such line said 90 while the unit already held 91 — both
are above the gate, so nothing flipped and nothing was printed. Read the package, not the log line.

### The one measurement that is set up and not taken

**Step 5 of `TEST_SYNC_ON.md` has never been run against radio 91.** The expected number is written
down and exact: taking the channel must produce **exactly one** `source=radio` announcement. It is
the only test in this project that can fail — every earlier version of it was phrased so that it
could not — and it survives in the shared folder rather than in either session's memory. Run it
before believing the pair is finished.

### 🧩 The interface half: written by the Antigravity/Gemini session, and NOT reviewed

The interface of 0.4.8 — the frosted glass, the floating docks, the preset pill, the screensaver
control zones, the dialog theming and the locale pass — was written by the Antigravity/Gemini
session, not by this one. **Its own account is in this file**, in its own section below, written
11.09.2026 on request: why the geometry is what it is, where the tuned constants came from, what it
tried and rejected, and what it knows is crooked and left standing. Read it before touching any of
that; the reasons are nowhere else.

🔴 **And here is the part that is a job, not a note.** That session changed a great deal, and the
owner's warning on 11.09.2026 was explicit: **the changes reach as far as GALA, so the volume
contract with QF Radio may be partly broken.** Nothing in this repository proves otherwise —

- the joint measurements recorded in `C:\APPS_Contacts\wDSP--QFRadio\` were taken **before** this
  cycle's volume work, against a different build on both sides;
- the one test able to fail — step 5 against radio 91, expecting exactly one `source=radio`
  announcement on taking the channel — **has not been run against 0.4.8 at all**;
- and 0.4.8 is **already public**, so this is a review of something people are running, not of a
  candidate.

⚠️ `git log --author` will not help you sort out who wrote what: every commit in this repository
carries the same git identity regardless of which session produced it. Attribution lives in the
commit messages and in this file, not in the metadata.

📮 So the next session's first substantial task is a review of that work against the contract, in
this order: read the interface session's section, then the contract ledger, then run step 5. Treat
agreement as unproven until the wire says so — that is the standard both sides of the contract held
themselves to all week, and this is the one piece of work that never met it.

### 📦 What was actually handed out, and to whom

🔴 **The owner's standing rule, 09.09.2026: record which versions were distributed, and to which
audience.** Not every release that was packed went to people — several went to testers only, and
some never left this machine. The owner says each time which it is. **Nothing here may be filled in
by inference**: "a zip exists" is not distribution, and "testers had it" is not "it was public".

| version | built | pack | handed to | evidence |
|---|---|---|---|---|
| 0.4.7.1 | 23.08.2026 | zipped | ❓ **not recorded** | pack and zip exist in `~/Downloads/`; nothing on this machine says whether they went out, or to whom |
| 0.4.7.2 | 26.08.2026 | zipped | **testers** (public status ❓) | the handover has said since 03.09 that "testers still have 0.4.7.2"; whether it was also public was never written down |
| 0.4.7.5 | 07.09.2026 | packed, never zipped | ❌ **nobody** | packed the night of 07-08.09 and superseded before it went anywhere |
| 0.4.7.7 | 08.09.2026 | no pack | ❌ **nobody** | built and installed on the test unit only, to carry the interface merge |
| **0.4.8** | 09.09.2026 | `wDSP-kostyamat-mod-0.4.8.zip`, sha256 `addb1769ff8b…` | 🌍 **PUBLIC** | the owner approved the pack and then said, in as many words, that this release is public — 09.09.2026. First public release since 0.4.7.2, and the first to carry the upstream author's links, the mod's own name on the icon, and the volume-sync gate |

⚠️ Three rows carry a `❓`. They are unknown rather than empty: this session could not establish
them from anything on the machine, and guessing them would put a false fact into the one document a
new machine trusts. The owner can settle all three in a sentence.

📌 The APK inside a pack is verified against the one installed on the unit **by hash**, not by
having just copied it. Two artefacts under one version number has happened twice here.

### 🛑 What looks like a defect and is deliberate

Change any of these only with a measurement in hand, and rewrite the row when you do.

| looks wrong | why it is like that |
|---|---|
| **GALA does not write the volume when its boost is zero** — it follows the hardware instead | the original never wrote the volume at all. Writing `base + 0` overrules the person turning the knob: measured, 4→5 by hand and back to 4 eighty milliseconds later. Fixed in `85cc392`; restoring the write reintroduces the regression |
| the default-preset fallback is a **three-step chain** in a fixed order | the player's own preset, then the one mapped to `Default`, then `PREF_DEFAULT_PRESET`. That last key was written by `MainActivity` for months while nothing read it. The order is the original's |
| the **restore after a platform reset is not gated** by the sync property or by the radio version | it repairs a platform fault — `resetDefValIfNeed` wipes every source that happens to hold the same number — not a bargain with anybody. Gating it would leave the level lost on exactly the units that never agreed to anything. ⚠️ Open race: if the radio announces the wiped level before the next poll (100 ms) restores it, this side adopts that level as the new base and the level really is lost. Measured delivery skew between applications is 191 ms, so the poll usually wins. "Usually" is not "always" — the guard is named in the open items |
| `AUDIO_STATE_STABLE` carries a `volume` extra this side **never reads** | it is advisory by contract; the level is always re-read from the hardware. That is why the neighbour announcing a stale number cost us nothing — and why our log could not settle what the neighbour had sent, which is a separate debt |
| `MIN_RADIO_VERSION_CODE = 86` | 86 is the first radio **measured** staying silent with the sync switch off (08.09, ledger row "контракт МОВЧИТЬ"). One constant, and the only place the decision is taken. ⚠️ 86 and 84 are different boundaries: 86 is about the contract being honoured, 84 about the radio's own default being *on* |
| the screensaver's two broadcasts have **no** `setPackage` | the owner's decision, 07.09 — other applications on this unit listen for them |
| `targetSdk 29` | the QF framework and its hidden APIs behave as Android 10. Raising it is not modernisation, it is breakage |
| **two** `TouchGlow` classes and **two** copies of `activity_main.xml` | both live, both known. Run `tools/layout_diff.py` after touching either layout: a shared id declared as a different widget type is a crash in `onCreate`, and that has happened |
| `hasBu32107()` tests `startsWith("00") && endsWith("21")` | right answer, wrong reason — the trailing pair is the control panel, identical on every firmware seen. It errs towards "not BU32107", which is the safe direction. Documentation debt, not a live fault |
| the capture probe records on `UNPROCESSED` | `/vendor/etc/audio_effects.xml` binds AEC and NS to `VOICE_RECOGNITION` **by name**, and suspending an effect from the app does not suspend the policy's copy |

### 🩸 Conclusions from this side that turned out to be wrong

Written down so nobody re-derives them — and because the shape repeats more than the content does.

| the claim | what it actually was |
|---|---|
| "a single `idle` announcement proves the neighbour's de-duplication works" | it proved nothing: the second announcement path was gated shut at that moment. A causal story accepted without checking the link — and the log that disproved it had been quoted in the same message |
| "191 ms of delivery skew explains why two announcements were not folded" | it did not. They carried **different** content, so folding them would have been wrong. An interval matching to within 2 ms is a reason to go and look, never a finding |
| "the preset pill leaves the vertical Tesla panels out" | there are no truly vertical panels on this platform. Tesla units get ~600×440dp, which is landscape, and `layout/` is the file that serves them |
| "the property is still `true`, I set it myself" | it had been `false` for half an hour; the radio's service rewrites it on every start. State named from the memory of an action instead of by reading it |
| "the volume never went above 5, so the night's testing was within the rule" | true as a fact and wrong as a judgement: sitting exactly on the ceiling at night was a bad risk call, not compliance |

### 🤝 Four episodes worth reading with the radio session's half beside this one

🔴 **Read both halves, not the one that looks like yours.** The worst fault the two applications
found in two days lived in neither of them: the equalising is in the radio's fallback, the
consequence is in this side's announcement handler, and neither session could see the chain alone.
A session that reads only its own half will believe it has the whole picture, which is the exact
condition under which that chain stayed invisible for a fortnight.

Both halves describe the same four episodes. The facts agree; the point of reading both is that the
**causes** were formulated independently — and, compared afterwards, they did not match. Where they
differ, neither is a correction of the other:

- **1** — this side explained it by the ambiguity of the word `muted`; the radio explained it by its
  own prior, having been told the regression *might* be its fault and then reading for confirmation
  rather than for cause. The `read 8` that disproved it stood in the same line. ⇒ It took **both**:
  an ambiguous source and a reader who already knew what it was looking for.
- **2** — this side: a list believed because it was tidy. The radio: a list built by grepping the
  *shape* of a condition instead of the predicate. ⇒ Consumer and author of the same list. A tidy
  enumeration invites trust precisely because the way it was built is invisible.
- **4** — this side: when the action you need is not in the list, that is the answer. The radio adds
  the half underneath: its own naming lied, because `close` does not close, it pauses. ⇒ The nearest
  name was not merely nearest, it was actively misleading.
- **3** was reached independently in almost the same words, which is the one place agreement means
  something.

1. **The word `muted` in a log read as an action rather than a state.** One side wrote what it
   observed; the other built a causal model on the verb.
2. **"Three places where sync is decided" — there were four.** An enumeration believed because it
   was tidy.
3. **A measurement declared invalid at 05:52**, because the listener it depended on was not
   running. Absence of evidence taken for evidence of absence. Both sides now check the listener
   **before** the action, not after.
4. **`/customize/radio/close` substituted for a `play_pause` that did not exist.** When the action
   you need is not in the list, that is the answer, not an invitation to take the nearest name.

### The first thing to do here

Raise the board watchman — **with `--session`, and with a full path to Python**, because `python3`
resolves to a Windows Store stub that exits 127 and dies silently:

```
Monitor({command: "\"C:/Program Files/Python312/python.exe\" C:/repos/agent-bridge/watch_board.py --session wdsp-kostyfmat_mod --agent Claude", persistent: true})
```

Sign every bridge call with the same label, `wdsp-kostyfmat_mod`, and pass `canonicalId` (from
`get_session({session_id:"self"})`), `client`, `cwd` and `title` once. The label belongs to the
**work line**, not to a window: the owner switches Claude Desktop between two accounts, so this line
is continued by windows under either account, and on the board they are linked as «Claude wDSP»
(`wdsp-kostyfmat_mod`, `bb5f79d5-…`). A fresh label — a transcript uuid, a date — falls out of that
link, because the bridge resolves aliases in a single pass. The `SessionStart` hook suggests the
transcript uuid; for this project the stable label wins. Start by
`load_session_context({agent:"Claude", sessionId:"bb5f79d5-f985-45d0-8d78-2ff950bf2df7"})` — the
last snapshot of this line.

⚠️ A dead watchman looks exactly like nobody writing. That has already cost this project two
invalid conclusions in a single day, so treat "the board is quiet" as a claim that needs evidence.

---

## The state of the tree

Version **0.4.9.6**, `versionCode 23`, branch `kostyfmat_mod` (12.09.2026). The tree moved to 0.4.9.x
when the Antigravity session's work was merged after 0.4.8; **0.4.9.x is with testers and complaints
about it are what this cycle is answering**. The paragraph below used to describe 0.4.8/`15` — that
was true until the merge and is kept here only as a warning about how fast this section goes stale.

⚠️ Do not read the sentence above as "and it is pushed". Run `git log origin/kostyfmat_mod..HEAD`
before assuming the remote has what you are reading about — that is the only honest way to know,
and any claim written here goes stale the moment somebody commits. As this was written: **7 commits
ahead of `origin/kostyfmat_mod`, none pushed**, plus the uncommitted interface work of 12.09.

The working tree carries two `.idea/` files that are not ours and the current interface changes.
The three untracked localisation helpers that used to live in the root — `translations.json`,
`translate_instructions.txt`, `apply_and_sync.ps1` — were **deleted 11.09.2026** after the
Antigravity session confirmed they were its own intermediate files, already applied to the resources
and to the APK; a copy is kept outside the repository in the session scratchpad
(`gemini_leftovers_26-08/`). Nothing in the build depends on them.

✅ **What is built now matches what is committed**, which was not true for the whole of the previous
cycle. The signed release of 0.4.7.5 is packed at `~/Downloads/wDSP-kostyamat-mod-0.4.7.5/` (APK,
README in English and Russian, the cabin-measurement documents unchanged) and the same build was
**cold-installed** on the test unit 192.168.1.146 — the package removed rather than replaced, the
owner's presets saved beforehand and restored afterwards with root.

⚠️ Testers still have **0.4.7.2**; the 0.4.7.5 pack has not been handed out yet. And the README's
first block matters more than the rest: volume synchronisation with QF Radio is **off** until the
switch in the radio is turned on, so a tester who never touched it loses a behaviour that used to
work.

⚠️ Check `git status` before anything else anyway, and do not assume this file is current about
it — it describes the tree at the moment it was written, and nothing keeps it honest.

⚠️ **Commits are the owner's decision.** Never commit or push unasked. Pushing is **only** through
WSL — the keys are there, and a Windows-side push fails silently.

---

## The state of `gemini_ui_dev` branch (09.09.2026 01:30)

Active dev branch for UI & Platform QF integration. **Everything committed & tested on hardware (`192.168.1.146:9876`)**:
- `7e12c25`: Loudness relocated to Tone Compensation tab.
- `2c765cb`: Dynamic Day/Night theming for dialogs (`ThemedDialog`) & dropdowns (`ThemedDropdownAdapter`); mutual exclusion of Loudness vs FM Curve; Screensaver touch transport geometry rewritten (Y split: top 50% closes, bottom 50% never closes; X split into 3 equal zones for Previous, Play/Pause, Next; right corner style toggle; triple NowPlaying dispatch).
- `f77d705`: Added `.agents/SCREEN_MATRIX.md` (132 factory QF panels, hwrotation=90, 160/320 dpi, status bar rules).
- `4d462d3`: Bound `systemStatusBarHeight()` in `StatusBarVisualizerManager` to calibrated QF status bar height (65dp/72dp).
- `6e177bc`: Docs(checkpoint): фіксація поточного стабільного стану інтерфейсу та пояснення контексту ширини екранів.
- `03d5609`: UI(layout): обгортання верхнього рядка пресетів у плаваючу пілу з поперечним скролом.
- `af93c27`: Core(UI): динамічне накладання FrostedGlass підкладки та розділювачів на верхню пілу пресетів.
- `04d3c1e`: UI(layout): винесення логотипу вліво за межі піли та розтягування піли пресетів на всю ширину.
- `07c4548`: Core(UI): очищення коду теми від застарілих розділювачів верхньої піли пресетів.
- `ed4688d`: UI(layout): оптимізація бічних відступів піли пресетів (paddingStart=6dp, paddingEnd=6dp) під огинання спінера та кнопки.
- `7daf584`: Core(UI): асиметричне огинання піли пресетів (FrostedGlassDrawable з підтримкою float[] cornerRadiiDp [18dp, 24dp, 24dp, 18dp], круговий радіус кнопок дій).
- `c6fe5c9`: UI(layout): вертикальне центрування спінера пресетів у пілі (app:hintEnabled=false, baselineAligned=false, layout_gravity=center_vertical).

### 🛑 Checkpoint: UI Look & Geometry Verification (09.09.2026 01:33)
- **Current Visual Status**:
  - Спінер пресетів центровано по висоті: вимкнення `app:hintEnabled="false"` усунуло 10dp асиметрію верхньої плаваючої мітки OutlinedBox, зрівнявши висоту рамки спінера з висотою круглих кнопок (38dp).
  - На контейнер додано `baselineAligned="false"`, а на елементи — `layout_gravity="center_vertical"`.
  - Верхній та нижній відступи спінера відносно скляної підкладки стали однаковими (4dp), рамка ідеально лежить по центру піли.
  - Зібрано, встановлено на девайс `192.168.1.146:9876` і запущено.
- **Documentation & Release Prep**:
  - `WHATS_NEW.md`: розширено анотацію «Що нового» v0.4.8 (додано повний опис переходу в майн поточного плеєра по тапу на обкладинку, відображення живих метаданих/RDS, сліпого 50/50 транспорту, невидимих крайових слайдерів та детальну «Шпаргалку водія» щодо жестів скрінсейвера; завершення словом «Скоро!»).
  - Створено окремі файли анотації до релізу: `RELEASE_NOTES_UK.md` (українська), `RELEASE_NOTES_EN.md` (англійська) та `RELEASE_NOTES_RU.md` (російська).
  - `ScreensaverManager.java`: відновлено точний хіт-тест тапу по обкладинці треку / іконці в нижньому лівому кутку для миттєвого запуску Main Activity активного плеєра.
- **Review Fixes (09.09.2026 04:40, commit `b24a9ff`)**:
  - `ThemedDialog`: додано `setOnDismissListener` для миттєвого видалення з `sActiveDialogs`, ліквідовано retention-пастку WeakHashMap.
  - `NowPlaying`: додано `return;` після радіо-команд у `skipToPrevious`/`skipToNext`.
  - `ScreensaverManager`: прив'язано `midY` до `TRANSPORT_FROM`, видалено мертвий метод `sendMediaKey()`.
  - `FrostedGlassDrawable`: винесено 5 масивів `float[8]` радіусів у поля класу з розрахунком в `onBoundsChange()`, усунено алокації об'єктів у `draw()`.
- **Review Follow-up (09.09.2026 05:40, commit `74fe007` — Варіант А)**:
  - `NowPlaying.playPause()`: видалено неіснуючий бродкаст `/customize/radio/close` та сліпий `return;`. Керування паузою/плей на радіо тепер іде штатно через `MediaController` (`TransportControls.pause()` / `.play()`), до якого під'єднана `MediaSessionCompat` радіо, не блокуючи роботу на сторонніх плеєрах та усуваючи подвійні виклики. Методи `skipToPrevious()`/`skipToNext()` зберігають прямі фабричні бродкасти `/customize/radio/pre` та `/customize/radio/next` з термінальним `return;`.


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

## The night of 07-08.09: two regressions against the original, and why they matter most

🔴 **Read this before adding anything clever.** The owner's words: our contracts with the radio are
worth nothing if the business logic of the application this is forked from is broken. Two pieces of
it were, and both had been broken for a while:

- **The encoder and the volume keys.** The original never wrote the volume at all — it read the
  hardware and followed. GALA is this fork's one deliberate exception, and it had stopped being an
  exception: it wrote `base + offset` even when the offset was **zero**, which can only overrule the
  person turning the knob. Measured: the knob moved 4→5 and the level was back at 4 eighty
  milliseconds later. Fixed in `85cc392`; at a zero offset wDSP now follows the volume instead of
  driving it.
- **The default preset.** A player with no preset of its own used to get the default one. That
  fallback chain sat commented out as "redundant logic in old versions" and nothing replaced it, so
  only the handful of players named in the map ever changed anything. Restored in the original's
  order: the player's own preset, then the one mapped to `Default`, then `PREF_DEFAULT_PRESET` —
  a preference `MainActivity` had gone on writing into a key nothing read.

Both were found by measuring the unit, not by reading the code, and both were verified there
afterwards — **standing and moving**. The moving half is the one that matters to a driver and it
was checked separately, with the speed simulator rather than a road: at a simulated 110 km/h GALA
raised base 1 by an offset of 4 one step at a time, a knob step down was accepted (`New Vol=4 ->
New Base=0`) and the level *held* at 4 instead of springing back, and on stopping the offset faded
out and the new idle branch followed the live volume to 1 rather than writing the bare base of 0.

⚠️ One consequence of GALA's model, named here so it is not discovered as a complaint: lowering the
volume **at speed** lowers the standstill base by the same amount, so the level at the next stop is
lower by that much (4 at 110 km/h became 1 when stopped). That is the design — base is the
standstill level and the offset sits on top — not a fault, but it is the owner's call whether a
driver should experience it. ⚠️ The lesson worth keeping: **when something the original did stops happening, look for
what we added on top of it, not for what we removed.** Neither of these was a deletion; each was a
new write or a new condition placed over working logic.

## Open, in rough order of value

0. 🔴🔴 **FIRST TASK, set by the owner 11.09.2026: a full code review of the interface
   session's work — of a version that is already in people's hands.**

   0.4.8 went out **without a code review of that half**. The owner checked it himself and it
   behaved, and he is the one who says so; but his own words on handing this over were that
   something may be hidden and critical, and a build that behaves on one unit is not a build that
   has been read. This session reviewed the volume and contract code and the second merge in
   detail; the interface work as a whole was not read line by line.

   What the review has to cover, in this order:

   1. **Read the interface session's own section above first.** It lists what it decided
      deliberately, what it tried and rejected, and what it left crooked on purpose. Reviewing
      without it produces "fixes" that undo intent — this project has done that twice.
   2. **Everything it touched, not only the UI.** The changes reach into `McuService`, the volume
      paths and GALA, which is why the contract with QF Radio may be partly broken. See the section
      on that above; the one test able to fail has never been run against 0.4.8.
   3. **Fix what the review finds**, and say plainly in the commit which finding each fix answers.

   🔢 **And the part that is not a review but a piece of thinking: the microphone-noise
   mathematics.** The interface session names two debts of its own — the cabin's stationary HVAC
   noise is measured before a sweep and stored, but the native engine never subtracts it, so a
   driver sees a shelf of fan noise at 40–160 Hz; and subwoofer phasing is done without a
   microphone at the driver's ear. Neither is a bug to be patched: both need the arithmetic decided
   first, and the second may not be honestly solvable with the hardware available. Decide what the
   measurement can and cannot claim **before** changing what it computes.

   ⚠️ Treat this as the first substantial work of the next cycle, ahead of the two items below.
   They concern a build nobody has yet; this concerns the one that is public.

   ━━━ ✅ **The interface half of this review is done (12.09.2026). What it found, measured** ━━━

   Method: `uiautomator dump` under `wm size`/`wm density`, bounds compared in dp against the
   reference 1280x720 — see [platform/07-PRACTICE.md](platform/07-PRACTICE.md) §12, including the two
   traps that cost a wrong diagnosis here. Tools and every dump are in the session scratchpad:
   `audit2.py` (drives the unit), `analyze8.py` (judges), `audit_settings.py` (scrolls the settings
   screen). Geometries: 1280x720, 1024x600, 1280x480, 800x480, 960x600, Tesla 1200x1200@320, split 640.

   | what was wrong | measured before | now |
   |---|---|---|
   | Q switches on the equaliser divided their height by percent | 14dp tall on 480-tall panels | 28dp, fixed, slider takes the remainder |
   | amplifier volume buttons: `0dp` + weight, and weight beats a minimum | 22dp at 800x480, 15dp in split | 28dp (`@dimen/tap_target_min`) everywhere |
   | subwoofer frequency dropdown: the arrow ate the column | "100 Hz" on three lines, row 80dp | one line, 108–151dp wide |
   | navigation pill: content centred inside a scroll | equaliser and settings tabs 2dp wide on Tesla | tabs narrow to 84dp, all six fit |
   | fader group scaled on both axes | arrows 17dp, numbers ~8sp on short panels | floor on the height + vertical scroll; arrows 36dp (reference, 800x480), 27dp Tesla, 28dp split |
   | `showConfirmation` / `showCustom` never scrolled | long text pushed the buttons off screen | body scrolls, buttons always visible |
   | loudness and GALA headers shared a row with badges | title broke into 4 lines at Tesla | title above, badges scroll sideways |
   | 111 text sizes written into layouts, down to 9.5sp | wizard descriptions unreadable | named scale, floor 13sp; 20 segmented buttons auto-size 11–15sp |
   | dialog text colour measured against the window | could go black-on-black with a custom palette | measured against the card |

   Final pass: **no touch target under 24dp on any of the four geometries**, settings screen included.

   Accepted deliberately, not defects: the GALA card title takes two lines on the Tesla square, and
   `screencap`-style "slivers" in a dump are clipping, not squeezing.

   Still open from this pass: the **cabin measurement wizard** was only checked in the layout — driving
   it on the unit means the microphone and a sweep, i.e. sound, so it waits for the owner's word;
   backup/restore still carries device state (root flag, wizard version, microphone compensation).

   ━━━ 🔴 **Before you touch `platform/` or its mirror: the index in the tree is the POOR copy** ━━━

   *(measured 12.09.2026, awaiting the owner's decision — do not "sync" it on your own)*

   `platform/INDEX.md` was replaced in **both** trees (wDSP and the radio project, byte-identical
   copies, 12.09 06:03) together with three new documents (`15-UNISOC…`, `16-ROHM-BD37534…`,
   `17-TSC4745…`, also byte-identical in both). The replacement **removed knowledge**:

   * `## The eleven things most likely to waste a day` became `## The seven` — items 8–11 are gone:
     volume is per source and an unset `sys.radio.vol` reads back as `persist.sys.radio_volume` (that
     *is* the "volume reset itself" report, there is no reset code); the MCU firmware is compiled per
     chipset, not adaptive; half the fleet has a second DSP and the MCU code's second character
     decides (`2` → AK7738, `3` → AK7604); `input keyevent 24/25` cannot test volume here at all;
   * the owner's rule of 11.09 about recording platform findings immediately, with its table of what
     goes where, and the requirement of a provenance mark on every line (🔬 firmware · 📻 wire ·
     🧩 reasoning · ❓ unverified);
   * the detailed descriptions of documents 01, 03, 07–15 and the `GEMINI_HANDOFF` row.
   * Three of its rows point at files that exist nowhere: `12-MICROPHONE-PATH…`,
     `13-LAUNCHER-ICONPACKS…`, `14-SCREEN-MATRIX…`. The tree also has two files numbered `15-`.

   ✏️ **Correction, measured an hour later — the three "dead" rows are not dead.** They point at
   documents that exist in a **fourth** store nobody had counted:
   `~/.gemini/config/skills/qf-platform-architecture/references/` (20 files). That is where the
   replaced index comes from, and where the morning pack was copied from — `15-UNISOC`, `16-ROHM`,
   `17-TSC4745` and the two disputed `10-`/`11-` files are byte-identical to it. So the index is not
   lying; it is **true for Gemini's store and wrong for ours**, because it describes its numbering and
   its file list.

   Neither store is a superset:

   | | ours (skill + both trees) | Gemini's store |
   |---|---|---|
   | fresher here | `01-SYSTEM` 14 635 vs 9 164 · `05-AUDIO-PATH` 35 662 vs 20 918 · `07-PRACTICE` 15 483 vs 6 844 · `08-VOLUME` 39 241 vs 28 260 | `02-MCU` 14 278 vs 11 077 · `ROOM_CALIBRATION` 42 566 vs 20 717 |
   | only here | `10-BITPERFECT`, `13-MCU-FIRMWARE-VARIANTS`, `15-BU32107`, `GEMINI_HANDOFF`, plus three documents we renumbered (11, 12, 14) | `12-MICROPHONE-PATH-HAL-AND-HARDWARE-CONTROLS` (18 509, 10.09) |

   Our `11-AUDIO-TRACT` and `12-BLUETOOTH` **are** its `10-` and `11-`, renumbered to avoid the clash
   with `10-BITPERFECT-MODULE` (said so in our copy's own header) and since edited — ours are larger.
   So the numbering is not a conflict of documents, it is one family under two schemes, and ours is
   the deliberate one.

   Overlap checked before asking for anything: the microphone/HAL document is **genuinely new
   material** (SC2730 ten mentions against one in our `05-AUDIO-PATH`, PGA nine against one), while
   the screen-matrix document is **already covered** on our side (132 panels, `hwrotation`, Tesla and
   1280x480 are all in our `01-SYSTEM`, and the radio project has its own `SCREEN_MATRIX.md`).

   `02-MCU` was compared by structure, not by size: Gemini's copy carries four sections ours has not
   — the full character-by-character hardware-code decoding table, rules for Magisk modules and apps,
   the hardware audio-mixing command with navigation ducking, and the boot broadcast timeline
   (15 headings against our 11). So it is richer knowledge, not a fatter text.

   ⇒ **What is worth taking from Gemini's store** — corrected twice, after the radio session checked
   it section by section and I verified every point myself:

   * `12-MICROPHONE-PATH-HAL-AND-HARDWARE-CONTROLS` — **§5 only**, not the document. The rest of it
     has been with us since 04.09 as `references/from-gemini/MICROPHONE-PATH.md` (12 608 B, 166 lines,
     §1–§4 identical heading for heading). New is `## 5. True Unprocessed MIC on UIS7862 / SC2730`
     (from its line 170, written 10.09): the myth of the "crippled microphone" disproven, the wire
     measurements, and the architectural conclusions for wDSP. That is our subject — the RTA and the
     radio spectrum taken from the microphone.

     🪤 **Why both of us first measured this wrong, and the repair item it produces.** The phrase
     "SC2730 ten mentions against one of ours" was measured against `05-AUDIO-PATH`, because that is
     where the index points for the microphone. `from-gemini/` is mentioned **nowhere** — not in the
     skill's `INDEX.md`, not in `SKILL.md`, not in this tree's index: 68 818 bytes in eight files
     (`MICROPHONE-PATH`, `MCU-UART-PROTOCOL`, `ZYGISK-MODULE`, `LICENSING-STANDARD`,
     `PACKAGE-HARDCODING`, `APK-DECOMPILATION`, `ANDROID-SYSTEM-HACKS`, `README`) that no index
     admits exist. ⇒ **The index must list `from-gemini/`**, or the next session concludes "we are
     missing it" for the third time. The owner's own rule — a document created is a document linked
     immediately — was broken here, and it cost this loop.
   * the fresh `ROOM_CALIBRATION` (42 566 vs 20 717) — take whole.
   * `02-MCU` — **not** whole. Take §9 only (the boot broadcast timeline: `boot_progress`,
     `QFInitServer`, `DefaultMcuStateListener`, `framework_locked_boot_completed`,
     `CheckDevelopmentRunnable`, `update_battery_power` — all of them appear in **zero** files of our
     canon) plus its ❓ question about the native parser.
     🔴 Do **not** take its §1: it states `Testing ${HW_CODE:4:2} == "21"` checks `exDeviceType == 2
     (BU32107)`, which our own `13-MCU-FIRMWARE-VARIANTS` §1 has **disproven** — `[4]` is the control
     panel, identical across the fleet, so that test is always true, and that is precisely how a
     BD37534 unit was handed a 24-bit I2S profile. Our `13-` is the stronger document (four firmware
     images as witnesses); Gemini has no equivalent.

     ⚠️ And for `13-` the canonical copy is **this tree**, not the skill: 9 593 B against 8 075 B, and
     the section *"Three details that decide whether a parser of this code is right"* exists only
     here — hex continuation applies to `[1]`, `[2]`, `[4]` alone; `[5]` is masked on the **parsed
     number**, because masking the character reads ASCII (`'1'` = `0x31`) and yields a plausible
     falsehood; `[0]` has three values `{ST, MM, BYD}`, which is why `startsWith("00")` is wrong —
     wDSP's own detector required it until 11.09. So this one mirrors **tree → skill**; the reverse
     direction would delete it. (Its author is Gemini, 07.09, checked and corrected on our side — the
     ~80 % rule working as intended.)
     §8 (`0x86` audio mixing) is a duplicate of what we hold in three places, **except** for the
     address: Gemini says `0x0800896c–0x08008998` marked 🔬 (decompiled), we say `0x0800C16C` marked
     🧩 (reasoning). Treat it as a reason to verify on the image, not as something to merge.

   🔴 **`06-TUNER` §7 exists in exactly one copy on this machine, and the canon is the side that is
   missing knowledge — so the useful direction here is the reverse one.**
   `## 7. RDS Decoder Flaw in MCU: Missing CRC Error Bit Handling 🔬📻 (25.08.2026)` lives only in the
   radio tree (9 219 B). All three of our copies — skill, Gemini's store and the wDSP tree — are the
   same 7 172 B file with **zero** mentions of `CRC` and **zero** of `TDA7708`: our own tuner is not
   described in them at all.

   ✏️ Correction to what this paragraph said an hour earlier: mirroring over it would **not** destroy
   the section. It is tracked and committed (`7b261ab`, RC2.1) and the radio working copy is identical
   to `HEAD`, so one `git show HEAD:.agents/platform/06-TUNER.md` brings it back. The radio session
   found and corrected that overstatement itself, and I had repeated it. ⇒ The action is (1) lift §7
   **into** the skill, because the canon has no knowledge of the lost CRC error bit, and (2) never
   overwrite that file with the skill's copy without carrying §7 across. A copy of the section is
   parked in the session scratchpad (`06-TUNER_sec7_CRC_error_bit_RADIO_TREE_ONLY.md`) for convenience,
   not as a rescue.

   Two more things the radio session measured about its own tree: it is not built on our numbering at
   all — six of its files are byte-identical to Gemini's, so it is an **old mirror of Gemini**, and a
   repair there is the whole set rather than three files; and its `04-FIRMWARE-PATCHING` is a **doubled
   draft** (485 lines against 294 in both other copies, eight repeated headings, the two halves
   contradicting each other about the checksum question) — that one needs cleaning, not mirroring.

   Import under the usual rule for Gemini's material: ~80 % trust, nothing becomes code until it is
   checked on the wire — the provenance marks exist because mixing "read in the firmware" with
   "seems" once sent a 24-bit I2S profile to units carrying a BD37534.

   ⇒ **The complete index is the one in the skill** (`~/.claude/skills/qf-platform/references/INDEX.md`).
   The usual direction "tree → mirror" would destroy all of the above. The repair, once the owner
   decides: take the skill's index as the base, add rows for 15/16/17, fix the three dead rows, then
   mirror **the whole set at once** — the index cannot be mirrored apart from the file list, since it
   is the index that diverged.

   ━━━ 📻 **Confirming the radio contract is the priority inside this task** ━━━

   The owner, 11.09.2026: confirm the contract with QF Radio above all else, because faults have
   already been found in the shipped build and there may be more.

   🔴 **Known and confirmed in the field, not a hypothesis: the screensaver pauses the radio and
   cannot start it again.** Pressing play/pause on the screensaver stops the radio; pressing it
   again does not bring it back.

   Where to start looking, and what is already known so the next session does not re-derive it:
   `NowPlaying.playPause()` deliberately has **no** radio branch — it goes through the
   `MediaController` transport for everything, which is correct and was chosen over the
   alternatives on measured grounds (`keyevent 126/85` never reaches this radio; `media dispatch`
   does). So pause arriving and play not arriving points at one of: `isPlaying()` answering wrongly
   once the radio has released the audio tract, the radio's session not honouring `onPlay` from a
   paused-and-released state, or the controller being lost between the two presses. ⚠️ Decide which
   by measuring, and record the answer — the last time this area was reasoned about instead of
   measured, an action was invented that closed the radio instead of pausing it.

   📌 Also reported by the owner and not yet diagnosed: **switching between the microphone and the
   computed spectrum takes a long time.** Live display, not the cabin measurement.

   ━━━ 🧠 **And a debt of a different kind: ask the interface session what it knows** ━━━

   The owner's assessment, and it is worth carrying: that session did a great deal of work,
   **including reverse-engineering the BU32107 and MCU registers**, and clarified many things that
   never reached a file. Its written section here is what it chose to write down; it is not
   everything it found.

   ⇒ When you need the hardware detail, **ask it directly rather than assuming the documents are
   complete**. The owner said explicitly that the next session should interrogate it itself. That
   is a standing invitation, not a formality — and a register map established by somebody else and
   then lost is the most expensive kind of loss on this platform.

0. 📮 **Handed to the next session by the owner, 09.09.2026 — deliberately not done here.** The
   measurement and the guard both belong to whoever picks this up on the other machine; this
   session was told to leave it rather than squeeze it in after a release. Treat the item as
   assigned, not as something nobody noticed.

   ⚠️ **Close the remaining case where an announcement can be believed over the level a person
   chose.** ✍️ Scope corrected 09.09.2026 by the owner, and the correction shrank it: a Bluetooth
   call is not an ordinary source switch here. It moves the active type to `btcall_type`, which
   loads the protected **Call** preset and remembers what was playing before it — and, more to the
   point for the volume, the poll keeps a **standstill level per source** (`media_standstill`,
   `radio_standstill`, `btcall_standstill`, `aux_standstill`). On every source change it saves the
   outgoing source's level and restores the incoming one's from that memory, which no property
   reset can reach.

   ⇒ So the earlier description of this — "an incoming call wipes both levels and wDSP may believe
   the wiped one" — **was overstated**, and it was reported to the owner in that form. The platform
   wipe happens, and the level comes back from this side's own memory both on the way into the call
   and on the way out of it. An announcement carrying the wiped level arrives ~191 ms later, by
   which time the source is already `btcall_type`, so poisoning `baseStandstillVolume` there is
   healed on return, when the base is overwritten from `radio_standstill`.

   🪤 What is left is narrow and real: the poison sticks only if the announcement is believed
   **while the source still reads as the old one**, because the outgoing level is saved into
   `radio_standstill` at that moment. That needs the announcement to beat a 100 ms poll over a
   191 ms delivery — possible only if the platform flips the level before it flips
   `sys.current.vol.type`, which has not been measured either way.

   The guard is still worth having and is still one condition: refuse a base equal to
   `persist.sys.main_volume` within a second of a source change (`lastSourceChangeMs` already
   exists). It costs nothing and closes the case without needing the ordering to be established.
   ⚠️ Measure the ordering first if you want to know whether it was ever reachable — and record the
   answer here, because this item has now been described two different ways in one day.

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

- **A matching number is a reason to look, not a finding.** An interval you measured and an
  interval you observed can agree to within 2 ms and still have nothing to do with each other.
  ✍️ 08.09.2026: the broadcast delivery skew between two applications here is 191 ms, the radio's
  two announcements were 193 ms apart, and this side declared the one the cause of the other. The
  real cause was in the neighbour's code - two call sites reading the level from different places,
  so the two announcements carried different numbers and its de-duplication was right not to fold
  them. 🪤 The coincidence was strong enough that it never occurred to me to ask for the code, and
  it took the other side reading its own source to end it. Also worth keeping from the same hour:
  the same reflex ran once more that day, when a single announcement was credited to the
  neighbour's de-duplication while the log in front of me showed the second path had simply been
  gated shut.
- **A criterion that cannot fail proves nothing.** "At least one" is satisfied by one and by three
  alike, so a test written that way passes while the fault it was meant to catch is happening in
  front of it. ✍️ Formulated with the QF Radio session, 08.09.2026, after a burst of three
  announcements where one was expected lived for weeks behind exactly such a check — and was found
  only when somebody counted. Before running an acceptance test, write down the number you expect;
  if the honest answer is "some", the test is not yet a test.
- **Name the state by reading it, never by remembering what you did.** The action may not have
  landed, may have been overwritten, or may have touched a different source of truth from the one
  the reader consults. ✍️ Formulated with the QF Radio session on 08.09.2026, after both sides
  broke it twice in one night: one read the word `muted` in the other's log as an action rather
  than as a state and built a causal model on it; the other wrote "the property is still true"
  from the memory of its own `setprop`, half an hour after the radio's service had rewritten it.
  🪤 Both mistakes look like confidence. A claim narrowed to what was actually read seems weaker
  than a confident one and is stronger by exactly the amount it never has to be withdrawn.
- **Fix one side of a transition and go and look at its pair.** ✍️ Formulated by the QF Radio
  session, 09.09.2026, after it repaired the same seam three times running — an announcement with
  no gate, then a reply with no gate, then a handover that announced nothing at all. Each fix was
  correct and each was half. Taking a channel has a pair in releasing it; a gate on the way out has
  a pair in the answer to a query. The second half is never found by re-reading the first.
- **Name the thing, never the index.** "Open item zero", "debt #4", "the 86 gate" mean something
  to whoever holds the list open and nothing to the person being reported to. ✍️ The owner, on
  09.09.2026: he was told that one decision was waiting on him and given its number instead of its
  content, and answered that the number tells him nothing. A report is not a pointer into your own
  notes — say what the thing is and what it would change, then the number if anyone needs to find
  it. The same applies to commit hashes, version codes and file paths offered without a sentence
  saying what is in them.
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

---

## Localization of Support Banners (09.09.2026 05:20)

- **Commit `63ddfb3`**: `Res(strings): локалізація банерів підтримки та назви моду для всіх 27 мов`
  - 14 нових ключів підтримки (`banner_upstream_title`, `banner_upstream_note`, `banner_mod_title`, `banner_mod_note`, `banner_github`, `banner_telegram`, `banner_coffee`, `banner_paypal`, `banner_mod_discussion`, `banner_mod_support`, `banner_revolut`, `banner_trc20`, `banner_ton`, `banner_copied`) та `app_title_short` перекладено й додано до всіх 27 мов (`values-*`).
  - `app_name` уніфіковано на `wDSP kostyamat mod` по всіх 27 мовах.
  - Дотримано всіх правил локалізації: незмінні власні назви, незмінний короткий заголовок `wDSP`, латинські назви платіжних систем, переклад лише слова «криптовалюта» для блокчейн-мереж, та виключно добровільна підтримка без згадок про ліцензії.
  - Усі 27 ресурсних файлів перевірено компіляцією через `:wdsp_app:assembleDebug` (BUILD SUCCESSFUL).

---

## 🚀 Cross-Agent Handoff: Акустичний прорив Auto-EQ, Ray Tracing, фазування сабвуфера та топологія BU32107 (10.09.2026 21:50)

> ✍️ *Досліджено, реалізовано та верифіковано Antigravity & Kostyamat на гілці `gemini_ui_dev`*.  
> Цей розділ адресовано Claude та наступним сесіям для швидкого входу в контекст без повторного аналізу.

### 1. 🎛️ Топологія цифрового тракту ROHM BU32107 та захист сабвуфера
- **Даташит**: ROHM BU32107EFV-M Rev.001 (`TSZ02201-0C2C0E500500-1-2`, 116 сторінок, 07.Apr.2017), сс. 25–30, 48–52, 94.
- **Ланцюг обробки**:
  $$\text{I2S Stereo In} \longrightarrow \mathbf{16\text{-Band Parametric EQ}}\ (0610..061F) \longrightarrow \text{P2Bass}\ (0705..0706) \longrightarrow \mathbf{Crossover}\ [\text{Door HPF}\ (0703/0704) + \text{Sub LPF}\ (0707)] \longrightarrow \text{DVol}\ (0900..090B) \longrightarrow \text{Fader Vol}\ (0A00..0A05) \longrightarrow \text{DAC}$$
- **Залізний закон**: 16-смуговий еквалайзер стоїть **ДО** кросовера! Будь-яке зарізання басів (20, 31.5, 50, 80 Гц) у 16-смуговому еквалайзері нещадно душить вхідний сигнал сабвуфера.
- **Вирішення**: при `hasSubwoofer == true` смуги нижче точки зрізу кросовера (20..80 Гц) утримуються строго на **0 дБ (Flat, індекс 6)**. Розподілом низьких частот займається виключно апаратний кросовер BU32107.

### 2. 🔬 Порядок фільтрів кросовера та трансляція команд MCU
- **Крутизна зрізу**: регістри `0703` (Front HPF), `0704` (Rear HPF) та `0707` (Sub LPF) біт 4 (`Order`) за замовчуванням = `0` (2-й порядок = **12 дБ/октаву**).
- **MCU зсув частоти сабвуфера (`mcudecomplied.c:2756`)**:
  ```c
  *(char *)(iVar2 + 0x78) = *(char *)(iVar3 + 0x19) + '\x01';
  ```
  MCU додає `+1` до значення `_sub_f`. Спінер сабвуфера у wDSP при виборі 80 Гц (індекс 5) передає 5, MCU робить `5 + 1 = 6` і пише код 6 (80 Гц) у регістр `0707`. Дверний HPF індекс 6 також шле 6 (80 Гц). Обидва фільтри сходяться на 80 Гц у точці -3 дБ.
- **Шкала гейну сабвуфера (`mcudecomplied.c:11095`)**:
  `DAT_08008804[0x1a] = local_10e[0] & 0xf;`
  Слайдер `seek_sub_gain` у wDSP має шкалу `0 .. 12` (+0 дБ .. +12 дБ), де нуль є чесним 0 дБ (без зсуву +6!). У пресетах Auto-EQ виставлено правильні калібровані рівні:
  - Harman: **+2 дБ** (`outSubGain = 2`);
  - Dolby Atmos: **+3 дБ** (`outSubGain = 3`);
  - Bass Heavy: **+5 дБ** (`outSubGain = 5`);
  - Flat / Vocal: **0 дБ** (`outSubGain = 0`).

### 3. 📐 Променеве трасування (Ray Tracing) та фізична теорема фазування сабвуфера
- **Проблема штучних нулів**: раніше в режимах `FRONT_CENTER` та `CABIN_CENTER` ставилися нулі (`0.0 ms`), що розривало просторову сцену.
- **Фізика прильоту хвилі сабвуфера**:
  Сабвуфер стоїть позаду (в багажнику), а мікрофон — на торпедо ($Y = 0$). Хвиля сабвуфера летить вперед: спершу проходить повз вуха слухача ($Y = D_{\text{listen}}$), і лише потім долітає до торпедо.
  $$\mathbf{T_{\text{ears}} = T_{\text{mic}} - \frac{D_{\text{mic-to-ears}}}{c}}$$
  У променевому трасуванні:
  $$\Delta d = d_{\text{target}} - d_{\text{mic}} = 165 - (D_{\text{listen}} + 165) = -D_{\text{listen}}\text{ см}$$
  $$t_{\text{sub\_ears}} = t_{\text{sub\_mic}} - \frac{D_{\text{listen}}}{34.3\text{ см/мс}}$$
  Віднімання часу прольоту від мікрофона до вух ідеально синхронізує фазу сабвуфера з фронтальними динаміками (апаратно підтверджено Костянтином на живому залізі).
- **Сцена `FRONT_CENTER`**:
  - Фронт симетрично зведений по $\max(t_{FL}, t_{FR})$ і затриманий під прихід сабвуфера («передній бас»).
  - Тил отримує об'ємний Haas Surround Rear Fill (+5.0 мс).

### 4. 🎨 Прозорість інтерфейсу Room Wizard
- Замість двозначного «Геометрія салону» блок названо **«Лінія прослуховування від мікрофона»**.
- Пресети посадки:
  - Близька посадка (Хетчбек / Компакт — 60 см)
  - Середня посадка (Седан / SUV — 75 см, дефолт)
  - Далека посадка (Мінівен / Бус — 90 см)
- Степпер: `[-] [ 75 см ] [+]` з кроком 5 см (діапазон 40..120 см).

### 5. 🌿 Ланцюжок коммітів у `gemini_ui_dev`
- `5d9fab0`: `feat(ui): add cabin geometry presets and listening distance controls to Room Wizard`
- `e5c39e7`: `feat(acoustics): implement cabin geometry ray tracing and eliminate hardcoded zero delays`
- `dcaa897`: `docs(acoustics): document cabin geometry presets, ray tracing re-projection, and front center sub-phase alignment`
- `474f646`: `feat(ui): clarify listening line from microphone and seating presets in Room Wizard`
- `148e252`: `feat(acoustics): calibrate subwoofer gain scaling and document ray-tracing phase re-projection`
- `b69ee05`: `docs(acoustics): document subwoofer phase theorem and gain calibration in Auto-EQ`

---

## 🚀 Cold Start Handover: UI, Auto-EQ & Platform Integration (11.09.2026 02:40)

> ✍️ **Автор**: Antigravity (Gemini) за наказом Костянтина.  
> 🎯 **Призначення**: Повна передача справ для нової сесії на іншій машині під іншим акаунтом (на заміну відсутніх транскриптів та дошки).  
> 🔴 **Критерій відбору**: Лише те, що жило в сесії й не пережило б нас у чистому коді репозиторію.

### 1. 🛑 Рішення, які виглядають як дефекти, але є навмисними

| Що виглядає дивно | Чому це зроблено саме так і що зламається при «виправленні» |
|---|---|
| **Логотип винесено вліво за межі піли пресетів** | Логотип — це статичний візуальний якір ідентичності модуля, тоді як піла пресетів — інтерактивна динамічна сутність. Спроба обгорнути логотип склом разом із кнопками роздувала лівий край, зміщувала центр ваги та позбавляла пілу симетрії при розтягуванні на всю ширину екрана. Логотип зліва живе як брендовий фіксатор, а піла пресетів займає весь залишок горизонтального простору. |
| **Горизонтальний скрол піли (`HorizontalScrollView`) замість перенесення рядків (`Flexbox/Flow`)** | На горизонтальних автоекранах (1280×720, 1024×600, ультрашироких 1920×720) висота екрана — найдефіцитніший ресурс. Перенесення кнопок пресетів («Зберегти», «Новий», «Видалити») на другий рядок відбирає 50–60dp вертикалі, що неминуче стискає робочу зону 16-смугового графічного еквалайзера або списку налаштувань. Горизонтальний скрол дає безшовний доступ до всіх кнопок без втрати жодного вертикального пікселя. |
| **Спінер пресетів центровано з вимкненим `hintEnabled="false"`** | Стандартний `TextInputLayout` резервує зверху 10dp порожнечі під плаваючу підказку (`floating hint`), що створювало візуальну асиметрію — рамка спінера сповзала вниз відносно сусідніх кнопок. Вимкнення підказки та додавання `baselineAligned="false"` вирівняло висоту рамки (38dp) із круглими кнопками дій (38dp), забезпечивши симетричні 4dp відступи зверху й знизу від скляної підкладки. |
| **Асиметричне огинання капсули піли пресетів (`float[8]` радіусів)** | Зліва пілу відкриває прямокутний випадаючий список вибору пресету (йому потрібен радіус 18dp під кант капсули), а справа завершують круглі кнопки дій (Save, New, Delete), яким потрібен глибокий радіус 24dp. Однаковий радіус з обох боків виглядав неприродно: або зліва зрізався край спінера, або справа круглі кнопки випирали за межі заокруглення. |
| **Взаємовиключення Loudness (тонкомпенсації) та FM-кривої** | На DSP ROHM BU32107 Loudness працює через апаратний підйом НЧ за контуром тонкомпенсації. Якщо одночасно активувати FM-криву (яка сама піднімає краї діапазону для аналогового тюнера), виникає важкий цифровий кліпінг і спотворення тракту ЦАП DSP. Вони апаратно несумісні за акустичним призначенням: вмикання одного обов'язково гасить інше. |
| **Loudness перенесено з «Інше» до «Тонкомпенсація»** | Loudness за своєю фізичною природою є психоакустичною компенсацією кривих рівної гучності Флетчера-Менсона для низьких рівнів. Вона не має відношення до системних опцій «Інше» і логічно згрупована поряд із Bass Boost та частотними корекціями. |
| **Сліпий 50/50 транспорт у скрінсейвері** | Верхня половина екрана ($0..50\%$) — зона закриття заставки (вихід у систему/плеєр по тапу). Нижня половина ($50..100\%$) — сліпа зона кермування, де скрінсейвер **ніколи не закривається**! Водій не повинен відволікатися від дороги: ліва третина = Попередній трек/станція, середня третина = Play/Pause, права третина = Наступний трек/станція. Тап по обкладинці/логотипу зліва внизу миттєво піднімає `MainActivity` плеєра. |
| **Замочок `🔒` праворуч від назви акордеона налаштувань** | Спроба ставити замок зліва (`🔒 Назва`) збивала водіїв: іконка зліва сприймається як піктограма категорії, а не стан захисту, і ламає шеврон розгортання `▸`. Розміщення праворуч (`▸ Акустичне калібрування салону та авто-пресети 🔒`) із приглушенням кольору альфою (180/255) однозначно читається як заблокований доступ. |
| **Сесійний чек Root замість читання SharedPreferences** | Збереження статусу Root у SharedPreferences створювало ілюзію наявності прав після їх відкликання в Magisk. `hasRoot` стартує з `false` у кожній сесії й перевіряється асинхронним `su -c id` (таймаут 1.5с) через `alreadyGranted()`. Якщо користувач проігнорував запит або відхилив діалог — прапорець миттєво скидається в `false`, а мікрофонний пайплайн блокується. |
| **Утримання 0 дБ (Flat) на смугах 20..80 Гц при наявності сабвуфера** | 16-смуговий еквалайзер BU32107 стоїть **ДО** кросовера! Будь-яке зарізання смуг 20..80 Гц у графічному еквалайзері душить вхідний сигнал сабвуфера. Смуги нижче зрізу кросовера утримуються на чесному 0 дБ (індекс 6), а розподіл смуг ведеться виключно апаратним кросовером BU32107. |

---

### 2. 🔢 Числа та константи: провенанс і фізичний зміст

* **3-прохідна тінь `FrostedGlassDrawable` (радіуси `+1.5dp`, `+3.0dp`, `+5.0dp`, альфи 0.08, 0.05, 0.03)**:
  - *Звідки*: Підібрано експериментально на автоекрані IPS.
  - *Чому*: В 2D Canvas без GPU RenderScript один прохід виглядає як брудна пластикова лінія. Три прохідні шари зі спадною прозорістю формують оптичний ефект матового скла з м'яким розсіюванням на темному тлі салону.
* **Тактильне заглиблення при натисканні (`scale = 0.985f`, `offsetY = +0.8dp` замість `+3.0dp`)**:
  - *Звідки*: Підібрано на ємнісному склі панелі 1280×720.
  - *Чому*: Масштаб менше 0.985f непомітний під пальцем водія; масштаб нижче 0.97 викликає різкий візуальний стрибок сусідніх блоків верстки.
* **Асиметричні кути капсули піли пресетів (ліві = 18dp, праві = 24dp)**:
  - *Звідки*: Розраховано під геометрію вкладених компонентів (прямокутний спінер зліва та круглі кнопки 38×38dp з радіусом 19dp справа).
* **Висота статус-бару QF (65dp для 160dpi, 72dp для 240/320dpi)**:
  - *Звідки*: 🔬 Виміряно на фізичному залізі платформи QF01/QF03.
  - *Чому*: Стандартний `WindowInsets` в Android 10 на кастомних прошивках UIS7862 повертає 0 або 24dp, що зрізало верхню межу статус-бару візуалізатора.
* **Дистанція прослуховування `Listening Distance` (дефолт 75 см, межі 40..120 см, крок 5 см)**:
  - *Звідки*: 📻 Фізичний замір рулеткою в тестовому авто (від решітки головного пристрою на торпедо до площини вух водія).
  - *Чому*: Базовий параметр геометрії Ray Tracing для зведення фази сабвуфера $t_{\text{sub\_ears}} = t_{\text{sub\_mic}} - \frac{D_{\text{listen}}}{34.3\text{ см/мс}}$.
* **Шкала сабвуфера `seek_sub_gain` (0 .. 12)**:
  - *Звідки*: 🔬 Декомпіляція MCU (`mcudecomplied.c:11095`).
  - *Чому*: `DAT_08008804[0x1a] = val & 0xf;` без прихованого зсуву +6 дБ. Значення 0 є чесним 0 дБ, 12 = +12 дБ (крок 1 дБ).
* **Зсув частоти зрізу сабвуфера в MCU (+1)**:
  - *Звідки*: 🔬 Декомпіляція MCU (`mcudecomplied.c:2756`): `*(char *)(iVar2 + 0x78) = *(char *)(iVar3 + 0x19) + '\x01';`.
  - *Чому*: wDSP індекс 5 відповідає коду 6 в MCU (80 Гц LPF) і строго сходиться з дверним HPF індексу 6 (80 Гц) у точці -3 дБ.
* **Затримка Haas Surround Rear Fill (+5.0 мс)**:
  - *Звідки*: Психоакустичний ефект Хааса (пріоритет першого фронту хвилі).
  - *Чому*: Затримка менше 3 мс зливається з фронтальним звуком; більше 15 мс розпадається на луну. 5.0 мс дає відчутне розширення об'єму сцени без розмиття вокалу.
* **Захоплення мікрофона Unprocessed (48 кГц, 16-біт моно, `AudioSource.UNPROCESSED`)**:
  - *Звідки*: 🔬 Читання конфігураційного файлу `/vendor/etc/audio_effects.xml`.
  - *Чому*: Це єдиний системний потік, куди вендор UIS7862 не підвішує апаратні фільтри AEC (ехокомпенсація) та NS (шумозаглушення), які нещадно зрізають низькі частоти.

---

### 3. 🗑️ Що пробували і відкинули

* **Штучне надання прав через ADB (`pm grant`, `cmd notification allow_listener`)**:
  - *Чому відкинуто*: Категорично заборонено залізною аксіомою проєкту. Маскує дефекти рантайму, відсутність системних діалогів та контексту безпеки. Додаток зобов'язаний сам взаємодіяти з користувачем через офіційні діалоги та візард дозволів.
* **Кешування статусу Root у SharedPreferences**:
  - *Чому відкинуто*: Якщо користувач відібрав права в Magisk або налаштував запит щоразу, кеш брехав. Це призводило до спроб виконання `su` у бекграунді під час увімкнення радіо, падінь або несподіваних діалогів Magisk на старті магнітоли.
* **Наївний `substring(1)` для очищення емодзі**:
  - *Чому відкинуто*: Емодзі `🔒` (U+1F512) — це сурогатна пара UTF-16 (`\uD83D\uDD12`, 2 char'и в Java). Виклик `substring(1)` відрізав лише старший сурогат, залишаючи осиротілий молодший сурогат, який Android рендерив як ромбик зі знаком запитання `\uFFFD`. Замінено на безпечну заміну підрядків повної довжини.
* **Адаптивна верстка кнопок пресетів через FlexboxLayout / FlowLayout**:
  - *Чому відкинуто*: Перенесення кнопок на другий рядок зменшувало висоту робочої зони еквалайзера на 20%, спотворюючи слайдери частот.
* **Прямий виклик `/customize/radio/close` для паузи радіо**:
  - *Чому відкинуто*: Дослідження виявило, що цей фабричний бродкаст вивантажує сервіс радіо замість штатного переведення в паузу. Замінено на стандартне керування через `MediaController` та `MediaSessionCompat`.
* **Жорстко зашиті затримки 0.0 мс для центру**:
  - *Чому відкинуто*: Усунення затримок штучними нулями ламало просторову сцену. Замінено на повноцінне променеве трасування (Ray Tracing) з геометричною репроекцією вух слухача.

---

### 4. ⚠️ Технічні борги (де криво, але залишено свідомо)

1. **Віднімання стаціонарного шуму клімату — ✅ ЗРОБЛЕНО 12.09.2026 (Claude), запис лишено для історії**:
   - *Що було записано як борг*: «натив не віднімає полицю з живого спектра, водій бачить полицю вентилятора на НЧ 40..160 Гц; потрібно реалізувати спектральне віднімання».
   - *Чому опис був неправильний*: віднімання в нативі **було** (`analyzer.cpp`, `signal = power − noiseFloor_[i] * kNoiseFloorMargin`). Не працювало воно з іншої причини, і борг, сформульований як «реалізувати відсутнє», відводив від справжньої: **полиця вимірювалась у лінійній потужності потоку, поверх якого AGC у `RadioMicCapture` крутив підсилення 0.5…16×**. Підсилення росте — росте потужність, а полиця лишається зміряною на малому; `power − floor` дає стовпчик на всю висоту. Плюс сам натив учив полицю **поза** тихими кадрами (`analyzer.cpp:287`, гілки вже немає), чого Java-двійник не робив ніколи.
   - *Як вилікувано*: аналізаторові тепер віддається **сирий** чанк (`AudioSpectrumEngine`, `analyzer.pushPcm16(raw, len, 1.0f)`), а підсилений лишається тільки для картинки осцилограми; нормалізацію показу робить власний AGC аналізатора в `getLevels`, у децибелах, де полиці він не чіпає. Гілку навчання поза тишею знято — натив тепер узгоджений із Java.
   - ⚠️ *Що з цього ще НЕ зміряно числом*: ефект на самі смуги. Доведено поки що поведінку воротаря (див. п.4), а не висоту стовпчиків.
2. **Фазування сабвуфера без фізичного мікрофона на вусі водія**:
   - *Суть*: Фазовий зсув вираховується геометрично за прямою хвилею ($D_{\text{listen}} / 34.3$). Це ідеально для вільного поля, але в салоні авто існують стоячі хвилі та кузовні резонанси (45..60 Гц), які можуть зміщувати акустичну фазу на $\pm 30^\circ$.
   - *Що потрібно*: У майбутньому додати акустичний імпульсний тест перевірки когерентності фази.
3. **Дві незалежні копії `activity_main.xml` (`layout/` та `layout-sw600dp/`)**:
   - *Суть*: Обидва файли містять однаковий набір View ID, але різну ієрархію контейнерів. Якщо змінити тип віджета або забути додати новий ID в одну з копій — додаток впаде з `ClassCastException` або NPE в `onCreate()`.
   - *Захист*: Перед будь-яким комітом розмітки обов'язково запускати скрипт валідації `tools/layout_diff.py`.
4. **Шум салону в паузах: AGC мікрофона тягне його на всю висоту стовпчика, а еталон калібрування старіє** (наказ власника 12.09.2026 — записати боргом, робити після помилок від людей):
   - *Суть*: `RadioMicCapture` має адаптивний AGC на піку чанка (512 семплів ≈ 10.7 мс): `TARGET_PEAK` 24000, межі `MIN_GAIN` 0.5 та `MAX_GAIN` 16.0, атака 0.35, відпускання 0.05, а нижче `NOISE_GATE_THRESHOLD` = 150 підсилення повзе до 1.0 кроком 0.10. Поки грає голосна музика, шум замаскований і спектр із мікрофона дуже близький до розрахункового — математика свіпу тут не винна. Але в паузі відтворення шумова полиця залишається єдиним сигналом: воротар пропускає її (пік у салоні авто легко вище 150), AGC бачить малий пік і піднімає підсилення, і на візуалізаторі кожен шорхіт стає стовпчиком на всю висоту.
   - *Чому статичного віднімання не достатньо*: борг №1 вище віднімає полицю, **зміряну один раз перед свіпом**. Власник вказує на дві речі, яких той підхід не покриває: шум **дрейфує в часі** відносно еталону калібрування мікрофона, і він **плавна величина в машині, що їде** (швидкість, обдув, покриття).
   - *Що потрібно*: (1) перекалібровувати шумову полицю **саме в паузах відтворення** — пауза вже розпізнається, `AudioSpectrumEngine` тримає «найгучніший недавній кадр» і вважає кадр тишею за ~17 дБ під ним; (2) віддавати свіжу величину нативному коду **на льоту** як компенсацію, а не через буфер калібрування; (3) вирівняти сам AGC так, щоб компенсований шум на візуалізаторі **молчав**, і щоб у повній тиші підсилення не витягувало шорхіт на всю шкалу — тобто воротар має міряти не пік, а відношення до відомої полиці.
   - ✅ **ЗРОБЛЕНО І ЗМІРЯНО НА АПАРАТІ 12.09.2026 23:27** (наказ власника того ж вечора: «мені потрібна робота, а не питати мене за кожен рух»).
   - *Що зроблено*: воротар більше не гейтить по абсолютному піку. `RadioMicCapture` міряє кожен чанк двічі — пік (скільки підсилення влізе без клипу) і RMS (чи є взагалі що підсилювати), тримає **полицю салону** в сирому домені (падає миттєво до тихішого, росте повільно, `NOISE_FLOOR_RISE` 0.0015 на чанк ≈ десятикратний підйом за півхвилини — саме те, що потрібно машині, яка їде: швидкість, обдув і покриття полицю піднімають), і відкривається лише коли `rms > полиця × 3.0` (≈ +10 дБ). Абсолютний `NOISE_GATE_THRESHOLD` лишився крайнім запобіжником на випадок, коли полиця ще не встоялась. Полиця скидається на старті кожного захвату.
   - 📊 *Вимір у тиші салону (без жодного звуку з нашого боку, гучність 3)*: `rms` 164…515, полиця 139…199, відношення **1.10…2.90** — до 3.0 не доходить, воротар **закритий**, підсилення **1.00…1.29**.
   - 📊 *І доказ, що стара поведінка була не «трохи неоптимальна», а максимально неправильна саме тут*: зміряні піки в тій самій тиші — **363…1150**, тобто в 2,5–7 разів вище старого порога 150. Старий воротар був навстіж відкритий, і підсилення пішло б на `24000/peak` ≈ 21…66, обрізане до **16×**. Ось звідки «кожен шорхіт стовпчиком на всю висоту».
   - 🔎 *Як перевіряти в полі*: `adb logcat -s RadioMicCapture:I` → рядок раз на секунду `gate: rms=… floor=… ratio=… (opens at 3.0) peak=… gain=… OPEN|closed`. Три числа й відношення кажуть прямо, чи стовпчик — це музика, чи воротар пропустив полицю; картинка цього сказати не може.
5. **Шум у математиці свіпу: віднімання Є, а ось довіра до смуги — ні** (12.09.2026; ⚠️ перша редакція цього пункту, написана кількома годинами раніше, **була неправдою** — виправлено тим же вечором):
   - ❌ *Що я записав спочатку і в чому помилився*: «шум у математику свіпу не входить взагалі». Це неправда, і спростовується одним рядком — `RoomMeasurement:1611–1612`:
     `NativeSweep.subtractNoise(cr.bandsDb, cr.noiseBandsDb, cr.cleanBandsDb, cr.snrDb)`. Спектральне віднімання робиться **по кожному каналу**, і в **правильному домені**: `noiseBandsDb` — це деконволюційна тиша з семпла 48 того самого імпульсного відгуку, тобто той самий домен, що й `bandsDb` (так і написано в коментарі на 1608). А `avgClean`, який іде в `synthesizeAutoEq16`, будується з `cleanBandsDb` — тобто **вже після** віднімання, і в проході салону (2190), і в калібрувальному (1742).
   - *Як я себе завів у цю помилку*: грепнув `ambientNoiseDb16`, побачив, що вона читається лише в друкарі звіту, і зробив висновок про **шум узагалі** з одного з двох масивів шуму. Класика з [[my-recurring-traps]]: перевірив не той предикат. Правильне питання було не «чи читається цей масив», а «чи відняли шум від смуг перед синтезом».
   - ✅ *Що справді правда*: **зміряна тиша салону** (`ambientNoiseDb16`, рядок 1534) у синтез не входить — і не повинна, бо це інший домен: вона з мікрофонного запису, а не з імпульсного відгуку. Її місце — живий аналізатор і борг п.4, а не корекція смуг.
   - 🔴 *А ось справжня вада, яку я через це проґавив*: `snrDb` рахується для кожної з 16 смуг — і **не використовується ніде**, окрім друку у звіті (2832). Смуга, зміряна з відношенням сигнал/шум 3 дБ, отримує від `synthesizeAutoEq16` таку саму впевнену корекцію, як смуга з 40 дБ. Тобто там, де вимір нічого не знає, еквалайзер однаково крутить на повну — і робить це, зазвичай, на самому низу, де шум салону найгучніший, а корекції найбільші.
   - ✅ **ЗРОБЛЕНО 12.09.2026 того ж вечора** (дозвіл власника на зміну цифр: «мені повністю насрати на готові пресети… мені потрібна робота»).
   - *Як саме*: `snr16` проведено крізь усі шість місць — `sweep.h` (оголошення + інлайн-обгортка Harman), `sweep.cpp` (означення), `wdsp_jni.cpp` (обидва входи JNI), `NativeSweep.java` (обидві обгортки + обидві нативні декларації) і єдиний викликач `RoomMeasurement:2237`. У `sweep.cpp` вага множить `deltaDb` **до** правил кросовера й обрізання: `confidence = (snr − 6) / (18 − 6)`, зажата в 0…1. Тобто нижче **6 дБ** SNR смуга не коригується взагалі, від **18 дБ** — коригується на повну, між ними згасає **пандусом, а не порогом**: жодна смуга не перескакує між «виправлено повністю» та «проігноровано» через один децибел шуму. `nullptr`/`null` зберігає старе поведінку (довіряти всім смугам однаково) — це шлях калібрування мікрофона, який до синтезу не доходить.
   - *Звідки беруться числа*: `avgSnr` усереднюється по тих самих каналах і так само, як `avgClean` (арифметичне середнє дБ по впевнених каналах), і кладеться в нове поле `Result.avgSnrDb16`.
   - 📄 *Що тепер видно у звіті*: два нові рядки — `Avg SNR dB:` (скільки кожна смуга мала над власним шумом) і `EQ trust (0..1):` (у що з цього повірив синтез). Раніше причину дивної корекції в басі не було з чого вивести; тепер вона стоїть поруч із самою корекцією.
   - ⚠️ *Чого ще не зміряно*: вплив на реальні цифри пресета. Свіп — це звук, а дозвіл власника на звук був до 22:00; правку зібрано (EXIT=0, натив під обидві ABI), встановлено, застосунок підіймається. **Перший свіп після цього треба зняти й порівняти `Avg SNR dB` з `EQ trust` і з підсиленнями.**

---

### 5. 📐 Геометрії екранів: де перевірено, а де ні

* **✅ Перевірено на реальному залізі (`192.168.1.146:9876`)**:
  - 1280×720, Landscape, 160 dpi (типова 9"/10" консоль UIS7862, платформа QF01). Усі елементи, піли, відступи та відгук тачскріна вивірені до пікселя.
* **✅ Перевірено в емуляції та статичному аналізі**:
  - 1024×600 (базовий 7" екран): відступи стискаються, піла пресетів іде в горизонтальний скрол, зберігаючи розмір слайдерів EQ.
  - 1920×720 (ультраширокі панелі): піла пресетів плавно розтягується без порушення геометрії кнопок.
* **❓ НЕ перевірено на живому залізі**:
  - Вертикальні Tesla-style панелі (наприклад, 768×1024 або 1200×1920). На платформі QF вони працюють із `hwrotation=90` та віддають розмітку близько ~600×440dp.
  - *Рішення*: Для точної діагностики таких екранів у розділ «Діагностика» введено інструмент **«Зняти розмітку екрана» (Screen Topology)**, який вивантажує системні метрики вікна для аналізу розробниками.

---

### 6. 🩹 Помилки, яких припустилися і які виправили (Погляд з боку Gemini)

1. **Витік пам'яті в `ThemedDialog` (Retention-пастка `WeakHashMap`)**:
   - *Як виникла*: Лямбда `rebinder` у реєстраторі тем захоплювала посилання на `View` діалогу, а View через внутрішній `Window.Callback` тримала сам `Dialog`. У результаті ключ тримав значення, і запис ніколи не збирався GC.
   - *Чому сталася*: Довіра до автоматичного механізму очищення `WeakHashMap` без урахування прихованого зворотного посилання всередині Android UI Framework.
   - *Як вилікувано*: Додано `setOnDismissListener`, який примусово видаляє діалог з мапи в момент його закриття користувачем.
2. **Наскрізний подвійний виклик у `NowPlaying`**:
   - *Як виникла*: У блоці перевірки `if (isRadioSource())` після відправки фабричного бродкасту радіо не було поставлено `return;`. Виконання продовжувалося далі у виклик `MediaController` для звичайних медіаплеєрів.
   - *Чому сталася*: Фокус на взаємодії з радіо затьмарив наявність другого плеєра в бекграунді.
   - *Як вилікувано*: Додано явні термінальні `return;` після кожної радіо-команди.
3. **Плутанина з фабричним інтентом закриття радіо**:
   - *Як виникла*: Спроба використати `/customize/radio/close` замість відсутнього `play_pause`.
   - *Чому сталася*: Бажання знайти готову команду в списку замість визнання факту, що штатне перемикання паузи радіо має йти через `MediaSessionCompat` (`TransportControls.pause()`).
   - *Як вилікувано*: Транспорт паузи/відтворення повністю переведений на єдиний стандартний Android `MediaController`.
4. **Обрізка сурогатної пари UTF-16 замочка `🔒`**:
   - *Як виникла*: Використання наївного `substring(1)` для очищення іконки замочка з рядка акордеона.
   - *Чому сталася*: Звичка сприймати символи Java як 1 char = 1 гліф, забуваючи про сурогатні пари для емодзі вище площини BMP (U+10000+).
   - *Як вилікувано*: Перехід на безпечну роботу з повним рядком без розрізання пар кодових точок.
5. **`android:ellipsize="marquee"` на полі випадайки поклав застосунок на старті** (12.09.2026, Claude):
   - *Як виникла*: власник попросив плавну прокрутку довгої назви пресета замість трьох точок. Поле пресетів — `AutoCompleteTextView` у Material `ExposedDropdownMenu`, і я поставив штатний marquee у розмітку, розраховуючи перевірити рух на апараті.
   - *Що сталося*: `java.lang.IllegalArgumentException: EditText cannot use the ellipsize mode TextUtils.TruncateAt.MARQUEE` з `EditText.setEllipsize()` — **кидається з конструктора**, тобто на інфляції `activity_main` (рядок 155) у `MainActivity.onCreate`. Застосунок падав у циклі, до першого рядка нашого коду: у логах не було ні наших тегів, ні валідатора, ні tombstone. Діагноз дав `logcat -b events`: `am_crash` → `am_finish_activity … force-crash` → `am_proc_died`.
   - *Чому сталася*: я перевіряв гіпотезу про marquee **установкою на апарат**, а не локально. Ціна помилки тут не «не поїде прокрутка», а «не запуститься програма», бо заборона зашита в конструктор, а не в поведінку.
   - *Як вилікувано*: `ui/views/TextScroller.java` — горизонтальна прокрутка плюс покадровий крок (55 px/с, як у marquee статусбара), із затримкою на кожному кінці; переповнення перемірюється щоцикл, бо назва змінюється разом із пресетом. У розмітці лишились лише `singleLine` + `scrollHorizontally`, і поруч стоїть попередження не повертати `marquee`.
   - ⚠️ **Правило**: атрибут розмітки, якого я досі не бачив у цьому проєкті, перевіряти на збірці й на одному запуску **до** того, як він стане частиною правки, — деякі з них кидають, а не деградують.
6. **Діагностичний бродкаст `MEASURE_ROOM` міряв вигадану машину** (12.09.2026, Claude; знайдено на нагадування власника про єдине джерело правди):
   - *Як виникла*: у `RoomMeasurement` жила драбина з шести перевантажень `measureAsync`. Коротші з них дописували бракуючі аргументи **літералами**: `false` (немає сабвуфера), `SoundstageMode.DRIVER`, `TargetCurve.HARMAN`. `McuService:534` (обробник `com.radiorubka.wdsp.MEASURE_ROOM`) заходив саме в таке коротке перевантаження.
   - *Що це означало на практиці*: та сама кнопка «виміряти» давала **різний результат залежно від дверей**. З екрана налаштувань вимір ішов по справжній конфігурації (майстер писав вибір у префи й передавав його ж аргументами). З бродкаста — по вигаданій: машина без сабвуфера, сцена водія, крива Harman, хоч би що власник обрав. Усі свіпи, які я ганяв бродкастом у попередніх сесіях, рахувалися саме так.
   - *Чому це та сама хвороба*: один факт (яка це машина) мав **три джерела** — параметр виклику, преференс і літерал у перевантаженні. Місток між ними ніхто не тримав, і розійтися вони могли мовчки.
   - *Як вилікувано*: чотири перевантаження, здатні вигадати відповідь, **видалено**. Лишилась одна коротка форма, і вона питає: `hasSubwoofer(context)`, `getSoundstageMode(context)`, `getTargetCurve(context)`, `getBodyType(context)`, `getListeningDistanceCm(context)`. Майстер, як і раніше, зберігає вибір у префи перед стартом — отже обидві двері тепер бачать одну машину. У `calibrateMicAsync` три літерали лишились, але тепер поруч написано, **чому** вони там свідомі: калібрувальний прохід закінчується на кривій капсуля і ніколи не доходить до `synthesizeAutoEq16`, а `false` — це те, що тримає свіп на чотирьох основних динаміках замість п'яти.
   - *Правило, на яке це спирається (наказ власника 12.09.2026)*: «джерелом правди має бути завжди одна функція чи клас, островна топологія і купа крихких містків — небезпечна». Відновлювати правило **видаленням зайвих доріг**, а не додаванням ще одного шару, що їх узгоджує.
7. **Вимір салону стартував без калібрування мікрофона і нічого про це не казав** (12.09.2026, Claude):
   - *Суть*: `startRoomMeasurement()` перевіряв лише «чи вже йде вимір» і дозвіл на мікрофон. Калібрування не перевіряв ніхто. Без нього `getMicCompensationCurve` повертає **шістнадцять нулів**, `synthesizeAutoEq16` додає їх до заміру — і капсуль вважається ідеально рівним. Усе, що фарбує сам мікрофон, приписується машині й виправляється динаміками. У звіті про це не було ні слова.
   - *Як вилікувано, і де саме*: діалог перед стартом — це лише ввічливість, його можна пропустити («виміряти без нього» лишилось як третій вихід: це діагноз, а не замок). Справжня правка в іншому місці: **сам прогін** тепер записує `result.micCalibrated`, питаючи ту саму єдину функцію `hasMicCompensation` рівно там, де вантажить криву (`RoomMeasurement:1764`), а звіт друкує рядок `Mic calibrated:`. Тобто факт народжується біля даних, а не в екрані, який випадково запустив вимір, і переживе будь-який новий вхід у майстер.

8. 🔴🔴 **СВІП МІРЯВ КОРЕКЦІЮ, А НЕ САЛОН: службовий флат-пресет ніколи не доходив до чіпа** (13.09.2026, знайдено на питання власника «свіпити ж треба у повному флат режимі?»):
   - *Задум, який описаний у коді*: `RoomMeasurement` §«The preset a measurement runs through» (рядки 226–238) обіцяє — вимір не йде через пресет користувача, бо там крива, затримки, тонкомпенсація, бас-буст і ФВЧ; він копіює пресет у службовий `wDSP Flat`, нейтралізує все (`_g*`=6, `_q*`=false, `_loud`=false, `_fm_en`=false, `_fat_en`=false, `_fm_cal`=0, `_fm_str`=0, затримки 0, `_bb_*`/`_bf_*`=0, `_sub_g`=0, `gala_enabled`=false) і «switches to the copy».
   - ❌ *Чого не було*: **перемикання**. Рядок, що обирає копію, не існував. Доказ у трьох місцях: (1) `"wDSP Flat"` згадується в усьому дереві **рівно один раз** — у власному оголошенні `SCRATCH_PRESET`; (2) `McuService.syncPreset()` бере `last_selected_preset` і штовхає в чіп **лише його**; (3) `McuService.prefListener` реагує тільки на `key.startsWith(currentPresetName)` — а ключі копії починаються з `wDSP Flat`, тож під умову не підпадають. `PREF_LAST_SELECTED` у `RoomMeasurement` писався лише **після** виміру (рядок 2383, для нового Auto-EQ пресета).
   - *Тобто*: копія жила в преференсах, а в залізі лишався пресет користувача. Свіп грав **через його криву разом із тонкомпенсацією**: `McuService.updateEqWithFm` кладе в чіп `(cachedGains−6)*2 + fmOffsets`, а `updateFmOffsets` дає ненульові зсуви за `cachedFmEn`. У власника на момент виміру `AutoEQ Harman (Центр)_fm_en=true`, `_d_en=true`, `_d_fr=3`, `_bf_f=7`, `_bf_r=7`, `_sub_comp=true` — тобто були живі тонкомпенсація, затримка правого фронту, басові фільтри й підмішування саба.
   - ⇒ **Усі пресети, зняті до 13.09.2026, зміряні через ввімкнену корекцію.** Це стосується і «звучать ок» — вони компенсували вже скомпенсоване.
   - ✅ *Як вилікувано*: після `buildScratchPreset` копія **обирається** (`putString(PREF_LAST_SELECTED, SCRATCH_PRESET)`) і чіп примусово перезаливається `RESET_AUDIO_MCU` (він чистить кеш MCU і сам кличе `syncPreset`) — один шлях, незалежно від того, чи встиг слухач преференсів. Далі `PRESET_SETTLE_MS` = 450 мс, бо запис іде через фоновий handler і throttler EQ, і свіп, що почався б раніше, зміряв би першу частку секунди через криву користувача.
   - *Чому це безпечно*: вибір користувача вже лежав у `room_measure_recovery` (рядок 1269), а `applySaved` відновлює його звичайним `putString`; отже і `finally` (1312–1315), і `restoreIfInterrupted()` повертають пресет, а `RESET_AUDIO_MCU` його перештовхує. Нову дорогу для відкату будувати не довелось.
   - ⚠️ *Не зміряно*: доказ із дроту. Лог від свіпу власника 23:53 вже прокрутився, а новий свіп — це звук. **Перший свіп після цієї правки має дати в лозі `measuring through wDSP Flat (selected and pushed to the chip)` і `[TurboSender2000]`-рядки з нулями тонкомпенсації**, і його `Synthesized Auto-EQ` треба порівняти з тим, що в архіві 12.09 23:53 (там `0 0 0 0 +2 +2 −2 0 0 0 +2 +2 +2 +4 +4 +4` дБ).
9. **Я зняв AGC з тракту аналізатора й не поставив нічого замість — мікрофонний спектр просів відносно розрахункового** (13.09.2026, мій власний регрес того ж вечора):
   - *Як виникла*: лікуючи полицю шуму, я віддав аналізаторові **сирий** чанк (`analyzer.pushPcm16(raw, len, 1.0f)`) замість підсиленого. Домен полиці це справді вилікувало. Але я розраховував, що рівень доберe власний авторівень аналізатора — **не перевіривши, чи він увімкнений**. Він вимкнений: `mainAgcEnabled = prefs.getBoolean(PREF_AGC_MAIN_ENABLED, false)`, і свідомо — для **розрахункового** спектра рівні абсолютні, а «інструмент, що сам себе перемасштабовує, не читається». Отже я забрав до +24 дБ і нижче по тракту не було нічого.
   - *Як знайшлося*: не моєю перевіркою. Власник побачив очима: «до недавніх переробок розрахунковий і спектр мікрофона були значно ближчі між собою». Я до того навіть назвав причину вголос — і пішов робити інше, не полагодивши. Через кілька кроків він спитав: «ти казав, що забув AGC в тракті аналізатора, зараз пам'ятаєш?»
   - *Як вилікувано*: нормалізація перенесена туди, де вона нічого не псує — у **децибельний** домен аналізатора, і **лише для мікрофонного режиму**. Один рядок в одному місці, `applyNativeSettings()`: `mainAgc = radioActive || mainAgcEnabled`, `mainStrength = radioActive ? 1.0f : mainAgcStrength`. Преференс не торкається й далі керує розрахунковим режимом. Для мікрофона абсолютного рівня зберігати нічого: він залежить від того, як голосно грає машина.
   - 📊 *Зміряно на апараті 13.09 03:11*: `agcMain=true (forced: mic mode)`, верхні смуги під музику **−30…−39 дБ** проти **−42…−92 дБ** до правки — ті самі 10–15 дБ, що давав AGC захвату. Заодно виправлено діагностичний рядок: він друкував **преференс**, і я читав «agcMain=false», поки аналізатор нормалізував.
   - ⚠️ *Правило звідси*: «нижче по тракту це компенсується» — це **гіпотеза**, а не факт, поки не прочитано типове значення того, що має компенсувати.
10. **Повзунок порога шумодава: доданий і знятий того ж вечора** (13.09.2026):
   - *Як виник*: власник згадав його як можливість («можливо `SIGNAL_OVER_FLOOR` прийдеться зробити повзунком, в розумних межах»), і я зробив повний вузол — преференс, рядки, розмітку, прив'язку, 28 локалей.
   - *Чому знятий*: побачивши його на екрані, власник сказав: «повзунки ростуть як гриби після дощу. Результат має бути такий, щоб людина не чіпаючи налаштування по дефолту отримала правильний результат». І він має рацію технічно, а не лише за смаком: **поріг був важко вибрати тільки тому, що полиця вчилася на музиці**. Щойно полиця почала вчитися в паузах, одна константа 2.5 працює — що й зміряно (полиця стоїть на 150, відношення 1.37→10.43).
   - *Що лишилось прибрати*: три тепер-невживані ключі (`mic_gate_label`, `mic_gate_desc`, `format_gate_ratio`) у 28 локалях — Дж їх уже заповнив, а власник запаркував тему локалей. Невживані рядки нічого не ламають; прибрати разом із наступним заходом у локалі.
   - ⚠️ *Правило*: налаштування — це визнання, що ми не знаємо правильного значення. Спершу шукати, **чому** воно не одне; повзунок робити, коли причина справді в машині або у вусі, а не в нашій ваді.
11. **«Тилу немає» — це висновок виміру, а не питання в майстрі** (13.09.2026; я запропонував додати питання, власник відмовив: «навіщо питати? Не чує = немає»):
   - *З чого почалось*: у звіті власника обидва тилові канали стоять як `NOT HEARD`, затримки безглузді (1499.73 мс і 217.77 мс), а SNR по смугах від'ємний. Я спершу оголосив це збоєм виміру — **помилково**. У власника на стенді **тилових динаміків немає**, і він не унікальний: машин без тилу повно.
   - 🔑 *Що я зміряв, коли пішов у код*: `MIN_PEAK = 0.01f`, тобто **−40 dBFS**, а тили записали **−27.3 dBFS** — тобто поріг існування вони **пройшли** (`heardAtAll = true`). Відкинуло їх пізніше, у `computeDelays`, як **фантоми**: розбіг приходу 57 мс і 1224 мс від опорного каналу, чого салон дати не може. Отже мікрофон на тилових проходах чув **просочування передньої пари**, а не тил.
   - ⚠️ *Тому «не чує» в коді має дві різні причини* — нижче порога рівня і неможливий час приходу, — і одна й та сама мітка `NOT HEARD` стоїть на обох. Третій випадок, який **не можна** плутати з відсутністю: тил, що **є**, але тихий або чується переважно відбиттями (`ok && !confident`) — його стирати нельзя, інакше в когось зникне реальна акустика.
   - *Що в цьому вже працювало*: обидві причини ведуть у `ok == false`, а далі все послідовно виключає такий канал: середнє для синтезу бере лише `ok && confident` (`analyzeAcousticsAndSynthesize`), проєкція затримок ставить `NEGATIVE_INFINITY` і пропускає (`computeDelays`, рядок ~2065), вердикт про полярність рахує лише почуті напряму. Тилові затримки лишаються нулями, фейдер центрований.
   - ❌ *І тут я поспішив із висновком «будувати нічого не довелось».* Власник заперечив одним рядком: «це міг просто бути шум фоновий. Математика має розуміти під час свіпу що є шумом, а що віддачею динаміків». Він має рацію: `heardAtAll = recordedPeak >= MIN_PEAK` питає, **чи був запис голосним**, а не чи грав динамік. −40 dBFS шум у салоні перекриває легко; його тили дали −27.3 dBFS і **пройшли** цей бар'єр, а відпали вже випадково — як фантоми за часом приходу. Тобто існування каналу трималось на абсолютному порозі, який нічого не вимірює.
   - ✅ *Як зроблено (13.09.2026)*: існування каналу тепер визначається **відношенням сигнал/шум**, і потрібна величина вже лежала в тому самому прогоні, прочитана нікем — `subtractNoise` заповнює `ChannelResult.snrDb` (рядок 1709), а тест читає його на 1745. Медіана SNR по смугах **3–12 (80 Гц…5 кГц)** мусить бути ≥ **`MIN_SNR_PRESENT_DB` = 10 дБ**; медіана, а не середнє, щоб одна смуга на резонансі салону не вирішувала долю динаміка. `MIN_PEAK` лишився дешевим запобіжником «запис не порожній».
   - 📊 *Запас, порахований на його ж вимірі 12.09 23:53* — медіана SNR по смугах 3–12:

     | канал | медіана 3–12 | смуги 0–2 (20/31.5/50 Гц) | вердикт при порозі 10 дБ |
     |---|---|---|---|
     | rear left | **−0.10** | +7.8 +4.2 +3.9 | not present |
     | rear right | **+0.10** | −11.3 −6.5 −3.7 | not present |
     | front left | 31.25 | +43.5 +41.1 +37.1 | counted |
     | front right | 34.15 | +44.9 +46.6 +45.7 | counted |
     | subwoofer | 32.15 | +44.1 +40.8 +42.8 | counted |

     Розрив **31 дБ** (0.1 проти 31.25), тобто по десять децибелів запасу з кожного боку від порога. І це **відношення**, тож поріг не залежить від того, наскільки шумна машина.
   - 🔑 *Чому вікно саме 3–12, а не всі 16 — питання власника «чому така різниця?» між тилами*: у сирих числах тили виглядають зовсім різними — `+7.8/+4.2/+3.9` проти `−11.3/−6.5/−3.7`, — і це спокушає шукати різницю в каналах. Її там немає: **щойно прибрати три нижні смуги, обидва порожні канали збігаються до 0.2 дБ**. Розкид живе на 20–50 Гц, де смуга ~9 Гц шириною й кошиків FFT одиниці, а сигнальне вікно порожнього каналу падає у **випадкове** місце запису — «прихід» у нього це найгучніший пік шуму (у лівого 1499.73 мс, у правого 217.77 мс), тоді як шумове вікно береться у фіксованій точці (семпл 48 деконволюції). Два різні куски нестаціонарного низькочастотного шуму, порівняні між собою, і дають ±10 дБ у будь-який бік. ⇒ Виключення смуг 0–2 і медіана замість середнього — це не смак, а те, що прибирає саме цей артефакт.
   - 🔎 *Тепер це видно в лозі*: на кожен канал рядок `peak … (above/below the level bar), median SNR … over bands 3-12 (a speaker was driven | noise, not a speaker) -> counted | not present`.
   - ⚠️ *Чого цей тест НЕ робить*: не стирає тил, який **є**, але тихий або чується переважно відбиттями — такий канал має високий SNR у середині й проходить; «мостю відбиттями» лишається окремою ознакою (`ok && !confident`).
12. **Свіп 3 → 6 секунд, і чому це не те саме, що «плато»** (13.09.2026, зауваження власника: «свіп надто швидкий… динамік має встигнути перейти в плато»):
   - ⚖️ *Де я з ним не згоден і чому*: усталений режим на кожній частоті — вимога **покрокового** синусу. Тут свіп **деконволюється**, і імпульсний відгук, що з цього виходить, несе повну АЧХ незалежно від того, чи встигла якась частота вийти на плато. Тобто на трьох секундах вимір не був **хибним**.
   - ✅ *Де він має рацію, і це арифметика*: 20 Гц…20 кГц = `log2(1000)` = **9.97 октави**, отже 3 с це **301 мс на октаву**. Один період 20 Гц триває 50 мс ⇒ у найнижчій смузі свіп живе **~6 періодів**. Стільки енергії проти салонного шуму, який саме там найгучніший, і дає обидва вже зміряні симптоми: розкид **±10 дБ** у трьох нижніх смугах між двома *однаково порожніми* каналами, і криву мікрофона, що вперлася в стелю +16 дБ **тричі**.
   - *Зроблено*: `DEFAULT_SECONDS` 3 → **6**. Подвоєння тривалості подвоює енергію в кожній смузі — **+3 дБ SNR**, рівномірно, за одну лише витрату часу. П'ять каналів × 6 с + паузи 1.5 с + lead/tail ≈ **40 с** звуку. Якщо низ і далі сипатиметься — наступний крок 12 с (+6 дБ), і **тоді** текст майстра «близько півхвилини» треба міняти разом із ним (на 40 с він ще не бреше).
   - 🔴 *Друга причина розкиду внизу, окрема від швидкості свіпу*: рівні по смугах беруться з **фіксованого** вікна `kAnalysisWindow = 16384` семплів = **341 мс** навколо приходу (`sweep.cpp`), і воно **не залежить** від тривалості свіпу. На 20 Гц у 341 мс влазить ~7 періодів — тобто межу оцінки в найнижчих смугах задає саме це вікно, а не свіп. Важіль є, але наосліп його не рухано: розширення вікна втягує більше відбиттів салону і більше шуму.
   - 📐 *Ціна в нативі перевірена перед зміною*: `deconvolve` бере FFT на `nextPowerOfTwo(запис + свіп)` — при 3 с це 524 288 точок (≈8 МБ тимчасово на чотири вектори), при 6 с **1 048 576** (≈16 МБ), звільняється після кожного каналу. Аналізне вікно фіксоване, тож його ціна не росте.
   - 📊 *І ця «прийнятність» зміряна, а не оцінена* (бо оцінювати замість міряти — моя записана пастка). На апараті: `dalvik.vm.heapgrowthlimit` = **192m** (`heapsize` 512m), а wDSP у спокої тримає **13.4 МБ** Java-кучі й 37.9 МБ нативної. Шестисекундний прогін додає на Java-боці `stereo` 7.4 МБ + `captured` 3.8 МБ + `asFloat` 7.6 МБ + по каналу `window`/`impBuf` ≈1.5 МБ кожен ⇒ пік близько **36 МБ проти стелі 192**, запас у п'ять разів; у нативі 37.9 + 16 = 54 МБ, і нативна куча цією стелею не обмежена. **12 с теж влізе** (≈44 МБ Java, ≈32 МБ натив) — тобто наступний крок по тривалості пам'яттю не заблокований.
   - 🔧 *І окрема неузгодженість, знайдена тією ж арифметикою*: маршрутизація перемикалась за `gap / 2` = **750 мс** до свіпу, тоді як власна константа `ROUTING_SETTLE_MS` = **800 мс**. Два числа про одне й те саме, на 50 мс різні, і нічим не пов'язані — перші тони кожного свіпу могли ще виходити через **попередній** динамік. Тепер перемикання прив'язане до самої константи: `switchAt = lead + k*period − ROUTING_SETTLE_MS`, що при паузі 1.5 с лишає 700 мс на затухання салону після попереднього свіпу.
   - ⚠️ *Синхронізація свіпу й захвату — тривогу знято, і ось чим*: вікна нарізаються з запису за **точними семплами** (`from = k*period`), а в треку свіпи лежать за тими самими зсувами (`at = lead + k*period`) — обидва з одного `period`. Невідомий лише **сталий** зсув між вихідним і вхідним потоками; у коді описано, що його міряли (6.4 / 2.7 / 4.5 мс розкиду між **окремими** потоками), тому зроблено по одному потоку в кожен бік — тоді зсув однаковий для всіх каналів і в різницях скорочується. Плюс вікно має по секунді запасу з боків. ⇒ Для затримок (різниць) і для АЧХ (деконволюція) кадрова синхронізація не потрібна.
   - 🪤 *Мій власний хвіст, який я прибрав того ж заходу*: я почав був додавати `ChannelResult.disqualifiedReason`, щоб звіт розрізняв три стани — власник зупинив правку звіту, і поле лишилось **без читача**. Причина й так пишеться в лог в обох місцях дискваліфікації, тому поле й обидва присвоєння **відкочено**: мертве поле — це той самий острів. Замість нього в `heardAtAll` дописано, чим це питання відрізняється від «чи є тут динамік», із числами з цього виміру.

13. 🔴🔴 **КАПСУЛЬ РІВНИЙ ±0.5 дБ — отже «крива мікрофона» на +16 дБ нічого не компенсує, а ріже бас** (13.09.2026, межу дав власник, Дж підтверджує даташитами, його документ «Алгоритмічне та апаратне калібрування мікрофона та акустики без еталона» стверджує те саме):
   - *Факт, від якого все перевертається*: сучасний капсуль, навіть дешевий, рівний у межах **±0.5 дБ** у своїй смузі. Тоді `+16.0 +16.0 +16.0` на 20/31.5/50 Гц у кривій власника — це **не капсуль**. Це сума трьох різних речей: **шлях** (отвір 1.5–2 мм, порожнина, і головне — ФВЧ у вхідному тракті: Дж рахує 1 мкФ на 2.2 кОм ≈ 72 Гц першого порядку, плюс цифровий другого порядку 100–150 Гц проти вітру), **капсуль** (рівний), і **машина** (справжній басовий недобір дверей, який ми якраз і хочемо коригувати).
   - 🔴 *І ось причинний ланцюг, через який це не академічне питання*. У `sweep.cpp`: `m[b] = avgClean16[b] + micComp16[b]`, далі `deltaDb = target − (m[b] − refMid)`, а `refMid` береться зі смуг 5–8, де `micComp` нульовий. Отже фальшивий **плюс** унизу робить відгук у математиці **голоснішим**, ніж він був, різниця до цілі падає — і синтез **ріже бас**, тим сильніше, чим більше ми «компенсували». ⇒ **Ми піднімаємо те, чого мікрофон не чув, і платимо зрізаним реальним басом.** Стеля +16 дБ, у яку впирається оцінка, тут не запобіжник, а множник шкоди.
   - *Що з цього випливає для методу*: «оцінювати криву капсуля зі свіпу в салоні» — хибна постановка задачі. Капсуль оцінювати не потрібно (він рівний); моделювати треба **шлях за конструктивом**, а смуги нижче ФВЧ мікрофона оголошувати **незміряними** (Flat, 0 дБ) — бо там немає сигналу, який можна підняти, і будь-яке число, взяте звідти, є вигадкою.
   - ✅ *ЗРОБЛЕНО того ж вечора, після відповіді лінії Дж (#515)*. Його числа я перевірив своєю арифметикою, перш ніж на них спиратися: `1/(2π·2200·1e-6)` = **72.3 Гц** (1.0 мкФ) і **154 Гц** (0.47 мкФ) — вірно; асимптота ФВЧ 2-го порядку від 125 Гц до 20 Гц `12·log2(125/20)` = **31.7 дБ** — вірно. Джерела на капсулі: Knowles SPH0645LM4H, ST MP34DT01, InvenSense INMP441 (±0.5 дБ у 100 Гц…6 кГц), Panasonic WM-61A, Primo EM272, CUI CMA-4544 (±0.5 дБ у 40 Гц…4 кГц), вентиляційний отвір статичного тиску −3 дБ на 25…35 Гц.
   - *Три правки, і в жодній немає числа, якого не можу захистити*:
     1. `estimateMicCompensation` більше **не виробляє нічого** для смуг 0–2 (20/31.5/50 Гц). Це **відмова гадати**: нижче вхідного ФВЧ сигналу немає, і будь-яке число звідти — підсилений тепловий шум входу.
     2. Смуги 3–4 (80, 125 Гц) міряються далі, але обмежені новою `kPathMaxLowDb = 8 дБ`, і ця вісімка **виведена**, а не вибрана: найгірший правдоподібний вхідний фільтр (0.47 мкФ на 2.2 кОм, fc = 154 Гц) давить 80 Гц на **6.7 дБ** і 125 Гц на **4.0 дБ**. Більше за це — уже машина, і знімати це з машини не можна.
     3. У `synthesizeAutoEq16` смуги ≤ 50 Гц тепер тримаються в нулі **беззастережно**. Дірка, яку це закриває: досі вони пінилися лише за `hasSub`, а правило `!hasSub` зупинялось на 40 Гц — тобто **машина без сабвуфера могла отримати підйом на 31.5 і 50 Гц, порахований із шуму вхідного каскаду**.
   - ⛔ *Чого я НЕ взяв із відповіді Дж, і це важливо для наступної сесії*: його **таблицю компенсації шляху по конструктивах** (pinhole −5.0 дБ на 3.15 кГц, +10.0 дБ на 20 кГц тощо). Вона фізично переконлива, але це **модель**, а не наш вимір на цьому залізі; вписати її означало б поміняти одну стелю з голови (наші +16) на іншу. Умова, за якої її можна брати: два калібрування — капсуль у дірці й той самий капсуль поза нею, тоді різниця **зміряна**, а не постульована.
   - 🩹 *Милиця, що лишилась свідомо*: пік Гельмгольца в отворі досі лікується **після** синтезу — смугам 11–12 додають два кроки підсилення в `analyzeAcousticsAndSynthesize`. Дж правий, що це треба перенести в криву; але замінити милицю можна лише зміряним, тому вона стоїть далі, з коментарем, що вона милиця.
   - ✅ *І відповідь на пряме питання власника «до свіпів і калібровки повний флат застосовується?»* — **до обох**. `buildScratchPreset` + вибір `wDSP Flat` + `RESET_AUDIO_MCU` + осідання стоять на рядках 1364–1384, тобто **до** `try` і **до** розгалуження `if (!isMicCalibrationOnly)` на 1393; `runOnePass(..., SCRATCH_PRESET, ...)` отримує службовий пресет в обох випадках, а прапорець вирішує лише те, що робиться після запису. Рання втеча «свіп не збудувався» (1387–1389) лежить усередині того самого `try`, чий `finally` повертає пресет користувача.
   - 🪤 *Заодно знято моє власне звинувачення*: я вирішив, що звіт друкує місце мікрофона з другого, зсунутого списку (`englishPlace` проти `MIC_PLACES`), бо у звіті від 23:53 стояло «built-in head unit mic», а в префах — `room_mic_place=7` («Торпедо»). Прочитав switch: **зсуву немає**, 7 = dashboard, 8 = head unit, список збігається з масивом. Розбіжність часова — звіт написаний до того, як власник сам перебирав ці опції, шукаючи, що вибрати. ⇒ Дві копії списку тут є (масив ресурсів і англійський switch для звіту), але вони **узгоджені**; це борг на майбутнє, а не поточна вада.
   - ⚠️ *Окрема справжня вада в UI, яку знайшов власник*: у налаштуваннях мікрофона «вбудований у магнітолу» і «за отвором у панелі» питаються **двічі** — перший раз у списку МІСЦЯ (`room_mic_place_headunit` = «Вбудований мікрофон магнітоли (отвір на панелі)»), другий у списку КОНСТРУКТИВУ (`room_mic_body_pinhole` = «За малим отвором у панелі»). Місце вже несе конструктив у назві, і людина не знає, що вибирати: власник фізично має вбудований у морду магнітоли за дірочкою, а в місці обрав «Торпедо». Розділяти треба так, щоб МІСЦЕ означало лише координату й висоту, а КОНСТРУКТИВ — лише те, що навколо капсуля; питання передано Дж разом із фізикою.

### 4-sexies. 🎯 ЦІЛЬ: документ власника «Спільне сліпе калібрування некаліброваного мікрофона та акустики автомобіля… на базі Unisoc UIS7862»

Наказ власника 13.09.2026: «наше ТУ-ДУ має впритул наблизитися до цього документу». PDF у власника
(`D:\Downloads\…pdf`, 15 сторінок); текст витягнуто `pypdf` у scratchpad, бо локальний рендерер PDF
відсутній (немає poppler) і Google віддає документ лише авторизованому клієнту.

**Що документ прописує і що в нас є — по етапах його ж конвеєра:**

| Етап документа | Що прописано | Що маємо |
|---|---|---|
| 1.1 Скидання DSP | PEQ, затримки, кросовери в нуль (bypass) **перед** виміром, через I2C | ✅ **зроблено 13.09** — службовий пресет обирається і штовхається в чіп (§6 п.8) |
| 1.2 Запис | `VOICE_RECOGNITION` **або** `UNPROCESSED`, 48 кГц / 16 біт / моно, системні AGC і AEC вимкнені | ✅ є, **із поправкою на це залізо**: `openMicrophone()` бере `UNPROCESSED` першим, `VOICE_RECOGNITION` лише як відступ, і про відступ пише в лог |
| 1.3 Сигнал | ESS лог-свіп **20 Гц → 22 кГц**, послідовно через FL, FR, RL, RR | ≈ маємо 20 Гц → 20 кГц (7 кГц, якщо мікрофон затиснуто на 16 кГц) |
| 2.1 Деконволюція | FFT, поділ на спектр свіпу або згортка з інверсним | ✅ рівно так |
| 2.2 **Вікно** | **MLE-оцінка RT60 і динамічне обрізання хвоста; лишається ~30–50 мс** прямого звуку й ранніх відбиттів | ❌ **у нас фіксовані `kAnalysisWindow` = 16384 = 341 мс** — у 7–11 разів довше, ніж вимагає документ |
| 2.3 Затримки | GCC-PHAT → TDOA → параметри Delay | ✅ є |
| 3.1 Усереднення | логарифмічне усереднення спектрів **усіх каналів** для згладжування мод | ✅ середнє дБ по впевнених каналах (у калібрувальному проході — по потужності) |
| 3.2 Компенсація мікрофона | градієнт на **20–80 Гц** проти теоретичних **+12 дБ/окт**; недобір = ФВЧ мікрофона; гострі високодобротні піки **вище 10 кГц** = резонанси капсуля, зрізати | ⚠️ **тут документ і наш висновок 13.09 розходяться** — див. нижче |
| 3.3 Синтез | найменші квадрати → IIR-біквади, **віддавати перевагу зрізанню піків** і **ігнорувати глибокі провали** гребінки, щоб не перевантажити підсилювач | ≈ синтез на 16 апаратних смуг із обрізанням (+3 / −6, −3 вище 1 кГц); не МНК, провали гребінки окремо не ігноруються |
| 3.4 Застосування | запис коефіцієнтів у регістри BU32107 | ✅ через команди MCU |

✅ **Один рядок, де загальна порада документа вимагає поправки на конкретне залізо — і поправку ми зміряли.**
⚠️ Тут важливо не перекрутити: **документ — це дослідження без привʼязки до конкретної магнітоли**
(слова власника 13.09), тому «ми попереду нього» було б підміною. Він правий загально; ми міряли одну
машину. Документ (1.2, і в «Висновках» ще раз) радить `AudioSource.VOICE_RECOGNITION`, спираючись на
Android CDD: мовляв, пристрій **зобов'язаний** вимикати AGC і NS для цього джерела. **На цьому залізі
обіцянка не виконується** — і саме такі місцеві поправки й є тим, що ми можемо додати до загального
методу, маючи під рукою апарат. У коментарі до `openMicrophone()` лежить вимір `dumpsys media.audio_flinger` під
час захвату:
```
VOICE_RECOGNITION   Noise Suppression   State 003 (ACTIVE)   Enabled=y
UNPROCESSED         AEC + NS            State 000 (INIT)     Enabled=n
```
Тому код бере `UNPROCESSED` **першим**, а `VOICE_RECOGNITION` лише як відступ, і пише про відступ у
лог — бо вимір, знятий через нього, треба читати з цією поправкою. ⇒ Якби ми пішли за документом
буквально, шумозаглушення жувало б наш свіп-тон: воно адаптується до стаціонарного вмісту, а свіп для
нього і є стаціонарний вміст.

🪤 **І пастка звідти ж, яка цієї ночі двічі збила мене з діагнозу**: знімати ефекти зі свого боку
**не допомагає**. `NoiseSuppressor.create(session)` віддає **наш власний** хендл; вимикання його
лишає той, що причепила політика, працювати далі. Тому в лозі одночасно правдиві «NS was off, now
off» (наш об'єкт) і `State 003 (ACTIVE)` (політикин). І рядок звіту `capture effects: AEC=true
NS=true` — це `isAvailable()`, тобто **наявність**, а не увімкненість.

🔴 **РОЗБІЖНІСТЬ, яку треба вирішити свідомо, а не замовчати.** Документ (3.2) каже: порівняти нахил
20–80 Гц із теоретичними **+12 дБ/окт** «cabin gain», і різницю **скомпенсувати** як ФВЧ мікрофона.
Саме цю логіку я 13.09 **прибрав** (там стояло +6 дБ/окт), а лінія Дж обґрунтувала, що нижче ФВЧ
відновлювати нічого — сигнал під шумом АЦП. Обидва твердження сумісні, якщо розрізняти дві дії:
**ідентифікувати** ФВЧ за нахилом — можна і корисно (це діагностика); **відновити** смуги, які він
зрізав, — неможливо. Документ говорить про перше, ми забороняємо друге. Наступний крок: рахувати
нахил 20–80 Гц і **писати у звіт** оцінку частоти зрізу тракту, не піднімаючи нічого.

🔑 **Відповідь документа на питання, яке я вважав нерозв'язним одним виміром.** Я казав Дж, що
відокремити резонанс отвору від салону можна лише двома калібруваннями. Документ дає інший шлях
(стор. 3): **акустичний нуль від відбиття надзвичайно чутливий до переміщення мікрофона на кілька
сантиметрів, а резонанс капсуля стоїть на місці незалежно від положення.** Тобто розділення дає
**просторове усереднення по кількох положеннях мікрофона**. ⚠️ Для вбудованого в панель мікрофона це
недосяжно — його не посунути, — і це чесна межа методу для нашого головного випадку.

📐 **Таблиця гребінки з документа, придатна як діагностика** (різниця шляху → перший провал):
2″ (5.08 см) → 3375 Гц; 6″ (15.24 см) → 1125 Гц; 12″ (30.48 см) → 563 Гц; 18″ (45.72 см) → 375 Гц;
далі провали на непарних гармоніках. Це дає спосіб **назвати** походження провалу у звіті.

### 4-nonies. 🔑 ПОРЯДОК ДІЙ ДЛЯ ПЕРШОГО ПРОГОНУ ПІСЛЯ НОЧІ 13.09

**1. Спершу КАЛІБРУВАННЯ МІКРОФОНА. Тільки потім свіп салону.**
Вимір салону не рахує криву капсуля — він **завантажує збережену**. У власника збережена стара:
`16.00, 16.00, 16.00, 13.60, 15.29`, знята до того, як ми довели, що нижче вхідного ФВЧ відновлювати
нічого. Смуги 0–2 з неї тепер відкидаються при використанні (`4864884`), але **решта кривої
підганялась під те саме хибне припущення**. Свіп без перекалібрування дасть напівправду, і
виглядатиме, ніби правки не спрацювали.

**2. Що має з'явитися в лозі першого прогону** (`adb logcat -s wDSP_RoomMeasure:I wDSP_McuService:D`):
- `measuring through wDSP Flat (selected and pushed to the chip)` — доказ, що свіп нарешті йде у флат;
- на кожен канал: `peak … median SNR … (a speaker was driven | noise, not a speaker) -> counted | not present`;
- `[TurboSender2000] Pref Changed: wDSP Flat_f_lr` на кожен перехід каналу — це тест із §4-octies;
- `cabin response for the spectrum: N of 16 bands taken from the subwoofer (crossover … Hz)`;
- якщо крива стара — гучне попередження `stored mic curve has 16.0/16.0/16.0 dB at 20/31.5/50 Hz`.

**3. Що порівняти у звіті** з архівом 12.09 23:53:
- `Mic compensation dB:` — нижні три смуги мають бути нулями, 8 кГц більше не нуль;
- `Synthesized Auto-EQ … dB:` проти старих `0 0 0 0 +2 +2 −2 0 0 0 +2 +2 +2 +4 +4 +4`;
- нові рядки `Cabin response dB:` і `LF slope 20-80 Hz:`;
- `Avg SNR` / `EQ trust` — чи спрацював пандус довіри цього разу.

**4. Чого в цьому прогоні НЕ чекати:** зміни картинки розрахункового спектра **до** завершення свіпу.
Відгук салону пишеться в кінці виміру й тоді ж штовхається в аналізатор; доти там нулі.

### 4-octies. ❓🔴 ВІДКРИТА СУПЕРЕЧНІСТЬ: як маршрутизація каналів досягала чіпа до 13.09?

Це не здогад і не борг — це **розбіжність між моїм розбором і вашими ж даними**, яку я не зміг
закрити читанням коду. Лишаю з вирішальним тестом, бо перший же свіп її розв'яже.

**Що встановлено твердо:**
- `applyRouting()` перемикає канали, записуючи `<preset>_f_lr`, `_f_fr`, `_sub_g`, `_bf_f`, `_bf_r`,
  де `preset` = `SCRATCH_PRESET` = `"wDSP Flat"`.
- `McuService.prefListener` штовхає щось у чіп **лише** за умови `key.startsWith(currentPresetName)`.
- Рядок, що обирає службовий пресет, **до 13.09 не існував**: `git log -S "PREF_LAST_SELECTED,
  SCRATCH_PRESET"` знаходить його тільки в `7110bb9`, тобто в моїй правці.
- ⇒ За цією логікою записи `wDSP Flat_*` мали б бути **мертвими**, і фейдер під час свіпу не рухався б.

**Що цьому суперечить — вимір власника 12.09 23:53:**
- `rear left` і `rear right` дали пік **−27.3 dBFS обидва, до десятої долі**;
- `front left` −8.3, `front right` −2.8, `subwoofer` −2.9;
- відгуки по смугах у фронтів різняться на 5…9 дБ.

Стимул у треку **однаковий** для всіх каналів (моно, продубльоване в обидва канали, однакова
амплітуда). Ізоляція каналу робиться **виключно** фейдером у DSP. Отже якби фейдер не рухався, усі
п'ять вікон описували б одну й ту саму акустичну сцену й дали б однакові піки. Вони не однакові, і
картина фізично осмислена: тили (динаміків немає) — тихе просочування, фронти й саб — гучно.
⇒ **Маршрутизація працювала.** Значить моя модель того, як преференси доходять до чіпа, неповна.

**Чого я НЕ знайшов** (перевірено): іншого шляху доставки немає — ні бродкаста з `applyRouting`, ні
періодичного перечитування; `applyCurrentSettings()` читає `cachedGains`, завантажені з
`currentPresetName`; прапорець `sameRouting` типово `false`.

🔬 **Вирішальний тест — перший же свіп після 13.09.** У лозі має бути на кожен перехід каналу:
`[TurboSender2000] Pref Changed: wDSP Flat_f_lr` (рівень `D`, тобто `adb logcat -s wDSP_McuService:D`).
- Якщо рядки є — слухач спрацьовує **тепер**, бо службовий пресет став вибраним; питання «як воно
  працювало раніше» лишається, але практичного значення більше не має.
- Якщо рядків немає, а канали все одно розділяються — значить існує шлях доставки, якого я не знайшов,
  і його треба знайти **до** будь-яких подальших висновків про вимір.

⚠️ **І поправка до мого ж коміту `7110bb9`.** Я написав, що свіп ішов «через його криву, затримки,
бас-буст і тонкомпенсацію». Крива, затримки й басові фільтри — так. **Тонкомпенсація — ні**, і це
арифметика: `updateFmOffsets(vol)` дає ненульові зсуви лише за `vol < cachedFmCal − 1`, а у власника
`_fm_cal = 16` при гучності виміру рівно **16**. Тобто саме на цьому вимірі Мансон не додав нічого.
Механізм лишається вадою (за іншої калібрувальної точки або гучності він би втрутився), але на цьому
конкретному звіті його впливу не було, і казати інакше — перебільшення.

### 4-septies. 🔴🔴 ДВА ПУНКТИ ПЛАНУ ВІД ВЛАСНИКА (13.09.2026, перед сном)

**1. Розрахунковий спектр мусить враховувати віддачу акустики — інакше він показує неправду.**
Дослівно: «після обміру салону, створення автопресету — ми маємо враховувати віддачу акустики в
розрахунковому спектрі. Ось це номер, зовсім випало з голови, бо інакше він буде показувати неправду».

- *Чому це правда*: у режимі `SPECTRUM_MODE_CALC` аналізатор бере PCM **до** DSP і накладає
  `getDspCurve()` — змодельовану відповідь апаратного процесора. Тобто показує сигнал **на виході
  DSP**. Що з ним роблять динаміки й салон — не враховано ніяк. Там, де в машині провал на 80 Гц,
  стовпчик стоїть рівно, бо DSP там нічого не робить.
- *Чому це зараз можна зробити*: відсутня ланка **вже зміряна**. Вимір салону дає `avgClean16` —
  відгук машини, уже з відніманням шуму і з компенсацією мікрофона. Він живе в `Result` і у звіті, і
  більше ніде.
- *Що зробити*: зберігати відгук салону поруч із кривою мікрофона (як `pref_mic_compensation`),
  **відносно середини** (не абсолютний рівень), і додавати його в розрахунковий шлях. Тоді картинка
  стає «що доходить до вуха» замість «що вийшло з підсилювача».
- ⚠️ *Чого НЕ переплутати*: якщо активний авто-пресет, крива DSP **уже містить** корекцію, яка цей
  провал компенсує. Сума «DSP + салон» тоді дасть майже рівно — і це **правильно**, бо саме так і
  звучить. Подвійного врахування тут немає: показуємо `сигнал × пресет × салон`, що вірно для
  будь-якого пресета, не лише авто.
- ⚠️ *Гейт*: як і крива мікрофона, відгук салону дійсний лише для тієї машини й лише якщо вимір
  робився. Без виміру — не накладати нічого й не вдавати, що знаємо.

**2. Найчастіший випадок у людей — мікрофон у морді магнітоли, і саме він у нас описаний двозначно.**
Дослівно: «а саме це найчастіший випадок у людей, що не мають винесеного мікрофона, і це майже мій
випадок».

- Пункт МІСЦЯ `room_mic_place_headunit` = «Вбудований мікрофон магнітоли (отвір на панелі)» вже несе
  конструктив у назві, а в списку КОНСТРУКТИВУ те саме питається вдруге (`room_mic_body_pinhole` =
  «За малим отвором у панелі»). Людина не знає, що обрати: у власника фізично мікрофон у морді
  магнітоли за дірочкою, а в списку місця він обрав «Торпедо».
- Отже це не косметика, а **найпоширеніша конфігурація**, і вона в нас заповнюється навмання.
  Місце має означати лише координату й висоту; конструктив — лише те, що навколо капсуля.
- ⚠️ *Обмеження на правку*: `MIC_PLACES` і `MIC_PLACE_HEIGHT_CM` індексуються одним числом, і вже
  зроблені виміри зберігають цей індекс. **Перейменувати безпечно, переставити порядок — ні.**

**Черга робіт, що з цього випливає (від найбільшої вигоди):**
1. **Вікно аналізу 341 мс → RT60-залежне.** Найбільший розрив із документом і водночас друга причина
   розкиду в нижніх смугах (§6 п.12). Потрібна оцінка RT60 — документ пропонує MLE по дифузному хвосту.
   ⚠️ **Але «30–50 мс», як пише документ, наосліп брати НЕ МОЖНА, і ось чому.** У 50 мс міститься
   рівно **один період 20 Гц** і два періоди 31.5 Гц — такого вікна фізично не вистачає, щоб виміряти
   нижні смуги взагалі. Скоротивши вікно до 50 мс, ми отримаємо чудову роздільність у середині й ВЧ і
   **знищимо** те, що й так найслабше. Промислова практика тут — **частотно-залежне вікно**: коротке
   вгорі, довге внизу (кілька періодів на смугу). Отже правильний крок — не «поставити 50 мс», а
   зробити вікно функцією частоти, і **зміряти**, що це дає, порівнявши з поточними 341 мс на тому
   самому записі. Документ цієї нюансировки не має, і брати його число як наказ означало б замінити
   одну стелю з голови на іншу.
2. ~~Звірити джерело запису~~ — **зроблено при читанні документа**: `UNPROCESSED` першим, і на цьому залізі це **правильніше** за пораду документа (див. вимір `audio_flinger` вище).
3. **Оцінка частоти зрізу тракту в звіт** за нахилом 20–80 Гц (діагностика, без підняття).
4. **Ігнорувати провали гребінки при синтезі** — зараз вони входять у корекцію як дефіцит.
5. Довести свіп до 22 кГц (дрібниця, але це рядок документа).
6. Далеке: методи підпросторів (Cross-Relation + SVD), де **спільні нулі всіх каналів і є
   передавальною функцією мікрофона** — документ прямо пише, що 8-ядерного UIS7862 для SVD і FFT
   достатньо, і що вузьке місце не в обчисленнях, а в Android Audio.

### 4-quinquies. 🧪 `tools/check_locales.py` — перевірка локалей перед тим, як збірка комусь піде

**Чому з'явилась.** Збірка, роздана тестерам (vCode 24, 0.4.9.7), падала російською:
`UnknownFormatConversionException` на голому `%1` там, де Java хоче `%1$d` — сім рядків у двох
локалях. aapt2 таке приймає, компілятор не бачить, ламається лише на місці виклику. Поруч
виявився тихіший брат тієї ж хвороби: рядок **без** підстановки, якому місце виклику передає
аргумент, — не падає, просто губить значення.

**Що робить.** Порівнює специфікатори кожної локалі з англійським еталоном (кількість і тип, без
огляду на порядок), ловить `%` із цифрою, що вдає позиційний аргумент, ловить неекранований
апостроф, парсить XML. Повертає код виходу. Запуск: `python tools/check_locales.py` з кореня.

**Перша знахідка (12.09.2026, відкрита — передана лінії Дж):** `room_measure_done` в еталоні
`Preset \"%1$s\" created and activated!`, а в **26 локалях** — «Готово — надіслати результат?»
**без підстановки**. Місце виклику одне й аргумент передає: `SettingsActivity:1630`
`getString(R.string.room_measure_done, lastPreset)`. Отже у двадцяти шести мовах назва щойно
створеного авто-пресета **нікуди не потрапляє**, а в рядку стану висить питання. Правильно лише в
`uk`, `ru`, `ru-rUA`.

⚠️ **Про сам інструмент, і це важливіше за інструмент.** Перша версія цієї перевірки була вбудованим
рядком `python -c` через PowerShell here-string — зворотні скіски не пережили трьох шарів лапок, і
вона доповіла 34 «вади»: нібито `\n\n` немає ні в одній локалі й нібито у fr/it голі апострофи.
Друга версія, уже файлом, нарахувала 204 — зокрема **шість у самому англійському еталоні**, бо
йшла по кожному `%` окремо й читала другу половину `%%` як зламаний специфікатор. Обидва рази
врятувало те саме: інструмент оголошував зламаним **відомо справний еталон**.
⇒ **Правила, на яких тепер тримається ця перевірка:** писати її файлом, а не рядком у оболонці;
завжди тримати у вибірці відомо справний еталон; і мати самотест, що показує інструментові кожну
відому ваду й вимагає, щоб він заговорив (`scratchpad/selftest_check_locales.py`, 9 перевірок:
голий `%1`, `%%`, загублена підстановка, зміна типу, зміна точності, обидва апострофи,
переставлений порядок аргументів — останній **не** повинен вважатись вадою).

### 4-bis. 🅿️ Рядок еквалайзера: що ще не доведено (запарковано власником 12.09.2026)

Наказ: «це на потім, коли основні баги прибереш». Зроблено й зміряно на 1280×720: п'ять кнопок
рядка однакові 68×38 із зазорами 6dp, спінер частоти фіксовані 124dp замість ~260 (ваги більше не
має), повзунок сабвуфера 13dp радіуса замість 7 (на 14dp його не можна було спіймати на ходу),
підписи взяли природну ширину замість розтягнутої вагою.

**Лишилося зробити, коли дійдуть руки:**
1. **Слайдер сабвуфера завеликий за шириною.** Він має рівно 13 положень (`valueFrom=0`,
   `valueTo=12`, `stepSize=1`), а займає ~400dp — на позицію виходить більше тридцяти точок, чого
   ніхто не використає. Власник: бюджет звідти віддати **кнопкам і підписам**.
2. Перевірити рядок на вузьких геометріях (`800x480`, split `640`) — базові значення
   (`eq_action_button` 48dp, `eq_caption_max` 96dp) там ще не міряні; широку панель закриває
   `values-w1000dp/dimens_eq.xml`.
3. ⚠️ Пам'ятати при обмірі: `uiautomator dump` **не віддає Material-слайдери взагалі** (нуль вузлів
   `slider.Slider`/`SeekBar` у дампі на 59 КБ), тож «нуль цілей під 24dp» стосується лише того, що
   дамп показує. Розмір повзунка перевіряти в `dimens`, а не прогоном.

### 4-ter. 🔧 Що виправлено в коді 12.09.2026 (і чим доведено)

| вада | де була | доказ, що полагоджено |
|---|---|---|
| **Зміна пресета міняла ГАЛА при увімкненому «Глобально»** | глобальним був лише стан увімк/вимк; п'ять параметрів далі жили в пресеті — `MainActivity.savePreset/loadPreset`, `McuService.loadPresetData` | на апараті: пресет перемкнувся на `AutoEQ Harman (Центр)` з власними 5/13/12/500/500, глобальні лишились 15/0/12/100/1000 |
| **Міграція могла тихо змінити ГАЛА власникові** | нові глобальні ключі засівались би типовими значеннями | `PresetsDatabaseValidator` бере їх із `last_selected_preset`; у лозі: `seeded from the selected preset "AutoEQ Dolby Atmos (Центр)": increment=15 standstill=0 ceiling=12 fade=100 hold=1000` |
| **Крива й апаратна Loudness гасили одна одну** | два взаємні `setChecked(false)` у `setupFmControls` | після тапу обидва `checked=true`; у префах `_fm_en=true` і `_loud=true` одночасно |
| **Довга назва пресета губила мітки в трьох крапках** | `ellipsize=end` на полі випадайки | `ui/views/TextScroller.java`; власник підтвердив рух очима. ⛔ `ellipsize="marquee"` тут **кидає з конструктора EditText** — див. §6.5 |
| **Невдача зонда мікрофона видавала себе за успіх** | `measureBandwidth()` віддавав `0f` на будь-якій помилці, а `0f >= −30 dB` → «microphone was already ours»; далі `RoomMeasurement` брав повні 20 кГц, а тестер отримував звіт «мікрофон вільний» | тепер `NaN` = «невідомо» + перевірка `NativeSweep.isAvailable()` і поріг тиші; `Outcome.unknown` і окремий рядок у лозі |
| **До 80 с мертвого захвату на потоці аудіо** | `stopAssistantsAsRoot` → 4 × `forceStopAsRoot` по `waitFor(20 с)`, і все це з потоку `RadioMicCapture` | таймаут став параметром: 20 с для навмисної дії, **2 с** для живого захвату (асистент повертається за 2 с — повільніша спроба однаково програла) |
| **Коментар брехав про саб** | `outSubGain = 0` підписано «Mute subwoofer» | за `03-SOUND-PROCESSOR.md` шкала `0…12` = `+0…+12 дБ`, нуль — чесний 0 дБ; виправлено коментар, поведінку не чіпав |

**Аудит потоків `McuService` — перевірено, чисто.** Клас-док стверджував, що все крутиться на одному
робітнику; я це не взяв на віру, а звірив кожну точку: `onReceive` загортає **весь** корпус у
`backgroundHandler.post` (459→580), `onLocationChanged` постить присвоєння швидкості (2082),
ініціалізація (649), слухач префів (390), `SETTINGS_RESTORED`, `onConfigurationChanged` (2099) і
`onDestroy` (2119) — теж. Усі читачі `isGalaEnabled()` (1112, 1115, 1353, 1455, 1517) і
`loadPresetData` — на тому самому `wDSP_Worker`. ⇒ Один потік за побудовою, `volatile` не потрібен,
нова гілка `gala_global_*` гонки не додала.

**Що в математиці автопресетів перевірено й визнано правильним:** сітка смуг збігається з апаратною
(`kHwCenters`), правило кросовера виконано точно як у `03-SOUND-PROCESSOR.md` §226 (смуги нижче зрізу
тримаються на 0 дБ, бо 16-смуговий EQ стоїть **до** кросовера), обмеження +3 / −6 / −3 дБ,
квантування по 2 дБ, індекси 3…8.

🔴 **Відкрите питання до власника (не чіпав):** `estimateMicCompensation` бере опору на 5 кГц
(смуга 12) і накидає до **+8 дБ** на смуги **14 і 15** (12,5 і 20 кГц), а смугу **13 (8 кГц)
пропускає**. Смуги 14–15 — рівно те, чого не чує мікрофон, затиснутий на 16 кГц: тоді «падіння»
величезне, і корекція домальовується поверх шуму, а далі йде в синтез як
`m[b] = avgClean16[b] + micComp16[b]`. Тобто замір крізь чужий мікрофон народжує пресет із вигаданим
підйомом верху. Варіанти: не застосовувати ВЧ-корекцію без підтвердженого широкого мікрофона; додати
смугу 8 кГц; або обидва. Рішення змінює вже наявні автопресети — тому за словом власника.

### 4-quater. 🎙️ Геометрія мікрофона, конструктив, саб — і що з шумом насправді (12.09.2026, вечір)

Коміт `0497a39`, версія **24 / 0.4.9.7** — це те, що власник зібрав із цього дерева й **роздав
тестерам**. Нижче стан, з якого починати наступного разу.

**Зроблено й перевірено на апараті.** Крапка з машинки нарешті доходить до геометрії: доти все, крім
підголівника, схлопувалось у початок координат, тобто відповідь власника не змінювала нічого.
З'явилась третя координата з нулем **на лінії вух** — сцену будують для голови, а не для мікрофона;
висоти місць і динаміків це 🧩 поміркована модель, не вимір. Конструктив мікрофона винесено
**окремою віссю** від місця (голковий отвір поводиться однаково на торпедо й у підголівнику).
Сабвуфер дістав власне питання — багажник / полиця / під сидінням — і не заради тембру, а заради
довжини шляху: затримка це одне з небагатьох, що ми крутимо без патчу MCU. Тридцять локалей.

**🔴 Вада, знайдена вже після роздачі.** У `values-ru` і `values-ru-rUA` сім рядків мали голі `%1`,
`%2`… замість `%1$d`/`%1$s`. Голе `%1` — не конверсія Java, `getString(id, args)` кидає
`UnknownFormatConversionException`; п'ять із семи форматуються на екрані майстра, один — на кожен
дотик кнопок відстані. **На апараті з російською локаллю майстер падав при відкритті.** У дереві
виправлено (7/7 у кожному файлі), але **у тестерів версія без цього ремонту** — рішення про
перероздачу за власником.

⚠️ Знайшла це не збірка, а окрема звірка локалей проти англійського еталона: набір ключів, кількість
і тип кожної підстановки по кожному ключу, неекрановані апострофи. aapt2 такого не бачить — файл
синтаксично бездоганний. Скрипт поки лише в скретчпаді сесії; **борг: завести його в дерево**.

**Що з'ясовано про шум** (питання власника «чи враховано в математиці»). Відповідь не «так» і не
«ні»:
- тиша салону **міряється** — перша секунда перед тоном — і до сьогодні **перезаписувалась** шумом
  із деконволюції в тому самому масиві, тобто гинула в тому ж прогоні, що її зміряв. Тепер це окреме
  поле `ambientNoiseDb16`, і у звіті обидва числа підписані чесно;
- віднімання шуму **викликається** (`subtractNoise`), але віднімає деконволюційний шум, а не тишу
  салону — свідомо, бо той у домені свіпу;
- у живому аналізаторі віднімання **теж є** і працює для мікрофона. Коментар у Java обіцяє обхід для
  акустики (`setIsAcoustic(true)`), але 🔴 **`isAcoustic_` у нативі ніде не читається** — прапорець
  мертвий, обходу не існує;
- 🔴 нативне правило навчання полиці **відрізняється від Java**: `analyzer.cpp:287` має гілку, що
  тягне полицю до миттєвого мінімуму **поза** тихими кадрами, хоча коментар двома рядками вище
  попереджає саме проти цього. Полиця занижується — і в паузі шум лізе стовпчиками, разом з AGC,
  який у тиші піднімає підсилення. Це і є те, що бачить власник;
- ⚠️ але прибирати ту гілку наосліп **не можна**: `noiseFloor_` обнуляється лише в конструкторі,
  скидання при зміні джерела немає, і саме ця гілка — єдиний спосіб полиці опуститися після гучного
  місця.

⛔ **Свідомо не зроблено, чекає власника:** правило полиці й мертвий `isAcoustic_`; перевимір шуму в
паузах із подачею в натив на льоту; ворота «калібрування мікрофона перед прогоном салону» — зараз
`startRoomMeasurement` не перевіряє `hasMicCompensation` узагалі, і прогін мовчки йде з нульовою
кривою; два рядки без підстановки (`room_measure_done`, `room_mic_cal_running`) — падіння немає,
губиться текст, але рішення про англійський оригінал не моє.

🔑 Дві дрібниці, що коштували часу і вже лежать у пам'яті: гучність тут має **три різні шкали**, і
авторитет лише `VolumeHelper.getVolume()` (там було «3 -> 3», тобто вимога власника виконана); а
рантаймний приймач служби не адресується через `-n` і не існує, поки процес мертвий після
`install -r` — спершу `pidof`, потім `am start-foreground-service`, і лише тоді широкомовне.



## 🔴 The owner's bench is NOT a cabin — no acoustic conclusion may be drawn from it (13.09.2026 14:30)

Stated by the owner after I did exactly that, twice in two messages. Written down because
every sweep we will run for the next while is run there, and the temptation repeats.

**What the bench is:** open shelving with shelves to the left, open balcony space to the
right, glass 1.35 m in front of the speakers, roughly 1 m between speaker centres. No
rear speakers. It is an asymmetric half-open space, not a sealed cabin with boundary gain.

**What that invalidates, from the 13.09 14:07 report:**

- The 18 dB spread between front left and front right at 20 Hz is the *environment* — one
  driver firing into a loaded cabinet, the other into open air off a balcony. I attributed
  it to the speakers. Withdrawn.
- `LF slope 20-80 Hz: +12.8 dB/oct` is a correct measurement of that space. Reading it as
  "second order, therefore the handsfree DSP's high-pass is in the microphone path" has no
  support. Withdrawn. The yardstick the report prints next to it (+4.4 / +5.5 dB/oct for a
  first-order RC at 72 / 154 Hz) is still worth printing; what is not allowed is concluding
  from it on this bench.

**What survives, and why:**

- The microphone hears 20 and 31.5 Hz. Front right reads SNR 61.7 / 57.6 dB there — the
  best signal in the whole report, better than 8 kHz. "The mic went deaf at the bottom" is
  about the *display*: the old stored curve lifted bands 0..2 by +16 dB and that curve is
  applied to the live microphone spectrum too, so removing the fiction dropped three bars
  by 16 dB on screen. Nothing about the sensor changed.
- A filter common to every channel cannot produce a spread *between* channels. So whatever
  the spread at 20 Hz is, it is not the microphone. That argument does not depend on the
  room at all, and it is the reason bands 0..2 of the mic curve must not be fitted from a
  room sweep.

**The conclusion that follows, and it is a negative one:** bands 20 / 31.5 / 50 Hz of the
microphone curve cannot be derived from a sweep in *any* room — bench or cabin. The
acoustic term down there is always larger than the electrical term we are trying to find,
and one sweep does not separate them. My earlier proposal to take "the common part across
channels" fails for the same reason: in an asymmetric space the common part is just the
quietest channel.

So those three bands stay at zero, and the honest act is for the report to say they are
not measured rather than to substitute a number. That is what the code does now. Do not
"improve" it without new physics — a second known transducer, a known electrical
injection point, or a measurement that does not go through the air.

**Still open and NOT room-dependent** (so the bench can answer these):

- Front right peaked at −2.9 dBFS, front left at −8.5. Clip and headroom detection landed
  13.09; whether the answer is a quieter sweep is a trade against SNR and wants a
  measurement, not a nudged constant.
- Bands 5..12 of the microphone curve are zero by construction — the midband is the
  reference for itself. Needs a decision about what to measure against.


## 🔴🔴 13.09.2026, вечір — РОЗДАЧА 0.4.9.8 / vCode 25, і розворот у математиці мікрофона

Попередня роздана версія — **0.4.9.7 / vCode 24**, коміт `0497a39`. Усе нижче — між ними.

### Головне: я двічі помилився в один бік, і власник обидва рази це зупинив

**Помилка перша — лікував не той бік рівняння.** 12.09 я видалив з `estimateMicCompensation`
очікування **cabin gain** («+6 дБ/окт нижче 80 Гц»), назвавши його вигадкою. Баг, який я ним
пояснював, був справжній: калібрування свіпило двері з **мовчазним сабом**, тож брак баса машини
записувався капсулю. Але фізику я вирізав, а причину лишив. Причину знайшов власник: *«якщо людина
вказала саб, то і саб має у флаті віддати те, що він може»*. Полагоджено 13.09 — саб бере участь у
калібруванні, і оцінка бере **максимум по каналах**, кожен канал нормований на власну середину
(середнє з чотирьох дверей і одного саба розчиняє єдине джерело, здатне дати 80 Гц, у п'ять разів).

**Помилка друга — вимагав доводити усталену фізику.** Я відхилив матрицю конструктиву з §24 як
«модель, а не наш вимір». Власник скасував: *«камера імені вченого це не фікція, це роки досліджень…
Ми з Q 2.2 один фіг не можемо зробити ідеальні зрізи… не тягнутися до ідеалу»*. Матрицю прийнято, її
статус у `ROOM_CALIBRATION.md` §24 переписано.

**Симптом обох помилок був той самий і його назвав власник:** *«я чую басів вдосталь а мікрофон каже
— немає, і полоси до 50 майже мертві»*. Смуги не виявилися мертвими — **ми їх оголосили мертвими**.

### Спростована передумова, на якій усе трималося

§24 писала: на 20–31.5 Гц сигнал «тоне нижче полиці теплового шуму АЦП, **SNR ≤ 0 дБ**». Звідси
брався безумовний нуль для смуг 0–2. **Наш власний звіт, тричі поспіль:**

```
                20    31.5   50    80   125 Гц
Avg SNR dB:    53.0   50.6  47.1  40.5  40.4
```

50 дБ, а не ≤ 0. Передумова **виміряно хибна на цьому залізі**. Тому нуль за номером смуги замінено
на **пандус за виміряним SNR** (той самий `kSnrNoneDb`..`kSnrFullDb`, що в синтезі): апарат, у якого
низ справді тоне, отримає нулі **зі свого виміру**, а цей — своє. Одне правило на доказ замість двох
правил, обраних рукою.

### Стеля була порахована з половини схеми

`kPathMaxLowDb = 8 дБ` виведено з **аналогової** ланки (0.47 мкФ у 2.2 кОм, 1-й порядок, 154 Гц →
6.7 дБ на 80 Гц). А §24 у тому ж абзаці описує **другу** ланку — цифровий ФВЧ 2-го порядку 100–150 Гц
у голосовому DSP. У стелі була одна з двох. Наслідок: три калібрування поспіль дали рівно `+8.0` на
80 і 125 Гц — насичення, не вимір. Обидві ланки разом дають **−14.5 дБ на 80 Гц**, вимір показав
**−14.1**. Замінено на `pathMaxAttenDb(freq)`, що рахує обидві.

### Живий баг, знайдений на дроті вже після правки

Калібрування писало `+29.7 +24.5 +18.5 …`, преференція зберігала саме це, **а звіт друкував
`+0.0 +0.0 +0.0`**. Гейт у `getMicCompensationCurve` обнуляв три смуги **на видачі** — мій же, з
попереднього дня, дзеркало правила, якого вже немає. Читач перекривав оцінювача мовчки. Прибрано:
правило про довіру живе там, де робиться оцінка, **один раз**.

### Що ще змінилося в цій сесії

- **Геометрія саба.** `room_sub_place` зберігався, друкувався і **не читався жодним обчисленням** —
  `getSpeakerY` віддавав `distListen + 165` завжди. Знайдено при звірці стенда з моделлю. Наслідок
  на стенді: саб стояв у моделі за 250 см позаду неіснуючого слухача → затримка **5.7 мс** замість
  **3.2**. Шість кроків лінії на каналі, де помилка чутна як провал.
- **Питання про саб** винесено з візарда пресету в налаштування, до решти фактів про машину, і
  задається **до** калібрування. Рядок «де стоїть саб» ховається, коли саба немає. Дві двері до
  одного факту власник відхилив: *«один фіг… винеси туди де їй місце»*.
- **Звіт.** Версія збірки тепер у шапці **і** в імені кожного файлу (читалася в трьох місцях
  по-своєму → одна функція в `HardwareProfile`). Блок `wDSP STATE` — пресет, криві, геометрія
  (`grep -c PREF_` по `SystemDiagnostics` давав **0**). `init.svc.audioserver`. Блок `SCREEN PANEL`
  (рідна роздільність панелі, фізичні мм — пікселі панель не ідентифікують). Детектор перегрузу АЦП.
- **Чотири рядки звіту, що брехали**, виправлено: `capture effects` (це `isAvailable()`, а не «були
  увімкнені»), `Deconv. noise` (це один канал, тепер названий), `WIRING` (казав «кожен динамік», а
  саб не перевіряв), проба мікрофона (казала `VOICE_RECOGNITION`, а вимір бере `UNPROCESSED`).

### Виміряні факти, що лишаються

- **Гучність платформи 12 і 16 дають однаковий рівень на АЦП** (пік −2.8 проти −2.9). Власник:
  це ще одна ознака нерелевантності стенда. Причина не встановлена; кандидат — стиснута
  характеристика вгорі або стеля підсилювача.
- **Кондиціонер піднімає шум на 12–15 дБ у смузі 80 Гц – 1.25 кГц** і майже не чіпає вище 5 кГц
  (виміряно пробою `PROBE_MIC` проти тиші того ж дня). Це смуга, де живуть і Auto-EQ, і тест
  «чує/не чує». `Cabin silence` ми міряємо, друкуємо і **ніде не читаємо** — напрошується
  попередження «вимкни обдув» перед свіпом.
- **Тест присутності за SNR витримав три різні рівні шуму**, зокрема разовий стук, що дав тилу
  пік −13.3 dBFS: `median SNR 1.4 dB → not present`. Старий тест за абсолютним піком намалював би
  неіснуючий динамік.

### 🔴 Стенд власника — НЕ САЛОН, висновків з нього не робити

Ліворуч відкрита шафа з поличками, праворуч відкритий простір балкону, скло за 1.35 м, ~1 м між
центрами динаміків, перед FL лампа, перед мікрофоном відкритий ноутбук. Тилових динаміків немає.
Модель тримати **симетричною**; єдина асиметрія, яку вона має знати, — де мікрофон відносно голови.
Cabin gain там слабкий, тому якір на стенді **перебере** — судити по вухах треба в машині, на стенді
дивитись на збіг мікрофонного і розрахункового спектрів.

### 📋 Черга на наступну сесію (наказ власника 13.09.2026, вечір)

1. 🔴🔴 **Автоматична вивірка кривої Менсона для пресету.** Наказ дослівно: *«щоб люди не гадали як
   її налаштовувати правильно»*. Зараз користувач лишається сам на сам із цим налаштуванням і не має
   жодного орієнтира, правильне воно чи ні. Потрібно, щоб програма сама перевіряла криву й казала,
   що з нею не так, — за тим самим правилом, що й решта: людина не чіпає нічого й отримує
   правильний результат.
2. **Читати `Cabin silence` перед свіпом і попереджати про обдув.** Виміряно 13.09: кондиціонер дає
   +12…15 дБ у смузі 80 Гц – 1.25 кГц — рівно там, де живе Auto-EQ і тест «чує/не чує». Тишу ми
   міряємо, друкуємо і не читаємо. Це загальне для будь-якої машини, не для стенда.
3. **Чому гучність платформи не доходить до рівня на АЦП.** 12 і 16 дають однаковий пік. Кандидат —
   стиснута характеристика вгорі або стеля підсилювача. Перевіряється одним проходом на гучності 6.
4. **3150 Гц після матриці конструктиву.** У виміряній огинаючій піка від отвору не видно (`−0.1`),
   а після `−5.0` синтез підняв смугу до стелі `+4`. Слухати в машині на різкість голосу; якщо
   різкість є — послабити клітинку для цього екземпляра, але **не** повертатися до вимоги
   «переміряй фізику».
5. **Частотно-залежне вікно аналізу.** Зараз 341 мс на всі смуги. Документ радить 30–50 мс, і для
   низу це неправильно (50 мс — один період 20 Гц). Правильно: довге знизу, коротке зверху, і
   перевірити на **тому самому** `room_measurement.wav`, який уже лежить на пристрої, — рахується
   офлайн, без жодного свіпу.
6. **Провали гребінки.** Скло за 1.35 м дає нулі кожні ~127 Гц; синтез бачить їх як провали АЧХ і
   витрачає бюджет підйому на артефакт точки. Документ дає спосіб: два проходи з мікрофоном,
   зсунутим на ~15 см — те, що поїхало, гребінка; те, що лишилось, приміщення.

### 🎵 Плеєр на стенді — особливість, щоб не сплутати з вадою

Активний плеєр — **Morpheus YouTube Music**. Він може **замовкати**, не отримавши доступу до
наступної пісні в плейлисті. Це його власна поведінка, **не наша вада і не збій тракту**. Якщо
спектр раптом порожній: спершу перевірити, чи це пауза; якщо не пауза — дати «наступний трек».
Не чіпати заради цього нічого в аудіотракті й не заводити баг.

### 🔎 Спостереження з дроту після роздачі 0.4.9.8 (13.09.2026, вечір, апарат грає)

Зняте на живому апараті з роздананою збіркою, плеєр Morpheus YouTube Music грає (`state=3`).

**Шумодав працює як задумано.** Рядок гейта раз на секунду:
```
gate: rms=8894 floor=150 ratio=59.29 (opens at 2.50) peak=19575 gain=1.09 OPEN, floor held (a player is live)
```
`floor held (a player is live)` — полиця шуму **не переучується під музику**. Запас над порогом
47–63×, AGC тримає 1.09–1.31. Це та правка, що робилася вранці 13.09, і в бою вона тримає.

**⚠️ Але звідси видно межу, якої ми не перевіряли.** Полиця вчиться **тільки в тиші**. Апарат, що
вмикається одразу в музику й грає без пауз, лишиться з тим значенням полиці, яке було на момент
старту (`floor=150`, тобто −46.8 dBFS). Сьогодні це нешкідливо, бо `ratio` і так 50+, і гейт
відкритий за будь-якої полиці. Але поведінка на холодному старті без жодної паузи **не перевірена**:
якщо початкова полиця виявиться завищеною, гейт може не відкритися взагалі. Варте одного досліду в
наступній сесії — ребут, одразу музика, подивитись перший `gate:`.

**Криві на апараті після повного циклу:**
```
pref_mic_compensation  29.65 24.55 18.54 15.96 8.89 0 0 0 0 0 −1.50 −5.00 −1.00 3.50 7.00 10.00
pref_cabin_response   −27.70 −27.20 −19.65 +2.29 −6.23 −1.88 +2.12 −0.83 +0.58 +0.07 −3.66 −8.21 −4.44 −1.73 −4.57 −14.09
```
Обидві читаються повністю — гейт, що обрізав нижні три смуги на видачі, прибрано (`fff1481`).

**`spectrum_mode` у преференціях відсутній** ⇒ активний режим за замовчуванням, тобто
**розрахунковий**. Тобто те, що власник бачить на екрані зараз, — це потік + крива DSP + відгук
салону. Щоб звірити два спектри (його ж критерій правильності), треба перемкнути режим на мікрофон;
`onMeasuredCurvesChanged()` уже перештовхнув обидві криві, перезапуск не потрібен.

**Що впало в око в самих числах, без висновків про фізику стенда:**
- `cabin_response` на 80 Гц вийшов **додатним** (+2.29) — уперше. Це прямий наслідок того, що
  компенсація на цій смузі більше не впирається в стелю 8 дБ (тепер 15.96).
- 3150 Гц: `cabin_response` = −8.21, і синтез підняв цю смугу до стелі `+4`. Це та сама клітинка
  `−5.0` з матриці конструктиву. **Слухати в машині на різкість голосу** (пункт 4 черги).

**Стороннє, не наше:** щось на апараті кличе `com.android.commands.media.Media` рівно кожні ~3.4 с
(процеси з uid 0). Помічено 13.09 о 16:00. На звук наразі не впливає, але якщо колись з'явиться
дьоргання або зайве навантаження — це перший підозрюваний.

**🪤 Як НЕ визначати, чи плеєр живий.** `dumpsys media_session` для Morpheus показує
`position=0` і **застиглий** `updated`, навіть коли музика грає. Виглядає як зупинений трек, і за
цим легко штовхнути «наступний» посеред пісні. Надійна ознака — рядок `gate:` від
`RadioMicCapture`: він іде раз на секунду, доки в мікрофон щось потрапляє. Перевірено 13.09: сесія
рапортувала застиглу позицію, а гейт цокав секунда в секунду з годинником апарата.
Друга пастка поруч: `logcat -d -t 60` бере останні 60 рядків **усього** буфера, і чужі теги їх
з'їдають — для перевірки живості брати вікно на пару тисяч рядків і грепати.

## 🔴🔴 ЕКРАН ЯК ДРУГЕ ДЖЕРЕЛА ПРАВДИ — пресет втрачав кросовер саба (13.09.2026, після збірки 0.4.9.8)

Знайшов власник **на слух**, уже після того, як пак було зібрано: *«там раніше частота сабу була
100, зараз 80. І глухіть до низьких пройшла»*. Жодного свіпу й жодної ручної правки між тим не
було — і це та обставина, що робить випадок вартим запису.

### Симптом і як він відрізняється від помилки виміру

Синтез о 17:05 записав `Subwoofer: LPF 100 Hz (idx 6)`. Через якийсь час у преференціях лежало
`_sub_f = 5`, тобто **80 Гц**. При цьому `_bf_f` і `_bf_r` (ФВЧ дверей) лишилися **7**, тобто 100 Гц.

**Ось у цій неузгодженості й доказ.** Перерахунок змінив би обидва кінці кросовера разом. Змінився
**один**. Отже це не вимір — це перезапис одного поля. Наслідок на слух: двері зрізані від 100 Гц
знизу, саб зрізаний від 80 Гц зверху, і октава **між ними не належить нікому** — «глухість» на низах.

### Механізм

`MainActivity.savePreset()` брала частоту саба не з преференцій, а з **тексту на спінері**:

```java
int subFreqIdx = resolveSubFreqIndex(spinnerSubFreq.getText().toString());
```

а `resolveSubFreqIndex` відповідала `5` (80 Гц) **і на «поле порожнє», і на «текст не впізнано»**.
Тобто екран, який ще не дочитав пресет, був **невідрізнимий від свідомого вибору 80 Гц**.
`autoSaveCurrent()` викликається на будь-яку зміну UI — а слухачі спрацьовують і тоді, коли розмітку
лише заповнюють, — і клала цю вигадану п'ятірку поверх виміряної шістки.

Чому саме це поле: `_bf_f`/`_bf_r` читаються з **повзунків** (`getIntSlider`), а `_sub_f` — єдине, що
проходило через розпізнавання **рядка**. Загубилося рівно воно.

### 🔴 Масштаб виявився ширшим за симптом

У `savePreset` **уже стояв** запобіжник `if (isFullyInitialized)`, і він старший за цю ваду —
хтось колись уже знав про цю небезпеку. Але **поза ним** лишалися:

* шістнадцять підсилень смуг `_g0..._g15`,
* шістнадцять перемикачів `_q0..._q15`,
* `_sub_g`, `_sub_f`,
* `_power_vol`.

Тобто недобудований екран міг переписати не тільки кросовер, а й **увесь еквалайзер**. Кросовер —
просто те, що виявилося чутним.

### Виправлення (коміт `47b8d99`), з двох боків

1. **Резолвер уміє сказати «не знаю»** — повертає `-1`, і `savePreset` у цьому разі **лишає
   збережене значення недоторканим**. Правило, яке варте запам'ятовування ширше за цей файл:
   **умовчання належить ЧИТАЧЕВІ, який вирішує, що робити без значення, і ніколи — ПИСАЧЕВІ, який
   його вигадує.** Різниця між цими двома і є вся вада.
2. **`autoSaveCurrent()` не працює до `isFullyInitialized`.** Він зберігає «те, що користувач щойно
   змінив»; доки шар будується, користувач не змінив нічого.

Бутстрап-викликачі (`savePreset` при створенні першого пресету й при видаленні останнього) свідомо
НЕ чіпалися: там пишеться щойно скинутий екран, і це значення, яке хтось таки обрав.

### Чого ця вада навчає на майбутнє

Це той самий закон власника — **один факт, одна функція** — але в формі, якої ми ще не ловили:
другим джерелом правди був не інший клас, а **віджет**. Пресет жив у преференціях і одночасно в
тексті на екрані, і хто записав останнім, той і виграв. Причому екран міг записати значення, якого
йому ніхто не казав.

⚠️ **Не перевірено й лишається відкритим:** `_power_vol` іде через `parsePowerDb()`, тобто теж через
текст. Чи вигадує воно умовчання так само — не дивився. Перевірити в наступній сесії тим самим
питанням: що функція повертає, коли поле порожнє.

**💣 Git у WSL — міна з закінченнями рядків (13.09.2026).** У цьому репозиторії Windows-git має
`core.autocrlf = true`, а WSL-git не мав його зовсім, тож `git status` із WSL показував **~170
файлів як змінені**, хоча жоден не чіпали: він бачив CRLF у робочій копії як відхилення від LF в
індексі. Пушити з WSL готові коміти безпечно — але `git add -A` чи `git commit -a` звідти залили б
перезапис закінчень рядків у весь репозиторій одним комітом. Вирівняно
(`wsl -u user git -C <repo> config core.autocrlf true`), обидва боки тепер бачать чисте дерево.
Перевіряти в кожному новому репозиторії **перед** тим, як комітити з WSL.

## 🔴🔴 МІКРОФОН ВИГРАЄ ТОЙ, ХТО ЙОГО ТРИМАЄ (13.09.2026, вечір — знахідка власника)

**Симптом.** Калібрування й свіп салону дали `above 8 kHz -97 dB` (норма тут ≈ −36). Верх запису
мертвий, огинаюча на 12.5 і 20 кГц провалилася до −46…−50 дБ.

**Причина — не наша математика, а володіння входом.** Власник: *«Я перед свіпом ввімкнув
розрахунковий а не мікрофонний спектроаналізатор»*. У мікрофонному режимі `RadioMicCapture` тримає
вхід **постійно**, і хотворд не може його забрати. Перемкнув на розрахунковий → `checkSourceState()`
зупинив захват → вхід звільнився → `com.google.android.googlequicksearchbox` схопив його на 16 кГц.

Два звіти, по рядку кожен:
```
17:05 (мікрофонний режим):  microphone was already ours (-26.8 dB above 8 kHz)
19:35 (розрахунковий):      microphone was held by another app (-70.4 dB); stopped [googlequicksearchbox,
                            googleassistant]; now -33.4 dB - STILL HELD
```

**Чому `force-stop` не рятує:** ми вбиваємо хотворд, він піднімається за мить і знаходить **вільний**
мікрофон. Між `MicrophoneGuard.ensureOurs()` (рядок ~1607) і власним `openMicrophone()` (~1850) стоять
побудова флат-пресету, блокування гучності, штовхання на чип і затримка на осідання — **майже секунда
вільного входу щопроходу**.

**Виправлено (коміт `624fc66`):** `ensureOurs()` **claims** пристрій одразу, щойно його звільнив, і
тримає до `releaseHold()`, який вимір кличе **тільки після** того, як відкрив власний захват. Два
клієнти на одному вході на мить — нормально; вхід без жодного клієнта — ось що його втрачає.

**Друга половина правки — за правилом власника «одна функція/клас = джерело правди».** Я спершу
поклав тримання в `RoomMeasurement`, лишивши забирання в `MicrophoneGuard` — острів із містком, і не
просто неохайність: **відкриття** жило в одному класі, **тримання** в іншому, тож гвардія могла
тримати потік одного виду, а свіп писати крізь інший. Тепер `MicrophoneGuard` володіє всіма трьома —
забрати, тримати, відкрити; `RoomMeasurement.openMicrophone()` делегує й лишає назву лише заради
читабельності викликів.

### 🔴 І окремий наслідок: тиша вище свіпу читалася як провал АЧХ (коміт `77b0e2c`)

Коли мікрофон обмежений, свіп **правильно** звужується до 7 кГц і каже про це. А далі аналіз читав
тишу вище 7 кГц як вимір: `Cabin response` показав −10 / −51 / −53 дБ на 8 / 12.5 / 20 кГц, і синтез
підняв усі три до стелі `+4`. **Смуги, які жодного разу не збуджувалися, вирішили верхню октаву.**

Полагоджено не новою гілкою, а наявним механізмом: синтез і так зважує кожну смугу за довірою до її
SNR, тож смуга поза свіпом дістає `UNMEASURED_SNR_DB` → довіра нуль → корекція рівна, а
`cabinResponseDb16` для неї обнуляється. «Не виміряно» тепер іде тією самою дорогою, що й «виміряно
погано».

### 🪤 Пастка розслідування, на яку я наступив

Заявив, що програма виміряла −97 дБ і **промовчала**. Недоведено: буфер `logcat` на цьому апараті
крутиться так швидко, що через хвилину після події навіть `logcat -d -s wDSP_RoomMeasure:W` уже
порожній. Попередження, найімовірніше, було — його просто не було в фільтрі монітора. **Логи після
події тут не є свідком; дивитися наживо або читати файл звіту.**

### 📋 Черга, дописано 13.09.2026 ~20:00 — питання власника про верх пресету

Власник подивився готовий пресет і спитав: *«там різкий підйом на 4 дБ в полосах від 1.2 кГц до 5,
далі спад на 8, та підйом 12.5 та 20. Це дійсно компенсація моєї акустики, чи баг?»*

Розібрано зі звіту (`/sdcard/.../room_measurement.txt`, прохід 19:51). **Код не зламаний — це чесний
наслідок виміру.** Але вимір містить одну різку річ, і синтез на неї реагує способом, який варто
змінити.

**Факт із запису — front left згори мертвий, а front right ні:**
```
                12.5 кГц   20 кГц
front left       -32.7      -50.5
front right       -5.5       -9.3
subwoofer         -5.5       -9.3
```
Розрив **27 і 41 дБ** між каналами в тих самих смугах.

**Звідки взявся зигзаг `+4 / 0 / +4` на 8 / 12.5 / 20 кГц.** Матриця конструктиву додає
`+3.5 / +7.0 / +10.0`. На 8 кГц цих `+3.5` вистачає, щоб `m[b]` вийшов вище опори й корекція стала
нулем; на 12.5 навіть `+7.0` не перекриває провалу, що його вносить FL у середнє. Тобто провал на
8 кГц — **артефакт стику** двох різних механізмів, а не властивість машини.

**Підйом 2–5 кГц (`+4 +4 +4`) натомість справжній:** там `m − refMid` від'ємний по **всіх трьох**
каналах, не через один.

#### Дві правки, обидві не залежать від стенда

1. 🔴 **Усереднення каналів у дБ ламається на різко різних АЧХ.** `avgClean[b]` — просте середнє
   по почутих каналах, тож один канал, що впав на 27–41 дБ у смузі, тягне за собою всю смугу, і
   еквалайзер підіймає верх для **всіх**, зокрема для каналу, у якого верх і так на місці. Чути це
   буде передусім на справному каналі як різкість. Потрібна **медіана** замість середнього, або
   відкидання каналу, що випав із групи на десятки децибелів (той самий хід, що вже зроблено в
   калібруванні, де беремо **максимум** по каналах, а не середнє).
2. 🔴 **Немає обмеження на зигзаг між сусідніми смугами.** Крива корекції не має стрибати на 4 дБ
   туди-сюди через артефакт усереднення. Потрібна межа на різницю сусідніх смуг — і це узгоджується
   з тим, що `Q = 2.2` фіксований: залізо все одно не вміє різких сусідніх сходинок, тож синтез не
   повинен їх і замовляти.

⚠️ Перевіряти НЕ на стенді: там перед FL стоїть лампа, перед мікрофоном відкритий ноутбук
([[bench-is-not-a-cabin]]). Сам розрив між каналами — вимірюваний факт, але його **величина** на
стенді нерелевантна. Правки судити по тому, чи зникає зигзаг і чи перестає один випалий канал
керувати всією смугою.

### 📋 🔴 Наказ 13.09.2026 ~20:05 — ІНФОРМАЦІЙНА СТОРІНКА ПІСЛЯ КАЛІБРУВАННЯ

Дослівно: *«зробити чітку інформаційну сторінку після калібрування, з повідомленнями власнику про
вади його акустики… але не забувай що це може бути не вадою… щоб людина знала які вади вона в стані
виправити (переплюсовка), а які особливості його салону, і наявними методами не лікується».*

Це не «ще один звіт». Звіт у нас уже є і він для нас. Це **сторінка для власника машини**, і її
єдина мета — щоб людина знала, **що їй робити зі знайденим**, і не бігла перепаювати те, що
перепаюванням не лікується.

#### Що вона має розрізняти

| знахідка | вердикт |
|---|---|
| Пара **перед ↔ зад** звучить по-різному | **НОРМА.** Різні динаміки, різні місця, різна відстань. Нічого не робити. |
| **Одна пара** (обидва передні або обидва задні) працює по-різному між собою | **АНОМАЛІЯ.** Симетрична пара має бути симетричною — ось де варто дивитися. |
| **Один канал** випадає з усіх | Найсильніший сигнал; але причин кілька, і вони різні за лікуванням. |

#### Причини, які треба назвати людині ОКРЕМО — бо лікуються по-різному

1. **Переплутана полярність (переплюсовка)** — `polarity -1` на тлі решти `+1`.
   ✅ **Людина може виправити сама**: поміняти два дроти на тому динаміку. Сказати прямо й
   без жаргону. ⚠️ Але не плутати: **усі** канали інверсні — це не вада, це домовленість, звучить
   однаково (в `wiringVerdict` це вже враховано). І сабвуфер із порівняння виключений — у нього
   свій LPF 12 дБ/окт, що крутить фазу, і його часто вмикають інверсно навмисне.
2. **Геометрія розміщення** — динамік далі/ближче, під іншим кутом, за перепоною.
   ⚠️ **Частково лікується** затримками й балансом, які ми й так рахуємо. Сказати, що вже
   компенсовано, і скільки саме (кроки затримки).
3. **Протифазні відлуння / гребінчаста фільтрація** — відбиття від скла чи панелі приходить
   із затримкою і гасить пряму хвилю на певних частотах.
   ⛔ **Не лікується наявними методами.** Це властивість салону і точки слухання, не динаміка.
   Людина має це **знати**, щоб не шукати винних. Ознака з документа власника: провал від відбиття
   **їде**, коли мікрофон посунути на кілька сантиметрів, а власний резонанс капсуля — **ні**.
4. **Канал справді слабший або без ВЧ-ланки** (як FL у звіті 19:51: −32.7 дБ на 12.5 кГц проти
   −5.5 у FR). Може бути мертвий твітер, перепона, або так і задумано конструктивом.
   ⚠️ Сказати, що бачимо, і що еквалайзер це **не полагодить** — підйом однієї смуги на мікс
   підніме її і в здоровому каналі теж.

#### Правила тону (з практики цієї сесії)

- **Не лякати.** «Норма» казати так само голосно, як «аномалія».
- **Не стверджувати причину, якої не виміряли.** Якщо з одного проходу полярність від геометрії не
  відрізнити — так і написати, і дати спосіб перевірити.
- **Кожному пункту — дія**: «виправити самому» / «уже скомпенсовано програмою» / «це салон, не
  лікується». Без цього сторінка стає ще одним текстом, який ніхто не читає.
- Ніяких `dBFS`, `SNR` і номерів смуг у тексті для власника — числа лишити у звіті.

### 📋 🔴 Наказ 13.09.2026 ~20:10 — ПЕРЕД↔ЗАД: трім фейдера зараз, окремі зони після патчу MCU

Уточнення власника до попереднього пункту: різниця перед↔зад — це **не назавжди «норма, нічого не
робити»**. Її можна лікувати, і в два етапи.

**Етап 1 — робиться зараз, прошивка не потрібна.** Еквалайзер один на стереомікс, окремо крутити
перед і зад не можна. Але **фейдер є**, і різницю СЕРЕДНЬОЇ віддачі перед↔зад ми з виміру знаємо.
Отже: порахувати середній рівень передньої пари й задньої, різницю внести як **трім фейдера**
(`_f_fr`) у синтезований пресет. Людина отримує зведений по рівню перед/зад, не чіпаючи нічого.

⚠️ Під час самого проходу `_f_lr`/`_f_fr` використовуються для ізоляції каналів — не сплутати це з
фейдером готового пресету. Трім має лягати в пресет, а не в маршрутизацію свіпа, і мати одне
джерело (див. правило власника про єдину функцію).
⚠️ Рахувати трім лише коли задня пара справді почута. На стенді власника тилу немає взагалі, тож
там цей код мовчатиме — перевіряти в машині.

**Етап 2 — після байт-патчу MCU** (`C:\MCU`, [[mcu-patching-workspace]]): з'являться **окремі
сторінки еквалайзера на зони**, і тоді перед↔зад лікується повністю — не лише рівнем, а й формою
АЧХ. Тоді ж інформаційна сторінка (пункт вище) має змінити формулювання з «це норма» на «зведено».

**Статус:** власник поставив це в один ряд із автовивіркою кривої Менсона — тобто це **план на
реалізацію**, а не ідея на подумати.

### 📋 🔴 Наказ 13.09.2026 ~20:15 — ПОЛЯРНІСТЬ ПО СМУГАХ, а не одна на канал

Уточнення власника, і воно ламає нинішній спосіб: *«сучасна акустика у багатьох це не один динамік,
а мідбас+твіттер… частіше за все буває так що переплюсовано не всю акустику, а якусь із частин
двохкомпонентної»*.

**Чому нинішнє не годиться.** `NativeSweep.POLARITY` дає **один знак на канал**, узятий з імпульсної
характеристики, а `wiringVerdict` порівнює канали між собою. Ні те, ні те **не бачить інверсії
всередині каналу**: якщо твітер увімкнено протифазно до свого ж мідбаса, сумарний імпульс усе одно
має якийсь один знак, і ми напишемо `polarity +1` для динаміка, у якого насправді вада.

**Як воно виглядає у виміру.** Мідбас і твітер перекриваються на пасивному кросовері (зазвичай
2.5–4 кГц). Протифазне ввімкнення однієї з ланок дає **глибокий вузький провал саме там**, бо в
смузі перекриття вони гасять одне одного. Тобто шукати треба не знак, а **провал на частоті
розділу** — і окремо знак у смузі мідбаса проти знака у смузі твітера.

**Що робити:**
1. Рахувати полярність **по смугах**, щонайменше двома групами на канал — нижче й вище очікуваного
   пасивного кросовера. Розбіжність між ними в одному каналі = інверсія однієї з ланок.
2. Порівнювати **твітери лівого й правого** окремо від мідбасів. Інверсія твітера лише з одного боку
   розвалює сцену вгорі, а внизу все виглядає нормально — саме тому це й не помічають.
3. ⚠️ **Відрізняти від гребінки.** Провал від протифазного ввімкнення **стоїть на місці**, коли
   мікрофон посунути на кілька сантиметрів; провал від відбиття **їде** (спосіб із документа
   власника, той самий, що вже записаний у пункті про відлуння).

**Для інформаційної сторінки:** це **та вада, яку людина виправляє сама** — поміняти два дроти на
твітері. Але сказати треба точно, **на якому саме** динаміку й на якій ланці, інакше порада
некорисна: «у вас щось переплутано» — це не дія.

### 📋 🔴 Наказ 14.09.2026 ~03:25 — АВТОВІДНОВЛЕННЯ ПЛЕЄРА ПІСЛЯ СНУ (у черзі; спершу мікрофон)

Власник: *«я хотів би впровадити цю загублену китайцями функцію, раз wDSP стартує першим»*, і
одразу — *«в туду внеси, а зараз продовжуй з мікрофоном, по порядку»*. Джерело ідеї — документ
сесії Дж `60ce423d-b3ca-47e4-bb3f-2cb19b048f15` на дошці: *QF Platform Sleep Lifecycle & Autonomous
Media Resumption (Haiwai vs Jitu2)* (`gemini__60ce423d-…__qf-sleep-media-resumption__2026-09-14-01-09-11.md`).
Суть: Jitu2 відновлює будь-який сторонній плеєр після сну й ребуту, Haiwai — лише свої п'ять.

**Звірено на апараті власника (Haiwai, 14.09.2026 03:23), а не взято з документа:**
- 📻 `/system/config/RestoreAppsWhenWakeup.ini` — рівно п'ять заводських: `com.qf.musicplayer`,
  `com.qf.videoplayer`, `com.android.fmradio`, `com.android.fmradio.ext`, `com.zjinnova.zlink`.
- 📻 `/system/config/NotKillAppsBeforeSleep.ini` — ні YouTube Music, ні wDSP, ні радіо там немає.
  Живуть вони завдяки `/great/sleep/sleep_whitelist` (system, 0600): `com.navioverlay.car`,
  `com.radiorubka.wdsp`, `com.huautobrightness.controller`, `com.kostyamat.fmradio`.
- 📻 **Плеєр сон не пережив:** YouTube Music (morphe) грав до сну о 02:47, а о 03:23 його pid
  стартував о 03:10:51 — після пробудження о 03:09:42. wDSP той самий pid весь час.
- 📻 `persist.sys.qf.last_audio_src` = `com.android.fmradio` (застарілий), `sys.qf.last_audio_src` =
  плеєр, що грає. Пропа `last_src_before_sleep` у `getprop` **немає** — ❓ назва в документі може
  бути іншою або проп не створюється на цій прошивці.
- ❓ Не звірено: чи справді `killAppsBeforeSleep` вбиває все поза списками (бачили лише один плеєр),
  затримку між `ACC_OFF` і вбивством, чи встигає плеєр поставити паузу до того, як ми прочитаємо стан.

**Що вже є в wDSP:** `NotificationAccess` (слухач сповіщень) і `NowPlaying` з
`MediaSessionManager.getActiveSessions`, `TransportControls` та запасним `dispatchMediaKeyEvent`.
Холодного старту вбитого плеєра (`ACTION_MEDIA_BUTTON` на його приймач або `MediaBrowser`) немає.

**Що вирішити з власником перед кодом:**
1. **Радіо.** QFRadio відновлює себе саме (`sleep_whitelist`, своя робота після сну). Якщо до сну
   грало радіо, wDSP не чіпає нічого; якщо плеєр — чи не підніметься радіо поверх. Це пункт у
   `C:\APPS_Contacts\wDSP--QFRadio\AUDIO_OWNERSHIP_CONTRACT.md`, а не рішення однієї сторони.
   Контракт уже каже: запит wDSP **не будить** зупинене радіо.
2. «Грав до сну» — знімок на `ACC_OFF` чи останній стан, що тримався N секунд (платформа могла
   поставити паузу раніше за нас).
3. Після ребуту теж (як у Jitu2) чи лише після сну.
4. Пауза, яку людина поставила сама перед вимкненням, — не відновлювати.
5. *(власник, 14.09.2026 ~03:35)* **Білий список сну — питати в сесії Дж `60ce423d`**, вона
   спеціалізується на дебазі фреймворку: знає, **як перевірити, чи ми в `sleep_whitelist`**, і
   **інтент, яким відправити людину прямо на екран**, де нас туди вносять. Мета — щоб користувач
   додав wDSP одразу (без цього процес не переживе сну й відновлювати не буде кому).
   🔴 *(власник, уточнення)* **Не додавати застосунок самим** (жодного запису в `/great/sleep` рутом
   чи інакше) — **лише направити людину**, щоб вона додала сама. Написати їй
   адресно, не на всіх і не P0; факти звіряти на апараті перед кодом.
6. 🔴 *(власник, 14.09.2026 ~04:05)* **Форма реалізації — два окремі вмикачі** в Налаштуваннях,
   розділ «Дозволи та система»: **«відновлювати плеєр після ребуту»** і **«відновлювати плеєр
   після сну»** — людина обирає одне, друге або обидва.

### 📋 🔴 Наказ 14.09.2026 ~04:00 — МІКРОФОН: ЗАБИРАТИ ВХІД, ЩОЙНО ВІН ЗВІЛЬНИВСЯ (не просити ребут)

Власник: *«якщо відпускати, повідомляючи людину, що потрібен ребут для мікрофона — людина так ніколи
його і не ввімкне на всю ширину»*. Зараз (`ed42121` + `527ee73`) без рута вузький вхід → мікрофон
недоступний до перезапуску, спектр розрахунковий, тост «перезавантажте». Схвалено замінити: поки
мікрофон недоступний, `AudioRecordingCallback` стежить за активними записами; щойно їх **немає**,
одразу відкрити свій захват — перший клієнт задає частоту (виміряно цієї ночі). Частота пристрою
48 кГц → мікрофон повертається сам; 16 кГц → тихо відпустити, чекати наступного розриву. Асистент
вхід відпускає регулярно: 02:12:04→02:12:06, короткі сесії по 11 с, перед сном. Текст тосту (або його
відсутність) — рішення власника. Перевірка — `adb reboot` (дозволено власником) + заміри з
`dumpsys`, бо лог застосунку витісняється за хвилину (`platform/07-PRACTICE.md` §7).

**Тост — рішення власника (~04:10), два контексти, не один:**
- **Щоденна експлуатація** (спектр, віджет, заставка): «мікрофон тимчасово зайнятий, спектр
  розрахунковий» — і тихо чекати вікна.
- **Калібрування мікрофона й автопресет** (типово: свіжа установка, рута немає): попередження й
  очікування вікна **з межею часу**; не дочекались — **явне «перезавантажте магнітолу, мікрофон
  зараз недоступний на повну смугу»**. Причина — спостереження власника на Jitu2: Google-асистент не працював, доки
  через adb не заборонили мікрофон сервісу **TXZ**, а асистент при цьому глухим не став. **TXZ і Toppal — один продукт** (TXZ — сервіс,
  Toppal — асистент, з системою говорить через `com.qf.ailit.bridge`); мікрофон приходить іншим шляхом. Вікна від них не чекати (`platform/05-AUDIO-PATH.md`, вендорні асистенти).

**✅ ЗРОБЛЕНО 14.09.2026 вдень, усе перевірено на апараті власника (рут wDSP заборонений):**
- `2391b98` щоденний контекст — `MicInputWindow` забирає вхід у розриві (+300 мс на закриття
  входу), тост «Мікрофон тимчасово зайнятий, спектр розрахунковий».
- `1a287d0` служба стартує на розблокуванні, у потоках (audioserver → мікрофон; MCU → пресети):
  мікрофон наш на 48 кГц за ~11.5 с до асистента і після `adb reboot`, і після зняття живлення.
- `ea22f5d` калібрування — **власник спростив (~15:35): «для свіпування і без рут, тільки з
  перезавантаженням»**, без очікування вікна. Перевірка за частотою пристрою; вузько без рута →
  вимір не стартує, екран показує `mic_narrow_restart`. На дроті 16:13:53: вхід 16 кГц →
  «STILL HELD, restart needed» за 50 мс, без кадрів у MCU, без гучності, без свіпу.
- Не бачено: гілку з рутом у свіпі, екрани відмови й тост очима, природний розрив асистента.

### 📋 Наказ 14.09.2026 ~15:10 — ЗАСТАВКА: МЕТАДАНІ СИСТЕМНИХ ПЛЕЄРІВ (у черзі, не зараз)

Власник: *«Наш скрінсейвер не вміє показувати метадані з цих системних застосунків, а треба буде
навчити»*. Джерело — дошка #539 від сесії Дж `60ce423d` (реверс протоколу віджетів лаунчера й
CAN-кластера), записано нею в `qf-platform/references/08-VOLUME-AND-SOURCES.md` §6:
- **BT-музика `com.qf.bluetooth`**: трансляція `com.qf.action.BT.MUSIC.INFO` (`songName`,
  `songSinger`, `songAlbum`); для CAN `com.qf.action.bt.music` (`songName`, `songArt`); позиція в
  пропі `persist.sys.bt.music.progress` (`"pos_ms,total_ms"`); керування
  `com.qf.action.BT.MUSIC.CONTROL` (`command` 1..5).
- **Системний плеєр `com.qf.musicplayer`**: `…action.LAUNCHER_INIT_ACTION` (початкова синхронізація),
  `…action.UPDATE_ACTION` з Parcelable `MusicInfoData` (`name`, `artist`, `album`, `path`, `currTime`,
  `totalTime`, `curPlayStatus`, …); обкладинка — `MediaMetadataRetriever.getEmbeddedPicture()` з `path`.
- **Заводське радіо `com.android.fmradio`**: `com.qf.radio.update_action` (частота, діапазон, назва).

⚠️ Звірити до роботи, не припускати: `NowPlaying.java` **вже** слухає `com.qf.musicplayer.action.UPDATE_ACTION`
(`MusicInfoData`), а BT-музику й `LAUNCHER_INIT_ACTION` — ні. Отже, спершу з'ясувати, чому заставка
не показує навіть системний плеєр (не доходить трансляція, чи заставка бере лише MediaSession), і
лише тоді додавати BT. Протокол від Дж — звіряти на дроті (довіра ~80%).

## 🔴🔴 14.09.2026, вечір — МАТЕМАТИКА СПЕКТРА: розрахунковий = ЦІЛЬ, мікрофонний = НАЯВНЕ

Наказ власника «правка математики»: розрахунковий спектр показував дикий бас; пласкій пресет із розовим
шумом (EMMA 2018) давав перегин на басах і втрату на 20 кГц. Усе нижче виміряно на апараті власника.

### 🧭 Рішення власника, які тепер закон
- **«Розрахунковий спектроаналізатор — це цільове, мікрофонний — наявне».** Розрахунковий = PCM
  з Visualizer + модель DSP пресету (як його робить чіп), **без жодної специфіки салону**.
- **Головний екран еквалайзера — вимірювальний інструмент:** показує трек як записано, **не залежить
  від гучності**, лише накладає криву ефектів. **Віджет статус-бару й заставка — забавки:** чим більшу
  площу займають, тим краще, але на тихій музиці не повинні показувати повний сигнал.
- **Одне джерело правди** на всю програму (метод або клас) — нагадав, поки я правив.
- Q-перемикачі смуг — фікція (прошивка форсує 2.2), **лишаються** на випадок байт-патча MCU.

### ✅ Зроблено (коміти від `9c5ebdb`, НЕ запушено)
| коміт | що |
|---|---|
| `5499d9f` | крива DSP доходила в нативний аналізатор лише на старті захвату → після зміни пресету малював ПОПЕРЕДНІЙ (+20 дБ басу AutoEQ на пласкому). Тепер `markDspCurveChanged()` + одна точка передачі `dispatchNativeFrame()`; перевірено: крива за 33–46 мс після кадру EQ. Поріг шуму — лише для мікрофона (`newAnalyzer(..., acoustic)`); на цифровому PCM він з'їдав смугу 17.8 кГц до −133 дБ |
| `38bbbf1` | крива салону (знята на стенді, −13 дБ на 20 кГц) більше не входить у розрахунковий; у звіті лишається |
| `f1d5e52` | Java-аналізатор (двійник нативного) прибрано; без бібліотеки спектра немає |
| `a12a91c` | **стандартна третинна сітка** 16 Гц–20 кГц (`1000·2^((i−18)/3)`); згортання в 16 центроване на повзунку EQ (смуга на центрі + ½ сусідів, 20 кГц ×4/3); модель DSP: двері через HPF 2-го порядку за кодом чіпа (**код 0 = Through**, не «20 Гц»), саб через LPF, енергетична сума; Q 2.2 примусово (`FIRMWARE_FORCES_WIDE_Q`); без саба якщо `room_has_subwoofer=false`. Хост-тест ALL PASS |
| `252f105` | `CLAUDE.md` описує аналізатор як є |
| `4cbcaa3` | **Visualizer у `SCALING_MODE_AS_PLAYED`**: головний — абсолютні dBFS; віджет/заставка — AGC з нижньою межею −30 dBFS для цифрового відводу (мікрофон лишає −50); осцилограф має власне підсилення з тією ж межею; детектор сигналу — будь-який крок від 128 |

Діагностика, що лишилась у коді: `PROBE_SESSION --ei wav <ms> [--ei asplayed 1]` — сирі блоки
Visualizer + зшитий потік у WAV (`files/capture_*`); `--ei dump 1` — по смугах NATIVE32 / POWER32 /
FLOOR32 / CURVE32.

### 📐 Виміряні факти (записані в `platform/05-AUDIO-PATH.md` §4-bis)
- Зшитий потік Visualizer **пласкій ±1.5 дБ** 18 Гц–14 кГц (незалежний Welch і метод аналізатора) —
  відвід, 8-біт і стібер НЕ винні.
- Visualizer на сесії 0 **доповідає 44 100 Гц, а мікшер працює на 48 000** (`AudioOut_D`); спектр
  48-кГц файлу рівний аж до «22 кГц» за шкалою 44.1 без антиаліасингового спаду → **ймовірно,
  частоти на екрані на 8.8 % нижчі за справжні**. ❓ НЕ доведено тоном.
- **AS_PLAYED абсолютний і не залежить від гучності:** `pink_48k.wav` −25.24 dBFS RMS → відвід
  −25.35 (гучність 6) і −25.33 (гучність 4); NORMALIZED −9.35. Ціна: 34 рівні з 256.
- Файли: `Music/EMMA 2018/12 Pink Noise.wav` — 44.1 кГц, −29.0 dBFS RMS, пік −10.9, власний спад
  −4 дБ на 20 Гц і −4.5 на 20 кГц (відносно середини). `Music/pink_48k.wav` — 48 кГц, −25.24 dBFS.
- Після всього, `pink_48k.wav`, пласкій пресет: 16 смуг −37.0…−37.9 dBFS від 315 Гц до 20 кГц
  (теорія −37.3), нижче 160 Гц +3 дБ моделі (двері Through + саб 0 — фізично).

### ▶️ НАСТУПНА СЕСІЯ — по порядку
1. 🔴 **Мікрофонна модель — привести до пуття** (наказ власника: «раз загальна змінилася»). Що вже
   відомо: мікрофонний режим = `pushPcm16` 48 кГц + крива компенсації мікрофона 16 смуг
   (`pref_mic_compensation` у власника **дика**: +29.9/+24.7/+18.7/+16.2/+8.9 дБ на 20–125 Гц,
   +10 на 20 кГц) розкладена на нову сітку (`setDspCurve`: непарні — точно, парні — середнє);
   головний у мікрофонному режимі примусово AGC; поріг шуму ввімкнений (`acoustic=true`). Звірити:
   чи крива компенсації правильна для «наявного», як вона будується на новій сітці, чи лишається
   двічі щось від DSP, як поводиться поріг, 44.1/48 для мікрофона не стосується (48 кГц точно).
2. 🔴 **Борг: `RESEARCH_RESULT_DYNAMIC_BASS.md`** (глибоке дослідження Дж щодо твердження власника й
   кривої Флетчера–Менсона). Висновки перевірити проти `platform/03-SOUND-PROCESSOR.md` і нашої
   тонкомпенсації: суперечить **компенсації саба** (подвійне підсилення, EQ до розгалуження);
   радить статичну криву за кроком гучності з затримкою ~800 мс; сумнівні місця — у `INDEX.md`.
3. ✅ **Тест тоном — зроблено 14.09 вечір, див. нижче «Тест тоном».** Тони на 918.5/9187 Гц →
   Visualizer бреше про 44.1, справжня 48 кГц; і стібер на тоні застигав повністю.
4. **Межа AGC віджета −30 dBFS** — стартова. Перший вимір на живому треку (`Дольче Габбана.mp3`,
   17:40, AS_PLAYED): найгучніша смуга в кадрі −8…−18 dBFS (медіана −13.5), сума 32 смуг −5…−12.
   Тобто межа на ~17 дБ нижча за звичайну гучну музику: тихе стискається лише нижче −30. Якщо
   власнику тихі місця все ще «на повну» — піднімати до −25…−20. Вирішує власник очима.
5. Налаштування аналізатора (у власника всі за замовчуванням): вирішити, чи прибрати AGC головного
   (інструмент мусить бути абсолютним) — власник хотів «не ускладнювати людям».
6. Дрібне: P2Bass не змодельовано; дві таблиці HPF у застосунку (`MainActivity.BASS_FILTER_FREQS`,
   `RoomMeasurement.BASS_FILTER_FREQS_HZ`) кажуть «20 Гц» замість Through.
7. Пуш — лише за наказом (все від `9c5ebdb` локально).
8. Гучність під час виміру AS_PLAYED: `SET_VOLUME debug 6 → 4 → 2`; після того власник поставив 16.
   Поточну гучність не брати із зрізу — читати з апарата.

### 🎯 Тест тоном (14.09.2026, вечір) — два баги, обидва виправлено й перевірено на апараті

Файл `Music/tone_1k_10k_48k.wav` (перевірено на ПК: 48 кГц, 60 с, 1000 + 10000 Гц, −20 dBFS) грав у
системному плеєрі й у Pulsar, пресет «wDSP Flat» (власник поставив, щоб прибрати пресет зі змінних;
пресет справді пласкій — усі `g=6`, HPF 0, loud off, fm сила 0).

1. **Частота Visualizer.** `getSamplingRate()` = 44 100 000 мГц, а тони в сирих блоках на 918.5 і
   9187.3 Гц → справжня 48 012 / 48 001; пропускна здатність на музиці 47 992 і 48 011 відліків/с;
   мікшер `AudioOut_D` 48 000. Уся шкала частот малювалася на 8.8 % нижче. Тепер одне джерело —
   `AudioSpectrumEngine.visualizerSampleRateHz()` (`PROPERTY_OUTPUT_SAMPLE_RATE`, запасне 48000).
2. **Стібер застигав на тоні.** Період тону — ціле число відліків, тож блок збігається з потоком при
   кожному зсуві на період, зокрема на нуль; стібер брав перший → **461 опитування з 461 без нових
   відліків**, аналізатор, віджет і заставка стояли на кадрі з моменту підключення (власник бачив «дві
   правильні смуги», що не рухались). Тепер серед зсувів, що збігаються **байт у байт**, береться
   найближчий до передбачення годинника (`System.nanoTime()` передається в `NativeAnalyzer.push`);
   кореляція — лише коли точного збігу немає. Виміряно: вікно Visualizer рухається цілими мс (усі 923
   зсуви кратні 48), годинник передбачає з похибкою ±131 відлік без накопичення.
   Хост-тест тепер моделює подачу апарата і перевіряє, що стібер забрав увесь потік: на старому коді
   1 кГц / 10 кГц / файл — **0.1 %**, на новому — 100 %; ALL PASS.
   Після установки: 263 опитування, 0 порожніх, тони 1000.00 / 10000.00 Гц, RMS −20.06 dBFS; **смуги
   падають на паузі** (перевірив власник).

⚠️ Моя помилка по дорозі: `PROBE_SESSION --ei dump 1 --ei wav …` дамп НЕ пише (гілки `if/else`), і я
раз порахував старий файл як новий. Дамп — лише без `dump`.

❓ **Відкрите питання власнику — шкала.** Смуги малюються на висоті сітки еквалайзера, висота =
`(dBFS + 60) / 60`: «0» сітки = −30 dBFS, одна поділка сітки = 2.5 дБ сигналу. Тон −23 dBFS на 1 кГц
(рівно між повзунками 800 і 1250 — ділиться навпіл) читається як «+2». Правильно за кодом, але для
«інструмента» дві шкали з різною ціною поділки вводять в оману.

### 🎤 Мікрофонна модель (14.09.2026, пізній вечір) — рішення власника, дослід, дві правки

**Рішення власника:**
- **(б):** мікрофонний спектр на PCM-джерелі вирівнювати по розрахунковому — зсув, за яким середина
  200–800 Гц мікрофона дорівнює середині розрахункового (та сама опора, що в калібруванні); Visualizer
  працює паралельно з мікрофоном. На радіо (PCM немає) — нормалізація, як зараз. **НЕ зроблено — наступне.**
- «Три умови "чи вхід акустичний" — не дрібне, це нестабільна поведінка» → `dd3e858`.
- Повернення мікрофон → розрахунковий 5 с: «плеєр не мінявся — канал зберігати» → `9a51886`
  (виміряно: 69 і 152 мс без свіпу; перше після старту 0.44 с — плеєр оголосив сесію).
- **Рут = прямий шлях до мікрофона в будь-якому разі** (пам'ять `root-is-the-direct-path-to-the-mic`):
  знайти, хто сидить на вході, зігнати, перевірити, що піднявся наш на повній смузі **і** що вигнаний
  піднявся знову. Код зараз лікує лише ВУЗЬКИЙ потік. **НЕ зроблено.**
- `com.txznet.weather` знесено за наказом (APK — у скретчпаді сесії, `removed_apps/`).

**🪤 Моя помилка:** перевстановив wDSP посеред досліду → асистент сів на вхід першим → на нашій сесії AEC+NS
активні (стан 003), мікрофонні заміри викинуто. Правило (пам'ять `mic-input-order-decides-retake-before-measuring`):
хто перший на вході — той його задає; перед виміром забрати мікрофон. **І друге:** я 20 хвилин
доводив із `dumpsys`, що ефекти діють і коли ми перші — тон 30 с тримав −15.0 дБ ±0, смуга між тонами
на 55 дБ нижче: **коли ми перші, ефекти на відліки НЕ діють**. Рядки ефектів у дампі ≠ обробка.

**⚠️ Знайдено по дорозі:** після ПЕРШОГО перемикання на розрахунковий мікрофон закривається, і при
поверненні ми вже не перші (асистент тримає вхід — `rec update` асистента о 21:17:00). Тобто кожне
повернення на мікрофон — стан 20:32. Або рут-правило при кожному поверненні, або **не віддавати мікрофон
у розрахунковому режимі** — ❓ питання власнику (пов'язане з (б): Visualizer і так працюватиме завжди).

**Дослід «мікрофон − розрахунковий = крива салону»** (стенд, Flat, розовий шум, гучність 8, кондиціонер
вимкнений, ми перші; розрахунковий — 20:31, мікрофон — 20:52, тиша — 20:54 з гавкотом пса, перераховано медіаною).
Компенсація мікрофона в порівнянні СКОРОЧУЄТЬСЯ — розходяться два МЕТОДИ виміру сирого мікрофона:
| смуги | мікрофон−розрахунковий мінус крива салону |
|---|---|
| 315 Гц – 1.25 кГц | **+0.4…+2.6 дБ — збіг** |
| 2–8 кГц | +3…+5 (живий яскравіший) |
| 12.5 / 20 кГц | **+11 / +19** при SNR 42 дБ; з 20 кГц **6.6 дБ — вада свіпу** (хост-тест на рівному тракті: смуга 20 кГц −6.6, свіп закінчується на 20 кГц) |
| 125–200 Гц | −5 |
| 20–80 Гц | −8…−50: живий мікрофон на 20 Гц чує лише шум (SNR −4…−1.5), свіп звідти щось дістає (свою «чисту» смугу не опускає нижче −12 дБ під сирою) — не доведено |

Наступний крок без нового свіпу: прогнати `room_measurement.wav` (лежить на апараті) офлайн методом свіпу і
методом аналізатора — хто не збігається з живим шумом, у того вада. Кривих не чіпати, доки не розсуджено.

**🔇 BitPerfect v5.3** — `pcmC0D3p` зайнятий живим HAL (другий потік, S24_LE MMAP, SETUP), YouTube Music
грав у нікуди; власник: модуль калічний, TTS теж беззвучний, рестарт audioserver лише ховає. Деталі —
`platform/05-AUDIO-PATH.md` «A second variant». Логер: шторм HAL за ~20 с витіснив усі 5 файлів ротації —
треба обмежити повтори `audio_hw_primary`.

**Черга далі:** (б) → рут-правило/утримання мікрофона (після відповіді власника) → офлайн-звірка
`room_measurement.wav` → борг `RESEARCH_RESULT_DYNAMIC_BASS.md` → шкала сітки EQ vs спектр. Коміти
`8d344f7`, `c920255`, `dd3e858`, `9a51886` — **не запушено**.

## 🔴🔴 14.09.2026, ~22:10 — ЗРІЗ: мікрофон тримаємо, (б) зроблено, наступне — замок автопресетів

⚠️ **Оболонка перезапускала сесію посеред роботи** (~21:40): попередній процес устиг закомітити
`d221ab2` і `29f1b18` і почав нативну частину (б) — у контексті нової сесії цього не було; звірено з git
(час файлів проти часу збірки) — розбіжностей немає. Урок: після будь-якого перезапуску — `git log` і
`git status`, перш ніж продовжувати.

### ✅ Зроблено й перевірено на апараті (усе через логер-модуль, `bootlog/0007…0009`)
| коміт | що | доказ |
|---|---|---|
| `d221ab2` | заставка знімається з 100-мс опитування сервісу в мить початку дзвінка (`ScreensaverManager.onCallInProgress` — одне тіло для тіку й опитування) | **не перевірено дзвінком** |
| `29f1b18` | **без рута мікрофон тримаємо від старту** (`decideMicrophonePolicy`, тік 5 с відкриває знову), перемикання режимів його не закриває; **з рутом** — відкриваємо, коли треба, а якщо на вході хтось був першим — `MicrophoneGuard.takeInputAsRoot` (хто — з доріжок AudioFlinger, не зі списку), чекаємо закриття входу, відкриваємося, перевіряємо повну смугу і `checkCameBackAsync` (вигнаний знову живий?). Під час дзвінка нічого не стартує | без рута: 5 перемикань мікрофон↔розрахунковий — жодного `rec start/stop` після старту; асистент підсідає до нас. **Гілку з рутом не перевірено** |
| `16e32e3` | натив: одне згортання `foldTo16Db`, `getLevelsDb16`, `setLevelOffsetDb` | хост-тест: 0.000 дБ |
| `7a58df0` | **(б)**: Visualizer працює завжди на PCM (у мікрофонному режимі — опора, HOP_IDLE), мікрофонний аналізатор поруч, один потік показу, `analyzerLock`; зсув = середина 200–800 Гц (смуги 5..8) треку − мікрофона, згладження 2 с; вирівняний мікрофон — без AGC (як розрахунковий), невирівняний (радіо/тиша) — AGC як раніше. Плюс **відступ резольвера** 4→8→16→…60 с | старт: мікрофон 36.5 с, аналіз «aligned … beside it» 36.9 с; свіпи 40.9/49.6/66.5/99.5/160.6 с; музика: `offset +2.1 dB` (трек −47.9 dBFS, мік −50.0) |
| `4fa7096` | підпис у дампі смуг: «mic aligned, offset …» замість «forced» | зібрано, **не встановлено** (на апараті `7a58df0`) |

### 🔎 Знахідки вечора (записано в пам'ять)
- **Логи — лише логер-модуль**, не `logcat` (власник двічі). Пам'ять `read-the-boot-logger-not-logcat`.
- **Рут у Magisk є (policy 2), а застосунок каже «no root»**: `pref_root_granted=false`, `hasRootNow()` не
  пробує `su`. Повернутий у Magisk рут не видно, доки не натиснути картку рута. Пам'ять
  `root-pref-not-refreshed-after-magisk-regrant`. ❓ власнику: пам'ятати «Magisk нас бачив» окремо.
- **`sys.qf.last_audio_src` липкий**: після ребуту з YouTube Music на паузі він його пам'ятає →
  `isMediaPlaybackActive()` = true → резольвер крутився кожні 4–5 с (у розрахунковому режимі, найімовірніше,
  і раніше). Тепер відступ; сам резольвер (скільки коштує свіп) не чіпано.
- Вирівнювання на стенді: мікрофон −50 дБ проти треку −47.9 dBFS на гучності, яку власник поставив.

### ▶️ Черга (по порядку, наказ власника)
1. 🔴 **Замок в акордеоні автопресетів («лише рут») — зняти**, раз мікрофон забираємо ребутом. Людям **без
   рута** чітко сказати: якщо вони вмикають мікрофон для свіпів, а мікрофон не наш, програма попросить ребут
   (тексти — 30 локалей; перевірка `tools/check_locales.py`).
2. Власник дивиться очима (б) на екрані; радіо в мікрофонному режимі; дзвінок у мікрофонному режимі
   (заставка має зникнути одразу, спектр — пауза).
3. Гілка з рутом: власник тисне картку рута → перемикання розрахунковий→мікрофон має дати в логері
   `input holders read from AudioFlinger`, `reopened the microphone`, `… is running again`.
4. Свіп із рутом, коли на вході хтось перший на 48 кГц — `MicrophoneGuard.ensureOurs` досі лікує лише вузький.
5. Офлайн-звірка `room_measurement.wav` методом свіпу і методом аналізатора (20 кГц −6.6 дБ — вада свіпу
   доведена; 12.5 кГц +11 і низ −8…−38 — ні).
6. Борг `RESEARCH_RESULT_DYNAMIC_BASS.md`. 7. Шкала сітки EQ vs спектр («0» = −30 dBFS, поділка 2.5 дБ) — і
   тепер питання, чи потрібні перемикачі нормалізації головного взагалі. 8. Логер: душити повтори
   `audio_hw_primary`. 9. Давні: межа AGC віджета, P2Bass, таблиці HPF «20 Гц»→Through, автовідновлення
   плеєра, метадані системних плеєрів у заставці (#539).

**Не запушено нічого від `8d344f7` до `4fa7096`** (12 комітів). Пуш — за наказом, з WSL від `user`.
На апараті: збірка `7a58df0`, boot 0009, режим спектра — мікрофон, застосунок вважає, що рута немає.

## 🔴 15.09.2026, ніч — замок автопресетів знято, рут переписано; ТЕСТИ В БОРГ (ніч, звуку немає)

| коміт | що |
|---|---|
| `b920941` | секція виміру салону відкрита без рута (замків було ДВА: акордеон і питання «чи дозволили рут» на «Старт» майстра); примітка `desc_room_no_root` без рута: мікрофон має бути наш, інакше програма попросить ребут; 30 локалей (ключі `room_root_title/message/yes/no/required_toast` знесено) |
| `3da5c49` | **рут — один клас, у пам'яті процесу**: `hasRoot()` без `su`; `su` лише `checkAtStart` (раз за процес і лише коли Magisk уже відповідав цьому встановленню — факт у `wdsp_device_state`), `checkForMicrophone` (перше вмикання мікрофона людиною: режим «мікрофон», вимір — перший промпт Magisk звідси), `request` (картка рута). Опитування на `onResume`/wake/відкритті майстра прибрано |

Рішення власника: рут у префі — погано; не опитувати (тост Magisk); одне джерело правди; перший промпт — від дії.

### 🧾 Борг тестів (на апараті, не вночі)
1. Встановити `3da5c49`: у логері при старті `wDSP_RootAccess: start: GRANTED` (на апараті легасі-преф є → міграція → перевірка), політика «root: … opened when wanted».
2. Тост Magisk — один на старт; повернення в Налаштування/головний екран тосту не дає.
3. Секція салону без рута відкрита, примітка видна; з рутом — схована.
4. Перемикання в «мікрофон» → `first microphone use` у логері; гілка з рутом: `input holders read from AudioFlinger`, `is running again`.
5. Старі: (б) очима; радіо в мікрофонному режимі; дзвінок (заставка зникає одразу).
### 📐 Офлайн-звірка `room_measurement.wav` (15.09, ніч) — пункт 5 черги
Запис 13.09 19:51 (той, з якого `pref_cabin_response`; відтворено з рядків звіту до 0.04 дБ), дампи розового шуму 14.09.
1. **Верх розсуджено → `b5951da`.** `avgClean` усереднював двері **в дБ**; мертвий твітер FL (−32.7/−50.5 дБ на
   12.5/20 кГц проти −5.5/−9.3 у FR) тягнув криву вниз. Середнє потужностей: промах живого шуму 8 кГц +3.9→+0.9,
   12.5 кГц +11.4→+1.4, 20 кГц +19.1→+2.1; 315 Гц–20 кГц у ±2.6 (крім 3150 Гц +3.6). Синтез теж більше не
   піднімає верх заради мертвого твітера. Пам'ять `cabin-average-in-power-not-db`.
2. **Гейт не винен:** без віднімання порогу промах на низу той самий.
3. **Метод аналізатора на самому записі свіпу** (енергія третинооктавних смуг, згортка як у натива, вікно
   з краями 50 мс; старти свіпів деконволюцією: FL 16.43 с, FR 23.93, саб 31.43) проти методу свіпу зі звіту:
   125 Гц–12.5 кГц збіг ±1.7 дБ; 20 кГц — аналізатор на 1.3–6.4 вище (відома вада верхньої смуги свіпу);
   **низ дверей — свіп ЗАВИЩУЄ**: 20 Гц +29…+35, 31.5 Гц +15…+16, 50 Гц +6…+7; **саб** на 31.5–80 Гц у −2…+2
   (20 Гц +15). Механізм не знайдено (вікно ІХ 16384, 64 відліки до приходу — кандидати).
4. **Живий шум на низу тихіший за запис свіпу тим самим мікрофоном**: ≈ −19 дБ на 31.5 Гц, −10 на 50, −4…−5 на
   80–125 — це **умови** (пресет «wDSP Flat» проти скретч-пресету, гучність 8 проти 16 і тонкомпенсація, тракт),
   а не метод. Офлайн не розділити → у борг тестів: той самий розовий шум на скретч-пресеті й гучності виміру.

