> ✍️ **Written by Gemini/Antigravity**, session 60ce423d, 27.08.2026. Copied here from its own
> knowledge tree (`~/.gemini/config/skills/qf-platform-architecture/references/10-AUDIO-TRACT-AND-TUNER-CHIPS.md`),
> renumbered to avoid a clash with 10-BITPERFECT-MODULE.md. Provenance marks are its own; treat
> anything unmarked as unverified until somebody measures it.

# 10. Архітектура аудіотракту, тюнерів, SmartRDS та бази OpenRadioFM на платформі QF

> ✍️ **Досліджено, декомпільовано та верифіковано**: **Antigravity (Gemini)** (24.08.2026)
> 🏷️ **Мітки провенансу знань**:
> - 🔬 **Read from firmware / decompiled code** (`QF_Framework.apk`, `McuManagerService`, `VolumeManager`, `VolumeState`, `QF_CarSettings`, `mcudecomplied.c`, `QFTunerManager`).
> - 📻 **Measured on the wire** (UART-кадри `0x80..0x8C`, `0xA0..0xA1`, `0xB0..0xB8`, adb dumpsys audio / getprop).
> - 🧩 **Deduced / Conclusion** (архітектурні висновки та закони платформи).

---

## 1. Загальна матриця комутації звуку (Audio Routing Matrix)

Платформа QF (QF01/QF03/QF05, K706, UIS7862/UIS8581) побудована за архітектурою **подвійного комп'ютера**:
1. **Android SoC (MPU)**: обробляє користувацький інтерфейс, додатки, навігацію та програмне аудіо (Android `AudioFlinger`, `AudioTrack`, `STREAM_MUSIC`).
2. **MCU (Cortex-M)** + **Аудіопроцесор DSP** (ROHM BU32107EFV-M або Asahi Kasei AK7738 / AK7604): здійснюють фізичну апаратну комутацію аналогових/цифрових аудіовходів, регулювання атенюаторів/підсилювачів, 16-смуговий еквалайзер та затримки.

```
       ┌────────────────────────┐
       │   Android SoC (MPU)    │
       │ (Media Players, Nav)   │
       └───────────┬────────────┘
                   │ I2S / PCM (Канал 4)
                   ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│  AUX In      │ │ FM Tuner DAC │ │  Bluetooth   │ │ Navigation   │
│ (Канал 1)    │ │ (Канал 2)    │ │  (Канал 5)   │ │ (Канал 3)    │
└──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └──────┬───────┘
       │                │                │                │
       └────────────────┼────────────────┼────────────────┘
                        ▼                ▼
       ┌────────────────────────────────────────────────────────┐
       │       Апаратний DSP / Мікшер (BU32107 / AK7738)        │
       │       Керується MCU через I2C (UART 0x84 / 0x80)       │
       └────────────────────────┬───────────────────────────────┘
                                ▼
                   ┌────────────────────────┐
                   │  Підсилювач (PowerAmp) │
                   │  Динаміки автомобіля   │
                   └────────────────────────┘
```

### Фізичні канали мікшера MCU (`AUDIO_CHANNEL_*`):
- `Канал 1` (`AUDIO_CHANNEL_AUX`): лінійний вхід AUX.
- `Канал 2` (`AUDIO_CHANNEL_FM`): прямий аналоговий/I2S вихід апаратного FM/AM тюнера (TDA7708 / TEF6686). **Повністю оминає Android AudioFlinger**.
- `Канал 3` (`AUDIO_CHANNEL_NAVI`): вхід мікшування голосових підказок навігації (Duck / Remix over Radio/AUX).
- `Канал 4` (`AUDIO_CHANNEL_MPU`): цифровий вихід I2S від Android SoC (усі стандартні Android плеєри, YouTube, Spotify).
- `Канал 5` (`AUDIO_CHANNEL_BT_CALL`): апаратний телефонний тракт Bluetooth модуля.

---

## 2. Дворівнева архітектура гучності: `sys.*` проти `persist.*`

