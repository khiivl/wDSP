# HANDOFF — wDSP slice

**16.10.2026 ~00:30, Claude cloud session (branch `kostyfmat_mod`).** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- **Everything is pushed:** `origin/kostyfmat_mod` = `052d301`; the desktop pulled it (fast-forward).
  `origin/master` (author) untouched at `5cc3860`.
- **Release 1.1 "Bulldozer" / 26 is handed out** (owner 15.10: «виставив людям»): XDA, Telegram, 4PDA.
  The pack is `~/Downloads/wDSP_Bulldozer_mod_by_kostyamat-v1.1.zip`, rebuilt from scratch from
  `052d301`: wdsp.1.1.apk (sha256 `0C8C106A…`, the one hash-checked on the bench) + 9 manuals +
  `screensaver_map.svg` + 9 `RELEASE_NOTES_1.1*.md` = 20 files. ❓ The owner confirmed 3 of 4 checks
  (APK, notes, 20 files); the final `ZIP: 20` after packing was not reported back.
- **Binaries never go into the repo** (owner 15.10): `/Release/` is in `.gitignore`; packs live on disk.
- Bench `192.168.1.138:9876`: the pack APK 1.1. **BitPerfect v5.6-Universal** (BitPerfect line
  released it 15.10 ~23:15, reboot done, board #1833). ❓ `0102[5:4]`.
- Call is not media (`6c7aae4`) proven 15.10 on a BT call: no session sweep, analyser resumes after.
- **The BU32107 EQ never reaches the sub** — memory `bu32107-eq-never-reaches-sub`; the "EQ is before the
  sub split" claim (Gemini 10.09/15.09) is wrong, `platform/03-SOUND-PROCESSOR.md` §5. Owed on the wire:
  doors muted, −10 dB at 31.5 Hz on the EQ — the sub's level must not move.
- Desktop: `git stash` holds Сова's raw translations (superseded by `8e9ce7f`) — droppable.

## What the docs now say, and why (15.10, after the owner caught false claims in public posts)

- **Attribution rule: author vs mod is read from `origin/master`, never from our notes.** The release
  notes had put Classic/Modern, the pair locks and "more visualisations" under "the author's 1.0";
  his master (1.0.2) has none of them. Also invented: "a crash on the Loudness tab" (the owner: the mod
  never had it, 1.0 came in already fixed). Both went into XDA/Telegram before being caught.
- **Notes structure (all 9 languages):** NEW = «FROM THE AUTHOR'S APP (khiivl, up to 1.0.2)» / «ONLY IN
  THE MOD» / «New in the mod in 1.1»; FIXED = «Author (khiivl)» (only the 1.0.1 sub-level race,
  `18cf110`) / «Mod · Start-up and processor load» / «Mod · Other». Owner: «а то читаєш і думаєш - а
  нахріна мені мод?» — the mod's value goes first and in full.
- **Only-in-the-mod facts, each checked against master:** cabin measurement; microphone (the author's
  code records nothing) incl. RTA on analogue FM (`RadioMicCapture`); native C++ analyser (author:
  Java `SpectrumAnalyzerView.processFft`, no native code); status bar visualiser + screensaver
  (`ScreensaverManager`, `StatusBarVisualizer*` absent in master); events instead of the author's
  two 200 ms loops (`McuService` primary/secondaryRunnable vs our `05dd980`, `46a717d`, `62919e2`);
  Modern (DECISIONS 05.10). Start-up/load items come from `e5caded` (checked against commits after
  13.09). **No CPU before/after numbers exist** — only ~14% of one core on the screensaver
  (SCREENSAVER_RADIO_CONTRACT §5); do not invent a comparison.
- **Support banners are at the TOP of Settings** (`activity_settings.xml:32`), not the bottom.
- **Manuals:** every `<span class="ui">` that has an app string carries `values-xx/strings.xml` verbatim
  (English where the app is untranslated). Сова had translated six languages from her own uk, not EN —
  fixed (`8e9ce7f`).
- **README:** 🇺🇦/🇬🇧 header links, full Ukrainian block, manuals + notes tables in 9 languages,
  visualisations/screensaver and player-resume sections, the same only-in-mod facts.
- Public posts were written in chat, not stored: XDA (BBCode, support in a named spoiler), Telegram
  EN/UA, 4PDA (RU, no spoiler, **no Telegram links**, support → "the two cards at the top of Settings").

## ⏳ Next

1. Who got 1.1 (testers / public) — record the owner's answer.
2. Pre-release `mod-v1.1` on khiivl/wDSP: owner «подумаємо»; the author's consent first.
3. Untranslated app strings found in passing: the permission wizard, «Close», «Save Archive (.zip)»,
   «Target Sound Profile» in DE/ES/FR/IT/PL/PT; «Backup All Settings» / «Restore Settings» in RU.
   An app change, not docs — ask the owner.
4. Waiting for the author: the 20 Hz rule, Argon's model findings (TODO «Ближнє» 5, 6).
5. Daytime ear checks: band overlap (TODO «Ближнє» 4), sub delay applies at once (TODO).

## ❓ For the owner

- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
