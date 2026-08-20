#include "sweep.h"

#include <algorithm>
#include <cmath>

namespace wdsp {

namespace {

constexpr float kPi = 3.14159265358979323846f;
/** Fade at each end of the sweep, so it neither clicks nor rings the tweeter on the way in. */
constexpr float kFadeInSec = 0.02f;
constexpr float kFadeOutSec = 0.05f;
/** How much of the impulse response is kept, and how far before the arrival the window opens. */
constexpr int kAnalysisWindow = 16384;
constexpr int kPreArrival = 64;

int nextPowerOfTwo(int n) {
    int size = 1;
    while (size < n) size <<= 1;
    return size;
}

/**
 * In-place iterative radix-2 complex FFT.
 *
 * Separate from the analyser's {@link Fft}, which is tuned for one fixed size and only ever needs
 * the power spectrum of a real signal. Deconvolution needs the complex spectrum, both directions,
 * and at a size that depends on how long the recording turned out to be.
 */
void fft(std::vector<float>& re, std::vector<float>& im, bool inverse) {
    const int n = static_cast<int>(re.size());
    for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (int len = 2; len <= n; len <<= 1) {
        const float angle = (inverse ? 2.0f : -2.0f) * kPi / static_cast<float>(len);
        const float wRe = std::cos(angle);
        const float wIm = std::sin(angle);
        for (int i = 0; i < n; i += len) {
            float curRe = 1.0f;
            float curIm = 0.0f;
            for (int k = 0; k < len / 2; k++) {
                const int a = i + k;
                const int b = i + k + len / 2;
                const float tRe = re[b] * curRe - im[b] * curIm;
                const float tIm = re[b] * curIm + im[b] * curRe;
                re[b] = re[a] - tRe;
                im[b] = im[a] - tIm;
                re[a] += tRe;
                im[a] += tIm;
                const float nextRe = curRe * wRe - curIm * wIm;
                curIm = curRe * wIm + curIm * wRe;
                curRe = nextRe;
            }
        }
    }
    if (inverse) {
        const float scale = 1.0f / static_cast<float>(n);
        for (int i = 0; i < n; i++) {
            re[i] *= scale;
            im[i] *= scale;
        }
    }
}

} // namespace

SweepMeasurement::SweepMeasurement(int sampleRate, float startHz, float endHz, float seconds)
        : sampleRate_(sampleRate > 0 ? sampleRate : 48000),
          startHz_(startHz),
          endHz_(endHz) {
    const int length = std::max(1024, static_cast<int>(seconds * static_cast<float>(sampleRate_)));
    sweep_.resize(length);
    inverse_.resize(length);

    const float w1 = 2.0f * kPi * startHz_;
    const float w2 = 2.0f * kPi * endHz_;
    const float duration = static_cast<float>(length) / static_cast<float>(sampleRate_);
    const float ratio = std::log(w2 / w1);
    const float k = w1 * duration / ratio;

    const int fadeIn = static_cast<int>(kFadeInSec * static_cast<float>(sampleRate_));
    const int fadeOut = static_cast<int>(kFadeOutSec * static_cast<float>(sampleRate_));

    for (int i = 0; i < length; i++) {
        const float t = static_cast<float>(i) / static_cast<float>(sampleRate_);
        float value = std::sin(k * (std::exp(t / duration * ratio) - 1.0f));

        if (i < fadeIn) {
            value *= 0.5f - 0.5f * std::cos(kPi * static_cast<float>(i)
                                            / static_cast<float>(fadeIn));
        }
        const int fromEnd = length - 1 - i;
        if (fromEnd < fadeOut) {
            value *= 0.5f - 0.5f * std::cos(kPi * static_cast<float>(fromEnd)
                                            / static_cast<float>(fadeOut));
        }
        sweep_[i] = value;
    }

    // The inverse filter is the sweep played backwards, with an envelope that rises in step with
    // the instantaneous frequency.
    //
    // The sweep puts equal energy into every octave, so its power per hertz falls as 1/f and the
    // product of the sweep with its own reversal falls twice as fast. The envelope has to undo
    // exactly that, which means weighting each sample by the frequency it carries. Because the
    // filter runs backwards, its highest frequency sits at the start - so along the filter the
    // envelope decays, which is the six decibels an octave the textbooks quote.
    //
    // Getting this the wrong way round does not break anything visibly: it simply tilts every
    // measurement by six decibels an octave, which looks exactly like a car that has no treble.
    // The host test caught it on a path that was flat by construction.
    for (int i = 0; i < length; i++) {
        const float t = static_cast<float>(length - 1 - i) / static_cast<float>(sampleRate_);
        inverse_[i] = sweep_[length - 1 - i] * std::exp(t / duration * ratio);
    }

    // Normalise so that a perfect recording of the sweep yields an impulse of unit height. Without
    // this the numbers depend on the sweep's length and range, which would make two measurements
    // taken with different settings incomparable.
    double energy = 0.0;
    for (int i = 0; i < length; i++) {
        energy += static_cast<double>(sweep_[i]) * inverse_[length - 1 - i];
    }
    if (energy > 1e-12) {
        const float scale = static_cast<float>(1.0 / energy);
        for (float& value : inverse_) value *= scale;
    }
}

void SweepMeasurement::generate(float* out, float amplitude) const {
    for (size_t i = 0; i < sweep_.size(); i++) out[i] = sweep_[i] * amplitude;
}

bool SweepMeasurement::deconvolve(const float* recorded, int recordedLength,
                                  std::vector<float>& impulse) const {
    const int sweepLen = sweepLength();
    if (recorded == nullptr || recordedLength < sweepLen / 2) return false;

    const int n = nextPowerOfTwo(recordedLength + sweepLen);
    std::vector<float> re(n, 0.0f);
    std::vector<float> im(n, 0.0f);
    std::vector<float> kRe(n, 0.0f);
    std::vector<float> kIm(n, 0.0f);

    for (int i = 0; i < recordedLength; i++) re[i] = recorded[i];
    for (int i = 0; i < sweepLen; i++) kRe[i] = inverse_[i];

    fft(re, im, false);
    fft(kRe, kIm, false);

    for (int i = 0; i < n; i++) {
        const float a = re[i] * kRe[i] - im[i] * kIm[i];
        const float b = re[i] * kIm[i] + im[i] * kRe[i];
        re[i] = a;
        im[i] = b;
    }
    fft(re, im, true);

    // The linear convolution places the direct sound one sweep length in, and everything before it
    // is harmonic distortion, which is precisely why this method is used. Keep what follows.
    const int start = sweepLen - 1;
    const int keep = std::min(n - start, recordedLength);
    impulse.assign(re.begin() + start, re.begin() + start + keep);
    return true;
}

int SweepMeasurement::findArrival(const float* impulse, int length, float& prominence) {
    prominence = 0.0f;
    if (impulse == nullptr || length < 16) return -1;

    // A short energy envelope, so a first arrival that happens to cross zero is not missed.
    constexpr int kEnvelope = 8;
    int best = -1;
    double bestEnergy = 0.0;
    double total = 0.0;
    int windows = 0;

    for (int i = 0; i + kEnvelope <= length; i += kEnvelope / 2) {
        double energy = 0.0;
        for (int k = 0; k < kEnvelope; k++) {
            const double v = impulse[i + k];
            energy += v * v;
        }
        total += energy;
        windows++;
        if (energy > bestEnergy) {
            bestEnergy = energy;
            best = i;
        }
    }
    if (best < 0 || windows == 0) return -1;

    const double average = total / windows;
    prominence = average > 1e-30 ? static_cast<float>(bestEnergy / average) : 0.0f;

    // The peak itself, not the moment the energy started rising. A sweep that stops at 20 Hz
    // recovers a band-limited impulse, and a band-limited impulse rings symmetrically on both
    // sides of its true position - so hunting for the onset walks straight into the ringing and
    // reports the arrival a couple of hundred samples early, every time and by the same amount.
    // The peak sits where the sound actually is.
    int peak = best;
    float peakValue = 0.0f;
    const int to = std::min(length, best + kEnvelope);
    for (int i = std::max(0, best); i < to; i++) {
        if (std::fabs(impulse[i]) > peakValue) {
            peakValue = std::fabs(impulse[i]);
            peak = i;
        }
    }
    return peak;
}

int SweepMeasurement::polarityAt(const float* impulse, int length, int arrival) {
    if (impulse == nullptr || arrival < 0 || arrival >= length) return 0;

    // Polarity is a question about the woofer, not the tweeter. At 10 kHz a wavelength is three
    // centimetres, so the sign of the very first excursion up there says more about where the
    // microphone happens to be than about how the speaker is wired - and on a quieter channel the
    // largest excursion of the raw response is often a ringing lobe of the wrong sign.
    //
    // Measured on a bench with two correctly wired front speakers: the raw rule called the louder
    // one +1 and the quieter one -1, every single run. Smoothing first, which keeps the judgement
    // on the frequencies where "in phase" means anything, is what makes both of them agree.
    constexpr int kSmooth = 48;          // a moving average this long rolls off around 1 kHz
    constexpr int kLook = 512;           // ten milliseconds is the direct sound and no more

    const int from = std::max(0, arrival - kSmooth);
    const int to = std::min(length - kSmooth, arrival + kLook);
    if (to <= from) return 0;

    double running = 0.0;
    for (int i = from; i < from + kSmooth && i < length; i++) running += impulse[i];

    float peak = 0.0f;
    int sign = 0;
    for (int i = from; i < to; i++) {
        const float smoothed = static_cast<float>(running / kSmooth);
        if (std::fabs(smoothed) > peak) {
            peak = std::fabs(smoothed);
            sign = smoothed >= 0.0f ? 1 : -1;
        }
        running -= impulse[i];
        running += impulse[i + kSmooth];
    }
    return sign;
}

float SweepMeasurement::clarityDb(const float* impulse, int length, int arrival,
                                  int sampleRate) {
    if (impulse == nullptr || arrival < 0 || arrival >= length || sampleRate <= 0) return 0.0f;

    const int direct = sampleRate / 1000;          // the first millisecond is the arrival itself
    const int tail = sampleRate / 100;             // the ten after it are the room answering back
    const int directTo = std::min(length, arrival + direct);
    const int tailTo = std::min(length, arrival + direct + tail);

    double directEnergy = 0.0;
    for (int i = arrival; i < directTo; i++) {
        directEnergy += static_cast<double>(impulse[i]) * impulse[i];
    }
    double tailEnergy = 0.0;
    for (int i = directTo; i < tailTo; i++) {
        tailEnergy += static_cast<double>(impulse[i]) * impulse[i];
    }
    if (directEnergy <= 0.0) return -60.0f;
    // Per sample, so the two windows are comparable although one is ten times longer.
    const double directPer = directEnergy / std::max(1, directTo - arrival);
    const double tailPer = tailEnergy / std::max(1, tailTo - directTo);
    if (tailPer <= 0.0) return 60.0f;
    return 10.0f * std::log10(static_cast<float>(directPer / tailPer));
}

float SweepMeasurement::bandwidthRatioDb(const float* signal, int length, int sampleRate) {
    constexpr int kWindow = 16384;
    if (signal == nullptr || length < kWindow || sampleRate <= 0) return 0.0f;

    // Take the window from where the recording is loudest, so the answer describes the sweep
    // rather than the silence around it.
    int best = 0;
    double bestEnergy = -1.0;
    const int stride = std::max(1, (length - kWindow) / 16);
    for (int start = 0; start + kWindow <= length; start += stride) {
        double energy = 0.0;
        for (int i = start; i < start + kWindow; i += 8) {
            const double v = signal[i];
            energy += v * v;
        }
        if (energy > bestEnergy) {
            bestEnergy = energy;
            best = start;
        }
    }

    std::vector<float> re(kWindow, 0.0f);
    std::vector<float> im(kWindow, 0.0f);
    for (int i = 0; i < kWindow; i++) {
        const float w = 0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(i)
                                               / static_cast<float>(kWindow - 1));
        re[i] = signal[best + i] * w;
    }
    fft(re, im, false);

