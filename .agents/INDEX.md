# .agents — what was learned about this platform

These notes are for whoever, or whatever, works on wDSP next. Everything in them was measured on a
real head unit rather than read from documentation, because on this platform documentation and
behaviour disagree often enough that only the wire can be trusted.

Read `../CLAUDE.md` first for how the app is put together. These files are about the platform
underneath it.

| file | read it when |
|---|---|
| [PLATFORM_AUDIO.md](PLATFORM_AUDIO.md) | touching the analyser, the visualiser, latency, or anything that records or plays for measurement |
| [HARDWARE.md](HARDWARE.md) | touching the MCU, the equaliser frames, the volume, or the preferences that carry them |
| [ROOM_CALIBRATION.md](ROOM_CALIBRATION.md) | working on automatic equalisation, delays or anything involving the microphone as an instrument |

## The three things most likely to waste a day

1. **`Visualizer(0)` measures silence on factory audio policies** — the primary output is idle and
   the music is on the fast one. `SessionResolver` exists for this; do not simplify it away.
   (PLATFORM_AUDIO §1)
2. **The volume you read is not the volume you wrote** — it lags, and under music it may differ
   permanently. Keep "what I sent" and "what was reported" apart, or an algorithm will read its own
   echo as the user. (HARDWARE §4)
3. **Numbers Android declares about latency are wrong here by a factor of seven.** Measure with
   `LatencyProbe` instead of trusting `getOutputLatency()`. (PLATFORM_AUDIO §3)
4. **An assistant holds the microphone at 16 kHz and nothing in the API says so.** Half of every
   recording is missing. Listen for it rather than asking. (PLATFORM_AUDIO §2)

## Diagnostics available at runtime

None of these run unless asked for. All log under their own tag.

```bash
adb shell am broadcast -a com.radiorubka.wdsp.PROBE_SESSION --ei sid -1     # which session carries audio
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_LATENCY --ei mic 1    # picture-to-sound delay
adb shell am broadcast -a com.radiorubka.wdsp.PROBE_MIC --ei src 6          # what the microphone really delivers
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM --ef amp 0.25    # sweep every speaker, one at a time
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM --ei same 1      # same routing four times: checks the instrument, not the car
adb shell am broadcast -a com.radiorubka.wdsp.SET_VOLUME --ei vol 12        # move the volume the way a person does
adb shell am broadcast -a com.radiorubka.wdsp.SIMULATE_SPEED --ef speed 110 # pretend to be driving
```

`MEASURE_ROOM` leaves its recordings and a readable report in the app's external files directory,
so a measurement made in somebody else's car can be sent back and examined rather than described.

## Host tests

Two C++ harnesses build with plain `g++` and are deliberately not part of the library. They exist
because a wrong answer from an audio measurement looks exactly as plausible as a right one, and on
a car there is nothing to check it against. On a synthetic signal there is.

```bash
cd wdsp_app/src/main/cpp
g++ -O2 -std=c++17 -o /tmp/t_analyzer test_analyzer.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_analyzer
g++ -O2 -std=c++17 -o /tmp/t_sweep test_sweep.cpp sweep.cpp analyzer.cpp fft.cpp stitcher.cpp && /tmp/t_sweep
```

Between them they have caught: third-octave band centres a semitone out, an arrival detector biased
by a constant 202 samples, an inverse filter with its envelope upside down, and a band-power rule
that added 6 dB per octave of pure bookkeeping to every response.
