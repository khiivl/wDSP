#include <jni.h>

#include <memory>
#include <vector>

#include "analyzer.h"
#include "sweep.h"

namespace {

inline wdsp::Analyzer* asAnalyzer(jlong handle) {
    return reinterpret_cast<wdsp::Analyzer*>(handle);
}

inline wdsp::SweepMeasurement* asSweep(jlong handle) {
    return reinterpret_cast<wdsp::SweepMeasurement*>(handle);
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeCreate(JNIEnv*, jclass,
                                                     jint sampleRate, jint captureSize) {
    auto* analyzer = new wdsp::Analyzer(sampleRate, captureSize);
    return reinterpret_cast<jlong>(analyzer);
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete asAnalyzer(handle);
}

JNIEXPORT jint JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativePush(JNIEnv* env, jclass, jlong handle,
                                                   jbyteArray block, jint len) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer == nullptr || block == nullptr || len <= 0) return 0;

    jbyte* data = env->GetByteArrayElements(block, nullptr);
    if (data == nullptr) return 0;
    jsize available = env->GetArrayLength(block);
    if (len > available) len = available;

    int fresh = analyzer->pushWaveform(reinterpret_cast<const uint8_t*>(data), len);

    env->ReleaseByteArrayElements(block, data, JNI_ABORT);
    return fresh;
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeProcess(JNIEnv*, jclass, jlong handle,
                                                      jint timeoutMs) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer != nullptr) analyzer->waitAndProcess(timeoutMs);
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeStop(JNIEnv*, jclass, jlong handle) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer != nullptr) analyzer->stop();
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeSetHop(JNIEnv*, jclass, jlong handle, jint hop) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer != nullptr) analyzer->setHop(hop);
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeSetConfig(JNIEnv*, jclass, jlong handle,
                                                        jfloat attackMs, jfloat releaseMs,
                                                        jfloat latencyMs, jfloat refMaxDb,
                                                        jfloat rangeDb) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer == nullptr) return;
    wdsp::Analyzer::Config config;
    config.attackMs = attackMs;
    config.releaseMs = releaseMs;
    config.latencyMs = latencyMs;
    config.refMaxDb = refMaxDb;
    config.rangeDb = rangeDb;
    analyzer->setConfig(config);
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeSetAgc(JNIEnv*, jclass, jlong handle,
                                                     jint consumer, jboolean enabled,
                                                     jfloat strength, jfloat minRefDb) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer == nullptr) return;
    wdsp::Analyzer::AgcConfig config;
    config.enabled = enabled == JNI_TRUE;
    config.strength = strength;
    config.minRefDb = minRefDb;
    analyzer->setAgcConfig(consumer, config);
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeSetDspCurve(JNIEnv* env, jclass, jlong handle,
                                                          jfloatArray curve16) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer == nullptr || curve16 == nullptr) return;
    if (env->GetArrayLength(curve16) < wdsp::kHwBands) return;

    jfloat* data = env->GetFloatArrayElements(curve16, nullptr);
    if (data == nullptr) return;
    analyzer->setDspCurve(reinterpret_cast<const float*>(data));
    env->ReleaseFloatArrayElements(curve16, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeGetLevels(JNIEnv* env, jclass, jlong handle,
                                                        jint consumer, jfloatArray out32,
                                                        jfloatArray out16) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer == nullptr || out32 == nullptr) return;
    if (env->GetArrayLength(out32) < wdsp::kBands) return;

    float levels32[wdsp::kBands];
    float levels16[wdsp::kHwBands];
    analyzer->getLevels(consumer, levels32, levels16);

    env->SetFloatArrayRegion(out32, 0, wdsp::kBands, levels32);
    if (out16 != nullptr && env->GetArrayLength(out16) >= wdsp::kHwBands) {
        env->SetFloatArrayRegion(out16, 0, wdsp::kHwBands, levels16);
    }
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeGetLevelsDb(JNIEnv* env, jclass, jlong handle,
                                                          jfloatArray out32) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer == nullptr || out32 == nullptr) return;
    if (env->GetArrayLength(out32) < wdsp::kBands) return;

    float db[wdsp::kBands];
    analyzer->getLevelsDb(db);
    env->SetFloatArrayRegion(out32, 0, wdsp::kBands, db);
}

