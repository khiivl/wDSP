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
| on the test unit 192.168.1.146 | **0.4.8 / `versionCode 15`** | **`versionCode 90`** |
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

### What has actually been handed out

**Nothing since 0.4.7.2.** Packs sit in `~/Downloads/`: 0.4.7.1 and 0.4.7.2 (zipped, distributed),
0.4.7.5 (built, never handed out), 0.4.8 (staged — APK and the two cabin documents only; the
READMEs are deliberately unwritten while the owner formulates one condition for the release).
Measurements from testers go to <https://t.me/wDSPapp/79> or a forum PM, **never** a direct
message: the owner's standing instruction, because lone files in DMs get lost.

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

0. 🔴 **Close the race between the platform reset and the radio announcement.** When a source
   switch makes the platform wipe both levels to `persist.sys.main_volume`, this side writes the
   level back on the next poll (100 ms). But if the radio announces the wiped level first —
   delivery between applications was measured at 191 ms, so this is unlikely rather than
   impossible — `onAudioStateStable` adopts it as the new base and the level is genuinely lost.
   The fix is one condition, and the machinery is already there: refuse a base that equals
   `persist.sys.main_volume` when `System.currentTimeMillis() - lastSourceChangeMs` is under a
   second. ⚠️ It matters most to the people who will never update their radio, and the testers'
   radio (`versionCode 83`) is exactly that population — confirmed from the radio's git rather
   than from memory: `versionCode <= 83` is precisely the class whose sync switch defaulted to
   *on*, the flip landed in `414b79a` on 07.09 at 17:30, and the tester release was built on 06.09
   at 08:10, a day and a half earlier. 🪤 Worse for this race specifically: 83 announces the level
   it read from the `VOLUME_CHANGED` extra rather than the live one, so what it announces after a
   platform wipe **is** the wiped number. On the stand (radio 90) a late announcement is harmless
   because the level it carries is the restored one; in the field on 83 it is poison if it wins. Deliberately not done on 09.09: the 0.4.8
   build was already pushed and installed, and editing the volume handler an hour before a release
   is the class of change this project has been burned by.
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
