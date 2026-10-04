# HANDOFF — зріз wDSP (Пісочниця gemini_ui_dev)

**05.10.2026, ~00:00, сесія Antigravity (Gemini).** Файл перезаписується; старі зрізи не переносяться.

## Де все стоїть

- **Пісочниця:** `D:\gemini\wdsp_test`, гілка `gemini_ui_dev`.
- **Останні коміти:**
  - `30860fc`: `audio: синхронне негайне звільнення AudioRecord при дзвінку` (виправлення P0 #1368)
  - `7ef2de8`: `docs: оновлено зріз HANDOFF після виправлення зависання пресету Call`
  - `77af1cb`: `mcu: таймаут callAnnounced і перемикання пресетів OnChange`
  - `60780d6`: `audio: централізація джерела мікрофона до SSOT UNPROCESSED`
  - `bfe03c8`: `audio: звільнення мікрофона на час дзвінків за контрактом BitPerfect`
- **Головний проект:** `C:\Users\kosty\AndroidStudioProjects\wDSP` — 🔴 НЕ ЧІПАЛИ, жодних git операцій!
- **Збірка:** `gradlew assembleDebug` успішна (70 tasks, BUILD SUCCESSFUL).
- **Стенд 192.168.1.140:9876:** PID 15355 активний, протестований, adb звільнено.

## ✅ Виконано

1. **Усунення дедлоку AGDSP при дзвінках (P0 #1368, коміт `30860fc`)**:
   - **Анатомія збою:** У бутлозі сесії Claude `0067/20_run.log` виявлено затримку 3.6 с між «releasing microphone» та «RadioMicCapture stopped». З'ясовано, що `AudioSpectrumEngine` спочатку очікував завершення потоку FFT (`analysis.join(200)`), а `RadioMicCapture.stop()` робив `t.join(300)` ДО виклику `releaseRecordLocked()`. Поки `captureThread` висів у нативному `AudioRecord.read()`, `rec.stop()` не викликався, і HAL паралельно отримував запис 48 кГц (`start_input_stream`) поверх голосового дзвінка (`start_voice_call`), викликаючи паніку AGDSP (`cmd:0x26 dsp timeout / dsp asserted`).
   - **Виправлення:**
     - `RadioMicCapture.stop()`: виклик `releaseRecordLocked()` (`rec.stop()` + `rec.release()`) перенесено на початок, ДО будь-яких `join()`. Тайм-аут скорочено до 50 мс.
     - `AudioSpectrumEngine.setCallActive(true)`: `stopRadioMicCapture(true)` перенесено на перше місце перед зупинкою аналізатора.
     - `CallState`: додано стан `callAnnounced` та метод `setCallAnnounced(boolean)`. Завдяки цьому `CallState.isActive()` стає `true` миттєво з приходом бродкасту `PHONE_CALL_START`.
     - `RoomMeasurement`: додано збереження `activeRecord` та миттєве закриття в `abort()`.
   - **Фінальне приймання (дошка #1377, сесія Claude wDSP)**:
     - Контрольний SIM-дзвінок на 1200 о 23:59:49: `RadioMicCapture stopped` відбувся за 274 мс ДО старту голосового тракту (`start_voice_call`).
     - Падінь AGDSP немає (`dsp timeout` відсутній), голосові набори `Audio\Handset\NB1 → WB1` завантажились успішно.
     - Пункт 0 у `TODO.md` закрито та видалено. З боку DSP/BitPerfect та сесії Claude wDSP роботу прийнято!

2. **Сторожовий таймер та OnChange перемикання пресетів (коміт `77af1cb`)**:
   - `CALL_ANNOUNCED_WATCHDOG_MS = 6000` мс скидає завислий прапорець за відсутності фізичного виклику.
   - `effectivePlayer = inCall ? "Call" : currentPlayer` з чистою умовою OnChange ліквідував спам-перезапис пресетів користувача. Перевірено на стенді з пресетом `AutoEQ Harman (Центр)`.

3. **Централізація захоплення мікрофона в SSOT UNPROCESSED (коміт `60780d6`)**:
   - Повне зведення `MicrophoneGuard` до UNPROCESSED (9) на 48 кГц моно 16-біт. Підтверджено підміну 9->1 у системній бібліотеці QF (сесія `0c0b138e`, дошка #1365).

## ⏳ У роботі

- Перехід до пункту 1 з `TODO.md` (узгодження назв блокування пар / огляд корекції 0.5 автора).

## 📋 План дій

1. Запропонувати власнику варіанти зрозумілих підписів блокування пар замість «Пара спереду разом / ззаду / Бас перед і зад разом».
2. Підтримувати координацію на Agent Bridge.
