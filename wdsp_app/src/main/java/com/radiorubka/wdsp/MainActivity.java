package com.radiorubka.wdsp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
//import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.widget.Toast;

import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.slider.LabelFormatter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.android.material.slider.Slider;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import androidx.activity.EdgeToEdge;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "wDSP_Main";

    private static final String KEY_SELECTED_TAB = "selected_tab_id";
    private static final String PREFS_NAME = "EqPresets";
    private static final String PREF_PRESET_NAMES = "preset_names";
    private static final String PREF_LAST_SELECTED = "last_selected_preset";
    private static final String PREF_PLAYER_MAP = "player_preset_map";
    private static final String PREF_DEFAULT_PRESET = "default_preset_name";
    private static final String PREF_GALA_GLOBAL_MODE = "gala_global_mode";
    // Not a real preset - just the "preset name" galaNamespace() resolves to when global mode
    // is on, so every GALA field (enable + all 5 sliders) reads/writes one shared bucket of the
    // exact same "<namespace>_gala_*" keys every preset already uses, instead of needing a
    // parallel set of global-only key constants and a branch at every read/write site.
    private static final String GALA_GLOBAL_NAMESPACE = "__gala_global__";
    private static final String[] GALA_FIELD_SUFFIXES = {
            "_gala_enabled", "_gala_increment", "_gala_min_speed", "_gala_max_adj", "_gala_fade_ms", "_gala_hold_ms"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<Slider> gainSliders = new ArrayList<>();
    private final List<ToggleButton> qSwitches = new ArrayList<>();
    private final List<TextView> dbLabels = new ArrayList<>();
    private AutoCompleteTextView spinnerPresets;
    private EqVisualizerView eqVisualizer;
    private SpectrumAnalyzerView spectrumAnalyzer;

    private Slider seekSubGain;
    private AutoCompleteTextView spinnerSubFreq;
    private TextView tvSubDb;

    private TextView tvPowerDb;
    private final String[] SUB_FREQS = {"25", "32", "40", "50", "63", "80", "100", "125", "160", "200", "250"};

    // Filter controls
    private Slider seekBassFilterFront, seekBassBoostFront, seekBassFilterRear, seekBassBoostRear;
    private TextView tvBassFilterFrontVal, tvBassBoostFrontDb, tvBassFilterRearVal, tvBassBoostRearDb;
    private AutoCompleteTextView spinnerBassFreqFront, spinnerBassFreqRear;
    // Rear's own last manually-picked Bass Boost frequency/gain - updateBassVisualizer()
    // overwrites spinnerBassFreqRear/seekBassBoostRear's displayed values to follow front
    // whenever Loudness is enabled (see its own doc), so these are what that override reverts
    // back to once Loudness is switched off. Kept in sync with the widgets themselves (tap/drag,
    // Sync Front/Rear Bass mirroring, preset load) - unset until the first of those happens.
    private String rearBassFreqManualText = null;
    private int rearBassGainManualValue = -1;
    private final String[] BASS_FILTER_FREQS = {"20", "25", "31", "40", "50", "63", "80", "100", "125", "160", "200", "250"};
    // Indices 1.. must stay in sync with AudioConfig.BASS_BOOST_FREQS_HZ/ISO_RAW_TARGET_BY_FREQ -
    // every one of these has its own tuned loudness-correction row, including 172/214Hz's
    // deeper EQ dip/ripple (an accepted tradeoff for users who want that shelf frequency).
    private final String[] BASS_BOOST_FREQS = {"off", "54", "68", "86", "108", "134", "172", "214"};

    // Fader & Delays
    private Slider seekFaderLr;
    private Slider seekFaderFr;
    private BalancePointerView balancePointer;
    private TextView tvFaderLrLeftVal, tvFaderLrRightVal, tvFaderFrFrontVal, tvFaderFrRearVal;
    private SwitchCompat switchLoud;
    private SwitchCompat switchSyncBass;
    private Slider seekDelayFl, seekDelayFr, seekDelayRl, seekDelayRr, seekDelaySub;
    private Slider seekDelay1Fl, seekDelay1Fr, seekDelay1Rl, seekDelay1Rr, seekDelay1RSSE;
    private SwitchCompat switchPreciseEnable, switchLegacyEnable;
    private SwitchCompat switchSyncDelayFront, switchSyncDelayRear;
    private TextView tvDelayFlVal, tvDelayFrVal, tvDelayRlVal, tvDelayRrVal, tvDelaySubVal;
    private TextView tvDelay1FlVal, tvDelay1FrVal, tvDelay1RlVal, tvDelay1RrVal, tvDelay1RSSEVal;

    // F-M Curve
    private SwitchCompat switchFmEnable, switchFatigueEnable, switchFmSubComp, switchShowLoudnessMain, switchUltraBass;
    private Slider seekFmCalVol, seekFmStrength, seekFatStartVol, seekUltraBassStartVol, seekUltraBassMaxDb;
    private TextView tvFmCalVolVal, tvFmStrengthVal, tvFatStartVolVal, tvSysVolumeVal, tvSubOffsetVal, tvSubOffsetWarn, tvUltraBassStartVolVal, tvUltraBassMaxDbVal;
    private FmVisualizerView fmVisualizer;
    
    // GALA Controls
    private SwitchCompat switchGalaEnable, switchGalaGlobal, switchGalaDisableForPreset;
    private Slider seekGalaInc, seekGalaMinSpeed, seekSimulateSpeed, seekGalaMaxAdj;
    private Slider seekGalaFadeMs, seekGalaHoldMs;
  
    private TextView tvGalaIncVal, tvGalaSpeed, tvGalaMinSpeedVal, tvGalaOffset, tvSimulateSpeedVal, tvGalaMaxAdjVal, tvGalaFadeMsVal, tvGalaHoldMsVal;
    
    // Whether every GALA field (enable + all 5 sliders) is shared across all presets instead
    // of per-preset. Kept in sync with PREF_GALA_GLOBAL_MODE; see galaNamespace().
    private boolean galaGlobalMode = false;

    private float currentFmSubOffset = 0f;
    private float currentFmBassShelfOffset = 0f;
    private int currentEffectiveVolume = -1;

    private ArrayAdapter<String> presetAdapter;
    private List<String> presetNames;
    private int accentColor;
    private boolean isUpdatingUi = false;
    private boolean isFullyInitialized = false;

    // Head unit's own backlight RGB, re-captured every time setButtonRgbCyan() runs (app open /
    // resume) so onStop() can restore whatever it actually was, not a stale first-launch value.
    private byte defaultBacklightR, defaultBacklightG, defaultBacklightB;
    private boolean backlightDefaultCaptured = false;

    private String defaultPreset;

    private Method getPropMethod;

    // Newer MCU firmware (dated after this) doesn't support positive amp power - see
    // checkAmpPositiveGate()/setPowerVolume()/loadPreset() for where this is enforced. The date
    // is the 4th dot-separated segment of persist.sys.qf.mcu.version (e.g. "20260703" here).
    private static final String AMP_POSITIVE_GATE_VERSION = "QF05.V02.14.20260703.002121";
    private boolean ampPositiveDisabled = false;

    public static class Globals {
        public static int currentSubFreqHz = 0;
    }

    // Populated in onCreate() from theme-aware color resources (light/night) instead of hardcoded
    // hex, so it stays in sync with EqVisualizerView's band-group palette (bass=warm, treble=cool).
    private int[] GROUP_COLORS;
    //private final String[] GROUP_NAMES = {"low bass", "bass", "mid-bass", "mids", "lower treble", "upper treble"};
    // Indices where each group starts: 0(20Hz), 3(80Hz), 5(200Hz), 7(500Hz), 10(2kHz), 13(8kHz)
    private final int[] GROUP_STARTS = {0, 3, 5, 7, 10, 13};


    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.radiorubka.wdsp.PRESET_CHANGED".equals(action)) {
                String name = intent.getStringExtra("preset");
                if (name != null && presetNames != null && presetNames.contains(name)) {
                    Toaster.show(MainActivity.this, "Auto: " + name);
                    spinnerPresets.setText(name, false);
                    loadPreset(name);
                }
            }
            else if ("com.radiorubka.wdsp.VOLUME_CHANGED".equals(action)) {
                currentEffectiveVolume = intent.getIntExtra("volume", -1);
                if (isFullyInitialized) {
                    if (findViewById(R.id.layout_fm_curve).getVisibility() == View.VISIBLE) {
                        updateFmVisualizer(); // also refreshes the main screen via its own hook
                    } else {
                        // The Loudness tab isn't visible, so skip its own (currently unseen) UI
                        // work, but the main-screen loudness preview (switchShowLoudnessMain)
                        // still needs to track volume changes live even when that tab isn't active.
                        updateVisualizer();
                    }
                }
            }
            else if ("com.radiorubka.wdsp.GALA_UPDATE".equals(action)) {
//                Log.e("MainActivity", "RECEIVED GALA UPDATE INTENT");
                float speed = intent.getFloatExtra("speed", 0.0f);
                int offset = intent.getIntExtra("waveOffset", 0);
                int base = intent.getIntExtra("base", 0);
                if (tvGalaSpeed != null) tvGalaSpeed.setText(String.format(Locale.getDefault(), "%.1f km/h", speed));
                String tvgalaformat = "[" + base + "] +" + offset;
                if (tvGalaOffset != null) tvGalaOffset.setText(tvgalaformat);
            }
            // Sub gain was adjusted by McuService (e.g. via an external HID key daemon
            // broadcast, handled even while this Activity/app isn't running). Reflect it
            // in the UI if we're alive to see it.
            else if ("com.radiorubka.wdsp.SUB_GAIN_CHANGED".equals(action)) {
                int subGain = intent.getIntExtra("subGain", -1);
                if (subGain >= 0 && seekSubGain != null) {
                    isUpdatingUi = true;
                    seekSubGain.setValue(subGain);
                    isUpdatingUi = false;
                }
            }
        }
    };

    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> { if (r.getResultCode() == RESULT_OK && r.getData() != null) saveCurrentPresetToFile(r.getData().getData()); });

    private final ActivityResultLauncher<Intent> importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> { if (r.getResultCode() == RESULT_OK && r.getData() != null) loadPresetFromFile(r.getData().getData()); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        EdgeToEdge.enable(this,
                SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
                SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        );

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        accentColor = ContextCompat.getColor(this, R.color.cyan_custom);

        // Same reversed order as EqVisualizerView's GROUP_COLORS: low bass -> upper treble
        // goes warm (red) to cool (blue/teal).
        GROUP_COLORS = new int[]{
                ContextCompat.getColor(this, R.color.btn_delete_bg),
                ContextCompat.getColor(this, R.color.btn_import_bg),
                ContextCompat.getColor(this, R.color.btn_export_bg),
                ContextCompat.getColor(this, R.color.btn_rename_bg),
                ContextCompat.getColor(this, R.color.btn_add_bg),
                ContextCompat.getColor(this, R.color.btn_auto_bg)
        };

        // 1. Instant UI: Minimal views needed for the first screen
        initPrimaryViews();
        registerServiceReceiver();

        if (savedInstanceState != null) {
            BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
            int tabId = savedInstanceState.getInt(KEY_SELECTED_TAB);
            bottomNav.setSelectedItemId(tabId);
        }
        else {
            SelectTab();
        }

        
        // 2. Background Tasks: Reflection and Service
        new Thread(() -> {
            bypassHiddenApiRestrictions();
            VolumeHelper.init(getApplicationContext());
        }).start();

        // 3. Delayed UI Initialization: EQ Bands are heavy
        handler.post(() -> {
            setupEqBands(); // Programmatic creation of 16 bands
            setupPresets(); // Load current data
        });
        
        // 4. Lazy Logic: Everything else can wait a few ms
        handler.postDelayed(() -> {
            setupLogic();
            isFullyInitialized = true;
            sendUiSignal(true);
            requestBatteryOptimization();
            initReflection();
            checkAmpPositiveGate();
            setButtonRgbCyan();
            ensureCallPresetExists();
            startMcuService();
            refreshAllUiValues();
        }, 50);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // Save the currently selected ID
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        outState.putInt(KEY_SELECTED_TAB, bottomNav.getSelectedItemId());
    }

    private void bypassHiddenApiRestrictions() {
        try {
            // 1. Get the standard reflection methods via reflection
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);

            // 2. Use those methods to find the hidden VMRuntime class
            Class<?> vmRuntimeClass = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");

            // 3. Find the 'getRuntime' and 'setHiddenApiExemptions' methods
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", new Class[0]);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});

            // 4. Execute the bypass
            assert getRuntime != null;
            Object sVmRuntime = getRuntime.invoke(null);
            // Passing "L" exempts ALL hidden APIs from the blacklist
            assert setHiddenApiExemptions != null;
            setHiddenApiExemptions.invoke(sVmRuntime, new Object[]{new String[]{"L"}});

            Log.d("wDSP", "Hidden API bypass successful");
        } catch (Exception e) {
            Log.e("wDSP", "Failed to bypass hidden API restrictions", e);
        }
    }

    private void startMcuService() {

        String[] permissions = {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION // Works bundled on API 29
        };

        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            // This is where your bug was: you requested but didn't wait for the result
            ActivityCompat.requestPermissions(this, permissions, 102);
        } else {
            // Permissions already exist (second launch)
            startMcuActualService();
        }
    }

    public void startMcuActualService() {
        Intent intent = new Intent(this, McuService.class);
        startForegroundService(intent);
        handler.postDelayed(() -> sendBroadcast(new Intent("com.radiorubka.wdsp.UI_ACTIVE").setPackage(getPackageName())), 1000);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 102) {
            // Check if Fine Location was granted (at minimum)
            boolean fineLocationGranted = false;
            for (int i = 0; i < permissions.length; i++) {
                if (permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    fineLocationGranted = true;
                    break;
                }
            }

            if (fineLocationGranted) {
                Log.d(TAG, "Permission granted on first launch. Starting McuService...");
                //Intent intent = new Intent(this, McuService.class);
                startMcuActualService();
            } else {
                Toaster.show(this, "Location permission is required for GALA features.");
                //Intent intent = new Intent(this, McuService.class);
                startMcuActualService();
            }
        } else if (requestCode == 103) {
            boolean recordAudioGranted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (recordAudioGranted && spectrumAnalyzer != null) {
                spectrumAnalyzer.start();
            } else {
                Log.w(TAG, "RECORD_AUDIO denied - spectrum analyzer stays disabled.");
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sendBroadcast(new Intent("com.radiorubka.wdsp.UI_ACTIVE").setPackage(getPackageName()));
        if (isFullyInitialized) {
            refreshAllUiValues();
            SelectTab();
            setButtonRgbCyan();
        }
        // Force the UI to match the saved preference
        if (isFullyInitialized && presetNames != null) {
            refreshAllUiValues();
            SelectTab();

            // Only run this if presetNames is actually ready
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String current = prefs.getString("last_selected_preset", defaultPreset);
            int index = presetNames.indexOf(current);
            if (index >= 0 && index < presetNames.size()) {
                String newName = presetNames.get(index);
                spinnerPresets.setText(newName, false);
                loadPreset(newName);
            }
        }
        checkAndStartSpectrumAnalyzer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sendBroadcast(new Intent("com.radiorubka.wdsp.UI_INACTIVE").setPackage(getPackageName()));
        if (spectrumAnalyzer != null) spectrumAnalyzer.stop();
    }

    // --- Spectrum analyzer (pre-EQ, visual-only; see SpectrumAnalyzerView javadoc) ---
    private void checkAndStartSpectrumAnalyzer() {
        if (spectrumAnalyzer == null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            spectrumAnalyzer.start();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 103);
        }
    }

    private void SelectTab() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
        bottomNav.setSelectedItemId(bottomNav.getSelectedItemId());
    }

    private void requestBatteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {

            // Show a quick explanation so the user isn't confused
            new AlertDialog.Builder(this)
                    .setTitle(R.string.battery_dialog_title)
                    .setMessage(R.string.battery_dialog_message)
                    .setPositiveButton(R.string.btn_allow, (dialog, which) -> {
                        try {
                            @SuppressLint("BatteryLife") Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception e) {
                            // Fallback to the main optimization settings if the direct intent fails
                            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            startActivity(intent);
                        }
                    })
                    .setNegativeButton(R.string.btn_later, null)
                    .show();
        }
    }


    private void refreshAllUiValues() {
        // 1. Get the name of the currently selected preset from the spinner
        String currentPreset = spinnerPresets.getText().toString();

        loadPreset(currentPreset);

        // 3. Specifically update things that might change outside the app (like Volume)
        updateFmVisualizer();
        updateFaderLabels();
    }

    private void initPrimaryViews() {
        spinnerPresets = findViewById(R.id.spinner_presets);
        eqVisualizer = findViewById(R.id.eq_visualizer);
        spectrumAnalyzer = findViewById(R.id.spectrum_analyzer);
        seekSubGain = findViewById(R.id.seek_sub_gain);
        spinnerSubFreq = findViewById(R.id.spinner_sub_freq);
        tvSubDb = findViewById(R.id.tv_sub_db);
        tvPowerDb = findViewById(R.id.tv_pwr_db);
        setupNavigation();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerServiceReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.radiorubka.wdsp.PRESET_CHANGED");
        filter.addAction("com.radiorubka.wdsp.VOLUME_CHANGED");
        filter.addAction("com.radiorubka.wdsp.GALA_UPDATE");
        filter.addAction("com.radiorubka.wdsp.SUB_GAIN_CHANGED");

        registerReceiver(serviceReceiver, filter);
    }

    private void setupLogic() {
        initSecondaryViews();
        setupSubControls();
        setupFilterControls();
        setupFmControls();
        setupDelayControls();
        setupDelay1Controls();
        setupGalaControls();

        findViewById(R.id.btn_minus).setOnClickListener(v -> adjustAllBands(-1));
        findViewById(R.id.btn_plus).setOnClickListener(v -> adjustAllBands(1));
        findViewById(R.id.btn_center).setOnClickListener(v -> resetAllBands());
        findViewById(R.id.btn_pwr_vol_minus).setOnClickListener(v -> setPowerVolume(101));
        findViewById(R.id.btn_pwr_vol_plus).setOnClickListener(v -> setPowerVolume(102));
        findViewById(R.id.btn_fader_lr_minus).setOnClickListener(v -> adjustFaderStep(seekFaderLr, -1));
        findViewById(R.id.btn_fader_lr_plus).setOnClickListener(v -> adjustFaderStep(seekFaderLr, 1));
        findViewById(R.id.btn_fader_fr_minus).setOnClickListener(v -> adjustFaderStep(seekFaderFr, -1));
        findViewById(R.id.btn_fader_fr_plus).setOnClickListener(v -> adjustFaderStep(seekFaderFr, 1));
        findViewById(R.id.btn_apply).setOnClickListener(v -> {
            autoSaveCurrent();
//            applyAllToMcu();
            Toaster.show(this, getString(R.string.toast_settings_applied));
        });
        findViewById(R.id.btn_auto_preset).setOnClickListener(v -> showAutoPresetDialog());
        findViewById(R.id.btn_add_preset).setOnClickListener(v -> addNewPreset());
        findViewById(R.id.btn_rename_preset).setOnClickListener(v -> renameCurrentPreset());
        findViewById(R.id.btn_delete_preset).setOnClickListener(v -> deleteCurrentPreset());
        findViewById(R.id.btn_export_presets).setOnClickListener(v -> exportPresets());
        findViewById(R.id.btn_import_presets).setOnClickListener(v -> importPresets());
    }

    private void initSecondaryViews() {
        seekBassFilterFront = findViewById(R.id.seek_bass_filter_front);
        tvBassFilterFrontVal = findViewById(R.id.tv_bass_filter_front_db);
        seekBassBoostFront = findViewById(R.id.seek_bass_boost_front);
        tvBassBoostFrontDb = findViewById(R.id.tv_bass_boost_front_db);
        spinnerBassFreqFront = findViewById(R.id.spinner_bass_freq_front);
        seekBassFilterRear = findViewById(R.id.seek_bass_filter_rear);
        tvBassFilterRearVal = findViewById(R.id.tv_bass_filter_rear_db);
        seekBassBoostRear = findViewById(R.id.seek_bass_boost_rear);
        tvBassBoostRearDb = findViewById(R.id.tv_bass_boost_rear_db);
        spinnerBassFreqRear = findViewById(R.id.spinner_bass_freq_rear);
        seekFaderLr = findViewById(R.id.seek_fader_lr);
        seekFaderFr = findViewById(R.id.seek_fader_fr);
        balancePointer = findViewById(R.id.balance_pointer);
        tvFaderLrLeftVal = findViewById(R.id.tv_fader_lr_left_val);
        tvFaderLrRightVal = findViewById(R.id.tv_fader_lr_right_val);
        tvFaderFrFrontVal = findViewById(R.id.tv_fader_fr_front_val);
        tvFaderFrRearVal = findViewById(R.id.tv_fader_fr_rear_val);
        switchLoud = findViewById(R.id.switch_loud);
        switchSyncBass = findViewById(R.id.switch_sync_bass);
        seekDelayFl = findViewById(R.id.seek_delay_fl);
        seekDelayFr = findViewById(R.id.seek_delay_fr);
        seekDelayRl = findViewById(R.id.seek_delay_rl);
        seekDelayRr = findViewById(R.id.seek_delay_rr);
        seekDelaySub = findViewById(R.id.seek_delay_sub);
        tvDelayFlVal = findViewById(R.id.tv_delay_fl_val);
        tvDelayFrVal = findViewById(R.id.tv_delay_fr_val);
        tvDelayRlVal = findViewById(R.id.tv_delay_rl_val);
        tvDelayRrVal = findViewById(R.id.tv_delay_rr_val);
        tvDelaySubVal = findViewById(R.id.tv_delay_sub_val);
        switchPreciseEnable = findViewById(R.id.switch_precise_enable);
        switchSyncDelayFront = findViewById(R.id.switch_sync_delay_front);
        switchSyncDelayRear = findViewById(R.id.switch_sync_delay_rear);
        seekDelay1Fl = findViewById(R.id.seek_delay1_fl);
        seekDelay1Fr = findViewById(R.id.seek_delay1_fr);
        seekDelay1Rl = findViewById(R.id.seek_delay1_rl);
        seekDelay1Rr = findViewById(R.id.seek_delay1_rr);
        seekDelay1RSSE = findViewById(R.id.seek_delay1_rsse);
        tvDelay1FlVal = findViewById(R.id.tv_delay1_fl_val);
        tvDelay1FrVal = findViewById(R.id.tv_delay1_fr_val);
        tvDelay1RlVal = findViewById(R.id.tv_delay1_rl_val);
        tvDelay1RrVal = findViewById(R.id.tv_delay1_rr_val);
        tvDelay1RSSEVal = findViewById(R.id.tv_delay1_rsse_val);
        switchLegacyEnable = findViewById(R.id.switch_legacy_enable);
        switchFmEnable = findViewById(R.id.switch_fm_enable);
        switchFatigueEnable = findViewById(R.id.switch_fatigue_enable);
        switchFmSubComp = findViewById(R.id.switch_fm_sub_comp);
        switchShowLoudnessMain = findViewById(R.id.switch_show_loudness_main);
        switchUltraBass = findViewById(R.id.switch_ultra_bass);
        seekFmCalVol = findViewById(R.id.seek_fm_cal_vol);
        tvFmCalVolVal = findViewById(R.id.tv_fm_cal_vol_val);
        seekFmStrength = findViewById(R.id.seek_fm_strength);
        tvFmStrengthVal = findViewById(R.id.tv_fm_strength_val);
        seekFatStartVol = findViewById(R.id.seek_fat_start_vol);
        tvFatStartVolVal = findViewById(R.id.tv_fat_start_vol_val);
        seekUltraBassStartVol = findViewById(R.id.seek_ultra_bass_start_vol);
        tvUltraBassStartVolVal = findViewById(R.id.tv_ultra_bass_start_vol_val);
        seekUltraBassMaxDb = findViewById(R.id.seek_ultra_bass_max_db);
        tvUltraBassMaxDbVal = findViewById(R.id.tv_ultra_bass_max_db_val);
        fmVisualizer = findViewById(R.id.fm_visualizer);
        tvSysVolumeVal = findViewById(R.id.tv_sys_volume_val);
        tvSubOffsetVal = findViewById(R.id.tv_sub_offset_val);
        tvSubOffsetWarn = findViewById(R.id.tv_sub_offset_warn);
        
        // GALA
        switchGalaEnable = findViewById(R.id.switch_gala_enable);
        switchGalaGlobal = findViewById(R.id.switch_gala_global);
        switchGalaDisableForPreset = findViewById(R.id.switch_gala_disable_for_preset);
        seekGalaInc = findViewById(R.id.seek_gala_increment);
        tvGalaIncVal = findViewById(R.id.tv_gala_increment_val);
        tvGalaSpeed = findViewById(R.id.tv_gala_speed);
        seekGalaMinSpeed = findViewById(R.id.seek_gala_minspeed);
        tvGalaMinSpeedVal = findViewById(R.id.tv_gala_minspeed_val);
        tvGalaOffset = findViewById(R.id.tv_gala_offset);
        seekSimulateSpeed = findViewById(R.id.seek_simulate_speed);
        tvSimulateSpeedVal = findViewById(R.id.tv_simulate_speed_val);
        seekGalaMaxAdj = findViewById(R.id.seek_gala_max_adj);
        tvGalaMaxAdjVal = findViewById(R.id.tv_gala_max_adj_val);
        seekGalaFadeMs = findViewById(R.id.seek_gala_fade_ms);
        tvGalaFadeMsVal = findViewById(R.id.tv_gala_fade_ms_val);
        seekGalaHoldMs = findViewById(R.id.seek_gala_hold_ms);
        tvGalaHoldMsVal = findViewById(R.id.tv_gala_hold_ms_val);
    }

    @Override
    protected void onStart() {
        super.onStart();
        sendUiSignal(true);
        SelectTab();
    }

    @Override
    protected void onStop() {
        super.onStop();
        sendUiSignal(false);
        restoreDefaultBacklight();
    }

    private void sendUiSignal(boolean active) {
        Intent intent = new Intent(active ? "com.radiorubka.wdsp.UI_ACTIVE" : "com.radiorubka.wdsp.UI_INACTIVE");
        intent.setPackage(getPackageName());
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        sendBroadcast(intent);
    }

