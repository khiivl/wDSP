// Host check of the zigzag limit on synthesised presets (TODO "межа на зигзаг", 08.10.2026).
// Criteria written before the change (gain in dB, 2 dB a step):
//   1. the owner's 20.09 preset, +4 +2 -2 +2 at 125..500 Hz -> 315 Hz becomes 0, nothing else moves
//      (checked by test_miccal against HEAD);
//   2. a single honest cut 0 -6 0                         -> unchanged;
//   3. +2 -2 +2 -2 +2                                      -> no back-and-forth of 4 dB is left.
//
//   g++ -O2 -std=c++17 -o /tmp/zigzag test_zigzag.cpp sweep.cpp analyzer.cpp fft.cpp stitcher.cpp
#include <cstdio>
#include <cstdlib>
#include "sweep.h"

using namespace wdsp;

static int failures = 0;

static void fromDb(const int (&db)[kHwBands], int* idx) {
    for (int b = 0; b < kHwBands; b++) idx[b] = db[b] / 2 + 6;
}

static void print(const char* label, const int* idx) {
    std::printf("    %-8s", label);
    for (int b = 0; b < kHwBands; b++) std::printf(" %+3d", (idx[b] - 6) * 2);
    std::printf("\n");
}

static bool hasBackAndForth(const int* idx) {
    for (int b = 1; b + 1 < kHwBands; b++) {
        const int g = idx[b] - 6, l = idx[b - 1] - 6, r = idx[b + 1] - 6;
        if (g != 0 && l != 0 && r != 0 && (l > 0) == (r > 0) && (g > 0) != (l > 0)
                && std::abs(g - l) >= 2 && std::abs(g - r) >= 2) return true;
    }
    return false;
}

int main() {
    const int cut[kHwBands] = {0, 0, 0, 0, 0, 0, -6, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    int a[kHwBands], before[kHwBands];
    fromDb(cut, a);
    for (int b = 0; b < kHwBands; b++) before[b] = a[b];
    SweepMeasurement::limitZigzag16(a);
    bool same = true;
    for (int b = 0; b < kHwBands; b++) same = same && a[b] == before[b];
    print("cut in", before);
    print("cut out", a);
    std::printf("  [%s] 2 a single cut stays\n", same ? "PASS" : "FAIL");
    if (!same) failures++;

    const int zz[kHwBands] = {0, 0, 0, 0, 0, 2, -2, 2, -2, 2, 0, 0, 0, 0, 0, 0};
    int z[kHwBands];
    fromDb(zz, z);
    print("zig in", z);
    SweepMeasurement::limitZigzag16(z);
    print("zig out", z);
    const bool clean = !hasBackAndForth(z);
    std::printf("  [%s] 3 no back-and-forth of 4 dB left\n", clean ? "PASS" : "FAIL");
    if (!clean) failures++;

    std::printf("%s\n", failures ? "ZIGZAG CRITERIA: FAIL" : "ZIGZAG CRITERIA: ALL PASS");
    return failures ? 1 : 0;
}
