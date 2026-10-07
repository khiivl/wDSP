# CLAUDE.md

@.agents/AGENTS.md
@.agents/HANDOFF.md

Everything else — the rules, the build, the prohibitions, the dependencies — is in the two files above and is shared
with every agent. Knowledge lives in `.agents/` behind [INDEX.md](.agents/INDEX.md); QF hardware facts live in the
`qf-platform` skill and are not duplicated here.

## Claude-specific

- The primary working directory of a session may be `qf_fmradio` (an empty leftover). This repository is an
  *additional* working directory — always use absolute paths into `C:\Users\kosty\AndroidStudioProjects\wDSP`.
- Skills worth loading here: `qf-platform` for anything touching the head unit, `agent-bridge` before writing to the
  board.
