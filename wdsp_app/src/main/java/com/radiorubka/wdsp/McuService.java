package com.radiorubka.wdsp;

import android.annotation.SuppressLint;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

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
//    private static final String PREF_DEFAULT_PRESET = "default_preset_name";

    private static final String PREF_GALA_GLOBAL_MODE = "gala_global_mode";
    // Must match MainActivity's GALA_GLOBAL_NAMESPACE/GALA_FIELD_SUFFIXES - when global mode is
    // on, every GALA field is read from this pseudo-preset's keys instead of the real preset's.
    private static final String GALA_GLOBAL_NAMESPACE = "__gala_global__";
    
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
    private Method setMcuMsgMethod;

    private String currentPresetName;
    private final int[] cachedGains = new int[16];
    private byte cachedQByte1, cachedQByte2;
    private int cachedSubFreq, cachedSubGain;

    private boolean cachedSubComp, cachedFmEn, cachedFatEn;
    private int cachedFmCal, cachedFmStr;
    // Trim Highs' own volume threshold - decoupled from cachedFmCal (Loudness' Calibration
    // Point), which the ISO/Loudness branch below still uses. See updateFmOffsets().
    private int cachedFatStartVol;

    // "Ultra Bass" - independent of Loudness/cachedFmEn entirely (see applyBassBoost()'s doc for
    // why that one IS tied to Loudness) - a plain volume-reactive sub-channel boost, ramping
    // linearly from 0 at cachedUltraBassStartVol up to cachedUltraBassMaxDb at volume 32. See
    // updateSubwoofer()'s use of these.
    private boolean cachedUltraBassEn;
    private int cachedUltraBassStartVol, cachedUltraBassMaxDb;

    // GALA settings
    private boolean cachedGalaEn, galaWasShutdown;
    private int cachedGalaInc;
    private int cachedGalaMinV;
    // private int cachedGalaMaxV;
    private int cachedGalaMaxAdj;
    private int cachedGalaFadeDelayMs; // ms between each ±1 fade step (default 100)
    private int cachedGalaHoldMs;      // ms a tier must be stable before being applied (default 1000)
    // Per-preset override - forces GALA off for this preset even while global mode is on for
    // every other preset. Always read from the real preset's own keys, never GALA_GLOBAL_NAMESPACE.
    private boolean cachedGalaDisabledForPreset;

    // When on, every GALA field above is loaded from GALA_GLOBAL_NAMESPACE's keys instead of
    // the current preset's own - see loadPresetData()/isGalaEnabled().
    private boolean galaGlobalMode;
    
    private float currentSpeedKmh = 0.0f;
    private float simulatedSpeedKmh = 0.0f;
    private int baseStandstillVolume = -1;

    // GALA fade & hold-timer state
    private int currentAppliedOffset = -1; // the offset currently SET on the hardware
    private int pendingTargetOffset  = -1; // the offset we want to reach (after hold timer)
    private int lastGalaTier         = -1; // last confirmed tier index
    private long tierChangeTimestamp = 0;   // when the current pending tier was first seen
    private long lastFadeStepTime    = 0;   // last time we moved applied offset by ±1

    private boolean wasMuted = false;

    private int lastReadHardwareVol = -1;

    private long lastEqWriteTime = 0;
    private byte[] pendingEqData = null;
    private boolean eqUpdatePending = false;

    private long lastSubWriteTime = 0;
    private byte[] pendingSubData = null;
    private boolean subUpdatePending = false;

    private long lastBassBoostWriteTime = 0;
    private byte[] pendingBassBoostData = null;
    private boolean bassBoostUpdatePending = false;
    private static final long THROTTLE_MS = 500; // 2 commands per second

    // Last packet each update*()/apply*() method actually computed (not what's been written to
    // hardware yet - sendToHardware()'s own mcuCache handles that dedupe). These let
    // updateEqWithFm()/updateSubwoofer()/applyBassBoost() skip the whole sendXThrottled() pipeline
    // - clone, throttle scheduling, and sendToHardware()'s own TurboSender2000 logging - on every
    // ~200ms volume-poll tick when the computed value hasn't actually changed since last time,
    // instead of only deduping at the very last step (the real RPC invoke) like before.
    private byte[] lastComputedEqData = null;
    private byte[] lastComputedSubData = null;
    private byte[] lastComputedBassBoostData = null;

    private LocationManager locationManager;
    private Map<String, String> playerMap = new HashMap<>();
    private final Map<Byte, byte[]> mcuCache = new HashMap<>();

    private final float[] fmOffsets = new float[16];
    private final byte[] eqData = new byte[12];
    private final byte[] subData = new byte[2];

    private final Intent volumeChangedIntent = new Intent("com.radiorubka.wdsp.VOLUME_CHANGED");
    private final Intent presetChangedIntent = new Intent("com.radiorubka.wdsp.PRESET_CHANGED");
    private final Intent galaUpdateIntent = new Intent("com.radiorubka.wdsp.GALA_UPDATE");
    private final Intent subGainChangedIntent = new Intent("com.radiorubka.wdsp.SUB_GAIN_CHANGED");

    private boolean isUiVisible = false;
    private boolean isBootStart = true;
    private String presetBeforeCall;

    private String galavoltype_last = VolumeHelper.getActivePlayerType();

    private int media_standstill = -1;
    private int btcall_standstill = -1;
    private int aux_standstill = -1;
    private int radio_standstill = -1;

    private void initReflection() {
        try {
            // noinspection PrivateApi
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
            }
            else if (key.equals(PREF_LAST_SELECTED)) {
                syncPreset(false);
            }
            else if (key.equals(PREF_GALA_GLOBAL_MODE)) {
                galaGlobalMode = prefs.getBoolean(PREF_GALA_GLOBAL_MODE, false);
                if (currentPresetName != null) loadPresetData(currentPresetName);
            }
            else if (key.startsWith(GALA_GLOBAL_NAMESPACE + "_")) {
                // The shared bucket changed (a slider dragged while global mode is on, or the
                // seed/crystallize copy fired when the global switch itself was flipped) -
                // refresh the cached GALA fields the same way a normal preset key change does.
                if (currentPresetName != null) loadPresetData(currentPresetName);
            }
            else if (currentPresetName != null && key.startsWith(currentPresetName + "_")) {

                // Reload the data first
                loadPresetData(currentPresetName);

                Log.d(TAG, "[TurboSender2000] Pref Changed: " + key);

                // 1. Check for Subwoofer first (specific) - _ultra_bass_* also only affects this
                // same sub-channel write (see updateSubwoofer()).
                if (key.contains("_sub") || key.contains("_ultra_bass")) {
                    updateSubwoofer(VolumeHelper.getVolume());
                }
                // 2. Then check for EQ bands or FM settings (less specific)
                else if (key.contains("_g") && !key.contains("_gala") || key.contains("_q") || key.contains("_fm") || key.contains("_fat_")) {
                    updateEqWithFm(VolumeHelper.getVolume());
                    // FM calibration/strength/enable also drive updateSubwoofer()'s Sub Comp
                    // offset and applyBassBoost()'s own bass-shelf assist (see their respective
                    // cachedSubComp/cachedFmEn branches) - without this, adjusting Cal
                    // Volume/Strength or toggling FM never re-sends either channel until an
                    // unrelated volume change happens to trigger applyVolumeDependentSettings()
                    // and pick it up incidentally.
                    if (key.contains("_fm")) {
                        updateSubwoofer(VolumeHelper.getVolume());
                        applyBassBoost(VolumeHelper.getVolume());
                    }
                }
                else if (key.contains("_power_vol")) {
                    setPowerAmpVol();
                }
                else if (key.contains("_d_")) {
                    applySpatialDelays();
                }
                else if (key.contains("_d1_") || key.contains("_rsse_")) {
                    applySurroundDelays();
                }
                else if (key.contains("_bb_") || key.contains("_bf_")) {
                    applyBassBoost(VolumeHelper.getVolume());
                    // _bb_frq_f specifically also changes which ISO_RAW_TARGET_BY_FREQ row the
                    // EQ's residual uses (see updateFmOffsets()) - resending unconditionally on
                    // any _bb_/_bf_ change is simpler than filtering to just that one key, and
                    // cheap since sendEqThrottled() is already rate-limited.
                    updateEqWithFm(VolumeHelper.getVolume());
                }
                else if (key.contains("_f_") || key.contains("_loud")) {
                    applyFaderLoud();
                }
//                else {
//                    Log.d(TAG, "[TurboSender2000] ApplyStaticSettings called: " + key);
//                    applyStaticSettings();
//                }
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
                else if ("com.radiorubka.wdsp.UI_ACTIVE".equals(action)) {
                    isUiVisible = true;
                    forceUiUpdate();
                }
                else if ("com.radiorubka.wdsp.UI_INACTIVE".equals(action)) {
                    isUiVisible = false;
                }
                else if ("com.radiorubka.wdsp.SIMULATE_SPEED".equals(action)) {
                    simulatedSpeedKmh = intent.getFloatExtra("speed", -1.0f);
                }
                else if ("com.radiorubka.wdsp.SUB_GAIN_UP".equals(action)) {
                    adjustSubGain(1);
                }
                else if ("com.radiorubka.wdsp.SUB_GAIN_DOWN".equals(action)) {
                    adjustSubGain(-1);
                }
            });
        }
    };

    /**
     * Bumps the subwoofer gain up/down by one step (matching seek_sub_gain's stepSize).
     * Persists to SharedPreferences so the existing prefListener picks it up and pushes
     * the change to the MCU (same path as the on-screen slider), and broadcasts the new
     * value so a running Activity can reflect it in the UI. Safe to call whether or not
     * the app's Activity is alive - the service is what starts with the system.
     */
    private void adjustSubGain(int delta) {
        backgroundHandler.post(() -> {
            if (currentPresetName == null) return;
            int newValue = Math.max(0, Math.min(12, cachedSubGain + delta));
            if (newValue == cachedSubGain) return;
            prefs.edit().putInt(currentPresetName + "_sub_g", newValue).apply();
            subGainChangedIntent.putExtra("subGain", newValue);
            sendBroadcast(subGainChangedIntent);
        });
    }

    private void forceUiUpdate() {
        // >= 0.0f → > 0.0f
        // consistent with checkVolumeAndGala(); 0.0f == Simulation off
        float speed = simulatedSpeedKmh > 0.0f ? simulatedSpeedKmh : currentSpeedKmh;
        int hardwareVol = VolumeHelper.getVolume();

        galaUpdateIntent.putExtra("speed", speed);
        int effectiveOffset = (baseStandstillVolume != -1) ? Math.max(0, hardwareVol - baseStandstillVolume) : 0;
        galaUpdateIntent.putExtra("waveOffset", effectiveOffset);
        sendBroadcast(galaUpdateIntent);

        volumeChangedIntent.putExtra("volume", hardwareVol);
        if (isUiVisible) sendBroadcast(volumeChangedIntent);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        volumeChangedIntent.setPackage(getPackageName());
        presetChangedIntent.setPackage(getPackageName());
        galaUpdateIntent.setPackage(getPackageName());
        subGainChangedIntent.setPackage(getPackageName());

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        workerThread = new HandlerThread("wDSP_Worker", -16); // THREAD_PRIORITY_AUDIO
        workerThread.start();
        backgroundHandler = new Handler(workerThread.getLooper());

        backgroundHandler.post(() -> {
            VolumeHelper.init(this);
            initReflection();
            prefs = getApplicationContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            currentPresetName = prefs.getString("Preset 1", "Preset 1");
            sendBroadcast(presetChangedIntent);
            // galaGlobalMode must be set before loadPresetData() runs - it decides which
            // "preset name" the GALA fields actually come from (see galaNamespace()).
            galaGlobalMode = prefs.getBoolean(PREF_GALA_GLOBAL_MODE, false);
            loadPresetData(currentPresetName);
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
            loadPlayerMap();
            syncPreset(true);
            isBootStart = false;
        });

        IntentFilter controlFilter = getIntentFilter();

        registerReceiver(controlReceiver, controlFilter);

        applyCurrentSettings();
    }

    @NonNull
    private static IntentFilter getIntentFilter() {
        IntentFilter controlFilter = new IntentFilter();
        controlFilter.addAction("com.qf.action.ACC_ON");
        controlFilter.addAction("com.qf.action.ACC_OFF");
        controlFilter.addAction("android.intent.action.QUICKBOOT_POWERON");
        controlFilter.addAction(Intent.ACTION_BOOT_COMPLETED);
        controlFilter.addAction("com.radiorubka.wdsp.UI_ACTIVE");
        controlFilter.addAction("com.radiorubka.wdsp.UI_INACTIVE");
        controlFilter.addAction("com.radiorubka.wdsp.SIMULATE_SPEED");
        controlFilter.addAction("com.radiorubka.wdsp.SET_POWER");
        controlFilter.addAction("com.radiorubka.wdsp.SUB_GAIN_UP");
        controlFilter.addAction("com.radiorubka.wdsp.SUB_GAIN_DOWN");
        return controlFilter;
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
            mainHandler.post(() -> Toaster.show(getApplicationContext(), "Preset Applied: " + currentPresetName));
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
        cachedFatStartVol = prefs.getInt(preset + "_fat_start_vol", 25);
        cachedFmCal = prefs.getInt(preset + "_fm_cal", 25);
        cachedFmStr = prefs.getInt(preset + "_fm_str", 100);
        cachedUltraBassEn = prefs.getBoolean(preset + "_ultra_bass_en", false);
        cachedUltraBassStartVol = prefs.getInt(preset + "_ultra_bass_start_vol", 16);
        cachedUltraBassMaxDb = prefs.getInt(preset + "_ultra_bass_max_db", 6);

        // GALA - galaGlobalMode must already be loaded by the time this runs, since it picks
        // which "preset name" these fields actually come from (see galaNamespace()).
        String galaNs = galaNamespace(preset);
        cachedGalaEn = prefs.getBoolean(galaNs + "_gala_enabled", false);
        cachedGalaInc = prefs.getInt(galaNs + "_gala_increment", 15);
        cachedGalaMinV = prefs.getInt(galaNs + "_gala_min_speed", 0);
//        cachedGalaMaxV = prefs.getInt(galaNs + "_gala_max_speed", 30);
        cachedGalaMaxAdj = prefs.getInt(galaNs + "_gala_max_adj", 12);
        cachedGalaFadeDelayMs = prefs.getInt(galaNs + "_gala_fade_ms", 100);
        cachedGalaHoldMs = prefs.getInt(galaNs + "_gala_hold_ms", 1000);
        // Always this preset's own key, never the global bucket.
        cachedGalaDisabledForPreset = prefs.getBoolean(preset + "_gala_disabled_for_preset", false);
    }

    private String galaNamespace(String presetName) {
        return galaGlobalMode ? GALA_GLOBAL_NAMESPACE : presetName;
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
            backgroundHandler.post(primaryRunnable);
            backgroundHandler.post(secondaryRunnable);
        }
    }

    private void stopPolling() {
        isPolling = false;
        backgroundHandler.removeCallbacks(primaryRunnable);
        backgroundHandler.removeCallbacks(secondaryRunnable);
    }

    private final Runnable secondaryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPolling) return;
            checkPlayer();
            checkForBug();
            backgroundHandler.postDelayed(this, 200);
        }
    };

    private final Runnable primaryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPolling) return;
            checkVolumeAndGala();
            backgroundHandler.postDelayed(this, 200);
        }
    };


    // This is used to check for the bug in the jitu (maybe also haiwai) f/w
    // the bug is caused by user turning the volume to 0 and then unmuting.
    // it COULD blow up the subwoofer, potentially.
    private void checkForBug() {
        if (VolumeHelper.getVolume() == 0 && !VolumeHelper.isHardwareMuted()) {
            VolumeHelper.setVolume(1);
        }
    }


    // True/false state actually used by GALA processing - cachedGalaEn already resolves to the
    // shared global switch or this preset's own, per galaNamespace(); cachedGalaDisabledForPreset
    // is a separate per-preset override that always applies on top of that, in either mode.
    private boolean isGalaEnabled() {
        return cachedGalaEn && !cachedGalaDisabledForPreset;
    }

    // THIS IS VERY FUCKED UP. IT WORKS??? MAYBE.
    private void checkVolumeAndGala() {

        boolean galaCurrentlyEnabled = isGalaEnabled();
        if (galaCurrentlyEnabled) {
            galaWasShutdown = false;
        }

        // Once GALA is off and has already faded back down to zero, skip the tier/fade
        // computation below (steps 2-6), but still fall through to step 7 so that
        // Fletcher-Munson/loudness EQ and the UI volume broadcast keep tracking manual
        // volume changes - previously this short-circuited the whole method and both of
        // those stopped updating for as long as GALA stayed off.
        boolean skipGalaProcessing = !galaCurrentlyEnabled && galaWasShutdown;

        // this gets the volume - needed by step 7 whether or not we skip GALA's own processing
        int hardwareVol = VolumeHelper.getVolume();

        if (!skipGalaProcessing) {

        // gets the player type that is currently active
        String galavoltype = VolumeHelper.getActivePlayerType();

        // if the player has changed since the last run
        if (!galavoltype.equals(galavoltype_last)) {
            // and the base volume is already established
            if (baseStandstillVolume != -1) {
                // save the last standstill volume recorded by the algorithm
                if (galavoltype_last.equals("media_type")) {
                    media_standstill = baseStandstillVolume;
                }
                if (galavoltype_last.equals("btcall_type")) {
                    btcall_standstill = baseStandstillVolume;
                }
                if (galavoltype_last.equals("aux_type")) {
                    aux_standstill = baseStandstillVolume;
                }
                if (galavoltype_last.equals("radio_type")) {
                    radio_standstill = baseStandstillVolume;
                }
                // set the base volume to the last known volume for this type
                // if not known, set to current for this type
                if (galavoltype.equals("media_type")) {
                    if (media_standstill != -1) {
                        baseStandstillVolume = media_standstill;
                    }
                    else {
                        baseStandstillVolume = VolumeHelper.getVolume();
                    }
                }
                if (galavoltype.equals("btcall_type")) {
                    if (btcall_standstill != -1) {
                        baseStandstillVolume = btcall_standstill;
                    }
                    else {
                        baseStandstillVolume = VolumeHelper.getVolume();
                    }
                }
                if (galavoltype.equals("aux_type")) {
                    if (aux_standstill != -1) {
                        baseStandstillVolume = aux_standstill;
                    }
                    else {
                        baseStandstillVolume = VolumeHelper.getVolume();
                    }
                }
                if (galavoltype.equals("radio_type")) {
                    if (radio_standstill != -1) {
                        baseStandstillVolume = radio_standstill;
                    }
                    else {
                        baseStandstillVolume = VolumeHelper.getVolume();
                    }
                }

                // Push the just-restored base (+ whatever GALA offset is currently active) to
                // hardware immediately, and sync lastReadHardwareVol/lastAppliedVolume to it,
                // instead of leaving this to step 5/6 below. The head unit's own OS remembers
                // each source's volume independently and has usually already jumped hardwareVol
                // (read at the top of this method, before this block ran) to the NEW source's own
                // natural volume by the time we get here - which looks exactly like a manual
                // knob-turn to step 5's detector (hardwareVol differs from the OLD source's
                // lastReadHardwareVol/lastAppliedVolume), so without this fix step 5 immediately
                // recomputes baseStandstillVolume as hardwareVol - the OLD currentAppliedOffset,
                // silently corrupting the base we just restored (and re-saving that corruption
                // into this source's own _standstill variable next time it's switched away from).
                // This was the reported "switch to radio while Gaja is active shows the right
                // volume at first, but once Gaja decays back to 0 the volume is wrong" bug, and
                // the "switching sources, the other source is still remembered with a stale Gaja
                // offset baked in" bug. Same "sync tracking vars, return early" pattern as the
                // unmute-recovery block (step 3) above uses for the same reason.
                int targetVol = Math.min(32, baseStandstillVolume + currentAppliedOffset);
                if (hardwareVol != targetVol) {
                    VolumeHelper.setVolume(targetVol);
                }
                lastAppliedVolume = targetVol;
                lastReadHardwareVol = targetVol;
                galavoltype_last = galavoltype;
                return; // Exit this poll to let the hardware stabilize
            }
        }

        // this gets the speed
        float speed = simulatedSpeedKmh > 0.0f ? simulatedSpeedKmh : currentSpeedKmh;

        // this gets the mute state
        boolean isCurrentlyMuted = (hardwareVol <= 0 || VolumeHelper.isHardwareMuted());

        // 1. THE GUARD: If muted or at volume 0, stop GALA processing immediately.
        // This prevents the bug with the volume being set to 1 and stops GALA from unmuting the system.
        if (isCurrentlyMuted) {
            wasMuted = true;
            lastReadHardwareVol = hardwareVol;
            lastAppliedVolume = hardwareVol;

            // Still update UI if visible so the speed needle/text moves while muted
            if (isUiVisible) {
                galaUpdateIntent.putExtra("speed", speed);
                galaUpdateIntent.putExtra("waveOffset", 0);
                sendBroadcast(galaUpdateIntent);
            }
            return;
        }

        // 2. PRE-CALCULATE RAW OFFSET: What should the GALA boost be at the current speed?
        int rawOffset = 0;
        if (isGalaEnabled()) {
            int speedIncrement = Math.max(1, cachedGalaInc + 5);
            int minSpeed = cachedGalaMinV * 5;
            if (speed >= minSpeed) {
                rawOffset = (int) ((speed - minSpeed) / speedIncrement);
                rawOffset = Math.min(rawOffset, cachedGalaMaxAdj);
            }
        }

        // 3. THE UNMUTE RECOVERY: If we just came out of a muted/zero state,
        // skip fade & timer — jump directly to the correct offset.
        if (wasMuted) {
            wasMuted = false;
            currentAppliedOffset = rawOffset;
            pendingTargetOffset  = rawOffset;
            lastGalaTier         = rawOffset;
            tierChangeTimestamp  = System.currentTimeMillis();
            if (baseStandstillVolume != -1 && rawOffset != 0) {
                int targetVol = Math.min(32, baseStandstillVolume + rawOffset);
                VolumeHelper.setVolume(targetVol);
                lastAppliedVolume   = targetVol;
                lastReadHardwareVol = targetVol; // set Tracking-Vars to targetVol. No wrong Manual-Adjust in next cycle
                Log.d(TAG, "Unmuted: Restoring Base(" + baseStandstillVolume + ") + Offset(" + rawOffset + ") = " + targetVol);
                return; // Exit this poll to let the hardware stabilize
            }
            lastReadHardwareVol = hardwareVol;
            lastAppliedVolume   = hardwareVol;
        }

        // 4. INITIALIZE BASE: If this is the first run after service start.
        // pendingTargetOffset is set directly to rawOffset here, which bypasses the hold timer
        // for the first cycle. This is intentional, GALA should take effect immediately upon boot,
        // without waiting 3 seconds for GPS stabilization. The hold timer takes effect starting with the second tier change.
        if (baseStandstillVolume == -1) {
            int initApplied = (currentAppliedOffset >= 0) ? currentAppliedOffset : rawOffset;
            baseStandstillVolume = Math.max(0, hardwareVol - initApplied);
            lastReadHardwareVol  = hardwareVol;
            lastAppliedVolume    = hardwareVol;
            currentAppliedOffset = initApplied;
            pendingTargetOffset  = rawOffset;
            lastGalaTier         = rawOffset;
            tierChangeTimestamp  = System.currentTimeMillis();
        }

        // 5. MANUAL ADJUSTMENT: If the user turned the knob/steering wheel.
        // We detect this because the hardware volume changed, but NOT by our script.
        // get the volume again so we have the freshest one
        if (hardwareVol != lastReadHardwareVol && hardwareVol != lastAppliedVolume) {
            baseStandstillVolume = Math.max(0, hardwareVol - currentAppliedOffset);
            if (hardwareVol < currentAppliedOffset) {
                baseStandstillVolume = 0;
            }
            lastAppliedVolume = hardwareVol;
            Log.d(TAG, "Manual Adjust: New Vol=" + hardwareVol + " -> New Base=" + baseStandstillVolume);
        }

        // 6. GALA APPLICATION with Hold-Timer and Fade
        if (galaCurrentlyEnabled) {
            long now = System.currentTimeMillis();

            // 6a. HOLD-TIMER: Has the tier changed?
            if (rawOffset != lastGalaTier) {
                // New tier seen — record the timestamp and remember it
                lastGalaTier        = rawOffset;
                tierChangeTimestamp = now;
                // pendingTargetOffset stays at the OLD value until the hold expires
            }

            // 6b. Only release the new target after it has been stable for cachedGalaHoldMs
            if (now - tierChangeTimestamp >= cachedGalaHoldMs) {
                pendingTargetOffset = lastGalaTier;
            }

            // 6c. FADE: move currentAppliedOffset one step towards pendingTargetOffset
            if (currentAppliedOffset < 0) currentAppliedOffset = 0; // first-run safety
            if (currentAppliedOffset != pendingTargetOffset) {
                long fadeDelay = Math.max(0, cachedGalaFadeDelayMs);
                if (now - lastFadeStepTime >= fadeDelay) {
                    currentAppliedOffset += (currentAppliedOffset < pendingTargetOffset) ? 1 : -1;
                    lastFadeStepTime = now;
                    Log.v(TAG, "GALA Fade: appliedOffset=" + currentAppliedOffset + " target=" + pendingTargetOffset);
                }
            }

            // 6d. Apply the faded offset to the hardware
            int targetVol = Math.min(32, baseStandstillVolume + currentAppliedOffset);
            if (hardwareVol != targetVol) {
                VolumeHelper.setVolume(targetVol);
                lastAppliedVolume = targetVol;
                Log.v(TAG, "GALA Update: vol=" + lastReadHardwareVol + " -> " + targetVol + " (offset=" + currentAppliedOffset + ")");
            }

            if (isUiVisible) {
                galaUpdateIntent.putExtra("speed", speed);
                galaUpdateIntent.putExtra("waveOffset", currentAppliedOffset);
                galaUpdateIntent.putExtra("base", baseStandstillVolume);
                sendBroadcast(galaUpdateIntent);
            }

        } else {

            pendingTargetOffset = 0;
            lastGalaTier        = 0;

            if (currentAppliedOffset < 0) {
                currentAppliedOffset = 0;
                lastAppliedVolume = hardwareVol;;
            }

            if (currentAppliedOffset > 0) {
                long now = System.currentTimeMillis();
                long fadeDelay = Math.max(0, cachedGalaFadeDelayMs);
                if (now - lastFadeStepTime >= fadeDelay) {
                    currentAppliedOffset--;
                    lastFadeStepTime = now;
                    Log.v(TAG, "GALA Fade-Out: appliedOffset=" + currentAppliedOffset);
                }
            }

            // Sync hardware to baseStandstillVolume + currentAppliedOffset unconditionally (not
            // just while currentAppliedOffset > 0, which this used to be nested under) - mirrors
            // the galaCurrentlyEnabled branch's step 6d above. Without this, switching audio
            // sources while GALA is already settled at offset 0 (e.g. car stopped, or GALA
            // toggled off) correctly restores that source's own saved baseStandstillVolume (see
            // the per-source save/restore block near the top of this method) but the actual
            // hardware volume never gets told about it, leaving it stuck at the OTHER source's
            // volume until something else happens to touch it - this was the reported "switch
            // source A -> B -> back to A, volume doesn't go back to A's own level" bug.
            int targetVol = Math.min(32, baseStandstillVolume + currentAppliedOffset);
            if (hardwareVol != targetVol) {
                VolumeHelper.setVolume(targetVol);
                lastAppliedVolume = targetVol;
            }

            if (isUiVisible) {
                galaUpdateIntent.putExtra("waveOffset", currentAppliedOffset);
                galaUpdateIntent.putExtra("base", baseStandstillVolume);
                sendBroadcast(galaUpdateIntent);
            }

            if (hardwareVol == baseStandstillVolume) {
                galaUpdateIntent.putExtra("base", 0);
                sendBroadcast(galaUpdateIntent);
                galaWasShutdown = true;
            }
        }

        }
        else if (isUiVisible) {
            float shortCircuitSpeed = simulatedSpeedKmh > 0.0f ? simulatedSpeedKmh : currentSpeedKmh;
            galaUpdateIntent.putExtra("speed", shortCircuitSpeed);
            galaUpdateIntent.putExtra("waveOffset", 0);
            sendBroadcast(galaUpdateIntent);
        }

        // 7. TRACKING: Update last seen volume and handle UI volume sync.
        lastReadHardwareVol = hardwareVol;

        if (hardwareVol != lastVolumeRead) {
            lastVolumeRead = hardwareVol;
            // cachedFatEn (Trim Highs) and cachedUltraBassEn (Ultra Bass) are both independent
            // toggles from cachedFmEn (Loudness) - they need this same live re-trigger as volume
            // changes while driving, or they just freeze at whatever was last computed at
            // (boot/preset load/a related pref edit) instead of tracking volume the way
            // MainActivity's own preview already does via the VOLUME_CHANGED broadcast below (see
            // updateFmOffsets()/updateSubwoofer(), which already handle them correctly - it was
            // only this call site gating them out).
            if (cachedFmEn || cachedFatEn || cachedUltraBassEn) {
                applyVolumeDependentSettings(hardwareVol); // Update EQ/Fletcher-Munson
            }
            if (isUiVisible) {
                volumeChangedIntent.putExtra("volume", hardwareVol);
                sendBroadcast(volumeChangedIntent);
            }
        }

        galavoltype_last = VolumeHelper.getActivePlayerType();
    }

    private void checkPlayer() {
        String currentPlayer = getSystemProperty();

        // Process the naming convention for the "unknown" preset.
        if ("nothing".equalsIgnoreCase(currentPlayer) || "Unknown".equalsIgnoreCase(currentPlayer)) {
            currentPlayer = "Default";
        }
        // If btcall_type, set the Player to be "Call".
        if (VolumeHelper.getActivePlayerType().equals("btcall_type")) {
            lastPlayerSource = "Call";
            processPlayerSwitch("Call");
        }
        // If the last Player doesn't match the new Player, process the switch.
        else if (!Objects.equals(currentPlayer, lastPlayerSource)) {
            lastPlayerSource = currentPlayer;
            processPlayerSwitch(currentPlayer);
        }
    }

    private void processPlayerSwitch(String currentPlayer) {
        String presetToLoad = playerMap.get(currentPlayer);
        String defaultPreset = playerMap.get("Default");

        // Redundant logic for the Default preset in old versions.
        //if (presetToLoad == null && (currentPlayer.isEmpty() || currentPlayer.equals("Unknown"))) {
        //    presetToLoad = playerMap.get("Unknown");
        //}
        //if (presetToLoad == null) {
        //    presetToLoad = prefs.getString(PREF_DEFAULT_PRESET, null);
        //}

        // Process Call switch; If Call is the Player and the current Preset is not Call, queue to Call preset,
        // save last applied preset
        if (currentPlayer.equals("Call") && !currentPresetName.equals("Call")) {
            presetBeforeCall = currentPresetName;
            presetToLoad = "Call";
        }
        // If Call is not the Player, queue to the preset that was active before Call if the Default preset doesn't exist
        // or queue to Default if it does
        else if (currentPresetName.equals("Call") && !currentPlayer.equals("Call") && presetToLoad == null) {
            if (defaultPreset == null) {
                presetToLoad = presetBeforeCall;
            }
            else {
                presetToLoad = defaultPreset;
            }
        }

        // Process the switch if the current preset doesn't already match the Player.
        if (presetToLoad != null && !presetToLoad.equals(currentPresetName)) {
            if (!isUiVisible) {
                //Toast.makeText(McuService.this, "Auto applied preset: " + presetToLoad, Toast.LENGTH_SHORT).show();
                Toaster.show(this, presetToLoad);
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
        applyBassBoost(currentVol);
    }

    private void updateEqWithFm(int currentVol) {
        updateFmOffsets(currentVol);
        eqData[0] = (byte) 0x80;

        boolean hasOffset = false;
        float[] targetDb = new float[16];
        for (int i = 0; i < 16; i++) {
            targetDb[i] = (cachedGains[i] - 6) * 2 + fmOffsets[i];
            if (fmOffsets[i] != 0f) hasOffset = true;
        }
        // Jointly pre-warp slider+offset together while loudness is actually contributing
        // something, so the real achieved curve cross-talk-cancels BOTH the offset and
        // whatever shape the manual sliders have, not just the offset alone (see
        // AudioConfig.prewarpEq()'s doc). With no offset, driveDb ends up equal to targetDb
        // anyway - skip the multiply then so manual-only EQ (no loudness) is byte-for-byte
        // identical to before this existed.
        float[] driveDb = hasOffset ? AudioConfig.prewarpEq(targetDb) : targetDb;

        for (int i = 0; i < 8; i++) {
            int b1 = i * 2;
            int idx1 = Math.max(0, Math.min(12, Math.round((driveDb[b1] / 2.0f) + 6)));

            int b2 = i * 2 + 1;
            int idx2 = Math.max(0, Math.min(12, Math.round((driveDb[b2] / 2.0f) + 6)));

            eqData[i + 1] = (byte) ((idx2 << 4) | (idx1 & 0x0F));
        }
        eqData[9] = cachedQByte1;
        eqData[10] = cachedQByte2;
        eqData[11] = 0x00;
        if (lastComputedEqData != null && Arrays.equals(lastComputedEqData, eqData)) return;
        lastComputedEqData = eqData.clone();
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
            // The EQ's residual job depends on which frequency the Bass Boost shelf is actually
            // carrying the low end at right now (front channel; see AudioConfig.
            // isoRawTargetForFreqIdx()'s doc) - only meaningful while applyBassBoost()'s own
            // assist is also active, but harmless to read unconditionally here. This is the RAW
            // (not pre-warped) target - updateEqWithFm() jointly pre-warps it together with the
            // slider's own dB via AudioConfig.prewarpEq(), so don't pre-warp it again here.
            int manualFreqIdxF = prefs.getInt(currentPresetName + "_bb_frq_f", 0);
            float[] isoRawTarget = AudioConfig.isoRawTargetForFreqIdx(manualFreqIdxF);
            for (int i = 0; i < 16; i++) {
                fmOffsets[i] = isoRawTarget[i] * ratio * strength;
            }
        }
        else if (cachedFatEn && vol > (cachedFatStartVol + deadzone)) {
            float range = (float) Math.max(1, 32 - (cachedFatStartVol + deadzone));
            float ratio = (vol - (cachedFatStartVol + deadzone)) / range;
            for (int i = 0; i < 16; i++) {
                fmOffsets[i] = AudioConfig.FATIGUE_RAW_TARGET[i] * ratio * strength;
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

        // "Ultra Bass" - plain linear ramp with volume, 0 at cachedUltraBassStartVol up to
        // cachedUltraBassMaxDb at volume 32, completely independent of Loudness/cachedFmEn/
        // cachedFmCal - see MainActivity.calculateUltraBassOffset() for the identical preview calc.
        float ultraBassOffset = 0f;
        if (cachedUltraBassEn && currentVol > cachedUltraBassStartVol) {
            float ubRange = Math.max(1, 32 - cachedUltraBassStartVol);
            float ubRatio = Math.min(1f, (currentVol - cachedUltraBassStartVol) / ubRange);
            ultraBassOffset = cachedUltraBassMaxDb * ubRatio;
        }

        int finalGainIdx = Math.max(0, Math.min(12, Math.round(cachedSubGain + subOffset + ultraBassOffset)));
        subData[1] = (byte) ((cachedSubFreq << 4) | (finalGainIdx & 0x0F));
        if (lastComputedSubData != null && Arrays.equals(lastComputedSubData, subData)) return;
        lastComputedSubData = subData.clone();
        sendSubThrottled(subData);
    }

    // Sub crossover frequency -> loudness boost lookup. Deliberately references the NEXT LOWER
    // EQ band's (bigger) offset rather than the matching band, since the matching-band offsets
    // alone proved visually/audibly insufficient - confirmed by eye against the sub curve overlay
    // in EqVisualizerView/FmVisualizerView (see AudioConfig.subFilterResponseDb()).
    // No branch exists above 80Hz (100/125/160/200/250 all fall through to 0) - intentional, not a
    // gap. The ISO 226 loudness-compensation curve this assist reinforces is steepest around
    // 20-80Hz (where ears lose the most sensitivity at low volume); a crossover set at 100Hz+ isn't
    // "deep bass" any more and already sits inside the main 16-band EQ's own directly-compensated
    // range (fmOffsets, same ISO_RAW_TARGET_BY_FREQ row) - an extra assist here would just double-
    // compensate the same region rather than model anything real.
    private float getMaxBassBoost() {
        int[] freqs = {25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250};
        int freq = (cachedSubFreq >= 0 && cachedSubFreq < freqs.length) ? freqs[cachedSubFreq] : 80;

        if (freq == 80) return AudioConfig.ISO_FULL_TARGET_DB[2];
        if (freq == 63 || freq == 50) return AudioConfig.ISO_FULL_TARGET_DB[1];
        if (freq == 40 || freq == 32) return AudioConfig.ISO_FULL_TARGET_DB[0];
        if (freq == 25) return AudioConfig.ISO_FULL_TARGET_DB[0];
        return 0;
    }

    /**
     * Sends the front/rear "Bass Boost" shelf (0x88) - the user's own manually-saved
     * frequency/gain, plus loudness compensation's bass-shelf assist (see
     * AudioConfig.LOUDNESS_BASS_SHELF_MAX_DB's doc) when active. Needs currentVol (unlike the
     * old static-prefs-only version) since the assist's strength depends on how far below the
     * calibration volume we currently are - same ratio/strength math as
     * updateFmOffsets()/updateSubwoofer()'s own branches, computed separately here since this
     * writes a different packet on its own throttled cadence (see sendBassBoostThrottled()).
     */
    private void applyBassBoost(int currentVol) {
        float shelfOffset = 0f;
        if (cachedFmEn) {
            int deadzone = 1;
            if (currentVol < (cachedFmCal - deadzone)) {
                float range = Math.max(1, cachedFmCal - deadzone);
                float ratio = (range - currentVol) / range;
                shelfOffset = AudioConfig.LOUDNESS_BASS_SHELF_MAX_DB * ratio * (cachedFmStr / 100f);
            }
        }

        int manualFreqIdxF = prefs.getInt(currentPresetName + "_bb_frq_f", 0);
        int manualGainF = prefs.getInt(currentPresetName + "_bb_f", 0);
        int manualFreqIdxR = prefs.getInt(currentPresetName + "_bb_frq_r", 0);
        int manualGainR = prefs.getInt(currentPresetName + "_bb_r", 0);

        int freqIdxF, gainF, freqIdxR, gainR;
        if (cachedFmEn) {
            // Gated on Loudness being enabled, not on shelfOffset>0 - rear stays synced to front
            // for the whole time Loudness is on, not just the moments it's actively below
            // calibration (see MainActivity.updateBassVisualizer()'s identical widened gate for
            // the preview/widgets). When shelfOffset is 0 (volume currently above calibration),
            // gainF reduces to manualGainF exactly, so this is a strict superset of the old
            // behavior, not a change to what gets sent whenever the assist actually has
            // magnitude. Front follows its own manually-chosen frequency (see MainActivity.
            // combineBassShelfFreq()'s identical logic for the preview) - so the manual gain
            // always adds on top, no mismatch to gate on. "off" (idx 0) has no frequency of its
            // own to contribute, so the assist falls back to the tuned default/most-optimal
            // frequency and no manual gain applies there.
            freqIdxF = manualFreqIdxF > 0 ? manualFreqIdxF : AudioConfig.LOUDNESS_BASS_SHELF_FREQ_IDX;
            gainF = Math.max(0, Math.min(12, Math.round(shelfOffset + (manualFreqIdxF > 0 ? manualGainF : 0))));
            // Rear's Boost (gain+freq) syncs to front's own whenever Loudness is enabled - keeps
            // rear's shelf matching the shared EQ curve, which is solved against front's
            // frequency only (see AudioConfig.isoRawTargetForFreqIdx()'s doc). Rear's manual
            // prefs are never touched, just not used for the real write while this is active.
            freqIdxR = freqIdxF;
            gainR = gainF;
        } else {
            freqIdxF = manualFreqIdxF; gainF = manualGainF;
            freqIdxR = manualFreqIdxR; gainR = manualGainR;
        }

        byte[] bbData = new byte[]{(byte) 0x88,
                (byte) (((freqIdxF + 8) << 4) | (gainF & 0x0F)),
                (byte) (((freqIdxR + 8) << 4) | (gainR & 0x0F)),
                (byte) ((prefs.getInt(currentPresetName + "_bf_f", 0) << 4) | (prefs.getInt(currentPresetName + "_bf_r", 0) & 0x0F))};
        if (lastComputedBassBoostData != null && Arrays.equals(lastComputedBassBoostData, bbData)) return;
        lastComputedBassBoostData = bbData;
        sendBassBoostThrottled(bbData);
    }

    private void applyFaderLoud() {
        sendToHardware(new byte[]{(byte) 0x81,
                (byte) (prefs.getInt(currentPresetName + "_f_lr", 12) & 0xFF),
                (byte) (prefs.getInt(currentPresetName + "_f_fr", 12) & 0xFF),
                (byte) (prefs.getBoolean(currentPresetName + "_loud", false) ? 1 : 0)});
    }

    private void applySpatialDelays() {
        if (prefs.getBoolean(currentPresetName + "_d_en", false)) {
            byte[] d8c = new byte[6]; d8c[0] = (byte) 0x8C;
            d8c[1] = (byte) ((prefs.getInt(currentPresetName + "_d_fl", 0) * 5) & 0xFF);
            d8c[2] = (byte) ((prefs.getInt(currentPresetName + "_d_fr", 0) * 5) & 0xFF);
            d8c[3] = (byte) ((prefs.getInt(currentPresetName + "_d_rl", 0) * 5) & 0xFF);
            d8c[4] = (byte) ((prefs.getInt(currentPresetName + "_d_rr", 0) * 5) & 0xFF);
            d8c[5] = (byte) ((prefs.getInt(currentPresetName + "_d_sub", 0) * 5) & 0xFF);
            sendToHardware(d8c);
        }
        else {
            byte[] d8c = new byte[6]; d8c[0] = (byte) 0x8C;
            sendToHardware(d8c);
        }
    }

    private void applySurroundDelays() {
        if (prefs.getBoolean(currentPresetName + "_d1_en", false)) {
            byte[] d89 = new byte[6]; d89[0] = (byte) 0x89;
            d89[1] = (byte) (138 + (prefs.getInt(currentPresetName + "_rsse_val", 10) - 10));
            d89[2] = (byte) (prefs.getInt(currentPresetName + "_d1_fl", 0) & 0xFF);
            d89[3] = (byte) (prefs.getInt(currentPresetName + "_d1_fr", 0) & 0xFF);
            d89[4] = (byte) (prefs.getInt(currentPresetName + "_d1_rl", 0) & 0xFF);
            d89[5] = (byte) (prefs.getInt(currentPresetName + "_d1_rr", 0) & 0xFF);
            sendToHardware(d89);
        }
        else {
            byte[] d89 = new byte[6]; d89[0] = (byte) 0x89;
            sendToHardware(d89);
        }
    }

    private void applyStaticSettings() {
        if (currentPresetName == null) return;

        applyBassBoost(VolumeHelper.getVolume());

        applyFaderLoud();

        applySpatialDelays();

        applySurroundDelays();

        setPowerAmpVol();
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

    // --- BASS BOOST THROTTLER --- (see applyBassBoost()'s doc for why this now needs
    // throttling like the EQ/sub above - it used to only fire on rare discrete UI events, now it
    // also refreshes every volume poll cycle for the loudness bass-shelf assist.)
    private void sendBassBoostThrottled(byte[] data) {
        pendingBassBoostData = data.clone();
        if (bassBoostUpdatePending) return;

        long now = System.currentTimeMillis();
        long elapsed = now - lastBassBoostWriteTime;

        if (elapsed >= THROTTLE_MS) {
            executeBassBoostWrite();
        } else {
            bassBoostUpdatePending = true;
            backgroundHandler.postDelayed(this::executeBassBoostWrite, THROTTLE_MS - elapsed);
        }
    }

    private void executeBassBoostWrite() {
        if (pendingBassBoostData != null) {
            sendToHardware(pendingBassBoostData);
            lastBassBoostWriteTime = System.currentTimeMillis();
        }
        bassBoostUpdatePending = false;
    }

    private void sendToHardware(byte[] data) {
        if (data == null || data.length == 0) return;
        byte cmd = data[0];

        byte[] cached = mcuCache.get(cmd);
        if (cached == null || !Arrays.equals(cached, data)) {
            // TurboSender2000 - moved inside this dedupe check (was unconditional before) so
            // logcat only sees an entry when something is actually being written, not on every
            // throttled flush of an unchanged value.
            String turboSender = java.util.stream.IntStream.range(0, data.length)
                    .mapToObj(i -> String.format("%02X", data[i]))
                    .collect(java.util.stream.Collectors.joining(" "));

            String turboSenderType = "[NO_IDEA_WHAT_THIS_IS]: ";
            if (cmd == (byte) 0x8B) {
                turboSenderType = "[SUB]: ";
            }
            if (cmd == (byte) 0x88) {
                turboSenderType = "[BASS_BOOST]: ";
            }
            if (cmd == (byte) 0x81) {
                turboSenderType = "[FADER_LOUD_LEGACY]: ";
            }
            if (cmd == (byte) 0x89) {
                turboSenderType = "[SPATIAL_DELAYS]: ";
            }
            if (cmd == (byte) 0x8c) {
                turboSenderType = "[SURROUND_DELAYS]: ";
            }
            if (cmd == (byte) 0x80) {
                turboSenderType = "[EQ]: ";
            }

            Log.d(TAG, "[TurboSender2000] SendToHardware invoked with " + turboSenderType + turboSender);

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
            @SuppressLint("PrivateApi") Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "mcu_service");
            if (binder != null) {
                @SuppressLint("PrivateApi") Class<?> stub = Class.forName("android.qf.mcu.IMcuManager$Stub");
                mcuManagerInstance = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);

                if (mcuManagerInstance != null) {
                    // Existing EQ method
                    setEqDataMethod = mcuManagerInstance.getClass().getMethod("RPC_SetEQData", byte[].class);

                    // NEW: Reflect RPC_SendMcuMsgData(byte cmd, byte[] data, int length)
                    setMcuMsgMethod = mcuManagerInstance.getClass().getMethod("RPC_SendMcuMsgData",
                            byte.class, byte[].class, int.class);
                }
            }
        }
    }

    public void setPowerAmpVol() {

        int val = prefs.getInt(currentPresetName + "_power_vol", 0);

        backgroundHandler.post(() -> {
            byte[] bArr = {2, (byte) val}; // Sub-ID 2, followed by value
            try {
                ensureMcuManager();
                if (setMcuMsgMethod != null && mcuManagerInstance != null) {
                    // Invoke: RPC_SendMcuMsgData((byte)24, bArr, 2)
                    setMcuMsgMethod.invoke(mcuManagerInstance, (byte) 24, bArr, bArr.length);
                    Log.d(TAG, "PowerAmpVol set to: " + val);
                }
                else {
                    Log.e(TAG, "setMcuMsgMethod or mcuManagerInstance is null, val:" + val);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to set PowerAmpVol: " + e.getMessage());
            }
        });

        Log.d(TAG, "[TurboSender2000] PowerAmpVol set to: " + -val);
    }

    private String getSystemProperty() {
        try {
            if (getPropMethod != null) {
                return (String) getPropMethod.invoke(null, "sys.qf.last_audio_src", "Unknown");
            }
        } catch (Exception ignored) {}
        return "";
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
        backgroundHandler.post(() -> currentSpeedKmh = location.getSpeed() * 3.6f);
    }

    @Override public void onProviderEnabled(@NonNull String provider) {}
    @Override public void onProviderDisabled(@NonNull String provider) {}

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "wDSP Background Service", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
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
