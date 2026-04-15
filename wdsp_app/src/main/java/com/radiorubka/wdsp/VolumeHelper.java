package com.radiorubka.wdsp;

import static android.media.AudioManager.FLAG_SHOW_UI;

import android.annotation.SuppressLint;
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
    private static Method mGetMuteState;

    public static void init(Context context) {
        if (audioManager == null) {
            audioManager = (AudioManager) context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
        }

        try {
            // "android.qf.os.VolumeManager"
            @SuppressLint("PrivateApi") Class<?> vmClass = Class.forName("android.qf.os.VolumeManager");
            mVolumeManager = vmClass.getMethod("getInstance").invoke(null);

            vmClass.getMethod("initVolumeManager", Context.class).invoke(mVolumeManager, context.getApplicationContext());

            mGetCurrentState = vmClass.getMethod("getCurrentVolumeState");

            // Obfuscated: "android.qf.os.VolumeState"
            String vsName = new String(Base64.decode("YW5kcm9pZC5xZi5vcy5Wb2x1bWVTdGF0ZQ==", Base64.DEFAULT));
            Class<?> vsClass = Class.forName(vsName);

            mGetVolumeVal = vsClass.getMethod("getVolumeVal");
            mSetVolumeVal = vsClass.getMethod("setVolumeVal", int.class);
            mGetVolumeType = vsClass.getMethod("getVolumeType");

            // Reflected: public boolean getVolumeStateMute()
            mGetMuteState = vsClass.getMethod("getVolumeStateMute");

            Log.d(TAG, "Hardware volume control linked via reflection.");
        } catch (Exception e) {
            Log.i(TAG, "Hardware volume not found. Using standard Android AudioManager.");
        }
    }

    public static boolean isHardwareMuted() {
        Object activeState = getActiveVolumeInstance();
        if (activeState != null && mGetMuteState != null) {
            try {
                Object result = mGetMuteState.invoke(activeState);
                if (result instanceof Boolean) {
                    return (Boolean) result;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to invoke hardware getVolumeStateMute", e);
            }
        }
        return false;
    }

    public static int getVolume() {
        Object activeState = getActiveVolumeInstance();
        if (activeState != null && mGetVolumeVal != null) {
            try {
                Object result = mGetVolumeVal.invoke(activeState);
                // Check for null before unboxing the result to a primitive int
                if (result instanceof Integer) {
                    return (Integer) result;
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to get hardware volume", e);
            }
        }

        return (audioManager != null) ? audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) : 0;
    }

    public static void setVolume(int val) {
        boolean success = false;
        Object activeState = getActiveVolumeInstance();

        if (activeState != null && mSetVolumeVal != null) {
            try {
                mSetVolumeVal.invoke(activeState, val);
                Log.d(TAG, "[VolumeSender2000] Volume has been set to " + val);
            } catch (Exception ignored) {}
        }

        if (!success && audioManager != null) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, val, FLAG_SHOW_UI);
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
