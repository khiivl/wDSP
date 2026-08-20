# The audio path on QF head units, as measured

Everything here was measured on a K706 (Unisoc UIS7862, SC2730 codec) with a wire attached, not
read from documentation. Where a figure is quoted, it came off that unit. Where something is
believed but unproven, it says so.

The reason this file exists: almost every number Android reports about audio on this platform is a
*declaration* rather than an observation, and several of them are wrong by a factor of seven.

---

## 1. Media does not always go where you think

There are two output threads. Which one carries the music depends on the audio policies installed,
and that changes what an app can do:

| policies | media plays on | consequence |
|---|---|---|
| factory | `AudioOut_15`, `AUDIO_OUTPUT_FLAG_FAST` | **`Visualizer(0)` is dead** |
| custom (the BitPerfect module) | `AudioOut_D`, `AUDIO_OUTPUT_FLAG_PRIMARY` | `Visualizer(0)` works |

A `Visualizer` created on session 0 is an *output mix* effect, and
`AudioPolicyManager::getOutputForEffect()` hard-prefers the primary output. On factory policies
the primary output sits in standby with zero tracks, so a session-0 effect faithfully measures
silence — for ever, without an error.

This is why `SessionResolver` exists: it finds the session id the music is actually on and attaches
the effect there. **Do not "simplify" it back to session 0.** It works on one head unit and not on
the next, and the difference is not in our code.

### Attaching an effect is a race

`getOutputForEffect()` looks for an output that already carries the session, and falls back to
primary when it finds none. So an effect created *before* its track starts playing lands on the
idle thread and hears nothing.

Both `LatencyProbe` and the analyser deal with this the same way: start the track, write a few
hundred milliseconds of silence, and only then create the `Visualizer`. Even that is not certain —
about one attempt in three still lost the race with custom policies — so `LatencyProbe` also checks
whether anything was heard after two bursts and re-attaches if not.

🪤 Re-attaching throws `IllegalStateException: setCaptureSize() called in wrong state: 2`, because
the previous effect is still enabled; releasing it does not take effect at once. Check
`getEnabled()` first and keep whatever capture size is already configured.

---

## 2. The capture path

### The assistant holds the microphone, and that silently caps everyone

`com.google.android.googlequicksearchbox` opens the microphone at boot and never lets go:
`session:25, source=MIC, 1ch 16000Hz`. The platform opens **one** input stream, so another app's
request does not open a second one — it is attached to the existing stream and gets 16 kHz
resampled up to 48.

```
                        assistant holding   microphone free
8.5 kHz                     -91.9 dB            -57.1 dB
energy above 8.1 kHz        -30.0 dB            +1.3 dB
```

🔴 **`AudioRecord.getSampleRate()` lies** — it returns 48000 either way. Detect it with
`AudioManager.getActiveRecordingConfigurations()`, or by measuring: a hard wall at 8 kHz means the
stream is really 16 kHz.

### There is no way past the AGDSP with stock policies

`/vendor/etc/audio_pcm.xml` promises a `recognition` path on PCM `device=0`
(`FE_ST_NORMAL_AP01`, straight to the AP). Measured: `MIC`, `VOICE_RECOGNITION` and `UNPROCESSED`
all land on `device=2` (`FE_ST_CAPTURE_DSP`, `HAL frame count: 1920`). The HAL picks `mm_normal`
regardless of source.

⚠️ This contradicts the Android CDD and most advice on the internet, which state that
`VOICE_RECOGNITION` and `UNPROCESSED` bypass processing. On this platform they do not.

The remaining route is `mmap_noirq` on `device=1`, reachable only through AAudio with
`EXCLUSIVE` sharing from native code. Not tried yet.

### Capture effects can be switched off from an app — and must be put back

| | factory policies | with the BitPerfect module |
|---|---|---|
| `AcousticEchoCanceler.isAvailable()` | true | true |
| `NoiseSuppressor.isAvailable()` | **false** | **true** |
| `AutomaticGainControl.isAvailable()` | false | false |
| state on the session | off | **on** |

```java
AcousticEchoCanceler aec = AcousticEchoCanceler.create(sessionId);
aec.setEnabled(false);   // returns 0 = SUCCESS, and getEnabled() confirms it
```

Any measurement that plays a sound and listens for it has to switch these off: an echo canceller
exists to remove exactly the sound we are playing, and noise suppression exists to remove steady
signals, which is what a test tone is.

🔴 **Put them back.** They belong to the platform, and on a unit with custom policies they are on
deliberately so that phone calls are intelligible. `MicProbe.suspendCapturePreprocessing()` records
the previous state and `Suspension.restore()` returns it.