//    private GradientDrawable getGroupDrawable(int index) {
//        int groupIdx = 0;
//        for (int i = 0; i < GROUP_STARTS.length; i++) {
//            if (index >= GROUP_STARTS[i]) groupIdx = i;
//        }
//
//        GradientDrawable gd = new GradientDrawable();
//        gd.setShape(GradientDrawable.RECTANGLE);
//        gd.setStroke((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, getResources().getDisplayMetrics()), GROUP_COLORS[groupIdx]);
//        gd.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics()));
//        return gd;
//    }

    private void setupEqBands() {
        LinearLayout container = findViewById(R.id.eq_container);
        container.removeAllViews();
        gainSliders.clear();
        qSwitches.clear();
        dbLabels.clear();
        int cQ = ContextCompat.getColor(this, R.color.q_switch_text);
        //int cL = ContextCompat.getColor(this, R.color.band_label);
        float smallTextSize = getResources().getDimension(R.dimen.text_size_small);

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            final int idx = i;
            ToggleButton q = new ToggleButton(this);
            TextView db = new TextView(this);
            Slider s = new Slider(this, null);
            gainSliders.add(s);
            qSwitches.add(q);
            dbLabels.add(db);

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setGravity(Gravity.CENTER_HORIZONTAL);
            layout.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));

            q.setTextOn(getString(R.string.q_high));
            q.setTextOff(getString(R.string.q_low));
            q.setChecked(false);
            q.setTextColor(cQ); q.setBackgroundColor(Color.TRANSPARENT);
            q.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize);
            q.setPadding(0, 0, 0, 0); q.setMinimumHeight(0); q.setMinimumWidth(0);
            q.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.08f));
            // Q-logic is non-functional on real hardware until the MCU firmware is updated -
            // hidden for now, but left fully wired (state still saved/loaded per band) so it
            // comes back for free once that firmware update ships. GONE reclaims this row's
            // weighted slot for the slider below it; syncVisualizerGeometry() (called once this
            // loop finishes) re-measures the real layout afterward so EqVisualizerView/
            // SpectrumAnalyzerView's overlay stays aligned with wherever the sliders actually
            // end up, instead of assuming a fixed row count.
            q.setVisibility(View.GONE);
            q.setOnCheckedChangeListener((bv, checked) -> {
                if (!isUpdatingUi) {
                    updateVisualizer();
//                    updateEqMcu();
                    autoSaveCurrent();
                }
            });

            db.setText("0"); db.setTextColor(accentColor);
            db.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize);
            db.setGravity(Gravity.CENTER); db.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.08f));

            TextView label = new TextView(this);
            label.setText(AudioConfig.BAND_LABELS[i]); label.setTextColor(getColor(R.color.transparent));
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize);
            label.setGravity(Gravity.CENTER); label.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.08f));

            s.setValueFrom(0f);
            s.setValueTo(12f);
            s.setStepSize(1f);
            s.setValue(6f);
            s.setThumbHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics()));
//            s.setThumbRadius((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 7, getResources().getDisplayMetrics()));
            s.setHaloRadius((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics()));
            s.setHaloTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            s.setThumbTintList(ColorStateList.valueOf(accentColor));

            // Color-code the track per band group (same palette as the EQ visualizer curve)
            int groupIdx = 0;
            for (int g = 0; g < GROUP_STARTS.length; g++) {
                if (idx >= GROUP_STARTS[g]) groupIdx = g;
            }
            int bandColor = GROUP_COLORS[groupIdx];
            s.setTrackActiveTintList(ColorStateList.valueOf(bandColor));
            s.setThumbTintList(ColorStateList.valueOf(bandColor));
            s.setTrackInactiveTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(bandColor, 70)));
            s.setTrackHeight((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, getResources().getDisplayMetrics()));
            s.setRotation(270f);

            // 1. Set the Stop Indicator size to 0 (This is the "first tick" you're seeing)
            s.setTrackStopIndicatorSize(0);

            // 3. Make the track itself invisible
//            ColorStateList transparent = ColorStateList.valueOf(Color.TRANSPARENT);
//            s.setTrackActiveTintList(transparent);
//            s.setTrackInactiveTintList(transparent);

            // 4. Ensure Ticks are off and invisible just in case, also hide label
