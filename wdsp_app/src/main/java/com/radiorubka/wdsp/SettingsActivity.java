package com.radiorubka.wdsp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.radiorubka.wdsp.ui.SettingsAccordion;
import com.radiorubka.wdsp.ui.TouchGlow;
import com.radiorubka.wdsp.ui.theme.ThemeManager;
import com.radiorubka.wdsp.ui.views.HueWheelView;

import java.io.BufferedReader;
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

    private ScrollView rootSettings;
    private LinearLayout settingsColumn;

    // Theme Mode
    private TextView btnThemeDay, btnThemeNight, btnThemeAuto;
    private boolean editNight;

    // 4 Hue Wheels
    private HueWheelView pickerAccentWheel, pickerPrimaryTextWheel, pickerLabelWheel, pickerOnAccentWheel;
    private SeekBar pickerAccentBrightness, pickerPrimaryTextBrightness, pickerLabelBrightness, pickerOnAccentBrightness;

    // Wallpaper
    private TextView labelWallpaper, wallpaperName;
    private TextView btnWallpaperPick, btnWallpaperReset, btnSolidWallpaper;
    private LinearLayout layoutSolidControls;
    private SeekBar seekSolidHue, seekSolidVal;
    private View bgSolidHue, bgSolidVal;

    // Status Bar Visualizer
    private SwitchCompat switchStatusBarVis;
    private SeekBar seekStatusBarWidth, seekStatusBarPos, seekStatusBarHue;
    private TextView tvStatusBarWidth, tvStatusBarPos, tvStatusBarHue;
    private TextView btnThemeSpectrum, btnThemeSolidHue, btnThemeAutoDayNight, btnThemeEqGroups;
    private TextView btnThemeWhite, btnThemeBlack, btnThemeFire, btnThemeNeon;
    private TextView btnOverlayPerm;

    // EQ Visualizer
    private SwitchCompat switchEqVisualizerEnable;
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
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        // Theme modes
        btnThemeDay = findViewById(R.id.btn_theme_day);
        btnThemeNight = findViewById(R.id.btn_theme_night);
        btnThemeAuto = findViewById(R.id.btn_theme_auto);

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
        bgSolidHue = findViewById(R.id.bg_solid_hue);
        bgSolidVal = findViewById(R.id.bg_solid_val);

        btnWallpaperPick.setOnClickListener(v -> {
            try {
                wallpaperPickerLauncher.launch(new String[]{"image/*"});
            } catch (Exception e) {
                Toast.makeText(this, "SAF error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
        seekStatusBarWidth = findViewById(R.id.seek_status_bar_width);
        seekStatusBarPos = findViewById(R.id.seek_status_bar_pos);
        seekStatusBarHue = findViewById(R.id.seek_status_bar_hue);
        tvStatusBarWidth = findViewById(R.id.tv_status_bar_width);
        tvStatusBarPos = findViewById(R.id.tv_status_bar_pos);
        tvStatusBarHue = findViewById(R.id.tv_status_bar_hue);

        btnThemeSpectrum = findViewById(R.id.btn_theme_spectrum);
        btnThemeSolidHue = findViewById(R.id.btn_theme_solid_hue);
        btnThemeAutoDayNight = findViewById(R.id.btn_theme_auto_day_night);
        btnThemeEqGroups = findViewById(R.id.btn_theme_eq_groups);
        btnThemeWhite = findViewById(R.id.btn_theme_white);
        btnThemeBlack = findViewById(R.id.btn_theme_black);
        btnThemeFire = findViewById(R.id.btn_theme_fire);
        btnThemeNeon = findViewById(R.id.btn_theme_neon);
        btnOverlayPerm = findViewById(R.id.btn_overlay_perm);

        initStatusBarVisualizerControls();

        // EQ Spectrum Visualizer
        switchEqVisualizerEnable = findViewById(R.id.switch_eq_visualizer_enable);
        btnEqVisSpectrum = findViewById(R.id.btn_eq_vis_spectrum);
        btnEqVisMonochrome = findViewById(R.id.btn_eq_vis_monochrome);

        switchEqVisualizerEnable.setOnCheckedChangeListener((btn, isChecked) -> {
            ThemeManager.prefs(this).edit().putBoolean("pref_eq_visualizer_enabled", isChecked).apply();
        });

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
        btnAudioPerm = findViewById(R.id.btn_audio_perm);
        btnLocationPerm = findViewById(R.id.btn_location_perm);
        btnAppDetails = findViewById(R.id.btn_app_details);
        btnBackupSettings = findViewById(R.id.btn_backup_settings);
        btnRestoreSettings = findViewById(R.id.btn_restore_settings);

        TouchGlow.attach(btnBatteryOpt);
        TouchGlow.attach(btnAudioPerm);
        TouchGlow.attach(btnLocationPerm);
        TouchGlow.attach(btnAppDetails);
        TouchGlow.attach(btnBackupSettings);
        TouchGlow.attach(btnRestoreSettings);

        btnBatteryOpt.setOnClickListener(v -> requestBatteryOptimization());
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
            backupLauncher.launch(filename);
        });

        btnRestoreSettings.setOnClickListener(v -> {
            restoreLauncher.launch(new String[]{"application/json", "*/*"});
        });
    }

    private void bindAccordion() {
        int accent = ThemeManager.accent(this, editNight);
        SettingsAccordion.build(settingsColumn, accent);
    }

    private void setupColorWheel(HueWheelView wheel, SeekBar brightness, int slot) {
        wheel.setListener((hue, sat, finished) -> {
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
        int[] hueColors = new int[361];
        for (int i = 0; i <= 360; i++) {
            hueColors[i] = Color.HSVToColor(new float[]{i, 1f, 1f});
        }
        GradientDrawable hueDrawable = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, hueColors);
        hueDrawable.setCornerRadius(16f);
        bgSolidHue.setBackground(hueDrawable);

        SeekBar.OnSeekBarChangeListener solidListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float h = seekSolidHue.getProgress();
                float v = seekSolidVal.getProgress() / 100f;
                int newColor = Color.HSVToColor(new float[]{h, 1f, v});

                int pureColor = Color.HSVToColor(new float[]{h, 1f, 1f});
                GradientDrawable valDrawable = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.BLACK, pureColor});
                valDrawable.setCornerRadius(16f);
                bgSolidVal.setBackground(valDrawable);

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

        switchStatusBarVis.setOnCheckedChangeListener((btn, isChecked) -> {
            p.edit().putBoolean(StatusBarVisualizerManager.PREF_STATUS_BAR_ENABLED, isChecked).apply();
            StatusBarVisualizerManager.getInstance(this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_ENABLED);
            if (isChecked && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.status_bar_visualizer_permission_needed, Toast.LENGTH_SHORT).show();
            }
        });

        seekStatusBarWidth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvStatusBarWidth.setText(getString(R.string.lbl_percent_fmt, progress));
                if (fromUser) {
                    float f = progress / 100f;
                    p.edit().putFloat(StatusBarVisualizerManager.PREF_STATUS_BAR_WIDTH_F, f).apply();
                    StatusBarVisualizerManager.getInstance(SettingsActivity.this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_WIDTH_F);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekStatusBarPos.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvStatusBarPos.setText(getString(R.string.lbl_percent_fmt, progress));
                if (fromUser) {
                    float f = progress / 100f;
                    p.edit().putFloat(StatusBarVisualizerManager.PREF_STATUS_BAR_POS_F, f).apply();
                    StatusBarVisualizerManager.getInstance(SettingsActivity.this).onPreferenceChanged(StatusBarVisualizerManager.PREF_STATUS_BAR_POS_F);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
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

        btnOverlayPerm.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });
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
        int accent = ThemeManager.accent(this, editNight);

        for (int i = 0; i < btns.length; i++) {
            if (i == currentTheme) {
                btns[i].setTextColor(accent);
                btns[i].setBackgroundResource(R.drawable.bg_slider_layout);
            } else {
                btns[i].setTextColor(ThemeManager.textSecondary(this, editNight));
                btns[i].setBackgroundResource(R.drawable.rds_btn_bg);
            }
        }
    }

    private void loadSettings() {
        SharedPreferences p = ThemeManager.prefs(this);

        // Theme mode
        int mode = ThemeManager.getThemeMode(this);
        int accent = ThemeManager.accent(this, editNight);
        btnThemeDay.setTextColor(mode == ThemeManager.THEME_MODE_DAY ? accent : ThemeManager.textSecondary(this, editNight));
        btnThemeNight.setTextColor(mode == ThemeManager.THEME_MODE_NIGHT ? accent : ThemeManager.textSecondary(this, editNight));
        btnThemeAuto.setTextColor(mode == ThemeManager.THEME_MODE_AUTO ? accent : ThemeManager.textSecondary(this, editNight));

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

        int solidColor = ThemeManager.getSolidWallpaperColor(this, editNight);
        float[] hsv = new float[3];
        Color.colorToHSV(solidColor, hsv);
        seekSolidHue.setProgress((int) hsv[0]);
        seekSolidVal.setProgress((int) (hsv[2] * 100));

        // Visualizer
        switchStatusBarVis.setChecked(p.getBoolean(StatusBarVisualizerManager.PREF_STATUS_BAR_ENABLED, false));
        int w = Math.round(p.getFloat(StatusBarVisualizerManager.PREF_STATUS_BAR_WIDTH_F, 0.40f) * 100);
        int pos = Math.round(p.getFloat(StatusBarVisualizerManager.PREF_STATUS_BAR_POS_F, 0.50f) * 100);
        int hue = p.getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_HUE, 0);

        seekStatusBarWidth.setProgress(w);
        seekStatusBarPos.setProgress(pos);
        seekStatusBarHue.setProgress(hue);
        tvStatusBarWidth.setText(getString(R.string.lbl_percent_fmt, w));
        tvStatusBarPos.setText(getString(R.string.lbl_percent_fmt, pos));
        tvStatusBarHue.setText(getString(R.string.lbl_degrees_fmt, hue));

        int currentTheme = p.getInt(StatusBarVisualizerManager.PREF_STATUS_BAR_THEME, StatusBarVisualizerView.THEME_SPECTRUM);
        updateThemeButtonHighlights(currentTheme);

        // EQ Spectrum Visualizer
        boolean eqVisEnabled = p.getBoolean("pref_eq_visualizer_enabled", true);
        switchEqVisualizerEnable.setChecked(eqVisEnabled);
        int eqVisMode = p.getInt("pref_eq_visualizer_mode", 0);
        updateEqVisModeHighlights(eqVisMode);
    }

    private void updateEqVisModeHighlights(int mode) {
        int accent = ThemeManager.accent(this);
        int onAccent = ThemeManager.onAccent(this);
        int normalBg = Color.parseColor("#15FFFFFF");
        int normalText = ThemeManager.textPrimary(this);

        if (mode == 0) {
            btnEqVisSpectrum.setBackgroundColor(accent);
            btnEqVisSpectrum.setTextColor(onAccent);
            btnEqVisMonochrome.setBackgroundColor(normalBg);
            btnEqVisMonochrome.setTextColor(normalText);
        } else {
            btnEqVisMonochrome.setBackgroundColor(accent);
            btnEqVisMonochrome.setTextColor(onAccent);
            btnEqVisSpectrum.setBackgroundColor(normalBg);
            btnEqVisSpectrum.setTextColor(normalText);
        }
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

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "Battery optimization already disabled", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void backupAllSettings(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) return;
            Map<String, Object> backupRoot = new HashMap<>();
            backupRoot.put("version", 1);
            backupRoot.put("app", "wDSP");
            backupRoot.put("timestamp", System.currentTimeMillis());

            // 1. Default preferences (Theme, wallpaper, statusbar, eq vis)
            SharedPreferences defPrefs = ThemeManager.prefs(this);
            backupRoot.put("default_preferences", defPrefs.getAll());

            // 2. EqPresets preferences (EQ, Presets, GALA, Subwoofer, Fader, Delays)
            SharedPreferences eqPrefs = getSharedPreferences("EqPresets", MODE_PRIVATE);
            backupRoot.put("eq_presets", eqPrefs.getAll());

            String json = new GsonBuilder().setPrettyPrinting().create().toJson(backupRoot);
            os.write(json.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, R.string.toast_backup_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Backup failed", e);
            Toast.makeText(this, getString(R.string.toast_backup_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void restoreAllSettings(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);

            JsonObject root = JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (root == null || !root.has("app") || !"wDSP".equals(root.get("app").getAsString())) {
                Toast.makeText(this, R.string.toast_restore_failed, Toast.LENGTH_SHORT).show();
                return;
            }

            // 1. Restore Default Preferences
            if (root.has("default_preferences")) {
                JsonObject defObj = root.getAsJsonObject("default_preferences");
                SharedPreferences.Editor defEditor = ThemeManager.prefs(this).edit();
                restoreJsonToPrefs(defObj, defEditor);
                defEditor.apply();
            }

            // 2. Restore EqPresets
            if (root.has("eq_presets")) {
                JsonObject eqObj = root.getAsJsonObject("eq_presets");
                SharedPreferences.Editor eqEditor = getSharedPreferences("EqPresets", MODE_PRIVATE).edit();
                restoreJsonToPrefs(eqObj, eqEditor);
                eqEditor.apply();
            }

            // 3. Notify McuService
            Intent syncIntent = new Intent("com.radiorubka.wdsp.UI_ACTIVE");
            syncIntent.setPackage(getPackageName());
            sendBroadcast(syncIntent);

            Toast.makeText(this, R.string.toast_restore_success, Toast.LENGTH_SHORT).show();

            // 4. Reload activity settings
            loadSettings();
            applyTheme();
        } catch (Exception e) {
            Log.e(TAG, "Restore failed", e);
            Toast.makeText(this, getString(R.string.toast_restore_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void restoreJsonToPrefs(JsonObject jsonObj, SharedPreferences.Editor editor) {
        for (Map.Entry<String, JsonElement> entry : jsonObj.entrySet()) {
            String key = entry.getKey();
            JsonElement elem = entry.getValue();
            if (elem.isJsonPrimitive()) {
                JsonPrimitive prim = elem.getAsJsonPrimitive();
                if (prim.isBoolean()) {
                    editor.putBoolean(key, prim.getAsBoolean());
                } else if (prim.isNumber()) {
                    Number num = prim.getAsNumber();
                    double d = num.doubleValue();
                    if (d == Math.rint(d)) {
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

    private void applyTheme() {
        int accent = ThemeManager.accent(this, editNight);
        int primaryText = ThemeManager.textPrimary(this, editNight);
        int secondaryText = ThemeManager.textSecondary(this, editNight);

        rootSettings.setBackground(ThemeManager.wallpaperBackground(this, editNight));
        SettingsAccordion.repaint(settingsColumn, accent);

        TextView title = findViewById(R.id.title);
        if (title != null) title.setTextColor(primaryText);

        findViewById(R.id.btn_back).setBackgroundResource(R.drawable.rds_btn_bg);
        ((TextView) findViewById(R.id.btn_back)).setTextColor(accent);

        if (btnBackupSettings != null) {
            btnBackupSettings.setTextColor(primaryText);
        }
        if (btnRestoreSettings != null) {
            btnRestoreSettings.setTextColor(primaryText);
        }
    }
}
