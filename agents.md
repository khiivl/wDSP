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


