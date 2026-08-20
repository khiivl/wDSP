# Calibrating a car with a microphone nobody calibrated

## Where this is going

The destination is a head unit that sets itself up: measure the microphone, then measure the car
through it, then set the sixteen bands to a chosen target — flat, Harman, or whatever the owner
likes — and go on to the crossovers, the subwoofer's corner frequency and its level. Everything the
owner now sets by ear and by argument.

Delays and polarity are step one, not the destination. They come first because they are exact
without any calibration at all, so they can ship while the harder half is still being worked out —
not because they are all that is wanted.

The obstacle for the harder half is that the only microphone available is either the one built into
the head unit or a cheap electret on a cable, and nobody knows the response of either. Calibrating
the microphone is not a goal in itself; it is what stands between a measurement and an automatic
equaliser that can be trusted.

This file says which parts are reachable today, which are not, and why — so that the line between
the two does not have to be rediscovered.

---

## 1. The part that is exact, and needs no calibration at all

A microphone with an unknown *level* response still has a perfectly good sense of *time*. A capsule
that is six decibels down at 4 kHz still hears an arrival at the same instant, and still reports
whether the first movement was outwards or inwards.

So these are exact, whatever microphone is used:

| what | how | why the microphone does not matter |
|---|---|---|
| **delays between the four speakers** | peak of the impulse response, per channel | it is a time, not a level |
| **subwoofer polarity** | sign of the first peak | it is a sign, not a level |
| **left/right matching** | the ratio of the two channels' responses | the microphone's error is the same in both and cancels |
| **narrow room modes** | sharp peaks with Q > 3 below 200 Hz | microphones have no such narrow resonances down there |

`RoomMeasurement` implements the first two today. The other two are the obvious next step and need
no new theory.

**This is why the delays are done first.** They are the most useful thing the app can offer that is
also completely defensible.

---

## 2. The part that is not reachable, and the reason

Write the measurement in decibels and it is a sum:

```
log|measured| = log|loudspeaker| + log|cabin| + log|microphone|
```

Multiply the microphone by any smooth tilt `A(f)` and divide the loudspeakers by the same `A(f)`,
and **every measurement stays identical, to the decibel**. No amount of cleverness inside the
signal can separate "a microphone that is dull on top" from "loudspeakers that are dull on top".
This is not a limitation of a particular algorithm; it is a property of the problem.

Consequence: if the equaliser is set to flatten what the microphone reports, the microphone's own
error is inverted straight into the sound. A capsule with a +8 dB port resonance at 4 kHz makes the
algorithm cut 4 kHz by 8 dB, and the car ends up genuinely dull there.

### The trap nobody expects: it is the hole, not the capsule

The silicon in a MEMS microphone is remarkably consistent — photolithography gives better than
±0.5 dB between units, and the bare response is nearly flat from 30 Hz to 6-8 kHz.

What ruins it is the **acoustic port**: a 1-1.5 mm hole through 2-4 mm of plastic, with a parasitic
cavity behind it. That is a Helmholtz resonator, and on these head units it lands squarely in the
audible range — **a +4 to +10 dB hump somewhere between 3.5 and 6 kHz**, then a steep fall above
8-10 kHz.

The useful half of that: **the port is the same on every unit of the same model**, so one careful
measurement of one faceplate gives a correction curve valid for all of them. The unhelpful half:
with an external microphone on a cable, which is what most people actually use, every user has a
different capsule and no such shortcut exists.

---

## 3. What partly rescues it

**An external microphone can be moved, and that is worth more than it sounds.** Interference nulls
from reflections shift by many decibels when the microphone moves a few centimetres; the
microphone's own resonance does not move at all. Average several positions around the listener's
head in the log domain and what survives unchanged is the microphone (plus the anchor below). The
built-in microphone cannot do this — it is bolted to the fascia.

