# HANDOFF — wDSP slice

**15.10.2026 21:50, Claude session "wDSP mod".** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- Pushed: `origin/kostyfmat_mod` = `1fc3f3e`. **Unpushed:** `6c7aae4` (call is not media),
  version 1.1 "Bulldozer" / 26, release notes in 7 languages, the slices.
- Bench `192.168.1.138:9876`: the pack APK 1.1 (sha256 `0c8c106a…`, hash checked on the unit). BitPerfect v5.5.15 on. ❓ `0102[5:4]`.
- **The BU32107 EQ never reaches the sub** - memory `bu32107-eq-never-reaches-sub`.

## ⏳ In progress: release 1.1 "Bulldozer", code 26 (owner 15.10: «Код релізу в.1.1 як в автора»)

- Bases final (`1fa31fc`): RELEASE_NOTES_1.1 EN+UA (plain language), manual_en.html (read through,
  map, doctype/charset), README (mod vs original). Сова (board #1832, strict: translation only,
  after a reprimand #1830 for invented fixes) translates notes RU/ES/DE/FR/IT/PL/PT and manuals
  uk/es/ru/de/fr/it/pl/pt. Then: check them, commit, rebuild the pack folder
  `~/Downloads/wDSP_Bulldozer_mod_by_kostyamat-v1.1/` (wdsp.1.1.apk `0c8c106a…` + 9 manuals + svg
  + 9 WHATS_NEW) and zip it myself. The zip Сова made there holds her bad notes - do not hand out.
- `6c7aae4` proven 15.10: BT call 21:52:56-21:54:14 - no resolve, 0 Visualizer failures, the
  analyser resumed at the end.
- Not proven on the unit, shipped on order: the volume-steps table, «cabin noise is no refusal»,
  the control sweep verdicts. Player resume after reboot/sleep - owner 15.10: «працює».
- The 0.4.9.9 pack in Downloads is superseded, never handed out.
- Pre-release `mod-v1.1` on khiivl/wDSP: owner «подумаємо»; the author's consent first.
- Who gets 1.1 (testers / public) - the owner says; record it.

## ⏳ Next after that

1. Waiting for the author: the 20 Hz rule, Argon's model findings (TODO «Ближнє» 5, 6).
2. Daytime ear checks: band overlap (TODO «Ближнє» 4), sub delay applies at once (TODO).

## ❓ For the owner

- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
