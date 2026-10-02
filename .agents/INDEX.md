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
| [AGENTS.md](AGENTS.md) | **read first** — the rules in force: what this project is, how it builds, how it is verified on the unit, the prohibitions, how to work with the owner, and the dependencies |
| [HANDOFF.md](HANDOFF.md) | one current snapshot, overwritten each session: where things stand, what is in flight with its exact next step, questions for the owner. No history — that is in git |
| [TODO.md](TODO.md) · [DEBT.md](DEBT.md) | the queue, and the defects that are still open (symptom, where in the code, why deferred). In Ukrainian: the owner reads them |
| [DECISIONS.md](DECISIONS.md) | why each rule in AGENTS.md exists — the episode that produced it and what it cost |
| [ARCHITECTURE.md](ARCHITECTURE.md) | how the app is put together: the SharedPreferences bus, `McuService`, the 100 ms loop, broadcasts, the measurement probes, who holds the microphone, the UI, the native analyser |
| [CABIN_MODEL.md](CABIN_MODEL.md) | 🔴 **the canon of the cabin/microphone model and of every kind of "bass compensation"** (02.10.2026): the goal (a list of acoustic defects plus a coarse correction within Q 2.2 / 2 dB / one EQ), the parameters (speaker layout, cabin size, closed or open space, the microphone), the physics, how the microphone is separated from the cabin, and the verdict on every claim of the two research documents. Read this, not the sources in `research/` |
| [CALIBRATION_HISTORY.md](CALIBRATION_HISTORY.md) | 🔴 how the microphone maths got here — it has been redone three times and each attempt gave a different answer: what was tried, which commits hold it, why each turn was made, and why the profile came out flat, and the owner's decision of 02.10. |
| [MEASURED_FACTS.md](MEASURED_FACTS.md) | numbers taken on the unit and marked "do not measure again" — the capture chain, the platform around us, and one git trap |
| [BUILDING.md](BUILDING.md) | how a fresh clone builds itself, what is committed on purpose, and why R8 must stay off |
| [ROOM_CALIBRATION.md](ROOM_CALIBRATION.md) | the measurement pipeline: sweep, deconvolution, capture, field cautions, delays, crossover and sub handling. The model and the microphone moved to `CABIN_MODEL.md` |
| [research/](research/) | 📦 digested sources, kept verbatim — **do not re-read, the canon is `CABIN_MODEL.md`**: `blind-calibration-reference.md` (the owner's blind-calibration document), `RESEARCH_REQUEST_DYNAMIC_BASS.md` + `RESEARCH_RESULT_DYNAMIC_BASS.md` (level-dependent bass, 14.09, with our check of 15.09) |
| [RESEARCH_BU32107_SUB_SOURCE.md](RESEARCH_BU32107_SUB_SOURCE.md) | 🔬 Gemini reverse (15.09.2026), checked by Claude: the subwoofer crossover input `0206[7:6]` is `00` = Time Alignment (datasheet reset value; register index verified in `mcu.bin`) - so the sub is fed after the EQ and "Sub compensation" double-boosts. ⚠️ its UART command table is wrong - see the check at the end |
| [RESEARCH_SLEEP_WHITELIST.md](RESEARCH_SLEEP_WHITELIST.md) | 🔬 Gemini (15.09.2026), checked on the unit: an ordinary app cannot read `/great/sleep/sleep_whitelist` (system 0600, dir 0700, no service exposes it); the screen that edits it is `com.qf.carsettings/.activity.FactorySleepWhiteListActivity` (VIEW filter, exported) - wDSP opens it for the person, never writes the list |
| [SCREENSAVER_RADIO_CONTRACT.md](SCREENSAVER_RADIO_CONTRACT.md) | screensaver lifecycle, Radio MCU integration, MediaSession metadata sync, clock 10 FPS breathing, and overlay layering (✍️ Antigravity 25.08.2026) |
| [SCREEN_MATRIX.md](SCREEN_MATRIX.md) | ЕКРАНИ ПЛАТФОРМИ QF — заводська матриця 132 панелей («屏参描述对照表»), реальні UI-геометрії в dp, поведінка статусбару QF та правила адаптації розмітки |
| [BITPERFECT_MODULE_CONTRACT.md](BITPERFECT_MODULE_CONTRACT.md) | who answers for the module that rewrites the audio path, and who for the application that measures it — the handover of 29.09.2026, the facts the incoming session should not re-derive, and the rule that a claim about the module is confirmed on the wire and never by wDSP's own analyser. **Mirror**; the canon is `C:\APPS_Contacts\wDSP--BitPerfect\` |
| [AUDIO_OWNERSHIP_CONTRACT.md](AUDIO_OWNERSHIP_CONTRACT.md) | who owns the volume, the channel and the EQ when radio and wDSP share one MCU path — the signal that ends the race, and why `volume` in it is advisory. **Agreed 26.08.2026; both sides implemented and jointly tested 26–31.08, wDSP's half committed** |

The standard automotive developer-support component (`ShimmerBadgeLayout` + `SupportDialog`) is documented in the
`automotive-support-badge` skill, not here.

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

## Audio mixing, volume and BitPerfect - the day's research

[RESEARCH_AUDIO_MIXING_AND_BITPERFECT.md](RESEARCH_AUDIO_MIXING_AND_BITPERFECT.md) (20.09.2026): what the platform
actually does while a navigation prompt speaks (a ladder off the default volume; ratio 90 or more means no ducking at
all), why the same fault is reported as prompts both too loud and too quiet, what BitPerfect's volume keeper cancels,
why v5.3 hangs the boot on a UIS8581 unit, and what is still missing - proof, options and open questions in one place.

## Host tests

```bash
cd wdsp_app/src/main/cpp
g++ -O2 -std=c++17 -o /tmp/t_analyzer test_analyzer.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_analyzer
g++ -O2 -std=c++17 -o /tmp/t_sweep    test_sweep.cpp    sweep.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_sweep
```

They are deliberately not part of the library. They exist because a wrong answer from an audio
measurement looks exactly as plausible as a right one, and in a car there is nothing to check it
against.
