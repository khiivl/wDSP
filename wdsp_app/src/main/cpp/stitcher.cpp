#include "stitcher.h"

#include <cmath>
#include <cstring>

namespace wdsp {

namespace {
/** How many samples to compare when testing an alignment. */
constexpr int kCompare = 256;
/** Below this normalised correlation the blocks are considered unrelated. */
constexpr float kMatchThreshold = 0.90f;
/** Coarse search stride; the winner is then refined sample by sample. */
constexpr int kCoarseStep = 8;
}

Stitcher::Stitcher(int capacity)
        : ring_(static_cast<size_t>(capacity), 0.0f),
          capacity_(capacity),
          writePos_(0),
          total_(0),
          discontinuities_(0),
          scratch_(static_cast<size_t>(kCompare), 0.0f) {}

void Stitcher::reset() {
    std::fill(ring_.begin(), ring_.end(), 0.0f);
    writePos_ = 0;
    total_ = 0;
    discontinuities_ = 0;
}

void Stitcher::append(const float* src, int count) {
    for (int i = 0; i < count; i++) {
        ring_[static_cast<size_t>(writePos_)] = src[i];
        writePos_ = (writePos_ + 1) % capacity_;
    }
    total_ += count;
}

void Stitcher::tail(float* dst, int count) const {
    int pos = writePos_ - count;
    while (pos < 0) pos += capacity_;
    for (int i = 0; i < count; i++) {
        dst[i] = ring_[static_cast<size_t>(pos)];
        pos = (pos + 1) % capacity_;
    }
}

float Stitcher::correlate(const float* a, const float* b, int count, float energyA) const {
    float dot = 0.0f, energyB = 0.0f;
    for (int i = 0; i < count; i++) {
        dot += a[i] * b[i];
        energyB += b[i] * b[i];
    }
    float denom = std::sqrt(energyA * energyB);
    if (denom < 1e-9f) return 0.0f;
    return dot / denom;
}

int Stitcher::push(const uint8_t* block, int len) {
    if (len <= 0) return 0;

    std::vector<float> in(static_cast<size_t>(len));
    for (int i = 0; i < len; i++) {
        in[static_cast<size_t>(i)] = (static_cast<float>(block[i]) - 128.0f) * (1.0f / 128.0f);
    }

    // Nothing to align against yet.
    if (total_ < kCompare || len <= kCompare) {
        append(in.data(), len);
        return len;
    }

    float ref[kCompare];
    tail(ref, kCompare);
    float energyRef = 0.0f;
    for (int i = 0; i < kCompare; i++) energyRef += ref[i] * ref[i];

    // Silence gives no alignment information; treat the whole block as new rather than locking
    // onto a meaningless correlation peak.
    if (energyRef < 1e-7f) {
        append(in.data(), len);
        return len;
    }

    // s is the count of new samples at the end of the block. Trying s means claiming that the
    // block's window ending at len - s lines up with what we already hold.
    int maxS = len - kCompare;
    int bestS = -1;
    float bestScore = -1.0f;

    for (int s = 0; s <= maxS; s += kCoarseStep) {
        float score = correlate(ref, in.data() + (len - s - kCompare), kCompare, energyRef);
        if (score > bestScore) {
            bestScore = score;
            bestS = s;
        }
    }

    if (bestS >= 0) {
        int from = bestS - kCoarseStep + 1;
        int to = bestS + kCoarseStep - 1;
        if (from < 0) from = 0;
        if (to > maxS) to = maxS;
        for (int s = from; s <= to; s++) {
            float score = correlate(ref, in.data() + (len - s - kCompare), kCompare, energyRef);
            if (score > bestScore) {
                bestScore = score;
                bestS = s;
            }
        }
    }

    if (bestScore < kMatchThreshold) {
        // Either the poll was too slow and there is a real hole, or the source restarted.
        // Appending everything keeps the stream going; the transform will see one bad window.
        discontinuities_++;
        append(in.data(), len);
        return len;
    }

    if (bestS <= 0) return 0; // polled faster than the buffer refills - nothing new yet

    append(in.data() + (len - bestS), bestS);
    return bestS;
}

void Stitcher::appendContinuous(const float* samples, int count) {
    if (samples == nullptr || count <= 0) return;
    append(samples, count);
}

bool Stitcher::readNewest(float* dst, int count) const {
    if (total_ < count || count > capacity_) return false;
    tail(dst, count);
    return true;
}

} // namespace wdsp
