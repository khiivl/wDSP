# wDSP Architecture & Developer Knowledge Base (kostyamat mod)

## 📌 Project Overview
- **Project**: wDSP (DSP / Sound Control for QF & K706 Head Units)
- **Target Platform**: UIS7862 / ums512 Android 10 (API 29) with QF framework
- **Repository**: [wDSP](https://github.com/khiivl/wDSP)
- **Branch**: `kostyfmat_mod`
- **Mod Version**: `0.4.2_kostyamat_mod`

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
  - **Fader & Balance & Loudness**: Command `0x82` (12 steps left/right, 12 steps front/rear).
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
- **TouchGlow Effect**: Interactive ripple and button flash on all interactive controls.

---

## 🎛️ Settings Accordion (`SettingsAccordion.java` & `SettingsActivity.java`)
Categorized accordion cards with fluid collapse/expand animations:
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
