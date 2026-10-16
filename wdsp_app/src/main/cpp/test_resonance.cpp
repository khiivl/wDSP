// Host check of the low resonances (TODO 3 step 7, 08.10.2026) on synthetic thirds, three channels.
// Criteria written before the detector:
//   1. +8 dB at 79 Hz in every channel            -> one cabin mode there;
//   2. +8 dB at 125 Hz in front left only          -> front left's own resonance;
//   3. nothing                                     -> nothing;
//   4. +8 dB in front left, +3 dB in front right   -> nothing (neither all nor one alone);
//   5. +8 dB at 315 Hz in every channel            -> nothing (above the range);
//   6. case 1 under noise (SNR 6 dB everywhere)    -> nothing;
//   7. a mode at 40 Hz heard by two channels only (the third's door high-pass: under noise there)
//                                                  -> a mode all the same;
//   8. a peak at 40 Hz, only one channel heard there -> nothing (a mode or its own, cannot tell).
//
//   g++ -O2 -std=c++17 -o /tmp/reson test_resonance.cpp sweep.cpp analyzer.cpp fft.cpp stitcher.cpp
#include <cstdio>
#include <cmath>
#include "bands.h"
#include "sweep.h"

using namespace wdsp;

constexpr int kCh = 3;
static int failures = 0;

static int at(float hz) {
    int best = 0;
    for (int i = 1; i < kThirdOctaveBands; i++) {
        if (std::fabs(std::log(thirdOctaveCentreHz(i) / hz)) < std::fabs(std::log(thirdOctaveCentreHz(best) / hz))) best = i;
    }
    return best;
}

static void run(const char* name, const float (&x)[kCh][kThirdOctaveBands], float noiseDb,
                int wantCount, int wantBand, int wantWho, int deafChannel = -1, int deafFrom = 0) {
    float noise[kCh][kThirdOctaveBands];
    for (auto& ch : noise) for (float& v : ch) v = noiseDb;
    // A channel that does not play the bottom: its thirds up to deafFrom sit on its noise.
    if (deafChannel >= 0) for (int i = 0; i <= deafFrom; i++) noise[deafChannel][i] = x[deafChannel][i] - 3.0f;
    int band[4], who[4];
    float rise[4];
    const int n = SweepMeasurement::lowResonances(&x[0][0], &noise[0][0], kCh, 4, band, who, rise);
    const bool ok = n == wantCount && (n == 0 || (band[0] == wantBand && who[0] == wantWho));
    std::printf("  [%s] %s: %d found", ok ? "PASS" : "FAIL", name, n);
    for (int k = 0; k < n; k++) std::printf("  %.0f Hz who %d +%.1f dB", thirdOctaveCentreHz(band[k]), who[k], rise[k]);
    std::printf("\n");
    if (!ok) failures++;
}

int main() {
    float a[kCh][kThirdOctaveBands] = {};
    for (auto& ch : a) ch[at(79)] = 8.0f;
    run("1 mode at 79 Hz in all", a, -60.0f, 1, at(79), -1);

    float b[kCh][kThirdOctaveBands] = {};
    b[0][at(125)] = 8.0f;
    run("2 front left alone at 125 Hz", b, -60.0f, 1, at(125), 0);

    float c[kCh][kThirdOctaveBands] = {};
    run("3 nothing", c, -60.0f, 0, 0, 0);

    float d[kCh][kThirdOctaveBands] = {};
    d[0][at(79)] = 8.0f;
    d[1][at(79)] = 3.0f;
    run("4 one strong, one half-way", d, -60.0f, 0, 0, 0);

    float e[kCh][kThirdOctaveBands] = {};
    for (auto& ch : e) ch[at(315)] = 8.0f;
    run("5 above the range at 315 Hz", e, -60.0f, 0, 0, 0);

    run("6 case 1 under noise", a, 2.0f, 0, 0, 0);   // peak 8 dB, noise 2 dB -> SNR 6

    float g[kCh][kThirdOctaveBands] = {};
    g[0][at(40)] = g[1][at(40)] = 8.0f;
    run("7 mode at 40 Hz, third channel deaf there", g, -60.0f, 1, at(40), -1, 2, at(63));

    float h[kCh][kThirdOctaveBands] = {};
    h[0][at(40)] = 8.0f;
    {
        // channels 1 and 2 deaf below 63 Hz: only channel 0 is heard at 40 Hz
        float noise[kCh][kThirdOctaveBands];
        for (auto& ch : noise) for (float& v : ch) v = -60.0f;
        for (int c = 1; c < kCh; c++) for (int i = 0; i <= at(63); i++) noise[c][i] = h[c][i] - 3.0f;
        int band[4], who[4];
        float rise[4];
        const int n = SweepMeasurement::lowResonances(&h[0][0], &noise[0][0], kCh, 4, band, who, rise);
        std::printf("  [%s] 8 one channel heard at 40 Hz: %d found\n", n == 0 ? "PASS" : "FAIL", n);
        if (n != 0) failures++;
    }

    std::printf("%s\n", failures ? "RESONANCE CRITERIA: FAIL" : "RESONANCE CRITERIA: ALL PASS");
    return failures ? 1 : 0;
}
