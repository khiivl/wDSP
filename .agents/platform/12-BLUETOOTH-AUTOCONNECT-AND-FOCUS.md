> ✍️ **Written by Gemini/Antigravity**, session 60ce423d, 27.08.2026. Copied here from its own
> knowledge tree (`~/.gemini/config/skills/qf-platform-architecture/references/11-BLUETOOTH-AUTOCONNECT-AND-FOCUS-ARBITRATION.md`),
> renumbered to avoid a clash with 10-BITPERFECT-MODULE.md. Provenance marks are its own; treat
> anything unmarked as unverified until somebody measures it.

# 11. Механіка Bluetooth AutoConnect та арбітраж аудіофокусу на платформі QF

> ✍️ **Досліджено, декомпільовано та верифіковано**: **Antigravity (Gemini)** (24.08.2026)
> 🏷️ **Мітки провенансу знань**:
> - 🔬 **Read from firmware / decompiled code** (`QF_Bluetooth.apk` -> `TechBTService.java`, `TechBTSettingManager.java`, `TechBTAudioFocusManager.java`, `KeyUtils.java`, `GlobalTool.java`).
> - 📻 **Measured on the wire** (logcat `MediaFocusControl`, спостереження 40с циклу `pollConnectRun`, перемикання каналів MCU `[84 04 01][84 02 00]`).
> - 🧩 **Deduced / Conclusion** (механізм придушення захоплення аудіофокусу через системні пропи).

---

## 1. Причина 40-секундного спаму Bluetooth AutoConnect

У додатку `com.qf.bluetooth` (`QF_Bluetooth.apk`) реалізовано фоновий сервіс `BtMainService` та менеджер налаштувань `TechBTSettingManager`.

Коли в налаштуваннях увімкнено автопідключення Bluetooth (за замовчуванням `auto_connect = 1` або `persist.sys.qf.bt_auto_connect = 1`):
1. Якщо жоден телефон не підключений (`hfpConnectState == 0` та `a2dpConnectState == 0`), менеджер запускає періодичний таймер:
   ```java
   // com.qf.btsdk.manager.TechBTSettingManager
   private Runnable pollConnectRun = new Runnable() {
       @Override
       public void run() {
           if (TechBTSettingManager.this.isCarplayConnected()) {
               return;
           }
           if (TechBTSettingManager.this.getHfpConnectState() == 1 || 
               TechBTMusicManager.getInstance().getBtA2DPConnectState() == 1) {
               return;
           }
           TechBTSettingManager.this.mH.removeCallbacks(TechBTSettingManager.this.connectRun);
           TechBTSettingManager.this.connectRun.run();
           TechBTSettingManager.this.mH.postDelayed(TechBTSettingManager.this.pollConnectRun, 40000L); // ⏱️ Кожні 40 секунд
       }
   };
   ```
2. Кожні 40 секунд `connectRun` намагається відновити зв'язок з останньою відомою MAC-адресою телефону.
3. Під час спроби підключення стек Bluetooth тимчасово ініціює перевірку профілю A2DP (Bluetooth Music), що викликає перевірку стану відтворення в `TechBTService.java`.

---

## 2. Як `QF_Bluetooth` перевіряє право на захоплення аудіофокусу

🔬 *Декомпільовано безпосередньо з `com.qf.btsdk.service.TechBTService.java` (рядки 327–358):*