**The bass end can be anchored by physics.** Below the first cabin mode — around 50-80 Hz, since
the wavelength then exceeds the length of the car — the cabin stops behaving like a room and starts
behaving like a pressure vessel. Pressure must rise towards low frequencies, roughly 12 dB per
octave in the ideal sealed case and clearly in practice. If a measurement shows the bass *flat or
falling* instead, that deficit is not the car: it is a high-pass filter in the microphone, and
cheap electret capsules have one, usually between 50 and 100 Hz.

**A phone is a decent proxy reference.** Flagship phones have laser-trimmed MEMS arrays and are
typically within ±1.5 dB of a real measurement microphone between 50 Hz and 15 kHz. Record the same
sweep with both at the same spot and the cabin cancels in the ratio, leaving the head unit
microphone's own curve.

⚠️ It cancels *exactly* only if both microphones occupy the same point, and they cannot. At 8 kHz a
wavelength is four centimetres, so a three-centimetre offset is worth several decibels. Third-octave
smoothing and moving both together over a small volume bring it down; realistically expect ±3 dB at
the top rather than the ±1.5 dB the method promises.

---

## 4. The rules any automatic equaliser here must follow

1. **Cut freely, boost sparingly.** Never more than about +4 dB, or the amplifier and the tweeters
   pay for it. Most of what a car needs is attenuation anyway.
2. **Ignore deep narrow notches.** They are interference between a direct sound and a reflection.
   No equaliser can fill them — the cancellation simply happens at a higher level — and trying
   wastes amplifier power.
3. **Aim at a target curve, not at flat.** A car measured flat sounds thin and sharp. What people
   call correct is a gentle downward tilt, roughly −0.8 dB per octave above 1 kHz. Offer Flat /
   Harman / Warm rather than a single "correct".
4. **Do not chase precision the hardware cannot use.** Sixteen fixed third-octave bands, 2 dB steps,
   ±12 dB. Anything finer than about ±1.5 dB is arithmetic, not sound.
5. **Flatten the DSP before measuring.** The delay lines especially: they exist to correct the very
   distances being measured, so leaving them on measures the correction rather than the problem.

---

## 5. What the measurement itself looks like

An **exponential sine sweep**, not pink noise. It spends equal time in every octave, so the bottom
of the range — where one cycle lasts fifty milliseconds — gets as much signal as the top.
Deconvolving against its inverse filter collapses the recording into an impulse response, and the
loudspeaker's harmonic distortion lands at *negative* times, ahead of the impulse, where it is
discarded rather than measured.

Implemented in `cpp/sweep.cpp`, driven by `RoomMeasurement`, proven by `cpp/test_sweep.cpp` on
synthetic signals whose answers are known in advance:

```
arrival        exact to the sample, even at 12 dB signal-to-noise
polarity       correct both ways round
flatness       0.5 dB across the bands from 50 Hz to 12.5 kHz
known filter   one pole at 1 kHz measures as 10.1 dB over two octaves (theory 12)
```

That host test earned its keep immediately: it caught an arrival detector that was walking into the
pre-ringing of a band-limited impulse (a constant 202 samples early), and an inverse filter whose
envelope was upside down — which tilted every measurement by 6 dB per octave, and would have looked
exactly like a car with no treble.

### The instrument has been checked against itself

There is a diagnostic that plays all four sweeps through the same routing, so the acoustics are
held identical and anything still differing between the four windows belongs to the measurement:

```bash
adb shell am broadcast -a com.radiorubka.wdsp.MEASURE_ROOM --ei same 1
```

Measured on a bench: `997.50, 997.48, 997.46, 997.44 ms` - **one sample between consecutive
windows**, three samples of drift over ten and a half seconds, about six parts per million. The
single-pass design holds, and a difference between channels can be believed to be acoustic.

🪤 Use this before blaming the code. An earlier hypothesis - that the recording and playback clocks
were drifting apart and faking a 1.3 ms difference - was wrong, and this test is what disproved it
in one run.

### What the instrument cannot fix: a loudspeaker the microphone cannot see

