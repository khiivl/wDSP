// Host check of the one-sided crossover notch (TODO 3 step 5, 08.10.2026) on synthetic thirds.
// Criteria written before the detector:
//   1. the right tweeter reversed - a 10 dB notch at 3150 Hz on the right only -> "B" (right);
//   2. both channels the same, no notch                                         -> nothing;
//   3. the same notch on both sides (the crossover's design)                    -> nothing;
//   4. a reflection's comb on the right - notches at 1600, 5040, 8000 Hz         -> nothing;
//   5. the notch of case 1 under noise (SNR 6 dB)                               -> nothing.
//
//   g++ -O2 -std=c++17 -o /tmp/xover test_crossover.cpp sweep.cpp analyzer.cpp fft.cpp stitcher.cpp
#include <cstdio>
#include "bands.h"
#include "sweep.h"

using namespace wdsp;

static int failures = 0;

static void run(const char* name, const float* a, const float* b, const float* noiseB, int expect) {
    float noise[kThirdOctaveBands];
    for (float& n : noise) n = -60.0f;
    float hz = 0.0f, depth = 0.0f;
    const int got = SweepMeasurement::crossoverNotch(a, noise, b, noiseB ? noiseB : noise, hz, depth);
    const bool ok = got == expect;
    std::printf("  [%s] %s: got %d (want %d), %.0f Hz, %.1f dB\n", ok ? "PASS" : "FAIL", name, got, expect, hz, depth);
    if (!ok) failures++;
}

static int at(float hz) {
    int best = 0;
    for (int i = 1; i < kThirdOctaveBands; i++) {
        const float d = thirdOctaveCentreHz(i) / hz, e = thirdOctaveCentreHz(best) / hz;
        if ((d > 1 ? d : 1 / d) < (e > 1 ? e : 1 / e)) best = i;
    }
    return best;
}

int main() {
    float flat[kThirdOctaveBands] = {0};
    float notched[kThirdOctaveBands] = {0};
    notched[at(3150)] = -10.0f;
    float comb[kThirdOctaveBands] = {0};
    comb[at(1600)] = comb[at(5040)] = comb[at(8000)] = -10.0f;
    float loudNoise[kThirdOctaveBands];
    for (float& n : loudNoise) n = -60.0f;
    loudNoise[at(3150)] = -16.0f;   // the notched third sits 6 dB over its noise

    run("1 right tweeter reversed", flat, notched, nullptr, 2);
    run("2 both the same", flat, flat, nullptr, 0);
    run("3 notch on both sides", notched, notched, nullptr, 0);
    run("4 reflection comb on the right", flat, comb, nullptr, 0);
    run("5 the notch under noise", flat, notched, loudNoise, 0);

    std::printf("%s\n", failures ? "CROSSOVER CRITERIA: FAIL" : "CROSSOVER CRITERIA: ALL PASS");
    return failures ? 1 : 0;
}
