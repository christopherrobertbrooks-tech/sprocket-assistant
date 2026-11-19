package dev.sprocket.app;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;

/**
 * The special cards in the chat: Studio pictures, videos and songs (full screen, play, save to phone), and the morning brief drawn from Markdown with its headings, bullets, tables and links. Moved out of MainActivity (roadmap: tidier code).
 */
final class ChatCards {
    final MainActivity host;

    ChatCards(MainActivity host) { this.host = host; }

    /** A studio result: the picture (tap: full screen), the video's first frame (tap: plays full screen), or the song
     * with Play/Pause and its lyrics on a tap; Save keeps a copy in his Pictures, Movies or Music (a Sprocket folder). */
    View studioCard(JSONObject j) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(host.round(MainActivity.PANEL, 16));
        card.setPadding(host.dp(14), host.dp(14), host.dp(14), host.dp(10));
        String kind = j.optString("kind"), title = Studio.title(j);
        File f = new File(StudioService.dir(MainActivity.app), j.optString("file"));
        if (!f.exists()) {
            card.addView(host.text("The studio's " + kind + " isn't on the phone any more.", 14, MainActivity.MUTED));
            return card;
        }
        int wide = Math.round(host.getResources().getDisplayMetrics().widthPixels * 0.84f) - host.dp(28);
        if (kind.equals("song")) {
            LinearLayout head = new LinearLayout(host);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = host.text(title.isEmpty() ? "Your song" : title, 17, MainActivity.TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView play = host.text(playLabel(f), 14, MainActivity.AMBER);
            play.setPadding(host.dp(12), host.dp(6), 0, host.dp(6));
            if (f.getPath().equals(MainActivity.songFile)) MainActivity.songLabel = play;
            play.setOnClickListener(v -> { playSong(f); MainActivity.songLabel = play; play.setText(playLabel(f)); });
            head.addView(play);
            card.addView(head);
        } else {
            Bitmap b = studioPicture(f, kind, wide);
            android.widget.FrameLayout frame = new android.widget.FrameLayout(host);
            ImageView iv = new ImageView(host);
            if (b != null) iv.setImageBitmap(b);
            iv.setAdjustViewBounds(true);                 // its own shape, no taller than about half the screen
            iv.setMaxWidth(wide);
            iv.setMaxHeight(Math.round(host.getResources().getDisplayMetrics().heightPixels * 0.55f));
            iv.setBackground(host.round(0xFF000000, 12));
            iv.setClipToOutline(true);
            frame.addView(iv, new android.widget.FrameLayout.LayoutParams(-2, -2));
            if (kind.equals("video")) {
                TextView playMark = host.text("\u25B6", 30, MainActivity.TEXT);
                playMark.setGravity(Gravity.CENTER);
                playMark.setBackground(host.round(0x99000000, 32));
                frame.addView(playMark, new android.widget.FrameLayout.LayoutParams(host.dp(64), host.dp(64), Gravity.CENTER));
            }
            frame.setContentDescription(kind.equals("video") ? "Play the video" : "Open the picture");
            frame.setOnClickListener(v -> fullScreen(f, kind.equals("video")));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
            fp.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(frame, fp);
        }
        TextView what = host.text(j.optString("prompt"), 13, MainActivity.MUTED);
        what.setMaxLines(3);
        what.setEllipsize(android.text.TextUtils.TruncateAt.END);
        what.setPadding(0, host.dp(8), 0, 0);
        card.addView(what);
        LinearLayout actions = new LinearLayout(host);
        actions.setPadding(0, host.dp(6), 0, 0);
        String lyrics = j.optJSONObject("note") == null ? "" : j.optJSONObject("note").optString("lyrics", "");
        TextView words = host.text(lyrics.trim(), 14, 0xFFC9CBD0);
        if (kind.equals("song") && !lyrics.isEmpty()) {
            boolean open = MainActivity.lyricsOpen.contains(f.getName());
            TextView lt = host.text(open ? "Hide lyrics" : "Lyrics", 13, MainActivity.TEXT);
            lt.setPadding(0, host.dp(6), host.dp(18), host.dp(6));
            lt.setOnClickListener(v -> {
                boolean now = !MainActivity.lyricsOpen.remove(f.getName());
                if (now) MainActivity.lyricsOpen.add(f.getName());
                words.setVisibility(now ? View.VISIBLE : View.GONE);
                lt.setText(now ? "Hide lyrics" : "Lyrics");
            });
            actions.addView(lt);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            TextView save = host.text("Save to phone", 13, MainActivity.TEXT);
            save.setPadding(0, host.dp(6), 0, host.dp(6));
            save.setContentDescription("Save the " + kind + " to the phone");
            save.setOnClickListener(v -> saveToPhone(f, kind, title));
            actions.addView(save);
        }
        card.addView(actions);
        if (kind.equals("song") && !lyrics.isEmpty()) {
            words.setLineSpacing(0, 1.2f);
            words.setTextIsSelectable(true);
            words.setPadding(0, host.dp(6), 0, host.dp(4));
            words.setVisibility(MainActivity.lyricsOpen.contains(f.getName()) ? View.VISIBLE : View.GONE);
            card.addView(words);
        }
        return card;
    }

