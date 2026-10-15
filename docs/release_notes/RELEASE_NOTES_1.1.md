wDSP — 1.1 "Bulldozer" (build 26)
Changes since 0.4.9.8 (build 25), the previous distribution of 13.09.2026
==========================================================================

BASE

This release is my modification of wDSP by Volodymyr Chebanenko (khiivl):
https://github.com/khiivl/wDSP
It contains all of the author's work up to his version 1.0.2.

INSTALLATION

Install the APK over the previous version. Presets and settings are kept. Open wDSP once after
installing so that its service starts.

DOCUMENTATION

A manual for every function is included: manual_en.html, manual_uk.html, manual_es.html,
manual_ru.html. They open in any browser without internet access.

=============================================================================
ADDED
=============================================================================

1. Interface (from the author's version 1.0)

- Settings → "UI Style": Classic (Original) — the author's interface; Modern — the glass interface
  of earlier mod versions. Both in day and night themes.
- The equaliser graph draws the processor's actual curve (Q 2.2).
- Ultra Bass: subwoofer gain that increases with volume.
- Loudness through the bass shelf; Trim Highs.
- Pair locks for delays.
- "Off" in the subwoofer frequency list, for cars without a subwoofer.
- GALA can be switched off for an individual preset: "Disable for this preset".
- "Audio Check" tab: pink noise, a sine at a chosen frequency, a sweep, test recordings per speaker.

2. Cabin model (updated)

- The result of a measurement is, first, a list of acoustic faults and, second, a coarse
  correction within what the processor can do (16 bands, 2 dB steps, fixed Q 2.2). Faults the
  equaliser cannot correct are reported, not equalised.
- Faults detected: a speaker quieter than its mirror counterpart; a tweeter in antiphase with its
  midbass; left and right channels swapped; a speaker resonance or a cabin mode below 200 Hz; a
  subwoofer in antiphase with the doors at the crossover; a measurement degraded by noise.
- The cabin is described by a profile: closed or open space, dimensions, installed speakers.
  Delays are computed from the geometry and the measured arrival times.
- The equaliser does not act on the subwoofer channel. The model now treats the subwoofer
  separately: its level, crossover and polarity are set on their own.
- Door channels are averaged in power. A failed tweeter no longer shifts the whole estimate.
- The correction for overlapping equaliser bands is always applied.
- The report shows the correction in dB.

3. Measurement procedure

1. Settings → cabin card: space type, dimensions, installed speakers (tap the speakers on the
   diagram). Only declared speakers are measured.
2. Microphone calibration (once per unit).
3. Measurement. The measurement volume is chosen by the application; during the run it also
   measures the level change per volume step of the unit.
4. "Apply Auto-EQ". A control sweep follows automatically and reports whether the correction
   took effect. If the subwoofer is in antiphase, the report says to change the phase switch on its
   amplifier (0/180).

Cabin noise no longer stops the measurement; it reduces the confidence of the affected bands.

4. Spectrum (RTA)

- Display as a live curve or as bars.
- "RTA zero" in Audio Check sets both zero references (channel and microphone) in one step.
- Microphone overload indication. If it appears, lower the microphone sensitivity in the car
  settings.
- Analysis pauses when nothing is playing and resumes on its own.
- Bluetooth audio is shown on units without the BitPerfect module.

5. Player resume after restart and after sleep

Settings: "Resume the player after a restart", "Resume the player after sleep". The player that
was playing is started again without its window being brought to the front. Radio is not
affected. For sleep, "Let wDSP stay alive through sleep" opens the unit's sleep whitelist; wDSP
must be enabled there.

6. Other

- Screensaver: "Cover the status bar" switch.
- Diagnostics: "Screen Topology" — a file describing the screen layout and touches.
- Interface in 30 languages and regional variants.

=============================================================================
FIXED
=============================================================================

Calls

- The preset no longer switches back and forth during a call; the call preset is applied, other
  processing is paused.
- The microphone is released immediately when a call starts.
- During a Bluetooth call the spectrum no longer searches for an audio session.
- The screensaver is closed when a call starts.
- After a call the volume is taken from the platform, not from the application's own memory.

Start-up and processor

- The service starts at unlock, in stages: audio system, microphone, MCU, presets.
- An empty preset is no longer sent to the processor before the stored one is read.
- After the MCU link is re-established the preset is sent again.
- The service restarts after an application update.
- Subwoofer gain set incorrectly at start-up (author's fix, 1.0.1).
- Unchanged data is not re-sent to the MCU.
- No audio session search at start-up when nothing is playing.
- A crash when the application opened directly on the Loudness tab.
- Storage permission is requested in the form this firmware grants.

Microphone and spectrum

- The microphone is taken back as soon as the input is free; no restart request.
- The microphone is no longer closed on wake-up.
- A mid-band dip in the microphone spectrum after sleep (caused by the application's own gate).
- A narrow-band microphone (16 kHz) is never shown as the cabin response.
- The platform's capture effects are no longer switched by the application.
- A test tone froze the spectrum.
- The spectrum's sample rate is taken from the output.
- The "Off" spectrum mode switches off only the RTA; the widget and the screensaver keep working.
- One analyser (native); the third-octave grid is standard.

Measurement

- The deepest door bands were overstated (the sweep window cut the first arrival).
- The 20 Hz and 20 kHz bands read flat.
- The subwoofer is swept from 15 Hz, the doors from 20 Hz.
- Door high-pass and subwoofer crossover: code 0 is "Through", not 20 Hz.
- Subwoofer compensation works at 100 Hz and no longer changes the crossover.
- The subwoofer is aligned at the crossover selected by the measurement, at the peak closest to the
  geometry.
- A measurement is no longer overwritten by the next one.
- The level probe is repeated if it clipped.
- Measurement progress follows the recording time.
- The "bass added twice" warning removed; the loudness fix button is offered only when it would
  change something.

Interface and screensaver

- The bottom row of the equaliser no longer cuts off the amplifier buttons on narrow and vertical
  screens.
- The bottom navigation fits 640 dp windows.
- The main screen and dialogs fit all panels of the platform.
- The processor follows the slider during the movement again.
- A pending save is written before the preset changes.
- The preset follows the player that is actually playing.
- The screensaver appears on units with "floating video" or "floating navigation" enabled in the
  factory settings.
- The navigator selected in the unit's settings is added to the screensaver exceptions.
- The screensaver steps aside when the application underneath changes.
- Screensaver touch zones are correct on vertical panels.
- A tap on the cover opens the radio that is actually playing.

Volume and GALA

- The volume of each source follows changes made outside wDSP.
- GALA no longer leaves an extra volume step after slowing down.
- Fewer disk writes when settings change (author's change).

=============================================================================
REQUEST TO TESTERS
=============================================================================

1. Measurements. After a cabin measurement send the measurement archive ("Save archive") and the
   system report (Diagnostics → "System report"). Both are saved to Download/wDSP. Add your
   impressions: how the sound changed after applying the preset, and the soundstage.

2. Screensaver. If it does not appear: leave the unit for two minutes on any application other
   than wDSP, then Diagnostics → "Screen Topology" → save and send the file. If it appears, tap it
   a few times (track zones, screen edges) before saving.

3. Player resume. Which player, after restart or after sleep, and the result.

Send to: https://t.me/wDSPapp/79

=============================================================================
ACKNOWLEDGEMENTS
=============================================================================

To Volodymyr Chebanenko (khiivl), the author of wDSP: for the application, the Classic interface
and version 1.0, on which this release is built, and for his knowledge of sound.
To the testers who sent measurements and reports.
