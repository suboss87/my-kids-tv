package io.mykidstv;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Where tools/push-config.py sends a config.properties, so settings (and the parent PIN, and the TMDB key) change
 * without a rebuild: `adb shell "content write --uri content://io.mykidstv.config/config.properties?size=<bytes>
 * < /data/local/tmp/<file>"` (a file, not `exec-in`: on some TVs the stream arrives empty). Only adb's shell (or root)
 * may write, as with PhotoInbox. The file lands in the app's private inbox and
 * {@link Prefs#importConfig} applies and deletes it the next time the home screen, the screensaver or the settings
 * open (the home screen also looks every 15 s while it is on). Nothing in it is ever logged.
 */
public class ConfigInbox extends ContentProvider {
    static final String NAME = "config.properties";
    private static final int SHELL_UID = 2000;

    @Override public boolean onCreate() {
        return true;
    }

    static File inbox(android.content.Context c) {
        return new File(c.getFilesDir(), "config-inbox");
    }

    private static long size(Uri uri) {
        try {
            return Long.parseLong(uri.getQueryParameter("size"));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        int caller = Binder.getCallingUid();
        if (caller != SHELL_UID && caller != 0) throw new SecurityException("only adb sends settings");
        if (!NAME.equals(uri.getLastPathSegment()) || !mode.contains("w")) {
            throw new FileNotFoundException("settings only: content://io.mykidstv.config/" + NAME);
        }
        File in = inbox(getContext());
        if (!in.isDirectory() && !in.mkdirs()) throw new FileNotFoundException("can't create " + in);
        long expected = size(uri);
        // Hidden until all of it came (a send cut short also closes cleanly), then named for the next import.
        File part = new File(in, "." + NAME), done = new File(in, NAME);
        try {
            return ParcelFileDescriptor.open(part, ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE, new Handler(Looper.getMainLooper()), e -> {
                        boolean whole = e == null && (expected < 0 || part.length() == expected);
                        if (!whole || !part.renameTo(done)) part.delete();
                    });
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
