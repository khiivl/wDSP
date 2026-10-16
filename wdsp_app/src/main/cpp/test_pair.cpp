// Host check of the pair imbalance (TODO 3 step 6, 09.10.2026). Criteria written before the detector,
// a fault being 6 dB or more beyond the distances:
//   1. the owner's 20.09 front left / front right                -> silent (he hears no difference);
//   2. B 8 dB quieter across the midrange, equal distances        -> B;
//   3. the same 8 dB, B far enough for the distance to explain it -> silent;
//   4. 30 dB apart in the treble only (the 20.09 angle)            -> silent;
//   5. the midrange under noise                                    -> no answer (NaN).
//
//   g++ -O2 -std=c++17 -o /tmp/pair test_pair.cpp sweep.cpp analyzer.cpp fft.cpp stitcher.cpp
#include <cmath>
#include <cstdio>
#include "sweep.h"

using namespace wdsp;

static int failures = 0;
constexpr float kFaultDb = 6.0f;

static void check(const char* name, float excess, int want) {   // want: 0 silent, 1 A quieter, 2 B quieter, -1 NaN
    int got = std::isnan(excess) ? -1 : excess <= -kFaultDb ? 1 : excess >= kFaultDb ? 2 : 0;
    const bool ok = got == want;
    std::printf("  [%s] %s: excess %+.1f dB -> %d (want %d)\n", ok ? "PASS" : "FAIL", name, excess, got, want);
    if (!ok) failures++;
}

int main() {
    // 20.09 (meas_20_09/room_measurement.txt), the same numbers test_miccal replays.
    const float fl[16] = {-57.0f,-45.5f,-36.9f,-27.4f,-25.5f,-11.3f,-6.9f,-7.8f,-9.0f,-8.9f,-14.3f,-16.8f,-15.0f,-21.4f,-36.7f,-45.9f};
    const float flSnr[16] = {16.9f,32.3f,34.7f,34.2f,33.9f,45.5f,40.4f,39.8f,24.4f,25.5f,26.9f,35.5f,34.0f,41.5f,26.6f,18.2f};
    const float fr[16] = {-46.2f,-36.6f,-24.2f,-15.7f,-18.5f,-7.0f,-2.0f,-6.9f,-3.4f,-4.3f,-5.2f,-5.1f,-5.2f,-5.8f,-5.5f,-5.5f};
    const float frSnr[16] = {36.4f,42.3f,52.1f,49.6f,46.4f,51.9f,27.6f,20.4f,16.6f,17.5f,18.2f,18.4f,18.3f,17.8f,18.1f,18.4f};
    check("1 20.09 front left / front right", SweepMeasurement::pairImbalanceDb(fl, flSnr, fr, frSnr, 0.0f), 0);

    float flat[16] = {0}, quiet[16], snr[16], treble[16], deaf[16];
    for (int b = 0; b < 16; b++) {
        quiet[b] = -8.0f;
        snr[b] = 30.0f;
        treble[b] = b >= 12 ? -30.0f : 0.0f;
        deaf[b] = 6.0f;
    }
    check("2 B 8 dB quieter, equal distances", SweepMeasurement::pairImbalanceDb(flat, snr, quiet, snr, 0.0f), 2);
    check("3 the same, the distance explains it", SweepMeasurement::pairImbalanceDb(flat, snr, quiet, snr, 8.0f), 0);
    check("4 the treble only", SweepMeasurement::pairImbalanceDb(flat, snr, treble, snr, 0.0f), 0);
    check("5 the midrange under noise", SweepMeasurement::pairImbalanceDb(flat, deaf, quiet, deaf, 0.0f), -1);

    std::printf("%s\n", failures ? "PAIR CRITERIA: FAIL" : "PAIR CRITERIA: ALL PASS");
    return failures ? 1 : 0;
}
