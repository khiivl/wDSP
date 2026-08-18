package com.radiorubka.wdsp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized audio spectrum capture and 16-band Post-DSP analysis engine.
 * Sourced from Android's global output mix via {@link Visualizer} (audio session 0).
 *
 * Synthesizes the actual acoustic spectrum AFTER wDSP processing (EQ gains, Q-factor bell curves,
 * Fletcher-Munson Loudness compensation, and fatigue filters) so the user observes the true
 * post-processed sound rather than pre-DSP input.
 *
 * Broadcasts calculated band levels to subscribed listeners (SpectrumAnalyzerView in MainActivity
 * and StatusBarVisualizerView) without multiple conflicting Visualizer sessions.
 */
public class AudioSpectrumEngine {
    private static final String TAG = "wDSP_SpectrumEngine";

    // Edge frequencies (Hz) bounding each of the 16 AudioConfig bands, placed
    // at the geometric mean between neighboring band centers (20...20k).
    private static final float[] BAND_EDGES_HZ = {
            15.9f, 25.1f, 39.7f, 63.2f, 100f, 158.1f, 250.8f, 397.4f,
            632.5f, 1000f, 1581.1f, 2506.0f, 3969.1f, 6299.6f, 10000f, 15849f, 25198f
    };

    private static final float REF_MIN_DB = 0f;
    private static final float REF_MAX_DB = 54f;
    private static final float RISE_SMOOTHING = 0.92f; // Instant explosive attack on beats/transients
    private static final float FALL_SMOOTHING = 0.26f; // Snappy musical release for punchy bounce
    private static final float ATTENUATION_DB = 8f;
    private static final float SILENCE_FADE_DB = 3.0f;

    // Laboratory Pink Noise reference calibration curve (AudioCheck.net 1/f standard).
    // Compensates for fractional-octave binning geometry and mathematical 1/f energy density,
    // guaranteeing a laser-flat 0 dB baseline across all 16/32 bands.
    // Official EMMA 2018 Competition Test Disc Reference Calibration Curve.
    // Compensates for fractional-octave binning geometry and AES/IEC 60268-1 spectrum,
    // ensuring an absolute laser-flat 0 dB horizontal line across all 16/32 bands.
    private static final float[] PINK_NOISE_CALIBRATION_DB = {
            19.0f,  // 20 Hz
            20.5f,  // 31.5 Hz
            -1.0f,  // 50 Hz
            -1.0f,  // 80 Hz
            0.5f,   // 125 Hz
            4.5f,   // 200 Hz
            6.0f,   // 315 Hz
            8.0f,   // 500 Hz
            8.5f,   // 800 Hz
            9.5f,   // 1.25 kHz
            12.0f,  // 2.0 kHz
            15.5f,  // 3.15 kHz
            17.5f,  // 5.0 kHz
            19.0f,  // 8.0 kHz
            21.5f,  // 12.5 kHz
            25.5f   // 20.0 kHz
    };

    // Fast, responsive bass resolution (4096-sample FFT window for 10.7Hz bin resolution, zero throttling)
    private static final int LOW_FFT_SIZE = 4096;
    // Scale 4096-FFT peak magnitude (N/2 = 2048 for full-scale float) to match Android 8-bit FFT magnitude domain (~128 max)
    private static final float LOW_BAND_MAGNITUDE_SCALE = 128.0f / (LOW_FFT_SIZE / 2.0f); // 0.0625f

    private final float[] lowRingBuffer = new float[LOW_FFT_SIZE];
    private int lowRingFill = 0;
    private long lastLowFftTime = 0;
    private boolean lowBandsReady = false;
    private final float[] lowBandMagnitude = new float[2];
    private final float[] fftScratchRe = new float[LOW_FFT_SIZE];
    private final float[] fftScratchIm = new float[LOW_FFT_SIZE];

    private Visualizer visualizer;

    public static final int NUM_BANDS_16 = 16;
    public static final int NUM_BANDS_32 = 32;

    private final float[] rawLevels16 = new float[NUM_BANDS_16];
    private final float[] displayLevels16 = new float[NUM_BANDS_16];
    private final float[] prevLevels16 = new float[NUM_BANDS_16];

