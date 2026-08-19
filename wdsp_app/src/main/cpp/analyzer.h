#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

#include "fft.h"
#include "stitcher.h"

namespace wdsp {

/** Number of hardware equaliser bands - the grid the BU32107 actually works on. */
constexpr int kHwBands = 16;
/** Analysis bands: every hardware band split in two, so folding pairs back is exact. */
constexpr int kBands = 32;

/**
 * The spectrum analyser.
 *
 * Analysis runs on 32 third-octave bands and is folded down to the 16 hardware bands by summing
 * pairs, rather than the other way round. Sixteen two-third-octave bands are too coarse to make a
 * kick drum look like a kick drum: the whole bottom of a track lands in two or three bars that
 * heave together. At a third of an octave the fundamental and its first harmonic separate, so a
 * beat reads as a beat.
 *
 * Two transform lengths are used at once. Below the crossover a long window buys the frequency
 * resolution the bottom bands need - the 20 Hz band is nine hertz wide, which simply cannot be
 * resolved by a short transform, no matter how fast it runs. Above it a short window keeps
 * transients sharp. The split is physics, not preference: one cycle of 20 Hz lasts 50 ms, so the
 * bottom of the spectrum can never be as quick as the top.
 */
class Analyzer {
public:
    struct Config {
        float attackMs = 25.0f;
        float releaseMs = 260.0f;
        /** How long after capture the audio is actually heard; the display is held back to match. */
        float latencyMs = 0.0f;
        /** Level mapped to full scale when the automatic gain is off. */
        float refMaxDb = 0.0f;
        /** Span below refMaxDb that the display covers. */
        float rangeDb = 60.0f;
    };

    struct AgcConfig {
        bool enabled = true;
        /** 0 keeps the fixed reference, 1 follows the loudest recent band completely. */
        float strength = 1.0f;
        /** The adaptive reference is never allowed below this, so quiet music stays quiet. */
        float minRefDb = -45.0f;
    };

    Analyzer(int sampleRate, int captureSize);

    void setConfig(const Config& config);
    void setAgcConfig(int consumer, const AgcConfig& config);
    /** Response the hardware DSP will add, in dB, on the 16 hardware bands. */
    void setDspCurve(const float* curve16);

    /** Feeds one polled Visualizer block. Returns the number of genuinely new samples. */
    int pushWaveform(const uint8_t* block, int len);

    /**
     * Feeds signed 16-bit PCM, the format AudioRecord delivers.
     *
     * Groundwork for measuring the car itself through the microphone: the same band engine can
     * then show what the cabin actually does to the sound next to what the equaliser is set to,
     * which is the only honest way to suggest slider positions for a flat response. Microphone
     * input needs no stitching - it arrives as a continuous stream already.
     */
    void pushPcm16(const int16_t* samples, int count, int channels);

    /** Fills 32 and 16 band levels, normalised to 0..1 for the given consumer. */
    void getLevels(int consumer, float* out32, float* out16);
    /** Fills the raw band levels in dB, before any normalisation. */
    void getLevelsDb(float* out32);

    int discontinuities() const { return stitcher_.discontinuities(); }
    int framesProduced() const { return frameCount_; }
    float frameRate() const;

private:
    struct BandPlan {
        float lowHz;
        float highHz;
        bool useLongFft;
        int lowBin;
        int highBin;
        float expectedBins;
    };

    struct AgcState {
        AgcConfig config;
        float runningPeakDb = -60.0f;
    };

    void buildBandPlan();
    void processFrame();
    /** Body of getLevelsDb, for callers that already hold the lock. */
    void readDelayedFrame(float* out32) const;
    void accumulate(const float* power, int binCount, float binWidth, bool longFft);

    int sampleRate_;
    int hop_;
    Stitcher stitcher_;
    Fft longFft_;
    Fft shortFft_;

    std::vector<float> longInput_;
    std::vector<float> shortInput_;
    std::vector<float> longPower_;
    std::vector<float> shortPower_;

    BandPlan plan_[kBands];
    float bandDb_[kBands];
    float smoothedDb_[kBands];
    float noiseFloor_[kBands];
    float dspCurve_[kBands];

    // Ring of finished frames, so the display can be held back by the playback latency.
    std::vector<std::vector<float>> frameRing_;
    int frameWrite_;
    int frameCount_;

    Config config_;
    AgcState agc_[2];

    int64_t lastProcessedSample_;
    int64_t longFftDueAt_;
    float frameMaxPower_;

    /**
     * Capture and display run on different threads on purpose - measurements arrive when the audio
     * buffer moves on, which is not a rate anyone should draw at. Everything they share is guarded
     * here rather than left to chance.
     */
    mutable std::mutex mutex_;
};

} // namespace wdsp
