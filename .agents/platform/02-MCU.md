# The MCU: the other computer in the box

Everything physical on this head unit — the tuner, the sound processor, the amplifier, the
ignition, the steering-wheel buttons, the illumination — belongs to a **separate ARM Cortex-M
microcontroller** with its own firmware and its own power. Android talks to it over a serial line
and has no other way in.

Three consequences follow, and every one of them has been learned the expensive way:

1. **Rebooting Android does not reset anything.** The tuner keeps playing through a reboot, the
   MCU's RAM keeps its state, buffers keep their contents. Only cutting power (ACC OFF) gives a
   clean slate — and only a person can do that.
2. **Uninstalling your app does not stop the sound.** If the mixer is on the tuner input, the radio
   plays on over a dead application until somebody else takes the audio focus.
3. **The MCU is a fragile state machine that responds to sequences, not commands.** Order is part
   of the contract with the hardware, not a matter of style.

## 1. Which firmware, and which chips

```
persist.sys.qf.mcu.version = QF05.V02.13.20251124.002121
                                                  └────┘
```

The last segment is a hardware code. Read it, do not guess:

- **third character from the end** is the external radio chip type — the platform itself does this
  in `McuManagerService.initRadioExtChip` and publishes `persist.sys.qf.radio.ext`. `002121` → `2`
  → TDA7708. `004121` → `4` → NXP6686;
- **`00xx21` means the sound processor is a ROHM BU32107.** The alternative is a BD37544, which is
  analogue and does roughly half as much.

⚠️ The MCU **speaks one command set to both sound processors** and makes the lesser one look
complete: the BD37544 accepts commands it cannot honour, and the BU32107's abilities are trimmed in
firmware until they nearly match. Commands cannot tell them apart. Only the version string can.

If the version cannot be read, assume the **lesser** chip. Promising hardware that is not there is
worse than promising nothing.

Two practical notes: the BD37544 is analogue and so adds no measurable delay — ignore it in any
timing model; and the BitPerfect audio module is not installed on BD37544 units, so those are
almost always on factory audio policies.

## 2. Reaching the MCU from an app: reflection, no root

```java
Class<?> sm = Class.forName("android.os.ServiceManager");
IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "mcu_service");

Class<?> stub = Class.forName("android.qf.mcu.IMcuManager$Stub");
Object mcu = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

Method setEqData  = mcu.getClass().getMethod("RPC_SetEQData", byte[].class);
Method sendMcuMsg = mcu.getClass().getMethod("RPC_SendMcuMsgData", byte.class, byte[].class, int.class);
```

No root, no special permission, no system signature. See [01-SYSTEM.md](01-SYSTEM.md) §5 for why
R8 must stay off.

## 3. The frame on the wire

`McuManagerService.sendRequestWriteUart` builds it:

```
FF FD FE [payloadLen+2] 01 [msgType] [payload...] [crc] FF
                        ^            ^
                     constant     your command
crc = 0x01 XOR msgType XOR every payload byte
```

🔴 **The command is the msgType, not the first payload byte.**

```
sendRaw(0x88, {a,b,c})        FF FD FE 05 01 88 a b c crc FF     correct
sendRaw(0x01, {0x88,a,b,c})   FF FD FE 06 01 01 88 a b c crc FF  a doubled 01
```

That doubled `01` silently corrupted every raw command for a long time and looked like a tuner
sensitivity problem.

**The framework does not filter anything.** The old belief that only `0x01/0x02/0xA0/0xA1` get
through is false: `0xA0`/`0xA1` go to a transparent channel and *everything else* goes to
`sendRequest` — also into the MCU. The framework is a transit wrapper with no state machine of its
own, so any broken sweep or lost command is yours, not its.

⚠️ It does have two side effects worth knowing: `A0 06` (setBand) is intercepted and pokes the
audio routing chip 300 ms later, and every `A0`/`A1` passes through the platform's own volume
manager.

## 4. The command map

The dispatcher lives at `FUN_0800bc08` in the firmware. Commands `0x80`–`0x87` go through a jump
table that Ghidra does not recover — it is unpacked in
[04-FIRMWARE-PATCHING.md](04-FIRMWARE-PATCHING.md).

### Sound processor

All of these go through `RPC_SetEQData(byte[])`, first byte = command. What they become inside the
chip is in [03-SOUND-PROCESSOR.md](03-SOUND-PROCESSOR.md).

