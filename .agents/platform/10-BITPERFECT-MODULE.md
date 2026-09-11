# The BitPerfect module: what it changes, and where to change it

This is the map. When something in the audio path has to be adjusted on these units, the answer is
almost always a file in this module — and this file says which one, what is already in it, and what
was measured about it.

**Repository:** `D:\My_K706_Magisk_Modules\BitPerfect2\`
**Current release:** `QF_BitPerfect.module.v5.3-Universal.zip` · `versionCode 503` — 📻 installed and
running on the reference unit (BU32107, single BT): detection names the chip correctly, only its own
route file remains, `Audio path is healthy` in the log. **Not yet tested on BD hardware.**
**On the unit:** `/data/adb/modules/BitPerfect.module/`

### What v5.3 changes

| fix | why |
|---|---|
| detection reads `${HW_CODE:1:1}` (sound processor) and `${HW_CODE:3:1}` (path), cross-checked with `persist.sys.qf.arm.use.i2s` | the old `${HW_CODE:4:2} == "21"` read the control panel + power bitmask, was **true on every unit**, and handed BD37534 the 24-bit I2S profile |
| BD37534 never gets the I2S profile, whatever the other flags say | analogue inputs only |
| the opposite profile's route file is deleted on install | `cp -rf` never removes, so each unit used to keep a stray route the HAL could load |
| the watchdog checks the **stream**, not the policies | it printed SUCCESS over a dead path; now it looks for `state: SETUP` + `trigger_time 0` + a dead owner and restarts audioserver |
| `persist.sys.main_volume` is no longer touched | it is the user's start-up volume slider |
| the I2S width guard only runs where there is an I2S bus | it was forcing `WD_24BIT` every 3 s on analogue units |

⚠️ **Packaging trap.** The repository folder also holds notes, spare copies, old releases and — at
one point — a 13 MB video. A naive "zip the folder" produced a 21 MB package for people to flash.
Pack from an allow-list (`META-INF`, `system`, `common`, `profiles`, `module.prop`, `customize.sh`,
`service.sh`); a correct package is about **1.6 MB**, the same as v5.1 and v5.2.

---

## 1. Where to change what — the short table

| you want to change | file | node |
|---|---|---|
| **microphone sensitivity** | `system/vendor/etc/audio_params/sprd/audio_pga.xml` | `Music/<device>/Record/VBC_ADC0_DG/volume0` |
| playback digital gain | same file | `Music/<device>/Playback/VBC_DAC0_DG/volume0` |
| sidetone / DRC / HPF **on calls** | `audio_params/sprd/dsp_vbc.xml` | `Audio/<device>/<NB1…VOIP1>/st_control_*` |
| DRC on **music playback** | same file | `Music/<device>/Playback/st_control_0` — ⚠️ **factory, untouched, leave alone** |
| second microphone / AEC reference | `audio_params/sprd/audio_process.xml` | `aux_mic_enable` |
| which usage lands on which stream | `system/vendor/etc/audio_policy_engine_product_strategies.xml` | `AttributesGroup` |
| how loud each volume group is | `system/vendor/etc/audio_policy_engine_stream_volumes.xml` | `volumeGroup` curves |
| which navigators the platform ducks for | `system/config/NaviApp.ini` | one line per package |
| PCM devices, I2S width, routes | `audio_pcm.xml`, `audio_route.xml`, `qf_double_bt_audio_route_*.xml` | per profile |

---

## 2. 🎤 The microphone — the only lever that actually works

📻 Measured 28.08.2026, on the unit, four sweeps and several probes.

**Three levers look plausible. Two of them are dead.**

| lever | verdict |
|---|---|
| ALSA `ADCL/ADCR Gain … Capture Volume` (the CarSettings sliders) | ⚠️ was at **7 of 7** on the reference unit, so it looked like a dead end — but that was that owner's own setting. 📻 A second unit reports `4` and `1`, i.e. people do have room in both directions |
| `tinymix "VBC ADC0 DG Set"` at runtime | ❌ **the HAL overwrites it** from the XML every time it opens a capture path |
| `AudioSource.UNPROCESSED` (the `UnprocessRecord` block, gain `0x18` = 8×) | ❌ the block exists, the HAL **never uses it** |
| **`audio_pga.xml` → `Record` → `VBC_ADC0_DG`** | ✅ **the only durable one** |

🔬 The reset is easy to reproduce and impossible to argue with:

```
before the sweep:  VBC ADC0 DG Set  5 5
during the sweep:  VBC ADC0 DG Set  3 3     ← the HAL put its own value back
```

So anything set with `tinymix` lives only until the next capture starts. That is also why raising
the gain appeared to do nothing to a sweep while clearly working on ambient noise — the ambient
probe reused an already-open path, the sweep opened a new one.

### The three entries that matter

```
Music / Headset       / Record / VBC_ADC0_DG / volume0
Music / Handsfree     / Record / VBC_ADC0_DG / volume0
Music / TypeC_Digital / Record / VBC_ADC0_DG / volume0
```

`Bluetooth/Record` and every `UnprocessRecord` sit at `0x18` and are **not** on this path.

### The scale is linear in the register value

📻 Measured on ambient, doubling the value:

| value | rms | peak |
|---|---|---|
| `3` | −28.9 dBFS | −15.5 dBFS |
| `6` | −22.2 dBFS | −8.6 dBFS |

+6.8 dB for ×2, against a theoretical +6.02 — linear, within the drift of ambient noise.

⚠️ **Headroom is the constraint, not the gain.** A cabin sweep at media volume 5 already peaks at
**−6.4 dBFS**. Every step of gain eats that margin, and a clipped sweep is a ruined measurement:

| value | gain | sweep peak at volume 5 |
|---|---|---|
| `0x03` factory | — | −6.4 dBFS |
| **`0x04` (shipped in v5.2)** | **+2.5 dB** | ≈ −3.9 dBFS |
| `0x05` | +4.4 dB | ≈ −2.0 dBFS — too close |

If more sensitivity is ever needed, the sweep amplitude has to come down with it.

---

## 3. What the module changes in the AGDSP parameters

All seven files differ from factory. The factory also ships `codec.xml`, which the module does
**not** carry — the factory copy stays in force.

| file | what changed | scale |
|---|---|---|
| `dsp_vbc.xml` | `st_drc_en_l/r` and `st_hpf_en_l/r` `0x01` → `0x00`; `st_en_l/r` off in 6 profiles | 63 profiles per channel |
| `audio_process.xml` | `aux_mic_enable` `0x01` → `0x00` | 120 lines |
| `audio_pga.xml` | input gains levelled to `0x20`; capture `Record` `0x03` → `0x04` in v5.2 | 10 + 3 |
| `audio_structure.xml` | **identical to 4.18**, `md5 e6df1a9e81` | — |
| `audio_effects.xml` | **identical to 4.18**, `md5 250123e35e` | — |

🔴 **The DRC that is switched off is the one on voice calls, not on music.** Walked the tree to be
sure: the 63 profiles live under `Audio/<device>/{NB1,NB2,WB1,WB2,SWB1,FB1,VOIP1}/st_control_*` and
under `Loopback`. On `Music/<device>/Playback/st_control_0` the value is `0x1` — **enabled, and
byte-identical to factory**. Nothing the module does removes the limiter from the music path.

This matters because it was suspected of causing bad mixing when music and a navigation prompt sum
together. It cannot: that path was never touched.

📻 Counted per AGDSP mode, ours against factory:

| mode | ours | factory |
|---|---|---|
| `Audio` — the call profiles | 118 × `0x00` | 112 × `0x01` |
| `Loopback` | 8 × `0x00` | 8 × `0x01` |
| **`Music`** | 10 × `0x1` | 10 × `0x1` — **identical** |

🔑 **A navigation prompt is not a separate case here.** It plays out of the SoC like any other
Android audio, so it travels the same `Music` profile as the music it is supposed to be heard over —
and that profile is untouched. Which also means the DSP **cannot tell the two apart**: any
difference between a prompt and the music has to come from the Android policy, from groups and
curves. There is no lever for it down here.

⚠️ ❓ **`Loopback` is the one neighbouring path that was touched** — 8 nodes. If the analogue FM or
AUX signal passes through the codec's loopback rather than going straight from the MCU to the
amplifier, this is the branch it uses. Not established either way, and worth knowing before blaming
anything else for how FM behaves against a prompt.

### AGC

📻 The AGDSP's own AGC is **on** and always was: `dl_EQ_AGC_switch` = `0x0101` at ids `0x1ac` and
`0x486` (`0x760` is off), in a file identical to 4.18. Android's *effect*-level `agc` is absent
from `audio_effects.xml` — also since 4.18, also not a regression. Adding it would stack a second,
software AGC on top of a hardware one that already works; do not, without a reason.

---

## 4. Traps that have already cost time here

🔴 **An upgrade does not clean the old module directory, and `$MODPATH` is not where the module
lives.** During `magisk --install-module`, `$MODPATH` points at `/data/adb/modules_update/<id>` —
a staging copy. The installed `/data/adb/modules/<id>` keeps everything an earlier version put
there, and files the new version simply does not ship are never removed.

📻 Verified twice on a BU unit upgrading v5.2 → v5.3: `rm -f "$MODPATH/…/qf_double_bt_audio_route_noi2s.xml"`
ran, and the file was still in the live module directory afterwards. Kostyantyn named the cause
before the second test finished — *"you delete it, Magisk restores it"*.

So anything that must **not** be present has to be deleted from **both** paths in `customize.sh`:

```sh
LIVE=/data/adb/modules/BitPerfect.module/system/vendor/etc
drop_route() { rm -f "$MODPATH/system/vendor/etc/$1"; rm -f "$LIVE/$1"; }
```

This matters beyond tidiness: the HAL picks its route file from
`use.i2s` × `use.a2dp_route`, so a stale route is one the HAL is entitled to load.

⚠️ **`module.prop` lies between install and reboot.** Magisk writes the new `module.prop` into
`/data/adb/modules/` while the payload beside it is still the old one, so the module reports the new
version before it is doing anything new. **Check content, never the version string.**

✅ **Fixed 28.08.2026 — the heal block is gone, deliberately.** What follows is why, so nobody adds
it back as an improvement.

🔴 **The v5.1 heal block overwrote a legitimate user setting.** It rewrote
`persist.sys.main_volume` from `15` to `12`, on the theory that `15` can only be v5.0's mistake. It
cannot: 🔬 **that property is the start-up volume slider in CarSettings**, on a 0…32 scale, and `15`
sits squarely inside the range a person can choose. Anyone who happens to land there gets silently
pulled back to 12 on every boot.

⚠️ **Two properties, constantly confused — including in this file until now:**

| property | scale | what it is |
|---|---|---|
| `persist.qf.arm.default.volume` | 0…15 | the **Android mixer**; 15 = unity. This is the module's actual intent since 4.18 and must stay |
| `persist.sys.main_volume` | 0…32 | the **start-up volume slider** in CarSettings, a user setting |

📻 v4.18 sets only the first one, three times in `service.sh` and once in `system.prop`. It never
touches the second. v5.0 wrote `15` into the second as well, on the assumption it was the same
scale — that is the whole bug, and the heal block is the over-correction. (The owner of this unit keeps it at **1**, so that an autonomous session cannot startle
the household after a reboot — which is exactly the kind of deliberate choice the block was
supposed to respect.)

A module cannot distinguish "our old mistake" from "the owner's choice" by value alone. Two options
existed — leave a marker in v5.0 and heal only on that marker, or drop the healing entirely.
**Kostyantyn chose to drop it**, on the grounds that v5.0 was pulled from distribution quickly and
reached few units, and anyone affected can move a visible slider back in two taps. Silently
rewriting a user's setting to repair our own old bug is not a trade worth making.

The property is now referenced in `service.sh` only by a comment explaining this.

⚠️ **`resetprop` does not make a `persist.` property persistent.** It writes memory only; without
`-p` nothing reaches `/data/property/`, and the value is gone at the next boot. That is why the
module re-sets its properties in a loop every boot — and why a module's damage to such a property
heals itself on reboot, along with any fix.

🔴🔴 **The audio chip test is always true, and that is why BD units rasp.**

`customize.sh` tests `${HW_CODE:4:2} == "21"` — characters 4 and 5. 🔬 Those are the **control panel
type** and the **power/op-amp bitmask** (`ProductInfoConstants.EX_DEVICE_TYPE_ARRAY`,
`POWER_OFF_METHOD_ARRAY`). Neither has anything to do with audio, and both are identical across the
fleet:

| firmware | real hardware | `${HW_CODE:4:2}` | what the module installs |
|---|---|---|---|
| `002121` | BU32107 + TDA7708 | `21` | BU I2S profile ✅ correct |
| `004121` | BU32107 + TEF6686 | `21` | BU I2S profile ✅ correct |
| `001121` | BU32107 + TSC4745 | `21` | BU I2S profile ✅ correct |
| `011021` | **BD37534**, analogue | `21` | **BU I2S profile** 🔴 **wrong** |

The test has never distinguished anything — it is true on every unit, so the BD branch is dead code
and **every** BD head unit receives the 24-bit I2S profile for a processor that has only analogue
inputs.

✅ **The correct test is `${HW_CODE:1:1}`** — the sound processor: `0` BU32107, `1` BD37534,
`2` AK7738, `3` AK7604. Or, equivalently for routing, read what the HAL already published:
`persist.sys.qf.arm.use.i2s`. Full decode table in
[13-MCU-FIRMWARE-VARIANTS.md](13-MCU-FIRMWARE-VARIANTS.md) §1.

📻 On this unit the property is already there and correct:

```
persist.sys.qf.arm.use.i2s = true
persist.sys.qf.radio.ext   = true
persist.sys.qf.mcu.version = QF05.V02.13.20251124.002121
```

Read it. Do not re-derive it. The decode table for every position is in
[13-MCU-FIRMWARE-VARIANTS.md](13-MCU-FIRMWARE-VARIANTS.md) §1.

⚠️ **A failed detection must land on the safe profile.** Since v5.1 the shipped `system/` holds the
**BD37544 16-bit** files, so any failure path is silent rather than noisy, and the installer says so
out loud. On a BU32107 the correct profile is copied over it at install.

---

## 5. Reading the factory copy of any of these files

🔑 **Easiest source first: the unpacked firmware on the desk.**

```
D:\Release\update(2)\vendor\vendor\etc\          every audio XML, all four routes
D:\Release\update(2)\system\system\system\config\NaviApp.ini
```

Untouched, no root, no mounting, no module overlay in the way. This is where to look when the
question is "what does the factory ship" — which is almost always the question. Mount the partition
on a unit only when the question is specifically "what does *this* head unit have", e.g. when
collecting a snapshot from somebody else's hardware whose firmware build may differ.

The overlay hides the original, and this build of Magisk has no mirror. The vendor partition can be
mounted a second time, read-only:

```bash
mkdir -p /data/local/tmp/vraw
mount -o ro /dev/block/dm-1 /data/local/tmp/vraw
cat /data/local/tmp/vraw/etc/audio_params/sprd/audio_pga.xml
umount /data/local/tmp/vraw && rmdir /data/local/tmp/vraw
```

⚠️ Do not `cd` into the mount point — the unmount then fails with "Device or resource busy".
`/vendor` here is an overlayfs (`lowerdir=/vendor`, `upperdir=/mnt/scratch/overlay/vendor/upper`),
and the upper layer holds no audio files: the overriding is Magisk's, not overlayfs's.

---

## 7. The HAL picks the route itself — the module must not fight it

📻 Found by grepping the vendor partition for the property: the only binary that touches it is
**`/vendor/lib/hw/audio.primary.ums512.so`**, and it does not merely read it — it *writes* it.

```
persist.sys.qf.mcu.version          ← the HAL parses it itself
set_mpu_i2s_property                ← and publishes persist.sys.qf.arm.use.i2s
parse_audio_route, mcu version2=%s, index=%d, i2s flag=%s
persist.sys.qf.use.a2dp_route       ← second flag: single or double Bluetooth
persist.sys.qf.product.name

