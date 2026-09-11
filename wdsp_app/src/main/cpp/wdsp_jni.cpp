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
Java_com_radiorubka_wdsp_NativeAnalyzer_nativePushPcm16(JNIEnv* env, jclass, jlong handle,
                                                       jshortArray samples, jint count, jfloat gain) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer == nullptr || samples == nullptr || count <= 0) return;

    jshort* data = env->GetShortArrayElements(samples, nullptr);
    if (data == nullptr) return;
    jsize available = env->GetArrayLength(samples);
    if (count > available) count = available;

    analyzer->pushPcm16(reinterpret_cast<const int16_t*>(data), count, 1, gain);

    env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeSetIsAcoustic(JNIEnv*, jclass, jlong handle,
                                                            jboolean acoustic) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer != nullptr) analyzer->setIsAcoustic(acoustic == JNI_TRUE);
}

JNIEXPORT jint JNICALL
Java_com_radiorubka_wdsp_NativeAnalyzer_nativeGetWaveform(JNIEnv* env, jclass, jlong handle,
                                                         jbyteArray outBuffer) {
    auto* analyzer = asAnalyzer(handle);
    if (analyzer == nullptr || outBuffer == nullptr) return 0;
    jsize len = env->GetArrayLength(outBuffer);
    if (len <= 0) return 0;

    jbyte* data = env->GetByteArrayElements(outBuffer, nullptr);
    if (data == nullptr) return 0;

    int got = analyzer->getWaveform(reinterpret_cast<uint8_t*>(data), len);
    env->ReleaseByteArrayElements(outBuffer, data, 0);
    return got;
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
    if (env->GetArrayLength(result) < 4 + wdsp::kHwBands * 2) return JNI_FALSE;
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

    // Parabolic sub-sample refinement of arrival peak
    float delta = 0.0f;
    if (arrival > 0 && arrival + 1 < (int) impulse.size()) {
        const float y0 = std::fabs(impulse[arrival - 1]);
        const float y1 = std::fabs(impulse[arrival]);
        const float y2 = std::fabs(impulse[arrival + 1]);
        const float denom = 2.0f * (y0 - 2.0f * y1 + y2);
        if (std::fabs(denom) > 1e-12f) {
            delta = (y0 - y2) / denom;
            if (delta < -1.0f || delta > 1.0f) delta = 0.0f;
        }
    }

    std::vector<float> out(4 + wdsp::kHwBands * 2, 0.0f);
    out[0] = static_cast<float>(arrival) + delta;
    out[1] = prominence;
    out[2] = static_cast<float>(polarity);
    out[3] = wdsp::SweepMeasurement::clarityDb(impulse.data(), (int) impulse.size(), arrival,
                                               sweep->sampleRate());
    // 1. Signal 16-band response around direct arrival
    sweep->bandLevelsDb(impulse.data(), (int) impulse.size(), arrival, out.data() + 4);
    // 2. Ambient noise floor 16 bands from pre-sweep lead silence (sample 48 -> samples 0..16384 of deconvolved silence)
    sweep->bandLevelsDb(impulse.data(), (int) impulse.size(), 48, out.data() + 4 + wdsp::kHwBands);

    env->SetFloatArrayRegion(result, 0, (jsize) out.size(), out.data());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeNoiseFloor(JNIEnv* env, jclass, jlong handle,
                                                      jfloatArray signal, jint length,
                                                      jfloatArray out16) {
    auto* sweep = asSweep(handle);
    if (sweep == nullptr || signal == nullptr || out16 == nullptr) return;
    if (env->GetArrayLength(out16) < wdsp::kHwBands) return;
    if (length > env->GetArrayLength(signal)) length = env->GetArrayLength(signal);

    jfloat* data = env->GetFloatArrayElements(signal, nullptr);
    if (data == nullptr) return;

    float bands[wdsp::kHwBands];
    sweep->spectrum16Db(data, length, bands);
    env->ReleaseFloatArrayElements(signal, data, JNI_ABORT);

    env->SetFloatArrayRegion(out16, 0, wdsp::kHwBands, bands);
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeSubtractNoise(JNIEnv* env, jclass,
                                                         jfloatArray sweepDb16,
                                                         jfloatArray noiseDb16,
                                                         jfloatArray outCleanDb16,
                                                         jfloatArray outSnrDb16) {
    if (sweepDb16 == nullptr || noiseDb16 == nullptr) return;
    if (env->GetArrayLength(sweepDb16) < wdsp::kHwBands ||
        env->GetArrayLength(noiseDb16) < wdsp::kHwBands) return;

    jfloat* sweepData = env->GetFloatArrayElements(sweepDb16, nullptr);
    jfloat* noiseData = env->GetFloatArrayElements(noiseDb16, nullptr);
    if (sweepData == nullptr || noiseData == nullptr) {
        if (sweepData != nullptr) env->ReleaseFloatArrayElements(sweepDb16, sweepData, JNI_ABORT);
        if (noiseData != nullptr) env->ReleaseFloatArrayElements(noiseDb16, noiseData, JNI_ABORT);
        return;
    }

    float clean[wdsp::kHwBands];
    float snr[wdsp::kHwBands];
    wdsp::SweepMeasurement::subtractNoise(sweepData, noiseData, clean, snr);

    env->ReleaseFloatArrayElements(sweepDb16, sweepData, JNI_ABORT);
    env->ReleaseFloatArrayElements(noiseDb16, noiseData, JNI_ABORT);

    if (outCleanDb16 != nullptr && env->GetArrayLength(outCleanDb16) >= wdsp::kHwBands) {
        env->SetFloatArrayRegion(outCleanDb16, 0, wdsp::kHwBands, clean);
    }
    if (outSnrDb16 != nullptr && env->GetArrayLength(outSnrDb16) >= wdsp::kHwBands) {
        env->SetFloatArrayRegion(outSnrDb16, 0, wdsp::kHwBands, snr);
    }
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeEstimateMicCompensation(JNIEnv* env, jclass,
                                                                   jfloatArray avgClean16,
                                                                   jfloatArray outCompensation16) {
    if (avgClean16 == nullptr || outCompensation16 == nullptr) return;
    if (env->GetArrayLength(avgClean16) < wdsp::kHwBands ||
        env->GetArrayLength(outCompensation16) < wdsp::kHwBands) return;

    jfloat* avgData = env->GetFloatArrayElements(avgClean16, nullptr);
    if (avgData == nullptr) return;

    float comp[wdsp::kHwBands];
    wdsp::SweepMeasurement::estimateMicCompensation(avgData, comp);
    env->ReleaseFloatArrayElements(avgClean16, avgData, JNI_ABORT);

    env->SetFloatArrayRegion(outCompensation16, 0, wdsp::kHwBands, comp);
}

JNIEXPORT jint JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeDeconvolve(JNIEnv* env, jclass, jlong handle,
                                                      jfloatArray recorded, jint length,
                                                      jfloatArray outImpulse) {
    auto* sweep = asSweep(handle);
    if (sweep == nullptr || recorded == nullptr) return 0;
    if (length > env->GetArrayLength(recorded)) length = env->GetArrayLength(recorded);

    jfloat* input = env->GetFloatArrayElements(recorded, nullptr);
    if (input == nullptr) return 0;

    std::vector<float> impulse;
    const bool ok = sweep->deconvolve(input, length, impulse);
    env->ReleaseFloatArrayElements(recorded, input, JNI_ABORT);
    if (!ok || impulse.empty()) return 0;

    const jsize impLen = static_cast<jsize>(impulse.size());
    if (outImpulse != nullptr) {
        const jsize copyLen = std::min(impLen, env->GetArrayLength(outImpulse));
        if (copyLen > 0) {
            env->SetFloatArrayRegion(outImpulse, 0, copyLen, impulse.data());
        }
    }
    return impLen;
}

JNIEXPORT jfloat JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeGccPhatDelay(JNIEnv* env, jclass,
                                                        jfloatArray hRef, jint refLen,
                                                        jfloatArray hCh, jint chLen,
                                                        jfloatArray outProminence1) {
    if (hRef == nullptr || hCh == nullptr) return 0.0f;
    if (refLen > env->GetArrayLength(hRef)) refLen = env->GetArrayLength(hRef);
    if (chLen > env->GetArrayLength(hCh)) chLen = env->GetArrayLength(hCh);

    jfloat* refData = env->GetFloatArrayElements(hRef, nullptr);
    jfloat* chData = env->GetFloatArrayElements(hCh, nullptr);
    if (refData == nullptr || chData == nullptr) {
        if (refData != nullptr) env->ReleaseFloatArrayElements(hRef, refData, JNI_ABORT);
        if (chData != nullptr) env->ReleaseFloatArrayElements(hCh, chData, JNI_ABORT);
        return 0.0f;
    }

    float prominence = 0.0f;
    float delay = wdsp::SweepMeasurement::gccPhatDelay(refData, refLen, chData, chLen, prominence);

    env->ReleaseFloatArrayElements(hRef, refData, JNI_ABORT);
    env->ReleaseFloatArrayElements(hCh, chData, JNI_ABORT);

    if (outProminence1 != nullptr && env->GetArrayLength(outProminence1) > 0) {
        env->SetFloatArrayRegion(outProminence1, 0, 1, &prominence);
    }
    return delay;
}

JNIEXPORT jint JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeDetectMidbassRollOff(JNIEnv* env, jclass,
                                                               jfloatArray avgClean16) {
    if (avgClean16 == nullptr || env->GetArrayLength(avgClean16) < wdsp::kHwBands) return 5;
    jfloat* data = env->GetFloatArrayElements(avgClean16, nullptr);
    if (data == nullptr) return 5;
    int idx = wdsp::SweepMeasurement::detectMidbassRollOff(data);
    env->ReleaseFloatArrayElements(avgClean16, data, JNI_ABORT);
    return idx;
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeSynthesizeAutoEq16(JNIEnv* env, jclass,
                                                              jfloatArray avgClean16,
                                                              jfloatArray micComp16,
                                                              jint hpfCutoffIdx,
                                                              jboolean hasSub,
                                                              jint targetCurveType,
                                                              jintArray outGains16,
                                                              jintArray outSubSettings2) {
    if (avgClean16 == nullptr || outGains16 == nullptr) return;
    if (env->GetArrayLength(avgClean16) < wdsp::kHwBands ||
        env->GetArrayLength(outGains16) < wdsp::kHwBands) return;

    jfloat* cleanData = env->GetFloatArrayElements(avgClean16, nullptr);
    jfloat* compData = (micComp16 != nullptr && env->GetArrayLength(micComp16) >= wdsp::kHwBands)
            ? env->GetFloatArrayElements(micComp16, nullptr) : nullptr;
    if (cleanData == nullptr) {
        if (compData != nullptr) env->ReleaseFloatArrayElements(micComp16, compData, JNI_ABORT);
        return;
    }

    int gains[wdsp::kHwBands];
    int subLpfIdx = 5;
    int subGain = 8;
    wdsp::SweepMeasurement::synthesizeAutoEq16(cleanData, compData,
                                               hpfCutoffIdx, hasSub, targetCurveType,
                                               gains, subLpfIdx, subGain);

    env->ReleaseFloatArrayElements(avgClean16, cleanData, JNI_ABORT);
    if (compData != nullptr) env->ReleaseFloatArrayElements(micComp16, compData, JNI_ABORT);

    env->SetIntArrayRegion(outGains16, 0, wdsp::kHwBands, gains);

    if (outSubSettings2 != nullptr && env->GetArrayLength(outSubSettings2) >= 2) {
        int sub[2] = { subLpfIdx, subGain };
        env->SetIntArrayRegion(outSubSettings2, 0, 2, sub);
    }
}

JNIEXPORT void JNICALL
Java_com_radiorubka_wdsp_NativeSweep_nativeSynthesizeHarmanEq16(JNIEnv* env, jclass clazz,
                                                               jfloatArray avgClean16,
                                                               jfloatArray micComp16,
                                                               jint hpfCutoffIdx,
                                                               jboolean hasSub,
                                                               jintArray outGains16,
                                                               jintArray outSubSettings2) {
    Java_com_radiorubka_wdsp_NativeSweep_nativeSynthesizeAutoEq16(env, clazz, avgClean16, micComp16,
                                                                 hpfCutoffIdx, hasSub, 0 /* TARGET_HARMAN */,
                                                                 outGains16, outSubSettings2);
}

} // extern "C"