//            s.setTickVisibilityMode(TickVisibilityMode.TICK_VISIBILITY_HIDDEN);
            s.setLabelBehavior(LabelFormatter.LABEL_GONE);
            s.setTickActiveTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.tick_color_active)));
            s.setTickInactiveTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.tick_color_inactive)));

            FrameLayout seekBox = new FrameLayout(this);
            seekBox.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.76f));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1000, -2);
            lp.gravity = Gravity.CENTER; s.setLayoutParams(lp);

            seekBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int h = b - t;
                if (h > 0 && s.getWidth() != h) { ViewGroup.LayoutParams vlp = s.getLayoutParams(); vlp.width = h; s.setLayoutParams(vlp); }
            });

            s.addOnChangeListener((slider, value, fromUser) -> {
                if (!isUpdatingUi) {
                    int p = Math.round(value);
                    updateDbLabel(idx, p);
                    updateVisualizer();
                    autoSaveCurrent();
                }
            });

            layout.addView(q);
            layout.addView(db);
            layout.addView(label);
            seekBox.addView(s);
            layout.addView(seekBox);
            container.addView(layout);
            updateDbLabel(i, 6);
        }

        // Re-measure on every layout pass (not just once) so eq_container's real geometry -
        // whichever rows are visible, whatever the screen size/orientation - is always the
        // source of truth for where EqVisualizerView/SpectrumAnalyzerView draw their overlay,
        // instead of a hardcoded guess that silently goes stale the next time this layout changes.
        container.getViewTreeObserver().addOnGlobalLayoutListener(() -> syncVisualizerGeometry(container));
    }

    /**
     * Measures where the real Slider track sits within eq_container (using band 0's column as
     * the reference - every column is laid out identically) and pushes that as a fraction of
     * the container's height to the views that draw an overlay on top of it, so their curve/
     * grid/bars always line up with the actual sliders. See EqVisualizerView.setSliderBounds().
     */
    private void syncVisualizerGeometry(LinearLayout container) {
        int containerHeight = container.getHeight();
        if (containerHeight <= 0 || container.getChildCount() == 0) return;

        View bandColumn = container.getChildAt(0);
        if (!(bandColumn instanceof LinearLayout)) return;
        LinearLayout column = (LinearLayout) bandColumn;

        // Children added in order: q (0), dB label (1), freq label (2), seekBox (3) - see the
        // addView() calls just above. seekBox is the one real sliders actually travel within.
        if (column.getChildCount() <= 3) return;
        View seekBox = column.getChildAt(3);
        if (seekBox.getHeight() <= 0) return;

        float topRatio = seekBox.getTop() / (float) containerHeight;
        float heightRatio = seekBox.getHeight() / (float) containerHeight;

        if (eqVisualizer != null) eqVisualizer.setSliderBounds(topRatio, heightRatio);
        if (spectrumAnalyzer != null) spectrumAnalyzer.setSliderBounds(topRatio, heightRatio);
    }

    private void updateVisualizer() {
        if (eqVisualizer == null) return;
        // The real/raw EQ curve always reflects the actual sliders, exactly like before this
        // whole loudness-preview feature existed - the correction is a separate overlay (see
        // setLoudnessCorrection() below), never blended into this.
        int[] gs = new int[AudioConfig.NUM_BANDS];
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) gs[i] = getIntSlider(gainSliders.get(i));
        eqVisualizer.setGains(gs);

        // Loudness correction/sub level data is now always computed (not gated on
        // switchShowLoudnessMain) - the RTA's own reactive shift (see
        // SpectrumAnalyzerView.setLoudnessReactive(), currently off by default, no UI switch yet)
        // is an independent, separately-togglable thing from the main screen's dotted-overlay
        // preview, so it needs this data regardless of whether that overlay is currently shown.
        // Gated on isFullyInitialized: updateVisualizer() gets called during early preset-load
        // bootstrap (setupPresets() -> loadPreset()), before setupFmControls() has initialized
        // seekFmCalVol/seekFmStrength - calculateFmOffsets() would NPE on those if called then.
        boolean hasCorrection = false;
        float[] correctionGains = new float[AudioConfig.NUM_BANDS];
        // subGainDbBase never includes Sub Tweaking's dynamic offset; subGainDbDynamic does -
        // the main screen shows the base (static) one unless Show on Main opts into the dynamic
        // one too (see showOnMain below), while the RTA always gets the dynamic one regardless,
        // same as hasCorrection/correctionGains already did before this.
        float subGainDbBase = getIntSlider(seekSubGain);
        float subGainDbDynamic = subGainDbBase;
        // Ultra Bass is independent of Loudness/Show on Main entirely - it's a real, always-active
        // effect when enabled (not a loudness-preview artifact), so it's added into BOTH variables
        // equally rather than only the "dynamic" one, which makes it show up on the main curve
        // regardless of which of the two showOnMain picks below.
        int ultraBassVol = (currentEffectiveVolume != -1) ? currentEffectiveVolume : getSystemVolume();
        float ultraBassOffsetDb = calculateUltraBassOffset(ultraBassVol);
        subGainDbBase += ultraBassOffsetDb;
        subGainDbDynamic += ultraBassOffsetDb;
        if (isFullyInitialized) {
            float[] offs = calculateFmOffsets(); // also refreshes currentFmSubOffset as a side effect
            float[] targetDb = new float[AudioConfig.NUM_BANDS];
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                if (offs[i] != 0f) hasCorrection = true;
                targetDb[i] = (gs[i] - 6) * 2 + offs[i];
            }
            // Jointly pre-warped the same way McuService.updateEqWithFm() pre-warps the real
            // hardware send (see AudioConfig.prewarpEq()'s doc) - this preview shows the real
            // achieved curve, slider ripple and loudness ripple cross-talk-cancelled together,
            // not the pre-pre-warp additive approximation. With no correction, driveDb equals
            // targetDb exactly, same as before this existed.
            float[] driveDb = hasCorrection ? AudioConfig.prewarpEq(targetDb) : targetDb;
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                // Rounded to the nearest achievable gain step exactly the way
                // McuService.updateEqWithFm() rounds it for the actual hardware
                // (round(driveDb[i]/2+6)), clamped [0,12] for both a boost and a cut.
                correctionGains[i] = Math.max(0, Math.min(12, Math.round(driveDb[i] / 2f + 6)));
            }
            if (switchFmSubComp != null && switchFmSubComp.isChecked()) subGainDbDynamic += currentFmSubOffset;
        } else {
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) correctionGains[i] = gs[i]; // no correction until fully initialized - just the real slider gain
        }
        // Rounded to the nearest achievable gain step and clamped [0,12] exactly the way
        // McuService.updateSubwoofer() rounds the real hardware register
        // (round(cachedSubGain + subOffset + ultraBassOffset)) - without this, Ultra Bass's
        // continuous ratio (and Sub Tweaking's own continuous offset) made the dashed line show
        // smooth sub-1dB steps that don't exist on the actual 1dB-stepped hardware register.
        subGainDbBase = Math.max(0, Math.min(12, Math.round(subGainDbBase)));
        subGainDbDynamic = Math.max(0, Math.min(12, Math.round(subGainDbDynamic)));

        // Show on Main is the single master toggle for every loudness-driven addition on the
        // main screen (EQ correction overlay, Sub Tweaking's dynamic offset below, and
        // updateBassVisualizer()'s bass-shelf assist) - the RTA below gets the dynamic data
        // unconditionally regardless, gated only by its own separate reactive-shift flags.
        boolean showOnMain = switchShowLoudnessMain != null && switchShowLoudnessMain.isChecked();
        eqVisualizer.setLoudnessCorrection(correctionGains, showOnMain && hasCorrection);
        eqVisualizer.setSubFilter(Globals.currentSubFreqHz, showOnMain ? subGainDbDynamic : subGainDbBase);

        if (spectrumAnalyzer != null) {
            spectrumAnalyzer.setGains(gs);
            spectrumAnalyzer.setLoudnessCorrection(correctionGains, hasCorrection);
            spectrumAnalyzer.setSubLevel(Globals.currentSubFreqHz, subGainDbDynamic);
        }

        updateBassVisualizer();
    }

    /**
     * Feeds the front/rear "Bass Boost" sliders+spinners (Other tab, see
     * AudioConfig.bassShapingResponseDb()) into the main screen's curve - front is always baked
     * into the curve itself, rear only shows as its own overlay line when it's actually set
     * differently from front (see EqVisualizerView.setBassShaping()). Called from the bass
     * sliders'/spinners' own listeners and from updateVisualizer() (covers preset load/bootstrap,
     * same as every other overlay it feeds).
     */
    private void updateBassVisualizer() {
        if (eqVisualizer == null && fmVisualizer == null && spectrumAnalyzer == null) return;
        // Gated on isFullyInitialized: updateVisualizer() (which calls this) runs during early
        // preset-load bootstrap, before initSecondaryViews()/setupFilterControls() have bound
        // the bass sliders/spinners - same ordering hazard as calculateFmOffsets() above.
        if (!isFullyInitialized) {
            if (eqVisualizer != null) eqVisualizer.setBassShaping(20f, 0f, 0f, 20f, 0f, 0f);
            if (fmVisualizer != null) fmVisualizer.setBassShaping(20f, 0f, 0f);
            if (spectrumAnalyzer != null) spectrumAnalyzer.setBassShaping(20f, 0f, 0f);
            return;
        }
        float frontFilterHz = Float.parseFloat(BASS_FILTER_FREQS[getIntSlider(seekBassFilterFront)]);
        float frontBoostHz = parseBassBoostFreqHz(spinnerBassFreqFront);
        float frontBoostDb = getIntSlider(seekBassBoostFront);
        float rearFilterHz = Float.parseFloat(BASS_FILTER_FREQS[getIntSlider(seekBassFilterRear)]);

        // Visually sync rear's frequency spinner AND Boost gain slider to front's whenever
        // Loudness is enabled (the switch itself, not just while the assist currently has
        // nonzero magnitude) - mirrors McuService.applyBassBoost()'s identical widened gate for
        // the real hardware write, so the widgets, this preview, and the real output all agree.
        // Reverts to rear's own last manually-set frequency/gain (rearBassFreqManualText/
        // rearBassGainManualValue) once Loudness is switched off.
        boolean loudnessEnabled = switchFmEnable != null && switchFmEnable.isChecked();
        String frontSpinnerText = spinnerBassFreqFront.getText().toString();
        if (loudnessEnabled) {
            if (!frontSpinnerText.contentEquals(spinnerBassFreqRear.getText())) {
                spinnerBassFreqRear.setText(frontSpinnerText, false);
            }
            int frontBoostVal = getIntSlider(seekBassBoostFront);
            if (getIntSlider(seekBassBoostRear) != frontBoostVal) {
                seekBassBoostRear.setValue((float) frontBoostVal);
                // setValue() only fires the label-updating listener when the value actually
                // changes - true here by construction (the guard above), but set it explicitly
                // anyway rather than depend on that, same reasoning as loadPreset()'s fix.
                tvBassBoostRearDb.setText(getString(R.string.lbl_db_fmt, frontBoostVal));
            }
        } else {
            if (rearBassFreqManualText != null && !rearBassFreqManualText.contentEquals(spinnerBassFreqRear.getText())) {
                spinnerBassFreqRear.setText(rearBassFreqManualText, false);
            }
            if (rearBassGainManualValue >= 0 && getIntSlider(seekBassBoostRear) != rearBassGainManualValue) {
                seekBassBoostRear.setValue((float) rearBassGainManualValue);
                tvBassBoostRearDb.setText(getString(R.string.lbl_db_fmt, rearBassGainManualValue));
            }
        }

        float rearBoostHz = parseBassBoostFreqHz(spinnerBassFreqRear);
        float rearBoostDb = getIntSlider(seekBassBoostRear);
        // currentFmBassShelfOffset (see calculateFmOffsets()) is loudness compensation's own
        // bass-shelf assist, added on top of whatever's manually set here - see
        // combineBassShelf()'s doc for how the two combine on the one real shelf frequency slot.
        float effFrontBoostHz = combineBassShelfFreq(frontBoostHz);
        float effFrontBoostDb = combineBassShelfDb(frontBoostHz, frontBoostDb);
        // Rear's Boost (gain+freq) syncs to front's own whenever Loudness is enabled - keeps
        // rear's shelf matching the shared EQ curve, which is solved against front's frequency
        // only (see calculateFmOffsets()'s doc). The separate Bass Filter (HPF) above stays
        // independent always - only Boost syncs here. Mirrors McuService.applyBassBoost()'s
        // identical logic for the real hardware write. Falls back to rear's own manual setting,
        // unchanged, whenever Loudness isn't enabled (combineBassShelf* already no-ops then).
        float effRearBoostHz = loudnessEnabled ? effFrontBoostHz : combineBassShelfFreq(rearBoostHz);
        float effRearBoostDb = loudnessEnabled ? effFrontBoostDb : combineBassShelfDb(rearBoostHz, rearBoostDb);
        if (eqVisualizer != null) {
            // Same Show on Main gating as updateVisualizer()'s EQ correction overlay/Sub Tweaking
            // offset - the main screen only bakes the assist into its curve when that's on;
            // otherwise it shows the plain manual Boost settings, same as if loudness weren't
            // running at all. fmVisualizer/spectrumAnalyzer below are unaffected - the Correction
            // tab is the dedicated loudness-preview page, and the RTA has its own separate gate.
            boolean showOnMain = switchShowLoudnessMain != null && switchShowLoudnessMain.isChecked();
            eqVisualizer.setBassShaping(
                    frontFilterHz, showOnMain ? effFrontBoostHz : frontBoostHz, showOnMain ? effFrontBoostDb : frontBoostDb,
                    rearFilterHz, showOnMain ? effRearBoostHz : rearBoostHz, showOnMain ? effRearBoostDb : rearBoostDb);
        }
        // Front only for now - see EqVisualizerView's own front/rear split for why rear would need
        // its own overlay line rather than being baked into this single curve.
        if (fmVisualizer != null) fmVisualizer.setBassShaping(frontFilterHz, effFrontBoostHz, effFrontBoostDb);
        if (spectrumAnalyzer != null) spectrumAnalyzer.setBassShaping(frontFilterHz, effFrontBoostHz, effFrontBoostDb);
    }

    /**
     * Combines a channel's own manually-set "Bass Boost" frequency/gain with loudness
     * compensation's bass-shelf assist (currentFmBassShelfOffset, see calculateFmOffsets() and
     * AudioConfig.LOUDNESS_BASS_SHELF_MAX_DB's doc) - there's only one real shelf filter per
     * channel, but it now follows the user's own manually-chosen frequency rather than forcing a
     * fixed one, so the manual gain always adds on top (see McuService.applyBassBoost()'s
     * identical logic for the real hardware write). "off" (manualBoostHz == 0) has no frequency
     * of its own to contribute, so the assist falls back to the tuned default/most-optimal
     * frequency and no manual gain applies. The manual setting itself is never overwritten -
     * these two methods gate on the Loudness switch itself (matching updateBassVisualizer()'s
     * loudnessEnabled and McuService.applyBassBoost()'s cachedFmEn), not on whether the assist
     * happens to be nonzero at the current volume - currentFmBassShelfOffset can still be 0 here
     * (e.g. volume above the calibration point) and just adds in as a no-op, same result as
     * before this gate was unified.
     */
    private float combineBassShelfFreq(float manualBoostHz) {
        if (!(switchFmEnable != null && switchFmEnable.isChecked())) return manualBoostHz;
        return manualBoostHz > 0f ? manualBoostHz : AudioConfig.LOUDNESS_BASS_SHELF_FREQ_HZ;
    }

    private float combineBassShelfDb(float manualBoostHz, float manualBoostDb) {
        if (!(switchFmEnable != null && switchFmEnable.isChecked())) return manualBoostDb;
        // Clamped to [0, 12] - matches McuService.applyBassBoost()'s identical clamp on the real
        // hardware write. The shelf is a single 0..12 gain register, so the manual gain and the
        // assist can't actually stack past 12 on the real channel, even though nothing here
        // stopped their sum from being computed (and previewed) past it.
        return Math.max(0f, Math.min(12f, currentFmBassShelfOffset + (manualBoostHz > 0f ? manualBoostDb : 0f)));
    }

    /** BASS_BOOST_FREQS[0] is "off" - everything else is a plain Hz string. */
    private float parseBassBoostFreqHz(AutoCompleteTextView spinner) {
        String text = spinner.getText().toString();
        if (text.isEmpty() || text.equalsIgnoreCase(BASS_BOOST_FREQS[0])) return 0f;
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private void updateDbLabel(int i, int p) {
        int val = (p - 6) * 2;
        String text = (val > 0 ? "+" : "") + val;
        dbLabels.get(i).setText(text);
    }

    private void adjustAllBands(int d) {
        isUpdatingUi = true;
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            Slider s = gainSliders.get(i);
            float n = Math.max(0f, Math.min(12f, s.getValue() + d));
            s.setValue(n); updateDbLabel(i, Math.round(n));
        }
        isUpdatingUi = false;
        updateVisualizer();
//        updateEqMcu();
        autoSaveCurrent();
    }

    private void resetAllBands() {
        isUpdatingUi = true;

        // Loop through all 16 bands
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            Slider s = gainSliders.get(i);
            s.setValue(6f);       // 6 is the center point (0 dB)
            updateDbLabel(i, 6);    // Updates the text label above the slider
        }

        isUpdatingUi = false;

        // Refresh the graph, the hardware, and save the state
        updateVisualizer();
