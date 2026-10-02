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
- **our interface stays ours** — owner 02.10: *«у нас власний інтерфейс, зовсім не як авторський,
  тому нові елементи мають органічно ввійти в наш інтерфейс, з динамічною розміткою, як і інші»*.
  From his UI take what an element does, its ids, ranges and defaults; never his layout, his
  widget types or his `flexbox` rows — place it where it belongs in ours, in our style;
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
| 3 | **Trim Highs (fatigue)**: own start volume `_fat_start_vol` (default 25) instead of the loudness calibration point; new target — a dip at 3.15–5 kHz (`FATIGUE_RAW_TARGET`) instead of a high shelf | `McuService.updateFmOffsets`, `AudioConfig.FATIGUE_RAW_TARGET`; UI `seek_fat_start_vol` | `LoudnessCurve`, prefs, UI | absorbed: engine, the start-volume slider in our calibration card, the new message in 30 locales | ✅ `10a1373`, `20a65f1`, `4fd54a7` |
| 4 | **Ultra Bass**: sub gain ramp, 0 at `_ultra_bass_start_vol` (16) up to `_ultra_bass_max_db` (6) at volume 32, independent of loudness | `McuService.updateSubwoofer`; UI `switch_ultra_bass`, `seek_ultra_bass_*` | `LoudnessCurve` (one place for every volume-dependent offset), `McuService.updateSubwoofer`, UI | absorbed: `LoudnessCurve.ultraBassOffset` on the same ramp as Trim Highs (`rampAbove`); a toggle button in **our** loudness toggle row and slider rows in **our** calibration card, not his layout | ✅ `6e0f2e7` + `20a65f1`; 📋 owner's look at the five-button row, 28 translations ordered (board #1227) |
| 5 | **Sub-comp boost table** from `ISO_FULL_TARGET_DB` (80 Hz 8, 50/63 10, 25–40 12, ≥100 0) | `McuService.getMaxBassBoost` | `LoudnessCurve.maxSubBoost` | **his table, carried on by his rule** (the band below the crossover band): 25–80 Hz his 12 12 12 10 10 8, then 100–250 Hz 8 6 6 4 4 — owner 02.10: *«тобто, це буде його, але доповнена нами таблиця?»* | ✅ `e2d9b7e` |
| 6 | **Dedupe of computed packets** (`lastComputed*`) before the throttle; pref listener matches `preset + "_"` (a preset "Music" no longer reacts to "Music2_*"); `_fm*` changes resend sub and bass boost; `_bb_/_bf_` resend EQ | `McuService` listener and `update*` | `McuService` | absorbed; **ours, on purpose**: one `forgetChipState()` clears `mcuCache` and the computed packets together on ACC_ON / RESET_AUDIO_MCU (his are never cleared, which would stop our post-sleep re-apply); the spectrum still updates on an unchanged packet | ✅ `e48bf8f` |
| 7 | **Polling split** 100 ms → primary (volume/GALA) + secondary (player/bug) at 200 ms | `McuService.primaryRunnable/secondaryRunnable` | — | **not taken, superseded**: owner 02.10 «бери події першим» — one loop woken by `VOLUME_CHANGED`/`MUTE_EQ`/speed events, 100 ms while something moves, 1000 ms idle (`wakePoll`, `needsFastPoll`); it keeps the Call preset and the audio contract in one place | ✅ events |
| 8 | **GALA fixes** (shutdown state, standstill fallback, push on source change) | `McuService.checkVolumeAndGala` | our GALA | **merged, no switch** — owner 02.10: *«краще з двох світів»* (he first asked for a switch, then: «може звести а не перемикати?»). His shutdown skip and disabled-state sync were already ours in our own form; taken: on a source change write base + the running boost and end the poll | ✅ `3b6f6e6` |
| 9 | **Filter models for drawing**: sub LPF (2nd-order Butterworth), front/rear bass shaping (HPF + 1st-order shelf) | `AudioConfig.subFilterResponseDb`, `bassShapingResponseDb`, `compositeResponseDb`, `frequencyAt` | `DspResponse` (we have `lowPass2Db/highPass2Db/peakingResponseDb`; add the shelf) | absorbed: `DspResponse` composes his shapes and keeps no bell, LPF or HPF of its own (our RBJ biquad at the capture rate is gone); the doors' bass shelf in the model since `75ec773` | ✅ `c9897c3`, `75ec773` |
| 10 | **EQ view**: true Q 2.2 composite curve, loudness overlay, sub curve, rear bass curve, animation | `EqVisualizerView` (+558), `FmVisualizerView` (+161); colours `loudness_line`, `sub_line`, `rear_bass_line` | our views | absorbed into our look: `EqVisualizerView` (composite curve, front shelf, sub dashed, rear dash-dot where it differs, loudness dotted); the loudness tab shows what the chip is sent (`87db6a5`, shared `sentSubGainDb`/`sentBassShelf`). His "True 2.2" switch and flatness blend not taken — ours always draws the true curve | ✅ `87db6a5`, loudness tab not yet seen on the bench |
| 11 | **Spectrum**: pink-noise tilt (`8c71b6a`), loudness/sub/bass-reactive scaling, RTA | `SpectrumAnalyzerView` (+1015 incl. its own FFT/Visualizer) | `AudioSpectrumEngine` + our view — **keep ours**, port the features only | **nothing to port, present by construction**: his tilt (+3 dB/octave) corrects FFT-bin magnitude (energy per Hz) toward pink; our analyser sums energy per third-octave band (`analyzer.cpp`, `foldTo16Db`), so pink already reads flat — `test_analyzer` checks it, and adding his tilt would tilt us twice. His reactive EQ/loudness/sub/bass shift is our calculated spectrum: `DspResponse.compute` with the EQ, the loudness offsets, the doors' shelf (`75ec773`) and the subwoofer power-summed (his clamps the sub at 0 dB instead). His reactive flags have no UI in 0.5 either | ✅ not needed |
| 12 | **UI**: show loudness on main, sync L/R delays front/rear, sync front/rear bass (+ toast) | `MainActivity` (+793), `activity_main.xml` (+348), `layout-port` (+190 — we have no portrait copy) | our `MainActivity`, `activity_main.xml` | absorbed into our layout (no `layout-port`): toggles row via `RowFit`, pair locks on the title lines; delays keep the difference (owner 02.10); labels by the owner: «Передні/Задні рухати разом» + a hint when switched on, «Як спереду» | ✅ `800e27c`, `fec2648`, `70010ba` |
| 13 | **Strings**: 8 new keys in 7 locales; "F-M Curve" tab renamed "Correction" | `values*/strings.xml` | ours, 30 locales (Antigravity translates, I check) | en/uk done; 28 locales owed (`show_loudness_on_main`, `sync_*`, `hint_sync_delay_*`, `toast_loudness_sync_bass`); our tab is «Тонкомп.», his rename not taken | ⏳ |
| 14 | **Build**: `flexbox` 3.0.0 dependency; `<profileable android:shell="true">` in the manifest | `libs.versions.toml`, `build.gradle`, `AndroidManifest.xml` | ours | `<profileable>` taken verbatim (`f655d5a`); `flexbox` not taken — our layout does not use it | ✅ `f655d5a` |

