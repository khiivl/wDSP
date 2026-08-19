#pragma once

#include <cstdint>
#include <vector>

namespace wdsp {

/**
 * Rebuilds a continuous audio stream out of the overlapping blocks the platform Visualizer hands
 * back.
 *
 * Visualizer has no notion of a stream: every read returns "the most recent captureSize samples",
 * with no timestamp and no guarantee about how much of it we have already seen. Reading on a
 * callback at the platform's maximum rate of 20 Hz gives 1024 samples every 50 ms, which at 48 kHz
 * is 21 ms of audio out of every 50 - the other 29 ms is simply lost, and concatenating what
 * arrives produces a signal that is neither continuous nor at the right time base. Any transform
 * over that is meaningless below the block rate, which is why the bottom two bands used to jump
 * between a plausible number and zero on stationary pink noise.
 *
 * Polling faster than the block duration instead means consecutive reads overlap. This class finds
 * the overlap by normalised cross-correlation and appends only the genuinely new tail, which
 * yields a real, continuous stream - the prerequisite for both honest low-frequency resolution and
 * a frame rate above 20.
 */
class Stitcher {
public:
    explicit Stitcher(int capacity);

    /**
     * Appends one freshly polled block.
     *
     * @param block unsigned 8-bit samples centred on 128, as Visualizer.getWaveForm delivers them
     * @param len   number of samples
     * @return how many samples were genuinely new
     */
    int push(const uint8_t* block, int len);

    /**
     * Appends samples that are already known to be continuous, skipping the alignment search.
     * Used for microphone input, which arrives as a real stream rather than as repeated reads of
     * the same rolling buffer.
     */
    void appendContinuous(const float* samples, int count);

    /** Copies the newest count samples into dst, oldest first. False if not enough history yet. */
    bool readNewest(float* dst, int count) const;

    /** Total samples ever appended - the caller uses it to decide when a hop is due. */
    int64_t totalSamples() const { return total_; }

    /** Blocks that could not be aligned; a rising count means the poll rate is too slow. */
    int discontinuities() const { return discontinuities_; }

    void reset();

private:
    float correlate(const float* a, const float* b, int count, float energyA) const;
    void append(const float* src, int count);
    void tail(float* dst, int count) const;

    std::vector<float> ring_;
    int capacity_;
    int writePos_;
    int64_t total_;
    int discontinuities_;
    std::vector<float> scratch_;
};

} // namespace wdsp
