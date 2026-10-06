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
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
//import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import java.io.ByteArrayOutputStream;
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
    private static final String PREF_AC_SWEEP_START = "audiocheck_sweep_start";
    private static final String PREF_AC_SWEEP_END = "audiocheck_sweep_end";
    private static final String PREF_AC_SWEEP_DURATION = "audiocheck_sweep_duration";
    private static final String PREF_AC_SWEEP_NORMALIZE = "audiocheck_sweep_normalize";
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
    // The "Off" entry is appended, not prepended, so every existing saved preset's "_sub_f" index
    // keeps meaning exactly what it already meant - only brand-new presets/installs default to it
    // (see setupSubControls()). It's a pure app-side/UI sentinel with no real hardware meaning -
    // McuService.loadPresetData() translates it to the lowest real table index + 0 gain before it
    // ever reaches the sub packet (the protocol has no "off" bit for the sub channel at all).
    // Populated in setupSubControls() rather than here, since that last entry is a translated
    // string resource and getString() isn't available yet during field initialization.
    private String[] SUB_FREQS;
    private final int NO_SUB_INDEX = 11; // SUB_FREQS.length - 1 (12 entries) - mirrored as a literal in McuService.NO_SUB_INDEX too

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
    // Switch-gated collapsible groups (see activity_main.xml's layout_fm_groups doc) - each
    // group's own visibility (shown at all) is driven by its switch; its panel's visibility
    // (expanded/collapsed within the group) is the same disclosure pattern as GALA's Advanced.
    private View groupLoudness, groupTrimHighs, groupUltraBass;
    
    // GALA Controls
    private SwitchCompat switchGalaEnable, switchGalaGlobal, switchGalaDisableForPreset;
    private Slider seekGalaInc, seekGalaMinSpeed, seekSimulateSpeed, seekGalaMaxAdj;
    private Slider seekGalaFadeMs, seekGalaHoldMs;
  
    private TextView tvGalaIncVal, tvGalaSpeed, tvGalaMinSpeedVal, tvGalaOffset, tvSimulateSpeedVal, tvGalaMaxAdjVal, tvGalaFadeMsVal, tvGalaHoldMsVal;
    
    // Whether every GALA field (enable + all 5 sliders) is shared across all presets instead
    // of per-preset. Kept in sync with PREF_GALA_GLOBAL_MODE; see galaNamespace().
    private boolean galaGlobalMode = false;

    // Audio Check Controls (internal hardware QA tool - see layout_audiocheck's doc). Not saved
    // to any preset and not loaded by loadPreset() - these are pure runtime state, on purpose.
    private SwitchCompat switchAcBass, switchAcVocal, switchAcDrums, switchAcMelody, switchAcOnlySub;
    private SwitchCompat switchAcPinkNoise, switchAcSine;
    private SwitchCompat switchAcSweepNormalize;
    private Slider seekAcSineFreq;
    private TextView tvAcSineFreqVal;
    private EditText editAcSweepStart, editAcSweepEnd, editAcSweepDuration;
    private TextView btnAcSweepToggle;
    private LoopTrack acSweep;
    // Pink noise and the sine generator are standalone signal sources, not stems of anything -
    // unlike acBass/acVocal/acDrums/acMelody above they don't participate in the stem sync clock
    // at all. Pink noise is cached/reused like a stem (same signal every time, so no reason to
    // resynthesize 5 seconds of noise on every toggle); the sine track is rebuilt fresh whenever
    // it's turned on or its frequency changes while on, since unlike pink noise its content
    // actually depends on a value that can change between toggles.
    private LoopTrack acPinkNoise, acSine;
    // Pending debounced rebuild from scheduleSineRebuild() - null when none is scheduled, so a
    // toggle-off (or another rebuild) knows whether there's a stale one to cancel.
    private Runnable pendingSineRebuild;
    // Stems use AudioTrack in MODE_STATIC with setLoopPoints(), not MediaPlayer - MediaPlayer's
    // own looping has an audible gap at the loop point even for an uncompressed WAV, since it
    // re-triggers the decoder; AudioTrack's static-buffer loop points are the only genuinely
    // sample-accurate/gapless way to loop on Android. Built lazily on first toggle-on and kept
    // around (paused, not released) across toggles so later ones don't re-parse the WAV.
    private LoopTrack acBass, acVocal, acDrums, acMelody;
    // Shared "loop clock" so a stem toggled on while others are already looping joins already in
    // sync instead of restarting the loop at 0 - see toggleAcStem()'s doc. -1 = no stem currently
    // playing (clock not running); reset whenever the last playing stem stops, so the next fresh
    // start begins a new sync epoch.
    private long acStemLoopStartNanos = -1;
    // Periodically re-checks playing stems against the loop clock and snaps back any that have
    // drifted - each stem loops independently via its own AudioTrack's hardware loop points, and
    // on devices that have to resample to a different native output rate, independent rounding at
    // each track's own loop boundary can slowly pull two stems that started in perfect sync apart
    // over many cycles. See checkAcStemSync()'s doc for the correction itself.
    private static final int AC_STEM_SYNC_CHECK_MS = 3000;
    private static final int AC_STEM_SYNC_TOLERANCE_MS = 40;
    private final Runnable acStemSyncWatchdog = this::checkAcStemSync;
    // All Audio Check sources (stems, pink noise, sine) are independent AudioTracks, each of
    // which otherwise gets its own distinct audio session from the system - a Visualizer can only
    // ever listen to one session, so whichever source last called attachToSession() was the only
    // one actually visualized while the others played silently as far as the RTA was concerned.
    // Giving every Audio Check AudioTrack this same explicit session (via setSessionId() in
    // buildStaticLoopAudioTrack()) makes them all mix into one session instead, so a single
    // Visualizer attached to it sees all of them combined. Lazily generated on first use;
    // -1 = not yet created.
    private int acSharedSessionId = -1;

    private int acAudioSessionId() {
        if (acSharedSessionId <= 0) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            acSharedSessionId = am.generateAudioSessionId();
        }
        return acSharedSessionId;
    }

    // Speaker-test buttons stay plain MediaPlayer, one-shot (no loop) - a single playthrough has
    // no loop point, so none of the AudioTrack machinery above is needed here. Only one plays at
    // a time (they share the one real fader, and testing two corners at once defeats the point of
    // isolating a single speaker) - these track whichever one (if any) is currently active, so a
    // second tap on the same button, or a tap on a different one, knows what to stop first.
    // 0 = none active.
    private MediaPlayer mpAcSpeaker;
    private int activeSpeakerBtnId = 0;

    /** AudioTrack + the frame count/sample rate it was built with, so toggleAcStem()'s sync math
     * doesn't have to re-derive them from the track itself. */
    private static class LoopTrack {
        final AudioTrack track;
        final int frames;
        final int sampleRate;
        LoopTrack(AudioTrack track, int frames, int sampleRate) {
            this.track = track; this.frames = frames; this.sampleRate = sampleRate;
        }
    }

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

        // Populated here, not as a field initializer, since the last entry is a translated
        // string resource and getString() needs the Activity's Context - not attached yet during
        // field initialization. Can't wait for the deferred setupLogic()/setupSubControls()
        // either though: setupPresets() (below, via the non-delayed handler.post()) calls
        // loadPreset() before that, and loadPreset() reads SUB_FREQS.
        SUB_FREQS = new String[]{"25", "32", "40", "50", "63", "80", "100", "125", "160", "200", "250", getString(R.string.no_sub)};

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
        flushPendingAutoSave();
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
        setupAudioCheckControls();

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
            flushPendingAutoSave(); // explicit "commit now" action - the toast below says it's done
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
        groupLoudness = findViewById(R.id.group_loudness);
        groupTrimHighs = findViewById(R.id.group_trim_highs);
        groupUltraBass = findViewById(R.id.group_ultra_bass);

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
        // Minimizing (as opposed to onPause()'s much more frequent "lost focus" - a notification,
        // a permission dialog) is the point an Activity recreation becomes possible without a
        // guaranteed onDestroy() on the old instance first - stopping here means there's never a
        // still-playing zombie AudioTrack left over from a previous instance to double up with a
        // freshly re-enabled switch.
        stopAllAudioCheck();
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
            if (spectrumAnalyzer != null) spectrumAnalyzer.setBassShaping(20f, 0f, 0f, 20f, 0f, 0f);
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
        // Unlike eqVisualizer above, not gated on showOnMain - the RTA has its own separate gate
        // (bassReactiveEnabled/loudnessReactiveEnabled), same as the existing comment already
        // established for the front-only value this always used. Needs both front AND rear (see
        // SpectrumAnalyzerView.setBassShaping()'s doc) so it can combine them into one real
        // perceived curve instead of only ever showing front's own response.
        if (spectrumAnalyzer != null) spectrumAnalyzer.setBassShaping(
                frontFilterHz, effFrontBoostHz, effFrontBoostDb,
                rearFilterHz, effRearBoostHz, effRearBoostDb);
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

    /** SUB_FREQS[NO_SUB_INDEX] ("No Sub") isn't a real frequency - parsing it as one would throw,
     * so this returns the 0 sentinel instead (see SUB_FREQS's own doc for why 0 is safe: it makes
     * AudioConfig.subFilterResponseDb() read as -infinity dB for any real frequency, so the sub
     * naturally drops out of every curve/combination with no special-casing needed downstream). */
    private int parseSubFreqHz(String subFreqText) {
        return SUB_FREQS[NO_SUB_INDEX].equals(subFreqText) ? 0 : Integer.parseInt(subFreqText);
    }

    /** Greys out the controls that only make sense with a real subwoofer attached, and zeroes the
     * gain slider to match what McuService actually sends to hardware once "No Sub" is selected
     * (see SUB_FREQS's doc). Called from the spinner's own click listener below AND from preset
     * load/reset, so the greyed-out state is correct immediately on load, not just reactively. */
    private void updateSubDependentControlsEnabled(boolean noSub) {
        // Null-checked: loadPreset() can run very early (before initSecondaryViews() has bound
        // switchFmSubComp/switchUltraBass) during startup bootstrap - same hazard
        // updateBassVisualizer() already guards against for its own early-bootstrap call. Just
        // skip greying out controls that don't exist yet; the real preset load that follows once
        // everything's bound calls this again with the correct final state anyway.
        if (seekSubGain != null) {
            seekSubGain.setEnabled(!noSub);
            if (noSub) seekSubGain.setValue(0f);
        }
        if (noSub && tvSubDb != null) tvSubDb.setText("+0");
        if (switchFmSubComp != null) switchFmSubComp.setEnabled(!noSub);
        if (switchUltraBass != null) switchUltraBass.setEnabled(!noSub);
    }

    private void setupSubControls() {
        // 1. Create the adapter (using a standard material-friendly layout)
        ArrayAdapter<String> subAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_list_item_1,
                SUB_FREQS
        );
        spinnerSubFreq.setAdapter(subAdapter);

        // 2. Set the initial text (replaces setSelection) - "No Sub" is the default for a fresh
        // install/new preset (see SUB_FREQS's doc); existing saved presets are unaffected since
        // this only matters until loadPreset() overwrites it with whatever was actually saved.
        // 'false' is critical here to prevent the dropdown from opening or filtering
        spinnerSubFreq.setText(SUB_FREQS[NO_SUB_INDEX], false);
        Globals.currentSubFreqHz = parseSubFreqHz(SUB_FREQS[NO_SUB_INDEX]);
        updateSubDependentControlsEnabled(true);

        // 3. Change OnItemSelectedListener to OnItemClickListener
        spinnerSubFreq.setOnItemClickListener((parent, view, pos, id) -> {
            // Logic for Sub Comp limit (if FM Sub Comp is on AND loudness is actually active, limit
            // to 80Hz/Index 5 - Sub Comp's offset is only ever computed while loudness is on, see
            // calculateFmOffsets(), so this restriction shouldn't apply while loudness is off).
            // Excludes NO_SUB_INDEX - "No Sub" is always reachable regardless of Sub Comp, since
            // selecting it disables Sub Comp outright (see updateSubDependentControlsEnabled()).
            if (isFullyInitialized && switchFmSubComp.isChecked() && switchFmEnable.isChecked() && pos > 5 && pos != NO_SUB_INDEX) {
                // Revert the text back to 80Hz (Index 5)
                spinnerSubFreq.setText(SUB_FREQS[5], false);
                Toaster.show(MainActivity.this, getString(R.string.toast_sub_comp_limit));

                // Re-sync Global just in case
                Globals.currentSubFreqHz = parseSubFreqHz(SUB_FREQS[5]);
                updateVisualizer();
                return;
            }

            if (!isUpdatingUi) {
                autoSaveCurrent();
            }

            // Update the global value for other calculations
            String freqString = SUB_FREQS[pos];
            Globals.currentSubFreqHz = parseSubFreqHz(freqString);
            updateSubDependentControlsEnabled(pos == NO_SUB_INDEX);
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
            updateFmGroupVisibility();
            if (!isUpdatingUi) {
                if (checked) Toaster.show(this, getString(R.string.toast_loudness_sync_bass), Toast.LENGTH_LONG);
                autoSaveCurrent();
                updateFmVisualizer();
            }
        });
        switchFatigueEnable.jumpDrawablesToCurrentState();
        switchFatigueEnable.setOnCheckedChangeListener((bv, checked) -> {
            updateFmGroupVisibility();
            if (!isUpdatingUi) {
                autoSaveCurrent();
                updateFmVisualizer();
//                updateEqMcu();
            }
        });
        switchFmSubComp.jumpDrawablesToCurrentState();
        switchFmSubComp.setOnCheckedChangeListener((bv, checked) -> {
            if (!isUpdatingUi) {
                int subIdx = java.util.Arrays.asList(SUB_FREQS).indexOf(spinnerSubFreq.getText().toString());
                if (checked && switchFmEnable.isChecked() && subIdx > 5 && subIdx != NO_SUB_INDEX) {
                    spinnerSubFreq.setText(SUB_FREQS[5], false);
                    Globals.currentSubFreqHz = parseSubFreqHz(SUB_FREQS[5]);
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
            updateFmGroupVisibility();
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

        setupFmDisclosureToggle(R.id.row_loudness_toggle, R.id.panel_loudness, R.id.iv_loudness_chevron, "fm_panel_loudness_open");
        setupFmDisclosureToggle(R.id.row_trim_highs_toggle, R.id.panel_trim_highs, R.id.iv_trim_highs_chevron, "fm_panel_trim_highs_open");
        setupFmDisclosureToggle(R.id.row_ultra_bass_toggle, R.id.panel_ultra_bass, R.id.iv_ultra_bass_chevron, "fm_panel_ultra_bass_open");
        updateFmGroupVisibility();
    }

    /** Same expand/collapse-on-tap pattern as GALA's Advanced section (see
     * row_gala_advanced_toggle's wiring), but unlike it, persisted to the standalone app prefs
     * (not per-preset, same as switch_show_loudness_main) so these three panels reopen the way
     * the user left them across app restarts instead of always starting collapsed. Reused here
     * for all three switch-gated groups instead of copying the click listener three times. */
    private void setupFmDisclosureToggle(int toggleId, int panelId, int chevronId, String prefKey) {
        View toggle = findViewById(toggleId);
        View panel = findViewById(panelId);
        ImageView chevron = findViewById(chevronId);
        if (toggle == null || panel == null) return;
        SharedPreferences appPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean open = appPrefs.getBoolean(prefKey, false);
        panel.setVisibility(open ? View.VISIBLE : View.GONE);
        if (chevron != null) chevron.setRotation(open ? 0f : 180f);
        toggle.setOnClickListener(v -> {
            boolean expanding = panel.getVisibility() != View.VISIBLE;
            panel.setVisibility(expanding ? View.VISIBLE : View.GONE);
            if (chevron != null) chevron.setRotation(expanding ? 0f : 180f);
            appPrefs.edit().putBoolean(prefKey, expanding).apply();
        });
    }

    /** Shows/hides each of the three Loudness/Trim Highs/Ultra Bass groups entirely based on
     * whether its own switch is on - see layout_fm_groups' doc in activity_main.xml for why
     * (every slider in them is meaningless while its feature is off). Called from each switch's
     * own listener above and from loadPreset(), so it's correct both live and right after a
     * preset loads. */
    private void updateFmGroupVisibility() {
        if (groupLoudness != null) groupLoudness.setVisibility(switchFmEnable != null && switchFmEnable.isChecked() ? View.VISIBLE : View.GONE);
        if (groupTrimHighs != null) groupTrimHighs.setVisibility(switchFatigueEnable != null && switchFatigueEnable.isChecked() ? View.VISIBLE : View.GONE);
        if (groupUltraBass != null) groupUltraBass.setVisibility(switchUltraBass != null && switchUltraBass.isChecked() ? View.VISIBLE : View.GONE);
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
        e.putInt("Call_gala_increment", 20);
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
        // NO_SUB_INDEX is the fallback only for a preset that's never saved "_sub_f" at all - an
        // existing saved preset always has its own real saved value here, so this changes nothing
        // for existing users (see SUB_FREQS's doc).
        int subFreqIdx = p.getInt(name + "_sub_f", NO_SUB_INDEX);
        if (subFreqIdx < 0 || subFreqIdx >= SUB_FREQS.length) {
            subFreqIdx = NO_SUB_INDEX; // Safety fallback
        }
        spinnerSubFreq.setText(SUB_FREQS[subFreqIdx], false);
        // setText(..., false) doesn't trigger the spinner's own OnItemClickListener - which is
        // the only other place Globals.currentSubFreqHz gets updated - so without this, the sub
        // overlay curve stays stuck on whatever frequency was last manually tapped instead of
        // following the frequency each preset actually loads.
        Globals.currentSubFreqHz = parseSubFreqHz(SUB_FREQS[subFreqIdx]);
        updateSubDependentControlsEnabled(subFreqIdx == NO_SUB_INDEX);
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
            updateFmGroupVisibility();
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
            // Rounded to the nearest valid step (0/10/20/30/40/50) - a preset saved before this
            // slider used stepSize=10 can hold any raw int 0-45, which Slider.setValue() would
            // otherwise throw on once it's actually laid out (value must land exactly on a step).
            int galaIncRaw = p.getInt(galaNs + "_gala_increment", 20);
            seekGalaInc.setValue((float) Math.max(10, Math.min(100, Math.round(galaIncRaw / 10f) * 10)));
            tvGalaIncVal.setText(getString(R.string.speed_kmh_format, getIntSlider(seekGalaInc)));
            seekGalaMinSpeed.setValue((float) Math.max(0, Math.min(15, p.getInt(galaNs + "_gala_min_speed", 0))));
            tvGalaMinSpeedVal.setText(getString(R.string.speed_kmh_format, getIntSlider(seekGalaMinSpeed)));
            // Clamped to the slider's new 0-16 range (was 0-32) - same reasoning as above, but for
            // range instead of step: a value saved under the old range could be out of bounds now.
            seekGalaMaxAdj.setValue((float) Math.max(0, Math.min(16, p.getInt(galaNs + "_gala_max_adj", 12))));
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
        final View ac = findViewById(R.id.layout_audiocheck);

        // 2. Put them in an array for easy looping
        final View[] allLayouts = {eq, fm, dly, ftr, gl, ac};
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
            else if (id == R.id.nav_audiocheck) target = ac;

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
            if (slider == seekGalaInc) tvGalaIncVal.setText(getString(R.string.speed_kmh_format,p));
            else if (slider == seekGalaMinSpeed) tvGalaMinSpeedVal.setText(getString(R.string.speed_kmh_format,p));
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

        // Advanced Settings disclosure (Standstill Speed/Fade Delay/Hold Timer/Simulate Speed) -
        // pure visibility toggle, nothing saved here; those sliders already autosave themselves
        // via galal above regardless of whether this section is open.
        View advancedToggle = findViewById(R.id.row_gala_advanced_toggle);
        View advancedPanel = findViewById(R.id.layout_gala_advanced);
        ImageView advancedChevron = findViewById(R.id.iv_gala_advanced_chevron);
        if (advancedToggle != null && advancedPanel != null) {
            advancedToggle.setOnClickListener(v -> {
                boolean expanding = advancedPanel.getVisibility() != View.VISIBLE;
                advancedPanel.setVisibility(expanding ? View.VISIBLE : View.GONE);
                if (advancedChevron != null) advancedChevron.setRotation(expanding ? 0f : 180f);
            });
        }
    }

    private void setupAudioCheckControls() {
        switchAcBass = findViewById(R.id.switch_audiocheck_bass);
        switchAcVocal = findViewById(R.id.switch_audiocheck_vocal);
        switchAcDrums = findViewById(R.id.switch_audiocheck_drums);
        switchAcMelody = findViewById(R.id.switch_audiocheck_melody);
        switchAcOnlySub = findViewById(R.id.switch_audiocheck_only_sub);

        switchAcBass.setOnCheckedChangeListener((bv, checked) -> acBass = toggleAcStem(acBass, checked, R.raw.audiocheck_bass));
        switchAcVocal.setOnCheckedChangeListener((bv, checked) -> acVocal = toggleAcStem(acVocal, checked, R.raw.audiocheck_vocal));
        switchAcDrums.setOnCheckedChangeListener((bv, checked) -> acDrums = toggleAcStem(acDrums, checked, R.raw.audiocheck_drums));
        switchAcMelody.setOnCheckedChangeListener((bv, checked) -> acMelody = toggleAcStem(acMelody, checked, R.raw.audiocheck_melody));

        switchAcPinkNoise = findViewById(R.id.switch_audiocheck_pink_noise);
        switchAcSine = findViewById(R.id.switch_audiocheck_sine);
        seekAcSineFreq = findViewById(R.id.seek_audiocheck_sine_freq);
        tvAcSineFreqVal = findViewById(R.id.tv_audiocheck_sine_freq_val);

        // Pink noise is cached/reused across toggles like a stem (see acPinkNoise's own doc).
        switchAcPinkNoise.setOnCheckedChangeListener((bv, checked) -> {
            if (acPinkNoise == null) acPinkNoise = buildPinkNoiseLoopTrack();
            if (checked) {
                acPinkNoise.track.play();
                if (spectrumAnalyzer != null) spectrumAnalyzer.attachToSession(acPinkNoise.track.getAudioSessionId());
            } else {
                acPinkNoise.track.pause();
            }
        });

        tvAcSineFreqVal.setText(getString(R.string.lbl_hz_fmt, String.valueOf(currentSineFreqHz())));
        // Unlike pink noise, the sine's content depends on a value that can change while it's
        // off (the slider), so it's always rebuilt fresh on toggle-on rather than reused.
        switchAcSine.setOnCheckedChangeListener((bv, checked) -> {
            if (pendingSineRebuild != null) { handler.removeCallbacks(pendingSineRebuild); pendingSineRebuild = null; }
            if (acSine != null) { acSine.track.release(); acSine = null; }
            if (checked) {
                acSine = buildSineLoopTrack(currentSineFreqHz());
                acSine.track.play();
                if (spectrumAnalyzer != null) spectrumAnalyzer.attachToSession(acSine.track.getAudioSessionId());
            }
            // The trick: the RTA's own frequency-smoothing was exactly what was blurring/
            // attenuating a narrowband test tone's true peak (see SpectrumAnalyzerView's
            // smoothingDisabled doc) - now that there's a real sine generator to drive that case,
            // disable it for exactly as long as a tone (sine or sweep) is actually playing.
            updateAcToneSmoothing();
        });
        // No fromUser guard - this slider is pure runtime AudioCheck state (see its field doc),
        // never set programmatically except by the step buttons below, which should restart the
        // tone at the new frequency exactly the same way a drag does. value is the slider's own
        // normalized 0..1 position, not Hz - see posToFreq()'s doc for why it's logarithmic. The
        // label updates immediately on every tick for responsiveness; the actual audio rebuild is
        // debounced (see scheduleSineRebuild()'s doc) so a drag doesn't pop dozens of times.
        seekAcSineFreq.addOnChangeListener((slider, value, fromUser) -> {
            int freq = Math.round(posToFreq(value));
            tvAcSineFreqVal.setText(getString(R.string.lbl_hz_fmt, String.valueOf(freq)));
            if (switchAcSine.isChecked()) scheduleSineRebuild(freq);
        });
        findViewById(R.id.btn_audiocheck_sine_minus10).setOnClickListener(v -> stepSineFreq(-10));
        findViewById(R.id.btn_audiocheck_sine_minus1).setOnClickListener(v -> stepSineFreq(-1));
        findViewById(R.id.btn_audiocheck_sine_plus1).setOnClickListener(v -> stepSineFreq(1));
        findViewById(R.id.btn_audiocheck_sine_plus10).setOnClickListener(v -> stepSineFreq(10));

        editAcSweepStart = findViewById(R.id.edit_audiocheck_sweep_start);
        editAcSweepEnd = findViewById(R.id.edit_audiocheck_sweep_end);
        editAcSweepDuration = findViewById(R.id.edit_audiocheck_sweep_duration);
        bindPersistedTextField(editAcSweepStart, PREF_AC_SWEEP_START);
        bindPersistedTextField(editAcSweepEnd, PREF_AC_SWEEP_END);
        bindPersistedTextField(editAcSweepDuration, PREF_AC_SWEEP_DURATION);
        btnAcSweepToggle = findViewById(R.id.btn_audiocheck_sweep_toggle);
        btnAcSweepToggle.setOnClickListener(v -> toggleAcSweep());

        switchAcSweepNormalize = findViewById(R.id.switch_audiocheck_sweep_normalize);
        switchAcSweepNormalize.setChecked(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_AC_SWEEP_NORMALIZE, false));
        switchAcSweepNormalize.setOnCheckedChangeListener((bv, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_AC_SWEEP_NORMALIZE, checked).apply();
            // Mirrors audiocheck.net's own perceptual-sweep instructions (https://www.audiocheck.net/
            // testtones_perceptualsinesweep.php): the per-frequency gain curve below only cancels
            // out the ear's own threshold-of-hearing shape - it still relies on the listener playing
            // quietly enough that they're near that threshold in the first place.
            if (checked) Toaster.show(this, getString(R.string.toast_sweep_hearing_threshold), Toast.LENGTH_LONG);
        });

        // Fader convention: _f_fr/_f_lr are 0..24, 12=center, LOW=rear/left, HIGH=front/right -
        // confirmed from SpectrumAnalyzerView's frontWeight/rearWeight math (raw=0 zeroes
        // frontWeight out entirely) and independently from the fader screen's own "+" step
        // button, labeled btn_front, which moves the value up. (Originally wired backwards here -
        // verified wrong on real hardware, fixed.)
        findViewById(R.id.btn_audiocheck_fl).setOnClickListener(v -> handleSpeakerButton(R.id.btn_audiocheck_fl, R.raw.audiocheck_fl, 24, 0));
        findViewById(R.id.btn_audiocheck_fr).setOnClickListener(v -> handleSpeakerButton(R.id.btn_audiocheck_fr, R.raw.audiocheck_fr, 24, 24));
        findViewById(R.id.btn_audiocheck_rl).setOnClickListener(v -> handleSpeakerButton(R.id.btn_audiocheck_rl, R.raw.audiocheck_rl, 0, 0));
        findViewById(R.id.btn_audiocheck_rr).setOnClickListener(v -> handleSpeakerButton(R.id.btn_audiocheck_rr, R.raw.audiocheck_rr, 0, 24));

        switchAcOnlySub.setOnCheckedChangeListener((bv, checked) -> {
            Intent intent = new Intent("com.radiorubka.wdsp.AUDIOCHECK_ONLY_SUB");
            intent.setPackage(getPackageName());
            intent.putExtra("enabled", checked);
            sendBroadcast(intent);
        });
    }

    /** Starts/stops one stem's gapless AudioTrack loop, joined in sync with whichever other
     * stems are already playing. The 4 stem switches are independent and freely combinable
     * (that's the point of having separate stems) - this just keeps them phase-locked when
     * combined, since they're parallel tracks of the same song: a stem toggled on while others
     * are already looping computes how far into the shared loop cycle those others currently
     * are (elapsed real time since acStemLoopStartNanos, modulo this stem's own loop length) and
     * starts there instead of at 0. When the last stem stops, the clock resets so the next fresh
     * start begins a new sync epoch at 0. */
    private LoopTrack toggleAcStem(LoopTrack lt, boolean play, int rawResId) {
        if (lt == null) {
            try {
                lt = buildLoopTrack(loadWav(rawResId));
            } catch (IOException e) {
                Log.e(TAG, "Audio Check: failed to load stem " + rawResId, e);
                return null;
            }
        }
        if (!play) {
            lt.track.pause();
            if (!anyStemPlaying()) acStemLoopStartNanos = -1;
            return lt;
        }
        if (acStemLoopStartNanos < 0) {
            acStemLoopStartNanos = System.nanoTime();
            lt.track.setPlaybackHeadPosition(0);
            handler.postDelayed(acStemSyncWatchdog, AC_STEM_SYNC_CHECK_MS);
        } else {
            double elapsedSec = (System.nanoTime() - acStemLoopStartNanos) / 1_000_000_000.0;
            int offsetFrames = (int) ((elapsedSec * lt.sampleRate) % lt.frames);
            lt.track.pause();
            lt.track.setPlaybackHeadPosition(offsetFrames);
        }
        lt.track.play();
        if (spectrumAnalyzer != null) spectrumAnalyzer.attachToSession(lt.track.getAudioSessionId());
        return lt;
    }

    private boolean anyStemPlaying() {
        return isStemPlaying(acBass) || isStemPlaying(acVocal) || isStemPlaying(acDrums) || isStemPlaying(acMelody);
    }

    private boolean isStemPlaying(LoopTrack lt) {
        return lt != null && lt.track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    /** Self-rescheduling watchdog (see acStemSyncWatchdog's own doc for why this exists) - as long
     * as 2+ stems are playing, re-derives each one's expected position in the loop from the shared
     * clock and snaps any that have drifted past AC_STEM_SYNC_TOLERANCE_MS back into line. Stops
     * rescheduling itself once fewer than 2 stems remain, so it dies out on its own rather than
     * needing an explicit cancel from the stop/toggle-off paths (onDestroy()'s existing
     * removeCallbacksAndMessages(null) still cancels a pending tick immediately if needed). */
    private void checkAcStemSync() {
        if (acStemLoopStartNanos >= 0 && countPlayingStems() >= 2) {
            double elapsedSec = (System.nanoTime() - acStemLoopStartNanos) / 1_000_000_000.0;
            resyncStemIfDrifted(acBass, elapsedSec);
            resyncStemIfDrifted(acVocal, elapsedSec);
            resyncStemIfDrifted(acDrums, elapsedSec);
            resyncStemIfDrifted(acMelody, elapsedSec);
        }
        if (anyStemPlaying()) handler.postDelayed(acStemSyncWatchdog, AC_STEM_SYNC_CHECK_MS);
    }

    private int countPlayingStems() {
        int n = 0;
        if (isStemPlaying(acBass)) n++;
        if (isStemPlaying(acVocal)) n++;
        if (isStemPlaying(acDrums)) n++;
        if (isStemPlaying(acMelody)) n++;
        return n;
    }

    /** getPlaybackHeadPosition() keeps counting cumulatively across loop boundaries rather than
     * wrapping at the buffer length (the same assumption toggleAcStem()'s own join-sync math
     * relies on) - '% lt.frames' is what turns that into "where in the current loop iteration."
     * Correcting via pause()+setPlaybackHeadPosition()+play() is a hard cut, same as the join-sync
     * path, but only fires when actually drifted past tolerance, so in practice it's rare. */
    private void resyncStemIfDrifted(LoopTrack lt, double elapsedSec) {
        if (!isStemPlaying(lt)) return;
        int expectedFrames = (int) ((elapsedSec * lt.sampleRate) % lt.frames);
        int actualFrames = lt.track.getPlaybackHeadPosition() % lt.frames;
        int diff = Math.abs(expectedFrames - actualFrames);
        diff = Math.min(diff, lt.frames - diff);
        int toleranceFrames = (int) (AC_STEM_SYNC_TOLERANCE_MS / 1000f * lt.sampleRate);
        if (diff > toleranceFrames) {
            lt.track.pause();
            lt.track.setPlaybackHeadPosition(expectedFrames);
            lt.track.play();
        }
    }

    /** Parses a WAV resource's PCM data by walking its RIFF chunks directly (skipping any
     * chunk that isn't "fmt "/"data", e.g. a DAW-exported "LIST"/"fact" chunk) instead of
     * assuming a fixed 44-byte header - simple since WAV is already uncompressed PCM, no decoder
     * needed. */
    private WavPcm loadWav(int rawResId) throws IOException {
        byte[] b;
        try (InputStream in = getResources().openRawResource(rawResId)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            b = buf.toByteArray();
        }
        int sampleRate = 44100, channels = 2, bitsPerSample = 16;
        byte[] data = null;
        int pos = 12; // skip "RIFF" + size(4) + "WAVE"
        while (pos + 8 <= b.length) {
            String chunkId = new String(b, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = (b[pos + 4] & 0xFF) | ((b[pos + 5] & 0xFF) << 8) | ((b[pos + 6] & 0xFF) << 16) | ((b[pos + 7] & 0xFF) << 24);
            int chunkDataStart = pos + 8;
            if ("fmt ".equals(chunkId)) {
                channels = (b[chunkDataStart + 2] & 0xFF) | ((b[chunkDataStart + 3] & 0xFF) << 8);
                sampleRate = (b[chunkDataStart + 4] & 0xFF) | ((b[chunkDataStart + 5] & 0xFF) << 8) | ((b[chunkDataStart + 6] & 0xFF) << 16) | ((b[chunkDataStart + 7] & 0xFF) << 24);
                bitsPerSample = (b[chunkDataStart + 14] & 0xFF) | ((b[chunkDataStart + 15] & 0xFF) << 8);
            } else if ("data".equals(chunkId)) {
                data = new byte[chunkSize];
                System.arraycopy(b, chunkDataStart, data, 0, chunkSize);
                break;
            }
            pos = chunkDataStart + chunkSize + (chunkSize % 2); // chunks are word-aligned/padded to even size
        }
        if (data == null) throw new IOException("No data chunk in WAV resource " + rawResId);
        return new WavPcm(sampleRate, channels, bitsPerSample, data);
    }

    /** Builds a MODE_STATIC AudioTrack holding the entire stem and configures it to loop the
     * whole buffer forever (see toggleAcStem()'s own doc for why this, not MediaPlayer). Assumes
     * 16-bit PCM, which is what these stems actually are - wDSP doesn't need to support anything
     * else here. */
    private LoopTrack buildLoopTrack(WavPcm wav) {
        AudioTrack track = buildStaticLoopAudioTrack(wav.pcm, wav.sampleRate, wav.channels);
        track.setLoopPoints(0, wav.frameCount, -1);
        return new LoopTrack(track, wav.frameCount, wav.sampleRate);
    }

    private static final int AC_GEN_SAMPLE_RATE = 44100;

    /** Shared AudioTrack construction for every Audio Check loop - stems, pink noise, and the
     * sine generator all end up here. Static buffer, not streamed, which is what makes
     * setLoopPoints() genuinely gapless (see toggleAcStem()'s doc). Caller still has to call
     * setLoopPoints() itself once it knows the frame count. */
    private AudioTrack buildStaticLoopAudioTrack(byte[] pcm, int sampleRate, int channels) {
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(pcm.length)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setSessionId(acAudioSessionId())
                .build();
        track.write(pcm, 0, pcm.length);
        return track;
    }

    private static final float AC_SINE_MIN_HZ = 20f, AC_SINE_MAX_HZ = 20000f;

    /** seek_audiocheck_sine_freq's raw value (0..1) is a position along a logarithmic frequency
     * scale, not Hz directly - pitch/frequency perception is logarithmic (an octave is a
     * doubling, not a fixed Hz step), same reasoning as AudioConfig.frequencyAt()'s EQ-band
     * mapping, so a linear slider would waste almost all its travel on the top octave and leave
     * the entire bass/midrange crammed into a sliver. pos 0 = 20Hz, pos 1 = 20000Hz, every equal
     * step in between is an equal ratio (not an equal Hz difference). */
    private float posToFreq(float pos) {
        return AC_SINE_MIN_HZ * (float) Math.pow(AC_SINE_MAX_HZ / AC_SINE_MIN_HZ, pos);
    }

    /** Inverse of posToFreq() - used when a Hz value (from the step buttons, or clamping) needs
     * to move the slider's thumb to match. */
    private float freqToPos(float freqHz) {
        return (float) (Math.log(freqHz / AC_SINE_MIN_HZ) / Math.log(AC_SINE_MAX_HZ / AC_SINE_MIN_HZ));
    }

    private int currentSineFreqHz() {
        return Math.round(posToFreq(seekAcSineFreq.getValue()));
    }

    /** Adjusts the sine generator's frequency by delta Hz, clamped to [20, 20000] - setValue()
     * re-fires seekAcSineFreq's own change listener (see setupAudioCheckControls()), which
     * restarts the tone at the new frequency if it's currently playing, exactly like a manual
     * drag would. Works in Hz, not slider position, so a tap always means "exactly delta Hz
     * away" regardless of where the log scale puts that on the slider. */
    private void stepSineFreq(int delta) {
        int newFreq = Math.max((int) AC_SINE_MIN_HZ, Math.min((int) AC_SINE_MAX_HZ, currentSineFreqHz() + delta));
        seekAcSineFreq.setValue(freqToPos(newFreq));
    }

    /** Loads a sweep field's last-saved value (global, not per-preset - these describe a test
     * signal, not a sound setting, so there's no reason they'd differ per preset) and saves it
     * back on every edit, the same reactive-save pattern as switch_show_loudness_main and the FM
     * disclosure panels' open/closed state. */
    private void bindPersistedTextField(EditText field, String prefKey) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String saved = prefs.getString(prefKey, null);
        if (saved != null) field.setText(saved);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(prefKey, s.toString()).apply();
            }
        });
    }

    /** Starts/stops the logarithmic sweep. Unlike the sine generator, there's no slider to react
     * to live - the 3 fields are only ever read at the moment Play is tapped, so editing them
     * while the sweep is already running has no effect until it's stopped and restarted. */
    private void toggleAcSweep() {
        if (isStemPlaying(acSweep)) {
            stopAcSweep();
        } else {
            float start = clampSweepHz(parseSweepField(editAcSweepStart, 100f));
            float end = clampSweepHz(parseSweepField(editAcSweepEnd, 10000f));
            float duration = Math.max(0.5f, Math.min(300f, parseSweepField(editAcSweepDuration, 10f)));
            acSweep = buildSweepLoopTrack(start, end, duration, switchAcSweepNormalize.isChecked());
            acSweep.track.play();
            if (spectrumAnalyzer != null) spectrumAnalyzer.attachToSession(acSweep.track.getAudioSessionId());
            btnAcSweepToggle.setText(getString(R.string.audiocheck_sweep_pause));
        }
        updateAcToneSmoothing();
    }

    private void stopAcSweep() {
        if (acSweep != null) { acSweep.track.pause(); acSweep.track.release(); acSweep = null; }
        if (btnAcSweepToggle != null) btnAcSweepToggle.setText(getString(R.string.audiocheck_sweep_play));
    }

    private float clampSweepHz(float hz) {
        return Math.max(AC_SINE_MIN_HZ, Math.min(AC_SINE_MAX_HZ, hz));
    }

    /** Falls back to a sane default rather than refusing to start - this is a QA tool, not a form,
     * so an empty or garbled field shouldn't block the test. */
    private float parseSweepField(EditText field, float fallback) {
        try {
            return Float.parseFloat(field.getText().toString().trim());
        } catch (NumberFormatException | NullPointerException e) {
            return fallback;
        }
    }

    /** Shared by the sine switch and the sweep button - either one playing disables RTA
     * smoothing (see switchAcSine's own doc for why), so this re-derives the combined state from
     * both instead of each one clobbering the other's intent. */
    private void updateAcToneSmoothing() {
        if (spectrumAnalyzer != null) spectrumAnalyzer.setSmoothingDisabled(switchAcSine.isChecked() || isStemPlaying(acSweep));
    }

    private static final int SINE_REBUILD_DEBOUNCE_MS = 80;

    /** Debounces the sine generator's rebuild so a slider drag (which can fire this many times a
     * second) only actually rebuilds once the value has settled for a moment, instead of on every
     * intermediate tick - each rebuild is a real pop risk (see crossfadeToSineFreq()'s doc), so
     * fewer rebuilds directly means fewer chances to hear one. */
    private void scheduleSineRebuild(int freq) {
        if (pendingSineRebuild != null) handler.removeCallbacks(pendingSineRebuild);
        pendingSineRebuild = () -> { pendingSineRebuild = null; crossfadeToSineFreq(freq); };
        handler.postDelayed(pendingSineRebuild, SINE_REBUILD_DEBOUNCE_MS);
    }

    /** Swaps to a new frequency with a short volume crossfade instead of a hard cut - the old
     * buffer gets interrupted at an arbitrary point in its waveform (not necessarily a zero
     * crossing), while the new one always starts at sin(0)=0, so a straight release()+play()
     * is an audible amplitude discontinuity every single time. Ramping the old one out and the
     * new one in over a few milliseconds hides that jump under the ramp instead of exposing it
     * as a click. */
    private void crossfadeToSineFreq(int freq) {
        LoopTrack old = acSine;
        LoopTrack fresh = buildSineLoopTrack(freq);
        fresh.track.setVolume(0f);
        fresh.track.play();
        if (spectrumAnalyzer != null) spectrumAnalyzer.attachToSession(fresh.track.getAudioSessionId());
        acSine = fresh;

        int steps = 4;
        int stepMs = 6;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            handler.postDelayed(() -> {
                fresh.track.setVolume(t);
                if (old != null) old.track.setVolume(1f - t);
            }, (long) i * stepMs);
        }
        handler.postDelayed(() -> { if (old != null) { old.track.pause(); old.track.release(); } }, (long) (steps + 1) * stepMs);
    }

    /** Builds a seamless single-tone loop at freqHz: the buffer holds exactly a whole number of
     * cycles (as close to 1 second as that allows), so the waveform's value AND slope both match
     * at the loop seam - the standard trick for a genuinely click-free tone loop, unlike pink
     * noise below where there's no "phase" to match. Mono, since a test tone has no reason to
     * differ L/R, and at reduced amplitude since this is a reference tone, not a mix. */
    private LoopTrack buildSineLoopTrack(int freqHz) {
        int cycles = Math.max(1, Math.round(freqHz * 1.0f));
        int frames = Math.round(cycles * AC_GEN_SAMPLE_RATE / (float) freqHz);
        byte[] pcm = new byte[frames * 2]; // mono, 16-bit
        double amplitude = 0.3 * Short.MAX_VALUE;
        for (int i = 0; i < frames; i++) {
            short s = (short) Math.round(amplitude * Math.sin(2 * Math.PI * freqHz * i / AC_GEN_SAMPLE_RATE));
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AudioTrack track = buildStaticLoopAudioTrack(pcm, AC_GEN_SAMPLE_RATE, 1);
        track.setLoopPoints(0, frames, -1);
        return new LoopTrack(track, frames, AC_GEN_SAMPLE_RATE);
    }

    /** Builds a logarithmic ("exponential") round-trip sweep: low to high, then back down to low,
     * looped - so the loop seam joins low to low (same frequency) instead of snapping from high
     * back to low every repeat. durationSec is the full up+down cycle, split evenly between the
     * two legs. Frequency moves at a constant ratio-per-second rather than a constant Hz-per-
     * second on each leg - same log reasoning as posToFreq() - so each leg's phase is the integral
     * of its own exponentially-changing instantaneous frequency (the standard exponential-chirp
     * formula), not a naive per-sample increment. The down leg's phase picks up exactly where the
     * up leg's left off (phaseAtTurn), so the waveform stays continuous through the turnaround at
     * the top, not just within each leg - the only remaining seam is the loop point itself, where
     * frequency now matches but phase generally won't exactly, same residual the sine generator's
     * own crossfade exists to hide (not applied here since this is one continuous loop, not a
     * rebuild).
     * <p>When normalize is set, each sample is additionally scaled by hearingThresholdGain() at
     * that instant's frequency - see its own doc for what that compensates for and why it only
     * ever attenuates, never boosts past the existing 0.3*full-scale ceiling. */
    private LoopTrack buildSweepLoopTrack(float startHz, float endHz, float durationSec, boolean normalize) {
        int halfFrames = Math.max(1, Math.round(durationSec * AC_GEN_SAMPLE_RATE / 2f));
        int frames = halfFrames * 2;
        byte[] pcm = new byte[frames * 2]; // mono, 16-bit
        double amplitude = 0.3 * Short.MAX_VALUE;
        float halfDuration = halfFrames / (float) AC_GEN_SAMPLE_RATE;
        boolean constant = Math.abs(endHz - startHz) < 0.01f;
        double kUp = constant ? 0 : Math.log(endHz / (double) startHz) / halfDuration;
        double kDown = constant ? 0 : Math.log(startHz / (double) endHz) / halfDuration;
        double tqMax = normalize ? hearingThresholdMaxDb(Math.min(startHz, endHz), Math.max(startHz, endHz)) : 0;

        double phaseAtTurn = 0;
        for (int i = 0; i < halfFrames; i++) {
            double t = i / (double) AC_GEN_SAMPLE_RATE;
            double fInstant = constant ? startHz : startHz * Math.exp(kUp * t);
            double phase = constant
                    ? 2 * Math.PI * startHz * t
                    : 2 * Math.PI * startHz / kUp * (Math.exp(kUp * t) - 1);
            phaseAtTurn = phase;
            double amp = normalize ? amplitude * hearingThresholdGain(fInstant, tqMax) : amplitude;
            short s = (short) Math.round(amp * Math.sin(phase));
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        for (int i = 0; i < halfFrames; i++) {
            double t = i / (double) AC_GEN_SAMPLE_RATE;
            double fInstant = constant ? endHz : endHz * Math.exp(kDown * t);
            double legPhase = constant
                    ? 2 * Math.PI * endHz * t
                    : 2 * Math.PI * endHz / kDown * (Math.exp(kDown * t) - 1);
            double amp = normalize ? amplitude * hearingThresholdGain(fInstant, tqMax) : amplitude;
            short s = (short) Math.round(amp * Math.sin(phaseAtTurn + legPhase));
            int idx = halfFrames + i;
            pcm[idx * 2] = (byte) (s & 0xFF);
            pcm[idx * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AudioTrack track = buildStaticLoopAudioTrack(pcm, AC_GEN_SAMPLE_RATE, 1);
        track.setLoopPoints(0, frames, -1);
        return new LoopTrack(track, frames, AC_GEN_SAMPLE_RATE);
    }

    // ISO 226:2003 free-field threshold-of-hearing (0 phon) curve - dB SPL needed to just detect
    // a tone at each listed Hz. This is the actual standardized, "well documented" human
    // sensitivity data (superseding the older Fletcher-Munson/Robinson-Dadson determinations),
    // the real-world source a genuine perceptual sweep is built from - not a formula
    // approximation. The standard itself only defines points up to 12500Hz; 16000/20000 are a
    // smooth, bounded extension past that (individual high-frequency thresholds vary hugely
    // anyway) so a sweep reaching 20kHz still has a sane reference instead of undefined behavior.
    private static final float[] ISO226_HZ = {
            20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000,
            1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
    };
    private static final float[] ISO226_DB = {
            74.3f, 65.0f, 56.3f, 48.4f, 41.7f, 35.5f, 29.8f, 25.1f, 20.7f, 16.8f, 13.8f, 11.2f,
            8.9f, 7.2f, 6.0f, 5.0f, 4.4f, 4.2f, 3.7f, 2.6f, 1.0f, -1.2f, -3.6f, -3.9f, -1.1f, 6.6f,
            15.3f, 16.4f, 11.6f, 20.0f, 40.0f
    };

    /** The ISO 226:2003 threshold-of-hearing curve, interpolated on a log-frequency axis between
     * its standard table points (same log reasoning as posToFreq() - the curve's features are
     * spaced by ratio, not by raw Hz). Frequencies outside the table's own range clamp to its
     * nearest endpoint rather than extrapolating further. */
    private static double hearingThresholdDb(double freqHz) {
        float f = (float) Math.max(ISO226_HZ[0], Math.min(ISO226_HZ[ISO226_HZ.length - 1], freqHz));
        int i = 0;
        while (i < ISO226_HZ.length - 2 && ISO226_HZ[i + 1] < f) i++;
        float f0 = ISO226_HZ[i], f1 = ISO226_HZ[i + 1];
        double t = (Math.log(f) - Math.log(f0)) / (Math.log(f1) - Math.log(f0));
        return ISO226_DB[i] + t * (ISO226_DB[i + 1] - ISO226_DB[i]);
    }

    /** The highest (least sensitive) point of the threshold-of-hearing curve within [loHz, hiHz] -
     * sampled at 200 log-spaced points since the curve has a dip around 1-5kHz, so the max over an
     * arbitrary sub-range isn't just its two endpoints. This is the anchor hearingThresholdGain()
     * normalizes against: the frequency that needs the loudest signal to be heard at all keeps the
     * sweep's existing full amplitude, and every more-sensitive frequency gets quieter relative to
     * it - never the other way around, so normalizing can only ever reduce level, never risk
     * clipping by boosting past what the sweep already plays at. */
    private static double hearingThresholdMaxDb(float loHz, float hiHz) {
        double max = Double.NEGATIVE_INFINITY;
        int steps = 200;
        double logLo = Math.log(loHz), logHi = Math.log(Math.max(hiHz, loHz * 1.0001));
        for (int i = 0; i <= steps; i++) {
            double f = Math.exp(logLo + (logHi - logLo) * i / steps);
            max = Math.max(max, hearingThresholdDb(f));
        }
        return max;
    }

    /** Linear amplitude multiplier (<=1) that cancels out the ear's own threshold-of-hearing shape
     * across the sweep - see the Background/Description on audiocheck.net's own Perceptual Sweep
     * page (https://www.audiocheck.net/testtones_perceptualsinesweep.php), which this mirrors:
     * playing this at the point where it's just barely audible is what actually makes it sound
     * flat, since that's specifically where the threshold curve's shape (not a louder-listening
     * equal-loudness contour) applies. No floor on the attenuation here - the real threshold
     * curve's span can be 80dB+ edge to edge on a full-range sweep, and that's correctly
     * representable in 16-bit PCM; what makes it audible is turning the playback volume up until
     * the loudest part (tqMaxDb's frequency) is itself just barely audible, same as the source
     * site instructs, not propping up the quiet parts artificially. */
    private static double hearingThresholdGain(double freqHz, double tqMaxDb) {
        double gainDb = hearingThresholdDb(freqHz) - tqMaxDb;
        return Math.pow(10.0, gainDb / 20.0);
    }

    /** Pink noise via Paul Kellet's well-known refined 3-pole IIR approximation, driven by white
     * noise - standard, cheap, good enough for a speaker-test signal (doesn't need to be
     * mathematically exact pink noise, just close). A few seconds long so the loop point isn't
     * rhythmically noticeable - unlike the sine above, random noise has no phase to match at the
     * seam, so this just relies on being long enough that any discontinuity is inaudible under
     * the signal itself. */
    private LoopTrack buildPinkNoiseLoopTrack() {
        int frames = AC_GEN_SAMPLE_RATE * 5;
        byte[] pcm = new byte[frames * 2];
        java.util.Random rnd = new java.util.Random();
        float b0 = 0, b1 = 0, b2 = 0;
        for (int i = 0; i < frames; i++) {
            float white = rnd.nextFloat() * 2f - 1f;
            b0 = 0.99765f * b0 + white * 0.0990460f;
            b1 = 0.96300f * b1 + white * 0.2965164f;
            b2 = 0.57000f * b2 + white * 1.0526913f;
            float pink = (b0 + b1 + b2 + white * 0.1848f) * 0.1f;
            pink = Math.max(-1f, Math.min(1f, pink));
            short s = (short) Math.round(pink * Short.MAX_VALUE);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        AudioTrack track = buildStaticLoopAudioTrack(pcm, AC_GEN_SAMPLE_RATE, 1);
        track.setLoopPoints(0, frames, -1);
        return new LoopTrack(track, frames, AC_GEN_SAMPLE_RATE);
    }

    /** Raw PCM + format pulled out of a WAV resource by loadWav(). */
    private static class WavPcm {
        final int sampleRate, channels, bitsPerSample;
        final byte[] pcm;
        final int frameCount;
        WavPcm(int sampleRate, int channels, int bitsPerSample, byte[] pcm) {
            this.sampleRate = sampleRate; this.channels = channels; this.bitsPerSample = bitsPerSample;
            this.pcm = pcm;
            this.frameCount = pcm.length / (channels * (bitsPerSample / 8));
        }
    }

    /** Only one speaker-test file plays at a time (see activeSpeakerBtnId's doc) - tapping the
     * currently-active button stops it early and clears the fader override (AUDIOCHECK_FADER
     * with fr/lr -1 - see McuService's receiver); tapping a different one switches straight to
     * it. One-shot (no loop): the OnCompletionListener clears the override on its own once
     * playback finishes, so you don't have to tap again just to put the fader back. */
    private void handleSpeakerButton(int btnId, int rawResId, int faderFr, int faderLr) {
        if (mpAcSpeaker != null) { mpAcSpeaker.stop(); mpAcSpeaker.release(); mpAcSpeaker = null; }
        boolean wasActive = activeSpeakerBtnId == btnId;
        activeSpeakerBtnId = 0;

        Intent intent = new Intent("com.radiorubka.wdsp.AUDIOCHECK_FADER");
        intent.setPackage(getPackageName());
        if (wasActive) {
            intent.putExtra("fr", -1); intent.putExtra("lr", -1);
            sendBroadcast(intent);
            return;
        }

        activeSpeakerBtnId = btnId;
        mpAcSpeaker = MediaPlayer.create(this, rawResId);
        if (mpAcSpeaker != null) {
            mpAcSpeaker.setOnCompletionListener(mp -> {
                if (activeSpeakerBtnId == btnId) handleSpeakerButton(btnId, rawResId, faderFr, faderLr);
            });
            mpAcSpeaker.start();
        }
        intent.putExtra("fr", faderFr); intent.putExtra("lr", faderLr);
        sendBroadcast(intent);
    }

    /** Stops every Audio Check loop and clears both runtime overrides via the stem/Only-Sub
     * switches' own listeners (unchecking them) plus a direct fader-clear broadcast for the
     * speaker buttons, which aren't backed by a switch. Deliberately NOT called on tab-switch or
     * onPause() - a test is meant to keep running while you work on other tabs or briefly lose
     * focus (a notification, a permission dialog). Called from onStop() (actually minimizing/
     * leaving the app - see its own doc for why that's the line) and onDestroy(). */
    private void stopAllAudioCheck() {
        if (switchAcBass != null) switchAcBass.setChecked(false);
        if (switchAcVocal != null) switchAcVocal.setChecked(false);
        if (switchAcDrums != null) switchAcDrums.setChecked(false);
        if (switchAcMelody != null) switchAcMelody.setChecked(false);
        if (switchAcPinkNoise != null) switchAcPinkNoise.setChecked(false);
        if (switchAcSine != null) switchAcSine.setChecked(false);
        if (switchAcOnlySub != null) switchAcOnlySub.setChecked(false);
        stopAcSweep();
        updateAcToneSmoothing();

        if (mpAcSpeaker != null) { mpAcSpeaker.stop(); mpAcSpeaker.release(); mpAcSpeaker = null; }
        activeSpeakerBtnId = 0;
        Intent faderIntent = new Intent("com.radiorubka.wdsp.AUDIOCHECK_FADER");
        faderIntent.setPackage(getPackageName());
        faderIntent.putExtra("fr", -1); faderIntent.putExtra("lr", -1);
        sendBroadcast(faderIntent);
    }

    /** Actually frees the stems' native AudioTrack buffers - stopAllAudioCheck() above only
     * pauses them (so resuming the Activity doesn't have to re-parse and re-write each WAV),
     * so this is separate and only called from onDestroy(), not onPause(). */
    private void releaseAudioCheckTracks() {
        if (acBass != null) { acBass.track.release(); acBass = null; }
        if (acVocal != null) { acVocal.track.release(); acVocal = null; }
        if (acDrums != null) { acDrums.track.release(); acDrums = null; }
        if (acMelody != null) { acMelody.track.release(); acMelody = null; }
        if (acPinkNoise != null) { acPinkNoise.track.release(); acPinkNoise = null; }
        if (acSine != null) { acSine.track.release(); acSine = null; }
        if (acSweep != null) { acSweep.track.release(); acSweep = null; }
    }

    private void savePresetList() { getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putStringSet(PREF_PRESET_NAMES, new HashSet<>(presetNames)).apply(); }
    // Debounces autoSaveCurrent()'s actual write - see its own doc for why narrowing which keys
    // go INTO the Editor (an earlier attempt at this) doesn't help: SharedPreferences.apply()
    // always re-serializes its entire backing map to the XML file on disk, regardless of how many
    // keys changed in that one transaction, so the only real lever is how often apply() gets
    // called at all. A slider drag fires many onChange ticks a second, each currently calling
    // autoSaveCurrent() - this collapses a whole burst of those into one write, shortly after the
    // user actually stops moving something.
    private static final int AUTOSAVE_DEBOUNCE_MS = 300;
    private Runnable pendingAutoSave;

    /** Saves the current preset's full state - called from every slider/switch/spinner listener
     * in the app, so this fires constantly (e.g. on every tick of a slider drag, not just on
     * release). Debounced (see AUTOSAVE_DEBOUNCE_MS's doc) rather than writing synchronously every
     * time, since each actual write is a full-file disk rewrite. flushPendingAutoSave() is called
     * from onPause() so a change never gets lost if the app is backgrounded mid-debounce.
     * <p>The target preset name is captured right now, NOT read lazily when the debounced write
     * actually fires - if the user switched to editing a different preset in those 300ms, this
     * still has to land on the ORIGINAL preset the change was actually made to, not whatever the
     * spinner happens to say by the time this runs. */
    private void autoSaveCurrent() {
        if (pendingAutoSave != null) handler.removeCallbacks(pendingAutoSave);
        String targetPreset = spinnerPresets.getText().toString();
        pendingAutoSave = () -> {
            pendingAutoSave = null;
            savePreset(targetPreset);
        };
        handler.postDelayed(pendingAutoSave, AUTOSAVE_DEBOUNCE_MS);
    }

    /** Runs any debounced autoSaveCurrent() write immediately instead of waiting out the rest of
     * its delay - call before anything that could end the process (onPause()) so a change made
     * right before backgrounding/closing the app isn't silently dropped. */
    private void flushPendingAutoSave() {
        if (pendingAutoSave != null) {
            handler.removeCallbacks(pendingAutoSave);
            pendingAutoSave.run();
        }
    }

    private int getSystemVolume() { return VolumeHelper.getVolume(); }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (spectrumAnalyzer != null) spectrumAnalyzer.stop();
        // Deliberately not stopped until the Activity is actually destroyed, not merely paused
        // (see stopAllAudioCheck()'s own doc) - a test should survive the app briefly losing
        // foreground focus (a notification, switching apps) just as much as switching tabs.
        stopAllAudioCheck();
        releaseAudioCheckTracks();
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
        if (spectrumAnalyzer != null) spectrumAnalyzer.setFaderBalance((float) fr, (float) lr);
    }

    private void resetUiInternal() {
        isUpdatingUi = true; for (Slider s : gainSliders) s.setValue(6f); for (int i = 0; i<AudioConfig.NUM_BANDS; i++) updateDbLabel(i, 6);
        if (isFullyInitialized) {
            for (ToggleButton t : qSwitches) t.setChecked(false); seekSubGain.setValue(0); spinnerSubFreq.setText(SUB_FREQS[NO_SUB_INDEX], false); Globals.currentSubFreqHz = parseSubFreqHz(SUB_FREQS[NO_SUB_INDEX]); updateSubDependentControlsEnabled(true);
            seekFaderLr.setValue(12); seekFaderFr.setValue(12); updateFaderLabels(); switchLoud.setChecked(false);
            switchFmEnable.setChecked(false); switchFatigueEnable.setChecked(false); switchFmSubComp.setChecked(false);
            seekFmCalVol.setValue(25); seekFmStrength.setValue(100);
            seekFatStartVol.setValue(25); tvFatStartVolVal.setText(String.valueOf(25));
            switchUltraBass.setChecked(false);
            seekUltraBassStartVol.setValue(16); tvUltraBassStartVolVal.setText(String.valueOf(16));
            seekUltraBassMaxDb.setValue(6); tvUltraBassMaxDbVal.setText(getString(R.string.lbl_db_fmt, 6));
            updateFmGroupVisibility();

            // GALA reset
            switchGalaEnable.setChecked(false);
            switchGalaDisableForPreset.setChecked(false);
            seekGalaInc.setValue(20);
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
