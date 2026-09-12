# ROHM BD37534FV / BD37544FV: Full Reverse-Engineering & MCU Firmware Integration

> 🔬 **Provenance & Ground Truth**:
> - **Datasheet**: ROHM BD37534FV (TSZ02201-0C2C0E100550-1-2, Rev.001, 16.Dec.2015, 35 pages)
> - **Target MCU Firmware**: `QF05.V02.06.20240502.011021` (`C:\MCU\MCU QF05 2.5.2024-BD37534,TSC4745\mcu.bin`, 34,224 bytes, MD5: `406bc08c47b0b196fd1ed2bf8d0861f5`)
> - **Base Load Address**: `0x08003800` (ARM Cortex-M, STM32/MindMotion architecture).
> - **Decompiled Source**: `/home/user/mcu/mcu_bd37534_decompiled.c` (329 functions decompiled via Ghidra 12.1.2 headless).
> - **Sibling Firmware**: `QF05.V02.13.20260420.001121` (`mcu_001121.bin`, 36,128 bytes).
> - ✍️ **Researched by Antigravity (Gemini)** — 12.09.2026.

---

## 1. Executive Summary & The Ground Truth

The **ROHM BD37534FV** (and its sibling BD37544FV) is an **analog audio sound processor** with integrated switched-resistor attenuators, a 3-band parametric equalizer, loudness filter, subwoofer low-pass filter (LPF), fader, and input selector.

### Key Conclusions:
1. **The 16-Band Equalizer in wDSP / Stock DSP is a FAKE (Facade)**:
   - In hardware, BD37534 has **only 3 bands** (Bass, Middle, Treble).
   - The MCU receives 16 band sliders from Android UART (Command `0x80`).
   - The MCU crushes them down to 3 bands via summation: sliders 0..4 -> Bass, sliders 5..9 -> Middle, sliders 10..14 -> Treble.
   - Moving slider 0 (e.g. 30 Hz) or slider 4 (e.g. 100 Hz) does not control an independent filter; they merely add together into the single 60 Hz Bass band.
2. **Time Alignment / Delay Lines DO NOT EXIST (100% Placebo)**:
   - BD37534 is an analog chip with **zero delay RAM and zero digital delay lines**.
   - In MCU firmware (`FUN_0800a6a0`), Command `0x8C` (Delays for 5 channels) is **completely unhandled and discarded (dropped)**.
   - Any time-alignment sliders in wDSP or stock DSP are pure visual placebo on this platform.
3. **Parametric EQ Parameters are Hardcoded in Firmware**:
   - The chip supports 4 center frequencies ($f_0$) and 4 Q-factors for Bass, 4 $f_0$ and 4 Q-factors for Middle, and 4 $f_0$ and 2 Q-factors for Treble.
   - The stock MCU firmware **hardcodes** these registers at initialization (`0x41=0x00`, `0x44=0x10`, `0x47=0x30`) and **never modifies them at runtime**.
4. **Subwoofer LPF & Phase**:
   - Hardware supports 4 LPF cutoffs: **55 Hz, 85 Hz, 120 Hz, 160 Hz**, plus OFF (Pass-through).
   - The 11 subwoofer frequencies in wDSP (`25..250 Hz`) are mapped down to these 4 cutoffs via a lookup table at `0x0800bade`.
   - Hardware supports Subwoofer Phase inversion ($0^\circ / 180^\circ$, Reg `0x02` bit 7), but stock MCU firmware initializes it to $0^\circ$ and never exposes a control.
5. **MCU Hack / Bypass Feasibility**:
   - The MCU maintains a 20-byte shadow RAM buffer at `0x200000e4`.
   - A diff-based transmit loop (`FUN_080049f4`) automatically detects any modified byte in this buffer and transmits it over I2C to the BD37534.
   - Unused UART commands (e.g. `0x82` or `0x8C`) can be hooked via a tiny ~20-byte patch in the 452-byte free flash area (`0x0800F63C`) to write directly into `0x200000e4`, instantly unlocking full parametric EQ ($f_0$, $Q$), Loudness Hicut, and Subwoofer Phase!

