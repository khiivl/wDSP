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
        int pad = (int) (5 * density);
        mContentContainer.setPadding(pad, pad, pad, pad);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.gravity = Gravity.CENTER;
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
            rippleMask.setCornerRadius(18 * density);
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
                // Замальовування активної кнопки суцільним акцентним кольором
                GradientDrawable activeBg = new GradientDrawable();
                activeBg.setShape(GradientDrawable.RECTANGLE);
                activeBg.setCornerRadius(18 * density);
                activeBg.setColor(accentColor);

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
                mask.setCornerRadius(18 * density);
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
}
