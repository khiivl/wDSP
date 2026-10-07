package com.radiorubka.wdsp;

import android.annotation.SuppressLint;
import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * The one way into the QF MCU manager ({@code mcu_service}), by reflection: the DSP frame
 * ({@code RPC_SetEQData}, the 0x80..0x8C commands) and the MCU message ({@code RPC_SendMcuMsgData},
 * message 0x18 and its sub-commands - the power-amp pre-gain, the button backlight).
 *
 * <p>Until 03.10.2026 the reflection lived inside McuService, so the activity had no way to send a
 * message without a second copy of it - which is exactly what the author's 0.5 backlight code is.
 * The binder is looked up once per process and kept; a failed lookup is retried on the next call,
 * because at boot mcu_service may not be up yet.
 */
final class McuLink {

    private static Object manager;
    private static Method setEqData;
    private static Method sendMsg;

    private McuLink() {
    }

    private static synchronized boolean ensure() throws Exception {
        if (manager != null) return true;
        @SuppressLint("PrivateApi") Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder binder = (IBinder) sm.getMethod("getService", String.class).invoke(null, "mcu_service");
        if (binder == null) return false;
        @SuppressLint("PrivateApi") Class<?> stub = Class.forName("android.qf.mcu.IMcuManager$Stub");
        Object m = stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (m == null) return false;
        setEqData = m.getClass().getMethod("RPC_SetEQData", byte[].class);
        sendMsg = m.getClass().getMethod("RPC_SendMcuMsgData", byte.class, byte[].class, int.class);
        manager = m;
        return true;
    }

    /** Sends one DSP frame. True when it reached the manager; throws what the reflection throws. */
    static boolean setEqData(byte[] frame) throws Exception {
        if (!ensure()) return false;
        setEqData.invoke(manager, (Object) frame);
        return true;
    }

    /** Sends one MCU message. True when it reached the manager; throws what the reflection throws. */
    static boolean sendMsg(byte message, byte[] payload) throws Exception {
        if (!ensure()) return false;
        sendMsg.invoke(manager, message, payload, payload.length);
        return true;
    }
}
