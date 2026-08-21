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
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;
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
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;
import androidx.appcompat.widget.SwitchCompat;

import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.slider.LabelFormatter;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.android.material.slider.Slider;
import com.radiorubka.wdsp.ui.theme.ThemeManager;

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
    private static final String PREF_GALA_GLOBAL_ENABLED = "gala_global_enabled";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<Slider> gainSliders = new ArrayList<>();
    private final List<ToggleButton> qSwitches = new ArrayList<>();
    private final List<TextView> dbLabels = new ArrayList<>();
    private final List<TextView> freqLabels = new ArrayList<>();
    private AutoCompleteTextView spinnerPresets;
    private EqVisualizerView eqVisualizer;
    private SpectrumAnalyzerView spectrumAnalyzer;

    private Slider seekSubGain;
    private AutoCompleteTextView spinnerSubFreq;
    private TextView tvSubDb;

    private TextView tvPowerDb;
    private final String[] SUB_FREQS_RAW = {"25", "32", "40", "50", "63", "80", "100", "125", "160", "200", "250"};
    /**
     * Built at runtime from SUB_FREQS_RAW and the localised hertz unit. It used to be a literal
     * array of Ukrainian strings, which meant the subwoofer dropdown stayed Ukrainian in all
     * thirty locales - and worse, the parsing code compares the spinner's text against these
     * entries, so a translated build would have failed to match at all.
     */
    private final String[] SUB_FREQS = new String[SUB_FREQS_RAW.length];

    // Filter controls
    private Slider seekBassFilterFront, seekBassBoostFront, seekBassFilterRear, seekBassBoostRear;
    private TextView tvBassFilterFrontVal, tvBassBoostFrontDb, tvBassFilterRearVal, tvBassBoostRearDb;
    private AutoCompleteTextView spinnerBassFreqFront, spinnerBassFreqRear;
    private final String[] BASS_FILTER_FREQS = {"20", "25", "31", "40", "50", "63", "80", "100", "125", "160", "200", "250"};
    private final String[] BASS_BOOST_FREQS = {"off", "54", "68", "86", "108", "134", "172", "214"};

    // Fader & Delays
    private Slider seekFaderLr;
    private Slider seekFaderFr;
    private BalancePointerView balancePointer;
    private TextView tvFaderLrLeftVal, tvFaderLrRightVal, tvFaderFrFrontVal, tvFaderFrRearVal;
    private MaterialButton switchLoud;
    private Slider seekDelayFl, seekDelayFr, seekDelayRl, seekDelayRr, seekDelaySub;
    private Slider seekDelay1Fl, seekDelay1Fr, seekDelay1Rl, seekDelay1Rr, seekDelay1RSSE;
    private MaterialButton switchPreciseEnable, switchLegacyEnable;
    private TextView tvDelayFlVal, tvDelayFrVal, tvDelayRlVal, tvDelayRrVal, tvDelaySubVal;
    private TextView tvDelay1FlVal, tvDelay1FrVal, tvDelay1RlVal, tvDelay1RrVal, tvDelay1RSSEVal;

    // F-M Curve
    private MaterialButton switchFmEnable, switchFatigueEnable, switchFmSubComp;
    private Slider seekFmCalVol, seekFmStrength;
    private TextView tvFmCalVolVal, tvFmStrengthVal, tvSysVolumeVal, tvSubOffsetVal, tvSubOffsetWarn;
    private FmVisualizerView fmVisualizer;
    
    // GALA Controls
    private MaterialButton switchGalaEnable, switchGalaGlobal;
    private Slider seekGalaInc, seekGalaMinSpeed, seekSimulateSpeed, seekGalaMaxAdj;
    private Slider seekGalaFadeMs, seekGalaHoldMs;
  
    private TextView tvGalaIncVal, tvGalaSpeed, tvGalaMinSpeedVal, tvGalaOffset, tvSimulateSpeedVal, tvGalaMaxAdjVal, tvGalaFadeMsVal, tvGalaHoldMsVal;

    // Whether GALA's on/off state is shared across all presets instead of per-preset.
    // Kept in sync with PREF_GALA_GLOBAL_MODE; see setupGalaControls()/savePreset()/loadPreset().
    private boolean galaGlobalMode = false;

    private float currentFmSubOffset = 0f;
    private int currentEffectiveVolume = -1;

    private ArrayAdapter<String> presetAdapter;
    private List<String> presetNames;
    private int accentColor;
    private boolean isUpdatingUi = false;
    private boolean isFullyInitialized = false;

    private String defaultPreset;

    private Method getPropMethod;
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
                    Toaster.show(MainActivity.this, getString(R.string.toast_auto_preset, name));
                    spinnerPresets.setText(name, false);
                    loadPreset(name);
                }
            }
            else if ("com.radiorubka.wdsp.VOLUME_CHANGED".equals(action)) {
                currentEffectiveVolume = intent.getIntExtra("volume", -1);
                if (isFullyInitialized && findViewById(R.id.layout_fm_curve).getVisibility() == View.VISIBLE) {
                    updateFmVisualizer();
                }
            }
            else if ("com.radiorubka.wdsp.GALA_UPDATE".equals(action)) {
//                Log.e("MainActivity", "RECEIVED GALA UPDATE INTENT");
                float speed = intent.getFloatExtra("speed", 0.0f);
                int offset = intent.getIntExtra("waveOffset", 0);
                if (tvGalaSpeed != null) tvGalaSpeed.setText(String.format(Locale.getDefault(), "%.1f km/h", speed));
                if (tvGalaOffset != null) tvGalaOffset.setText(String.format(Locale.getDefault(), "+%d", offset));
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
            else if ("com.radiorubka.wdsp.SETTINGS_RESTORED".equals(action)) {
                Log.i("MainActivity", "SETTINGS_RESTORED received, reloading all UI components");
                applyAppTheme();
                setupPresets();
                refreshAllUiValues();
                SelectTab();
                if (spectrumAnalyzer != null) {
                    spectrumAnalyzer.invalidate();
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

        for (int i = 0; i < SUB_FREQS_RAW.length; i++) {
            SUB_FREQS[i] = getString(R.string.unit_hz, SUB_FREQS_RAW[i]);
        }

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
                Toaster.show(this, getString(R.string.toast_location_permission_needed));
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTargetTab(intent);
    }

    private void handleTargetTab(Intent intent) {
        if (intent == null) return;
        int targetTabId = intent.getIntExtra("target_tab_id", -1);
        if (targetTabId != -1) {
            BottomNavigationView bn = findViewById(R.id.bottom_navigation);
            if (bn != null) {
                bn.setSelectedItemId(targetTabId);
            }
            intent.removeExtra("target_tab_id");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyAppTheme();
        handleTargetTab(getIntent());
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
            String current = prefs.getString("last_selected_preset", defaultPreset);
            int index = presetNames.indexOf(current);
            if (index >= 0 && index < presetNames.size()) {
                String newName = presetNames.get(index);
                spinnerPresets.setText(newName, false);
                loadPreset(newName);
            }
        }
        updateVisualizer();
        applyAppTheme();
        checkAndStartSpectrumAnalyzer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sendBroadcast(new Intent("com.radiorubka.wdsp.UI_INACTIVE").setPackage(getPackageName()));
        if (spectrumAnalyzer != null) spectrumAnalyzer.stop();
    }

    private void tintSlider(Slider s, ColorStateList csl, ColorStateList cslTrack) {
        if (s == null) return;
        float density = getResources().getDisplayMetrics().density;
        s.setThumbTintList(csl);
        s.setTrackActiveTintList(csl);
        s.setTrackInactiveTintList(ColorStateList.valueOf(Color.parseColor("#26FFFFFF")));
        s.setHaloRadius(0);
        s.setTrackHeight((int) (5 * density));
        s.setThumbRadius((int) (10 * density));
        s.setThumbWidth((int) (20 * density));
        s.setThumbHeight((int) (20 * density));
        s.setTrackStopIndicatorSize(0);
        s.setLabelBehavior(LabelFormatter.LABEL_GONE);
    }

    private void updateToggleStyle(View v) {
        if (v == null) return;
        int accent = com.radiorubka.wdsp.ui.theme.ThemeManager.accent(this);
        int onAccent = com.radiorubka.wdsp.ui.theme.ThemeManager.getContrastingTextColor(accent);
        int textPrimary = com.radiorubka.wdsp.ui.theme.ThemeManager.textPrimary(this);
        int border = com.radiorubka.wdsp.ui.theme.ThemeManager.panelBorder(this);

        boolean isNight = com.radiorubka.wdsp.ui.theme.ThemeManager.isNight(this);
        int unselectedBg = isNight ? Color.parseColor("#25FFFFFF") : Color.parseColor("#18000000");
        int unselectedBorder = isNight ? Color.parseColor("#4DFFFFFF") : Color.parseColor("#4D000000");
        int onAccentColor = com.radiorubka.wdsp.ui.theme.ThemeManager.getContrastingTextColor(accent);

        if (v instanceof MaterialButton) {
            MaterialButton mb = (MaterialButton) v;
            boolean checked = mb.isChecked();
            if (checked) {
                mb.setBackgroundTintList(ColorStateList.valueOf(accent));
                mb.setTextColor(onAccentColor);
                mb.setStrokeColor(ColorStateList.valueOf(accent));
            } else {
                mb.setBackgroundTintList(ColorStateList.valueOf(unselectedBg));
                mb.setTextColor(textPrimary);
                mb.setStrokeColor(ColorStateList.valueOf(unselectedBorder));
            }
            return;
        }

        if (v instanceof ToggleButton) {
            ToggleButton tb = (ToggleButton) v;
            boolean checked = tb.isChecked();
            float density = getResources().getDisplayMetrics().density;
            int radius = (int) (6 * density);
            if (checked) {
                tb.setBackground(com.radiorubka.wdsp.ui.theme.ThemeManager.roundedDrawable(this, radius, accent, accent, 1.2f));
                tb.setTextColor(onAccentColor);
            } else {
                tb.setBackground(com.radiorubka.wdsp.ui.theme.ThemeManager.roundedDrawable(this, radius, unselectedBg, unselectedBorder, 1.2f));
                tb.setTextColor(textPrimary);
            }
            return;
        }

        if (v instanceof CompoundButton) {
            CompoundButton toggle = (CompoundButton) v;
            boolean checked = toggle.isChecked();
            float density = getResources().getDisplayMetrics().density;
            int radius = (int) (10 * density);
            toggle.setPadding((int) (14 * density), (int) (6 * density), (int) (14 * density), (int) (6 * density));
            toggle.setGravity(Gravity.CENTER);
            if (checked) {
                toggle.setBackground(com.radiorubka.wdsp.ui.theme.ThemeManager.roundedDrawable(this, radius, accent, accent, 1.2f));
                toggle.setTextColor(onAccentColor);
            } else {
                toggle.setBackground(com.radiorubka.wdsp.ui.theme.ThemeManager.roundedDrawable(this, radius, unselectedBg, unselectedBorder, 1.2f));
                toggle.setTextColor(textPrimary);
            }
        }
    }

    private void applyAppTheme() {
        try {
            Drawable wallpaper = com.radiorubka.wdsp.ui.theme.ThemeManager.wallpaperBackground(this);
            View mainView = findViewById(R.id.main);
            if (mainView != null) {
                mainView.setBackground(wallpaper);
            }
            View root = getWindow().getDecorView();
            if (root != null) {
                root.setBackground(wallpaper);
            }

            int accent = com.radiorubka.wdsp.ui.theme.ThemeManager.accent(this);
            int onAccent = com.radiorubka.wdsp.ui.theme.ThemeManager.onAccent(this);
            int primaryText = com.radiorubka.wdsp.ui.theme.ThemeManager.textPrimary(this);
            int secondaryText = com.radiorubka.wdsp.ui.theme.ThemeManager.textSecondary(this);
            int border = com.radiorubka.wdsp.ui.theme.ThemeManager.panelBorder(this);
            float density = getResources().getDisplayMetrics().density;

            ColorStateList csl = ColorStateList.valueOf(accent);
            ColorStateList cslTrack = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 70));

            // Dynamically tint all 16 EQ sliders and labels
            for (Slider s : gainSliders) {
                tintSlider(s, csl, cslTrack);
            }
            for (TextView db : dbLabels) {
                if (db != null) {
                    db.setTextColor(accent);
                }
            }
            for (TextView l : freqLabels) {
                if (l != null) {
                    l.setTextColor(secondaryText);
                }
            }

            // Q factor toggles
            for (ToggleButton q : qSwitches) {
                if (q != null) {
                    updateToggleStyle(q);
                }
            }

            // Sub gain
            tintSlider(seekSubGain, csl, cslTrack);

            // Filters & Fader
            tintSlider(seekBassFilterFront, csl, cslTrack);
            tintSlider(seekBassBoostFront, csl, cslTrack);
            tintSlider(seekBassFilterRear, csl, cslTrack);
            tintSlider(seekBassBoostRear, csl, cslTrack);
            tintSlider(seekFaderLr, csl, cslTrack);
            tintSlider(seekFaderFr, csl, cslTrack);

            // Delays
            tintSlider(seekDelayFl, csl, cslTrack);
            tintSlider(seekDelayFr, csl, cslTrack);
            tintSlider(seekDelayRl, csl, cslTrack);
            tintSlider(seekDelayRr, csl, cslTrack);
            tintSlider(seekDelaySub, csl, cslTrack);
            tintSlider(seekDelay1Fl, csl, cslTrack);
            tintSlider(seekDelay1Fr, csl, cslTrack);
            tintSlider(seekDelay1Rl, csl, cslTrack);
            tintSlider(seekDelay1Rr, csl, cslTrack);
            tintSlider(seekDelay1RSSE, csl, cslTrack);

            // F-M Curve
            tintSlider(seekFmCalVol, csl, cslTrack);
            tintSlider(seekFmStrength, csl, cslTrack);

            // GALA
            tintSlider(seekGalaInc, csl, cslTrack);
            tintSlider(seekGalaMinSpeed, csl, cslTrack);
            tintSlider(seekSimulateSpeed, csl, cslTrack);
            tintSlider(seekGalaMaxAdj, csl, cslTrack);
            tintSlider(seekGalaFadeMs, csl, cslTrack);
            tintSlider(seekGalaHoldMs, csl, cslTrack);

            // Style all toggle buttons
            updateToggleStyle(switchLoud);
            updateToggleStyle(switchPreciseEnable);
            updateToggleStyle(switchLegacyEnable);
            updateToggleStyle(switchFmEnable);
            updateToggleStyle(switchFatigueEnable);
            updateToggleStyle(switchFmSubComp);
            updateToggleStyle(switchGalaEnable);
            updateToggleStyle(switchGalaGlobal);

            // Spinners
            ThemeManager.tintTextInputLayout(findViewById(R.id.layout_spinner_presets), spinnerPresets, accent, secondaryText);
            ThemeManager.tintTextInputLayout(findViewById(R.id.layout_spinner_sub_freq), spinnerSubFreq, accent, secondaryText);
            ThemeManager.tintTextInputLayout(findViewById(R.id.layout_spinner_bass_freq_front), spinnerBassFreqFront, accent, secondaryText);
            ThemeManager.tintTextInputLayout(findViewById(R.id.layout_spinner_bass_freq_rear), spinnerBassFreqRear, accent, secondaryText);

            // Primary Section Titles & Headers
            int[] primaryTitles = {
                R.id.tv_app_logo_title, R.id.tv_fm_title, R.id.tv_delays_title, R.id.tv_gala_title
            };
            for (int id : primaryTitles) {
                TextView tv = findViewById(id);
                if (tv != null) tv.setTextColor(primaryText);
            }

            // Accent Values
            boolean isNight = com.radiorubka.wdsp.ui.theme.ThemeManager.isNight(this);
            int valueColor = isNight ? accent : primaryText;
            int faderIconTint = isNight ? accent : primaryText;
            int[] accentValues = {
                R.id.tv_pwr_db, R.id.tv_sub_db,
                R.id.tv_fm_cal_vol_val, R.id.tv_fm_strength_val, R.id.tv_sys_volume_val, R.id.tv_sub_offset_val, R.id.tv_sub_offset_warn,
                R.id.tv_delay_fl_val, R.id.tv_delay_fr_val, R.id.tv_delay_rl_val, R.id.tv_delay_rr_val, R.id.tv_delay_sub_val,
                R.id.tv_delay1_fl_val, R.id.tv_delay1_fr_val, R.id.tv_delay1_rl_val, R.id.tv_delay1_rr_val, R.id.tv_delay1_rsse_val,
                R.id.tv_bass_filter_front_db, R.id.tv_bass_boost_front_db, R.id.tv_bass_filter_rear_db, R.id.tv_bass_boost_rear_db,
                R.id.tv_fader_fr_front_val, R.id.tv_fader_fr_rear_val, R.id.tv_fader_lr_left_val, R.id.tv_fader_lr_right_val,
                R.id.tv_gala_increment_val, R.id.tv_gala_speed, R.id.tv_gala_minspeed_val, R.id.tv_gala_offset,
                R.id.tv_gala_max_adj_val, R.id.tv_gala_fade_ms_val, R.id.tv_gala_hold_ms_val, R.id.tv_simulate_speed_val
            };
            for (int id : accentValues) {
                TextView tv = findViewById(id);
                if (tv != null) tv.setTextColor(valueColor);
            }

            // Secondary Labels
            int[] secondaryLabels = {
                R.id.lbl_pwr, R.id.lbl_sub, R.id.lbl_cal_vol, R.id.lbl_strength
            };
            for (int id : secondaryLabels) {
                TextView tv = findViewById(id);
                if (tv != null) tv.setTextColor(secondaryText);
            }

            // Fader Buttons
            int[] faderArrows = {
                R.id.btn_fader_fr_plus, R.id.btn_fader_fr_minus,
                R.id.btn_fader_lr_plus, R.id.btn_fader_lr_minus
            };
            for (int id : faderArrows) {
                ImageView iv = findViewById(id);
                if (iv != null) iv.setImageTintList(ColorStateList.valueOf(faderIconTint));
            }

            // Power / Sub Buttons
            int[] subButtons = {
                R.id.btn_pwr_vol_plus, R.id.btn_pwr_vol_minus,
                R.id.btn_plus, R.id.btn_minus
            };
            for (int id : subButtons) {
                View btn = findViewById(id);
                if (btn instanceof TextView) {
                    ((TextView) btn).setTextColor(valueColor);
                }
            }

            // Bottom Navigation Bar
            BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
            if (bottomNav != null) {
                ColorStateList navCsl = ThemeManager.bottomNavColorStateList(this);
                bottomNav.setItemIconTintList(navCsl);
                bottomNav.setItemTextColor(navCsl);
                bottomNav.setItemActiveIndicatorColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 40)));
            }

            ImageView carView = findViewById(R.id.imageView);
            if (carView != null) {
                carView.setImageResource(isNight ? R.drawable.ic_car_cabriolet_night : R.drawable.ic_car_cabriolet_day);
            }

            if (eqVisualizer != null) eqVisualizer.invalidate();
            if (spectrumAnalyzer != null) spectrumAnalyzer.invalidate();
        } catch (Exception ignored) {
        }
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
            com.radiorubka.wdsp.ui.ThemedDialog.show(
                    com.radiorubka.wdsp.ui.ThemedDialog.builder(this)
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
                    .setNegativeButton(R.string.btn_later, null));
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
        filter.addAction("com.radiorubka.wdsp.SETTINGS_RESTORED");

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
        checkStatusBarHeightCalibration();

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

        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_minus));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_plus));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_center));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_pwr_vol_minus));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_pwr_vol_plus));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_fader_lr_minus));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_fader_lr_plus));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_fader_fr_minus));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_fader_fr_plus));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_apply));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_add_preset));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_rename_preset));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_delete_preset));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_export_presets));
        com.radiorubka.wdsp.ui.theme.TouchGlow.attach(findViewById(R.id.btn_import_presets));
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
        switchGalaGlobal = findViewById(R.id.switch_gala_global);
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
        freqLabels.clear();
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
            q.setTextColor(ContextCompat.getColor(this, R.color.text_theme_aware_2));
            q.setBackgroundColor(Color.TRANSPARENT);
            q.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            q.setPadding(0, 0, 0, 0); q.setMinimumHeight(0); q.setMinimumWidth(0);
            q.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.07f));
            updateToggleStyle(q);
            q.setOnCheckedChangeListener((bv, checked) -> {
                updateToggleStyle(bv);
                if (!isUpdatingUi) {
                    updateVisualizer();
                    autoSaveCurrent();
                }
            });

            db.setText("0");
            db.setTextColor(accentColor);
            db.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            db.setTypeface(null, Typeface.BOLD);
            db.setGravity(Gravity.CENTER);
            db.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.07f));

            TextView label = new TextView(this);
            label.setText(AudioConfig.BAND_LABELS[i]);
            label.setTextColor(com.radiorubka.wdsp.ui.theme.ThemeManager.textSecondary(this));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            label.setGravity(Gravity.CENTER);
            label.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.08f));
            freqLabels.add(label);

            s.setValueFrom(0f);
            s.setValueTo(12f);
            s.setStepSize(1f);
            float density = getResources().getDisplayMetrics().density;
            s.setThumbHeight((int) (20 * density));
            s.setThumbWidth((int) (20 * density));
            s.setThumbRadius((int) (10 * density));
            s.setHaloRadius(0);
            s.setHaloTintList(ColorStateList.valueOf(Color.TRANSPARENT));
            s.setThumbTintList(ColorStateList.valueOf(accentColor));
            s.setTrackActiveTintList(ColorStateList.valueOf(accentColor));
            s.setTrackInactiveTintList(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accentColor, 70)));
            s.setTrackHeight((int) (5 * density));
            s.setRotation(270f);
            s.setTrackStopIndicatorSize(0);
            s.setLabelBehavior(LabelFormatter.LABEL_GONE);

            FrameLayout seekBox = new FrameLayout(this);
            seekBox.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 0.78f));
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
            seekBox.addView(s);
            layout.addView(seekBox);
            layout.addView(label);
            container.addView(layout);
            updateDbLabel(i, 6);
        }
    }

    private void updateVisualizer() {
        if (eqVisualizer == null || gainSliders.size() < AudioConfig.NUM_BANDS) return;
        int[] gs = new int[AudioConfig.NUM_BANDS];
        boolean[] qn = new boolean[AudioConfig.NUM_BANDS];
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            gs[i] = getIntSlider(gainSliders.get(i));
            if (i < qSwitches.size()) {
                qn[i] = qSwitches.get(i).isChecked();
            }
        }
        eqVisualizer.setGains(gs);
        float[] offs = calculateFmOffsets();
        AudioSpectrumEngine engine = AudioSpectrumEngine.getInstance();
        engine.setGains(gs);
        engine.setQFactors(qn);
        engine.setFmOffsets(offs);
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
        // 1. Create the adapter (using themed QFRadio-styled dropdown layout)
        ArrayAdapter<String> subAdapter = new ThemeManager.ThemedDropdownAdapter<>(
                this,
                SUB_FREQS
        );
        spinnerSubFreq.setAdapter(subAdapter);

        // 2. Set the initial text (replaces setSelection)
        // 'false' is critical here to prevent the dropdown from opening or filtering
        spinnerSubFreq.setText(SUB_FREQS[5], false);
        Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS_RAW[5]);

        // 3. Change OnItemSelectedListener to OnItemClickListener
        spinnerSubFreq.setOnItemClickListener((parent, view, pos, id) -> {
            // Logic for Sub Comp limit (if FM Sub Comp is on, limit to 80Hz/Index 5)
            if (isFullyInitialized && switchFmSubComp.isChecked() && pos > 5) {
                // Revert the text back to 80Hz (Index 5)
                spinnerSubFreq.setText(SUB_FREQS[5], false);
                Toaster.show(MainActivity.this, getString(R.string.toast_sub_comp_limit));

                // Re-sync Global just in case
                Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS_RAW[5]);
                return;
            }

            if (!isUpdatingUi) {
                autoSaveCurrent();
            }

            // Update the global value for other calculations
            Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS_RAW[pos]);
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
        ArrayAdapter<String> bbAdapter = new ThemeManager.ThemedDropdownAdapter<>(this, BASS_BOOST_FREQS);

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
        updateToggleStyle(switchLoud);
        switchLoud.addOnCheckedChangeListener((bv, checked) -> {
            updateToggleStyle(bv);
            if (!isUpdatingUi) {
                autoSaveCurrent();
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
        updateToggleStyle(switchPreciseEnable);
        switchPreciseEnable.addOnCheckedChangeListener((bv, checked) -> {
            updateToggleStyle(bv);
            if (!isUpdatingUi) {
                if (checked) switchLegacyEnable.setChecked(false);
                autoSaveCurrent();
            }
        });
    }

    /**
     * What one step of a Surround delay slider is really worth, in milliseconds.
     *
     * <p>Not one millisecond, which is what these sliders said for as long as the app has existed.
     * The MCU takes the slider value from the {@code 0x89} frame and multiplies it by 102 before it
     * reaches the sound processor, and the processor counts delay in samples at 48 kHz - the ROHM
     * datasheet gives the rule outright, "send data = time in ms x 48". So a step is 102/48 =
     * 2.125 ms, and the ten steps the slider offers cover the 21.3 ms the chip can do, not 10 ms.
     *
     * <p>Measured on a head unit to be sure, by holding the routing still and moving this delay
     * line between sweeps: 3 steps shifted the arrival by 6.354 ms, 6 steps by 12.688 ms, 10 steps
     * by 21.167 ms. That is 2.117 ms per step, four parts in a thousand from the arithmetic, and
     * nowhere near the 1.0 that was printed.
     *
     * <p>Only the label was wrong; the sliders always did this. So nothing about a saved preset
     * changes - the same setting produces the same sound as before, and now says so honestly.
     *
     * <p>The positional delays ({@code _d_*}, command {@code 0x8C}) are a different line with a
     * different scale, half a millisecond per step, and that one was measured to be correct.
     */
    private static final float SURROUND_DELAY_STEP_MS = 102f / 48f;

    private void setupDelay1Controls() {
        Slider.OnChangeListener dl = (slider, value, fromUser) -> {
            int p = (int) value;
            if (slider == seekDelay1RSSE) { 
                int v = p - 10; 
                String text = (v > 0 ? "+" : "") + v;
                tvDelay1RSSEVal.setText(text); 
            }
            else { float ms = p * SURROUND_DELAY_STEP_MS; String val = String.format(Locale.getDefault(), getString(R.string.delay_value_format), ms, Math.round(ms * 34.3f));
                if (slider == seekDelay1Fl) tvDelay1FlVal.setText(val); else if (slider == seekDelay1Fr) tvDelay1FrVal.setText(val);
                else if (slider == seekDelay1Rl) tvDelay1RlVal.setText(val); else if (slider == seekDelay1Rr) tvDelay1RrVal.setText(val);
            }
            if (fromUser && !isUpdatingUi) autoSaveCurrent();
        };
        seekDelay1Fl.addOnChangeListener(dl); seekDelay1Fr.addOnChangeListener(dl);
        seekDelay1Rl.addOnChangeListener(dl); seekDelay1Rr.addOnChangeListener(dl); seekDelay1RSSE.addOnChangeListener(dl);
        updateToggleStyle(switchLegacyEnable);
        switchLegacyEnable.addOnCheckedChangeListener((bv, checked) -> {
            updateToggleStyle(bv);
            if (!isUpdatingUi) {
                if (checked) switchPreciseEnable.setChecked(false);
                autoSaveCurrent();
            }
        });
    }

    private void setupFmControls() {
        updateToggleStyle(switchFmEnable);
        switchFmEnable.addOnCheckedChangeListener((bv, checked) -> {
            updateToggleStyle(bv);
            if (!isUpdatingUi) {
                autoSaveCurrent();
                updateFmVisualizer();
            }
        });
        updateToggleStyle(switchFatigueEnable);
        switchFatigueEnable.addOnCheckedChangeListener((bv, checked) -> {
            updateToggleStyle(bv);
            if (!isUpdatingUi) {
                autoSaveCurrent();
                updateFmVisualizer();
            }
        });
        updateToggleStyle(switchFmSubComp);
        switchFmSubComp.addOnCheckedChangeListener((bv, checked) -> {
            updateToggleStyle(bv);
            if (!isUpdatingUi) {
                int idx = java.util.Arrays.asList(SUB_FREQS).indexOf(spinnerSubFreq.getText().toString());
                if (idx < 0) idx = java.util.Arrays.asList(SUB_FREQS_RAW).indexOf(spinnerSubFreq.getText().toString());
                if (checked && idx > 5) {
                    spinnerSubFreq.setText(SUB_FREQS[5], false);
                    Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS_RAW[5]);
                }
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
        AudioSpectrumEngine.getInstance().setFmOffsets(offs);
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
        if (seekFmCalVol == null || seekFmStrength == null || switchFmEnable == null || switchFatigueEnable == null) {
            return offs;
        }
        int vol = Math.max(1, (currentEffectiveVolume != -1) ? currentEffectiveVolume : getSystemVolume());
        int cal = getIntSlider(seekFmCalVol); float str = getIntSlider(seekFmStrength) / 100f;
        if (vol < cal && switchFmEnable.isChecked()) {
            float ratio = (float)(cal - vol) / (float)(cal - 1);
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) offs[i] = AudioConfig.ISO_MAX_OFFSETS[i] * ratio * str;
            if (switchFmSubComp != null && switchFmSubComp.isChecked()) {
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

    private void checkStatusBarHeightCalibration() {
        SharedPreferences p = ThemeManager.prefs(this);
        if (p.getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_HEIGHT_PX, 0) == 0) {
            View decorView = getWindow().getDecorView();
            decorView.post(() -> {
                Rect rect = new Rect();
                decorView.getWindowVisibleDisplayFrame(rect);
                if (rect.top > 0) {
                    StatusBarVisualizerManager.getInstance(this).updateStatusBarHeight(rect.top);
                }
            });
        }
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

        // Use themed QFRadio-styled layout for preset items
        presetAdapter = new ThemeManager.ThemedDropdownAdapter<>(this, presetNames);
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
            Toaster.show(this, getString(R.string.error));
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
        com.radiorubka.wdsp.ui.ThemedDialog.show(
                com.radiorubka.wdsp.ui.ThemedDialog.builder(this)
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
                .setNegativeButton(R.string.btn_cancel, null));
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
        String[] keys = {"_sub_g", "_sub_f", "_bf_f", "_bb_f", "_bf_r", "_bb_r", "_bb_frq_f", "_bb_frq_r", "_f_lr", "_f_fr", "_loud", "_fm_en", "_fat_en", "_sub_comp", "_fm_cal", "_fm_str", "_d_fl", "_d_fr", "_d_rl", "_d_rr", "_d_sub", "_d_en", "_d1_fl", "_d1_fr", "_d1_rl", "_d1_rr", "_rsse_val", "_d1_en", "_gala_enabled", "_gala_increment", "_gala_min_speed", "_gala_max_speed", "_gala_max_adj", "_gala_fade_ms", "_gala_hold_ms", "_power_vol"};
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

    private int resolveSubFreqIndex(String text) {
        if (text == null || text.trim().isEmpty()) return 5;
        String trimmed = text.trim();
        int idx = java.util.Arrays.asList(SUB_FREQS).indexOf(trimmed);
        if (idx >= 0) return idx;
        idx = java.util.Arrays.asList(SUB_FREQS_RAW).indexOf(trimmed);
        if (idx >= 0) return idx;
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
            idx = java.util.Arrays.asList(SUB_FREQS_RAW).indexOf(digits);
            if (idx >= 0) return idx;
        }
        return 5;
    }

    private int resolveBassBoostFreqIndex(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        String trimmed = text.trim();
        int idx = java.util.Arrays.asList(BASS_BOOST_FREQS).indexOf(trimmed);
        if (idx >= 0) return idx;
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
            idx = java.util.Arrays.asList(BASS_BOOST_FREQS).indexOf(digits);
            if (idx >= 0) return idx;
        }
        return 0;
    }

    private int parsePowerDb() {
        if (tvPowerDb == null) return 0;
        String text = tvPowerDb.getText().toString().replace("+", "").trim();
        try {
            return -Integer.parseInt(text);
        } catch (Exception e) {
            return 0;
        }
    }

    private void savePreset(String name) {
        if (name == null || name.trim().isEmpty()) return;
        SharedPreferences.Editor e = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            e.putInt(name + "_g" + i, getIntSlider(gainSliders.get(i)));
            e.putBoolean(name + "_q" + i, qSwitches.get(i).isChecked());
        }
        
        int subFreqIdx = resolveSubFreqIndex(spinnerSubFreq != null ? spinnerSubFreq.getText().toString() : "");
        int subGain = seekSubGain != null ? getIntSlider(seekSubGain) : 0;
        e.putInt(name + "_sub_g", subGain);
        e.putInt(name + "_sub_f", subFreqIdx);

        int powerVal = parsePowerDb();
        e.putInt(name + "_power_vol", powerVal);

        if (isFullyInitialized) {
            e.putInt(name + "_bf_f", getIntSlider(seekBassFilterFront));
            e.putInt(name + "_bb_f", getIntSlider(seekBassBoostFront));
            e.putInt(name + "_bf_r", getIntSlider(seekBassFilterRear));
            e.putInt(name + "_bb_r", getIntSlider(seekBassBoostRear));

            int frontFreqIdx = resolveBassBoostFreqIndex(spinnerBassFreqFront.getText().toString());
            int rearFreqIdx = resolveBassBoostFreqIndex(spinnerBassFreqRear.getText().toString());
            e.putInt(name + "_bb_frq_f", frontFreqIdx);
            e.putInt(name + "_bb_frq_r", rearFreqIdx);

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
            if (galaGlobalMode) {
                // Shared across all presets - not part of this preset's own data.
                e.putBoolean(PREF_GALA_GLOBAL_ENABLED, switchGalaEnable.isChecked());
            } else {
                e.putBoolean(name + "_gala_enabled", switchGalaEnable.isChecked());
            }
            e.putInt(name + "_gala_increment", getIntSlider(seekGalaInc));
            e.putInt(name + "_gala_min_speed", getIntSlider(seekGalaMinSpeed));
            e.putInt(name + "_gala_max_adj", getIntSlider(seekGalaMaxAdj));
            e.putInt(name + "_gala_fade_ms", getIntSlider(seekGalaFadeMs));
            e.putInt(name + "_gala_hold_ms", getIntSlider(seekGalaHoldMs));
        }
        e.apply();
    }

    private void loadPreset(String name) {
        isUpdatingUi = true;
        SharedPreferences p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            int g = p.getInt(name + "_g" + i, 6);
            gainSliders.get(i).setValue((float) g);
            updateDbLabel(i, g);
            qSwitches.get(i).setChecked(p.getBoolean(name + "_q" + i, false));
        }

        // Subwoofer Gain & Frequency
        int sg = p.getInt(name + "_sub_g", 0);
        seekSubGain.setValue((float) Math.max(0, Math.min(12, sg)));
        String subText = "+" + sg;
        tvSubDb.setText(subText);

        int subFreqIdx = p.getInt(name + "_sub_f", 5);
        if (subFreqIdx < 0 || subFreqIdx >= SUB_FREQS.length) {
            subFreqIdx = 5;
        }
        spinnerSubFreq.setText(SUB_FREQS[subFreqIdx], false);
        Globals.currentSubFreqHz = Integer.parseInt(SUB_FREQS_RAW[subFreqIdx]);

        // Power Volume
        int powerVal = p.getInt(name + "_power_vol", 0);
        tvPowerDb.setText(String.valueOf(-powerVal));

        if (isFullyInitialized) {
            seekBassFilterFront.setValue((float) p.getInt(name + "_bf_f", 0));
            seekBassBoostFront.setValue((float) p.getInt(name + "_bb_f", 0));
            seekBassFilterRear.setValue((float) p.getInt(name + "_bf_r", 0));
            seekBassBoostRear.setValue((float) p.getInt(name + "_bb_r", 0));

            int frontIdx = p.getInt(name + "_bb_frq_f", 0);
            int rearIdx = p.getInt(name + "_bb_frq_r", 0);
            if (frontIdx < 0 || frontIdx >= BASS_BOOST_FREQS.length) frontIdx = 0;
            if (rearIdx < 0 || rearIdx >= BASS_BOOST_FREQS.length) rearIdx = 0;

            spinnerBassFreqFront.setText(BASS_BOOST_FREQS[frontIdx], false);
            spinnerBassFreqRear.setText(BASS_BOOST_FREQS[rearIdx], false);

            seekFaderLr.setValue((float) p.getInt(name + "_f_lr", 12));
            seekFaderFr.setValue((float) p.getInt(name + "_f_fr", 12));
            updateFaderLabels();
            switchLoud.setChecked(p.getBoolean(name + "_loud", false));
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
            switchGalaEnable.setChecked(galaGlobalMode
                    ? p.getBoolean(PREF_GALA_GLOBAL_ENABLED, false)
                    : p.getBoolean(name + "_gala_enabled", false));
            seekGalaInc.setValue((float) p.getInt(name + "_gala_increment", 15));
            tvGalaIncVal.setText(getString(R.string.speed_kmh_format, getIntSlider(seekGalaInc) + 5));
            seekGalaMinSpeed.setValue((float) p.getInt(name + "_gala_min_speed", 0));
            tvGalaMinSpeedVal.setText(getString(R.string.speed_kmh_format, getIntSlider(seekGalaMinSpeed) * 5));
            seekGalaMaxAdj.setValue((float) p.getInt(name + "_gala_max_adj", 12));
            tvGalaMaxAdjVal.setText(String.valueOf(getIntSlider(seekGalaMaxAdj)));
            seekGalaFadeMs.setValue((float) p.getInt(name + "_gala_fade_ms", 100));
            tvGalaFadeMsVal.setText(getString(R.string.gala_ms_fmt, getIntSlider(seekGalaFadeMs)));
            seekGalaHoldMs.setValue((float) p.getInt(name + "_gala_hold_ms", 1000));
            tvGalaHoldMsVal.setText(String.format(Locale.getDefault(), getString(R.string.gala_s_fmt), getIntSlider(seekGalaHoldMs) / 1000f));
        }
        isUpdatingUi = false;
        updateVisualizer();
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
            else if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return false;
            }

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

        com.radiorubka.wdsp.ui.ThemedDialog.show(
                com.radiorubka.wdsp.ui.ThemedDialog.builder(this)
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
                }));
    }

    private void setupGalaControls() {
        updateToggleStyle(switchGalaEnable);
        switchGalaEnable.addOnCheckedChangeListener((bv, checked) -> { 
            updateToggleStyle(bv);
            if (!isUpdatingUi) { autoSaveCurrent(); } 
        });

        // Global GALA: not tied to any preset, so it's loaded/wired once here rather than
        // in loadPreset(). When on, switchGalaEnable's on/off state is shared across every
        // preset (saved/read from PREF_GALA_GLOBAL_ENABLED instead of a per-preset key) -
        // see the GALA sections of savePreset()/loadPreset().
        updateToggleStyle(switchGalaGlobal);
        SharedPreferences galaPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        galaGlobalMode = galaPrefs.getBoolean(PREF_GALA_GLOBAL_MODE, false);
        switchGalaGlobal.setChecked(galaGlobalMode);
        updateToggleStyle(switchGalaGlobal);
        switchGalaGlobal.addOnCheckedChangeListener((bv, checked) -> {
            updateToggleStyle(bv);
            if (isUpdatingUi) return;
            galaGlobalMode = checked;
            SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(PREF_GALA_GLOBAL_MODE, checked);
            if (checked) {
                // Seed the global value from whatever's on screen right now, so flipping
                // this on doesn't silently reset GALA to off.
                ed.putBoolean(PREF_GALA_GLOBAL_ENABLED, switchGalaEnable.isChecked());
            }
            ed.apply();
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
    private void exportPresets() {
        autoSaveCurrent();
        String s = spinnerPresets.getText().toString();
        // Saved straight to Download/wDSP so that the same file manager handles both saving and
        // loading. Only the document picker offers a save dialog on these head units, and only a
        // file manager offers a load one, which is why the two used to look different.
        if (exportPresetToDownloads(s)) return;
        exportLauncher.launch(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, s + ".json"));
    }

    /** @return false when the media store refused, so the caller can fall back to the picker */
    private boolean exportPresetToDownloads(String presetName) {
        Downloads.Pending pending =
                Downloads.create(this, presetName + ".json", "application/json");
        if (pending == null) return false;
        try {
            writeCurrentPresetTo(pending.stream);
            Downloads.finish(this, pending);
            Toaster.show(this, getString(R.string.toast_saved_to, pending.displayPath));
            return true;
        } catch (Exception e) {
            Downloads.discard(this, pending);
            Log.e(TAG, "Preset export to Downloads failed", e);
            return false;
        }
    }

    private void importPresets() {
        importLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json"));
    }

    /**
     * Writes the selected preset as JSON.
     *
     * Shared by both ways of exporting - straight to the Downloads folder, and through the
     * document picker when a ROM will not have the media store - so that the two can never drift
     * apart and produce files that restore differently.
     */
    private void writeCurrentPresetTo(OutputStream os) throws java.io.IOException {
        String currentPreset = spinnerPresets.getText().toString();
        savePreset(currentPreset);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Map<String, ?> allEntries = prefs.getAll();

        Map<String, Object> filteredData = new HashMap<>();
        filteredData.put("is_single_preset", true);
        filteredData.put("preset_name_label", currentPreset);

        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(currentPreset + "_")) {
                String suffix = key.substring(currentPreset.length());
                filteredData.put(suffix, entry.getValue());
                filteredData.put(key, entry.getValue());
            }
        }

        os.write(new Gson().toJson(filteredData)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void saveCurrentPresetToFile(Uri u) {
        try (OutputStream os = getContentResolver().openOutputStream(u)) {
            if (os == null) return;
            writeCurrentPresetTo(os);
            Toaster.show(this, getString(R.string.toast_exported));
        } catch (IOException e) {
            Log.e(TAG, "Export error", e);
            Toaster.show(this, getString(R.string.error));
        }
    }

    private void loadPresetFromFile(Uri u) {
        try (InputStream is = getContentResolver().openInputStream(u);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);

            Map<String, Object> importedMap = new Gson().fromJson(sb.toString(), new TypeToken<Map<String, Object>>() {}.getType());
            if (importedMap == null) return;

            String sourcePresetName = (String) importedMap.get("preset_name_label");
            String newPresetName = sourcePresetName;
            if (newPresetName == null || newPresetName.trim().isEmpty()) {
                newPresetName = "Imported_" + (System.currentTimeMillis() / 1000);
            }

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            for (Map.Entry<String, Object> entry : importedMap.entrySet()) {
                String rawKey = entry.getKey();
                Object value = entry.getValue();

                if ("is_single_preset".equals(rawKey) || "preset_name_label".equals(rawKey)) continue;

                String suffix;
                if (rawKey.startsWith("_")) {
                    suffix = rawKey;
                } else if (sourcePresetName != null && rawKey.startsWith(sourcePresetName + "_")) {
                    suffix = rawKey.substring(sourcePresetName.length());
                } else if (rawKey.contains("_")) {
                    suffix = rawKey.substring(rawKey.indexOf('_'));
                } else {
                    suffix = "_" + rawKey;
                }

                String targetKey = newPresetName + suffix;

                if (value instanceof Boolean) {
                    editor.putBoolean(targetKey, (Boolean) value);
                } else if (value instanceof Double) {
                    double d = (Double) value;
                    if (d == Math.rint(d)) editor.putInt(targetKey, (int) d);
                    else editor.putFloat(targetKey, (float) d);
                } else if (value instanceof String) {
                    editor.putString(targetKey, (String) value);
                }
            }

            if (!presetNames.contains(newPresetName)) {
                presetNames.add(newPresetName);
                Collections.sort(presetNames);
                editor.putStringSet(PREF_PRESET_NAMES, new HashSet<>(presetNames));
            }

            editor.putString(PREF_LAST_SELECTED, newPresetName);
            editor.apply();

            setupPresets();
            ensureCallPresetExists();

            spinnerPresets.setText(newPresetName, false);
            loadPreset(newPresetName);

            Toaster.show(this, getString(R.string.toast_imported) + ": " + newPresetName);

        } catch (Exception e) {
            Log.e(TAG, "Import error", e);
            Toaster.show(this, getString(R.string.toast_import_failed, e.getMessage()));
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
            seekGalaFadeMs.setValue(100);
            tvGalaFadeMsVal.setText(getString(R.string.gala_ms_fmt, 100));
            seekGalaHoldMs.setValue(1000);
            tvGalaHoldMsVal.setText(String.format(Locale.getDefault(), getString(R.string.gala_s_fmt), 1.0f));
        }
        isUpdatingUi = false; updateVisualizer(); updateFmVisualizer();
    }
}