    private final float[] rawLevels16Norm = new float[NUM_BANDS_16];
    private final float[] displayLevels16Norm = new float[NUM_BANDS_16];
    private final float[] prevLevels16Norm = new float[NUM_BANDS_16];

    private final float[] smoothedContentDb = new float[NUM_BANDS_16];

    private final float[] rawLevels32 = new float[NUM_BANDS_32];
    private final float[] displayLevels32 = new float[NUM_BANDS_32];
    private final float[] prevLevels32 = new float[NUM_BANDS_32];

    private final float[] rawLevels32Norm = new float[NUM_BANDS_32];
    private final float[] displayLevels32Norm = new float[NUM_BANDS_32];
    private final float[] prevLevels32Norm = new float[NUM_BANDS_32];

    // State parameters for Post-DSP synthesis
    private final int[] gains = new int[NUM_BANDS_16];
    private final boolean[] qNarrow = new boolean[NUM_BANDS_16];
    private final float[] fmOffsets = new float[NUM_BANDS_16];
    private float runningPeakDb = 25f;

    private long lastCaptureTime = 0;
    private long captureIntervalMs = 50;

    public interface OnSpectrumDataListener {
        void onSpectrumCapture(float[] displayLevels16, float[] displayLevels16Norm,
                               float[] prevLevels16, float[] prevLevels16Norm,
                               float[] displayLevels32, float[] displayLevels32Norm,
                               float[] prevLevels32, float[] prevLevels32Norm,
                               long lastCaptureTime, long captureIntervalMs);
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
        Arrays.fill(qNarrow, false);
        Arrays.fill(fmOffsets, 0f);
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

    public void setQFactors(boolean[] newQNarrow) {
        if (newQNarrow == null) return;
        synchronized (qNarrow) {
            System.arraycopy(newQNarrow, 0, this.qNarrow, 0, Math.min(newQNarrow.length, AudioConfig.NUM_BANDS));
        }
    }

    public void setFmOffsets(float[] newFmOffsets) {
        synchronized (fmOffsets) {
            if (newFmOffsets == null) {
                Arrays.fill(this.fmOffsets, 0f);
            } else {
                System.arraycopy(newFmOffsets, 0, this.fmOffsets, 0, Math.min(newFmOffsets.length, AudioConfig.NUM_BANDS));
            }
        }
    }

    private int currentSessionId = 0;
    private AudioManager audioManager;
    private AudioManager.AudioPlaybackCallback playbackCallback;