🔬 *Досліджено в `android.qf.os.VolumeState`, `android.qf.os.VolumeManager` та `com.qf.framework.mcu.McuManagerService`.*

Платформа QF **не використовує єдину глобальну шкалу гучності**. Замість цього реалізовано незалежне збереження рівня для кожного джерела звуку:

| Джерело (`sys.current.vol.type`) | Живий стан (Live Runtime) | Mute стан | Збережене налаштування Flash (`persist.*`) |
|---|---|---|---|
| `media_type` (Android плеєри) | `sys.media.vol` (0..32) | `sys.media.mute.state` | `persist.sys.main_volume` (дефолт 12) / `persist.sys.music_volume` |
| `radio_type` (FM тюнер MCU) | `sys.radio.vol` (0..32) | `sys.radio.mute.state` | `persist.sys.radio_volume` (дефолт 12) |
| `btcall_type` (BT дзвінки) | `sys.call.vol` (0..32) | `sys.call.mute.state` | `persist.sys.phone_volume` (дефолт 12) |
| `aux_type` (AUX вхід) | `sys.aux.vol` (0..32) | `sys.aux.mute.state` | `persist.sys.aux_volume` (дефолт 12) |

### 🔑 Закони роботи з гучністю на платформі QF:

1. **`persist.*` належить користувачу**:
   - `persist.sys.main_volume` та подібні записуються заводськими налаштуваннями (`QF_CarSettings` / `VolumeSetFragment`).
   - 🔴 **ВИНЯТОК, знайдений 31.08.2026 — `persist.sys.radio_volume` НЕ належить лише їм.** У декомпіляті
     `McuManagerService.java:1536-1544` видно: поки активне джерело `radio_type`, фреймворк **сам,
     безумовно перезаписує** цей проп на **кожне** натискання клавіш гучності `293`/`294`. Тобто після
     будь-якого кручення ручки на FM там лежить останній живий рівень, а не налаштування людини.
     Читати його як «що обрав користувач» більше не можна, і «лагодити» зворотним записом теж — наступне
     натискання перезапише знову. Деталі й наслідки — `08-VOLUME-AND-SOURCES.md` §2.
   - Додатки (Радіо, Плеєри) **КАТЕГОРИЧНО НЕ ПОВИННІ** затирати `persist.*` пропи під час нормальної роботи, паузи чи перемикання джерел.

2. **Синхронізація ВИКЛЮЧНО між музичними джерелами (`radio_type` $\leftrightarrow$ `media_type`)**:
   - Радіо та Android-плеєри (Spotify, YouTube Music тощо) синхронізуються 1:1, забезпечуючи безшовний перехід без перепадів рівня звуку.
   - **Повна ізоляція Дзвінків (`btcall_type`) та AUX (`aux_type`)**: телефонні розмови (`sys.call.vol`) та зовнішній AUX (`sys.aux.vol`) мають власні ізольовані рівні. Регулювання звуку під час телефонного дзвінка (наприклад, підйом до 24) або AUX ніколи не чіпає гучність радіо чи медіаплеєрів!

3. **Слухати ТІЛЬКИ `com.qf.action.VOLUME_CHANGED`**:
   - Додаток радіо слухає **виключно апаратну подію `com.qf.action.VOLUME_CHANGED`** від MCU/фреймворку (коли водій повертає фізичний енкодер або тисне кнопки керма).
   - ⛔ **Заборона `VOLUME_CHANGED_ACTION`**: слухати або мапити `android.media.VOLUME_CHANGED_ACTION` **категорично заборонено**. Потоки Android `STREAM_MUSIC` (0..15) система регулює внутрішньо при мікшуванні/дакінгу навігації (`FLAG_NAVI 7419`). Перехоплення цього бродкасту створює колізії з навігацією.

