#include "fft.h"

#include <cmath>

namespace wdsp {

namespace {
constexpr float kPi = 3.14159265358979323846f;
}

Fft::Fft(int size)
        : size_(size),
          bits_(0),
          re_(static_cast<size_t>(size)),
          im_(static_cast<size_t>(size)),
          window_(static_cast<size_t>(size)),
          cosTable_(static_cast<size_t>(size / 2)),
          sinTable_(static_cast<size_t>(size / 2)),
          reverse_(static_cast<size_t>(size)),
          windowPowerCorrection_(1.0f) {
    while ((1 << bits_) < size_) bits_++;

    for (int i = 0; i < size_ / 2; i++) {
        float angle = -2.0f * kPi * static_cast<float>(i) / static_cast<float>(size_);
        cosTable_[i] = std::cos(angle);
        sinTable_[i] = std::sin(angle);
    }

    for (int i = 0; i < size_; i++) {
        int r = 0;
        for (int b = 0; b < bits_; b++) {
            if (i & (1 << b)) r |= 1 << (bits_ - 1 - b);
        }
        reverse_[i] = r;
    }

    // Hann. Its power gain is 3/8 of a rectangular window of the same length, and every bin
    // power is scaled down by that, so undo it here rather than in the band maths.
    double powerSum = 0.0;
    for (int i = 0; i < size_; i++) {
        float w = 0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(i)
                                         / static_cast<float>(size_ - 1));
        window_[i] = w;
        powerSum += static_cast<double>(w) * w;
    }
    double meanPower = powerSum / size_;
    windowPowerCorrection_ = meanPower > 0 ? static_cast<float>(1.0 / meanPower) : 1.0f;
}

void Fft::transform() {
    // Bit-reversal permutation is folded into the copy done by the caller, so start at stage 2.
    for (int len = 2; len <= size_; len <<= 1) {
        int half = len >> 1;
        int step = size_ / len;
        for (int i = 0; i < size_; i += len) {
            int t = 0;
            for (int j = i; j < i + half; j++, t += step) {
                float wr = cosTable_[t];
                float wi = sinTable_[t];
                int k = j + half;
                float tr = re_[k] * wr - im_[k] * wi;
                float ti = re_[k] * wi + im_[k] * wr;
                re_[k] = re_[j] - tr;
                im_[k] = im_[j] - ti;
                re_[j] += tr;
                im_[j] += ti;
            }
        }
    }
}

void Fft::powerSpectrum(const float* input, float* out) {
    for (int i = 0; i < size_; i++) {
        int r = reverse_[i];
        re_[r] = input[i] * window_[i];
        im_[r] = 0.0f;
    }

    transform();

    // Scale so a full-scale sine reads the same regardless of transform length: the coherent
    // gain of an N-point transform is N/2 for a real sine, and power is that squared.
    float norm = 2.0f / (static_cast<float>(size_) * static_cast<float>(size_));
    norm *= windowPowerCorrection_;

    int bins = size_ / 2;
    for (int i = 0; i <= bins; i++) {
        float rr = re_[i];
        float ii = im_[i];
        out[i] = (rr * rr + ii * ii) * norm;
    }
}

} // namespace wdsp
