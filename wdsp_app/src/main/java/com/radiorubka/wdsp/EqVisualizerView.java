package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

/**
 * The main screen's EQ curve. Our layout - the grid, the dB scale on the left, the theme's accent -
 * and, since 02.10.2026, the author's 0.5 curve inside it: the real composite of 16 overlapping
 * Q = 2.2 bells ({@link AudioConfig#compositeResponseDb}) with the front bass shelf baked in, plus
 * three overlays - the subwoofer's low-pass (dashed), the rear bass shelf where it differs from the
 * front (dash-dot), and what loudness is driving the EQ with right now (dotted, fading). All of it
 * eases toward new values frame by frame.
 *
 * <p>What was not taken from his view: the frequency-group boxes and their names above the plot and
 * the background layer. Our screen dropped them for want of vertical room (owner, 02.10.2026: «ми
 * міняли інтерфейс, прибрали лишнє і нам бракувало вертикального бюджету»), so everything here is
 * drawn inside the plot the sliders already span.
 */
public class EqVisualizerView extends View {
    private Paint linePaint;
    private Paint fillPaint;
    private Paint gridPaint;
    private Paint textPaint;
    private Paint subLinePaint;
    private Paint rearBassLinePaint;
    private Paint loudnessLinePaint;

    private final int[] gains = new int[AudioConfig.NUM_BANDS];
    private final float[] animatedGains = new float[AudioConfig.NUM_BANDS];
    private boolean gainAnimInitialized = false;

    // The subwoofer's low-pass; cutoff <= 0 means there is no subwoofer to draw.
    private float subCutoffHz = 0f, subGainDb = 0f;
    private float animatedSubCutoffHz = 0f, animatedSubGainDb = 0f;
    private boolean subAnimInitialized = false;

    // The doors' bass shelf (P2Bass) - front baked into the curve, rear drawn only where it differs.
    private float frontBassFilterHz, frontBassBoostFreqHz, frontBassBoostGainDb;
    private float rearBassFilterHz, rearBassBoostFreqHz, rearBassBoostGainDb;
    private float animFrontFilterHz, animFrontBoostHz, animFrontBoostDb;
    private float animRearFilterHz, animRearBoostHz, animRearBoostDb;
    private boolean bassAnimInitialized = false;
    private boolean rearBassDiffers = false;

    // What loudness drives the EQ with, as gain indices; fades in and out.
    private final float[] loudnessGains = new float[AudioConfig.NUM_BANDS];
    private final float[] animatedLoudnessGains = new float[AudioConfig.NUM_BANDS];
    private boolean loudnessActive = false;
    private boolean loudnessAnimInitialized = false;
    private float loudnessAlpha = 0f;

    private static final float ANIM_SMOOTHING = 0.25f;      // per-frame blend toward the target
    private static final float GAIN_EPS = 0.02f;            // gain units
    private static final float HZ_EPS = 0.5f;
    private static final float DB_EPS = 0.02f;
    private static final float LOUDNESS_ALPHA_STEP = 0.03f;
    private static final float REAR_VISIBLE_DB = 0.1f;      // below this rear traces the front curve
    private static final int CURVE_SAMPLES = 160;
    private boolean frameCallbackActive = false;

    private float[] xCoords;

    private final Path fullPath = new Path();
    private final Path fillPath = new Path();
    private final Path subPath = new Path();
    private final Path rearBassPath = new Path();
    private final Path loudnessPath = new Path();

    // 0.14f top offset for Q (0.07) + dB (0.07), 0.78f for seekBox
    private static final float TOP_OFFSET_RATIO = 0.14f;
    private static final float DRAW_HEIGHT_RATIO = 0.78f;

    private float thumbRadiusOffset;

    // dB scale labels matching 13 levels from 0 to 12 (0 is -12dB, 6 is 0dB, 12 is +12dB)
    private static final String[] DB_LABELS = {
            "-12", "-10", "-8", "-6", "-4", "-2", "0", "+2", "+4", "+6", "+8", "+10", "+12"
    };

