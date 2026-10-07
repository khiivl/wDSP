package com.radiorubka.wdsp;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the last crash and the last swallowed failure where a person can hand them over.
 *
 * <p>Field reports said the screensaver "does not appear" on Tesla-style units, and nothing on the
 * unit could say why: the app caught its own failures and logged them to a logcat that scrolls
 * away in minutes, and a real crash left no trace at all once the service came back up. The screen
 * report (Settings, saved to a folder) now carries both, so a report from somebody else's car can
 * be read instead of guessed.
 */
public final class CrashLog {
    private static final String CRASH_FILE = "last_crash.txt";
    private static final String CAUGHT_FILE = "last_caught.txt";
    private static final int MAX_CHARS = 6000;
    private static volatile boolean installed;

    private CrashLog() {
    }

    /** Idempotent; called from every entry point of the process. Chains to the platform's handler. */
    public static synchronized void install(Context context) {
        if (installed || context == null) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write(app, CRASH_FILE, "CRASH in thread " + thread.getName(), error);
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /** A failure the app caught and carried on after - kept so it is not lost with the logcat. */
    public static void recordCaught(Context context, String where, Throwable error) {
        if (context == null || error == null) return;
        try {
            write(context.getApplicationContext(), CAUGHT_FILE, "CAUGHT in " + where, error);
        } catch (Throwable ignored) {
        }
    }

    /** Both records, for the screen report. */
    public static String describe(Context context) {
        if (context == null) return "";
        return section(context, CRASH_FILE, "LAST CRASH") + section(context, CAUGHT_FILE, "LAST CAUGHT FAILURE");
    }

    private static void write(Context app, String name, String what, Throwable error) throws IOException {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + what);
        pw.println("app " + versionName(app) + ", Android " + Build.VERSION.RELEASE);
        error.printStackTrace(pw);
        pw.flush();
        String text = sw.toString();
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS);
        // Synchronous and synced: after an uncaught exception the process has moments left, and a
        // preference written with apply() would not survive them.
        try (FileOutputStream out = new FileOutputStream(new File(app.getFilesDir(), name), false)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    private static String section(Context context, String name, String title) {
        File f = new File(context.getFilesDir(), name);
        if (!f.exists()) return title + ": none\n";
        try {
            return title + ":\n" + new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) + "\n";
        } catch (Throwable t) {
            return title + ": unreadable (" + t + ")\n";
        }
    }

    private static String versionName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