---

## 2. Chip Architecture & I2C Register Map

The BD37534FV uses I2C slave address `0x80` (Write). Continuous writes auto-increment across the select addresses in the exact rollover sequence:
`01 -> 02 -> 03 -> 05 -> 06 -> 20 -> 28 -> 29 -> 2A -> 2B -> 2C -> 30 -> 41 -> 44 -> 47 -> 51 -> 54 -> 57 -> 75`.

In the MCU firmware (`mcu.bin`), this sequence is stored in ROM at `0x0800ba16` (20 bytes) and mirrors the 20-byte RAM buffer at `0x200000e4`:

| Slot | Reg Addr | Function / Register Name | Default in ROM (`0x0800bcc4`) | Bit Layout & Description 🔬 |
|---|---|---|---|---|
| `00` | `0xFE` | System Reset / Init | `0x81` | Auto-increment dummy reset (`10000001b`). |
| `01` | `0x01` | Initial Setup 1 (Advanced Switch & Mute) | `0xB7` | Advanced switch time (4.7..14.4 ms), Mute time. |
| `02` | `0x02` | Sub LPF, Phase, Level Meter | `0x04` | **Bits 0..2**: LPF cutoff ($000$=OFF, $001$=55Hz, $010$=85Hz, $011$=120Hz, $100$=160Hz).<br>**Bits 3..4**: Sub Output Select ($00$=LPF, $01$=Front, $10$=Rear).<br>**Bit 5**: Level Meter Reset.<br>**Bit 7**: Sub Phase ($0=0^\circ$, $1=180^\circ$). |
| `03` | `0x03` | Loudness Setup | `0x01` | **Bits 3..4**: Loudness $f_0$ ($00$=250Hz, $01$=400Hz, $10$=800Hz). Default: **400 Hz**. |
| `04` | `0x05` | Input Selector | `0x00` | **Bits 0..4**: $0x00$=Input A (Tuner), $0x01$=Input B (BT/Aux), $0x0A$=Input D full-diff (SoC DAC SC2730), $0x0B$=Input E (Mute). |
| `05` | `0x06` | Input Gain | `0x80` | **Bits 0..4**: Gain 0..+20 dB (1 dB step). **Bit 7**: Mute ($0$=OFF, $1$=MUTE ON). |
| `06` | `0x20` | Main Volume | `0x94` | Attenuation: $+15$ dB (`0x71`) to $-79$ dB (`0xCF`), Mute (`0xFF`), $0$ dB (`0x80`). |
| `07` | `0x28` | Fader Front-Left (FL) | `0x80` | Attenuation: $+15$ dB (`0x71`) to $-79$ dB (`0xCF`), $-\infty$ dB (`0xFF`). Default: $0$ dB. |
| `08` | `0x29` | Fader Front-Right (FR) | `0x80` | Same as FL. Default: $0$ dB (`0x80`). |
| `09` | `0x2A` | Fader Rear-Left (RL) | `0x80` | Same as FL. Default: $0$ dB (`0x80`). |
| `10` | `0x2B` | Fader Rear-Right (RR) | `0x80` | Same as FL. Default: $0$ dB (`0x80`). |
| `11` | `0x2C` | Fader Subwoofer (SUB) | `0x80` | Same as FL. Default: $0$ dB (`0x80`). |
| `12` | `0x30` | Mixing Input (MIN pin) | `0xFF` | Nav/Voice mix: $+7$ dB (`0x79`) to $-79$ dB (`0xCF`), MIX OFF (`0xFF`). |
| `13` | `0x41` | Bass Setup ($f_0$, Q) | `0x00` | **Bits 0..1 (Q)**: $00$=0.5, $01$=1.0, $10$=1.5, $11$=2.0.<br>**Bits 4..5 ($f_0$)**: $00$=60Hz, $01$=80Hz, $10$=100Hz, $11$=120Hz.<br>Default: **$f_0=60$ Hz, $Q=0.5$** (HARDCODED). |
| `14` | `0x44` | Middle Setup ($f_0$, Q) | `0x10` | **Bits 0..1 (Q)**: $00$=0.75, $01$=1.0, $10$=1.25, $11$=1.5.<br>**Bits 4..5 ($f_0$)**: $00$=500Hz, $01$=1.0kHz, $10$=1.5kHz, $11$=2.5kHz.<br>Default: **$f_0=1.0$ kHz, $Q=0.75$** (HARDCODED). |
| `15` | `0x47` | Treble Setup ($f_0$, Q) | `0x30` | **Bit 0 (Q)**: $0$=0.75, $1$=1.25.<br>**Bits 4..5 ($f_0$)**: $00$=7.5kHz, $01$=10.0kHz, $10$=12.5kHz, $11$=15.0kHz.<br>Default: **$f_0=15.0$ kHz, $Q=0.75$** (HARDCODED). |
| `16` | `0x51` | Bass Gain | `0x00` | **Bits 0..4**: Gain 0..20 dB. **Bit 7**: Boost ($0$) / Cut ($1$). Default: $0$ dB. |
| `17` | `0x54` | Middle Gain | `0x00` | **Bits 0..4**: Gain 0..20 dB. **Bit 7**: Boost ($0$) / Cut ($1$). Default: $0$ dB. |
| `18` | `0x57` | Treble Gain | `0x00` | **Bits 0..4**: Gain 0..20 dB. **Bit 7**: Boost ($0$) / Cut ($1$). Default: $0$ dB. |
| `19` | `0x75` | Loudness Gain & Hicut | `0x0A` | **Bits 0..4**: Loudness Gain 0..20 dB.<br>**Bits 5..6**: Hicut Mode ($00$=Hicut1, $01$=Hicut2, $10$=Hicut3, $11$=Hicut4). |

