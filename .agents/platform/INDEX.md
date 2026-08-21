# The QF / K706 platform — what is known

A head unit on this platform is two computers. Android runs on a Unisoc SoC; everything physical —
tuner, sound processor, amplifier, ignition, buttons — belongs to a separate microcontroller that
Android can only talk to over a serial line. Almost every surprising thing about working here
follows from that split.

These notes were gathered across two applications and several months, and **everything in them was
measured on real hardware or read out of the firmware**, because on this platform the
documentation, the framework's own source and the actual behaviour disagree often enough that only
the wire can be trusted.

## Provenance marks

Used throughout, and they are not decoration:

```
🔬 read in firmware or decompiled code (address, or file and line)
📻 measured on the wire
🧩 inference drawn from those
❓ guess, not verified
```

A guess written down as a fact has cost this project a day more than once. If you add to these
files, mark what you add.

## The files

| file | read it when |
|---|---|
| [01-SYSTEM.md](01-SYSTEM.md) | anything about the Android side: hardware, screens, properties, hidden API, release builds, sleep |
| [02-MCU.md](02-MCU.md) | talking to the microcontroller: framing, the command map, send discipline, volume |
| [03-SOUND-PROCESSOR.md](03-SOUND-PROCESSOR.md) | equaliser, delays, crossovers, subwoofer — the ROHM BU32107 register map and what each command becomes |
| [04-FIRMWARE-PATCHING.md](04-FIRMWARE-PATCHING.md) | inside the MCU image: memory map, the dispatcher, the settings structure, and what changing it would take |
| [05-AUDIO-PATH.md](05-AUDIO-PATH.md) | recording, playback, latency, the microphone, audio policies, the player role |
| [08-VOLUME-AND-SOURCES.md](08-VOLUME-AND-SOURCES.md) | anything that changes how loud something is: the per-source volume model, source switching, the optional second DSP, and the vendor Bluetooth app breaking the radio |
| [06-TUNER.md](06-TUNER.md) | the radio side — mostly relevant to other projects, but several MCU facts live here |
| [07-PRACTICE.md](07-PRACTICE.md) | how to work here without wasting runs: adb traps, testing discipline, what a reboot really resets |

Application-specific design lives outside this folder — for wDSP that is
[../ROOM_CALIBRATION.md](../ROOM_CALIBRATION.md).

## The nine things most likely to waste a day

1. **`Visualizer(0)` measures silence.** The platform's audio policy puts media on the *fast*
   output while the *primary* one sits idle, and AOSP hard-codes an output-mix effect onto primary.
   Attach to the track's session instead. (05 §1)
2. **The numbers Android reports about latency are wrong here by a factor of three.** Measure.
   (05 §3)
3. **An assistant holds the microphone open at 16 kHz and nothing in the API says so.** Half of
   every recording is missing, and `getSampleRate()` reports what you asked for either way. (05 §2)
4. **The volume you read is not the volume you wrote.** It lags, and under music it can differ
   permanently. Keep "sent" and "reported" apart or an algorithm will read its own echo as a
   person. (02 §7)
5. **Rebooting Android does not reset the MCU.** The tuner keeps playing, buffers keep their
   contents. Only power does. (07 §4)
6. **R8 silently deletes hidden-API calls.** Debug works, signed release does nothing.
   (01 §5)
7. **`am broadcast` without an explicit receiver goes nowhere and reports success.** (07 §2)
8. **Volume is per source, not per Android stream, and the live values are throwaway properties.**
   An unset `sys.radio.vol` reads back as `persist.sys.radio_volume` on every call — that *is* the
   "volume reset itself" bug, there is no reset code. And a volume only reaches the hardware when
   its source is the current one. (08 §1)
9. **Half the fleet has a second DSP and half does not, and one character decides which.** The
   platform reads the second character of the MCU code: 2 → AK7738, 3 → AK7604, anything else →
   none. Units with one re-push the master volume on every source change; units without one never
   do. Check it before reproducing, explaining or promising anything about volume. (08 §3)

## Diagnostics available at runtime in wDSP

None of these run unless asked for; each logs under its own tag.

```bash
adb shell am broadcast -a com.radiorubka.wdsp.PROBE_SESSION  --ei sid -1      # which session carries audio
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_LATENCY --ei mic 1      # picture-to-sound delay
adb shell am broadcast -a com.radiorubka.wdsp.PROBE_MIC       --ei src 6      # what the microphone really delivers
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM    --ef amp 0.25   # sweep every speaker
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM    --ei same 1     # same routing four times: checks the instrument
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM    --ei delaytest 1  # positional delay line
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM    --ei delaytest 2  # surround delay line
adb shell am broadcast -a com.radiorubka.wdsp.SET_VOLUME      --ei vol 12     # move the volume the way a person does
adb shell am broadcast -a com.radiorubka.wdsp.SIMULATE_SPEED  --ef speed 110  # pretend to be driving
```

`MEASURE_ROOM` leaves its recordings and a readable report in the app's external files directory,
so a measurement made in somebody else's car can be examined rather than described. Settings has a
button that zips the lot into the system share sheet.
