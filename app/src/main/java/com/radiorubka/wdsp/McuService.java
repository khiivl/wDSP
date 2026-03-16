package com.radiorubka.wdsp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
 * player-based preset switching,
 * and GALA (Speed Sensitive Volume).
 */
public class McuService extends Service implements LocationListener {
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

    private boolean isPolling = false;
    private int lastVolumeRead = -1;
    private int lastAppliedVolume = -1;
    private String lastPlayerSource = null; 
    private Method getPropMethod;
    private Object mcuManagerInstance;
    private Method setEqDataMethod;

    private String currentPresetName;
    private final int[] cachedGains = new int[16];
    private byte cachedQByte1, cachedQByte2;
    private int cachedSubFreq, cachedSubGain;

    private boolean cachedSubComp, cachedFmEn, cachedFatEn;
    private int cachedFmCal, cachedFmStr;

    // GALA settings
    private boolean cachedGalaEn;
    private int cachedGalaInc;
    private int cachedGalaMinV;
    private int cachedGalaMaxV;
    private int cachedGalaMaxAdj;
    
    private float currentSpeedKmh = 0.0f;
    private float simulatedSpeedKmh = -1.0f;
    private int baseStandstillVolume = -1;
    private int lastReadHardwareVol = -1;

    private long lastEqWriteTime = 0;
    private byte[] pendingEqData = null;
    private boolean eqUpdatePending = false;

    private long lastSubWriteTime = 0;
    private byte[] pendingSubData = null;
    private boolean subUpdatePending = false;
    private static final long THROTTLE_MS = 500; // 2 commands per second

    private LocationManager locationManager;
    private Map<String, String> playerMap = new HashMap<>();
    private final Map<Byte, byte[]> mcuCache = new HashMap<>();
    
    private final float[] fmOffsets = new float[16];
    private final byte[] eqData = new byte[12];
    private final byte[] subData = new byte[2];

    private final Intent volumeChangedIntent = new Intent("com.example.wdsp.VOLUME_CHANGED");
    private final Intent presetChangedIntent = new Intent("com.example.wdsp.PRESET_CHANGED");
    private final Intent galaUpdateIntent = new Intent("com.example.wdsp.GALA_UPDATE");

    private boolean isUiVisible = false;
    private boolean isBootStart = true;