### Late commits, 02.10 evening (found by the trial merge 03.10, which was aborted to port them first)

| # | his commit | what | status |
|---|---|---|---|
| 15 | `c64e6fc` | 172/214 Hz shelves back with tuned loudness rows (verified on hardware); rear Boost saves the manual value, not the mirrored one | ✅ `e34e830`: table verbatim, options back; the rear-save fix not needed — our UI never mirrors the rear widgets |
| 16 | `26b8d14` | GALA settings in a global namespace `__gala_global__` + "disable GALA for this preset"; MainActivity +229; McuService +63; spectrum +111; proxy `build.gradle` +25; `.gitignore`, `wdsp_app/build.gradle`; 6 locales | ⏳ |
| 17 | `8b1c0a9` | **reverts our** `fd447a9` ("GALA stopping after the volume is changed by hand": `lastReadHardwareVol = reportedVolume`); no reason given | ❓ owner |

## Order

1 → 9 (the model: pre-warp and filter shapes in `DspResponse`) → 2, 3 (loudness) → 4 (Ultra
Bass) → 6 (wire hygiene) → 8 (GALA, merged) → 12, 13, 14 (controls) → 10 (EQ views) → 11 (spectrum features).
Each lands as its own commit, verified by build and, where it changes what reaches the chip, by the
`TurboSender2000` log on the bench before and after.

Last step: `git merge origin/master` with every conflict resolved to the ported result (above).


Open questions for the owner — **one at a time, as a full sentence about the thing, never an item number** (owner 02.10: *«я не розумію пункти я розумію розгорнуті питання»*): none left: #7 (polling) went to events. #5 is decided
(his table, extended) and #8 too (a switch, after #6 in the order).

**`AudioConfig` is his file, taken verbatim**: ours never changed it after the split, so his version
merges with no conflict. Our models reuse his tables instead of keeping copies; `ISO_MAX_OFFSETS` and
`FATIGUE_MAX_OFFSETS`, which he removed, stay only until #2/#3 port the loudness that reads them.
