package com.radiorubka.wdsp;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.audiofx.Visualizer;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

/**
 * Live 16-band spectrum drawn behind {@link EqVisualizerView}, sourced from
 * Android's global output mix via {@link Visualizer} (audio session 0).
 *
 * READ BEFORE TOUCHING THIS FILE - important caveats:
 *
 * 1. This captures audio BEFORE it leaves Android and reaches the MCU/amp
 *    that actually applies the real EQ (see McuService.sendToHardware()).
 *    It is a PRE-EQ signal - wDSP has no way to see the true, hardware-
 *    filtered output. The gain-reactive scaling in onDraw() is a synthetic
 *    visual effect (real captured level x the user's slider gain, converted
 *    to a linear amplitude multiplier), not a measurement of the filtered
 *    signal.
 * 2. Session-0 capture normally requires the signature-level
 *    CAPTURE_AUDIO_OUTPUT permission, which most ROMs restrict to system
 *    apps. Whether this head unit's ROM enforces that is UNTESTED - start()
 *    is wrapped so any failure just leaves the view blank instead of
 *    crashing the app. Check logcat for tag "wDSP_Spectrum" to see whether
 *    it actually attached.
 * 3. Audio sources that don't pass through Android's own mixer (e.g. Radio
 *    or AUX, if this head unit routes them straight to the amp in hardware)
 *    will never show up here - only whatever Android itself is mixing
 *    (media apps, Bluetooth A2DP, etc.) is visible to Visualizer.
 * 4. REF_MIN_DB / REF_MAX_DB below are rough starting guesses for mapping
 *    the raw FFT magnitude to a 0..1 bar height. They will very likely need
 *    tuning by eye once this runs on real hardware with real music.
 * 5. Bands 0 and 1 (20Hz/31.5Hz) get their own separate, slower analysis
 *    path (see processWaveform()/computeLowBandMagnitudes()) instead of
 *    coming from the same fast FFT capture as the other 14 bands. At the
 *    platform's capture-size ceiling (commonly 1024 samples), bin spacing is
 *    ~43Hz - coarser than the gap between those two band edges, so there is
 *    no bin that belongs to them. To resolve them independently we
 *    accumulate raw waveform samples across several capture callbacks into
 *    an 8192-sample sliding window (LOW_FFT_SIZE) and run our own windowed
 *    FFT over that, giving ~5Hz bin spacing at the cost of updating only a
 *    few times a second instead of ~20x/sec. That's the right trade for
 *    bass, which doesn't move fast anyway. Until that first ~186ms window
 *    fills (right after start()), those two bands fall back to borrowing
 *    from the nearest resolved neighbor so they aren't just dead at
 *    startup - see the fallback loop in processFft().
 */
public class SpectrumAnalyzerView extends View {

    private static final String TAG = "wDSP_Spectrum";

    // Same 6-group band coloring as EqVisualizerView/FmVisualizerView - kept
    // in sync per the project's band_color_scheme convention (low bass ->
    // upper treble, warm -> cool).
    private int[] groupColors;
    private final int[][] GROUP_RANGES = {{0, 2}, {3, 4}, {5, 6}, {7, 9}, {10, 12}, {13, 15}};

    // Edge frequencies (Hz) bounding each of the 16 AudioConfig bands, placed
    // at the geometric mean between neighboring band centers (20...20k).
    private static final float[] BAND_EDGES_HZ = {
            15.9f, 25.1f, 39.7f, 63.2f, 100f, 158.1f, 250.8f, 397.4f,
            632.5f, 1000f, 1581.1f, 2506.0f, 3969.1f, 6299.6f, 10000f, 15849f, 25198f
    };

    private static final float REF_MIN_DB = 0f;   // magnitude at/below this reads as silence
    private static final float REF_MAX_DB = 50f;  // magnitude at/above this reads as full-height
    private static final float RISE_SMOOTHING = 0.55f; // fast attack
    private static final float FALL_SMOOTHING = 0.12f; // slow release (classic VU-meter feel)
    // Both apply only to smoothedContentDb[] (real captured audio), not to
    // the gain/attenuation offset - see processFft() for why that split
    // exists.

