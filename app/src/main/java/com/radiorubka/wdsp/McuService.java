package com.radiorubka.wdsp;

import android.app.Notification;
import android.content.pm.ServiceInfo;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Background service to handle MCU communication, 
 * dynamic Fletcher-Munson EQ adjustment,
 * and player-based preset switching.
 */
public class McuService extends Service {
    private static final String TAG = "wDSP_McuService";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "wDSP_Background_Service";
    
    private static final String PREFS_NAME = "EqPresets";
    private static final String PREF_LAST_SELECTED = "last_selected_preset";
    private static final String PREF_PLAYER_MAP = "player_preset_map";
    private static final String PREF_DEFAULT_PRESET = "default_preset_name";
    
    private SharedPreferences prefs;
    private HandlerThread workerThread;
    private Handler backgroundHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    private boolean isPolling = false;
    private int lastVolumeRead = -1;
    private int lastAppliedVolume = -1;
    private String lastPlayerSource = null; 
    private Method getPropMethod;

    private String currentPresetName;
    private final int[] cachedGains = new int[16];
    private byte cachedQByte1, cachedQByte2;
    private int cachedSubFreq, cachedSubGain;

    private boolean cachedSubComp, cachedFmEn, cachedFatEn;
    private int cachedFmCal, cachedFmStr;
    private Map<String, String> playerMap = new HashMap<>();
    
    private final float[] fmOffsets = new float[AudioConfig.NUM_BANDS];
    private final byte[] eqData = new byte[12];
    private final byte[] subData = new byte[2];

    private final Intent volumeChangedIntent = new Intent("com.example.wdsp.VOLUME_CHANGED");
    private final Intent presetChangedIntent = new Intent("com.example.wdsp.PRESET_CHANGED");

    private boolean isUiVisible = false;

    private int lastReadHardwareVol = -1;
    private int lastEqAppliedVol = -1;

