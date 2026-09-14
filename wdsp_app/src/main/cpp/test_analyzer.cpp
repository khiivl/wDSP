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

/**
 * A tone, or the sum of two, at amplitude each. In double: a float phase stops repeating exactly
 * after a few seconds, and a tone that is not exactly periodic hides the very failure a test file
 * made of whole-period tones exposes.
 */
std::vector<float> makeSine(int samples, float freqHz, float amplitude = 0.5f,
                            float secondHz = 0.0f) {
    std::vector<float> out(static_cast<size_t>(samples));
    const double w1 = 2.0 * 3.14159265358979323846 * freqHz / kSampleRate;
    const double w2 = 2.0 * 3.14159265358979323846 * secondHz / kSampleRate;
    for (int i = 0; i < samples; i++) {
        double v = std::sin(w1 * i) + (secondHz > 0.0f ? std::sin(w2 * i) : 0.0);
        out[static_cast<size_t>(i)] = static_cast<float>(amplitude * v);
    }
    return out;
}

/**
 * Polls the fake tap the way the app polls Visualizer, and returns how many samples the stitcher
 * took as new - which, for a stream it rebuilt correctly, is the length of the signal.
 *
 * The timing is the head unit's, measured on 14.09.2026 from four capture dumps (923 polls): polls
 * 10-13 ms apart; Visualizer's window moves in whole milliseconds - every advance was a multiple
 * of 48 samples - and a timestamp predicts the advance with an error inside +-131 samples that
 * does not accumulate. So each read advances by a whole number of milliseconds around the poll
 * interval, and the timestamp handed over carries its own scatter on top.
 */
int64_t feed(wdsp::Analyzer& analyzer, std::vector<float> signal, int advancePerRead) {
    const int msSamples = kSampleRate / 1000;
    std::mt19937 rng(77);
    std::uniform_int_distribution<int> pollScatterMs(-1, 1);
    std::uniform_int_distribution<int> clockScatter(-60, 60);
    FakeTap tap(std::move(signal));
    int64_t position = 0;
    int64_t taken = 0;
    while (!tap.exhausted()) {
        int advance = (advancePerRead / msSamples + pollScatterMs(rng)) * msSamples;
        position += advance;
        std::vector<uint8_t> block = tap.read(advance);
        int64_t timeNs = (position + clockScatter(rng)) * 1000000000LL / kSampleRate;
        taken += analyzer.pushWaveform(block.data(), kCaptureSize, timeNs);
        // Analysis now lives on its own thread in the app; drain it synchronously here.
        analyzer.waitAndProcess(0);
    }
    return taken - kCaptureSize;   // the first read is taken whole
}

/** A rebuilt stream as long as the signal fed, within 2 %. */
bool streamIsWhole(int64_t taken, size_t fed, const char* what) {
    double ratio = static_cast<double>(taken) / static_cast<double>(fed);
    bool ok = ratio > 0.98 && ratio < 1.02;
    printf("  %s: stitcher took %lld of %zu samples (%.1f %%) -> %s\n", what,
           static_cast<long long>(taken), fed, ratio * 100.0, ok ? "PASS" : "FAIL");
    return ok;
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

    const size_t fed = static_cast<size_t>(kSampleRate * 12);
    int64_t taken = feed(analyzer, makePinkNoise(kSampleRate * 12), 480); // read every 10 ms, blocks overlap
    bool whole = streamIsWhole(taken, fed, "pink noise");

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

    // The dB fold for comparing two analysers is the same fold, and an offset moves every band by
    // exactly itself - the microphone is drawn on the calculated spectrum's scale through these two.
    float db16[wdsp::kHwBands];
    analyzer.getLevelsDb16(db16);
    float worstFold = 0.0f;
    for (int i = 1; i < wdsp::kHwBands; i++) {
        float viaLevels = l16[i] * range - range + config.refMaxDb;
        if (viaLevels > -range + config.refMaxDb + 0.5f) {   // not clamped at the bottom
            worstFold = std::max(worstFold, std::fabs(viaLevels - db16[i]));
        }
    }
    const float offset = -7.5f;
    analyzer.setLevelOffsetDb(0, offset);
    float shifted32[wdsp::kBands], shifted16[wdsp::kHwBands];
    analyzer.getLevels(0, shifted32, shifted16);
    float worstShift = 0.0f;
    for (int i = 1; i < wdsp::kHwBands; i++) {
        float before = l16[i] * range, after = shifted16[i] * range;
        if (before > 8.0f && after > 0.5f) worstShift = std::max(worstShift, std::fabs(after - before - offset));
    }
    bool foldOk = worstFold < 0.05f && worstShift < 0.05f;
    printf("  dB fold vs levels: worst %.3f dB; offset %.1f dB moves bands by it, worst error %.3f dB -> %s\n",
           worstFold, offset, worstShift, foldOk ? "PASS" : "FAIL");
    return (whole && spread < 6.0f && spread16 < 4.0f && foldOk) ? 0 : 1;
}