```java
private void requestAudioFocusWhenBTMusicInTop() {
    this.handler.removeCallbacks(this.delayPaseMusic);
    boolean z = false;
    this.checkMusicTimes = 0;
    boolean isAudioFocusBTMusic = TechBTAudioFocusManager.getInstance().isAudioFocusBTMusic();
    boolean isTopActivity = isTopActivity(GlobalTool.getInstance().getContext());
    boolean isBTMusicUIVisible = TechBTMusicManager.getInstance().isBTMusicUIVisible();
    
    // 🔑 ГОЛОВНИЙ ПЕРЕВІРОЧНИЙ ПРОП СИСТЕМИ:
    boolean equals = "nothing".equals(SystemProperties.get("sys.qf.last_audio_src"));
    
    if (isTopActivity && isBTMusicUIVisible && !isAudioFocusBTMusic) {
        TechBTAudioFocusManager.getInstance().requestBTMusicAudioFocus();
        z = true;
    }
    if (Build.VERSION.SDK_INT >= 31) {
        if (!isAudioFocusBTMusic && !TechBTAudioFocusManager.getInstance().getRealAudioBtMusicFocus() && !equals) {
            Log.d(TAG, "do not to play a2dp11 ");
            if (CheckingAirPlayHelper.getInstance().isAirPlayConnecting()) {
                return;
            }
            // 🛑 БЛОКУВАННЯ: Bluetooth бачить, що грає інше джерело, ТИСНЕ СОБІ ПАУЗУ і виходить!
            TechBTMusicManager.getInstance().pause();
            this.handler.postDelayed(this.delayPaseMusic, 500L);
            return;
        }
    }
}
```

---

## 3. Чому раніше звук радіо вирубався і як це виправлено

### ❌ Що відбувалося раніше (Симптом обриву на ~30-й секунді):
1. Додаток радіо не виставляв системний проп `sys.qf.last_audio_src` (або там було значення `"nothing"`).
2. Умова `equals = "nothing".equals(...)` ставала `true`.
3. `TechBTService` вважав, що в машині нічого не грає, і викликав `requestBTMusicAudioFocus()`:
   - Відправляв запит до `MediaFocusControl` у `services.jar`.
   - Фреймворк викликав `RPC_SetChannel(4)` (MPU/Media ON, Radio OFF).
   - Мікшер MCU вимикав FM-вхід (`Канал 2`), і звук радіо вирубався на ~30 секунд.

### ✅ Як працює повний захист зараз:
1. Під час відтворення радіо виставляються системні пропи:
   - `persist.sys.qf.last_audio_src = com.android.fmradio`
   - `sys.qf.last_audio_src = com.android.fmradio`
   - `sys.qf.radio.status = true`
   - `persist.sys.qf.radio.ext = true`
2. Коли таймер Bluetooth (40с) викликає `requestAudioFocusWhenBTMusicInTop()`:
   - Властивість `sys.qf.last_audio_src` містить `"com.android.fmradio"`.
   - Значення `equals` дорівнює `false`.
   - Сервіс Bluetooth потрапляє в гілку `!equals` $\rightarrow$ друкує в системний лог `do not to play a2dp11` $\rightarrow$ викликає внутрішній `pause()` і **ПОВНІСТЮ СКАСОВУЄ запит фокусу**.
3. Мікшер MCU **НЕ перемикається**, `Канал 2` залишається непорушним, відтворення радіо продовжується безперервно.

---

## 4. Матриця перевірки системних пропів у сервісах платформи

| Системний компонент | Проп, який перевіряється | Очікуване значення для Радіо | Що відбувається |
|---|---|---|---|
| `QF_Bluetooth` (`TechBTService`) | `sys.qf.last_audio_src` | `com.android.fmradio` | Bluetooth блокує сам себе (`do not to play a2dp11`) і не відбирає звук |
| `QF_Bluetooth` (`TechBTAudioFocusManager`) | `persist.sys.qf.last_audio_src` | `com.android.fmradio` | `isAudioFocusBTMusic()` повертає `false` |
| `QF_Canbus` (`SourceDistributor`) | `persist.sys.qf.last_audio_src` | `com.android.fmradio` | Виставляє `mSourceId = 4` (FM Radio) для приборної панелі авто |
| `QF_Canbus` (`KeyDistributor`) | `sys.qf.last_audio_src` | `com.android.fmradio` | Перенаправляє медіа-кнопки керма в радіо |
| `McuManagerService` | `sys.qf.radio.status` | `true` | Дозволяє пряму комутацію FM-тракту в MCU |