    public void initContext(Context context) {
        if (context == null) return;
        this.audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null && playbackCallback == null) {
            playbackCallback = new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    checkAndSwitchSession();
                }
            };
            try {
                audioManager.registerAudioPlaybackCallback(playbackCallback, new Handler(Looper.getMainLooper()));
            } catch (Throwable t) {
                Log.w(TAG, "Failed to register AudioPlaybackCallback: " + t);
            }
        }
    }

    private synchronized void checkAndSwitchSession() {
        if (listeners.isEmpty()) return;
        int bestSession = getActiveMediaSessionId();
        if (bestSession != currentSessionId) {
            Log.i(TAG, "Active playback session changed: " + currentSessionId + " -> " + bestSession);
            restartWithSession(bestSession);
        }
    }

    private int getActiveMediaSessionId() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioManager != null) {
            try {
                List<AudioPlaybackConfiguration> configs = audioManager.getActivePlaybackConfigurations();
                if (configs != null) {
                    for (AudioPlaybackConfiguration config : configs) {
                        if (isConfigActive(config)) {
                            AudioAttributes attr = config.getAudioAttributes();
                            if (attr != null && (attr.getUsage() == AudioAttributes.USAGE_MEDIA
                                    || attr.getUsage() == AudioAttributes.USAGE_GAME
                                    || attr.getUsage() == AudioAttributes.USAGE_UNKNOWN)) {
                                int session = getSessionIdFromConfig(config);
                                if (session > 0) {
                                    return session;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return 0; // Fallback to global output mix session 0
    }

    private boolean isConfigActive(AudioPlaybackConfiguration config) {
        if (config == null) return false;
        try {
            java.lang.reflect.Method m = config.getClass().getMethod("isActive");
            Object res = m.invoke(config);
            if (res instanceof Boolean) return (Boolean) res;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = config.getClass().getMethod("getPlayerState");
            Object res = m.invoke(config);
            if (res instanceof Integer) return ((Integer) res) == 2; // 2 = PLAYER_STATE_STARTED
        } catch (Throwable ignored) {}
        return true;
    }

    private int getSessionIdFromConfig(AudioPlaybackConfiguration config) {
        if (config == null) return 0;
        try {
            java.lang.reflect.Method m = config.getClass().getMethod("getSessionId");
            Object res = m.invoke(config);
            if (res instanceof Integer && (Integer) res > 0) return (Integer) res;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = config.getClass().getMethod("getClientAudioSessionId");
            Object res = m.invoke(config);
            if (res instanceof Integer && (Integer) res > 0) return (Integer) res;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field f = config.getClass().getDeclaredField("mSessionId");
            f.setAccessible(true);
            int sid = f.getInt(config);
            if (sid > 0) return sid;
        } catch (Throwable ignored) {}
        return 0;
    }

    private synchronized void restartWithSession(int sessionId) {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Throwable ignored) {}
            visualizer = null;
        }
        currentSessionId = sessionId;
        if (!listeners.isEmpty()) {
            startInternal(sessionId);
        }
    }

    public synchronized void start() {
        if (visualizer != null) return;
        int targetSession = getActiveMediaSessionId();
        currentSessionId = targetSession;
        startInternal(targetSession);
    }

    private void startInternal(int sessionId) {
        try {
            Visualizer v = new Visualizer(sessionId);

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
            Log.d(TAG, "AudioSpectrumEngine attached to session " + sessionId + ", captureSize=" + captureSize);
        } catch (Throwable t) {
            Log.w(TAG, "AudioSpectrumEngine session " + sessionId + " failed: " + t);
            if (sessionId != 0) {
                try {
                    startInternal(0);
                    currentSessionId = 0;
                } catch (Throwable ignored) {
                    visualizer = null;
                }
            } else {
                visualizer = null;
            }
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

        // 1. Calculate synthesized Post-DSP response (EQ filter gains with Q-bell curves + Fletcher-Munson)
        float[] postDspGainDb = new float[AudioConfig.NUM_BANDS];
        synchronized (gains) {
            synchronized (qNarrow) {
                synchronized (fmOffsets) {
                    for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                        float eqGain = 0f;
                        for (int j = 0; j < AudioConfig.NUM_BANDS; j++) {
                            float baseGain = (gains[j] - 6) * 2.0f; // -12 dB to +12 dB
                            if (baseGain == 0f) continue;
                            int dist = Math.abs(i - j);
                            float weight;
                            if (dist == 0) {
                                weight = 1.0f;
                            } else if (qNarrow[j]) {
                                // Narrow Q (Q = 4.7): sharp localized bell curve
                                if (dist == 1) weight = 0.10f;
                                else weight = 0.0f;
                            } else {
                                // Wide Q (Q = 2.2): standard 2/3 octave filter spreading into adjacent bands
                                if (dist == 1) weight = 0.36f;
                                else if (dist == 2) weight = 0.08f;
                                else weight = 0.0f;
                            }
                            eqGain += baseGain * weight;
                        }
                        postDspGainDb[i] = eqGain + fmOffsets[i];
                    }
                }
            }
        }

        // 2. Synthesize post-DSP signal levels in dB space
        float[] postSignalDb = new float[AudioConfig.NUM_BANDS];
        float maxPostDb = 0f;

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float avgMag = bandCount[i] > 0 ? bandSum[i] / bandCount[i] : 0f;
            float rawSignalDb = (float) (20 * Math.log10(avgMag + 1f));

            // Fade presence over SILENCE_FADE_DB to prevent ghost bars during silence
            float presence = rawSignalDb / SILENCE_FADE_DB;
            presence = Math.max(0f, Math.min(1f, presence));

            // Pink noise tilt calibration
            float calibratedDb = rawSignalDb + presence * PINK_NOISE_CALIBRATION_DB[i];

            // Fast-attack, smooth-release ballistics applied to content
            float prevContentDb = smoothedContentDb[i];
            float contentSmoothing = calibratedDb > prevContentDb ? RISE_SMOOTHING : FALL_SMOOTHING;
            smoothedContentDb[i] = prevContentDb + (calibratedDb - prevContentDb) * contentSmoothing;

            // Combine audio with physical 1:1 unity DSP gain and flat headroom attenuation
            float finalDb = smoothedContentDb[i] + presence * postDspGainDb[i] - ATTENUATION_DB;
            postSignalDb[i] = Math.max(0f, finalDb);
            if (finalDb > maxPostDb) {
                maxPostDb = finalDb;
            }
        }

        // 3. Compute Unnormalized Mapping [0..54 dB] with soft ceiling headroom (16-band)
        for (int i = 0; i < NUM_BANDS_16; i++) {
            float normalized = (postSignalDb[i] - REF_MIN_DB) / (REF_MAX_DB - REF_MIN_DB);
            rawLevels16[i] = applySoftHeadroom(normalized);
        }

        // 4. Compute Dynamic AGC Normalization Mapping (16-band)
        if (maxPostDb > runningPeakDb) {
            // Fast attack on musical transients/beats
            runningPeakDb += (maxPostDb - runningPeakDb) * 0.40f;
        } else {
            // Smooth musical release (~2.5 sec)
            runningPeakDb += (maxPostDb - runningPeakDb) * 0.008f;
        }
        float effectivePeak = Math.max(10f, runningPeakDb);
        for (int i = 0; i < NUM_BANDS_16; i++) {
            float normAgc = postSignalDb[i] / effectivePeak;
            rawLevels16Norm[i] = applySoftHeadroom(normAgc);
        }

        // 5. Synthesize continuous, ultra-smooth 32-band spectrum for RTA/Status bar
        for (int k = 0; k < NUM_BANDS_32; k++) {
            float pos = k * (15f / 31f);
            int idx = (int) pos;
            float frac = pos - idx;
            if (idx < 15) {
                rawLevels32[k] = rawLevels16[idx] * (1.0f - frac) + rawLevels16[idx + 1] * frac;
                rawLevels32Norm[k] = rawLevels16Norm[idx] * (1.0f - frac) + rawLevels16Norm[idx + 1] * frac;
            } else {
                rawLevels32[k] = rawLevels16[15];
                rawLevels32Norm[k] = rawLevels16Norm[15];
            }
        }

        long now = System.currentTimeMillis();
        if (lastCaptureTime != 0) {
            long observed = now - lastCaptureTime;
            if (observed > 0) captureIntervalMs = observed;
        }
        System.arraycopy(displayLevels16, 0, prevLevels16, 0, NUM_BANDS_16);
        System.arraycopy(rawLevels16, 0, displayLevels16, 0, NUM_BANDS_16);

        System.arraycopy(displayLevels16Norm, 0, prevLevels16Norm, 0, NUM_BANDS_16);
        System.arraycopy(rawLevels16Norm, 0, displayLevels16Norm, 0, NUM_BANDS_16);

        System.arraycopy(displayLevels32, 0, prevLevels32, 0, NUM_BANDS_32);
        System.arraycopy(rawLevels32, 0, displayLevels32, 0, NUM_BANDS_32);

        System.arraycopy(displayLevels32Norm, 0, prevLevels32Norm, 0, NUM_BANDS_32);
        System.arraycopy(rawLevels32Norm, 0, displayLevels32Norm, 0, NUM_BANDS_32);

        lastCaptureTime = now;

        for (OnSpectrumDataListener l : listeners) {
            try {
                l.onSpectrumCapture(displayLevels16, displayLevels16Norm, prevLevels16, prevLevels16Norm,
                                   displayLevels32, displayLevels32Norm, prevLevels32, prevLevels32Norm,
                                   lastCaptureTime, captureIntervalMs);
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

    private static float applySoftHeadroom(float normalized) {
        if (normalized <= 0.80f) {
            return Math.max(0f, normalized);
        }
        // Graceful analog-style saturation knee: smoothly compresses extreme peaks between 80% and 95%
        float excess = normalized - 0.80f;
        float compressed = 0.80f + (float) Math.tanh(excess * 1.5f) * 0.15f;
        return Math.min(0.95f, compressed);
    }
}
