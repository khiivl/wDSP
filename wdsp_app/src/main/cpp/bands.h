#pragma once

#include <cmath>

namespace wdsp {

/** Bands of the standard third-octave grid, 16 Hz .. 20 kHz. */
constexpr int kThirdOctaveBands = 32;

/**
 * Centre of third-octave band {@code i}: exactly 1000 * 2^((i - 18) / 3), so band 31 is 20 kHz and
 * every hardware centre (20, 31.5, 50 ... 20000) is an odd band. One grid for the live analyser and
 * for the sweep's per-channel response, so a band means the same thing in both.
 */
inline float thirdOctaveCentreHz(int i) {
    return 1000.0f * std::pow(2.0f, static_cast<float>(i - 18) / 3.0f);
}

}  // namespace wdsp