    private void initReflection() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            getPropMethod = sp.getMethod("get", String.class, String.class);
            Log.i(TAG, "Reflection initialized successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Critical Reflection Failure", e);
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if (key == null) return;
        backgroundHandler.post(() -> {
            if (key.equals(PREF_PLAYER_MAP)) {
                loadPlayerMap();
            } else if (key.equals(PREF_LAST_SELECTED)) {
                syncPreset(false);
            } else {
                if (currentPresetName != null && key.startsWith(currentPresetName)) {
                    loadPresetData(currentPresetName);
                    applyCurrentSettings();
                }
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
                    applyCurrentSettings();
                    backgroundHandler.postDelayed(() -> {
                        startPolling();
                        startGps();
                    }, 3000);
                }
                else if ("com.qf.action.ACC_OFF".equals(action)) {
                    stopPolling();
                    stopGps();
                }
                else if ("com.example.wdsp.UI_ACTIVE".equals(action)) {
                    isUiVisible = true;
                    forceUiUpdate();
                }
                else if ("com.example.wdsp.UI_INACTIVE".equals(action)) {
                    isUiVisible = false;
                }
                else if ("com.example.wdsp.SIMULATE_SPEED".equals(action)) {
                    simulatedSpeedKmh = intent.getFloatExtra("speed", -1.0f);
                }
            });
        }
    };

    private void forceUiUpdate() {
        float speed = simulatedSpeedKmh >= 0.0f ? simulatedSpeedKmh : currentSpeedKmh;
        int hardwareVol = VolumeHelper.getVolume();
        
        galaUpdateIntent.putExtra("speed", speed);
        int effectiveOffset = (baseStandstillVolume != -1) ? Math.max(0, hardwareVol - baseStandstillVolume) : 0;
        galaUpdateIntent.putExtra("waveOffset", effectiveOffset);
        sendBroadcast(galaUpdateIntent);

        volumeChangedIntent.putExtra("volume", hardwareVol);
        if (isUiVisible) sendBroadcast(volumeChangedIntent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        
        volumeChangedIntent.setPackage(getPackageName());
        presetChangedIntent.setPackage(getPackageName());
        galaUpdateIntent.setPackage(getPackageName());

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        workerThread = new HandlerThread("wDSP_Worker", -16); // THREAD_PRIORITY_AUDIO
        workerThread.start();
        backgroundHandler = new Handler(workerThread.getLooper());

        backgroundHandler.post(() -> {
            VolumeHelper.init(this);
            initReflection();
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
            loadPlayerMap();
            syncPreset(true);
            isBootStart = false;
        });

        IntentFilter controlFilter = new IntentFilter();
        controlFilter.addAction("com.qf.action.ACC_ON");
        controlFilter.addAction("com.qf.action.ACC_OFF");
        controlFilter.addAction("android.intent.action.QUICKBOOT_POWERON");
        controlFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        controlFilter.addAction("com.example.wdsp.UI_ACTIVE");
        controlFilter.addAction("com.example.wdsp.UI_INACTIVE");
        controlFilter.addAction("com.example.wdsp.SIMULATE_SPEED");
        
        registerReceiver(controlReceiver, controlFilter);

        applyCurrentSettings();
    }

    private void loadPlayerMap() {
        String json = prefs.getString(PREF_PLAYER_MAP, "{}");
        try {
            playerMap = new Gson().fromJson(json, new TypeToken<Map<String, String>>(){}.getType());
        } catch (Exception e) {
            playerMap = new HashMap<>();
        }
    }

    private void syncPreset(boolean showToast) {
        currentPresetName = prefs.getString(PREF_LAST_SELECTED, "Preset 1");
        loadPresetData(currentPresetName);
        applyCurrentSettings();

        if (showToast && isBootStart) {
            mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Preset Applied: " + currentPresetName, Toast.LENGTH_SHORT).show());
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
        
        // GALA
        cachedGalaEn = prefs.getBoolean(preset + "_gala_enabled", false);
        cachedGalaInc = prefs.getInt(preset + "_gala_increment", 15);
        cachedGalaMinV = prefs.getInt(preset + "_gala_min_speed", 0);
        cachedGalaMaxV = prefs.getInt(preset + "_gala_max_speed", 30);
        cachedGalaMaxAdj = prefs.getInt(preset + "_gala_max_adj", 12);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("wDSP Active")
                .setContentText("Foreground EQ processing enabled")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            backgroundHandler.post(() -> {
                applyCurrentSettings();
                startPolling();
                startGps();
            });
            Log.d("McuService", "Background tasks started after 5s delay");
        }, 3000); // 5 second delay

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
            checkVolumeAndGala();
            checkPlayer();
            backgroundHandler.postDelayed(this, 100);
        }
    };

    private void checkVolumeAndGala() {
        float speed = simulatedSpeedKmh >= 0.0f ? simulatedSpeedKmh : currentSpeedKmh;
        int hardwareVol = VolumeHelper.getVolume();
        
        if (cachedGalaEn) {
            int speedIncrement = Math.max(1, cachedGalaInc + 5);
            int minSpeed = cachedGalaMinV * 5;
            int maxSpeed = cachedGalaMaxV * 5;
            
            int rawOffset = 0;
            if (speed >= minSpeed) {
                float constrainedSpeed = Math.min(speed, maxSpeed);
                rawOffset = (int) ((constrainedSpeed - minSpeed) / speedIncrement);
                // Apply max adjustment limit
                rawOffset = Math.min(rawOffset, cachedGalaMaxAdj);
            }
            
            if (baseStandstillVolume == -1) {
                baseStandstillVolume = Math.max(0, hardwareVol - rawOffset);
                lastReadHardwareVol = hardwareVol;
                lastAppliedVolume = hardwareVol;
            }
            
            if (hardwareVol != lastReadHardwareVol && hardwareVol != lastAppliedVolume) {
                baseStandstillVolume = Math.max(0, hardwareVol - rawOffset);
                if (hardwareVol < rawOffset) {
                    baseStandstillVolume = 0;
                    lastAppliedVolume = hardwareVol;
                }
                Log.d(TAG, "Manual Adjust: Vol=" + hardwareVol + " Base=" + baseStandstillVolume);
            }
            
            lastReadHardwareVol = hardwareVol;
            int targetVol = Math.min(32, baseStandstillVolume + rawOffset);
            
            if (hardwareVol != targetVol) {
                VolumeHelper.setVolume(targetVol);
                lastAppliedVolume = targetVol;
                hardwareVol = targetVol; 
                Log.v(TAG, "GALA Update: " + lastReadHardwareVol + " -> " + targetVol);
            }
            
            if (isUiVisible) {
                int effectiveOffset = Math.max(0, targetVol - baseStandstillVolume);
                galaUpdateIntent.putExtra("speed", speed);
                galaUpdateIntent.putExtra("waveOffset", effectiveOffset);
                sendBroadcast(galaUpdateIntent);
            }
        } else {
            baseStandstillVolume = hardwareVol;
            lastAppliedVolume = hardwareVol;
            lastReadHardwareVol = hardwareVol;
        }

        if (hardwareVol != lastVolumeRead) {
            lastVolumeRead = hardwareVol;
            applyVolumeDependentSettings(hardwareVol);
            if (isUiVisible) {
                volumeChangedIntent.putExtra("volume", hardwareVol);
                sendBroadcast(volumeChangedIntent);
            }
        }
    }

    private void checkPlayer() {
        String currentPlayer = getSystemProperty("sys.qf.last_audio_src", "");
        if (VolumeHelper.getActivePlayerType().equals("btcall_type")) {
            lastPlayerSource = "Call";
            processPlayerSwitch("Call");
        }
        else if (!Objects.equals(currentPlayer, lastPlayerSource)) {
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
        if (currentPlayer.equals("Call")) {
            presetToLoad = "Call";
        }
        if (presetToLoad != null && !presetToLoad.equals(currentPresetName)) {
            if (isUiVisible == false) {
                Toast.makeText(McuService.this, "Auto applied preset: " + presetToLoad, Toast.LENGTH_SHORT).show();
            }
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
        sendEqThrottled(eqData);
    }

    private void updateFmOffsets(int vol) {
        Arrays.fill(fmOffsets, 0f);
        if (!cachedFmEn && !cachedFatEn) return;

        float strength = cachedFmStr / 100.0f;
        int deadzone = 1;

        if (cachedFmEn && vol < (cachedFmCal - deadzone)) {
            float range = (float) Math.max(1, cachedFmCal - deadzone);
            float ratio = (range - vol) / range;
            for (int i = 0; i < 16; i++) {
                fmOffsets[i] = AudioConfig.ISO_MAX_OFFSETS[i] * ratio * strength;
            }
        }
        else if (cachedFatEn && vol > (cachedFmCal + deadzone)) {
            float range = (float) Math.max(1, 32 - (cachedFmCal + deadzone));
            float ratio = (vol - (cachedFmCal + deadzone)) / range;
            for (int i = 0; i < 16; i++) {
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
            subOffset = maxBassBoost * ratio * (cachedFmStr / 100.0f);
        }

        int finalGainIdx = Math.max(0, Math.min(12, Math.round(cachedSubGain + subOffset)));
        subData[1] = (byte) ((cachedSubFreq << 4) | (finalGainIdx & 0x0F));
        sendSubThrottled(subData);
    }

    private float getMaxBassBoost() {
        int[] freqs = {25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250};
        int freq = (cachedSubFreq >= 0 && cachedSubFreq < freqs.length) ? freqs[cachedSubFreq] : 80;
        
        if (freq == 80) return AudioConfig.ISO_MAX_OFFSETS[3];
        if (freq == 63 || freq == 50) return AudioConfig.ISO_MAX_OFFSETS[2];
        if (freq == 40 || freq == 32) return AudioConfig.ISO_MAX_OFFSETS[1];
        if (freq == 25) return AudioConfig.ISO_MAX_OFFSETS[0];
        return 0;
    }

    private void applyStaticSettings() {
        if (currentPresetName == null) return;
        
        sendToHardware(new byte[]{(byte) 0x88, 
            (byte) (((prefs.getInt(currentPresetName + "_bb_frq_f", 0) + 8) << 4) | (prefs.getInt(currentPresetName + "_bb_f", 0) & 0x0F)),
            (byte) (((prefs.getInt(currentPresetName + "_bb_frq_r", 0) + 8) << 4) | (prefs.getInt(currentPresetName + "_bb_r", 0) & 0x0F)),
            (byte) ((prefs.getInt(currentPresetName + "_bf_f", 0) << 4) | (prefs.getInt(currentPresetName + "_bf_r", 0) & 0x0F))});

        sendToHardware(new byte[]{(byte) 0x81, 
            (byte) (prefs.getInt(currentPresetName + "_f_lr", 12) & 0xFF),
            (byte) (prefs.getInt(currentPresetName + "_f_fr", 12) & 0xFF),
            (byte) (prefs.getBoolean(currentPresetName + "_loud", false) ? 1 : 0)});

        if (prefs.getBoolean(currentPresetName + "_d_en", false)) {
            byte[] d8c = new byte[6]; d8c[0] = (byte) 0x8C;
            d8c[1] = (byte) ((prefs.getInt(currentPresetName + "_d_fl", 0) * 5) & 0xFF);
            d8c[2] = (byte) ((prefs.getInt(currentPresetName + "_d_fr", 0) * 5) & 0xFF);
            d8c[3] = (byte) ((prefs.getInt(currentPresetName + "_d_rl", 0) * 5) & 0xFF);
            d8c[4] = (byte) ((prefs.getInt(currentPresetName + "_d_rr", 0) * 5) & 0xFF);
            d8c[5] = (byte) ((prefs.getInt(currentPresetName + "_d_sub", 0) * 5) & 0xFF);
            sendToHardware(d8c);
        }

        if (prefs.getBoolean(currentPresetName + "_d1_en", false)) {
            byte[] d89 = new byte[6]; d89[0] = (byte) 0x89;
            d89[1] = (byte) (138 + (prefs.getInt(currentPresetName + "_rsse_val", 10) - 10));
            d89[2] = (byte) (prefs.getInt(currentPresetName + "_d1_fl", 0) & 0xFF);
            d89[3] = (byte) (prefs.getInt(currentPresetName + "_d1_fr", 0) & 0xFF);
            d89[4] = (byte) (prefs.getInt(currentPresetName + "_d1_rl", 0) & 0xFF);
            d89[5] = (byte) (prefs.getInt(currentPresetName + "_d1_rr", 0) & 0xFF);
            sendToHardware(d89);
        }
    }

    private byte calculateQByte(String preset, int offset) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            if (prefs.getBoolean(preset + "_q" + (offset + i), false)) r |= (1 << i);
        }
        return (byte) r;
    }

    // --- EQ THROTTLER ---
    private void sendEqThrottled(byte[] data) {
        pendingEqData = data.clone();
        if (eqUpdatePending) return;

        long now = System.currentTimeMillis();
        long elapsed = now - lastEqWriteTime;

        if (elapsed >= THROTTLE_MS) {
            executeEqWrite();
        } else {
            eqUpdatePending = true;
            backgroundHandler.postDelayed(this::executeEqWrite, THROTTLE_MS - elapsed);
        }
    }

    private void executeEqWrite() {
        if (pendingEqData != null) {
            sendToHardware(pendingEqData);
            lastEqWriteTime = System.currentTimeMillis();
        }
        eqUpdatePending = false;
    }

    // --- SUBWOOFER THROTTLER ---
    private void sendSubThrottled(byte[] data) {
        pendingSubData = data.clone();
        if (subUpdatePending) return;

        long now = System.currentTimeMillis();
        long elapsed = now - lastSubWriteTime;

        if (elapsed >= THROTTLE_MS) {
            executeSubWrite();
        } else {
            subUpdatePending = true;
            backgroundHandler.postDelayed(this::executeSubWrite, THROTTLE_MS - elapsed);
        }
    }

    private void executeSubWrite() {
        if (pendingSubData != null) {
            sendToHardware(pendingSubData);
            lastSubWriteTime = System.currentTimeMillis();
        }
        subUpdatePending = false;
    }

    private void sendToHardware(byte[] data) {
        if (data == null || data.length == 0) return;
        byte cmd = data[0];
        byte[] cached = mcuCache.get(cmd);
        if (cached == null || !Arrays.equals(cached, data)) {
            try {
                ensureMcuManager();
                if (setEqDataMethod != null && mcuManagerInstance != null) {
                    setEqDataMethod.invoke(mcuManagerInstance, (Object) data);
                    mcuCache.put(cmd, data.clone());
                }
            } catch (Exception e) {
                Log.e(TAG, "MCU Error: " + e.getMessage());
            }
        }
    }

    private void ensureMcuManager() throws Exception {
        if (mcuManagerInstance == null) {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "mcu_service");
            if (binder != null) {
                Class<?> stub = Class.forName("android.qf.mcu.IMcuManager$Stub");
                mcuManagerInstance = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
                if (mcuManagerInstance != null) {
                    setEqDataMethod = mcuManagerInstance.getClass().getMethod("RPC_SetEQData", byte[].class);
                }
            }
        }
    }

    private String getSystemProperty(String key, String def) {
        try {
            if (getPropMethod != null) {
                return (String) getPropMethod.invoke(null, key, def);
            }
        } catch (Exception ignored) {}
        return def;
    }

    private void startGps() {
        try {
            if (locationManager != null) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0.0f, this);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "GPS Permission missing", e);
        }
    }

    private void stopGps() {
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        backgroundHandler.post(() -> {
            currentSpeedKmh = location.getSpeed() * 3.6f;
        });
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "wDSP Background Service", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
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
            stopGps();
            workerThread.quitSafely();
        });
        try { unregisterReceiver(controlReceiver); } catch (Exception ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
