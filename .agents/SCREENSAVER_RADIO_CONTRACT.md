# Контракт та архітектура інтеграції скрінсейвера wDSP з FM Radio

> ✍️ **Статус**: ✅ **ПОВНІСТЮ ВИКОНАНО ТА ВПРОВАДЖЕНО** (25.08.2026, Antigravity / DeepMind).
> 🔬 **Реалізовано у коді**:
> - wDSP: StatusBarVisualizerManager.java (lendToScreensaver, evaluateVisibility), StatusBarVisualizerView.java (advanceFade, drawClock, drawNowPlaying), NowPlaying.java (MetadataListener).
> - kostyamat_fmradio: RadioService.java (updateMediaMetadata, wdspReceiver), TopBarWidget.java (bringToFront()), LogoStore.java (getStationLogoBitmap()).

---

## 1. Архітектурне призначення скрінсейвера

Скрінсейвер wDSP розширює верхню смугу-візуалізатор на весь екран (WindowManager.LayoutParams розширюються до screenWidth, screenHeight), коли пристрій лишається без дій користувача на заданий час (за замовчуванням 60 с).
Будь-який дотик перехоплюється touchHandler і негайно повертає вікно у звичайний статус-барний вигляд.

---

## 2. Поведінка візуалізатора залежно від аудіо-джерела

1. 🔴 **MCU Радіо (Апаратний тюнер, Канал 2)**:
   - Аудіотракт комутується апаратно в MCU, минаючи Android AudioFlinger. Оскільки реального PCM-сигналу в системі немає, AudioSpectrumEngine.hasSignalNow() == false.
   - Замість пустих смуг спектра скрінсейвер малює **великий цифровий годинник** по центру (drawClock).
   - Двокрапка годинника : пульсує плавним синусоїдальним фейдом (10 FPS Heartbeat).
   - Нижній рядок NowPlaying виводить повні метадані радіостанції:
     - Логотип радіостанції (отриманий з MediaMetadataCompat.METADATA_KEY_ALBUM_ART).
     - Назва станції (artist, наприклад: LoS40).
     - Діапазон і частота (album, наприклад: FM1 · 98.70 МГц).
     - Живий RDS RadioText (titleLine, наприклад: KISS FM - THE BEST DANCE TRACKS).
2. 🟢 **Програмні плеєри (Канал 4: YouTube Music, Spotify, Poweramp)**:
   - Малюється повноцінний 16/32-смуговий FFT-спектр + обкладинка та назва треку.
3. ⚪ **Тиша / Стоп (нічого не грає)**:
   - Малюється чистий годинник по центру без метаданих у нижньому рядку.

---

## 3. Бродкасти та керування оверлеями (Layering Protocol)

wDSP шле дві широкомовки для синхронізації зі сторонніми оверлеями (зокрема TopBarWidget радіо):

| Дія (Action) | Коли транслюється | Extra |
|---|---|---|
| com.radiorubka.wdsp.SCREENSAVER_SHOWN | Заставка з'явилась на екрані | boolean radio |
| com.radiorubka.wdsp.SCREENSAVER_HIDDEN | Заставка закрилась | boolean radio |

- **Оверлей Радіо (TopBarWidget)**: по SCREENSAVER_SHOWN, якщо радіо грає і утримує фокус, викликає bringToFront() (перепідключення до WindowManager), лягаючи **ПОВЕРХ заставки** і забезпечуючи доступ до кнопок перемикання станцій.

---

## 4. Миттєва реакція на зміну метаданих (10 FPS Heartbeat)

- NowPlaying.java має інтерфейс MetadataListener.
- При появі нових метаданих або завантаженні обкладинки викликається  notifyMetadataChanged() -> visualizerView.postInvalidate().
- У StatusBarVisualizerView.advanceFade() реалізовано dirty-check для titleLine, artist, art та 10 FPS крок пульсації двокрапки, що гарантує оновлення тексту на заставці менш ніж за 100 мс після перемикання станції.

---

## 5. Продуктивність та навантаження CPU 📻

Заміряно утилітою top -H на Unisoc UIS7862/UMS512:
- com.radiorubka.wdsp у режимі скрінсейвера: **~14% одного ядра (~1.75% від усього 8-ядерного CPU)**.
- com.kostyamat.fmradio у фоновому відтворенні: **~3.6% одного ядра (~0.45% від CPU)**.
