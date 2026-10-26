package com.radiorubka.wdsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;
import android.util.Log;
import androidx.core.content.ContextCompat;

/**
 * Receiver to start McuService on boot, quick boot, or after an app update.
 * Uses ContextCompat to safely handle foreground service starts on all Android versions.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "wDSP_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "Received broadcast action: " + action);

        // LOCKED_BOOT_COMPLETED cannot start the service directly: McuService isn't direct-boot
        // aware, so the platform refuses the start attempt this early ("Unable to start service
        // McuService U=0: not found") - it fires well before Android has actually finished
        // booting. ACTION_USER_UNLOCKED arrives much sooner than BOOT_COMPLETED does, so wait for
        // that instead of racing the rest of boot.
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            startAtUnlock(context);
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action) ||
            // An app update kills the process, and the platform does not restart a foreground
            // service on its own afterward - nothing else does either, since the only other
            // starter is MainActivity, which on this app might not get opened for months. Without
            // this, an update leaves McuService dead (no preset switching, no GALA, no loudness,
            // chip just keeps whatever was last pushed into it) until the next power cycle.
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Still here after a LOCKED_BOOT_COMPLETED-triggered unlock wait: a second start of an
            // already-running service just repeats onStartCommand, and this is also the fallback
            // for a unit where USER_UNLOCKED never arrives.
            startService(context, action);
        }
    }

    /**
     * Starts the service the moment the user is unlocked. Returns immediately - a receiver held
     * open here would hold up every other app's own LOCKED_BOOT_COMPLETED handling behind it.
     * ACTION_USER_UNLOCKED is delivered to registered receivers only, so one is registered on the
     * application context, which outlives this call.
     */
    private static void startAtUnlock(Context context) {
        final Context app = context.getApplicationContext();
        final UserManager users = (UserManager) app.getSystemService(Context.USER_SERVICE);
        if (users == null || users.isUserUnlocked()) {
            startService(app, "LOCKED_BOOT_COMPLETED, user already unlocked");
            return;
        }
        final BroadcastReceiver onUnlock = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                try {
                    app.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
                startService(app, Intent.ACTION_USER_UNLOCKED);
            }
        };
        try {
            app.registerReceiver(onUnlock, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
            Log.i(TAG, "waiting for the user to be unlocked to start McuService");
        } catch (Throwable t) {
            Log.w(TAG, "could not wait for unlock, BOOT_COMPLETED will start the service: " + t);
            return;
        }
        // Unlocked between the check above and registering the receiver - the broadcast may
        // already have been sent and missed.
        if (users.isUserUnlocked()) {
            try {
                app.unregisterReceiver(onUnlock);
                startService(app, "user unlocked while registering");
            } catch (IllegalArgumentException alreadyFired) {
                // the receiver already ran and unregistered itself - the service is started
            }
        }
    }

    private static void startService(Context context, String why) {
        Log.d(TAG, "Starting McuService as foreground service... (" + why + ")");
        Intent serviceIntent = new Intent(context, McuService.class);

        // Required for Android 8.0+ to ensure the service starts reliably from background
        try {
            ContextCompat.startForegroundService(context, serviceIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start McuService: " + e.getMessage());
        }
    }
}
