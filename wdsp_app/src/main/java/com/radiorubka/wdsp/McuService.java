package com.radiorubka.wdsp;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.media.AudioManager;
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
import java.util.Locale;
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
    private static final String PREF_GALA_GLOBAL_ENABLED = "gala_global_enabled";
    
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

    // GALA settings
    private boolean cachedGalaEn;
    /**
     * Highest the standstill slider goes, in slider steps of 5 km/h - so 40 means 200 km/h.
     *
     * <p>The same number is enforced in {@code MainActivity.loadPreset}. If either moves without
     * the other, the screen and this service go back to computing GALA from different figures,
     * which is the failure this constant exists to end.
     */
    private static final int GALA_MIN_SPEED_CEILING = 40;

    private int cachedGalaInc;
    private int cachedGalaMinV;
    // private int cachedGalaMaxV;
    private int cachedGalaMaxAdj;
    private int cachedGalaFadeDelayMs; // ms between each ±1 fade step (default 100)
    private int cachedGalaHoldMs;      // ms a tier must be stable before being applied (default 1000)


    // When galaGlobalMode is on, GALA's on/off state is shared across all presets
    // (galaGlobalEnabled) instead of read from cachedGalaEn per-preset - see isGalaEnabled().
    private boolean galaGlobalMode;
    private boolean galaGlobalEnabled;
    
    /**
     * Speed in km/h - the real one from GPS, and the simulated one from the slider in Settings.
     *
     * <p>Both are read and written only on the worker thread, which is worth knowing before
     * anyone reaches for {@code volatile} here: {@code controlReceiver} looks like it runs on the
     * main thread, and its body does not - the whole of {@code onReceive} is posted to
     * {@code backgroundHandler}. {@code onLocationChanged} posts as well. So there is one thread
     * involved and no visibility problem to solve.
     */
    private float currentSpeedKmh = 0.0f;
    /** Last offset the simulator diagnostic reported, so it logs on change only. */
    private int lastLoggedSimOffset = Integer.MIN_VALUE;
    private float simulatedSpeedKmh = 0.0f;
    private int baseStandstillVolume = -1;

    /**
     * The audio ownership contract with QF Radio - see {@code .agents/AUDIO_OWNERSHIP_CONTRACT.md}.
     *
     * <p>Radio and this service are two state machines on one MCU path. On a volume change the
     * radio synchronises the levels and holds the FM channel; on the same change this service
     * recomputes the EQ, because the curve depends on volume. Polling at 100 ms, we always arrive
     * second, chasing intermediate values and landing on top of a state the radio had just
     * finished arranging.
     *
     * <p>So ownership is split rather than shared. The radio owns the <b>base level</b> and the
     * channel; we own the <b>offset</b> on top of it (GALA), the EQ, the tone and the subwoofer.
     * The radio sends one signal after its last write, and we act once instead of racing.
     *
     * <p>🔑 The {@code volume} extra is <b>advisory and deliberately ignored</b>: the two sides do
     * not share a scale - we clamp to 32, {@code STREAM_MUSIC} on these units reports a maximum of
     * 15 - and stitching scales across IPC is its own class of bug. The contract is the edge, not
     * the number, so the level is always re-read here through {@link VolumeHelper}.
     */
    static final String ACTION_AUDIO_STATE_STABLE = "com.radiorubka.wdsp.AUDIO_STATE_STABLE";
    private static final String RADIO_PACKAGE = "com.kostyamat.fmradio";
    private static final String ACTION_AUDIO_STATE_QUERY = RADIO_PACKAGE + ".AUDIO_STATE_QUERY";

    /** Last {@code seq} accepted, so a late or repeated signal is dropped rather than acted on. */
    private int lastAudioStateSeq = Integer.MIN_VALUE;

    // GALA fade & hold-timer state
    private int currentAppliedOffset = -1; // the offset currently SET on the hardware
    private int pendingTargetOffset  = -1; // the offset we want to reach (after hold timer)
    private int lastGalaTier         = -1; // last confirmed tier index
    private long tierChangeTimestamp = 0;   // when the current pending tier was first seen
    private long lastFadeStepTime    = 0;   // last time we moved applied offset by ±1

    private boolean wasMuted = false;

    private int lastReadHardwareVol = -1;

    // What the hardware last REPORTED, which is not the same thing as what we last sent it.

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

    // Exactly what the DSP was last told - Fletcher-Munson already folded in, quantised and
    // clamped the same way the hardware sees it. Handed to the spectrum analyser so it can show
    // the processed sound instead of the slider positions.
    private final int[] effectiveGainIdx = new int[16];
    private int effectiveSubGainIdx = 0;

    private final Intent volumeChangedIntent = new Intent("com.radiorubka.wdsp.VOLUME_CHANGED");
    private final Intent presetChangedIntent = new Intent("com.radiorubka.wdsp.PRESET_CHANGED");
    private final Intent galaUpdateIntent = new Intent("com.radiorubka.wdsp.GALA_UPDATE");
    private final Intent subGainChangedIntent = new Intent("com.radiorubka.wdsp.SUB_GAIN_CHANGED");

    private boolean isUiVisible = false;
    private boolean isBootStart = true;
    private String presetBeforeCall;

    private String galavoltype_last = VolumeHelper.getActivePlayerType();

    private StatusBarVisualizerManager statusBarManager;

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
            // One line that says which sound processor is fitted and whether the capture
            // path carries voice processing - both change what this app may promise.
            Log.i(TAG, HardwareProfile.describe());
            // A room measurement borrows the equaliser, the fader and the delay
            // lines. If the app died while it held them, give them back now rather
            // than leaving somebody driving with the sound in one door.
            RoomMeasurement.restoreIfInterrupted(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Critical Reflection Failure", e);
        }
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (p, key) -> {
        if (key == null) return;
        backgroundHandler.post(() -> {
            if (key.startsWith("sb_vis_")) {
                if (statusBarManager != null) statusBarManager.onPreferenceChanged(key);
            }
            else if (key.equals(PREF_PLAYER_MAP)) {
                loadPlayerMap();
            }
            else if (key.equals(PREF_LAST_SELECTED)) {
                syncPreset(false);
            }
            else if (key.equals(PREF_GALA_GLOBAL_MODE)) {
                galaGlobalMode = prefs.getBoolean(PREF_GALA_GLOBAL_MODE, false);
            }
            else if (key.equals(PREF_GALA_GLOBAL_ENABLED)) {
                galaGlobalEnabled = prefs.getBoolean(PREF_GALA_GLOBAL_ENABLED, false);
            }
            else if (currentPresetName != null && key.startsWith(currentPresetName)) {

                // Reload the data first
                loadPresetData(currentPresetName);

                Log.d(TAG, "[TurboSender2000] Pref Changed: " + key);

                // 1. Check for Subwoofer first (specific)
                if (key.contains("_sub")) {
                    updateSubwoofer(VolumeHelper.getVolume());
                }
                // 2. Then check for EQ bands or FM settings (less specific)
                else if (key.contains("_g") && !key.contains("_gala") || key.contains("_q") || key.contains("_fm")) {
                    updateEqWithFm(VolumeHelper.getVolume());
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
                    applyBassBoost();
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
                    ScreensaverManager.getInstance(McuService.this).setScreenState(true);
                    if (statusBarManager != null) {
                        statusBarManager.setScreenState(true);
                        statusBarManager.evaluateVisibility();
                    }
                    applyCurrentSettings();
                    backgroundHandler.postDelayed(() -> {
                        startPolling();
                        startGps();
                    }, 3000);
                }
                else if ("com.qf.action.ACC_OFF".equals(action)) {
                    ScreensaverManager.getInstance(McuService.this).setScreenState(false);
                    if (statusBarManager != null) {
                        statusBarManager.setScreenState(false);
                    }
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
                    // Logged because when somebody says the simulator does nothing, the first
                    // thing worth knowing is whether the value ever arrived at the service at all.
                    Log.i(TAG, "SIMULATE_SPEED: " + simulatedSpeedKmh + " km/h"
                            + (simulatedSpeedKmh > 0 ? "" : " (off, back to GPS)"));
                }
                else if (ACTION_AUDIO_STATE_STABLE.equals(action)) {
                    onAudioStateStable(intent);
                }
                else if ("com.radiorubka.wdsp.SUB_GAIN_UP".equals(action)) {
                    adjustSubGain(1);
                }
                else if ("com.radiorubka.wdsp.SUB_GAIN_DOWN".equals(action)) {
                    adjustSubGain(-1);
                }
                else if ("com.radiorubka.wdsp.PROBE_SESSION".equals(action)) {
                    // Diagnostic only - see SessionProbe. sid >= 0 probes one session,
                    // sid < 0 scans downwards from the platform's session counter.
                    int sid = intent.getIntExtra("sid", -1);
                    int ms = intent.getIntExtra("ms", sid >= 0 ? 1000 : 120);
                    if (intent.hasExtra("dump")) {
                        AudioSpectrumEngine.getInstance()
                                .setDebugDump(intent.getIntExtra("dump", 0) != 0);
                    }
                    else if (sid >= 0) {
                        SessionProbe.probeAsync(sid, ms);
                    } else {
                        SessionProbe.scanAsync(getApplicationContext(),
                                intent.getIntExtra("max", 64), ms);
                    }
                }
                else if ("com.radiorubka.wdsp.MEASURE_ROOM".equals(action)) {
                    // Plays a sweep through each speaker in turn and reports what came
                    // back - see RoomMeasurement. Everything it changes is restored, and
                    // the recordings are kept so a tester can send them back.
                    RoomMeasurement.setSameRouting(intent.getIntExtra("same", 0) != 0);
                    RoomMeasurement.setDelayTest(intent.getIntExtra("delaytest", 0));
                    RoomMeasurement.measureAsync(getApplicationContext(),
                            intent.getFloatExtra("amp", 0.25f),
                            intent.getFloatExtra("sec", 3f), null);
                }
                else if ("com.radiorubka.wdsp.SET_VOLUME".equals(action)) {
                    // Diagnostic only: moves the volume the way a person does, without
                    // telling GALA, so its manual-adjustment detector can be tested.
                    int vol = intent.getIntExtra("vol", -1);
                    if (vol >= 0) {
                        Log.i(TAG, "SET_VOLUME debug: " + VolumeHelper.getVolume()
                                + " -> " + vol);
                        VolumeHelper.setVolume(vol);
                    }
                }
                else if ("com.radiorubka.wdsp.MEASURE_LATENCY".equals(action)) {
                    // Diagnostic only - see LatencyProbe. Plays eight quiet bursts on its own
                    // session and reports how far ahead of the speakers the analyser runs.
                    LatencyProbe.measureAsync(
                            (AudioManager) getSystemService(Context.AUDIO_SERVICE),
                            intent.getIntExtra("fast", 0) != 0,
                            intent.getIntExtra("mic", 0) != 0,
                            McuService.this::storeMeasuredLatency);
                }
                else if ("com.radiorubka.wdsp.PROBE_MIC".equals(action)) {
                    // Diagnostic only - see MicProbe. Records to a WAV so the capture path can
                    // be inspected; src picks the audio source, 6 is VOICE_RECOGNITION.
                    MicProbe.probeAsync(getApplicationContext(),
                            intent.getIntExtra("src",
                                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION),
                            intent.getIntExtra("ms", 4000));
                }
                else if ("com.radiorubka.wdsp.SETTINGS_RESTORED".equals(action)) {
                    Log.i(TAG, "SETTINGS_RESTORED received, reloading all prefs and syncing DSP");
                    prefs = getApplicationContext().getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                    loadPlayerMap();
                    galaGlobalMode = prefs.getBoolean(PREF_GALA_GLOBAL_MODE, false);
                    galaGlobalEnabled = prefs.getBoolean(PREF_GALA_GLOBAL_ENABLED, false);
                    syncPreset(false);
                    if (statusBarManager != null) {
                        statusBarManager.loadPreferences();
                        statusBarManager.evaluateVisibility();
                    }
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

        AudioSpectrumEngine.getInstance().initContext(this);
        statusBarManager = StatusBarVisualizerManager.getInstance(this);
        statusBarManager.evaluateVisibility();

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
            // Both flags before syncPreset, because applying a preset consults isGalaEnabled().
            galaGlobalMode = prefs.getBoolean(PREF_GALA_GLOBAL_MODE, false);
            galaGlobalEnabled = prefs.getBoolean(PREF_GALA_GLOBAL_ENABLED, false);
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
            loadPlayerMap();
            // syncPreset reads last_selected_preset, loads it and applies it - all three. What
            // stood here did the first two by hand and got the first one wrong: it asked for a
            // preference literally named "Preset 1" rather than the key that stores which preset
            // is selected, so it loaded Preset 1's settings over whatever the owner had chosen.
            // syncPreset then corrected it two lines later, which is why nothing was ever visibly
            // broken - but the PRESET_CHANGED broadcast went out in between, announcing the wrong
            // one. Announced after now, when the name is true.
            syncPreset(true);
            sendBroadcast(presetChangedIntent);
            askRadioForItsState();
            isBootStart = false;
        });

        IntentFilter controlFilter = getIntentFilter();

        registerReceiver(controlReceiver, controlFilter);

        // The screensaver watches which activity is in front and needs to be running whether or
        // not anybody has the app open - that is the whole point of it.
        ScreensaverManager.getInstance(this).start();
        // Cheap: one broadcast filter, and media sessions only if the owner has allowed them.
        NowPlaying.getInstance(this).start();

        // Armed here rather than when the diagnostic screen is opened, because the events worth
        // recording - a Bluetooth call taking the audio path and giving it back - happen while
        // nobody is looking at the app. It only listens; there is no polling behind this.
        SystemDiagnostics.arm(this);

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
        controlFilter.addAction("com.radiorubka.wdsp.SETTINGS_RESTORED");
        controlFilter.addAction("com.radiorubka.wdsp.PROBE_SESSION");
        controlFilter.addAction("com.radiorubka.wdsp.MEASURE_ROOM");
        controlFilter.addAction("com.radiorubka.wdsp.SET_VOLUME");
        controlFilter.addAction("com.radiorubka.wdsp.MEASURE_LATENCY");
        controlFilter.addAction("com.radiorubka.wdsp.PROBE_MIC");
        controlFilter.addAction(ACTION_AUDIO_STATE_STABLE);
        return controlFilter;
    }

    /**
     * Keeps what a latency measurement found, so the analyser stops guessing.
     *
     * The acoustic figure is preferred when there is one: it is the distance from the moment we
     * see a sample to the moment it reaches the cabin, which is exactly what the bars have to
     * wait for. Without a microphone we only know how far the samples got inside Android, and the
     * stretch below the DAC has to be allowed for instead of measured.
     */
    private void storeMeasuredLatency(LatencyProbe.Result result) {
        if (AudioSpectrumEngine.storeMeasuredLatency(getApplicationContext(), result) < 0) {
            Log.w(TAG, "latency measurement did not produce a usable figure, keeping the old one");
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
        cachedFmCal = prefs.getInt(preset + "_fm_cal", 25);
        cachedFmStr = prefs.getInt(preset + "_fm_str", 100);

        // GALA
        cachedGalaEn = prefs.getBoolean(preset + "_gala_enabled", false);
        cachedGalaInc = prefs.getInt(preset + "_gala_increment", 15);
        // Clamped exactly as MainActivity.loadPreset clamps it, and for the same reason: the
        // standstill slider used to reach 300 km/h and now stops at 200.
        //
        // 🔴 It was clamped only there, and that is worse than not clamping at all. A preset saved
        // under the old range showed 200 on screen while this service went on computing with 300,
        // so GALA never engaged and nothing said why - not the screen, which looked right, and not
        // the log, which reported the offset as 0 with settings that appeared to ask for one. The
        // owner then opened the main screen, touched anything, autosave wrote the clamped value
        // back, and GALA came alive - which reads as "it only works when I go to the main screen".
        cachedGalaMinV = Math.min(GALA_MIN_SPEED_CEILING, prefs.getInt(preset + "_gala_min_speed", 0));
//        cachedGalaMaxV = prefs.getInt(preset + "_gala_max_speed", 30);
        cachedGalaMaxAdj = prefs.getInt(preset + "_gala_max_adj", 12);
        cachedGalaFadeDelayMs = prefs.getInt(preset + "_gala_fade_ms", 100);
        cachedGalaHoldMs = prefs.getInt(preset + "_gala_hold_ms", 1000);
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
            checkForBug();
            backgroundHandler.postDelayed(this, 100);
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


    /**
     * The radio has finished arranging the sound and says so. Re-baseline once, apply once.
     *
     * <p>Called from the receiver, which posts everything onto {@code backgroundHandler}, so this
     * runs on the same thread as the poll and needs no locking.
     *
     * <h2>Why the live volume becomes the base outright</h2>
     *
     * The radio's synchronisation writes a <b>level</b>. Whatever stood there before - our base
     * plus whatever offset GALA had applied - has been replaced by the level the radio decided on,
     * and there is no way afterwards to say how much of the new number was ours. Pretending we can
     * subtract our old offset from it would carry a stale figure into a level we did not set.
     *
     * <p>So the level is the new base, and the offset restarts from zero. GALA recomputes it on
     * the next poll, 100 ms later, and fades back in from the base the radio chose. The tracking
     * variables are reset the same way the unmute recovery resets them, and for the same reason:
     * without it the very next poll sees a volume it did not command, decides a person turned the
     * knob, and re-bases a second time.
     *
     * <p>{@code source="idle"} - the radio giving up the channel - takes this identical path.
     * 🔴 It means "re-baseline from the live volume", <b>not</b> "stop GALA": the radio going
     * quiet does not stop the car. Both sides recorded that reading explicitly, because the word
     * invites the opposite one.
     */
    /**
     * Asks the radio, once at startup, what the audio state is now.
     *
     * <p>The other half of the contract, and the half that exists because a signal is an edge:
     * whoever did not hear it does not know the state. That is not theoretical here. After
     * {@code adb install -r} this service does not come back on its own - the process shows in
     * {@code pidof} while the service is dead and broadcasts reach nobody - and it also happens on
     * a crash restart, or when the unit wakes and the start order falls differently. In each case
     * the radio has already sent its signal and we would sit on a stale base, which is the exact
     * race the contract removes.
     *
     * <p>The radio answers with an ordinary {@link #ACTION_AUDIO_STATE_STABLE} carrying its
     * current state - one signal type, not two. If the radio is not installed, or says nothing,
     * nothing happens and this service behaves exactly as it did before the contract existed.
     * Neither application requires the other.
     */
    private void askRadioForItsState() {
        try {
            Intent query = new Intent(ACTION_AUDIO_STATE_QUERY).setPackage(RADIO_PACKAGE);
            sendBroadcast(query);
            Log.i(TAG, "asked " + RADIO_PACKAGE + " for the current audio state");
        } catch (Throwable t) {
            // A radio that is not installed is the ordinary case, not a fault.
            Log.i(TAG, "could not ask the radio for its state: " + t);
        }
    }

    private void onAudioStateStable(Intent intent) {
        int seq = intent.getIntExtra("seq", 0);
        String source = intent.getStringExtra("source");
        int channel = intent.getIntExtra("channel", -1);

        // Late or repeated. The signal is an edge, and acting on a stale one re-bases to a level
        // that has already been superseded.
        if (lastAudioStateSeq != Integer.MIN_VALUE && seq <= lastAudioStateSeq) {
            Log.i(TAG, "AUDIO_STATE_STABLE seq=" + seq + " ignored, already at " + lastAudioStateSeq);
            return;
        }
        lastAudioStateSeq = seq;

        int live = VolumeHelper.getVolume();

        // 🔴 A muted amplifier reads back as zero, and zero is not a base - it is the absence of
        // one. Caught on the wire the first time this ran: the unit happened to be muted, the
        // signal arrived, and the base was set to 0. Nothing looks wrong until the mute comes off,
        // at which point GALA restores base plus offset and the car goes almost silent.
        //
        // The poll's own mute guard returns before any of its base handling for exactly this
        // reason, but that guard is upstream of here, so this path needs its own. The base is left
        // untouched and the unmute recovery re-establishes it when there is sound to measure
        // against. The EQ is still applied, because the curve does not depend on the mute.
        if (VolumeHelper.isHardwareMuted() || live <= 0) {
            applyVolumeDependentSettings(Math.max(0, live));
            Log.i(TAG, "AUDIO_STATE_STABLE seq=" + seq + " source=" + source + " channel=" + channel
                    + " -> muted (read " + live + "), base left at " + baseStandstillVolume
                    + ", EQ applied once");
            return;
        }

        baseStandstillVolume = live;
        currentAppliedOffset = 0;
        pendingTargetOffset  = 0;
        lastGalaTier         = 0;
        tierChangeTimestamp  = System.currentTimeMillis();
        lastReadHardwareVol  = live;
        lastAppliedVolume    = live;

        applyVolumeDependentSettings(live);

        Log.i(TAG, "AUDIO_STATE_STABLE seq=" + seq + " source=" + source + " channel=" + channel
                + " -> base=" + live + ", offset reset, EQ applied once");
    }

    // True/false state actually used by GALA processing - the shared global switch when
    // galaGlobalMode is on, otherwise whatever the current preset has stored.
    private boolean isGalaEnabled() {
        return galaGlobalMode ? galaGlobalEnabled : cachedGalaEn;
    }

    // THIS IS VERY FUCKED UP. IT WORKS??? MAYBE.
    private void checkVolumeAndGala() {

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
                        // The other three sources all fall back to the live volume here, and the
                        // omission was doing real harm: switching to AUX for the first time in a
                        // session left the base belonging to whatever played before it. GALA then
                        // added its offset to somebody else's base - too loud if the previous
                        // source was louder, silent if it was quieter.
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
            }
        }

        // this gets the speed
        float speed = simulatedSpeedKmh > 0.0f ? simulatedSpeedKmh : currentSpeedKmh;

        // this gets the volume and the mute state
        int hardwareVol = VolumeHelper.getVolume();
        // Kept aside because hardwareVol is overwritten further down with whatever GALA decides to
        // command, and step 7 has to remember what the hardware REPORTED, not what we sent it.
        final int reportedVolume = hardwareVol;
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
            // Logged because this is where "the speed simulator does nothing" comes from, every
            // time it has been looked into. The division is integer: with the standstill speed at
            // 65 and the increment at 45, simulating 90 gives (90-65)/45 = 0 and the volume
            // correctly does not move. Nothing is broken, the settings simply ask for no change -
            // and from the outside that is indistinguishable from a dead control.
            //
            // The standstill slider used to reach 300 km/h - a threshold nothing carrying one of
            // these head units ever crosses, so it was possible to set GALA to never engage and
            // have nothing say so. It stops at 200 now, which an ordinary car can still reach
            // downhill, and presets saved under the old range are clamped when they load.
            //
            // Once per change, not ten times a second: the log on these units is flooded by the
            // serial layer and scrolls away in minutes, and a diagnostic that buries itself is
            // worse than none.
            if (simulatedSpeedKmh > 0f && rawOffset != lastLoggedSimOffset) {
                lastLoggedSimOffset = rawOffset;
                Log.i(TAG, String.format(Locale.US,
                        "GALA at %.0f km/h: standstill %d, increment %d, ceiling %d -> offset %d%s",
                        speed, minSpeed, speedIncrement, cachedGalaMaxAdj, rawOffset,
                        rawOffset == 0 ? "  (no change asked for - check these three numbers)" : ""));
            }
        } else if (simulatedSpeedKmh > 0f && lastLoggedSimOffset != Integer.MIN_VALUE) {
            lastLoggedSimOffset = Integer.MIN_VALUE;
            Log.i(TAG, "GALA is switched off, so the simulated speed changes nothing");
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
        // We detect this because the hardware volume changed since the last poll, and not by us.
        //
        // Both halves matter. lastReadHardwareVol must hold what the hardware REPORTED last time,
        // never what we told it to be: the platform keeps its own volume curve per source and
        // does not always hand back the number it was given. Storing our own command there made
        // the first test true for ever, so every poll counted as a person turning the knob, and
        // re-based GALA to whatever was already playing. Base plus offset then equals the current
        // volume by construction - a fixed point - and the volume never moves again. That is the
        // reported failure: GALA dies after one press of volume-down while music plays, and only
        // then, because only then is anything writing volumes that read back differently.
        if (hardwareVol != lastReadHardwareVol && hardwareVol != lastAppliedVolume) {
            baseStandstillVolume = Math.max(0, hardwareVol - currentAppliedOffset);
            if (hardwareVol < currentAppliedOffset) {
                baseStandstillVolume = 0;
            }
            Log.d(TAG, "Manual Adjust: New Vol=" + hardwareVol + " -> New Base=" + baseStandstillVolume);
        }

        // 6. GALA APPLICATION with Hold-Timer and Fade
        //
        // 🔴 isGalaEnabled(), not cachedGalaEn. This was the raw per-preset field, and it was the
        // only place left reading it directly - step 2 above already asks properly. With the
        // global switch on, the two disagreed: a preset created while global mode was on never has
        // "_gala_enabled" written at all, so it read false while the global flag read true. Step 2
        // then computed a correct offset, logged it, and step 6 took the else branch and faded that
        // offset straight back to zero. GALA looked switched on, the log showed it working, and the
        // volume never moved.
        if (isGalaEnabled()) {
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
                hardwareVol = targetVol;
                Log.v(TAG, "GALA Update: vol=" + lastReadHardwareVol + " -> " + targetVol + " (offset=" + currentAppliedOffset + ")");
            }

            if (isUiVisible) {
                galaUpdateIntent.putExtra("speed", speed);
                galaUpdateIntent.putExtra("waveOffset", currentAppliedOffset);
                sendBroadcast(galaUpdateIntent);
            }
        } else {
            pendingTargetOffset = 0;
            lastGalaTier        = 0;

            if (currentAppliedOffset < 0) currentAppliedOffset = 0;

            if (currentAppliedOffset > 0) {
                long now = System.currentTimeMillis();
                long fadeDelay = Math.max(0, cachedGalaFadeDelayMs);
                if (now - lastFadeStepTime >= fadeDelay) {
                    currentAppliedOffset--;
                    lastFadeStepTime = now;
                    Log.v(TAG, "GALA Fade-Out (disabled): appliedOffset=" + currentAppliedOffset);
                }
                int targetVol = Math.min(32, baseStandstillVolume + currentAppliedOffset);
                if (hardwareVol != targetVol) {
                    VolumeHelper.setVolume(targetVol);
                    lastAppliedVolume = targetVol;
                    hardwareVol = targetVol;
                }
            } else {
                currentAppliedOffset = 0;
                baseStandstillVolume = hardwareVol;
                lastAppliedVolume    = hardwareVol;
            }
        }

        // 7. TRACKING: Update last seen volume and handle UI volume sync.
        //
        // reportedVolume, not hardwareVol. The manual-adjustment test in step 5 asks whether the
        // volume changed since the last poll and was not changed by us; storing our own command
        // here made the first half true for ever, because the hardware does not read back the
        // number it was given - it lags by a poll, and while music plays the platform runs its own
        // volume curve per source and may differ permanently. Every poll then counted as a person
        // turning the knob, the base was re-learned as volume - offset, and GALA set base + offset,
        // which is the volume that is already there. A fixed point: the volume never moved again.
        lastReadHardwareVol = reportedVolume;

        if (hardwareVol != lastVolumeRead) {
            lastVolumeRead = hardwareVol;
            if (cachedFmEn) {
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
        String activeType = VolumeHelper.getActivePlayerType();

        // Audio gating for status bar visualizer: Hide only when hardware Radio (tuner DSP) is active
        // The hardware tuner bypasses Android PCM AudioFlinger, so there is nothing to measure.
        // If the spectrum engine hears real signal, or a software media session is active, it is NOT tuner.
        boolean hasSignal = AudioSpectrumEngine.getInstance().hasSignalNow();
        boolean isPlayingMedia = NowPlaying.getInstance(this).isPlaying()
                && !NowPlaying.getInstance(this).isRadioSource();
        // The channel counts as evidence FOR the tuner and never against it: it only reads
        // reliably on a unit carrying the BitPerfect policies, and wanders on a factory one. A
        // stray 2 while music plays costs nothing, because hasSignal above has already answered.
        // A stray 4 while the tuner plays used to suppress the radio flag here, and nothing else
        // would have caught it - so that guard is gone. See NowPlaying.isRadioSource().
        String soundChannel = HardwareProfile.systemProperty("sys.qf.sound.channel");

        boolean isRadio = !hasSignal && !isPlayingMedia
                && ("radio_type".equals(activeType) || "2".equals(soundChannel)
                    || "true".equalsIgnoreCase(HardwareProfile.systemProperty("sys.qf.radio.status")));

        // Deliberately NOT gating on mute, though the flag itself is honest - the unit really was
        // muted when this was measured. The problem is what hiding costs: the widget unregisters
        // when hidden, and it is the only listener once the main screen is closed, so muting the
        // amplifier tore down the whole measurement chain. Leaving it running instead shows the
        // signal that genuinely exists upstream of the mute, and costs almost nothing now that
        // the widget only redraws when the picture actually changes.
        boolean isMuted = false;
        int channel = isRadio ? 2 : 0; // 2 = Radio (external DSP, no PCM), 0 = Android master mixer (all media)
        if (statusBarManager != null) {
            statusBarManager.setAudioGating(channel, isMuted);
        }

        // Process the naming convention for the "unknown" preset.
        if (isPlayingMedia && NowPlaying.getInstance(this).playerPackage() != null
                && !NowPlaying.getInstance(this).playerPackage().isEmpty()) {
            currentPlayer = NowPlaying.getInstance(this).playerPackage();
        }
        if ("nothing".equalsIgnoreCase(currentPlayer) || "Unknown".equalsIgnoreCase(currentPlayer)) {
            currentPlayer = "Default";
        }
        // If btcall_type, set the Player to be "Call".
        if (activeType.equals("btcall_type")) {
            lastPlayerSource = "Call";
            processPlayerSwitch("Call");
        }
        // A measurement selects its own preset and must keep it: our own sweeps make this app
        // the active player, and a player-based switch would drop the flat preset half way
        // through and measure the user's equaliser instead.
        else if (RoomMeasurement.isRunning()) {
            // deliberately nothing
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

            effectiveGainIdx[b1] = idx1;
            effectiveGainIdx[b2] = idx2;

            eqData[i + 1] = (byte) ((idx2 << 4) | (idx1 & 0x0F));
        }
        eqData[9] = cachedQByte1;
        eqData[10] = cachedQByte2;
        eqData[11] = 0x00;
        sendEqThrottled(eqData);
        publishDspStateToSpectrum();
    }

    /**
     * Hands the spectrum analyser the state that was actually sent to the DSP, not the raw slider
     * positions: the Fletcher-Munson curve is already baked into these indices, and so is the
     * hardware's own quantisation to 2 dB steps and its clamp at +/-12 dB. Feeding the sliders and
     * the curve separately would count the curve twice and hide the clamping.
     */
    private void publishDspStateToSpectrum() {
        boolean[] q = new boolean[AudioConfig.NUM_BANDS];
        for (int i = 0; i < 8; i++) {
            q[i] = (cachedQByte1 & (1 << i)) != 0;
            q[i + 8] = (cachedQByte2 & (1 << i)) != 0;
        }
        AudioSpectrumEngine.getInstance().setDspState(
                effectiveGainIdx, q, cachedSubFreq, effectiveSubGainIdx);
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
        effectiveSubGainIdx = finalGainIdx;
        subData[1] = (byte) ((cachedSubFreq << 4) | (finalGainIdx & 0x0F));
        sendSubThrottled(subData);
        publishDspStateToSpectrum();
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

    private void applyBassBoost() {
        sendToHardware(new byte[]{(byte) 0x88,
                (byte) (((prefs.getInt(currentPresetName + "_bb_frq_f", 0) + 8) << 4) | (prefs.getInt(currentPresetName + "_bb_f", 0) & 0x0F)),
                (byte) (((prefs.getInt(currentPresetName + "_bb_frq_r", 0) + 8) << 4) | (prefs.getInt(currentPresetName + "_bb_r", 0) & 0x0F)),
                (byte) ((prefs.getInt(currentPresetName + "_bf_f", 0) << 4) | (prefs.getInt(currentPresetName + "_bf_r", 0) & 0x0F))});
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

        applyBassBoost();

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

    private void sendToHardware(byte[] data) {
        if (data == null || data.length == 0) return;
        byte cmd = data[0];

        // TurboSender2000
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
        // These two were the wrong way round: 0x89 carries the surround/RSSE frame and 0x8C
        // the positional delays. Harmless to the hardware, but it sends anyone reading the log
        // looking at the wrong command - which is exactly what happened while measuring what the
        // delay sliders really do.
        if (cmd == (byte) 0x89) {
            turboSenderType = "[SURROUND_RSSE]: ";
        }
        if (cmd == (byte) 0x8c) {
            turboSenderType = "[SPATIAL_DELAYS]: ";
        }
        if (cmd == (byte) 0x80) {
            turboSenderType = "[EQ]: ";
        }

        Log.d(TAG, "[TurboSender2000] SendToHardware invoked with " + turboSenderType + turboSender);

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
        if (statusBarManager != null) {
            statusBarManager.removeOverlay();
        }
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