//        updateEqMcu();
        autoSaveCurrent();
    }

    private void setPowerVolume(int control) {

        String currentPreset = spinnerPresets.getText().toString();

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String key = currentPreset + "_power_vol";

        int currentVal = prefs.getInt(key, 0);

        if (control == 102) {
            // Floor is 0 (no positive amp power) instead of -3 once ampPositiveDisabled - see its
            // declaration.
            currentVal = Math.max(ampPositiveDisabled ? 0 : -3, currentVal - 1);
        }
        else if (control == 101) {
            currentVal = Math.min(9, currentVal + 1);
        }
        else {
            currentVal = control;
        }

        tvPowerDb.setText(String.valueOf(-currentVal));

        prefs.edit().putInt(key, currentVal).apply();
    }

    // Steps a fader slider (L/R or F/R) by one increment via the small +/- buttons next
    // to it. Slider.setValue() doesn't fire the "fromUser" branch of the slider's own
    // OnChangeListener (see setupFilterControls()), so the label refresh and autosave
    // that would normally happen on a user drag are done explicitly here instead.
    private void adjustFaderStep(Slider slider, int delta) {
        float newValue = Math.max(slider.getValueFrom(), Math.min(slider.getValueTo(), slider.getValue() + delta));
        if (newValue == slider.getValue()) return;
        slider.setValue(newValue);
        updateFaderLabels();
        if (!isUpdatingUi) autoSaveCurrent();
    }

    private void setupSubControls() {
        // 1. Create the adapter (using a standard material-friendly layout)
        ArrayAdapter<String> subAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                SUB_FREQS
        );
        spinnerSubFreq.setAdapter(subAdapter);

        // 2. Set the initial text (replaces setSelection)
        // 'false' is critical here to prevent the dropdown from opening or filtering
        spinnerSubFreq.setText(SUB_FREQS[5], false);
        Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS[5]);

        // 3. Change OnItemSelectedListener to OnItemClickListener
        spinnerSubFreq.setOnItemClickListener((parent, view, pos, id) -> {
            // Logic for Sub Comp limit (if FM Sub Comp is on AND loudness is actually active, limit
            // to 80Hz/Index 5 - Sub Comp's offset is only ever computed while loudness is on, see
            // calculateFmOffsets(), so this restriction shouldn't apply while loudness is off)
            if (isFullyInitialized && switchFmSubComp.isChecked() && switchFmEnable.isChecked() && pos > 5) {
                // Revert the text back to 80Hz (Index 5)
                spinnerSubFreq.setText(SUB_FREQS[5], false);
                Toaster.show(MainActivity.this, getString(R.string.toast_sub_comp_limit));

                // Re-sync Global just in case
                Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS[5]);
                updateVisualizer();
                return;
            }

            if (!isUpdatingUi) {
                autoSaveCurrent();
            }

            // Update the global value for other calculations
            String freqString = SUB_FREQS[pos];
            Globals.currentSubFreqHz = Integer.parseInt(freqString);
            updateVisualizer();
        });

        // 4. Seek Gain logic remains largely the same
        seekSubGain.addOnChangeListener((slider, value, fromUser) -> {
            int p = (int) value;
            String text = "+" + p;
            tvSubDb.setText(text);
            updateVisualizer();
            if (fromUser && !isUpdatingUi) {
                autoSaveCurrent();
            }
        });
    }

    private void setupFilterControls() {
        ArrayAdapter<String> bbAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, BASS_BOOST_FREQS);

        spinnerBassFreqFront.setAdapter(bbAdapter);
        spinnerBassFreqRear.setAdapter(bbAdapter);

        // Replaced the old OnItemSelectedListener with OnItemClickListener. Two separate
        // listeners (not one shared instance like before) since AutoCompleteTextView's callback
        // hands back the dropdown's internal ListView as `parent`, not the AutoCompleteTextView
        // itself - there's no way to tell which of the two spinners fired from inside a shared
        // listener, and Sync Front/Rear Bass now needs to know that to mirror the selection.
        // setText(..., false) doesn't re-trigger this listener (only a real user tap on the
        // dropdown does), so no fromUser-style reentrancy guard is needed here.
        spinnerBassFreqFront.setOnItemClickListener((parent, view, pos, id) -> {
            if (!isUpdatingUi) {
                if (switchSyncBass != null && switchSyncBass.isChecked()) {
                    spinnerBassFreqRear.setText(BASS_BOOST_FREQS[pos], false);
                    rearBassFreqManualText = BASS_BOOST_FREQS[pos];
                }
                autoSaveCurrent();
                updateBassVisualizer();
                // Front's frequency picks the AudioConfig.ISO_RAW_TARGET_BY_FREQ row (see
                // calculateFmOffsets()), so the main-screen loudness curve itself needs a
                // redraw too, not just the bass-shaping overlay.
                updateVisualizer();
            }
        });
        spinnerBassFreqRear.setOnItemClickListener((parent, view, pos, id) -> {
            if (!isUpdatingUi) {
                rearBassFreqManualText = BASS_BOOST_FREQS[pos];
                boolean synced = switchSyncBass != null && switchSyncBass.isChecked();
                if (synced) {
                    spinnerBassFreqFront.setText(BASS_BOOST_FREQS[pos], false);
                }
                autoSaveCurrent();
                updateBassVisualizer();
                // setText(..., false) above doesn't re-trigger spinnerBassFreqFront's own
                // listener, so when synced this rear change also just changed front's effective
                // frequency - the main-screen loudness curve (keyed off front, see
                // calculateFmOffsets()) needs its own redraw in that case too.
                if (synced) updateVisualizer();
            }
        });

        Slider.OnChangeListener bl = (slider, value, fromUser) -> {
            int p = (int) value;
            if (slider == seekBassFilterFront) tvBassFilterFrontVal.setText(getString(R.string.lbl_hz_fmt, BASS_FILTER_FREQS[p]));
            else if (slider == seekBassBoostFront) tvBassBoostFrontDb.setText(getString(R.string.lbl_db_fmt, p));
            else if (slider == seekBassFilterRear) tvBassFilterRearVal.setText(getString(R.string.lbl_hz_fmt, BASS_FILTER_FREQS[p]));
            else if (slider == seekBassBoostRear) tvBassBoostRearDb.setText(getString(R.string.lbl_db_fmt, p));
            if (fromUser && !isUpdatingUi) {
                // Sync Front/Rear Bass: mirror this slider's new value onto its opposite-side
                // counterpart. Only fires on a genuine user drag (fromUser) - the mirrored
                // slider's own setValue() call below re-enters this listener with fromUser=false,
                // so it just updates that slider's label without recursing or double-saving.
                if (switchSyncBass != null && switchSyncBass.isChecked()) {
                    if (slider == seekBassFilterFront) seekBassFilterRear.setValue(value);
                    else if (slider == seekBassFilterRear) seekBassFilterFront.setValue(value);
                    else if (slider == seekBassBoostFront) seekBassBoostRear.setValue(value);
                    else if (slider == seekBassBoostRear) seekBassBoostFront.setValue(value);
                }
                // Track rear's own last manually-set Boost gain (user's own drag, or Sync
                // Front/Rear Bass mirroring front's drag onto it) - see rearBassGainManualValue's
                // own doc for why.
                if (slider == seekBassBoostRear || (slider == seekBassBoostFront && switchSyncBass != null && switchSyncBass.isChecked())) {
                    rearBassGainManualValue = p;
                }
                autoSaveCurrent();
                updateBassVisualizer();
//                    updateBassMcu();
            }
        };
        seekBassFilterFront.addOnChangeListener(bl); seekBassBoostFront.addOnChangeListener(bl);
        seekBassFilterRear.addOnChangeListener(bl); seekBassBoostRear.addOnChangeListener(bl);

        // Standalone app preference (not preset-tied), same pattern as switchShowLoudnessMain -
        // this is a UI convenience toggle, not a DSP setting of its own.
        switchSyncBass.setChecked(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("sync_bass_fr", false));
        switchSyncBass.jumpDrawablesToCurrentState();
        switchSyncBass.setOnCheckedChangeListener((bv, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("sync_bass_fr", checked).apply();
        });

        seekFaderLr.addOnChangeListener((slider, value, fromUser) -> {
            updateFaderLabels();
            if (fromUser && !isUpdatingUi) {
                autoSaveCurrent();
//                updateFaderMcu();
            }
        });
        seekFaderFr.addOnChangeListener((slider, value, fromUser) -> {
            updateFaderLabels();
            if (fromUser && !isUpdatingUi) {
                autoSaveCurrent();
//                updateFaderMcu();
            }
        });

        // Draggable balance dot on top of the car image: drives both fader sliders at once.
        if (balancePointer != null) {
            balancePointer.setOnBalanceChangeListener((lrNorm, frNorm) -> {
                int lr = Math.max(0, Math.min(24, Math.round(12 + lrNorm * 12)));
                int fr = Math.max(0, Math.min(24, Math.round(12 + frNorm * 12)));
                seekFaderLr.setValue(lr);
                seekFaderFr.setValue(fr);
                if (!isUpdatingUi) autoSaveCurrent();
            });
        }
        switchLoud.jumpDrawablesToCurrentState();
        switchLoud.setOnCheckedChangeListener((bv, checked) -> {
            if (!isUpdatingUi) {
                autoSaveCurrent();
//                updateFaderMcu();
            } });
    }

    private void setupDelayControls() {
        Slider.OnChangeListener dl = (slider, value, fromUser) -> {
            int p = (int) value;
            float ms = p * 0.5f; String val = String.format(Locale.getDefault(), getString(R.string.delay_value_format), ms, Math.round(ms * 34.3f));
            if (slider == seekDelayFl) tvDelayFlVal.setText(val); else if (slider == seekDelayFr) tvDelayFrVal.setText(val);
            else if (slider == seekDelayRl) tvDelayRlVal.setText(val); else if (slider == seekDelayRr) tvDelayRrVal.setText(val);
            else if (slider == seekDelaySub) tvDelaySubVal.setText(val);
            if (fromUser && !isUpdatingUi) {
                // Sync Front/Rear L-R: mirror this slider's new value onto its opposite-side
                // counterpart, same pattern as the Other tab's "Sync Front/Rear Bass" - only on a
                // genuine user drag, the mirrored slider's own setValue() re-enters this listener
                // with fromUser=false so it just updates its label without recursing.
                if (switchSyncDelayFront != null && switchSyncDelayFront.isChecked()) {
                    if (slider == seekDelayFl) seekDelayFr.setValue(value);
                    else if (slider == seekDelayFr) seekDelayFl.setValue(value);
                }
                if (switchSyncDelayRear != null && switchSyncDelayRear.isChecked()) {
                    if (slider == seekDelayRl) seekDelayRr.setValue(value);
                    else if (slider == seekDelayRr) seekDelayRl.setValue(value);
                }
                autoSaveCurrent();
            }
        };
        seekDelayFl.addOnChangeListener(dl); seekDelayFr.addOnChangeListener(dl);
        seekDelayRl.addOnChangeListener(dl); seekDelayRr.addOnChangeListener(dl); seekDelaySub.addOnChangeListener(dl);
        switchPreciseEnable.jumpDrawablesToCurrentState();
        switchPreciseEnable.setOnCheckedChangeListener((bv, checked) -> {
            if (!isUpdatingUi) {
                if (checked) switchLegacyEnable.setChecked(false);
//                updateDelayMcu();
                autoSaveCurrent();
            }
        });

        // Standalone app preferences (not preset-tied), same pattern as switchShowLoudnessMain /
        // switchSyncBass - UI convenience toggles, not DSP settings of their own. Shared by both
        // delay systems (this group and setupDelay1Controls()'s legacy/RSSE group below).
        switchSyncDelayFront.setChecked(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("sync_delay_front", false));
        switchSyncDelayFront.jumpDrawablesToCurrentState();
        switchSyncDelayFront.setOnCheckedChangeListener((bv, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("sync_delay_front", checked).apply());

        switchSyncDelayRear.setChecked(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("sync_delay_rear", false));
        switchSyncDelayRear.jumpDrawablesToCurrentState();
        switchSyncDelayRear.setOnCheckedChangeListener((bv, checked) ->
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("sync_delay_rear", checked).apply());
    }

    private void setupDelay1Controls() {
        Slider.OnChangeListener dl = (slider, value, fromUser) -> {
            int p = (int) value;
            if (slider == seekDelay1RSSE) { 
                int v = p - 10; 
                String text = (v > 0 ? "+" : "") + v;
                tvDelay1RSSEVal.setText(text); 
            }
            else { float ms = p * 1.0f; String val = String.format(Locale.getDefault(), getString(R.string.delay_value_format), ms, Math.round(ms * 34.3f));
                if (slider == seekDelay1Fl) tvDelay1FlVal.setText(val); else if (slider == seekDelay1Fr) tvDelay1FrVal.setText(val);
                else if (slider == seekDelay1Rl) tvDelay1RlVal.setText(val); else if (slider == seekDelay1Rr) tvDelay1RrVal.setText(val);
            }
            if (fromUser && !isUpdatingUi) {
                // Same "Sync Front/Rear L-R" toggles as the precise delay group above - shared
                // preference, applies to this (legacy/RSSE) delay slider pair too.
                if (switchSyncDelayFront != null && switchSyncDelayFront.isChecked()) {
                    if (slider == seekDelay1Fl) seekDelay1Fr.setValue(value);
                    else if (slider == seekDelay1Fr) seekDelay1Fl.setValue(value);
                }
                if (switchSyncDelayRear != null && switchSyncDelayRear.isChecked()) {
                    if (slider == seekDelay1Rl) seekDelay1Rr.setValue(value);
                    else if (slider == seekDelay1Rr) seekDelay1Rl.setValue(value);
                }
                autoSaveCurrent();
            }
        };
        seekDelay1Fl.addOnChangeListener(dl); seekDelay1Fr.addOnChangeListener(dl);
        seekDelay1Rl.addOnChangeListener(dl); seekDelay1Rr.addOnChangeListener(dl); seekDelay1RSSE.addOnChangeListener(dl);
        switchLegacyEnable.jumpDrawablesToCurrentState();
        switchLegacyEnable.setOnCheckedChangeListener((bv, checked) -> {
            if (!isUpdatingUi) {
                if (checked) switchPreciseEnable.setChecked(false);
//                updateDelay1Mcu();
                autoSaveCurrent();
            }
        });
    }

    private void setupFmControls() {
        switchFmEnable.jumpDrawablesToCurrentState();
        switchFmEnable.setOnCheckedChangeListener((bv, checked) -> {
            if (!isUpdatingUi) {
                if (checked) Toaster.show(this, getString(R.string.toast_loudness_sync_bass), Toast.LENGTH_LONG);
                autoSaveCurrent();
                updateFmVisualizer();
            }
        });
        switchFatigueEnable.jumpDrawablesToCurrentState();
        switchFatigueEnable.setOnCheckedChangeListener((bv, checked) -> {
            if (!isUpdatingUi) {
                autoSaveCurrent();
                updateFmVisualizer();
//                updateEqMcu();
            }
        });
        switchFmSubComp.jumpDrawablesToCurrentState();
        switchFmSubComp.setOnCheckedChangeListener((bv, checked) -> {
            if (!isUpdatingUi) {
                if (checked && switchFmEnable.isChecked() && java.util.Arrays.asList(SUB_FREQS).indexOf(spinnerSubFreq.getText().toString()) > 5) {
                    spinnerSubFreq.setText(SUB_FREQS[5], false);
                    Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS[5]);
                }
                autoSaveCurrent();
                updateFmVisualizer();
            }
        });
        // Standalone app preference (not per-preset, not gated on isUpdatingUi) - purely a display
        // choice for the main EQ/spectrum visualizers, doesn't touch the real EQ sliders or MCU
        // data at all - see updateVisualizer().
        switchShowLoudnessMain.setChecked(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("show_loudness_on_main", false));
        switchShowLoudnessMain.jumpDrawablesToCurrentState();
        switchShowLoudnessMain.setOnCheckedChangeListener((bv, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("show_loudness_on_main", checked).apply();
            updateVisualizer();
        });
        // True 2.2 Display is no longer a user toggle - always show the real composite Q=2.2
        // response (bypassing the flatness blend) on both curve views.
        if (eqVisualizer != null) eqVisualizer.setTrueQDisplay(true);
        if (fmVisualizer != null) fmVisualizer.setTrueQDisplay(true);
        // "Ultra Bass" - per-preset, like switchFmEnable/switchFatigueEnable above (loaded via
        // loadPreset(), not a standalone SharedPreferences flag), and fully independent of them -
        // see McuService.updateSubwoofer()'s identical calc for the real hardware write.
        switchUltraBass.jumpDrawablesToCurrentState();
        switchUltraBass.setOnCheckedChangeListener((bv, checked) -> {
            if (!isUpdatingUi) {
                autoSaveCurrent();
                updateFmVisualizer();
            }
        });
        Slider.OnChangeListener fml = (slider, value, fromUser) -> {
            int p = (int) value;
            if (slider == seekFmCalVol) tvFmCalVolVal.setText(String.valueOf(p));
            else if (slider == seekFatStartVol) tvFatStartVolVal.setText(String.valueOf(p));
            else tvFmStrengthVal.setText(String.valueOf(p));
            if (fromUser && !isUpdatingUi) {
                updateFmVisualizer();
                autoSaveCurrent();
            }
        };
        seekFmCalVol.addOnChangeListener(fml); seekFmStrength.addOnChangeListener(fml); seekFatStartVol.addOnChangeListener(fml);

        Slider.OnChangeListener ubl = (slider, value, fromUser) -> {
            int p = (int) value;
            if (slider == seekUltraBassStartVol) tvUltraBassStartVolVal.setText(String.valueOf(p));
            else tvUltraBassMaxDbVal.setText(getString(R.string.lbl_db_fmt, p));
            if (fromUser && !isUpdatingUi) {
                updateFmVisualizer();
                autoSaveCurrent();
            }
        };
        seekUltraBassStartVol.addOnChangeListener(ubl); seekUltraBassMaxDb.addOnChangeListener(ubl);
    }

    private void updateFmVisualizer() {
        if (fmVisualizer == null) return;
        float[] offs = calculateFmOffsets();
        int[] gs = new int[AudioConfig.NUM_BANDS]; float[] actual = new float[AudioConfig.NUM_BANDS]; float[] warns = new float[AudioConfig.NUM_BANDS];
        int vol = (currentEffectiveVolume != -1) ? currentEffectiveVolume : getSystemVolume(); 
        tvSysVolumeVal.setText(String.valueOf(vol));
        boolean hasCorrection = false;
        int[] sliderVals = new int[AudioConfig.NUM_BANDS];
        float[] targetDb = new float[AudioConfig.NUM_BANDS];
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            if (offs[i] != 0f) hasCorrection = true;
            sliderVals[i] = getIntSlider(gainSliders.get(i));
            targetDb[i] = (sliderVals[i] - 6) * 2 + offs[i];
        }
        // Jointly pre-warped the same way McuService.updateEqWithFm() pre-warps the real
        // hardware send (see AudioConfig.prewarpEq()'s doc) - this curve shows the real
        // achieved value, slider ripple and loudness ripple cross-talk-cancelled together, not
        // the pre-pre-warp additive approximation. With no correction, driveDb equals targetDb
        // exactly, same as before this existed.
        float[] driveDb = hasCorrection ? AudioConfig.prewarpEq(targetDb) : targetDb;
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float pot = driveDb[i] / 2f + 6;
            float wOffset = (pot - 12f) * 2; warns[i] = (pot > 12.025f && wOffset > 0.999f) ? wOffset : 0f;
            // pot is exactly what McuService.updateEqWithFm() rounds for the real hardware -
            // round and clamp it the same way here so this curve shows the actual value that
            // would be sent, not an offset-from-neutral value that rounds against a different
            // (and usually wrong) step boundary than whatever the real slider is already
            // sitting at.
            gs[i] = Math.max(0, Math.min(12, Math.round(pot)));
            // The number drawn above each point (see FmVisualizerView's "val" label) should match
            // gs[i] exactly - the REAL achieved change once rounded to a real 2dB step and clamped,
            // not the raw theoretical "total" offset before rounding/clamping. Otherwise a band
            // that rounds away to nothing (or gets ceiling-clipped) would still show a misleading
            // non-zero number next to a point that visibly didn't move.
            actual[i] = (gs[i] - sliderVals[i]) * 2f;
        }
        fmVisualizer.setGains(gs); fmVisualizer.setOffsets(actual); fmVisualizer.setWarnings(warns);
        if (switchFmSubComp.isChecked()) {
            tvSubOffsetVal.setText(String.format(Locale.getDefault(), getString(R.string.lbl_db_fmt2), currentFmSubOffset));
            float subPot = currentFmSubOffset + seekSubGain.getValue();
            tvSubOffsetWarn.setText(subPot > 12.25f ? String.format(Locale.getDefault(), getString(R.string.lbl_db_fmt2), subPot - 12f) : "OK");
        } else { tvSubOffsetVal.setText(getString(R.string.none)); tvSubOffsetWarn.setText(getString(R.string.none)); }
        // Same total (base + loudness-compensated + Ultra Bass) sub gain as the subPot warning
        // check above, rounded and clamped [0,12] exactly the way McuService.updateSubwoofer()
        // rounds the real hardware register - without this, Ultra Bass's continuous ratio made
        // this curve show smooth sub-1dB steps that don't exist on the actual hardware.
        // Ultra Bass is independent of Loudness/switchFmSubComp - added unconditionally.
        float subGainDb = Math.max(0, Math.min(12, Math.round(seekSubGain.getValue() + (switchFmSubComp.isChecked() ? currentFmSubOffset : 0f) + calculateUltraBassOffset(vol))));
        fmVisualizer.setSubFilter(Globals.currentSubFreqHz, subGainDb);
        fmVisualizer.invalidate();
        // Every place that refreshes this preview should also keep the main screen's optional
        // loudness-correction preview (see switchShowLoudnessMain) in sync, rather than hunting
        // down each individual call site (volume changes, switch toggles, cal/strength sliders,
        // nav to this tab, preset loads) - updateVisualizer() itself is a cheap no-op when the
        // main-screen toggle is off.
        updateVisualizer();
    }

    private float[] calculateFmOffsets() {
        float[] offs = new float[AudioConfig.NUM_BANDS]; currentFmSubOffset = 0f; currentFmBassShelfOffset = 0f;
        // No Math.max(1, ...) floor here - McuService.updateFmOffsets()/updateSubwoofer()/
        // applyBassBoost() all use the raw volume with no such clamp, so keeping it here made
        // this preview compute a very slightly weaker ratio than hardware at volume exactly 0.
        int vol = (currentEffectiveVolume != -1) ? currentEffectiveVolume : getSystemVolume();
        int cal = getIntSlider(seekFmCalVol); float str = getIntSlider(seekFmStrength) / 100f;
        // Deadzone must match McuService.updateFmOffsets() exactly, or this preview
        // won't match what's actually sent to the MCU.
        int deadzone = 1;
        if (vol < (cal - deadzone) && switchFmEnable.isChecked()) {
            float range = Math.max(1, cal - deadzone);
            float ratio = (range - vol) / range;
            // Matches McuService.updateFmOffsets()'s own row selection - the EQ's residual job
            // depends on which frequency the front Bass Boost shelf is actually carrying the low
            // end at right now (see AudioConfig.isoRawTargetForFreqHz()'s doc). This is the RAW
            // (not pre-warped) target - updateVisualizer()/updateFmVisualizer() jointly pre-warp
            // it together with the slider's own dB via AudioConfig.prewarpEq(), so don't
            // pre-warp it again here.
            float[] isoRawTarget = AudioConfig.isoRawTargetForFreqHz(parseBassBoostFreqHz(spinnerBassFreqFront));
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) offs[i] = isoRawTarget[i] * ratio * str;
            // Bass-shelf assist (see AudioConfig.LOUDNESS_BASS_SHELF_MAX_DB's doc) - part of the
            // core loudness curve now, not gated behind the optional "Sub Tweaking" toggle like
            // currentFmSubOffset below is.
            currentFmBassShelfOffset = AudioConfig.LOUDNESS_BASS_SHELF_MAX_DB * ratio * str;
            if (switchFmSubComp.isChecked()) {
                // Kept in sync with McuService.getMaxBassBoost() - see its comment for why this
                // references the next lower EQ band's offset instead of the matching band.
                // ISO_FULL_TARGET_DB, not ISO_MAX_OFFSETS - the sub channel is a separate hardware
                // output from the 16-band EQ/bass-shelf split, so it targets the real intended
                // boost, not the EQ's own (now much smaller) residual share of it.
                // No branch above 80Hz - intentional, see McuService.getMaxBassBoost()'s identical
                // doc: 100Hz+ isn't "deep bass" any more and is already covered by the main 16-band
                // EQ's own offsets, so skipping the sub-channel assist there avoids double-compensating.
                int currentSubFreq = Globals.currentSubFreqHz;
                if (currentSubFreq == 80) currentFmSubOffset = AudioConfig.ISO_FULL_TARGET_DB[2] * ratio * str;
                else if (currentSubFreq == 63 || currentSubFreq == 50) currentFmSubOffset = AudioConfig.ISO_FULL_TARGET_DB[1] * ratio * str;
                else if (currentSubFreq == 40 || currentSubFreq == 32) currentFmSubOffset = AudioConfig.ISO_FULL_TARGET_DB[0] * ratio * str;
                else if (currentSubFreq == 25) currentFmSubOffset = AudioConfig.ISO_FULL_TARGET_DB[0] * ratio * str;
            }
        } else if (switchFatigueEnable.isChecked()) {
            // Trim Highs' own threshold - decoupled from cal (Loudness' Calibration Point, used
            // by the ISO branch above only). Matches McuService.updateFmOffsets()'s identical swap.
            int fatStartVol = getIntSlider(seekFatStartVol);
            if (vol > (fatStartVol + deadzone)) {
                float range = Math.max(1, 32 - (fatStartVol + deadzone));
                float ratio = (vol - (fatStartVol + deadzone)) / range;
                for (int i = 0; i < AudioConfig.NUM_BANDS; i++) offs[i] = AudioConfig.FATIGUE_RAW_TARGET[i] * ratio * str;
            }
        }
        return offs;
    }

    /**
     * "Ultra Bass" preview - plain linear ramp with volume, 0 at the Start Volume slider up to
     * the Max Boost slider's value at volume 32, completely independent of Loudness/Fatigue (no
     * calibration point, no switchFmEnable check) - see McuService.updateSubwoofer()'s identical
     * calc for the real hardware write. Null-guarded (not isFullyInitialized-gated like
     * calculateFmOffsets()) since this is meant to be callable from updateVisualizer() even during
     * early bootstrap, before setupFmControls() has bound these views.
     */
    private float calculateUltraBassOffset(int vol) {
        if (switchUltraBass == null || seekUltraBassStartVol == null || seekUltraBassMaxDb == null) return 0f;
        if (!switchUltraBass.isChecked()) return 0f;
        int startVol = getIntSlider(seekUltraBassStartVol);
        int maxDb = getIntSlider(seekUltraBassMaxDb);
        if (vol <= startVol) return 0f;
        float range = Math.max(1, 32 - startVol);
        float ratio = Math.min(1f, (vol - startVol) / range);
        return maxDb * ratio;
    }

    private void setupPresets() {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Set<String> names = p.getStringSet(PREF_PRESET_NAMES, null);
        String last = p.getString(PREF_LAST_SELECTED, null);

        presetNames = new ArrayList<>();
        if (names != null) {
            presetNames.addAll(names);
            Collections.sort(presetNames);
        }

        defaultPreset = getString(R.string.default_preset_name);
        if (presetNames.isEmpty()) {
            presetNames.add(defaultPreset);
            savePresetList();
            savePreset(defaultPreset);
        }

        // Use a simpler layout for the list items (standard Android or a custom one)
        presetAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, presetNames);
        spinnerPresets.setAdapter(presetAdapter);

        // Initial load
        String toLoad = (last != null && presetNames.contains(last)) ? last : presetNames.get(0);

        // NOTE: Use setText(value, filter) for AutoCompleteTextView
        spinnerPresets.setText(toLoad, false);
        loadPreset(toLoad);

        // Change from setOnItemSelectedListener to setOnItemClickListener
        spinnerPresets.setOnItemClickListener((parent, view, position, id) -> {
            String s = presetNames.get(position);
            if (!isUpdatingUi) {
                loadPreset(s);
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_LAST_SELECTED, s).apply();
            }
        });
    }

    private void ensureCallPresetExists() {
        if (!presetNames.contains("Call")) {
            presetNames.add("Call");
            Collections.sort(presetNames);
            savePresetList();
            writeCallPresetDefaults();
            presetAdapter.notifyDataSetChanged();
        }
    }

    // Optimized for voice intelligibility during a phone call, not for music: bass filter maxed
    // (highpasses all midbass off the door speakers) plus sub pushed down to 25Hz leaves no bass
    // reinforcement anywhere, which is intentional - a call's own bandwidth has no bass to begin
    // with. Fader pushed fully front since rear speakers just add echo/reverb to a mono call
    // signal. All loudness curves, delays and GALA disabled - none of them help intelligibility,
    // and delays in particular would otherwise silently carry over 0 instead of this preset's own
    // calibration since it's a separate, independent set of keys from every other preset.
    private void writeCallPresetDefaults() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            e.putInt("Call_g" + i, 6); // flat (0dB)
            e.putBoolean("Call_q" + i, false);
        }
        e.putInt("Call_sub_g", 0);
        e.putInt("Call_sub_f", 0); // 25Hz
        e.putInt("Call_bf_f", BASS_FILTER_FREQS.length - 1); // max filter freq (250Hz)
        e.putInt("Call_bb_f", 0); // boost disabled
        e.putInt("Call_bf_r", BASS_FILTER_FREQS.length - 1);
        e.putInt("Call_bb_r", 0);
        e.putInt("Call_bb_frq_f", 0);
        e.putInt("Call_bb_frq_r", 0);
        e.putInt("Call_f_lr", 12); // centered L/R
        e.putInt("Call_f_fr", 24); // full front
        e.putBoolean("Call_loud", false);
        e.putBoolean("Call_fm_en", false);
        e.putBoolean("Call_fat_en", false);
        e.putBoolean("Call_sub_comp", false);
        e.putInt("Call_fm_cal", 25);
        e.putInt("Call_fm_str", 100);
        e.putInt("Call_fat_start_vol", 25);
        e.putBoolean("Call_ultra_bass_en", false);
        e.putInt("Call_ultra_bass_start_vol", 16);
        e.putInt("Call_ultra_bass_max_db", 6);
        e.putInt("Call_d_fl", 0);
        e.putInt("Call_d_fr", 0);
        e.putInt("Call_d_rl", 0);
        e.putInt("Call_d_rr", 0);
        e.putInt("Call_d_sub", 0);
        e.putBoolean("Call_d_en", false);
        e.putInt("Call_d1_fl", 0);
        e.putInt("Call_d1_fr", 0);
        e.putInt("Call_d1_rl", 0);
        e.putInt("Call_d1_rr", 0);
        e.putInt("Call_rsse_val", 10);
        e.putBoolean("Call_d1_en", false);
        e.putBoolean("Call_gala_enabled", false);
        e.putInt("Call_gala_increment", 15);
        e.putInt("Call_gala_min_speed", 0);
        e.putInt("Call_gala_max_adj", 12);
        e.putInt("Call_gala_fade_ms", 100);
        e.putInt("Call_gala_hold_ms", 1000);
        // Forces GALA off for Call even if Global GALA is on for every other preset - "Call_gala_enabled"
        // above only covers the case where global mode is off.
        e.putBoolean("Call_gala_disabled_for_preset", true);
        e.putInt("Call_power_vol", 0);
        e.apply();
    }

    private void addNewPreset() {
        int c = 1;
        String n;
        String prefix = getString(R.string.default_preset_name).split(" ")[0] + " ";
        do {
            n = prefix + c++;
        } while (presetNames.contains(n));
        presetNames.add(n);
        Collections.sort(presetNames);
        savePresetList();
        savePreset(n);
        presetAdapter.notifyDataSetChanged();

        spinnerPresets.setText(n, false);
        loadPreset(n);
    }

    private void renameCurrentPreset() {
        final String oldName = spinnerPresets.getText().toString();

        // Prevent renaming the protected "Call" preset immediately
        if ("Call".equals(oldName)) {
            Toaster.show(this, "ERROR"); // Ensure this string exists or use a literal
            return;
        }

        // 1. Create the EditText with Material styling
        com.google.android.material.textfield.TextInputEditText editText = new com.google.android.material.textfield.TextInputEditText(this);
        editText.setText(oldName);
        editText.setSelection(oldName.length());
        editText.setSingleLine(true);

        // 2. Wrap it in a TextInputLayout to get the Material look (outline/hint)
        com.google.android.material.textfield.TextInputLayout inputLayout = new com.google.android.material.textfield.TextInputLayout(this);
        inputLayout.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setHint(getString(R.string.dialog_rename_title));
        inputLayout.setBoxCornerRadii(12, 12, 12, 12); // Optional: match your app's roundness

        // 3. Add margins to the container so the input isn't flush against the dialog edges
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        int margin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20, getResources().getDisplayMetrics());
        params.leftMargin = margin;
        params.rightMargin = margin;
        params.topMargin = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        inputLayout.setLayoutParams(params);

        inputLayout.addView(editText);
        container.addView(inputLayout);

        // 4. Build using MaterialAlertDialogBuilder
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_rename_title)
                .setView(container)
                .setPositiveButton(R.string.btn_ok, (d, w) -> {
                    String newName = Objects.requireNonNull(editText.getText()).toString().trim();
                    if (!newName.isEmpty() && !newName.equals(oldName)) {
                        if (presetNames.contains(newName)) {
                            Toaster.show(this, getString(R.string.toast_exists));
                        } else {
                            performRename(oldName, newName);
                        }
                    }
                })
                .setNegativeButton(R.string.btn_cancel, null)
                .show();
    }

    private void performRename(String o, String n) {
        if ("Call".equals(o)) return;

        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();

        // 1. Move the actual EQ/Filter data (existing logic)
        copyPresetData(p, e, o, n);

        // 2. Update the Automation Map (The fix for your question)
        try {
            String jsonMap = p.getString(PREF_PLAYER_MAP, "{}");
            // Using Gson (which you already have in dependencies) to parse the map
            java.lang.reflect.Type type = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> map = new Gson().fromJson(jsonMap, type);

            boolean mapChanged = false;
            if (map != null) {
                for (Map.Entry<String, String> entry : map.entrySet()) {
                    if (o.equals(entry.getValue())) {
                        entry.setValue(n); // Update the link to the new name
                        mapChanged = true;
                    }
                }
            }

            if (mapChanged) {
                e.putString(PREF_PLAYER_MAP, new Gson().toJson(map));
            }

            // 3. Update the Global Default if it was renamed
            String currentDefault = p.getString(PREF_DEFAULT_PRESET, "");
            if (o.equals(currentDefault)) {
                e.putString(PREF_DEFAULT_PRESET, n);
            }

        } catch (Exception err) {
            Log.e(TAG, "Error updating automation map during rename: " + err.getMessage());
        }

        // 4. Update the preset list (existing logic)
        int idx = presetNames.indexOf(o);
        presetNames.set(idx, n);
        Collections.sort(presetNames);

        if (o.equals(p.getString(PREF_LAST_SELECTED, null))) {
            e.putString(PREF_LAST_SELECTED, n);
        }

        e.putStringSet(PREF_PRESET_NAMES, new HashSet<>(presetNames));
        e.apply();

        presetAdapter.notifyDataSetChanged();
        spinnerPresets.setText(n, false);
        loadPreset(n);
    }

    private void copyPresetData(SharedPreferences p, SharedPreferences.Editor e, String o, String n) {
        String[] keys = {"_sub_g", "_sub_f", "_bf_f", "_bb_f", "_bf_r", "_bb_r", "_bb_frq_f", "_bb_frq_r", "_f_lr", "_f_fr", "_loud", "_fm_en", "_fat_en", "_fat_start_vol", "_sub_comp", "_fm_cal", "_fm_str", "_ultra_bass_en", "_ultra_bass_start_vol", "_ultra_bass_max_db", "_d_fl", "_d_fr", "_d_rl", "_d_rr", "_d_sub", "_d_en", "_d1_fl", "_d1_fr", "_d1_rl", "_d1_rr", "_rsse_val", "_d1_en", "_gala_enabled", "_gala_increment", "_gala_min_speed", "_gala_max_speed", "_gala_max_adj", "_gala_fade_ms", "_gala_hold_ms", "_gala_disabled_for_preset", "_power_vol"};
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            String g = "_g" + i, q = "_q" + i; e.putInt(n+g, p.getInt(o+g, 6)); e.putBoolean(n+q, p.getBoolean(o+q, false)); e.remove(o+g); e.remove(o+q);
        }
        for (String k : keys) {
            Object v = p.getAll().get(o + k);
            if (v instanceof Integer) e.putInt(n + k, (Integer) v); else if (v instanceof Boolean) e.putBoolean(n + k, (Boolean) v);
            e.remove(o + k);
        }
    }

    private void deleteCurrentPreset() {
        String curr = spinnerPresets.getText().toString();
        int currindex = presetNames.indexOf(curr);
        if ("Call".equals(curr)) {
            // Call can't actually be deleted (automation depends on it always existing) - instead
            // "delete" resets it back to its own optimized defaults, same as a fresh install.
            writeCallPresetDefaults();
            loadPreset("Call");
            return;
        }
        if (presetNames.size() <= 1) {
            Toaster.show(this, getString(R.string.toast_cannot_delete_last));
            return;
        }
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet()) if (key.startsWith(curr + "_")) e.remove(key);
        try {
            JSONObject playerMap = new JSONObject(p.getString("player_preset_map", "{}"));
            JSONObject updatedMap = new JSONObject();
            Iterator<String> keys = playerMap.keys();
            while (keys.hasNext()) {
                String playerName = keys.next(); String linkedPreset = playerMap.getString(playerName);
                if (!linkedPreset.equals(curr)) updatedMap.put(playerName, linkedPreset);
            }
            e.putString("player_preset_map", updatedMap.toString());
        } catch (Exception err) {
            Log.e(TAG, "Error updating player map: " + err.getMessage());
        }
        String defaultPreset = getString(R.string.default_preset_name);
        if (curr.equals(p.getString("default_preset_name", ""))) e.putString("default_preset_name", defaultPreset);
        presetNames.remove(curr);
        if (presetNames.isEmpty()) { presetNames.add(defaultPreset); resetUiInternal(); savePreset(defaultPreset); }
        e.putStringSet(PREF_PRESET_NAMES, new HashSet<>(presetNames));
        e.apply();
        presetAdapter.notifyDataSetChanged();

        String newName = presetNames.get(Math.max(currindex - 1, 0));
        spinnerPresets.setText(newName, false);
        loadPreset(newName);
    }

    private int getIntSlider(Slider s) {
        return Math.round(s.getValue());
    }

    private void savePreset(String name) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) { e.putInt(name + "_g" + i, getIntSlider(gainSliders.get(i))); e.putBoolean(name + "_q" + i, qSwitches.get(i).isChecked()); }
        e.putInt(name + "_sub_g", getIntSlider(seekSubGain)); e.putInt(name + "_sub_f", java.util.Arrays.asList(SUB_FREQS).indexOf(spinnerSubFreq.getText().toString()));
        if (isFullyInitialized) {
            e.putInt(name + "_bf_f", getIntSlider(seekBassFilterFront));
            e.putInt(name + "_bb_f", getIntSlider(seekBassBoostFront));
            e.putInt(name + "_bf_r", getIntSlider(seekBassFilterRear));
            // Rear's Boost (gain+freq) always persists rearBassGainManualValue/rearBassFreqManualText
            // - the user's own last manual choice - never whatever's currently showing on the
            // widgets, which while Loudness is on is forcibly mirrored to front's value (see
            // updateBassVisualizer()'s doc). Saving the mirrored value instead would permanently
            // clobber rear's real setting with front's the moment any autosave fires while Loudness
            // is active - McuService.applyBassBoost() already relies on rear's saved prefs staying
            // untouched while Loudness is on ("never touched, just not used"), so this keeps that
            // invariant true on the UI/save side too.
            e.putInt(name + "_bb_r", rearBassGainManualValue >= 0 ? rearBassGainManualValue : getIntSlider(seekBassBoostRear));

            int frontFreqIdx = java.util.Arrays.asList(BASS_BOOST_FREQS).indexOf(spinnerBassFreqFront.getText().toString());
            int rearFreqIdx = rearBassFreqManualText != null
                    ? java.util.Arrays.asList(BASS_BOOST_FREQS).indexOf(rearBassFreqManualText)
                    : java.util.Arrays.asList(BASS_BOOST_FREQS).indexOf(spinnerBassFreqRear.getText().toString());
            e.putInt(name + "_bb_frq_f", Math.max(0, frontFreqIdx));
            e.putInt(name + "_bb_frq_r", Math.max(0, rearFreqIdx));

            e.putInt(name + "_f_lr", getIntSlider(seekFaderLr));
            e.putInt(name + "_f_fr", getIntSlider(seekFaderFr));
            e.putBoolean(name + "_loud", switchLoud.isChecked());
            e.putBoolean(name + "_fm_en", switchFmEnable.isChecked());
            e.putBoolean(name + "_fat_en", switchFatigueEnable.isChecked());
            e.putBoolean(name + "_sub_comp", switchFmSubComp.isChecked());
            e.putInt(name + "_fm_cal", getIntSlider(seekFmCalVol));
            e.putInt(name + "_fm_str", getIntSlider(seekFmStrength));
            e.putInt(name + "_fat_start_vol", getIntSlider(seekFatStartVol));
            e.putBoolean(name + "_ultra_bass_en", switchUltraBass.isChecked());
            e.putInt(name + "_ultra_bass_start_vol", getIntSlider(seekUltraBassStartVol));
            e.putInt(name + "_ultra_bass_max_db", getIntSlider(seekUltraBassMaxDb));
            e.putInt(name + "_d_fl", getIntSlider(seekDelayFl));
            e.putInt(name + "_d_fr", getIntSlider(seekDelayFr));
            e.putInt(name + "_d_rl", getIntSlider(seekDelayRl));
            e.putInt(name + "_d_rr", getIntSlider(seekDelayRr));
            e.putInt(name + "_d_sub", getIntSlider(seekDelaySub));
            e.putBoolean(name + "_d_en", switchPreciseEnable.isChecked());
            e.putInt(name + "_d1_fl", getIntSlider(seekDelay1Fl));
            e.putInt(name + "_d1_fr", getIntSlider(seekDelay1Fr));
            e.putInt(name + "_d1_rl", getIntSlider(seekDelay1Rl));
            e.putInt(name + "_d1_rr", getIntSlider(seekDelay1Rr));
            e.putInt(name + "_rsse_val", getIntSlider(seekDelay1RSSE));
            e.putBoolean(name + "_d1_en", switchLegacyEnable.isChecked());
            
            // GALA - galaNamespace(name) resolves to GALA_GLOBAL_NAMESPACE instead of this
            // preset's own name when global mode is on, so these land in the one shared bucket
            // every preset reads back from instead of this preset's own data.
            String galaNs = galaNamespace(name);
            e.putBoolean(galaNs + "_gala_enabled", switchGalaEnable.isChecked());
            e.putInt(galaNs + "_gala_increment", getIntSlider(seekGalaInc));
            e.putInt(galaNs + "_gala_min_speed", getIntSlider(seekGalaMinSpeed));
            e.putInt(galaNs + "_gala_max_adj", getIntSlider(seekGalaMaxAdj));
            e.putInt(galaNs + "_gala_fade_ms", getIntSlider(seekGalaFadeMs));
            e.putInt(galaNs + "_gala_hold_ms", getIntSlider(seekGalaHoldMs));
            // Always this preset's own data, never the global bucket - lets a preset opt out of
            // GALA even while global mode is on for every other preset.
            e.putBoolean(name + "_gala_disabled_for_preset", switchGalaDisableForPreset.isChecked());

            e.putInt(name + "_power_vol", -Integer.parseInt(tvPowerDb.getText().toString()));

        }
        e.apply();
    }

    private void loadPreset(String name) {
        isUpdatingUi = true;
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            int g = p.getInt(name + "_g" + i, 6); gainSliders.get(i).setValue((float) g); updateDbLabel(i, g);
            qSwitches.get(i).setChecked(p.getBoolean(name + "_q" + i, false));
        }
        int sg = p.getInt(name + "_sub_g", 0); seekSubGain.setValue((float) sg);
        String subText = "+" + sg;
        tvSubDb.setText(subText);
        int subFreqIdx = p.getInt(name + "_sub_f", 5); // 5 is the default (80Hz)
        if (subFreqIdx < 0 || subFreqIdx >= SUB_FREQS.length) {
            subFreqIdx = 5; // Safety fallback
        }
        spinnerSubFreq.setText(SUB_FREQS[subFreqIdx], false);
        // setText(..., false) doesn't trigger the spinner's own OnItemClickListener - which is
        // the only other place Globals.currentSubFreqHz gets updated - so without this, the sub
        // overlay curve stays stuck on whatever frequency was last manually tapped instead of
        // following the frequency each preset actually loads.
        Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS[subFreqIdx]);
        if (isFullyInitialized) {
            // Slider.setValue() only fires the OnChangeListener (which is what normally updates
            // these labels, see the "bl" listener in setupFilterControls()) when the new value
            // actually differs from the slider's current one - a loaded value that happens to
            // match left the label stuck on its XML placeholder ("Not set") forever. Setting the
            // label explicitly here, same as gainSliders/seekSubGain already do a few lines up,
            // makes this not depend on that listener firing at all.
            int bfF = p.getInt(name + "_bf_f", 0);
            seekBassFilterFront.setValue((float) bfF);
            tvBassFilterFrontVal.setText(getString(R.string.lbl_hz_fmt, BASS_FILTER_FREQS[bfF]));
            int bbF = p.getInt(name + "_bb_f", 0);
            seekBassBoostFront.setValue((float) bbF);
            tvBassBoostFrontDb.setText(getString(R.string.lbl_db_fmt, bbF));
            int bfR = p.getInt(name + "_bf_r", 0);
            seekBassFilterRear.setValue((float) bfR);
            tvBassFilterRearVal.setText(getString(R.string.lbl_hz_fmt, BASS_FILTER_FREQS[bfR]));
            rearBassGainManualValue = p.getInt(name + "_bb_r", 0);
            seekBassBoostRear.setValue((float) rearBassGainManualValue);
            tvBassBoostRearDb.setText(getString(R.string.lbl_db_fmt, rearBassGainManualValue));

            // Replace .setSelection(int) with .setText(String, false)
            int frontIdx = p.getInt(name + "_bb_frq_f", 0);
            int rearIdx = p.getInt(name + "_bb_frq_r", 0);
            // Safety fallback - same hazard as subFreqIdx above (a stale/corrupt out-of-range
            // index on disk).
            if (frontIdx < 0 || frontIdx >= BASS_BOOST_FREQS.length) frontIdx = AudioConfig.LOUDNESS_BASS_SHELF_FREQ_IDX;
            if (rearIdx < 0 || rearIdx >= BASS_BOOST_FREQS.length) rearIdx = AudioConfig.LOUDNESS_BASS_SHELF_FREQ_IDX;

            // Use false to prevent the dropdown from popping up while loading
            spinnerBassFreqFront.setText(BASS_BOOST_FREQS[frontIdx], false);
            spinnerBassFreqRear.setText(BASS_BOOST_FREQS[rearIdx], false);
            rearBassFreqManualText = BASS_BOOST_FREQS[rearIdx];

            seekFaderLr.setValue((float) p.getInt(name + "_f_lr", 12));
            seekFaderFr.setValue((float) p.getInt(name + "_f_fr", 12));
            updateFaderLabels(); switchLoud.setChecked(p.getBoolean(name + "_loud", false));
            switchFmEnable.setChecked(p.getBoolean(name + "_fm_en", false));
            switchFatigueEnable.setChecked(p.getBoolean(name + "_fat_en", false));
            switchFmSubComp.setChecked(p.getBoolean(name + "_sub_comp", false));
            seekFmCalVol.setValue((float) p.getInt(name + "_fm_cal", 25));
            String calText = "" + getIntSlider(seekFmCalVol);
            tvFmCalVolVal.setText(calText);
            seekFmStrength.setValue((float) p.getInt(name + "_fm_str", 100));
            String strText = "" + getIntSlider(seekFmStrength);
            tvFmStrengthVal.setText(strText);
            int fatStartVol = p.getInt(name + "_fat_start_vol", 25);
            seekFatStartVol.setValue((float) fatStartVol);
            tvFatStartVolVal.setText(String.valueOf(fatStartVol));
            switchUltraBass.setChecked(p.getBoolean(name + "_ultra_bass_en", false));
            int ultraBassStartVol = p.getInt(name + "_ultra_bass_start_vol", 16);
            seekUltraBassStartVol.setValue((float) ultraBassStartVol);
            tvUltraBassStartVolVal.setText(String.valueOf(ultraBassStartVol));
            int ultraBassMaxDb = p.getInt(name + "_ultra_bass_max_db", 6);
            seekUltraBassMaxDb.setValue((float) ultraBassMaxDb);
            tvUltraBassMaxDbVal.setText(getString(R.string.lbl_db_fmt, ultraBassMaxDb));
            seekDelayFl.setValue((float) p.getInt(name + "_d_fl", 0));
            seekDelayFr.setValue((float) p.getInt(name + "_d_fr", 0));
            seekDelayRl.setValue((float) p.getInt(name + "_d_rl", 0));
            seekDelayRr.setValue((float) p.getInt(name + "_d_rr", 0));
            seekDelaySub.setValue((float) p.getInt(name + "_d_sub", 0));
            switchPreciseEnable.setChecked(p.getBoolean(name + "_d_en", false));
            seekDelay1Fl.setValue((float) p.getInt(name + "_d1_fl", 0));
            seekDelay1Fr.setValue((float) p.getInt(name + "_d1_fr", 0));
            seekDelay1Rl.setValue((float) p.getInt(name + "_d1_rl", 0));
            seekDelay1Rr.setValue((float) p.getInt(name + "_d1_rr", 0));
            seekDelay1RSSE.setValue((float) p.getInt(name + "_rsse_val", 10));
            switchLegacyEnable.setChecked(p.getBoolean(name + "_d1_en", false));
            
            // GALA - see galaNamespace()/savePreset() for why this reads from a shared bucket
            // instead of this preset's own keys when global mode is on.
            String galaNs = galaNamespace(name);
            switchGalaEnable.setChecked(p.getBoolean(galaNs + "_gala_enabled", false));
            seekGalaInc.setValue((float) p.getInt(galaNs + "_gala_increment", 15));
            tvGalaIncVal.setText(getString(R.string.speed_kmh_format, getIntSlider(seekGalaInc) + 5));
            seekGalaMinSpeed.setValue((float) p.getInt(galaNs + "_gala_min_speed", 0));
            tvGalaMinSpeedVal.setText(getString(R.string.speed_kmh_format, getIntSlider(seekGalaMinSpeed) * 5));
            seekGalaMaxAdj.setValue((float) p.getInt(galaNs + "_gala_max_adj", 12));
            tvGalaMaxAdjVal.setText(String.valueOf(getIntSlider(seekGalaMaxAdj)));
            seekGalaFadeMs.setValue((float) p.getInt(galaNs + "_gala_fade_ms", 100));    // Changed from 300ms
            tvGalaFadeMsVal.setText(getString(R.string.gala_ms_fmt, getIntSlider(seekGalaFadeMs)));
            seekGalaHoldMs.setValue((float) p.getInt(galaNs + "_gala_hold_ms", 1000));   // Changed from 3000ms
            tvGalaHoldMsVal.setText(String.format(Locale.getDefault(), getString(R.string.gala_s_fmt), getIntSlider(seekGalaHoldMs) / 1000f));
            switchGalaDisableForPreset.setChecked(p.getBoolean(name + "_gala_disabled_for_preset", false));
            switchGalaDisableForPreset.setEnabled(galaGlobalMode);

            // Power - if a preset was saved with positive amp power before the MCU firmware
            // stopped supporting it, clamp and re-persist it now rather than just hiding the
            // stale value in the display (see ampPositiveDisabled/setPowerVolume()), otherwise the
            // stepper's "+1" branch could let it creep back into positive territory later since it
            // only floors on the other direction.
            int powerVal = p.getInt(name + "_power_vol", 0);
            if (ampPositiveDisabled && powerVal < 0) {
                powerVal = 0;
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(name + "_power_vol", 0).apply();
            }
            tvPowerDb.setText(String.valueOf(-powerVal));
        }
        isUpdatingUi = false; updateVisualizer();
        updateFmVisualizer();

    }

    private void setupNavigation() {
        BottomNavigationView bn = findViewById(R.id.bottom_navigation);

        // 1. Reference all your layout containers
        final View eq = findViewById(R.id.layout_eq);
        final View fm = findViewById(R.id.layout_fm_curve);
        final View dly = findViewById(R.id.layout_delays);
        final View ftr = findViewById(R.id.layout_filters);
        final View gl = findViewById(R.id.layout_gala);

        // 2. Put them in an array for easy looping
        final View[] allLayouts = {eq, fm, dly, ftr, gl};
        final ViewGroup tabContainer = (ViewGroup) eq.getParent(); // shared parent of all tab layouts

        bn.setOnItemSelectedListener(it -> {
            int id = it.getItemId();
            View target = null;

            // Determine which layout to show
            if (id == R.id.nav_eq) target = eq;
            else if (id == R.id.nav_fm_curve) { target = fm; updateFmVisualizer(); }
            else if (id == R.id.nav_delays) target = dly;
            else if (id == R.id.nav_other) target = ftr;
            else if (id == R.id.nav_gala) target = gl;

            if (target != null) {
                // Only animate an actual tab change. Without this guard, SelectTab()
                // re-firing the same selection in onStart() (to force a redraw on resume)
                // would replay a visible crossfade every time the app comes to the
                // foreground, since the hide/show loop below still runs either way.
                if (target.getVisibility() != View.VISIBLE) {
                    TransitionManager.beginDelayedTransition(tabContainer, new Fade());
                }

                // 3. Hide EVERYTHING first
                for (View layout : allLayouts) {
                    layout.setVisibility(View.GONE);
                }

                // 4. Show the target
                target.setVisibility(View.VISIBLE);

                // 5. Force switches in this layout to snap (fixes the twitch)
                snapSwitches(target);
            }
            return true;
        });
    }

    private void snapSwitches(View root) {
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                snapSwitches(group.getChildAt(i));
            }
        } else if (root instanceof SwitchCompat) {
            // This is the magic line that stops the animation immediately
            root.jumpDrawablesToCurrentState();
        }
    }

    private void initReflection() {
        try {
            @SuppressLint("PrivateApi") Class<?> sp = Class.forName("android.os.SystemProperties");
            getPropMethod = sp.getMethod("get", String.class, String.class);
            Log.i(TAG, "Reflection initialized successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Critical Reflection Failure", e);
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

    /**
     * Reads persist.sys.qf.mcu.version and sets ampPositiveDisabled if its date is at or past
     * AMP_POSITIVE_GATE_VERSION's own date (inclusive - that constant is itself the first
     * firmware this applies to). Fails open (leaves the gate
     * off) if the property is missing or either version string doesn't parse, rather than
     * guessing. Call once, after initReflection(), before anything reads ampPositiveDisabled.
     */
    private void checkAmpPositiveGate() {
        String version = getSystemProperty("persist.sys.qf.mcu.version", "");
        int deviceDate = parseVersionDate(version);
        int gateDate = parseVersionDate(AMP_POSITIVE_GATE_VERSION);
        ampPositiveDisabled = deviceDate > 0 && gateDate > 0 && deviceDate >= gateDate;
        Log.i(TAG, "MCU version=" + version + " ampPositiveDisabled=" + ampPositiveDisabled);
    }

    /** Extracts the YYYYMMDD date segment from a "QF05.V02.14.20260703.002121"-style version string. */
    private static int parseVersionDate(String version) {
        try {
            String[] parts = version.split("\\.");
            if (parts.length < 4) return -1;
            return Integer.parseInt(parts[3]);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Resolves a color resource's night-mode variant regardless of the app's own current theme -
     * used by setButtonRgbCyan() so the physical backlight always matches the same cyan whether
     * the UI itself is currently rendering day or night mode, rather than following accentColor
     * (which does follow the current theme, correctly, for actual UI elements - see its own
     * usages). Reads it via a Configuration override instead of hardcoding the night hex value,
     * so values-night/colors.xml stays the single source of truth for it.
     */
    private int getNightModeColor(int colorRes) {
        Configuration nightConfig = new Configuration(getResources().getConfiguration());
        nightConfig.uiMode = (nightConfig.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        return ContextCompat.getColor(createConfigurationContext(nightConfig), colorRes);
    }

    /**
     * Sets the head unit's button/backlight RGB to wDSP's night-mode accent color (always the
     * night variant of R.color.cyan_custom - see getNightModeColor()'s doc - regardless of which
     * theme the app's own UI is currently in), scaled to the same perceptual luminance (standard
     * 0.299R+0.587G+0.114B luma weights, not just the raw peak channel) as whatever the user
     * currently has set via persist.sys.color.light.value (a packed ARGB int; see
     * C:\Users\v\Downloads\QF_RGB_control.md for the full protocol writeup this mirrors). If
     * that property can't be read or doesn't parse, this leaves the backlight alone entirely -
     * no guessed fallback color - rather than touching something it has no real reading for.
     * Re-captures that prop's raw RGB into defaultBacklightR/G/B every successful call, so
     * onStop() can restore whatever the head unit actually had, freshly read each time the
     * activity opens or resumes - not a stale value from first launch (if a call's read fails,
     * the previous successful capture - if any - is left in place rather than cleared). Static
     * mode (no cycling). Best-effort: silently no-ops on any failure (non-QF ROM, no root/
     * signature permission, service not present, etc.) rather than crashing - same defensive
     * style as the rest of this app's MCU reflection (see McuService.ensureMcuManager()), since
     * this is
     * purely cosmetic.
     */
    private void setButtonRgbCyan() {
        String raw = getSystemProperty("persist.sys.color.light.value", "");
        if (raw.isEmpty()) return; // can't read the current color - leave the backlight alone

        int r, g, b;
        try {
            int packed = Integer.parseInt(raw);
            r = (packed >> 16) & 0xFF;
            g = (packed >> 8) & 0xFF;
            b = packed & 0xFF;
        } catch (NumberFormatException e) {
            return; // malformed property - same as unreadable, leave the backlight alone
        }

        defaultBacklightR = (byte) r;
        defaultBacklightG = (byte) g;
        defaultBacklightB = (byte) b;
        backlightDefaultCaptured = true;

        int nightAccent = getNightModeColor(R.color.cyan_custom);
        int accentR = Color.red(nightAccent);
        int accentG = Color.green(nightAccent);
        int accentB = Color.blue(nightAccent);
        // Standard luma weights - matches perceived brightness far better than the raw peak
        // channel would, since nightAccent's hue (teal/cyan) differs from whatever hue the
        // system's own light color happens to be.
        float systemLuminance = 0.299f * r + 0.587f * g + 0.114f * b;
        float accentLuminance = 0.299f * accentR + 0.587f * accentG + 0.114f * accentB;
        float scale = accentLuminance > 0f ? systemLuminance / accentLuminance : 0f;
        byte payloadR = (byte) Math.round(Math.max(0, Math.min(255, accentR * scale)));
        byte payloadG = (byte) Math.round(Math.max(0, Math.min(255, accentG * scale)));
        byte payloadB = (byte) Math.round(Math.max(0, Math.min(255, accentB * scale)));

        sendButtonRgb(payloadR, payloadG, payloadB);
        Log.i(TAG, "Button RGB set to accent color at luminance " + systemLuminance);
    }

    /**
     * [0x09, R, G, B, mode] - sub-command 0x09 = button/backlight RGB, mode 0 = static color.
     * Best-effort, see setButtonRgbCyan()'s javadoc.
     */
    private void sendButtonRgb(byte r, byte g, byte b) {
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getMethod("getService", String.class);
            Object binder = getServiceMethod.invoke(null, "mcu_service");
            if (binder == null) return;

            Class<?> iBinderClass = Class.forName("android.os.IBinder");
            Class<?> stubClass = Class.forName("android.qf.mcu.IMcuManager$Stub");
            Method asInterface = stubClass.getMethod("asInterface", iBinderClass);
            Object mcuManager = asInterface.invoke(null, binder);
            if (mcuManager == null) return;

            Method rpc = mcuManager.getClass().getMethod("RPC_SendMcuMsgData", byte.class, byte[].class, int.class);
            byte[] payload = {0x09, r, g, b, 0};
            rpc.invoke(mcuManager, (byte) 24, payload, payload.length);
        } catch (Exception e) {
            Log.w(TAG, "sendButtonRgb failed (non-fatal): " + e);
        }
    }

    /** Restores the head unit's own backlight, captured by the last setButtonRgbCyan() call. */
    private void restoreDefaultBacklight() {
        if (!backlightDefaultCaptured) return;
        sendButtonRgb(defaultBacklightR, defaultBacklightG, defaultBacklightB);
    }

    private void showAutoPresetDialog() {
        String ass = getSystemProperty("sys.qf.last_audio_src", "Unknown");
        if (VolumeHelper.getActivePlayerType().equals("btcall_type")) {
            ass = "Call";
        }
        else if ("nothing".equalsIgnoreCase(ass) || "Unknown".equalsIgnoreCase(ass)) {
            ass = "Default";
        }
        String p = ass;
        String cur = spinnerPresets.getText().toString();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Map<String, String> map = new Gson().fromJson(prefs.getString(PREF_PLAYER_MAP, "{}"), new TypeToken<Map<String, String>>(){}.getType());
        // String def = prefs.getString(PREF_DEFAULT_PRESET, getString(R.string.none));

        StringBuilder sb = new StringBuilder(getString(R.string.current_associations));
        for (Map.Entry<String, String> entry : map.entrySet()) sb.append("- ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append("\n");
//        sb.append(getString(R.string.global_default_fmt, def));

        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.automation_title_fmt, cur))
                .setMessage(getString(R.string.active_player_fmt, p) + "\n\n" + sb)
                .setPositiveButton(R.string.btn_assign, (d, w) -> {
                    map.put(p, cur);
                    prefs.edit().putString(PREF_PLAYER_MAP, new Gson().toJson(map)).apply();
                })
                .setNeutralButton(R.string.btn_set_default, (d, w) -> {
                        map.put("Default", cur);
                        prefs.edit().putString(PREF_PLAYER_MAP, new Gson().toJson(map)).apply();
                })
                .setNegativeButton(R.string.btn_unassign, (d, w) -> {
                    if (map.containsKey(p)) {
                        map.remove(p);
                        prefs.edit().putString(PREF_PLAYER_MAP, new Gson().toJson(map)).apply();
                    }
                })
                .show();
    }

    // galaGlobalMode's current "preset name" for every GALA field - GALA_GLOBAL_NAMESPACE
    // when global mode is on, or the real preset otherwise. Letting load/save just prefix
    // keys with this instead of branching per field is what keeps the GALA sections of
    // loadPreset()/savePreset() a single pass instead of six hand-written if/else pairs.
    private String galaNamespace(String presetName) {
        return galaGlobalMode ? GALA_GLOBAL_NAMESPACE : presetName;
    }

    // Copies every GALA field (enable + all 5 sliders) from one preset's keys to another's,
    // treating GALA_GLOBAL_NAMESPACE as just another "preset name" - used by switchGalaGlobal's
    // listener below to seed/crystallize the shared bucket. Same copy-by-suffix idiom as
    // copyPresetData() uses for the rest of a preset's fields.
    private void copyGalaFields(SharedPreferences.Editor e, String fromNamespace, String toNamespace) {
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        for (String suffix : GALA_FIELD_SUFFIXES) {
            Object v = p.getAll().get(fromNamespace + suffix);
            if (v instanceof Boolean) e.putBoolean(toNamespace + suffix, (Boolean) v);
            else if (v instanceof Integer) e.putInt(toNamespace + suffix, (Integer) v);
        }
    }

    private void setupGalaControls() {
        switchGalaEnable.jumpDrawablesToCurrentState();
        switchGalaEnable.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { autoSaveCurrent(); } });

        // Per-preset override, always independent of galaNamespace() - lets a preset opt out
        // of GALA even while global mode is on for every other preset. Only meaningful while
        // global mode is on (otherwise this preset's own switchGalaEnable already does the
        // same job), so it's greyed out the rest of the time - see the setEnabled() calls below
        // and in loadPreset().
        switchGalaDisableForPreset.jumpDrawablesToCurrentState();
        switchGalaDisableForPreset.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { autoSaveCurrent(); } });

        // Global GALA: not tied to any preset, so it's loaded/wired once here rather than
        // in loadPreset(). When on, every GALA field reads/writes GALA_GLOBAL_NAMESPACE
        // instead of this preset's own name - see galaNamespace() and the GALA sections of
        // savePreset()/loadPreset().
        switchGalaGlobal.jumpDrawablesToCurrentState();
        SharedPreferences galaPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        galaGlobalMode = galaPrefs.getBoolean(PREF_GALA_GLOBAL_MODE, false);
        switchGalaGlobal.setChecked(galaGlobalMode);
        switchGalaDisableForPreset.setEnabled(galaGlobalMode);
        switchGalaGlobal.setOnCheckedChangeListener((bv, checked) -> {
            if (isUpdatingUi) return;
            String currentPreset = spinnerPresets.getText().toString();
            SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
            if (checked) {
                // Seed the global bucket from this preset's own values (every GALA slider
                // already autosaves on change, so these are exactly what's on screen), so
                // flipping this on doesn't silently reset GALA to off.
                copyGalaFields(ed, currentPreset, GALA_GLOBAL_NAMESPACE);
            } else {
                // Turning global mode off: the global bucket's values become this preset's
                // own, instead of discarding whatever was just in use globally.
                copyGalaFields(ed, GALA_GLOBAL_NAMESPACE, currentPreset);
            }
            ed.putBoolean(PREF_GALA_GLOBAL_MODE, checked);
            ed.apply();
            galaGlobalMode = checked;
            switchGalaDisableForPreset.setEnabled(checked);
        });

        Slider.OnChangeListener galal = (slider, value, fromUser) -> {
            int p = (int) value;
            if (slider == seekGalaInc) tvGalaIncVal.setText(getString(R.string.speed_kmh_format,p + 5));
            else if (slider == seekGalaMinSpeed) tvGalaMinSpeedVal.setText(getString(R.string.speed_kmh_format,p * 5));
            else if (slider == seekGalaMaxAdj) tvGalaMaxAdjVal.setText(String.valueOf(p));
            else if (slider == seekGalaFadeMs) tvGalaFadeMsVal.setText(getString(R.string.gala_ms_fmt, p));
            else if (slider == seekGalaHoldMs) tvGalaHoldMsVal.setText(String.format(Locale.getDefault(), getString(R.string.gala_s_fmt), p / 1000f));
            else if (slider == seekSimulateSpeed) {
                if (p == 0) {
                    tvSimulateSpeedVal.setText(getString(R.string.value_default));
                }
                else {
                    tvSimulateSpeedVal.setText(getString(R.string.speed_kmh_format, p));
                }
                if (fromUser) {
                    Intent intent = new Intent("com.radiorubka.wdsp.SIMULATE_SPEED");
                    intent.setPackage(getPackageName());
                    intent.putExtra("speed", (float) p);
                    sendBroadcast(intent);
                }
            }
            if (fromUser && !isUpdatingUi) { autoSaveCurrent(); }
        };
        seekGalaInc.addOnChangeListener(galal);
        seekGalaMinSpeed.addOnChangeListener(galal);
        seekGalaMaxAdj.addOnChangeListener(galal);
        seekGalaFadeMs.addOnChangeListener(galal);
        seekGalaHoldMs.addOnChangeListener(galal);
        seekSimulateSpeed.addOnChangeListener(galal);
    }

    private void savePresetList() { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putStringSet(PREF_PRESET_NAMES, new HashSet<>(presetNames)).apply(); }
    private void autoSaveCurrent() {
        String n = spinnerPresets.getText().toString();
        savePreset(n);
    }
    private int getSystemVolume() { return VolumeHelper.getVolume(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (spectrumAnalyzer != null) spectrumAnalyzer.stop();
        try {
            unregisterReceiver(serviceReceiver);
        }
        catch (Exception e) {
            Log.e(TAG, "Failed to unregister receiver. It may have already been unregistered.", e);
        }
    }
    private void exportPresets() { String s = spinnerPresets.getText().toString();
        exportLauncher.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, (s) + ".json")); }
    private void importPresets() {
        importLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")); }
    private void saveCurrentPresetToFile(Uri u) {
        try (OutputStream os = getContentResolver().openOutputStream(u)) {
            if (os == null) return;

            // 1. Get the name of the currently selected preset
            String currentPreset = spinnerPresets.getText().toString();

            // Get the preferences into prefs
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

            // Save all the prefs entries to a map where string is the name of the pref and ? is a wildcard for all data types.
            Map<String, ?> allEntries = prefs.getAll();

            // Creating a placeholder map for filtered data
            Map<String, Object> filteredData = new HashMap<>();

            // 2. Add metadata so the importer knows this is a single preset
            filteredData.put("is_single_preset", true);
            filteredData.put("preset_name_label", currentPreset);

            // 3. Only grab keys that start with the current preset's name
            // (e.g., "Music_g0", "Music_sub_g", etc.)
            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                if (entry.getKey().startsWith(currentPreset + "_")) {
                    filteredData.put(entry.getKey(), entry.getValue());
                }
            }

            // 4. Save only this filtered map to the file
            os.write(new Gson().toJson(filteredData).getBytes());
            Toaster.show(this, getString(R.string.toast_exported));
        }
        catch (IOException e) {
            Log.e(TAG, "Export error", e);
            Toaster.show(this, "ERROR");
        }
    }
    private void loadPresetFromFile(Uri u) {
        try (InputStream is = getContentResolver().openInputStream(u);
             BufferedReader r = new BufferedReader(new InputStreamReader(is))) {

            // 1. Read the file into a String
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);

            // 2. Parse JSON into a Map
            Map<String, Object> importedMap = new Gson().fromJson(sb.toString(), new TypeToken<Map<String, Object>>() {}.getType());

            // 3. Get the Preset Name from metadata
            String newPresetName = (String) importedMap.get("preset_name_label");
            if (newPresetName == null) newPresetName = "Imported_" + System.currentTimeMillis() / 1000;

            // 4. Prepare to save (NOTICE: No .clear() here!)
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            // 5. Import the settings keys
            for (Map.Entry<String, Object> entry : importedMap.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Skip metadata keys
                if (key.equals("is_single_preset") || key.equals("preset_name_label")) continue;

                // Save the value based on its type
                if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof Double) {
                    // JSON numbers are Doubles; convert to Int or Float
                    double d = (Double) value;
                    if (d == Math.rint(d)) editor.putInt(key, (int) d);
                    else editor.putFloat(key, (float) d);
                } else if (value instanceof String) {
                    editor.putString(key, (String) value);
                }
            }

            // 6. Update the "preset_names" list so the UI shows the new preset
            if (!presetNames.contains(newPresetName)) {
                presetNames.add(newPresetName);
                Collections.sort(presetNames);
                editor.putStringSet(PREF_PRESET_NAMES, new HashSet<>(presetNames));
            }

            // 7. Save and Refresh
            editor.apply();
            setupPresets();           // Reloads the spinner list
            ensureCallPresetExists(); // Safety check

            // 8. Auto-select the newly imported preset
            //int newIndex = presetNames.indexOf(newPresetName);
            spinnerPresets.setText(newPresetName, false);
            loadPreset(newPresetName);

            Toaster.show(this, getString(R.string.toast_imported) + ": " + newPresetName);

        } catch (Exception e) {
            Log.e(TAG, "Import error", e);
            Toaster.show(this, "Import failed: " + e.getMessage());
        }
    }

    private void updateFaderLabels() {
        // Center labels stay blank; only the arrow on the side the fader has
        // moved toward shows its step count, the opposite arrow's label clears.
        int lr = getIntSlider(seekFaderLr);
        tvFaderLrLeftVal.setText(lr < 12 ? String.valueOf(12 - lr) : "");
        tvFaderLrRightVal.setText(lr > 12 ? String.valueOf(lr - 12) : "");
        int fr = getIntSlider(seekFaderFr);
        tvFaderFrFrontVal.setText(fr > 12 ? String.valueOf(fr - 12) : "");
        tvFaderFrRearVal.setText(fr < 12 ? String.valueOf(12 - fr) : "");
        if (balancePointer != null) balancePointer.setBalance((lr - 12) / 12f, (fr - 12) / 12f);
    }

    private void resetUiInternal() {
        isUpdatingUi = true; for (Slider s : gainSliders) s.setValue(6f); for (int i = 0; i<AudioConfig.NUM_BANDS; i++) updateDbLabel(i, 6);
        if (isFullyInitialized) {
            for (ToggleButton t : qSwitches) t.setChecked(false); seekSubGain.setValue(0); spinnerSubFreq.setText(SUB_FREQS[5], false); Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS[5]);
            seekFaderLr.setValue(12); seekFaderFr.setValue(12); updateFaderLabels(); switchLoud.setChecked(false);
            switchFmEnable.setChecked(false); switchFatigueEnable.setChecked(false); switchFmSubComp.setChecked(false);
            seekFmCalVol.setValue(25); seekFmStrength.setValue(100);
            seekFatStartVol.setValue(25); tvFatStartVolVal.setText(String.valueOf(25));
            switchUltraBass.setChecked(false);
            seekUltraBassStartVol.setValue(16); tvUltraBassStartVolVal.setText(String.valueOf(16));
            seekUltraBassMaxDb.setValue(6); tvUltraBassMaxDbVal.setText(getString(R.string.lbl_db_fmt, 6));

            // GALA reset
            switchGalaEnable.setChecked(false);
            switchGalaDisableForPreset.setChecked(false);
            seekGalaInc.setValue(15);
            seekGalaMinSpeed.setValue(0);
//            seekGalaMaxSpeed.setProgress(30);
            seekGalaMaxAdj.setValue(12);
            seekGalaFadeMs.setValue(100);
            tvGalaFadeMsVal.setText(getString(R.string.gala_ms_fmt, 100));
            seekGalaHoldMs.setValue(1000);
            tvGalaHoldMsVal.setText(String.format(Locale.getDefault(), getString(R.string.gala_s_fmt), 1.0f));
        }
        isUpdatingUi = false; updateVisualizer(); updateFmVisualizer();
    }
}
