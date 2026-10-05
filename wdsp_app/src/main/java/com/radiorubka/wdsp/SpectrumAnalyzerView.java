package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

public class SpectrumAnalyzerView extends View implements AudioSpectrumEngine.OnSpectrumDataListener {

    private static final String TAG = "wDSP_Spectrum";

    public static final int MODE_SPECTRUM = 0;
    public static final int MODE_MONOCHROME = 1;

    /**
     * The shape of the main spectrum: the live curve (the author's 1.0 RTA model, {@link RtaCurve})
     * or the bars. Owner, 05.10.2026: a switch in the visualisation settings. App-wide, not per preset.
     */
    public static final String PREF_SHAPE = "pref_eq_visualizer_shape";
    public static final int SHAPE_CURVE = 0;
    public static final int SHAPE_BARS = 1;

    /** Reads the stored shape; anything but bars is the curve, the default. */
    public static int shapeOf(android.content.SharedPreferences prefs) {
        return prefs.getInt(PREF_SHAPE, SHAPE_CURVE) == SHAPE_BARS ? SHAPE_BARS : SHAPE_CURVE;
    }

    // 16-band base colors following the physical optical spectrum (700 nm Red -> 390 nm Violet)
    private static final int[] SPECTRUM_BASE_COLORS = {
            0xFFD50000, // 20 Hz   (700 nm - Deep Red)
            0xFFFF1744, // 31.5 Hz (680 nm - Bright Red)
            0xFFFF3D00, // 50 Hz   (650 nm - Red-Orange)
            0xFFFF6D00, // 80 Hz   (620 nm - Orange)
            0xFFFF9100, // 125 Hz  (600 nm - Amber-Orange)
            0xFFFFC400, // 200 Hz  (585 nm - Amber-Yellow)
            0xFFFFEA00, // 315 Hz  (570 nm - Yellow)
            0xFFAEEA00, // 500 Hz  (550 nm - Lime)
            0xFF00E676, // 800 Hz  (530 nm - Pure Green)
            0xFF00BFA5, // 1.25 kHz (510 nm - Teal / Spring Green)
            0xFF00E5FF, // 2 kHz   (490 nm - Cyan)
            0xFF00B0FF, // 3.15 kHz (475 nm - Sky Blue)
            0xFF2979FF, // 5 kHz   (460 nm - Pure Blue)
            0xFF3D5AFE, // 8 kHz   (440 nm - Deep Blue/Indigo)
            0xFF651FFF, // 12.5 kHz (420 nm - Violet)
            0xFF6200EA  // 20 kHz  (390 nm - Pure Deep Violet)
    };

    private static final float TOP_OFFSET_RATIO = 0.14f;
    private static final float DRAW_HEIGHT_RATIO = 0.78f;

    private final float[] displayLevels = new float[AudioConfig.NUM_BANDS];
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
            if (shapeOf(ThemeManager.prefs(getContext())) == SHAPE_CURVE) updateCurve(frameTimeNanos);
            invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private Paint barPaint;
    private final RectF barRect = new RectF();

    // The live curve. Its points sit on the equaliser's axis: band position + half a slot, as the
    // bars' centres do, so a point and the slider of its frequency are on one vertical.
    private static final float[] CURVE_SLOT = new float[RtaCurve.POINTS];
    static {
        for (int j = 0; j < CURVE_SLOT.length; j++) {
            CURVE_SLOT[j] = AudioConfig.bandPositionOf(NativeAnalyzer.curveHz(j)) + 0.5f;
        }
    }
    /** The author's ALPHA_RISE_SMOOTHING / ALPHA_FALL_SMOOTHING per 50 ms capture, as time constants. */
    private static final float ALPHA_RISE_TAU_MS = (float) (-50.0 / Math.log(1.0 - 0.4));
    private static final float ALPHA_FALL_TAU_MS = (float) (-50.0 / Math.log(1.0 - 0.08));
    private final RtaCurve rta = new RtaCurve();
    private final float[] curveContent = new float[RtaCurve.POINTS];
    private final float[] curveCorrection = new float[RtaCurve.POINTS];
    private float curveAlpha = 0f;
    private long lastCurveNanos = 0;
    private final Path curvePath = new Path();
    private final Path curveFillPath = new Path();
    private Paint curveLinePaint;
    private Paint curveFillPaint;
    // What the shaders were built for; rebuilt only when one of these changes.
    private float shaderW = -1f, shaderTop = -1f, shaderBottom = -1f, shaderLeft = -1f;
    private int shaderMode = -1, shaderAccent = 0;
    private boolean shaderClassic;

