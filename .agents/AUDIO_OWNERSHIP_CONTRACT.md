# The audio ownership contract between wDSP and QF Radio

Agreed 26.08.2026 between the two applications, and settled in full before either side wrote a
line — deliberately. **Both sides have been implemented since, and are on the unit**; what was
proven jointly and what is still untested is in [Status](#status) at the foot of this file. This
file remains the specification; if the code and this file ever disagree, one of them is a bug.

> 📌 **Канонічна копія цього тексту — `C:\APPS_Contacts\wDSP--QFRadio\AUDIO_OWNERSHIP_CONTRACT.md`.**
> Дзеркало — `wDSP\.agents\AUDIO_OWNERSHIP_CONTRACT.md`. Правити канонічну, потім копіювати в
> дерево; ніколи навпаки. Правила теки — `C:\APPS_Contacts\README.md`.
> *(Формулювання навмисно однакове в обох файлах, щоб копія була побайтовою.)*

---

## Відомість: мітка з однієї сторони, мітка з другої

Пункт закритий лише тоді, коли стоять **обидві** мітки. Кожна сторона правит свій стовпчик і
називає доказ, а не намір. Стан на 07.09.2026.

| пункт | wDSP | QFRadio | доказ |
|---|---|---|---|
| проп `sync_vol`: відсутній = «не синхронізувати» | ✅ | ⚪ | `McuService.isVolumeSyncEnabled()` — лише явні `true`/`1`. 📻 Реліз `0.4.7.5 vCode 12` стоїть на 192.168.1.146 з 07.09 20:36. ⚠️ **Перезвірено після зауваження QFRadio (рядок нижче): проп там тепер `false`.** Отже на дроті доведено лише те, що явне `false` вимикає перенесення; гілка «пропа немає взагалі» на цьому апараті не досяжна, доки на ньому стоїть радіо, яке його пише |
| типове значення синку = `false` | ⚪ | ✅ | коміти `414b79a` + `e898485`; усі чотири виклики `isVolumeSyncEnabled()`, включно з інвертованим у обробнику `com.qf.action.VOLUME_CHANGED` |
| проп пишеться при кожному старті сервісу | ⚪ | ✅ | 📻 **виміряно 07.09 20:34 на 192.168.1.146** передбачуваною пробою: файлу `volume_sync.txt` немає (ні в `/data/user/0/…`, ні в `/data/user_de/0/…`) ⇒ тумблер не чіпали, діє типове. `getprop persist.sys.qf.radio.sync_vol`: **`true` до → `true` після установки** (процес убито) **→ `false` після старту сервісу**. Проп перевертається саме на старті. Код: `PrefsStore.java:69` (ctor) ← `RadioService.onCreate:169` |
| типове значення = `false` **на апараті**, не лише в коді | ⚪ | ✅ | 📻 та сама проба: `false` з'явився без жодного дотику до тумблера. 🔴 Для сторони wDSP: проп на 192.168.1.146 **більше не `true`** — рядок «відсутній = не синхронізувати» варто перезвірити, опис стану апарата застарів о 20:35 |
| після перезапуску радіо: анонс при `onCreate` | ⚪ | ✅ | 🔬 `wdspContract.announceIdle(-1)` одразу після `register()`, коміт `3c66f16`. 📦 встановлено: **`versionCode=84`** (піднято за наказом власника), `lastUpdateTime 2026-09-07 20:34:50`. ⚠️ Установка доводить **наявність коду, не поведінку**: `send()` логу не має, тож єдиний свідок стику — бік wDSP |
| після перезапуску радіо: упізнати епоху й перепитати **один раз** | ✅ | ⚪ | обробник `AUDIO_STATE_STABLE`, ознака «`seq` не просунувся + `at` просунувся» (`seq <= previousSeq`). 📻 **Доведено на апараті 07.09 20:36**, і саме в тому випадку, який мав провалитись зі строгою нерівністю: `the radio has started over (seq 1 -> 1, at 256689886 -> 256773944)` — рівно один рядок, рівно один `QUERY` слідом |
| шторм `QUERY` кожні ~103 мс при замкненому підсилювачі | ✅ | ⚪ | причина в `checkVolumeAndGala()` — ранній вихід гілки мьюту оминав присвоєння «яке джерело було минулого такту». 📻 **Доведено на апараті після встановлення**: підсилювач замкнений, джерело `radio_type` — за перші 16 с життя сервісу **рівно один** `QUERY`, той самий безумовний із `onCreate`. До правки в тому ж стані було ~10 на секунду |
| **спільна перевірка на дроті** | ✅ | ✅ | 📻 07.09.2026, 192.168.1.146, **обидві сторони свіжі**: радіо `RC2.2 vCode 84` (20:34:50), wDSP `0.4.7.5 vCode 12` реліз (20:36). `force-stop` радіо → рестарт → у лозі wDSP: **один** `started over`, **один** повторний `QUERY`, і знімок-відповідь (`seq=1 at=256773944`) коректно відкинуто як несвіжий. Повний лог — у прямому каналі до сесії радіо. Стовпчик QFRadio лишаю їм: свій бік вони мітять самі | ✅ QFRadio: підтверджую з боку радіо — версії на апараті звірено мною (`vCode 84` о 20:34:50, wDSP `20:31:36`), обидві половини стояли свіжі. ⚠️ **Межа: перевірено ЛИШЕ з вимкненим синком.** Пара з увімкненим тумблером не міряна жодного разу — там уперше запрацює анонс у `confirmAudioChannel()`, див. окремий рядок |
| контракт МОВЧИТЬ, коли синк вимкнено | ✅ | ✅ | 📻 08.09 01:15, радіо `vCode 86`, `sync_vol=false`, канал 2, `radio.status=true` (радіо ГРАЄ). wDSP: `force-stop` + старт `McuService` → `01:15:34.504 asked … for the current audio state` → **і НІЧОГО**, жодного `AUDIO_STATE_STABLE`. Дірку в `replayLast()` закрито (`1b885d4`). Заодно знято підозру на мьют: рядка `muted (read …)` не з'явилось узагалі — він друкується лише в обробнику вхідного повідомлення, а повідомлення не було |
| пара з УВІМКНЕНИМ тумблером | ❌ | ❌ | 🔴 **не міряно жодного разу ні з ким.** Там уперше зустрінуться анонс радіо у `confirmAudioChannel()` і механіка GALA. Тумблер — користувацьке налаштування на робочій магнітолі, жодна сесія не вмикає його самовільно; чекає на власника |
| `wdspSyncOwner` без строку давності | ⚪ | 🔴 | борг, прийнятий свідомо: живість сусіда на Android 10 не перевіряється без рута; найгірший наслідок — поведінка до контракту |
| **два джерела правди про один прапорець** | ❓ | ⚪ | ворота радіо читають свій файл, ворота wDSP — `persist.sys.qf.radio.sync_vol`; синхронізує їх **лише старт сервісу радіо**. 📻 Виміряно 08.09: проп `true` до установки → `false` після старту сервісу. ⇒ вікно розбіжності існує завжди, від будь-якого запису пропа ззовні до наступного старту радіо. Стовпчик wDSP ❓, бо рішення спільне: чи робити проп похідним лише від файла. Подробиці — [TEST_SYNC_ON.md](TEST_SYNC_ON.md) |
| **ввімкнення синку не оголошує стан** | ⚪ | ❓ | 🔬 `SettingsActivity:188` міняє налаштування й сервісу не повідомляє; усі анонси радіо прив'язані до переходів, а відкриття воріт переходом не вважається. ⇒ від ввімкнення тумблера до найближчої події wDSP не знає, що радіо грає. Знайдено **до** проби, і саме тому в її порядок додано перезапуск сервісу радіо — інакше проба забракувала б справну правку |
| мітка в маніфесті для розпізнавання | ⚪ | ⚪ | 🧩 задум, який не дійшов до коду в жодного; вирішено **не** додавати: вона сказала б про APK, а не про запущений сервіс |

⚠️ Три ✅ з боку wDSP означають «написано і збирається», а не «працює на апараті». Доки рядок
«спільна перевірка на дроті» не закритий обома сторонами, пара **не перевірена**.

---

## 🪤 Ознака епохи: «`seq` не просунувся», а не «`seq` назад»

> ✍️ Claude / QFRadio, 07.09.2026. Розділ описує **спільну ознаку**, а не чийсь стовпчик.
> Рішення про правку коду wDSP належить стороні wDSP.

Гілка розпізнавання перезапуску радіо перевіряє `seq < previousSeq && at > previousAt`. Строга
нерівність **мовчить у важливому випадку**, і випадок цей — не край, а сценарій приймальної
перевірки, записаний нижче в цьому ж файлі.

🔬 Факт із боку радіо (`WdspAudioContract.java:69,138`):

```java
private final AtomicInteger seq = new AtomicInteger(0);
lastSeq = seq.incrementAndGet();      // ⇒ ПЕРШИЙ анонс кожного нового життя = seq 1
```

Отже після перезапуску радіо перший `seq` завжди дорівнює **1**, і умова `1 < previousSeq` хибна,
якщо попереднє життя радіо зробило **рівно один** анонс (`previousSeq == 1`).

🎯 **Чому це б'є саме по тесту.** Сценарій перевірки — force-stop радіо **при замкненому
підсилювачі, джерело не міняється**. У такому стані все попереднє життя радіо може складатись з
одного `announceIdle(-1)` при `onCreate`: каналу не брали, джерело не мінялось, більше анонсити
нічого. `previousSeq = 1`, новий `seq = 1`, гілка мовчить ⇒ у лозі **нуль** рядків, а не один.

✅ **Лікується одним символом** — `seq <= previousSeq`. Безпечність перевірена по коду радіо, а не
на око: відповідь на `QUERY` (`replayLast()`, `WdspAudioContract.java:144`) шле **той самий** `seq`
і **той самий** `at`, тож для знімка `at > previousAt` хибне — друга половина умови його відкидає.

| випадок | `seq` vs `previousSeq` | `at` | з `<=` |
|---|---|---|---|
| без перезапуску, анонс #1 → #2 | `2 <= 1` хибне | уперед | не спрацьовує ✔ |
| знімок у відповідь на `QUERY` | `=` істинне | **рівний** | не спрацьовує ✔ |
| перезапуск після 5 анонсів | `1 <= 5` істинне | уперед | спрацьовує ✔ |
| перезапуск після **1** анонсу | `1 <= 1` істинне | уперед | **спрацьовує** (з `<` — ні) |
| перше в житті | відсікає `previousSeq != Integer.MIN_VALUE` | — | не спрацьовує ✔ |

🔑 Тому правильне формулювання ознаки — **«`seq` не просунувся, а `at` просунувся»**. «Назад» це
окремий випадок «не просунувся», і різниця між ними ховала діру.

⚖️ Урок, ширший за цей контракт: **правильний результат із правильної причини й правильний
результат випадково на дроті виглядають однаково.** На щасливому шляху (радіо жило довго,
`previousSeq` великий) гілка працює й зі строгою нерівністю — саме тому вада дожила б до тесту.

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

## How the two sides actually recognise each other

✍️ *Claude/QFRadio, 07.09.2026, from `service/WdspAudioContract.java` and measured on
192.168.1.146. Folded in here because this file is the contract and the radio keeps no copy.*

- 🔬 **Recognition travels in the extras of the query, not in the package.** The radio reads
  `versionCode` and `syncOwner` out of `AUDIO_STATE_QUERY` and requires both:
  `wdspSyncOwner = (vc >= MIN_CONTRACT && ow)`, with `MIN_CONTRACT = 11`. So it believes what wDSP
  says about itself on the wire, not what is installed.
- 🧩 **Neither side has a manifest marker, and neither looks for one.** Both trees were checked on
  07.09.2026: wDSP's manifest carries no `<meta-data>` but the FileProvider's, and nothing in
  `wdsp_app/build.gradle` beyond `versionCode`. The marker is a design that never reached the code.
- 🔬 `PackageManager.getPackageInfo` on the radio side is called in exactly one place, and only to
  **clear** the flag if wDSP was uninstalled mid-session — a guard against a ghost, not a means of
  detection.
- 📻 `wdspSyncOwner` is a field of the radio's process and dies with it. ❓ **It has no expiry**, so
  a wDSP that dies an hour after announcing leaves the radio still standing aside. Debt, and
  accepted deliberately: on Android 10 an application cannot tell whether another's service is
  alive — `getRunningAppProcesses` and `getRunningServices` have returned only the caller's own
  since API 26 — so the alternatives are a heartbeat, which is precisely the 10 Hz storm removed on
  07.09, or accepting that a dead wDSP degrades to "no synchronisation", which is the behaviour
  from before this contract rather than silence.
- 🔬 📻 The radio writes `persist.sys.qf.radio.sync_vol` on **every start of its service**
  (`PrefsStore` constructor, called unconditionally from `RadioService.onCreate:169`), confirmed on
  the wire. ⇒ wDSP's rule that an absent property means "off" costs the pair nothing: the only case
  with no property is a radio that has never once started.
- 🔴 **The default is `false` — the synchronisation is off until a person turns it on.** Changed on
  07.09.2026 by the owner's instruction ("by default the radio does not synchronise the sound"),
  radio-side commits `414b79a` and `e898485`. Everything that said otherwise was inverted: the
  settings store read `1`, and **every call of `isVolumeSyncEnabled()` — four of them as of
  07.09.2026** — treated a store that had not come up yet as "sync on", so the earliest moment of
  start-up was the boldest.
  🪤 The fourth was found only on a second pass, because it was written the other way round
  (`prefs != null && !prefs.isVolumeSyncEnabled()`) and a search for the shape of the first three
  missed it. **Grep the predicate, not the form of the condition** — a boolean test has as many
  shapes as there are ways to write it, and this one hid in the `com.qf.action.VOLUME_CHANGED`
  handler, which is the hottest path there is. ⇒ Both halves now agree in the
  same direction: absent property means off here, default means off there. Before this they were
  opposites, and the pair would have found that out in somebody's car.
  ⚠️ Testers on RC2.2 who never touched the toggle lose the synchronisation on update: they have no
  settings file, so they were running on the old default.
- 🔴 **After a restart of the radio it takes no initiative** — it waits to be asked. While wDSP was
  querying every ~103 ms under mute that hole was covered by accident; with that fault fixed on
  07.09 the hole is real, and until it is closed a restarted radio can sit in its fallback
  indefinitely, writing levels at the same time as wDSP.

### The hole after a restart — the shape agreed, and who owns which half

*Agreed 07.09.2026.* Initiative belongs to whichever application has just come up, and neither of
them reminds the other on a timer:

| half | who | what | state |
|---|---|---|---|
| the radio announces once at its own `onCreate` | radio | its ordinary `AUDIO_STATE_STABLE` — `announceIdle(-1)`: `source="idle"`, `channel=-1`, because the channel has not been taken at that moment — with `at = elapsedRealtime()` and a `seq` from a fresh counter, so 1 after a restart | ✅ done, right after `wdspContract.register()` |
| wDSP answers a new epoch with exactly one query | wDSP | in the `AUDIO_STATE_STABLE` handler: `seq` lower than the last accepted while `at` is newer is the restart signature this file already names, and it is the moment to re-send `AUDIO_STATE_QUERY` | ✅ written and building, in the handler right after the staleness check; not committed and not on a unit yet |

🪤 **The signature does not fire after a reboot of the unit, and that is fine.** `elapsedRealtime()`
restarts from zero with the machine, so `at` goes *backwards* rather than forwards and the branch
above stays quiet. Nothing is lost: after a reboot wDSP starts too, and the unconditional query in
its own `onCreate` covers the case completely. Written down so it is not rediscovered in six months
as something mysterious. (✍️ Claude/QFRadio.)

🔑 Deliberately **no new action**. The radio's first suggestion was a query of its own at start-up,
which would have meant a second stream — the thing this document rules out two sections above, and
for the same reason: `seq` orders messages only within one stream.

## 🔑 Половина розмови гірша за мовчання

✍️ *Сформульовано разом, 08.09.2026, після того як обидві сторони наступили на це за одну добу.*

Сторона, яка каже сусідові **частину** правди, шкідливіша за сторону, яка мовчить: мовчання
залишає сусіда з його власним станом, а півправда змушує його діяти за неповним знанням — і
робити те, чого він не робив би, не почувши нічого.

📻 Як це виглядало на апараті. Синк вимкнено, отже радіо не анонсить. Але його відповідь на
`AUDIO_STATE_QUERY` (`replayLast()`) воріт не мала, а поле стану лишалось на типовому значенні —
тож на **кожен** запит wDSP отримував «я нічого не граю», **навіть коли радіо грало**. Хибний факт
замість жодного.

⇒ **Усі вихідні повідомлення однієї сторони мусять стояти за одними воротами** — або говорить усе,
або мовчить усе. Проміжного стану «частина каналів увімкнена» не існує як допустимого.

🪤 І практичний висновок, який коштував двох помилок поспіль: **ворота ставити в горловині, а не в
кожного викликача.** Ворота, які треба пам'ятати в кожному місці виклику, рано чи пізно забудуть в
одному — на боці радіо їх поставили в трьох місцях із чотирьох, і забутим виявився саме той шлях,
що відповідає на запит. Тепер вони живуть у `send()`, крізь який проходять усі чотири.

📻 Перевірено 08.09.2026 на 192.168.1.146, радіо `vCode 86`, `sync_vol=false`: перезапуск
`McuService` → `asked com.kostyamat.fmradio … (vCode 12, syncOwner true)` → **у відповідь тиша**.
Контракт із вимкненим синком не починається взагалі, і це правильний результат, а не збій
доставки.

## 🔑 Стан системи називають зчитуванням, а не пам'яттю про власну дію

✍️ *Сформульовано стороною QFRadio, 08.09.2026, після того як обидві сторони порушили це двічі за
одну добу.*

> **Дія могла не долетіти, бути перезаписаною або стосуватись іншого джерела правди.**

📻 Обидва випадки за одну ніч, і жоден не був недбалістю:

- сторона QFRadio прочитала в чужому лозі слово `muted` як **дію** («я заглушив») замість присудка
  стану («читається як замкнений») — і збудувала на цьому причинно-наслідкову модель, яку вписала
  у два коміти;
- сторона wDSP написала «проп лишаю `true`», назвавши стан **за своєю дією** (`setprop true`), тоді
  як на апараті вже пів години стояло `false`: сервіс радіо стартував і `PrefsStore` переписав
  прапорець. Помилку знайшла друга сторона зчитуванням, а не міркуванням.

⇒ У цій парі це не абстракція: **прапорець синку має два джерела правди**, і кожна сторона бачить
своє. Тому «я ввімкнув» ніколи не означає «увімкнено», а означає лише «я записав у своє джерело».

🪤 Найдорожче в цьому те, що обидві помилки виглядають як упевненість. Твердження, звужене до
зчитаного, здається слабшим за впевнене — а насправді сильніше рівно тим, що його не доводиться
відкликати.

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

Both sides are implemented and on the unit. The wDSP half is committed: `2b7355c`…`634f527`
(the protocol, 26.08), `4eea344` (the two faults found only while driving, 27.08, and the commit
that carries `0.4.7.4` / `versionCode 11`), `f2a5d3a` (the `sync_vol` gate, 03.09). Joint testing,
26.08.2026:

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
- 📌 **Бік wDSP закомічено** `f2a5d3a` (03.09.2026): `McuService.PROP_VOLUME_SYNC` і ранній вихід
  на початку `carryBaseToOtherSource()`.

🔴 **Відсутність властивості означає «не синхронізувати».** Виправлено 07.09.2026; доти wDSP читав
`null` як згоду, і це було хибно саме для тих апаратів, які до домовленості не мають стосунку:
заводське радіо, чуже радіо, або версія до контракту. Там властивості немає — отже, немає й того,
хто перестав писати рівні, і перенесення бази дало б двох писарів із різними числами на апараті, де
ніхто ні про що не домовлявся. Без властивості wDSP поводиться так, як поводився до контракту.

⚠️ **Наслідок для радіо, і він обов'язковий:** увімкнений синк тепер треба **оголосити**. Радіо
мусить писати `persist.sys.qf.radio.sync_vol=true` при старті, а не лише коли користувач торкнувся
тогла — інакше після чистого встановлення властивості немає, wDSP базу не переносить, і мовчазна
відмова виглядатиме як «синк зламався».

`announceRadio` is proven **on a source transition** — the radio taking the channel as its process
started. On a **level change from the knob** it is proven via `keyevent 293/294`.

The witness for that proof is a line that is *absent* from the wDSP log: at 15:44:46 no
`asked com.kostyamat…` preceded the signal, so it was not a reply to a query, so it came from
`announceRadio`. Recorded here because the evidence lives on this side, not the radio's.
