# The sound processor itself: ROHM BU32107EFV-M

Everything below is from the ROHM datasheet (TSZ02201-0C2C0E500500-1-2, Rev.001, 07.Apr.2017,
116 pages) cross-checked against the head unit's own MCU firmware, image
`QF05.V02.13.20251124.002121`, decompiled at `C:\MCU\d002121.c`. Where the two agree, the fact is
stated plainly. Where only one of them says it, that is said too.

This matters because the app never talks to the chip. It sends a byte or two to the MCU over the
QF serial protocol, and the MCU decides what register that becomes. Until now that translation was
guessed at, from Chinese source and from measurement. Now it can be read.

## 1. How the MCU reaches the chip

`FUN_08004a58(selHigh, selLow, len, data)` in the MCU firmware performs one I2C transaction:

```
START  0x80  selHigh  selLow  data[0] .. data[len-1]  STOP
```

`0x80` is the chip's write address. `selHigh:selLow` is the datasheet's **Select Address**, and
the chip auto-increments it across the data bytes, so a whole block goes in one transaction.
`FUN_08004a20` is the same thing with five retries.

Everything the app can change eventually arrives here. Finding the callers of `FUN_08004a20` is
therefore the way to map any DSP feature to a register.

## 2. The register blocks that matter to us

| Select | What lives there |
|---|---|
| `0001`–`0008` | system, mute, power |
| `0100`–`010A` | input selectors, analog gain |
| `0200`–`0208` | DSP routing selectors, **Time Alignment Mode (`0203`[3])**, Noise Gen (`0204`) |
| `0400`–`040D` | **Time Alignment**, 7 channels x 2 bytes |
| `0500`–`0501` | **16-band spectrum analyser** built into the chip |
| `0600` | EQ Mode / Pre- and PostScaler |
| `0610`–`061F` | **EQ, front**, 13 bands + 3 more |
| `0620`–`062F` | EQ, rear |
| `0700`–`0709` | DC-cut, loudness filter, **front/rear HPF**, **P2Bass**, **sub LPF/HPF**, IIR |
| `0800`–`0805` | beep, DVol(Output2) |
| `0900`–`090B` | **DVol** attenuate and boost, 6 channels each |
| `0A00`–`0A05` | analog fader volume, 6 channels |
| `1000`–`1014` | direct biquad coefficients for EQ/tone (32-bit b0,b1,b2,a1,a2) |
| `1100`–`1114` | direct biquad coefficients for HPF and surround IIR |
| `1200`–`1214` | direct coefficients for loudness |

## 3. Time Alignment — measured, decompiled and documented, all three agreeing

**The register is samples.** The datasheet gives the rule and the example outright:

> Send data(hex) = "Time Alignment Time" x "48". Example: 2.5 ms x 48 = 120 -> 78(hex)

Ten bits, `0400`/`0401` high and low for FL, then FR, RL, RR, S, RL2, RR2 in pairs up to `040D`.

| | ceiling | register |
|---|---|---|
| 2ch-stereo input mode | **21.3 ms** (23.0 ms at 44.1 kHz) | `3FF` = 1023 samples |
| 4ch-independent input mode | 10.6 ms | `1FF`; **`200`–`3FF` prohibited** |

Our unit is in 2ch mode: a sweep measurement saturated at **21.271 ms**, which is 1021 samples.
Mode lives in `0203`[3] — worth confirming that the firmware never switches it, because in 4ch
mode the values we now allow would be prohibited ones.

### What the app's byte becomes

Positional delays, MCU command `0x8C`, five payload bytes FL FR RL RR S:

```c
uVar11 = FUN_08003bca(byte * 0x30, 10);        // byte * 48 / 10
*(short *)(S + 0x14) = uVar11;                 // FL, then 0x16 FR, 0x18 RL, 0x1a RR, 0x1c Sub
if (DAT_0800c330 < uVar11) *(short *)(S + 0x14) = DAT_0800c330;   // clamped
```

