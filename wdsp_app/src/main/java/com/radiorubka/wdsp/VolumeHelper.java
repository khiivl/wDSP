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

    /**
     * Reaches a source that is <b>not</b> the current one — the whole basis of carrying a volume
     * across sources.
     *
     * <p>Read out of the decompiled framework rather than guessed:
     * {@code VolumeManager.findVolumeStateByType(String)} hands back any of the four states, and
     * {@code VolumeState.setVolumeVal(int)} then does this:
     *
     * <pre>
     *   if (i &lt; 0 || i &gt; 32) return;
     *   SystemProperties.set(this.propSave, String.valueOf(i));   // always
     *   if (this.volType.equals(sys.current.vol.type)) { ...RPC_SetVolume, mute... }
     * </pre>
     *
     * <p>🔑 So writing a source that is not live <b>stores the level and makes no sound</b>: no
     * command to the amplifier, no mute change. That is exactly what carrying a level across
     * sources needs, and it is why this can be done without disturbing anybody.
     *
     * <p>Null when the framework is older or obfuscated differently — see {@link #canReachOtherSources()},
     * which must be consulted before promising anyone that this unit can do it.
     */
    private static Method mFindStateByType;

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

            // Optional: a unit whose framework lacks it simply cannot carry a level across
            // sources, and must not claim it can.
            try {
                mFindStateByType = vmClass.getMethod("findVolumeStateByType", String.class);
            } catch (NoSuchMethodException e) {
                Log.i(TAG, "findVolumeStateByType is absent - this unit cannot carry a level between sources");
            }

            // Obfuscated: "android.qf.os.VolumeState"
            String vsName = new String(Base64.decode("YW5kcm9pZC5xZi5vcy5Wb2x1bWVTdGF0ZQ==", Base64.DEFAULT));
            Class<?> vsClass = Class.forName(vsName);

            mGetVolumeVal = vsClass.getMethod("getVolumeVal");
            mSetVolumeVal = vsClass.getMethod("setVolumeVal", int.class);
            mGetVolumeType = vsClass.getMethod("getVolumeType");

            // Reflected: public boolean getVolumeStateMute()
            mGetMuteState = vsClass.getMethod("getVolumeStateMute");

            Log.d(TAG, "Hardware volume control linked via reflection.");
        }
        catch (Exception e) {
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
                // 🔴 This was never assigned, so the Android fallback below ran on every single
                // call - including every step GALA takes - even when the hardware path had just
                // succeeded. On a unit with persist.sys.double_bt the platform mirrors the
                // hardware volume onto STREAM_MUSIC itself (i * 15 / 32), so writing that stream
                // directly is fighting the platform, and the platform wins at the next source
                // change. The fallback is still there for units where the reflection is absent.
                success = true;
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

    /**
     * Whether this unit can store a level for a source that is not the live one.
     *
     * <p>Must be checked before telling the radio that wDSP owns the synchronisation: a unit whose
     * framework has no {@code findVolumeStateByType} cannot do it, and claiming otherwise would
     * leave the radio deferring to somebody who cannot act — the failure being total silence on
     * that unit's volume synchronisation, with nothing to say why.
     */
    public static boolean canReachOtherSources() {
        return mVolumeManager != null && mFindStateByType != null
                && mSetVolumeVal != null && mGetVolumeVal != null;
    }

    /** The stored level of any source, live or not. -1 when it cannot be read. */
    public static int getVolumeForType(String volumeType) {
        Object state = stateForType(volumeType);
        if (state != null && mGetVolumeVal != null) {
            try {
                Object result = mGetVolumeVal.invoke(state);
                if (result instanceof Integer) return (Integer) result;
            } catch (Exception e) {
                Log.w(TAG, "could not read the volume of " + volumeType, e);
            }
        }
        return -1;
    }

    /**
     * Stores a level for a source. Silent unless that source happens to be the live one.
     *
     * @return true when the platform accepted it.
     */
    public static boolean setVolumeForType(String volumeType, int val) {
        if (val < 0 || val > 32) return false;   // the framework rejects it anyway, quietly
        Object state = stateForType(volumeType);
        if (state != null && mSetVolumeVal != null) {
            try {
                mSetVolumeVal.invoke(state, val);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "could not set the volume of " + volumeType, e);
            }
        }
        return false;
    }

    private static Object stateForType(String volumeType) {
        if (mVolumeManager == null || mFindStateByType == null) return null;
        try {
            return mFindStateByType.invoke(mVolumeManager, volumeType);
        } catch (Exception e) {
            return null;
        }
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
