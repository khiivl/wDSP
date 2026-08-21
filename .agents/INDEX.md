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
| [ROOM_CALIBRATION.md](ROOM_CALIBRATION.md) | the cabin measurement: sweep, deconvolution, what it can and cannot honestly tell a user, and where it is going |

Read [../CLAUDE.md](../CLAUDE.md) first for how the app is put together.

## Host tests

```bash
cd wdsp_app/src/main/cpp
g++ -O2 -std=c++17 -o /tmp/t_analyzer test_analyzer.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_analyzer
g++ -O2 -std=c++17 -o /tmp/t_sweep    test_sweep.cpp    sweep.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_sweep
```

They are deliberately not part of the library. They exist because a wrong answer from an audio
measurement looks exactly as plausible as a right one, and in a car there is nothing to check it
against.
