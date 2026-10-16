# 🔬 Дослідження Sleep Whitelist у прошивці QF (Haiwai / K706 / QF05)

> ✍️ **Автор**: Antigravity (Gemini)  
> 📅 **Дата дослідження**: 15.09.2026  
> 🎯 **Цільова платформа**: Haiwai / Jitu2 QF05 / K706 (Android 10, UIS7862, `192.168.1.146:9876`)  
> 📖 **Досліджені джерела**:
> - Системний фреймворк: `QF_Framework.jar` / `services.jar` / `sources/android/qf/os/QFSleepWakeup.java`
> - Заводські налаштування: `QF_CarSettings.apk` (`com.qf.carsettings`)
> - Конфігураційні сервіси: `IConfigInfoManager`, `IUtilEventManager`, `IMcuManager`, `QFApi`, `QFConfig`
> - Виміри на реальному залізі: перевірка прав та доступу файлової системи через ADB

---

## 1. Резюме для розробника (Executive Summary)

1. **Можливість програмної перевірки non-system додатком**:
   - ❌ **НЕДОСТУПНО**. Сторонній додаток без root **НЕ МОЖЕ** перевірити свій статус у `sleep_whitelist`.
   - Файл `/great/sleep/sleep_whitelist` має права `0600` (`-rw------- system:system`), а тека `/great/sleep` — права `0700` (`drwx------ system:system`). Будь-яка спроба читання повертає `EACCES (Permission denied)`.
   - Жоден системний Binder-сервіс, ContentProvider чи SystemProperties платформи QF не транслює стан цього білого списку назовні.
2. **Прямий виклик екрана налаштування в 1 дотик**:
   - ✅ **ПОВНІСТЮ ВІДКРИТИЙ ТА ДОСТУПНИЙ**.
   - Activity: `com.qf.carsettings.activity.FactorySleepWhiteListActivity`
   - Пакет: `com.qf.carsettings`
   - Прапорець `android:exported="true"`, спеціальних системних дозволів (`permission`) не вимагає, пароль заводських налаштувань (`8888` / `11223344`) при прямому виклику **не запитується**.

---

## 2. Факт 1: Доступність та механізм перевірки для non-system додатка

### 2.1. Апаратні права файлової системи (виміряно на залізі)
```bash
ls -ld /great /great/sleep /great/sleep/sleep_whitelist
drwxrwx--x 19 system system 4096 /great
drwx------  2 system system 4096 /great/sleep
-rw-------  1 system system   94 /great/sleep/sleep_whitelist
```
* Тека `/great/sleep` захищена режимом `0700` (читання/виконання дозволено тільки `system` UID 1000).
* Спроба виконання читання від імені звичайного застосунку (UID `10xxx`):
  ```bash
  su 10000 -c 'cat /great/sleep/sleep_whitelist'
  cat: /great/sleep/sleep_whitelist: Permission denied
  ```

### 2.2. Аудит системних сервісів та фреймворку
Було проведено повний аудит системного коду платформенних компонентів:
* 🔬 **`android.qf.os.QFSleepWakeup.java`**:
  - Рядки `50–51`:
    ```java
    private static final String NOT_KILL_APPS_CONFIG = "/system/config/NotKillAppsBeforeSleep.ini";
    private static final String NOT_KILL_APPS_CONFIG_3PARTY = "/great/sleep/sleep_whitelist";
    ```
  - Метод `readNotKillAppsConfig()` (рядки `109–159`) зчитує обидва файли у приватний список `mNotKillApps`.
  - Метод `isNotKillApp(String str)` (рядки `195–206`) перевіряє ім'я пакета/процесу виключно локально всередині процесу `system_server` при виконанні процедури `killAppsBeforeSleep(Context context)` (рядки `319–357`).
* 🔬 **Системні сервіси QF (`IMcuManager`, `IUtilEventManager`, `IConfigInfoManager`, `IQFFramework`)**:
  - Жоден інтерфейс AIDL не містить методів перевірки або отримання вмісту `sleep_whitelist`.