4. **Критична черговість при перемиканні каналів (`RPC_SetChannel`)**:
   - У `McuManagerService.RPC_SetChannel(byte mode)` система миттєво зчитує `currentVolumeState.getVolumeVal()` (відповідний `sys.*.vol`) і передає його в апаратний пакет перемикання каналу:
     `byte[] bArr = {checkChannelMode, (byte) getMappingVolume(currentVolumeState.getVolumeVal())};`
     `mcuProtocol.getSoundEffectSender().requestAudioChannelSwitch(bArr);`
   - **Залізне правило синхронізації**: Оновлювати `sys.radio.vol` або `sys.media.vol` необхідно **ДО** виклику `RPC_SetChannel`! Якщо викликати `setChannel` до оновлення пропа, MCU перемкне тракт на застарілу гучність.

---

## 3. Чіпи FM/AM тюнерів та взаємодія з MCU

🔬 *Досліджено в `mcudecomplied.c` (`FUN_08008408`), `TunerCmdFactory.java`, `QFTunerManager.java`.*

На платформі QF використовуються DSP-тюнери: **STMicroelectronics TDA7708** (найчастіше), **NXP TEF6686** або **Silicon Labs Si4755**.

### Схема підключення тюнера:
- Тюнер підключений по цифровій шині **I2C до мікроконтролера MCU**, а не до процесора Android!
- Процесор Android не має прямого доступу до регістрів I2C тюнера і спілкується з ним **виключно через пакети UART** до `McuManagerService`.

### Ключові команди протоколу тюнера (`0xA0` / `-96`):
- **Встановлення частоти (`0x00`)**: `{-96, 0, freq_high, freq_low}` або `tuneExt` з явною передачею `Area` та `Band`.
- **Пошук Seek (`0x01` / `0x02`)**:
  - ⚠️ **Інверсія напрямку в прошивці MCU**: команда `{-96, 2, 0, 0}` шукає **назад/вниз**, команда `{-96, 1, 0, 0}` шукає **вперед/вгору**.
- **Поріг чутливості LOC/DX (`0x07` та Raw 0x8C / 0x89)**:
  - Команда `0x8C` передає 5 байт порогів DSP тюнера в мікроконтролер.
  - Оскільки системний міст UART не пропускає сирі RSSI/SNR в Android, якість сигналу оцінює сам DSP тюнера на базі цих порогів.
- **RDS дані (`0xB0..0xB8`)**:
  - `0xB7` — пакет Radio Text (RT). Шлеться кожні 200-400 мс, вимагає дедуплікації та заміни `0x00` на пробіли.
  - `0xB8` / `0xB1` — Program Service (PS) назва станції.
  - 🔬 **Ізоляція PI коду**: MCU зчитує RDS PI код для внутрішнього алгоритму AF (Alternative Frequency), але **НЕ транслює PI код назовні в Android**.

---

## 4. Архітектура SmartRDS та бази OpenRadioFM (ORFM)

> [!IMPORTANT]
> ### 🛡️ Залізне правило ізоляції: Радіо vs SmartRDS
> - **Радіо (`kostyamat_fmradio`)**:
>   - Швидкий, ультра-стабільний аудіоплеєр.
>   - Працює автономно без інтернету і без root.
>   - **Категорично заборонено** сканувати файлову систему або ходити в мережу під час відтворення.
>   - Читає логотипи лише з:
>     1. Папок користувача (`PrefsStore.getLogoPackPaths()`).
>     2. Локального кешу SmartRDS (`/sdcard/QFRadio/RadioLogos/`).
> - **SmartRDS (`com.kostyamat.smartrds`)**:
>   - Автономний бекграунд-сервіс гео-збагачення метаданих.
>   - Працює за протоколом IPC / Broadcast (`ACTION_STATION_ENRICHED`).
>   - Володіє локальною базою SQLite / Supabase OpenRadioFM (ORFM).

