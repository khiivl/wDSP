# HANDOFF — зріз wDSP (Пісочниця gemini_ui_dev)

**04.10.2026, ~23:25, сесія Antigravity (Gemini).** Файл перезаписується; старі зрізи не переносяться.

## Де все стоїть

- **Пісочниця:** `D:\gemini\wdsp_test`, гілка `gemini_ui_dev`.
- **Останні коміти:**
  - `77af1cb`: `mcu: таймаут callAnnounced і перемикання пресетів OnChange`
  - `0074760`: `docs: оновлено зріз HANDOFF після зведення джерела мікрофона до SSOT`
  - `60780d6`: `audio: централізація джерела мікрофона до SSOT UNPROCESSED`
  - `bfe03c8`: `audio: звільнення мікрофона на час дзвінків за контрактом BitPerfect`
  - `ff904b2`: `docs: створено розгорнутий звіт UPSTREAM_MERGE_REPORT`
- **Головний проект:** `C:\Users\kosty\AndroidStudioProjects\wDSP` — 🔴 НЕ ЧІПАЛИ, жодних git операцій!
- **Збірка:** `gradlew assembleDebug` успішна (70 tasks, BUILD SUCCESSFUL).

## ✅ Виконано

1. **Ліквідація зависання пресету Call та циклу опитування (коміт `77af1cb`)**:
   - `McuService`: додано сторожовий таймер `CALL_ANNOUNCED_WATCHDOG_MS = 6000` мс. Якщо прапорець `callAnnounced` встановлено через бродкаст `PHONE_CALL_START`, але системні властивості `sys.qf.call_state` та `sys.current.vol.type != btcall_type` не активні понад 6 с — прапорець автоматично скидається. Перевірено в живому лозі стенду (`Watchdog: callAnnounced timed out (6000 ms)... Clearing callAnnounced`).
   - Перемикання пресетів уніфіковано до чистого OnChange: `effectivePlayer = inCall ? "Call" : currentPlayer` зі строгою перевіркою `!Objects.equals(effectivePlayer, lastPlayerSource)`. Ліквідовано level-triggered спам `processPlayerSwitch("Call")`, через який пресет Call примусово перезаписував ручний вибір користувача в UI.
   - На `PHONE_CALL_START` та `PHONE_CALL_END` додано негайний `backgroundHandler.post(McuService.this::checkPlayer)`, що забезпечує миттєве відновлення користувацького пресету без затримки опитування. Перевірено на стенді: збережено та відновлено пресет `AutoEQ Harman (Центр)`.

2. **Централізація захоплення мікрофона в єдиний SSOT UNPROCESSED (коміт `60780d6`, дошка #1346/#1350/#1351)**:
   - `MicrophoneGuard`: оголошено єдине джерело `CAPTURE_AUDIO_SOURCE` (`UNPROCESSED` = 9), частоту 48000 Гц, моно, 16 біт. Фабрику `openCaptureRecord(int minBufferSize)` та `tryOpenCapture` уніфіковано із підтримкою `AudioRecord.Builder` та блокуванням при дзвінках `CallState.isActive()`.
   - `RadioMicCapture`, `LatencyProbe`, `McuService`: усі острівні виклики `AudioRecord` та приховані fallback переведено на єдиний канонічний метод.
   - Верифікація на стенді (повідомлення #1350, #1351): на тестовому стенді захоплення жиfilter rms 3000–8500 (~ −16 dBFS). Підтверджено підміну джерела 9 на 1 у прошивці QF по дорозі до HAL (`source:1`, `Music\Handsfree\Record`). Фіксація: −98 дБ на цьому HAL не відтворилося, причину первинного заниження не встановлено. Звільнення мікрофона на `PHONE_CALL_START` підтверджено.

## ⏳ У роботі

- Робота над гілкою `gemini_ui_dev` у повному обсязі верифікована.
- Очікування від сесії BitPerfect (`0c0b138e`) інформації щодо підміни джерела 9 на 1 у QF ROM.
- Передача результатів роботи сесії Claude wDSP на прийомку.

## 📋 План дій

1. Зберегти стан документації в git пісочниці.
2. Підтримувати координацію на Agent Bridge без порушення контрактів.
