package com.radiorubka.wdsp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;  // <--- Correct one
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

public class Toaster {
    // One static handler for the whole app - very low resource usage
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static Toast toast;
    public static void show(Context context, String message) {

        final Context appContext = context.getApplicationContext();

        mainHandler.post(() -> {
            // 1. Cancel the previous toast if it's still showing
            if (toast != null) {
                toast.cancel();
            }

            // 2. Create the background
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.RECTANGLE);

            int color = androidx.core.content.ContextCompat.getColor(appContext, R.color.toast_bg);
            int stroke = androidx.core.content.ContextCompat.getColor(appContext, R.color.toast_stroke);

            shape.setColor(color);
            shape.setCornerRadius(appContext.getResources().getDimension(R.dimen.button_radius));
            shape.setStroke(appContext.getResources().getDimensionPixelSize(R.dimen.stroke_width), stroke);

            // 3. Create the TextView
            TextView tv = new TextView(appContext);
            tv.setText(message);

            int text_color = androidx.core.content.ContextCompat.getColor(appContext, R.color.text_theme_aware);
            tv.setTextColor(text_color);
            tv.setTextSize(appContext.getResources().getDimension(R.dimen.text_size_button));

            int pad = appContext.getResources().getDimensionPixelSize(R.dimen.padding_small);
            int pad2 = appContext.getResources().getDimensionPixelSize(R.dimen.padding_standard);
            tv.setPadding(pad, pad, pad, pad);
            tv.setGravity(Gravity.CENTER);
            tv.setBackground(shape);

            // 4. Create and show the Toast
            toast = new Toast(appContext);
            toast.setDuration(Toast.LENGTH_SHORT);
            toast.setView(tv);
            toast.setGravity(Gravity.BOTTOM | Gravity.END, pad2, pad2);
            toast.show();
        });
    }
}
