package com.radiorubka.wdsp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * The one screen that has to exist so a projection can be asked for.
 *
 * <h2>Why there is no way around it</h2>
 *
 * A projection token comes back through {@code onActivityResult} and nowhere else, so something
 * with an activity result has to ask, however briefly. This is that something: no layout, no
 * theme, gone before it can be seen.
 *
 * <p>Whether the owner is shown a dialog is not decided here. The {@code PROJECT_MEDIA} app op
 * decides, and its default is to ask - so every recording costs a full-screen system dialog. That
 * is the honest price and it stays. An earlier version of this comment recommended setting the op
 * to {@code allow} over adb so the dialog would never appear; done on a bench, that hides from the
 * developer exactly what every tester sees. The owner's rule since 11.09.2026: every permission the
 * app needs is obtained through the app, on the bench as everywhere else.
 */
public class CaptureConsentActivity extends Activity {

    private static final String TAG = "CaptureProbe";
    private static final int REQUEST = 4711;

    private String packages;
    private int seconds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        packages = getIntent().getStringExtra(CaptureProbeService.EXTRA_PACKAGES);
        seconds = getIntent().getIntExtra(CaptureProbeService.EXTRA_SECONDS, 10);

        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            Log.e(TAG, "no projection service");
            finish();
            return;
        }
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST) return;
        if (resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "consent refused (" + resultCode + ")");
            finish();
            return;
        }
        Intent service = new Intent(this, CaptureProbeService.class)
                .putExtra(CaptureProbeService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureProbeService.EXTRA_RESULT_DATA, data)
                .putExtra(CaptureProbeService.EXTRA_PACKAGES, packages)
                .putExtra(CaptureProbeService.EXTRA_SECONDS, seconds);
        startForegroundService(service);
        finish();
    }
}