    const float binHz = static_cast<float>(sampleRate) / static_cast<float>(kWindow);
    double low = 0.0;
    double high = 0.0;
    for (int i = 1; i <= kWindow / 2; i++) {
        const float hz = static_cast<float>(i) * binHz;
        const double power = static_cast<double>(re[i]) * re[i] + static_cast<double>(im[i]) * im[i];
        // Below 300 Hz is left out: road rumble and body resonance would swamp the comparison.
        if (hz >= 300.0f && hz < 8000.0f) low += power;
        else if (hz >= 8500.0f && hz < static_cast<float>(sampleRate) * 0.45f) high += power;
    }
    if (low <= 0.0) return 0.0f;
    return 10.0f * std::log10(static_cast<float>(high / low) + 1e-12f);
}

void SweepMeasurement::bandLevelsDb(const float* impulse, int length, int arrival,
                                    float* out16) const {
    for (int b = 0; b < kHwBands; b++) out16[b] = -120.0f;
    if (impulse == nullptr || arrival < 0 || arrival >= length) return;

    const int from = std::max(0, arrival - kPreArrival);
    const int available = length - from;
    const int windowLen = std::min(kAnalysisWindow, available);
    if (windowLen < 1024) return;

    const int n = nextPowerOfTwo(windowLen);
    std::vector<float> re(n, 0.0f);
    std::vector<float> im(n, 0.0f);

    // A half-Hann fade at the tail only: the arrival must keep its full height, but the window has
    // to close smoothly or the transform will show the cut as broadband splatter.
    const int fade = windowLen / 4;
    for (int i = 0; i < windowLen; i++) {
        float w = 1.0f;
        const int fromEnd = windowLen - 1 - i;
        if (fromEnd < fade) {
            w = 0.5f - 0.5f * std::cos(kPi * static_cast<float>(fromEnd)
                                       / static_cast<float>(fade));
        }
        re[i] = impulse[from + i] * w;
    }

    fft(re, im, false);

    const float binHz = static_cast<float>(sampleRate_) / static_cast<float>(n);
    const float third = std::pow(2.0f, 1.0f / 3.0f);

    for (int b = 0; b < kHwBands; b++) {
        const float low = kHwCenters[b] / third;
        const float high = std::min(kHwCenters[b] * third,
                                    static_cast<float>(sampleRate_) * 0.5f);
        int first = static_cast<int>(std::ceil(low / binHz));
        int last = static_cast<int>(std::floor(high / binHz));
        first = std::max(first, 1);
        last = std::min(last, n / 2);
        if (last < first) {
            // Narrower than one bin: take the nearest bin rather than reporting silence.
            first = last = std::max(1, std::min(n / 2,
                                                static_cast<int>(kHwCenters[b] / binHz + 0.5f)));
        }

        double power = 0.0;
        for (int i = first; i <= last; i++) {
            power += static_cast<double>(re[i]) * re[i] + static_cast<double>(im[i]) * im[i];
        }
        // Mean power per bin, which is the magnitude of the transfer function - NOT the energy
        // summed over the band. The analyser sums, because it is showing how much sound is in a
        // band; a response measurement asks how much a band is amplified, and summing would add
        // six decibels an octave of pure bookkeeping to the answer. Measured: it turned a flat
        // path into a 45 dB ramp and halved the slope of a known filter.
        const int bins = last - first + 1;
        const double mean = power / bins;
        out16[b] = 10.0f * std::log10(static_cast<float>(mean) + 1e-20f);
    }
}

} // namespace wdsp
