# 15. Unisoc UMS512 (UIS7862 / SharkL5Pro) — Повний вихідний код Android 10 (BSP & AOSP)

> ✍️ **Досліджено Antigravity (Gemini) — 11.09.2026 19:15**  
> 🔬 **Базова основа**: Офіційні вихідні коди Unisoc IDH (Independent Design House) BSP релізу `W21.24.3` (Android 10 Q, SDK 29), дерева пристроїв JingPad A1 (UD710/UMS512), напрацювання `sprd-oss-devs`, `lineageos-on-jingpad`, `Veynamer` та декомпільований шар платформи QF (`d:\UIS_android\qf_platform_framework`).

---

## 1. Архітектурна карта системи Android 10 на UIS7862

Головний пристрій на базі **Unisoc UIS7862 / UMS512** (кодова назва платформи **SharkL5Pro**) складається з 4-х шарів коду:

```
+-------------------------------------------------------------------------+
| [Шар 4] QF / K706 Автомобільний шар (QF_Framework.apk, services.jar,     |
|         McuManagerService, BackCarService, системні властивості persist) |
+-------------------------------------------------------------------------+
| [Шар 3] Unisoc IDH BSP (HALs, Vendor, Device Trees, RIL, Whale Audio,    |
|         HWComposer v2.x, DPU, Cam, RadioInteractor, IDH Build Scripts)   |
+-------------------------------------------------------------------------+
| [Шар 2] AOSP Base Android 10 (Tag: android-10.0.0_r41, SDK 29)          |
|         (frameworks/base, system/core, bionic, art, build/make)         |
+-------------------------------------------------------------------------+
| [Шар 1] Ядро Linux 4.14 (SharkL5Pro) + U-Boot15 + Chipram (SPL)          |
+-------------------------------------------------------------------------+
```

---

## 2. Де знаходяться повні вихідні коди

