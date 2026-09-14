// Host-side check of the analysis maths. Not built into the app; compile and run it on a
// desktop when changing the band plan, the transforms or the stitcher:
//
//   g++ -O2 -std=c++17 -o /tmp/wdsp_test test_analyzer.cpp fft.cpp stitcher.cpp analyzer.cpp
//   /tmp/wdsp_test
//
// It answers the questions the head unit cannot: does pink noise really read flat with an empty
// correction table, does a tone land in the band it belongs to, and does the stitcher rebuild a
// continuous stream out of overlapping reads.

#include <cmath>
#include <cstdio>
#include <cstdint>
#include <random>
#include <vector>

#include "analyzer.h"

namespace {

constexpr int kSampleRate = 48000;
constexpr int kCaptureSize = 1024;

/** Mimics Visualizer: repeated reads of a rolling buffer, so consecutive reads overlap. */
class FakeTap {
public:
    explicit FakeTap(std::vector<float> source) : source_(std::move(source)), readPos_(0) {}

    /** Advances by advance samples, then hands back the newest captureSize as unsigned 8-bit. */
    std::vector<uint8_t> read(int advance) {
        readPos_ += advance;
        std::vector<uint8_t> out(kCaptureSize);
        for (int i = 0; i < kCaptureSize; i++) {
            long idx = readPos_ - kCaptureSize + i;
            float v = (idx >= 0 && idx < static_cast<long>(source_.size()))
                      ? source_[static_cast<size_t>(idx)] : 0.0f;
            int s = static_cast<int>(std::lround(v * 127.0f)) + 128;
            if (s < 0) s = 0;
            if (s > 255) s = 255;
            out[static_cast<size_t>(i)] = static_cast<uint8_t>(s);
        }
        return out;
    }

