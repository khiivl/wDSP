package com.radiorubka.wdsp;

import android.media.audiofx.Visualizer;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized audio spectrum capture and 16-band FFT analysis engine.
 * Sourced from Android's global output mix via {@link Visualizer} (audio session 0).
 * Broadcasts calculated band levels to subscribed listeners (such as SpectrumAnalyzerView
 * and StatusBarVisualizerView) to prevent conflicting multiple Visualizer sessions.
 */
public class AudioSpectrumEngine {
    private static final String TAG = "wDSP_SpectrumEngine";

    private static final float[] BAND_EDGES_HZ = {
            15.9f, 25.1f, 39.7f, 63.2f, 100f, 158.1f, 250.8f, 397.4f,
            632.5f, 1000f, 1581.1f, 2506.0f, 3969.1f, 6299.6f, 10000f, 15849f, 25198f
    };

    private static final float REF_MIN_DB = 0f;
    private static final float REF_MAX_DB = 55f;
    private static final float RISE_SMOOTHING = 0.60f;
    private static final float FALL_SMOOTHING = 0.14f;
    private static final float ATTENUATION_DB = 6f;
    private static final float SILENCE_FADE_DB = 2.5f;
    private static final float BAND_MULT = 2f;

    // Pink noise calibration curve (dB tilt compensation across 16 fractional-octave bands).
    // Compensates for the natural 1/f spectral energy density falloff in logarithmic RTA bands,
    // ensuring Pink Noise renders with flat uniform bars, and high frequencies (treble, cymbals,
    // vocal harmonics) are vividly and accurately represented.
    private static final float[] PINK_NOISE_CALIBRATION_DB = {
            0.0f,   // 20 Hz
            0.0f,   // 31.5 Hz
            1.0f,   // 50 Hz
            2.0f,   // 80 Hz
            3.5f,   // 125 Hz
            5.5f,   // 200 Hz
            8.0f,   // 315 Hz
            10.5f,  // 500 Hz
            13.5f,  // 800 Hz
            16.5f,  // 1.25 kHz
            20.0f,  // 2.0 kHz
            23.5f,  // 3.15 kHz
            27.0f,  // 5.0 kHz
            30.5f,  // 8.0 kHz
            34.0f,  // 12.5 kHz
            37.5f   // 20.0 kHz
    };

    private static final int LOW_FFT_SIZE = 8192;
    private static final long LOW_FFT_MIN_INTERVAL_MS = 150;
    private static final float LOW_BAND_MAGNITUDE_SCALE = 0.7f;

    private final float[] lowRingBuffer = new float[LOW_FFT_SIZE];
    private int lowRingFill = 0;
    private long lastLowFftTime = 0;
    private boolean lowBandsReady = false;
    private final float[] lowBandMagnitude = new float[2];
    private final float[] fftScratchRe = new float[LOW_FFT_SIZE];
    private final float[] fftScratchIm = new float[LOW_FFT_SIZE];

    private Visualizer visualizer;

    private final float[] rawLevels = new float[AudioConfig.NUM_BANDS];
    private final float[] displayLevels = new float[AudioConfig.NUM_BANDS];
    private final int[] gains = new int[AudioConfig.NUM_BANDS];
    private final float[] smoothedContentDb = new float[AudioConfig.NUM_BANDS];

    private final float[] prevLevels = new float[AudioConfig.NUM_BANDS];
    private long lastCaptureTime = 0;
    private long captureIntervalMs = 50;

    public interface OnSpectrumDataListener {
        void onSpectrumCapture(float[] displayLevels, float[] prevLevels, long lastCaptureTime, long captureIntervalMs);
    }

    private final CopyOnWriteArrayList<OnSpectrumDataListener> listeners = new CopyOnWriteArrayList<>();

    private static AudioSpectrumEngine instance;

    public static synchronized AudioSpectrumEngine getInstance() {
        if (instance == null) {
            instance = new AudioSpectrumEngine();
        }
        return instance;
    }

    private AudioSpectrumEngine() {
        Arrays.fill(gains, 6);
    }

    public synchronized void registerListener(OnSpectrumDataListener listener) {
        if (listener == null) return;
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        if (!listeners.isEmpty() && visualizer == null) {
            start();
        }
    }

    public synchronized void unregisterListener(OnSpectrumDataListener listener) {
        if (listener == null) return;
        listeners.remove(listener);
        if (listeners.isEmpty() && visualizer != null) {
            stop();
        }
    }