* 🔬 **Провайдери та властивості**:
  - `Settings.System`, `Settings.Global`, `Settings.Secure` не дублюють цей список.
  - `SystemProperties` не містить властивостей зі списком пакетів сну.
  - Жоден `ContentProvider` у системі не надає доступу до `/great/sleep/`.

### 🧩 Висновок щодо перевірки:
Звичайний додаток (без прав root) не має жодної можливості дізнатися, чи доданий він користувачем у білий список сну. Будь-який UI онбордингу чи налаштувань повинен пропонувати кнопку переходу до меню як рекомендаційну дію (наприклад: *«Перейти до налаштувань сну»*).

---

## 3. Факт 2: Intent та Activity для відкриття екрана списку сну

### 3.1. Декларація у маніфесті (`QF_CarSettings`)
Файл: `d:\De-compiled\QF_CarSettingsesources\AndroidManifest.xml` (рядки `133–140`):
```xml
<activity
    android:name="com.qf.carsettings.activity.FactorySleepWhiteListActivity"
    android:exported="true"
    android:excludeFromRecents="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
    </intent-filter>
</activity>
```

### 3.2. Логіка роботи Activity
Файл: `d:\De-compiled\QF_CarSettings\sources\com\qf\carsettingsctivity\FactorySleepWhiteListActivity.java`:
* Успадковано від `BaseActivity` (не містить перевірки паролів чи системних прав у `onCreate`).
* У методі `initData()` (рядки `34–64`):
  - Отримує список усіх сторонніх встановлених додатків через `Tools.getAllThirdApps(this)`.
  - Зчитує поточний вміст `/great/sleep/sleep_whitelist` через `FileOperationUtils.readData(...)`.
  - Відмічає чекбокси для пакетів, що вже є у списку.
  - Відображає інтерактивний `ListView` із чекбоксами для користувача.
* У методі `onStop()` (рядки `67–88`):
  - Збирає всі обрані користувачем пакети і зберігає їх безпосередньо у `/great/sleep/sleep_whitelist` (оскільки `QF_CarSettings` працює з `android:sharedUserId="android.uid.system"`).

### 3.3. Готовий код запуску з додатка (Java / Kotlin)

```java
public static boolean openSleepWhiteListSettings(Context context) {
    try {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
            "com.qf.carsettings",
            "com.qf.carsettings.activity.FactorySleepWhiteListActivity"
        ));
        intent.setAction(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    } catch (ActivityNotFoundException | SecurityException e) {
        Log.e("SleepWhitelist", "Failed to launch FactorySleepWhiteListActivity", e);
        return false;
    }
}
```

---

## 4. Додаткові відомості (Супутні білі списки)

У `QF_CarSettings` також присутнє супутнє Activity для білого списку фонової роботи:
* **Клас**: `com.qf.carsettings.activity.FactoryBackgroundWhiteListActivity`
* **Файл конфігурації**: `/great/protect_dir/bg_white_list.config`
* **Призначення**: Захист від фонового вивантаження під час звичайної роботи системи (не під час сну).
* **Експортованість**: `android:exported="true"`.

---

## Перевірка Claude (15.09.2026, апарат власника, лише читання)
- ✅ `ls -ld` через `su`: `/great/sleep` — `drwx------ system system`, `sleep_whitelist` — `-rw------- system system`, 94 байти;
  вміст: `com.navioverlay.car`, `com.radiorubka.wdsp`, `com.huautobrightness.controller`, `com.kostyamat.fmradio`.
- ✅ `dumpsys package com.qf.carsettings`: `.activity.FactorySleepWhiteListActivity` з фільтром `android.intent.action.VIEW`
  (так само `FactoryBackgroundWhiteListActivity`). Активність із фільтром на targetSdk < 31 експортована за замовчуванням.
- ⚠️ Не перевірено: відсутність дозволу/пароля при прямому запуску — екран не відкривався (ніч, чужий екран). Перевірити дотиком
  кнопки в Налаштуваннях wDSP.

