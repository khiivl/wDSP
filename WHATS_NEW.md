# wDSP — Що нового / What's New (v0.4.8)

---

## 🇺🇦 Українська версія (Текст для Telegram)

🚗 **wDSP — Велике оновлення: новий скляний дизайн, розумний скрінсейвер та конструктор візуалізацій!**

Друзі, всім привіт! Радий представити вам масштабне оновлення нашого DSP-додатка. Я провів глибоку роботу над інтерфейсом, повністю переосмислив скрінсейвер та додав купу крутих можливостей, які роблять користування звуком в авто ще зручнішим, безпечнішим та приємнішим для очей. Більшість цих функцій ви побачите вперше — вони ще не виходили в публічні релізи!

Ось головне, що змінилося:

✨ **1. Новий скляний інтерфейс (Frosted Glass)**
- **Преміальний вигляд**: інтерфейс отримав плаваючі матові скляні доки з органічними контурами, які плавно огинають кнопки та списки без зайвих порожнеч.
- **Адаптація під будь-які панелі**: від компактних 7-дюймових екранів (1024×600) до вертикальних дисплеїв у стилі Tesla та режиму Split-Screen — верхній рядок пресетів отримав плавний скрол, тому жодна кнопка більше не зрізається краєм екрана.
- **Живі теми День/Ніч**: вмикання габаритів чи фар миттєво підлаштовує всі картки, діалоги, випадаючі списки та контрастність тексту на льоту, без перезапуску програми.
- **Зручна «Тонкомпенсація»**: об’єднав в одній вкладці корекцію кривої для FM-радіо та класичний Loudness із захистом від їхнього взаємного накладання.

🎛️ **2. Розумний інтерактивний скрінсейвер**
- **Миттєвий перехід у плеєр**: один тап по обкладинці треку або іконці плеєра в нижньому лівому кутку — і скрінсейвер згортається, відкриваючи головне вікно запущеного додатка (Spotify, YouTube Music, QF FM-радіо тощо).
- **Живі метадані треків**: повне відображення назви, виконавця, альбому, біжучого рядка для довгих назв, лінії прогресу відтворення та реальних обкладинок альбомів або частоти й RDS-тексту радіостанції.
- **Безпечні зони керування наосліп (50/50)**: екран поділено навпіл. Верхня половина — закриває скрінсейвер, а вся нижня половина — це зона керування музикою, яка **ніколи випадково не закриє екран** під час руху!
- **Широкі зони жестів**: простір під візуалізатором поділено на три великі тач-області («Попередній трек», «Плей/Пауза», «Наступний трек») із великими візуальними підтвердженнями (гліфами) по центру.
- **Швидка зміна стилів**: перемикайте анімацію візуалізатора прямо на скрінсейвері кнопкою з іконкою хвилі у правому нижньому кутку.
- **Невидимі крайові слайдери**: налаштовуйте вигляд екрана прямо на скрінсейвері жестами без входу в Налаштування.

🌈 **3. Конструктор візуалізацій та палітри з FireLamp**
- **Повноцінний редактор у Налаштуваннях**: налаштовуйте вигляд спектра з можливістю живого перегляду на скрінсейвері.
- **6 нових стилів візуалізації**:
  - *Classic Bars* — класичні чіткі смуги спектра.
  - *Outrun Peaks* — неонові піки, що динамічно підстрибують у такт басу.
  - *Center Bars* — симетричний розмах частот від центру до країв.
  - *VU-Meter* — плавні студійні індикатори рівня гучності.
  - *Осцилограф* — аналогова звукова хвиля з теплою інерцією та ефектом післясвітіння фосфору (CRT).
