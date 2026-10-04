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
| **Live RTA** (`SpectrumAnalyzerView` 207 → 1048) | a 200-point log curve 20 Hz–20 kHz behind the EQ curve, drawn like FabFilter Pro-Q's analyser | **take his display model onto our analyser** — design below |
| `SessionResolver`, `SessionProbe` | finding the player's audio session | **ours** (9a640bb, 19.08) copied with edits (25/76 lines): fold his edits back where they fix something |
| `AudioConfig.typicalCarSpeakerFloorDb` | fixed rolloff of a 5.25–6.5" door speaker left after cabin gain: −1 dB at 80 Hz, −5 at 30, −8 at 20 | **take** — into the RTA's music-mode shift, and as an input to our cabin model (TODO 3️⃣) |
| **Audio Check tab** (`MainActivity` +~700, `res/raw` 8 WAVs, 8.6 MB) | stems (bass/drums/melody/vocal), per-speaker test (sets the fader), pink noise, sine generator, sine sweep normalised to the hearing threshold, "only sub" | **take** — runtime overrides through broadcasts, never saved into the preset (his rule, keep it); his layout into Classic, ours in Modern |
| `McuService` `AUDIOCHECK_FADER` / `AUDIOCHECK_ONLY_SUB` | test overrides of fader / sub-only, runtime only | **take**, gated by our audio-ownership and Call rules |
| **"No Sub"** (`SUB_FREQS` last entry, `NO_SUB_INDEX`) | a preset without a subwoofer: lowest index + 0 gain on the wire, no sub curve, dependent controls disabled | **take**; it is also the "kit" answer our cabin model needs (sub present or not) |
| FM disclosure toggles, GALA "Advanced", shorter labels | collapsible groups on the loudness tab, tidier GALA | Classic: as his; Modern: our layout already differs — take the grouping idea only where it fits |
| `proguard-rules.pro` | keep rules for his release build | take (harmless; we build debug only) |

## The live RTA: his display model on our measurement

His measurement path is weaker than ours, so it is **not** taken: one 1024-sample Visualizer block per
50 ms callback (58 % of the audio missing; our `Stitcher` makes a continuous stream), the Visualizer's
reported 44.1 kHz (wrong on QF, the samples are at 48 kHz — his bins sit 9 % off; ours read
`PROPERTY_OUTPUT_SAMPLE_RATE`), and a 2× low-band splice where we run 8192 points below 800 Hz.

What **is** taken — his audio reasoning and his numbers:
1. 200 log-spaced points, each read by Catmull-Rom (Hermite) interpolation in dB through the four
   nearest bins — no overshoot "ears", no clamp.
2. Triangular smoothing over ±4 points (fractional-octave-like), off while a sine tone plays.
3. Ballistics per point: attack 1.0, release 0.15; a presence gate `(smoothed − (−20)) / 3 dB`, so the EQ
   shift shows only where there is content.
4. Music mode only: the shift by the calculated chain — EQ (`compositeResponseDb`), loudness correction,
   front and rear bass shaping **plus the door-speaker floor** and the sub low-pass, combined in
   **power**, weighted by fader/balance (`combineDbLinear`). Microphone mode gets no shift — the
   microphone already hears the chip's output.
5. Normalisation −20…60 dB, unclamped; the curve fades out in silence (peak below 2 dB, 4 dB fade) and
   back in; `Choreographer` interpolates between captures.

Where it lives: the native analyser gains a 200-point curve output from the transforms it already
runs (8192 below 800 Hz, 1024 above); a Java helper `RtaCurve` does 2–5; `SpectrumAnalyzerView` draws
curve or bars by a setting in the visualisation card. Classic draws it his way, Modern ours.

## For the author: what the merge commit must explain (owner, 05.10.2026)

The owner: *«щоб його клауде зразу входив у курс справ, і давав автору розуміння, що ми не погіршуємо код, а
покращуємо де можемо. Проблема автора в тому, що він працює з емулятором, а я з живим справжнім QF»*. So the
merge commit (and the PR) carries: the decisions taken, where our approach differs and why, the defects found in
his code, how each was fixed, and the evidence **from the wire on a real QF** — what an emulator forgives (CPU,
broadcast storms, a dead output, a wrong rate) a head unit does not. Collect them here as they are found:

| his code did | on a real QF | what we do instead | evidence |
|---|---|---|---|
| `Visualizer(0)` on the output mix | silence: media plays on the `fast` output, the session-0 effect lands on the idle primary | the player's own session (`SessionResolver`, ours from 19.08 — he copied it) | memory `qf-visualizer-session0-dead`, dump 19.08 |
| FFT at `Visualizer.getSamplingRate()` | reports 44.1 kHz, delivers 48 kHz: every frequency 8.8 % low | `PROPERTY_OUTPUT_SAMPLE_RATE` | 1 kHz + 10 kHz tone read 918.5 / 9187 Hz, 14.09 |
| one 1024-sample block per 50 ms callback | 58 % of the audio never seen; nothing below the block rate means anything | polled `Stitcher`, continuous stream, 8192-point window below 800 Hz | ARCHITECTURE.md "Native analyzer"; `test_analyzer` |
| polling the volume state every 100 ms | tens of framework log lines a second, a CPU cost on every tick | events, a slow check as a safety net | TODO 1️⃣➕, `8590462` |
| early return in the mute branch of `checkVolumeAndGala()` | `QUERY` to the radio every ~103 ms while the amplifier is muted | the source remembered on every path | AUDIO_OWNERSHIP_CONTRACT, ~10/s before, 1 per 16 s after |
| microphone left open during a phone call | the AGDSP crashes (`dsp timeout cmd:0x26`), the call has no audio | release on `PHONE_CALL_START`, synchronously | BitPerfect line 04.10, call to 1200 |
| R8 / minify in a release build | hidden-API reflection into the QF framework breaks | debug builds only (`minifyEnabled false`) | AGENTS.md "Build" |