The app sends `slider * 5`, so a step of one on the slider is `5 * 4.8 = 24` samples = **0.5 ms
exactly**, and the payload byte is plainly "tenths of a millisecond". The label inherited from the
original firmware was right. Measured on the wire it comes out at 0.4990 ms per step, two parts in
a thousand from nominal, which is the measurement's error and not the chip's.

**The MCU clamps.** Whatever we send, `DAT_0800c330` bounds it, so the app cannot produce a
prohibited register value by asking for too much. This is why raising the sliders to 40 steps
(20 ms) is safe rather than merely untested.

### Where the values are written out

`FUN_08005154` fills the outgoing frame, and it has two branches selected by bit 2 of the flags at
`S+10`:

| frame slot | register | surround branch | positional branch |
|---|---|---|---|
| `+0x0E,0x0F` | `0400` FL | field `0x0C` | field `0x14` |
| `+0x10,0x11` | `0402` FR | field `0x0E` | field `0x16` |
| `+0x12,0x13` | `0404` RL | field `0x10` | field `0x18` |
| `+0x14,0x15` | `0406` RR | field `0x12` | field `0x1a` |
| `+0x16,0x17` | `0408` S | *not written* | field `0x1c` |
| `+0x18,0x19` | `040A` RL2 | same as RL | same as RL |
| `+0x1A,0x1B` | `040C` RR2 | same as RR | same as RR |

So both modes drive the same seven registers; they differ only in which set of fields they read.
RL2 and RR2 always mirror RL and RR.

### 🔴 The surround delays are not one millisecond per step

MCU command `0x89` writes the other set of fields, and it scales differently:

```c
*(ushort *)(S + 0xc) = byte1 * 0x66;   // 102
*(ushort *)(S + 0xe) = byte2 * 0x66;
*(ushort *)(S + 0x10) = byte3 * 0x66;
*(ushort *)(S + 0x12) = byte4 * 0x66;
```

The app sends the `_d1_*` slider value raw, 0 to 10. The field is in samples, as established above,
so one step is `102 / 48` = **2.125 ms**, and the slider's full travel is 1020 samples = **21.25
ms** — the whole range the chip has.

The interface labelled these sliders 1.0 ms per step for as long as the app has existed.

🟢 **Measured, and the firmware was right.** `--ei delaytest 2` holds the routing still and moves
this line between sweeps:

```
 3 steps ->  +6.354 ms    2.1181 ms per step
 6 steps -> +12.688 ms    2.1146
10 steps -> +21.167 ms    2.1167
```

Arithmetic says 102/48 = 2.1250. The measurement says 2.1165, four parts in a thousand away, and
nothing at all like 1.0. So a Surround slider at ten was never 10 ms — it was the whole 21.2 ms the
chip has, and the distance printed beside it was out by 386 cm.

Only the label was wrong. The sliders always did this, so no saved preset changes meaning: the same
setting produces the same sound, and now says so honestly. Fixed in `MainActivity`,
`SURROUND_DELAY_STEP_MS`.

Note also that nothing clamps the `0x89` path. Ten steps lands on 1020, just inside the 1023
ceiling, which is very probably why the range is ten. **Do not raise these sliders** the way the
positional ones were raised.

## 4. EQ — sixteen bands, and they are the chip's own

The app's sixteen sliders are not a construction of its own. They are the chip's 13-band EQ plus
the three-band EQ that can replace the tone controls, and they are in register order:

| band | f | select | band | f | select |
|---|---|---|---|---|---|
| A | 20 Hz | `061D` | 7 | 800 Hz | `0616` |
| B | 31.5 Hz | `061E` | 8 | 1.25 kHz | `0617` |
| 1 | 50 Hz | `0610` | 9 | 2 kHz | `0618` |
| 2 | 80 Hz | `0611` | 10 | 3.15 kHz | `0619` |
| 3 | 125 Hz | `0612` | 11 | 5 kHz | `061A` |
| 4 | 200 Hz | `0613` | 12 | 8 kHz | `061B` |
| 5 | 315 Hz | `0614` | 13 | 12.5 kHz | `061C` |
| 6 | 500 Hz | `0615` | C | 20 kHz | `061F` |

One byte each:

