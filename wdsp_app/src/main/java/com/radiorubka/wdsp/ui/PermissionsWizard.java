package com.radiorubka.wdsp.ui;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.radiorubka.wdsp.NowPlaying;
import com.radiorubka.wdsp.R;
import com.radiorubka.wdsp.RootAccess;
import com.radiorubka.wdsp.Toaster;
import com.radiorubka.wdsp.ui.theme.ThemeManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 🛡️ Єдиний стандарт онбордингу та майстра дозволів (Permissions Onboarding Wizard).
 *
 * <p>Забезпечує прозоре, легальне отримання всіх дозволів (як Runtime, так і Special Settings)
 * без використання штучних ADB-хаків. Візуалізує стан кожного дозволу (зелений ✓ / сірий [Увімкнути])
 * і миттєво оновлює список при поверненні користувача з системних налаштувань.</p>
 */
public final class PermissionsWizard {

    public static final int REQ_LOCATION = 102;
    public static final int REQ_AUDIO = 103;

    private static final String PREF_LAST_WIZARD_VERSION = "pref_last_wizard_version_code";
    private static WeakReference<PermissionsWizard> sCurrentInstance;

    private static volatile boolean sRootChecked = false;
    private static volatile boolean sRootGranted = false;

    public static boolean isRootGranted(Context context) {
        if (!sRootChecked) {
            sRootChecked = true;
            new Thread(() -> {
                boolean granted = RootAccess.alreadyGranted();
                sRootGranted = granted;
                if (granted) {
                    if (context instanceof Activity) {
                        ((Activity) context).runOnUiThread(PermissionsWizard::refreshCurrent);
                    }
                }
            }, "wDSP_RootCheck").start();
        }
        return sRootGranted;
    }

    private final Activity activity;
    private final Runnable onDismissCallback;
    private Dialog dialog;
    private LinearLayout listContainer;
    private final List<PermissionCardBinding> cardBindings = new ArrayList<>();

    public interface PermissionChecker {
        boolean isGranted(Context context);
    }

    public interface PermissionAction {
        void execute(Activity activity);
    }

    public static class Item {
        public final int id;
        public final int titleRes;
        public final int descRes;
        public final PermissionChecker checker;
        public final PermissionAction action;
        public final boolean optional;

        public Item(int id, int titleRes, int descRes, PermissionChecker checker, PermissionAction action) {
            this(id, titleRes, descRes, checker, action, false);
        }

        public Item(int id, int titleRes, int descRes, PermissionChecker checker, PermissionAction action, boolean optional) {
            this.id = id;
            this.titleRes = titleRes;
            this.descRes = descRes;
            this.checker = checker;
            this.action = action;
            this.optional = optional;
        }
    }

    private static class PermissionCardBinding {
        final Item item;
        final LinearLayout cardView;
        final TextView tvTitle;
        final TextView tvDesc;
        final TextView tvStatusBadge;

        PermissionCardBinding(Item item, LinearLayout cardView, TextView tvTitle, TextView tvDesc, TextView tvStatusBadge) {
            this.item = item;
            this.cardView = cardView;
            this.tvTitle = tvTitle;
            this.tvDesc = tvDesc;
            this.tvStatusBadge = tvStatusBadge;
        }
    }

    private PermissionsWizard(Activity activity, Runnable onDismissCallback) {
        this.activity = activity;
        this.onDismissCallback = onDismissCallback;
    }

    /**
     * Перевіряє чи всі обов'язкові дозволи надані.
     */
    public static boolean areAllGranted(Context context) {
        List<Item> items = getItems();
        for (Item item : items) {
            if (!item.optional && !item.checker.isGranted(context)) {
                return false;
            }
        }
        return true;
    }

    public static int getAppVersionCode(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Автоматична перевірка при запуску: якщо версія оновилася або це перший старт,
     * і не всі дозволи надані — показує візард.
     */
    public static boolean checkAndShowIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        SharedPreferences prefs = ThemeManager.prefs(activity);
        int lastVer = prefs.getInt(PREF_LAST_WIZARD_VERSION, -1);
        int currentVer = getAppVersionCode(activity);

        if (lastVer < currentVer) {
            show(activity, () -> prefs.edit().putInt(PREF_LAST_WIZARD_VERSION, currentVer).apply());
            return true;
        }
        return false;
    }

    /**
     * Показує майстер дозволів вручну (наприклад, з Налаштувань).
     */
    public static PermissionsWizard show(Activity activity) {
        return show(activity, null);
    }

    public static PermissionsWizard show(Activity activity, Runnable onDismiss) {
        if (activity == null || activity.isFinishing()) return null;
        dismissCurrent();

        PermissionsWizard wizard = new PermissionsWizard(activity, onDismiss);
        wizard.buildAndShow();
        sCurrentInstance = new WeakReference<>(wizard);
        return wizard;
    }

