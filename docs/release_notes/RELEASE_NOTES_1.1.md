wDSP — 1.1 "Bulldozer" (build 26)
Changes since 0.4.9.8, the previous distribution of 13.09.2026
==============================================================

This release is my modification of wDSP by Volodymyr Chebanenko (khiivl),
https://github.com/khiivl/wDSP, and contains all of his work up to his version 1.0.2.

INSTALLATION

- Over a previous version of the mod: install the APK on top; presets and settings stay.
- Over the author's build: the two are signed with different keys and Android will not install
  one over the other. Save your presets with the app's backup, uninstall the author's build,
  then install the mod.
- Open wDSP once after installing.

The manuals (nine languages, including the screensaver gesture map) are in the archive. They open
in any browser without internet.

-----------------------------------------------------------------------------
NEW
-----------------------------------------------------------------------------

FROM THE AUTHOR'S APP (khiivl, up to his 1.0.2) — all of it is in the mod too
- Ultra Bass, the new Loudness, a live analyser curve behind the equaliser, "Off" in the
  subwoofer list for cars without one, GALA switchable per preset.
- The "Audio Check" tab: test signals and test recordings for each speaker.

ONLY IN THE MOD — the author's app does not do this
- It measures the car: sweeps through every speaker, recorded by the unit's microphone, and
  builds a preset, the delays and a list of problems from what it heard.
- Its spectrum can listen to the cabin through the microphone, not only show the music.
- A visualiser in the status bar and a full-screen screensaver with playback controls.
- It looks after the sound beyond the equaliser: calls, the volume of each source, resuming
  the player after a restart or sleep.
- It runs on events, not on a clock: the author's service checks the volume and the player
  every 200 ms; the mod reacts only when something actually changes.
- Two interfaces: Classic (the author's look) and the mod's own Modern.

New in the mod in 1.1:

Interface
- Two interface styles in Settings → "UI Style": Classic (the author's look) and Modern. Day and
  night themes in both.
- Pair locks for delays: "Move fronts together" and "Move rears together" keep the difference
  between left and right.

Cabin measurement
- Settings → cabin card: say what kind of cabin it is, its size, and tap the speakers that are
  installed.
- The app chooses the measurement volume itself.
- After "Apply Auto-EQ" a short control sweep shows whether the correction worked.
- The report starts with a list of problems in plain words — for example a weak speaker, a tweeter
  in the wrong polarity, swapped channels, a subwoofer playing against the doors (with a hint to
  turn its phase switch). Problems an equaliser cannot fix are reported instead of being
  "corrected".

Spectrum
- A switch on the main screen: Off, Music (what the preset makes of the music), Microphone (what
  the cabin actually sounds like).
- "RTA zero" in Audio Check calibrates the spectrum in one press.
- A warning when the microphone is overloaded: lower its sensitivity in the car settings.

Player resume
- Settings: "Resume the player after a restart" and "Resume the player after sleep". The player
  that was playing comes back on its own, without its window jumping on top of the navigator.
- For sleep, press "Let wDSP stay alive through sleep" and enable wDSP in the list that opens.

Screensaver
- Swipe from the middle of the screen to change the volume.
- Hold the style button for a second to open the equaliser.
- A tap on the cover opens the player or radio that is actually playing.
- "Cover the status bar" switch.

-----------------------------------------------------------------------------
FIXED
-----------------------------------------------------------------------------

Author (khiivl)
- The subwoofer level at switch-on: a start-up race set it wrong (his 1.0.1).

Mod
- Calls: the preset no longer jumps during a call, the microphone is released at once, and the
  volume after a call is correct.
- Start-up: settings reach the sound processor reliably after switching on and after an update.
- The screensaver now appears on units with "floating video" or "floating navigation" enabled,
  does not cover the navigator, and works on vertical screens.
- The equaliser screen and dialogs fit narrow and vertical screens.
- The microphone spectrum after sleep and after the voice assistant has used the microphone.
- Several errors in the cabin measurement, especially in the low bass and with the subwoofer.
- GALA no longer leaves the volume raised after slowing down.

-----------------------------------------------------------------------------
PLEASE SEND
-----------------------------------------------------------------------------

The author's version has no cabin measurement, so measurements go to the mod's group.

1. After a cabin measurement: the measurement archive ("Save archive") and the system report
   (Diagnostics → "System report"), both in Download/wDSP, and a few words on how the sound
   changed and how the soundstage feels.
2. If the screensaver never appears: Diagnostics → "Screen Topology" → save and send.
3. Whether player resume worked for you, and with which player.

Mod's group: https://t.me/kostyamat_dev/92
The original project: https://t.me/wDSPapp, https://github.com/khiivl/wDSP

-----------------------------------------------------------------------------
THANKS
-----------------------------------------------------------------------------

To Volodymyr Chebanenko (khiivl), the author of wDSP, for the application this release is built
on. To Mirek (Poland) for testing the author's equaliser on real hardware. To the testers who send
measurements and reports.
