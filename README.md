# wDSP "Bulldozer" mod by kostyamat — 1.1

**[🇺🇦 Українська](#ua) · [🇬🇧 English](#en)**

<a id="ua"></a>

## 🇺🇦 Українська

Еквалайзер, вимір салону й візуалізатор для Android-магнітол **QF / K706** зі звуковим процесором
ROHM **BU32107**. Програма керує процесором через MCU магнітоли.

Ця гілка (`kostyfmat_mod`) — **модифікація Костянтина Матвієвського** програми
[wDSP Володимира Чебаненка (khiivl)](https://github.com/khiivl/wDSP). У ній уся робота автора до
його версії 1.0.2; програма автора та її опис — у [гілці `master`](https://github.com/khiivl/wDSP/tree/master).

### Інструкції

Інструкція до кожної функції, дев'ятьма мовами. Завантажте файл і відкрийте в будь-якому браузері,
інтернет не потрібен. Ті самі файли є в архіві релізу.

| | | |
|---|---|---|
| [Українська](docs/manual_uk.html) | [English](docs/manual_en.html) | [Español](docs/manual_es.html) |
| [Deutsch](docs/manual_de.html) | [Français](docs/manual_fr.html) | [Italiano](docs/manual_it.html) |
| [Português](docs/manual_pt.html) | [Polski](docs/manual_pl.html) | [Русский](docs/manual_ru.html) |

Карта жестів заставки: [docs/screensaver_map.svg](docs/screensaver_map.svg).

### Що нового в 1.1

| | | |
|---|---|---|
| [Українська](docs/release_notes/RELEASE_NOTES_1.1_UA.md) | [English](docs/release_notes/RELEASE_NOTES_1.1.md) | [Español](docs/release_notes/RELEASE_NOTES_1.1_ES.md) |
| [Deutsch](docs/release_notes/RELEASE_NOTES_1.1_DE.md) | [Français](docs/release_notes/RELEASE_NOTES_1.1_FR.md) | [Italiano](docs/release_notes/RELEASE_NOTES_1.1_IT.md) |
| [Português](docs/release_notes/RELEASE_NOTES_1.1_PT.md) | [Polski](docs/release_notes/RELEASE_NOTES_1.1_PL.md) | [Русский](docs/release_notes/RELEASE_NOTES_1.1_RU.md) |

### Чим мод відрізняється від оригіналу

- **Він вимірює машину.** Оригінал налаштовують на слух: еквалайзер, кросовери й затримки ви
  виставляєте самі, а його спектр показує музику, що грає. Мод ще й програє тестові свіпи через
  динаміки, записує їх мікрофоном магнітоли і з почутого будує пресет, затримки та перелік проблем.
- **Його спектр уміє слухати салон** через мікрофон, а не лише показувати сигнал.
- **Він дбає про звук магнітоли не тільки еквалайзером**: гучність кожного джерела, дзвінки
  (мікрофон звільняється, вмикається пресет дзвінка), відновлення плеєра після перезавантаження
  чи сну.
- **Візуалізатор у статус-барі й повноекранна заставка** — в оригіналі їх немає взагалі.
- **Обидва інтерфейси.** Інтерфейс автора лишився як «Класичний», поруч із «Модерн» мода.
- Root необов'язковий; з ним мод отримує мікрофон раніше за інші програми.

### Візуалізації та заставка

Цього в оригіналі немає.

- **Візуалізатор статус-бара** — міні-спектр просто в смужці Android, над будь-якою програмою.
  Ширина, положення, висота, кольори.
- **Шість стилів** для віджета й заставки, кожному свій: Класика, Outrun, Градієнт, Центрований,
  VU-метр, Осцилограф. Плаваючі пікові мітки, симетричний спектр (бас у центрі), палітри FastLED
  (FireLamp), післясвітіння люмінофору для осцилографа.
- **Заставка** — повноекранна візуалізація, коли магнітолу лишили в спокої; будь-який дотик її
  ховає. Затримка появи, яскравість і затемнення тла окремо вдень і вночі, смуга треку з
  обкладинкою, список програм, поверх яких вона не з'являється. Навігатор, вибраний у налаштуваннях
  магнітоли, вона не закриває ніколи. Перемикач «Накривати статус-бар».
- **Жести заставки**: зони попереднього / наступного треку й паузи; дотик до обкладинки відкриває
  той плеєр чи радіо, що справді грає; утримання кнопки стилю відкриває еквалайзер; свайпами
  змінюються гучність, яскравість, затемнення, ширина й висота візуалізації. Усе — на
  [карті жестів](docs/screensaver_map.svg).
- Працює й на магнітолах з увімкненим «плаваючим відео» чи «плаваючою навігацією», і на
  вертикальних екранах.

### Відновлення плеєра

«Відновлювати плеєр після перезавантаження» і «Відновлювати плеєр після сну»: плеєр, що грав, сам
продовжує, і його вікно не вискакує поверх навігатора. Пауза, яку ви поставили самі,
зберігається. Для сну магнітола має дозволяти wDSP жити: кнопка «Дозволити wDSP жити під час сну»
відкриває її список.

### Що ще додає мод

- **Звіт виміру салону**, що починається з проблем людськими словами (слабкий динамік, твітер у
  неправильній полярності, переплутані канали, саб грає проти дверей) і лише потім виправляє те,
  що здатен еквалайзер. Контрольний свіп після застосування показує, чи спрацювало.
- **Калібрування вбудованого мікрофона.**
- **Спектр** у трьох режимах: Вимкнено, Музика (що робить з музикою пресет), Мікрофон (як
  насправді звучить салон).
- **Тонкомпенсація**, що йде за реальними кроками гучності магнітоли, виміряними програмою.
- **Діагностика**: системний звіт, топологія екрана, архів виміру.
- **30 мов інтерфейсу.**

### Встановлення

1. Встановіть APK з архіву релізу. Один раз відкрийте wDSP, дайте дозволи, які вона просить, і
   додайте її в список сну магнітоли (програма відкриває його сама: Налаштування → «Дозволити
   wDSP жити під час сну»).
2. Мод підписано власним ключем, а не ключем автора. Android не ставить одну збірку поверх іншої:
   щоб перейти зі збірки автора на мод (чи назад), збережіть пресети резервною копією програми,
   видаліть іншу збірку, потім встановіть.
3. Заводську програму DSP радимо вимкнути, бо вона пише ті самі регістри процесора:
   `adb shell pm disable com.qf.soundeffect` (назад: `adb shell pm enable com.qf.soundeffect`).
4. Root (необов'язковий) дає мікрофону пріоритет над іншими програмами. З root і модулем Magisk
   [PMPatch3](https://github.com/vova7878-modules/PMPatch/releases) проксі wDSP (`wdsp_proxy`)
   можна встановити як оновлення заводської програми DSP — тоді кнопка DSP у швидких
   налаштуваннях відкриває wDSP.

### Звіти

Виміри (архів, збережений після виміру салону, плюс системний звіт), враження від пресетів і баги
цього мода: [обговорення мода](https://t.me/kostyamat_dev/92). Обидва файли зберігаються в
`Download/wDSP` на магнітолі.

Оригінальний проєкт, його автор і користувачі: [Telegram-група wDSP](https://t.me/wDSPapp),
[GitHub](https://github.com/khiivl/wDSP).

### Збирання

```bash
./gradlew :wdsp_app:assembleRelease
```

Gradle і JDK підтягує wrapper; див. [.agents/BUILDING.md](.agents/BUILDING.md). Java, `targetSdk 29`
навмисно (фреймворк QF поводиться як Android 10); протокол MCU відновлено реверсом, до нього
звертаються через службу фреймворку рефлексією.

### Підтримка

Ті самі два блоки, що вгорі Налаштувань програми, у тому самому порядку: спершу оригінальний
проєкт, потім цей мод.

**wDSP — оригінальний проєкт.** Ця збірка — мод на його основі. Основа, і подяка за неї, — автора.

- GitHub — обговорення wDSP: <https://github.com/khiivl/wDSP>
- Telegram-група: <https://t.me/wDSPapp>
- Buy me a coffee: <https://buymeacoffee.com/radiorubka>
- PayPal: <https://paypal.me/wDSPApp>

**wDSP kostyamat mod.** Ця збірка. Вона безкоштовна: без ліцензій і розблокувань — лише
добровільна подяка.

- Обговорення мода: <https://t.me/kostyamat_dev/92>
- Buy me a coffee: <https://t.me/kostyamat_dev/10>
- PayPal: <https://www.paypal.com/paypalme/kostyamat>
- Revolut: <https://revolut.me/kostyamat>
- Крипто — TRC-20 (Tron): `TCSvCEyX1Zxb9E97oTpxHsBCLiDZLPTvzk`
- Крипто — TON (гаманець Telegram): `UQCqracCOo_DEwVWQjPVYkKak-gSZx1TXQOxICi_9frpdr5j`

### Подяки й ліцензія

**Володимиру Чебаненку (khiivl)**, автору wDSP: за програму, на якій побудовано мод, Класичний
інтерфейс, версію 1.0 і його знання звуку. Тестерам, які надсилають виміри й звіти.

Програма під ліцензією **GPLv3**: хто її покращує й поширює, поширює і код. Права на ліцензію
належать Володимиру Чебаненку, творцю й супровіднику wDSP.

----------------------------------------------------------------------------------------------------

<a id="en"></a>

## 🇬🇧 English

An equaliser, cabin-measurement and visualiser app for **QF / K706** Android head units with the
ROHM **BU32107** sound processor. It drives the processor through the unit's MCU.

This branch (`kostyfmat_mod`) is **Kostiantyn Matviyevskyy's modification** of
[wDSP by Volodymyr Chebanenko (khiivl)](https://github.com/khiivl/wDSP). It contains all of the
author's work up to his version 1.0.2 ("Bulldozer"); the author's own app and description are on
the [`master` branch](https://github.com/khiivl/wDSP/tree/master).

### Manuals

A manual for every function, in nine languages. Download the file and open it in any browser; no
internet is needed. The same files are in the release archive.

| | | |
|---|---|---|
| [English](docs/manual_en.html) | [Українська](docs/manual_uk.html) | [Español](docs/manual_es.html) |
| [Deutsch](docs/manual_de.html) | [Français](docs/manual_fr.html) | [Italiano](docs/manual_it.html) |
| [Português](docs/manual_pt.html) | [Polski](docs/manual_pl.html) | [Русский](docs/manual_ru.html) |

Screensaver gesture map: [docs/screensaver_map.svg](docs/screensaver_map.svg).

### What's new in 1.1

| | | |
|---|---|---|
| [English](docs/release_notes/RELEASE_NOTES_1.1.md) | [Українська](docs/release_notes/RELEASE_NOTES_1.1_UA.md) | [Español](docs/release_notes/RELEASE_NOTES_1.1_ES.md) |
| [Deutsch](docs/release_notes/RELEASE_NOTES_1.1_DE.md) | [Français](docs/release_notes/RELEASE_NOTES_1.1_FR.md) | [Italiano](docs/release_notes/RELEASE_NOTES_1.1_IT.md) |
| [Português](docs/release_notes/RELEASE_NOTES_1.1_PT.md) | [Polski](docs/release_notes/RELEASE_NOTES_1.1_PL.md) | [Русский](docs/release_notes/RELEASE_NOTES_1.1_RU.md) |

### How the mod differs from the original

- **It measures the car.** The original is tuned by ear: you set the equaliser, crossovers and
  delays yourself, and its spectrum shows the music being played. The mod also plays test sweeps
  through the speakers, records them with the unit's microphone, and builds a preset, the delays
  and a list of problems from what it heard.
- **Its spectrum can listen to the cabin**, through the microphone, not only show the signal.
- **It looks after the unit's sound beyond the equaliser**: the volume of each source, calls (the
  microphone is released, the call preset applied), resuming the player after a restart or sleep.
- **A status bar visualiser and a full-screen screensaver** — the original has neither.
- **Both interfaces.** The author's interface is kept as "Classic", next to the mod's "Modern".
- Root is optional; with it the mod gets the microphone before other apps.

### Visualisations and screensaver

None of this is in the original.

- **Status Bar Visualizer** — a mini spectrum right in the Android status bar, over any app.
  Width, position, height, colours.
- **Six styles**, chosen separately for the widget and the screensaver: Classic, Outrun, Gradient,
  Center, VU-Meter, Oscilloscope. Floating Peak Caps, Symmetrical Spectrum (bass in the centre),
  FastLED Palettes (FireLamp), Phosphor Afterglow for the oscilloscope.
- **Screensaver** — a full-screen visualiser once the head unit has been left alone; any touch
  puts it away. Delay before it appears, brightness and backdrop separately for day and night, a
  track strip with the cover, a list of apps it never appears over. It never covers the navigator
  chosen in the head unit's settings. "Cover the status bar" switch.
- **Screensaver gestures**: previous / next track and pause zones; a tap on the cover opens the
  player or radio that is actually playing; holding the style button opens the equaliser; swipes
  change the volume, brightness, backdrop, visualiser width and height. All of it is on the
  [gesture map](docs/screensaver_map.svg).
- Works on units with "floating video" or "floating navigation" enabled, and on vertical screens.

### Player resume

"Resume the player after a restart" and "Resume the player after sleep": the player that was
playing comes back on its own, without its window jumping on top of the navigator. A pause you
made yourself is kept. For sleep, the head unit has to let wDSP stay alive: the "Let wDSP stay
alive through sleep" button opens its list.

### What else the mod adds

- **Cabin measurement report** that starts with problems in plain words (a weak speaker, a
  tweeter in the wrong polarity, swapped channels, a subwoofer playing against the doors) and
  only then corrects what the equaliser can. A control sweep after applying shows whether it
  worked.
- **Microphone calibration** for the built-in microphone.
- **Spectrum** in three modes: Off, Music (what the preset makes of the music), Microphone (what
  the cabin actually sounds like).
- **Loudness** that follows the real volume steps of the unit, measured by the app.
- **Diagnostics**: system report, screen topology, measurement archive.
- **30 interface languages.**

### Installing

1. Install the APK from the release archive. Open wDSP once, grant the permissions it asks for, and
   add it to the unit's sleep whitelist (the app opens it: Settings → "Let wDSP stay alive through
   sleep").
2. The mod is signed with its own key, not the author's. Android does not install one over the
   other: to switch from the author's build to the mod (or back), save your presets with the
   app's backup, uninstall the other build, then install.
3. Disabling the stock DSP app is recommended, since it writes the same processor registers:
   `adb shell pm disable com.qf.soundeffect` (back: `adb shell pm enable com.qf.soundeffect`).
4. Root (optional) gives the microphone priority over other apps. With root and the
   [PMPatch3](https://github.com/vova7878-modules/PMPatch/releases) Magisk module, the wDSP proxy
   (`wdsp_proxy`) can be installed as an update of the stock DSP app, so the quick-settings DSP
   button opens wDSP.

### Reports

Measurements (the archive saved after a cabin measurement plus the system report), impressions of
the presets, and bugs of this mod: [discussion of the mod](https://t.me/kostyamat_dev/92). Both
files are saved to `Download/wDSP` on the unit.

The original project, its author and its users: [wDSP Telegram group](https://t.me/wDSPapp),
[GitHub](https://github.com/khiivl/wDSP).

### Building

```bash
./gradlew :wdsp_app:assembleRelease
```

Gradle and the JDK are provisioned by the wrapper; see [.agents/BUILDING.md](.agents/BUILDING.md).
Java, `targetSdk 29` on purpose (the QF framework behaves as Android 10); the MCU protocol was
reverse-engineered and is reached through the framework service by reflection.

### Support

The same two blocks as at the top of the app's Settings, in the same order: the original project
first, then this mod.

**wDSP — the original project.** This build is a mod based on it. The base, and the thanks for it,
are the author's.

- GitHub — wDSP discussion: <https://github.com/khiivl/wDSP>
- Telegram group: <https://t.me/wDSPapp>
- Buy me a coffee: <https://buymeacoffee.com/radiorubka>
- PayPal: <https://paypal.me/wDSPApp>

**wDSP kostyamat mod.** This build. It is free: no licences, no unlocks — only voluntary thanks.

- Discussion of the mod: <https://t.me/kostyamat_dev/92>
- Buy me a coffee: <https://t.me/kostyamat_dev/10>
- PayPal: <https://www.paypal.com/paypalme/kostyamat>
- Revolut: <https://revolut.me/kostyamat>
- Crypto — TRC-20 (Tron): `TCSvCEyX1Zxb9E97oTpxHsBCLiDZLPTvzk`
- Crypto — TON (Telegram wallet): `UQCqracCOo_DEwVWQjPVYkKak-gSZx1TXQOxICi_9frpdr5j`

### Credits and license

To **Volodymyr Chebanenko (khiivl)**, the author of wDSP: for the application this mod is built on,
the Classic interface, version 1.0, and his knowledge of sound. To the testers who send
measurements and reports.

The app is licensed under **GPLv3**: whoever improves it and shares it shares the source too.
License rights belong to Volodymyr Chebanenko, the creator and maintainer of wDSP.