| cmd | len | what | encoding |
|---|---|---|---|
| `0x80` | 12 | 16-band equaliser | 8 packed bytes, two bands each (`idx2<<4 \| idx1`), index 0..12 = −12..+12 dB in 2 dB steps; 2 bytes of Q bitmask; **12th byte selects a factory curve** |
| `0x81` | 4 | fader / balance / loudness | L-R step, F-R step (12 = centre, 0..24), loudness flag |
| `0x82`..`0x87` | — | **never sent by any app we have** — see [04](04-FIRMWARE-PATCHING.md) |
| `0x88` | 4 | bass boost and high-pass, front and rear | `((boostFreqIdx+8)<<4) \| boostLevel` per channel, then `(hpfFront<<4) \| hpfRear` |
| `0x89` | 6 | surround / RSSE + surround delays | `138 + (rsse − 10)`, then FL, FR, RL, RR; **one step = 2.125 ms** |
| `0x8A` | 0 | commit / refresh |
| `0x8B` | 2 | subwoofer | `(freqIdx << 4) \| gainIdx`, frequencies `{25,32,40,50,63,80,100,125,160,200,250}` Hz |
| `0x8C` | 6 | positional delays | FL, FR, RL, RR, Sub, each **slider × 5**; one register unit = 0.1 ms, one step = 0.5 ms |

🔴 **The Q bitmask in `0x80` does nothing.** The firmware stores it and never reads it; every band
is Q = 2.2. Proven at instruction level — [03-SOUND-PROCESSOR.md](03-SOUND-PROCESSOR.md) §7.

Separately, not through `RPC_SetEQData`:

| message | what |
|---|---|
| `RPC_SendMcuMsgData(0x18, {1, x})` | steering-wheel button type |
| `RPC_SendMcuMsgData(0x18, {2, x})` | **power amplifier pre-gain** |
| `RPC_SendMcuMsgData(0x18, {5, x})` | CAN bus rate |
| `RPC_SendMcuMsgData(0x18, {6, …})` | reset audio settings |

### Tuner

`0xA0` with a sub-command, plus `0xA1`. Detail in [06-TUNER.md](06-TUNER.md).

### Incoming

The MCU reports in frames named by their first byte: `b0` status, `b1` frequency, `b2` band limits,
`b3` RDS flags, `b4` RDS present, `b5` PTY, `b6` PS, `b7` RadioText, `0x24` ignition, `0x29`
heartbeat every ~1.5 s.

🔴 **Status is reported on demand, not periodically.** At rest only the heartbeat and RDS arrive —
`b0`, `b3` and `b5` come only when a command changes what they contain. So between service start
and the first command **you do not know the hardware's state**, and showing defaults as fact makes
the UI lie. Keep an explicit "not known yet" state.

The heartbeat carries no state — that hypothesis was tested by toggling three flags and watching it
not change by a bit.

## 5. Send discipline

Learned by flooding the bus and watching things break:

- **Deduplicate per command.** Do not send a frame identical to the last one for that command.
  Without it, dragging a slider floods the MCU.
- **Throttle the frequent ones** (equaliser, subwoofer) to ~500 ms, with a trailing write so the
  final value still lands.
- **One background thread** at `THREAD_PRIORITY_AUDIO`. Never the main thread.
- 🔴 **Wait for answers, not for timers.** "We are not Arduino people" — the MCU announces
  readiness with frames; count them. Constants in milliseconds are guesses tuned to one machine
  under one load. Timers are legitimate for exactly two things: detecting *silence*, and an upper
  bound so a thread cannot hang forever.
- ⚠️ Some commands are **toggles, not setters** — the MCU inverts its own state and ignores your
  payload. Read the real state from the reply frame, compare, and send only on a difference. A
  "set" that is really a toggle, sent on every start, produced a class of bug that took days to
  find.
- ⚠️ Some commands get no ACK at all. The framework then retries every 100 ms forever and the bus
  chokes. That is what made RDS look like it "switched on when it felt like it".

## 6. The bus inside an app is usually SharedPreferences

Both apps on this platform ended up with the same shape, and it has one sharp edge:

```
UI → prefs → OnSharedPreferenceChangeListener in the service → RPC_*
```

🔴 **The listener tells keys apart by substring.** So **naming a preference is a wiring decision**:
a key with `_f_` anywhere inside it will silently resend the fader frame, and a key matching nothing
is stored and goes nowhere. The keys also have no schema — in wDSP, `_q0..q15` are **booleans**
while everything beside them is an int, and writing one as the wrong type crashes the service the
moment it reads the preset.

## 7. Volume: what you read is not what you wrote

`android.qf.os.VolumeManager` / `VolumeState`, by reflection.

🔴 **After `setVolumeVal(N)` the next read still returns the old value**, and while music plays the
platform runs its own curve per source, so it may keep returning a different number indefinitely.

➡️ **Any algorithm that both writes and reads the volume must keep "what I sent" and "what was
reported" in separate variables.** wDSP's speed-dependent volume died exactly here: its "last read"
held its own command, the mismatch read as a person turning the knob, the base was re-learned, and
the algorithm set the volume that was already there. A fixed point — it never moved again, and only
under music, because only then was the mismatch permanent.