```
┌────────────────────────────────────────────────────────┐
│              Радіо (kostyamat_fmradio)                 │
│  - Налаштування на частоту (напр. 100.4 MHz)           │
│  - Збір сирих RDS PS/RT пакетів від MCU                │
│  - Точковий IPC-запит з GPS + частотою (Trip Mode)     │
└───────────────────────────┬────────────────────────────┘
                            │ IPC / Broadcast
                            ▼
┌────────────────────────────────────────────────────────┐
│             SmartRDS (Сервіс гео-збагачення)           │
│  1. Отримує: Freq (100.4), GPS (Lat, Lon), PS ("AUTO") │
│  2. Запит у базу OpenRadioFM (ORFM SQLite / Supabase): │
│     - Пошук станцій у радіусі 50–80 км                 │
│     - Резолв назви та завантаження логотипу            │
│  3. Збереження логотипу в /sdcard/QFRadio/RadioLogos/  │
│  4. Відправка результату назад у Радіо                 │
└───────────────────────────┬────────────────────────────┘
                            │ ACTION_STATION_ENRICHED
                            ▼
┌────────────────────────────────────────────────────────┐
│              Радіо (kostyamat_fmradio)                 │
│  - Миттєве оновлення слота: назва + іконка             │
│  - Трансляція в MediaSession та віджети автомобіля     │
└────────────────────────────────────────────────────────┘
```

### Структура бази даних OpenRadioFM (ORFM):
```sql
CREATE TABLE orfm_stations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    frequency_khz INTEGER NOT NULL,      -- наприклад, 100400 (100.4 MHz)
    station_name TEXT NOT NULL,          -- "Авторадіо", "Hit FM"
    country_code TEXT NOT NULL,          -- "UA", "PL", "DE"
    city TEXT,                           -- "Київ", "Варшава"
    latitude REAL NOT NULL,              -- 50.4501
    longitude REAL REAL NOT NULL,        -- 30.5234
    coverage_radius_km INTEGER DEFAULT 60,
    pi_code INTEGER,                     -- RDS PI (на майбутнє після патчу MCU)
    logo_filename TEXT,                  -- "hit_fm.png"
    logo_url TEXT,                       -- хмарне джерело Supabase
    updated_at INTEGER
);
```

### Алгоритм гео-резолву (Geo-Fencing Resolver):
1. **Формула гаверсинуса (Haversine)** обчислює відстань між координатами автомобіля $(lat_1, lon_1)$ та передавачем $(lat_2, lon_2)$:
   $$d = 2R \arcsin\left(\sqrt{\sin^2\left(\frac{\Delta lat}{2}\right)} + \cos(lat_1)\cos(lat_2)\sin^2\left(\frac{\Delta lon}{2}\right)}\right)$$
2. Фільтруються всі передавачі з $d \le \text{coverage\_radius\_km}$ (дефолт 60–80 км) на поточній частоті.
3. Якщо знайдено кілька збігів, використовується PS-ранжувальник (`RdsRanker` / Levenshtein distance) для зіставлення з рядками з MCU.
4. При точному збігу станція отримує статус **«Бінго»** і закріплюється без відкриття зайвих діалогів введення.

---

## 5. Пам'ятка для наступних агентів (Claude Handover)

1. **Ніколи не повертати прямий запис у `persist.sys.*` з плеєра**: Усі перемикання гучності здійснюються виключно через `sys.radio.vol`, `sys.media.vol` та `sys.current.vol.type`.
2. **Синхронізація тільки між музикою (Radio <-> Media)**: Дзвінки (`btcall_type`) та AUX (`aux_type`) повністю ізольовані.
3. **Слухати ТІЛЬКИ `com.qf.action.VOLUME_CHANGED`**: Не підписуватися на `VOLUME_CHANGED_ACTION`, щоб не конфліктувати з системним аудіотрактом і мікшуванням навігації.
4. **Зберігати черговість `confirmAudioChannel` та `releaseAudioTract`**: Завжди підтягувати пропи гучності **перед** відправкою виклику `RPC_SetChannel` в MCU.
5. **Платформа QF — це НЕ FYT**: Жодних пакетів `com.syu.*` чи сервісів FYT. Тільки `android.qf.*`, `com.qf.*`, `IMcuManager` (транзакція 1001), та `AudioTrack` PCM-тиша.
