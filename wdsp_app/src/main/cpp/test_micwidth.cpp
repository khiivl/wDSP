// Host check of the microphone estimate's width rule (08.10.2026): between the cabin's modal limit
// and 10 kHz a deviation common to every channel is charged to the capsule only when it is NARROW
// and at least one equaliser step deep. Criteria written before the change:
//   A. a narrow common dip of -4 dB at 800 Hz          -> a correction of at least +2.5 dB there;
//   B. a broad common dip of -3 dB at 500, 800, 1250 Hz -> nothing anywhere (that is the cabin);
//   C. a narrow common dip of -4 dB at 200 Hz          -> nothing (below the modal limit: a mode);
//   D. the channels disagree (one dips, one does not)  -> nothing.
//
//   g++ -O2 -std=c++17 -o /tmp/micwidth test_micwidth.cpp sweep.cpp analyzer.cpp fft.cpp stitcher.cpp
#include <cmath>
#include <cstdio>
#include "sweep.h"

using namespace wdsp;

static int failures = 0;

static void run(const char* name, const float* best, const float* worst, int band, bool expectCorrection) {
    float mean[16], snr[16], mounting[16] = {0}, comp[16];
    int status[16];
    for (int b = 0; b < 16; b++) {
        mean[b] = 0.5f * (best[b] + worst[b]);
        snr[b] = 40.0f;   // full confidence: the rule is under test, not the trust ramp
    }
    SweepMeasurement::estimateMicCompensation(best, worst, mean, snr, mounting, nullptr, comp, status);
    bool ok = true;
    for (int b = 5; b < 16; b++) {
        const bool wanted = expectCorrection && b == band;
        if (wanted && comp[b] < 2.5f) ok = false;
        if (!wanted && std::fabs(comp[b]) > 0.05f) ok = false;
    }
    std::printf("  [%s] %s:", ok ? "PASS" : "FAIL", name);
    for (int b = 5; b < 16; b++) std::printf(" %+.1f", comp[b]);
    std::printf("\n");
    if (!ok) failures++;
}

int main() {
    float flat[16] = {0};
    float a[16] = {0};
    a[8] = -4.0f;                               // 800 Hz
    run("A narrow dip at 800 Hz", a, a, 8, true);

    float b[16] = {0};
    b[7] = b[8] = b[9] = -3.0f;                 // 500, 800, 1250 Hz
    run("B broad dip 500-1250 Hz", b, b, -1, false);

    float c[16] = {0};
    c[5] = -4.0f;                               // 200 Hz
    run("C narrow dip at 200 Hz", c, c, -1, false);

    run("D channels disagree at 800 Hz", flat, a, -1, false);

    std::printf("%s\n", failures ? "WIDTH CRITERIA: FAIL" : "WIDTH CRITERIA: ALL PASS");
    return failures ? 1 : 0;
}
