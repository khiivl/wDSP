#include "analyzer.h"

#include <algorithm>
#include <cmath>
#include <chrono>
#include <cstring>

namespace wdsp {

namespace {

constexpr int kLongFft = 8192;
constexpr int kShortFft = 1024;
/** Below this, resolution matters more than speed; above it, the reverse. */
constexpr float kCrossoverHz = 800.0f;
constexpr int kFrameRingSize = 192;
constexpr float kPowerFloor = 1e-12f;
/** A frame this far below the loudest recent one is treated as silence. */
constexpr float kQuietFraction = 0.02f;
constexpr float kFrameMaxDecay = 0.999f;
constexpr float kNoiseFloorRise = 0.0002f;
constexpr float kNoiseFloorMargin = 1.2f;

/** Centre frequency of each hardware equaliser band. */
const float kHwCenters[kHwBands] = {
        20.0f, 31.5f, 50.0f, 80.0f, 125.0f, 200.0f, 315.0f, 500.0f,
        800.0f, 1250.0f, 2000.0f, 3150.0f, 5000.0f, 8000.0f, 12500.0f, 20000.0f
};

inline float toDb(float power) {
    return 10.0f * std::log10(power + kPowerFloor);
}

} // namespace

Analyzer::Analyzer(int sampleRate, int captureSize)
        : sampleRate_(sampleRate > 0 ? sampleRate : 48000),
          hop_(512),
          stitcher_(1 << 17),
          longFft_(kLongFft),
          shortFft_(kShortFft),
          longInput_(kLongFft),
          shortInput_(kShortFft),
          longPower_(kLongFft / 2 + 1),
          shortPower_(kShortFft / 2 + 1),
          frameWrite_(0),
          frameCount_(0),
          lastProcessedSample_(0),
          longFftDueAt_(0),
          frameMaxPower_(0.0f),
          running_(true) {
    (void) captureSize;
    for (int i = 0; i < kBands; i++) {
        bandPower_[i] = 0.0f;
        smoothedDb_[i] = -120.0f;
        noiseFloor_[i] = 0.0f;
        dspCurve_[i] = 0.0f;
    }
    frameRing_.resize(kFrameRingSize);
    for (auto& frame : frameRing_) frame.assign(kBands, -120.0f);
    buildBandPlan();
}

void Analyzer::buildBandPlan() {
    // Each hardware band is two-thirds of an octave wide, so splitting it in two gives third-octave
    // analysis bands centred a sixth of an octave either side of the hardware centre.
    // A hardware band spans HW/2^(1/3) .. HW*2^(1/3). Its two halves are therefore centred at
    // HW/2^(1/6) and HW*2^(1/6), each a third of an octave wide - the same 2^(1/6) factor sets
    // both the offset and the half-width, so the halves meet exactly at HW and tile the band with
    // no overlap and no gap.
    const float sixth = std::pow(2.0f, 1.0f / 6.0f);
    const float nyquist = static_cast<float>(sampleRate_) * 0.5f;

    for (int i = 0; i < kBands; i++) {
        int hw = i / 2;
        float center = kHwCenters[hw] * ((i % 2 == 0) ? (1.0f / sixth) : sixth);
        // A third-octave band spans a sixth of an octave either side of its centre.
        float low = center / sixth;
        float high = center * sixth;
        if (high > nyquist) high = nyquist;
        if (low >= high) low = high * 0.99f;

        BandPlan& p = plan_[i];
        p.lowHz = low;
        p.highHz = high;
        p.useLongFft = center < kCrossoverHz;

        int fftSize = p.useLongFft ? kLongFft : kShortFft;
        float binWidth = static_cast<float>(sampleRate_) / static_cast<float>(fftSize);
        p.lowBin = std::max(1, static_cast<int>(std::ceil(low / binWidth)));
        p.highBin = std::min(fftSize / 2, static_cast<int>(std::floor(high / binWidth)));
        p.expectedBins = (high - low) / binWidth;
        if (p.expectedBins < 0.01f) p.expectedBins = 0.01f;
    }
}

void Analyzer::setConfig(const Config& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    config_ = config;
}

void Analyzer::setAgcConfig(int consumer, const AgcConfig& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (consumer < 0 || consumer > 1) return;
    agc_[consumer].config = config;
}

void Analyzer::setDspCurve(const float* curve16) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (curve16 == nullptr) return;
    for (int i = 0; i < kBands; i++) {
        dspCurve_[i] = curve16[i / 2];
    }
}

int Analyzer::pushWaveform(const uint8_t* block, int len) {
    int fresh;
    {
        std::lock_guard<std::mutex> lock(ringMutex_);
        fresh = stitcher_.push(block, len);
    }
    ringSignal_.notify_one();
    return fresh;
}

