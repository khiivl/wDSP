# The audio ownership contract between wDSP and QF Radio

Agreed 26.08.2026 between the two applications. **Nothing here is implemented yet** — it was
settled before either side wrote a line, deliberately, and the owner opens the cycle that builds
it. This file is the specification; if the code and this file ever disagree, one of them is a bug.

---

## Why it exists

Radio and wDSP are two independent state machines on one MCU audio path, and neither knows what
the other is doing.

- On a volume change the radio synchronises the levels (`sys.media.vol` = `sys.radio.vol`) and
  holds the FM channel.
- On the same change wDSP sends an EQ packet (`0x80`), because its curve depends on the volume —
  `McuService.updateEqWithFm()` / `applyVolumeDependentSettings()`.

wDSP polls every **100 ms** (`pollingRunnable`) and throttles EQ writes to **500 ms**
(`THROTTLE_MS`). So it arrives *after* the radio, chasing intermediate volume values, and lands on
top of a state the radio had just finished arranging. That is the race.

---

## Who owns what

| | owner |
|---|---|
| MCU channel | **radio** |
| base volume level, source-switch synchronisation | **radio** |
| offset on top of that base (GALA) | **wDSP** |
| equaliser, tone, subwoofer | **wDSP** |

Two of these needed correcting during the negotiation, and the corrections are the useful part:

🔴 **Volume could not simply become the radio's.** GALA *is* volume writing — that is the whole
feature. `VolumeHelper.setVolume()` is called from three GALA paths (`McuService:734`, `:817`,
`:844`), plus `checkForBug()` at `:595`, which lifts a volume of 0 on an unmuted amplifier to 1 and
exists to stop the jitu firmware destroying a subwoofer. So the split is **base versus offset**, and
wDSP already had the concept it needed: `baseStandstillVolume`.

🟢 **The channel needed no negotiation at all.** wDSP has never written it — the only mentions in
the whole project are reads of `sys.qf.sound.channel` (`McuService:895`, `NowPlaying:55`).

---

## The signal

Radio → wDSP, sent **after** the radio's last write of a synchronisation, never before:

```
action:  com.radiorubka.wdsp.AUDIO_STATE_STABLE
package: com.radiorubka.wdsp          // explicit, not a broadcast into the air
extras:
  int    channel   // 2 = FM. When releasing: whatever it actually set (4/MPU), or -1 if untouched
  int    volume    // ADVISORY, never a command - see below
  String source    // "radio" | "idle"
  int    seq       // monotonic, so a late message can be dropped
  long   at        // SystemClock.elapsedRealtime()
```

On receipt wDSP re-reads the volume itself, re-baselines `baseStandstillVolume`, and applies the EQ
**exactly once**.

### `volume` is advisory on purpose

The two sides do not share a scale — wDSP clamps to `Math.min(32, base + offset)` while
`STREAM_MUSIC` on this unit reports `Max: 15`. Stitching scales across IPC is its own class of bug.

🔑 **The contract is the edge — "the state is stable now" — not the number.**

### 🔴 Ordering is by `at`, never by `seq`

`seq` is monotonic only **within one run of the radio**. Reinstall it, or let the system kill and
restart it, and it begins again from a low number — at which point a receiver ordering on `seq`
alone rejects every signal the radio ever sends again, and goes on rejecting them, because nothing
on the wDSP side resets until `McuService` itself is recreated. From outside that does not look
like a fault; it looks like the contract quietly not existing.

So `at` — `SystemClock.elapsedRealtime()`, one clock both applications read — is the ordering key.
It only goes backwards when the unit reboots, and then both sides start from nothing anyway. `seq`
separates two signals inside one millisecond, and reads well in a log.

⚠️ `at` must be `elapsedRealtime()` and nothing else. `currentTimeMillis()` jumps when the clock is
synchronised, and the two sides would part company at that moment.

🔴 **`at` is never 0.** When the radio has nothing stored to replay — a fresh process that has not
announced yet — it must send the current `elapsedRealtime()` rather than a zero. This is
load-bearing, not tidiness: wDSP orders by the clock only while `at > 0` and otherwise falls back to
comparing `seq`, and `seq = 0` is the lowest number there is. A reply carrying `at = 0` and
`seq = 0` would therefore be rejected every time wDSP's service had not just restarted. Measured
26.08.2026: two consecutive queries into a freshly started radio returned `seq=0` twice with
`at` 26 seconds apart — the fallback doing exactly its job.

Measured on the unit, 26.08.2026: `seq=40 at=500000` accepted, `seq=41 at=400000` ignored (a higher
counter on an older clock), `seq=1 at=600000` accepted — the restarted radio that would otherwise
have been locked out permanently.

### One event stream, not two

`source="idle"` (the radio has given up the channel or the focus) travels on the **same action**.
A second action would be a second queue, and `seq` orders messages only within one stream; the
first thing we would write is the code that stitches the two back together.

🔴 **`idle` means "re-baseline from the live volume". It does not mean "stop GALA".** The radio
going quiet does not stop the car: music plays, the car accelerates, GALA keeps working. Both sides
recorded this interpretation explicitly, because the word invites the opposite reading.

---

## The missed event

A signal is an edge, and whoever did not hear it does not know the state. This is not theoretical:

