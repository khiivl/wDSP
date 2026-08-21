# The radio side

wDSP does not touch the tuner. This file exists because the knowledge base is meant to serve any
work on this platform, and because several of the facts here are really facts about **the MCU**,
which wDSP very much does touch.

Everything below was earned on a different project — a replacement FM radio application — over
about six weeks, mostly by being wrong first.

Provenance: 🔬 read in firmware or decompiled code · 📻 measured on the wire · 🧩 inference ·
❓ unverified.

## 1. The radio is hardware, and Android does not know it exists

🧩 The tuner is external equipment driven by the MCU. If it is playing, it **keeps playing** — with
the app closed, uninstalled, or the system rebooting. It stops when somebody else takes the audio
focus and the platform moves the mixer input.

So an application here is never "playing the radio". It is doing four separate things at once, and
[05-AUDIO-PATH.md](05-AUDIO-PATH.md) §"the player role" explains why they must never be derived
from one another.

## 2. Commands

`0xA0` plus a sub-command, `0xA1` for a few others. 🔬 The sub-command table is at `0x0800961A`
(first byte = count) and the radio core's own opcode table at `0x08008E5A`.

| frame | what |
|---|---|
| `A0 01` | seek |
| `A0 06` | set band — ⚠️ **the framework intercepts this** and pokes the audio routing chip 300 ms later |
| `A0 07` | LOC/DX — a **toggle** |
| `A0 08` | native auto-scan |
| `A0 0E` / `A0 0F` | next / previous preset (not seek, despite appearances) |
| `A0 11`, `A0 12` | AF, TA — **toggles** |
| `0x88` | 🔬 raw configuration bytes for the tuner chip itself, written into a structure the C code never reads — i.e. DMA'd out over I2C. 📻 The platform sends `88 80 80 40` four times at startup; that is the factory default |
| `0x8B` | 🔬 the same, one byte split into two nibbles |

🔴 **`0x88`/`0x8B` are chip-specific.** This platform ships three different tuner chips from three
vendors. Sending raw frames blind to an unknown tuner is at best a no-op and at worst deafens the
scan — which is exactly what was observed. Gate them on the chip type
([02-MCU.md](02-MCU.md) §1) or leave them alone.

## 3. Reply frames

📻 Counted over one session: `b1` 942 · `b7` 622 · `b6` 271 · `b4` 109 · `b0` 109 · `b5` 62 ·
`b3` 21 · `b2` 1.

| frame | contents |
|---|---|
| `b0` | status bits: searching, stereo, LOC |
| `b1` | frequency, and during a sweep the accumulated preset bank |
| `b2` | band limits — arrives once |
| `b3` | 🔬 RDS flags, bit 0 = AF, 1 = TA, 2 = REG, 3 = EON, **polarity direct** (1 = on) |
| `b4` | RDS present |
| `b5` | `[B5][filter][programme type]` |
| `b6` | PS, the station name |
| `b7` | `[B7][64 bytes]` = exactly 65 bytes, no trailing service field |

🔴 **The MCU does not report signal strength.** There is no RSSI field in any frame. The chip
measures it — that is what `0x88` configures — but only "stopped / did not stop" comes out. The
indirect quality indicators you do get are **stereo** (`b0`) and **whether RDS decodes** (`b4`).

## 4. Things that cost days

📻 **A seek sent too early does not queue — it kills the operation in progress and vanishes.** 🔬
The firmware has one busy flag at `0x2000038C`; the gate at `0x080097F0` aborts and returns, and
the seek handler exits silently. `tune` calls the same gate but ignores the result, which is why
"tune revives the MCU but seek is still dead".

📻 The marker for "ready" is that the MCU reports the final frequency **three times**. Two
confirmations meant the next seek was eaten — 3 out of 3 times. The original application never hits
this because a human finger takes 2.4–5.6 seconds between actions.

➡️ Arm the next seek on a **count of confirmations**, not a delay. Detect a wedge by *silence*,
not by a step timer: a healthy sweep sends `b1` every ~140 ms and the longest legal pause is
1735 ms.

📻 **After its own sweep the MCU goes quiet** — five minutes of nothing but the heartbeat, while the
radio plays, with the RDS decoder not raised. The cure is a second `tune`, which is what a finger
does. Do it on the confirmation event, not on a timer.

📻 **The native auto-scan tops out at 18 stations** because the MCU has 3 banks × 6 slots and writes
into them. A seek loop has no such ceiling: seek does not write the banks at all. Measured head to
head, the software scan found a **superset** of the hardware scan's results and was one second
slower over 41.

🔬 **Some commands are toggles**, which was learned by having a "set" call flip LOC on every start,
so every tap on AF/TA also flipped LOC and the sweep ran alternately in two sensitivity modes. The
symptom looked like unstable hardware.

📻 **The RadioText buffer inside the MCU is shared and dirty.** It survives an Android reboot,
because only power resets the MCU. Cut it by position on the first `0x00`; do **not** cut on `0x0D`
even though the standard says so — the MCU puts `0x0D` at the *start* of the buffer too.

📻 A retune makes the MCU re-send the station name — 11 times out of 12, at a cost of 1.7 s. It does
**not** clear the RadioText buffer.

## 5. Region and units

🔬 The MCU has a region setting (`persist.sys.radio_area`) that fixes band limits and grid step —
Europe is 100 kHz, the US 200. **Do not hard-code it.**

⚠️ In the decompiled original and everything derived from it, FM frequencies are in units of
**MHz × 100** despite field names saying kHz: `8750` means 87.50 MHz. AM is in real kHz. The
asymmetry is inherited, not invented, and multiplying "to fix it" moves the band limits by two
orders of magnitude and throws away every station found.

📻 Below 0.05 MHz the hardware simply does not tune. Fine-tuning finer than that is not available.