    static String studioMeta(JSONObject j) {
        String kind = j.optString("kind"), q = j.optString("quality");
        String by = kind.equals("picture") ? "Qwen-Image 2.1" : kind.equals("video") ? (q.equals("draft") ? "FastWan, quick" : "Wan 2.2")
                : (q.equals("draft") ? "Song Maker, quick" : "Song Maker");
        int sec = j.optInt("seconds");
        return "Studio  \u00b7  " + by + (sec > 0 ? "  \u00b7  " + (sec < 90 ? sec + " s" : Math.round(sec / 60.0) + " min") : "");
    }

    /** The picture, or the video's first frame, at card width (kept, so redrawing the chat doesn't decode it again). */
    Bitmap studioPicture(File f, String kind, int px) {
        String key = "studio:" + f.getName() + px;
        Bitmap b = MainActivity.thumbs.get(key);
        if (b != null) return b;
        try {
            Bitmap full;
            if (kind.equals("video")) {
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try { r.setDataSource(f.getPath()); full = r.getFrameAtTime(0); } finally { r.release(); }
            } else {
                android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
                o.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, o.outWidth / px)));
                o.inJustDecodeBounds = false;
                full = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            }
            if (full == null) return null;
            b = full.getWidth() <= px ? full : Bitmap.createScaledBitmap(full, px, Math.round(full.getHeight() * (px / (float) full.getWidth())), true);
            MainActivity.thumbs.put(key, b);
            return b;
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "studio picture: " + e);
            return null;
        }
    }

    /** Full screen on black: the picture (tap to close) or the video, playing with the usual controls. */
    void fullScreen(File f, boolean video) {
        android.app.Dialog d = new android.app.Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout box = new android.widget.FrameLayout(host);
        box.setBackgroundColor(0xFF000000);
        if (video) {
            if (MainActivity.song != null && MainActivity.song.isPlaying()) { MainActivity.song.pause(); if (MainActivity.songLabel != null) MainActivity.songLabel.setText("\u25B6 Play"); }
            android.widget.VideoView vv = new android.widget.VideoView(host);
            android.widget.MediaController mc = new android.widget.MediaController(host);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            vv.setVideoPath(f.getPath());
            vv.setOnPreparedListener(mp -> { mp.setLooping(true); vv.start(); });
            box.addView(vv, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        } else {
            ImageView iv = new ImageView(host);
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(f.getPath()));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setOnClickListener(v -> d.dismiss());
            box.addView(iv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        d.setContentView(box);
        d.show();
    }

    static String playLabel(File f) {
        return MainActivity.song != null && f.getPath().equals(MainActivity.songFile) && MainActivity.song.isPlaying() ? "\u275A\u275A Pause" : "\u25B6 Play";
    }

    void playSong(File f) {
        try {
            if (MainActivity.song != null && f.getPath().equals(MainActivity.songFile)) {
                if (MainActivity.song.isPlaying()) MainActivity.song.pause(); else MainActivity.song.start();
            } else {
                if (MainActivity.song != null) MainActivity.song.release();
                MainActivity.song = new MediaPlayer();
                MainActivity.songFile = f.getPath();
                MainActivity.song.setDataSource(MainActivity.songFile);
                if (MainActivity.songLabel != null) MainActivity.songLabel.setText("\u25B6 Play");   // the song that was playing: stopped
                MainActivity.song.setOnCompletionListener(mp -> {
                    mp.release();
                    MainActivity.song = null;
                    MainActivity.songFile = null;
                    if (MainActivity.songLabel != null) MainActivity.songLabel.setText("\u25B6 Play");
                });
                MainActivity.song.prepare();
                MainActivity.song.start();
            }
        } catch (Exception e) {
            host.note("Couldn't play it: " + e.getMessage());
            MainActivity.song = null;
            MainActivity.songFile = null;
        }
    }

    /** A copy in his Pictures, Movies or Music, in a Sprocket folder (Android 10+: no permission needed). */
    void saveToPhone(File f, String kind, String title) {
        if (Build.VERSION.SDK_INT < 29) return;
        new Thread(() -> {
            try {
                boolean pic = kind.equals("picture"), vid = kind.equals("video");
                String folder = pic ? android.os.Environment.DIRECTORY_PICTURES : vid ? android.os.Environment.DIRECTORY_MOVIES
                        : android.os.Environment.DIRECTORY_MUSIC;
                String ext = f.getName().substring(f.getName().lastIndexOf('.'));
                String name = (title.isEmpty() ? "Sprocket " + kind + " " + f.getName().replace(ext, "") : title.replaceAll("[^A-Za-z0-9 _-]", "")) + ext;
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, pic ? "image/png" : vid ? "video/mp4" : "audio/mpeg");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, folder + "/Sprocket");
                v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri where = pic ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : vid ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri u = host.getContentResolver().insert(where, v);
                if (u == null) throw new Exception("Android said no");
                try (java.io.OutputStream o = host.getContentResolver().openOutputStream(u)) { Files.copy(f.toPath(), o); }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                host.getContentResolver().update(u, v, null, null);
                host.runOnUiThread(() -> host.note("Saved to " + folder + "/Sprocket"));
            } catch (Exception e) {
                host.runOnUiThread(() -> host.note("Couldn't save it: " + e.getMessage()));
            }
        }).start();
    }

    View briefCard(Chat.Turn t) {
        LinearLayout card = new LinearLayout(host);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(host.round(MainActivity.PANEL, 16));
        card.setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(14));
        markdownInto(card, t.text);
        return card;
    }

    /** Markdown drawn into a card: title, headings, bullets, tables and tappable links. */
    void markdownInto(LinearLayout card, String md) {
        for (Markdown.Block b : Markdown.parse(md)) {
            if (b.kind.equals("rows")) { card.addView(table(b)); continue; }
            boolean title = b.kind.equals("title"), head = b.kind.equals("heading"), bullet = b.kind.equals("bullet");
            TextView v = host.text("", title ? 19 : head ? 16 : 15, title || head ? MainActivity.TEXT : 0xFFC9CBD0);
            v.setText(styled((bullet ? "\u2022  " : "") + b.text));
            v.setLineSpacing(0, 1.15f);
            if (title || head) v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            v.setLinkTextColor(MainActivity.AMBER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(bullet ? host.dp(6) : 0, head ? host.dp(14) : host.dp(4), 0, 0);
            card.addView(v, lp);
        }
    }

    CharSequence styled(String line) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (Markdown.Span sp : Markdown.spans(line)) {
            int a = sb.length();
            sb.append(sp.text);
            if (sp.bold) sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), a, sb.length(), 0);
            if (sp.url != null) sb.setSpan(new android.text.style.URLSpan(sp.url), a, sb.length(), 0);
        }
        return sb;
    }

    View table(Markdown.Block b) {
        android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(host);
        android.widget.TableLayout tl = new android.widget.TableLayout(host);
        for (int r = 0; r < b.rows.size(); r++) {
            android.widget.TableRow row = new android.widget.TableRow(host);
            row.setBackgroundColor(r == 0 ? MainActivity.MINE : r % 2 == 0 ? 0xFF222327 : MainActivity.PANEL);
            for (String cell : b.rows.get(r)) {
                TextView c = host.text("", 14, r == 0 ? MainActivity.TEXT : 0xFFC9CBD0);
                c.setText(styled(cell));
                c.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                c.setLinkTextColor(MainActivity.AMBER);
                if (r == 0) c.setTypeface(Typeface.DEFAULT_BOLD);
                c.setPadding(host.dp(10), host.dp(7), host.dp(10), host.dp(7));
                c.setMaxWidth(host.dp(220));
                row.addView(c);
            }
            tl.addView(row);
        }
        sc.addView(tl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, host.dp(10), 0, host.dp(4));
        sc.setLayoutParams(lp);
        return sc;
    }
}
