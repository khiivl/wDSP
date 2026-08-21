package com.radiorubka.wdsp.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;

import com.radiorubka.wdsp.Downloads;

/**
 * Opening a document, but starting in the folder this app saves into.
 *
 * <p>{@link ActivityResultContracts.OpenDocument} gives no way to say where the picker should
 * start, so it starts at the root of internal storage and the user walks down to
 * {@code Download/wDSP} by hand — on a touchscreen, in a car, every single time. The hint that
 * fixes it is one extra on the intent, and the only way to add it is to build the intent
 * ourselves.
 *
 * <p>It is a hint and nothing more: a picker that does not honour it opens where it would have
 * opened anyway, so there is no failure case to handle.
 */
public final class OpenInDownloads extends ActivityResultContracts.OpenDocument {

    @NonNull
    @Override
    public Intent createIntent(@NonNull Context context, @NonNull String[] input) {
        Intent intent = super.createIntent(context, input);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri start = Downloads.initialUri();
            if (start != null) {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, start);
            }
        }
        return intent;
    }
}