void Analyzer::stop() {
    {
        std::lock_guard<std::mutex> lock(ringMutex_);
        running_ = false;
    }
    ringSignal_.notify_all();
}

void Analyzer::setHop(int hop) {
    if (hop < 128) hop = 128;
    std::lock_guard<std::mutex> lock(ringMutex_);
    hop_ = hop;
}

void Analyzer::waitAndProcess(int timeoutMs) {
    for (;;) {
        bool haveLong = false;
        {
            std::unique_lock<std::mutex> lock(ringMutex_);
            if (!running_) return;
            if (stitcher_.totalSamples() - lastProcessedSample_ < hop_) {
                ringSignal_.wait_for(lock, std::chrono::milliseconds(timeoutMs));
                if (!running_) return;
                if (stitcher_.totalSamples() - lastProcessedSample_ < hop_) return;
            }
            lastProcessedSample_ += hop_;

            // Copy the windows out under the lock, then let capture carry on while the transforms
            // run. This is the whole point of the split.
            if (!stitcher_.readNewest(shortInput_.data(), kShortFft)) return;
            if (stitcher_.totalSamples() >= longFftDueAt_) {
                haveLong = stitcher_.readNewest(longInput_.data(), kLongFft);
                longFftDueAt_ = stitcher_.totalSamples() + hop_ * 4;
            }
        }
        processFrame(haveLong);
    }
}

void Analyzer::pushPcm16(const int16_t* samples, int count, int channels) {
    if (samples == nullptr || count <= 0) return;
    std::lock_guard<std::mutex> lock(ringMutex_);

    if (channels < 1) channels = 1;
    int frames = count / channels;
    std::vector<float> mono(static_cast<size_t>(frames));
    for (int i = 0; i < frames; i++) {
        float sum = 0.0f;
        for (int c = 0; c < channels; c++) {
            sum += static_cast<float>(samples[i * channels + c]);
        }
        mono[static_cast<size_t>(i)] = sum / (channels * 32768.0f);
    }

    // Straight into the ring: a microphone stream is already continuous, so unlike the polled
    // Visualizer blocks there is no overlap to find and nothing to align.
    stitcher_.appendContinuous(mono.data(), frames);
    ringSignal_.notify_one();
}

void Analyzer::accumulate(const float* power, int binCount, float binWidth, bool longFft) {
    for (int i = 0; i < kBands; i++) {
        BandPlan& p = plan_[i];
        if (p.useLongFft != longFft) continue;

        double sum = 0.0;
        int count = 0;
        for (int bin = p.lowBin; bin <= p.highBin && bin < binCount; bin++) {
            sum += power[bin];
            count++;
        }

        double density;
        if (count > 0) {
            density = sum / count;
        } else {
            // Band narrower than one bin: take the nearest bin as the local density rather than
            // reporting nothing. Scaling by the band's own width below keeps it energy-consistent.
            int bin = std::min(binCount - 1, std::max(1, p.lowBin));
            density = power[bin];
        }
        (void) binWidth;

        bandPower_[i] = static_cast<float>(density * p.expectedBins);
    }
}

void Analyzer::processFrame(bool haveLong) {
    // No lock held here: the windows were copied out already, and everything touched below is
    // either private to this thread or published at the end under mutex_.
    shortFft_.powerSpectrum(shortInput_.data(), shortPower_.data());
    accumulate(shortPower_.data(), kShortFft / 2 + 1,
               static_cast<float>(sampleRate_) / kShortFft, false);

    // The long transform is the most expensive thing here, and it covers the part of the spectrum
    // that physically cannot change quickly, so it runs at a quarter of the frame rate and its
    // values are held in between.
    if (haveLong) {
        longFft_.powerSpectrum(longInput_.data(), longPower_.data());
        accumulate(longPower_.data(), kLongFft / 2 + 1,
                   static_cast<float>(sampleRate_) / kLongFft, true);
    }

    // Noise floor, learned only while nothing is playing. Learning it continuously would eat
    // stationary signals - pink noise never varies, so a floor chasing the running minimum
    // settles onto the signal itself, and does so faster in the wide high bands than in the
    // narrow low ones, inventing a convincing high-frequency roll-off out of nothing.
    float frameTotal = 0.0f;
    for (int i = 0; i < kBands; i++) frameTotal += bandPower_[i];
    if (frameTotal > frameMaxPower_) frameMaxPower_ = frameTotal;
    else frameMaxPower_ *= kFrameMaxDecay;
    bool quiet = frameMaxPower_ > 0.0f && frameTotal < frameMaxPower_ * kQuietFraction;

    Config config;
    float curve[kBands];
    int hop;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        config = config_;
        std::copy(dspCurve_, dspCurve_ + kBands, curve);
    }
    {
        std::lock_guard<std::mutex> lock(ringMutex_);
        hop = hop_;
    }

    float framePeriodMs = 1000.0f * static_cast<float>(hop) / static_cast<float>(sampleRate_);
    float attack = 1.0f - std::exp(-framePeriodMs / std::max(1.0f, config.attackMs));
    float release = 1.0f - std::exp(-framePeriodMs / std::max(1.0f, config.releaseMs));

    std::lock_guard<std::mutex> publish(mutex_);
    std::vector<float>& frame = frameRing_[static_cast<size_t>(frameWrite_)];
    for (int i = 0; i < kBands; i++) {
        float power = bandPower_[i];
        if (quiet) {
            if (noiseFloor_[i] <= 0.0f || power < noiseFloor_[i]) noiseFloor_[i] = power;
            else noiseFloor_[i] += (power - noiseFloor_[i]) * kNoiseFloorRise;
        }
        float signal = power - noiseFloor_[i] * kNoiseFloorMargin;
        if (signal < 0.0f) signal = 0.0f;

        float db = toDb(signal) + curve[i];
        float coeff = db > smoothedDb_[i] ? attack : release;
        smoothedDb_[i] += (db - smoothedDb_[i]) * coeff;
        frame[static_cast<size_t>(i)] = smoothedDb_[i];
    }

    frameWrite_ = (frameWrite_ + 1) % kFrameRingSize;
    frameCount_++;
}