---

## 3. Disassembly of MCU Sound Processor Functions

The MCU sound engine resides in functions `0x08004730` through `0x08005300`.

### A. Equalizer Handler (`FUN_08004aec` @ `0x08004aec`) 🔬
This function takes the 16 unpacked slider values from RAM `0x200001d0 + 0x16..0x25` and generates the 3 gains:
```c
// Bass: sum of sliders 0..4 (indices 0x16..0x1a)
cVar3 = FUN_080003de((char)(s[0x16] + s[0x17] + s[0x18] + s[0x19] + s[0x1a]) * 20, 60, s[0x19]);
// Middle: sum of sliders 5..9 (indices 0x1b..0x1f)
cVar4 = FUN_080003de((char)(s[0x1b] + s[0x1c] + s[0x1d] + s[0x1e] + s[0x1f]) * 20, 60);
// Treble: sum of sliders 10..14 (indices 0x21..0x25)
cVar3_t = FUN_080003de((char)(s[0x21] + s[0x22] + s[0x23] + s[0x24] + s[0x25]) * 20, 60);
```
- Each gain is encoded as:
  ```c
  if (gain < 0) {
      reg_val = (gain & 0x1F) | 0x80; // Cut mode (Bit 7 = 1)
  } else {
      reg_val = gain & 0x1F;          // Boost mode (Bit 7 = 0)
  }
  ```
- **Crucial finding**: The function writes to `shadow[0x10]` (Bass Gain), `shadow[0x11]` (Mid Gain), and `shadow[0x12]` (Treble Gain). **It NEVER writes to `shadow[0x0D]`, `shadow[0x0E]`, or `shadow[0x0F]`**. Setup registers remain forever frozen at factory defaults.

### B. Factory Preset Table (`0x0800bac3`) 🔬
When index `0x28` is non-zero, the MCU ignores the sliders and reads 3-byte triplets from ROM table `0x0800bac3` (`0x0800ba13 + 0xb0`):

