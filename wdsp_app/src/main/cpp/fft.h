#pragma once

#include <vector>

namespace wdsp {

/**
 * Iterative radix-2 FFT with precomputed twiddles and a precomputed Hann window.
 *
 * One instance is fixed to one transform size, because the analyser uses two of them at once:
 * a long window for the bottom of the spectrum, where resolution matters more than speed, and a
 * short one for the top, where the opposite is true.
 */
class Fft {
public:
    explicit Fft(int size);

    int size() const { return size_; }

    /**
     * Windows the input, transforms it, and writes the power of each bin (|X|^2) into out.
     *
     * @param input  size() real samples, normalised to roughly [-1, 1]
     * @param out    size()/2 + 1 bin powers, already corrected for the window's power loss
     */
    void powerSpectrum(const float* input, float* out);

private:
    void transform();

    int size_;
    int bits_;
    std::vector<float> re_;
    std::vector<float> im_;
    std::vector<float> window_;
    std::vector<float> cosTable_;
    std::vector<float> sinTable_;
    std::vector<int> reverse_;
    float windowPowerCorrection_;
};

} // namespace wdsp