### 2.1. Повний стек репозиторіїв Unisoc BSP (421 репозиторій)
🔬 **Організація на GitHub**: [jingpad-bsp](https://github.com/jingpad-bsp)  
Всі репозиторії синхронізовані в гілці: `W21.24.3` (офіційний реліз Unisoc IDH Android 10).

Включає в себе:
1. **Дерева пристроїв (`device/` та `bsp/device/`)**:
   - `device/sprd/sharkl5pro` ➔ [jingpad-bsp/device_sprd_sharkl5pro](https://github.com/jingpad-bsp/device_sprd_sharkl5pro)
     - Містить конфігурації: `common`, `ums512_1h10`, `ums512_20c10`, `ums512_2h10`.
   - `bsp/device/sharkl5pro/androidq` ➔ [jingpad-bsp/bsp_device_sharkl5pro](https://github.com/jingpad-bsp/bsp_device_sharkl5pro)
2. **Аудіотракт та HAL (Whale Audio HAL)**:
   - `vendor/sprd/modules/audio` ➔ [jingpad-bsp/vendor_sprd_modules_audio](https://github.com/jingpad-bsp/vendor_sprd_modules_audio)
     - Повний нативний код звукового рушія Unisoc: `whale/audio_hw.c`, `whale/fm.c`, `whale/audio_offload.c`, `whale/alsa_pcm_util.c`, `whale/tinyalsa_util.cpp`, `whale/agdsp.c`.
3. **Графіка та дисплей (DPU & HWComposer v2.x)**:
   - `vendor/sprd/modules/hwcomposer` ➔ [zhangye/vendor_sprd_modules_hwcomposer](https://github.com/zhangye/vendor_sprd_modules_hwcomposer) (версії v1.x та v2.x для SharkL5Pro).
   - `vendor/sprd/modules/dpu` ➔ [jingpad-bsp/vendor_sprd_modules_dpu](https://github.com/jingpad-bsp/vendor_sprd_modules_dpu)
4. **HIDL інтерфейси радіо та заліза**:
   - `vendor/sprd/interfaces/broadcastradio` (2.0) ➔ [jingpad-bsp/vendor_sprd_interfaces_broadcastradio](https://github.com/jingpad-bsp/vendor_sprd_interfaces_broadcastradio)
   - `vendor/sprd/interfaces/radio` ➔ [jingpad-bsp/vendor_sprd_interfaces_radio](https://github.com/jingpad-bsp/vendor_sprd_interfaces_radio)
   - `vendor/sprd/modules/radiointeractor` ➔ [jingpad-bsp/vendor_sprd_modules_radiointeractor](https://github.com/jingpad-bsp/vendor_sprd_modules_radiointeractor)
5. **Завантажувач (Bootloader)**:
   - `bsp/bootloader/u-boot15` ➔ [Veynamer/bsp_bootloader_u-boot15_sharkl5pro](https://github.com/Veynamer/bsp_bootloader_u-boot15_sharkl5pro) або [jingpad-bsp/bsp_bootloader_u-boot15](https://github.com/jingpad-bsp/bsp_bootloader_u-boot15)
   - `bsp/bootloader/chipram` ➔ [iscle/ums512_chipram](https://github.com/iscle/ums512_chipram)
6. **Скрипти збірки та пакування PAC-прошивки**:
   - `vendor/sprd/release/IDH/Script` ➔ [jingpad-bsp/vendor_sprd_release_IDH_Script](https://github.com/jingpad-bsp/vendor_sprd_release_IDH_Script) (`build_pac.sh`, `mkpac.pl`, `UpdatedPacCRC_Linux`).

---

### 2.2. Повний архів (Торрент сирців єдиним тарболом)
🔬 **Офіційний вихідний архів сирців JingLing**:
- Репозиторій торента: [qwqlemon2333/source_code_for_jingpad](https://github.com/qwqlemon2333/source_code_for_jingpad)
- Файл: `source.tar.gz.torrent`
- Містить: Повний монолітний зріз AOSP 10 + Unisoc IDH BSP ready-to-build.

---

### 2.3. Дерева LineageOS / Community для UMS512
🔬 **Організація**: [sprd-oss-devs](https://github.com/sprd-oss-devs)
- `android_device_realme_ums512-common` (локально в `d:\UIS_android\android_device_realme_ums512-common`)
  - `BoardConfigCommon.mk`: конфігурація ядра Clang r416183b, SharkL5Pro модулі, розділи dynamic partitions (A/B).
  - `proprietary-files.txt`: повний перелік вендорних BLOB'ів з магнітоли/пристрою.
- `android_hardware_sprd` (локально в `d:\UIS_android\android_hardware_sprd`)
- `android_device_sprd_sepolicy` (політики SELinux для платформи Unisoc).

---

### 2.4. Ядро Linux 4.14 (UMS512 / SharkL5Pro)
🔬 **Локальні копії на робочій станції**:
- Windows: `d:\UIS_android\android_kernel_unisoc_ums512`
- WSL Ubuntu: `/home/user/UIS_android/android_kernel_unisoc_ums512`
- Віддалені джерела: [iscle/android_kernel_unisoc_ums512](https://github.com/iscle/android_kernel_unisoc_ums512), [deadman96385/android_kernel_teclast_sharkl5Pro](https://github.com/deadman96385/android_kernel_teclast_sharkl5Pro).

---

### 2.5. Автомобільна специфіка платформи QF (QianFang / K706)
🔬 **Локальний декомпільований код**: `d:\UIS_android\qf_platform_framework`
- `McuManagerService.java` — комунікація з MCU через UART, транзакції Binder (1001 тощо), комутація каналів аудіотракту.
- `BackCarService.java` — робота з камерою заднього виду / 360 круговим оглядом.
- `QF_Framework.apk` — системні провайдери та системний інтерфейс.

---

## 3. Збірка повноцінної прошивки Unisoc PAC

Для збірки повного образу використовується нативний скрипт Unisoc IDH:
```bash
# Перехід у директорію скриптів IDH
cd vendor/sprd/release/IDH/Script/

# Збірка ядра та образів (system, vendor, boot, dtbo)
./build_pac.sh -a ums512_1h10-userdebug-native -b build 2>&1 | tee build.log

# Пакування фінального образу PAC для прошивки через UpgradeDownload / ResearchDownload
./build_pac.sh -a ums512_1h10-userdebug-native -b pac 2>&1 | tee pac.log
```

🧩 **Висновки для подальшої розробки**:
1. Повний офіційний вихідний код Android 10 для UMS512 (SharkL5Pro) збережено на GitHub у просторі `jingpad-bsp` та у форматі торенту `qwqlemon2333/source_code_for_jingpad`.
2. Усі апаратні HAL (включаючи Whale Audio HAL з файлом `fm.c`, дисплей HWComposer v2.x, RIL та HIDL інтерфейси) тепер доступні в чистому вигляді C/C++, що дає можливість досконало розуміти будь-яку дію на залізі магнітоли.