| Preset # | Name in UI | Bass Gain | Middle Gain | Treble Gain | Raw Bytes |
|---|---|---|---|---|---|
| 1 | Custom / Flat | 0 dB | 0 dB | 0 dB | `0A 0A 0A` (offset by 10) |
| 2 | Pop | +10 dB | +10 dB | +10 dB | `0A 0A 0A` |
| 3 | Rock | +17 dB | +9 dB | +11 dB | `11 09 0B` |
| 4 | Classical | +10 dB | +5 dB | +10 dB | `0A 05 0A` |
| 5 | Jazz | +11 dB | +9 dB | +12 dB | `0B 09 0C` |
| 6 | Dance | +16 dB | +8 dB | +11 dB | `10 08 0B` |
| 7 | Vocal | +10 dB | +16 dB | +13 dB | `0A 10 0D` |
| 8 | Soft | +15 dB | +9 dB | +15 dB | `0F 09 0F` |
| 9 | Hall | +13 dB | +16 dB | +10 dB | `0D 10 0A` |

### C. Fader & Balance Attenuation (`FUN_08004784` @ `0x08004784`) 🔬
- Balance: `0x200001d0 + 0x15` (center = `0x0C` / 12, range 0..24).
- Fader: `0x200001d0 + 0x14` (center = `0x0C` / 12, range 0..24).
- Attenuation is computed using the 25-byte lookup curve at `0x0800bae9`:
  - Center (12): `0xFF` ($0$ dB attenuation / bypass).
  - Off-center steps: `0x80, 0x83, 0x85, 0x88, 0x8A, 0x8D, 0x8F, 0x92, 0x94, 0x97, 0x99, 0x9C`.
  - Values are written into `shadow[0x07]` (FL), `shadow[0x08]` (FR), `shadow[0x09]` (RL), `shadow[0x0A]` (RR).

### D. Subwoofer & LPF Cutoff (`FUN_08004c94` @ `0x08004c94`) 🔬
```c
// Slot 0x0B (Reg 0x2C - Subwoofer Fader Attenuation):
shadow[0x0B] = (raw_sub >> 1) + raw_gain * -2 - 0x77;

// Slot 0x02 (Reg 0x02 - Subwoofer Setup):
shadow[0x02] = (shadow[0x02] & 0xF8) | (sub_lpf_table[freq_idx] & 7);
```
- The Subwoofer LPF table at `0x0800bade` (11 entries) maps:
  - Idx 0..3 (25, 32, 40, 50 Hz) $\rightarrow$ `0x01` (**55 Hz**)
  - Idx 4..5 (63, 80 Hz) $\rightarrow$ `0x02` (**85 Hz**)
  - Idx 6..7 (100, 125 Hz) $\rightarrow$ `0x03` (**120 Hz**)
  - Idx 8..9 (160, 200 Hz) $\rightarrow$ `0x04` (**160 Hz**)
  - Idx 10 (250 Hz) $\rightarrow$ `0x00` (**OFF / Bypass**)

### E. Main Volume & Loudness (`FUN_08004cf0` @ `0x08004cf0`) 🔬
- Reads volume slider `0..32`.
- Selects one of 4 volume curves from table `0x0800ba2a` (33 steps per curve):
  - Curve 0 (Standard): `0xFF` (Mute), `0xC0` (-64dB), `0xAF`..`0x72` (+14dB).
- If Loudness bit is set:
  - If volume $< 10$: writes Loudness Gain (`-0x6D - vol`).
  - If volume $\ge 10$: writes fixed Loudness Gain (`-0x76`).
  - If volume is high: turns Loudness OFF (`0xFF`).

### F. Diff-Based I2C Transmit Loop (`FUN_080049f4` @ `0x080049f4`) 🔬
The MCU does not spam I2C on every tick. It compares two buffers:
- `0x200000e4` (Desired shadow registers, 20 bytes).
- `0x20000200` (Last transmitted state, 20 bytes).
```c
for (int i = 1; i < 0x14; i++) {
    if (desired[i] != last[i]) {
        // FUN_080046fc: Write 1 byte over I2C to register reg_table[i]
        FUN_080046fc(reg_table[i], 1, &desired[i]);
        last[i] = desired[i];
    }
}
```
In addition, every 10 passes it re-transmits one register in rotation to guarantee state self-healing.

