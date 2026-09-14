package com.radiorubka.wdsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;
import android.util.Log;
import androidx.core.content.ContextCompat;

/**
 * Receiver to start McuService on boot or quick boot.
 * Uses ContextCompat to safely handle foreground service starts on all Android versions.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "wDSP_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "Received broadcast action: " + action);

        // 🔴 LOCKED_BOOT_COMPLETED cannot start the service itself: McuService is not direct-boot
        // aware, and before the user is unlocked the platform answers "Unable to start service
        // McuService U=0: not found" (measured 14.09.2026, 32.399 s into a boot). What it can do is
        // wait for the unlock - 0.2 s later on the owner's unit - instead of for BOOT_COMPLETED,
        // which arrived 12.9 s after that, by which time the assistant had taken the microphone.
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            startAtUnlock(context);
            return;
        }

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action) ||
            // An update kills the process and the platform does not bring a foreground service
            // back by itself. Without this the service stayed dead until somebody opened the
            // screen - see the note beside this action in the manifest.
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Still here after the unlock start: a second start of a running service only repeats
            // onStartCommand, and it is the fallback for a unit where USER_UNLOCKED never came.
            startService(context, action);
        }
    }

    /**
     * Starts the service the moment the user is unlocked. Returns at once: a receiver held open
     * would hold up every other app's LOCKED_BOOT_COMPLETED behind it. USER_UNLOCKED is delivered to
     * registered receivers only, so one is registered on the application context, which outlives
     * this call.
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
        // Unlocked between the check and the registration: the broadcast may already be gone.
        if (users.isUserUnlocked()) {
            try {
                app.unregisterReceiver(onUnlock);
                startService(app, "user unlocked while registering");
            } catch (IllegalArgumentException alreadyFired) {
                // the receiver ran and unregistered itself; the service is started
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
