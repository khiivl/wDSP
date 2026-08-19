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
    }
}

const char* kBandNames[wdsp::kBands] = {
        "17.8", "22.4", "28.1", "35.4", "44.5", "56.1", "71.3", "89.8",
        "111", "140", "178", "224", "281", "354", "445", "561",
        "713", "898", "1114", "1403", "1782", "2245", "2806", "3536",
        "4454", "5612", "7127", "8980", "11136", "14031", "17818", "22449"
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
    return spread < 6.0f ? 0 : 1;
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
    printf("  peak %.1f dB, 18 kHz band %.1f dB, separation %.1f dB -> %s\n",
           db[peak], topDb, leak, leak > 30.0f ? "PASS" : "FAIL");
    return (ok && leak > 30.0f) ? 0 : 1;
}

} // namespace

int main() {
    int failures = 0;
    failures += runPinkNoise();
    failures += runTone(50.0f, 4);
    failures += runTone(1000.0f, 17);
    printf("\n%s\n", failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
    return failures;
}