JNIEXPORT jint JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeDiscontinuities(JNIEnv*, jclass, jlong handle) {
    auto* analyzer = asAnalyzer(handle);
    return analyzer == nullptr ? 0 : analyzer->discontinuities();
}

JNIEXPORT jint JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeFrames(JNIEnv*, jclass, jlong handle) {
    auto* analyzer = asAnalyzer(handle);
    return analyzer == nullptr ? 0 : analyzer->framesProduced();
}

// --- room measurement by sweep -------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeCreate(JNIEnv*, jclass, jint sampleRate,
                                                  jfloat startHz, jfloat endHz, jfloat seconds) {
    return reinterpret_cast<jlong>(new wdsp::SweepMeasurement(sampleRate, startHz, endHz, seconds));
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    delete asSweep(handle);
}

JNIEXPORT jint JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeLength(JNIEnv*, jclass, jlong handle) {
    auto* sweep = asSweep(handle);
    return sweep == nullptr ? 0 : sweep->sweepLength();
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeGenerate(JNIEnv* env, jclass, jlong handle,
                                                    jfloatArray out, jfloat amplitude) {
    auto* sweep = asSweep(handle);
    if (sweep == nullptr || out == nullptr) return;
    if (env->GetArrayLength(out) < sweep->sweepLength()) return;

    jfloat* data = env->GetFloatArrayElements(out, nullptr);
    if (data == nullptr) return;
    sweep->generate(data, amplitude);
    env->ReleaseFloatArrayElements(out, data, 0);
}

/**
 * Deconvolves one recording and reports what it found.
 *
 * The layout of the result array is fixed by NativeSweep: arrival in samples, how far the peak
 * stood above the rest, polarity, then sixteen band levels in decibels.
 */
JNIEXPORT jfloat JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeBandwidth(JNIEnv* env, jclass, jfloatArray recorded,
                                                     jint length, jint sampleRate) {
    if (recorded == nullptr) return 0.0f;
    if (length > env->GetArrayLength(recorded)) length = env->GetArrayLength(recorded);
    jfloat* data = env->GetFloatArrayElements(recorded, nullptr);
    if (data == nullptr) return 0.0f;
    const float ratio = wdsp::SweepMeasurement::bandwidthRatioDb(data, length, sampleRate);
    env->ReleaseFloatArrayElements(recorded, data, JNI_ABORT);
    return ratio;
}

JNIEXPORT jboolean JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeAnalyse(JNIEnv* env, jclass, jlong handle,
                                                   jfloatArray recorded, jint length,
                                                   jfloatArray result) {
    auto* sweep = asSweep(handle);
    if (sweep == nullptr || recorded == nullptr || result == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(result) < 4 + wdsp::kHwBands) return JNI_FALSE;
    if (length > env->GetArrayLength(recorded)) length = env->GetArrayLength(recorded);

    jfloat* input = env->GetFloatArrayElements(recorded, nullptr);
    if (input == nullptr) return JNI_FALSE;

    std::vector<float> impulse;
    const bool ok = sweep->deconvolve(input, length, impulse);
    env->ReleaseFloatArrayElements(recorded, input, JNI_ABORT);
    if (!ok || impulse.empty()) return JNI_FALSE;

    float prominence = 0.0f;
    const int arrival = wdsp::SweepMeasurement::findArrival(impulse.data(),
                                                            (int) impulse.size(), prominence);
    if (arrival < 0) return JNI_FALSE;
    const int polarity = wdsp::SweepMeasurement::polarityAt(impulse.data(),
                                                             (int) impulse.size(), arrival);

    std::vector<float> out(4 + wdsp::kHwBands, 0.0f);
    out[0] = static_cast<float>(arrival);
    out[1] = prominence;
    out[2] = static_cast<float>(polarity);
    out[3] = wdsp::SweepMeasurement::clarityDb(impulse.data(), (int) impulse.size(), arrival,
                                               sweep->sampleRate());
    sweep->bandLevelsDb(impulse.data(), (int) impulse.size(), arrival, out.data() + 4);

    env->SetFloatArrayRegion(result, 0, (jsize) out.size(), out.data());
    return JNI_TRUE;
}

} // extern "C"