    public void setGains(int[] newGains) {
        if (newGains == null) return;
        synchronized (gains) {
            System.arraycopy(newGains, 0, this.gains, 0, Math.min(newGains.length, AudioConfig.NUM_BANDS));
        }
    }

    public synchronized void start() {
        if (visualizer != null) return;
        try {
            Visualizer v = new Visualizer(0);

            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = range[1];
            if (captureSize < range[0]) captureSize = range[0];
            v.setCaptureSize(captureSize);

            int rate = Visualizer.getMaxCaptureRate();
            if (rate <= 0) rate = 20000;

            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    processWaveform(waveform, samplingRate);
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    processFft(fft, samplingRate);
                }
            }, rate, true, true);

            v.setEnabled(true);
            visualizer = v;
            Log.d(TAG, "AudioSpectrumEngine attached to session 0, captureSize=" + captureSize);
        } catch (Throwable t) {
            Log.w(TAG, "AudioSpectrumEngine unavailable: " + t);
            visualizer = null;
        }
    }

    public synchronized void stop() {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (Throwable t) {
            Log.w(TAG, "Error releasing AudioSpectrumEngine: " + t);
        } finally {
            visualizer = null;
        }
        lowRingFill = 0;
        lowBandsReady = false;
        lastLowFftTime = 0;
    }

    private void processFft(byte[] fft, int samplingRateMilliHz) {
        int n = fft.length;
        if (n < 4) return;
        int numBins = n / 2;
        float sampleRateHz = samplingRateMilliHz / 1000f;

        float[] bandSum = new float[AudioConfig.NUM_BANDS];
        int[] bandCount = new int[AudioConfig.NUM_BANDS];

        for (int bin = 0; bin <= numBins; bin++) {
            float re, im;
            if (bin == 0) {
                re = fft[0]; im = 0;
            } else if (bin == numBins) {
                re = fft[1]; im = 0;
            } else {
                re = fft[2 * bin]; im = fft[2 * bin + 1];
            }
            float magnitude = (float) Math.sqrt(re * re + im * im);

            float freqHz = bin * sampleRateHz / n;
            int band = bandForFrequency(freqHz);
            if (band >= 0) {
                bandSum[band] += magnitude;
                bandCount[band]++;
            }
        }

        if (lowBandsReady) {
            bandSum[0] = lowBandMagnitude[0] * LOW_BAND_MAGNITUDE_SCALE;
            bandCount[0] = 1;
            bandSum[1] = lowBandMagnitude[1] * LOW_BAND_MAGNITUDE_SCALE;
            bandCount[1] = 1;
        }

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            if (bandCount[i] > 0) continue;
            for (int distance = 1; distance < AudioConfig.NUM_BANDS; distance++) {
                int lo = i - distance, hi = i + distance;
                int source = -1;
                if (lo >= 0 && bandCount[lo] > 0) source = lo;
                else if (hi < AudioConfig.NUM_BANDS && bandCount[hi] > 0) source = hi;
                if (source >= 0) {
                    float attenuation = (float) Math.pow(0.65, distance);
                    bandSum[i] = (bandSum[source] / bandCount[source]) * attenuation;
                    bandCount[i] = 1;
                    break;
                }
            }
        }

        synchronized (gains) {
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                float avgMag = bandCount[i] > 0 ? bandSum[i] / bandCount[i] : 0f;
                float rawSignalDb = (float) (20 * Math.log10(avgMag + 1f));

                // Fade presence over SILENCE_FADE_DB above 0 dB
                float presence = rawSignalDb / SILENCE_FADE_DB;
                presence = Math.max(0f, Math.min(1f, presence));

                // Apply Pink Noise tilt calibration proportionally to presence to avoid noise floor lift on silence
                float calibratedDb = rawSignalDb + presence * PINK_NOISE_CALIBRATION_DB[i];

                // Dynamic ballistics (fast attack, smooth release)
                float prevContentDb = smoothedContentDb[i];
                float contentSmoothing = calibratedDb > prevContentDb ? RISE_SMOOTHING : FALL_SMOOTHING;
                smoothedContentDb[i] = prevContentDb + (calibratedDb - prevContentDb) * contentSmoothing;

                // Slider gain modulation & normalization
                float db = smoothedContentDb[i] + presence * (gains[i] - 6) * 2f * BAND_MULT;
                db -= ATTENUATION_DB;
                float normalized = (db - REF_MIN_DB) / (REF_MAX_DB - REF_MIN_DB);
                rawLevels[i] = Math.max(0f, Math.min(1f, normalized));
            }
        }

        long now = System.currentTimeMillis();
        if (lastCaptureTime != 0) {
            long observed = now - lastCaptureTime;
            if (observed > 0) captureIntervalMs = observed;
        }
        System.arraycopy(displayLevels, 0, prevLevels, 0, AudioConfig.NUM_BANDS);
        lastCaptureTime = now;
        System.arraycopy(rawLevels, 0, displayLevels, 0, AudioConfig.NUM_BANDS);

        for (OnSpectrumDataListener l : listeners) {
            try {
                l.onSpectrumCapture(displayLevels, prevLevels, lastCaptureTime, captureIntervalMs);
            } catch (Throwable ignored) {}
        }
    }

    private void processWaveform(byte[] waveform, int samplingRateMilliHz) {
        int len = waveform.length;
        if (len == 0) return;

        if (len >= LOW_FFT_SIZE) {
            for (int i = 0; i < LOW_FFT_SIZE; i++) {
                int b = waveform[len - LOW_FFT_SIZE + i] & 0xFF;
                lowRingBuffer[i] = (b - 128) / 128f;
            }
            lowRingFill = LOW_FFT_SIZE;
        } else {
            System.arraycopy(lowRingBuffer, len, lowRingBuffer, 0, LOW_FFT_SIZE - len);
            for (int i = 0; i < len; i++) {
                int b = waveform[i] & 0xFF;
                lowRingBuffer[LOW_FFT_SIZE - len + i] = (b - 128) / 128f;
            }
            lowRingFill = Math.min(LOW_FFT_SIZE, lowRingFill + len);
        }

        if (lowRingFill < LOW_FFT_SIZE) return;

        long now = System.currentTimeMillis();
        if (now - lastLowFftTime < LOW_FFT_MIN_INTERVAL_MS) return;
        lastLowFftTime = now;

        computeLowBandMagnitudes(samplingRateMilliHz / 1000f);
    }

    private void computeLowBandMagnitudes(float sampleRateHz) {
        for (int i = 0; i < LOW_FFT_SIZE; i++) {
            float w = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / (LOW_FFT_SIZE - 1));
            fftScratchRe[i] = lowRingBuffer[i] * w;
            fftScratchIm[i] = 0f;
        }
        fft(fftScratchRe, fftScratchIm);

        float band0Sum = 0f; int band0Count = 0;
        float band1Sum = 0f; int band1Count = 0;
        int half = LOW_FFT_SIZE / 2;
        for (int bin = 1; bin < half; bin++) {
            float freqHz = bin * sampleRateHz / LOW_FFT_SIZE;
            if (freqHz >= BAND_EDGES_HZ[2]) break;
            float magnitude = (float) Math.sqrt(fftScratchRe[bin] * fftScratchRe[bin] + fftScratchIm[bin] * fftScratchIm[bin]);
            if (freqHz >= BAND_EDGES_HZ[0] && freqHz < BAND_EDGES_HZ[1]) {
                band0Sum += magnitude; band0Count++;
            } else if (freqHz >= BAND_EDGES_HZ[1]) {
                band1Sum += magnitude; band1Count++;
            }
        }

        lowBandMagnitude[0] = band0Count > 0 ? band0Sum / band0Count : 0f;
        lowBandMagnitude[1] = band1Count > 0 ? band1Sum / band1Count : 0f;
        lowBandsReady = true;
    }

    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i]; re[i] = re[j]; re[j] = tr;
                float ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angStep = -2 * Math.PI / len;
            float wr = (float) Math.cos(angStep);
            float wi = (float) Math.sin(angStep);
            for (int start = 0; start < n; start += len) {
                float curWr = 1f, curWi = 0f;
                int half = len / 2;
                for (int k = 0; k < half; k++) {
                    int a = start + k;
                    int b = start + k + half;
                    float tr = re[b] * curWr - im[b] * curWi;
                    float ti = re[b] * curWi + im[b] * curWr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    float nextWr = curWr * wr - curWi * wi;
                    float nextWi = curWr * wi + curWi * wr;
                    curWr = nextWr; curWi = nextWi;
                }
            }
        }
    }

    private int bandForFrequency(float freqHz) {
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            if (freqHz >= BAND_EDGES_HZ[i] && freqHz < BAND_EDGES_HZ[i + 1]) return i;
        }
        return -1;
    }
}
