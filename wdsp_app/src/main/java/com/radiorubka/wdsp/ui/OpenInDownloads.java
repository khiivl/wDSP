package com.radiorubka.wdsp.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import com.radiorubka.wdsp.Downloads;

import java.util.List;

/**
 * Opening a document, starting in the folder this app saves into.
 *
 * <h2>Why this is not just an extra on the intent</h2>
 *
 * {@link ActivityResultContracts.OpenDocument} gives no way to say where the picker should start,
 * so it starts at the root of internal storage and the user walks down to {@code Download/wDSP} by
 * hand — on a touchscreen, in a car, every single time.
 *
 * The hint that fixes it is {@link DocumentsContract#EXTRA_INITIAL_URI}, and adding it means
 * building the intent ourselves. But the hint alone is not enough: it is only binding on the
 * system document picker, and on these head units {@code ACTION_OPEN_DOCUMENT} is usually handled
 * by whatever file manager the owner installed — which opens at its own home screen and ignores it
 * entirely. Measured on a real unit: File Manager + does exactly that.
 *
 * So the picker is aimed at the system document picker, which does honour it. That does take the
 * choice away for this one action, and the trade was made deliberately: it lasts for a single file
 * selection inside one app, and in exchange the file is already on screen instead of four
 * navigation steps away. If the system picker is not installed, nothing is forced and the intent
 * goes wherever it would have gone.
 */
public final class OpenInDownloads extends ActivityResultContracts.OpenDocument {

    private static final String TAG = "wDSP_Picker";

    @NonNull
    @Override
    public Intent createIntent(@NonNull Context context, @NonNull String[] input) {
        return aim(context, super.createIntent(context, input));
    }

    /**
     * Points an {@code ACTION_OPEN_DOCUMENT} intent at {@code Download/wDSP}.
     *
     * <p>Shared with the preset import, which builds its own intent rather than using a contract.
     *
     * @return the same intent, so it can be used inline
     */
    public static Intent aim(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri start = Downloads.initialUri();
            if (start != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);
            }
        }
        String picker = systemPicker(context, intent);
        if (picker != null) {
            intent.setPackage(picker);
        } else {
            // No system picker on this build. Leave the intent open: some file manager will take
            // it, the starting folder will be ignored, and that is still better than nothing.
            Log.i(TAG, "no system document picker found; the starting folder will be a suggestion");
        }
        return intent;
    }

    /**
     * The system document picker, if this build has one.
     *
     * <p>The package name is {@code com.android.documentsui} on AOSP and
     * {@code com.google.android.documentsui} where the Google build is used, so it is matched by
     * name rather than assumed — and only among the applications that actually offered to handle
     * this intent, so a package that exists but cannot open documents is never selected.
     */
    private static String systemPicker(Context context, Intent intent) {
        try {
            PackageManager pm = context.getPackageManager();
            List<ResolveInfo> handlers = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo info : handlers) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (pkg != null && pkg.endsWith("documentsui")) {
                    return pkg;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "could not look for the system document picker", e);
        }
        return null;
    }
}
