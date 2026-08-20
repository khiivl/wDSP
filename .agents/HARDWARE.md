# The hardware wDSP talks to

## 1. Which sound processor is fitted, and why you cannot ask it

Two chips are used across these head units:

| chip | what it is | notes |
|---|---|---|
| **ROHM BU32107** | digital sound processor | the full feature set the app is written against |
| **ROHM BD37544** | analogue sound processor hanging off the DAC | roughly half of it |

The MCU speaks **one command set to both** and makes the lesser chip look complete: the BD37544
accepts commands it cannot really honour, and the BU32107's abilities have been trimmed in firmware
until they nearly match. So the commands cannot tell them apart, and neither can trial and error.

The firmware version can:

```
persist.sys.qf.mcu.version = QF05.V02.13.20251124.002121
                                                  └────┘
                                          hardware code: 00xx21 means BU32107
```

Six digits, `00` at the front and `21` at the back. The two in the middle vary between builds and
mean nothing here. Implemented in `HardwareProfile.hasBu32107()`.

⚠️ If the code cannot be read, assume the BU32107 is **not** fitted. Promising better hardware than
is present is worse than promising nothing.

Two practical consequences:

- **The BD37544 is analogue, so it adds no delay** — its group delay is microseconds. It can be
  ignored entirely in any timing model.
- **BitPerfect is not installed on BD37544 units** (it produces digital noise instead of sound
  there), so those head units are almost always on factory audio policies.

---

## 2. Reaching the MCU: reflection, not root

```java
Class<?> sm = Class.forName("android.os.ServiceManager");
IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "mcu_service");

Class<?> stub = Class.forName("android.qf.mcu.IMcuManager$Stub");
Object mcu = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

Method setEqData  = mcu.getClass().getMethod("RPC_SetEQData", byte[].class);
Method sendMcuMsg = mcu.getClass().getMethod("RPC_SendMcuMsgData", byte.class, byte[].class, int.class);
```

⚠️ **R8 breaks this silently.** `minifyEnabled false` is deliberate. If it is ever turned on, every
class and method name above has to go into `proguard-rules.pro`, or reflection will fail in release
builds only.

⚠️ **`targetSdk 29` is deliberate too** — hidden API access is blocked harder above it.

---

## 3. The command map, as the code actually sends it

All of these go through `RPC_SetEQData(byte[])`; the first byte is the command.

| cmd | length | what | encoding |
|---|---|---|---|
| **`0x80`** | 12 | 16-band equaliser | 8 packed bytes, **two bands per byte** (`idx2<<4 \| idx1`), gain index 0..12 = **−12..+12 dB in 2 dB steps**; then 2 bytes of Q bitmask (**bit set → 4.7**, clear → 2.2); trailing `0x00` |
| **`0x8B`** | 2 | subwoofer | `(freqIdx << 4) \| gainIdx`; frequencies `{25,32,40,50,63,80,100,125,160,200,250}` Hz |
| **`0x88`** | 4 | bass boost and high-pass, front and rear | `((boostFreqIdx+8)<<4) \| boostLevel` per channel, then `(hpfFront<<4) \| hpfRear` |
| **`0x81`** | 4 | fader / balance / loudness | L-R step, F-R step (**12 is centre**, range 0..24), loudness flag |
| **`0x8C`** | 6 | time alignment | FL, FR, RL, RR, Sub, each stored value **× 5**; all zeroes when disabled. The UI shows 0.5 ms per step |
| **`0x89`** | 6 | surround / Haas + RSSE | `138 + (rsse − 10)`, then FL, FR, RL, RR; zeroes when disabled |

Separately, **not** through `RPC_SetEQData`:

| message | data | what |
|---|---|---|
| `RPC_SendMcuMsgData((byte) 24, {2, val}, 2)` | sub-id `2` plus value | power amplifier pre-gain |

⚠️ The description in the project's own `agents.md` is wrong in places — it calls the fader `0x82`,
the delays `0x84` and surround `0x85`. The code sends `0x81`, `0x8C`, `0x89`. **Trust the code that
does the sending.**

### Send discipline

- **Deduplicate per command.** A frame is not sent if it is byte-for-byte the same as the last one
  for that command (`mcuCache`). Without it, dragging a slider floods the MCU with identical frames.
- **Throttle `0x80` and `0x8B` to 500 ms**, with a trailing write so the final value still arrives.
  These are the two frequent ones.
- **One background thread**, `HandlerThread` at priority −16 (`THREAD_PRIORITY_AUDIO`). Never touch
  the MCU from the main thread.

---

## 4. Volume: the reading lags the write

`android.qf.os.VolumeManager` / `android.qf.os.VolumeState`, also by reflection. The class name
`android.qf.os.VolumeState` is **base64-encoded in the source** so that Play Store scanners do not
see a hidden-API string; keep that trick if you touch it.

🔴 **After `setVolumeVal(N)` the next read still returns the old number.** Measured in the log:
`vol=12 -> 13`, and on the next poll `vol=12 -> 14`. And while music is playing the platform runs
its own volume curve per source, so it may keep returning a different number than it was given,
indefinitely.

➡️ **Any algorithm that both writes and reads the volume must keep "what I sent" and "what the
hardware reported" in separate variables.** GALA died on exactly this: its "last read volume" held
its own command, so the mismatch read as a person turning the knob, the base was re-learned as
`volume − offset`, and the algorithm then set `base + offset` — the volume that was already there.
A fixed point. It never moved again, and only under music, because only then was the mismatch
permanent.

**Active player** comes from a property, not from an Android API:

```java
SystemProperties.get("sys.qf.last_audio_src", "Unknown")   // package name, or "nothing"
```

🪤 Do not compare it by substring: `com.radiorubka.wdsp` contains "radio", so the app decided the
tuner was playing whenever its own UI came forward.

---

## 5. The Fletcher-Munson curve is already inside the indices

`McuService` computes the loudness compensation, folds in the ISO-226 curve and the "fatigue"
term, and only then quantises to 2 dB steps and clamps at ±12:

```java
float db  = (cachedGains[b] - 6) * 2 + fmOffsets[b];
int   idx = clamp(round(db / 2.0f) + 6, 0, 12);
```

➡️ Anything that needs the DSP's *real* settings — the spectrum analyser, for instance, so it can
show processed sound — must take the **final indices**, not the slider positions. Using the sliders
applies the curve twice and loses the clamping.

---

## 6. The bus between the UI and the hardware is SharedPreferences

There is no service binding and no callbacks:

```
MainActivity → prefs("EqPresets") → McuService.OnSharedPreferenceChangeListener → RPC_*
```

Every value is a flat key prefixed with the preset name: `<preset>_g0..g15`, `_q0..q15`, `_sub_g`,
`_sub_f`, `_bb_*`, `_bf_*`, `_f_lr`, `_f_fr`, `_loud`, `_d_*`, `_d1_*`, `_rsse_val`, `_fm_cal`,
`_fm_str`, `_power_vol`, `_gala_*`.

🔴 **The listener tells them apart by substring** (`key.contains("_sub")`, `_d_`, `_bb_`/`_bf_`,
`_f_`/`_loud`, …). So **naming a new preference is a wiring decision**. A key with `_f_` anywhere
inside it will silently resend the fader frame; a key that matches nothing will be stored and go
nowhere.

Consequence: renaming or importing a preset means rewriting every prefixed key.
