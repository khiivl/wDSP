# Inside the MCU firmware, and what it would take to change it

Provenance marks are used throughout, and they mean what they say:
🔬 read in the binary · 📻 measured on the wire · 🧩 inference from those two · ❓ guess, unverified.
A guess presented as a fact has cost this project a day more than once. Keep the marks.

Working image: **`QF05.V02.13.20251124.002121`**, `C:\MCU\mcu.bin`, 48 700 bytes, sha256
`ab3cc503192ce30d0d01d2a3c2087ca0ff0875792fb1bad12b394daab2b51e45`. Loads at **`0x08003800`** — a
file offset is `address − 0x08003800`.

## 1. Flash layout 🔬

```
0x08000000 .. 0x080037FF   bootloader        SP 0x20000990   Reset 0x0800015D
0x08003800 .. 0x0800F63B   application       SP 0x200014C0   Reset 0x08003979
0x0800F63C .. 0x0800F7FF   FREE, 452 bytes   <- the only place a veneer can go
0x0800F800 .. 0x0800FA5F   settings page, records of 15-16 bytes
0x0800FA60 .. 0x0800FFFF   the rest of that page: it GROWS into here, do not use
```

🔬 The image is dense — the largest run of unused bytes inside it is 45. The 452 bytes between the
application and the settings page are all there is.

🔬 **`.data` is compressed in flash** (LZ77-like, source around `0x0800F5D0`, unpacked by the copy
loop in the reset handler at `0x08003D7C`). So the *code* can be patched by replacing bytes, but
the **initial register values cannot** — they are not lying there as raw bytes.

## 2. 🔴 The open question, before anything is ever flashed

**Does the bootloader verify a checksum over the application image?** ❓ Not established.

- 🔬 no standard CRC32 polynomial (`04C11DB7`, `EDB88320`) appears anywhere in the image;
- 🔬 the serial frames carry a per-frame checksum which the *sender* computes, so transferring a
  patched file is transparent;
- ❓ it remains possible that the bootloader keeps its own sum and refuses to start.

The Android side has since been read — §8 — and it checks nothing about the image itself, only the
download. So the question now belongs either to the bootloader, `0x08000000..0x080037FF` in
`mcu_full.bin`, or to a single experiment: flash a marked image and see whether the progress
broadcast answers `105` (rejected) or `103` (done).

⚠️ `mcu_full.bin` on disk is a dump from **August 2025** and matches the current image on only
5 909 bytes out of 48 700. Take a fresh dump from the actual unit before trusting any of it.

## 3. The command dispatcher and its hidden jump table

🔬 `FUN_0800bc08` handles `0x10, 0x16, 0x18, 0x19, 0x1a, 0x1b, 0x1d, 0x20, 0x22`–`0x28`, `0x30`,
`0x40`, `0x41`, `0x80`–`0x8c`, `0xa0`, `0xa1`, `0xc0`.

Commands `0x80`–`0x87` dispatch through a table Ghidra reports as unrecoverable. It is not hard by
hand: 🔬 the count byte is at `0x0800bc92` = **8**, the entries follow at `0x0800bc93` =
`F2 F1 F0 EF EE ED EC EB`, and the handler address is `entry × 2 + 0x0800bc93`, where a 16-bit
branch sits.

| cmd | handler | what it does 🔬 |
|---|---|---|
| `0x80` | `0x0800C074` | equaliser: 16 gain nibbles → `S+0x22…`, two Q bytes → `S+0x32/33`, curve selector → `S+0x34` |
| `0x81` | `0x0800C0D2` | fader, balance, loudness |
| `0x82` | `0x0800C11A` | a flag in `S+9`, a 16-bit value at `S+30` ❓ |
| `0x83` | `0x0800C144` | one byte → `S+3`, master volume 🧩 |
| `0x84` | `0x0800C14E` | `S+1` from an eight-entry table, then `S+3` — **`S+1` is the flag the equaliser composer tests** |
| `0x85` | `0x0800C19A` | one byte 0..36 → `S+0`, packed into a 3-bit field. 🧩 0..36 is exactly the chip's DVol Boost range, 0 to +36 dB |
| `0x86` | `0x0800C16C` | a flag, a value 0..127, and `(100 − x)/10` — fader/balance 🧩 |
| `0x87` | `0x0800C1C0` | a single on/off bit |