    bool exhausted() const { return readPos_ >= static_cast<long>(source_.size()); }

private:
    std::vector<float> source_;
    long readPos_;
};

std::vector<float> makePinkNoise(int samples) {
    std::mt19937 rng(1234);
    std::uniform_real_distribution<float> dist(-1.0f, 1.0f);
    double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
    std::vector<float> out(static_cast<size_t>(samples));
    float peak = 0.0f;
    for (int i = 0; i < samples; i++) {
        double w = dist(rng);
        b0 = 0.99886 * b0 + w * 0.0555179;
        b1 = 0.99332 * b1 + w * 0.0750759;
        b2 = 0.96900 * b2 + w * 0.1538520;
        b3 = 0.86650 * b3 + w * 0.3104856;
        b4 = 0.55000 * b4 + w * 0.5329522;
        b5 = -0.7616 * b5 - w * 0.0168980;
        double pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362;
        b6 = w * 0.115926;
        out[static_cast<size_t>(i)] = static_cast<float>(pink);
        peak = std::max(peak, std::fabs(out[static_cast<size_t>(i)]));
    }
    float gain = peak > 0 ? 0.5f / peak : 1.0f;
    for (auto& v : out) v *= gain;
    return out;
}

std::vector<float> makeSine(int samples, float freqHz) {
    std::vector<float> out(static_cast<size_t>(samples));
    for (int i = 0; i < samples; i++) {
        out[static_cast<size_t>(i)] = 0.5f * std::sin(2.0f * 3.14159265358979f * freqHz
                                                      * static_cast<float>(i) / kSampleRate);
    }
    return out;
}

void feed(wdsp::Analyzer& analyzer, std::vector<float> signal, int advancePerRead) {
    FakeTap tap(std::move(signal));
    while (!tap.exhausted()) {
        std::vector<uint8_t> block = tap.read(advancePerRead);
        analyzer.pushWaveform(block.data(), kCaptureSize);
        // Analysis now lives on its own thread in the app; drain it synchronously here.
        analyzer.waitAndProcess(0);
    }
}

// The standard third-octave grid, 1000 * 2^((i - 18) / 3), by nominal name.
const char* kBandNames[wdsp::kBands] = {
        "16", "20", "25", "31.5", "40", "50", "63", "80",
        "100", "125", "160", "200", "250", "315", "400", "500",
        "630", "800", "1000", "1250", "1600", "2000", "2500", "3150",
        "4000", "5000", "6300", "8000", "10000", "12500", "16000", "20000"
};

int runPinkNoise() {
    wdsp::Analyzer analyzer(kSampleRate, kCaptureSize);
    wdsp::Analyzer::Config config;
    // Long time constants on purpose: a single frame of noise in a five-bin band has several dB
    // of scatter, so without averaging this measures the realisation, not the response.
    config.attackMs = 1500.0f;
    config.releaseMs = 1500.0f;
    analyzer.setConfig(config);

    feed(analyzer, makePinkNoise(kSampleRate * 12), 480); // read every 10 ms, blocks overlap

    float db[wdsp::kBands];
    analyzer.getLevelsDb(db);

    printf("\nPink noise, third-octave bands (should be flat):\n");
    float minDb = 1e9f, maxDb = -1e9f;
    for (int i = 0; i < wdsp::kBands; i++) {
        printf("  %6s Hz  %7.1f dB\n", kBandNames[i], db[i]);
        // The lowest band sits at the edge of what a 170 ms window can resolve, and the top one
        // is clipped by Nyquist, so judge flatness on the rest.
        if (i >= 2 && i < wdsp::kBands - 1) {
            minDb = std::min(minDb, db[i]);
            maxDb = std::max(maxDb, db[i]);
        }
    }
    float spread = maxDb - minDb;
    printf("  spread across bands 2..30: %.1f dB  -> %s\n", spread,
           spread < 6.0f ? "PASS" : "FAIL");
    printf("  discontinuities: %d, frames: %d\n",
           analyzer.discontinuities(), analyzer.framesProduced());

    // The fold onto the 16 equaliser bands, each centred on its slider's frequency - including the
    // 20 kHz band, whose upper neighbour does not exist on this grid.
    float l32[wdsp::kBands], l16[wdsp::kHwBands];
    wdsp::Analyzer::AgcConfig noAgc;
    noAgc.enabled = false;              // absolute levels, or every band reads as full scale
    analyzer.setAgcConfig(0, noAgc);
    analyzer.getLevels(0, l32, l16);
    const float range = config.rangeDb;
    const char* hwNames[wdsp::kHwBands] = {"20", "31.5", "50", "80", "125", "200", "315", "500",
                                           "800", "1250", "2000", "3150", "5000", "8000", "12500", "20000"};
    printf("\nSame noise folded onto the 16 equaliser bands (should be flat, 20 kHz included):\n");
    float min16 = 1e9f, max16 = -1e9f;
    for (int i = 0; i < wdsp::kHwBands; i++) {
        float d = l16[i] * range - range + config.refMaxDb;
        printf("  %6s Hz  %7.1f dB\n", hwNames[i], d);
        if (i >= 1) {   // the 20 Hz band sits at the resolution limit of the long window
            min16 = std::min(min16, d);
            max16 = std::max(max16, d);
        }
    }
    float spread16 = max16 - min16;
    printf("  spread across 31.5 Hz..20 kHz: %.1f dB  -> %s\n", spread16, spread16 < 4.0f ? "PASS" : "FAIL");
    return (spread < 6.0f && spread16 < 4.0f) ? 0 : 1;
}

int runTone(float freqHz, int expectedBand) {
    wdsp::Analyzer analyzer(kSampleRate, kCaptureSize);
    wdsp::Analyzer::Config config;
    config.attackMs = 5.0f;
    config.releaseMs = 5.0f;
    analyzer.setConfig(config);

    feed(analyzer, makeSine(kSampleRate * 4, freqHz), 480);

    float db[wdsp::kBands];
    analyzer.getLevelsDb(db);

    int peak = 0;
    for (int i = 1; i < wdsp::kBands; i++) {
        if (db[i] > db[peak]) peak = i;
    }
    bool ok = std::abs(peak - expectedBand) <= 1;
    printf("\n%.0f Hz tone -> peak in band %d (%s Hz), expected around %d  -> %s\n",
           freqHz, peak, kBandNames[peak], expectedBand, ok ? "PASS" : "FAIL");

    // Leakage into the top of the spectrum is what used to make a bass line light up 20 kHz.
    float topDb = db[wdsp::kBands - 2];
    float leak = db[peak] - topDb;
    printf("  peak %.1f dB, 16 kHz band %.1f dB, separation %.1f dB -> %s\n",
           db[peak], topDb, leak, leak > 30.0f ? "PASS" : "FAIL");
    return (ok && leak > 30.0f) ? 0 : 1;
}

} // namespace

int main() {
    int failures = 0;
    failures += runPinkNoise();
    failures += runTone(50.0f, 5);     // standard grid: band 5 is 50 Hz
    failures += runTone(1000.0f, 18);  // band 18 is 1 kHz
    printf("\n%s\n", failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
    return failures;
}
