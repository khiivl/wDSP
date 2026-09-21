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

inline float toDb(float power) {
    return 10.0f * std::log10(power + kPowerFloor);
}

} // namespace

const float kHwCenters[kHwBands] = {
        20.0f, 31.5f, 50.0f, 80.0f, 125.0f, 200.0f, 315.0f, 500.0f,
        800.0f, 1250.0f, 2000.0f, 3150.0f, 5000.0f, 8000.0f, 12500.0f, 20000.0f
};

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

Analyzer::~Analyzer() = default;

int Analyzer::getWaveform(uint8_t* out, int maxLen) {
    if (out == nullptr || maxLen <= 0) return 0;
    std::lock_guard<std::mutex> lock(ringMutex_);
    int count = std::min(maxLen, 1024);
    std::vector<float> tmp(static_cast<size_t>(count));
    if (!stitcher_.readNewest(tmp.data(), count)) return 0;
    for (int i = 0; i < count; i++) {
        int val = static_cast<int>(tmp[static_cast<size_t>(i)] * 128.0f) + 128;
        out[i] = static_cast<uint8_t>(std::max(0, std::min(255, val)));
    }
    return count;
}

int Analyzer::readStream(float* out, int count) {
    if (out == nullptr || count <= 0) return 0;
    std::lock_guard<std::mutex> lock(ringMutex_);
    int64_t total = stitcher_.totalSamples();
    if (total < count) count = static_cast<int>(total);
    if (count <= 0 || !stitcher_.readNewest(out, count)) return 0;
    return count;
}

void Analyzer::setNoiseFloorEnabled(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    noiseFloorEnabled_ = enabled;
    if (!enabled) {
        for (int i = 0; i < kBands; i++) noiseFloor_[i] = 0.0f;
    }
}

void Analyzer::getTermsDb(float* powerDb32, float* floorDb32, float* curveDb32) {
    std::lock_guard<std::mutex> lock(mutex_);
    for (int i = 0; i < kBands; i++) {
        if (powerDb32 != nullptr) powerDb32[i] = toDb(bandPower_[i]);
        if (floorDb32 != nullptr) floorDb32[i] = toDb(noiseFloor_[i]);
        if (curveDb32 != nullptr) curveDb32[i] = dspCurve_[i];
    }
}

