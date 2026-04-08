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
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
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
import android.widget.Toast;
import android.widget.ToggleButton;

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

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<Slider> gainSliders = new ArrayList<>();
    private final List<ToggleButton> qSwitches = new ArrayList<>();
    private final List<TextView> dbLabels = new ArrayList<>();
    private AutoCompleteTextView spinnerPresets;
    private EqVisualizerView eqVisualizer;
    
    private Slider seekSubGain;
    private AutoCompleteTextView spinnerSubFreq;
    private TextView tvSubDb;

    private TextView tvPowerDb;
    private final String[] SUB_FREQS = {"25", "32", "40", "50", "63", "80", "100", "125", "160", "200", "250"};

    // Filter controls
    private Slider seekBassFilterFront, seekBassBoostFront, seekBassFilterRear, seekBassBoostRear;
    private TextView tvBassFilterFrontVal, tvBassBoostFrontDb, tvBassFilterRearVal, tvBassBoostRearDb;
    private AutoCompleteTextView spinnerBassFreqFront, spinnerBassFreqRear;
    private final String[] BASS_FILTER_FREQS = {"20", "25", "31", "40", "50", "63", "80", "100", "125", "160", "200", "250"};
    private final String[] BASS_BOOST_FREQS = {"off", "54", "68", "86", "108", "134", "172", "214"};

    // Fader & Delays
    private Slider seekFaderLr;
    private Slider seekFaderFr;
    private TextView tvFaderLrVal, tvFaderFrVal;
    private SwitchCompat switchLoud;
    private Slider seekDelayFl, seekDelayFr, seekDelayRl, seekDelayRr, seekDelaySub;
    private Slider seekDelay1Fl, seekDelay1Fr, seekDelay1Rl, seekDelay1Rr, seekDelay1RSSE;
    private SwitchCompat switchPreciseEnable, switchLegacyEnable;
    private TextView tvDelayFlVal, tvDelayFrVal, tvDelayRlVal, tvDelayRrVal, tvDelaySubVal;
    private TextView tvDelay1FlVal, tvDelay1FrVal, tvDelay1RlVal, tvDelay1RrVal, tvDelay1RSSEVal;

    // F-M Curve
    private SwitchCompat switchFmEnable, switchFatigueEnable, switchFmSubComp;
    private Slider seekFmCalVol, seekFmStrength;
    private TextView tvFmCalVolVal, tvFmStrengthVal, tvSysVolumeVal, tvSubOffsetVal, tvSubOffsetWarn;
    private EqVisualizerView fmVisualizer;
    
    // GALA Controls
    private SwitchCompat switchGalaEnable;
    private Slider seekGalaInc, seekGalaMinSpeed, seekSimulateSpeed, seekGalaMaxAdj;
    private TextView tvGalaIncVal, tvGalaSpeed, tvGalaMinSpeedVal, tvGalaOffset, tvSimulateSpeedVal, tvGalaMaxAdjVal;

    private float currentFmSubOffset = 0f;
    private int currentEffectiveVolume = -1;

    private ArrayAdapter<String> presetAdapter;
    private List<String> presetNames;
    private int accentColor;
    private boolean isUpdatingUi = false;
    private boolean isFullyInitialized = false;

    private Method getPropMethod;
    public static class Globals {
        public static int currentSubFreqHz = 0;
    }

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
            } else if ("com.radiorubka.wdsp.VOLUME_CHANGED".equals(action)) {
                currentEffectiveVolume = intent.getIntExtra("volume", -1);
                if (isFullyInitialized && findViewById(R.id.layout_fm_curve).getVisibility() == View.VISIBLE) {
                    updateFmVisualizer();
                }
            } else if ("com.radiorubka.wdsp.GALA_UPDATE".equals(action)) {
                float speed = intent.getFloatExtra("speed", 0.0f);
                int offset = intent.getIntExtra("waveOffset", 0);
                if (tvGalaSpeed != null) tvGalaSpeed.setText(String.format(Locale.getDefault(), "%.1f km/h", speed));
                if (tvGalaOffset != null) tvGalaOffset.setText(String.format(Locale.getDefault(), "+%d", offset));
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
        
        // 1. Instant UI: Minimal views needed for the first screen
        initPrimaryViews();

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
            VolumeHelper.init(MainActivity.this);
            startMcuService();
        }).start();

        // 3. Delayed UI Initialization: EQ Bands are heavy
        handler.post(() -> {
            setupEqBands(); // Programmatic creation of 16 bands
            setupPresets(); // Load current data
        });
        
        // 4. Lazy Logic: Everything else can wait a few ms
        handler.postDelayed(() -> {
            setupLogic();
            registerServiceReceiver();
            isFullyInitialized = true;
            sendUiSignal(true);
            refreshAllUiValues();
            requestBatteryOptimization();
            initReflection();
            ensureCallPresetExists();
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
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), 102);
            return;
        }

        startForegroundService(new Intent(this, McuService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        sendBroadcast(new Intent("com.radiorubka.wdsp.UI_ACTIVE").setPackage(getPackageName()));
        if (isFullyInitialized) {
            refreshAllUiValues();
            SelectTab();
        }
        // Force the UI to match the saved preference
        if (isFullyInitialized && presetNames != null) {
            refreshAllUiValues();
            SelectTab();

            // Only run this if presetNames is actually ready
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            String current = prefs.getString("last_selected_preset", "Call");
            int index = presetNames.indexOf(current);
            if (index >= 0 && index < presetNames.size()) {
                String newName = presetNames.get(index);
                spinnerPresets.setText(newName, false);
                loadPreset(newName);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sendBroadcast(new Intent("com.radiorubka.wdsp.UI_INACTIVE").setPackage(getPackageName()));
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
        tvFaderLrVal = findViewById(R.id.tv_fader_lr_val);
        tvFaderFrVal = findViewById(R.id.tv_fader_fr_val);
        switchLoud = findViewById(R.id.switch_loud);
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
        seekFmCalVol = findViewById(R.id.seek_fm_cal_vol);
        tvFmCalVolVal = findViewById(R.id.tv_fm_cal_vol_val);
        seekFmStrength = findViewById(R.id.seek_fm_strength);
        tvFmStrengthVal = findViewById(R.id.tv_fm_strength_val);
        fmVisualizer = findViewById(R.id.fm_visualizer);
        tvSysVolumeVal = findViewById(R.id.tv_sys_volume_val);
        tvSubOffsetVal = findViewById(R.id.tv_sub_offset_val);
        tvSubOffsetWarn = findViewById(R.id.tv_sub_offset_warn);
        
        // GALA
        switchGalaEnable = findViewById(R.id.switch_gala_enable);
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
    }

    private void sendUiSignal(boolean active) {
        Intent intent = new Intent(active ? "com.radiorubka.wdsp.UI_ACTIVE" : "com.radiorubka.wdsp.UI_INACTIVE");
        intent.setPackage(getPackageName());
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        sendBroadcast(intent);
    }
    private void setupEqBands() {
        LinearLayout container = findViewById(R.id.eq_container);
        container.removeAllViews();
        gainSliders.clear();
        qSwitches.clear();
        dbLabels.clear();
        int cQ = ContextCompat.getColor(this, R.color.q_switch_text);
        int cL = ContextCompat.getColor(this, R.color.band_label);
        float smallTextSize = getResources().getDimension(R.dimen.text_size_small);

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            final int idx = i;
            ToggleButton q = new ToggleButton(this);
            TextView db = new TextView(this);
            Slider s = new Slider(this, null);
            gainSliders.add(s); qSwitches.add(q); dbLabels.add(db);

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL); layout.setGravity(Gravity.CENTER_HORIZONTAL);
            layout.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));

            q.setTextOn(getString(R.string.q_high)); q.setTextOff(getString(R.string.q_low)); q.setChecked(false);
            q.setTextColor(cQ); q.setBackgroundColor(Color.TRANSPARENT);
            q.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize); 
            q.setPadding(0, 0, 0, 0); q.setMinimumHeight(0); q.setMinimumWidth(0);
            q.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.08f));
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
            label.setText(AudioConfig.BAND_LABELS[i]); label.setTextColor(cL); 
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
            s.setTrackActiveTintList(ColorStateList.valueOf(accentColor));
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

            layout.addView(q); layout.addView(db); layout.addView(label);
            seekBox.addView(s); layout.addView(seekBox);
            container.addView(layout); updateDbLabel(i, 6);
        }
    }

    private void updateVisualizer() {
        if (eqVisualizer == null) return;
        int[] gs = new int[AudioConfig.NUM_BANDS];
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) gs[i] = getIntSlider(gainSliders.get(i));
        eqVisualizer.setGains(gs);
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
            currentVal = Math.max(-3, currentVal - 1); // Example range -15 to +15
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
            // Logic for Sub Comp limit (if FM Sub Comp is on, limit to 80Hz/Index 5)
            if (isFullyInitialized && switchFmSubComp.isChecked() && pos > 5) {
                // Revert the text back to 80Hz (Index 5)
                spinnerSubFreq.setText(SUB_FREQS[5], false);
                Toaster.show(MainActivity.this, getString(R.string.toast_sub_comp_limit));

                // Re-sync Global just in case
                Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS[5]);
                return;
            }

            if (!isUpdatingUi) {
                autoSaveCurrent();
            }

            // Update the global value for other calculations
            String freqString = SUB_FREQS[pos];
            Globals.currentSubFreqHz = Integer.parseInt(freqString);
        });

        // 4. Seek Gain logic remains largely the same
        seekSubGain.addOnChangeListener((slider, value, fromUser) -> {
            int p = (int) value;
            String text = "+" + p;
            tvSubDb.setText(text);
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

        // Replaced the old OnItemSelectedListener with OnItemClickListener
        AdapterView.OnItemClickListener itemClickListener = (parent, view, pos, id) -> {
            if (!isUpdatingUi) {
                autoSaveCurrent();
                // updateBassMcu(); // Uncomment if you use this
            }
        };

        spinnerBassFreqFront.setOnItemClickListener(itemClickListener);
        spinnerBassFreqRear.setOnItemClickListener(itemClickListener);

        Slider.OnChangeListener bl = (slider, value, fromUser) -> {
            int p = (int) value;
            if (slider == seekBassFilterFront) tvBassFilterFrontVal.setText(getString(R.string.lbl_hz_fmt, BASS_FILTER_FREQS[p]));
            else if (slider == seekBassBoostFront) tvBassBoostFrontDb.setText(getString(R.string.lbl_db_fmt, p));
            else if (slider == seekBassFilterRear) tvBassFilterRearVal.setText(getString(R.string.lbl_hz_fmt, BASS_FILTER_FREQS[p]));
            else if (slider == seekBassBoostRear) tvBassBoostRearDb.setText(getString(R.string.lbl_db_fmt, p));
            if (fromUser && !isUpdatingUi) {
                autoSaveCurrent();
//                    updateBassMcu();
            }
        };
        seekBassFilterFront.addOnChangeListener(bl); seekBassBoostFront.addOnChangeListener(bl);
        seekBassFilterRear.addOnChangeListener(bl); seekBassBoostRear.addOnChangeListener(bl);

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
            if (fromUser && !isUpdatingUi) autoSaveCurrent();
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
            if (fromUser && !isUpdatingUi) autoSaveCurrent();
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
                if (checked && java.util.Arrays.asList(SUB_FREQS).indexOf(spinnerSubFreq.getText().toString()) > 5) spinnerSubFreq.setText(SUB_FREQS[5], false);
                autoSaveCurrent();
                updateFmVisualizer();
            }
        });
        Slider.OnChangeListener fml = (slider, value, fromUser) -> {
            int p = (int) value;
            if (slider == seekFmCalVol) tvFmCalVolVal.setText(String.valueOf(p)); else tvFmStrengthVal.setText(String.valueOf(p));
            if (fromUser && !isUpdatingUi) {
                updateFmVisualizer();
                autoSaveCurrent();
            }
        };
        seekFmCalVol.addOnChangeListener(fml); seekFmStrength.addOnChangeListener(fml);
    }

    private void updateFmVisualizer() {
        if (fmVisualizer == null) return;
        float[] offs = calculateFmOffsets();
        int[] gs = new int[AudioConfig.NUM_BANDS]; float[] actual = new float[AudioConfig.NUM_BANDS]; float[] warns = new float[AudioConfig.NUM_BANDS];
        int vol = (currentEffectiveVolume != -1) ? currentEffectiveVolume : getSystemVolume(); 
        tvSysVolumeVal.setText(String.valueOf(vol));
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float total = offs[i];
            actual[i] = total;
            float pot = getIntSlider(gainSliders.get(i)) + (total / 2f);
            float wOffset = (pot - 12f) * 2; warns[i] = (pot > 12.025f && wOffset > 0.999f) ? wOffset : 0f;
            gs[i] = Math.round(Math.max(0, Math.min(12, 6f + (total / 2f))));
        }
        fmVisualizer.setGains(gs); fmVisualizer.setOffsets(actual); fmVisualizer.setWarnings(warns);
        if (switchFmSubComp.isChecked()) {
            tvSubOffsetVal.setText(String.format(Locale.getDefault(), getString(R.string.lbl_db_fmt2), currentFmSubOffset));
            float subPot = currentFmSubOffset + seekSubGain.getValue();
            tvSubOffsetWarn.setText(subPot > 12.25f ? String.format(Locale.getDefault(), getString(R.string.lbl_db_fmt2), subPot - 12f) : "OK");
        } else { tvSubOffsetVal.setText(getString(R.string.none)); tvSubOffsetWarn.setText(getString(R.string.none)); }
        fmVisualizer.invalidate();
    }

    private float[] calculateFmOffsets() {
        float[] offs = new float[AudioConfig.NUM_BANDS]; currentFmSubOffset = 0f;
        int vol = Math.max(1, (currentEffectiveVolume != -1) ? currentEffectiveVolume : getSystemVolume());
        int cal = getIntSlider(seekFmCalVol); float str = getIntSlider(seekFmStrength) / 100f;
        if (vol < cal && switchFmEnable.isChecked()) {
            float ratio = (float)(cal - vol) / (float)(cal - 1);
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) offs[i] = AudioConfig.ISO_MAX_OFFSETS[i] * ratio * str;
            if (switchFmSubComp.isChecked()) {
                int currentSubFreq = Globals.currentSubFreqHz;
                if (currentSubFreq == 80) currentFmSubOffset = AudioConfig.ISO_MAX_OFFSETS[3] * ratio * str;
                else if (currentSubFreq == 63 || currentSubFreq == 50) currentFmSubOffset = AudioConfig.ISO_MAX_OFFSETS[2] * ratio * str;
                else if (currentSubFreq == 40 || currentSubFreq == 32) currentFmSubOffset = AudioConfig.ISO_MAX_OFFSETS[1] * ratio * str;
                else if (currentSubFreq == 25) currentFmSubOffset = AudioConfig.ISO_MAX_OFFSETS[0] * ratio * str;
            }
        } else if (vol > cal && switchFatigueEnable.isChecked()) {
            float ratio = (float)(vol - cal) / ((32 - cal) > 0 ? (float)(32 - cal) : 1f);
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) offs[i] = AudioConfig.FATIGUE_MAX_OFFSETS[i] * ratio * str;
        }
        return offs;
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

        String defaultPreset = getString(R.string.default_preset_name);
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
            presetAdapter.notifyDataSetChanged();
        }
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
        String[] keys = {"_sub_g", "_sub_f", "_bf_f", "_bb_f", "_bf_r", "_bb_r", "_bb_frq_f", "_bb_frq_r", "_f_lr", "_f_fr", "_loud", "_fm_en", "_fat_en", "_sub_comp", "_fm_cal", "_fm_str", "_d_fl", "_d_fr", "_d_rl", "_d_rr", "_d_sub", "_d_en", "_d1_fl", "_d1_fr", "_d1_rl", "_d1_rr", "_rsse_val", "_d1_en", "_gala_enabled", "_gala_increment", "_gala_min_speed", "_gala_max_speed", "_gala_max_adj", "_power_vol"};
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
        if ("Call".equals(curr)) return;
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
            e.putInt(name + "_bb_r", getIntSlider(seekBassBoostRear));

            int frontFreqIdx = java.util.Arrays.asList(BASS_BOOST_FREQS).indexOf(spinnerBassFreqFront.getText().toString());
            int rearFreqIdx = java.util.Arrays.asList(BASS_BOOST_FREQS).indexOf(spinnerBassFreqRear.getText().toString());
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
            
            // GALA
            e.putBoolean(name + "_gala_enabled", switchGalaEnable.isChecked());
            e.putInt(name + "_gala_increment", getIntSlider(seekGalaInc));
            e.putInt(name + "_gala_min_speed", getIntSlider(seekGalaMinSpeed));
