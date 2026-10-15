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
  `ab204f2` (version 0.4.9.9 / 26), `f89293e` (release notes EN+UA), this slice.
- Bench `192.168.1.138:9876`: 0.4.9.9 built **before** `6c7aae4` (sha256 `77f97f51…`), installed
  21:45 by mistake during the owner's BT calls. BitPerfect v5.5.15 on. ❓ `0102[5:4]`.
- **The BU32107 EQ never reaches the sub** - memory `bu32107-eq-never-reaches-sub`.

## ⏳ In progress: release 0.4.9.9 (owner 15.10: «читай зріз і збирай реліз»)

- Pack folder `~/Downloads/wDSP-0.4.9.9/`: `wdsp.0.4.9.9.apk` (release build with `6c7aae4`,
  sha256 `0c90a87c…`), WHATS_NEW_EN/UA.txt, manual_{en,uk,es,ru}.html. Not zipped yet.
- Waiting: RU/ES/DE/FR/IT notes from Сова (board #1821) -> `docs/release_notes/`, commit, copy
  into the pack as WHATS_NEW_<LANG>.txt, zip `wDSP-0.4.9.9.zip`.
- Before handing out: install the pack APK on the bench when the owner is off the calls, compare
  the hash, and one BT call must show no «Resolving audio session» between «call in progress»
  and its end.
- Not proven on the unit, shipped on order: the volume-steps table, «cabin noise is no refusal»,
  the control sweep verdicts. Player resume after reboot/sleep - owner 15.10: «працює».
- Who gets 0.4.9.9 (testers / public) - the owner says; record it.

## ⏳ Next after that

1. Waiting for the author: the 20 Hz rule, Argon's model findings (TODO «Ближнє» 5, 6).
2. Daytime ear checks: band overlap (TODO «Ближнє» 4), sub delay applies at once (TODO).

## ❓ For the owner

- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
