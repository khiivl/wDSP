# HANDOFF — wDSP slice

**16.10.2026, Claude session "wDSP mod" (after the cloud session's 00:30 slice).** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- **Everything is pushed:** `origin/kostyfmat_mod` = `dc6bbb0` (this slice on top); the desktop pulled it (fast-forward).
  `origin/master` (author) untouched at `5cc3860`.
- **Release 1.1 "Bulldozer" / 26 is handed out** (owner 15.10: «виставив людям»): XDA, Telegram, 4PDA.
  The pack is `~/Downloads/wDSP_Bulldozer_mod_by_kostyamat-v1.1.zip`, rebuilt from scratch from
  `052d301`: wdsp.1.1.apk (sha256 `0C8C106A…`, the one hash-checked on the bench) + 9 manuals +
  `screensaver_map.svg` + 9 `RELEASE_NOTES_1.1*.md` = 20 files. ❓ The owner confirmed 3 of 4 checks
  (APK, notes, 20 files); the final `ZIP: 20` after packing was not reported back.
- **Binaries never go into the repo** (owner 15.10): `/Release/` is in `.gitignore`; packs live on disk.
- Bench `192.168.1.138:9876`: the pack APK 1.1. **BitPerfect v5.6-Universal** (BitPerfect line
  released it 15.10 ~23:15, reboot done, board #1833). ❓ `0102[5:4]`.
- **The BU32107 EQ never reaches the sub** — memory `bu32107-eq-never-reaches-sub`; the "EQ is before the
  sub split" claim (Gemini 10.09/15.09) is wrong, `platform/03-SOUND-PROCESSOR.md` §5. Owed on the wire:
  doors muted, −10 dB at 31.5 Hz on the EQ — the sub's level must not move.
- Call is not media (`6c7aae4`) proven 15.10 on a BT call: no session sweep, analyser resumes after.

## Release docs: rules learned 15.10 (details in commits `461bb7c`..`dc6bbb0`)

- **Author vs mod is read from `origin/master`, never from our notes** — false "author's 1.0" claims
  and an invented "Loudness tab crash" reached XDA/Telegram before the owner caught them.
- Notes (9 languages): «from the author's app» / «only in the mod» / «new in 1.1»; the mod's value
  first. No CPU before/after numbers exist — do not invent a comparison.
- Manuals: UI names verbatim from `values-xx/strings.xml`. Support banners are at the TOP of Settings.
- Public posts (XDA, Telegram, 4PDA — no Telegram links there) were written in chat, not stored.

## ⏳ Next

1. Pre-release `mod-v1.1` on khiivl/wDSP: owner «подумаємо»; the author's consent first.
2. Untranslated app strings found in passing: the permission wizard, «Close», «Save Archive (.zip)»,
   «Target Sound Profile» in DE/ES/FR/IT/PL/PT; «Backup All Settings» / «Restore Settings» in RU.
   An app change, not docs — ask the owner.
3. Waiting for the author: the 20 Hz rule, Argon's model findings (TODO «Ближнє» 5, 6).
4. Daytime ear checks: band overlap (TODO «Ближнє» 4), sub delay applies at once (TODO).

## ❓ For the owner

- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
