# 14. Системні іконпаки лаунчера QF та повнорозмірні іконки без рамок (Launcher Icon Injection)

> 🔬 **Read from firmware / decompiled code**: Пакет лаунчера платформи QF (`QF_Launcher_BlueMan`, `QF_Launcher_SimpleBlue`, `QF_Launcher_MeetAgain` тощо).
> 📻 **Measured on the wire / disk**: Досліджено на залізі QF01/QF03/QF05, каталог `/data/QF/.icons/`.
> ✍️ **Досліджено Antigravity (Gemini) — 31.08.2026 05:48**.
> 🤝 **Для реалізації в wDSP агентом Claude**.

---

## 1. Проблема: сторонні додатки зменшуються лаунчером
Усі штатні лаунчери платформи QF (`QF_Launcher_*`) відмальовують іконки за наступним правилом:
1. **Заводські додатки** (Радіо, Музика, Bluetooth, DSP, Spotify, YouTube тощо) мають красиві **повнорозмірні іконки без рамок** (full-bleed 200×200 px), які заповнюють весь виділений квадрат робочого столу.
2. **Сторонні додатки** (включно з wDSP `com.radiorubka.wdsp`), яких немає в системному конфігу лаунчера: лаунчер примусово стискає їхню іконку до ~60-65% і розміщує всередині дефолтної квадратної плашки з рамкою `ic_launcher_bg.png`.

---

## 2. Системний каталог `/data/QF/.icons` (Заводські права 0777)
Каталог іконпаків лаунчера знаходиться за шляхом:
`/data/QF/.icons/`

### 🔑 Критична особливість:
Каталог та всі піддиректорії мають системні права **`0777` (`drwxrwxrwx`)**, власник `system:system`.
Це дозволяє будь-якому non-root додатку (`UID >= 10000`) вільно читати та записувати туди файли!

Структура каталогу:
```text
/data/QF/.icons/
├── icons_style_2/
│   ├── icons.config
│   └── drawable-mdpi/ (PNG файли 200x200 32-bit RGBA)
└── icons_style_gongban/
    ├── icons.config
    └── drawable-mdpi/ (PNG файли 200x200 32-bit RGBA)
```

Формат `icons.config`:
```properties
ic_launcher_bg=ic_launcher_bg.png
com.android.fmradio/com.android.fmradio.FmMainActivity=ic_launcher_radio.png
com.spotify.music/com.spotify.music.MainActivity=ic_launcher_spotify.png
com.google.android.youtube/com.google.android.youtube.app.honeycomb.Shell$HomeActivity=ic_launcher_youtube.png
com.radiorubka.wdsp/com.radiorubka.wdsp.MainActivity=ic_launcher_wdsp.png
```

---

## 3. Готова реалізація для wDSP (LauncherIconInjector.java)

Для того, щоб іконка wDSP стала повнорозмірною та безрамковою у всіх лаунчерах QF:

### Крок 1: Створити клас `LauncherIconInjector.java` у `wdsp_app`:

```java
package com.radiorubka.wdsp.util;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LauncherIconInjector {
    private static final String TAG = "LauncherIconInjector";
    private static final File ICONS_ROOT = new File("/data/QF/.icons");

    public static void injectAsync(Context context) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        new Thread(() -> {
            try {
                inject(appCtx);
            } catch (Throwable t) {
                Log.w(TAG, "Icon injection failed: " + t.getMessage());
            }
        }, "WdspIconInjectorThread").start();
    }

    public static void inject(Context context) {
        if (!ICONS_ROOT.exists() || !ICONS_ROOT.isDirectory()) return;
        File[] styles = ICONS_ROOT.listFiles();
        if (styles == null || styles.length == 0) return;

        byte[] wdspPng = extractIconPng(context, "com.radiorubka.wdsp");
        if (wdspPng == null) return;

        for (File styleDir : styles) {
            if (!styleDir.isDirectory()) continue;
            File drawableDir = new File(styleDir, "drawable-mdpi");
            File configFile = new File(styleDir, "icons.config");
            if (!drawableDir.exists() || !configFile.exists()) continue;

            File iconFile = new File(drawableDir, "ic_launcher_wdsp.png");
            writeFileIfChanged(iconFile, wdspPng);
            ensureConfigMapping(configFile, "com.radiorubka.wdsp/com.radiorubka.wdsp.MainActivity", "ic_launcher_wdsp.png");
        }
    }

    private static byte[] extractIconPng(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            Drawable drawable = pm.getApplicationIcon(packageName);
            Bitmap bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, 200, 200);
            drawable.draw(canvas);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            return baos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void writeFileIfChanged(File target, byte[] data) {
        try {
            if (target.exists() && target.length() == data.length) {
                byte[] existing = new byte[(int) target.length()];
                try (java.io.FileInputStream fis = new java.io.FileInputStream(target)) {
                    int read = fis.read(existing);
                    if (read == data.length && Arrays.equals(existing, data)) {
                        return;
                    }
                }
            }
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(data);
                fos.flush();
            }
            target.setReadable(true, false);
            target.setWritable(true, false);
        } catch (Throwable t) {
            Log.w(TAG, "Failed writing " + target + ": " + t.getMessage());
        }
    }

    private static void ensureConfigMapping(File configFile, String key, String iconName) {
        try {
            List<String> lines = new ArrayList<>();
            boolean found = false;
            boolean modified = false;
            String expected = key + "=" + iconName;

            if (configFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith(key + "=")) {
                            found = true;
                            if (!trimmed.equals(expected)) {
                                lines.add(expected);
                                modified = true;
                                continue;
                            }
                        }
                        lines.add(line);
                    }
                }
            }

            if (!found) {
                lines.add(expected);
                modified = true;
            }

            if (modified) {
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
                    for (String l : lines) {
                        writer.write(l);
                        writer.newLine();
                    }
                }
                configFile.setReadable(true, false);
                configFile.setWritable(true, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed updating config " + configFile + ": " + t.getMessage());
        }
    }
}
```

### Крок 2: Викликати в `App.java` (або `MainActivity.java:onCreate`):
```java
LauncherIconInjector.injectAsync(this);
```
