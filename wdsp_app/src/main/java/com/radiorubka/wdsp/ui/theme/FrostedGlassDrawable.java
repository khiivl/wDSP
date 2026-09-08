package com.radiorubka.wdsp.ui.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Багатошаровий скляний Drawable (Glassmorphism / Frosted Glass).
 * Особливості:
 * 1. Компіляція шпалер: горизонтальний градієнт хроматичного спектру шпалер (користувацьких або стандартних).
 * 2. Скляна напівпрозорість із фізичним відблиском (Specular highlight) на верхній фасці.
 * 3. 3D-фаска облямівки: світловий кант зверху та глибокий panelBorder знизу.
 * 4. Концентричний радіус для повної гармонії з кнопками.
 * 5. Динамічний середній колір для системи автоконтрасту тексту.
 */
public class FrostedGlassDrawable extends Drawable {

    private final Context mContext;
    private final boolean mIsNight;
    private final float mCornerRadiusDp;
    private final float mStrokeWidthDp;
    private final int[] mChromaticColors;
    private final int mBaseGlassColor;
    private final int mSubstrateColor;

    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChromaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSpecularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mClipPath = new Path();
    private final RectF mRectF = new RectF();
    private final RectF mStrokeRectF = new RectF();

    public FrostedGlassDrawable(@NonNull Context context,
                                boolean isNight,
                                float cornerRadiusDp,
                                float strokeWidthDp,
                                @NonNull int[] chromaticColors,
                                int baseGlassColor,
                                int substrateColor) {
        this.mContext = context.getApplicationContext();
        this.mIsNight = isNight;
        this.mCornerRadiusDp = cornerRadiusDp;
        this.mStrokeWidthDp = strokeWidthDp;
        this.mChromaticColors = chromaticColors;
        this.mBaseGlassColor = baseGlassColor;
        this.mSubstrateColor = substrateColor;

        mBasePaint.setStyle(Paint.Style.FILL);
        mBasePaint.setColor(baseGlassColor);

        mChromaPaint.setStyle(Paint.Style.FILL);
        // 40% напівпрозорість для м'якого хроматичного світіння шпалер
        mChromaPaint.setAlpha(mIsNight ? 100 : 80);

        mSpecularPaint.setStyle(Paint.Style.FILL);

        mStrokePaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        if (bounds.isEmpty()) return;

        mRectF.set(bounds);
        float density = mContext.getResources().getDisplayMetrics().density;
        float strokePx = Math.max(1f, mStrokeWidthDp * density);
        float strokeHalf = strokePx / 2f;
        mStrokePaint.setStrokeWidth(strokePx);

        mStrokeRectF.set(bounds.left + strokeHalf, bounds.top + strokeHalf,
                bounds.right - strokeHalf, bounds.bottom - strokeHalf);

        // 1. Горизонтальний хроматичний спектр шпалер
        if (mChromaticColors.length > 1) {
            LinearGradient chromaGrad = new LinearGradient(
                    bounds.left, bounds.top, bounds.right, bounds.top,
                    mChromaticColors, null, Shader.TileMode.CLAMP
            );
            mChromaPaint.setShader(chromaGrad);
        } else if (mChromaticColors.length == 1) {
            mChromaPaint.setColor(mChromaticColors[0]);
            mChromaPaint.setAlpha(mIsNight ? 100 : 80);
        }

        // 2. Верхній оптичний відблиск скла (Specular Sheen)
        int specTop = mIsNight ? Color.argb(90, 255, 255, 255) : Color.argb(140, 255, 255, 255);
        int specBottom = Color.argb(0, 255, 255, 255);
        LinearGradient specGrad = new LinearGradient(
                bounds.left, bounds.top, bounds.left, bounds.top + (bounds.height() * 0.45f),
                specTop, specBottom, Shader.TileMode.CLAMP
        );
        mSpecularPaint.setShader(specGrad);

        // 3. Світлова 3D-фаска облямівки скла (світло зверху, темніший кант знизу)
        int borderTop = mIsNight ? Color.argb(100, 255, 255, 255) : Color.argb(160, 255, 255, 255);
        int borderBottom = ThemeManager.panelBorder(mContext, mIsNight);
        LinearGradient strokeGrad = new LinearGradient(
                bounds.left, bounds.top, bounds.left, bounds.bottom,
                borderTop, borderBottom, Shader.TileMode.CLAMP
        );
        mStrokePaint.setShader(strokeGrad);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        float density = mContext.getResources().getDisplayMetrics().density;
        float radiusPx = mCornerRadiusDp * density;

        mClipPath.reset();
        mClipPath.addRoundRect(mRectF, radiusPx, radiusPx, Path.Direction.CW);

        canvas.save();
        canvas.clipPath(mClipPath);

        // 1. Напівпрозоре тіло скла
        canvas.drawRect(mRectF, mBasePaint);

        // 2. Компіляція хроматичного потоку шпалер
        canvas.drawRect(mRectF, mChromaPaint);

        // 3. Верхній дзеркальний відблиск
        canvas.drawRect(mRectF, mSpecularPaint);

        canvas.restore();

        // 4. Оптична скляна рамка (beveled rim)
        float strokeRadius = Math.max(0, radiusPx - (mStrokeWidthDp * density / 2f));
        canvas.drawRoundRect(mStrokeRectF, strokeRadius, strokeRadius, mStrokePaint);
    }

    public int getSubstrateColor() {
        return mSubstrateColor;
    }

    public float getCornerRadiusDp() {
        return mCornerRadiusDp;
    }

    @Override
    public void setAlpha(int alpha) {
        mBasePaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mBasePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
