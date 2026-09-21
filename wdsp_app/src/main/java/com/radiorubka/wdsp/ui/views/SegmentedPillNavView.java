package com.radiorubka.wdsp.ui.views;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.navigation.NavigationBarView;
import com.radiorubka.wdsp.R;
import com.radiorubka.wdsp.ui.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Плаваючий сегментований навігаційний бар (Floating Pill Navigation Bar).
 * Особливості:
 * 1. Тач-зона кожного таба охоплює ВСЮ ширину сегмента (максимальна зручність для водія).
 * 2. Між табами розташовані "мертві зони" (проміжки 10dp) із вертикальними розділювачами по центру.
 * 3. Повна адаптивність: на широкому екрані центрується, на вузькому (Split-Screen) підтримує плавний свайп (scroll).
 * 4. Сумісність за API з BottomNavigationView.
 */
public class SegmentedPillNavView extends HorizontalScrollView {

    private LinearLayout mContentContainer;
    private NavigationBarView.OnItemSelectedListener mListener;
    private int mSelectedItemId = -1;

    private ColorStateList mIconTintList;
    private ColorStateList mTextColorList;
    private ColorStateList mActiveIndicatorColor;

    private final List<NavItemViewHolder> mHolders = new ArrayList<>();
    private final List<View> mDividers = new ArrayList<>();

    private static class NavItemViewHolder {
        MenuItem menuItem;
        LinearLayout itemView;
        FrameLayout indicatorFrame;
        ImageView iconView;
        TextView textView;
    }

    public SegmentedPillNavView(@NonNull Context context) {
        this(context, null);
    }

    public SegmentedPillNavView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SegmentedPillNavView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFillViewport(true);

        mContentContainer = new LinearLayout(context);
        mContentContainer.setOrientation(LinearLayout.HORIZONTAL);
        mContentContainer.setGravity(Gravity.CENTER);

        float density = getResources().getDisplayMetrics().density;
        int padX = (int) (6 * density);
        int padTop = (int) (5 * density);
        int padBottom = (int) (7.5f * density);
        mContentContainer.setPadding(padX, padTop, padX, padBottom);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        // CENTER_VERTICAL, not CENTER. A horizontally centred child of a scroll view puts part of
        // itself to the left of scroll position zero, where no scrolling can reach it: on the Tesla
        // square the row (six tabs, 840dp) was cut by 128dp on EACH side, so the equaliser and the
        // settings tab showed 2dp slivers and could not be brought into view at all. Measured on
        // the unit 12.09.2026; the arithmetic matched at 800x480 (102dp) and in split screen (22dp).
        // The pill still looks centred on a wide panel, because the bar that holds it is centred and
        // wraps its content.
        lp.gravity = Gravity.CENTER_VERTICAL;
        addView(mContentContainer, lp);

