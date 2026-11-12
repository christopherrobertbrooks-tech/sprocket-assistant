package dev.sprocket.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Base64;

/**
 * His photos (phase 3b, the owner 2026-10-17: the + left of the text box, camera or gallery): each one is made at most
 * 1568 px on its long side (the size Claude reads best; bigger only costs more), turned the right way up, saved as a
 * JPEG in Sprocket's own files (files/photos) and sent to Claude with the message it belongs to. Nothing else sees it.
 */
final class Photo {
    private Photo() { }

    static final int LONG_EDGE = 1568;

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "photos");
        if (!d.isDirectory() && !d.mkdirs()) android.util.Log.w("Sprocket", "no photos folder");
        return d;
    }

    /** A picked or taken picture -> a saved JPEG's name (files/photos/NAME). Off the main thread. */
    static String save(Context c, Uri uri) throws Exception {
        Bitmap b;
        if (Build.VERSION.SDK_INT >= 28) {
            b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.getContentResolver(), uri), (dec, info, src) -> {
                int w = info.getSize().getWidth(), h = info.getSize().getHeight(), big = Math.max(w, h);
                if (big > LONG_EDGE) dec.setTargetSize(Math.round(w * (float) LONG_EDGE / big), Math.round(h * (float) LONG_EDGE / big));
                dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);   // the camera's EXIF turn is applied by ImageDecoder
            });
        } else {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= LONG_EDGE) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o); }
            if (b == null) throw new Exception("that picture couldn't be read");
            int big = Math.max(b.getWidth(), b.getHeight());
            if (big > LONG_EDGE)
                b = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * (float) LONG_EDGE / big), Math.round(b.getHeight() * (float) LONG_EDGE / big), true);
        }
        String name = "p" + System.currentTimeMillis() + ".jpg";
        try (FileOutputStream out = new FileOutputStream(new File(dir(c), name))) {
            if (!b.compress(Bitmap.CompressFormat.JPEG, 85, out)) throw new Exception("that picture couldn't be saved");
        }
        return name;
    }

    static final String SCREEN = "screen.jpg";
    static volatile long screenAt;             // when the side button last handed over his screen (0 = never)

    /** His screen, handed over by the side button: kept as one file (the next one replaces it), never in the chat. */
    static void saveScreen(Context c, Bitmap b) {
        try {
            int big = Math.max(b.getWidth(), b.getHeight());
            Bitmap s = big <= LONG_EDGE ? b : Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * (float) LONG_EDGE / big),
                    Math.round(b.getHeight() * (float) LONG_EDGE / big), true);
            try (FileOutputStream out = new FileOutputStream(new File(dir(c), SCREEN))) {
                if (!s.compress(Bitmap.CompressFormat.JPEG, 85, out)) throw new Exception("not saved");
            }
            screenAt = System.currentTimeMillis();
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "screen not kept: " + e);
        }
    }

    /** For Claude: the saved JPEG, base64. null if it's gone. */
    static String base64(Context c, String name) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(new File(dir(c), name).toPath()));
        } catch (Exception e) {
            return null;
        }
    }

    /** A small copy for the chat (about `px` wide), or null. */
    static Bitmap thumb(Context c, String name, int px) {
        File f = new File(dir(c), name);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= px) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Where the camera writes a new picture (served to it by PhotoProvider). */
    static File cameraFile(Context c) {
        File d = new File(c.getCacheDir(), "camera");
        if (!d.isDirectory() && !d.mkdirs()) android.util.Log.w("Sprocket", "no camera folder");
        return new File(d, "shot.jpg");
    }
}
