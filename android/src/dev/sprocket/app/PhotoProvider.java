package dev.sprocket.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * The one file the camera app may write: Photo.cameraFile, at content://dev.sprocket.app.photos/shot.jpg, granted
 * to the camera for that one picture (the app is built without AndroidX, so no FileProvider). Not exported; nothing
 * else is served.
 */
public final class PhotoProvider extends ContentProvider {
    static final String AUTHORITY = "dev.sprocket.app.photos";
    static final Uri SHOT = Uri.parse("content://" + AUTHORITY + "/shot.jpg");

    @Override public boolean onCreate() { return true; }

    private File file(Uri uri) throws FileNotFoundException {
        if (!"shot.jpg".equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        return Photo.cameraFile(getContext());
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode.contains("w") ? "rwt" : "r"));
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }

    @Override public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try { c.addRow(new Object[]{"shot.jpg", file(uri).length()}); } catch (FileNotFoundException ignored) { }
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { return null; }

    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }

    @Override public int update(Uri uri, ContentValues v, String sel, String[] args) { return 0; }
}