```
[3:0]  gain 0..24 dB in 2 dB steps (0..12; 13..15 prohibited)
[4]    0 = boost, 1 = cut
[5]    Q: 0 = 4.7, 1 = 2.2
[6]    0 = front only, 1 = front and rear together
[7]    0 = use the table, 1 = use direct coefficients from 1000..1014
```

Rear is the same layout at `0620`–`062F` without bits 6 and 7.

This explains what the app does. Command `0x80` carries eight bytes packing sixteen 4-bit gain
indices where 6 means 0 dB, then two bytes of Q as a bitmap, one bit per band. The MCU turns index
into the datasheet's magnitude-plus-sign form. **The 2 dB step is the hardware's**, not a choice
anyone made, and so is the ±24 dB limit. Anything that wants finer than 2 dB has to go through the
direct coefficients at `1000`–`1014`, which are ordinary 32-bit biquads.

## 5. Crossovers, bass and the subwoofer

| register | fields | app |
|---|---|---|
| `0703` front HPF | `[3:0]` fC, `[4]` 2nd/4th order, `[5]` phase 0/180, `[7]` direct coef | `0x88` byte 3 high nibble |
| `0704` rear HPF | same, no direct coef | `0x88` byte 3 low nibble |
| `0705` P2Bass front | `[7]`=1, `[6:4]` fC, `[3:0]` gain 0..12 dB, 1 dB steps | `0x88` byte 1 |
| `0706` P2Bass rear | same | `0x88` byte 2 |
| `0707` sub LPF | `[3:0]` fC, `[4]` order, `[6]` phase, `[7]` direct coef | `0x8B` high nibble |
| `0708` sub HPF | `[3:0]` fC | not exposed |

HPF fC codes 0..11: Through, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250 Hz.
Sub HPF fC codes 0..11: Through, 20, 25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200 Hz.
P2Bass fC codes 0..7: Through, 54, 68, 86, 108, 134, 172, 214 Hz.

The app builds `0x88` byte 1 as `((freq + 8) << 4) | gain`, which is exactly the `0705` byte with
its mandatory bit 7 already set. So P2Bass gain really is dB, one per step, straight through.

The sub high-pass — the subsonic filter that keeps a ported box from unloading — exists in the
chip and the app does not expose it.

## 6. Two things the chip has that the app is not using

**A 16-band spectrum analyser**, `0500`–`0501`. Sixteen band-pass filters at 20 Hz to 20 kHz with a
selectable Q of 2.4, 3.6, 5.1 or 7.5 and 0 to 36 dB of gain in 2 dB steps, reading out over the
same bus. The app currently computes its spectrum on the CPU from a `Visualizer` capture, which
costs latency and processor time and measures the Android mix rather than what the DSP is doing.

**A noise generator**, `0204`, white or pink. Room calibration is currently done by playing a
sweep through the media path, which means the sweep goes through everything the DSP is doing to it
and through whatever Android does on the way. A generator inside the DSP would bypass all of that.

Both are worth reaching for, and both need the MCU to expose them: neither has a QF command today,
as far as the firmware shows.

## 7. How the firmware actually drives the chip, and what that costs us

Reading the firmware end to end turned up the mechanism, and the mechanism answers several
questions that used to be guesses.

### The shadow register file

`FUN_08004e64` is the only thing that writes ordinary registers. It holds two byte arrays — what
the chip *should* contain at `0x200000E2`, and what was last actually written at `0x20000268` —
walks indices 8 to 0x80, and for every byte that differs sends exactly one register write. When
nothing differs it rewrites one register per pass in a rolling cycle, so a chip that lost its state
heals itself within a second or two.

The index-to-register map is a table in flash at **`0x0800CFD7`**, two bytes per entry, and it is
the complete list of everything this firmware can address:

| index | registers | | index | registers |
|---|---|---|---|---|
| `00`–`07` | `0001`–`0008` system | | `3F`–`4E` | **EQ front, 16 bands** |
| `08`–`1A` | `0010`–`0022` | | `4F`–`5E` | **EQ rear, 16 bands** |
| `1B`–`24` | `0101`–`010A` inputs | | `5F`–`68` | `0700`–`0709` filters |
| `25`–`2D` | `0200`–`0208` routing | | `69`–`6E` | `0800`–`0805` beep |
| `2E`–`3B` | `0400`–`040D` delays | | `6F`–`7A` | `0900`–`090B` DVol |
| `3C`–`3D` | `0500`–`0501` analyser | | `7B`–`80` | `0A00`–`0A05` fader |
| `3E` | `0600` EQ mode/scaler | | | |

