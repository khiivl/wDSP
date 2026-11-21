// Blocking wait for a system property change - the subscription Android does not offer in Java.
// See PropertyWatch.java for why it exists and how it is used.

#include <jni.h>
#include <sys/system_properties.h>
#include <time.h>

extern "C" {

// The global serial of the property area: bumped by every property set or added, system-wide.
JNIEXPORT jlong JNICALL
Java_com_radiorubka_wdsp_PropertyWatch_nativeSerial(JNIEnv *, jclass) {
    return static_cast<jlong>(__system_property_area_serial());
}

// Sleeps until the global serial moves past oldSerial (futex, no polling) or timeoutMs passes.
// Returns the new serial, or -1 on timeout.
JNIEXPORT jlong JNICALL
Java_com_radiorubka_wdsp_PropertyWatch_nativeWait(JNIEnv *, jclass, jlong oldSerial, jint timeoutMs) {
    timespec timeout{timeoutMs / 1000, static_cast<long>(timeoutMs % 1000) * 1000000L};
    uint32_t newSerial = 0;
    bool changed = __system_property_wait(nullptr, static_cast<uint32_t>(oldSerial), &newSerial,
                                          &timeout);
    return changed ? static_cast<jlong>(newSerial) : -1;
}

}
