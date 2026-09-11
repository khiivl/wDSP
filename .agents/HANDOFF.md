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

⚠️ A dead watchman looks exactly like nobody writing. That has already cost this project two
invalid conclusions in a single day, so treat "the board is quiet" as a claim that needs evidence.

---

## The state of the tree

Version **0.4.8**, `versionCode 15`, branch `kostyfmat_mod`. The number was chosen 09.09.2026 by the
owner's instruction: the interface work of the Antigravity session had already announced itself as
0.4.8 in its release notes while the build file still said 0.4.7.7, and two different builds sharing
one version is a thing this project has already paid for once. `versionCode` moves with it, because
0.4.7.7 is on the test unit and in the release pack.

⚠️ Do not read the sentence above as "and it is pushed". Run `git log origin/kostyfmat_mod..HEAD`
before assuming the remote has what you are reading about — that is the only honest way to know,
and any claim written here goes stale the moment somebody commits.

The working tree carries two `.idea/` files and three untracked helpers from the localisation pass
— `translations.json`, `translate_instructions.txt`, `apply_and_sync.ps1`. They are the source data
for that pass — 27 locales × 16 keys, the 26 translated ones plus the hand-written `ru-rUA` — and
they are kept deliberately. They are not stray edits.

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

1. **Віднімання стаціонарного шуму клімату (HVAC Noise Subtraction) на льоту**:
   - *Суть*: Замір фонового шуму салону перед свіпом зберігається в буфері калібрування, але нативний C++ рушій FFT (`AudioSpectrumEngine`) не віднімає його з живого спектроаналізатора радіо. Водій бачить полицю шуму вентилятора на НЧ (40..160 Гц).
   - *Що потрібно*: Реалізувати спектральне віднімання (Spectral Subtraction) в нативному коді обробки БПФ.
2. **Фазування сабвуфера без фізичного мікрофона на вусі водія**:
   - *Суть*: Фазовий зсув вираховується геометрично за прямою хвилею ($D_{\text{listen}} / 34.3$). Це ідеально для вільного поля, але в салоні авто існують стоячі хвилі та кузовні резонанси (45..60 Гц), які можуть зміщувати акустичну фазу на $\pm 30^\circ$.
   - *Що потрібно*: У майбутньому додати акустичний імпульсний тест перевірки когерентності фази.
3. **Дві незалежні копії `activity_main.xml` (`layout/` та `layout-sw600dp/`)**:
   - *Суть*: Обидва файли містять однаковий набір View ID, але різну ієрархію контейнерів. Якщо змінити тип віджета або забути додати новий ID в одну з копій — додаток впаде з `ClassCastException` або NPE в `onCreate()`.
   - *Захист*: Перед будь-яким комітом розмітки обов'язково запускати скрипт валідації `tools/layout_diff.py`.

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


