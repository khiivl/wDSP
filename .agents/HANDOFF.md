# HANDOFF — wDSP slice

**15.10.2026 evening, Claude session "wDSP mod".** Rewritten, never appended; history is git.

## For the author's agent: where to start

Branch `kostyfmat_mod` is Kostiantyn's mod on top of the author's (khiivl) `master`; **all of
`origin/master` is already in it**. Owner's goal (08.10): bring the work to a near-finished product, hand
the author everything (every function described, a class roadmap) and close the project; open no new
fronts. A PR into `master` is the author's call. Branch map: [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md).
Every UI change goes into **Classic** (the author's) and **Modern** (ours), day and night.

## State

- Pushed: `origin/kostyfmat_mod` = `22d9cad` (manuals uk/en/es/ru, 28 translations by Сова).
  **Unpushed:** `fe0dd2f` (TODO) and the player-resume series `040cc07`..`279b0e1`.
- Bench `192.168.1.138:9876`: `279b0e1`, BitPerfect v5.5.15 on. ❓ `0102[5:4]`. Argon chat:
  32890fb409d8df41. Board #1813 → BitPerfect line (other account): a disabled module leaves
  `car.config music_volume=15`.
- **The BU32107 EQ never reaches the sub** - memory `bu32107-eq-never-reaches-sub`.

## ⏳ In progress: resuming the player after reboot / sleep (`PlayerResume`)

Owner 15.10: the right recipe is DefaultAppsChanger `c0bca87` (before Gemini's refactor `3111d5a`);
ours of 21.09 was copied from the broken one. Done so far: open the player, wait for it on screen,
+1.5 s, PLAY through its session (key to its package as fallback), verify, one more PLAY; streams
(BUFFERING or http/spotify media URI, `resume_streams_<pkg>`) wait for the network; state written with
`commit()`; `ACTION_SHUTDOWN` takes the ACC_OFF snapshot; the launcher goes back by its own HOME
(a launcher entry opened the chooser - 15 launchers, no default).

📻 Reboot 20:08: YouTube Music opened (via launcher - the remembered `.morphe_black_1` was an
icon alias), its session PAUSED at 2:56, two keys to the package did nothing - hence session-first
PLAY in `eb40b9b`, not yet seen on a reboot.

**🔴 Owner's last order, not yet done:** «ти аудіосесію плеєра маєш зберігати а не всю систему».
So: what is remembered must come from the player's media session, not from the system's foreground
(`noteForeground` / `sys.qf.current.activity`, `resume_activity_<pkg>`, `restoreForeground` - all
system-wide). Candidates to read before coding: the session's own media button receiver /
browser service component, and Android's own `Settings.Secure` `media_button_receiver` (the last
receiver, kept across reboot). Ask the owner one question if the reading is ambiguous.

Switches «after restart / after sleep» were never on in this unit's prefs - the owner turns them on.

## ⏳ Next after that

1. Release (owner: «поки день зробимо всі проби. І зарелізимо»); annotation 0.4.9.8 in TODO.
2. Waiting for the author: the 20 Hz rule, Argon's model findings (TODO «Ближнє» 5, 6).
3. Daytime ear checks: band overlap (TODO «Ближнє» 4), sub delay applies at once (TODO).

## ❓ For the owner

- The probe of undeclared doors (two short sweeps) — keep, or make it switchable?
- Card spinners in Modern night — how should they look?
