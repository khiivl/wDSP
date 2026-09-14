# .agents — what was learned

Two kinds of thing live here, and they are kept apart on purpose.

## Platform knowledge → [platform/](platform/INDEX.md)

Everything about the machine itself: the Android side, the microcontroller, the sound processor,
the audio path, the tuner, and how to work here without wasting runs. It applies to any application
on this hardware, not just this one, and it was gathered across two of them.

**Start at [platform/INDEX.md](platform/INDEX.md).** It opens with the seven things most likely to
cost you a day.

Everything in those files carries a provenance mark — 🔬 read in firmware, 📻 measured on the wire,
🧩 inferred, ❓ unverified. If you add to them, mark what you add. On this platform the
documentation and the behaviour disagree often enough that an unmarked claim is not usable.

## This application's own design

| file | what |
|---|---|
| [HANDOFF.md](HANDOFF.md) | **read first after a compaction, and first of all on a new machine** — opens with a cold-start section written for a session that has none of this history: which build is where, what is deliberate and must not be "fixed", which of our own conclusions were wrong. Then — where things stand, what is uncommitted, what is open, and the rules that cost something when forgotten |
| [BUILDING.md](BUILDING.md) | how a fresh clone builds itself, what is committed on purpose, and why R8 must stay off |
| [ROOM_CALIBRATION.md](ROOM_CALIBRATION.md) | the cabin measurement: sweep, deconvolution, what it can and cannot honestly tell a user, and where it is going |
| [RESEARCH_REQUEST_DYNAMIC_BASS.md](RESEARCH_REQUEST_DYNAMIC_BASS.md) | 🔬 research request (14.09.2026): should bass compensation and the midbass high-pass track listening level, expressed in the hardware's own coarse steps |
| [RESEARCH_RESULT_DYNAMIC_BASS.md](RESEARCH_RESULT_DYNAMIC_BASS.md) | 📥 the answer, from the Gemini deep research (delivered 14.09.2026, **checked 15.09.2026** - verdict table at the end of the file): the owner's "lazy midbass at low power" is not supported - the loss is hearing (ISO 226); a static loudness curve by volume step, EQ only, applied after a debounce; HPF and subwoofer gain static; bass added through the subwoofer gain double-counts because the EQ is before the split (contradicts our sub compensation). Weak spots: the "MEMS mic cut at 70-150 Hz", CAL_STEP 24, "2 commands a second", several unrelated sources. Debt - see HANDOFF |
| [RESEARCH_BU32107_SUB_SOURCE.md](RESEARCH_BU32107_SUB_SOURCE.md) | 🔬 Gemini reverse (15.09.2026), checked by Claude: the subwoofer crossover input `0206[7:6]` is `00` = Time Alignment (datasheet reset value; register index verified in `mcu.bin`) - so the sub is fed after the EQ and "Sub compensation" double-boosts. ⚠️ its UART command table is wrong - see the check at the end |
| [SCREENSAVER_RADIO_CONTRACT.md](SCREENSAVER_RADIO_CONTRACT.md) | screensaver lifecycle, Radio MCU integration, MediaSession metadata sync, clock 10 FPS breathing, and overlay layering (✍️ Antigravity 25.08.2026) |
| [SCREEN_MATRIX.md](SCREEN_MATRIX.md) | ЕКРАНИ ПЛАТФОРМИ QF — заводська матриця 132 панелей («屏参描述对照表»), реальні UI-геометрії в dp, поведінка статусбару QF та правила адаптації розмітки |
| [AUDIO_OWNERSHIP_CONTRACT.md](AUDIO_OWNERSHIP_CONTRACT.md) | who owns the volume, the channel and the EQ when radio and wDSP share one MCU path — the signal that ends the race, and why `volume` in it is advisory. **Agreed 26.08.2026; both sides implemented and jointly tested 26–31.08, wDSP's half committed** |

Read [../CLAUDE.md](../CLAUDE.md) first for how the app is put together.

## Agreements with other applications → `C:\APPS_Contacts\`

📌 **Anything agreed with another application lives there, not here.** The folder was created by
the owner on 07.09.2026 to end a choice that had gone wrong both ways: a contract kept by one side
only is read by the other from memory, and a contract kept by both drifts within a day.

- canonical text: `C:\APPS_Contacts\<pair>\` — for this pair, `wDSP--QFRadio\`;
- **a ledger with a mark from each side**: an item counts as closed only when wDSP and the other
  application have both marked it, and a mark names its evidence — a commit, a measurement, a line
  in a log — rather than an intention;
- each side edits **its own column** and nobody else's;
- the two contract files in this folder are **mirrors**. Edit the canonical copy, then copy across;
  never the other way round.

Rules of the folder: `C:\APPS_Contacts\README.md`. It also carries how the two sessions reach each
other directly, because the shared board delivers Claude→Claude unreliably.

## Host tests

```bash
cd wdsp_app/src/main/cpp
g++ -O2 -std=c++17 -o /tmp/t_analyzer test_analyzer.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_analyzer
g++ -O2 -std=c++17 -o /tmp/t_sweep    test_sweep.cpp    sweep.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_sweep
```

They are deliberately not part of the library. They exist because a wrong answer from an audio
measurement looks exactly as plausible as a right one, and in a car there is nothing to check it
against.