    // Uniformly pulls every band down by this many dB before normalization,
    // independent of REF_MAX_DB. The difference: raising REF_MAX_DB also
    // widens the REF_MIN_DB..REF_MAX_DB window, which compresses/stretches
    // the whole visible dynamic range as a side effect. ATTENUATION_DB just
    // shifts everything down by a flat amount without touching that window's
    // size - use this when the bars are simply too loud/tall overall but the
    // existing compression (how much a given dB change moves the bar) looks
    // right as-is.
    private static final float ATTENUATION_DB = 10f;

    // How many dB above REF_MIN_DB a band's gain reactivity fades in over,
    // instead of switching on the instant rawDb ticks above REF_MIN_DB. A
    // hard on/off cutoff there flickers: the underlying magnitude is an 8-bit
    // integer scale, so a near-silent bin bounces between reading exactly 0
    // and reading 1 from one capture to the next just from quantization
    // noise, and each bounce would snap the gain shift fully on/off. With a
    // fade, that same noise only nudges the shift's strength by a hair
    // instead of popping it from 0% to 100%.
    private static final float SILENCE_FADE_DB = 3f;

    private static final float band_mult = 2f;
    // Same top-padding fractions as EqVisualizerView's TOP_OFFSET_RATIO/DRAW_HEIGHT_RATIO,
    // so bars are bounded to the same plot area as the curve/grid and can never grow up
    // into the frequency-label row above it.
    private static final float TOP_OFFSET_RATIO = 0.25555555555555f;
    private static final float DRAW_HEIGHT_RATIO = 0.72222222222222f;

    // --- Slow, high-resolution path for bands 0 (20Hz) and 1 (31.5Hz) only ---
    // See point 5 of the class javadoc for why this exists. LOW_FFT_SIZE must
    // be a power of two (the FFT below is radix-2). 8192 samples @ ~44.1kHz
    // is ~186ms - too slow for the upper bands' snappy response, exactly
    // right for bass.
    private static final int LOW_FFT_SIZE = 8192;
    private static final long LOW_FFT_MIN_INTERVAL_MS = 150; // throttle: don't recompute faster than this
    // The built-in FFT capture (bands 2-15) reports raw magnitude on an
    // unwindowed, unnormalized ~8-bit scale straight from the platform's
    // native Visualizer effect, whose exact internal scaling we can't
    // inspect. Our own FFT runs on a Hann-windowed, [-1,1]-normalized, much
    // longer (8x) buffer, so its raw magnitude lands on a very different
    // scale. This constant is a rough empirical correction (undo the [-1,1]
    // normalization, i.e. back to an ~8-bit scale) so bands 0/1 read at
    // roughly the same visual height as their neighbors - it is a guess and
    // will likely need tuning by eye once seen against bands 2+ on real
    // hardware.
    private static final float LOW_BAND_MAGNITUDE_SCALE = 0.7f;

    private final float[] lowRingBuffer = new float[LOW_FFT_SIZE]; // sliding window of recent PCM samples, oldest first
    private int lowRingFill = 0;       // how many valid samples are currently in the ring (caps at LOW_FFT_SIZE)
    private long lastLowFftTime = 0;
    private boolean lowBandsReady = false;
    private final float[] lowBandMagnitude = new float[2]; // [0]=20Hz band, [1]=31.5Hz band
    private final float[] fftScratchRe = new float[LOW_FFT_SIZE];
    private final float[] fftScratchIm = new float[LOW_FFT_SIZE];

    private Visualizer visualizer;

