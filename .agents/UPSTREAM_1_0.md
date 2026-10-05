# UPSTREAM 1.0 "Bulldozer" — absorbing the author's work into the mod

The owner, 05.10.2026: *«нам прийдеться всосати і це, але не напряму, а як і решту»*; *«це то-до, до
продовження злиття»*; on the analyser: *«живий RTA я б хотів отримати і у нас, і на базі його розуміння
звукотехніки»*; curve or bars — *«перемикач в налаштуваннях візуалізації»*. The principles of
[UPSTREAM_0_5.md](UPSTREAM_0_5.md) stand (his new work on top of ours, his measured numbers stand, port by
feature, then one real merge), with one change: **his interface goes into the Classic style**, which
emulates it; Modern stays ours ([DECISIONS.md](DECISIONS.md), 05.10).

Source: `origin/master` since the merge base `17be298` (02.10): `380023d` "wDSP 1.0, codename Bulldozer"
(04.10), `fec9532` "added sine sweep, fixed bugs", `821c636` "added missing files", `cedd694` "some
updates", README and screenshots (05.10). +3538/−779 in 33 files. The build he handed the owner is
`380023d` (Gemini `0c0b138e` decompiled it to `D:\De-compiled\bulldozer_hotfix\`); git is newer — port
from git, use the decompile only to cross-check.

## Inventory and decision per feature

| feature (his files) | what it is | decision |
|---|---|---|
| **Live RTA** (`SpectrumAnalyzerView` 207 → 1048) | a 200-point log curve 20 Hz–20 kHz behind the EQ curve, drawn like FabFilter Pro-Q's analyser | ✅ **taken** (05.10): his display model on our analyser — design below |
| `SessionResolver`, `SessionProbe` | finding the player's audio session | ✅ checked at the merge (`11926b3`): his copies differ from ours in comments and one log line only — nothing to fold back, ours stand |
| `AudioConfig.typicalCarSpeakerFloorDb` | fixed rolloff of a 5.25–6.5" door speaker left after cabin gain: −1 dB at 80 Hz, −5 at 30, −8 at 20 | ✅ **taken**: came with the merge; on the door paths of the RTA over music (`db3c73d`, `DspResponse.computeAt(..., doorSpeakers)`), not in the chip model the bands and the verdict use. Still an input for our cabin model (TODO 3️⃣) |
| **Audio Check tab** (`MainActivity` +~700, `res/raw` 8 WAVs, 8.6 MB) | stems (bass/drums/melody/vocal), per-speaker test (sets the fader), pink noise, sine generator, sine sweep normalised to the hearing threshold, "only sub" | ✅ **taken** (05.10): engine `AudioCheck` (his code, out of the activity; generated at 48 kHz, the QF output rate), controls `AudioCheckPanel`, one layout themed for both styles, his WAVs and his translations; added for a real QF — GAIN focus while anything plays (`PlaybackFocus`, shared with the cabin measurement), stop on focus loss and on a call, the spectrum told the test's session. Not yet heard on the unit |
| `McuService` `AUDIOCHECK_FADER` / `AUDIOCHECK_ONLY_SUB` | test overrides of fader / sub-only, runtime only | ✅ **taken**, his action names; cleared at a call start; the door high-pass code is one function (`doorHpfCode`) for the packet and the spectrum's model |
| **"No Sub"** (`SUB_FREQS` last entry, `NO_SUB_INDEX`) | a preset without a subwoofer: lowest index + 0 gain on the wire, no sub curve, dependent controls disabled | ✅ **taken** at his index (`DspResponse.SUB_OFF_IDX` = 11), so his presets mean the same here; the validator's clamp raised to 11 (at 10 his "No Sub" would have become a 250 Hz subwoofer). Our car-level kit flag (`hasSubwoofer`) stays beside it: a preset choice and the car's equipment are two facts. New presets keep our default, 80 Hz (his: No Sub) |
| FM disclosure toggles, GALA "Advanced", shorter labels | collapsible groups on the loudness tab, tidier GALA | ✅ **taken** (05.10): his behaviour - a group shown only while its switch is on, folded under its title, open state kept under his `fm_panel_*_open` keys; GALA "Advanced" closed on every start, with his set of controls - through one helper, `ui/Disclosure`, for all four. Laid out in our card (shared label barrier; the curve preview above the groups, since it draws all three features; the verdict outside them; `ic_chevron`, as `ic_arrow` is a half-alpha hairline); the keys live in the UI prefs, not in the presets file the export walks. Labels: `enable_loudness` = "Loudness"; our GALA labels were already shorter. Other locales: Gemini |
| `proguard-rules.pro` | keep rules for his release build | ✅ came with the merge |

## The live RTA: his display model on our measurement

The measurement stays this branch's, because of three things a real QF does (the table below has the
evidence): the Visualizer callback carries 1024 samples every 50 ms, so a stitched stream is needed to see
all of the audio; the Visualizer reports 44.1 kHz while delivering 48 kHz; and the bottom octaves want the
8192-point window. Both views then show the same frequencies at the same place.

What is taken — the author's audio reasoning and his numbers:
1. 200 log-spaced points. His point is read from the four nearest FFT bins (Catmull-Rom in dB) and then
   smoothed over ±4 points in dB to stand in for fractional-octave smoothing. Here each point is measured
   as the energy of a third of an octave around it by the bands' own function (`planEnergy`), which
   *is* fractional-octave smoothing, done in power: a point on a band centre reads that band to 0.27 dB
   (pink noise, `test_analyzer`), so the curve and the bars are one measurement. His ±4-point pass is
   therefore not applied on top — it would blur the curve to two thirds of an octave. Owner, 05.10:
   *«в нас же натив 32, це ж насправді точніше»*.
2. A pure tone becomes a plateau a third of an octave wide, as it does in the bands. His sine
   generator wants a sharp peak (he turns his smoothing off while it plays) — a narrow-band reading for
   that case comes with Audio Check.
3. Ballistics per point: attack 1.0, release 0.15; a presence gate `(smoothed − (−20)) / 3 dB`, so the EQ
   shift shows only where there is content.
4. Music mode only: the shift by the calculated chain — EQ (`compositeResponseDb`), loudness correction,
   front and rear bass shaping **plus the door-speaker floor** and the sub low-pass, combined in
   **power**, weighted by fader/balance (`combineDbLinear`). Microphone mode gets no shift — the
   microphone already hears the chip's output.
5. Normalisation −20…60 dB, unclamped; the curve fades out in silence (peak below 2 dB, 4 dB fade) and
   back in; `Choreographer` interpolates between captures.

Where it lives: the native analyser gains a 200-point curve output from the transforms it already
runs (8192 below 800 Hz, 1024 above); a Java helper `RtaCurve` does 3–5; `SpectrumAnalyzerView` draws
curve or bars by a setting in the visualisation card. Classic draws it his way, Modern ours.

## For the author: what the merge commit explains (owner, 05.10.2026)

The owner: *«щоб його клауде зразу входив у курс справ, і давав автору розуміння, що ми не погіршуємо код, а
покращуємо де можемо»*; on the tone: *«акуратно ... не образити, а по діловому і з аргументацією»*; and *«не путай
наші вади, з його»*. The author develops on an emulator, this branch is tested on a real QF head unit, and some
behaviour exists only on the real one. So the merge commit and the PR state the decisions taken, where the
approaches differ and why, what a real QF does with a given piece of his code, and what was changed — each with
its evidence: a measurement, a log line, a commit; no judgement of the person. **Only what was checked against
his own code goes in** — faults of this branch's own features (the radio contract, the microphone) are ours and
are not listed. Collected here as they are found:

| in the 1.0 code | on a real QF | change in this branch | evidence |
|---|---|---|---|
| `Visualizer(0)` on the output mix (the class comment; the code now resolves a session) | session 0 processes silence: media plays on the `fast` output, an output-mix effect lands on the idle primary | the player's own session — `SessionResolver`, which 1.0 already carries | `media.audio_flinger` dump, 19.08.2026 |
| FFT bins placed at `Visualizer.getSamplingRate()` | it reports 44.1 kHz while the samples are at 48 kHz, so every frequency reads 8.8 % low | the rate from `PROPERTY_OUTPUT_SAMPLE_RATE` | a 1 kHz + 10 kHz test tone read 918.5 / 9187 Hz, 14.09.2026 |
| one FFT per 1024-sample callback, 20 per second | 21 ms of every 50 ms is seen; content between callbacks never reaches the analyser | a polled `Stitcher` rebuilds the continuous stream; 8192 points below 800 Hz | `.agents/ARCHITECTURE.md` "Native analyzer"; host test `test_analyzer` |
| `McuService` polls `checkPlayer` and `checkVolumeAndGala` every 200 ms | ten framework calls a second, each logged by the framework, for state that changes a few times a minute | events, with a slow check kept as a safety net | TODO "polling → events", `8590462` |
| GALA step: the slider shows the stored number as km/h (10..100), `McuService` still applies `cachedGalaInc + 5` | the screen says 20 km/h, GALA steps every 25 | not taken: the owner keeps this branch's GALA whole (`DECISIONS.md`); here both the label and the service add 5 | his `MainActivity` (`speed_kmh_format, p`) against his `McuService` (`speedIncrement = cachedGalaInc + 5`), 1.0 |
| `res/drawable/ic_bass_radiation.png` (the RTA's bass easter egg) | the image carries "Adobe Stock" watermarks — a stock preview, not a licensed asset, in a GPL project | not merged here (this branch has no use for it); the trefoil is easy to draw as a vector | the file itself, 879×879 |

## The merge (`11926b3`, 05.10.2026)

Twenty conflicts. Where 1.0 had already been taken by this branch's own implementation (RTA, "No Sub", Audio Check, the loudness groups and GALA "Advanced"), this branch's file stands; `SessionResolver` / `SessionProbe` are ours (see above); `layout-port` stays deleted (`34a9e9a`); `build.gradle` keeps versionCode 25 (his 2 would not install over our builds). Taken from his side: `typicalCarSpeakerFloorDb`, `wdsp_app/proguard-rules.pro`, README (`8c508fd`). Not taken: GALA rescaling, `ic_bass_radiation.png`, his new dimens and `.idea` (only his layouts and his IDE use them). The report for him, in Ukrainian: `UPSTREAM_MERGE_REPORT.md` in the repository root.
