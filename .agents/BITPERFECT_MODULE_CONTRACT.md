# wDSP ↔ BitPerfect — the audio path and the module that changes it

> Canonical text. Mirrors live in the trees and point here; edit here, then copy across, never the
> other way round. Rules of the folder: `C:\APPS_Contacts\README.md`.
> Written in English per the owner's language rule of 28.09.2026; his own words stay as he said them.

**Parties**

| side | what it is | who leads it |
|---|---|---|
| **BitPerfect** | the Magisk module `BitPerfect.module` — a radically changed audio path — and the still-empty control app `com.radiorubka.bitperfect` | the BitPerfect Control session `3827b401-ee38-4390-8b5a-8b5a12ad49b7` — **both** the module and the app, ruled by the owner 29.09.2026 |
| **wDSP** | `com.radiorubka.wdsp` — the equaliser and cabin measurement, and the owner of the system audio path | the Claude wDSP session |
| *affected third party* | `kostyamat_fmradio` — the radio | the Claude Main (Radio Dev) session |

The radio is named because it shares the path: it plays through the MCU channel, past AudioFlinger,
so a module fault can be inaudible to it and fatal to everything else — or the reverse.

---

## Why this contract exists at all

The owner, 29.09.2026: BitPerfect *«це радикально змінений аудіотракт, що може вплинути на любий наш
проект»*, and it was deliberately set up so that **one specific session** carries it.

wDSP raised the objection that produced this split, and it is the reason the handover happened
rather than a merge of duties:

> One head over both the module and the application that measures it is a risk, not a convenience.
> While the module is somebody else's work, wDSP catches its faults and says so out loud. Once the
> module is wDSP's own, the temptation appears to cure the consequence in the application — tweak a
> compensation, route around a dead path — and the module's fault quietly leaves the field of view.
> That is exactly how "works for the owner, fails for the tester" is made.

**Therefore the standing rule for both sides:**

🔴 **A claim about the module is confirmed on the wire, never by wDSP's own analyser.**
`dumpsys media.audio_flinger`, `tinymix`, `/proc/asound/card0/pcm3p/sub0/status`, the HAL log.
wDSP's spectrum sits on the same path and cannot be a witness about it.

---

## Why v5.4.x exists at all — read this before reverting anything

The owner, 29.09.2026. The recent versions are not polish; they are an attempt to reach two things
at once, and the incoming session must not undo them by accident:

1. 🎤 **An unprocessed microphone natively** — so that wDSP no longer has to hunt for the way round
   it. Today the route is a fight: whoever opens the input first sets its format, the assistant
   holds it at 16 kHz from boot, and `VOICE_RECOGNITION` does not lead where `audio_pcm.xml`
   promises. v5.4 routes normal and recognition capture to the unprocessed ALSA device 0 without a
   DSP lock. **If that holds on the wire, several of wDSP's workarounds become unnecessary** —
   which is a reason to verify it rather than to assume it.
2. 📞 **Compatibility with the calling experiment** — the head unit made to work as a phone, calls
   over the network from the SIM. This bore fruit: the calls work.

The owner's own summary of the cost: it worked, and *«довело мене до сказу, чому я затіяв всі ці
чистки»*.

**What the calling side actually consists of** (so nobody rediscovers it the hard way):

- the dialer was taken from a Teclast tablet — a Google dialer — and the telecom part is Topway's.
  🔴 The two are **not fully compatible**, the Google dialer especially;
- the head unit's own notification shade **cannot display an incoming call** or offer a button to
  accept it. A sketch project that restores the notification functions is being modified to cover
  this;
- along the way there was no ringtone at all until it was fixed.

