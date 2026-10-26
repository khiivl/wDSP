package com.radiorubka.wdsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

/**
 * Receiver to start McuService on boot, quick boot, or after an app update.
 *
 * <p>Deliberately does NOT listen to early-boot broadcasts (e.g. LOCKED_BOOT_COMPLETED)
 * or declare directBootAware. Starting early competes with system boot threads and risks
 * communicating with the MCU before its UART link or power rails are ready.
 *
 * <p>Instead, this receiver waits for full system initialization:
 * <ul>
 *     <li>{@link Intent#ACTION_BOOT_COMPLETED}: Delivered only after Android has fully booted,
 *         user storage is decrypted, and system services (including the MCU link) are up.</li>
 *     <li>QUICKBOOT_POWERON: Delivers wake-from-sleep events when resuming from RAM.</li>
 *     <li>{@link Intent#ACTION_MY_PACKAGE_REPLACED}: Restarts the foreground service immediately
 *         after an APK update/reinstall.</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "wDSP_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        Log.d(TAG, "Received broadcast action: " + action);

        // Intentionally start as late as possible: only after full boot complete,
        // on resume from sleep, or after an app update.
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action) ||
            // An app update kills the process, and the platform does not restart a foreground
            // service on its own afterward. Without this, an update leaves McuService dead until
            // the next power cycle.
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            startService(context, action);
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