The same bench measured its two front speakers 1.3 ms apart, repeatably to the sample, where a
tape measure said 0.47 ms *the other way*. The instrument was not at fault. The left speaker had a
shelf 30-40 cm to one side and the head unit body between it and the microphone, and its clarity
came out at 10-12 dB against 23-33 dB for the right one: the microphone was hearing it mostly
through reflections, and the loudest moment of a reflected arrival is not the direct sound.

Every threshold from -30 dB to -6 dB below the peak was tried offline against that recording. None
recovered the tape measure, because there was no earlier arrival to find - the response rose
gradually rather than starting with a direct impulse.

➡️ **Clarity is the honest guard here, not a cleverer arrival detector.** Below about 15 dB, treat
a delay as measured but unproven, whatever its repeatability. Repeatable and true are different
claims, and this bench produces the first without the second.

### Where the microphone is decides what the measurement is worth

This is the single biggest variable, and it is not under the app's control.

The standard place to fit a hands-free microphone is the **A-pillar beside the windscreen**, on
the driver's side: there is room for it and the cable run is easy. Left pillar on a left-hand-drive
car, right pillar on a right-hand-drive one. From there the near-side loudspeaker is heard directly
and the far-side one largely through reflections - which is exactly the asymmetry the bench showed,
with 23-33 dB of clarity on one channel and 10-12 dB on the other.

**So a lopsided result is the normal case, not a faulty installation.** Waiting for "better" cars
to arrive would be waiting for something that mostly does not exist.

The installations that give a clean measurement are the ones with the microphone in the **front
courtesy light**, roughly on the centre line, where all four loudspeakers are heard on comparable
terms.

➡️ Two consequences worth acting on:

- **Ask where the microphone is.** A result read without knowing that is a result read wrong. Every
  report should carry it, even if it arrives as a sentence in a chat message.
- **Say what a pillar microphone can and cannot deliver.** The near side and the polarity of every
  channel are sound. The far-side delays are measured through the room and should be offered as a
  starting point rather than an answer.

### Two things to be careful of in the field

- **A channel that was never driven still produces an impulse response** — of the room noise — and
  its loudest moment still looks like an arrival. `RoomMeasurement` requires both a prominence above
  200 and a recorded peak above −40 dBFS, and refuses a set of arrivals spanning more than 30 ms,
  because sound covers a third of a metre per millisecond and no car is ten metres long.
- **Which end of the fader is "front" is assumed, not confirmed.** The arrival times and their
  differences are right either way; only the labels could be mirrored. One test in a real car
  settles it.

---

## 6. Honest summary

| task | built-in microphone | external, moved | with a phone as reference |
|---|---|---|---|
| delays between speakers | 🟢 exact | 🟢 exact | 🟢 exact |
| subwoofer polarity and phase | 🟢 exact | 🟢 exact | 🟢 exact |
| left/right matching | 🟢 exact | 🟢 exact | 🟢 exact |
| narrow bass modes | 🟢 reliable | 🟢 reliable | 🟢 reliable |
| subwoofer corner frequency | 🟡 the crossing point is a shape, and shapes survive a tilt | 🟡 | 🟢 |
| subwoofer level against the mains | 🔴 a level, so the microphone's error goes straight in | 🟡 | 🟢 ±3 dB |
| **overall tonal balance, target curves** | 🔴 impossible without a stored profile | 🟡 partly | 🟡 ±3 dB |

The first four are worth shipping on their own, and none of them needs the argument in §2 to be
settled first. Everything below that line waits on the microphone being known.

### The order the rest should come in

1. **Left/right matching and narrow bass modes.** Differences and shapes, so the microphone
   cancels. No new theory needed, only the analysis.
2. **Crossover points.** Where the subwoofer and the mains cross is a shape in the response, and a
   smooth tilt on the microphone moves a crossing point far less than it moves a level.
3. **Subwoofer level, then the full target curve.** These are levels, and they need the microphone
   itself to be known — by a stored profile for the built-in one, or by a phone for an external
   one. This is the part §2 and §3 are about.
