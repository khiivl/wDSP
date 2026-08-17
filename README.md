<img width="1280" height="646" alt="wDSP_repo" src="https://github.com/user-attachments/assets/13869fb3-748f-4d1f-aa26-e6bf8a090b9e" />

----------------------------------------------------------------------------------------------------------------------

This is a free and open-source DSP app designed to fully replace the stock DSP app on K706/QF Android head units. 

The app communicates with the MCU using the framework, so it doesn't require rooting the device.
Compatible with vertical and horizontal head units.

Telegram group for discussion: https://t.me/wDSPapp

If you want to buy me a coffee or otherwise support me financially, use this link: https://buymeacoffee.com/radiorubka or this link: https://paypal.me/wDSPApp

----------------------------------------------------------------------------------------------------------------------

### ✨ Features (kostyamat mod v0.4.2):

#### 🎛️ Equalization & Sound Tuning:
- **True-to-hardware 16-band EQ** with 2dB step (-12dB .. +12dB), precise Q-factor controls (2.2 / 4.7) per band.
- **Subwoofer Control**: Discrete crossover frequency selection (25 Hz to 250 Hz) and independent Subwoofer Gain (+0dB to +12dB).
- **Loudness & Dynamic Fletcher-Munson (ISO 226)**: Realistic low-volume loudness curve with automatic Subwoofer low-frequency compensation.
- **Bass Filters & Bass Boost**: Front and rear independent high-pass filters and bass enhancement with accurate cutoff frequencies.
- **Power Volume Amp Control**: Hardware pre-amplifier gain adjustment.

#### 🚗 Time Alignment & Spatial Balance:
- **Speaker Fader & Balance**: Precision 4-channel balance with intuitive cabriolet acoustic diagram and hollow quick-tap speaker buttons.
- **Positioning Delays**: 0.5 ms fine adjustment step (up to 5.0 ms) for FL, FR, RL, RR, and Subwoofer (calculated in ms and cm).
- **Surround / Haas Effect**: Up to 10 ms delay and RSSE surround widening.
- **Speed-Compensated Volume (GALA)**: Adaptive speed volume control with min speed, increment, maximum adjustment, and smooth fade/hold timings.

#### 🌈 Visualizers & Spectrum Analysis:
- **Physical Optical Dispersion Spectrum**: 16 frequency bands mapped to true physical optical wavelengths (700 nm Deep Red at 20 Hz down to 390 nm Pure Deep Violet at 20 kHz).
- **Real-time Live Audio Spectrum Analyzer**: Smooth peak decay, rounded capsules, and dynamic height scaling.
- **Dynamic Fletcher-Munson Response Curve**: Live gradient visualizer showing real-time acoustic loudness compensation.
- **Status Bar Overlay Visualizer**: Highly customizable top status bar visualizer (width %, center position %, hue angle rotation, and presets: Physical Spectrum, EQ Groups, Auto Day/Night, Fire, Neon, Monochrome).

#### 🎨 Modern Design & Theme Engine:
- **Theme Modes**: Day, Night, and Auto (automatic switching with car illumination).
- **Custom Color Palette**: 4 interactive circular Hue Wheels with brightness sliders (Accent, Primary Text, Secondary Labels, On-Accent).
- **Wallpaper System**: Built-in dark/light background textures, custom user photo picker via SAF, or procedural solid color generator.
- **Animated Accordion Settings**: Smooth collapsible/expandable cards in Settings with interactive touch-glow effects.

#### 💾 Presets, Full Backup & Restore:
- **Preset Management**: Create, duplicate, rename, auto-switch based on audio source (Media, Radio, AUX, BT Calls), and export/import individual presets (.json).
- **Full Settings Backup & Restore**: One-click export/import of all appearance preferences, theme colors, wallpapers, visualizer configurations, and complete preset databases.
- **Robust Persistence**: Key prefix normalization on import and auto-save state flushing.

#### 🌍 Multi-language Localization:
- Complete support for 30 languages with fully spelled-out headers and labels across all screens.

----------------------------------------------------------------------------------------------------------------------

### 📖 How to use:

1. Install, launch, grant required permissions (Audio Record, GPS Location, Battery Optimization), and add to the sleep whitelist in system factory settings (password `8888`).
2. Do not use the stock DSP app, as launching it may overwrite hardware registers with stock settings.
3. If you want to disable the stock DSP app, run:
   ```bash
   adb shell pm disable com.qf.soundeffect
   ```
   To enable it back:
   ```bash
   adb shell pm enable com.qf.soundeffect
   ```
4. *(Root only)* You can install `wDSP-Proxy` as an update to the stock DSP app to open wDSP from the system quick settings panel. (Requires `PMPatch3` Magisk module).

----------------------------------------------------------------------------------------------------------------------

Written in Java, targeting Android 10 (API 29). Reverse-engineered proprietary MCU communication protocol via reflections.

Licensed under **GPLv3**.