    public SpectrumAnalyzerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        barPaint.setStyle(Paint.Style.FILL);
        curveLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        curveLinePaint.setStyle(Paint.Style.STROKE);
        curveLinePaint.setStrokeCap(Paint.Cap.ROUND);
        curveLinePaint.setStrokeJoin(Paint.Join.ROUND);
        curveFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        curveFillPaint.setStyle(Paint.Style.FILL);
    }

    /**
     * One display frame of the live curve: the engine's measurement through {@link RtaCurve}, and the
     * fade the author gives it - in while there is sound, out in silence.
     */
    private void updateCurve(long frameTimeNanos) {
        AudioSpectrumEngine engine = AudioSpectrumEngine.getInstance();
        boolean sound = engine.readCurve(curveContent, curveCorrection)
                && rta.update(curveContent, curveCorrection, engine.getDisplayRangeDb(), false,
                frameTimeNanos);
        float dtMs = lastCurveNanos == 0 ? 0f : (frameTimeNanos - lastCurveNanos) / 1_000_000f;
        lastCurveNanos = frameTimeNanos;
        float target = sound ? 1f : 0f;
        float tau = target > curveAlpha ? ALPHA_RISE_TAU_MS : ALPHA_FALL_TAU_MS;
        curveAlpha += (target - curveAlpha) * (float) (1.0 - Math.exp(-Math.max(0f, dtMs) / tau));
    }

    public void setGains(int[] newGains) {
        // Gain handling delegated to AudioSpectrumEngine
    }

    public void start() {
        boolean enabled = ThemeManager.prefs(getContext()).getBoolean("pref_eq_visualizer_enabled", true);
        if (!enabled) return;
        AudioSpectrumEngine.getInstance().registerListener(this);
        AudioSpectrumEngine.getInstance().start();
        if (!frameCallbackActive) {
            frameCallbackActive = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    public void stop() {
        AudioSpectrumEngine.getInstance().unregisterListener(this);
        frameCallbackActive = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            renderLevels[i] = 0f;
            displayLevels[i] = 0f;
            prevLevels[i] = 0f;
        }
        rta.reset();
        curveAlpha = 0f;
        lastCurveNanos = 0;
        invalidate();
    }

    @Override
    public void onSpectrumCapture(float[] displayLevels16, float[] displayLevels16Norm,
                                   float[] prevLevels16, float[] prevLevels16Norm,
                                   float[] displayLevels32, float[] displayLevels32Norm,
                                   float[] prevLevels32, float[] prevLevels32Norm,
                                   long lastCaptureTime, long captureIntervalMs) {
        // Absolute levels only: the bars are drawn relative to their own average (onDraw), which
        // takes any gain common to all bands away, so there is no normalised variant to choose.
        System.arraycopy(prevLevels16, 0, this.prevLevels, 0, AudioConfig.NUM_BANDS);
        System.arraycopy(displayLevels16, 0, this.displayLevels, 0, AudioConfig.NUM_BANDS);
        this.lastCaptureTime = lastCaptureTime;
        this.captureIntervalMs = captureIntervalMs;
    }

    /** Below this a band is silent: it is not drawn and does not count toward the average. */
    private static final float SILENT_LEVEL = 0.005f;
    /** Fewer bands with sound than this and there is no average worth drawing against. */
    private static final int MIN_BANDS_FOR_AVERAGE = 3;
    /** The equaliser grid's range, ±12 dB, as EqVisualizerView draws it. */
    private static final float GRID_HALF_RANGE_DB = 12f;

    /**
     * 🔴 Owner, 15.09.2026 (option В): the bars are drawn on the equaliser's grid in the equaliser's
     * own decibels, relative to the frame's average. The top of a bar at "+4" means that band is 4 dB
     * above the average of the bands that have sound - the slider under it would go to −4 to level
     * it. Until then a bar's height was its absolute level over a 60 dB range, so the grid's "0" meant
     * −30 dBFS and one grid dB meant 2.5 dB of signal: a −23 dBFS test tone stood at "+2" and read as
     * two decibels of equaliser. The geometry is EqVisualizerView's, thumb inset included, so a bar
     * and a slider at the same number are at the same height.
     */
    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float totalH = getHeight();
        if (w == 0 || totalH == 0) return;

        float density = getResources().getDisplayMetrics().density;

        boolean enabled = ThemeManager.prefs(getContext()).getBoolean("pref_eq_visualizer_enabled", true);
        if (!enabled) return;

        int mode = ThemeManager.prefs(getContext()).getInt("pref_eq_visualizer_mode", MODE_SPECTRUM);

        boolean isClassic = ThemeManager.isClassic(getContext());
        float leftMargin = isClassic ? 0f : (32 * density);
        float activeWidth = w - leftMargin;

        float topOffsetRatio = isClassic ? 0.25555555555555f : TOP_OFFSET_RATIO;
        float drawHeightRatio = isClassic ? 0.72222222222222f : DRAW_HEIGHT_RATIO;
        float thumbInset = 10 * density;
        float drawStartY = totalH * topOffsetRatio + thumbInset;
        float drawHeight = totalH * drawHeightRatio - 2 * thumbInset;
        float gridBottom = drawStartY + drawHeight;

        if (shapeOf(ThemeManager.prefs(getContext())) == SHAPE_CURVE) {
            drawCurve(canvas, w, leftMargin, activeWidth / (float) AudioConfig.NUM_BANDS,
                    drawStartY, drawHeight, gridBottom, isClassic, mode, density);
            return;
        }

        // The average of the bands that have sound, in display-level units; a difference of levels
        // times the display range is a difference in decibels.
        float sum = 0f;
        int active = 0;
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            if (renderLevels[i] > SILENT_LEVEL) {
                sum += renderLevels[i];
                active++;
            }
        }
        if (active < MIN_BANDS_FOR_AVERAGE) return;
        final float average = sum / active;
        final float rangeDb = AudioSpectrumEngine.getInstance().getDisplayRangeDb();

        float stepX = activeWidth / (float) AudioConfig.NUM_BANDS;
        float barGap = stepX * 0.22f;
        float barWidth = stepX - barGap;
        // Capsule pill corner radius matching status bar visualizer
        float barCornerRadius = barWidth * 0.35f;

        int accent = ThemeManager.accent(getContext());

        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float level = renderLevels[i];
            if (level <= SILENT_LEVEL) continue; // Clean when idle

            float relativeDb = (level - average) * rangeDb;
            float clamped = Math.max(-GRID_HALF_RANGE_DB, Math.min(GRID_HALF_RANGE_DB, relativeDb));
            float barHeight = drawHeight * (0.5f + clamped / (2f * GRID_HALF_RANGE_DB));
            if (barHeight <= 0.5f) continue;
            float left = leftMargin + i * stepX + barGap / 2f;
            float right = left + barWidth;
            float top = gridBottom - barHeight;

            if (mode == MODE_MONOCHROME) {
                barPaint.setColor(accent);
                barPaint.setAlpha(70);
            } else {
                int c = SPECTRUM_BASE_COLORS[i];
                barPaint.setColor(c);
                barPaint.setAlpha(115);
            }

            barRect.set(left, top, right, gridBottom);
            canvas.drawRoundRect(barRect, barCornerRadius, barCornerRadius, barPaint);
        }
    }

    /**
     * The live curve on the equaliser's grid, in the equaliser's decibels relative to the average of
     * what has sound - the bars' reading. The look is the author's: a line coloured along the
     * frequency axis and a fill that fades towards the bottom; Classic uses his line weight.
     */
    private void drawCurve(Canvas canvas, float w, float leftMargin, float stepX, float drawStartY,
                           float drawHeight, float gridBottom, boolean isClassic, int mode,
                           float density) {
        int alpha = Math.round(255f * Math.max(0f, Math.min(1f, curveAlpha)));
        if (alpha <= 0) return;

        final float[] rel = rta.relativeDb();
        // A loud point may rise half a grid above the top, as in the author's view; clipped below.
        final float limit = GRID_HALF_RANGE_DB * 1.5f;
        curvePath.reset();
        float firstX = 0f, lastX = 0f;
        for (int j = 0; j < RtaCurve.POINTS; j++) {
            float x = leftMargin + CURVE_SLOT[j] * stepX;
            float db = Math.max(-limit, Math.min(limit, rel[j]));
            float y = gridBottom - drawHeight * (0.5f + db / (2f * GRID_HALF_RANGE_DB));
            if (j == 0) {
                curvePath.moveTo(x, y);
                firstX = x;
            } else {
                curvePath.lineTo(x, y);
            }
            lastX = x;
        }
        curveFillPath.set(curvePath);
        curveFillPath.lineTo(lastX, gridBottom);
        curveFillPath.lineTo(firstX, gridBottom);
        curveFillPath.close();

        int accent = ThemeManager.accent(getContext());
        if (w != shaderW || drawStartY != shaderTop || gridBottom != shaderBottom
                || leftMargin != shaderLeft || mode != shaderMode || accent != shaderAccent
                || isClassic != shaderClassic) {
            int[] colors;
            float[] positions;
            if (mode == MODE_MONOCHROME) {
                colors = new int[]{accent, accent};
                positions = null;
            } else {
                colors = SPECTRUM_BASE_COLORS.clone();
                positions = new float[colors.length];
                for (int i = 0; i < colors.length; i++) {
                    positions[i] = Math.max(0f, Math.min(1f, (leftMargin + (i + 0.5f) * stepX) / w));
                }
            }
            int fillAlpha = isClassic ? 110 : 80;
            int[] fillColors = new int[colors.length];
            for (int i = 0; i < colors.length; i++) {
                fillColors[i] = Color.argb(fillAlpha, Color.red(colors[i]), Color.green(colors[i]),
                        Color.blue(colors[i]));
            }
            curveLinePaint.setShader(new LinearGradient(0, 0, w, 0, colors, positions,
                    Shader.TileMode.CLAMP));
            Shader fillColor = new LinearGradient(0, 0, w, 0, fillColors, positions,
                    Shader.TileMode.CLAMP);
            Shader fade = new LinearGradient(0, drawStartY, 0, gridBottom, Color.BLACK,
                    Color.TRANSPARENT, Shader.TileMode.CLAMP);
            curveFillPaint.setShader(new ComposeShader(fillColor, fade, PorterDuff.Mode.DST_IN));
            curveLinePaint.setStrokeWidth((isClassic ? 2.5f : 2f) * density);
            shaderW = w;
            shaderTop = drawStartY;
            shaderBottom = gridBottom;
            shaderLeft = leftMargin;
            shaderMode = mode;
            shaderAccent = accent;
            shaderClassic = isClassic;
        }
        curveLinePaint.setAlpha(alpha * 200 / 255);
        curveFillPaint.setAlpha(alpha);

        canvas.save();
        canvas.clipRect(leftMargin, 0, w, gridBottom);
        canvas.drawPath(curveFillPath, curveFillPaint);
        canvas.drawPath(curvePath, curveLinePaint);
        canvas.restore();
    }
}