- **Багаті палітри FastLED**: Sunset Real, Ocean Breeze, Warm VU, Purple Synthwave, Rainbow Sherbet тощо. Я переніс їх зі свого власного проєкту світлових ефектів [FireLamp](https://github.com/kostyamat).

💾 **4. Резервне копіювання (Backup & Restore)**
- Збереження повної резервної копії всіх налаштувань, колірних тем та індивідуальних пресетів звуку у файл. Жодне оновлення прошивки чи магнітоли більше не зітре відбудовану звукову сцену!

🚗 **5. Розумна автогучність GALA**
- Повністю налагоджено алгоритм зміни гучності від швидкості: додаток більше не сперечається з водієм і коректно слідує за обертанням фізичної ручки гучності чи кнопок на кермі.

---

### 📖 Шпаргалка водія: як користуватися скрінсейвером

| Де торкатися / свайпати | Дія |
|---|---|
| **Верхня половина екрана** (тап) | Закрити скрінсейвер і повернутися до попереднього вікна. |
| **Нижня ліва область** (тап) | Попередній трек ⏮️ |
| **Нижня середня область** (тап) | Плей / Пауза ⏯️ |
| **Нижня права область** (тап) | Наступний трек ⏭️ |
| **Обкладинка / іконка внизу зліва** (тап) | Відкрити головне вікно активного плеєра / радіо 📱 |
| **Кнопка з хвилею внизу справа** (тап) | Перемкнути стиль візуалізатора (Classic, Peaks, VU, CRT тощо) 〰️ |
| **Верхній край** (свайп вниз/вгору) | Прозорість фонової підкладки (від матової до 100% чорного екрана для нічної траси) 🌑 |
| **Правий край** (свайп вгору/вниз) | Яскравість візуалізатора (окремо для Дня і Ночі) 💡 |
| **Лівий край** (свайп вгору/вниз) | Висота смуги візуалізатора 📏 |
| **Нижній край** (свайп вправо/вліво) | Ширина смуги візуалізатора 📐 |
| **Центр екрана** (свайп) | Вертикальний рух — висота, горизонтальний — ширина 🎚️ |

*Усі налаштування жестів зберігаються автоматично після відпускання пальця.*

...Скоро! 🚀

---

## 🇬🇧 English Version (Telegram Post Format)

🚗 **wDSP — Major Update: All-New Glass Design, Smart Screensaver & Visualizer Studio!**

Hey everyone! I’m excited to present a major update to our DSP audio suite. I’ve done a deep revamp of the UI, completely redesigned the screensaver experience, and packed in lots of great features to make sound management in your car cleaner, safer, and much more enjoyable. Most of these features are premiering here and haven't been available in earlier public releases!

Here’s the breakdown of what’s new:

✨ **1. Modern Frosted Glass Interface**
- **Premium Aesthetics**: Floating frosted-glass docks with organic contours that cleanly hug buttons and dropdowns without awkward gaps.
- **Adaptive Across All Car Panels**: Scaled for everything from classic 1024×600 screens to ultra-tall Tesla-style consoles and Android Split-Screen mode — presets row features smooth horizontal scrolling so nothing gets cut off.
- **Live Day & Night Theming**: Headlight switching smoothly transforms all cards, dialogs, dropdowns, and text contrast on the fly without restarting the app.
- **Unified Tone Compensation**: FM tuner curve correction and classic Loudness are now conveniently housed in a single tab with built-in conflict prevention.

🎛️ **2. Smart Interactive Screensaver**
- **One-Tap Jump to Active Player**: Tap directly on the album art or player icon in the bottom-left corner to immediately dismiss the screensaver and bring your active music player (Spotify, YouTube Music, QF FM Radio, etc.) to the front!
- **Rich Live Metadata**: Full display of track title, artist, album, scrolling marquee for longer names, live playback progress line, and genuine album artwork or radio station frequency and RDS text.
- **Accident-Proof Touch Zones (50/50 Split)**: The upper 50% closes the screensaver, while the entire lower 50% is a dedicated transport control zone that will **never accidentally dismiss** the screen while driving!
- **Blind-Friendly Gestures**: Tap anywhere across three spacious zones (Previous Track, Play/Pause, Next Track) with clear glyph confirmations right in the center.
- **On-the-Fly Visualizer Switcher**: Cycle through visualizer styles in real-time using the dedicated sine-wave button in the bottom-right corner.
- **Invisible Edge Sliders**: Adjust visual parameters directly on the screensaver with intuitive edge drags without opening Settings.

🌈 **3. Visualizer Studio & FireLamp Palettes**
- **Dedicated Studio in Settings**: Customize every visualizer effect with instant live preview right on the screensaver.
- **6 Distinct Visualizer Styles**:
  - *Classic Bars* — timeless solid spectrum bars.
  - *Outrun Peaks* — retro-futuristic floating peak caps bouncing to the beat.
  - *Center Bars* — symmetrical frequency spread expanding from the center.
  - *VU-Meter* — smooth vertical studio VU gradients.
  - *CRT Oscilloscope* — authentic analog audio wave with physical phosphor glow and beam decay inertia.
- **Rich FastLED Palettes**: Ported directly from my own LED matrix project [FireLamp](https://github.com/kostyamat) (Sunset Real, Ocean Breeze, Warm VU, Purple Synthwave, Rainbow Sherbet, and more).

💾 **4. One-Click Backup & Restore**
- Save a complete backup of your appearance settings, custom colors, and sound presets to a single file. Never worry about losing your acoustic tuning after system updates or firmware re-flashing!

🚗 **5. Refined Speed-Dependent Volume (GALA)**
- Smooth vehicle speed tracking that never fights your physical volume knob or steering wheel controls — it seamlessly respects manual adjustments.

---

### 📖 Driver’s Cheat Sheet: Screensaver Gestures & Controls

| Where to Tap / Drag | What Happens |
|---|---|
| **Upper 50% of the screen** (tap) | Dismiss screensaver and return to previous screen. |
| **Lower-left zone** (tap) | Previous track ⏮️ |
| **Lower-middle zone** (tap) | Play / Pause ⏯️ |
| **Lower-right zone** (tap) | Next track ⏭️ |
| **Album Art / Player Icon** (bottom-left tap) | Launch main window of active player / radio 📱 |
| **Wave Icon** (bottom-right tap) | Cycle visualizer style (Classic, Peaks, VU, CRT, etc.) 〰️ |
| **Top edge** (vertical drag) | Backdrop dimming / opacity (frosted glass down to 100% pitch black for nighttime highway driving) 🌑 |
| **Right edge** (vertical drag) | Visualizer brightness (saved independently for Day and Night) 💡 |
| **Left edge** (vertical drag) | Visualizer band height 📏 |
| **Bottom edge** (horizontal drag) | Visualizer band width 📐 |
| **Center screen** (drag) | Vertical drag scales height; horizontal drag scales width 🎚️ |

*All edge slider adjustments save automatically the moment you lift your finger.*

...Coming soon! 🚀