/vendor/etc/qf_audio_route_has_i2s.xml
/vendor/etc/qf_audio_route_no_i2s.xml
/vendor/etc/qf_double_bt_audio_route_i2s.xml
/vendor/etc/qf_double_bt_audio_route_noi2s.xml
```

So the route is chosen by **two** flags, and the module ships only half the matrix:

| `a2dp_route` | `use.i2s` | file the HAL loads | module ships it? |
|---|---|---|---|
| true | true | `qf_double_bt_audio_route_i2s.xml` | ✅ |
| true | false | `qf_double_bt_audio_route_noi2s.xml` | ✅ (byte-identical to factory — redundant) |
| **false** | true | `qf_audio_route_has_i2s.xml` | ❌ factory copy stays |
| **false** | false | `qf_audio_route_no_i2s.xml` | ❌ factory copy stays |

🔴 **And the leftover file is worse than the missing ones.** `customize.sh` copies the chosen profile
*over* `system/`, which already holds the BU variant — and `cp -rf` never deletes. A BD unit
therefore keeps `qf_double_bt_audio_route_i2s.xml` from the BU set, carrying **`WD_24BIT`** on an
I2S bus that BD hardware does not use. 🧩 That is the most likely source of the digital rasp people
report on BD units — not "wrong profile", but a stale file the HAL is entitled to load.

### 🪤 "Identical to factory" does not mean "redundant"

An audit of every shipped file against the factory copy shows two in the BD profile that are
byte-identical to what the system already has:

```
profiles/bd37544_noi2s/…/audio_pcm.xml                     identical to factory
profiles/bd37544_noi2s/…/primary_audio_policy_configuration.xml   identical
profiles/bd37544_noi2s/…/audio_route.xml                   identical
profiles/bd37544_noi2s/…/qf_double_bt_audio_route_noi2s.xml identical
```

The obvious conclusion — "delete them, they overlay a file with itself" — is **wrong**, and acting
on it briefly broke the BD branch here. `system/` is pre-seeded with the **BU** variant, and the
chosen profile is copied *over* it. A factory-identical file in the BD profile is not a copy of
itself: it is what **restores the factory file over the BU one**. Remove it and a BD unit inherits
the BU `audio_route.xml`, complete with `WD_24BIT` on a bus it does not have.

🔑 So the rule is: judge a profile file by what it replaces in `system/`, not by whether it differs
from the factory. The only genuinely removable file is one that is identical to factory **and** has
no BU counterpart in `system/`.

### 🔑 Double BT is a *setting*, not hardware — and only one route file carries the width

The `i2s / no_i2s` axis is the board and never changes. The `double_bt / single` axis is a **user
setting**: it is switched in CarSettings and takes effect on reboot (Kostyantyn). So a unit must be
correct in **both** BT modes, and a module that ships only one of the pair is wrong for half the
time on the same head unit.

📻 But counting the actual control across all four factory routes changes what "correct" means:

| factory route | `VBC_IIS_MST_WIDTH_SET` entries |
|---|---|
| `qf_double_bt_audio_route_i2s.xml` | **10** (5 × `WD_16BIT`, 5 × `WD_24BIT`) |
| `qf_audio_route_has_i2s.xml` | **0** |
| `qf_audio_route_no_i2s.xml` | **0** |
| `qf_double_bt_audio_route_noi2s.xml` | **0** |

The master I2S width is set **only** in the double-BT + I2S route. Nowhere else does the factory
touch it.

That explains an experiment that looked baffling: forcing `WD_16BIT`, restarting audioserver and
watching the factory `has_i2s` route fail to restore 24 bit. It was never going to — that file has
no such line. So:

| mode | what holds 24 bit |
|---|---|
| double BT + I2S | the **route file**, our five edits |
| single BT + I2S | the **module's watchdog**, every 3 s — there is nothing in the route to edit |
| BD, either mode | nobody, correctly: no I2S bus exists |

🧩 Therefore the module should **not** ship `qf_audio_route_has_i2s.xml`. There is nothing in it to
change, and inventing lines the factory never wrote would be a guess about routing structure. The
watchdog is the mechanism for single-BT, and it is enough — 📻 verified with `a2dp_route=false`:
width stayed `WD_24BIT` across repeated samples, watchdog alive.

⚠️ What the module *must* still do is delete the route belonging to the **other chip** (i2s ↔
noi2s), because that one is about hardware and a stale copy is loadable.

### What the BU route edits actually are

📻 Normalised for line endings, the whole change is five lines, in `<off>` blocks:

```
-  <ctl name="VBC_IIS_MST_WIDTH_SET" val="WD_16BIT" />
+  <ctl name="VBC_IIS_MST_WIDTH_SET" val="WD_24BIT" />
```

Counted across the factory files: `has_i2s` 23×16/26×24, `no_i2s` 13×16/25×24,
`double_bt_i2s` 18×16/41×24. The module fixes 5 of the 18 in the one file it ships.

⚠️ Six module files are stored with **CRLF** line endings (`audio_route.xml`, the three
`audio_policy_*`, `primary_audio_policy_configuration.xml`, `qf_double_bt_audio_route_i2s.xml`) while
the factory uses LF. ❓ No evidence it harms anything — XML parsers tolerate it — but it makes every
naive diff useless, and it hides real changes.

### Two separate things that were being confused

🔬 `format="3"` in `audio_pcm.xml` is the **DMA buffer width toward the DAC**.
🔬 `WD_24BIT` in a route is the **I2S bus width**.

On a BD board the chain is `Android → VBC → SC2730 DAC → analogue → BD37534 → amplifier`: the
BD chip has analogue inputs only, so no I2S reaches it and the bus width is meaningless there. The
buffer width still matters, and the factory already uses 24 bit on `mm_normal`.

📻 Proven on a live stream (BU unit, music playing):

```
pcm3p (FE_ST_FAST)   format: S24_LE   rate: 48000   channels: 2
                     period_size: 640   buffer_size: 2560
