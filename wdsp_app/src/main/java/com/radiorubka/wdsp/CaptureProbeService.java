package com.radiorubka.wdsp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/**
 * Holds a media projection while {@link CaptureProbe} measures with it.
 *
 * <h2>Why a service, and why in this order</h2>
 *
 * Since Android 10 a projection may only be obtained by an app that already has a foreground
 * service running, and that service has to declare {@code mediaProjection} as its type. So the
 * sequence is fixed and cannot be shortened: the consent activity gets the token, hands it here,
 * this goes into the foreground, and only then does the token become a projection. Asking for the
 * projection first throws.
 *
 * <p>Temporary. When the recorder itself exists this becomes part of it; for now it is here to
 * answer the questions the recorder is blocked on.
 */
public class CaptureProbeService extends Service {

    private static final String TAG = "CaptureProbe";
    private static final String CHANNEL = "wdsp_capture_probe";
    private static final int NOTIFICATION_ID = 4711;

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_PACKAGES = "packages";
    public static final String EXTRA_SECONDS = "seconds";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification());

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        String packages = intent.getStringExtra(EXTRA_PACKAGES);
        int seconds = intent.getIntExtra(EXTRA_SECONDS, 10);

        new Thread(() -> {
            try {
                probe(resultCode, data, packages, seconds);
            } catch (Throwable t) {
                Log.e(TAG, "the probe fell over", t);
            }
            stopForeground(true);
            stopSelf();
        }, "capture-probe").start();

        return START_NOT_STICKY;
    }

    private void probe(int resultCode, Intent data, String packages, int seconds) {
        MediaProjectionManager mgr =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            Log.e(TAG, "no projection service");
            return;
        }
        MediaProjection projection = mgr.getMediaProjection(resultCode, data);
        if (projection == null) {
            Log.e(TAG, "consent produced no projection");
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append("capture probe  ").append(new java.util.Date()).append('\n');
        report.append("android ").append(android.os.Build.VERSION.SDK_INT)
                .append("  ").append(android.os.Build.MODEL).append("\n\n");

        // Free the microphone first, on purpose.
        //
        // This platform opens exactly one input stream and attaches every later request to it
        // instead of opening a second - the finding behind MicrophoneGuard. The assistant holds
        // one at 16 kHz from boot, so a playback capture asked for while it is there does not get
        // a submix at all: it gets bolted onto the microphone, which is what every earlier
        // measurement was actually recording. Whether that is the whole story is exactly what
        // this run is for.
        MicrophoneGuard.Outcome mic = MicrophoneGuard.ensureOurs(this);
        report.append("microphone: ").append(mic).append("\n\n");
        Log.i(TAG, "microphone: " + mic);

        try {
            for (String pkg : targets(packages)) {
                pkg = pkg.trim();

                // A target written as tone:<usage> makes its own sound of that kind and then
                // listens for it. This is the only way to find out whether a sound the platform
                // is supposed to withhold - navigation, alarms - reaches a recording, because
                // waiting for the satnav to speak while a probe happens to be running is not a
                // method.
                ToneSource tone = null;
                if (pkg.startsWith("tone:")) {
                    int[] toneUsage = usagesOf("usage:" + pkg.substring("tone:".length()));
                    if (toneUsage == null) {
                        report.append(pkg).append("  unknown usage\n\n");
                        continue;
                    }
                    tone = new ToneSource();
                    try {
                        tone.start(toneUsage[0]);
                    } catch (Throwable t) {
                        report.append(pkg).append("  could not play: ").append(t).append("\n\n");
                        continue;
                    }
                    try {
                        CaptureProbe.Result r =
                                CaptureProbe.run(this, projection, pkg, -1, null, seconds);
                        report.append(CaptureProbe.describe(r)).append('\n');
                    } finally {
                        tone.stop();
                    }
                    continue;
                }

                int[] usages = usagesOf(pkg);
                if (usages != null) {
                    CaptureProbe.Result r =
                            CaptureProbe.run(this, projection, pkg, -1, usages, seconds);
                    report.append(CaptureProbe.describe(r)).append('\n');
                    continue;
                }
                int uid = uidOf(pkg);
                if (uid < 0 && !"*".equals(pkg)) {
                    report.append(pkg).append("  not installed\n\n");
                    continue;
                }
                CaptureProbe.Result r = CaptureProbe.run(this, projection, pkg, uid, seconds);
                report.append(CaptureProbe.describe(r)).append('\n');
            }
        } finally {
            projection.stop();
        }

        File out = new File(CaptureProbe.probeDir(this), "report.txt");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), Charset.forName("UTF-8"))) {
            w.write(report.toString());
        } catch (Throwable t) {
            Log.w(TAG, "could not write the report", t);
        }
        Log.i(TAG, "=== probe done ===\n" + report);
    }

    /**
     * What to point at.
     *
     * <p>Nothing named means the app that owns the media session right now, which is the case
     * that matters: the recorder will never be asked to guess either.
     */
    private String[] targets(String packages) {
        if (packages != null && !packages.trim().isEmpty()) {
            return packages.split(",");
        }
        String owner = NowPlaying.getInstance(this).playerPackage();
        if (owner == null || owner.isEmpty()) {
            Log.w(TAG, "nothing is playing and no package was named; taking everything");
            return new String[]{"*"};
        }
        return new String[]{owner};
    }

    /**
     * Reads a target written as {@code usage:media} rather than as a package name.
     *
     * <p>Returns null for anything that is not one, which is how the caller tells the two kinds
     * of target apart.
     */
    private int[] usagesOf(String target) {
        if (!target.startsWith("usage:")) return null;
        String name = target.substring("usage:".length());
        switch (name) {
            case "media":
                return new int[]{android.media.AudioAttributes.USAGE_MEDIA};
            case "game":
                return new int[]{android.media.AudioAttributes.USAGE_GAME};
            case "unknown":
                return new int[]{android.media.AudioAttributes.USAGE_UNKNOWN};
            case "nav":
                return new int[]{
                        android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE};
            case "assistant":
                return new int[]{android.media.AudioAttributes.USAGE_ASSISTANT};
            case "notification":
                return new int[]{android.media.AudioAttributes.USAGE_NOTIFICATION};
            case "alarm":
                return new int[]{android.media.AudioAttributes.USAGE_ALARM};
            default:
                Log.w(TAG, "unknown usage " + name);
                return null;
        }
    }

    /** {@code -1} when the package is not installed; {@code -1} for the "everything" marker too. */
    private int uidOf(String pkg) {
        if ("*".equals(pkg)) return -1;
        try {
            return getPackageManager().getApplicationInfo(pkg.trim(), 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    private Notification notification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("capture probe")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
    }
}
