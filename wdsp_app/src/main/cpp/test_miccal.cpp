// Host check of the microphone estimate and of the preset it produces, fed with the owner's own
// 20.09 numbers (meas_20_09/room_measurement.txt). Two runs: the curve the unit had that day, and
// the curve today's code would build - so the difference is the change, not the harness.
#include <cstdio>
#include <cmath>
#include <algorithm>
#include "sweep.h"

using namespace wdsp;

struct Ch { const char* name; float clean[16]; float snr[16]; };

// Only the three channels the report calls "heard directly"; rear was not connected on the bench.
static Ch kChannels[] = {
    {"front left", {-57.0f,-45.5f,-36.9f,-27.4f,-25.5f,-11.3f,-6.9f,-7.8f,-9.0f,-8.9f,-14.3f,-16.8f,-15.0f,-21.4f,-36.7f,-45.9f},
                   {16.9f,32.3f,34.7f,34.2f,33.9f,45.5f,40.4f,39.8f,24.4f,25.5f,26.9f,35.5f,34.0f,41.5f,26.6f,18.2f}},
    {"front right",{-46.2f,-36.6f,-24.2f,-15.7f,-18.5f,-7.0f,-2.0f,-6.9f,-3.4f,-4.3f,-5.2f,-5.1f,-5.2f,-5.8f,-5.5f,-5.5f},
                   {36.4f,42.3f,52.1f,49.6f,46.4f,51.9f,27.6f,20.4f,16.6f,17.5f,18.2f,18.4f,18.3f,17.8f,18.1f,18.4f}},
    {"subwoofer",  {-52.4f,-38.0f,-30.5f,-20.9f,-17.4f,-7.5f,-0.9f,-4.4f,-5.0f,-3.9f,-4.4f,-4.8f,-4.9f,-5.6f,-5.5f,-5.5f},
                   {7.7f,21.1f,25.9f,13.9f,12.0f,10.6f,12.6f,13.4f,16.2f,23.7f,19.6f,18.8f,18.7f,17.9f,18.1f,18.4f}},
};

// What the unit stored that day, and what the report printed as the finished preset.
static const float kOldCurve[16] =
    {52.81f,40.45f,24.14f,13.61f,4.99f,0,0,0,0,0,-1.50f,-5.00f,-1.00f,3.50f,7.00f,10.00f};
static const int kOldGains[16] = {6,6,6,6,8,7,5,7,6,6,7,8,7,5,4,4};
// avgClean as the synthesis saw it: the report's cabin response minus the curve above.
static const float kAvgClean[16] =
    {-46.2f,-31.8f,-24.2f,-14.6f,-14.5f,-2.4f,2.5f,-1.0f,0.9f,0.2f,-1.4f,-1.6f,-1.5f,-2.4f,-2.3f,-2.3f};
static const float kAvgSnr[16] =
    {26.6f,37.3f,43.4f,41.9f,40.2f,48.7f,34.0f,30.1f,20.5f,21.5f,22.5f,26.9f,26.2f,29.6f,22.4f,18.3f};
// The mounting the owner stated: room_mic_body = 1, pinhole. Lives in MicProfile.java now.
static const float kPinhole[16] = {0,0,0,2.0f,0,0,0,0,0,0,-1.5f,-5.0f,-1.0f,3.5f,7.0f,10.0f};

static void printRow(const char* label, const float* v) {
    printf("%-22s", label);
    for (int b = 0; b < 16; b++) printf(" %+6.1f", v[b]);
    printf("\n");
}

static void printGains(const char* label, const int* g) {
    printf("%-22s", label);
    for (int b = 0; b < 16; b++) printf(" %+6d", (g[b] - 6) * 2);
    printf("   (dB)\n");
}

int main() {
    float best[16], worst[16], mean[16], snr[16];
    for (int b = 0; b < 16; b++) { best[b] = -1e9f; worst[b] = 1e9f; mean[b] = 0.0f; snr[b] = 0.0f; }
    for (const Ch& c : kChannels) {
        float mid = 0.0f;
        for (int b = 5; b <= 8; b++) mid += c.clean[b];
        mid /= 4.0f;
        for (int b = 0; b < 16; b++) {
            const float shape = c.clean[b] - mid;
            if (shape > best[b]) { best[b] = shape; snr[b] = c.snr[b]; }
            if (shape < worst[b]) worst[b] = shape;
            mean[b] += shape / 3.0f;
        }
    }
    printRow("best channel", best);
    printRow("worst channel", worst);
    printRow("dB mean", mean);

    float comp[16];
    int status[16];
    SweepMeasurement::estimateMicCompensation(best, worst, mean, snr, kPinhole, comp, status);
    printf("\n");
    printRow("mic curve 20.09", kOldCurve);
    printRow("mic curve now", comp);
    printf("%-22s", "status");
    for (int b = 0; b < 16; b++) printf(" %6s", status[b] == 1 ? "UNKN" : status[b] == 2 ? "trim" : "-");
    printf("\n\n");

    // The preset each curve produces. HPF 100 Hz is index 7, which is what the report says.
    int gains[16], subLpf = 0, subGain = 0;
    SweepMeasurement::synthesizeAutoEq16(kAvgClean, kOldCurve, kAvgSnr, 7, true,
                                         SweepMeasurement::TARGET_HARMAN, gains, subLpf, subGain);
    printGains("preset, old curve", gains);
    printGains("preset, as reported", kOldGains);
    printf("%-22s sub gain %+d dB\n", "", subGain);

    SweepMeasurement::synthesizeAutoEq16(kAvgClean, comp, kAvgSnr, 7, true,
                                         SweepMeasurement::TARGET_HARMAN, gains, subLpf, subGain);
    printf("\n");
    printGains("preset, curve now", gains);
    printf("%-22s sub gain %+d dB (a constant per target curve, not measured)\n", "", subGain);
    return 0;
}