//            e.putInt(name + "_gala_max_speed", seekGalaMaxSpeed.getProgress());
            e.putInt(name + "_gala_max_adj", getIntSlider(seekGalaMaxAdj));

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
        if (isFullyInitialized) {
            seekBassFilterFront.setValue((float) p.getInt(name + "_bf_f", 0));
            seekBassBoostFront.setValue((float) p.getInt(name + "_bb_f", 0));
            seekBassFilterRear.setValue((float) p.getInt(name + "_bf_r", 0));
            seekBassBoostRear.setValue((float) p.getInt(name + "_bb_r", 0));

            // Replace .setSelection(int) with .setText(String, false)
            int frontIdx = p.getInt(name + "_bb_frq_f", 0);
            int rearIdx = p.getInt(name + "_bb_frq_r", 0);

            // Use false to prevent the dropdown from popping up while loading
            spinnerBassFreqFront.setText(BASS_BOOST_FREQS[frontIdx], false);
            spinnerBassFreqRear.setText(BASS_BOOST_FREQS[rearIdx], false);

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
            
            // GALA
            switchGalaEnable.setChecked(p.getBoolean(name + "_gala_enabled", false));
            seekGalaInc.setValue((float) p.getInt(name + "_gala_increment", 15));
            tvGalaIncVal.setText(getString(R.string.speed_kmh_format, getIntSlider(seekGalaInc) + 5));
            seekGalaMinSpeed.setValue((float) p.getInt(name + "_gala_min_speed", 0));
            tvGalaMinSpeedVal.setText(getString(R.string.speed_kmh_format, getIntSlider(seekGalaMinSpeed) * 5));
            seekGalaMaxAdj.setValue((float) p.getInt(name + "_gala_max_adj", 12));
            tvGalaMaxAdjVal.setText(String.valueOf(getIntSlider(seekGalaMaxAdj)));

            // Power
            tvPowerDb.setText(String.valueOf(-p.getInt(name + "_power_vol", 0)));
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

    private String getSystemProperty() {
        try {
            if (getPropMethod != null) {
                return (String) getPropMethod.invoke(null, "sys.qf.last_audio_src", "Unknown");
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private void showAutoPresetDialog() {
        String ass = getSystemProperty();
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
//                .setNeutralButton(R.string.btn_set_default, (d, w) ->
//                        prefs.edit().putString(PREF_DEFAULT_PRESET, cur).apply())
                .setNegativeButton(R.string.btn_unassign, (d, w) -> {
                    if (map.containsKey(p)) {
                        map.remove(p);
                        prefs.edit().putString(PREF_PLAYER_MAP, new Gson().toJson(map)).apply();
                    }
                })
                .show();
    }

    private void setupGalaControls() {
        switchGalaEnable.jumpDrawablesToCurrentState();
        switchGalaEnable.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { autoSaveCurrent(); } });
        
        Slider.OnChangeListener galal = (slider, value, fromUser) -> {
            int p = (int) value;
            if (slider == seekGalaInc) tvGalaIncVal.setText(getString(R.string.speed_kmh_format,p + 5));
            else if (slider == seekGalaMinSpeed) tvGalaMinSpeedVal.setText(getString(R.string.speed_kmh_format,p * 5));
//                else if (sb == seekGalaMaxSpeed) tvGalaMaxSpeedVal.setText(getString(R.string.speed_kmh_format,p * 5));
            else if (slider == seekGalaMaxAdj) tvGalaMaxAdjVal.setText(String.valueOf(p));
            else if (slider == seekSimulateSpeed) {
                tvSimulateSpeedVal.setText(getString(R.string.speed_kmh_format, p));
                if (fromUser) {
                    Intent intent = new Intent("com.radiorubka.wdsp.SIMULATE_SPEED");
                    intent.putExtra("speed", (float) p);
                    sendBroadcast(intent);
                }
            }
            if (fromUser && !isUpdatingUi) { autoSaveCurrent(); }
        };
        seekGalaInc.addOnChangeListener(galal);
        seekGalaMinSpeed.addOnChangeListener(galal);
//        seekGalaMaxSpeed.setOnSeekBarChangeListener(galal);
        seekSimulateSpeed.addOnChangeListener(galal);
        seekGalaMaxAdj.addOnChangeListener(galal);
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
        int lr = getIntSlider(seekFaderLr); tvFaderLrVal.setText(lr == 12 ? getString(R.string.lbl_center) : (lr < 12 ? "L " + (12-lr) : "R " + (lr-12)));
        int fr = getIntSlider(seekFaderFr); tvFaderFrVal.setText(fr == 12 ? getString(R.string.lbl_center) : (fr < 12 ? "Rear " + (12-fr) : "Front " + (fr-12)));
    }

    private void resetUiInternal() {
        isUpdatingUi = true; for (Slider s : gainSliders) s.setValue(6f); for (int i = 0; i<AudioConfig.NUM_BANDS; i++) updateDbLabel(i, 6);
        if (isFullyInitialized) {
            for (ToggleButton t : qSwitches) t.setChecked(false); seekSubGain.setValue(0); spinnerSubFreq.setText(SUB_FREQS[5], false);
            seekFaderLr.setValue(12); seekFaderFr.setValue(12); updateFaderLabels(); switchLoud.setChecked(false);
            switchFmEnable.setChecked(false); switchFatigueEnable.setChecked(false); switchFmSubComp.setChecked(false);
            seekFmCalVol.setValue(25); seekFmStrength.setValue(100);
            
            // GALA reset
            switchGalaEnable.setChecked(false);
            seekGalaInc.setValue(15);
            seekGalaMinSpeed.setValue(0);
//            seekGalaMaxSpeed.setProgress(30);
            seekGalaMaxAdj.setValue(12);
        }
        isUpdatingUi = false; updateVisualizer(); updateFmVisualizer();
    }
}
