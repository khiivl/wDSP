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

## 6. Why RadioText grows tails on `002121`

🔬 Reported by the reverse-engineering assistant working the same image, and partly corroborated
here: `FUN_0800643c` exists at `0x0800643c`, is reached when the MCU enters its tune state, and
does clear a set of buffers.

Its account: the function zeroes the RDS segment masks and counters and the PS buffer, but **not
the 64-byte RadioText buffer** (`0x2000048C`, with `0x20000625` a second candidate). So when a new
station sends a short RadioText, it overwrites only the front of the buffer and the tail of the
previous station's text survives — which is exactly the symptom the radio application spent weeks
cutting off by position.

⚠️ **The exclusion itself is not independently verified here** — the function and its clearing
behaviour are, the specific omission is not. Before acting on it, check which addresses that
function writes.

🧩 If it holds, it also explains why a retune makes the MCU resend the station name but not clean
the RadioText, and why only a power cycle clears it.

🧩 On `004121` (the NXP tuner) the reported behaviour is different and worse: a signal-quality check
strict enough that any block error resets the state, which reads as "RDS disappears at the
slightest provocation". That matches what a tester on that firmware reports — RadioText almost
never arrives.

## 7. Why a TSC4745 unit sounds muffled — the MCU never configures the tuner's audio

✍️ *Gemini, 12.09.2026*, 🔬 from the same `011021` image and Silicon Labs **AN332 Rev 1.0**
(*Si47xx Programming Guide*). ❓ **Not re-verified here.**

🔴 **Scope: `radioType = 1` (TSC4745, a licensed clone of the Silicon Labs Si4745).** Not the
TDA7708 (`2`) and not the TEF6686 (`4`). This is the same rule as §2 above — `0x88`/`0x8B` are
chip-specific — seen from the other side.

**The finding is an absence.** The tuner is driven over a bit-banged I2C at address `0x22`, and
`SET_PROPERTY` (`FUN_08009740`) is called in the entire firmware **exactly three times**, all three
for RDS: `0x1500`, `0x1501`, `0x1502`. Nothing else is ever set. After `POWER_UP` the tuner
therefore runs on Silicon Labs' factory silicon defaults for the whole of its audio behaviour:

| property | factory default | what it does in a car |
|---|---|---|
| `0x1100` `FM_DEEMPHASIS` | `0x0002` = **75 µs (US)** | Europe transmits with 50 µs. Listening to a 50 µs signal through a 75 µs curve costs about **−3.25 dB at 10 kHz and −3.5 dB at 15 kHz** — the "muffled, as if through a pillow" complaint, present on every station, always |
| `0x1A00` / `0x1A01` `FM_HICUT_SNR_*` | 24 dB / 15 dB | Below 24 dB of SNR the tuner starts rolling the top off and reaches full cut at 15 dB. City driving sits at 18–25 dB, so the band is **clamped to ~8 kHz most of the time** |
| `0x1A04` `FM_HICUT_MULTIPATH_TRIGGER` | 20 % | Reflections above a fifth trigger the same cut. In a street of buildings, constantly |
| `0x1800` / `0x1804` `FM_BLEND_*` | 49 dBµV / 27 dB | Stereo collapses to mono while the signal is still perfectly good, taking the width with it |
| `0x1302` `FM_SOFT_MUTE_MAX_ATTENUATION` | 16 dB | Aggressive ducking on noise |

🧩 So the tuner is not weak and the aerial is not necessarily at fault: the chip is running someone
else's country's settings with an adaptive treble cut left switched on. Every one of these is a
single `SET_PROPERTY` away from being right — but **Android cannot send them**: the MCU exposes no
command that reaches `FUN_08009740`.

✍️ Gemini's patch plan closes that: 184 free bytes at `0x0800BCF4`–`0x0800BDAC` hold a
`radio_auto_init` trampoline (76 B) that sets de-emphasis from the region byte at `0x20000224+8`,
disables the SNR and multipath Hi-Cut and widens the stereo blend; and 26 bytes of dead code at
`0x0800ac6a` become an in-place bridge so command `0x88` carries `[PROP_HI, PROP_LO, VAL_HI, VAL_LO]`
straight into `SET_PROPERTY`. ❓ **Designed, never flashed** — no byte has been written to any MCU,
and the owner's own unit is a TDA7708 anyway, so this cannot be tested on it.

⚠️ For the radio application this is the more interesting half of the two: de-emphasis is a fixed
−3.5 dB that no equaliser setting can honestly undo, and it is wrong on every TSC4745 unit in
Europe.
