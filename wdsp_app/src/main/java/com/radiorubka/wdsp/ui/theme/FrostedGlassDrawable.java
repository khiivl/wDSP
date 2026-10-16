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
    private final float[] mCornerRadiiDp;
    private final float mStrokeWidthDp;
    private final int[] mChromaticColors;
    private final int mBaseGlassColor;
    private final int mSubstrateColor;

    private final Paint mBasePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mChromaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDepthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSpecularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path mClipPath = new Path();
    private final Path mStrokePath = new Path();
    private final Path mShadowPath = new Path();
    private final RectF mRectF = new RectF();
    private final RectF mStrokeRectF = new RectF();
    private final RectF mShadowRectF = new RectF();

    private final float[] mSh1Radii = new float[8];
    private final float[] mSh2Radii = new float[8];
    private final float[] mSh3Radii = new float[8];
    private final float[] mBodyRadii = new float[8];
    private final float[] mStrokeRadii = new float[8];

    private boolean mEnableShadow = true;
    private boolean mIsPressed = false;
    private final boolean mIsSolidAccent;

    public static FrostedGlassDrawable createAccentPill(@NonNull Context context,
                                                        boolean isNight,
                                                        float cornerRadiusDp,
                                                        int accentColor) {
        return new FrostedGlassDrawable(context, isNight, cornerRadiusDp, 1.2f,
                new int[]{accentColor}, accentColor, accentColor, true, true);
    }

    public FrostedGlassDrawable(@NonNull Context context,
                                boolean isNight,
                                float cornerRadiusDp,
                                float strokeWidthDp,
                                @NonNull int[] chromaticColors,
                                int baseGlassColor,
                                int substrateColor) {
        this(context, isNight, cornerRadiusDp, strokeWidthDp, chromaticColors, baseGlassColor, substrateColor, true, false);
    }

    public FrostedGlassDrawable(@NonNull Context context,
                                boolean isNight,
                                float cornerRadiusDp,
                                float strokeWidthDp,
                                @NonNull int[] chromaticColors,
                                int baseGlassColor,
                                int substrateColor,
                                boolean enableShadow) {
        this(context, isNight, cornerRadiusDp, strokeWidthDp, chromaticColors, baseGlassColor, substrateColor, enableShadow, false);
    }

    public FrostedGlassDrawable(@NonNull Context context,
                                boolean isNight,
                                float cornerRadiusDp,
                                float strokeWidthDp,
                                @NonNull int[] chromaticColors,
                                int baseGlassColor,
                                int substrateColor,
                                boolean enableShadow,
                                boolean isSolidAccent) {
        this.mContext = context.getApplicationContext();
        this.mIsNight = isNight;
        this.mCornerRadiusDp = cornerRadiusDp;
        this.mCornerRadiiDp = null;
        this.mStrokeWidthDp = strokeWidthDp;
        this.mChromaticColors = chromaticColors;
        this.mBaseGlassColor = baseGlassColor;
        this.mSubstrateColor = substrateColor;
        this.mEnableShadow = enableShadow;
        this.mIsSolidAccent = isSolidAccent;

        mBasePaint.setStyle(Paint.Style.FILL);
        mBasePaint.setColor(baseGlassColor);

        mChromaPaint.setStyle(Paint.Style.FILL);
        // Хроматичне світіння шпалер: 100 вночі, 110 вдень для чистої передачі пастельного спектру
        mChromaPaint.setAlpha(mIsNight ? 100 : 110);

        mDepthPaint.setStyle(Paint.Style.FILL);

        mSpecularPaint.setStyle(Paint.Style.FILL);

        mStrokePaint.setStyle(Paint.Style.STROKE);

        mShadowPaint.setStyle(Paint.Style.FILL);
    }

    public FrostedGlassDrawable(@NonNull Context context,
                                boolean isNight,
                                @NonNull float[] cornerRadiiDp,
                                float strokeWidthDp,
                                @NonNull int[] chromaticColors,
                                int baseGlassColor,
                                int substrateColor) {
        this(context, isNight, cornerRadiiDp, strokeWidthDp, chromaticColors, baseGlassColor, substrateColor, true, false);
    }

    public FrostedGlassDrawable(@NonNull Context context,
                                boolean isNight,
                                @NonNull float[] cornerRadiiDp,
                                float strokeWidthDp,
                                @NonNull int[] chromaticColors,
                                int baseGlassColor,
                                int substrateColor,
                                boolean enableShadow,
                                boolean isSolidAccent) {
        this.mContext = context.getApplicationContext();
        this.mIsNight = isNight;
        this.mCornerRadiiDp = cornerRadiiDp;
        this.mCornerRadiusDp = (cornerRadiiDp != null && cornerRadiiDp.length > 0) ? cornerRadiiDp[0] : 0f;
        this.mStrokeWidthDp = strokeWidthDp;
        this.mChromaticColors = chromaticColors;
        this.mBaseGlassColor = baseGlassColor;
        this.mSubstrateColor = substrateColor;
        this.mEnableShadow = enableShadow;
        this.mIsSolidAccent = isSolidAccent;

        mBasePaint.setStyle(Paint.Style.FILL);
        mBasePaint.setColor(baseGlassColor);

        mChromaPaint.setStyle(Paint.Style.FILL);
        mChromaPaint.setAlpha(mIsNight ? 100 : 110);

        mDepthPaint.setStyle(Paint.Style.FILL);

        mSpecularPaint.setStyle(Paint.Style.FILL);

        mStrokePaint.setStyle(Paint.Style.STROKE);

        mShadowPaint.setStyle(Paint.Style.FILL);
    }

    public void setEnableShadow(boolean enable) {
        if (this.mEnableShadow != enable) {
            this.mEnableShadow = enable;
            invalidateSelf();
        }
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean pressed = false;
        if (state != null) {
            for (int s : state) {
                if (s == android.R.attr.state_pressed) {
                    pressed = true;
                    break;
                }
            }
        }
        if (mIsPressed != pressed) {
            mIsPressed = pressed;
            invalidateSelf();
            return true;
        }
        return super.onStateChange(state);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        if (bounds.isEmpty()) return;

        float density = mContext.getResources().getDisplayMetrics().density;
        if (mEnableShadow) {
            float padX = 2.0f * density;
            float padYTop = 1.0f * density;
            float padYBottom = 3.5f * density;
            mRectF.set(bounds.left + padX, bounds.top + padYTop, bounds.right - padX, bounds.bottom - padYBottom);
        } else {
            mRectF.set(bounds);
        }

        float strokePx = Math.max(1f, mStrokeWidthDp * density);
        float strokeHalf = strokePx / 2f;
        mStrokePaint.setStrokeWidth(strokePx);

        mStrokeRectF.set(mRectF.left + strokeHalf, mRectF.top + strokeHalf,
                mRectF.right - strokeHalf, mRectF.bottom - strokeHalf);

        if (mCornerRadiiDp != null && mCornerRadiiDp.length >= 4) {
            float strokeInset = strokePx / 2f;
            for (int i = 0; i < 4; i++) {
                float r = mCornerRadiiDp[i] * density;
                mSh1Radii[i * 2] = mSh1Radii[i * 2 + 1] = r + 1.2f * density;
                mSh2Radii[i * 2] = mSh2Radii[i * 2 + 1] = r + 0.5f * density;
                mSh3Radii[i * 2] = mSh3Radii[i * 2 + 1] = r;
                mBodyRadii[i * 2] = mBodyRadii[i * 2 + 1] = r;
                mStrokeRadii[i * 2] = mStrokeRadii[i * 2 + 1] = Math.max(0f, r - strokeInset);
            }
        }

        // 1. Горизонтальний хроматичний спектр шпалер
        if (mChromaticColors.length > 1) {
            LinearGradient chromaGrad = new LinearGradient(
                    mRectF.left, mRectF.top, mRectF.right, mRectF.top,
                    mChromaticColors, null, Shader.TileMode.CLAMP
            );
            mChromaPaint.setShader(chromaGrad);
        } else if (mChromaticColors.length == 1) {
            mChromaPaint.setColor(mChromaticColors[0]);
            mChromaPaint.setAlpha(mIsNight ? 100 : 110);
        }

        // 2. Верхній оптичний відблиск скла (Specular Sheen)
        // Вночі 90 альфа над темним склом, вдень делікатний кришталевий блік 70 альфа (не вибілює скло)
        int specTop = mIsNight ? Color.argb(90, 255, 255, 255) : Color.argb(70, 255, 255, 255);
        int specBottom = Color.argb(0, 255, 255, 255);
        float sheenHeight = mRectF.top + (mRectF.height() * (mIsNight ? 0.45f : 0.35f));
        LinearGradient specGrad = new LinearGradient(
                mRectF.left, mRectF.top, mRectF.left, sheenHeight,
                specTop, specBottom, Shader.TileMode.CLAMP
        );
        mSpecularPaint.setShader(specGrad);

        // 3. Об'ємна фізична глибина скла в денному режимі (заломлення нижньої грані)
        if (!mIsNight) {
            LinearGradient depthGrad = new LinearGradient(
                    mRectF.left, mRectF.top, mRectF.left, mRectF.bottom,
                    new int[]{ Color.argb(0, 20, 30, 45), Color.argb(22, 20, 30, 45) },
                    new float[]{ 0.35f, 1.0f },
                    Shader.TileMode.CLAMP
            );
            mDepthPaint.setShader(depthGrad);
        }

        // 4. Світлова 3D-фаска облямівки скла (кришталевий кант зверху, контрастне темне заломлення знизу)
        int borderTop;
        int borderBottom;
        if (mIsSolidAccent) {
            borderTop = Color.argb(130, 255, 255, 255);
            borderBottom = Color.argb(70, 0, 0, 0);
        } else {
            borderTop = mIsNight ? Color.argb(100, 255, 255, 255) : Color.argb(220, 255, 255, 255);
            borderBottom = mIsNight ? ThemeManager.panelBorder(mContext, true) : Color.argb(65, 25, 40, 60);
        }
        LinearGradient strokeGrad = new LinearGradient(
                mRectF.left, mRectF.top, mRectF.left, mRectF.bottom,
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

        // 0. Soft drop shadow (drawn only when floating, hidden when pressed into the surface)
        if (mEnableShadow && !mIsPressed) {
            int shCol1 = mIsNight ? Color.argb(85, 0, 0, 0) : Color.argb(50, 20, 30, 45);
            int shCol2 = mIsNight ? Color.argb(50, 0, 0, 0) : Color.argb(30, 20, 30, 45);
            int shCol3 = mIsNight ? Color.argb(22, 0, 0, 0) : Color.argb(14, 20, 30, 45);

            if (mCornerRadiiDp != null && mCornerRadiiDp.length >= 4) {
                // Pass 1: Outer ambient halo
                mShadowRectF.set(mRectF.left - 0.8f * density, mRectF.top + 1.5f * density, mRectF.right + 0.8f * density, mRectF.bottom + 3.2f * density);
                mShadowPaint.setColor(shCol3);
                mShadowPath.reset();
                mShadowPath.addRoundRect(mShadowRectF, mSh1Radii, Path.Direction.CW);
                canvas.drawPath(mShadowPath, mShadowPaint);

                // Pass 2: Mid diffuse shadow
                mShadowRectF.set(mRectF.left - 0.4f * density, mRectF.top + 1.0f * density, mRectF.right + 0.4f * density, mRectF.bottom + 2.0f * density);
                mShadowPaint.setColor(shCol2);
                mShadowPath.reset();
                mShadowPath.addRoundRect(mShadowRectF, mSh2Radii, Path.Direction.CW);
                canvas.drawPath(mShadowPath, mShadowPaint);

                // Pass 3: Core occlusion contact shadow
                mShadowRectF.set(mRectF.left, mRectF.top + 0.6f * density, mRectF.right, mRectF.bottom + 1.2f * density);
                mShadowPaint.setColor(shCol1);
                mShadowPath.reset();
                mShadowPath.addRoundRect(mShadowRectF, mSh3Radii, Path.Direction.CW);
                canvas.drawPath(mShadowPath, mShadowPaint);
            } else {
                // Pass 1: Outer ambient halo (symmetric horizontal spread)
                mShadowRectF.set(mRectF.left - 0.8f * density, mRectF.top + 1.5f * density, mRectF.right + 0.8f * density, mRectF.bottom + 3.2f * density);
                mShadowPaint.setColor(shCol3);
                canvas.drawRoundRect(mShadowRectF, radiusPx + 1.2f * density, radiusPx + 1.2f * density, mShadowPaint);

                // Pass 2: Mid diffuse shadow
                mShadowRectF.set(mRectF.left - 0.4f * density, mRectF.top + 1.0f * density, mRectF.right + 0.4f * density, mRectF.bottom + 2.0f * density);
                mShadowPaint.setColor(shCol2);
                canvas.drawRoundRect(mShadowRectF, radiusPx + 0.5f * density, radiusPx + 0.5f * density, mShadowPaint);

                // Pass 3: Core occlusion contact shadow
                mShadowRectF.set(mRectF.left, mRectF.top + 0.6f * density, mRectF.right, mRectF.bottom + 1.2f * density);
                mShadowPaint.setColor(shCol1);
                canvas.drawRoundRect(mShadowRectF, radiusPx, radiusPx, mShadowPaint);
            }
        }

        mClipPath.reset();
        if (mCornerRadiiDp != null && mCornerRadiiDp.length >= 4) {
            mClipPath.addRoundRect(mRectF, mBodyRadii, Path.Direction.CW);
        } else {
            mClipPath.addRoundRect(mRectF, radiusPx, radiusPx, Path.Direction.CW);
        }

        canvas.save();
        canvas.clipPath(mClipPath);

        // 1. Напівпрозоре кришталеве тіло скла (або насичений акцент)
        canvas.drawRect(mRectF, mBasePaint);

        if (!mIsSolidAccent) {
            // 2. Компіляція хроматичного потоку шпалер
            canvas.drawRect(mRectF, mChromaPaint);

            // 3. Фізична об'ємна глибина (тільки вдень)
            if (!mIsNight) {
                canvas.drawRect(mRectF, mDepthPaint);
            }
        }

        // 4. Верхній дзеркальний відблиск
        canvas.drawRect(mRectF, mSpecularPaint);

        canvas.restore();

        // 5. Оптична скляна рамка (beveled rim)
        if (mCornerRadiiDp != null && mCornerRadiiDp.length >= 4) {
            mStrokePath.reset();
            mStrokePath.addRoundRect(mStrokeRectF, mStrokeRadii, Path.Direction.CW);
            canvas.drawPath(mStrokePath, mStrokePaint);
        } else {
            float strokeRadius = Math.max(0, radiusPx - (mStrokeWidthDp * density / 2f));
            canvas.drawRoundRect(mStrokeRectF, strokeRadius, strokeRadius, mStrokePaint);
        }
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
