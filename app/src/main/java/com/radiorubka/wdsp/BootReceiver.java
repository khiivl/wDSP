package com.radiorubka.wdsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            Log.d(TAG, "Starting McuService as foreground service...");
            Intent serviceIntent = new Intent(context, McuService.class);
            
            // Required for Android 8.0+ to ensure the service starts reliably from background
            try {
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start McuService: " + e.getMessage());
            }
        }
    }
}