        int menuResId = 0;
        if (attrs != null) {
            menuResId = attrs.getAttributeResourceValue("http://schemas.android.com/apk/res-auto", "menu", 0);
        }
        if (menuResId == 0) {
            menuResId = R.menu.bottom_nav_menu;
        }
        inflateMenu(menuResId);
    }

    public void inflateMenu(int menuResId) {
        mContentContainer.removeAllViews();
        mHolders.clear();
        mDividers.clear();

        PopupMenu popup = new PopupMenu(getContext(), this);
        popup.inflate(menuResId);
        Menu menu = popup.getMenu();

        float density = getResources().getDisplayMetrics().density;
        int itemCount = menu.size();

        for (int i = 0; i < itemCount; i++) {
            MenuItem item = menu.getItem(i);
            if (!item.isVisible()) continue;

            // 1. Мертва зона з вертикальною роздільною смужкою по центру
            if (!mHolders.isEmpty()) {
                FrameLayout deadZone = new FrameLayout(getContext());
                LinearLayout.LayoutParams dzLp = new LinearLayout.LayoutParams(
                        (int) (12 * density),
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
                deadZone.setLayoutParams(dzLp);
                deadZone.setClickable(false);
                deadZone.setFocusable(false);

                View divider = new View(getContext());
                FrameLayout.LayoutParams divLp = new FrameLayout.LayoutParams(
                        Math.max(1, (int) (1.5f * density)),
                        (int) (26 * density)
                );
                divLp.gravity = Gravity.CENTER;
                divider.setLayoutParams(divLp);

                GradientDrawable divGd = new GradientDrawable();
                divGd.setShape(GradientDrawable.RECTANGLE);
                divGd.setCornerRadius(1 * density);
                divGd.setColor(ThemeManager.navDividerColor(ThemeManager.isNight(getContext())));
                divider.setBackground(divGd);

                deadZone.addView(divider);
                mContentContainer.addView(deadZone);
                mDividers.add(divider);
            }

            // 2. Сегмент таба (клікабельний на ВСЮ ширину - велика тач-зона)
            LinearLayout itemView = new LinearLayout(getContext());
            itemView.setOrientation(LinearLayout.VERTICAL);
            itemView.setGravity(Gravity.CENTER);

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            itemView.setLayoutParams(itemLp);
            itemView.setMinimumWidth((int) (130 * density));
            itemView.setPadding((int) (8 * density), (int) (4 * density), (int) (8 * density), (int) (4 * density));

            // Округла підкладка для ripple-ефекту
            GradientDrawable rippleMask = new GradientDrawable();
            rippleMask.setShape(GradientDrawable.RECTANGLE);
            rippleMask.setCornerRadius(25 * density);
            rippleMask.setColor(Color.WHITE);
            RippleDrawable ripple = new RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#33888888")),
                    null,
                    rippleMask
            );
            itemView.setBackground(ripple);
            itemView.setClickable(true);
            itemView.setFocusable(true);

            // Активна пілла навколо іконки
            FrameLayout indicatorFrame = new FrameLayout(getContext());
            LinearLayout.LayoutParams indLp = new LinearLayout.LayoutParams(
                    (int) (64 * density),
                    (int) (32 * density)
            );
            indLp.gravity = Gravity.CENTER_HORIZONTAL;
            indicatorFrame.setLayoutParams(indLp);

            ImageView iconView = new ImageView(getContext());
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                    (int) (24 * density),
                    (int) (24 * density)
            );
            iconLp.gravity = Gravity.CENTER;
            iconView.setLayoutParams(iconLp);
            iconView.setImageDrawable(item.getIcon());
            indicatorFrame.addView(iconView);

            itemView.addView(indicatorFrame);

            // Текст підпису
            TextView textView = new TextView(getContext());
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            textLp.gravity = Gravity.CENTER_HORIZONTAL;
            textLp.topMargin = (int) (3 * density);
            textView.setLayoutParams(textLp);
            textView.setText(item.getTitle());
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity(Gravity.CENTER);

            itemView.addView(textView);

            NavItemViewHolder holder = new NavItemViewHolder();
            holder.menuItem = item;
            holder.itemView = itemView;
            holder.indicatorFrame = indicatorFrame;
            holder.iconView = iconView;
            holder.textView = textView;
            mHolders.add(holder);

            final int itemId = item.getItemId();
            itemView.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        v.setTranslationX(1.5f * density);
                        v.setTranslationY(2.0f * density);
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                        v.setTranslationX(0f);
                        v.setTranslationY(0f);
                        setSelectedItemId(itemId);
                        break;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.setTranslationX(0f);
                        v.setTranslationY(0f);
                        break;
                }
                return true;
            });

            mContentContainer.addView(itemView);
        }

        if (mSelectedItemId != -1) {
            applySelection(mSelectedItemId);
        } else if (!mHolders.isEmpty()) {
            applySelection(mHolders.get(0).menuItem.getItemId());
        }
    }

    public void setOnItemSelectedListener(@Nullable NavigationBarView.OnItemSelectedListener listener) {
        this.mListener = listener;
    }

    public void setSelectedItemId(int id) {
        NavItemViewHolder targetHolder = null;
        for (NavItemViewHolder h : mHolders) {
            if (h.menuItem.getItemId() == id) {
                targetHolder = h;
                break;
            }
        }

        if (targetHolder != null && mListener != null) {
            boolean handled = mListener.onNavigationItemSelected(targetHolder.menuItem);
            if (!handled) {
                return;
            }
        }

        applySelection(id);
    }

    public void applySelection(int id) {
        mSelectedItemId = id;
        float density = getResources().getDisplayMetrics().density;
        boolean night = ThemeManager.isNight(getContext());
        int accentColor = ThemeManager.accent(getContext(), night);
        int onAccentColor = ThemeManager.onAccent(getContext(), night);
        int substrateColor = ThemeManager.dockSubstrateColor(getContext(), night);
        int baseInactive = getInactiveTextColor();
        int inactiveColor = ThemeManager.contrastText(baseInactive, substrateColor);

        for (NavItemViewHolder holder : mHolders) {
            boolean isSelected = (holder.menuItem.getItemId() == id);
            if (isSelected) {
                // Замальовування активної кнопки капсулою з акцентного скла (FrostedGlassDrawable)
                com.radiorubka.wdsp.ui.theme.FrostedGlassDrawable activeBg =
                        com.radiorubka.wdsp.ui.theme.FrostedGlassDrawable.createAccentPill(
                                getContext(),
                                night,
                                25f,
                                accentColor
                        );

                RippleDrawable ripple = new RippleDrawable(
                        ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
                        activeBg,
                        activeBg
                );
                holder.itemView.setBackground(ripple);
                holder.indicatorFrame.setBackground(null);

                // Чіткий контрастний значок та надпис (onAccent)
                holder.iconView.setImageTintList(ColorStateList.valueOf(onAccentColor));
                holder.textView.setTextColor(onAccentColor);
                holder.textView.setTypeface(null, Typeface.BOLD);

                // Автоматичне доведення скролу (якщо таб частково за межами екрана у Split-Screen)
                post(() -> {
                    int left = holder.itemView.getLeft();
                    int right = holder.itemView.getRight();
                    int scrollX = getScrollX();
                    int width = getWidth();
                    if (left < scrollX) {
                        smoothScrollTo(Math.max(0, left - (int) (16 * density)), 0);
                    } else if (right > scrollX + width) {
                        smoothScrollTo(right - width + (int) (16 * density), 0);
                    }
                });
            } else {
                // Неактивна кнопка: прозора підкладка з м'яким ripple
                GradientDrawable mask = new GradientDrawable();
                mask.setShape(GradientDrawable.RECTANGLE);
                mask.setCornerRadius(25 * density);
                mask.setColor(Color.WHITE);

                RippleDrawable ripple = new RippleDrawable(
                        ColorStateList.valueOf(night ? Color.parseColor("#26FFFFFF") : Color.parseColor("#1F000000")),
                        null,
                        mask
                );
                holder.itemView.setBackground(ripple);
                holder.indicatorFrame.setBackground(null);

                holder.iconView.setImageTintList(ColorStateList.valueOf(inactiveColor));
                holder.textView.setTextColor(inactiveColor);
                holder.textView.setTypeface(null, Typeface.NORMAL);
            }
        }
    }

    public int getSelectedItemId() {
        return mSelectedItemId;
    }

    public void setItemIconTintList(@Nullable ColorStateList tint) {
        this.mIconTintList = tint;
        refreshHolderStates();
    }

    public void setItemTextColor(@Nullable ColorStateList textColor) {
        this.mTextColorList = textColor;
        refreshHolderStates();
    }

    public void setItemActiveIndicatorColor(@Nullable ColorStateList color) {
        this.mActiveIndicatorColor = color;
        refreshHolderStates();
    }

    public void updateTheme(boolean isNight) {
        int divColor = ThemeManager.navDividerColor(isNight);
        float density = getResources().getDisplayMetrics().density;
        for (View div : mDividers) {
            GradientDrawable divGd = new GradientDrawable();
            divGd.setShape(GradientDrawable.RECTANGLE);
            divGd.setCornerRadius(1 * density);
            divGd.setColor(divColor);
            div.setBackground(divGd);
        }
        refreshHolderStates();
    }

    private void refreshHolderStates() {
        if (mSelectedItemId != -1) {
            applySelection(mSelectedItemId);
        }
    }

    private int getActiveTextColor() {
        if (mTextColorList != null) {
            return mTextColorList.getColorForState(new int[]{android.R.attr.state_selected}, mTextColorList.getDefaultColor());
        }
        return ThemeManager.accent(getContext());
    }

    private int getInactiveTextColor() {
        if (mTextColorList != null) {
            return mTextColorList.getColorForState(new int[]{}, mTextColorList.getDefaultColor());
        }
        return ThemeManager.textSecondary(getContext());
    }

    /**
     * Narrow the tabs before resorting to a swipe.
     *
     * <p>Six tabs at their designed 130dp, plus five 12dp gaps and the container padding, need
     * 852dp. No panel in the matrix except the reference 1280 has that much, so on every other one
     * the row was wider than the screen and the two end tabs were only reachable by swiping - which
     * nothing on screen suggests. Here the width each tab asks for is recomputed from the room
     * actually given, down to a floor: 84dp still holds a 24dp icon and a two-word caption, and
     * six of those fit a 600dp panel. Below the floor the row stays scrollable, as before.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int n = mHolders.size();
        if (n > 0 && mode != MeasureSpec.UNSPECIFIED) {
            float density = getResources().getDisplayMetrics().density;
            int designed = (int) (130 * density);
            int floor = (int) (84 * density);
            int gaps = (int) (12 * density) * Math.max(0, n - 1);
            int room = MeasureSpec.getSize(widthMeasureSpec)
                    - getPaddingLeft() - getPaddingRight()
                    - mContentContainer.getPaddingLeft() - mContentContainer.getPaddingRight()
                    - gaps;
            int per = Math.max(floor, Math.min(designed, room / n));
            float textScale = captionScale(per, density);
            float textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CAPTION_SP,
                    getResources().getDisplayMetrics()) * textScale;
            int padX = Math.round(ITEM_PAD_X_DP * density * textScale);
            for (NavItemViewHolder h : mHolders) {
                if (h.itemView.getMinimumWidth() != per) {
                    h.itemView.setMinimumWidth(per);
                }
                if (Math.abs(h.textView.getTextSize() - textPx) > 0.5f) {
                    h.textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
                }
                if (h.itemView.getPaddingLeft() != padX) {
                    h.itemView.setPadding(padX, h.itemView.getPaddingTop(), padX, h.itemView.getPaddingBottom());
                }
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private static final float CAPTION_SP = 12f;
    private static final float ITEM_PAD_X_DP = 8f;
    /** Below this the captions stop being readable at a glance; the row scrolls instead. */
    private static final float MIN_CAPTION_SCALE = 0.7f;

    /**
     * How much the captions and the tabs' side padding shrink so that the widest caption fits a tab
     * {@code per} pixels wide. The tabs could narrow only down to their caption, so on a 640dp
     * window "Налаштування" at 12sp made its tab wider than its share and the last tab was cut
     * (15.09.2026). The owner's rule for such rows: shrink everything together rather than cut.
     * Measured bold, because whichever tab is selected is drawn bold.
     */
    private float captionScale(int per, float density) {
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, CAPTION_SP,
                getResources().getDisplayMetrics()));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        float widest = 0f;
        for (NavItemViewHolder h : mHolders) {
            CharSequence title = h.textView.getText();
            if (title != null) widest = Math.max(widest, paint.measureText(title.toString()));
        }
        float need = widest + 2f * ITEM_PAD_X_DP * density;
        if (need <= per) return 1f;
        return Math.max(MIN_CAPTION_SCALE, per / need);
    }
}
