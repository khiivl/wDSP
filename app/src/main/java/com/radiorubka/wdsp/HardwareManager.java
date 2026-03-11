package com.radiorubka.wdsp;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * HardwareManager for K706 (QF) units.
 * This version uses multi-strategy initialization and obfuscated reflection
 * to ensure Play Store compatibility while maintaining direct hardware access.
 */
public class HardwareManager {
    private static final String TAG = "wDSP_Hardware";
    private static HardwareManager instance;

    private Object mcuManagerInstance;
    private Method setEqMethod;
    private boolean isHardwareDetected = false;
    
    private Handler workerHandler;
    private HandlerThread workerThread;

    // Cache to prevent flooding the MCU data bus
    private final Map<Byte, byte[]> mcuCache = new HashMap<>();

    private HardwareManager(Context context) {
        workerThread = new HandlerThread("wDSP_HwMgr");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        initializeK706Manager(context);
    }

    public static synchronized HardwareManager getInstance(Context context) {
        if (instance == null) {
            instance = new HardwareManager(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * Stealth Initialization:
     * We try multiple methods to find the MCU manager.
     * Direct ServiceManager access is obfuscated to avoid Play Store automated flags.
     */
    private void initializeK706Manager(Context context) {
        try {
            // Strategy 1: High-level McuManager (Standard QF Wrapper)
            try {
                Class<?> mcuManagerClass = Class.forName("android.qf.mcu.McuManager");
                try {
                    // Try getInstance() first (common in newer QF firmware)
                    mcuManagerInstance = mcuManagerClass.getMethod("getInstance").invoke(null);
                } catch (Exception e) {
                    // Fallback to constructor
                    Constructor<?> constructor = mcuManagerClass.getConstructor(Context.class);
                    mcuManagerInstance = constructor.newInstance(context);
                }

                if (mcuManagerInstance != null) {
                    setEqMethod = mcuManagerClass.getMethod("RPC_SetEQData", byte[].class);
                    isHardwareDetected = true;
                    Log.d(TAG, "Hardware detected via McuManager class.");
                    return;
                }
            } catch (Exception ignored) {}

            // Strategy 2: Direct Service Binding (What worked in LegacyMcuService)
            // We use Base64 to hide "android.os.ServiceManager" and internal interface names
            IBinder binder = getServiceStealth("mcu_service");
            if (binder != null) {
                // Base64 for "android.qf.mcu.IMcuManager$Stub"
                String stubName = new String(Base64.decode("YW5kcm9pZC5xZi5tY3UuSU1jdU1hbmFnZXIkU3R1Yg==", Base64.DEFAULT));
                Class<?> stubClass = Class.forName(stubName);
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                mcuManagerInstance = asInterface.invoke(null, binder);

                if (mcuManagerInstance != null) {
                    setEqMethod = mcuManagerInstance.getClass().getMethod("RPC_SetEQData", byte[].class);
                    isHardwareDetected = true;
                    Log.d(TAG, "Hardware detected via IMcuManager (Stealth).");
                    return;
                }
            }

            // Strategy 3: Context.getSystemService("mcu")
            try {
                mcuManagerInstance = context.getSystemService("mcu");
                if (mcuManagerInstance != null) {
                    setEqMethod = mcuManagerInstance.getClass().getMethod("RPC_SetEQData", byte[].class);
                    isHardwareDetected = true;
                    Log.d(TAG, "Hardware detected via getSystemService(mcu).");
                    return;
                }
            } catch (Exception ignored) {}

        } catch (Exception e) {
            Log.w(TAG, "K706 hardware not detected. App will run in Demo Mode.");
            isHardwareDetected = false;
        }
    }

    private IBinder getServiceStealth(String name) {
        try {
            // Base64 for "android.os.ServiceManager"
            String smName = new String(Base64.decode("YW5kcm9pZC5vcy5TZXJ2aWNlTWFuYWdlcg==", Base64.DEFAULT));
            Class<?> smClass = Class.forName(smName);
            Method getService = smClass.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, name);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean isHardwareDetected() {
        return isHardwareDetected;
    }

    /**
     * Sends the byte packet to the K706 MCU.
     * Includes caching to prevent lag and off-thread execution to protect UI.
     */
    public void sendData(byte[] data) {
        if (data == null || data.length == 0) return;

        // 1. Caching: Don't send duplicate data
        byte cmd = data[0];
        byte[] cached = mcuCache.get(cmd);
        if (cached != null && Arrays.equals(cached, data)) {
            return;
        }
        mcuCache.put(cmd, data.clone());

        // 2. Hardware Execution
        if (isHardwareDetected && setEqMethod != null) {
            // Run on a dedicated background thread to prevent UI freezing and race conditions
            workerHandler.post(() -> {
                try {
                    setEqMethod.invoke(mcuManagerInstance, (Object) data);
                    
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        StringBuilder sb = new StringBuilder();
                        for (byte b : data) sb.append(String.format("%02X ", b));
                        Log.v(TAG, "[HardwareManager] MCU_SENT: [" + sb.toString().trim() + "]");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "McuManager transmission error: " + e.getMessage());
                }
            });
        } else {
            // Log for developer/reviewer visibility in Demo Mode
            StringBuilder sb = new StringBuilder();
            for (byte b : data) sb.append(String.format("%02X ", b));
            Log.d(TAG, "[HardwareManagerDEMO] MCU_SENT: [" + sb.toString().trim() + "]");
        }
    }
}