🧩 **No app on this platform has ever sent `0x82`–`0x87`.** They move bytes into the same settings
structure as everything else; none of them is a raw I2C bridge.

## 4. The settings structure

🔬 `S = 0x2000022C`. The byte array the handlers call `DAT_0800c004` is `0x2000024C`, which is
**`S + 0x20`** — they are the same block, and confusing the two is how one analysis concluded that
the equaliser command was corrupting a delay register.

| offset | contents |
|---|---|
| `S+0` | volume 0..36 |
| `S+1` | equaliser mode, set by `0x84`; when 0 a fixed trim curve is added |
| `S+3` | volume |
| `S+9` | dirty flags, bit 3 = time alignment |
| `S+10` | dirty flags, bit 0 = equaliser, bit 2 = **surround mode selects which delay set is used** |
| `S+0x0C…0x12` | surround delays, slider × 102 samples |
| `S+0x14…0x1C` | positional delays FL FR RL RR Sub, slider × 4.8 samples, clamped to `DAT_0800c330` = `0x3FF` |
| `S+0x22…0x31` | 16 equaliser gain indices |
| `S+0x32, S+0x33` | 🔴 the Q bitmask — **written and never read** |
| `S+0x34` | factory curve selector, 0 = use the app's own gains |
| `S+0x35…0x3A` | P2Bass front/rear, front/rear HPF, sub LPF frequency, sub gain |

## 5. How registers actually reach the chip

🔬 `FUN_08004e64` is the only thing that writes ordinary registers. Two byte arrays — what the chip
*should* contain at `0x200000E2`, what was *last written* at `0x20000268` — and a walk over indices
8 to 0x80. One differing byte per pass becomes one register write; when nothing differs it rewrites
one register per pass in rotation, so a chip that lost its state heals itself.

🔬 The index → select-address table is at **`0x0800CFD7`**, two bytes per entry, 129 entries. It is
the complete list of what this firmware can address; the map is in
[03-SOUND-PROCESSOR.md](03-SOUND-PROCESSOR.md) §7.

🧩 **This is the mechanism that makes a small patch worth more than a big one.** A command that
wrote a byte into the "should contain" array at `0x200000E2 + index` would put all 129 registers
within reach, and the firmware would carry it to the chip itself, with its own retries, without two
masters fighting over the bus.

And the slots nothing in the firmware ever fills would simply keep what we put there: 🔬 the rear
equaliser bands (`0x4F…0x5E`), the subwoofer high-pass (`0x67`), the spectrum analyser (`0x3C/0x3D`),
the noise generator (`0x29`). ⚠️ The front equaliser bands (`0x3F…0x4E`) are rewritten by the
composer every time the equaliser changes, so poking those is pointless — `0x80` already does it.

## 6. The patch harness

