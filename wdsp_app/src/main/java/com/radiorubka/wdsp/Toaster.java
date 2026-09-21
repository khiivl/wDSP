package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

public class Toaster {
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Toast toast;

    public static void show(Context context, int resId) {
        if (context != null) {
            show(context, context.getString(resId));
        }
    }

    public static void show(Context context, String message) {
        if (context == null || message == null) return;
        final Context appContext = context.getApplicationContext();

        mainHandler.post(() -> {
            // 1. Cancel previous toast
            if (toast != null) {
                toast.cancel();
            }

            // 2. Compute theme-aware colors
            int bg = ThemeManager.cardBackground(appContext);
            int border = ThemeManager.panelBorder(appContext);
            int textColor = ThemeManager.contrastText(ThemeManager.textPrimary(appContext), bg);

            float density = appContext.getResources().getDisplayMetrics().density;
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);
            shape.setColor(ColorUtils.setAlphaComponent(bg, 0xF2));
            shape.setCornerRadius(14f * density);
            shape.setStroke(Math.max(1, (int) (1.2f * density)), border);

            // 3. Create TextView
            TextView tv = new TextView(appContext);
            tv.setText(message);
            tv.setTextColor(textColor);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setTypeface(null, Typeface.BOLD);

            int hPad = (int) (20 * density);
            int vPad = (int) (12 * density);
            tv.setPadding(hPad, vPad, hPad, vPad);
            tv.setGravity(Gravity.CENTER);
            tv.setBackground(shape);

            // 4. Create and show Toast
            toast = new Toast(appContext);
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setView(tv);
            int yOffset = (int) (48 * density);
            toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, yOffset);
            toast.show();
        });
    }
}