float Analyzer::frameRate() const {
    return static_cast<float>(sampleRate_) / static_cast<float>(hop_);
}

void Analyzer::getLevelsDb(float* out32) {
    if (out32 == nullptr) return;
    std::lock_guard<std::mutex> lock(mutex_);
    readDelayedFrame(out32);
}

void Analyzer::readDelayedFrame(float* out32) const {

    // Hold the display back by the playback latency. What we just captured has not reached the
    // speakers yet, so showing it immediately puts the picture ahead of the sound.
    float framePeriodMs = 1000.0f * static_cast<float>(hop_) / static_cast<float>(sampleRate_);
    (void) 0;
    int delayFrames = static_cast<int>(config_.latencyMs / std::max(1.0f, framePeriodMs) + 0.5f);
    if (delayFrames < 0) delayFrames = 0;
    if (delayFrames > kFrameRingSize - 2) delayFrames = kFrameRingSize - 2;
    if (delayFrames >= frameCount_) delayFrames = std::max(0, frameCount_ - 1);

    int index = frameWrite_ - 1 - delayFrames;
    while (index < 0) index += kFrameRingSize;
    const std::vector<float>& frame = frameRing_[static_cast<size_t>(index)];
    for (int i = 0; i < kBands; i++) out32[i] = frame[static_cast<size_t>(i)];
}

void Analyzer::getLevels(int consumer, float* out32, float* out16) {
    if (consumer < 0 || consumer > 1) consumer = 0;
    std::lock_guard<std::mutex> lock(mutex_);
    float db[kBands];
    readDelayedFrame(db);

    AgcState& state = agc_[consumer];
    float peak = -120.0f;
    for (int i = 0; i < kBands; i++) peak = std::max(peak, db[i]);

    // Fast up, slow down: the reference must not lunge at every transient, or a quiet passage
    // right after a loud one reads as full scale.
    if (peak > state.runningPeakDb) state.runningPeakDb += (peak - state.runningPeakDb) * 0.25f;
    else state.runningPeakDb += (peak - state.runningPeakDb) * 0.004f;

    float reference = config_.refMaxDb;
    if (state.config.enabled) {
        float adaptive = std::max(state.runningPeakDb, state.config.minRefDb);
        // strength blends between the fixed reference and the adaptive one, so the user can dial
        // in how much the display is allowed to flatter quiet music instead of just on or off.
        float s = std::min(1.0f, std::max(0.0f, state.config.strength));
        reference = config_.refMaxDb * (1.0f - s) + adaptive * s;
    }

    float range = std::max(6.0f, config_.rangeDb);
    for (int i = 0; i < kBands; i++) {
        float level = (db[i] - (reference - range)) / range;
        out32[i] = std::min(1.0f, std::max(0.0f, level));
    }

    if (out16 != nullptr) {
        for (int i = 0; i < kHwBands; i++) {
            // Folding pairs back to the hardware grid is an exact energy sum, because the two
            // analysis bands are precisely the two halves of the hardware band.
            float a = std::pow(10.0f, db[i * 2] / 10.0f);
            float b = std::pow(10.0f, db[i * 2 + 1] / 10.0f);
            float sumDb = toDb(a + b);
            float level = (sumDb - (reference - range)) / range;
            out16[i] = std::min(1.0f, std::max(0.0f, level));
        }
    }
}

} // namespace wdsp