    private final Choreographer.FrameCallback animCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            boolean moving = stepAnimation();
            invalidate();
            if (moving && frameCallbackActive) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                frameCallbackActive = false;
            }
        }
    };

    public EqVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        float density = getContext().getResources().getDisplayMetrics().density;
        thumbRadiusOffset = 10 * density;

        xCoords = new float[AudioConfig.NUM_BANDS];

        linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setStrokeWidth(2.8f * density);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(Color.parseColor("#18FFFFFF"));
        gridPaint.setStrokeWidth(1.2f * density);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(9.5f * density);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        try {
            textPaint.setTypeface(ResourcesCompat.getFont(getContext(), R.font.main_font));
        } catch (Exception ignored) {
        }

        // Three line styles that cannot be confused: dashed sub, dash-dot rear, dotted loudness.
        subLinePaint = overlayPaint(2f * density);
        subLinePaint.setPathEffect(new DashPathEffect(new float[]{10f * density, 6f * density}, 0f));
        rearBassLinePaint = overlayPaint(2f * density);
        rearBassLinePaint.setPathEffect(new DashPathEffect(
                new float[]{14f * density, 5f * density, 2f * density, 5f * density}, 0f));
        loudnessLinePaint = overlayPaint(4f * density);
        loudnessLinePaint.setPathEffect(new DashPathEffect(new float[]{0f, 7f * density}, 0f));
    }

    private static Paint overlayPaint(float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeWidth(width);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    public void setGains(int[] newGains) {
        System.arraycopy(newGains, 0, this.gains, 0, AudioConfig.NUM_BANDS);
        if (!gainAnimInitialized) {
            for (int i = 0; i < AudioConfig.NUM_BANDS; i++) animatedGains[i] = gains[i];
            gainAnimInitialized = true;
            invalidate();
        } else {
            startAnimIfNeeded();
        }
    }

    /**
     * What loudness drives the EQ with: gain indices 0..12 as the chip will get them (the sliders
     * and the curve pre-warped and rounded - LoudnessCurve.eqDriveDb / gainIndex). Drawn only while
     * active; it fades out rather than vanishing.
     */
    public void setLoudnessCorrection(float[] curveGains, boolean active) {
        loudnessActive = active;
        if (active) {
            System.arraycopy(curveGains, 0, loudnessGains, 0, AudioConfig.NUM_BANDS);
            if (!loudnessAnimInitialized) {
                System.arraycopy(curveGains, 0, animatedLoudnessGains, 0, AudioConfig.NUM_BANDS);
                loudnessAnimInitialized = true;
            }
        }
        startAnimIfNeeded();
    }

    /** The subwoofer's crossover (Hz, 0 = no subwoofer) and its total gain in dB. */
    public void setSubFilter(float cutoffHz, float gainDb) {
        if (cutoffHz == subCutoffHz && gainDb == subGainDb) return;
        subCutoffHz = cutoffHz;
        subGainDb = gainDb;
        if (!subAnimInitialized || cutoffHz <= 0f) {
            animatedSubCutoffHz = cutoffHz;
            animatedSubGainDb = gainDb;
            subAnimInitialized = true;
            invalidate();
        } else {
            startAnimIfNeeded();
        }
    }

    /**
     * The doors' bass stage, front and rear: high-pass cut-off (Hz, 0 = through), shelf frequency
     * (Hz, 0 = off) and shelf gain (dB) - as they reach the chip, loudness's share included.
     */
    public void setBassShaping(float frontFilterHz, float frontBoostHz, float frontBoostDb,
                               float rearFilterHz, float rearBoostHz, float rearBoostDb) {
        frontBassFilterHz = frontFilterHz; frontBassBoostFreqHz = frontBoostHz; frontBassBoostGainDb = frontBoostDb;
        rearBassFilterHz = rearFilterHz; rearBassBoostFreqHz = rearBoostHz; rearBassBoostGainDb = rearBoostDb;
        rearBassDiffers = frontFilterHz != rearFilterHz || frontBoostHz != rearBoostHz || frontBoostDb != rearBoostDb;
        if (!bassAnimInitialized) {
            animFrontFilterHz = frontFilterHz; animFrontBoostHz = frontBoostHz; animFrontBoostDb = frontBoostDb;
            animRearFilterHz = rearFilterHz; animRearBoostHz = rearBoostHz; animRearBoostDb = rearBoostDb;
            bassAnimInitialized = true;
            invalidate();
        } else {
            startAnimIfNeeded();
        }
    }

    private void startAnimIfNeeded() {
        if (!frameCallbackActive) {
            frameCallbackActive = true;
            Choreographer.getInstance().postFrameCallback(animCallback);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        frameCallbackActive = false;
        Choreographer.getInstance().removeFrameCallback(animCallback);
    }

    /** One easing step toward every target; true while anything still visibly moves. */
    private boolean stepAnimation() {
        boolean moving = false;
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            float d = gains[i] - animatedGains[i];
            if (Math.abs(d) > GAIN_EPS) moving = true;
            animatedGains[i] += d * ANIM_SMOOTHING;
            // Inactive: relax toward flat while fading, rather than freezing in place.
            float target = loudnessActive ? loudnessGains[i] : 6f;
            float l = target - animatedLoudnessGains[i];
            if (Math.abs(l) > GAIN_EPS) moving = true;
            animatedLoudnessGains[i] += l * ANIM_SMOOTHING;
        }
        float[] cur = {animatedSubCutoffHz, animatedSubGainDb, animFrontFilterHz, animFrontBoostHz,
                animFrontBoostDb, animRearFilterHz, animRearBoostHz, animRearBoostDb};
        float[] tgt = {subCutoffHz, subGainDb, frontBassFilterHz, frontBassBoostFreqHz,
                frontBassBoostGainDb, rearBassFilterHz, rearBassBoostFreqHz, rearBassBoostGainDb};
        boolean[] isHz = {true, false, true, true, false, true, true, false};
        for (int k = 0; k < cur.length; k++) {
            float d = tgt[k] - cur[k];
            if (Math.abs(d) > (isHz[k] ? HZ_EPS : DB_EPS)) moving = true;
            cur[k] += d * ANIM_SMOOTHING;
        }
        animatedSubCutoffHz = cur[0]; animatedSubGainDb = cur[1];
        animFrontFilterHz = cur[2]; animFrontBoostHz = cur[3]; animFrontBoostDb = cur[4];
        animRearFilterHz = cur[5]; animRearBoostHz = cur[6]; animRearBoostDb = cur[7];

        float alphaTarget = loudnessActive ? 1f : 0f;
        float a = alphaTarget - loudnessAlpha;
        if (Math.abs(a) > 0.01f) {
            moving = true;
            loudnessAlpha += Math.signum(a) * Math.min(Math.abs(a), LOUDNESS_ALPHA_STEP);
        } else {
            loudnessAlpha = alphaTarget;
        }
        return moving;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        float w = getWidth();
        float totalH = getHeight();
        if (w == 0 || totalH == 0) return;

        float density = getResources().getDisplayMetrics().density;
        int accent = ThemeManager.accent(getContext());

        float leftMargin = 32 * density; // Space for left dB scale numbers
        float activeWidth = w - leftMargin;

        float topArea = totalH * TOP_OFFSET_RATIO;
        float sliderAreaH = totalH * DRAW_HEIGHT_RATIO;
        float drawStartY = topArea + thumbRadiusOffset;
        float drawHeight = sliderAreaH - (thumbRadiusOffset * 2);
        float gridBottom = drawStartY + drawHeight;

        float stepX = activeWidth / (float) AudioConfig.NUM_BANDS;
        float MAX_GAIN = 12f;

        // 1. Slider x positions (the y of the curve comes from the composite response below)
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            xCoords[i] = leftMargin + (i + 0.5f) * stepX;
        }

        boolean isNight = ThemeManager.isNight(getContext());
        int gridColorNormal = isNight ? Color.parseColor("#20FFFFFF") : Color.parseColor("#25000000");
        int gridColorZero = androidx.core.graphics.ColorUtils.setAlphaComponent(accent, isNight ? 85 : 120);
        int textNormalColor = ThemeManager.contrastText(
                ThemeManager.textSecondary(getContext(), isNight),
                isNight ? 0xFF12161B : 0xFFFFFFFF);
        int verticalGridColor = isNight ? Color.parseColor("#12FFFFFF") : Color.parseColor("#15000000");

        // 2. Horizontal grid lines and the dB scale
        for (int i = 0; i <= 12; i++) {
            float y = drawStartY + drawHeight - (i / MAX_GAIN) * drawHeight;
            if (i == 6) {
                gridPaint.setColor(gridColorZero);
                gridPaint.setStrokeWidth(1.4f * density);
            } else {
                gridPaint.setColor(gridColorNormal);
                gridPaint.setStrokeWidth(1.0f * density);
            }
            canvas.drawLine(leftMargin, y, w, y, gridPaint);
            if (i == 6) {
                textPaint.setColor(accent);
                textPaint.setFakeBoldText(true);
            } else {
                textPaint.setColor(textNormalColor);
                textPaint.setFakeBoldText(false);
            }
            canvas.drawText(DB_LABELS[i], leftMargin - (6 * density), y + (textPaint.getTextSize() / 3f), textPaint);
        }

        // 3. Vertical slider tracks
        gridPaint.setColor(verticalGridColor);
        gridPaint.setStrokeWidth(1.0f * density);
        for (int i = 0; i < AudioConfig.NUM_BANDS; i++) {
            canvas.drawLine(xCoords[i], drawStartY, xCoords[i], gridBottom, gridPaint);
        }

        // 4. The curves, sampled across the plot on the bands' own log-frequency axis
        float spanStart = xCoords[0];
        float spanEnd = xCoords[AudioConfig.NUM_BANDS - 1];
        fullPath.reset();
        subPath.reset();
        rearBassPath.reset();
        loudnessPath.reset();
        boolean drawSub = animatedSubCutoffHz > 0f;
        boolean drawLoudness = loudnessAlpha > 0.004f;
        boolean rearPenDown = false;
        for (int s = 0; s <= CURVE_SAMPLES; s++) {
            float x = leftMargin + activeWidth * (s / (float) CURVE_SAMPLES);
            float t = (x - spanStart) / (spanEnd - spanStart) * (AudioConfig.NUM_BANDS - 1);
            float hz = AudioConfig.frequencyAt(t);
            float eqDb = AudioConfig.compositeResponseDb(animatedGains, hz);

            float frontBass = AudioConfig.bassShapingResponseDb(hz, animFrontFilterHz, animFrontBoostHz, animFrontBoostDb);
            float y = yOf(eqDb + frontBass, drawStartY, drawHeight, MAX_GAIN);
            if (s == 0) fullPath.moveTo(x, y); else fullPath.lineTo(x, y);

            if (drawSub) {
                float subDb = AudioConfig.subFilterResponseDb(hz, animatedSubCutoffHz,
                        AudioConfig.SUB_FILTER_ORDER, animatedSubGainDb);
                float ys = yOf(Math.min(subDb, MAX_GAIN), drawStartY, drawHeight, MAX_GAIN);
                if (s == 0) subPath.moveTo(x, ys); else subPath.lineTo(x, ys);
            }
            if (rearBassDiffers) {
                float rearBass = AudioConfig.bassShapingResponseDb(hz, animRearFilterHz, animRearBoostHz, animRearBoostDb);
                if (Math.abs(rearBass - frontBass) > REAR_VISIBLE_DB) {
                    float yr = yOf(eqDb + rearBass, drawStartY, drawHeight, MAX_GAIN);
                    if (!rearPenDown) { rearBassPath.moveTo(x, yr); rearPenDown = true; } else { rearBassPath.lineTo(x, yr); }
                } else {
                    rearPenDown = false;
                }
            }
            if (drawLoudness) {
                float yl = yOf(AudioConfig.compositeResponseDb(animatedLoudnessGains, hz),
                        drawStartY, drawHeight, MAX_GAIN);
                if (s == 0) loudnessPath.moveTo(x, yl); else loudnessPath.lineTo(x, yl);
            }
        }

        // 5. Fill under the main curve
        fillPath.set(fullPath);
        fillPath.lineTo(w, gridBottom);
        fillPath.lineTo(leftMargin, gridBottom);
        fillPath.close();

        int fillTopColor = Color.argb(55, Color.red(accent), Color.green(accent), Color.blue(accent));
        int fillBottomColor = Color.argb(0, Color.red(accent), Color.green(accent), Color.blue(accent));
        fillPaint.setShader(new LinearGradient(0, drawStartY, 0, gridBottom, fillTopColor, fillBottomColor, Shader.TileMode.CLAMP));
        linePaint.setColor(accent);
        subLinePaint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(textNormalColor, 210));
        rearBassLinePaint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(textNormalColor, 150));
        int loudnessColor = isNight ? 0xFFFFB74D : 0xFFE67E22;
        loudnessLinePaint.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                loudnessColor, Math.round(230 * loudnessAlpha)));

        // 6. Everything clipped to the plot: a curve leaving it exits at the edge, not pinned flat
        canvas.save();
        canvas.clipRect(leftMargin, drawStartY, w, gridBottom);
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(fullPath, linePaint);
        if (drawSub) canvas.drawPath(subPath, subLinePaint);
        if (rearBassDiffers) canvas.drawPath(rearBassPath, rearBassLinePaint);
        if (drawLoudness) canvas.drawPath(loudnessPath, loudnessLinePaint);
        canvas.restore();
    }

    /** dB to the plot's y: the slider scale, 0..12 with 6 at 0 dB. */
    private static float yOf(float db, float drawStartY, float drawHeight, float maxGain) {
        float value = 6f + db / 2f;
        return drawStartY + drawHeight - (value / maxGain) * drawHeight;
    }
}