    /**
     * Оновлює активний відкритий діалог (викликається в onResume та onRequestPermissionsResult).
     */
    public static void refreshCurrent() {
        if (sCurrentInstance != null) {
            PermissionsWizard wizard = sCurrentInstance.get();
            if (wizard != null && wizard.dialog != null && wizard.dialog.isShowing()) {
                wizard.refresh();
            }
        }
    }

    public static void dismissCurrent() {
        if (sCurrentInstance != null) {
            PermissionsWizard wizard = sCurrentInstance.get();
            if (wizard != null && wizard.dialog != null && wizard.dialog.isShowing()) {
                try {
                    wizard.dialog.dismiss();
                } catch (Throwable ignored) {
                }
            }
            sCurrentInstance = null;
        }
    }

    public static List<Item> getItems() {
        List<Item> list = new ArrayList<>();

        // 1. Доступ до сповіщень (Метадані радіо та плеєрів)
        list.add(new Item(
                1,
                R.string.perm_wizard_item_notif_title,
                R.string.perm_wizard_item_notif_desc,
                ctx -> NowPlaying.getInstance(ctx).canReadSessions(),
                act -> {
                    try {
                        act.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    } catch (Throwable t) {
                        Toaster.show(act, act.getString(R.string.perm_notification_access));
                    }
                }
        ));

        // 2. Поверх інших додатків (Оверлей для скрінсейвера та статус-бару)
        list.add(new Item(
                2,
                R.string.perm_wizard_item_overlay_title,
                R.string.perm_wizard_item_overlay_desc,
                ctx -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        return Settings.canDrawOverlays(ctx);
                    }
                    return true;
                },
                act -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + act.getPackageName()));
                        act.startActivity(intent);
                    }
                }
        ));

        // 3. Фонова робота / Енергозбереження
        list.add(new Item(
                3,
                R.string.perm_wizard_item_battery_title,
                R.string.perm_wizard_item_battery_desc,
                ctx -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
                    }
                    return true;
                },
                act -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + act.getPackageName()));
                            act.startActivity(intent);
                        } catch (Exception e) {
                            try {
                                act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
        ));

        // 4. Геолокація (GALA швидкість)
        list.add(new Item(
                4,
                R.string.perm_wizard_item_location_title,
                R.string.perm_wizard_item_location_desc,
                ctx -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                act -> ActivityCompat.requestPermissions(act, new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION)
        ));

        // 5. Мікрофон / Запис звуку (Вимірювання акустики салону та спектроаналізатор)
        list.add(new Item(
                5,
                R.string.perm_wizard_item_audio_title,
                R.string.perm_wizard_item_audio_desc,
                ctx -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                act -> ActivityCompat.requestPermissions(act, new String[]{
                        Manifest.permission.RECORD_AUDIO
                }, REQ_AUDIO)
        ));

        // 6. Суперкористувач (Root Magisk) - для вимірювань салону та повного діапазону мікрофона FM-радіо
        list.add(new Item(
                6,
                R.string.perm_wizard_item_root_title,
                R.string.perm_wizard_item_root_desc,
                PermissionsWizard::isRootGranted,
                act -> new Thread(() -> {
                    RootAccess.Outcome outcome = RootAccess.request();
                    if (outcome == RootAccess.Outcome.GRANTED) {
                        sRootGranted = true;
                        sRootChecked = true;
                        try {
                            Runtime.getRuntime().exec(new String[]{"su", "-c", "cmd appops set com.google.android.googlequicksearchbox RECORD_AUDIO ignore"}).waitFor();
                        } catch (Throwable ignored) {}
                    } else if (outcome == RootAccess.Outcome.DENIED_BY_POLICY) {
                        sRootGranted = false;
                        sRootChecked = true;
                        act.runOnUiThread(() -> Toaster.show(act, act.getString(R.string.room_root_blocked)));
                    } else {
                        sRootGranted = false;
                        sRootChecked = true;
                    }
                    act.runOnUiThread(PermissionsWizard::refreshCurrent);
                }, "wDSP_WizardRoot").start(),
                true
        ));

        return list;
    }

    private void buildAndShow() {
        Context context = activity;
        int cardBg = ThemeManager.cardBackground(context);
        int border = ThemeManager.panelBorder(context);
        int accent = ThemeManager.accent(context);
        int onAccent = ThemeManager.onAccent(context);
        int textPrimary = ThemeManager.contrastText(ThemeManager.textPrimary(context), cardBg);
        int textSecondary = ThemeManager.contrastText(ThemeManager.textSecondary(context), cardBg);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(0, (int) dp(8), 0, (int) dp(8));

        cardBindings.clear();
        List<Item> items = getItems();

        for (Item item : items) {
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            int padV = (int) dp(10);
            int padH = (int) dp(12);
            card.setPadding(padH, padV, padH, padV);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) dp(8);
            card.setLayoutParams(lp);

            // Left text block (Title + Description)
            LinearLayout textBlock = new LinearLayout(context);
            textBlock.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            textBlock.setLayoutParams(textLp);

            TextView tvTitle = new TextView(context);
            tvTitle.setText(item.titleRes);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
            tvTitle.setTypeface(null, Typeface.BOLD);
            textBlock.addView(tvTitle);

            TextView tvDesc = new TextView(context);
            tvDesc.setText(item.descRes);
            tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            tvDesc.setPadding(0, (int) dp(2), 0, 0);
            textBlock.addView(tvDesc);

            card.addView(textBlock);

            // Right status badge / button
            TextView tvStatus = new TextView(context);
            tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tvStatus.setTypeface(null, Typeface.BOLD);
            tvStatus.setGravity(Gravity.CENTER);
            int badgePadH = (int) dp(12);
            int badgePadV = (int) dp(6);
            tvStatus.setPadding(badgePadH, badgePadV, badgePadH, badgePadV);
            card.addView(tvStatus);

            TouchGlow.attach(card);
            card.setOnClickListener(v -> {
                if (!item.checker.isGranted(activity)) {
                    item.action.execute(activity);
                }
            });

            PermissionCardBinding binding = new PermissionCardBinding(item, card, tvTitle, tvDesc, tvStatus);
            cardBindings.add(binding);
            listContainer.addView(card);
        }

        scrollView.addView(listContainer);

        final boolean[] dismissed = new boolean[1];
        Runnable safeDismiss = () -> {
            if (!dismissed[0]) {
                dismissed[0] = true;
                if (onDismissCallback != null) {
                    onDismissCallback.run();
                }
            }
        };

        dialog = ThemedDialog.builder(context)
                .setTitle(context.getString(R.string.perm_wizard_title))
                .setMessage(context.getString(R.string.perm_wizard_subtitle))
                .setView(scrollView)
                .setPositiveButton(context.getString(R.string.perm_wizard_btn_continue), (d, which) -> safeDismiss.run())
                .setOnDismissListener(d -> safeDismiss.run())
                .create();

        dialog.show();
        refresh();
    }

    /**
     * Оновлює візуальний стан кожної картки (зелена/сіра).
     */
    public void refresh() {
        if (activity == null || activity.isFinishing() || listContainer == null) return;
        Context context = activity;
        int cardBg = ThemeManager.cardBackground(context);
        int border = ThemeManager.panelBorder(context);
        int accent = ThemeManager.accent(context);
        int onAccent = ThemeManager.onAccent(context);
        int textPrimary = ThemeManager.contrastText(ThemeManager.textPrimary(context), cardBg);
        int textSecondary = ThemeManager.contrastText(ThemeManager.textSecondary(context), cardBg);

        int greenAccent = 0xFF4CAF50;
        int greenDark = 0xFF2E7D32;
        int grayCard = ColorUtils.blendARGB(cardBg, Color.DKGRAY, 0.35f);
        int greenCard = ColorUtils.blendARGB(cardBg, greenAccent, 0.12f);

        for (PermissionCardBinding binding : cardBindings) {
            boolean isGranted = binding.item.checker.isGranted(context);
            if (isGranted) {
                // Підсвічено зеленим
                binding.cardView.setBackground(ThemeManager.roundedDrawable(context, 12, greenCard, greenAccent, 1.2f));
                binding.tvTitle.setTextColor(textPrimary);
                binding.tvDesc.setTextColor(textSecondary);

                binding.tvStatusBadge.setText(R.string.perm_wizard_status_granted);
                binding.tvStatusBadge.setTextColor(Color.WHITE);
                binding.tvStatusBadge.setBackground(ThemeManager.roundedDrawable(context, 8, greenDark, greenAccent, 1f));
            } else {
                // Сірий стан, потрібна дія
                binding.cardView.setBackground(ThemeManager.roundedDrawable(context, 12, grayCard, border, 1.0f));
                binding.tvTitle.setTextColor(textPrimary);
                binding.tvDesc.setTextColor(textSecondary);

                binding.tvStatusBadge.setText(R.string.perm_wizard_status_action);
                binding.tvStatusBadge.setTextColor(onAccent);
                binding.tvStatusBadge.setBackground(ThemeManager.roundedDrawable(context, 8, accent, border, 1f));
            }
        }
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }
}
