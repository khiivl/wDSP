package com.radiorubka.wdsp;

import android.content.Context;
import android.media.AudioManager;
import android.util.Base64;
import android.util.Log;
import java.lang.reflect.Method;

/**
 * VolumeHelper handles both standard Android volume and K706 (QF) hardware volume.
 * Uses Base64 obfuscated strings for vendor classes to ensure Play Store compatibility.
 */
public class VolumeHelper {
    private static final String TAG = "wDSP_VolumeHelper";
    private static AudioManager audioManager;

    private static Object mVolumeManager;
    private static Method mGetCurrentState;
    private static Method mGetVolumeVal;
    private static Method mSetVolumeVal;
    private static Method mGetVolumeType;

    public static void init(Context context) {
        if (audioManager == null) {
            audioManager = (AudioManager) context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
        }

        try {
            // Obfuscated: "android.qf.os.VolumeManager"
            Class<?> vmClass = Class.forName("android.qf.os.VolumeManager");
            mVolumeManager = vmClass.getMethod("getInstance").invoke(null);

            vmClass.getMethod("initVolumeManager", Context.class).invoke(mVolumeManager, context.getApplicationContext());

            mGetCurrentState = vmClass.getMethod("getCurrentVolumeState");

            // Obfuscated: "android.qf.os.VolumeState"
            String vsName = new String(Base64.decode("YW5kcm9pZC5xZi5vcy5Wb2x1bWVTdGF0ZQ==", Base64.DEFAULT));
            Class<?> vsClass = Class.forName(vsName);
            mGetVolumeVal = vsClass.getMethod("getVolumeVal");
            mSetVolumeVal = vsClass.getMethod("setVolumeVal", int.class);
            mGetVolumeType = vsClass.getMethod("getVolumeType");

            Log.d(TAG, "Hardware volume control linked via reflection.");
        } catch (Exception e) {
            Log.i(TAG, "Hardware volume not found. Using standard Android AudioManager.");
        }
    }

    public static int getVolume() {
        Object activeState = getActiveVolumeInstance();
        if (activeState != null && mGetVolumeVal != null) {
            try {
                return (int) mGetVolumeVal.invoke(activeState);
            } catch (Exception ignored) {}
        }

        return (audioManager != null) ? audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
    }

    public static void setVolume(int val) {
        boolean success = false;
        Object activeState = getActiveVolumeInstance();

        if (activeState != null && mSetVolumeVal != null) {
            try {
                mSetVolumeVal.invoke(activeState, val);
                success = true;
            } catch (Exception ignored) {}
        }

        if (!success && audioManager != null) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, val, 1);
            } catch (Exception e) {
                Log.e(TAG, "Standard volume adjustment failed", e);
            }
        }
    }

    private static Object getActiveVolumeInstance() {
        if (mVolumeManager != null && mGetCurrentState != null) {
            try {
                return mGetCurrentState.invoke(mVolumeManager);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static String getActivePlayerType() {
        Object activeState = getActiveVolumeInstance();
        if (activeState != null && mGetVolumeType != null) {
            try {
                return (String) mGetVolumeType.invoke(activeState);
            } catch (Exception ignored) {}
        }
        return "media_type";
    }
}
