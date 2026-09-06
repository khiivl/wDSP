# MCU firmware: one code base, one build per chipset

The single most useful thing to know here: **there is no runtime branch on the hardware.** The MCU
firmware is compiled for a specific set of chips, and every head unit ships the build that matches
its board. Looking for an `if (tuner == NXP)` inside the image is wasted time — the tuner driver is
linked in or it is not.

The archive names say so out loud, and reading them costs nothing:

```
7862_32107_6686_QF05.V02.13.20251124.004121.zip   → UIS7862 · BU32107 · TEF6686
MCU-QF05-2.5.2024-BD37534-TSC4745.zip             → BD37534 · TSC4745
```

📻 Confirmed by comparing the images: TDA and NXP builds of the *same* date share only **16 % of
their bytes**, and the sizes differ by 5 400 bytes. Two builds, not one binary with a switch.

---

## 1. The version suffix decodes the board — the complete table

🔬 **All six characters, from the factory parser and the factory name arrays.**
`QF_CarSettings` → `com.qf.carsettings.utils.product.McuVersionUtils.parseMcuVersion` splits the
last dot-separated field and reads it character by character; `ProductInfoConstants` holds the names
each value maps to. Both are decompiled factory code, not inference.

```java
mcuType              = charAt(0)
dspType              = charAt(1)      // hex-capable: (c + 10 - 97)
radioType            = charAt(2)      // hex-capable
mpuMode              = charAt(3)
exDeviceType         = charAt(4)      // hex-capable
forcePowerOff        = charAt(5) & 1
operationalAmplifier = charAt(5) & 2
```

| position | field | values (factory strings) |
|---|---|---|
| `[1]` | **sound processor** | `0` BU32107 · `1` BD37534 · `2` AK7738 · `3` AK7604 |
| `[2]` | **tuner** | `0` Build-in · `1` TSC4745 · `2` TDA7708 · `3` QN8035 · `4` TEF6686(NXP) · `5` TDA7708L · `6` TDA7708LX · `7` LXH4745 · `8` TDA7786 · `9` SI4755 |
| `[3]` | **output path** | `0` analogue · `1` IIS |
| `[4]` | **control panel** | encoder/remote/backlight variants — **nothing to do with audio** |
| `[5]` | power & op-amp | bit 0 force-power-off, bit 1 operational amplifier |

🔴 **Two corrections this table forces:**

- `[1]` is not "the AK hub flag". It is the **whole list of sound processors**, with the AK parts as
  values 2 and 3. "No hub" and "BU32107" are the *same* value `0` — reading it as "0 = nothing
  fitted" loses which processor the unit actually has.
- `[4]` is **not** the sound processor. It is the control panel, which is why it reads `2` on every
  firmware examined, BU and BD alike. A detector built on it cannot distinguish anything.

📻 Verified against four real firmware images, with the archive filenames as an independent witness:

| suffix | `[1]` | `[2]` | `[3]` | archive name says | match |
|---|---|---|---|---|---|
| `002121` | BU32107 | TDA7708 | IIS | TDA7708 + BU32107 | ✅ |
| `004121` | BU32107 | TEF6686 | IIS | `7862_32107_6686_…` | ✅ |
| `001121` | BU32107 | TSC4745 | IIS | (not stated) | — |
| `011021` | **BD37534** | TSC4745 | **analogue** | `…BD37534-TSC4745.zip` | ✅ |

🔴 **Consequence for any installer:** the sound processor is `${HW_CODE:1:1}`. BitPerfect's
`${HW_CODE:4:2} == "21"` reads the control panel plus the power bitmask — two things unrelated to
audio, and identical across the fleet, so the test is **always true**. That is how a BD unit ends up
with the 24-bit I2S profile. See [10-BITPERFECT-MODULE.md](10-BITPERFECT-MODULE.md) §4.

## 1-bis. What the platform publishes from it

🔬 `McuManagerService.onVersionInfoChanged` / `initRadioExtChip`
(`D:\De-compiled\qf_framework_code\sources\com\qf\framework\mcu\McuManagerService.java:1033-1070`)

The last dot-separated field of `persist.sys.qf.mcu.version` is a six-character code, and the
platform reads exactly two positions out of it — **by index, zero-based**:

```java
substring = str.substring(lastIndexOf + 4, lastIndexOf + 5);   // index 3 of the suffix
if ("0".equals(substring)) use.i2s = "false"; else use.i2s = "true";

numericValue = Character.getNumericValue(split[4].charAt(2));   // index 2 of the suffix
if (numericValue != 0) radio.ext = "true";
```

