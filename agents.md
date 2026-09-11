# wDSP Architecture & Developer Knowledge Base (kostyamat mod)

## 📌 Project Overview
- **Project**: wDSP (DSP / Sound Control for QF & K706 Head Units)
- **Target Platform**: UIS7862 / ums512 Android 10 (API 29) with QF framework
- **Repository**: [wDSP](https://github.com/khiivl/wDSP)
- **Branch**: `kostyfmat_mod`
- **Mod Version**: `0.4.2_kostyamat_mod`

---

## 📻 Canonical Radio Project Location
- **Офіційний канонічний проєкт радіо (`kostyamat_fmradio`)**:
  - Розташований **ВИКЛЮЧНО ТА ЛИШЕ ТУТ**: `C:\Users\kosty\AndroidStudioProjects\kostyamat_fmradio`
  - Будь-які інші каталоги на диску (наприклад, `D:\qf_fmradio` чи архіви) не є робочими проєктами.
  - Для перевірки стилю UI, ресурсів, розкладки, динамічної розмітки чи міжпрограмних контрактів — дивитися суворо на вказаний канонічний шлях.
  - ✍️ Зафіксовано Antigravity (Gemini) за наказом Костянтина — 08.09.2026 08:30.

---

## 🔴 Platform knowledge — read this before changing anything that makes sound

This file describes **the app**. Everything about **the machine underneath** — and that is where
most of the surprises live — is in [`.agents/platform/`](.agents/platform/INDEX.md), eight files,
every claim carrying a provenance mark (🔬 read in firmware or decompiled code, 📻 measured on the
wire, 🧩 inferred, ❓ unverified). If you add to them, mark what you add.

Start at [`.agents/platform/INDEX.md`](.agents/platform/INDEX.md) — it opens with the things most
likely to cost a day. The four that catch people hardest:

- **`Visualizer(0)` measures silence.** Media goes to the *fast* output; AOSP pins the effect to
  *primary*. Attach to the track's session. (`05-AUDIO-PATH.md`)
- **Volume here is per source, not per Android stream**, and the live values are non-persistent
  properties. An unset one reads back as a factory default — which is the entire mechanism behind
  "the volume reset itself". (`08-VOLUME-AND-SOURCES.md`)
- **Some units carry a second DSP (AK7738/AK7604) and some do not**, and the platform decides by
  the *second character of the MCU code*. That one character separates two hardware classes; do not
  promise behaviour across it. (`08-VOLUME-AND-SOURCES.md`)
- **R8 silently deletes hidden-API calls.** Debug works, signed release does nothing.
  (`01-SYSTEM.md`, and `.agents/BUILDING.md`)

---

## 🛠️ Key Architectural Components

### 1. MCU & DSP Communication Bridge (`McuService.java`)
- Communicates directly with the system `framework.jar` and `IMcuManager` using Java reflection (no Root required).
- Sends raw hardware payloads via `RPC_SendMcuMsgData(byte msgId, byte[] data, int len)`:
  - **EQ (16 Bands)**: Command `0x80`, payload containing 8 packed bytes (2 bands per byte, 4-bit nibbles for gains 0..12 corresponding to -12dB..+12dB in 2dB steps) + 2 Q-factor bytes (2.2 vs 4.7).
  - **Subwoofer Control**: Command `0x8B`, payload `(cachedSubFreq << 4) | (finalGainIdx & 0x0F)`.
    - Frequencies: `25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250 Hz`.
    - Gain: `0..12` (+0dB .. +12dB).
    - ISO 226 Fletcher-Munson dynamic compensation at low volume levels.
  - **Bass Boost & High-Pass Filter**: Command `0x88` for front and rear channels.
  - **Fader & Balance & Loudness**: Command `0x82` (12 steps left/right, 12 steps front/rear). Dynamic Day/Night adaptive car graphic (`ic_car_cabriolet_day.xml` pearl platinum / `ic_car_cabriolet_night.xml` dark titanium graphite).
  - **Delays (Time Alignment)**: 
    - Positioning: Command `0x84` (0..5.0 ms with 0.5 ms step for FL, FR, RL, RR, Sub).
    - Surround / Haas effect: Command `0x85` (0..10 ms delay and RSSE surround widening).
  - **Power Amp Volume**: Sub-ID `2` via MCU message `(byte) 24`.

---

## 🌈 Visualizers & Physical Optical Frequency Spectrum

### Physical Optical Spectrum Mapping (Physics Standard: 700 nm -> 390 nm)
Both the **Equalizer Visualizer** (`SpectrumAnalyzerView.java`), the **Fletcher-Munson Curve** (`FmVisualizerView.java`), and the **Status Bar Visualizer** (`StatusBarVisualizerView.java`) follow the canonical physical dispersion of visible light:
- **Band 0 (20 Hz)**: `0xFFD50000` (700 nm - Deep Red)
- **Band 1 (31.5 Hz)**: `0xFFFF1744` (680 nm - Bright Red)
- **Band 2 (50 Hz)**: `0xFFFF3D00` (650 nm - Red-Orange)
- **Band 3 (80 Hz)**: `0xFFFF6D00` (620 nm - Orange)
- **Band 4 (125 Hz)**: `0xFFFF9100` (600 nm - Amber-Orange)
- **Band 5 (200 Hz)**: `0xFFFFC400` (585 nm - Amber-Yellow)
- **Band 6 (315 Hz)**: `0xFFFFEA00` (570 nm - Yellow)
- **Band 7 (500 Hz)**: `0xFFAEEA00` (550 nm - Lime Green)
- **Band 8 (800 Hz)**: `0xFF00E676` (530 nm - Pure Green)
- **Band 9 (1.25 kHz)**: `0xFF00BFA5` (510 nm - Teal / Spring Green)
- **Band 10 (2 kHz)**: `0xFF00E5FF` (490 nm - Pure Cyan)
- **Band 11 (3.15 kHz)**: `0xFF00B0FF` (475 nm - Sky Blue)
- **Band 12 (5 kHz)**: `0xFF2979FF` (460 nm - Pure Blue)
- **Band 13 (8 kHz)**: `0xFF3D5AFE` (440 nm - Deep Blue / Indigo)
- **Band 14 (12.5 kHz)**: `0xFF651FFF` (420 nm - Electric Violet)
- **Band 15 (20 kHz)**: `0xFF6200EA` (390 nm - Pure Deep Violet)

---

## 🎨 Theme Engine & Customization (`ThemeManager.java`)
- **Theme Modes**: Day, Night, Auto (follows system/illumination).
- **Custom Color Picker**: 4 circular Hue Wheels (`HueWheelView`) with brightness sliders:
  1. Accent color
  2. Primary text color
  3. Secondary text / labels color
  4. Text on accent color
- **Wallpapers**:
  - Built-in dynamic theme wallpaper
  - Custom photo wallpaper via SAF (`OpenDocument` with persistable URI permission)
  - Solid color background generator with real-time HUE and brightness adjustments
- **Dynamic Theme Inheritance**:
  - **Bottom Navigation Bar**: Icons and text dynamically follow active accent / inactive secondary text; capsule active indicator takes `ColorUtils.setAlphaComponent(accent, 40)`.
  - **Dropdown Spinners**: All 4 `TextInputLayout` capsules (`layout_spinner_presets`, `layout_spinner_sub_freq`, `layout_spinner_bass_freq_front`, `layout_spinner_bass_freq_rear`) inherit dynamic `accent` border/arrow/text and `secondaryText` hint.
  - **Labels & Headers**: All section headers inherit `primaryText`, field labels inherit `secondaryText`, and live dB/frequency/parameter values inherit `accent`.
- **TouchGlow Effect**: Interactive ripple and button flash on all interactive controls.

---

## 🎛️ Navigation & Settings Accordion (`SettingsAccordion.java` & `SettingsActivity.java`)
- **Unified Bottom Navigation**: `BottomNavigationView` is shared across `MainActivity` and `SettingsActivity`. Selecting a tab in Settings smoothly transitions to `MainActivity` with the chosen tab open.
- **Clean Header**: Removed legacy back button `←` in favor of full bottom navigation continuity and centered header.
- **Uniform Slider Grid**: All sliders in `SettingsActivity` share a strict horizontal grid layout:
  - Left Label: `160dp`
  - SeekBar: `0dp` (weight 1)
  - Right Value Label: `48dp` (end-aligned)
- **Categorized Accordion Cards**:
  1. **Оформлення та Теми** (Theme Mode, 4 Color Wheels, Wallpaper)
  2. **Візуалізатор статус-бара** (Enable switch, Width %, Center Position %, Hue shift, Themes: Spectrum, Fire, Neon, EQ Groups, Monochrome White/Black)
  3. **Візуалізатор еквалайзера** (Enable switch, Color Mode: Frequency-Color Spectrum vs Solid Accent)
  4. **Дозволи та Система** (Battery Optimization, Audio Record permission, GPS Location, App Details, Full Backup & Restore)

---

## 💾 Full Backup & Restore & Preset Persistence

### 1. Full Settings Backup & Restore (JSON)
- Exports and imports both:
  - `com.radiorubka.wdsp_preferences` (theme, colors, wallpapers, visualizers)
  - `EqPresets` (all EQ presets, band gains, Q-factors, sub gain/frequency, fader/balance, delays, GALA settings, player map)
- Automatically notifies `McuService` and hot-reloads the UI upon restore.

### 2. Preset Export & Import Fixes (`MainActivity.java`)
- **Prefix Normalization**: Automatically strips old preset names from JSON keys and remaps them to the target preset name upon import.
- **Pre-export Flush**: Automatically calls `autoSaveCurrent()` and `savePreset()` before writing to file to ensure the latest UI changes are captured.
- **Robust Parsers**:
  - `resolveSubFreqIndex()`: Regex-based digit extractor to safely resolve frequencies across all locales.
  - `parsePowerDb()`: Safe integer parser handling signed labels (`+`, `-`) and whitespace without throwing exceptions.
  - `loadPreset()`: Explicitly updates `Globals.currentSubFreqHz` to ensure immediate DSP and FM compensation synchronization.

---

## 🌍 Localization (30 Locales)
Fully localized in 30 languages with zero abbreviations in headers/labels:
- Ukrainian (`values-uk`), English (`values`), Polish (`values-pl`), German (`values-de`), French (`values-fr`), Spanish (`values-es`), Italian (`values-it`), Portuguese (`values-pt`, `values-pt-rBR`), Czech (`values-cs`), Slovak (`values-sk`), Hungarian (`values-hu`), Romanian (`values-ro`), Bulgarian (`values-bg`), Croatian (`values-hr`), Serbian (`values-sr`), Slovenian (`values-sl`), Turkish (`values-tr`), Greek (`values-el`), Dutch (`values-nl`), Danish (`values-da`), Swedish (`values-sv`), Norwegian (`values-nb`), Finnish (`values-fi`), Estonian (`values-et`), Latvian (`values-lv`), Lithuanian (`values-lt`), Russian (`values-ru`, `values-ru-rUA`).

---

## 🔮 Floating Glass Dock & Capsule UIX Architecture (🔬 / 📻 08.09.2026 23:25) *(✍️ Antigravity)*

### 1. Проблема геометрії доку та асиметрії країв
- 🔬 **Виявлена першопричина**:
  - У `FrostedGlassDrawable.java` тіньові проходи були зсунуті вправо (`mRectF.left` з нульовим зсувом та `mRectF.right + 1.2f * density`), що створювало несиметричний темний ореол на правому кінці доку.
  - Вертикальні відступи підкладки тіні складали `padYTop = 1.0f * density` та `padYBottom = 3.5f * density`, через що внутрішній контент був зміщений вниз, порушуючи концентричність.
  - Внутрішня активна кнопка в `SegmentedPillNavView.java` малювалася плоским `GradientDrawable` з радіусом `18dp` (висота кнопки 54dp, напівкругла капсула вимагає `25-27dp`), тоді як зовнішній скляний док мав радіус `23dp` при висоті 64dp. Через це лівий край мав кутасту прямокутну форму, а правий — видовжене скло, що давало візуальний ефект «різного типу заокруглення зліва та справа».

### 2. Математично точна концентрична геометрія капсул (Concentric Stadium Capsules)
- 🧩 **Архітектурне рішення**:
  - **Зовнішній док (`ThemeManager.dockBackground`)**: радіус заокруглення `concentricRadiusDp = 29f`. На висоті 58-64dp це дає чисту форму stadium capsule (ідеальний півкруг на обох краях).
  - **Внутрішня активна кнопка (`FrostedGlassDrawable.createAccentPill`)**: радіус заокруглення `25f`.
  - **Рівномірний концентричний зазор**: відступи контейнера встановлено в `padX = 6dp`, `padTop = 5dp`, `padBottom = 7.5dp` (компенсація проекції нижньої тіні 2.5dp). Завдяки цьому внутрішня кнопка має рівномірний зазор `4dp` з усіх 4 сторін (`25dp + 4dp = 29dp`), формуючи ідеальну концентричну дугу.
  - **Симетричні тіні**: у `FrostedGlassDrawable` розкид тіней Pass 1, 2, 3 вирівняно на симетричні зсуви `left - offset` та `right + offset` з симетричним горизонтальним полем `padX = 2.0f * density`.

### 3. Уніфікація Toggle-кнопок скляного неоморфізму
- 🔬 **Уніфікація по всьому інтерфейсу**:
  - Усі перемикачі (`MaterialButton`, `ToggleButton`, `CompoundButton`) переведено на єдиний фабричний метод `ThemeManager.pillDrawable(ctx, checked, isNight, radiusDp, accent, border)`.
  - Забезпечено автоконтраст тексту (`ThemeManager.contrastText`) відносно підкладки скла.
  - Підключено інтерактивний тактильний ефект занурення `TouchGlow` (зміщення + втоплення тіні) до всіх 8 перемикачів верхніх панелей (Loudness, Tonkomp, Delays, Filters, GALA) та Q-перемикачів смуг еквалайзера.
  - `TouchGlow.applyGlow` отримав захист для `FrostedGlassDrawable`, що запобігає перетиранню текстури скла дефолтним підсвічуванням.
- 📻 **Верифікація на залізі (192.168.1.146:9876)**: підтверджено ідентичну геометрію та паритет UIX для обох тем (Day / Night) на живому дисплеї 1280x720.

---

## 🏎️ GALA (Speed-Dependent Volume) Boost-Floor Architecture (🔬 / 🧩 10.09.2026 02:40) *(✍️ Antigravity & Kostyamat)*

### 1. Проблема блокування та боротьби автоматики з людиною (Human Intent Lockout)
- 🔬 **Анатомія вади**:
  - У циклі плавного крокування GALA (`pollingRunnable` кожні 100 мс) умова детекції ручної зміни `hardwareVol != lastReadHardwareVol && hardwareVol != lastAppliedVolume` містила сліпу зону: при кроці водія вниз на 1 значення `hardwareVol` збігалося з `lastReadHardwareVol`, через що Крок 5 повністю пропускався.
  - Навіть при виявленні ручного регулювання GALA не скасовувала залишковий фейд (`currentAppliedOffset != pendingTargetOffset`), і в тому ж 100-мс циклі перезаписувала значення водія своєю цільовою командою.
  - Безперервний спам `VolumeHelper.setVolume()` під час фейду викликав колізію в шині MCU при фізичному обертанні енкодера або натисканні кнопок керма.
  - Стара наївна формула `base = hardwareVol - currentAppliedOffset` призводила до колапсу: коли водій на 120 км/год скручував надмірний звук 18 до 12, база опускалася до `12 - 6 = 6`, і на світлофорі машина зустрічала водія мертвим шепотом (6 замість початкових 12).

### 2. Архітектура «Boost-Floor» та пріоритет водія
- 🧩 **Інженерне рішення**:
  1. **User Grace Period (1200 мс)**: щойно зафіксовано ручне регулювання гучності, вмикається таймаут тиші на 1.2 с, під час якого GALA категорично не посилає команд `setVolume` на MCU та заморожує кроки фейду. Це забезпечує плавний і слухняний хід фізичного енкодера.
  2. **Підтвердження команд (Command Ack)**: введені `lastGalaCommandVol` та `lastGalaCommandTimeMs`. Детектор надійно відрізняє очікуване ехо своєї команди та апаратний лаг від реальної дії людини.
   3. **Модель Boost-Floor (Розділення намірів водія)**:
     - **Зона бусту ($V_{user} \ge baseStandstillVolume$)**: коли водій зменшує або підкручує звук на швидкості понад поріг GALA, він коригує **виключно буст (офсет)**! Базова гучність стоянки (`baseStandstillVolume`) залишається недоторканою. Фейд миттєво узгоджується (`currentAppliedOffset = pendingTargetOffset = newOffset`). При зупинці авто буст плавно спадає до 0, повертаючи в салоні точний вихідний рівень стоянки (12 ➔ 12).
     - **Зона примусового приглушення ($V_{user} < baseStandstillVolume$)**: якщо водій скручує звук нижче рівня стоянки (наприклад, до 4 при базі 12), це наказ «зробити тихо взагалі». База опускається до нового значення (`baseStandstillVolume = 4`), буст анулюється (`offset = 0`), а нова база передається іншим джерелам через `carryBaseToOtherSource()`. При сповільненні звук ніколи не підстрибує вгору.
     - **Синхронізація з Radio**: обробник `ACTION_AUDIO_STATE_STABLE` у `McuService.java` приведено до ідентичної логіки Boost-Floor, виключаючи забруднення сторонніх джерел під час руху.

### 3. Адаптивна поправка кривої «UserTrim» та асиметрія вгору/вниз (🔬 / 🧩 10.09.2026 02:45)
- 🔬 **Проблема «Офігівання на 140 км/год»**:
  - Якщо водій на 120 км/год скрутив звук з 16 до 12, бо буст +6 був надмірним, а потім скинув швидкість до 90 і розігнався на автобані до 140 км/год — стандартний алгоритм без пам'яті поправки накидає на 140 повний буст (+8), ігноруючи водія і знову влуплюючи звук 18.
  - Бігуче середнє (Moving Average) цю проблему не вирішує, оскільки на сталій швидкості 140 км/год фільтр усе одно виходить на повні +8, а при гальмуванні дає запізнілий акустичний лаг.
- 🧩 **Інженерне вирішення (UserTrim)**:
  - Введено стан `galaUserTrim` (зсув крутизни GALA під комфорт водія на час поїздки).
  - **Крутимо вниз на швидкості ($desiredBoost \le rawOffset$)**: водій вважає заводську криву занадто крутою. Обчислюється `galaUserTrim = desiredBoost - rawOffset` (від'ємна поправка). При подальшому розгоні до 140 км/год GALA накидає не +8, а комфортні `rawOffset (8) + trim (-4) = +4`!
  - **Крутимо вгору на швидкості ($desiredBoost > rawOffset$)**: водій крутить звук голосніше за максимальний розрахунковий буст GALA (улюблений трек). У цьому випадку росте **базова гучність** (`baseStandstillVolume = hardwareVol - rawOffset`), а `galaUserTrim = 0`. Трек грає голосно на автобані й залишається голосним на наступних світлофорах.
  - **Скидання поправки**: коли автомобіль зупиняється на світлофорі/стоянці і водій змінює гучність на нульовій швидкості, `galaUserTrim` обнуляється, починаючи новий чистий цикл поїздки.

---

## 💎 Апаратне відкриття: Реальний необроблений мікрофонний тракт (True Unprocessed MIC) на UIS7862 / SC2730 (🔬 / 📻 10.09.2026 03:20) *(✍️ Antigravity & Kostyamat)*

### 1. Преамбула та міф про «зарізаний мікрофон»
- 🔬 **Старий стан знань**:
  - Раніше в документації вважалося, що на платформі UIS7862 джерело `AudioSource.UNPROCESSED` або ігнорується HAL, або що мікрофонний капсуль/кодек апаратно намертво зрізає низькі частоти нижче 150–200 Гц вбудованим High-Pass фільтром (`st_hpf_en` в `dsp_vbc.xml`).
  - Це вважалося глухим кутом для калібрування кривої еквалайзера під акустику салону автомобіля (Room Measurement / RTA) та для спектроаналізатора під час відтворення аналогового FM-радіо.

### 2. Дослідження та апаратні виміри на стенді (192.168.1.146:9876)
- 🔬 **Факти в системних політиках Android (`/vendor/etc/audio_effects.xml`)**:
  - У секції `<preprocess>` системної конфігурації ефекти (AEC — ехокомпенсатор, NS — шумодав) прив'язані виключно до трьох джерел за іменем: `mic`, `voice_communication`, `voice_recognition`.
  - **Стрім `unprocessed` у `<preprocess>` ПОВНІСТЮ ВІДСУТНІЙ!**
  - Це означає, що Android AudioPolicy на рівні ОС не накладає жодного телефонного ланцюжка обробки на стрім `AudioSource.UNPROCESSED`!
- 📻 **Апаратні заміри на дроті**:
  - Пряме захоплення через `AudioRecord(AudioSource.UNPROCESSED, 48000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)` з викликом `suspendCapturePreprocessing()` для очищення прапорців сесії.
  - Знято 2.5-секундний файл `mic_unprocessed_48000.wav` (120 320 семплів, 48 кГц, 16-біт).
  - Рівні сигналу: Peak = `−11.9 dBFS`, RMS = `−32.0 dBFS`.
  - **Спектральний аналіз (FFT) реального шуму салону**:
    * **Sub-bass (20 – 60 Гц)**: **`34.1 dB`** (величезна потужність, відсутній будь-який High-Pass Filter!).
    * **Bass (60 – 250 Гц)**: **`28.7 dB`**.
    * **Low-Mids (250 – 500 Гц)**: **`29.4 dB`**.
    * **Mids (500 – 2000 Гц)**: **`25.3 dB`**.
    * **Upper-Mids (2 – 4 кГц)**: **`25.6 dB`**.
    * **Presence (4 – 6 кГц)**: **`17.9 dB`**.
    * **Brilliance (6 – 12 кГц)**: **`4.9 dB`**.
    * **Top Air (12 – 20 кГц)**: `−34.3 dB` (природний спад електретного капсуля).

### 3. Архітектурні висновки для wDSP та екосистеми QF
1. **Чому це бездоганно працює для аналогового радіо**:
   - Звук тюнера TDA7708 іде в обхід Android напряму в підсилювач. Android AudioFlinger має нульовий вихідний потік, тому системний AEC `sprd cvs` навіть не намагається нічого віднімати.
   - Мікрофон у салоні через `AudioSource.UNPROCESSED` чує чисте звучання динаміків у повному діапазоні від 20 Гц до 12 кГц.

### 4. Анатомія затримки та «завмирання» спектра: Перехід на нативний `pushPcm16` (🔬 / 🧩 10.09.2026 03:50) *(✍️ Antigravity & Kostyamat)*

- 🔬 **Першопричина статичного («незмінного») вигляду спектра**:
  1. **Колізія зі `Stitcher::push()`**:
     - `Stitcher` був спроєктований автором wDSP виключно для відновлення неперервного потоку з *дискретних опитувань буфера AudioFlinger* (де блоки перекриваються на невідомий зсув). `Stitcher` шукає збіг за нормалізованою крос-кореляцією на хвості.
     - Для неперервного потоку салонного мікрофона крос-кореляція штучного вікна часто провалювалася нижче порогу 0.90, через що `Stitcher` оголошував розрив (`discontinuity`) і безумовно додавав у кільцевий буфер увесь 1024-байтовий блок замість 512 свіжих семплів.
     - Повторне дублювання половини семплів утворювало **гребінчастий фільтр (comb filter)** зі статичними резонансними піками, а буфер ріс швидше за реальний час, створюючи лавиноподібний лаг.
  2. **Агресивне АРП (AGC) у Java**:
     - Попередній алгоритм AGC підтягував пік кожного 10-мс блоку до 24 000. Це повністю сплющувало музичну динаміку, позбавляючи сигнал пульсації та ударів.
  3. **Штучна затримка `nativeLatencyMs = 500 ms`**:
     - `AudioSpectrumEngine` успадковував вихідну затримку відтворення AudioFlinger (500 мс), що змушувало C++ `readDelayedFrame()` читати кадри з минулого (~50 кадрів тому). Для акустичного мікрофона звук вже прилетів у повітрі, тому будь-яка затримка перетворювалася на відчутний лаг.

- 🧩 **Інженерне вирішення (Native Direct Streaming)**:
  1. **JNI-міст `NativeAnalyzer.pushPcm16(short[] buffer, int count, float gain)`**:
     - Зв'язано `RadioMicCapture` безпосередньо з C++ методом `Analyzer::pushPcm16()`.
     - `pushPcm16()` викликає `stitcher_.appendContinuous()`, миттєво записуючи чисті 16-бітні семпли в кільцевий буфер **в обхід алгоритму пошуку зсувів**.
     - Виклик `ringSignal_.notify_one()` миттєво будить потік аналізу `waitAndProcess()`.
  2. **Нульова затримка для мікрофона**:
     - При активному `RadioMicCapture` системна затримка примусово виставляється в `latencyMs = 0.0f`, що дає миттєву реакцію на кожен барабанний удар чи вокальну фразу.
  3. **Нативне плавне АРП та 60 fps**:
     - Віддано контроль динаміки нативному біжучому піковому АРП (`runningPeakDb` з постійною часу ~2.5 с), що зберігає повний динамічний діапазон музики.
     - Потік відображення переведено на стабільні 60 fps (`DISPLAY_PERIOD_MS = 16`).

---

## 🎯 Автоматичне сліпе калібрування акустики салону та мікрофона (🔬 / 🧩 10.09.2026 05:20) *(✍️ Antigravity & Kostyamat)*

Детальний опис та алгоритми зафіксовано в [`.agents/ROOM_CALIBRATION.md`](.agents/ROOM_CALIBRATION.md).

### Три кити повної авто-калібрації:
1. **GCC-PHAT (Generalized Cross-Correlation with Phase Transform)**:
   - Відмова від енергетичного пошуку піку обвідної (`findArrival`) на користь фазової нормалізації крос-спектру $R_{12}^{PHAT}(f) = \frac{X_1(f) X_2^*(f)}{|X_1(f) X_2^*(f)| + \epsilon}$.
   - Субсемплерна параболічна інтерполяція піку для мікросекундної точності TDOA між динаміками (FL, FR, RL, RR).
   - Точний розрахунок затримок для апаратних регістрів BU32107 (крок 0.5 мс, до 40 кроків / 20 мс).
2. **Сліпе калібрування мікрофона через Cabin Gain Anchor (+12 дБ/октава)**:
   - Використання фізичного ефекту камери тиску замкненого салону нижче 50–80 Гц (+12 дБ/окт).
   - Просторове усереднення 4-х каналів (FL, FR, RL, RR) для нівелювання інтерференційних провалів (comb nulls).
   - Оцінка спаду ФВЧ некаліброваного мікрофона нижче 80 Гц та синтез інверсної компенсаційної кривої $H_{mic\_inv}(f)$.
   - 1/3-октавне психоакустичне згладжування вище 200 Гц.
3. **Синтез Auto-EQ під цільову криву Harman Target Curve**:
   - Цільова крива Harman In-Car: плавний підйом суббасу (+4..+6 дБ), нейтральна середина, плавний спад ВЧ (-0.8..-1.0 дБ/окт).
   - Асиметрична оптимізація: зріз стоячих резонансних піків салону (до -9 дБ), сувора заборона бусту гребінчастих нулів (макс +2.5..+3 дБ).
   - Квантування та мапінг у 16 апаратних смуг BU32107 (індекси 0..12, Q wide/narrow) та запис пресету `"AutoEQ Harman"`.

### Покроковий план робіт:
- **Етап 1**: GCC-PHAT у C++ DSP та верифікація затримок TDOA.
- **Етап 2**: Замір фонового шуму (Noise Floor у фазі тиші), спектральне віднімання $P_{clean} = P_{sweep} - P_{noise}$, Cabin Gain Anchor та калібрування мікрофона.
- **Етап 3**: Синтез Auto-EQ Harman та мапінг BU32107.
- **Етап 4**: UI-інтеграція у `SettingsActivity` та тестування на залізі UIS7862.
- **Бонус**: Миттєве застосування кривої компенсації мікрофона в `Analyzer.cpp` (`setDspCurve`) для студійного спектру FM-радіо.

---

## 📻 Апаратна комутація динаміків через Fader/Balance MCU при Auto-EQ (🔬 / 📻 10.09.2026 19:15) *(✍️ Antigravity & Kostyamat)*

### 1. Тракт звуку та ідентифікація заліза
- **Звуковий процесор**: ROHM **BU32107EFV-M** (24-біт цифровий I2S DSP).
- **Datasheet**: TSZ02201-0C2C0E500500-1-2, Rev.001, 116 сторінок (07.Apr.2017).
- **Регістри Fader Volume (`0A00`–`0A05`)**:
  - `0A00` = FL (Front Left)
  - `0A01` = FR (Front Right)
  - `0A02` = RL (Rear Left)
  - `0A03` = RR (Rear Right)
  - `0A04` = SL (Sub/Surround Left)
  - `0A05` = SR (Sub/Surround Right)
  - Формула розрахунку: `Data = 0x20 (32 dec) - Fader_Volume_dB` ($0\text{ dB} = \text{0x20}$, $-79\text{ dB} = \text{0x6F}$, $- \infty\text{ dB} = \text{0x00}$).
- **Апаратні обмеження Advanced Switch (стор. 25-30, 94 даташиту)**:
  - Час плавного переходу гучності становить від 0.7 мс до 23.3 мс (`0003(hex)`, `0005(hex)`).
  - Даташит суворо забороняє слати одночасні команди гучності/мікшування під час роботи Advanced Switch: *"Do not send Fader Volume Gain setting data (0A00(hex) to 0A05(hex)) during same channel Mixing/Mixing Fader Advanced Switch operation. Fader Volume may malfunction."*

### 2. Чому виник збій при спробі «прямої» комутації (sendFaderDirect):
- Впровадження `sendFaderDirect` в обхід SharedPreferences спричинило:
  1. Гонку на шині UART між потоком UI / вимірювання та фоновим воркером `McuService.backgroundHandler`.
  2. Відкладені виклики `prefListener` читали застарілі значення з SharedPreferences і перетирали щойно відправлені регістри назад у центр `(12, 12)`.
  3. Порушення часу наростання/спаду Advanced Switch спричиняло десинхронізацію та непередбачуване звучання каналів.

### 3. Еталонна реалізація (Відновлено та закріплено):
1. **Єдина черга відправки (`McuService.backgroundHandler`)**:
   - Усі команди `0x81` (Fader/Balance/Loudness) проходять суворо через SharedPreferences (`currentPresetName + "_f_lr"`, `currentPresetName + "_f_fr"`).
   - `MainActivity` слайдери викликають виключно `autoSaveCurrent()`.
   - `RoomMeasurement.applyRouting()` пише в SharedPreferences тимчасового пресету `SCRATCH_PRESET`.
2. **Гарантований старт сервісу**: `McuService.ensureStarted(Context)` викликається в `SettingsActivity.onCreate()`, `RoomMeasurement.measure()`, та `RoomMeasurement.restoreIfInterrupted()`.
3. **Чистота тимчасового пресету**:
   - У блоці `finally` методу `runOnePass` координати тимчасового пресету повертаються в центр `(12, 12)`, тому пресет ніколи не зависає в кутку.
4. **Гарантоване скидання кешу та відновлення**:
   - У `restoreIfInterrupted()` та `finally` методу `measurePass` відправляється широкомовне повідомлення `RESET_AUDIO_MCU` (`com.radiorubka.wdsp.RESET_AUDIO_MCU`), яке очищує `mcuCache` та виконує `syncPreset(false)` для синхронізації всього заліза з пресетом користувача.

---

## 🔊 Сабвуферний свіп, відсіювання фантомів, бас без саба та взаємовиключення затримок (🔬 / 📻 10.09.2026 20:00) *(✍️ Antigravity & Kostyamat)*

### 1. Акустичний свіп сабвуфера як 5-го каналу (`Channel.SUBWOOFER`)
- Сабвуфер комутується апаратно через команду `0x8B` (`_sub_g = 12` (+6 дБ), `_sub_f = 8` (160 Гц LPF)).
- Дверні динаміки на час сабвуферного свіпу зрізаються апаратним ФВЧ на 250 Гц (`_bf_f = 11`, `_bf_r = 11`), що ефективно глушить випромінювання дверей у басовому діапазоні без впливу на сабвуферний тракт (який бере сигнал до блоку HPF).
- Оцінюється TDOA сабвуфера відносно якірного динаміка салону з урахуванням латентності сабвуферного тракту (допустимий спред до 18 мс замість 10 мс для дверей). Розраховані значення записуються в `result.suggestedSubDelayMs` та `result.suggestedSubDelaySteps`.

### 2. Басова стратегія без сабвуфера (`hasSubwoofer == false`)
- Сувора заборона зрізати дверні динаміки на 100 Гц!
- ФВЧ мідбасів виставляється в Through (`_bf_f = 0, _bf_r = 0`, 0 Гц / 20 Гц).
- У нативному синтезаторі `SweepMeasurement::synthesizeAutoEq16` для профілів Harman, Dolby Atmos та Club Bass цільова басова полиця розширюється вниз до 45 Гц з підйомом $+3.5 \dots +4.0\text{ дБ}$, витискаючи максимум панчу з дверної акустики.

### 3. Апаратне взаємовиключення Positional Delays (`_d_en`, 0x8C) та Surround (`_d1_en`, 0x89)
- ROHM BU32107 / AK7604 має спільний блок Delay RAM (регістри `0400`–`040D`). Команди `0x8C` (крок 0.5 мс) та `0x89` (крок 2.125 мс) пишуть в одну й ту саму пам'ять затримок.
- Одночасне увімкнення обох блоків фізично неможливе і призводить до взаємного перетирання регістрів.
- Введено суворе взаємовиключення: у пресеті `AutoEQ Dolby Atmos` активний лише Surround (`_d1_en = true, _d_en = false`); в інших пресетах активний лише Time Alignment (`_d_en = true, _d1_en = false`).
- У `MainActivity` додано взаємне перехресне вимкнення тумблерів `switchPreciseEnable` та `switchLegacyEnable` із прапорцем `isUpdatingUi`, а також фільтрацію в `savePreset` і `loadPreset`.

### 4. Усунення аномалії затримок 460 см (Physical TDOA Resolution)
- Реальні апаратні заміри показали різницю передніх динаміків: 1042.85 мс (FR) та 1044.19 мс (FL), $\Delta t = 1.34\text{ мс} \approx 46.0\text{ см}$ (3 кроки по 0.5 мс).
- Помилка ~460 см виникала через завищений ліміт `MAX_PLAUSIBLE_SPREAD_MS = 60` мс та потрапляння непевних салонних відбиттів (13.5 мс) у розрахунок `latest`.
- Спред обмежено до 10.0 мс для дверей салону (18.0 мс для сабвуфера), а опорний час `latest` обирається суворо серед впевнених прямих приходів (`confident || clarityDb >= MIN_CLARITY_DB`).

---

## 🎙️ Динамічне калібрування мікрофона, Soft Noise Gate, акустичні воронки та верифікація MCU (🔬 / 📻 10.09.2026 20:30) *(✍️ Antigravity & Kostyamat)*

### 1. Верифікація за декомпільованим кодом MCU (`mcudecomplied.c`) та даташитом ROHM BU32107
- 🔬 **Команда `0x88` (Фільтри дверей та Bass Boost, рядки 11057–11063)**:
  - Байт 0 (`local_10e[0]`): Front Bass Boost (`_bb_f`).
  - Байт 1 (`local_10e[1]`): Rear Bass Boost (`_bb_r`).
  - Байт 2 старший нібл (`local_10e[2] >> 4`): Front HPF cutoff index (`_bf_f`).
  - Байт 2 молодший нібл (`local_10e[2] & 0xf`): Rear HPF cutoff index (`_bf_r`).
  - Значення `0` = Through (повний діапазон без зрізу від 20 Гц). Підтверджено: під час свіпу 4-х дверей `_bf_f = 0, _bf_r = 0`, що забезпечує чесний замір повної АЧХ дверних динаміків.
- 🔬 **Команда `0x8B` (Сабвуфер, рядки 11093–11097)**:
  - Байт 0 старший нібл (`local_10e[0] >> 4`): частота LPF сабвуфера (`_sub_f`, індекси 0..10 для 25..250 Гц).
  - Байт 0 молодший нібл (`local_10e[0] & 0xf`): гейн сабвуфера (`_sub_g`, 0..12, де 0 = повний mute/мінімум).
  - Підтверджено: під час свіпу дверей `_sub_g = 0`, сабвуфер повністю знеструмлений і не заважає виміру. Під час свіпу саба `_sub_g = 12` (+6 дБ) та `_sub_f = 8` (160 Гц).
- 🔬 **Команда `0x8C` (Затримки динаміків та сабвуфера, рядки 11114–11143)**:
  - Приймає 5 байтів: FL, FR, RL, RR та SUB (`local_10a`).
  - Масштабний коефіцієнт у прошивці MCU: `val * 0x30 / 10 = val * 4.8` семплів при 48 кГц. Оскільки Android відправляє `steps * 5`, маємо `(steps * 5) * 4.8 = steps * 24` семпли $= 0.5\text{ мс}$ на крок!
  - 5-й байт записується в `DAT_08008b28 + 0x1c` — це окремий повноцінний канал затримки сабвуфера в RAM BU32107!
- 🔬 **Команда `0x89` (Surround RSSE, рядки 11079–11086)**:
  - Масштабний коефіцієнт `val * 0x66 = val * 102` семпли $= 2.125\text{ мс}$ на крок.
  - Обидві команди (`0x8C` та `0x89`) пишуть у спільні регістри Delay RAM `0400`–`040D` BU32107. Їх одночасне увімкнення є взаємовиключним.

### 2. Усунення протікання шуму в тиші: Soft Noise Gate у `RadioMicCapture.java`
- 🔬 **Анатомія проблеми**:
  - Умова `peak > NOISE_GATE_THRESHOLD` регулювала лише коефіцієнт АРП `currentGain`. Коли в салоні стояла тиша (`peak <= 150`), семпли множилися на `g \approx 1.0` і транслювалися в `Analyzer.cpp`.
  - Одночасно `Analyzer.cpp` для акустичного режиму (`isAcoustic_ == true`) не віднімав адаптивний цифровий шум `noiseFloor_`, а стара оцінка компенсації мікрофона в `sweep.cpp` додавала до $+15\text{ дБ}$ підйому нижче 80 Гц у `PREF_MIC_COMPENSATION`.
  - Через це фоновий шум салону (робота кондиціонера, шелест, шум двигуна на холостих, тепловий шум передпідсилювача) задирав стовпчики спектроаналізатора вгору навіть при вимкненій музиці!
- 🧩 **Інженерне вирішення (Soft Gate Envelope)**:
  - Введено динамічну обвідну гейту `gateGain` (швидка атака ~30 мс, плавний реліз ~150 мс) з лінійною інтерполяцією всередині кожного PCM-блоку:
    $g(t) = \text{gain}(t) \cdot \text{gate}(t)$.
  - Коли сигнал падає нижче порогу тиші, `gateGain` плавно сходить до `0.0`, повністю обнуляючи PCM-буфер. Аналізатор отримує чистий нуль, стовпчики візуалізатора миттєво і стабільно лягають у нуль.

### 3. Живе калібрування рівня шуму салону перед свіпом (`RoomMeasurement.java`)
- 📻 **Динамічний замір у фазі тиші (Lead-in Silence)**:
  - Перша 1.0 секунда запису (`lead = 48000` семплів) — це чиста салонна тиша перед стартом першого свіпу.
  - Безпосередньо з цього буфера викликається `NativeSweep.noiseFloor(asFloat, lead, result.noiseFloorDb16)`, який обраховує реальний 16-смуговий акустичний спектр фонового шуму автомобіля.
  - Вимірюється піковий та RMS рівень шуму салону і зберігається в SharedPreferences (`room_calibrated_noise_peak`, `room_calibrated_noise_rms_db`), передаючи актуальний калібрований поріг у `RadioMicCapture`.
  - Спектральне віднімання `NativeSweep.subtractNoise` для всіх каналів використовує цей живий замір замість штучного семплу 48 деконвольваної імпульсної характеристики.

### 4. Акустичні воронки та розміщення мікрофона (`micPlace` та `micSpot`)
- 🧩 **Розширення списку місць кріплення мікрофона (`MIC_PLACES`)**:
  - `room_mic_place_headunit` (індекс 8): **Вбудований мікрофон магнітоли (отвір на панелі)**.
  - `room_mic_place_headrest` (індекс 9): **Підголівник водія (рівень вух)** — еталонна акустична точка виміру.
  - `room_mic_place_armrest` (індекс 10): **Центральний підлокітник / консоль**.
- 🔬 **Фізична компенсація резонансу Гельмгольца (Helmholtz Cavity Resonance)**:
  - Вбудований мікрофон магнітоли знаходиться за отвором 1.5–2 мм у пластиковій панелі. Разом із внутрішньою камерою він утворює класичний резонатор Гельмгольца з піком $+4 \dots +6\text{ дБ}$ на частотах $2.8 \dots 3.2\text{ кГц}$ та різким спадом віддачі нижче 150 Гц.
  - Якщо `micPlace == 8`, Auto-EQ пом'якшує зріз смуг 2.5–3.15 кГц (щоб не провалювати мовну чіткість акустики авто через акустичну камеру магнітоли) та обмежує корекцію суббасу нижче 100 Гц.
- 🔬 **Граничні відбиття (Лобове скло, `micPlace == 0`)**:
  - Мікрофон впритул до скла створює інтерференційні гребінчасті провали (comb filter nulls) вище 2 кГц. Алгоритм забороняє вузькосмуговий буст понад $+2\text{ дБ}$ у цій зоні, запобігаючи різкому свисту твітерів.
- 🧩 **Геометричний анкер `micSpot` у розрахунку затримок**:
  - Координати точки мікрофона ($lr, fr$) використовуються для верифікації фізичної черговості приходу звуку (наприклад, для водія зліва $t_{FL} < t_{FR}$). Аномальні відбиття, що суперечать геометрії салону більш ніж на 6–8 мс, автоматично відсіюються.

### 5. Виправлення «нульових високих» в Auto-EQ (🔬 / 📻 10.09.2026 20:38) *(✍️ Antigravity & Kostyamat)*
- 🔬 **Анатомія збою в пресеті `AutoEQ Harman`**:
  - У вимірі `wdsp_room_measurement_20260910_203148.zip` смуги 10, 13, 14, 15 (2 кГц, 8 кГц, 12.5 кГц, 20 кГц) були загнані в індекс `1` ($-7.5\text{ дБ}$), повністю глушачи високі частоти.
  - **Причина 1 (Units Disparity БПФ)**: Спектр фонового шуму з сирого PCM у `spectrum16Db` без ділення на $N^2$ мав енергетичний підйом $+42\text{ дБ}$ (значення шуму до $+16.3\text{ дБ}$), тоді як відгук динаміка в деконвольвованій імпульсній характеристиці (`bandLevelsDb`) мав значення $-8 \dots -25\text{ дБ}$.
  - **Причина 2 (Чорна діра віднімання)**: Оскільки `pSweep - pNoise < 0`, смуги 0..9 занулялися до $-120\text{ дБ}$. Опорна середина `refMid` (200..800 Гц) стала рівною $-120\text{ дБ}$. Смуги високих частот, які вціліли на правому каналі ($-62\text{ дБ}$), математика сприйняла як «викид $+58\text{ дБ}$ над серединою» і зрізала їх у максимально можливий ліміт (індекс 1).
- 🧩 **Виправлення**:
  1. **Нормалізація БПФ**: у `spectrum16Db` потужність ділиться на $\text{windowLen}^2$, приводячи значення до коректної шкали dBFS.
  2. **Повернення домену деконволюції**: у `RoomMeasurement.java` рівень шуму `cr.noiseBandsDb` читається суворо з `analysis[NativeSweep.NOISE_BANDS]` (семпл 48 деконвольвованої тиші), де масштаб і вікно 1:1 збігаються з відгуком динаміка.
  3. **Berouti Spectral Subtraction Floor**: у `SweepMeasurement::subtractNoise` введено спектральний поріг $\beta = -12\text{ дБ}$ (`pSweep * 0.0631f`). Придушення шуму ніколи не створює штучних дірок $-120\text{ дБ}$.
  4. **Захист розрахунку `refMid`**: ігноруються смуги з рівнем $\le -70\text{ дБ}$, захищаючи опорну середину від завалювання.
### 6. Апаратна топологія кросовера BU32107 vs 16-смуговий еквалайзер (🔬 / 📻 10.09.2026 20:45) *(✍️ Antigravity & Kostyamat)*
- 🔬 **Тракт сигналу та положення еквалайзера**:
  - Вхід I2S $\rightarrow$ **16-смуговий еквалайзер (`0610..061F`)** $\rightarrow$ **P2Bass (`0705..0706`)** $\rightarrow$ **Кросовер: HPF дверей (`0703/0704`, cmd `0x88`) та LPF сабвуфера (`0707`, cmd `0x8B`)** $\rightarrow$ DVol / Faders.
  - **16-смуговий еквалайзер стоїть ДО кросовера!** Будь-який зріз смуг 20, 31.5, 50 Гц на еквалайзері неминуче ріже вхідний сигнал для сабвуфера. Підйом сабвуфера повзунком до $+8\text{ дБ}$ стає марним, оскільки еквалайзер уже відфільтрував найглибші баси на вході мікросхеми.
- 🔬 **Крутизна та частоти фільтрів кросовера (Даташит BU32107 с. 88–90, `mcudecomplied.c`)**:
  - Біт `Order` для HPF дверей (`0703/0704`) та LPF сабвуфера (`0707`): значення `0` = **2nd order (12 дБ/октава)**. Обидва фільтри сконфігуровані в прошивці MCU як 12 дБ/окт Баттерворт.
  - Сітка частот зрізу (коди 1..11): `25, 31.5, 40, 50, 63, 80, 100, 125, 160, 200, 250 Гц`.
  - У коді MCU (`mcudecomplied.c:2756`): `*(char *)(iVar2 + 0x78) = *(char *)(iVar3 + 0x19) + '\x01'`. До індексу спінера сабвуфера (0..10 у `SUB_FREQS_RAW`) MCU додає `+1` (код 6 = 80 Гц). Слайдер HPF дверей `_bf_f = 6` (80 Гц) шле код 6 безпосередньо. Обидва фільтри зустрічаються на одній частоті 80 Гц на рівні $-3\text{ дБ}$ із симетричним спадом 12 дБ/октава.
- 🧩 **Правило розділу обов'язків в Auto-EQ**:
  1. **Коли сабвуфер присутній (`hasSub == true`)**:
     - Еквалайзер нижче частоти кросовера ($f < \text{cutoffHz}$) **залишається рівним 0 dB (Flat / індекс 6)**, гарантуючи надходження повного сигналу на сабвуфер.
     - Двері зрізаються виключно апаратним HPF 12 дБ/октава.
     - Гейн сабвуфера виставляється у природні $+2 \dots +4\text{ дБ}$ (індекс 7 або 8 для Harman), що забезпечує глибокий, масивний бас без перевантаження.
  2. **Коли сабвуфера немає (`hasSub == false`)**:
     - Двері працюють у повному діапазоні: HPF ставиться у Through (код 0, 20 Гц).
     - Смуги 50 Гц та 80 Гц отримують максимальну віддачу (+2..+3 дБ підйому), щоб отримати максимальний бас від 45–50 Гц.
     - Смуги 20 та 31.5 Гц фіксуються в 0 dB (Flat), запобігаючи небезпечному перевантаженню дифузорів інфразвуком.
- 🎙️ **Природа «Калібрування мікрофона» (Етап 1)**:
  - Етап 1 свіп-майстра — це замір **фонового шуму тиші салону (1 секунда)** при вимкненому звуці (для калібрування Soft Noise Gate).
  - Сам АЧХ-профіль мікрофона (`estimateMicCompensation`) обраховується автоматично зі свіпів динаміків на наступному етапі (метод акустичного анкера кабіни). Статус на Етапі 1 перейменовано на «Замір фону тиші», щоб не створювати хибного враження відсутності свіпу.

### 7. Спектральний розрив: Розрахунковий vs Мікрофонний спектр, шлюз Root та тонкощі Fletcher-Munson (🔬 / 📻 10.09.2026 22:30) *(✍️ Antigravity & Kostyamat)*
- 🔬 **Природа разючої різниці між «Розрахунковим» та «Мікрофонним» спектром**:
  - **Розрахунковий (Calculated / PCM)**:
    - Знімається безпосередньо з цифрового виходу AudioFlinger до аналогового тракту.
    - Математично «сухий», стерильний спектр файлу або стріму без спотворень.
  - **Мікрофонний (Live Mic Capture)**:
    - Знімає реальну звукову хвилю після всього ланцюга: ЦАП $\rightarrow$ аналоговий передпідсилювач $\rightarrow$ підсилювач потужності $\rightarrow$ нелінійна АЧХ дифузорів динаміків $\rightarrow$ акустичні моди салону (стоячі хвилі, гребінчасті фільтри, поглинання оббивкою та відбиття від лобового скла) $\rightarrow$ мікрофон магнітоли.
    - Вбудовані автомобільні мікрофони мають специфічну АЧХ: різкий спад нижче 100 Гц (захист від гулу двигуна) та горб на 2.5–3.5 кГц для розбірливості голосу при телефонних розмовах. Навіть із застосуванням компенсації кімнати живий спектр відображає *реальний звук у кабіні*, а не стерильний файл.
- 🛑 **Шлюз безпеки Root для секції Акустичного калібрування**:
  - Секція `label_room_section` («Акустичне калібрування салону (експериментальна, root)») відокремлена від загальної діагностики.
  - Жорсткий гейт: якщо `!PermissionsWizard.isRootGranted(context)`, розгортання секції повністю заблоковано як при кліку, так і при переході за Intent `open_section_id`. Відображається тост `room_root_required_toast` та відкривається майстер прав `PermissionsWizard`.
- 📻 **Захист мікрофонного спектру від невідкаліброваного тракту**:
  - Пайплайн мікрофона блокується, доки на диску немає файлу компенсації `mic_compensation.bin`.
  - При спробі перемкнутися на «Мікрофон» без калібрування або під час відтворення аналогового радіо користувач отримує діалог-запрошення провести калібрування із кнопкою швидкого переходу.
- 🔬 **Архітектура кнопок Fletcher-Munson (Equal-Loudness ISO 226)**:
  - Усі налаштування (`_fm_cal`, `_fm_str`, `_fm_en`, `_fat_en`, `_sub_comp`) зберігаються **суворо окремо для кожного пресету** у `SharedPreferences` (`EqPresets.xml`).
  - **«Компенсація сабвуфера» (`switch_fm_sub_comp`)**: динамічно підвищує коефіцієнт підсилення сабвуфера (через MCU команду `0x8B`) при тихій гучності (`vol < cal`) пропорційно обраній частоті зрізу (до +6..+12 дБ), компенсуючи втрату чутливості людського вуха до суббасу.
  - **«Обмежити ВЧ / Захист від втоми» (`switch_fatigue_enable`)**: на гучності вище точки калібрування (`vol > cal`) автоматично пом'якшує високі частоти (від -1 дБ на 5 кГц до -3.5 дБ на 20 кГц), захищаючи слух від стомлення при тривалих поїздках.
  - **«Точка калібрування 80 дБ» (`seek_fm_cal_vol`)**: позначає рівень системної гучності, за якого в салоні досягається 80 дБ SPL. При цій гучності криві Флетчера-Менсона вважаються пласкими (компенсація = 0 дБ). Автоматичний розрахунок цієї точки винесено в майбутній roadmap (потребує каліброваного SPL-метра).
- ❓ **TODO Дослідження**:
  - Розрахунок фази сабвуфера за формулою: $t_{sub\_phase} = t_{sub\_mic} - t_{mic\_ears}$ (затримка сабвуфера до мікрофона мінус відстань від мікрофона до вух слухачів). Дослідити у наступних версіях.

### 8. Роадмап: Порівняльна дельта спектрів (Розрахунковий vs Отриманий) як інструмент калібрування стенда та діагностики (🧩 / 🔬 10.09.2026 22:55) *(✍️ Antigravity за ідеєю Костянтина)*
- 💡 **Концептуальна основа (Очікуване vs Отримане)**:
  - У системі одночасно функціонують два взаємодоповнюючі спектроаналізатори:
    1. $S_{\text{expected}}(f)$ — **Розрахунковий спектр (Очікуваний результат)**: стерильний цифровий сигнал AudioFlinger із накладеною передатною характеристикою DSP BU32107 (еквалайзер, кросовер, Fletcher-Munson, сабвуфер). Це «ідеальний образ» того, що має пролунати в кабіні.
    2. $S_{\text{measured}}(f)$ — **Мікрофонний спектр (Отримана реальність)**: акустичний сигнал, захоплений фізичним мікрофоном у салоні авто.
  - Їхня спектральна дельта в реальному часі:
    $$\Delta(f) = S_{\text{measured}}(f) - S_{\text{expected}}(f)$$
    є прямим і найчистішим джерелом інформації про те, **де саме виникає похибка** — в апаратному тракті мікрофона чи в фізичній акустиці салону:
    - **Якщо $\Delta(f)$ має плавний завал на краях діапазону (< 100 Гц або > 12 кГц)**: це апаратне обмеження капсуля мікрофона або конденсаторів передпідсилювача кодека UIS7862 $\rightarrow$ вектор для автоматичної підстройки `pref_mic_compensation`.
    - **Якщо $\Delta(f)$ має вузькі гострі провали або піки (наприклад, +8 дБ на 125 Гц, -12 дБ на 63 Гц)**: це акустичні стоячі хвилі (кімнатні моди салону) або фазова деструкція сабвуфера з передніми мідбасами $\rightarrow$ інформація для Auto-EQ або налаштування затримок.
    - **Якщо $\Delta(f)$ різко провалює окремі смуги при додаванні гучності**: це кліпінг підсилювача або компресія динаміків.
- 🚀 **Практичні напрямки впровадження у Roadmap**:
  1. **Пасивне автокалібрування на живій музиці (Live Music System Identification)**:
     - Замість агресивних, гучних тестових свіпів (які лякають пасажирів і вимагають абсолютної тиші), додаток накопичує інтегральну дельту $\overline{\Delta}(f)$ під час звичайного прослуховування музики користувачем (за 30–60 секунд).
     - Метод когерентності та взаємної спектральної густини потужності (Cross-Spectral Density / Welch CPSD) дозволяє витягти чисту передатну функцію салону $H(f)$ навіть на звичайних треках.
  2. **Інтерактивна діагностика стенда (Acoustic Health Check)**:
     - Відображення живої дельти на екрані діагностики або у вигляді підсвітки проблемних смуг (зелений — відгук салону збігається з файлом $\pm 2\text{ дБ}$, жовтий/червоний — акустична яма або резонанс).
     - Автоматична підказка користувачу: «Виявлено спад суббасу на мікрофоні: перевірте отвір мікрофона або запустіть калібрування».
  3. **Авто-узгодження чутливості мікрофона**:
     - Динамічне підлаштування рівня AGC та компенсаційних коефіцієнтів мікрофона так, щоб на стаціонарних середніх частотах (315–1000 Гц) енергія мікрофонного спектру ідеально збігалася з розрахунковим.

### 9. Єдиний стандарт оформлення гіперпосилань (Hyperlink Standard: Link Blue & Underline) (10.09.2026 23:15) *(✍️ Antigravity & Kostyamat)*
- 🔗 **Проблема**:
  - Клікабельні посилання на групи (Telegram, GitHub, платіжні сервіси) зливалися зі звичайним текстом вторинних підписів (сірий колір без підкреслення), через що користувачі не розуміли, що ці елементи клікабельні.
- 🎨 **Стандарт оформлення**:
  - **Колір**: Адаптивний насичений синій `link_blue`:
    - Денна тема: `#1976D2` (Material Blue 700 з високою контрастністю на світлих картках).
    - Нічна тема: `#4BA3E3` / `#3390EC` (офіційний яскравий синій колір Telegram з ідеальною чіткістю на темних картках).
  - **Підкреслення**: Обов'язковий прапорець `Paint.UNDERLINE_TEXT_FLAG`.
  - **Інтерактивність**: Підключення анімації натискання `TouchGlow.attach(view)`.
- 🛠️ **Реалізація в системі**:
  1. `ThemeManager.java`:
     - Додано методи `linkBlue(boolean night)` та `linkBlue(Context ctx)`.
     - Додано утилітарні методи `styleAsLink(TextView tv, boolean night)` та `styleAsLink(TextView tv)` для централізованої стилізації.
  2. `SupportBanners.java`:
     - Усі рядки капсул підтримки з валідним URI (`it.uri != null`) автоматично отримують `ThemeManager.styleAsLink(value, night)`. Рядки криптогаманців без веб-посилань залишаються у звичайному моноширинному стилі без підкреслення.
  3. `SettingsActivity.java`:
     - Посилання на групу Telegram у секції діагностики (`tv_room_telegram`) переведено на `ThemeManager.styleAsLink(tvTelegram, editNight)` з `TouchGlow`.
     - Вилучено `R.id.tv_room_telegram` з масиву `secondaryLabels`, що запобігає скиданню кольору та прапорців підкреслення при викликах `repaint()`.
  4. `ThemedDialog.java`:
     - Повідомлення діалогів (`tvMsg`) отримали `Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES` з `LinkMovementMethod` та кольором `linkBlue`.
     - Додано регулярний вираз для автоматичного розпізнавання Telegram-тегів виду `@[a-zA-Z0-9_]+(/[0-9]+)?` із перетворенням на відкриття `https://t.me/...`.

#### 10. Розділення складок акордеону: Акустичне калібрування з Auto-EQ vs Інженерна діагностика (10.09.2026 23:25) *(✍️ Antigravity & Kostyamat)*
- 🧩 **Архітектурне розділення сутностей**:
  - Раніше інтерфейс змішував інженерний діагностичний свіп (для збору raw-файлів і надсилання автору) з кінцевим функціоналом побудови користувацьких Auto-EQ пресетів.
  - Проведено чітке розмежування двох незалежних підсистем в акордеоні налаштувань:
    1. **Вкладка «Акустичне калібрування салону (експериментальна, root)» (`card_settings_room`)**:
       - Єдине канонічне місце для калібрування звукового тракту під салон та генерації повноцінних пресетів еквалайзера.
       - Кнопка: `btn_room_measure` — **«Калібрувати салон та створити пресет»**.
       - Статус: `tv_room_status` — відображає останній створений пресет («Пресет «AutoEQ Harman (Водій)» успішно створено та застосовано!») або повідомлення «Немає створених Auto-EQ пресетів».
       - **Ідентифікатори звукової сцени в назвах**: до імені пресету автоматично додається короткий тег обраного фокусування сцени:
         - `DRIVER` $\rightarrow$ `(Водій)` (наприклад, `AutoEQ Harman (Водій)`)
         - `FRONT_CENTER` $\rightarrow$ `(Центр)` (наприклад, `AutoEQ Harman (Центр)`)
         - `CABIN_CENTER` $\rightarrow$ `(Всі)` (наприклад, `AutoEQ Harman (Всі)`)
         - `OFF` $\rightarrow$ `(Вимкн)` (наприклад, `AutoEQ Harman (Вимкн)`)
       - **Перзистентність параметрів авто**:
         - Наявність сабвуфера (`PREF_ROOM_HAS_SUBWOOFER`), режим сцени (`PREF_ROOM_SOUNDSTAGE`), цільова крива (`PREF_ROOM_TARGET_CURVE`), тип кузова та відстань зберігаються в `SharedPreferences` і автоматично заповнюються при наступному відкритті майстра. Водій не змушений щоразу повторно вибирати параметри авто.
       - **🛑 ЗАЛІЗНЕ ПРАВИЛО: «Пресети не терти!»**:
         - Новий пресет додається в множину `PREF_PRESET_NAMES` у `EqPresets.xml`. Жодні існуючі пресети користувача не затираються і не видаляються.
       - **🚫 Видалено діагностичні кнопки зі звіту Auto-EQ**:
         - З вікна звіту (`dialog_room_wizard.xml`) видалено кнопку «Зберегти звіт для розробника». Майстер працює виключно на створення та активацію пресету користувача.
    2. **Вкладка «🔬 Діагностика» (`card_settings_debug`)**:
       - Суто інженерний розділ для технічного збору замірів та аудиту платформи:
         - `btn_debug_room_measure`: **«Діагностичний свіп»** — запускає технічний замір для визначення полярності та імпульсної характеристики, оновлює статус `tv_debug_room_status` («Діагностичний замір виконано. Готово до збереження архіву.») і пропонує упакувати архів.
         - `btn_room_send`: **«Зберегти архів»** — створює zip-архів з усіма raw wav-записами замірів та `wdsp_system_report.txt` у `Download/wDSP/`.
         - `tv_room_telegram`: синє підкреслене посилання на гілку розробника Telegram `@kostyamat_dev/92`.
         - `btn_system_report`: **«Зібрати»** — звіт про систему.
         - `btn_screen_topology`: **«Топологія екрана»**.

  11. **Ізоляція математики фонового шуму салону та апаратної кривої мікрофона** (✍️ Досліджено Antigravity — 10.09.2026 23:40):
      - **Проблема**:
        - 📻 *Спостереження на стенді*: у салоні авто при увімкненому кондиціонері/пічці спектроаналізатор у паузах між треками продовжував малювати активні смуги на середніх частотах, тоді як усе решта мовчало. Крім того, кожен замір салону намагався заново перекалібрувати мікрофон.
      - **Анатомія та виправлення**:
        1. **Віднімання фонового шуму (Noise Floor Subtraction)**:
           - 🔬 *Код*: `wdsp_app/src/main/cpp/analyzer.cpp`, метод `Analyzer::processFrame`.
           - *Причина*: раніше логіка навчання шуму та віднімання мала перевірку `if (quiet && !isAcoustic_)` та `float signal = isAcoustic_ ? power : (power - noiseFloor_[i] * kNoiseFloorMargin)`. Акустичний режим повністю вимикав віднімання шуму, тому стаціонарний повітряний шум кондиціонера світився на спектрі як музика.
           - *Рішення*: в акустичному режимі дозволено відстеження шуму в моменти пауз (`quiet`) та його віднімання: `signal = power - noiseFloor_[i] * kNoiseFloorMargin`. Якщо сигнал нижче порогу шуму — він обнуляється до -120 дБ. Спектр у тиші стає чистим.
        2. **Захист апаратної калібрувальної кривої мікрофона (Mic Curve Protection)**:
           - 🔬 *Код*: `wdsp_app/src/main/java/com/radiorubka/wdsp/RoomMeasurement.java`, метод `measureAsync()`.
           - *Причина*: раніше кожен звичайний замір салону викликав `NativeSweep.estimateMicCompensation()` та безумовно перезаписував `setMicCompensationCurve(context, result.micCompensation16)`. Оскільки мікрофонна крива та розрахункова крива на стенді вже зведені та узгоджені, автоматичне перезаписування за акустикою динаміків авто ламало калібрування.
           - *Рішення*: видалено виклик `setMicCompensationCurve(...)` з `measureAsync()`. Звичайні свіпи салону тепер завантажують готову криву через `getMicCompensationCurve(context)` як константу. Крива компенсації мікрофона залишається непорушною і не затирається.

### 12. Акустичне калібрування мікрофона vs Живе мінусування стаціонарного шуму (HVAC / Кондиціонер) — Архітектурний борг (11.09.2026 00:45) *(✍️ Antigravity & Kostyamat)*
- 📻 **Замір на стенді**:
  - Після проведення калібрування мікрофона в машині монотонний фоновий шум увімкненого кондиціонера/пічки залишається видимим на спектроаналізаторі при увімкненому радіо в паузах та тихих моментах.
- 🔬 **Анатомія та висновок**:
  - Процедура `RoomMeasurement.calibrateMicAsync(...)` вимірює фоновий шум (`noiseRms`, `bgFft`) та АЧХ капсюля мікрофона, формуючи криву апаратної корекції `setMicCompensationCurve(...)`.
  - `AudioSpectrumEngine` коректно застосовує цю криву per-band для нормалізації чутливості мікрофона до еталону.
  - Проте в реальному часі нативний C++ рушій спектрального аналізу (`NativeAnalyzer.cpp` / `AudioSpectrumEngine`) наразі не виконує динамічного **віднімання стаціонарного шуму на льоту (Live Spectral Subtraction / Stationary Noise Gate)**:
    $$S_{\text{clean}}(b) = \max\left(0.0, S_{\text{raw}}(b) - \alpha \cdot N_{\text{floor}}(b)\right)$$
    де $N_{\text{floor}}(b)$ — спектральний профіль фонового шуму кондиціонера, заміряний перед свіпом, а $\alpha$ — коефіцієнт надлишкового віднімання (oversubtraction factor $\approx 1.2 \dots 1.5$).
- 📌 **Архітектурний борг (Task Debt for Claude & Kostyamat)**:
  - Реалізувати в нативному коді C++ або DSP-двигуні динамічне віднімання заміряного спектрального профілю шуму кондиціонера на льоту, щоб спектроаналізатор показував виключно чистий корисний музичний сигнал радіо.

### 13. Автоматична валідація, санітизація та міграція бази програм (`PresetsDatabaseValidator`) (11.09.2026 00:30) *(✍️ Antigravity & Kostyamat)*
- 🧩 **Призначення**:
  - Повна зворотна сумісність та автоматична «підтяжка» пресетів (`EqPresets.xml`) користувачів з попередніх версій додатку (з меншим `versionCode` або без версії), а також при відновленні зі старих JSON-бекапів чи імпорті окремих файлів `.json`.
- 🛠️ **Реалізовані механізми захисту та міграції**:
  1. **Захист від крашів `Slider.setValue()`**:
     - Всі 16 смуг еквалайзера клампуються в межах `0..12` (дефолт 6 = 0 дБ).
     - GALA `_gala_min_speed` обмежується стелею `40` (раніше в старих версіях було до 60 / 300 км/год, що викликало `IllegalArgumentException` при завантаженні у новий слайдер із максимумом 200 км/год).
     - Сабвуфер, підсилення басу, фільтри частот, фейдери приводяться до апаратних діапазонів BU32107.
  2. **Апаратна взаємовиключність затримок (Delay RAM Mutual Exclusion)**:
     - DSP BU32107 ділить оперативну пам'ять буфера затримок між Time Alignment (`_d_en`) та Surround (`_d1_en`). Одночасне увімкнення обох руйнує звук.
     - Валідатор автоматично детектує конфлікт: якщо `_rsse_val > 10` або назва пресету містить "surround"/"dolby" — залишає Surround, інакше — Time Alignment.
  3. **Підтяжка відсутніх параметрів**:
     - Пресети, створені до появи нових алгоритмів (FM-крива, тонкомпенсація, захист від втоми слуху `_fat_en`, компенсація субвуфера `_sub_comp`), автоматично отримують безпечні значення за замовчуванням.
  4. **Відновлення «загублених» пресетів**:
     - Сканування ключів `*_g0` та `*_power_vol` у файлі пресетів відновлює назви програм у `PREF_PRESET_NAMES`, якщо список був пошкоджений. Жоден пресет користувача ніколи не видаляється («Пресети не терти!»).
  5. **Тригери запуску**:
     - Викликається автоматично при старті `MainActivity`, на старті фонової служби `McuService`, при відновленні з повного бекапу в `SettingsActivity` та при імпорті окремого файлу `.json`.
     - Фіксує версію схеми в `pref_presets_db_version_code`.

### 14. Очищення UI, уніфікація діалогів та повна синхронізація локалізацій (11.09.2026 00:35) *(✍️ Antigravity & Kostyamat)*
- 🎨 **Перейменування секції**:
  - Секція в акордеоні налаштувань перейменована на **«Акустичне калібрування салону та авто-пресети»** (вилучено зайвий шум `(експериментальна, root)`).
- 🧹 **Очищення Діагностики та статусу Auto-EQ**:
  - Повністю вилучено оманливий статус `tv_room_status` («Немає створених Auto-EQ пресетів») біля кнопки запуску майстра: користувач сам знає свої дії, а статус вводив в оману.
  - Вилучено зайві кнопки діагностичного свіпу та ручного збереження архіву з картки «Діагностика». Збереження архіву для розробника інтегровано безпосередньо у фінальне вікно звіту майстра акустичних замірів.
- 🌐 **Синхронізація локалізацій**:
  - Всі 87 нових строкових ключів синхронізовано для всіх 28 локалей (`values-*`), підготовлено коректний переклад для `values-ru` та `values-ru-rUA` без попереджень синтаксису форматування замінників (`%1$s`, `%1$d`).

### 15. Відстеження відкликання Root, замок 🔒 у Налаштуваннях та протокол холодної інсталяції (11.09.2026 01:05) *(✍️ Antigravity & Kostyamat)*
- 🔬 **Анатомія проблеми та викриття на стенді**:
  - Користувач відібрав права root у Magisk, запустив додаток та проігнорував системний запит Magisk.
  - *Симптоми*: перемикач мікрофона в головному еквалайзері залишався видимим, вкладка «Акустичне калібрування салону та авто-пресети» відкривалася без root, а замочок `🔒` був відсутній.
  - *Першопричина 1 (Stale Preference Cache)*: у `RootAccess.java` метод `hasRoot(context)` сліпо зчитував `pref_root_granted` зі `SharedPreferences`. Якщо додаток хоч раз у минулому отримував root, значення `true` залишалося на диску назавжди, навіть якщо в Magisk права було відкликано або додаток перевстановлювався через `adb install -r`.
  - *Першопричина 2 (Фоновий виклик su без прав)*: в `AudioSpectrumEngine` запуск мікрофонного захоплення (`startRadioMicPipeline`) перевіряв лише наявність калібрувальної кривої `hasMicCal`, не перевіряючи root. Як наслідок, `RadioMicCapture` викликав `su -c cmd appops...`, що викликало несподіване спливаюче вікно Magisk при старті.
  - *Першопричина 3 (Ігнорування таймауту)*: при ігноруванні запиту Magisk повертався статус `Outcome.TIMED_OUT`, проте в `RootAccess.request()` скидання префів відбувалося лише для `REFUSED` та `DENIED_BY_POLICY`.
- 🛠️ **Реалізовані виправлення**:
  1. **Надійна верифікація `RootAccess`**:
     - `hasRoot(context)` більше ніколи не повертає `true` наосліп зі `SharedPreferences`. Якщо в поточній сесії процес ще не верифікував права, він повертає безпечний `false` і ініціює асинхронну перевірку `checkAsync()`.
     - `alreadyGranted()` виконує швидку перевірку `su -c id` (15-50 мс). Якщо в Magisk права відкликані (`Permission denied`) або запит проігноровано (таймаут 1.5 с) — повертає `false`.
     - Будь-який результат, відмінний від `Outcome.GRANTED` (включаючи `TIMED_OUT`), скидає кеш `sRootGranted = false` та записує `false` у префи.
     - `onResume` у `MainActivity` та `SettingsActivity` автоматично перевіряє статус у фоні, миттєво реагуючи на зміни в Magisk.
  2. **Індикатор замка 🔒 у `SettingsAccordion`**:
     - Якщо `!PermissionsWizard.isRootGranted(ctx)`: секція отримує префікс `🔒 Акустичне калібрування салону та авто-пресети` з приглушеним кольором тексту.
     - Розгортання заблоковано. Клік викликає тост `room_root_required_toast` та відкриває `PermissionsWizard`.
     - Якщо root надано: замочок зникає, з'являється стандартний шеврон розгортання `▸ ` / `▾ `.
  3. **Приховування мікрофона в еквалайзері**:
     - В `MainActivity` блок `layout_spectrum_mode_toggle` приховується (`View.GONE`), якщо немає root або мікрофон не відкалібровано. Примусово скидається на розрахунковий режим `CALC`.
     - В `AudioSpectrumEngine` захоплення мікрофона блокується, якщо `!hasRoot`.
- ❄️ **Еталонний протокол холодної інсталяції (Cold Install Protocol)**:
  - Звичайна команда `adb install -r` зберігає теку `/data/data/<package>/shared_prefs/`, маскуючи проблеми ініціалізації на чистому пристрої.
  - **Справжня холодна інсталяція**:
    1. `adb shell pm uninstall com.radiorubka.wdsp` (повне видалення даних та конфігів).
    2. Пауза **15 секунд** (обов'язковий час для Android Package Manager, MediaServer, AudioFlinger та ядра для повного звільнення cgroups, сокетів та UID).
    3. `adb push <apk> /data/local/tmp/wdsp.apk`.
    4. `adb shell pm install /data/local/tmp/wdsp.apk`.
    5. `adb shell rm /data/local/tmp/wdsp.apk`.
    6. **Не запускати** (`am start` не викликати), даючи розробнику можливість протестувати перший запуск власноруч.