`C:\MCU\patch\` holds the pristine baseline and `mcupatch.py`:

```
python mcupatch.py list                  what patches exist and why
python mcupatch.py map    <file>         layout, free space, landmarks
python mcupatch.py verify <file>         which patches are applied
python mcupatch.py apply  <in> <out> N   apply patch N
python mcupatch.py revert <in> <out> N   put the original bytes back
python mcupatch.py diff   <a> <b>
```

**A patch refuses to apply unless the bytes it expects are exactly where it expects them.** That
check is the whole point — a patch landing on the wrong bytes because the image was a different
build is how a head unit stops working. `map` reads six bytes from each of eleven landmarks, so a
single command tells you whether the image in front of you is the one everything here was written
against.

Candidate patches on record, none applied:

| # | address | effect |
|---|---|---|
| 1 | `0x08005112` | cut constant `0x70` → `0x50`: Q becomes 4.7 on cuts |
| 2 | `0x0800512C` | boost constant `0x60` → `0x40`: Q becomes 4.7 on boosts |
| 3 | `0x08005112` | clear bit 6 on cuts: front and rear stop being mirrored |
| 4 | `0x0800512C` | the same on boosts |
| 5 | `0x08003A0C` | the version year `2025` → `2030`: marks the image as ours — see §8 |

🧩 Patches 1 and 2 are self-sufficient one-byte changes and do work — but they replace a fixed 2.2
with a fixed 4.7; the switch in the app still would not control anything. Making it *selectable*
means reading `S+0x32/0x33` and folding a bit into the constant, which is a dozen instructions and
therefore a veneer.

🔴 Patches 3 and 4 **are harmful on their own**: clearing bit 6 stops the mirroring, but nothing
writes the rear bands, so they freeze at their initial values. Independent front and rear needs
patch 3+4 *and* a way to write `0620…062F` — see §5.

## 7. Safety protocol

1. **Dump the flash from the actual unit first.** Do not trust a file on disk.
2. Keep the original `.bin` on a USB stick before the first write, not after.
3. **Check that the unit enters recovery** before you need it to.
4. One patch at a time, verified on the wire. Two at once and you cannot tell which broke it.
5. After every flash: `getprop persist.sys.qf.mcu.version`, then send one frame and watch for a
   reply. If the MCU answers, it is alive.
6. 🧩 The first patch should be the smallest one that has an audible, measurable effect — not
   because the effect matters, but because it tests **the whole flashing chain** on something you
   can verify.

## 8. How the head unit flashes the MCU 🔬

Decompiled from `/system/priv-app/QF_OTAUpgrade/QF_OTAUpgrade.apk` (`com.qf.packageupgrade`).

The path is short:

```
server → /sdcard/otapackage/BIN1          (BIN0 is the Android system image)
       → SystemProperties.set("sys.qf.ota.upgrade.type", "BIN1")
       → broadcast com.qf.action.ota.send, key_type 2001, action "qf.ota.upgrade"
       → progress comes back on com.qf.action.ota.recv:
            101 starting · 102 in progress · 103 success · 104 reboot
            105 file is bad · 106 failed
```

🧩 **The integrity check is the server's, not the image's.** `FileUtils.checkBinFileIsExists`
computes an MD5 of the downloaded file and compares it to a `verify` field the *server* sent with
the package. There is no signature, no self-check and no checksum belonging to the image itself
anywhere in this application. ❓ Whether the bootloader has its own check is still open — but
nothing on the Android side would stop a locally placed `BIN1`.

The network side of this — a server that decides whether an update exists, and a `force` mode that
flashes after a three-second dialog — is **not in use on these units**: there is no server behind
it. So it is neither a route in nor a threat to a patched image.

🧩 **What is left is the useful half: a local flashing path we can drive ourselves.** Put the image
at `/sdcard/otapackage/BIN1`, set the property, send the broadcast. No recovery, no USB stick, no
server — and the same code the factory uses, so nothing unusual is being asked of the bootloader.

⚠️ Not yet exercised. Before relying on it, watch `com.qf.action.ota.recv` for `105` (the file is
rejected) versus `101/102/103`, and have the original image ready.

### 🔬 The version string is parsed by fixed character positions

`QF05.V02.13.20251124.002121` — 27 characters, and the OTA application indexes into it directly:

| chars | field | means |
|---|---|---|
| `0..3` | `mcuPlatform` | `QF05` |
| `7..10` | `subStrmcu` | `2.13`, the build line |
| `12..19` | — | **the date, `20251124` — used only as part of the whole string** |
| `21` | `mcuModel` | `0` |
| `22` | `audioicModel` | `0` — the sound processor |
| `23` | `radioModel` | `2` — the tuner chip, the same character `McuManagerService` reads |
| `24` | `MPUAudio` | `1` |
| `25` | `Externalhardwareinterfacetype` | `2` |

🔬 In the firmware the string lives at **`0x08003A00`**, 27 bytes followed by exactly one `NUL`, and
then used bytes. **It cannot be lengthened in place** — but any character can be replaced.

### 🧩 Marking a patched image so software can tell

Both requirements point at the same place. The marker must not move any index and must not lie
about hardware, which rules out everything except the date; and the date is in no hardware field,
so changing it cannot make the server match this unit to somebody else's package.

**Keep the original date and move the year into the future.**

```
stock    QF05.V02.13.20251124.002121
ours     QF05.V02.13.20301124.002121      year + 5  = our build 1
         QF05.V02.13.20311124.002121      year + 6  = our build 2
