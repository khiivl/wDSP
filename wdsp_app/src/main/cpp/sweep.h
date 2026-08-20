#pragma once

#include <vector>

#include "analyzer.h"

namespace wdsp {

/**
 * Room measurement by exponential sine sweep.
 *
 * A sweep that rises exponentially spends the same time in every octave, which is what a room
 * measurement wants: the bottom of the range, where a single cycle lasts fifty milliseconds,
 * gets as much of the signal as the top. Deconvolving the recording against the sweep's inverse
 * filter collapses it back into an impulse response - and, because the sweep's harmonic
 * distortion lands at negative times, the distortion separates itself from the answer instead of
 * contaminating it.
 *
 * One measurement therefore yields everything the calibration needs:
 *
 *   - when the sound arrived, to a fraction of a millisecond, which sets the channel delays;
 *   - whether it arrived the right way up, which catches a subwoofer wired out of phase;
 *   - how loud each band was, which is the frequency response.
 *
 * The first two are exact whatever the microphone's own response happens to be, because they come
 * from timing rather than from level. Only the third carries the microphone's error.
 */
class SweepMeasurement {
public:
    /**
     * @param sampleRate  48000 on this platform
     * @param startHz     bottom of the sweep; below the loudspeaker's range is wasted energy
     * @param endHz       top of the sweep; must stay under the Nyquist frequency
     * @param seconds     longer means better signal against a noisy cabin, and more memory
     */
    SweepMeasurement(int sampleRate, float startHz, float endHz, float seconds);

    int sampleRate() const { return sampleRate_; }
    int sweepLength() const { return static_cast<int>(sweep_.size()); }
    const std::vector<float>& sweep() const { return sweep_; }

    /** Writes the sweep, scaled to the given amplitude, into a caller-owned buffer. */
    void generate(float* out, float amplitude) const;

    /**
     * Recovers the impulse response from a recording of the sweep.
     *
     * The recording may - and should - run past the end of the sweep, so that the room's decay is
     * captured too. The returned impulse response is trimmed to the useful part: distortion
     * products sit before the direct sound and are discarded with it.
     *
     * @return false when the recording is too short to hold a sweep at all
     */
    bool deconvolve(const float* recorded, int recordedLength, std::vector<float>& impulse) const;

    /**
     * Index of the direct sound within an impulse response.
     *
     * The peak of the energy envelope rather than of the waveform itself, so that a response whose
     * first arrival happens to cross zero is not missed by a sample or two.
     *
     * @param prominence how far the peak stands above the rest, as a ratio. A room measurement
     *                   that returns a prominence near one has found noise, not a loudspeaker.
     */
    static int findArrival(const float* impulse, int length, float& prominence);

    /** Sign of the direct sound: negative means the loudspeaker is wired the wrong way round. */
    static int polarityAt(const float* impulse, int length, int arrival);

    /**
     * How much of a recording lives above 8 kHz, relative to the band below it, in decibels.
     *
     * A stream that claims 48 kHz but is really 16 kHz resampled has nothing above 8 kHz at all,
     * and the platform will not admit it: {@code AudioRecord.getSampleRate()} returns the rate that
     * was asked for either way. Measuring is the only honest test, and it matters here because an
     * assistant hotword holds the microphone from boot on some head units and quietly halves the
     * bandwidth of every other recording.
     *
     * Around -10 dB is normal for a sweep. Below -25 dB the top of the sweep is simply missing.
     */
    static float bandwidthRatioDb(const float* signal, int length, int sampleRate);

    /**
     * Band levels in dB from a window of the impulse response, on the hardware band grid.
     *
     * The window starts a little before the arrival and runs long enough for the bottom of the
     * range to have something to show: at 48 kHz, sixteen thousand samples is a third of a
     * second, which is seven cycles of the lowest band and several times the decay of a car.
     */
    void bandLevelsDb(const float* impulse, int length, int arrival, float* out16) const;

private:
    int sampleRate_;
    float startHz_;
    float endHz_;
    std::vector<float> sweep_;
    std::vector<float> inverse_;
};

} // namespace wdsp