🔴 **That project is led by a Claude session too**, like the rest of the commercial work (the
owner's ruling of 29.09.2026). Gemini is on support: it is given work, not ownership. The reason is
named plainly — while it led this, it broke one thing after another, and **because it kept git badly
it could not get back to a known state**. Not the breaking; the inability to return.

⚠️ **This explains the overlay collision, and makes it worse than it looked.**
`qf_cellular_calling_master` and BitPerfect both write
`primary_audio_policy_configuration.xml`, and now it is clear they are not unrelated neighbours —
**both serve the same feature**. Whichever mounts last wins, so the calling path and the
bit-perfect path can silently cancel each other. Resolving this is item 3 of the ledger, and it is
not cosmetic.

---

## Who owns what

| axis | BitPerfect | wDSP |
|---|---|---|
| audio policy files, PCM descriptions, routes (`audio_pcm.xml`, `primary_audio_policy_configuration.xml`, `qf_audio_route_*`) | **owns** | reads, reports faults |
| bit depth and sample rate on the wire | **owns** | verifies and reports |
| capture effects on **its own** session (AEC/NS) | ⚪ | **owns** — suspends for a measurement and restores as taken |
| the equaliser, delays, crossover, the preset on the chip | ⚪ | **owns** |
| volume level (base + GALA offset) | ⚪ | **owns** — see `wDSP--QFRadio` |
| the MCU channel | ⚪ | ⚪ — belongs to whoever plays; the radio declares its route once |
| ❓ routing and muting for the control app | **proposed**, never approved by the owner | — |

⚠️ The last row came into memory through a third party, not from Kostyantyn. Ask him; do not act on
it ([[who-kostyantyn-is-and-how-to-work]]).

---

## What wDSP hands over, and what it keeps

**Handed over in full:** the module's source and releases, its git repository, its version numbering,
its release notes, the decision of what the module writes into the audio policy, and answering for
its faults.

**Kept by wDSP:** the register of the platform's own axes
(`wDSP/.agents/platform/08-VOLUME-AND-SOURCES.md`, section "own axes"), the measurement chain, and
the duty to report what it sees on the wire — including when it is the module's fault.

---

## Facts the incoming session should not re-derive

📻 measured on the wire · 🔬 read in code · 🧩 inferred · ❓ unverified

- 📻 **Where the module lives:** `D:\My_K706_Magisk_Modules\BitPerfect2\`, **its own git repository**,
  remote `git@github.com:kostyamat/BitPerfec-Audio-Qf-module-Magisk.git`, branch `v5.4-repaired`,
  tree clean as of 29.09.2026. Commits are authored `kostyamat`.
- ⚠️ **The declared version and the built one disagree**: `module.prop` says `v5.4-Universal`
  (versionCode 504), while the directory holds a built `v5.4.1` zip dated 27.09 20:43. The unit has
  **5.4** installed. Settle this before trusting either number.
- 📻 **v5.3 left `pcmC0D3p` busy** with a live HAL holding a second `MMAP_INTERLEAVED S24_LE
  period 1040` stream in `SETUP`, and TTS completely silent. The owner: *«Бітперфект явно калічний…
  поточна версія повністю робить ТТС беззвучним»*, and restarting audioserver *«не виправить
  проблему»* — so **do not restart it to make a symptom go away**.
- 🔬 **The stranded stream is not the module's doing.** `McuManagerService.onVersionInfoChanged`
  calls `ctl.restart audioserver` whenever the old MCU version is empty or differs — on a cold start
  it is empty *always*. An earlier conclusion blaming a race with the Magisk mount was **disproven**.
- 📻 **v5.4 rasped** because it added `PCM_16_BIT 48000` to the `primary output` mixPort and the
  `Speaker` devicePort while the module holds the I2S bus in `WD_24BIT`. Fixed by making both
  `PCM_8_24`. `DAC LRCLK Select invert` is **factory** — not the cause, do not touch it.
- 🔴 **`VBC DAC0 DG Set = 32` is correct and settled by the owner:** *«це вивірене значення… справжнє
  значення на яке реагує HAL 0-63, 32 рівно половина»*. Look elsewhere for rasp.
- 🔬 **The HAL picks the route itself** — it parses `persist.sys.qf.mcu.version`, publishes
  `use.i2s`, and chooses one of four `qf_audio_route_*` files by two flags. A module has nothing to
  guess. 🔴 `customize.sh` copies a profile **over** `system/` and `cp -rf` deletes nothing, so a
  surplus file from another chipset can survive — worse than a missing one.
- 💎 **AGDSP parameters play from `/data`**, not `/vendor`: `/data/vendor/local/media/audio_params/`,
  with `/vendor/firmware/*` symlinked into it. AEC, NR, mic gain and the voice EQ can be tuned
  **without a `/vendor` overlay** — and it is the `/vendor` overlay that hangs the boot on 8581.
- 📻 **`NoiseSuppressor.isAvailable()` is a ready-made sign of custom policies** — false on stock,
  true with the module, no root and no permissions needed.
- 🔴 **Two modules overlay the same file:** this one and `qf_cellular_calling_master` both write
  `primary_audio_policy_configuration.xml`. Whichever mounts last wins. Open in wDSP's `DEBT.md`.
- 🔴 **Read the boot-logger module, not `logcat`** — the buffer here rotates within a minute, and a
  retry storm evicts all five rotation files in about 20 s. `logcat -c` is forbidden.

---

## Ledger

One column per side. Edit only your own. An item closes when **both** marks are present, and a mark
names its evidence — a commit, a measurement, a line in a log.

| # | item | BitPerfect | wDSP |
|---|---|---|---|
| 1 | Handover accepted; the incoming session has announced itself on the board | ✅ accepted 29.09 — the owner ruled in chat that this session leads **both** the module and the app ("так, це ти і модуль твій"); announced in board #853 | ✅ board #853 — session `3827b401-ee38-4390-8b5a-8b5a12ad49b7` announced itself 29.09; handed over in #856 |
| 2 | `module.prop` and the built zip agree on a version | ❌ | ⚪ |
| 3 | The two-module overlay collision on `primary_audio_policy_configuration.xml` is resolved or accepted with a named reason | ❌ | ❌ recorded in `DEBT.md` |
| 4 | Every claim about the module in either tree carries wire evidence | ❌ | ⏳ rule stated above |
| 5 | The zone boundary for the control app (routing and muting) is put to the owner and answered | ❌ | ❌ |
| 6 | The radio line is told what changes for it | ❌ | ✅ board #859 — the overlay collision, why v5.4.x exists, and the stale skill copies |

---

## Where the knowledge is

Platform facts live in the `qf-platform` skill, not in any project:
`09-NAVIGATION-AND-BITPERFECT.md`, `10-BITPERFECT-MODULE.md` (§7 the routes, §12 the AGDSP
parameters), `08-VOLUME-AND-SOURCES.md`, `02-MCU.md`. The live copy is `wDSP\.agents\platform\`;
anything added there carries a provenance mark. The decompiled platform is in `D:\De-compiled\`.