```

- no factory build is dated five years ahead, so **one `getprop persist.sys.qf.mcu.version` tells
  any application it is talking to a patched microcontroller**;
- the month and day are untouched, so the stock build it was patched *from* stays legible — which
  matters, because a patch is only valid against one base image;
- the year offset counts our own builds, so the string says which of them is running;
- all eight characters stay numeric, the length is unchanged, every index above still reads what it
  read before, and `002121` is untouched — chip detection and the tuner-type character keep
  working.

**What exactly is inside a given build** is not in the string, and deliberately so: cramming a
bitmask into the date would cost the base date, which is worth more. `mcupatch.py verify` reads any
image and prints which patches are present, and it refuses to apply anything to an image it does
not recognise. The string identifies the build; the harness describes it.

❓ If self-describing turns out to matter more than provenance, the alternative is to keep the year
as the marker and use the **day** as a five-bit mask — a day must stay in `01..31`, which is
exactly five bits, so five patches can be encoded and the string still parses as a date. That
trades away the original day.

## 9. If we ever did patch it — what, for what, and at what risk

Nothing below is applied. It is written down so the decision can be made on facts rather than on
enthusiasm, and the order matters more than the list.

**First, the question that gates everything: does the bootloader check a sum over the image?** ❓
Still open, and it is the one place where being wrong costs a head unit rather than a setting.

🧩 There is a cheap way to find out. The factory's own flashing path needs no server and no
recovery: put the image at `/sdcard/otapackage/BIN1`, set `sys.qf.ota.upgrade.type`, send
`com.qf.action.ota.send`, and watch `com.qf.action.ota.recv` — `105` means the file was rejected,
`101/102/103` means it was not.

| | what changes | what it buys | what it risks |
|---|---|---|---|
| **mark the build** | four bytes at `0x08003A0C`: the year `2025` → `2030` | one `getprop` tells any app it is talking to a patched MCU, while the month and day still say which stock build it came from | lowest of all — same length, still numeric, no hardware field touched |
| **equaliser Q** | one byte each at `0x08005112` and `0x0800512C` | every band becomes Q = 4.7 instead of 2.2; narrower cuts, which is what room correction wants. And the two constants are separate, so **narrow cuts with wide boosts** is available — a deliberate tuning choice, not a compromise | small: one instruction, fully reversible, worst case is that it sounds wrong |
| **front/rear split** | clear bit 6 in the same two constants | nothing on its own — ⚠️ **actively worse**: the rear stops being mirrored and nothing writes it, so it freezes | only useful together with the next row |
| **a poke command** | a branch in the dispatcher plus a veneer in the 452 free bytes, writing one byte into the shadow image | all 129 registers reachable: the rear equaliser bands, the subsonic filter, the chip's own spectrum analyser, the noise generator, and **direct biquad coefficients** | the real one — this is our code in the firmware. A mistake here is not "it sounds wrong", it is "the MCU did not come up" |

The last row is the only one that changes what the app can *be*. The equaliser we can reach today
is the ceiling on room correction — sixteen bands, 2 dB steps, fixed Q, front and rear identical —
and no amount of careful measurement gets past a ceiling.

**Order:** settle the checksum; then flash the *marker alone*, because a change with no behavioural
effect is the right way to test the flashing chain; then the Q constants, which are audible and
measurable in forty seconds; and only then the veneer.

**Not on the list, deliberately:** changing initial register values (they arrive from compressed
`.data`), raising the Surround slider past 10 (ten steps is already 1020 of 1023 samples), touching
the `002121` tail of the version string (chip detection reads it), and building a raw UART-to-I2C
bridge instead of the poke (two masters on one bus is a class of problem that can simply be
declined).
