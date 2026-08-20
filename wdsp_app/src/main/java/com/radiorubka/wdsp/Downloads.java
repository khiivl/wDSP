package com.radiorubka.wdsp;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;

/**
 * Writes a file straight into the public Downloads folder.
 *
 * <h2>Why this exists rather than a save dialog</h2>
 *
 * Saving used to open the system document picker and loading used to open the user's own file
 * manager, which looked like carelessness but was not: on these head units only
 * {@code com.android.documentsui} registers for {@code ACTION_CREATE_DOCUMENT}, while
 * {@code ACTION_OPEN_DOCUMENT} is also handled by the installed file manager, so the two actions
 * genuinely go to different apps and nothing in our code chose that.
 *
 * There is no way to send a *save* dialog to a file manager that does not offer one. So the
 * dialog is dropped instead: the file goes to {@code Download/wDSP/} on its own, the user is told
 * where, and both halves of the job then live in the same place - the one their file manager opens
 * by default.
 *
 * No permission is needed. Since API 29 an app may add its own entries to the MediaStore
 * collections without {@code WRITE_EXTERNAL_STORAGE}, and this app declares no storage permission
 * at all.
 */
public final class Downloads {
    private static final String TAG = "wDSP_Downloads";

    /** Everything the app writes goes in one folder, so it is easy to find and easy to clear. */
    private static final String FOLDER = "wDSP";

    private Downloads() {
    }

    /** Where a file will end up, in the form a person can look for. */
    public static String pathFor(String fileName) {
        return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/" + fileName;
    }

    /**
     * Creates the file and hands back a stream to write it.
     *
     * The entry is marked pending until {@link #finish} is called, so a half-written file is not
     * offered to anything that scans the Downloads folder.
     *
     * @return null if the platform refused, in which case the caller should fall back to a picker
     */
    public static Pending create(Context context, String fileName, String mimeType) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            values.put(MediaStore.Downloads.IS_PENDING, 1);

            Uri uri = context.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                Log.w(TAG, "the media store would not create " + fileName);
                return null;
            }
            OutputStream stream = context.getContentResolver().openOutputStream(uri);
            if (stream == null) {
                Log.w(TAG, "the media store created " + fileName + " but would not open it");
                context.getContentResolver().delete(uri, null, null);
                return null;
            }
            return new Pending(uri, stream, pathFor(fileName));
        } catch (Throwable t) {
            // Some vendor ROMs restrict the Downloads collection in ways the documentation does
            // not mention. Losing the file is not acceptable, so the caller gets null and falls
            // back to asking the user where to put it.
            Log.w(TAG, "could not write " + fileName + " to Downloads", t);
            return null;
        }
    }

    /** A file being written, together with where it will be visible once finished. */
    public static final class Pending {
        public final Uri uri;
        public final OutputStream stream;
        /** For example {@code Download/wDSP/wDSP_backup_2026-08-20_05-40.json}. */
        public final String displayPath;

        Pending(Uri uri, OutputStream stream, String displayPath) {
            this.uri = uri;
            this.stream = stream;
            this.displayPath = displayPath;
        }
    }

    /** Closes the file and makes it visible. Safe to call twice. */
    public static void finish(Context context, Pending pending) {
        if (pending == null) return;
        try {
            pending.stream.close();
        } catch (Throwable ignored) {
        }
        try {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(pending.uri, done, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "could not clear the pending flag on " + pending.displayPath, t);
        }
    }

    /** Removes a file that was never finished, so a failed save leaves nothing behind. */
    public static void discard(Context context, Pending pending) {
        if (pending == null) return;
        try {
            pending.stream.close();
        } catch (Throwable ignored) {
        }
        try {
            context.getContentResolver().delete(pending.uri, null, null);
        } catch (Throwable ignored) {
        }
    }
}