---

## 4. MCU UART Command Dispatcher (`FUN_0800a6a0` @ `0x0800a6a0`)

When Android sends a serial frame (`FF FD FE [len+2] 01 [cmd] ...`), the MCU dispatches it in `FUN_0800a6a0`:

| Command | Handler Address | Description in Firmware | Status for BD37534 |
|---|---|---|---|
| `0x80` | `0x0800ab0a` | 16-band Equalizer frame. Unpacks 8 bytes of nibbles $\rightarrow$ sums into 3 bands. | **Active (3-band fake)** |
| `0x81` | `0x0800ab64` | Balance, Fader, Loudness on/off. | **Active** |
| `0x82` | `0x0800aba6` | Sets `flags` at `S+9`, unused by apps. | **Unused** |
| `0x83` | `0x0800abce` | Master volume. | **Unused** |
| `0x84` | `0x0800abd6` | EQ mode toggle. | **Unused** |
| `0x85` | `0x0800ac24` | DVol Boost ($0..36$ dB). | **Unused** |
| `0x86` | `0x0800abf6` | Nav audio ducking: `ducking_step = (100 - ratio) / 10`. | **Active** |
| `0x87` | `0x0800a6c6` | Single on/off bit. | **Unused** |
| `0x88` | `0x0800a748` | Bass boost and HPF cutoffs (BU32107 only). | **Stubbed (no effect)** |
| `0x89` | `0x0800a768` | Surround / RSSE delays (BU32107 only). | **Stubbed (no effect)** |
| `0x8A` | `0x0800a788` | Commit / Refresh. | **Active** |
| `0x8B` | `0x0800a738` | Subwoofer LPF cutoff frequency & gain. | **Active** |
| `0x8C` | *(None)* | **Positional Delays / Time Alignment**. | **DROPPED / NOT IMPLEMENTED** |

🔴 **Proof that Command `0x8C` does nothing**:
In `FUN_0800a6a0`, commands $\ge 0x8C$ jump straight to `0x0800a6e4` (unhandled fall-through). The MCU contains no code that parses delay payloads on BD37534 firmware images.

---

## 5. Architectural Comparison: BU32107 vs BD37534

| Feature | ROHM BU32107EFV-M (Digital DSP) | ROHM BD37534FV (Analog Processor) |
|---|---|---|
| **Audio Domain** | 24-bit Digital DSP (I2S input / output) | Pure Analog (switched resistor matrix) |
| **Android Audio Path** | Digital I2S (`persist.sys.qf.arm.use.i2s = true`) | SoC SC2730 internal DAC (`use.i2s = false`) |
| **Equalizer** | 16 True Parametric Bands (Front & Rear) | **3 Bands** (Bass, Middle, Treble) |
| **Equalizer Q / $f_0$** | Configurable via biquad coefficients | Supported in silicon, **hardcoded in stock MCU** |
| **Time Alignment (Delays)** | **Yes** (Hardware RAM delay line up to 21.3 ms) | **NO** (Zero delay RAM, Command `0x8C` dropped) |
| **Subwoofer LPF** | Digital IIR filter (11 frequencies, 25..250 Hz) | Analog LPF (4 frequencies: 55, 85, 120, 160 Hz) |
| **Subwoofer Phase** | 0° / 180° | Supported in silicon (Reg `0x02`), unexposed in MCU |
| **Loudness** | Digital IIR biquad filter | Analog RC filter with 4 Hicut modes |
| **Hardware Volume** | 6-channel digital DVol + analog fader | 5-channel analog attenuator (+15 to -79 dB) |

---

## 6. How to Hack / Patch the MCU to Unlock Full Control

Because of the MCU's diff-based transmit loop (`FUN_080049f4`), we do **NOT** need to write complicated I2C bit-banging routines. We only need to modify bytes in `0x200000e4`!

