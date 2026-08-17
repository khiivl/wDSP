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
public class SpectrumAnalyzerView extends View implements AudioSpectrumEngine.OnSpectrumDataListener {

    private static final String TAG = "wDSP_Spectrum";

    // Same 6-group band coloring as EqVisualizerView/FmVisualizerView - kept
    // in sync per the project's band_color_scheme convention (low bass ->
    // upper treble, warm -> cool).
    private int[] groupColors;
    private final int[][] GROUP_RANGES = {{0, 2}, {3, 4}, {5, 6}, {7, 9}, {10, 12}, {13, 15}};

    private static final float TOP_OFFSET_RATIO = 0.25555555555555f;
    private static final float DRAW_HEIGHT_RATIO = 0.72222222222222f;

    private final float[] displayLevels = new float[AudioConfig.NUM_BANDS];
    private final int[] gains = new int[AudioConfig.NUM_BANDS];
    private final float[] prevLevels = new float[AudioConfig.NUM_BANDS];
    private final float[] renderLevels = new float[AudioConfig.NUM_BANDS];
    private long lastCaptureTime = 0;
    private long captureIntervalMs = 50;
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

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) gains[i] = 6;
    }

    /** Feed the current 16 band gain slider values (0..12, 6 = 0dB) in for the visual gain scaling. */
    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
        AudioSpectrumEngine.getInstance().setGains(newGains);
    }

    /**
     * Attaches to the shared AudioSpectrumEngine and starts 60fps frame callbacks.
     */
    public void start() {
        AudioSpectrumEngine.getInstance().setGains(this.gains);
        AudioSpectrumEngine.getInstance().registerListener(this);

        if (!frameCallbackActive) {
            frameCallbackActive = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    /** Stops capture listener and releases Choreographer callback. */
    public void stop() {
        AudioSpectrumEngine.getInstance().unregisterListener(this);
        frameCallbackActive = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            renderLevels[i] = 0f;
            displayLevels[i] = 0f;
            prevLevels[i] = 0f;
        }
        invalidate();
    }

    @Override
    public void onSpectrumCapture(float[] displayLevels, float[] prevLevels, long lastCaptureTime, long captureIntervalMs) {
        System.arraycopy(prevLevels, 0, this.prevLevels, 0, AudioConfig.NUM_BANDS);
        System.arraycopy(displayLevels, 0, this.displayLevels, 0, AudioConfig.NUM_BANDS);
        this.lastCaptureTime = lastCaptureTime;
        this.captureIntervalMs = captureIntervalMs;
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