- wDSP is reinstalled — and after `adb install -r` **`McuService` does not come back on its own**
  (the process shows in `pidof`, the service is dead, broadcasts reach nobody);
- wDSP crashes and restarts;
- the unit wakes and the start order falls differently.

In all three the radio has already sent its signal, and wDSP sits on a stale base — the very race
this contract removes.

So, symmetrically, wDSP asks once when `McuService` starts:

```
action:  com.kostyamat.fmradio.AUDIO_STATE_QUERY
package: com.kostyamat.fmradio
extras:  none
```

The radio answers with an ordinary `AUDIO_STATE_STABLE` carrying its current state — the same single
signal, no second message type. If the radio is not installed, or says nothing, wDSP behaves exactly
as it does today. **Neither application requires the other.**

📌 **The query does not wake the radio, and must not.** An explicit broadcast does not start a
stopped application — `FLAG_INCLUDE_STOPPED_PACKAGES` is deliberately not set. If somebody has
closed the radio, we do not resurrect it to ask it a question. Observed on the unit 26.08.2026: the
query went out while the radio process was dead, nothing answered, and wDSP carried on without an
error — which is the whole of the intended behaviour, and is easy to mistake for a fault later.

---

## Also agreed, and worth not relearning

⚠️ `sys.qf.sound.channel`: **`2` is evidence, `4` is evidence of nothing.** It only reads honestly
on a unit carrying the BitPerfect policies and wanders on a factory one. The radio writes `4` (MPU)
only deliberately, when it is itself giving the channel away, and it keys its own logic on `== 2`.

The radio side lives in a helper (`WdspAudioContract`) rather than as strings scattered through
`RadioService`.

**A muted amplifier is a legal input.** `VolumeHelper.getVolume()` reads back 0 under mute, and 0 is
not a base — it is the absence of one. Taking it looks harmless until the mute comes off, when GALA
restores base plus offset into near silence. The radio gates its own send on `v > 0`, but wDSP does
not rely on that: under mute, or a level of 0, it leaves the base alone, applies the EQ, and lets
the ordinary unmute recovery re-establish the base when there is sound to measure against.

**`source="media"` was considered and rejected.** The radio would be announcing a state it neither
owns nor controls; wDSP keeps its own per-source bases (`media_standstill`, `aux_standstill`,
`btcall_standstill`, `radio_standstill`) and there is no race in media mode because the radio writes
nothing there. `idle` already covers the only moment that matters — the radio giving up the path.

---

## Status

Both sides are implemented and on the unit. Joint testing, 26.08.2026:

| behaviour | state |
|---|---|
| delivery radio → wDSP | ✅ repeatedly, 25-65 ms |
| delivery wDSP → radio (`QUERY`) | ✅ answered every time the radio was running |
| acted on exactly once per signal | ✅ |
| the base wDSP re-reads equals the platform's own level | ✅ `base=4` against `sys.radio.vol=4`, every time |
| a stale signal is dropped | ✅ higher `seq` on an older `at` ignored |
| **recovery after the radio restarts** | ✅ **on a real restart, not a simulation** — `seq` reset to 1 and was accepted on the clock, where `seq` ordering would have locked the contract out for good |
| `QUERY` reply repeats `seq` | ✅ |
| `QUERY` reply repeats `at` | ⏳ untested — the radio had never announced, so there was no `at` to repeat |
| `idle` re-baselines rather than silencing GALA | ✅ on a real `idle` from the radio |
| **a real volume knob** | ✅ **proven** via `keyevent 293/294` (27-29.08.2026) |
| the radio's sync switch set to OFF | ✅ **resolved & verified** (31.08.2026) via `persist.sys.qf.radio.sync_vol` |

### 🎚️ Синхронізація гучності: вимкнення користувачем (`persist.sys.qf.radio.sync_vol`)

*(✍️ Досліджено та впроваджено Antigravity (Gemini) — 31.08.2026 03:27)*

- **Проблема**: Користувацький тогл «Синхронізація гучності з плеєром» у радіо при вимкненні не зупиняв синк у `wDSP`, оскільки `wDSP` автономно у своєму 100-мс поллінгу (`carryBaseToOtherSource`) переносив гучність на інше джерело, не знаючи про стан налаштування радіо.
- **Рішення**:
  - Радіо пише стан тогла у системну властивість `persist.sys.qf.radio.sync_vol` (`"true"` / `"false"`, дефолт `"true"`).
  - `wDSP` (`McuService.java`) у `carryBaseToOtherSource()` читає `HardwareProfile.systemProperty("persist.sys.qf.radio.sync_vol")`. Якщо значення `"false"` — синк між джерелами блокується, і кожне джерело зберігає власну незалежну гучність (`media_standstill` vs `radio_standstill`).
  - У fallback-режимі Радіо (`RadioService.java`) в `acquireAudioTract()` та `releaseAudioTract()` також перевіряє стан `prefs.isVolumeSyncEnabled()`, перш ніж записувати `sys.media.vol`.

`announceRadio` is proven **on a source transition** — the radio taking the channel as its process
started. On a **level change from the knob** it is proven via `keyevent 293/294`.

The witness for that proof is a line that is *absent* from the wDSP log: at 15:44:46 no
`asked com.kostyamat…` preceded the signal, so it was not a reply to a query, so it came from
`announceRadio`. Recorded here because the evidence lives on this side, not the radio's.
