# UPSTREAM 0.5 — absorbing the author's work into the mod

The owner, 02.10.2026: *«важливо — абсорбуй. Гала в нас дещо своєї конструкції, але все що автор
додав — повна абсорбація»*; on the spectrum: *«зберегти наше»* (our `AudioSpectrumEngine` stays).

And, the same day: *«ми від нього на голову далі, але важливе що він робив треба забрати, і ми не
повинні створювати конфлікти для злиття якщо він захоче прийняти ПР. Але наша гала ніби продвинутіша,
ми маємо контракти, яких не має його версія, тобто — його нові напрацювання + наше, а не сліпа
заміна»*. So:
- **his new work on top of ours**, never a replacement of ours — our GALA, the contracts
  (`C:\APPS_Contacts\`), the Call preset, the audio-ownership duties stay;
- **the branch must stay mergeable into his `master`**: after the features are ported, finish with
  a real `git merge origin/master` whose conflicts are resolved to "ours + his features", so a PR
  from `kostyfmat_mod` to `master` applies without conflicts;
- **questions to the owner one at a time**, never a list;
- **where his numbers and ours disagree, his stand** — owner 02.10: *«суть, автор має обладнання
  для замірів, ми ні.»* Keep his values, extend them by his own rule where they stop; a value of
  ours that is only derived (research, ISO, a model) does not replace one of his.

Source: `origin/master` (author khiivl) since our split at `fd447a9` (20.08):
`8c71b6a` (17.08, pink-noise tilt), `59c542c` (02.10, "huge commit for 0.5", +3224/−637 in 24
files), `704ee61` (merge), `8b1c0a9` (revert of our "Fix GALA stopping…").

**Method: port by feature, never merge.** We rewrote the same files since 20.08 (`MainActivity`
1734 → 3163 lines, `McuService` 1073 → 2448, our spectrum moved into the native
`AudioSpectrumEngine`), so a merge is a wall of conflicts and would bring back what we deleted
(`layout-port/`). Each feature lands in **our** single source of truth for its subject — never as a
second copy beside it: the chip model is `DspResponse`, loudness is `LoudnessCurve`
(+ `LoudnessCheck`), the wire is `McuService`, the spectrum is `AudioSpectrumEngine`.

Read the author's code with `git show origin/master:<path>`; line numbers below are in that tree.

## Features, where each goes, status

| # | feature (author) | author's code | our home | decision | status |
|---|---|---|---|---|---|
| 1 | **EQ cross-talk pre-warp**: 16 Q=2.2 bells overlap; the target is pre-warped by a fixed 16×16 inverse matrix so the composite hits the target at every band centre (worst ripple −1.8 → −0.2 dB) | `AudioConfig.EQ_CROSSTALK_INVERSE`, `prewarpEq`; used in `McuService.updateEqWithFm` (pre-warp slider dB + loudness offset jointly, only while an offset is active) | `DspResponse` (matrix + `prewarpEq`), applied in our EQ send path | absorbed: his `AudioConfig` as is, applied in `McuService.updateEqWithFm` exactly as he does; also a candidate for the auto-EQ synthesis (cabin model) | ✅ `c9897c3`, bench check pending |
| 2 | **Loudness through the bass shelf**: part of the ISO bass boost goes to the front/rear P2Bass shelf (1 dB steps, one filter) instead of the EQ alone; EQ gets the residual row for the shelf frequency (`ISO_RAW_TARGET_BY_FREQ`, rows 54/68/86/108/134 Hz, default 86 Hz, shelf max 10 dB) | `AudioConfig` (`LOUDNESS_BASS_SHELF_*`, `ISO_FULL_TARGET_DB`, `ISO_RAW_TARGET_BY_FREQ`, `BASS_BOOST_FREQS_HZ`, `isoRawTargetForFreqIdx`); `McuService.updateFmOffsets`, `applyBassBoost(vol)` (rear synced to front while loudness is on) | `LoudnessCurve` (tables + offsets), `McuService.applyBassBoost` | absorbed: `LoudnessCurve.offsets/bassShelf/eqDriveDb`, `McuService.applyBassBoost(vol)` with its own throttle; `LoudnessCheck` measures the pre-warped drive and the shelf's room; 172/214 Hz dropped as in his. **Ours, on purpose**: the share by our `loudnessRatio` (full curve at volume 1; his stops at (cal-2)/(cal-1)); a stale shelf index means 86 Hz for both shelf and residual | ✅ `10a1373`, bench check pending |
| 3 | **Trim Highs (fatigue)**: own start volume `_fat_start_vol` (default 25) instead of the loudness calibration point; new target — a dip at 3.15–5 kHz (`FATIGUE_RAW_TARGET`) instead of a high shelf | `McuService.updateFmOffsets`, `AudioConfig.FATIGUE_RAW_TARGET`; UI `seek_fat_start_vol` | `LoudnessCurve`, prefs, UI | engine absorbed (start volume from the preset, default 25; validator 0..32; the old "calibration point" message replaced by a new key in 30 locales, 28 translations ordered from Antigravity, board #1225). 📋 the start-volume slider on screen | ✅ `10a1373` engine, 📋 slider |
| 4 | **Ultra Bass**: sub gain ramp, 0 at `_ultra_bass_start_vol` (16) up to `_ultra_bass_max_db` (6) at volume 32, independent of loudness | `McuService.updateSubwoofer`; UI `switch_ultra_bass`, `seek_ultra_bass_*` | `LoudnessCurve` (one place for every volume-dependent offset), `McuService.updateSubwoofer`, UI | absorb | 📋 |
| 5 | **Sub-comp boost table** from `ISO_FULL_TARGET_DB` (80 Hz 8, 50/63 10, 25–40 12, ≥100 0) | `McuService.getMaxBassBoost` | `LoudnessCurve.maxSubBoost` | **his table, carried on by his rule** (the band below the crossover band): 25–80 Hz his 12 12 12 10 10 8, then 100–250 Hz 8 6 6 4 4 — owner 02.10: *«тобто, це буде його, але доповнена нами таблиця?»* | ✅ `e2d9b7e` |
| 6 | **Dedupe of computed packets** (`lastComputed*`) before the throttle; pref listener matches `preset + "_"` (a preset "Music" no longer reacts to "Music2_*"); `_fm*` changes resend sub and bass boost; `_bb_/_bf_` resend EQ | `McuService` listener and `update*` | `McuService` | absorb | 📋 |
| 7 | **Polling split** 100 ms → primary (volume/GALA) + secondary (player/bug) at 200 ms | `McuService.primaryRunnable/secondaryRunnable` | — | ❓ our loop also carries the Call preset and the audio contract; check before touching | ❓ |
| 8 | **GALA fixes** (shutdown state, standstill fallback, push on source change) | `McuService.checkVolumeAndGala` | a GALA strategy beside ours, chosen by a switch | **both, switchable** — owner 02.10: *«Гала - зробиш перемикач - оригінал\наш варіант.»* His GALA ported whole as the "original" option, ours stays the other | 📋 |
| 9 | **Filter models for drawing**: sub LPF (2nd-order Butterworth), front/rear bass shaping (HPF + 1st-order shelf) | `AudioConfig.subFilterResponseDb`, `bassShapingResponseDb`, `compositeResponseDb`, `frequencyAt` | `DspResponse` (we have `lowPass2Db/highPass2Db/peakingResponseDb`; add the shelf) | absorbed: `DspResponse` composes his shapes and keeps no bell, LPF or HPF of its own (our RBJ biquad at the capture rate is gone) | ✅ `c9897c3` |
| 10 | **EQ view**: true Q 2.2 composite curve, loudness overlay, sub curve, rear bass curve, animation | `EqVisualizerView` (+558), `FmVisualizerView` (+161); colours `loudness_line`, `sub_line`, `rear_bass_line` | our views | absorb | 📋 |
| 11 | **Spectrum**: pink-noise tilt (`8c71b6a`), loudness/sub/bass-reactive scaling, RTA | `SpectrumAnalyzerView` (+1015 incl. its own FFT/Visualizer) | `AudioSpectrumEngine` + our view — **keep ours**, port the features only | absorb features | 📋 |
| 12 | **UI**: show loudness on main, sync L/R delays front/rear, sync front/rear bass (+ toast) | `MainActivity` (+793), `activity_main.xml` (+348), `layout-port` (+190 — we have no portrait copy) | our `MainActivity`, `activity_main.xml` | absorb (no `layout-port`) | 📋 |
| 13 | **Strings**: 8 new keys in 7 locales; "F-M Curve" tab renamed "Correction" | `values*/strings.xml` | ours, 30 locales (Antigravity translates, I check) | absorb | 📋 |
| 14 | **Build**: `flexbox` 3.0.0 dependency; `<profileable android:shell="true">` in the manifest | `libs.versions.toml`, `build.gradle`, `AndroidManifest.xml` | ours | absorb with the UI that needs flexbox | 📋 |

## Order

1 → 9 (the model: pre-warp and filter shapes in `DspResponse`) → 2, 3 (loudness) → 4 (Ultra
Bass) → 6 (wire hygiene) → 8 (GALA switch) → 12, 13, 14 (controls) → 10 (EQ views) → 11 (spectrum features).
Each lands as its own commit, verified by build and, where it changes what reaches the chip, by the
`TurboSender2000` log on the bench before and after.

Last step: `git merge origin/master` with every conflict resolved to the ported result (above).

📋 Owed by the model: `DspResponse` still draws the doors without their bass shelf (`AudioConfig.bassShapingResponseDb` has it) — the shelf now carries up to +10 dB of loudness.

Open questions for the owner — **one at a time, as a full sentence about the thing, never an item number** (owner 02.10: *«я не розумію пункти я розумію розгорнуті питання»*): #7 (polling) is the only one left. #5 is decided
(his table, extended) and #8 too (a switch, after #6 in the order).

**`AudioConfig` is his file, taken verbatim**: ours never changed it after the split, so his version
merges with no conflict. Our models reuse his tables instead of keeping copies; `ISO_MAX_OFFSETS` and
`FATIGUE_MAX_OFFSETS`, which he removed, stay only until #2/#3 port the loudness that reads them.