⚠️ **Index 3, not "position 4".** An earlier note in this folder said position 4; that was wrong and
is corrected here.

Four real firmware images, all four decoded and consistent:

| suffix | idx 2 → tuner | idx 3 → I2S | board | image size |
|---|---|---|---|---|
| `002121` | 2 → TDA7708 | 1 → yes | TDA7708 + BU32107 | 48 700 |
| `004121` | 4 → TEF6686 | 1 → yes | NXP6686 + BU32107 | 43 300 |
| `001121` | 1 → TSC4745 | 1 → yes | TSC4745 + BU32107 | 36 128 |
| `011021` | 1 → TSC4745 | **0 → no** | TSC4745 + **BD37534** | 34 224 |

🧩 Tuner and audio chip are **independent axes**: the same 4745 appears with BU32107 and with
BD37534. A detector that infers one from the other will be wrong on some part of the fleet.

## 2. What the flags are actually used for

📻 Every consumer of `persist.sys.qf.radio.ext` in the system was located:

```java
// QFKeyPolicy.java:252
if (radio.ext && packageName.equals("com.android.fmradio.ext")) …
// QF_CarSettings AppOperationUtils.java:30
str = radio.ext ? "com.android.fmradio.ext" : "com.android.fmradio";
```

That is the entire list. The flag chooses **which radio APK runs** and touches nothing in the audio
path — no mixing, no volume, no policy, no route. So the platform does *not* compensate for a
TEF6686 being roughly 6 dB hotter than a TDA7708. If that difference is to be evened out, it has to
be done in an application.

`persist.sys.qf.arm.use.i2s` is a different story — see [10-BITPERFECT-MODULE.md](10-BITPERFECT-MODULE.md) §7.

## 3. Image layout, for anyone disassembling

📻 Established from a full 64 KB flash dump next to the matching update image:

```
0x08000000  bootloader   QF-BOOT.V02.01.20240104     (14 KB)
0x08003800  application  ← this is where mcu.bin loads
```

The update `mcu.bin` is a **raw Cortex-M image of the application only**; its first word is the
initial SP and the second is the reset vector. So:

```bash
arm-none-eabi-objdump -D -b binary -m arm -M force-thumb \
    --adjust-vma=0x08003800 mcu.bin
```

A dump taken off the chip (`flash.bin`) starts with the bootloader instead, so its first vectors
differ; the application inside it begins at file offset `0x3800`.

⚠️ **`D:\...\mcudecomplied.c` is a partial decompilation** and was made from a *flash dump*
(base `0x08000000`), covering `0x080000c0` … `0x08009624` — roughly 60 % of the image. The rest,
including everything above `0x9624`, is simply absent. Do not conclude "not present in the firmware"
from that file alone.

❓ **Open, and previously mis-recorded as fact:** the ducking formula
`ducking_step = (100 - ratio) / 10`, attributed to `mcudecomplied.c:0x0800896c`. That address falls
in the uncovered gap; there is no function there, no `100 -`, and no divide-by-10 idiom
(`0x66666667`) anywhere in the file. The formula may still be right — it is simply **not evidenced**
by the artefact it was credited to.

## 4. Where the material lives

```
D:\Downloads\QF05.V02.13.20251124.002121.ZIP              TDA + BU32107
D:\Downloads\7862_32107_6686_QF05.V02.13.20251124.004121.zip   NXP + BU32107
D:\Downloads\QF05.V02.13.20260420.001121.ZIP              TSC4745 + BU32107
D:\Downloads\MCU-QF05-2.5.2024-BD37534-TSC4745.zip        TSC4745 + BD37534
D:\Downloads\...\mcu_dump.zip        flash.bin (64 KB, off a 004121 unit) + OB.json
D:\Downloads\...\mcudecomplied.c     partial Ghidra export, base 0x08000000
```

WSL has `strings` and `arm-none-eabi-objdump`; Windows-side MSYS has neither.

## 5. What is still unknown

- ❓ whether the volume/ducking path differs between the TDA and NXP builds at all. The way to find
  out is to disassemble both at `0x08003800` and compare the UART command handler for volume — not
  to look for a branch.
- ❓ what tuner value `3` would mean, if it exists. Only 1, 2 and 4 have been seen.
- ❓ whether `Si4745` and `TSC4745` are the same part under two names.
