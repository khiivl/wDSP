package com.radiorubka.wdsp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.content.res.ColorStateList;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.radiorubka.wdsp.ui.SettingsAccordion;
import com.radiorubka.wdsp.ui.TouchGlow;
import com.radiorubka.wdsp.ui.theme.ThemeManager;
import com.radiorubka.wdsp.ui.views.HueWheelView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG = "wDSP_Settings";

    private View rootSettings;
    private LinearLayout settingsColumn;
    private BottomNavigationView bottomNav;

    // Theme Mode
    private TextView btnThemeDay, btnThemeNight, btnThemeAuto;
    private boolean editNight;

    // 4 Hue Wheels
    private HueWheelView pickerAccentWheel, pickerPrimaryTextWheel, pickerLabelWheel, pickerOnAccentWheel;
    private SeekBar pickerAccentBrightness, pickerPrimaryTextBrightness, pickerLabelBrightness, pickerOnAccentBrightness;

    // Wallpaper
    private TextView labelWallpaper, wallpaperName;
    private TextView btnWallpaperPick, btnWallpaperReset, btnSolidWallpaper;
    private View layoutSolidControls;
    private SeekBar seekSolidHue, seekSolidVal;
    private TextView tvSolidHueVal, tvSolidValVal;
    private View bgSolidHue, bgSolidVal, bgStatusBarHue;

    // Status Bar Visualizer
    private SwitchCompat switchStatusBarVis, switchStatusBarNormalization;
    private Slider seekStatusBarWidth, seekStatusBarPos;
    private SeekBar seekStatusBarHue;
    private TextView tvStatusBarWidth, tvStatusBarPos, tvStatusBarHue;
    private TextView btnThemeSpectrum, btnThemeSolidHue, btnThemeAutoDayNight, btnThemeEqGroups;
    private TextView btnThemeWhite, btnThemeBlack, btnThemeFire, btnThemeNeon;
    private TextView btnStatusBarBands16, btnStatusBarBands32;
    private TextView btnOverlayPerm;

    // EQ Visualizer
    private SwitchCompat switchEqVisualizerEnable, switchEqVisNormalization;
    private TextView btnEqVisSpectrum, btnEqVisMonochrome;

    // Permissions & Backup
    private TextView btnBatteryOpt, btnAudioPerm, btnLocationPerm, btnAppDetails;
    private TextView btnBackupSettings, btnRestoreSettings;

    private ActivityResultLauncher<String[]> wallpaperPickerLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<String> backupLauncher;
    private ActivityResultLauncher<String[]> restoreLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        rootSettings = findViewById(R.id.root_settings);
        settingsColumn = findViewById(R.id.settings_column);
        editNight = ThemeManager.isNight(this);

        initLauncher();
        initViews();
        bindAccordion();
        loadSettings();
        applyTheme();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        if ("com.radiorubka.wdsp.ACTION_BACKUP".equals(action)) {
            String path = intent.getStringExtra("path");
            if (path != null) {
                backupToFile(new File(path));
            }
        } else if ("com.radiorubka.wdsp.ACTION_RESTORE".equals(action)) {
            String path = intent.getStringExtra("path");
            if (path != null) {
                restoreFromFile(new File(path));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSettings();
        applyTheme();
    }

    private void initLauncher() {
        wallpaperPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            );
                        } catch (Exception ignored) {
                        }
                        ThemeManager.setWallpaperUri(this, editNight, uri.toString());
                        ThemeManager.setSolidWallpaper(this, editNight, false);
                        loadSettings();
                        applyTheme();
                    }
                }
        );

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    loadSettings();
                    applyTheme();
                }
        );

        backupLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri != null) {
                        backupAllSettings(uri);
                    }
                }
        );

        restoreLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        restoreAllSettings(uri);
                    }
                }
        );
    }

    private void initViews() {
        bottomNav = findViewById(R.id.bottom_navigation);
        if (bottomNav != null) {
            bottomNav.setSelectedItemId(R.id.nav_settings);
            bottomNav.setOnItemSelectedListener(item -> {
                int id = item.getItemId();
                if (id == R.id.nav_settings) {
                    return true;
                }
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("target_tab", id);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
                return true;
            });
        }

        // Theme modes
        btnThemeDay = findViewById(R.id.btn_theme_day);
        btnThemeNight = findViewById(R.id.btn_theme_night);
        btnThemeAuto = findViewById(R.id.btn_theme_auto);

        TouchGlow.attach(btnThemeDay);
        TouchGlow.attach(btnThemeNight);
        TouchGlow.attach(btnThemeAuto);

        btnThemeDay.setOnClickListener(v -> {
            ThemeManager.setThemeMode(this, ThemeManager.THEME_MODE_DAY);
            editNight = false;
            loadSettings();
            applyTheme();
        });
        btnThemeNight.setOnClickListener(v -> {
            ThemeManager.setThemeMode(this, ThemeManager.THEME_MODE_NIGHT);
            editNight = true;
            loadSettings();
            applyTheme();
        });
        btnThemeAuto.setOnClickListener(v -> {
            ThemeManager.setThemeMode(this, ThemeManager.THEME_MODE_AUTO);
            editNight = ThemeManager.isNight(this);
            loadSettings();
            applyTheme();
        });

        // 4 Hue Wheels
        pickerAccentWheel = findViewById(R.id.picker_accent_wheel);
        pickerAccentBrightness = findViewById(R.id.picker_accent_brightness);
        pickerPrimaryTextWheel = findViewById(R.id.picker_primary_text_wheel);
        pickerPrimaryTextBrightness = findViewById(R.id.picker_primary_text_brightness);
        pickerLabelWheel = findViewById(R.id.picker_label_wheel);
        pickerLabelBrightness = findViewById(R.id.picker_label_brightness);
        pickerOnAccentWheel = findViewById(R.id.picker_on_accent_wheel);
        pickerOnAccentBrightness = findViewById(R.id.picker_on_accent_brightness);

        setupColorWheel(pickerAccentWheel, pickerAccentBrightness, 0);
        setupColorWheel(pickerPrimaryTextWheel, pickerPrimaryTextBrightness, 1);
        setupColorWheel(pickerLabelWheel, pickerLabelBrightness, 2);
        setupColorWheel(pickerOnAccentWheel, pickerOnAccentBrightness, 3);

        // Wallpaper controls
        labelWallpaper = findViewById(R.id.label_wallpaper);
        wallpaperName = findViewById(R.id.wallpaper_name);
        btnWallpaperPick = findViewById(R.id.btn_wallpaper_pick);
        btnWallpaperReset = findViewById(R.id.btn_wallpaper_reset);
        btnSolidWallpaper = findViewById(R.id.btn_solid_wallpaper);
        layoutSolidControls = findViewById(R.id.layout_solid_controls);
        seekSolidHue = findViewById(R.id.seek_solid_hue);
        seekSolidVal = findViewById(R.id.seek_solid_val);
        tvSolidHueVal = findViewById(R.id.tv_solid_hue_val);
        tvSolidValVal = findViewById(R.id.tv_solid_val_val);
        bgSolidHue = findViewById(R.id.bg_solid_hue);
        bgSolidVal = findViewById(R.id.bg_solid_val);

        TouchGlow.attach(btnWallpaperPick);
        TouchGlow.attach(btnWallpaperReset);
        TouchGlow.attach(btnSolidWallpaper);

        btnWallpaperPick.setOnClickListener(v -> {
            try {
                wallpaperPickerLauncher.launch(new String[]{"image/*"});
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.toast_saf_error, e.getMessage()),
                        Toast.LENGTH_SHORT).show();
            }
        });

        btnWallpaperReset.setOnClickListener(v -> {
            ThemeManager.setWallpaperUri(this, editNight, null);
            ThemeManager.setSolidWallpaper(this, editNight, false);
            loadSettings();
            applyTheme();
        });

        btnSolidWallpaper.setOnClickListener(v -> {
            boolean current = ThemeManager.isSolidWallpaper(this, editNight);
            ThemeManager.setSolidWallpaper(this, editNight, !current);
            loadSettings();
            applyTheme();
        });

        initSolidControls();

        // Status Bar Visualizer
        switchStatusBarVis = findViewById(R.id.switch_status_bar_vis);
        switchStatusBarNormalization = findViewById(R.id.switch_status_bar_normalization);
        seekStatusBarWidth = findViewById(R.id.seek_status_bar_width);
        seekStatusBarPos = findViewById(R.id.seek_status_bar_pos);
        seekStatusBarHue = findViewById(R.id.seek_status_bar_hue);
        tvStatusBarWidth = findViewById(R.id.tv_status_bar_width);
        tvStatusBarPos = findViewById(R.id.tv_status_bar_pos);
        tvStatusBarHue = findViewById(R.id.tv_status_bar_hue);
        bgStatusBarHue = findViewById(R.id.bg_status_bar_hue);

        btnThemeSpectrum = findViewById(R.id.btn_theme_spectrum);
        btnThemeSolidHue = findViewById(R.id.btn_theme_solid_hue);
        btnThemeAutoDayNight = findViewById(R.id.btn_theme_auto_day_night);
        btnThemeEqGroups = findViewById(R.id.btn_theme_eq_groups);
        btnThemeWhite = findViewById(R.id.btn_theme_white);
        btnThemeBlack = findViewById(R.id.btn_theme_black);
        btnThemeFire = findViewById(R.id.btn_theme_fire);
        btnThemeNeon = findViewById(R.id.btn_theme_neon);
        btnStatusBarBands16 = findViewById(R.id.btn_status_bar_bands_16);
        btnStatusBarBands32 = findViewById(R.id.btn_status_bar_bands_32);

        TouchGlow.attach(btnThemeSpectrum);
        TouchGlow.attach(btnThemeSolidHue);
        TouchGlow.attach(btnThemeAutoDayNight);
        TouchGlow.attach(btnThemeEqGroups);
        TouchGlow.attach(btnThemeWhite);
        TouchGlow.attach(btnThemeBlack);
        TouchGlow.attach(btnThemeFire);
        TouchGlow.attach(btnThemeNeon);
        TouchGlow.attach(btnStatusBarBands16);
        TouchGlow.attach(btnStatusBarBands32);

        if (btnStatusBarBands16 != null) {
            btnStatusBarBands16.setOnClickListener(v -> {
                StatusBarVisualizerManager.getInstance(this).setBandCount(16);
                updateStatusBarBandsHighlights(16);
            });
        }
        if (btnStatusBarBands32 != null) {
            btnStatusBarBands32.setOnClickListener(v -> {
                StatusBarVisualizerManager.getInstance(this).setBandCount(32);
                updateStatusBarBandsHighlights(32);
            });
        }

        initStatusBarVisualizerControls();
        initAnalyzerControls();

        // EQ Spectrum Visualizer
        switchEqVisualizerEnable = findViewById(R.id.switch_eq_visualizer_enable);
        switchEqVisNormalization = findViewById(R.id.switch_vis_normalization);
        btnEqVisSpectrum = findViewById(R.id.btn_eq_vis_spectrum);
        btnEqVisMonochrome = findViewById(R.id.btn_eq_vis_monochrome);

        TouchGlow.attach(btnEqVisSpectrum);
        TouchGlow.attach(btnEqVisMonochrome);

        switchEqVisualizerEnable.setOnCheckedChangeListener((btn, isChecked) -> {
            ThemeManager.prefs(this).edit().putBoolean("pref_eq_visualizer_enabled", isChecked).apply();
        });

        if (switchStatusBarNormalization != null) {
            switchStatusBarNormalization.setOnCheckedChangeListener((btn, isChecked) -> {
                ThemeManager.prefs(this).edit().putBoolean(StatusBarVisualizerManager.PREF_STATUS_BAR_NORMALIZATION, isChecked).apply();
                StatusBarVisualizerManager.getInstance(this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_NORMALIZATION);
            });
        }
        if (switchEqVisNormalization != null) {
            switchEqVisNormalization.setOnCheckedChangeListener((btn, isChecked) -> {
                ThemeManager.prefs(this).edit().putBoolean("pref_eq_visualizer_normalization", isChecked).apply();
            });
        }

        btnEqVisSpectrum.setOnClickListener(v -> {
            ThemeManager.prefs(this).edit().putInt("pref_eq_visualizer_mode", 0).apply();
            updateEqVisModeHighlights(0);
        });

        btnEqVisMonochrome.setOnClickListener(v -> {
            ThemeManager.prefs(this).edit().putInt("pref_eq_visualizer_mode", 1).apply();
            updateEqVisModeHighlights(1);
        });

        // Permissions & Backup
        btnBatteryOpt = findViewById(R.id.btn_battery_opt);
        btnOverlayPerm = findViewById(R.id.btn_overlay_perm);
        btnAudioPerm = findViewById(R.id.btn_audio_perm);
        btnLocationPerm = findViewById(R.id.btn_location_perm);
        btnAppDetails = findViewById(R.id.btn_app_details);
        btnBackupSettings = findViewById(R.id.btn_backup_settings);
        btnRestoreSettings = findViewById(R.id.btn_restore_settings);

        TouchGlow.attach(btnBatteryOpt);
        TouchGlow.attach(btnOverlayPerm);
        TouchGlow.attach(btnAudioPerm);
        TouchGlow.attach(btnLocationPerm);
        TouchGlow.attach(btnAppDetails);
        TouchGlow.attach(btnBackupSettings);
        TouchGlow.attach(btnRestoreSettings);

        btnBatteryOpt.setOnClickListener(v -> requestBatteryOptimization());
        btnOverlayPerm.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
        btnAudioPerm.setOnClickListener(v -> requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO));
        btnLocationPerm.setOnClickListener(v -> requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION));
        btnAppDetails.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        btnBackupSettings.setOnClickListener(v -> {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US);
            String filename = "wDSP_backup_" + sdf.format(new Date()) + ".json";
            // Straight to Download/wDSP rather than through a save dialog - see Downloads for why
            // the dialog and the load dialog could never be the same app. The picker stays as a
            // fallback for a ROM that refuses the media store.
            if (!backupToDownloads(filename)) {
                backupLauncher.launch(filename);
            }
        });

        btnRestoreSettings.setOnClickListener(v -> {
            restoreLauncher.launch(new String[]{"application/json", "*/*"});
        });
    }

    private void bindAccordion() {
        int accent = ThemeManager.accent(this, editNight);
        SettingsAccordion.build(settingsColumn, accent);
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    try {
                        startActivity(intent);
                    } catch (Exception ignored) {}
                }
            } else {
                intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                try {
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    private void setupColorWheel(HueWheelView wheel, SeekBar brightness, int slot) {
        brightness.setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        brightness.setThumb(ThemeManager.whiteThumbDrawable(this));
        updateWheelBrightnessGradient(wheel, brightness);

        wheel.setListener((hue, sat, finished) -> {
            updateWheelBrightnessGradient(wheel, brightness);
            float val = brightness.getProgress() / 100f;
            int color = Color.HSVToColor(new float[]{hue, sat, val});
            saveSlotColor(slot, color);
            if (finished) {
                applyTheme();
            }
        });

        brightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float hue = wheel.getHue();
                    float sat = wheel.getSat();
                    float val = progress / 100f;
                    int color = Color.HSVToColor(new float[]{hue, sat, val});
                    saveSlotColor(slot, color);
                    applyTheme();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyTheme();
            }
        });
    }

    private void updateWheelBrightnessGradient(HueWheelView wheel, SeekBar brightness) {
        if (wheel == null || brightness == null) return;
        int pureColor = Color.HSVToColor(new float[]{wheel.getHue(), wheel.getSat(), 1f});
        brightness.setBackground(ThemeManager.brightnessGradientDrawable(this, pureColor, 14f, 14));
    }

    private void saveSlotColor(int slot, int color) {
        switch (slot) {
            case 0:
                ThemeManager.setAccent(this, editNight, color);
                break;
            case 1:
                ThemeManager.setTextPrimary(this, editNight, color);
                break;
            case 2:
                ThemeManager.setTextSecondary(this, editNight, color);
                break;
            case 3:
                ThemeManager.setOnAccent(this, editNight, color);
                break;
        }
    }

    private void initSolidControls() {
        if (bgSolidHue != null) {
            bgSolidHue.setBackground(ThemeManager.hueGradientDrawable(this, 14f, 14));
        }

        seekSolidHue.setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        seekSolidHue.setThumb(ThemeManager.whiteThumbDrawable(this));

        seekSolidVal.setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        seekSolidVal.setThumb(ThemeManager.whiteThumbDrawable(this));

        SeekBar.OnSeekBarChangeListener solidListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float h = seekSolidHue.getProgress();
                float v = seekSolidVal.getProgress() / 100f;
                int newColor = Color.HSVToColor(new float[]{h, 1f, v});

                if (tvSolidHueVal != null) tvSolidHueVal.setText(getString(R.string.lbl_degrees_fmt, (int) h));
                if (tvSolidValVal != null) tvSolidValVal.setText(getString(R.string.lbl_percent_fmt, (int) (v * 100)));

                int pureColor = Color.HSVToColor(new float[]{h, 1f, 1f});
                if (bgSolidVal != null) {
                    bgSolidVal.setBackground(ThemeManager.brightnessGradientDrawable(SettingsActivity.this, pureColor, 14f, 14));
                }

                if (fromUser) {
                    ThemeManager.setSolidWallpaperColor(SettingsActivity.this, editNight, newColor);
                    ThemeManager.setSolidWallpaper(SettingsActivity.this, editNight, true);
                    applyTheme();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyTheme();
            }
        };

        seekSolidHue.setOnSeekBarChangeListener(solidListener);
        seekSolidVal.setOnSeekBarChangeListener(solidListener);
    }

    private void initStatusBarVisualizerControls() {
        SharedPreferences p = ThemeManager.prefs(this);

        if (bgStatusBarHue != null) {
            bgStatusBarHue.setBackground(ThemeManager.hueGradientDrawable(this, 14f, 14));
        }
        seekStatusBarHue.setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        seekStatusBarHue.setThumb(ThemeManager.whiteThumbDrawable(this));

        switchStatusBarVis.setOnCheckedChangeListener((btn, isChecked) -> {
            p.edit().putBoolean(StatusBarVisualizerManager.PREF_STATUS_BAR_ENABLED, isChecked).apply();
            StatusBarVisualizerManager.getInstance(SettingsActivity.this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_ENABLED);
        });

        seekStatusBarWidth.addOnChangeListener((slider, value, fromUser) -> {
            int progress = Math.round(value);
            tvStatusBarWidth.setText(getString(R.string.lbl_percent_fmt, progress));
            if (fromUser) {
                float f = progress / 100f;
                p.edit().putFloat(StatusBarVisualizerManager.PREF_STATUS_BAR_WIDTH_F, f).apply();
                StatusBarVisualizerManager.getInstance(SettingsActivity.this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_WIDTH_F);
            }
        });

        seekStatusBarPos.addOnChangeListener((slider, value, fromUser) -> {
            int progress = Math.round(value);
            tvStatusBarPos.setText(getString(R.string.lbl_percent_fmt, progress));
            if (fromUser) {
                float f = progress / 100f;
                p.edit().putFloat(StatusBarVisualizerManager.PREF_STATUS_BAR_POS_F, f).apply();
                StatusBarVisualizerManager.getInstance(SettingsActivity.this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_POS_F);
            }
        });

        seekStatusBarHue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvStatusBarHue.setText(getString(R.string.lbl_degrees_fmt, progress));
                if (fromUser) {
                    p.edit().putInt(StatusBarVisualizerManager.PREF_STATUS_BAR_HUE, progress).apply();
                    StatusBarVisualizerManager.getInstance(SettingsActivity.this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_HUE);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        setupThemeButton(btnThemeSpectrum, StatusBarVisualizerView.THEME_SPECTRUM);
        setupThemeButton(btnThemeSolidHue, StatusBarVisualizerView.THEME_SOLID_HUE);
        setupThemeButton(btnThemeAutoDayNight, StatusBarVisualizerView.THEME_AUTO_DAY_NIGHT);
        setupThemeButton(btnThemeEqGroups, StatusBarVisualizerView.THEME_EQ_GROUPS);
        setupThemeButton(btnThemeWhite, StatusBarVisualizerView.THEME_MONOCHROME_WHITE);
        setupThemeButton(btnThemeBlack, StatusBarVisualizerView.THEME_MONOCHROME_BLACK);
        setupThemeButton(btnThemeFire, StatusBarVisualizerView.THEME_FIRE);
        setupThemeButton(btnThemeNeon, StatusBarVisualizerView.THEME_NEON);
    }

    private void setupThemeButton(TextView btn, int themeIndex) {
        btn.setOnClickListener(v -> {
            ThemeManager.prefs(this).edit().putInt(StatusBarVisualizerManager.PREF_STATUS_BAR_THEME, themeIndex).apply();
            StatusBarVisualizerManager.getInstance(this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_THEME);
            updateThemeButtonHighlights(themeIndex);
        });
    }

    private void updateThemeButtonHighlights(int currentTheme) {
        TextView[] btns = {btnThemeSpectrum, btnThemeSolidHue, btnThemeAutoDayNight, btnThemeEqGroups,
                btnThemeWhite, btnThemeBlack, btnThemeFire, btnThemeNeon};
        for (int i = 0; i < btns.length; i++) {
            styleToggleButton(btns[i], i == currentTheme);
        }
    }

    private void updateThemeModeButtons(int mode) {
        styleToggleButton(btnThemeDay, mode == ThemeManager.THEME_MODE_DAY);
        styleToggleButton(btnThemeNight, mode == ThemeManager.THEME_MODE_NIGHT);
        styleToggleButton(btnThemeAuto, mode == ThemeManager.THEME_MODE_AUTO);
    }

    private void updatePermissionButtons() {
        int accent = ThemeManager.accent(this, editNight);
        int border = ThemeManager.panelBorder(this, editNight);

        // 1. Battery Optimization
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean batteryGranted = (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()));
        stylePermissionButton(btnBatteryOpt, batteryGranted, getString(R.string.perm_battery_opt), accent, border);

        // 2. Overlay Permission
        boolean overlayGranted = Settings.canDrawOverlays(this);
        stylePermissionButton(btnOverlayPerm, overlayGranted, getString(R.string.settings_perm_overlay), accent, border);

        // 3. Audio Record
        boolean audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        stylePermissionButton(btnAudioPerm, audioGranted, getString(R.string.perm_audio_record), accent, border);

        // 4. GPS Location
        boolean locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        stylePermissionButton(btnLocationPerm, locationGranted, getString(R.string.perm_gps_location), accent, border);
    }

    private void stylePermissionButton(TextView btn, boolean granted, String title, int accent, int border) {
        if (btn == null) return;
        int primaryText = editNight ? 0xFFFFFFFF : 0xFF101418;
        int normalBtnBg = editNight ? Color.parseColor("#25FFFFFF") : Color.parseColor("#18000000");
        int ungrantedBorder = editNight ? Color.parseColor("#4DFFFFFF") : Color.parseColor("#4D000000");
        if (granted) {
            btn.setText("✓ " + title);
            btn.setTextColor(ThemeManager.getContrastingTextColor(accent));
            btn.setBackground(ThemeManager.roundedDrawable(this, 10f, accent, accent, 1.2f));
        } else {
            btn.setText(title);
            btn.setTextColor(primaryText);
            btn.setBackground(ThemeManager.roundedDrawable(this, 10f, normalBtnBg, ungrantedBorder, 1.2f));
        }
    }

    /**
     * Makes a button look like one, in either theme.
     *
     * The measurement and synchronise buttons started out with a plain drawable background, and in
     * practice they disappeared: the author of the app had to hunt for the synchronise button on
     * his own screen, knowing exactly where it was. Everything else on this screen that can be
     * pressed is filled with the accent colour, so these are too, with text picked for contrast
     * against whatever accent the user has chosen.
     */
    private void styleActionButton(TextView btn) {
        if (btn == null) return;
        int accent = ThemeManager.accent(this, editNight);
        btn.setTextColor(ThemeManager.getContrastingTextColor(accent));
        btn.setBackground(ThemeManager.roundedDrawable(this, 10f, accent, accent, 1.2f));
    }

    private void styleToggleButton(TextView btn, boolean active) {
        if (btn == null) return;
        int accent = ThemeManager.accent(this, editNight);
        int primaryText = editNight ? 0xFFFFFFFF : 0xFF101418;
        int border = editNight ? Color.parseColor("#4DFFFFFF") : Color.parseColor("#4D000000");
        int normalBtnBg = editNight ? Color.parseColor("#25FFFFFF") : Color.parseColor("#18000000");

        if (active) {
            btn.setTextColor(ThemeManager.getContrastingTextColor(accent));
            btn.setBackground(ThemeManager.roundedDrawable(this, 10f, accent, accent, 1.2f));
        } else {
            btn.setTextColor(primaryText);
            btn.setBackground(ThemeManager.roundedDrawable(this, 10f, normalBtnBg, border, 1.2f));
        }
    }

    private void loadSettings() {
        SharedPreferences p = ThemeManager.prefs(this);

        // Theme mode
        int mode = ThemeManager.getThemeMode(this);
        updateThemeModeButtons(mode);

        // 4 Hue Wheels
        loadColorToWheel(ThemeManager.accent(this, editNight), pickerAccentWheel, pickerAccentBrightness);
        loadColorToWheel(ThemeManager.textPrimary(this, editNight), pickerPrimaryTextWheel, pickerPrimaryTextBrightness);
        loadColorToWheel(ThemeManager.textSecondary(this, editNight), pickerLabelWheel, pickerLabelBrightness);
        loadColorToWheel(ThemeManager.onAccent(this, editNight), pickerOnAccentWheel, pickerOnAccentBrightness);

        // Wallpaper
        boolean isSolid = ThemeManager.isSolidWallpaper(this, editNight);
        String uriStr = ThemeManager.getWallpaperUri(this, editNight);

        if (isSolid) {
            wallpaperName.setText(R.string.settings_wallpaper_status_solid);
            layoutSolidControls.setVisibility(View.VISIBLE);
        } else if (uriStr != null) {
            wallpaperName.setText(getUriFileName(Uri.parse(uriStr)));
            layoutSolidControls.setVisibility(View.GONE);
        } else {
            wallpaperName.setText(R.string.settings_wallpaper_none);
            layoutSolidControls.setVisibility(View.GONE);
        }
        styleToggleButton(btnSolidWallpaper, isSolid);

        int solidColor = ThemeManager.getSolidWallpaperColor(this, editNight);
        float[] hsv = new float[3];
        Color.colorToHSV(solidColor, hsv);
        seekSolidHue.setProgress((int) hsv[0]);
        seekSolidVal.setProgress((int) (hsv[2] * 100));
        if (tvSolidHueVal != null) tvSolidHueVal.setText(getString(R.string.lbl_degrees_fmt, (int) hsv[0]));
        if (tvSolidValVal != null) tvSolidValVal.setText(getString(R.string.lbl_percent_fmt, (int) (hsv[2] * 100)));

        int pureColor = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
        if (bgSolidVal != null) {
            bgSolidVal.setBackground(ThemeManager.brightnessGradientDrawable(this, pureColor, 14f, 14));
        }

        // Visualizer
        switchStatusBarVis.setChecked(p.getBoolean(StatusBarVisualizerManager.PREF_STATUS_BAR_ENABLED, true));
        int w = Math.round(p.getFloat(StatusBarVisualizerManager.PREF_STATUS_BAR_WIDTH_F, 0.40f) * 100);
        int pos = Math.round(p.getFloat(StatusBarVisualizerManager.PREF_STATUS_BAR_POS_F, 0.50f) * 100);
        int hue = p.getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_HUE, 0);

        seekStatusBarWidth.setValue(w);
        seekStatusBarPos.setValue(pos);
        seekStatusBarHue.setProgress(hue);
        tvStatusBarWidth.setText(getString(R.string.lbl_percent_fmt, w));
        tvStatusBarPos.setText(getString(R.string.lbl_percent_fmt, pos));
        tvStatusBarHue.setText(getString(R.string.lbl_degrees_fmt, hue));

        int currentTheme = p.getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_THEME, StatusBarVisualizerView.THEME_SPECTRUM);
        updateThemeButtonHighlights(currentTheme);

        int bands = p.getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_BANDS, StatusBarVisualizerManager.DEFAULT_BANDS);
        updateStatusBarBandsHighlights(bands);

        // EQ Spectrum Visualizer
        boolean eqVisEnabled = p.getBoolean("pref_eq_visualizer_enabled", true);
        switchEqVisualizerEnable.setChecked(eqVisEnabled);
        int eqVisMode = p.getInt("pref_eq_visualizer_mode", 0);
        updateEqVisModeHighlights(eqVisMode);

        // Visualizer Normalization (AGC) - separate for Status Bar and EQ Visualizer
        if (switchStatusBarNormalization != null) {
            switchStatusBarNormalization.setChecked(p.getBoolean(StatusBarVisualizerManager.PREF_STATUS_BAR_NORMALIZATION, false));
        }
        if (switchEqVisNormalization != null) {
            switchEqVisNormalization.setChecked(p.getBoolean("pref_eq_visualizer_normalization", false));
        }

        loadAnalyzerSettings(p);

        // Permissions
        updatePermissionButtons();
    }

    // --- Точність аналізатора та синхронізація ---------------------------------------------------

    private SwitchCompat switchAgcMain, switchAgcBar;
    private SeekBar seekAgcMainStrength, seekAgcBarStrength, seekLatencyTrim, seekRangeDb;
    private TextView tvAgcMainStrength, tvAgcBarStrength, tvLatencyTrim, tvRangeDb;
    private TextView tvSyncStatus;
    private TextView tvRoomStatus;

    /** Trim slider spans +/-250 ms, stored centred on this offset because SeekBar has no sign. */
    private static final int LATENCY_TRIM_OFFSET = 250;

    private void initAnalyzerControls() {
        switchAgcMain = findViewById(R.id.switch_agc_main);
        switchAgcBar = findViewById(R.id.switch_agc_bar);
        seekAgcMainStrength = findViewById(R.id.seek_agc_main_strength);
        seekAgcBarStrength = findViewById(R.id.seek_agc_bar_strength);
        seekLatencyTrim = findViewById(R.id.seek_latency_trim);
        seekRangeDb = findViewById(R.id.seek_range_db);
        tvAgcMainStrength = findViewById(R.id.tv_agc_main_strength);
        tvAgcBarStrength = findViewById(R.id.tv_agc_bar_strength);
        tvLatencyTrim = findViewById(R.id.tv_latency_trim);
        tvRangeDb = findViewById(R.id.tv_range_db);

        switchAgcMain.setOnCheckedChangeListener((btn, checked) -> saveAnalyzerPref(
                AudioSpectrumEngine.PREF_AGC_MAIN_ENABLED, checked));
        switchAgcBar.setOnCheckedChangeListener((btn, checked) -> saveAnalyzerPref(
                AudioSpectrumEngine.PREF_AGC_BAR_ENABLED, checked));

        bindAnalyzerSeek(seekAgcMainStrength, tvAgcMainStrength,
                AudioSpectrumEngine.PREF_AGC_MAIN_STRENGTH, 0, getString(R.string.format_percent));
        bindAnalyzerSeek(seekAgcBarStrength, tvAgcBarStrength,
                AudioSpectrumEngine.PREF_AGC_BAR_STRENGTH, 0, getString(R.string.format_percent));
        bindAnalyzerSeek(seekLatencyTrim, tvLatencyTrim,
                AudioSpectrumEngine.PREF_LATENCY_TRIM, LATENCY_TRIM_OFFSET,
                getString(R.string.format_latency_ms));
        bindAnalyzerSeek(seekRangeDb, tvRangeDb,
                AudioSpectrumEngine.PREF_RANGE_DB, 0, getString(R.string.format_range_db));

        tvSyncStatus = findViewById(R.id.tv_sync_status);
        showSyncStatus();
        TextView syncButton = findViewById(R.id.btn_sync_measure);
        TouchGlow.attach(syncButton);
        syncButton.setOnClickListener(v -> startLatencyMeasurement());

        initDiagnostics();
    }

    // --- Diagnostics: cabin measurement ---------------------------------------------------------

    /**
     * The diagnostics fold: measure the car, then hand the result to whoever can look at it.
     *
     * The measurement is only half the job. It runs in someone else's car, hundreds of kilometres
     * away, so the numbers are worth nothing unless they can come back - which is why the second
     * button exists and why the recordings are kept rather than thrown away after analysis.
     */
    private void initDiagnostics() {
        tvRoomStatus = findViewById(R.id.tv_room_status);
        TextView measureButton = findViewById(R.id.btn_room_measure);
        TextView sendButton = findViewById(R.id.btn_room_send);
        TouchGlow.attach(measureButton);
        TouchGlow.attach(sendButton);
        measureButton.setOnClickListener(v -> startRoomMeasurement());
        sendButton.setOnClickListener(v -> shareRoomMeasurement());
        styleActionButtons();
        // The address is a link as well as a label: a tester who has never sent anything to a
        // developer should not have to work out where it goes.
        findViewById(R.id.tv_room_telegram).setOnClickListener(v -> openTelegram());
        showRoomStatus();
    }

    private void showRoomStatus() {
        if (tvRoomStatus == null) return;
        tvRoomStatus.setText(RoomMeasurement.hasResult(this)
                ? getString(R.string.room_measure_done)
                : getString(R.string.room_measure_nothing));
    }

    private void startRoomMeasurement() {
        if (RoomMeasurement.isRunning()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // Without the microphone there is nothing to measure with, so ask rather than fail.
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        tvRoomStatus.setText(getString(R.string.room_measure_running, ""));
        RoomMeasurement.measureAsync(this, new RoomMeasurement.Listener() {
            @Override
            public void onProgress(String stage) {
                runOnUiThread(() ->
                        tvRoomStatus.setText(getString(R.string.room_measure_running, stage)));
            }

            @Override
            public void onFinished(RoomMeasurement.Result result) {
                runOnUiThread(() -> tvRoomStatus.setText(result != null && result.error == null
                        ? getString(R.string.room_measure_done)
                        : getString(R.string.room_measure_failed)));
            }
        });
    }

    /**
     * Packs the report and the recordings into one archive and offers it to the share sheet.
     *
     * A single file rather than five: a tester picking attachments one by one in a car park will
     * miss one, and a measurement missing a channel is not a measurement.
     */
    private void shareRoomMeasurement() {
        if (!RoomMeasurement.hasResult(this)) {
            Toaster.show(this, getString(R.string.room_measure_nothing));
            return;
        }
        java.io.File dir = RoomMeasurement.outputDir(this);
        java.io.File zip = new java.io.File(dir, "wdsp_room_measurement.zip");
        int packed = 0;
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                new java.io.FileOutputStream(zip))) {
            byte[] buffer = new byte[8192];
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (!f.isFile() || f.getName().endsWith(".zip")) continue;
                    zos.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        int n;
                        while ((n = in.read(buffer)) > 0) zos.write(buffer, 0, n);
                    }
                    zos.closeEntry();
                    packed++;
                }
            }
        } catch (java.io.IOException e) {
            android.util.Log.e("wDSP_Settings", "could not build the measurement archive", e);
            Toaster.show(this, getString(R.string.room_measure_failed));
            return;
        }
        if (packed == 0) {
            Toaster.show(this, getString(R.string.room_measure_nothing));
            return;
        }

        android.net.Uri uri;
        try {
            uri = androidx.core.content.FileProvider.getUriForFile(
                    this, "com.radiorubka.wdsp.logs", zip);
        } catch (IllegalArgumentException e) {
            // The folder is not listed in file_paths.xml. That is our own configuration mistake
            // and hiding it would only make it harder to find.
            android.util.Log.e("wDSP_Settings", "FileProvider path not configured", e);
            Toaster.show(this, zip.getAbsolutePath());
            return;
        }

        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, zip.getName())
                .putExtra(Intent.EXTRA_TEXT,
                        getString(R.string.room_measure_share_text, appVersion())
                                + "\n" + getString(R.string.room_measure_telegram))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (send.resolveActivity(getPackageManager()) == null) {
            Toaster.show(this, zip.getAbsolutePath());
            return;
        }
        startActivity(Intent.createChooser(send, getString(R.string.room_measure_send)));
    }

    private void openTelegram() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://t.me/kostyamat")));
        } catch (Exception e) {
            Toaster.show(this, getString(R.string.room_measure_telegram));
        }
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Measures how far the bars run ahead of the sound, and keeps the answer.
     *
     * With permission to record, the microphone hears the test bursts and the figure covers the
     * whole journey to the cabin. Without it only the journey through Android can be timed and
     * the rest is allowed for, so the result is coarser but still far closer than the platform's
     * own declaration.
     */
    private void startLatencyMeasurement() {
        if (LatencyProbe.isRunning()) return;
        boolean canListen = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        tvSyncStatus.setText(R.string.sync_measure_running);
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        LatencyProbe.measureAsync(audioManager, false, canListen, result -> runOnUiThread(() -> {
            int ms = AudioSpectrumEngine.storeMeasuredLatency(getApplicationContext(), result);
            if (ms < 0) {
                tvSyncStatus.setText(R.string.sync_measure_failed);
            } else {
                tvSyncStatus.setText(getString(R.string.sync_measure_result, ms));
            }
        }));
    }

    /** Shows the figure this head unit was measured at, or nothing if it never has been. */
    private void showSyncStatus() {
        if (tvSyncStatus == null) return;
        int stored = AudioSpectrumEngine.storedLatencyBaseMs(this);
        tvSyncStatus.setText(stored >= 0 ? getString(R.string.sync_measure_result, stored) : "");
    }

    /**
     * @param offset added to the stored value to get the slider position, so a slider that only
     *               counts upwards can carry a signed setting such as the latency trim
     */
    private void bindAnalyzerSeek(SeekBar seek, TextView label, String key, int offset,
                                  String format) {
        if (seek == null) return;
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = progress - offset;
                if (label != null) {
                    label.setText(String.format(java.util.Locale.US, format, value));
                }
                if (fromUser) saveAnalyzerPref(key, value);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
    }

    private void saveAnalyzerPref(String key, boolean value) {
        ThemeManager.prefs(this).edit().putBoolean(key, value).apply();
        AudioSpectrumEngine.getInstance().loadDisplaySettings(this);
    }

    private void saveAnalyzerPref(String key, int value) {
        ThemeManager.prefs(this).edit().putInt(key, value).apply();
        AudioSpectrumEngine.getInstance().loadDisplaySettings(this);
    }

    private void loadAnalyzerSettings(SharedPreferences p) {
        if (switchAgcMain == null) return;
        // The main analyser defaults to absolute levels: it is an instrument, and one that quietly
        // rescales itself tells you nothing. The status bar widget defaults the other way.
        switchAgcMain.setChecked(p.getBoolean(AudioSpectrumEngine.PREF_AGC_MAIN_ENABLED, false));
        switchAgcBar.setChecked(p.getBoolean(AudioSpectrumEngine.PREF_AGC_BAR_ENABLED, true));
        seekAgcMainStrength.setProgress(p.getInt(AudioSpectrumEngine.PREF_AGC_MAIN_STRENGTH, 60));
        seekAgcBarStrength.setProgress(p.getInt(AudioSpectrumEngine.PREF_AGC_BAR_STRENGTH, 100));
        seekLatencyTrim.setProgress(
                p.getInt(AudioSpectrumEngine.PREF_LATENCY_TRIM, 0) + LATENCY_TRIM_OFFSET);
        seekRangeDb.setProgress(p.getInt(AudioSpectrumEngine.PREF_RANGE_DB, 60));
    }

    private void updateStatusBarBandsHighlights(int bands) {
        styleToggleButton(btnStatusBarBands16, bands == 16);
        styleToggleButton(btnStatusBarBands32, bands == 32);
    }

    private void updateEqVisModeHighlights(int mode) {
        styleToggleButton(btnEqVisSpectrum, mode == 0);
        styleToggleButton(btnEqVisMonochrome, mode == 1);
    }

    private void loadColorToWheel(int color, HueWheelView wheel, SeekBar brightness) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        wheel.setColor(hsv[0], hsv[1]);
        brightness.setProgress((int) (hsv[2] * 100));
    }

    private String getUriFileName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return name != null ? name : "wallpaper";
    }

    /**
     * Writes the backup to the public Downloads folder without asking where.
     *
     * @return false if the platform would not have it, so the caller can fall back to a picker
     */
    private boolean backupToDownloads(String filename) {
        Downloads.Pending pending = Downloads.create(this, filename, "application/json");
        if (pending == null) return false;
        try {
            backupToStream(pending.stream);
            Downloads.finish(this, pending);
            Toast.makeText(this, getString(R.string.toast_saved_to, pending.displayPath),
                    Toast.LENGTH_LONG).show();
            return true;
        } catch (Exception e) {
            // A half-written backup is worse than none: it looks restorable and is not.
            Downloads.discard(this, pending);
            Log.e(TAG, "Backup to Downloads failed", e);
            return false;
        }
    }

    private void backupAllSettings(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) return;
            backupToStream(os);
            Toast.makeText(this, R.string.toast_backup_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Backup failed", e);
            Toast.makeText(this, getString(R.string.toast_backup_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    public void backupToFile(File file) {
        try {
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                backupToStream(fos);
                Log.i(TAG, "Successfully backed up settings to " + file.getAbsolutePath());
                Toast.makeText(this, R.string.toast_backup_success, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "backupToFile failed", e);
            Toast.makeText(this, getString(R.string.toast_backup_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    public void backupToStream(OutputStream os) throws Exception {
        JsonObject backupRoot = new JsonObject();
        backupRoot.addProperty("version", 2);
        backupRoot.addProperty("app", "wDSP");
        backupRoot.addProperty("timestamp", System.currentTimeMillis());

        // 1. Default preferences (Theme, wallpaper, statusbar, eq vis)
        SharedPreferences defPrefs = ThemeManager.prefs(this);
        backupRoot.add("default_preferences", serializePrefsToJson(defPrefs.getAll()));

        // 2. EqPresets preferences (EQ, Presets, GALA, Subwoofer, Fader, Delays)
        SharedPreferences eqPrefs = getSharedPreferences("EqPresets", MODE_PRIVATE);
        backupRoot.add("eq_preferences", serializePrefsToJson(eqPrefs.getAll()));

        // Write JSON
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(backupRoot);
        os.write(json.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private JsonObject serializePrefsToJson(Map<String, ?> map) {
        JsonObject obj = new JsonObject();
        if (map == null) return obj;
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            String key = entry.getKey();
            Object val = entry.getValue();
            if (val == null) continue;
            JsonObject item = new JsonObject();
            if (val instanceof Boolean) {
                item.addProperty("t", "b");
                item.addProperty("v", (Boolean) val);
            } else if (val instanceof Integer) {
                item.addProperty("t", "i");
                item.addProperty("v", (Integer) val);
            } else if (val instanceof Long) {
                item.addProperty("t", "l");
                item.addProperty("v", (Long) val);
            } else if (val instanceof Float) {
                item.addProperty("t", "f");
                item.addProperty("v", (Float) val);
            } else if (val instanceof String) {
                item.addProperty("t", "s");
                item.addProperty("v", (String) val);
            } else if (val instanceof Set) {
                item.addProperty("t", "ss");
                JsonArray arr = new JsonArray();
                for (Object s : (Set<?>) val) {
                    if (s != null) arr.add(s.toString());
                }
                item.add("v", arr);
            }
            obj.add(key, item);
        }
        return obj;
    }

    private void restoreAllSettings(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) return;
            restoreFromStream(is);
            Toast.makeText(this, R.string.toast_restore_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Restore failed", e);
            Toast.makeText(this, getString(R.string.toast_restore_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    public void restoreFromFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            restoreFromStream(fis);
            Log.i(TAG, "Successfully restored settings from " + file.getAbsolutePath());
            Toast.makeText(this, R.string.toast_restore_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "restoreFromFile failed", e);
            Toast.makeText(this, getString(R.string.toast_restore_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    public void restoreFromStream(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        if (root == null || !root.has("app") || !"wDSP".equals(root.get("app").getAsString())) {
            throw new IllegalArgumentException("Invalid wDSP backup format");
        }

        // 1. Restore Default preferences
        if (root.has("default_preferences") && root.get("default_preferences").isJsonObject()) {
            SharedPreferences.Editor defEditor = ThemeManager.prefs(this).edit();
            defEditor.clear();
            restoreJsonToPrefs(root.getAsJsonObject("default_preferences"), defEditor);
            defEditor.apply();
        }

        // 2. Restore Eq preferences
        if (root.has("eq_preferences") && root.get("eq_preferences").isJsonObject()) {
            SharedPreferences.Editor eqEditor = getSharedPreferences("EqPresets", MODE_PRIVATE).edit();
            eqEditor.clear();
            restoreJsonToPrefs(root.getAsJsonObject("eq_preferences"), eqEditor);
            eqEditor.apply();
        }

        // 3. Notify status bar manager and all activities/services
        StatusBarVisualizerManager.getInstance(this).loadPreferences();
        StatusBarVisualizerManager.getInstance(this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_ENABLED);
        sendBroadcast(new Intent("com.radiorubka.wdsp.SETTINGS_RESTORED").setPackage(getPackageName()));

        // 4. Reload activity settings & theme
        loadSettings();
        applyTheme();
    }

    private void restoreJsonToPrefs(JsonObject jsonObj, SharedPreferences.Editor editor) {
        for (Map.Entry<String, JsonElement> entry : jsonObj.entrySet()) {
            String key = entry.getKey();
            JsonElement elem = entry.getValue();
            if (elem == null || elem.isJsonNull()) continue;

            // V2 Format with explicit type tag "t" and value "v"
            if (elem.isJsonObject()) {
                JsonObject item = elem.getAsJsonObject();
                if (item.has("t") && item.has("v")) {
                    String type = item.get("t").getAsString();
                    JsonElement val = item.get("v");
                    switch (type) {
                        case "b":
                            editor.putBoolean(key, val.getAsBoolean());
                            break;
                        case "i":
                            editor.putInt(key, val.getAsInt());
                            break;
                        case "l":
                            editor.putLong(key, val.getAsLong());
                            break;
                        case "f":
                            editor.putFloat(key, val.getAsFloat());
                            break;
                        case "s":
                            editor.putString(key, val.getAsString());
                            break;
                        case "ss":
                            Set<String> set = new HashSet<>();
                            if (val.isJsonArray()) {
                                for (JsonElement se : val.getAsJsonArray()) {
                                    set.add(se.getAsString());
                                }
                            }
                            editor.putStringSet(key, set);
                            break;
                    }
                    continue;
                }
            }

            // V1 Legacy Fallback
            if (elem.isJsonPrimitive()) {
                JsonPrimitive prim = elem.getAsJsonPrimitive();
                if (prim.isBoolean()) {
                    editor.putBoolean(key, prim.getAsBoolean());
                } else if (prim.isNumber()) {
                    Number num = prim.getAsNumber();
                    double d = num.doubleValue();
                    if (isFloatKey(key)) {
                        editor.putFloat(key, (float) d);
                    } else if (d == Math.rint(d)) {
                        editor.putInt(key, (int) d);
                    } else {
                        editor.putFloat(key, (float) d);
                    }
                } else if (prim.isString()) {
                    editor.putString(key, prim.getAsString());
                }
            } else if (elem.isJsonArray()) {
                Set<String> set = new HashSet<>();
                for (JsonElement item : elem.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) set.add(item.getAsString());
                }
                editor.putStringSet(key, set);
            }
        }
    }

    private static boolean isFloatKey(String key) {
        return StatusBarVisualizerManager.PREF_STATUS_BAR_WIDTH_F.equals(key)
                || StatusBarVisualizerManager.PREF_STATUS_BAR_POS_F.equals(key);
    }

    private void applyTheme() {
        int accent = ThemeManager.accent(this, editNight);
        int primaryText = ThemeManager.textPrimary(this, editNight);
        int secondaryText = ThemeManager.textSecondary(this, editNight);

        if (rootSettings != null) {
            rootSettings.setBackground(ThemeManager.wallpaperBackground(this, editNight));
        }
        SettingsAccordion.repaint(settingsColumn, accent);

        TextView title = findViewById(R.id.title);
        if (title != null) title.setTextColor(primaryText);

        // Section labels & headers
        int[] primaryLabels = {
            R.id.label_theme_section, R.id.label_statusbar_section,
            R.id.label_eq_vis_section, R.id.label_permissions_section,
            R.id.label_wallpaper, R.id.label_status_bar_vis_enable,
            R.id.label_status_bar_bands, R.id.label_status_bar_theme, R.id.label_eq_vis_enable,
            R.id.label_sb_vis_normalization, R.id.label_vis_normalization
        };
        for (int id : primaryLabels) {
            TextView tv = findViewById(id);
            if (tv != null) tv.setTextColor(primaryText);
        }

        // Secondary / Row labels
        int[] secondaryLabels = {
            R.id.label_accent_wheel, R.id.label_primary_text_wheel,
            R.id.label_label_wheel, R.id.label_on_accent_wheel,
            R.id.wallpaper_name, R.id.label_solid_hue, R.id.label_solid_val,
            R.id.label_status_bar_width, R.id.label_status_bar_pos,
            R.id.label_status_bar_hue, R.id.label_eq_vis_mode,
            R.id.desc_sb_vis_normalization, R.id.desc_vis_normalization
        };
        for (int id : secondaryLabels) {
            TextView tv = findViewById(id);
            if (tv != null) tv.setTextColor(secondaryText);
        }

        // Value text views
        int valueColor = editNight ? accent : primaryText;
        if (tvStatusBarWidth != null) tvStatusBarWidth.setTextColor(valueColor);
        if (tvStatusBarPos != null) tvStatusBarPos.setTextColor(valueColor);
        if (tvStatusBarHue != null) tvStatusBarHue.setTextColor(valueColor);
        if (tvSolidHueVal != null) tvSolidHueVal.setTextColor(valueColor);
        if (tvSolidValVal != null) tvSolidValVal.setTextColor(valueColor);

        // Tint status bar Sliders
        tintSlider(seekStatusBarWidth, accent);
        tintSlider(seekStatusBarPos, accent);

        // Update wheel brightness backgrounds
        updateWheelBrightnessGradient(pickerAccentWheel, pickerAccentBrightness);
        updateWheelBrightnessGradient(pickerPrimaryTextWheel, pickerPrimaryTextBrightness);
        updateWheelBrightnessGradient(pickerLabelWheel, pickerLabelBrightness);
        updateWheelBrightnessGradient(pickerOnAccentWheel, pickerOnAccentBrightness);

        // Tint Bottom Navigation Bar
        if (bottomNav != null) {
            ColorStateList navCsl = ThemeManager.bottomNavColorStateList(this, editNight);
            bottomNav.setItemIconTintList(navCsl);
            bottomNav.setItemTextColor(navCsl);
            bottomNav.setItemActiveIndicatorColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 40)));
        }

        // Action buttons styling
        int actionBorder = editNight ? Color.parseColor("#4DFFFFFF") : Color.parseColor("#4D000000");
        int normalBtnBg = editNight ? Color.parseColor("#25FFFFFF") : Color.parseColor("#18000000");
        TextView[] normalActionButtons = {
            btnWallpaperPick, btnWallpaperReset, btnAppDetails,
            btnBackupSettings, btnRestoreSettings
        };
        for (TextView btn : normalActionButtons) {
            if (btn != null) {
                btn.setTextColor(primaryText);
                btn.setBackground(ThemeManager.roundedDrawable(this, 10f, normalBtnBg, actionBorder, 1.2f));
            }
        }

        // Update toggle and permission button states
        updateThemeModeButtons(ThemeManager.getThemeMode(this));
        styleToggleButton(btnSolidWallpaper, ThemeManager.isSolidWallpaper(this, editNight));
        int currentTheme = ThemeManager.prefs(this).getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_THEME, StatusBarVisualizerView.THEME_SPECTRUM);
        updateThemeButtonHighlights(currentTheme);
        int eqVisMode = ThemeManager.prefs(this).getInt("pref_eq_visualizer_mode", 0);
        updateEqVisModeHighlights(eqVisMode);
        updatePermissionButtons();
        styleActionButtons();
    }

    /** Re-applies the accent to every button that performs an action rather than toggling one. */
    private void styleActionButtons() {
        styleActionButton(findViewById(R.id.btn_sync_measure));
        styleActionButton(findViewById(R.id.btn_room_measure));
        styleActionButton(findViewById(R.id.btn_room_send));
    }

    private void tintSlider(Slider s, int accent) {
        if (s == null) return;
        ColorStateList csl = ColorStateList.valueOf(accent);
        s.setThumbTintList(csl);
        s.setTrackActiveTintList(csl);
        s.setTrackInactiveTintList(ColorStateList.valueOf(editNight ? Color.parseColor("#33FFFFFF") : Color.parseColor("#33000000")));
        s.setHaloRadius(0);
        s.setTrackStopIndicatorSize(0);
        float density = getResources().getDisplayMetrics().density;
        s.setTrackHeight((int) (5 * density));
        s.setThumbRadius((int) (10 * density));
        s.setThumbWidth((int) (20 * density));
        s.setThumbHeight((int) (20 * density));
        s.setLabelBehavior(LabelFormatter.LABEL_GONE);
    }
}
