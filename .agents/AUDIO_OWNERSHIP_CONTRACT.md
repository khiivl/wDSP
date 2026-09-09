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
називає доказ, а не намір. Стан на 08.09.2026.

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
| пара з УВІМКНЕНИМ тумблером | ✅ | ❌ | 🔴 Було «не міряно жодного разу ні з ким» — закрито 08.09.2026 з боку wDSP. 📻 Проба відбулася на 192.168.1.146 за прямим дозволом власника, радіо `vCode 90` (з дедуплікацією анонсів), wDSP `0.4.7.7 vCode 14`. Числа — в рядках нижче. Стовпчик QFRadio лишається за ними |
| крок 5 сценарію: віддача тракту — `source=idle` **рівно один** | ✅ | ⚪ | 📻 08.09 16:53:51. Слухача перевірено **до** дії: `pidof com.radiorubka.wdsp` = 31098, той самий pid стоїть у **кожному** рядку лога від 16:50:38 до 16:53:58. `16:53:51.339 AUDIO_STATE_STABLE seq=5 at=69707498 source=idle` — і більше нічого. 🔴 **Не приписувати цю одиницю дедуплікації.** Спершу сторона wDSP так і зробила («прийшли дві `VOLUME_CHANGED` з різними значеннями, а анонс один») — і це причинне пояснення без перевірки ланки: дедуп порівнює вміст, при `5` проти `9` він би їх **не** згорнув. Правильно (знайшла сторона QFRadio у своєму коді): анонс один, **бо шлях один** — `releaseAudioTract()` кличе `announceIdle(MPU)` рівно раз, а другий шлях закритий гейтом `radio_type`, тоді як джерело вже було `media_type`. Це видно в лозі wDSP, який сам же це й зафіксував: обидві `VOLUME_CHANGED` прийшли з `type=media_type`. ⇒ **на віддачі дедуплікація не перевірена взагалі** |
| крок 5: взяття каналу — `source=radio` **два замість одного** | ⚠️ | ⚪ | 📻 08.09 `16:53:57.864 seq=6 at=69714020` і `16:53:58.053 seq=7 at=69714213`. Вміст **ідентичний** — `source=radio`, рівень 9, обидва лягли як «our own echo (9), base kept at 9», — різняться лише `seq` і `at`. ⇒ друге повідомлення не несе нічого нового, що і є визначенням зайвого анонсу. Очікуване число було записане **до** проби і дорівнювало одиниці; вже не три, як уночі, але ще не один |
| 🔬 причина двох анонсів `radio` — **різний вміст**, а не вікно | ⚪ | 📻 | 🔬 знайдено стороною QFRadio у власному коді: два місця виклику беруть рівень із **різних** джерел — `confirmAudioChannel()` оголошує `sys.qf.radio.saved_vol` (тоді **5**), обробник `VOLUME_CHANGED` — значення з екстри (**9**). ⇒ вміст різний, і дедуплікація спрацювала **правильно**, не згорнувши їх; згортати не було чого. 🔴 Але це виявляє вужчу й гіршу ваду: `confirmAudioChannel` оголошує **застарілу** гучність. Наслідок для wDSP сьогодні — рівно нуль, і доведено це сильніше за «поле довідкове»: `onAudioStateStable()` бере з інтенту лише `seq`, `source`, `channel`, `at`, а екстру `volume` **не читає жодного разу**. ⚠️ Прямого числа з дроту немає і вже не буде: лог wDSP оголошену гучність не друкує, а в `dumpsys activity broadcasts` історія провернулась. Пояснення тримається на коді обох сторін, не на екстрі |
| 📻 скіс доставки broadcast між програмами = **191 мс** | 📻 | ⚪ | виміряно тією ж пробою: `QUERY` від wDSP відправлено `16:53:57.863`, радіо записало отримання `16:53:58.054`. Обидва рядки — з одного `logcat`, тобто один годинник. ⚠️ Це **окремий** факт, а не пояснення рядка вище: спершу сторона wDSP натягнула його на причину двійки (193 мс між анонсами ≈ 191 мс скосу), і помилилась — причина в коді. Скіс пояснює лише **час** другого анонсу: остання `VOLUME_CHANGED` дійшла до wDSP о `57.860`, `+190 мс` = `58.05`. 🔴 Але після правки `confirmAudioChannel` обидва анонси нестимуть однаковий вміст — і тоді все вирішить пристрій дедуплікації: **станова** (пам'ятає останній оголошений вміст) згорне другий без жодного вікна; **віконна** з `W` меншим за ~190 мс не згорне, і пачка лишиться, тільки з однакових замість різних. ❓ Питання до сторони QFRadio: станова чи віконна |
| `replayLast()` віддає знімок із **тим самим** `at` | ✅ | ⚪ | 📻 08.09 `16:53:58.057 AUDIO_STATE_STABLE seq=7 at=69714213 ignored, already at seq=7 at=69714213` — третя копія повторила `at` попередньої, за 3 мс після того, як радіо записало отримання `QUERY`. Рядок «знімок проти нового повідомлення» уперше побачив дріт, і знімок коштував нуль: дедуплікація wDSP з'їла його мовчки. ⚠️ Доказ **непрямий** — збіг `at` плюс чужий рядок про `QUERY`; прямого «`replayLast` надіслано» сторона wDSP не бачила і не видає за побачене |
| ❌ борг wDSP: два `QUERY` на одну подію | ❌ | ⚪ | 📻 08.09 16:50:38, на перевстановленні радіо: `.954 the radio has started over (seq 15 -> 1)`, `.956 asked …` — гілка епохи (`McuService:886`), `.958 asked …` — гілка «джерело змінилось» (`McuService:1038`). Дві законні гілки спрацювали на одну подію. Це не шторм: за весь цикл рівно **3** `QUERY`. Але це рівно та вада, за яку сторона wDSP рахувала сусіда напередодні, і лікується вона так само — воротами **в горловині** `askRadioForItsState()`, а не в кожного викликача. Правку не зроблено: код без слова власника не міняється |
| ❌ борг wDSP: лог не друкує **оголошену** гучність | ❌ | ⚪ | 🔬 `onAudioStateStable()` не читає екстру `volume` і, отже, ніде її не друкує. Наслідок побачили 08.09, коли сторони розійшлися в тому, **що саме було надіслано**: розсудити виявилось нічим. Це той самий недогляд, який уже одного разу закривали додаванням `at` у всі гілки — коментар у коді там каже дослівно, що єдиний свідок стику це рядок лога. Лікування: друкувати оголошену гучність поруч із прочитаною. Правку не зроблено: чекає на дозвіл власника на збірку |
| дедуплікація анонсів у QFRadio — **станова**, не віконна | ⚪ | ✅ | 🔬 умова дослівно: `if (lastAt != 0 && channel == lastChannel && volume == lastVolume && src.equals(lastSource)) return;` — часу в ній немає, `lastAt != 0` лише захищає найперше повідомлення життя. ⇒ виміряні 191/193 мс на згортання не впливають, і застереження сторони wDSP про ширину вікна **знято повністю** |
| 🔴 `sys.qf.radio.saved_vol` заморожений, поки синком володіє wDSP | ⚪ | 🔬 | знайдено стороною QFRadio у власному коді: `saved_vol` оновлює **лише** фалбек-гілка (`if (!wdspOwns)`), якою при живому сумісному wDSP не ходять. 📻 Підтверджено пасивним читанням 08.09 після проби: `saved_vol=5` при `sys.radio.vol=9` і `sys.media.vol=9`. 🔑 Клас спільний для обох сторін: **кожна читала поле, яке в новому режимі більше ніхто не пише** — у wDSP екстра `volume` не читалась із інтенту, у QFRadio `saved_vol` писався лише в мертвій гілці. Поле не повідомляє, що його покинули; воно просто віддає останнє значення |
| ❓ передбачення, записане **до** наступної проби | ⏳ | ⚪ | правка QFRadio виправляє шлях `confirmAudioChannel` (живий рівень замість `saved_vol`). Але другий шлях — обробник `VOLUME_CHANGED` — бере рівень **з екстри**, а 📻 екстра на переході джерела несе застаріле число: чотири випадки поспіль `pushed=5` при `media=9 radio=9`, кожен зі свіжим `pushed=9` через 6–20 мс (див. `platform/08-VOLUME-AND-SOURCES.md`). ⇒ **очікується 2 анонси після часткової правки** (один із 9, другий із 5 — вміст різний, станова дедуплікація правильно не згорне) **і 1 після повної**, коли обидва шляхи читатимуть одне джерело правди. Число записане заздалегідь: проба вміє провалитись |
| ❌ борг QFRadio: відправка не логується | ⚪ | ❌ | `send()` не пише нічого, тож про бік QFRadio на стику не можна дізнатись узагалі; на цьому спіткнулись двічі за добу. Окремо й важливіше: **проковтнутий дедуплікацією анонс невидимий обом сторонам** — ми не вміємо відрізнити «згорнуто правильно» від «не надіслано». Борг названий стороною QFRadio самостійно |
| 🔴 **знахідка, що потребує рішення власника**: запис застарілої гучності просто в MCU | 📻 | 🔬 | 🔬 QFRadio у власному коді: у `releaseAudioTract()` записи властивостей стоять під `if (!wdspOwns)`, а `tuner.link().setVolume(radioVol)` — **поза** умовою, тож виконується завжди; `radioVol` тут це заморожений `saved_vol`. ⇒ **кожна віддача тракту пише 5 просто в чіп, поки синком володіє wDSP.** 📻 З боку wDSP це й видно як `pushed=5` при `media=9 radio=9`. 🔴 Наслідок, якого не видно з боку радіо: **жоден механізм wDSP цього не бачить** — перенесення бази, відновлення після `resetDefValIfNeed` і база GALA працюють на властивостях і фреймворку, а запис іде повз них. І `applyVolumeDependentSettings()` рахує тонкомпенсацію та підпір саба від **прочитаних 9**. ❓ Чи стає це чутним, залежить від того, чи переписує гучність чіпа зворотне взяття каналу — з боку wDSP не встановити, читання гучності з MCU немає. ⚖️ Розділити два питання: **задум** «знижувати FM-вихід MCU при віддачі» може бути свідомою апаратною специфікою і скасовується лише власником; **джерело значення** — мертве поле — вада за будь-якої відповіді на перше |
| ❓ передбачення «2 після часткової правки» | ⚪ | ⚪ | **знято правкою до проби**, не справдилось і не провалилось: сторона QFRadio виправила шлях `VOLUME_CHANGED` (тепер обидва читають `sys.radio.vol`) раніше, ніж проба відбулася. Так і фіксуємо, щоб облік не вигадував результату, якого не було. Чинним лишається друге число: **1 після повної правки**. ⚠️ І нове застереження: обидва шляхи тепер читають **властивість**, а не гучність чіпа — якщо ті розійдуться, обидва оголосять однакове й однаково хибне, і дедуплікація буде задоволена. Узгодженість шляхів між собою і правдивість — різні питання |
| 🔬 **одна ручка**: обидва писарі виходять на `RPC_SetVolume` | ✅ | 🔬 | доведено з обох боків. QFRadio: `tuner.link().setVolume()` → `svc.RPC_SetVolume(...)`, і таких записів у сервісі рівно два — на **взятті** (рядок 1062) і на **віддачі** (3687) тракту, обидва **поза** `if (!wdspOwns)`, обидва беруть заморожений `saved_vol`. wDSP: декомпільований `VolumeState.setVolumeVal(int)` (переписаний у javadoc `VolumeHelper`) пише `propSave` **завжди**, а `RPC_SetVolume` — лише коли джерело живе. ⇒ **запис через фреймворк рухає властивість і чіп разом; прямий `RPC_SetVolume` рухає лише чіп і не лишає сліду у властивостях.** Ось повний механізм розбіжності й причина, чому жоден читач властивостей її не бачить |
| 🔒 **wDSP не синхронізується з радіо, старішим за `versionCode 86`** | ✅ | ⚪ | 🔴 запобіжник, доданий за наказом власника 09.09.2026 (`wDSP 0.4.8 / vCode 15`, коміт `e51d940`). Причина: у радіо знайдено вади поза контрактом, і сумісна версія затримається приблизно на тиждень, а мод wDSP уже в людей. `MIN_RADIO_VERSION_CODE = 86` — перша версія, яка **виміряно** мовчала при вимкненому синку (08.09 01:15, рядок «контракт МОВЧИТЬ» вище). Версія читається з `PackageManager`, **не** з того, що надсилає радіо: радіо, застаре для контракту, застаре й для того, щоб описувати себе, а версія з широкомовки відома лише після того, як воно заговорить. Перечитується раз на хвилину, бо радіо можуть замінити за живого сервісу. ⚙️ Поведінка при старому радіо: (1) база **не** переноситься між джерелами; (2) радіо отримує `syncOwner=false` — і його власна фалбек-гілка знову пише рівні сама, тобто повертається поведінка, з якою його ж збірку й випробували. 📌 Поріг підняти, коли вийде виправлена версія радіо: це одна константа і єдине місце, де рішення ухвалюється |
| 🎧 межа симптому: розбіжність лікується першим же записом через фреймворк | 📻 | ⚪ | наслідок рядка вище: будь-який запис у **живе** джерело через фреймворк переписує чіп правильним числом. ⇒ очікуваний вигляд для власника — не «радіо назавжди тихіше», а **«перемкнув на плеєр і назад — тихіше, ніж показує шкала; один крок ручкою — і гучність стрибнула вгору»**. ⚠️ На стоянці саме не полікується: гілка GALA при нульовому прирості навмисно не пише, а йде слідом. У русі полагодить перший крок GALA. 📌 Проба тиха й коротка: віддати тракт, повернути, **нічого не крутити**, слухати; тоді один крок ручкою. Стелю гучності 5 не порушує |
| ⚖️ третій варіант рішення для власника | ⚪ | ⚪ | окрім «писати в MCU при переході тракту» / «не писати» є третій: писати **через фреймворк** (`VolumeState.setVolumeVal` на живому джерелі) замість прямого `RPC_SetVolume`. Задум зберігається повністю, значення береться з живого стану, а чіп із властивістю не розходяться **за побудовою**, а не завдяки чиїйсь пильності |
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

## 🔴 Пачка `VOLUME_CHANGED` оголошувала ПРОМІЖНІ значення

📻 *Виміряно 08.09.2026, крок 5. Вада стара — не з правок цієї доби.*

Правило в цьому файлі стоїть із 26.08: анонс кличеться **після останнього запису** синхронізації,
не перед, «інакше wDSP перебазується на проміжне значення й гонка лишиться».

Пачка анонсів — це буквально порушення того правила зсередини. Платформа шле `VOLUME_CHANGED`
пачкою, і кожна подія давала окремий анонс. У лозі wDSP:

```
06:04:56.758  seq=3 at=30772907 source=radio channel=2     ← confirmAudioChannel, взяття каналу
06:04:56.979  seq=4 at=30773138 source=radio channel=2     ← обробник VOLUME_CHANGED
06:04:56.982  seq=5 at=30773142 source=radio channel=2     ← він же, +3 мс
```

⇒ Три анонси за 224 мс. Прапорець `wdspToldWeArePlaying` тут ні до чого — він захищає шлях
**взяття каналу** і своє зробив (`seq=3` рівно один). Дебаунсу на шляху **гучності** не було
взагалі.

🪤 Чому це дожило непоміченим: під мьютом wDSP бази не чіпає, тож три перебазування поспіль ні на
що не впливають. На живому звуці той самий обмін означав би три перебазування за чверть секунди —
рівно та гонка, заради усунення якої контракт і писався.

✅ **Лікування — дедуплікація в горловині, не таймер** (✍️ Claude/QFRadio): анонс, що несе ті самі
`channel`, `volume` і `source`, що й попередній, не відправляється. Обґрунтування, яке варто
зберегти: повідомлення, яке повторює попереднє, **не несе інформації**, і його відсутність нічого
не приховує; таймер натомість додав би затримку в осмислений випадок. Побічний зиск — `seq` стає
чеснішим: він і задуманий як лічильник **реальних змін стану**, а не подій платформи.

⇒ Пачка з однаковими значеннями згортається в один анонс. Пачка, у якій рівень справді повзе
(5→4→3), лишається трьома — і це правильно, бо стан справді змінився тричі.

### 🔑 Як відрізнити нове повідомлення від повтору у відповідь на запит

🔬 `replayLast()` не чіпає ані `seq`, ані `at`. Отже:

| що прийшло | ознака |
|---|---|
| **знімок** у відповідь на `AUDIO_STATE_QUERY` | `seq` **і** `at` збігаються з попереднім |
| **нове** повідомлення | `at` інший, навіть якщо `seq` збігається |

📻 Саме за цією ознакою 08.09 вдалося сказати, що `seq=4` і `seq=5` — окремі повідомлення, а не
повтор: у них були різні `at`. Ознака надійна й не потребує здогадок про намір відправника.

## 🔴 Стартовий анонс мусить бути ОБЧИСЛЕНИЙ, а не зашитий

📻 *Виміряно 08.09.2026, 01:35, і це третій прояв одного кореня за добу.*

Домовленість від 07.09 казала: радіо при `onCreate` шле свій звичайний `AUDIO_STATE_STABLE`
**зі своїм станом** — `source="radio"`, якщо канал уже взяло, або `"idle"`, якщо ні. Реалізовано
було половину: `announceIdle(-1)` **зашитий намертво**, бо в мить написання він був правдою.

Що з цього вийшло на дроті:

```
радіо   01:35:00.390  confirmAudioChannel: channel=2 (FM)   ← канал уже взято
        …але wdspContract ще null (створюється на 28 рядків нижче),
          і власний захист != null мовчки з'їдає announceRadio
радіо   01:35:00.4xx  announceIdle(-1)                       ← уже ГРАЮЧИ на каналі 2
wDSP    01:35:00.410  AUDIO_STATE_STABLE seq=1 source=idle channel=-1 -> base=5
```

⇒ За весь день `source=radio` — **нуль разів**, при тому що радіо грало. Це не «анонсу не було»:
**останнє, що сусід чує, — неправда**, і створює її порядок ініціалізації самого відправника.

Два правила, обидва куплені цим виміром:

1. **Стан в анонсі обчислюється в момент відправки**, а не береться з типового значення поля.
   Зашите значення — це стан, названий за пам'яттю про звичайний випадок.
2. **Анонс, надісланий до того, як існує транспорт, невідрізненний від ненадісланого.** Захист
   `!= null` рятує від падіння і **мовчки втрачає повідомлення** — тож або транспорт створюється
   раніше за перший перехід, або перехід, що стався раніше, переоголошується після створення.

🪤 Обережно з джерелом для обчислення: за `platform/08-VOLUME-AND-SOURCES.md` **`sys.qf.sound.channel`
= 2 є доказом, а 4 не доказ нічого** — воно чесне лише на апараті з політиками BitPerfect і гуляє
на заводському. Опиратись варто на власне знання «я взяв канал» плюс `sys.qf.radio.status`, а не
на саму лише властивість.

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