void Analyzer::buildBandPlan() {
    // The standard third-octave grid, 16 Hz .. 20 kHz: exact centres 1000 * 2^((i - 18) / 3), so
    // band 31 is 20 kHz and every hardware centre (20, 31.5, 50 ... 20000) is an odd band.
    //
    // 🔴 Until 14.09.2026 the 32 bands were the two halves of each hardware band, centred a sixth of
    // an octave either side of it: 17.8, 22.4, 28.1 ... 17818, 22449 Hz. The fold back to 16 was an
    // exact pair sum, but the grid sat a sixth of an octave off every standard frequency and its top
    // band, 20-25 kHz, was always empty - on the owner's unit the Visualizer tap ran at 44.1 kHz and
    // that bar read -16 dB on pink noise, pulling the 20 kHz hardware bar down with it. Owner:
    // "сітку стандартну".
    const float sixth = std::pow(2.0f, 1.0f / 6.0f);
    const float nyquist = static_cast<float>(sampleRate_) * 0.5f;

    for (int i = 0; i < kBands; i++) {
        float center = 1000.0f * std::pow(2.0f, static_cast<float>(i - 18) / 3.0f);
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
    // On the standard grid an odd band sits exactly on hardware centre (i - 1) / 2; an even band sits
    // half way between two hardware centres and takes the mean of their dB values. Band 0 (16 Hz)
    // is below the lowest hardware centre and takes its value.
    for (int i = 0; i < kBands; i++) {
        if (i % 2 == 1) dspCurve_[i] = curve16[(i - 1) / 2];
        else if (i == 0) dspCurve_[i] = curve16[0];
        else dspCurve_[i] = 0.5f * (curve16[i / 2 - 1] + curve16[i / 2]);
    }
}

void Analyzer::forgetNoiseFloor() {
    // Same lock processFrame holds while it reads and teaches the floor.
    std::lock_guard<std::mutex> lock(mutex_);
    for (int i = 0; i < kBands; i++) {
        noiseFloor_[i] = 0.0f;
    }
}

int Analyzer::pushWaveform(const uint8_t* block, int len, int64_t captureTimeNs) {
    int fresh;
    {
        std::lock_guard<std::mutex> lock(ringMutex_);
        int expectedNew = -1;
        if (lastCaptureNs_ > 0 && captureTimeNs > lastCaptureNs_) {
            expectedNew = static_cast<int>(std::min<int64_t>(
                    (captureTimeNs - lastCaptureNs_) * sampleRate_ / 1000000000LL, len));
        }
        lastCaptureNs_ = captureTimeNs;
        fresh = stitcher_.push(block, len, expectedNew);
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

void Analyzer::pushPcm16(const int16_t* samples, int count, int channels, float gain) {
    if (samples == nullptr || count <= 0) return;
    std::lock_guard<std::mutex> lock(ringMutex_);

    if (channels < 1) channels = 1;
    int frames = count / channels;
    std::vector<float> mono(static_cast<size_t>(frames));
    float scale = (gain > 0.0f ? gain : 1.0f) / (channels * 32768.0f);
    for (int i = 0; i < frames; i++) {
        float sum = 0.0f;
        for (int c = 0; c < channels; c++) {
            sum += static_cast<float>(samples[i * channels + c]);
        }
        float val = sum * scale;
        if (val > 1.0f) val = 1.0f;
        else if (val < -1.0f) val = -1.0f;
        mono[static_cast<size_t>(i)] = val;
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
        // Only quiet frames teach the floor. The branch that used to stand here learned outside
        // them as well - taking the running minimum of any frame at all - which is precisely what
        // the comment above warns against: on stationary content the floor walks down onto the
        // signal's own minima. The Java twin of this loop (AudioSpectrumEngine.processFft) never
        // had that branch, so the two analysers disagreed about the same car.
        //
        // Nothing is lost by removing it: the quiet branch min-tracks, so the floor still falls
        // the moment a genuinely quiet frame arrives that is lower than what is stored.
        //
        // And only for a microphone (noiseFloorEnabled_): the Visualizer's PCM has no acoustic
        // noise in it, and on 14.09.2026 a floor learned in the calculated mode took the 17.8 kHz
        // band of steady pink noise down to nothing.
        if (quiet && noiseFloorEnabled_) {
            if (noiseFloor_[i] <= 0.0f || power < noiseFloor_[i]) noiseFloor_[i] = power;
            else noiseFloor_[i] += (power - noiseFloor_[i]) * kNoiseFloorRise;
        }
        float signal = noiseFloorEnabled_ ? power - noiseFloor_[i] * kNoiseFloorMargin : power;
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

void Analyzer::foldTo16Db(const float* db32, float* out16Db) {
    for (int i = 0; i < kHwBands; i++) {
        // A hardware band is two-thirds of an octave around its centre: the third-octave band on
        // that centre (index 2i + 1) and the inner half of each neighbour. Half the neighbour's
        // energy is exact for pink content - its two log-halves carry equal energy - and close
        // for anything that does not change sharply inside one third of an octave.
        //
        // The top hardware band has no neighbour above 20 kHz on this grid (22.4-25.4 kHz is
        // not a band). Its missing quarter is taken at the density of what was measured, x4/3,
        // so a flat input reads level at 20 kHz instead of 1.25 dB low.
        const int c = i * 2 + 1;
        float centre = std::pow(10.0f, db32[c] / 10.0f);
        float below = 0.5f * std::pow(10.0f, db32[c - 1] / 10.0f);
        float sum = centre + below;
        if (c + 1 < kBands) sum += 0.5f * std::pow(10.0f, db32[c + 1] / 10.0f);
        else sum *= 4.0f / 3.0f;
        out16Db[i] = toDb(sum);
    }
}

void Analyzer::getLevelsDb16(float* out16) {
    if (out16 == nullptr) return;
    std::lock_guard<std::mutex> lock(mutex_);
    float db[kBands];
    readDelayedFrame(db);
    foldTo16Db(db, out16);
}

void Analyzer::setLevelOffsetDb(int consumer, float offsetDb) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (consumer < 0 || consumer > 1) return;
    agc_[consumer].offsetDb = offsetDb;
}

void Analyzer::getLevels(int consumer, float* out32, float* out16) {
    if (consumer < 0 || consumer > 1) consumer = 0;
    std::lock_guard<std::mutex> lock(mutex_);
    float db[kBands];
    readDelayedFrame(db);

    AgcState& state = agc_[consumer];
    for (int i = 0; i < kBands; i++) db[i] += state.offsetDb;
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
        float db16[kHwBands];
        foldTo16Db(db, db16);
        for (int i = 0; i < kHwBands; i++) {
            float level = (db16[i] - (reference - range)) / range;
            out16[i] = std::min(1.0f, std::max(0.0f, level));
        }
    }
}

} // namespace wdsp