`NoiseSuppressor.isAvailable()` doubles as a free test for custom audio policies — see
`HardwareProfile.captureHasVoiceProcessing()`.

---

## 3. Latency: measured, not declared

`getOutputLatency()` says **125 ms**. The track's own dump says **558 ms**. The measured distance
from the moment a sample is seen by the analyser to the moment it reaches the cabin is **53 ms**.

The declared figure counts buffering that has already elapsed by the time an effect sees the
samples, which is why it is so far out.

### How it is measured (`LatencyProbe`)

Eight quiet 2 kHz bursts on our own session, timed three ways:

| what | from | gives |
|---|---|---|
| when a frame reached the hardware | `AudioTrack.getTimestamp()` | the Android side, exactly |
| when we saw that frame | `Visualizer` on the same session | our measurement point |
| when it came back through the cabin | `AudioRecord.getTimestamp(TIMEBASE_MONOTONIC)` | a ceiling on the answer |

🟢 The input timestamps here are genuine: 208 of 208 came from the HAL, so the recording buffer is
already accounted for and does not inflate the result.

### Measured, four runs in each configuration

| configuration | capture → DAC | capture → ear |
|---|---|---|
| factory policies | 14.4 – 15.8 ms | **52.8 – 54.0 ms** |
| BitPerfect module | 8.4 – 12.3 ms | **55.3 – 61.0 ms** |

The difference at the ear is about 4 ms, smaller than the spread between bursts, so **there is no
need to branch on the configuration** — one measurement per head unit covers both. The result is
stored in `spec_latency_base_ms` and the ±250 ms trim sits on top of it.

⚠️ `PERFORMANCE_MODE_LOW_LATENCY` is refused (`getPerformanceMode()` returns 0), and the minimum
`AudioTrack` buffer here is 23080 bytes = 120 ms. The "fast" output is assigned by policy; an app
cannot ask for it.

### Traps found while building this

1. Attaching the effect before the track plays — see §1.
2. `lastHitFrame = Long.MIN_VALUE` in `frame - lastHitFrame > COOLDOWN` overflows, so the condition
   is never true and the microphone appears deaf while the peak is −22 dBFS.
3. A threshold of "N times above the floor" does not work in a cabin: 44 triggers for 8 bursts,
   two of them *before* the sound was played. Replaced by a matched filter — correlation against
   the burst's own energy envelope — which gave a prominence of ~50 and a spread of 5 ms.

---

## 4. Capture does not give a continuous stream

```
getMaxCaptureRate()   = 20000 mHz  → 20 callbacks per second, the platform's ceiling
getCaptureSizeRange() = [128, 1024]
1024 samples at 48 kHz = 21 ms of audio every 50 ms
```

29 ms out of every 50 simply do not exist. Concatenating the blocks gives a signal with time
compressed by a factor of 2.3, and any analysis below the block rate is meaningless.

The cure is to poll *faster* than a block lasts — every 9–12 ms — so consecutive reads overlap, and
to find the overlap by normalised cross-correlation. Measured: 0 discontinuities over 1126 frames
synthetically, 2 over thirteen minutes of real music (both at track changes, where the stream
really did break).

⚠️ Polling and analysis must live in different threads. While the FFT ran in the polling thread,
the long window delayed the next poll, and a late poll is a missed piece of the ring buffer — a
discontinuity the stitcher then had to repair.

---

## 5. Small things that cost hours

- **Our own package name contains "radio".** A substring test for the tuner decided the radio was
  playing whenever our own UI came to the front. Compare with `startsWith(getPackageName())` first.
- **`getVolumeStateMute()` is honest, but gating on it is wrong.** The status bar widget
  unregisters from the engine when hidden, and it is the only listener once the main window is
  closed — so muting the amplifier tore down the whole measurement chain.
- **Bluetooth audio is invisible to the Java API.** It is produced by the native `gocsdk_zj`
  daemon through libmedia with no `PlayerBase`, so `getActivePlaybackConfigurations()` is empty
  while music plays. The session sweep finds it anyway, because it attaches by session number.
- **The boot path is not the launch path.** At boot the service comes up with no activity, so a
  fault that only appears after a reboot lives there. `BOOT_COMPLETED` cannot be replayed from the
  shell (`Background execution not allowed`); use
  `am start-foreground-service -n com.radiorubka.wdsp/.McuService`.
- **`uiautomator dump` returns "could not get idle state"** while the analyser is animating. Use
  `dumpsys activity top -a`, but note its coordinates are relative to the parent, not the screen —
  a screenshot is more reliable for finding something to tap.
- **CPU has to be measured per thread.** Drawing cost three times as much as the measurement
  (RenderThread 1142 ticks against Capture 425).