So the rear equaliser, the subwoofer's high-pass, the spectrum analyser and the noise generator are
all reachable by the flusher. Whether anything ever *fills in* their slots is a separate question,
and for some of them the answer is no.

### 🔴 The equaliser is locked to front-and-rear together, and Q is ignored

`FUN_080050d4` composes the sixteen EQ bytes whenever bit 0 of `S+10` is set. Disassembled, each
byte it writes is

```
value = |gain - 6|  |  0x60      when boosting
value = |gain - 6|  |  0x70      when cutting
```

and nothing else is ever OR-ed in. Read against the register layout in §4, that means **bit 6 is
permanently 1 — "front and rear common" — and bit 5 is permanently 1, Q = 2.2**.

Two consequences, both worth knowing before promising anything:

- **Independent front and rear equalisation cannot be reached through this firmware.** The chip has
  it and the flush table has room for it, but bit 6 is nailed high and the rear slots are never
  filled, so the chip mirrors the front curve into the rear itself.
- **The per-band Q the app sends is dead.** Command `0x80` carries two bytes of Q flags, the
  handler stores them at `S+0x32` and `S+0x33`, and nothing in the firmware ever reads them back.
  Every band is Q = 2.2 whatever the interface says.

Both were checked at instruction level, not from decompiled C, because the whole claim rests on
which bits are set in one constant.

### 💎 The twelfth byte of command `0x80` selects a factory curve

The app has always sent a twelfth byte of zero. It is not padding. The handler stores it at
`S+0x34`, and the composer reads it:

```c
gain[i] = (S[0x34] == 0) ? our_gain[i]                       // what the sliders say
                         : curve_table[S[0x34] * 0x10 + i];  // a curve built into the MCU
```

The table is at **`0x0800D16D`**, sixteen bytes per curve, in the same 0..12 index the app uses
with 6 as flat:

```
1:  6 6 6 6 6 6 6 6 6 6 6 6 6 6 6 6      flat
2:  7 7 9 9 9 5 0 3 5 6 5 6 8 8 7 6      deep bass and treble, midrange scooped out
3:  6 7 6 5 5 3 3 3 4 4 4 4 6 8 7 5
4:  5 6 6 6 6 3 3 3 6 5 4 4 5 7 6 5
5:  6 6 7 6 7 8 7 6 6 4 4 6 6 8 6 6      forward midrange
6:  6 6 4 6 5 5 5 6 9 10 6 6 8 8 7 6     presence lift at 1.25-2 kHz
7:  6 6 7 8 8 6 6 4 6 6 6 7 6 7 8 5
8:  6 6 4 7 8 5 5 6 9 10 6 6 8 6 6 6
```

These are the factory presets from the original head unit application, and they cost one byte to
use. There is also a fixed trim at `0x0800D1FD` — zeroes with −1 on the top three bands — added
whenever `S+1` is zero, and `S+1` is what command `0x84` sets from an eight-entry table.

The band order is a table at `0x0800D15D`: `0D 0E 00 01 … 0C 0F`, which maps the app's sixteen
sliders onto `061D, 061E, 0610…061C, 061F` — the datasheet's own band layout, confirming the
sixteen sliders are the chip's 13-band EQ plus the three-band EQ that replaces the tone controls.

### The delay clamp is the datasheet's own ceiling

`DAT_0800c330` = **`0x3FF` = 1023 samples = 21.31 ms**, the 2ch-input-mode maximum exactly. So the
firmware refuses to exceed what the chip allows, and the app cannot produce a prohibited value by
asking for too much.

### Commands the app has never sent