```

24/48 confirmed end to end. `buffer_size 2560 / 48000 = 53.3 ms` — the same figure the acoustic
latency measurement produced independently.

🧩 The safe improvement for BD is therefore **format only**: raise `fast` and `mmap_noirq` from
`format="0"` to `"3"`, leave `device=` alone, and touch no route. Still ❓ until one `hw_params`
from a real BD unit confirms that `fast` accepts S24_LE.

## 8. First report from another unit — 28.08.2026, NXP

📻 The first complete survey returned from hardware that is not on this desk. It is worth more than
a day of reasoning, and it corrected two of our beliefs.

```
MCU 004121  →  BU32107 + TEF6686(NXP) + I2S      the decoder was right
persist.sys.qf.use.a2dp_route = true             double BT (opposite to the reference unit)
AK hub regmap: all XX                            no chip, same as here
BitPerfect: module.prop v5.1, log says v5.0      installed, never rebooted
```

### The I2S width model is confirmed

```
BEFORE (silence):   >WD_16BIT  WD_24BIT      ← 16 bit
DURING (playing):    WD_16BIT >WD_24BIT      ← became 24 by itself
```

With `a2dp_route=true` the route file contains `VBC_IIS_MST_WIDTH_SET` and sets it when the path
opens — nobody has to hold it. With `a2dp_route=false` (the reference unit) that file is not the one
loaded, the width is in no route at all, and the module's watchdog is what keeps it. 🧩 Two units,
two mechanisms, both correct — and this is why a single "fix" for the width would have been wrong.

### 🔴 Ducking here is Android, not the hub

```
STREAM_MUSIC:  9  →  7 while the prompt speaks  →  9 after
STREAM_SYSTEM: 5 throughout
```

Two index steps, applied by Android. `persist.sys.navi_remix_ratio` is **73** on this unit and does
nothing at all: without an AK hub `AK7738VolumeManager.setMixAudio` never runs. So the owner has a
slider that cannot affect their hardware — which is exactly the kind of thing that produces two
people describing opposite behaviour.

### 🔴 The microphone sliders are not maxed out in general

```
reference unit:  mainmic.gain = 7   secondmic.gain = 7
this NXP unit:   mainmic.gain = 4   secondmic.gain = 1
```

"Already at 7 of 7, no headroom left" was a property of **one** unit, not of the platform. The open
question about re-centring the ALSA range is therefore withdrawn: people do have room in both
directions.

### What the old version actually does on that unit

⚠️ Everything above about volumes describes **v5.0 behaviour** — the module was installed but never
rebooted into. Two things stand out anyway:

- `persist.qf.arm.default.volume` reads **9**, while the log shows the module setting 15 on every
  boot (`Detected volume drop to 9! Restoring to 15...`). It sets it, something puts it back, and 9
  is what is live. Unity gain is **not** achieved there.
- Playback runs on `pcm0p` at `S16_LE` — the factory fast path, not device 3 at 24 bit. On a
  BU32107 unit with the module installed.

🧩 Both are consistent with a module that was flashed and never activated, which is why the
instructions now insist on the reboot in capital letters.

## 9. Open questions

- ✅ **Closed 28.08:** the CarSettings microphone sliders were suspected of being pinned at maximum
  platform-wide. They are not — a second unit reports `mainmic.gain=4`, `secondmic.gain=1`. The
  "7 of 7" was one owner's own setting. No range re-centring is needed.
- ❓ **Why the level of a prompt differs so much between FM and media** on NXP units. The measured
  facts: TEF6686 puts out ~1.0–1.2 V RMS, about +6 dB above a TDA7708 (🔬 Gemini, firmware and HAL),
  and one owner needs `navi gain` 7-8 on FM against 15 on media. Two owners on identical silicon
  report opposite ducking behaviour, which `navi_remix_ratio` explains — it runs backwards, and the
  MCU computes `ducking_step = (100 - ratio) / 10`.
- ❓ Whether the module should carry `codec.xml` at all, given the factory one stays in force.
