package com.radiorubka.wdsp;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "wDSP_Main";
    private static final String PREFS_NAME = "EqPresets";
    private static final String PREF_PRESET_NAMES = "preset_names";
    private static final String PREF_LAST_SELECTED = "last_selected_preset";
    private static final String PREF_PLAYER_MAP = "player_preset_map";
    private static final String PREF_DEFAULT_PRESET = "default_preset_name";

    private final List<SeekBar> gainSliders = new ArrayList<>();
    private final List<ToggleButton> qSwitches = new ArrayList<>();
    private final List<TextView> dbLabels = new ArrayList<>();
    private Spinner spinnerPresets;
    private EqVisualizerView eqVisualizer;
    
    private SeekBar seekSubGain;
    private Spinner spinnerSubFreq;
    private TextView tvSubDb;
    private final String[] SUB_FREQS = {"25", "32", "40", "50", "63", "80", "100", "125", "160", "200", "250"};

    // Filter controls
    private SeekBar seekBassFilterFront, seekBassBoostFront, seekBassFilterRear, seekBassBoostRear;
    private TextView tvBassFilterFrontVal, tvBassBoostFrontDb, tvBassFilterRearVal, tvBassBoostRearDb;
    private Spinner spinnerBassFreqFront, spinnerBassFreqRear;
    private final String[] BASS_FILTER_FREQS = {"20", "25", "31", "40", "50", "63", "80", "100", "125", "160", "200", "250"};
    private final String[] BASS_BOOST_FREQS = {"off", "54", "68", "86", "108", "134", "172", "214"};

    // Fader & Delays
    private SeekBar seekFaderLr, seekFaderFr;
    private TextView tvFaderLrVal, tvFaderFrVal;
    private SwitchCompat switchLoud;
    private SeekBar seekDelayFl, seekDelayFr, seekDelayRl, seekDelayRr, seekDelaySub;
    private SeekBar seekDelay1Fl, seekDelay1Fr, seekDelay1Rl, seekDelay1Rr, seekDelay1RSSE;
    private SwitchCompat switchPreciseEnable, switchLegacyEnable;
    private TextView tvDelayFlVal, tvDelayFrVal, tvDelayRlVal, tvDelayRrVal, tvDelaySubVal;
    private TextView tvDelay1FlVal, tvDelay1FrVal, tvDelay1RlVal, tvDelay1RrVal, tvDelay1RSSEVal;

    // F-M Curve
    private SwitchCompat switchFmEnable, switchFatigueEnable, switchFmSubComp;
    private SeekBar seekFmCalVol, seekFmStrength;
    private TextView tvFmCalVolVal, tvFmStrengthVal, tvSysVolumeVal, tvSubOffsetVal, tvSubOffsetWarn;
    private EqVisualizerView fmVisualizer;
    
    // GALA Controls
    private SwitchCompat switchGalaEnable;
    private SeekBar seekGalaInc, seekGalaMinSpeed, seekGalaMaxSpeed, seekSimulateSpeed, seekGalaMaxAdj;
    private TextView tvGalaIncVal, tvGalaSpeed, tvGalaMinSpeedVal, tvGalaOffset, tvGalaMaxSpeedVal, tvSimulateSpeedVal, tvGalaMaxAdjVal;

    private float currentFmSubOffset = 0f;
    private int currentEffectiveVolume = -1;

    private ArrayAdapter<String> presetAdapter;
    private List<String> presetNames;
    private int accentColor;
    private boolean isUpdatingUi = false;
    private boolean isFullyInitialized = false;

    // MCU Management Fields
    private Object mcuManager;
    private Method setEqMethod;

    private Method getPropMethod;
    private final Map<Byte, byte[]> mcuCache = new HashMap<>();

    public static class Globals {
        public static int currentSubFreqHz = 0;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable eqMcuRunnable = this::updateEqMcu;

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.example.wdsp.PRESET_CHANGED".equals(action)) {
                String name = intent.getStringExtra("preset");
                if (name != null && presetNames != null && presetNames.contains(name)) {
                    Toast.makeText(MainActivity.this, "Auto applied preset: " + name, Toast.LENGTH_SHORT).show();
                    spinnerPresets.setSelection(presetNames.indexOf(name));
                }
            } else if ("com.example.wdsp.VOLUME_CHANGED".equals(action)) {
                currentEffectiveVolume = intent.getIntExtra("volume", -1);
                if (isFullyInitialized && findViewById(R.id.layout_fm_curve).getVisibility() == View.VISIBLE) {
                    updateFmVisualizer();
                }
            } else if ("com.example.wdsp.GALA_UPDATE".equals(action)) {
                float speed = intent.getFloatExtra("speed", 0.0f);
                int offset = intent.getIntExtra("waveOffset", 0);
                if (tvGalaSpeed != null) tvGalaSpeed.setText(String.format(Locale.getDefault(), "%.1f km/h", speed));
                if (tvGalaOffset != null) tvGalaOffset.setText(String.format(Locale.getDefault(), "+%d", offset));
            }
        }
    };

    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> { if (r.getResultCode() == RESULT_OK && r.getData() != null) savePresetsToFile(r.getData().getData()); });

    private final ActivityResultLauncher<Intent> importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> { if (r.getResultCode() == RESULT_OK && r.getData() != null) loadPresetsFromFile(r.getData().getData()); });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        accentColor = ContextCompat.getColor(this, R.color.cyan_custom);
        
        // 1. Instant UI: Minimal views needed for the first screen
        initPrimaryViews();
        SelectTab();
        
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

    private void bypassHiddenApiRestrictions() {
        try {
            Method gr = Class.forName("dalvik.system.VMRuntime").getDeclaredMethod("getRuntime");
            Object vmr = gr.invoke(null);
            Method setEx = vmr.getClass().getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setEx.invoke(vmr, (Object) new String[]{"L"});
        } catch (Exception e) {
            Log.e(TAG, "Hidden API bypass failed", e);
        }
    }

    private void startMcuService() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
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
        if (isFullyInitialized) {
            refreshAllUiValues();
            SelectTab();
        }
        // Force the UI to match the saved preference
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String current = prefs.getString("last_selected_preset", "Preset 1");
        int index = presetNames.indexOf(current);
        if (index >= 0) {
            spinnerPresets.setSelection(index, false); // Update spinner without triggering listeners
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
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
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
        String currentPreset = (String) spinnerPresets.getSelectedItem();

        if (currentPreset != null) {
            // 2. This updates all sliders, toggles, and EQ visuals from SharedPreferences
            loadPreset(currentPreset);
        }

        // 3. Specifically update things that might change outside the app (like Volume)
        updateFmVisualizer();
        updateFaderLabels();

        TextView tvStatus = findViewById(R.id.tv_hw_status);
        if (tvStatus != null) {
            if (mcuManager != null || isHardwareAccessible()) {
                tvStatus.setText("");
                tvStatus.setTextColor(Color.GREEN);
            } else {
                tvStatus.setText(R.string.hw_demo_mode);
                tvStatus.setTextColor(Color.RED);
            }
        }
    }

    private boolean isHardwareAccessible() {
        try {
            IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "mcu_service");
            return b != null;
        } catch (Exception e) {
            return false;
        }
    }

    private void initPrimaryViews() {
        spinnerPresets = findViewById(R.id.spinner_presets);
        eqVisualizer = findViewById(R.id.eq_visualizer);
        seekSubGain = findViewById(R.id.seek_sub_gain);
        spinnerSubFreq = findViewById(R.id.spinner_sub_freq);
        tvSubDb = findViewById(R.id.tv_sub_db);
        setupNavigation();
    }

    private void registerServiceReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.wdsp.PRESET_CHANGED");
        filter.addAction("com.example.wdsp.VOLUME_CHANGED");
        filter.addAction("com.example.wdsp.GALA_UPDATE");
        
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
        findViewById(R.id.btn_apply).setOnClickListener(v -> {
            autoSaveCurrent();
            applyAllToMcu();
            Toast.makeText(this, R.string.toast_settings_applied, Toast.LENGTH_SHORT).show();
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
        seekGalaMaxSpeed = findViewById(R.id.seek_gala_maxspeed);
        tvGalaMaxSpeedVal = findViewById(R.id.tv_gala_maxspeed_val);
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
        Intent intent = new Intent(active ? "com.example.wdsp.UI_ACTIVE" : "com.example.wdsp.UI_INACTIVE");
        intent.setPackage(getPackageName());
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        sendBroadcast(intent);
    }

    private void applyAllToMcu() {
        if (!isFullyInitialized) return;
        handler.post(this::updateEqMcu);
        handler.postDelayed(this::updateSubMcu, 50);
        handler.postDelayed(this::updateBassMcu, 100);
        handler.postDelayed(this::updateFaderMcu, 150);
        handler.postDelayed(this::updateDelayMcu, 200);
        handler.postDelayed(this::updateDelay1Mcu, 250);
    }

    private void setupEqBands() {
        LinearLayout container = findViewById(R.id.eq_container);
        container.removeAllViews(); gainSliders.clear(); qSwitches.clear(); dbLabels.clear();
        int cQ = ContextCompat.getColor(this, R.color.q_switch_text);
        int cL = ContextCompat.getColor(this, R.color.band_label);
        int cS = ContextCompat.getColor(this, R.color.seek_track);
        float smallTextSize = getResources().getDimension(R.dimen.text_size_small);

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            final int idx = i;
            ToggleButton q = new ToggleButton(this);
            TextView db = new TextView(this);
            SeekBar s = new SeekBar(this);
            qSwitches.add(q); dbLabels.add(db); gainSliders.add(s);

            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL); layout.setGravity(Gravity.CENTER_HORIZONTAL);
            layout.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));

            q.setTextOn(getString(R.string.q_high)); q.setTextOff(getString(R.string.q_low)); q.setChecked(false);
            q.setTextColor(cQ); q.setBackgroundColor(Color.TRANSPARENT);
            q.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize); 
            q.setPadding(0, 0, 0, 0); q.setMinimumHeight(0); q.setMinimumWidth(0);
            q.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.08f));
            q.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { updateVisualizer(); updateEqMcu(); autoSaveCurrent(); } });

            db.setText("0"); db.setTextColor(accentColor); 
            db.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize);
            db.setGravity(Gravity.CENTER); db.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.08f));

            TextView label = new TextView(this);
            label.setText(AudioConfig.BAND_LABELS[i]); label.setTextColor(cL); 
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, smallTextSize);
            label.setGravity(Gravity.CENTER); label.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.08f));

            s.setMax(12); s.setProgress(6);
            s.setProgressTintList(ColorStateList.valueOf(cS));
            s.setThumbTintList(ColorStateList.valueOf(accentColor));
            s.setPadding(0, 0, 0, 0); s.setThumbOffset(0); s.setRotation(270f);

            FrameLayout seekBox = new FrameLayout(this);
            seekBox.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.76f));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(1000, -2);
            lp.gravity = Gravity.CENTER; s.setLayoutParams(lp);

            seekBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int h = b - t;
                if (h > 0 && s.getWidth() != h) { ViewGroup.LayoutParams vlp = s.getLayoutParams(); vlp.width = h; s.setLayoutParams(vlp); }
            });

            s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    if (!isUpdatingUi) {
                        updateDbLabel(idx, p);
                        updateVisualizer();
                        if (u) {
                            handler.removeCallbacks(eqMcuRunnable);
                            handler.postDelayed(eqMcuRunnable, 50);
                        }
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {
                    if (!isUpdatingUi) autoSaveCurrent();
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
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) gs[i] = gainSliders.get(i).getProgress();
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
            SeekBar s = gainSliders.get(i);
            int n = Math.max(0, Math.min(12, s.getProgress() + d));
            s.setProgress(n); updateDbLabel(i, n);
        }
        isUpdatingUi = false;
        updateVisualizer(); updateEqMcu(); autoSaveCurrent();
    }

    private void setupSubControls() {
        spinnerSubFreq.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, SUB_FREQS) {{ setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); }});
        spinnerSubFreq.setSelection(5);
        spinnerSubFreq.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (isFullyInitialized && switchFmSubComp.isChecked() && pos > 5) { spinnerSubFreq.setSelection(5); Toast.makeText(MainActivity.this, R.string.toast_sub_comp_limit, Toast.LENGTH_SHORT).show(); return; }
                if (!isUpdatingUi) { autoSaveCurrent(); updateSubMcu(); }
                String freqString = SUB_FREQS[pos];
                Globals.currentSubFreqHz = Integer.parseInt(freqString);
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        seekSubGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { 
                String text = "+" + p;
                tvSubDb.setText(text); 
                if (u && !isUpdatingUi) { autoSaveCurrent(); updateSubMcu(); } 
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupFilterControls() {
        ArrayAdapter<String> bbAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, BASS_BOOST_FREQS) {{ setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); }};
        spinnerBassFreqFront.setAdapter(bbAdapter); spinnerBassFreqRear.setAdapter(bbAdapter);
        AdapterView.OnItemSelectedListener sl = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { if (!isUpdatingUi) { autoSaveCurrent(); updateBassMcu(); } }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        };
        spinnerBassFreqFront.setOnItemSelectedListener(sl); spinnerBassFreqRear.setOnItemSelectedListener(sl);

        SeekBar.OnSeekBarChangeListener bl = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (sb == seekBassFilterFront) tvBassFilterFrontVal.setText(getString(R.string.lbl_hz_fmt, BASS_FILTER_FREQS[p]));
                else if (sb == seekBassBoostFront) tvBassBoostFrontDb.setText(getString(R.string.lbl_db_fmt, p));
                else if (sb == seekBassFilterRear) tvBassFilterRearVal.setText(getString(R.string.lbl_hz_fmt, BASS_FILTER_FREQS[p]));
                else if (sb == seekBassBoostRear) tvBassBoostRearDb.setText(getString(R.string.lbl_db_fmt, p));
                if (u && !isUpdatingUi) { autoSaveCurrent(); updateBassMcu(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        seekBassFilterFront.setOnSeekBarChangeListener(bl); seekBassBoostFront.setOnSeekBarChangeListener(bl);
        seekBassFilterRear.setOnSeekBarChangeListener(bl); seekBassBoostRear.setOnSeekBarChangeListener(bl);

        seekFaderLr.setMax(24); seekFaderFr.setMax(24);
        SeekBar.OnSeekBarChangeListener fl = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { updateFaderLabels(); if (u && !isUpdatingUi) { autoSaveCurrent(); updateFaderMcu(); } }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        seekFaderLr.setOnSeekBarChangeListener(fl); seekFaderFr.setOnSeekBarChangeListener(fl);
        switchLoud.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { autoSaveCurrent(); updateFaderMcu(); } });
    }

    private void setupDelayControls() {
        SeekBar.OnSeekBarChangeListener dl = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                float ms = p * 0.5f; String val = String.format(Locale.getDefault(), getString(R.string.delay_value_format), ms, Math.round(ms * 34.3f));
                if (sb == seekDelayFl) tvDelayFlVal.setText(val); else if (sb == seekDelayFr) tvDelayFrVal.setText(val);
                else if (sb == seekDelayRl) tvDelayRlVal.setText(val); else if (sb == seekDelayRr) tvDelayRrVal.setText(val);
                else if (sb == seekDelaySub) tvDelaySubVal.setText(val);
                if (u && !isUpdatingUi) updateDelayMcu();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { if (!isUpdatingUi) autoSaveCurrent(); }
        };
        seekDelayFl.setOnSeekBarChangeListener(dl); seekDelayFr.setOnSeekBarChangeListener(dl);
        seekDelayRl.setOnSeekBarChangeListener(dl); seekDelayRr.setOnSeekBarChangeListener(dl); seekDelaySub.setOnSeekBarChangeListener(dl);
        switchPreciseEnable.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { if (checked) switchLegacyEnable.setChecked(false); updateDelayMcu(); autoSaveCurrent(); } });
    }

    private void setupDelay1Controls() {
        SeekBar.OnSeekBarChangeListener dl = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (sb == seekDelay1RSSE) { 
                    int v = p - 10; 
                    String text = (v > 0 ? "+" : "") + v;
                    tvDelay1RSSEVal.setText(text); 
                }
                else { float ms = p * 1.0f; String val = String.format(Locale.getDefault(), getString(R.string.delay_value_format), ms, Math.round(ms * 34.3f));
                    if (sb == seekDelay1Fl) tvDelay1FlVal.setText(val); else if (sb == seekDelay1Fr) tvDelay1FrVal.setText(val);
                    else if (sb == seekDelay1Rl) tvDelay1RlVal.setText(val); else if (sb == seekDelay1Rr) tvDelay1RrVal.setText(val);
                }
                if (u && !isUpdatingUi) updateDelay1Mcu();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { if (!isUpdatingUi) autoSaveCurrent(); }
        };
        seekDelay1Fl.setOnSeekBarChangeListener(dl); seekDelay1Fr.setOnSeekBarChangeListener(dl);
        seekDelay1Rl.setOnSeekBarChangeListener(dl); seekDelay1Rr.setOnSeekBarChangeListener(dl); seekDelay1RSSE.setOnSeekBarChangeListener(dl);
        switchLegacyEnable.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { if (checked) switchPreciseEnable.setChecked(false); updateDelay1Mcu(); autoSaveCurrent(); } });
    }

    private void setupFmControls() {
        switchFmEnable.setOnCheckedChangeListener((bv, checked) -> {
            if (!isUpdatingUi) {
                autoSaveCurrent();
                updateFmVisualizer();
                updateEqMcu();
                updateSubMcu();
            }
        });
        switchFatigueEnable.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { autoSaveCurrent(); updateFmVisualizer(); updateEqMcu(); } });
        switchFmSubComp.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { if (checked && spinnerSubFreq.getSelectedItemPosition() > 5) spinnerSubFreq.setSelection(5); autoSaveCurrent(); updateFmVisualizer(); updateEqMcu(); updateSubMcu(); } });
        SeekBar.OnSeekBarChangeListener fml = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (sb == seekFmCalVol) tvFmCalVolVal.setText(String.valueOf(p)); else tvFmStrengthVal.setText(String.valueOf(p));
                if (u && !isUpdatingUi) { updateFmVisualizer(); updateEqMcu(); updateSubMcu(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { autoSaveCurrent(); }
        };
        seekFmCalVol.setOnSeekBarChangeListener(fml); seekFmStrength.setOnSeekBarChangeListener(fml);
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
            float pot = gainSliders.get(i).getProgress() + (total / 2f);
            float wOffset = (pot - 12f) * 2; warns[i] = (pot > 12.025f && wOffset > 0.999f) ? wOffset : 0f;
            gs[i] = Math.round(Math.max(0, Math.min(12, 6f + (total / 2f))));
        }
        fmVisualizer.setGains(gs); fmVisualizer.setOffsets(actual); fmVisualizer.setWarnings(warns);
        if (switchFmSubComp.isChecked()) {
            tvSubOffsetVal.setText(String.format(Locale.getDefault(), "%.1f dB", currentFmSubOffset));
            float subPot = currentFmSubOffset + seekSubGain.getProgress();
            tvSubOffsetWarn.setText(subPot > 12.25f ? String.format(Locale.getDefault(), "%.1f dB", subPot - 12f) : "OK");
        } else { tvSubOffsetVal.setText(getString(R.string.none)); tvSubOffsetWarn.setText(getString(R.string.none)); }
        fmVisualizer.invalidate();
    }

    private float[] calculateFmOffsets() {
        float[] offs = new float[AudioConfig.NUM_BANDS]; currentFmSubOffset = 0f;
        int vol = Math.max(1, (currentEffectiveVolume != -1) ? currentEffectiveVolume : getSystemVolume());
        int cal = seekFmCalVol.getProgress(); float str = seekFmStrength.getProgress() / 100f;
        if (vol < cal && switchFmEnable.isChecked()) {
            float ratio = (float)(cal - vol) / (cal > 1 ? (float)(cal - 1) : 1f);
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
        presetNames = new ArrayList<>(); if (names != null) { presetNames.addAll(names); Collections.sort(presetNames); }
        String defaultPreset = getString(R.string.default_preset_name);
        if (presetNames.isEmpty()) { presetNames.add(defaultPreset); savePresetList(); savePreset(defaultPreset); }
        presetAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, presetNames) {{ setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); }};
        spinnerPresets.setAdapter(presetAdapter);
        String toLoad = (last != null && presetNames.contains(last)) ? last : presetNames.get(0);
        spinnerPresets.setSelection(presetNames.indexOf(toLoad));
        loadPreset(toLoad);
        spinnerPresets.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String s = presetNames.get(pos); if (!isUpdatingUi) { loadPreset(s); getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_LAST_SELECTED, s).apply(); }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
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
        do { n = prefix + (presetNames.size() + c++); } while (presetNames.contains(n));
        presetNames.add(n);
        Collections.sort(presetNames);
        savePresetList();
        savePreset(n);
        presetAdapter.notifyDataSetChanged();
        spinnerPresets.setSelection(presetNames.indexOf(n));
    }

    private void renameCurrentPreset() {
        final String old = (String) spinnerPresets.getSelectedItem(); if (old == null) return;
        final EditText in = new EditText(this); in.setText(old); in.setSelection(old.length());
        new AlertDialog.Builder(this).setTitle(R.string.dialog_rename_title).setView(in).setPositiveButton(R.string.btn_ok, (d, w) -> {
            String n = in.getText().toString().trim();
            if (!n.isEmpty() && !n.equals(old)) { if (presetNames.contains(n)) Toast.makeText(this, R.string.toast_exists, Toast.LENGTH_SHORT).show(); else performRename(old, n); }
        }).setNegativeButton(R.string.btn_cancel, null).show();
    }

    private void performRename(String o, String n) {
        if ("Call".equals(o)) return;
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        copyPresetData(p, e, o, n);
        int idx = presetNames.indexOf(o);
        presetNames.set(idx, n);
        Collections.sort(presetNames);
        if (o.equals(p.getString(PREF_LAST_SELECTED, null))) e.putString(PREF_LAST_SELECTED, n);
        e.putStringSet(PREF_PRESET_NAMES, new HashSet<>(presetNames)); e.apply();
        presetAdapter.notifyDataSetChanged(); spinnerPresets.setSelection(presetNames.indexOf(n));
    }

    private void copyPresetData(SharedPreferences p, SharedPreferences.Editor e, String o, String n) {
        String[] keys = {"_sub_g", "_sub_f", "_bf_f", "_bb_f", "_bf_r", "_bb_r", "_bb_frq_f", "_bb_frq_r", "_f_lr", "_f_fr", "_loud", "_fm_en", "_fat_en", "_sub_comp", "_fm_cal", "_fm_str", "_d_fl", "_d_fr", "_d_rl", "_d_rr", "_d_sub", "_d_en", "_d1_fl", "_d1_fr", "_d1_rl", "_d1_rr", "_rsse_val", "_d1_en", "_gala_enabled", "_gala_increment", "_gala_min_speed", "_gala_max_speed", "_gala_max_adj"};
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
        String curr = (String) spinnerPresets.getSelectedItem();
        if (curr == null || "Call".equals(curr)) return;
        if (presetNames.size() <= 1) { Toast.makeText(this, R.string.toast_cannot_delete_last, Toast.LENGTH_SHORT).show(); return; }
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
        } catch (Exception err) { }
        String defaultPreset = getString(R.string.default_preset_name);
        if (curr.equals(p.getString("default_preset_name", ""))) e.putString("default_preset_name", defaultPreset);
        presetNames.remove(curr);
        if (presetNames.isEmpty()) { presetNames.add(defaultPreset); resetUiInternal(6); savePreset(defaultPreset); }
        e.putStringSet(PREF_PRESET_NAMES, new HashSet<>(presetNames));
        e.apply();
        presetAdapter.notifyDataSetChanged();
        spinnerPresets.setSelection(0);
        loadPreset(presetNames.get(0));
    }

    private void savePreset(String name) {
        SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) { e.putInt(name + "_g" + i, gainSliders.get(i).getProgress()); e.putBoolean(name + "_q" + i, qSwitches.get(i).isChecked()); }
        e.putInt(name + "_sub_g", seekSubGain.getProgress()); e.putInt(name + "_sub_f", spinnerSubFreq.getSelectedItemPosition());
        if (isFullyInitialized) {
            e.putInt(name + "_bf_f", seekBassFilterFront.getProgress()); e.putInt(name + "_bb_f", seekBassBoostFront.getProgress());
            e.putInt(name + "_bf_r", seekBassFilterRear.getProgress()); e.putInt(name + "_bb_r", seekBassBoostRear.getProgress());
            e.putInt(name + "_bb_frq_f", spinnerBassFreqFront.getSelectedItemPosition()); e.putInt(name + "_bb_frq_r", spinnerBassFreqRear.getSelectedItemPosition());
            e.putInt(name + "_f_lr", seekFaderLr.getProgress()); e.putInt(name + "_f_fr", seekFaderFr.getProgress());
            e.putBoolean(name + "_loud", switchLoud.isChecked()); e.putBoolean(name + "_fm_en", switchFmEnable.isChecked());
            e.putBoolean(name + "_fat_en", switchFatigueEnable.isChecked()); e.putBoolean(name + "_sub_comp", switchFmSubComp.isChecked());
            e.putInt(name + "_fm_cal", seekFmCalVol.getProgress()); e.putInt(name + "_fm_str", seekFmStrength.getProgress());
            e.putInt(name + "_d_fl", seekDelayFl.getProgress()); e.putInt(name + "_d_fr", seekDelayFr.getProgress());
            e.putInt(name + "_d_rl", seekDelayRl.getProgress()); e.putInt(name + "_d_rr", seekDelayRr.getProgress());
            e.putInt(name + "_d_sub", seekDelaySub.getProgress()); e.putBoolean(name + "_d_en", switchPreciseEnable.isChecked());
            e.putInt(name + "_d1_fl", seekDelay1Fl.getProgress()); e.putInt(name + "_d1_fr", seekDelay1Fr.getProgress());
            e.putInt(name + "_d1_rl", seekDelay1Rl.getProgress()); e.putInt(name + "_d1_rr", seekDelay1Rr.getProgress());
            e.putInt(name + "_rsse_val", seekDelay1RSSE.getProgress()); e.putBoolean(name + "_d1_en", switchLegacyEnable.isChecked());
            
            // GALA
            e.putBoolean(name + "_gala_enabled", switchGalaEnable.isChecked());
            e.putInt(name + "_gala_increment", seekGalaInc.getProgress());
            e.putInt(name + "_gala_min_speed", seekGalaMinSpeed.getProgress());
            e.putInt(name + "_gala_max_speed", seekGalaMaxSpeed.getProgress());
            e.putInt(name + "_gala_max_adj", seekGalaMaxAdj.getProgress());
        }
        e.apply();
    }

    private void loadPreset(String name) {
        isUpdatingUi = true;
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            int g = p.getInt(name + "_g" + i, 6); gainSliders.get(i).setProgress(g); updateDbLabel(i, g);
            qSwitches.get(i).setChecked(p.getBoolean(name + "_q" + i, false));
        }
        int sg = p.getInt(name + "_sub_g", 0); seekSubGain.setProgress(sg); 
        String subText = "+" + sg;
        tvSubDb.setText(subText);
        spinnerSubFreq.setSelection(p.getInt(name + "_sub_f", 5));
        if (isFullyInitialized) {
            seekBassFilterFront.setProgress(p.getInt(name + "_bf_f", 0));
            seekBassBoostFront.setProgress(p.getInt(name + "_bb_f", 0));
            seekBassFilterRear.setProgress(p.getInt(name + "_bf_r", 0));
            seekBassBoostRear.setProgress(p.getInt(name + "_bb_r", 0));
            spinnerBassFreqFront.setSelection(p.getInt(name + "_bb_frq_f", 0));
            spinnerBassFreqRear.setSelection(p.getInt(name + "_bb_frq_r", 0));
            seekFaderLr.setProgress(p.getInt(name + "_f_lr", 12));
            seekFaderFr.setProgress(p.getInt(name + "_f_fr", 12));
            updateFaderLabels(); switchLoud.setChecked(p.getBoolean(name + "_loud", false));
            switchFmEnable.setChecked(p.getBoolean(name + "_fm_en", false));
            switchFatigueEnable.setChecked(p.getBoolean(name + "_fat_en", false));
            switchFmSubComp.setChecked(p.getBoolean(name + "_sub_comp", false));
            seekFmCalVol.setProgress(p.getInt(name + "_fm_cal", 25));
            String calText = "" + seekFmCalVol.getProgress();
            tvFmCalVolVal.setText(calText);
            seekFmStrength.setProgress(p.getInt(name + "_fm_str", 100));
            String strText = "" + seekFmStrength.getProgress();
            tvFmStrengthVal.setText(strText);
            seekDelayFl.setProgress(p.getInt(name + "_d_fl", 0));
            seekDelayFr.setProgress(p.getInt(name + "_d_fr", 0));
            seekDelayRl.setProgress(p.getInt(name + "_d_rl", 0));
            seekDelayRr.setProgress(p.getInt(name + "_d_rr", 0));
            seekDelaySub.setProgress(p.getInt(name + "_d_sub", 0));
            switchPreciseEnable.setChecked(p.getBoolean(name + "_d_en", false));
            seekDelay1Fl.setProgress(p.getInt(name + "_d1_fl", 0));
            seekDelay1Fr.setProgress(p.getInt(name + "_d1_fr", 0));
            seekDelay1Rl.setProgress(p.getInt(name + "_d1_rl", 0));
            seekDelay1Rr.setProgress(p.getInt(name + "_d1_rr", 0));
            seekDelay1RSSE.setProgress(p.getInt(name + "_rsse_val", 10));
            switchLegacyEnable.setChecked(p.getBoolean(name + "_d1_en", false));
            
            // GALA
            switchGalaEnable.setChecked(p.getBoolean(name + "_gala_enabled", false));
            seekGalaInc.setProgress(p.getInt(name + "_gala_increment", 15));
            tvGalaIncVal.setText((seekGalaInc.getProgress() + 5) + " km/h");
            seekGalaMinSpeed.setProgress(p.getInt(name + "_gala_min_speed", 0));
            tvGalaMinSpeedVal.setText((seekGalaMinSpeed.getProgress() * 5) + " km/h");
            seekGalaMaxSpeed.setProgress(p.getInt(name + "_gala_max_speed", 30));
            tvGalaMaxSpeedVal.setText((seekGalaMaxSpeed.getProgress() * 5) + " km/h");
            seekGalaMaxAdj.setProgress(p.getInt(name + "_gala_max_adj", 12));
            tvGalaMaxAdjVal.setText(String.valueOf(seekGalaMaxAdj.getProgress()));
        }
        isUpdatingUi = false; updateVisualizer(); updateFmVisualizer(); applyAllToMcu();
    }

    private void updateEqMcu() {
        if (!isFullyInitialized) return;
        float[] offs = calculateFmOffsets(); byte[] data = new byte[12]; data[0] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            int b1 = i * 2; float db1 = (gainSliders.get(b1).getProgress() - 6) * 2 + offs[b1];
            int b2 = i * 2 + 1; float db2 = (gainSliders.get(b2).getProgress() - 6) * 2 + offs[b2];
            data[i+1] = (byte) ((AudioConfig.GAIN_MAP[Math.max(0, Math.min(12, Math.round((db2/2f)+6)))] << 4) | (AudioConfig.GAIN_MAP[Math.max(0, Math.min(12, Math.round((db1/2f)+6)))] & 0x0F));
        }
        data[9] = calculateQByte(0); data[10] = calculateQByte(8); data[11] = 0x00; sendEqToHardware(data);
    }

    private void updateSubMcu() {
        if (!isFullyInitialized) return;
        byte[] d = new byte[2]; d[0] = (byte) 0x8B;
        int g = Math.max(0, Math.min(12, Math.round(seekSubGain.getProgress() + currentFmSubOffset)));
        d[1] = (byte) ((spinnerSubFreq.getSelectedItemPosition() << 4) | (g & 0x0F)); sendEqToHardware(d);
    }

    private void updateBassMcu() {
        if (!isFullyInitialized) return;
        byte[] d = new byte[4]; d[0] = (byte) 0x88;
        d[1] = (byte) (((spinnerBassFreqFront.getSelectedItemPosition() + 8) << 4) | (seekBassBoostFront.getProgress() & 0x0F));
        d[2] = (byte) (((spinnerBassFreqRear.getSelectedItemPosition() + 8) << 4) | (seekBassBoostRear.getProgress() & 0x0F));
        d[3] = (byte) ((seekBassFilterFront.getProgress() << 4) | (seekBassFilterRear.getProgress() & 0x0F)); sendEqToHardware(d);
    }

    private void updateFaderMcu() {
        if (!isFullyInitialized) return;
        byte[] d = new byte[4]; d[0] = (byte) 0x81; d[1] = (byte) (seekFaderLr.getProgress() & 0xFF); d[2] = (byte) (seekFaderFr.getProgress() & 0xFF); d[3] = (byte) (switchLoud.isChecked() ? 1 : 0); sendEqToHardware(d);
    }

    private void updateDelayMcu() {
        if (!isFullyInitialized) return;
        byte[] d = new byte[6]; d[0] = (byte) 0x8C;
        if (switchPreciseEnable.isChecked()) {
            d[1] = (byte) ((seekDelayFl.getProgress() * 5) & 0xFF); d[2] = (byte) ((seekDelayFr.getProgress() * 5) & 0xFF);
            d[3] = (byte) ((seekDelayRl.getProgress() * 5) & 0xFF); d[4] = (byte) ((seekDelayRr.getProgress() * 5) & 0xFF); d[5] = (byte) ((seekDelaySub.getProgress() * 5) & 0xFF);
        } else Arrays.fill(d, 1, 6, (byte)0); sendEqToHardware(d);
    }

    private void updateDelay1Mcu() {
        if (!isFullyInitialized) return;
        byte[] d = new byte[6]; d[0] = (byte) 0x89;
        if (switchLegacyEnable.isChecked()) {
            d[1] = (byte) (138 + (seekDelay1RSSE.getProgress() - 10)); d[2] = (byte) (seekDelay1Fl.getProgress() & 0xFF);
            d[3] = (byte) (seekDelay1Fr.getProgress() & 0xFF); d[4] = (byte) (seekDelay1Rl.getProgress() & 0xFF); d[5] = (byte) (seekDelay1Rr.getProgress() & 0xFF);
        } else Arrays.fill(d, 1, 6, (byte)0); sendEqToHardware(d);
    }

    private byte calculateQByte(int off) { int r = 0; for (int i = 0; i < 8; i++) if (qSwitches.get(off+i).isChecked()) r |= (1 << i); return (byte) r; }

    private void sendEqToHardware(byte[] data) {
        if (data == null || data.length == 0) return;
        byte cmd = data[0];
        byte[] cached = mcuCache.get(cmd);
        if (cached == null || !Arrays.equals(cached, data)) {
            mcuCache.put(cmd, data.clone());
            try {
                if (mcuManager == null) {
                    IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "mcu_service");
                    if (b != null) {
                        mcuManager = Class.forName("android.qf.mcu.IMcuManager$Stub").getMethod("asInterface", IBinder.class).invoke(null, b);
                    }
                    if (mcuManager != null) {
                        setEqMethod = mcuManager.getClass().getMethod("RPC_SetEQData", byte[].class);
                    }
                }
                if (mcuManager == null || setEqMethod == null) return;
                setEqMethod.invoke(mcuManager, (Object) data);
            } catch (Exception e) {
                Log.e(TAG, "MCU Error: " + e.getMessage());
            }
        }
    }

    private void setupNavigation() {
        BottomNavigationView bn = findViewById(R.id.bottom_navigation);
        final View eq = findViewById(R.id.layout_eq),
                fm = findViewById(R.id.layout_fm_curve),
                dly = findViewById(R.id.layout_delays),
                ftr = findViewById(R.id.layout_filters),
                gl = findViewById(R.id.layout_gala);
        bn.setOnItemSelectedListener(it -> {
            int id = it.getItemId(); 
            eq.setVisibility(View.GONE);
            fm.setVisibility(View.GONE);
            dly.setVisibility(View.GONE);
            ftr.setVisibility(View.GONE);
            gl.setVisibility(View.GONE);
            if (id == R.id.nav_eq) eq.setVisibility(View.VISIBLE); 
            else if (id == R.id.nav_fm_curve) { fm.setVisibility(View.VISIBLE); updateFmVisualizer(); }
            else if (id == R.id.nav_delays) dly.setVisibility(View.VISIBLE); 
            else if (id == R.id.nav_other) ftr.setVisibility(View.VISIBLE);
            else if (id == R.id.nav_gala) gl.setVisibility(View.VISIBLE);
            return true;
        });
    }

    private void initReflection() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
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

    private void showAutoPresetDialog() {
        String p = getSystemProperty("sys.qf.last_audio_src", "Unknown");
        String cur = (String) spinnerPresets.getSelectedItem();
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Map<String, String> map = new Gson().fromJson(prefs.getString(PREF_PLAYER_MAP, "{}"), new TypeToken<Map<String, String>>(){}.getType());
        String def = prefs.getString(PREF_DEFAULT_PRESET, getString(R.string.none));
        
        StringBuilder sb = new StringBuilder(getString(R.string.current_associations));
        for (Map.Entry<String, String> entry : map.entrySet()) sb.append("- ").append(entry.getKey()).append(" -> ").append(entry.getValue()).append("\n");
        sb.append(getString(R.string.global_default_fmt, def));
        
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.automation_title_fmt, cur))
                .setMessage(getString(R.string.active_player_fmt, p) + "\n\n" + sb.toString())
                .setPositiveButton(R.string.btn_assign, (d, w) -> { map.put(p, cur); prefs.edit().putString(PREF_PLAYER_MAP, new Gson().toJson(map)).apply(); })
                .setNeutralButton(R.string.btn_set_default, (d, w) -> { prefs.edit().putString(PREF_DEFAULT_PRESET, cur).apply(); })
                .setNegativeButton(R.string.btn_unassign, (d, w) -> { if (map.containsKey(p)) { map.remove(p); prefs.edit().putString(PREF_PLAYER_MAP, new Gson().toJson(map)).apply(); } })
                .show();
    }

    private void setupGalaControls() {
        switchGalaEnable.setOnCheckedChangeListener((bv, checked) -> { if (!isUpdatingUi) { autoSaveCurrent(); } });
        
        SeekBar.OnSeekBarChangeListener galal = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (sb == seekGalaInc) tvGalaIncVal.setText((p + 5) + " km/h");
                else if (sb == seekGalaMinSpeed) tvGalaMinSpeedVal.setText((p * 5) + " km/h");
                else if (sb == seekGalaMaxSpeed) tvGalaMaxSpeedVal.setText((p * 5) + " km/h");
                else if (sb == seekGalaMaxAdj) tvGalaMaxAdjVal.setText(String.valueOf(p));
                else if (sb == seekSimulateSpeed) {
                    tvSimulateSpeedVal.setText(p + " km/h");
                    if (u) {
                        Intent intent = new Intent("com.example.wdsp.SIMULATE_SPEED");
                        intent.putExtra("speed", (float) p);
                        sendBroadcast(intent);
                    }
                }
                if (u && !isUpdatingUi) { autoSaveCurrent(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        seekGalaInc.setOnSeekBarChangeListener(galal);
        seekGalaMinSpeed.setOnSeekBarChangeListener(galal);
        seekGalaMaxSpeed.setOnSeekBarChangeListener(galal);
        seekSimulateSpeed.setOnSeekBarChangeListener(galal);
        seekGalaMaxAdj.setOnSeekBarChangeListener(galal);
    }

    private void savePresetList() { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putStringSet(PREF_PRESET_NAMES, new HashSet<>(presetNames)).apply(); }
    private void autoSaveCurrent() { String n = (String) spinnerPresets.getSelectedItem(); if (n != null) savePreset(n); }
    private int getSystemVolume() { return VolumeHelper.getVolume(); }

    @Override protected void onDestroy() { super.onDestroy(); handler.removeCallbacksAndMessages(null); try { unregisterReceiver(serviceReceiver); } catch (Exception e) {} }
    private void exportPresets() { String s = (String) spinnerPresets.getSelectedItem(); exportLauncher.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, (s != null ? s : "wDSP_Presets") + ".json")); }
    private void importPresets() {
        importLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")); }
    private void savePresetsToFile(Uri u) { try (OutputStream os = getContentResolver().openOutputStream(u)) { os.write(new Gson().toJson(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getAll()).getBytes()); Toast.makeText(this, R.string.toast_exported, Toast.LENGTH_SHORT).show(); } catch (IOException e) { Log.e(TAG, "Export error", e); } }
    private void loadPresetsFromFile(Uri u) {
        try (InputStream is = getContentResolver().openInputStream(u); BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String l; while ((l = r.readLine()) != null) sb.append(l);
            Map<String, Object> im = new Gson().fromJson(sb.toString(), new TypeToken<Map<String, Object>>() {}.getType());
            SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().clear();
            for (Map.Entry<String, Object> ent : im.entrySet()) {
                Object v = ent.getValue(); String k = ent.getKey();
                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else if (v instanceof String) e.putString(k, (String) v);
                else if (v instanceof Double) { double d = (Double) v;
                    if (d == Math.rint(d)) e.putInt(k, (int) d);
                    else e.putFloat(k, (float) d); }
                else if (v instanceof List) { Set<String> s = new HashSet<>(); for (Object item : (List<?>) v) s.add(item.toString()); e.putStringSet(k, s); }
            }
            e.apply();
            setupPresets();
            Toast.makeText(this, R.string.toast_imported, Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Log.e(TAG, "Import error", e); }
    }

    private void updateFaderLabels() {
        int lr = seekFaderLr.getProgress(); tvFaderLrVal.setText(lr == 12 ? getString(R.string.lbl_center) : (lr < 12 ? "L " + (12-lr) : "R " + (lr-12)));
        int fr = seekFaderFr.getProgress(); tvFaderFrVal.setText(fr == 12 ? getString(R.string.lbl_center) : (fr < 12 ? "Rear " + (12-fr) : "Front " + (fr-12)));
    }

    private void resetUiInternal(int p) {
        isUpdatingUi = true; for (SeekBar s : gainSliders) s.setProgress(p); for (int i=0; i<AudioConfig.NUM_BANDS; i++) updateDbLabel(i, p);
        if (isFullyInitialized) {
            for (ToggleButton t : qSwitches) t.setChecked(false); seekSubGain.setProgress(0); spinnerSubFreq.setSelection(5);
            seekFaderLr.setProgress(12); seekFaderFr.setProgress(12); updateFaderLabels(); switchLoud.setChecked(false);
            switchFmEnable.setChecked(false); switchFatigueEnable.setChecked(false); switchFmSubComp.setChecked(false);
            seekFmCalVol.setProgress(25); seekFmStrength.setProgress(100);
            
            // GALA reset
            switchGalaEnable.setChecked(false);
            seekGalaInc.setProgress(15);
            seekGalaMinSpeed.setProgress(0);
            seekGalaMaxSpeed.setProgress(30);
            seekGalaMaxAdj.setProgress(12);
        }
        isUpdatingUi = false; updateVisualizer(); updateFmVisualizer();
    }
}