The dispatcher `FUN_0800bc08` handles `0x10, 0x16, 0x18, 0x19, 0x1a, 0x1b, 0x1d, 0x20, 0x22`–`0x28`,
`0x30, 0x40, 0x41, 0x80`–`0x8c, 0xa0, 0xa1, 0xc0`. The app sends four of them: `0x80`, `0x81`,
`0x88`, `0x8b`, `0x8c`, `0x89`.

`0x80`–`0x87` go through a jump table Ghidra could not recover. It is at `0x0800bc93`, eight
entries `F2 F1 F0 EF EE ED EC EB`, and the handler address is `entry * 2 + 0x0800bc93`:

| cmd | handler | first look |
|---|---|---|
| `0x80` | `0x0800C074` | equaliser, 12 bytes — known |
| `0x81` | `0x0800C0D2` | fader, balance, loudness — known |
| `0x82` | `0x0800C11A` | a flag in `S+9` and a 16-bit value at `S+30` |
| `0x83` | `0x0800C144` | one byte into `S+3` |
| `0x84` | `0x0800C14E` | `S+1` from an eight-entry table, then `S+3` — the EQ mode the composer tests |
| `0x85` | `0x0800C19A` | one byte 0..36 into `S+0`, packed into a 3-bit field — **0 to 36 matches the chip's DVol Boost range, 0 to +36 dB** |
| `0x86` | `0x0800C16C` | a flag, a value 0..127, and `(100 - x) / 10` |
| `0x87` | `0x0800C1C0` | a single on/off bit |

None of them is a raw I2C bridge; they all move bytes into the same settings structure. What each
one finally becomes is being traced.

### 💎 The chip's own spectrum analyser can be read back

The datasheet's read protocol: write the starting address into `D000`/`D001`, then address `D100`
to begin reading. **The SpeAna levels live at read-only `A000`–`A01F`, two bytes per band, sixteen
bands**, and must be read as a 2-byte pair.

The firmware already contains a read routine that does exactly this — `0x80`, `0xD0`, address,
then a repeated start with `0x81`. So the hardware path exists. If any MCU command exposes it, the
app could read a 16-band analysis of what the DSP is actually outputting, with no CPU cost, no
`Visualizer`, no session hunting and no latency to guess at.

### 💎 One bus, two devices, and a bridge that is nearly already written

The sound processor is not alone on that I2C bus. Beside the fixed-address routine of §1 the
firmware carries two **generic** ones that take the device address as an argument:

```c
FUN_080060cc(deviceAddr, buffer, len);   // write
FUN_08006084(deviceAddr, buffer, len);   // read
```

They are called with **`0xC2`** — the radio tuner. The sound processor is `0x80`. Same bus, same
primitives, two devices.

That matters more than it looks. A raw serial-to-I2C bridge — "here is a device address, a register
address and a value, and give me back what you read" — would let the app reach anything either chip
can do, including everything the MCU does not model: the chip's own spectrum analyser, the tuner's
raw signal strength and PI code, the direct biquad coefficients. And the two functions that would
have to sit behind such a bridge already exist and already take exactly the arguments it needs.

What does **not** exist is a command that calls them with attacker-supplied bytes. Every command in
the dispatcher moves data into the settings structure; none passes a device address through from
the wire. So the bridge is not hidden somewhere waiting to be found — it would have to be added,
which means patching the MCU image. That is a real option here rather than a fantasy, because the
image can be reflashed from recovery, but it is a different kind of project from writing an app,
and it should be decided deliberately rather than drifted into.

## 8. Where to look next

- `0203`[3], Time Alignment Mode. If any firmware sets 4ch, the delay ceiling halves and values
  above `1FF` become prohibited there.
- `DAT_0800c330`, the delay clamp, and whether it tracks the mode.
- The initialisation tables the firmware walks at `DAT_08004d24` and `DAT_08004ed8` — pairs of
  select address and data. They hold the state the chip powers up in.
- Command `0x80`'s handler, which sits behind a jump table Ghidra could not recover at
  `0x0800bc8e`.

Related: [../ROOM_CALIBRATION.md](../ROOM_CALIBRATION.md) for how the delays are measured,
[02-MCU.md](02-MCU.md) for the MCU side of the serial protocol.
