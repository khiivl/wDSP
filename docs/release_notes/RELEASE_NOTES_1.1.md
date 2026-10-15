wDSP — 0.4.9.9 (build 26)
What changed since 0.4.9.8 (build 25)
=====================================

This is a big one: a month of work, and the author's version 1.0 is now inside. Below is what is
new and how to use it. The full manual for every function is in the pack: manual_en.html (also in
Ukrainian, Spanish and Russian). Open it in any browser, it needs no internet.

INSTALLING

Install the APK over the previous version, nothing needs removing first. Your presets and
settings stay. After installing, open wDSP once so it can start its service.

-----------------------------------------------------------------------------
1. TWO LOOKS: CLASSIC AND MODERN
-----------------------------------------------------------------------------

The author's version 1.0 is merged in. Under Settings → "UI Style" you choose:

- Classic (Original) — the author's own Material 3 interface;
- Modern — the frosted-glass one you know from my versions.

Both work in day and night themes. What came with 1.0:

- The equaliser graph now draws the chip's real curve (Q 2.2), not a sketch.
- Ultra Bass — subwoofer gain that grows with the volume knob.
- Loudness through the bass shelf, plus Trim Highs.
- Pair locks for delays — left and right move together and say so when switched on.
- "Off" in the subwoofer frequency list, for cars without a subwoofer.
- GALA (speed-dependent volume) can be switched off for a single preset — "Disable for this
  preset".
- The Audio Check tab: pink noise, a sine at any frequency, a sweep, and test recordings per
  speaker. Use it to hear each speaker on its own before measuring.

-----------------------------------------------------------------------------
2. CABIN MEASUREMENT (AUTO-EQ) — REWORKED
-----------------------------------------------------------------------------

How to use it now:

1. Settings → the cabin card. Say whether it is a closed car or an open space (bench,
   cabriolet), set the dimensions, and TAP THE SPEAKERS on the little car to say which ones are
   really installed. Only the speakers you declared are swept.
2. Calibrate the microphone (once per unit).
3. Run the measurement. You no longer set the volume — the app picks it itself, and on the way it
   measures how many dB each step of the volume knob gives on your unit.
4. Press "Apply Auto-EQ". Straight after it the app plays a short CONTROL SWEEP through the new preset and
   tells you whether the correction actually worked.

What the report shows now:

- A LIST OF FAULTS comes first, in plain words: a speaker quieter than its mirror twin, a tweeter
  playing against its own midbass, channels swapped left/right, a speaker resonance or a cabin
  mode below 200 Hz, a measurement drowned in noise.
- If the subwoofer plays against the doors at the crossover, the app tells you to turn the phase
  switch on the subwoofer amplifier to the other position (0/180).
- The correction is shown in dB, not in chip numbers.
- Cabin noise is no longer a reason to refuse the measurement; it lowers the trust in the bands it
  covers instead.

-----------------------------------------------------------------------------
3. SPECTRUM (RTA)
-----------------------------------------------------------------------------

- Choose its shape: a live curve or bars.
- The "RTA zero" button in Audio Check takes both zeros at once (the channel and the microphone):
  it plays pink noise itself, listens, and sets the zero.
- If the microphone clips, the spectrum says "Microphone overloaded". Lower the microphone
  sensitivity in the car settings until the message goes.
- The spectrum sleeps when nothing is playing, and wakes on its own.
- Bluetooth music now shows in the spectrum also on units without the BitPerfect module.

-----------------------------------------------------------------------------
4. NEW: RESUMING THE PLAYER AFTER A RESTART OR SLEEP
-----------------------------------------------------------------------------

Two switches in Settings: "Resume the player after a restart" and "Resume the player after sleep".
If music was playing when the unit went off, wDSP starts the same player again once it is up —
without opening its screen on top of your launcher or navigator. Radio restores itself and is
left alone.

For sleep, press "Let wDSP stay alive through sleep" under the switch: it opens the unit's own
sleep whitelist, where wDSP has to be ticked.

This is new. Tell me which player you use and whether it came back.

-----------------------------------------------------------------------------
5. CALLS, SCREENSAVER, VOLUME
-----------------------------------------------------------------------------

- During a call the app lets go of the microphone at once and switches to the call preset; after
  the call everything comes back.
- The screensaver closes the moment a call starts.
- The screensaver now appears on units where "floating video" or "floating navigation" is ticked
  in the factory settings — it did not before.
- The navigator chosen in the head unit settings is added to the screensaver's exceptions by
  itself, so the screensaver does not cover the map. If you want the screensaver over the
  navigator, remove it from that list.
- The bottom row of the equaliser no longer cuts off the amplifier buttons on narrow and vertical
  screens — the whole row shrinks together.
- The volume of each source is remembered and follows changes made outside wDSP.
- GALA no longer leaves an extra step of volume behind after every slow-down.

-----------------------------------------------------------------------------
LANGUAGES
-----------------------------------------------------------------------------

The interface is in 30 languages and regional variants.

-----------------------------------------------------------------------------
A REQUEST TO TESTERS
-----------------------------------------------------------------------------

1. AUTO-PRESETS. Is the preset assembled correctly, in your opinion? Is the soundstage right?
   Please send the measurement archive ("Save archive" after a measurement) and the system report
   (Diagnostics → "System report"). Both land in the Download/wDSP folder.

2. IF THE SCREENSAVER NEVER APPEARS on your unit: leave the unit for two minutes on any app
   except wDSP. Then Settings → Diagnostics → "Screen Topology" → save and send the file. If the
   screensaver does appear, first tap it a few times (the track zones, the screen edges), and only
   then save — the file will show where the touches landed and how the app understood them.

3. PLAYER RESUME — which player, restart or sleep, and did it come back.

Send everything to the group: https://t.me/wDSPapp/79

If something is wrong, add a comment saying what exactly. If nothing is wrong, tell me how the
presets sound to you.

Thank you.