    private void initReflection() {
        try {
            HardwareManager.getInstance(this);
            String spClassName = new String(android.util.Base64.decode("YW5kcm9pZC5vcy5TeXN0ZW1Qcm9wZXJ0aWVz", android.util.Base64.DEFAULT));
            Class<?> sp = Class.forName(spClassName);
            getPropMethod = sp.getMethod("get", String.class, String.class);
            Log.i(TAG, "Reflection initialized successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Critical Reflection Failure", e);
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if (key == null) return;
        backgroundHandler.post(() -> {
            switch (key) {
                case PREF_PLAYER_MAP:
                    loadPlayerMap();
                    break;
                case PREF_LAST_SELECTED:
                    syncPreset(false);
                    break;
                default:
                    if (currentPresetName != null && key.startsWith(currentPresetName)) {
                        loadPresetData(currentPresetName);
                        applyCurrentSettings();
                    }
                    break;
            }
        });
    };

    private final BroadcastReceiver controlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            backgroundHandler.post(() -> {
                String action = intent.getAction();
                Log.d(TAG, "Received broadcast: " + action);
                if ("com.qf.action.ACC_ON".equals(action)
                        || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                        || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                    backgroundHandler.postDelayed(() ->  {
                        startPolling();
                    }, 3000);
                }
                else if ("com.qf.action.ACC_OFF".equals(action)) {
                    stopPolling();
                }
                else if ("com.example.wdsp.UI_ACTIVE".equals(action)) {
                    isUiVisible = true;
                    forceUiUpdate();
                }
                else if ("com.example.wdsp.UI_INACTIVE".equals(action)) {
                    isUiVisible = false;
                }
            });
        }
    };

    private void forceUiUpdate() {
        volumeChangedIntent.putExtra("volume", lastReadHardwareVol);
        sendBroadcast(volumeChangedIntent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        volumeChangedIntent.setPackage(getPackageName());
        presetChangedIntent.setPackage(getPackageName());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wDSP:McuWakeLock");
            wakeLock.setReferenceCounted(false);
            if (!wakeLock.isHeld()) wakeLock.acquire();
        }

        workerThread = new HandlerThread("wDSP_Worker", Process.THREAD_PRIORITY_AUDIO);
        workerThread.start();
        backgroundHandler = new Handler(workerThread.getLooper());

        backgroundHandler.post(() -> {
            VolumeHelper.init(this);
            initReflection();
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
            loadPlayerMap();
            syncPreset(true);
        });

        IntentFilter controlFilter = new IntentFilter();
        controlFilter.addAction("com.qf.action.ACC_ON");
        controlFilter.addAction("com.qf.action.ACC_OFF");
        controlFilter.addAction("android.intent.action.QUICKBOOT_POWERON");
        controlFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        controlFilter.addAction("com.example.wdsp.UI_ACTIVE");
        controlFilter.addAction("com.example.wdsp.UI_INACTIVE");
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, controlFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(controlReceiver, controlFilter);
        }
    }

    private void loadPlayerMap() {
        String json = prefs.getString(PREF_PLAYER_MAP, "{}");
        try {
            playerMap = new Gson().fromJson(json, new TypeToken<Map<String, String>>(){}.getType());
        } catch (Exception e) {
            playerMap = new HashMap<>();
        }
    }

    private void syncPreset(boolean isInitialLoad) {
        currentPresetName = prefs.getString(PREF_LAST_SELECTED, getString(R.string.default_preset_name));
        loadPresetData(currentPresetName);
        applyCurrentSettings();

        if (!isInitialLoad) {
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), getString(R.string.toast_preset_fmt, currentPresetName), Toast.LENGTH_SHORT).show());
        }
    }

    private void loadPresetData(String preset) {
        for (int i = 0; i < 16; i++) {
            cachedGains[i] = prefs.getInt(preset + "_g" + i, 6);
        }
        cachedQByte1 = calculateQByte(preset, 0);
        cachedQByte2 = calculateQByte(preset, 8);
        cachedSubFreq = prefs.getInt(preset + "_sub_f", 5);
        cachedSubGain = prefs.getInt(preset + "_sub_g", 0);
        cachedSubComp = prefs.getBoolean(preset + "_sub_comp", false);
        cachedFmEn = prefs.getBoolean(preset + "_fm_en", false);
        cachedFatEn = prefs.getBoolean(preset + "_fat_en", false);
        cachedFmCal = prefs.getInt(preset + "_fm_cal", 25);
        cachedFmStr = prefs.getInt(preset + "_fm_str", 100);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_content))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground: " + e.getMessage());
        }

        backgroundHandler.post(() -> {
            applyCurrentSettings();
            startPolling();
        });

        return START_STICKY;
    }

    private void startPolling() {
        if (!isPolling) {
            isPolling = true;
            backgroundHandler.post(pollingRunnable);
        }
    }

    private void stopPolling() {
        isPolling = false;
        backgroundHandler.removeCallbacks(pollingRunnable);
    }

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPolling) return;
            syncVolume();
            checkPlayer();
            backgroundHandler.postDelayed(this, 500);
        }
    };

    private void syncVolume() {
        int vol = VolumeHelper.getVolume();

        // EQ/Loudness Logic (Only when volume is stable)
        if (vol == lastReadHardwareVol && vol != lastEqAppliedVol) {
            applyVolumeDependentSettings(vol);
            lastEqAppliedVol = vol;
        }

        // UI Volume Slider Update
        if (vol != lastVolumeRead) {
            lastVolumeRead = vol;
            if (isUiVisible) {
                volumeChangedIntent.putExtra("volume", vol);
                sendBroadcast(volumeChangedIntent);
            }
        }

        lastReadHardwareVol = vol;
    }

    private void checkPlayer() {
        String currentPlayer = VolumeHelper.getActivePlayerType();
        if (!Objects.equals(currentPlayer, lastPlayerSource)) {
            lastPlayerSource = currentPlayer;
            processPlayerSwitch(currentPlayer);
        }
    }

    private void processPlayerSwitch(String currentPlayer) {
        String presetToLoad = playerMap.get(currentPlayer);
        if (presetToLoad == null && (currentPlayer.isEmpty() || currentPlayer.equals("Unknown"))) {
            presetToLoad = playerMap.get("Unknown");
        }
        if (presetToLoad == null) {
            presetToLoad = prefs.getString(PREF_DEFAULT_PRESET, null);
        }

        if (presetToLoad != null && !presetToLoad.equals(currentPresetName)) {
            prefs.edit().putString(PREF_LAST_SELECTED, presetToLoad).apply();
            presetChangedIntent.putExtra("preset", presetToLoad);
            if (isUiVisible) {
                sendBroadcast(presetChangedIntent);
            }
        }
    }
    private void applyCurrentSettings() {
        int hardwareVol = VolumeHelper.getVolume();
        applyVolumeDependentSettings(hardwareVol);
        applyStaticSettings();
    }


    private void applyVolumeDependentSettings(int currentVol) {
        updateEqWithFm(currentVol);
        updateSubwoofer(currentVol);
    }

    private void updateEqWithFm(int currentVol) {
        updateFmOffsets(currentVol);
        eqData[0] = (byte) 0x80;
        
        for (int i = 0; i < 8; i++) {
            int b1 = i * 2;
            float db1 = (cachedGains[b1] - 6) * 2 + fmOffsets[b1];
            int idx1 = Math.max(0, Math.min(12, Math.round((db1 / 2.0f) + 6)));
            
            int b2 = i * 2 + 1;
            float db2 = (cachedGains[b2] - 6) * 2 + fmOffsets[b2];
            int idx2 = Math.max(0, Math.min(12, Math.round((db2 / 2.0f) + 6)));
            
            eqData[i + 1] = (byte) ((idx2 << 4) | (idx1 & 0x0F));
        }
        eqData[9] = cachedQByte1;
        eqData[10] = cachedQByte2;
        eqData[11] = 0x00;
        sendToHardware(eqData);
    }

    private void updateFmOffsets(int vol) {
        Arrays.fill(fmOffsets, 0f);
        if (!cachedFmEn && !cachedFatEn) return;

        float strength = cachedFmStr / 100.0f;
        int deadzone = 1;

        if (cachedFmEn && vol < (cachedFmCal - deadzone)) {
            float range = (float) Math.max(1, cachedFmCal - deadzone);
            float ratio = (range - vol) / range;

            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                float maxOffset = AudioConfig.ISO_MAX_OFFSETS[i];
                float boost = maxOffset * ratio * strength;
                fmOffsets[i] = boost;
            }
        }
        else if (cachedFatEn && vol > (cachedFmCal + deadzone)) {
            float range = (float) Math.max(1, 32 - (cachedFmCal + deadzone));
            float ratio = (vol - (cachedFmCal + deadzone)) / range;

            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                fmOffsets[i] = AudioConfig.FATIGUE_MAX_OFFSETS[i] * ratio * strength;
            }
        }
    }

    private void updateSubwoofer(int currentVol) {
        subData[0] = (byte) 0x8B;
        float subOffset = 0f;
        int deadzone = 1;

        if (cachedSubComp && cachedFmEn && currentVol < (cachedFmCal - deadzone)) {
            float range = (float) Math.max(1, cachedFmCal - deadzone);
            float ratio = (range - currentVol) / range;
            float maxBassBoost = getMaxBassBoost();
            subOffset = (maxBassBoost * ratio * (cachedFmStr / 100.0f));
        }

        int finalGainIdx = Math.max(0, Math.min(12, Math.round(cachedSubGain + subOffset)));
        subData[1] = (byte) ((cachedSubFreq << 4) | (finalGainIdx & 0x0F));
        sendToHardware(subData);
    }

    private static float getMaxBassBoost() {
        int currentSubFreq = MainActivity.Globals.currentSubFreqHz;
        float maxBassBoost = 0;
        if (currentSubFreq == 80) maxBassBoost = AudioConfig.ISO_MAX_OFFSETS[3];
        if (currentSubFreq == 63 || currentSubFreq == 50) maxBassBoost = AudioConfig.ISO_MAX_OFFSETS[2];
        if (currentSubFreq == 40 || currentSubFreq == 32) maxBassBoost = AudioConfig.ISO_MAX_OFFSETS[1];
        if (currentSubFreq == 25) maxBassBoost = AudioConfig.ISO_MAX_OFFSETS[0];
        return maxBassBoost;
    }

    private void applyStaticSettings() {
        if (currentPresetName == null) return;
        
        byte[] d88 = new byte[4]; d88[0] = (byte) 0x88;
        d88[1] = (byte) (((prefs.getInt(currentPresetName + "_bb_frq_f", 0) + 8) << 4) | (prefs.getInt(currentPresetName + "_bb_f", 0) & 0x0F));
        d88[2] = (byte) (((prefs.getInt(currentPresetName + "_bb_frq_r", 0) + 8) << 4) | (prefs.getInt(currentPresetName + "_bb_r", 0) & 0x0F));
        d88[3] = (byte) ((prefs.getInt(currentPresetName + "_bf_f", 0) << 4) | (prefs.getInt(currentPresetName + "_bf_r", 0) & 0x0F));
        sendToHardware(d88);
        android.os.SystemClock.sleep(40);

        byte[] d81 = new byte[4]; d81[0] = (byte) 0x81;
        d81[1] = (byte) (prefs.getInt(currentPresetName + "_f_lr", 12) & 0xFF);
        d81[2] = (byte) (prefs.getInt(currentPresetName + "_f_fr", 12) & 0xFF);
        d81[3] = (byte) (prefs.getBoolean(currentPresetName + "_loud", false) ? 1 : 0);
        sendToHardware(d81);
        android.os.SystemClock.sleep(40);

        byte[] d8c = new byte[6]; d8c[0] = (byte) 0x8C;
        if (prefs.getBoolean(currentPresetName + "_d_en", false)) {
            d8c[1] = (byte) ((prefs.getInt(currentPresetName + "_d_fl", 0) * 5) & 0xFF);
            d8c[2] = (byte) ((prefs.getInt(currentPresetName + "_d_fr", 0) * 5) & 0xFF);
            d8c[3] = (byte) ((prefs.getInt(currentPresetName + "_d_rl", 0) * 5) & 0xFF);
            d8c[4] = (byte) ((prefs.getInt(currentPresetName + "_d_rr", 0) * 5) & 0xFF);
            d8c[5] = (byte) ((prefs.getInt(currentPresetName + "_d_sub", 0) * 5) & 0xFF);
            sendToHardware(d8c);
            android.os.SystemClock.sleep(40);
        }

        byte[] d89 = new byte[6]; d89[0] = (byte) 0x89;
        if (prefs.getBoolean(currentPresetName + "_d1_en", false)) {
            int rsseByte = 138 + (prefs.getInt(currentPresetName + "_rsse_val", 10) - 10);
            d89[1] = (byte) (rsseByte & 0xFF);
            d89[2] = (byte) (prefs.getInt(currentPresetName + "_d1_fl", 0) & 0xFF);
            d89[3] = (byte) (prefs.getInt(currentPresetName + "_d1_fr", 0) & 0xFF);
            d89[4] = (byte) (prefs.getInt(currentPresetName + "_d1_rl", 0) & 0xFF);
            d89[5] = (byte) (prefs.getInt(currentPresetName + "_d1_rr", 0) & 0xFF);
            sendToHardware(d89);
            android.os.SystemClock.sleep(40);
        }
    }

    private byte calculateQByte(String preset, int offset) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            if (prefs.getBoolean(preset + "_q" + (offset + i), false)) r |= (1 << i);
        }
        return (byte) r;
    }

    private void sendToHardware(byte[] data) {
        if (data == null || data.length == 0) return;
        HardwareManager.getInstance(this).sendData(data);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        backgroundHandler.post(() -> {
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
            stopPolling();
            workerThread.quitSafely();
        });
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}