    private final float[] rawLevels = new float[AudioConfig.NUM_BANDS];     // this capture's final level (content ballistics + instant gain/attenuation), 0..1
    private final float[] displayLevels = new float[AudioConfig.NUM_BANDS]; // == rawLevels as of the last audio capture; kept separate only for the prevLevels/frameCallback interpolation below
    private final int[] gains = new int[AudioConfig.NUM_BANDS];             // raw slider values, 0..12 (6 = 0dB)
    // Fast-attack/slow-release ballistics live HERE now, applied to the raw
    // captured dB per band before gain/attenuation are added - see the
    // ballistics comment inside processFft() for why they moved off the
    // final (gain-inclusive) value.
    private final float[] smoothedContentDb = new float[AudioConfig.NUM_BANDS];

    // --- 60fps render interpolation ---
    // Audio captures only arrive at ~20Hz (or whatever the device reports),
    // so onDraw() would otherwise visibly step between values instead of
    // animating smoothly. A Choreographer loop redraws every vsync and
    // interpolates between the last two captured value-sets based on how far
    // through the current capture interval we are, independent of the audio
    // capture rate.
    private final float[] prevLevels = new float[AudioConfig.NUM_BANDS];   // displayLevels as of the previous capture
    private final float[] renderLevels = new float[AudioConfig.NUM_BANDS]; // interpolated values onDraw() actually reads
    private long lastCaptureTime = 0;
    private long captureIntervalMs = 50; // running estimate of the gap between captures, self-adjusts in processFft()
    private boolean frameCallbackActive = false;
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!frameCallbackActive) return;
            long elapsed = System.currentTimeMillis() - lastCaptureTime;
            float t = captureIntervalMs > 0 ? Math.min(1f, elapsed / (float) captureIntervalMs) : 1f;
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
                renderLevels[i] = prevLevels[i] + (displayLevels[i] - prevLevels[i]) * t;
            }
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private Drawable customBackground;
    private final Path bgPath = new Path();

    private Paint barPaint;
    private final RectF barRect = new RectF();

    public SpectrumAnalyzerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Same rounded-corner panel background as EqVisualizerView, so this
        // view can sit behind it as the bottom-most layer without a visible
        // seam between the two.
        customBackground = ContextCompat.getDrawable(getContext(), R.drawable.ui_bg_layer);

        groupColors = new int[]{
                ContextCompat.getColor(getContext(), R.color.btn_delete_bg),
                ContextCompat.getColor(getContext(), R.color.btn_import_bg),
                ContextCompat.getColor(getContext(), R.color.btn_export_bg),
                ContextCompat.getColor(getContext(), R.color.btn_rename_bg),
                ContextCompat.getColor(getContext(), R.color.btn_add_bg),
                ContextCompat.getColor(getContext(), R.color.btn_auto_bg)
        };

        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) gains[i] = 6; // default: 0 dB on every band
    }

    /** Feed the current 16 band gain slider values (0..12, 6 = 0dB) in for the visual gain scaling. */
    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
        // No invalidate() here on purpose - the Choreographer frame callback
        // (see frameCallback field) already redraws every vsync while
        // running; no need to force an extra one on every slider drag tick.
    }

    /**
     * Attempts to attach to the global output mix (session 0) and start FFT
     * capture. Safe to call repeatedly (no-ops if already running). Any
     * failure (missing permission, ROM restriction, no active session, etc.)
     * is caught and logged - the view simply stays blank rather than taking
     * the app down with it. Call from onResume() once RECORD_AUDIO is granted.
     */
    public void start() {
        if (visualizer != null) return;
        try {
            Visualizer v = new Visualizer(0); // 0 = global output mix, not this app's own (nonexistent) session

            int[] range = Visualizer.getCaptureSizeRange();
            int captureSize = range[1]; // use the device max for the best low-frequency bin resolution
            if (captureSize < range[0]) captureSize = range[0];
            v.setCaptureSize(captureSize);

            int rate = Visualizer.getMaxCaptureRate();
            if (rate <= 0) rate = 20000; // fallback: 20Hz, in milliHertz as the API expects

            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    // Feeds the slow, high-resolution bass-only path (bands 0/1) - see processWaveform().
                    processWaveform(waveform, samplingRate);
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    processFft(fft, samplingRate);
                }
            }, rate, true, true);

            v.setEnabled(true);
            visualizer = v;
            Log.d(TAG, "Spectrum analyzer attached to session 0, captureSize=" + captureSize);

            if (!frameCallbackActive) {
                frameCallbackActive = true;
                Choreographer.getInstance().postFrameCallback(frameCallback);
            }
        } catch (Throwable t) {
            // Deliberately broad: this talks to an untested vendor ROM's audio
            // stack, so anything from SecurityException (CAPTURE_AUDIO_OUTPUT
            // denied) to a native UnsatisfiedLinkError is possible here.
            Log.w(TAG, "Spectrum analyzer unavailable: " + t);
            visualizer = null;
        }
    }

    /** Stops capture and releases the Visualizer. Call from onPause()/onDestroy(). */
    public void stop() {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (Throwable t) {
            Log.w(TAG, "Error releasing spectrum analyzer: " + t);
        } finally {
            visualizer = null;
        }
        // Reset the slow bass path so a later restart re-fills its window
        // from fresh audio instead of analyzing stale samples.
        lowRingFill = 0;
        lowBandsReady = false;
        lastLowFftTime = 0;

        frameCallbackActive = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private void processFft(byte[] fft, int samplingRateMilliHz) {
        int n = fft.length; // capture size; n/2 complex bins are packed into these n bytes
        if (n < 4) return;
        int numBins = n / 2;
        float sampleRateHz = samplingRateMilliHz / 1000f;

        float[] bandSum = new float[AudioConfig.NUM_BANDS];
        int[] bandCount = new int[AudioConfig.NUM_BANDS];

        for (int bin = 0; bin <= numBins; bin++) {
            float re, im;
            if (bin == 0) {
                re = fft[0]; im = 0; // DC
            } else if (bin == numBins) {
                re = fft[1]; im = 0; // Nyquist
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

        // Bands 0/1 (20Hz/31.5Hz) have their own slower, higher-resolution
        // source - use it once the first window has filled (see class
        // javadoc point 5 and processWaveform()/computeLowBandMagnitudes()).
        if (lowBandsReady) {
            bandSum[0] = lowBandMagnitude[0] * LOW_BAND_MAGNITUDE_SCALE;
            bandCount[0] = 1;
            bandSum[1] = lowBandMagnitude[1] * LOW_BAND_MAGNITUDE_SCALE;
            bandCount[1] = 1;
        }

        // Any band still with no bin/data at this point - in practice just
        // bands 0/1 during the brief startup window before lowBandsReady
        // flips true - borrows the nearest resolved neighbor's average
        // magnitude, attenuated by distance, so they still move with the
        // beat instead of staying dead. This is a display fallback, not real
        // independent low-band resolution.
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

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float avgMag = bandCount[i] > 0 ? bandSum[i] / bandCount[i] : 0f;
            float rawDb = (float) (20 * Math.log10(avgMag + 1f));

            // Fast attack, slow release - classic spectrum analyzer
            // ballistics - applied ONLY to the real captured content, in
            // dB-space, before gain/attenuation are added below. This used
            // to be applied to the final gain-inclusive value instead, which
            // meant every gain/attenuation change had to "catch up" through
            // the same slow-release ballistics as real audio - since content
            // naturally dips and recovers every capture, that made the
            // *entire* graph look like it was bouncing/settling for a moment
            // after every +/- press (most visible there because +/- moves
            // every band's gain at once). Gain and attenuation are UI
            // settings, not signal to smooth, so they're added after this
            // step and take effect on the very next capture instead.
            float prevContentDb = smoothedContentDb[i];
            float contentSmoothing = rawDb > prevContentDb ? RISE_SMOOTHING : FALL_SMOOTHING;
            smoothedContentDb[i] = prevContentDb + (rawDb - prevContentDb) * contentSmoothing;

            // A band with no/near-no real captured energy shouldn't have its
            // slider gain conjure a bar out of nothing - that misrepresents
            // what's actually playing. "presence" ramps 0..1 as rawDb rises
            // through the SILENCE_FADE_DB range just above REF_MIN_DB, so a
            // truly silent band (presence 0) ignores the gain shift entirely
            // while a band with genuine signal (presence 1) reacts fully -
            // see SILENCE_FADE_DB's declaration comment for why this is a
            // fade and not a hard cutoff. Based on the instantaneous rawDb,
            // not the smoothed content, so silence is detected promptly.
            float presence = (rawDb - REF_MIN_DB) / SILENCE_FADE_DB;
            presence = Math.max(0f, Math.min(1f, presence));
            // Gain-reactive shift applied here, in dB-space, BEFORE normalizing -
            // not as a multiplier on the already-clamped-to-[0,1] level (that was
            // the bug: multiplying a bounded value is wildly asymmetric depending
            // on how close it already sits to 0 or 1 - a boost near the ceiling
            // has nowhere to go, a cut near the floor barely moves). Adding a
            // fixed dB offset before normalizing shifts every band by the same
            // fraction of the REF_MIN_DB..REF_MAX_DB window regardless of where
            // it currently sits, so boost and cut feel symmetric. Scaled by
            // presence so silent bands don't react to it.
            float db = smoothedContentDb[i] + presence * (gains[i] - 6) * 2f * band_mult;
            // Flat attenuation applied last, still in dB-space and before
            // normalization - always applied, even to silent bands, since it
            // only ever pulls level down and can't conjure a bar out of
            // nothing the way the gain shift could - see ATTENUATION_DB's
            // declaration comment for why this is not the same knob as
            // REF_MAX_DB.
            db -= ATTENUATION_DB;
            float normalized = (db - REF_MIN_DB) / (REF_MAX_DB - REF_MIN_DB);
            rawLevels[i] = Math.max(0f, Math.min(1f, normalized));
        }

        // Snapshot the outgoing values as the interpolation start point, and
        // re-estimate the gap between captures, before overwriting
        // displayLevels[] with this capture's new levels - see frameCallback,
        // which animates renderLevels[] from prevLevels[] to displayLevels[]
        // over this interval instead of jumping on every capture. This is
        // pure sub-frame visual interpolation between two already-final
        // capture values now, not ballistics (that happened above, on
        // content only) - so displayLevels[] is just a direct copy of
        // rawLevels[], no further smoothing/lag here.
        long now = System.currentTimeMillis();
        if (lastCaptureTime != 0) {
            long observed = now - lastCaptureTime;
            if (observed > 0) captureIntervalMs = observed;
        }
        System.arraycopy(displayLevels, 0, prevLevels, 0, AudioConfig.NUM_BANDS);
        lastCaptureTime = now;
        System.arraycopy(rawLevels, 0, displayLevels, 0, AudioConfig.NUM_BANDS);
        // No invalidate() here - the Choreographer frame callback (see
        // frameCallback field) drives all redraws now, at display refresh
        // rate, interpolating toward this new target.
    }

    /**
     * Slides this callback's raw 8-bit unsigned PCM samples into an
     * {@link #LOW_FFT_SIZE}-sample window (a simple delay line - oldest
     * samples drop off the front) and, once full, periodically (throttled to
     * {@link #LOW_FFT_MIN_INTERVAL_MS}) runs our own FFT over it to resolve
     * bands 0/1 independently. See class javadoc point 5.
     */
    private void processWaveform(byte[] waveform, int samplingRateMilliHz) {
        int len = waveform.length;
        if (len == 0) return;

        if (len >= LOW_FFT_SIZE) {
            // Rare (would need a device capture size >= 8192), but just take the most recent samples.
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

        if (lowRingFill < LOW_FFT_SIZE) return; // window not full yet

        long now = System.currentTimeMillis();
        if (now - lastLowFftTime < LOW_FFT_MIN_INTERVAL_MS) return; // this path is intentionally slow
        lastLowFftTime = now;

        computeLowBandMagnitudes(samplingRateMilliHz / 1000f);
    }

    /** Windows the current low-band buffer, runs the FFT, and averages the bins that fall in bands 0/1. */
    private void computeLowBandMagnitudes(float sampleRateHz) {
        for (int i = 0; i < LOW_FFT_SIZE; i++) {
            // Hann window - reduces spectral leakage from analyzing a finite chunk of continuous audio.
            float w = 0.5f - 0.5f * (float) Math.cos(2 * Math.PI * i / (LOW_FFT_SIZE - 1));
            fftScratchRe[i] = lowRingBuffer[i] * w;
            fftScratchIm[i] = 0f;
        }
        fft(fftScratchRe, fftScratchIm);

        float band0Sum = 0f; int band0Count = 0;
        float band1Sum = 0f; int band1Count = 0;
        int half = LOW_FFT_SIZE / 2;
        for (int bin = 1; bin < half; bin++) { // skip DC (bin 0)
            float freqHz = bin * sampleRateHz / LOW_FFT_SIZE;
            if (freqHz >= BAND_EDGES_HZ[2]) break; // bins are in ascending frequency order past here
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

    /**
     * In-place iterative radix-2 Cooley-Tukey FFT (re/im must be equal length, a power of two).
     * Verified against a naive O(n^2) DFT and a known sine-tone bin peak before shipping.
     */
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

    private int groupForBand(int band) {
        for (int g = 0; g < GROUP_RANGES.length; g++) {
            if (band >= GROUP_RANGES[g][0] && band <= GROUP_RANGES[g][1]) return g;
        }
        return 0;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float totalH = getHeight();
        if (w == 0 || totalH == 0 || groupColors == null) return;

        float density = getResources().getDisplayMetrics().density;

        float topArea = totalH * TOP_OFFSET_RATIO;
        float drawHeight = totalH * DRAW_HEIGHT_RATIO;
        float gridBottom = topArea + drawHeight;

        float stepX = w / (float) AudioConfig.NUM_BANDS;
        float barGap = stepX * 0.18f;
        float barWidth = stepX - barGap;
        float barCornerRadius = getResources().getDimension(R.dimen.button_radius);

        // Same rounded-corner background geometry as EqVisualizerView
        // draws its own copy of this on top of us) - bgPadding/cornerRadius/
        // shiftUp values match exactly so the two layers line up seamlessly.
//        float bgPadding = 25 * density;
//        float cornerRadius = 15 * density;
//        int shiftUp = 6;
//        float bgLeft = 0.5f * stepX - bgPadding;
//        float bgRight = (AudioConfig.NUM_BANDS - 0.5f) * stepX + bgPadding;
//        float bgTop = -shiftUp;
//        float bgBottom = totalH - shiftUp;

        canvas.save();
//        bgPath.reset();
//        bgPath.addRoundRect(bgLeft, bgTop, bgRight, bgBottom, cornerRadius, cornerRadius, Path.Direction.CW);
//        canvas.clipPath(bgPath);

//        if (customBackground != null) {
//            customBackground.setBounds((int) bgLeft, (int) bgTop, (int) bgRight, (int) bgBottom);
//            customBackground.draw(canvas);
//        }

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            // Gain-reactive scaling now happens in processFft(), added in
            // dB-space before normalization (see the comment there) instead
            // of multiplied here after clamping - that's what fixed the
            // boost/cut asymmetry. renderLevels[i] already reflects it.
            float level = renderLevels[i];

            float barHeight = level * drawHeight * 1f;
            float left = i * stepX + barGap / 2f;
            float right = left + barWidth;
            float top = gridBottom - barHeight;

            int group = groupForBand(i);
            barPaint.setColor(groupColors[group]);
            barPaint.setAlpha(110);

            barRect.set(left, top, right, gridBottom);
            canvas.drawRoundRect(barRect, barCornerRadius, barCornerRadius, barPaint);
        }

        canvas.restore();

    }
}
