/**
 * Host-side proof for the sweep measurement. Not part of the APK - see CMakeLists.txt.
 *
 *   g++ -O2 -std=c++17 -o /tmp/wdsp_sweep test_sweep.cpp sweep.cpp analyzer.cpp fft.cpp \
 *       stitcher.cpp && /tmp/wdsp_sweep
 *
 * A room measurement is easy to get subtly wrong and hard to check on a car: the answer is
 * plausible either way. Here the answer is known in advance, so a wrong one is obvious.
 */
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

#include "sweep.h"

using namespace wdsp;

namespace {

constexpr int kRate = 48000;
constexpr float kSweepSeconds = 2.0f;

int failures = 0;

void check(bool ok, const char* what, const char* detail) {
    std::printf("  [%s] %s %s\n", ok ? "PASS" : "FAIL", what, detail);
    if (!ok) failures++;
}

/** A recording of the sweep, delayed, scaled, optionally inverted, with noise on top. */
std::vector<float> playThrough(const SweepMeasurement& m, int delay, float gain, bool invert,
                               float noiseRms, int tailSamples) {
    const int len = m.sweepLength() + delay + tailSamples;
    std::vector<float> out(len, 0.0f);
    const std::vector<float>& sweep = m.sweep();
    for (int i = 0; i < m.sweepLength(); i++) {
        out[delay + i] = sweep[i] * gain * (invert ? -1.0f : 1.0f);
    }
    if (noiseRms > 0.0f) {
        std::mt19937 rng(1234);
        std::normal_distribution<float> noise(0.0f, noiseRms);
        for (float& v : out) v += noise(rng);
    }
    return out;
}

/** One-pole low pass, so the measured response has a shape that is known analytically. */
void lowPass(std::vector<float>& signal, float cutoffHz) {
    const float dt = 1.0f / kRate;
    const float rc = 1.0f / (2.0f * 3.14159265f * cutoffHz);
    const float alpha = dt / (rc + dt);
    float state = 0.0f;
    for (float& v : signal) {
        state += alpha * (v - state);
        v = state;
    }
}

void testCleanRecovery(const SweepMeasurement& m) {
    std::printf("\nA perfect recording gives a clean impulse\n");
    for (int delay : {0, 480, 4800, 24000}) {
        std::vector<float> rec = playThrough(m, delay, 1.0f, false, 0.0f, kRate / 2);
        std::vector<float> ir;
        if (!m.deconvolve(rec.data(), (int) rec.size(), ir)) {
            check(false, "deconvolve", "refused the recording");
            continue;
        }
        float prominence = 0.0f;
        const int arrival = SweepMeasurement::findArrival(ir.data(), (int) ir.size(), prominence);
        const int error = arrival - delay;
        char detail[160];
        std::snprintf(detail, sizeof(detail),
                      "delay %6d -> arrival %6d (error %+3d samples, %+.2f ms), prominence %.0f",
                      delay, arrival, error, error * 1000.0f / kRate, prominence);
        check(std::abs(error) <= 4 && prominence > 50.0f, "arrival", detail);
    }
}

void testPolarity(const SweepMeasurement& m) {
    std::printf("\nAn inverted loudspeaker is seen as inverted\n");
    for (bool invert : {false, true}) {
        std::vector<float> rec = playThrough(m, 2400, 0.7f, invert, 0.0f, kRate / 2);
        std::vector<float> ir;
        m.deconvolve(rec.data(), (int) rec.size(), ir);
        float prominence = 0.0f;
        const int arrival = SweepMeasurement::findArrival(ir.data(), (int) ir.size(), prominence);
        const int sign = SweepMeasurement::polarityAt(ir.data(), (int) ir.size(), arrival);
        char detail[96];
        std::snprintf(detail, sizeof(detail), "%s wiring -> sign %+d",
                      invert ? "reversed" : "normal", sign);
        check(sign == (invert ? -1 : 1), "polarity", detail);
    }
}

void testFlatResponse(const SweepMeasurement& m) {
    std::printf("\nA flat path measures flat\n");
    std::vector<float> rec = playThrough(m, 2400, 0.5f, false, 0.0f, kRate / 2);
    std::vector<float> ir;
    m.deconvolve(rec.data(), (int) rec.size(), ir);
    float prominence = 0.0f;
    const int arrival = SweepMeasurement::findArrival(ir.data(), (int) ir.size(), prominence);

    float bands[kHwBands];
    m.bandLevelsDb(ir.data(), (int) ir.size(), arrival, bands);

    // The sweep only covers 20 Hz to 20 kHz, so the outermost bands sit on its edges where there
    // is nothing to measure. Judge the range the sweep actually excites.
    float lowest = 1e9f;
    float highest = -1e9f;
    for (int b = 2; b < kHwBands - 1; b++) {
        lowest = std::min(lowest, bands[b]);
        highest = std::max(highest, bands[b]);
    }
    static const float centres[16] = {20, 31.5f, 50, 80, 125, 200, 315, 500,
                                      800, 1250, 2000, 3150, 5000, 8000, 12500, 20000};
    for (int b = 0; b < kHwBands; b++) {
        std::printf("        %7.1f Hz  %8.2f dB\n", centres[b], bands[b]);
    }
    char detail[128];
    std::snprintf(detail, sizeof(detail), "spread across bands 2..14 is %.1f dB", highest - lowest);
    check(highest - lowest < 3.0f, "flatness", detail);
}

void testKnownFilter(const SweepMeasurement& m) {
    std::printf("\nA known low pass is measured where it is\n");
    std::vector<float> rec = playThrough(m, 2400, 0.5f, false, 0.0f, kRate);
    lowPass(rec, 1000.0f);

    std::vector<float> ir;
    m.deconvolve(rec.data(), (int) rec.size(), ir);
    float prominence = 0.0f;
    const int arrival = SweepMeasurement::findArrival(ir.data(), (int) ir.size(), prominence);

    float bands[kHwBands];
    m.bandLevelsDb(ir.data(), (int) ir.size(), arrival, bands);

    // A one-pole filter is 3 dB down at its corner and falls 6 dB an octave above it. Band 9 is
    // 1250 Hz, band 12 is 5000 Hz - two octaves apart, so about 12 dB between them.
    const float atCorner = bands[9];
    const float twoOctavesUp = bands[12];
    const float drop = atCorner - twoOctavesUp;
    char detail[160];
    std::snprintf(detail, sizeof(detail),
                  "1250 Hz %.1f dB, 5000 Hz %.1f dB, drop %.1f dB (theory ~12)",
                  atCorner, twoOctavesUp, drop);
    check(drop > 8.0f && drop < 16.0f, "slope", detail);
}

void testNoiseTolerance(const SweepMeasurement& m) {
    std::printf("\nA noisy cabin does not move the arrival\n");
    for (float noise : {0.001f, 0.01f, 0.05f}) {
        std::vector<float> rec = playThrough(m, 9600, 0.2f, false, noise, kRate / 2);
        std::vector<float> ir;
        m.deconvolve(rec.data(), (int) rec.size(), ir);
        float prominence = 0.0f;
        const int arrival = SweepMeasurement::findArrival(ir.data(), (int) ir.size(), prominence);
        const int error = arrival - 9600;
        const float snrDb = 20.0f * std::log10(0.2f / noise);
        char detail[160];
        std::snprintf(detail, sizeof(detail),
                      "signal %.0f dB over noise -> error %+d samples (%+.2f ms), prominence %.0f",
                      snrDb, error, error * 1000.0f / kRate, prominence);
        check(std::abs(error) <= 24, "arrival under noise", detail);
    }
}

} // namespace

int main() {
    std::printf("Sweep measurement, %.0f s exponential sweep 20 Hz - 20 kHz at %d Hz\n",
                kSweepSeconds, kRate);
    SweepMeasurement m(kRate, 20.0f, 20000.0f, kSweepSeconds);
    std::printf("sweep is %d samples, %.2f s\n", m.sweepLength(),
                m.sweepLength() / (float) kRate);

    testCleanRecovery(m);
    testPolarity(m);
    testFlatResponse(m);
    testKnownFilter(m);
    testNoiseTolerance(m);

    std::printf("\n%s (%d failures)\n", failures == 0 ? "ALL PASS" : "FAILURES", failures);
    return failures == 0 ? 0 : 1;
}