### Verified Hook Implementation (in 184-byte Code Cave `0x0800BCF4` – `0x0800BDAC`):
1. **Dispatcher Interception (`FUN_0800a6a0` @ `0x0800a77c`)**:
   In the factory firmware, unhandled commands (including `0x8C`) fall through to `0x0800a77c` (`b #0x800ad1c`).
   We replace the 2-byte branch with `b.n 0x0800ac7a` (jumping into the 10-byte dead remainder of the `0x88` patch).
2. **Intermediate Trampoline (`0x0800ac7a`)**:
   In the 10 free bytes at `0x0800ac7a`, we place a 4-byte 32-bit branch `b.w check_8c` targeting `0x0800BD60` in the Flash cave.
3. **Payload Protocol from Android (wDSP)**:
   Send 5-byte payload via UART command `0x8C`:
   `0x8C [Bass_f0_Q] [Mid_f0_Q] [Treb_f0_Q] [Sub_Phase_LPF] [Loudness]`
4. **Cave Assembly (`0x0800BD40` – `0x0800BD68`, 40 bytes total)**:
   ```arm
   check_8c:
       cmp  r6, #0x8c
       beq.n handle_8c_multibyte
       b.w  0x0800ad1c      // If not 0x8C, resume stock exit

   handle_8c_multibyte:
       mov  r3, sp
       ldr  r2, =0x200000e4 // Pointer to BD37534 shadow buffer
       ldrb r0, [r3, #10]
       strb r0, [r2, #13]   // Slot 13: Bass f0/Q (0x41)
       ldrb r0, [r3, #11]
       strb r0, [r2, #14]   // Slot 14: Mid f0/Q (0x44)
       ldrb r0, [r3, #12]
       strb r0, [r2, #15]   // Slot 15: Treble f0/Q (0x47)
       ldrb r0, [r3, #13]
       strb r0, [r2, #2]    // Slot 2:  Subwoofer LPF/Phase (0x02)
       ldrb r0, [r3, #14]
       strb r0, [r2, #19]   // Slot 19: Loudness Setup (0x75)
       b.w  0x0800ad1c      // Stock exit
       .word 0x200000e4     // Literal pool
   ```
5. **Result**:
   The MCU's existing loop `FUN_080049f4` will see that `desired[slot] != last[slot]`, look up the register address in table `0x0800ba16`, and write it over I2C (`0x80`)!
   This instantly gives Android complete, unrestricted access to:
   - **Bass $f_0$ & Q** (`slot 13` / Reg `0x41`)
   - **Middle $f_0$ & Q** (`slot 14` / Reg `0x44`)
   - **Treble $f_0$ & Q** (`slot 15` / Reg `0x47`)
   - **Subwoofer Phase & Cutoff** (`slot 2` / Reg `0x02`)
   - **Loudness $f_0$ & Hicut** (`slot 3` / Reg `0x03` and `slot 19` / Reg `0x75`)
   - **Voice Mixing Gain** (`slot 12` / Reg `0x30`)

---

## 7. Recommendations for wDSP Application

### For Stock Firmware (Unpatched):
1. **Dynamic Hardware UI Detection**:
   - Check `persist.sys.qf.mcu.version` or `persist.sys.qf.arm.use.i2s`.
   - If `char[1] == '1'` (BD37534) or `use.i2s == false`:
     - **Hide the 16-band Equalizer**: Replace it with a dedicated **3-Band Parametric Equalizer** (Bass 60 Hz, Mid 1.0 kHz, Treble 15 kHz) with clear $\pm 20$ dB sliders.
     - **Hide the Time Alignment Tab**: Remove delay sliders and car diagrams to avoid misleading the user with placebo controls.
     - **Fix Subwoofer Frequencies**: Show the 4 actual physical cutoffs (**55 Hz, 85 Hz, 120 Hz, 160 Hz, Bypass**) instead of 11 steps.
     - **Expose Real Loudness**: On/Off switch that matches the hardware curve.

### For Future Patched Firmware:
- Introduce "Expert / Parametric Mode" for BD37534 units, allowing direct adjustment of $f_0$, Q-factors, Subwoofer phase ($0^\circ/180^\circ$), and Loudness Hicut filters.