int runTone(float freqHz, int expectedBand) {
    wdsp::Analyzer analyzer(kSampleRate, kCaptureSize);
    wdsp::Analyzer::Config config;
    config.attackMs = 5.0f;
    config.releaseMs = 5.0f;
    analyzer.setConfig(config);

    printf("\n%.0f Hz tone:\n", freqHz);
    // A tone is periodic, so a block matches what the stitcher holds at every whole period of
    // shift - including a shift of nothing. On the head unit a 1 kHz + 10 kHz test tone (a period
    // of exactly 48 samples) was taken as "nothing new" on all 203 polls, and the analyser drew
    // nothing while it played (14.09.2026).
    int64_t taken = feed(analyzer, makeSine(kSampleRate * 4, freqHz), 480);
    bool whole = streamIsWhole(taken, static_cast<size_t>(kSampleRate * 4), "stream");

    float db[wdsp::kBands];
    analyzer.getLevelsDb(db);

    int peak = 0;
    for (int i = 1; i < wdsp::kBands; i++) {
        if (db[i] > db[peak]) peak = i;
    }
    bool ok = std::abs(peak - expectedBand) <= 1;
    printf("  peak in band %d (%s Hz), expected around %d  -> %s\n",
           peak, kBandNames[peak], expectedBand, ok ? "PASS" : "FAIL");

    // Leakage into the top of the spectrum is what used to make a bass line light up 20 kHz. A
    // tone in the treble is itself near the top, so there it is judged against the bottom instead.
    int farBand = expectedBand < wdsp::kBands / 2 ? wdsp::kBands - 2 : 8;
    float farDb = db[farBand];
    float leak = db[peak] - farDb;
    printf("  peak %.1f dB, %s Hz band %.1f dB, separation %.1f dB -> %s\n",
           db[peak], kBandNames[farBand], farDb, leak, leak > 30.0f ? "PASS" : "FAIL");
    return (whole && ok && leak > 30.0f) ? 0 : 1;
}

/**
 * tone_1k_10k_48k.wav as it reaches the tap: 1 kHz + 10 kHz, -23 dBFS each (-20 dBFS together).
 * Played on the head unit it gave nothing new on 461 polls of 461, in two players, and nothing on
 * screen (14.09.2026).
 */
int runTestFile() {
    wdsp::Analyzer analyzer(kSampleRate, kCaptureSize);
    wdsp::Analyzer::Config config;
    config.attackMs = 5.0f;
    config.releaseMs = 5.0f;
    analyzer.setConfig(config);

    printf("\ntone_1k_10k_48k.wav (1 kHz + 10 kHz, -23 dBFS each):\n");
    const float amplitude = static_cast<float>(std::pow(10.0, -23.0 / 20.0) * std::sqrt(2.0));
    int64_t taken = feed(analyzer, makeSine(kSampleRate * 4, 1000.0f, amplitude, 10000.0f), 480);
    bool whole = streamIsWhole(taken, static_cast<size_t>(kSampleRate * 4), "stream");

    float db[wdsp::kBands];
    analyzer.getLevelsDb(db);
    // Both tones stand well above the band between them.
    float gap1 = db[18] - db[23], gap10 = db[28] - db[23];
    bool both = gap1 > 30.0f && gap10 > 30.0f;
    printf("  1 kHz band %.1f dB, 10 kHz band %.1f dB, 3150 Hz band %.1f dB -> %s\n",
           db[18], db[28], db[23], both ? "PASS" : "FAIL");
    return (whole && both) ? 0 : 1;
}

} // namespace

int main() {
    int failures = 0;
    failures += runPinkNoise();
    failures += runTone(50.0f, 5);     // standard grid: band 5 is 50 Hz
    failures += runTone(1000.0f, 18);  // band 18 is 1 kHz; a period of exactly 48 samples
    failures += runTone(440.0f, 14);   // a period that is not a whole number of samples
    failures += runTone(10000.0f, 28); // band 28 is 10 kHz; a period of exactly 4.8 samples
    failures += runTestFile();
    printf("\n%s\n", failures == 0 ? "ALL PASS" : "FAILURES PRESENT");
    return failures;
}
