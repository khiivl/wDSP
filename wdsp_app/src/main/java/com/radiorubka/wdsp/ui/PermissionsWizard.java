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

    public static boolean isRootGranted(Context context) {
        return RootAccess.hasRoot();
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
     * Перевіряє чи всі дозволи надані (включно з Root, якщо на пристрої є su).
     */
    public static boolean areAllGranted(Context context) {
        List<Item> items = getItems();
        boolean hasSu = new java.io.File("/system/bin/su").exists() || new java.io.File("/system/xbin/su").exists();
        for (Item item : items) {
            if (item.optional) {
                if (hasSu && !item.checker.isGranted(context)) {
                    return false;
                }
            } else if (!item.checker.isGranted(context)) {
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

    /** Describes this unit, not the owner's settings - backups do not carry it. */
    private static final String DEVICE_STATE_PREFS = "wdsp_device_state";
    private static final String PREF_LAST_WIZARD_MISSING = "wizard_last_missing";

    /**
     * Opens the wizard on its own only when it has something to say.
     *
     * <p>Once per new version if a required permission is really missing, and again whenever the
     * set of missing ones changes - which is how a grant the platform withdrew without notice gets
     * noticed. Never merely because the version moved while everything is in place: that is what
     * showed the wizard on every start to a tester whose every card was green.
     *
     * <p>What was shown is recorded when it is shown, not when the dialog closes. A dialog can be
     * closed in ways that never call back - a tap outside, Back, the activity going away - and the
     * version that waited for the callback opened again on every start.
     */
    public static boolean checkAndShowIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        SharedPreferences state = activity.getSharedPreferences(DEVICE_STATE_PREFS, Context.MODE_PRIVATE);
        int lastVer = state.getInt(PREF_LAST_WIZARD_VERSION, -1);
        int currentVer = getAppVersionCode(activity);
        String missing = missingRequired(activity);
        String lastMissing = state.getString(PREF_LAST_WIZARD_MISSING, "");

        boolean needed = !missing.isEmpty() && (lastVer < currentVer || !missing.equals(lastMissing));
        state.edit()
                .putInt(PREF_LAST_WIZARD_VERSION, currentVer)
                .putString(PREF_LAST_WIZARD_MISSING, missing)
                .apply();
        if (needed) show(activity);
        return needed;
    }

    /** Ids of the required items not in place right now, such as "1,4"; empty when none. */
    private static String missingRequired(Context context) {
        StringBuilder sb = new StringBuilder();
        for (Item item : getItems()) {
            if (item.optional || item.checker.isGranted(context)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(item.id);
        }
        return sb.toString();
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
        // The root card shows the answer RootAccess last took; opening the wizard does not ask
        // Magisk again (that was a su, and a Magisk toast, on every opening). A tap on the card does.
        return wizard;
    }

    /**
     * Reads the notification grant the way the platform enforces it, not the way it records it:
     * asks for the active media sessions under our listener. This platform withdraws grants
     * without notice (owner, 11.09.2026), and a setting that still names us proves nothing.
     */
    private static boolean sessionsReallyReadable(Context ctx) {
        try {
            android.media.session.MediaSessionManager sessions =
                    (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (sessions == null) return false;
            sessions.getActiveSessions(new android.content.ComponentName(ctx,
                    com.radiorubka.wdsp.NotificationAccess.class));
            return true;
        } catch (SecurityException e) {
            return false;
        } catch (Throwable t) {
            return true;   // an unrelated failure is not a withdrawn grant - do not paint it red
        }
    }

    /** A runtime permission can be granted while its app-op says otherwise; both have to agree. */
    private static boolean opAllowed(Context ctx, String op) {
        try {
            android.app.AppOpsManager ops =
                    (android.app.AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return true;
            int mode = ops.checkOpNoThrow(op, android.os.Process.myUid(), ctx.getPackageName());
            return mode != android.app.AppOpsManager.MODE_IGNORED
                    && mode != android.app.AppOpsManager.MODE_ERRORED;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Where a green card leads. The platform withdraws grants and lets them go stale without
     * notice, and the only cure for a stale one is switching it off and on in Settings - so a card
     * that reads as granted still opens its switch instead of doing nothing (owner's rule,
     * 11.09.2026).
     */
    private static void openSwitch(Item item, Activity act) {
        try {
            switch (item.id) {
                case 1:
                case 2:
                    item.action.execute(act);   // both already open their own switch
                    return;
                case 3:
                    act.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    return;
                case 6:
                    // Root lives in Magisk: a tap on the green card asks it again.
                    new Thread(() -> {
                        RootAccess.request(act);
                        act.runOnUiThread(PermissionsWizard::refreshCurrent);
                    }, "wDSP_WizardRootRecheck").start();
                    return;
                default:
                    act.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + act.getPackageName())));
            }
        } catch (Throwable t) {
            android.util.Log.w("wDSP_Wizard", "could not open the switch for item " + item.id + ": " + t);
        }
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
                ctx -> NowPlaying.getInstance(ctx).canReadSessions() && sessionsReallyReadable(ctx),
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
                        // The API reads the recorded grant; the last real attempt says whether
                        // a window was actually let through (see OverlayHealth).
                        return Settings.canDrawOverlays(ctx)
                                && !com.radiorubka.wdsp.OverlayHealth.lastAttemptRefused(ctx);
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
                ctx -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        && opAllowed(ctx, android.app.AppOpsManager.OPSTR_FINE_LOCATION),
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
                ctx -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        && opAllowed(ctx, android.app.AppOpsManager.OPSTR_RECORD_AUDIO),
                act -> ActivityCompat.requestPermissions(act, new String[]{
                        Manifest.permission.RECORD_AUDIO
                }, REQ_AUDIO)
        ));

        // 6. Суперкористувач (Root Magisk) - для вимірювань салону та повного діапазону мікрофона FM-радіо
        list.add(new Item(
                6,
                R.string.perm_wizard_item_root_title,
                R.string.perm_wizard_item_root_desc,
                ctx -> RootAccess.hasRoot(),
                act -> new Thread(() -> {
                    RootAccess.Outcome outcome = RootAccess.request(act);
                    if (outcome == RootAccess.Outcome.DENIED_BY_POLICY) {
                        act.runOnUiThread(() -> Toaster.show(act, act.getString(R.string.room_root_blocked)));
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
                } else {
                    openSwitch(item, activity);
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
