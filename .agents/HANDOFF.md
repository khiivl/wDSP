# HANDOFF — зріз wDSP (Пісочниця gemini_ui_dev)

**04.10.2026, ~23:00, сесія Antigravity (Gemini).** Файл перезаписується; старі зрізи не переносяться.

## Де все стоїть

- **Пісочниця:** `D:\gemini\wdsp_test`, гілка `gemini_ui_dev`.
- **Останні коміти:**
  - `60780d6`: `audio: централізація джерела мікрофона до SSOT UNPROCESSED`
  - `6c3a6f8`: `docs: оновлено зріз HANDOFF після закриття дефекту мікрофона`
  - `bfe03c8`: `audio: звільнення мікрофона на час дзвінків за контрактом BitPerfect`
  - `ff904b2`: `docs: створено розгорнутий звіт UPSTREAM_MERGE_REPORT`
  - `45a3f74`: `res: повна синхронізація перекладів у 28 локалях`
- **Головний проект:** `C:\Users\kosty\AndroidStudioProjects\wDSP` — 🔴 НЕ ЧІПАЛИ, жодних git операцій!
- **Збірка:** `gradlew assembleDebug` успішна (70 tasks, BUILD SUCCESSFUL).

## ✅ Виконано

1. **Централізація захоплення мікрофона в єдиний SSOT (завдання #1346/#1348, TODO 0а)**:
   - `MicrophoneGuard`: визначено константи `CAPTURE_AUDIO_SOURCE` (`UNPROCESSED` = 9), `SAMPLE_RATE` (48000), `CHANNEL_CONFIG` (`CHANNEL_IN_MONO`), `AUDIO_FORMAT` (`ENCODING_PCM_16BIT`). Фабрику `openCaptureRecord(int minBufferSize)` та `tryOpenCapture` уніфіковано із підтримкою `AudioRecord.Builder` та захистом від дзвінків `CallState.isActive()`.
   - `RadioMicCapture`: ліквідовано острівне створення `AudioRecord` та прихований fallback на `DEFAULT` (`MIC`). Захоплення делеговано до `MicrophoneGuard.openCaptureRecord(bufferSize)`.
   - `LatencyProbe`: `MicWatcher` переведено на канонічну фабрику `MicrophoneGuard.openCaptureRecord()`.
   - `McuService`: дефолтне джерело `PROBE_MIC` переведено на `MicrophoneGuard.CAPTURE_AUDIO_SOURCE`.
   - `AudioSpectrumEngine`: придушено Toast під час телефонних викликів.
2. **Контракт BitPerfect (пункт 7, P0 #1323) — звільнення мікрофона під час дзвінка**:
   - `CallState`, `AudioSpectrumEngine`, `McuService`: верифіковано на стенді (`bfe03c8`), дзвінки на 1200 успішні, DSP живий, звук є.

## ⏳ У роботі

- Координація та верифікація на стенді через Agent Bridge.
- Очікування від сесії `0c0b138e` розкопки ремапінгу джерела 9 у прошивці QF (#1347/#1348).

## 📋 План дій

1. Скоординувати сесію на дошці Agent Bridge щодо adb для тестування APK.
2. Зняти вихідні параметри: `dumpsys media.audio_policy` та логи HAL.
