package dev.sprocket.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sprocket, phase 2: chat with him. Claude (Sonnet; "Think harder" sends the next question to Opus) answers in his
 * persona, streamed onto the screen and read aloud by the phone's voice a sentence at a time (his own robot voice is
 * phase 4). The chat lives on the phone only (files/chat.json). Deliberately no microphone button next to Send.
 *
 * Self-test (scripts/emu-chat.sh): extras st_base + st_key (a made-up "sk-standin-" key; a real saved key is never sent
 * anywhere but Anthropic) + st_say send one message through the real screen and write files/selftest.json.
 */
public class MainActivity extends Activity {
    private static final int BG = 0xFF111214, PANEL = 0xFF1C1D21, MINE = 0xFF2A2B30, TEXT = 0xFFE6E7EA,
            MUTED = 0xFF8B8F97, FAINT = 0xFF5D6169, AMBER = 0xFFE3B341, ERR = 0xFFE5786D;

    // The conversation and the reply in progress belong to the app, not to one screen: Android can rebuild the screen
    // mid-reply (emulator test, 2026-10-17: the new screen loaded the chat before the old one saved it), and the new
    // screen must carry on with the same live conversation.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final HttpURLConnection[] live = new HttpURLConnection[1];
    private static Chat chat;
    private static boolean busy, stopped;
    private static Chat.Turn pending;          // the reply streaming in right now
    static Memory memory;                      // phase 3b: what he's had remembered (files/memory.json)
    static Lists lists;                        // his lists (files/lists.json)
    static Goals goals;                        // what he's working toward (files/goals.json)
    static Feed feed;                          // the morning brief: settings, AI news topics, the last week (files/feed.json)
    static volatile String about = "";         // his About-you page (files/about.md), edited in ⋯ Memory
    private static String attached;            // a photo waiting to go with his next message (its file name)
    private static final ExecutorService photoWork = Executors.newSingleThreadExecutor();
    private static final android.util.LruCache<String, Bitmap> thumbs = new android.util.LruCache<>(12);
    private static final int TAKE_PHOTO = 21, PICK_PHOTO = 22;
    private boolean showingMemory, showingLists, editingAbout, showingFeed, showingGoals;
    private final java.util.Set<Long> openDays = new java.util.HashSet<>();   // the Feed screen's opened days
    private LinearLayout attachRow;
    static MainActivity current;              // the screen on show, if any (Hands shows its cards there)
    static final int ASK_TOOL_PERMISSION = 8;       // the screen on show, if any
    private static Context app;                // for saving when no screen is on show
    private TextView pendingView;
    private static String persona = "";
    private LinearLayout list;
    private ScrollView scroll;
    private EditText box;
    private Button send;
    private static boolean harder;

    /** The side-button card (SprocketSession) while it shows: what it hears back about the question it asked. */
    interface Watch {
        void text(String soFar);
        void done(String text, String error);
        void spoken();              // the reply has been said: listen once for his follow-up
    }

    static volatile Watch watch;
    private static final Listen listen = new Listen();   // phase 4: Talk, and the one follow-up
    private static boolean spokenTurn;          // the question now being answered was said, not typed
    static final int ASK_MIC = 9;
    static final int ASK_SETUP = 11;
    private LinearLayout bottomBar;               // hidden on the first-open screen, with the top bar's buttons
    private final java.util.List<View> topButtons = new java.util.ArrayList<>();
    // first-time setup (Setup): the question on show, whether its username is awaited, what this step already tried
    static int setupAt = Setup.OFF;
    private static boolean setupGithub, setupReturning;
    private static final java.util.Set<String> setupTried = new java.util.HashSet<>();
    private static String testKey;     // self-test only: a made-up key, never saved

    /** True only in a self-test against the stand-in server: a made-up key, not Claude and not his own server. The self-test's
     * pretend answers (cards that answer themselves, words "heard" without the mic) work only then (Codex 041 #2). */
    static boolean standIn() {
        String b = Claude.base;
        return testKey != null && testKey.startsWith("sk-standin") && !b.startsWith(Claude.ANTHROPIC) && !Config.own;
    }

    // ------------------------------------------------------------------ setup
    /** Everything Sprocket needs, loaded once per run of the app: by this screen or by the side-button card, whichever
     * comes first (the card can open when the app hasn't been opened since the phone started). */
    static synchronized void init(Context c) {
        if (app != null && chat != null) return;
        app = c.getApplicationContext();
        SharedPreferencesLike sp = new SharedPreferencesLike(app);
        sp.loadConfig();
        Claude.useBrain(Config.own);
        persona = sp.persona().trim().isEmpty() ? defaultPersona() : sp.persona();
        chat = loadChat();
        memory = loadMemory();
        about = loadAbout();
        importAbout();
        lists = loadLists();
        goals = Goals.fromJson(read("goals.json"));
        feed = Feed.fromJson(read("feed.json"));
        FeedService.channels(app);
        StudioService.channels(app);
        if (Config.on("brief") && brainSetUp())   // a new install schedules nothing before its brain and setup (Codex 043 #3)
            try { FeedAlarm.schedule(app); } catch (Exception e) { android.util.Log.w("Sprocket", "brief alarm: " + e); }
        Claude.photos = name -> Photo.base64(app, name);
        VoiceOut.get(app);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        init(this);
        current = this;
        VoiceOut.get(this);                     // his voice (gateway) and the phone's, ready before the first reply
        setupAt = getSharedPreferences("sprocket", Context.MODE_PRIVATE).getInt("setup_at", Setup.OFF);
        buildScreen();
        render();
        if (b == null) askLocationOnce();
        if (b == null) {                        // a rebuilt screen must never replay the message that opened it
            Intent test = takeTest();
            selfTest(test);
            shared(getIntent(), test);
        }
        takeTextBack();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Intent test = takeTest();
        selfTest(test);
        shared(i, test);
        takeTextBack();
    }

    private static Intent pendingTest;          // from SelfTestActivity (adb only), never from this screen's own intent
    private static String[] pendingTextBack;    // the call announcer's Text, when he didn't say the words (caller, number)

    /** The call announcer's Text, to finish here: the question in the chat and "Text Toni: " in the box for his
     * words. Opens Sprocket now (behind the lock screen if it's locked, so it's there when he unlocks). */
    static synchronized void textBack(String who, String number) {
        pendingTextBack = new String[]{who, number};
        app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    private void takeTextBack() {
        String[] t;
        synchronized (MainActivity.class) { t = pendingTextBack; pendingTextBack = null; }
        if (t == null) return;
        chat.add("assistant", Calls.whatToSay(t[0]), System.currentTimeMillis());
        saveChat();
        render();
        String start = Calls.textAsk(t[0], t[1], "");
        box.setText(start);
        box.setSelection(start.length());
    }

    static synchronized void handTest(Intent i) { pendingTest = i; }

    private static synchronized Intent takeTest() {
        Intent i = pendingTest;
        pendingTest = null;                     // handed over once
        return i;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (editingAbout) { editingAbout = false; render(); return; }                                    // Back: no changes
        if (showingMemory || showingLists || showingFeed || showingGoals) {                                // Back: the chat
            showingMemory = showingLists = showingFeed = showingGoals = false;
            render();
            return;
        }
        super.onBackPressed();
    }

    /** A picture shared to Sprocket (a screenshot's Share button, the gallery): attached to his next message. */
    private void shared(Intent i, Intent test) {
        if (!Intent.ACTION_SEND.equals(i.getAction()) || i.getType() == null) return;
        if (i.getType().startsWith("image/")) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) attach(u);
        } else if (i.getType().startsWith("text/")) {          // an article he shared: research it (the owner 2026-10-17)
            String base = test == null ? null : test.getStringExtra("st_base"), k = test == null ? null : test.getStringExtra("st_key");
            if (base != null && k != null && k.startsWith("sk-standin")) { Claude.base = base; testKey = k; }   // self-test
            Research.Shared sh = Research.shared(i.getStringExtra(Intent.EXTRA_SUBJECT), i.getStringExtra(Intent.EXTRA_TEXT));
            if (!sh.title.isEmpty()) startResearch(sh.url.isEmpty() ? sh.title : "", sh.url, sh.title);
        }
        i.setAction(Intent.ACTION_MAIN);        // handled once, not again when the screen is rebuilt
    }

    // ------------------------------------------------------------------ research
    /** A research job: his line in the chat, then the job in the background (ResearchService). */
    void startResearch(String question, String url, String title) {
        if (!ready()) { note("Sprocket needs its brain set up first (\u22ef > Settings)."); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 10);   // to say when it's ready
        chat.add("user", "Research: " + title + (url.isEmpty() || url.equals(title) ? "" : "\n" + url), System.currentTimeMillis());
        saveChat();
        render();
        ResearchService.start(this, question, url, title);
    }

    /** The brain switched on now, with its key (the self-test's made-up one only against the stand-in), taken once per
     * question so every round of it goes to the same place (Codex 040 #6). */
    static Claude.Brain brain() {
        SharedPreferencesLike p = new SharedPreferencesLike(app);
        return Claude.Brain.now(q -> testKey != null ? testKey : q ? p.ownKey() : p.key());
    }

    /** Main thread: this morning's brief into the chat (a card) and the Feed screen's week. */
    static void addFeed(Feed.Brief b) {
        try {
            Chat.Turn t = chat.add("assistant", b.toJson(), b.at);
            t.kind = "feed";
            t.model = Config.own ? Config.model() : Claude.SONNET;
            t.cost = b.cost;
            synchronized (feed) { feed.keep(b); saveFeed(); }
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "brief not kept: " + e);
        }
        saveChat();
        if (current != null) current.render();
    }

    /** Main thread: something the studio made, as a card in the chat; said aloud (one line) if he is in the app. */
    static void addStudio(JSONObject job) {
        Chat.Turn t = chat.add("assistant", job.toString(), System.currentTimeMillis());
        t.kind = "studio";
        saveChat();
        if (current != null) {
            current.render();
            if (voiceOn() && !busy) {
                VoiceOut v = VoiceOut.get(app);
                v.add(Studio.ready(job) + ", boss.", "proud");
                v.end(null);
            }
        }
    }

    static void addStudioError(String kind, String why) {
        chat.add("error", "The studio couldn't make your " + kind + ": " + why, System.currentTimeMillis());
        saveChat();
        if (current != null) current.render();
    }

    /** The newest photo he sent in the last half hour (a video can start from it), or null. */
    private static String lastPhoto() {
        long since = System.currentTimeMillis() - 30 * 60_000L;
        for (int i = chat.turns.size() - 1; i >= 0; i--) {
            Chat.Turn t = chat.turns.get(i);
            if (t.at < since) break;
            if (t.role.equals("user") && !t.photos.isEmpty()) return t.photos.get(t.photos.size() - 1);
        }
        return null;
    }

    /** Main thread: a finished brief into the chat; said aloud (one line) if he is in the app. */
    static void addBrief(Research.Brief b, Claude.Reply r, String title) {
        Chat.Turn t = chat.add("assistant", b.markdown + (b.say.isEmpty() ? "" : "\n\n**Bottom line:** " + b.say),
                System.currentTimeMillis());
        t.kind = "brief";
        t.model = r.model;
        t.cost = r.cost();
        saveChat();
        if (current != null) {
            current.render();
            if (voiceOn() && !busy && !b.say.isEmpty()) {
                VoiceOut v = VoiceOut.get(app);
                v.add("Research is in, boss.", "proud");
                v.add(b.say, "deadpan");
                v.end(null);
            }
        }
        if (testKey != null) {                  // self-test: what came back
            try {
                JSONObject o = new JSONObject().put("title", b.title).put("say", b.say).put("chars", b.markdown.length())
                        .put("cost", r.cost()).put("searches", r.searches);
                Files.write(new File(app.getExternalFilesDir(null), "researchtest.json").toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
    }

    static void addResearchError(String title, String why) {
        chat.add("error", "Research on \u201c" + title + "\u201d didn't finish: " + why + ".", System.currentTimeMillis());
        saveChat();
        if (current != null) current.render();
    }

    /** A brief drawn as one card: title, headings, bullets, tables (sideways scrolling) and tappable links. */
    private static MediaPlayer song;              // the studio song playing from a card (one at a time)
    private static String songFile;
    private static TextView songLabel;            // its card's Play/Pause, changed in place (a redraw jumps to the end)
    private static final java.util.Set<String> lyricsOpen = new java.util.HashSet<>();

    /** A studio result: the picture (tap: full screen), the video's first frame (tap: plays full screen), or the song
     * with Play/Pause and its lyrics on a tap; Save keeps a copy in his Pictures, Movies or Music (a Sprocket folder). */
    private View studioCard(JSONObject j) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(PANEL, 16));
        card.setPadding(dp(14), dp(14), dp(14), dp(10));
        String kind = j.optString("kind"), title = Studio.title(j);
        File f = new File(StudioService.dir(app), j.optString("file"));
        if (!f.exists()) {
            card.addView(text("The studio's " + kind + " isn't on the phone any more.", 14, MUTED));
            return card;
        }
        int wide = Math.round(getResources().getDisplayMetrics().widthPixels * 0.84f) - dp(28);
        if (kind.equals("song")) {
            LinearLayout head = new LinearLayout(this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(title.isEmpty() ? "Your song" : title, 17, TEXT);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView play = text(playLabel(f), 14, AMBER);
            play.setPadding(dp(12), dp(6), 0, dp(6));
            if (f.getPath().equals(songFile)) songLabel = play;
            play.setOnClickListener(v -> { playSong(f); songLabel = play; play.setText(playLabel(f)); });
            head.addView(play);
            card.addView(head);
        } else {
            Bitmap b = studioPicture(f, kind, wide);
            android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
            ImageView iv = new ImageView(this);
            if (b != null) iv.setImageBitmap(b);
            iv.setAdjustViewBounds(true);                 // its own shape, no taller than about half the screen
            iv.setMaxWidth(wide);
            iv.setMaxHeight(Math.round(getResources().getDisplayMetrics().heightPixels * 0.55f));
            iv.setBackground(round(0xFF000000, 12));
            iv.setClipToOutline(true);
            frame.addView(iv, new android.widget.FrameLayout.LayoutParams(-2, -2));
            if (kind.equals("video")) {
                TextView playMark = text("\u25B6", 30, TEXT);
                playMark.setGravity(Gravity.CENTER);
                playMark.setBackground(round(0x99000000, 32));
                frame.addView(playMark, new android.widget.FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
            }
            frame.setContentDescription(kind.equals("video") ? "Play the video" : "Open the picture");
            frame.setOnClickListener(v -> fullScreen(f, kind.equals("video")));
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
            fp.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(frame, fp);
        }
        TextView what = text(j.optString("prompt"), 13, MUTED);
        what.setMaxLines(3);
        what.setEllipsize(android.text.TextUtils.TruncateAt.END);
        what.setPadding(0, dp(8), 0, 0);
        card.addView(what);
        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, dp(6), 0, 0);
        String lyrics = j.optJSONObject("note") == null ? "" : j.optJSONObject("note").optString("lyrics", "");
        TextView words = text(lyrics.trim(), 14, 0xFFC9CBD0);
        if (kind.equals("song") && !lyrics.isEmpty()) {
            boolean open = lyricsOpen.contains(f.getName());
            TextView lt = text(open ? "Hide lyrics" : "Lyrics", 13, TEXT);
            lt.setPadding(0, dp(6), dp(18), dp(6));
            lt.setOnClickListener(v -> {
                boolean now = !lyricsOpen.remove(f.getName());
                if (now) lyricsOpen.add(f.getName());
                words.setVisibility(now ? View.VISIBLE : View.GONE);
                lt.setText(now ? "Hide lyrics" : "Lyrics");
            });
            actions.addView(lt);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            TextView save = text("Save to phone", 13, TEXT);
            save.setPadding(0, dp(6), 0, dp(6));
            save.setContentDescription("Save the " + kind + " to the phone");
            save.setOnClickListener(v -> saveToPhone(f, kind, title));
            actions.addView(save);
        }
        card.addView(actions);
        if (kind.equals("song") && !lyrics.isEmpty()) {
            words.setLineSpacing(0, 1.2f);
            words.setTextIsSelectable(true);
            words.setPadding(0, dp(6), 0, dp(4));
            words.setVisibility(lyricsOpen.contains(f.getName()) ? View.VISIBLE : View.GONE);
            card.addView(words);
        }
        return card;
    }

    private static String studioMeta(JSONObject j) {
        String kind = j.optString("kind"), q = j.optString("quality");
        String by = kind.equals("picture") ? "Qwen-Image 2.1" : kind.equals("video") ? (q.equals("draft") ? "FastWan, quick" : "Wan 2.2")
                : (q.equals("draft") ? "Song Maker, quick" : "Song Maker");
        int sec = j.optInt("seconds");
        return "Studio  \u00b7  " + by + (sec > 0 ? "  \u00b7  " + (sec < 90 ? sec + " s" : Math.round(sec / 60.0) + " min") : "");
    }

    /** The picture, or the video's first frame, at card width (kept, so redrawing the chat doesn't decode it again). */
    private Bitmap studioPicture(File f, String kind, int px) {
        String key = "studio:" + f.getName() + px;
        Bitmap b = thumbs.get(key);
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
            thumbs.put(key, b);
            return b;
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "studio picture: " + e);
            return null;
        }
    }

    /** Full screen on black: the picture (tap to close) or the video, playing with the usual controls. */
    private void fullScreen(File f, boolean video) {
        android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setBackgroundColor(0xFF000000);
        if (video) {
            if (song != null && song.isPlaying()) { song.pause(); if (songLabel != null) songLabel.setText("\u25B6 Play"); }
            android.widget.VideoView vv = new android.widget.VideoView(this);
            android.widget.MediaController mc = new android.widget.MediaController(this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            vv.setVideoPath(f.getPath());
            vv.setOnPreparedListener(mp -> { mp.setLooping(true); vv.start(); });
            box.addView(vv, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        } else {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(f.getPath()));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setOnClickListener(v -> d.dismiss());
            box.addView(iv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        }
        d.setContentView(box);
        d.show();
    }

    private static String playLabel(File f) {
        return song != null && f.getPath().equals(songFile) && song.isPlaying() ? "\u275A\u275A Pause" : "\u25B6 Play";
    }

    private void playSong(File f) {
        try {
            if (song != null && f.getPath().equals(songFile)) {
                if (song.isPlaying()) song.pause(); else song.start();
            } else {
                if (song != null) song.release();
                song = new MediaPlayer();
                songFile = f.getPath();
                song.setDataSource(songFile);
                if (songLabel != null) songLabel.setText("\u25B6 Play");   // the song that was playing: stopped
                song.setOnCompletionListener(mp -> {
                    mp.release();
                    song = null;
                    songFile = null;
                    if (songLabel != null) songLabel.setText("\u25B6 Play");
                });
                song.prepare();
                song.start();
            }
        } catch (Exception e) {
            note("Couldn't play it: " + e.getMessage());
            song = null;
            songFile = null;
        }
    }

    /** A copy in his Pictures, Movies or Music, in a Sprocket folder (Android 10+: no permission needed). */
    private void saveToPhone(File f, String kind, String title) {
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
                Uri u = getContentResolver().insert(where, v);
                if (u == null) throw new Exception("Android said no");
                try (java.io.OutputStream o = getContentResolver().openOutputStream(u)) { Files.copy(f.toPath(), o); }
                v.clear();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(u, v, null, null);
                runOnUiThread(() -> note("Saved to " + folder + "/Sprocket"));
            } catch (Exception e) {
                runOnUiThread(() -> note("Couldn't save it: " + e.getMessage()));
            }
        }).start();
    }

    private View briefCard(Chat.Turn t) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(PANEL, 16));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        markdownInto(card, t.text);
        return card;
    }

    /** Markdown drawn into a card: title, headings, bullets, tables and tappable links. */
    private void markdownInto(LinearLayout card, String md) {
        for (Markdown.Block b : Markdown.parse(md)) {
            if (b.kind.equals("rows")) { card.addView(table(b)); continue; }
            boolean title = b.kind.equals("title"), head = b.kind.equals("heading"), bullet = b.kind.equals("bullet");
            TextView v = text("", title ? 19 : head ? 16 : 15, title || head ? TEXT : 0xFFC9CBD0);
            v.setText(styled((bullet ? "\u2022  " : "") + b.text));
            v.setLineSpacing(0, 1.15f);
            if (title || head) v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            v.setLinkTextColor(AMBER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(bullet ? dp(6) : 0, head ? dp(14) : dp(4), 0, 0);
            card.addView(v, lp);
        }
    }

    private CharSequence styled(String line) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        for (Markdown.Span sp : Markdown.spans(line)) {
            int a = sb.length();
            sb.append(sp.text);
            if (sp.bold) sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), a, sb.length(), 0);
            if (sp.url != null) sb.setSpan(new android.text.style.URLSpan(sp.url), a, sb.length(), 0);
        }
        return sb;
    }

    private View table(Markdown.Block b) {
        android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
        android.widget.TableLayout tl = new android.widget.TableLayout(this);
        for (int r = 0; r < b.rows.size(); r++) {
            android.widget.TableRow row = new android.widget.TableRow(this);
            row.setBackgroundColor(r == 0 ? MINE : r % 2 == 0 ? 0xFF222327 : PANEL);
            for (String cell : b.rows.get(r)) {
                TextView c = text("", 14, r == 0 ? TEXT : 0xFFC9CBD0);
                c.setText(styled(cell));
                c.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                c.setLinkTextColor(AMBER);
                if (r == 0) c.setTypeface(Typeface.DEFAULT_BOLD);
                c.setPadding(dp(10), dp(7), dp(10), dp(7));
                c.setMaxWidth(dp(220));
                row.addView(c);
            }
            tl.addView(row);
        }
        sc.addView(tl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, dp(4));
        sc.setLayoutParams(lp);
        return sc;
    }

    // Back to Sprocket after the hands (the owner 2026-11-10: "if the actual sprocket app was open when this ran it should
    // return to the app"): a question asked on Sprocket's screen that used the screen tool comes back here when it's
    // answered, unless the brain chose to leave what he asked to see on screen (screen action "stay").
    private static volatile boolean resumed, startedInApp, usedHands;
    private static volatile String askedNow = "";

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        VoiceOut.get(this).stop();             // he left: stop talking, and stop listening
        handler.removeCallbacks(stillThereTimer);
        if (listen.on()) { listen.cancel(); styleSend(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        current = this;
        if (importAbout() && showingMemory && !editingAbout) render();
        briefIfMissed();
        if (setupReturning && Setup.running(setupAt)) {      // back from Android's settings page: give the switch a moment
            setupReturning = false;
            handler.postDelayed(() -> { if (Setup.running(setupAt)) setupContinue(); }, 700);
        }
        StudioService.resume(this);           // anything the studio was still making when Android ended the app
        Place.refresh(this);                  // the town, if it's older than 20 minutes (background)
    }

    /** This morning's brief didn't happen (the phone was off, or Android held it back): make it now that he's here. Only
     * with his real brain (never during a test against the stand-in) and only on the day it was due. */
    private void briefIfMissed() {
        if (!Config.on("brief") || !ready() || !Claude.base.equals(Config.base()) || Setup.running(setupAt)) return;
        long now = System.currentTimeMillis(), due = Feed.nextAt(now - 24 * 3600_000L, java.time.ZoneId.systemDefault(), Feed.HOUR, Feed.MINUTE);
        long last;
        synchronized (feed) { last = feed.lastAt; }
        if (last == 0) return;                    // a new install hasn't missed one: its first brief is the next 6 am
        if (last < due && now - due < 18 * 3600_000L) FeedService.start(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (current == this) current = null;   // the worker keeps going: the next screen picks the reply up
    }

    private SharedPreferencesLike prefs() { return new SharedPreferencesLike(this); }

    /** Tiny wrapper so the key, the voice switch and nothing else live in one private settings file. */
    static final class SharedPreferencesLike {
        private final android.content.SharedPreferences p;
        SharedPreferencesLike(Context c) { p = c.getSharedPreferences("sprocket", Context.MODE_PRIVATE); }
        String key() { return p.getString("claude_key", ""); }
        void key(String k) { p.edit().putString("claude_key", k.trim()).apply(); }
        boolean voice() { return p.getBoolean("voice", true); }
        void voice(boolean v) { p.edit().putBoolean("voice", v).apply(); }
        /** His own server's key, if it needs one (named qwen_key from stage 2, when that server was always Qwen). */
        String ownKey() { return p.getString("qwen_key", ""); }
        void ownKey(String k) { p.edit().putString("qwen_key", k.trim()).apply(); }
        String activeKey() { return Config.own ? ownKey() : key(); }
        /** Settings (Config) as saved; a phone set up before them keeps its brain: stage 2's "brain" = "qwen". */
        void loadConfig() {
            Config.reset();
            String saved = p.getString("config", "");
            if (!saved.isEmpty()) Config.fromJson(saved);
            else if ("qwen".equals(p.getString("brain", ""))) { Config.own = true; Config.ownName = "Qwen"; Config.ownModel = "qwen"; }
        }
        void saveConfig() { p.edit().putString("config", Config.toJson()).apply(); }
        /** His own wording of Sprocket's personality, or "" for the one the app comes with. */
        String persona() { return p.getString("persona", ""); }
        void persona(String text) { p.edit().putString("persona", text).apply(); }
        String notionKey() { return p.getString("notion_key", ""); }
        void notionKey(String k) { p.edit().putString("notion_key", k.trim()).remove("notion_page").apply(); }
        String notionPage() { return p.getString("notion_page", ""); }
        void notionPage(String id) { p.edit().putString("notion_page", id).apply(); }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private Button chip(String t) {
        Button c = new Button(this);
        c.setText(t);
        c.setAllCaps(false);
        c.setTextSize(13);
        c.setMinHeight(0);
        c.setMinimumHeight(0);
        c.setPadding(dp(12), dp(6), dp(12), dp(6));
        return c;
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(10), dp(8), dp(8));
        TextView title = text(getPackageName().endsWith(".test") ? "Sprocket Test" : "Sprocket", 20, TEXT);   // the test build says so
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        // Think harder and the voice switch live in the ⋯ menu (the owner 2026-10-17): the bar holds the name and one button
        // the morning brief and goals, by the title (the owner 2026-10-17: "an icon for the feed and goals to the right of the
        // sprocket title")
        for (int k = 0; k < 2; k++) {
            boolean isFeed = k == 0;
            android.widget.ImageButton b = new android.widget.ImageButton(this);
            b.setImageResource(isFeed ? R.drawable.ic_feed : R.drawable.ic_goals);
            b.setBackground(round(PANEL, 18));
            b.setContentDescription(isFeed ? "Feed" : "Goals");
            b.setOnClickListener(v -> {
                showingMemory = showingLists = false;
                showingFeed = isFeed;
                showingGoals = !isFeed;
                render();
            });
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(44), dp(36));
            ip.setMargins(dp(6), 0, 0, 0);
            top.addView(b, ip);
            topButtons.add(b);
        }
        Button more = chip("⋯");
        more.setTextSize(18);
        more.setBackground(round(PANEL, 18));
        more.setTextColor(TEXT);
        more.setOnClickListener(this::menu);
        topButtons.add(more);
        more.setMinWidth(0);
        more.setMinimumWidth(0);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(52), dp(36));   // the same size as the icons beside it
        cp.setMargins(dp(6), 0, 0, 0);
        top.addView(more, cp);
        root.addView(top);

        scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(12));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        attachRow = new LinearLayout(this);       // the photo waiting to be sent, with a way to take it back
        attachRow.setGravity(Gravity.CENTER_VERTICAL);
        attachRow.setPadding(dp(14), dp(6), dp(14), 0);
        attachRow.setVisibility(View.GONE);
        root.addView(attachRow);

        LinearLayout bottom = new LinearLayout(this);
        bottomBar = bottom;
        bottom.setGravity(Gravity.BOTTOM);
        bottom.setPadding(dp(10), dp(6), dp(10), dp(10));
        // a picture: as far from Send as the screen allows (the owner: the Claude app's mic sat on top of its Send)
        Button plus = chip("+");
        plus.setTextSize(20);
        plus.setBackground(round(PANEL, 23));
        plus.setTextColor(TEXT);
        plus.setOnClickListener(this::photoMenu);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(46), dp(46));
        pp.setMargins(0, 0, dp(8), 0);
        bottom.addView(plus, pp);
        box = new EditText(this);
        box.setHint("Talk to Sprocket");
        box.setTextColor(TEXT);
        box.setHintTextColor(FAINT);
        box.setTextSize(16);
        box.setMaxLines(4);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.setBackground(round(PANEL, 22));
        box.setPadding(dp(16), dp(10), dp(16), dp(10));
        box.setImeOptions(EditorInfo.IME_ACTION_SEND);
        if (Build.VERSION.SDK_INT >= 31) {
            // a picture pasted into the box (long-press Paste, or the keyboard's clipboard: Samsung's keeps screenshots)
            box.setOnReceiveContentListener(new String[]{"image/*"}, (view, payload) -> {
                ClipData clip = payload.getClip();
                for (int k = 0; k < clip.getItemCount(); k++) {
                    Uri u = clip.getItemAt(k).getUri();
                    if (u != null) { attach(u); return null; }   // the picture waits for his next message
                }
                return payload;                     // plain text pastes as usual
            });
        }
        bottom.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        send = new Button(this);
        send.setText("Send");
        send.setAllCaps(false);
        send.setTextSize(15);
        send.setOnClickListener(v -> sendPressed());
        box.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) { if (send != null) styleSend(); }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(46));
        sp.setMargins(dp(10), 0, 0, 0);          // the only button down here: nothing to hit by mistake
        bottom.addView(send, sp);
        root.addView(bottom);
        setContentView(root);
        styleHarder();
        styleSend();
        showAttached();
    }

    // ------------------------------------------------------------------ photos
    private void photoMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Take photo");
        m.getMenu().add(0, 2, 1, "Choose photo");
        m.setOnMenuItemClickListener(it -> {
            try {
                if (it.getItemId() == 1) {
                    File shot = Photo.cameraFile(this);
                    if (shot.exists() && !shot.delete()) android.util.Log.w("Sprocket", "old shot kept");
                    Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, PhotoProvider.SHOT)
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.setClipData(ClipData.newRawUri("photo", PhotoProvider.SHOT));   // so the grant reaches the camera
                    startActivityForResult(i, TAKE_PHOTO);
                } else {
                    Intent i = Build.VERSION.SDK_INT >= 33 ? new Intent(MediaStore.ACTION_PICK_IMAGES)   // no permission needed
                            : new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
                    startActivityForResult(i, PICK_PHOTO);
                }
            } catch (android.content.ActivityNotFoundException e) {
                note("No app on this phone can do that.");
            }
            return true;
        });
        m.show();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK) return;
        if (code == TAKE_PHOTO) attach(Uri.fromFile(Photo.cameraFile(this)));
        else if (code == PICK_PHOTO && data != null && data.getData() != null) attach(data.getData());
    }

    /** A picture becomes the photo waiting for his next message (made small and saved off the main thread). */
    private void attach(Uri u) {
        photoWork.execute(() -> {
            try {
                String name = Photo.save(app, u);
                runOnUiThread(() -> { attached = name; if (current != null) current.showAttached(); });
            } catch (Exception e) {
                runOnUiThread(() -> note("That picture couldn't be read."));
            }
        });
    }

    private void showAttached() {
        attachRow.removeAllViews();
        if (attached == null) { attachRow.setVisibility(View.GONE); return; }
        Bitmap b = thumb(attached, dp(64));
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(round(PANEL, 10));
        iv.setClipToOutline(true);
        attachRow.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
        Button remove = chip("Remove");
        remove.setBackground(round(MINE, 18));
        remove.setTextColor(TEXT);
        remove.setOnClickListener(v -> { attached = null; showAttached(); });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(36));
        rp.setMargins(dp(12), 0, 0, 0);
        attachRow.addView(remove, rp);
        attachRow.setVisibility(View.VISIBLE);
    }

    private Bitmap thumb(String name, int px) {
        Bitmap b = thumbs.get(name + px);
        if (b == null && (b = Photo.thumb(app, name, px)) != null) thumbs.put(name + px, b);
        return b;
    }

    private void note(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

    /** Think harder is on for the next question only: the text box says so, outlined amber, until it's sent. */
    private void styleHarder() {
        GradientDrawable g = round(PANEL, 22);
        if (harder) g.setStroke(dp(2), AMBER);
        box.setBackground(g);
        box.setHint(harder ? "Thinking harder on this one (Opus)" : "Talk to Sprocket");
        box.setHintTextColor(harder ? AMBER : FAINT);
    }

    /** Stop while he answers; Listening while the mic is on; Talk when the box is empty; Send when there are words. */
    private void styleSend() {
        boolean empty = box.getText().toString().trim().isEmpty() && attached == null;
        String label = busy ? "Stop" : listen.on() ? "Listening\u2026" : empty ? "Talk" : "Send";
        send.setText(label);
        send.setBackground(round(busy ? MINE : listen.on() ? AMBER : TEXT, 23));
        send.setTextColor(busy ? TEXT : 0xFF111111);
    }

    private void sendPressed() {
        if (busy) { stop(); return; }
        if (listen.on()) { listen.finish(); return; }   // done talking: use what was heard
        if (box.getText().toString().trim().isEmpty() && attached == null) talk(false);
        else sendTyped();
    }

    /** Talk: the mic on, his words into the box as he says them, then asked. followUp: the one listen after a spoken
     * reply, which ends quietly if he says nothing. */
    private void talk(boolean followUp) {
        if (!ready()) return;
        if (!Listen.scripted() && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!followUp) requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, ASK_MIC);
            return;
        }
        VoiceOut.get(this).stop();             // never listen to himself
        long began = System.currentTimeMillis();
        hearingWords = false;
        boolean started = listen.start(this, new Listen.Heard() {
            public void partial(String soFar) { hearingWords = true; box.setText(soFar); box.setSelection(soFar.length()); }
            public void heard(String words) {
                box.setText("");
                handler.removeCallbacks(stillThereTimer);
                Conversation.Next next = convo.heard(words, System.currentTimeMillis());
                android.util.Log.i("Sprocket", "conversation: heard, " + next);
                if (next == Conversation.Next.END) { styleSend(); return; }     // "that's all for now": nothing sent
                if (next == Conversation.Next.LISTEN) { talk(true); return; }   // "I'm here" after Still there?
                spokenTurn = true;
                ask(words, null);
            }
            public void nothing() {
                if (!followUp) box.setText("");
                styleSend();
                if (!followUp) return;
                Conversation.Next next = convo.nothing(began, System.currentTimeMillis());
                android.util.Log.i("Sprocket", "conversation: quiet, " + next);
                if (next == Conversation.Next.LISTEN) talk(true);                  // still quiet: keep listening
                else if (next == Conversation.Next.STILL_THERE) stillThere();
            }
        });
        if (!started && !followUp) note("This phone has no speech recognizer.");
        if (started && followUp) {
            handler.removeCallbacks(stillThereTimer);
            handler.postDelayed(stillThereTimer, convo.due(System.currentTimeMillis()));
        }
        styleSend();
    }

    // the back-and-forth after a spoken answer (V2 item 3): stop phrases, and "Still there?" after 12 s of quiet
    private static final Conversation convo = new Conversation();
    private static boolean hearingWords;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stillThereTimer = () -> {
        if (!listen.on() || convo.timeUp(System.currentTimeMillis(), hearingWords) != Conversation.Next.STILL_THERE) return;
        listen.cancel();
        stillThere();
    };

    /** The reply has been said: listen for what's next, until he's done. */
    private void followUp() {
        convo.begin(System.currentTimeMillis());
        talk(true);
    }

    private void stillThere() {
        handler.removeCallbacks(stillThereTimer);
        android.util.Log.i("Sprocket", "conversation: still there?");
        if (voiceOn()) VoiceOut.get(this).sayThen("Still there?", "deadpan", () -> talk(true));
        else talk(true);
    }

    private void menu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Think harder, next question").setCheckable(true).setChecked(harder);
        m.getMenu().add(0, 2, 1, "Voice").setCheckable(true).setChecked(prefs().voice());
        m.getMenu().add(0, 6, 2, "Lists");
        m.getMenu().add(0, 5, 2, "Memory");
        m.getMenu().add(0, 3, 3, "New chat");
        m.getMenu().add(0, 7, 4, "Settings");
        m.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 7) {
                settingsCard();
            } else if (it.getItemId() == 1) {
                harder = !harder;
                styleHarder();
            } else if (it.getItemId() == 2) {
                prefs().voice(!prefs().voice());
                if (!prefs().voice()) VoiceOut.get(this).stop();
            } else if (it.getItemId() == 6) {
                showingFeed = showingGoals = false;
                showingLists = true;
                render();
                listChange(null);                // the latest from Notion
            } else if (it.getItemId() == 8) {
                notionCard();
            } else if (it.getItemId() == 5) {
                showingFeed = showingGoals = false;
                showingMemory = true;
                editingAbout = false;
                importAbout();                  // a page handed over while the app was open
                render();
            } else if (it.getItemId() == 3) {
                if (busy) stop();
                VoiceOut.get(this).stop();
                chat = new Chat();
                saveChat();
                File[] old = Photo.dir(app).listFiles();      // a new chat: its photos go too
                for (File f : old == null ? new File[0] : old) if (!f.getName().equals(attached) && !f.delete()) android.util.Log.w("Sprocket", "kept " + f);
                render();
            }
            return true;
        });
        m.show();
    }

    // ------------------------------------------------------------------ the conversation on screen
    private void render() {
        list.removeAllViews();
        bottomBar.setVisibility(ready() ? View.VISIBLE : View.GONE);
        for (View b : topButtons) b.setVisibility(ready() ? View.VISIBLE : View.GONE);
        if (!ready()) { welcomeScreen(); return; }
        if (showingMemory) { memoryCard(); return; }
        if (showingLists) { listsCard(); return; }
        if (showingFeed) { feedScreen(); return; }
        if (showingGoals) { goalsScreen(); return; }
        if (chat.turns.isEmpty()) {
            TextView hi = text("Say something, or tap Talk.", 14, FAINT);
            hi.setPadding(dp(6), dp(24), dp(6), dp(6));
            list.addView(hi);
        }
        pendingView = null;
        for (Chat.Turn t : chat.turns) {
            TextView v = addBubble(t);
            if (t == pending) pendingView = v;
        }
        Hands.Card c = Hands.card;
        if (c != null) addCard(c);
        else if (Setup.running(setupAt) && !busy) addSetupCard();
        scrollDown();
    }

    /** Before a text is sent or a call placed: exactly what will happen, and two buttons. Nothing happens without a tap. */
    private void addCard(Hands.Card c) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = round(PANEL, 16);
        g.setStroke(dp(2), AMBER);
        panel.setBackground(g);
        panel.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t = text(c.text, 16, TEXT);
        t.setLineSpacing(0, 1.15f);
        panel.addView(t);
        LinearLayout btns = new LinearLayout(this);
        Button yes = chip(c.yes);
        yes.setBackground(round(AMBER, 18));
        yes.setTextColor(0xFF111111);
        yes.setOnClickListener(v -> Hands.answer(c, true));
        btns.addView(yes, new LinearLayout.LayoutParams(-2, dp(42)));
        Button no = chip("Cancel");
        no.setBackground(round(MINE, 18));
        no.setTextColor(TEXT);
        no.setOnClickListener(v -> Hands.answer(c, false));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, dp(42));
        np.setMargins(dp(10), 0, 0, 0);
        btns.addView(no, np);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, dp(12), 0, 0);
        panel.addView(btns, bp);
        if (c.listening) panel.addView(text("Listening: say \u201c" + c.yes.toLowerCase(Locale.US) + "\u201d or \u201ccancel\u201d", 13, MUTED));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        list.addView(panel, lp);
    }

    private TextView addBubble(Chat.Turn t) {
        Feed.Brief fb = t.kind.equals("feed") ? Feed.Brief.fromJson(t.text) : null;
        if (fb != null) {                             // the morning brief: one card, then what it cost
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(feedCard(fb));
            TextView meta = text("Morning brief  \u00b7  " + (ownModel(t.model) ? Claude.ownName() + "  \u00b7  free"
                    : "Sonnet  \u00b7  " + String.format(Locale.US, "$%.2f", t.cost)), 11, FAINT);
            meta.setPadding(dp(8), dp(3), 0, 0);
            row.addView(meta);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(8), 0, 0);
            list.addView(row, lp);
            return null;
        }
        if (t.kind.equals("studio")) {                // something the studio made: the picture, video or song itself
            JSONObject j;
            try { j = new JSONObject(t.text); } catch (Exception e) { j = new JSONObject(); }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(studioCard(j));
            TextView meta = text(studioMeta(j), 11, FAINT);
            meta.setPadding(dp(8), dp(3), 0, 0);
            row.addView(meta);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(8), 0, 0);
            list.addView(row, lp);
            return null;
        }
        if (t.kind.equals("brief")) {                 // a research brief: one card, then what it cost
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(briefCard(t));
            TextView meta = text("Research  \u00b7  Opus  \u00b7  " + String.format(Locale.US, "$%.2f", t.cost), 11, FAINT);
            meta.setPadding(dp(8), dp(3), 0, 0);
            row.addView(meta);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(8), 0, 0);
            list.addView(row, lp);
            return null;
        }
        if (t.role.equals("action")) {               // a record of something real Sprocket did: small, plain, no bubble
            TextView v = text("\u2713  " + t.text, 13, MUTED);
            v.setPadding(dp(8), dp(6), dp(8), dp(2));
            list.addView(v);
            return v;
        }
        boolean mine = t.role.equals("user"), err = t.role.equals("error");
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(mine ? Gravity.END : Gravity.START);
        int wide = Math.round(getResources().getDisplayMetrics().widthPixels * 0.6f);
        for (String name : t.photos) {
            Bitmap b = thumb(name, wide);
            if (b == null) continue;
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(b);
            iv.setAdjustViewBounds(true);
            iv.setMaxWidth(wide);
            iv.setMaxHeight(wide);
            iv.setBackground(round(PANEL, 16));
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-2, -2);
            ip.setMargins(0, 0, 0, dp(4));
            row.addView(iv, ip);
        }
        if (mine && t.text.isEmpty() && !t.photos.isEmpty()) {   // a photo with no words: just the photo
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(6), 0, 0);
            list.addView(row, lp);
            return null;
        }
        TextView v = text(t.text.isEmpty() ? "…" : t.text, 16, err ? ERR : TEXT);
        v.setTextIsSelectable(true);
        v.setLineSpacing(0, 1.15f);
        v.setPadding(dp(14), dp(10), dp(14), dp(10));
        v.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.84f));
        v.setBackground(round(mine ? MINE : PANEL, 16));
        row.addView(v, new LinearLayout.LayoutParams(-2, -2));
        if (t.role.equals("assistant") && !t.model.isEmpty()) {
            TextView meta = text(meta(t), 11, FAINT);
            meta.setPadding(dp(8), dp(3), 0, 0);
            row.addView(meta);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(6), 0, 0);
        list.addView(row, lp);
        return v;
    }

    private static String meta(Chat.Turn t) {
        if (ownModel(t.model)) return Claude.ownName() + ", your server  ·  free";
        String who = Claude.OPUS.equals(t.model) ? "Opus, thinking harder" : "Sonnet";
        return who + String.format(Locale.US, "  ·  $%.4f", t.cost);
    }

    private void scrollDown() { scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }

    private void keyCard(boolean replacing) {
        list.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView h = text(replacing ? "Claude key" : "Sprocket needs a Claude key", 17, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        c.addView(text(CLAUDE_KEY_HOW, 14, MUTED));
        EditText k = new EditText(this);
        k.setHint("sk-ant-...");
        k.setTextColor(TEXT);
        k.setHintTextColor(FAINT);
        k.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(k);
        TextView note = text("", 13, ERR);
        c.addView(note);
        LinearLayout btns = new LinearLayout(this);
        Button save = chip("Save");
        save.setBackground(round(TEXT, 18));
        save.setTextColor(0xFF111111);
        save.setOnClickListener(v -> {
            String s = k.getText().toString().trim();
            if (!s.startsWith("sk-ant-")) { note.setText("That isn't a Claude key: they start with sk-ant-"); return; }
            prefs().key(s);
            render();
        });
        btns.addView(save, new LinearLayout.LayoutParams(-2, dp(40)));
        if (!prefs().key().isEmpty()) {
            Button remove = chip("Remove key");
            remove.setBackground(round(MINE, 18));
            remove.setTextColor(TEXT);
            remove.setOnClickListener(v -> { prefs().key(""); render(); });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(40));
            rp.setMargins(dp(10), 0, 0, 0);
            btns.addView(remove, rp);
            Button back = chip("Back");
            back.setBackground(round(MINE, 18));
            back.setTextColor(TEXT);
            back.setOnClickListener(v -> render());
            btns.addView(back, new LinearLayout.LayoutParams(rp));
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, dp(10), 0, 0);
        c.addView(btns, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(16), 0, 0);
        list.addView(c, lp);
    }

    // ------------------------------------------------------------------ first open (Setup)
    static final String ADDRESS_HOW = "The go-between's start.py prints exactly what to type here. Its address is a Tailscale one "
            + "(https://my-computer.my-tailnet.ts.net:8448), another https:// one, or a home-network one "
            + "(http://192.168.1.50:8048: only at home, and unencrypted). The README shows how to set each one up.";
    // Codex's review 2026-11-12: a newcomer needs the steps, and the surprise said up front.
    static final String CLAUDE_KEY_HOW = "How to get one: 1. Sign in at console.anthropic.com. A Claude.ai subscription doesn't "
            + "cover this: the API is paid separately, for what you use. 2. Under Billing, add a little credit (and set a "
            + "monthly limit under Limits, if you like). 3. Under API keys, create a key, copy it and paste it here. It stays "
            + "on this phone.";
    static final String CONSOLE_KEYS = "https://console.anthropic.com/settings/keys";

    /** A new install's first screen, and what shows whenever there's no brain: pick one, full screen (the owner,
     * 2026-11-11). Sprocket walks them through everything else in the chat (Setup). */
    private void welcomeScreen() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(8), dp(28), dp(8), dp(24));
        TextView h = text("Hi, I'm Sprocket", 26, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        TextView sub = text("First, pick my brain. I'll walk you through the rest in the chat.", 15, MUTED);
        sub.setPadding(0, dp(6), 0, 0);
        c.addView(sub);

        final boolean[] own = {Config.own};
        LinearLayout claude = option("Claude", "Anthropic's AI. Needs a Claude key; you pay Anthropic for what you use.");
        LinearLayout mine = option("Your own server", "A model you run yourself, through the included go-between or anything "
                + "that speaks Anthropic's Messages API. Tested with Qwen.");
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2);
        first.setMargins(0, dp(22), 0, 0);
        c.addView(claude, first);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, -2);
        second.setMargins(0, dp(10), 0, 0);
        c.addView(mine, second);

        LinearLayout claudeBox = new LinearLayout(this);
        claudeBox.setOrientation(LinearLayout.VERTICAL);
        EditText key = field(claudeBox, "Your Claude key", "", "sk-ant-...", true);
        claudeBox.addView(text(CLAUDE_KEY_HOW, 12, FAINT));
        TextView console = text("Open the Claude Console", 13, TEXT);
        console.setPadding(0, dp(8), 0, dp(4));
        console.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(CONSOLE_KEYS))); } catch (Exception ignored) { }
        });
        claudeBox.addView(console);
        c.addView(claudeBox);
        LinearLayout ownBox = new LinearLayout(this);
        ownBox.setOrientation(LinearLayout.VERTICAL);
        EditText name = field(ownBox, "Its name (what I call it)", Config.ownName, "Qwen", false);
        EditText base = field(ownBox, "Its address", Config.ownBase, "https://my-server:8448", false);
        EditText model = field(ownBox, "Model name (blank if the server has one)", Config.ownModel, "qwen", false);
        EditText ownKey = field(ownBox, "Its access code (blank if it needs none)", "", "none", true);
        ownBox.addView(text(ADDRESS_HOW, 12, FAINT));
        c.addView(ownBox);
        TextView note = text("", 13, ERR);
        note.setPadding(0, dp(8), 0, 0);
        TextView anyway = text("Start anyway", 13, FAINT);   // after a failed test: their server may just be off right now
        anyway.setPadding(0, dp(12), 0, 0);
        anyway.setVisibility(View.GONE);
        final int[] asked = {0};                              // which check is current: switching brains drops the last one
        Runnable show = () -> {
            pick(claude, !own[0]);
            pick(mine, own[0]);
            claudeBox.setVisibility(own[0] ? View.GONE : View.VISIBLE);
            ownBox.setVisibility(own[0] ? View.VISIBLE : View.GONE);
        };
        Runnable switched = () -> {   // the other brain's message would read as this one's (the owner's phone test, 2026-11-12)
            asked[0]++;
            note.setText("");
            anyway.setVisibility(View.GONE);
            show.run();
        };
        claude.setOnClickListener(v -> { if (own[0]) { own[0] = false; switched.run(); } });
        mine.setOnClickListener(v -> { if (!own[0]) { own[0] = true; switched.run(); } });
        show.run();

        c.addView(note);
        Button start = button("Start", true);
        start.setOnClickListener(v -> {
            final boolean useOwn = own[0];
            final String k = key.getText().toString().trim(), b = base.getText().toString().trim();
            final String n = name.getText().toString().trim(), m = model.getText().toString().trim();
            final String typed = ownKey.getText().toString().trim();
            note.setTextColor(ERR);
            if (useOwn) {
                if (b.isEmpty()) { note.setText("Your own server needs its address."); return; }
                String bad = Config.addressProblem(b);
                if (bad != null) { note.setText(bad); return; }
            } else if (!k.startsWith("sk-ant-")) { note.setText("That isn't a Claude key: they start with sk-ant-"); return; }
            Runnable go = () -> {
                if (useOwn) {
                    Config.ownName = n;
                    Config.ownBase = b;
                    Config.ownModel = m;
                    if (!typed.isEmpty()) prefs().ownKey(typed);
                } else prefs().key(k);
                Config.own = useOwn;
                prefs().saveConfig();
                Claude.useBrain(Config.own);
                startSetup();
                render();
            };
            // test it first (Codex's review 2026-11-12): a wrong key or an unreachable server is said here, not mid-setup
            Claude.Brain test = useOwn ? new Claude.Brain(b.replaceAll("/+$", ""), typed.isEmpty() ? prefs().ownKey() : typed, true,
                    m.isEmpty() ? "own" : m) : new Claude.Brain(Claude.ANTHROPIC, k, false, "");
            note.setTextColor(MUTED);
            note.setText(useOwn ? "Checking your server... A model can take a minute to load the first time." : "Checking the key with Anthropic...");
            start.setEnabled(false);
            anyway.setVisibility(View.GONE);
            final int ticket = ++asked[0];
            new Thread(() -> {
                String problem = Claude.check(test);
                runOnUiThread(() -> {
                    start.setEnabled(true);
                    if (ticket != asked[0]) return;   // he switched brains while it checked: this answer is for the other one
                    if (problem == null) { go.run(); return; }
                    note.setTextColor(ERR);
                    note.setText(problem);
                    anyway.setOnClickListener(x -> go.run());
                    anyway.setVisibility(View.VISIBLE);
                });
            }).start();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, dp(44));
        sp.setMargins(0, dp(12), 0, 0);
        c.addView(start, sp);
        c.addView(anyway);
        list.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    /** One of the two brains on the first screen: a panel to tap. */
    private LinearLayout option(String title, String what) {
        LinearLayout o = new LinearLayout(this);
        o.setOrientation(LinearLayout.VERTICAL);
        o.setPadding(dp(16), dp(14), dp(16), dp(14));
        o.setClickable(true);
        TextView t = text(title, 17, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        o.addView(t);
        TextView w = text(what, 13, MUTED);
        w.setPadding(0, dp(4), 0, 0);
        o.addView(w);
        return o;
    }

    private void pick(LinearLayout o, boolean picked) {
        GradientDrawable g = round(PANEL, 16);
        g.setStroke(dp(2), picked ? TEXT : PANEL);
        o.setBackground(g);
    }

    /** Only on a brand-new install (a brain removed and put back later doesn't start it again). */
    private void startSetup() {
        android.content.SharedPreferences sp = getSharedPreferences("sprocket", Context.MODE_PRIVATE);
        if (sp.getBoolean("setup_offered", false) || !chat.turns.isEmpty()) return;
        sp.edit().putBoolean("setup_offered", true).putBoolean("asked_location", true).apply();
        Config.off.addAll(Setup.features());      // each feature waits for its own Yes (Codex 042 #3)
        prefs().saveConfig();
        FeedAlarm.cancel(this);
        setupGo(0);
        chat.add("assistant", Setup.HELLO + "\n\n" + Setup.ask(0), System.currentTimeMillis());
        saveChat();
    }

    private void setupGo(int at) {
        setupAt = at;
        setupGithub = false;
        setupReturning = false;
        setupTried.clear();
        getSharedPreferences("sprocket", Context.MODE_PRIVATE).edit().putInt("setup_at", at).apply();
    }

    /** Under the question on show: Yes / Not now (or Skip while a username is awaited), and a way out of setup. */
    private void addSetupCard() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = round(PANEL, 16);
        g.setStroke(dp(1), FAINT);
        panel.setBackground(g);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        Setup.Step st = Setup.STEPS[setupAt];
        TextView t = text(setupGithub ? "Type your username below, or skip this one." : st.name, 15, setupGithub ? MUTED : TEXT);
        if (!setupGithub) t.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(t);
        LinearLayout btns = new LinearLayout(this);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, dp(42));
        gap.setMargins(dp(10), 0, 0, 0);
        if (setupGithub) {
            Button skip = button("Skip", false);
            skip.setOnClickListener(v -> setupTapped(false));
            btns.addView(skip, new LinearLayout.LayoutParams(-2, dp(42)));
        } else {
            Button yes = button("Yes", true), no = button("Not now", false);
            yes.setOnClickListener(v -> setupTapped(true));
            no.setOnClickListener(v -> setupTapped(false));
            btns.addView(yes, new LinearLayout.LayoutParams(-2, dp(42)));
            btns.addView(no, gap);
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, dp(10), 0, 0);
        panel.addView(btns, bp);
        TextView out = text("Start with basic chat (set up the rest later)", 13, FAINT);
        out.setPadding(0, dp(10), 0, 0);
        out.setOnClickListener(v -> setupSkipRest());
        panel.addView(out);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(10), 0, 0);
        list.addView(panel, lp);
    }

    private void setupTapped(boolean yes) {
        if (busy || !Setup.running(setupAt)) return;
        chat.add("user", yes ? "Yes" : setupGithub ? "Skip" : "Not now", System.currentTimeMillis());
        if (setupGithub) { Config.off.add("github"); prefs().saveConfig(); setupNext(Setup.ack(Setup.STEPS[setupAt], false)); return; }
        setupAnswer(yes);
    }

    /** Typed during setup: true if it answered the question on show; false = a question, for the brain. */
    private boolean setupTyped(String s) {
        Boolean yes = Setup.answer(s);
        if (setupGithub) {
            String name = yes == Boolean.FALSE ? null : Setup.githubName(s);
            if (yes != Boolean.FALSE && name == null) return false;
            chat.add("user", s, System.currentTimeMillis());
            if (name == null) { Config.off.add("github"); prefs().saveConfig(); setupNext(Setup.ack(Setup.STEPS[setupAt], false)); return true; }
            Config.github = name;
            Config.off.remove("github");
            prefs().saveConfig();
            setupNext("Got it: " + name + ".");
            return true;
        }
        if (yes == null) return false;
        chat.add("user", s, System.currentTimeMillis());
        setupAnswer(yes);
        return true;
    }

    private void setupAnswer(boolean yes) {
        Setup.Step st = Setup.STEPS[setupAt];
        if (st.feature != null) {
            if (yes) Config.off.remove(st.feature); else Config.off.add(st.feature);
            prefs().saveConfig();
            if (st.feature.equals("brief")) { if (yes) FeedAlarm.schedule(this); else FeedAlarm.cancel(this); }
        }
        if (!yes) { setupNext(Setup.ack(st, false)); return; }
        render();                                 // their Yes on screen before Android's question
        if (st.way == Setup.Way.GITHUB_NAME) {
            setupGithub = true;
            chat.add("assistant", Setup.GITHUB_ASK, System.currentTimeMillis());
            saveChat();
            render();
            return;
        }
        setupContinue();
    }

    /** Asks Android for whatever the step on show still lacks, one thing at a time and each only once, then moves on. */
    private void setupContinue() {
        Setup.Step st = Setup.STEPS[setupAt];
        boolean listener = st.way == Setup.Way.NOTIFICATION_ACCESS || st.way == Setup.Way.BOTH_SWITCHES;
        boolean screen = st.way == Setup.Way.SCREEN_CONTROL || st.way == Setup.Way.BOTH_SWITCHES;
        String[] missing = missingPerms(st.perms);
        if (missing.length > 0 && setupTried.add("perms")) { requestPermissions(missing, ASK_SETUP); return; }
        if (listener && !MessageListener.on(this) && setupTried.add("listener")) {
            setupReturning = true;
            MessageListener.openSwitch(this);
            return;
        }
        if (screen && !ScreenHands.on() && setupTried.add("screen")) {
            setupReturning = true;
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        boolean switches = (!listener || MessageListener.on(this)) && (!screen || ScreenHands.on());
        if (Place.allowed(this)) Place.refresh(this);
        setupNext(!switches ? Setup.STILL_OFF : missingPerms(st.perms).length > 0 ? Setup.DENIED : Setup.ack(st, true));
    }

    private String[] missingPerms(String[] perms) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : perms) {
            if (p.equals(Setup.POST) && Build.VERSION.SDK_INT < 33) continue;       // no such question before Android 13
            if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) out.add(p);
        }
        return out.toArray(new String[0]);
    }

    private void setupNext(String ack) {
        int n = Setup.next(setupAt);
        setupGo(n);
        chat.add("assistant", ack + "\n\n" + (Setup.running(n) ? Setup.ask(n) : Setup.DONE), System.currentTimeMillis());
        saveChat();
        render();
    }

    private void setupSkipRest() {
        if (busy) return;
        setupGo(Setup.OFF);
        chat.add("assistant", "Okay, that's it for setup. What we didn't get to stays off: switch it on in ⋯ > Settings, "
                        + "and ask me about any of it.",
                System.currentTimeMillis());
        saveChat();
        render();
    }

    // ------------------------------------------------------------------ settings (Config)
    private EditText field(LinearLayout c, String label, String value, String hint, boolean secret) {
        TextView l = text(label, 13, MUTED);
        l.setPadding(0, dp(10), 0, 0);
        c.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        c.addView(e);
        return e;
    }

    private TextView heading(LinearLayout c, String t) {
        TextView h = text(t, 16, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(18), 0, dp(2));
        c.addView(h);
        return h;
    }

    private Button button(String t, boolean main) {
        Button b = chip(t);
        b.setBackground(round(main ? TEXT : MINE, 18));
        b.setTextColor(main ? 0xFF111111 : TEXT);
        return b;
    }

    /** ⋯ Settings (the open-source cleanup, 2026-11-11): the brain, voices, features, optional servers, personality. */
    private void settingsCard() {
        list.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView h = text("Settings", 17, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);

        heading(c, "Brain");
        final boolean[] own = {Config.own};
        LinearLayout pick = new LinearLayout(this);
        Button claude = button("Claude", !own[0]), mine = button("Your own server", own[0]);
        pick.addView(claude, new LinearLayout.LayoutParams(-2, dp(38)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, dp(38));
        gap.setMargins(dp(10), 0, 0, 0);
        pick.addView(mine, gap);
        c.addView(pick);
        LinearLayout ownBox = new LinearLayout(this);
        ownBox.setOrientation(LinearLayout.VERTICAL);
        EditText name = field(ownBox, "Its name (what Sprocket calls it)", Config.ownName, "Qwen", false);
        EditText base = field(ownBox, "Its address (speaks Anthropic's Messages API, or the included go-between)", Config.ownBase,
                "https://my-server:8448", false);
        EditText model = field(ownBox, "Model name (blank if the server has one)", Config.ownModel, "qwen", false);
        EditText ownKey = field(ownBox, "Its access code (blank if it needs none)", "", prefs().ownKey().isEmpty() ? "none" : "saved", true);
        ownBox.addView(text(ADDRESS_HOW, 12, FAINT));
        c.addView(ownBox);
        Button claudeKey = button(prefs().key().isEmpty() ? "Add Claude key" : "Claude key", false);
        claudeKey.setOnClickListener(v -> keyCard(true));
        LinearLayout.LayoutParams top = new LinearLayout.LayoutParams(-2, dp(38));
        top.setMargins(0, dp(8), 0, 0);
        c.addView(claudeKey, top);
        Runnable showBrain = () -> {
            ownBox.setVisibility(own[0] ? View.VISIBLE : View.GONE);
            claudeKey.setVisibility(own[0] ? View.GONE : View.VISIBLE);
            claude.setBackground(round(own[0] ? MINE : TEXT, 18));
            claude.setTextColor(own[0] ? TEXT : 0xFF111111);
            mine.setBackground(round(own[0] ? TEXT : MINE, 18));
            mine.setTextColor(own[0] ? 0xFF111111 : TEXT);
        };
        claude.setOnClickListener(v -> { own[0] = false; showBrain.run(); });
        mine.setOnClickListener(v -> { own[0] = true; showBrain.run(); });
        showBrain.run();
        c.addView(text("Tested with Claude, and with Qwen through the included go-between. Other models may work if they speak "
                + "Anthropic's Messages API, or sit behind the go-between (any OpenAI-compatible server).", 12, FAINT));

        heading(c, "Voices");
        EditText out = field(c, "Voice server (blank = the phone's own voice)", Config.voiceOut, "http://my-server:8472", false);
        EditText in = field(c, "Speech-to-text server (blank = the phone's own recognizer)", Config.voiceIn, "http://my-server:8080", false);
        c.addView(text("The voice server takes POST /speak {text, mood} and returns audio. The speech-to-text server takes "
                + "an OpenAI-style /v1/audio/transcriptions upload (whisper.cpp's server does).", 12, FAINT));

        heading(c, "Features");
        java.util.Map<String, android.widget.CheckBox> boxes = new java.util.LinkedHashMap<>();
        for (String[] f : Config.FEATURES) {
            android.widget.CheckBox b = new android.widget.CheckBox(this);
            b.setText(f[1]);
            b.setTextColor(TEXT);
            b.setChecked(Config.on(f[0]));
            c.addView(b);
            TextView d = text(f[2], 12, FAINT);
            d.setPadding(dp(32), 0, 0, dp(4));
            c.addView(d);
            boxes.put(f[0], b);
        }

        EditText hours = field(c, "Call announcer hours (blank = always)", Config.callHours, "mon-thu 6-23, fri 6-24, sat 0-24, sun 0-23", false);

        heading(c, "Optional servers");
        EditText gh = field(c, "Your GitHub username", Config.github, "for the GitHub feature", false);
        EditText dash = field(c, "Home dashboard (/api/state)", Config.homeDash, "blank = off", false);
        EditText stats = field(c, "Server stats (/stats)", Config.homeStats, "blank = off", false);
        EditText hands = field(c, "Server job runner", Config.homeHands, "blank = off", false);
        EditText studio = field(c, "Studio", Config.studio, "blank = off", false);

        heading(c, "More");
        LinearLayout more = new LinearLayout(this);
        Button notion = button("Notion key (lists)", false), who = button("Personality", false);
        notion.setOnClickListener(v -> notionCard());
        who.setOnClickListener(v -> personaCard());
        more.addView(notion, new LinearLayout.LayoutParams(-2, dp(38)));
        more.addView(who, gap);
        c.addView(more);
        TextView credits = text("Weather data by Open-Meteo.com (CC BY 4.0); place names from GeoNames. Sprocket is not "
                + "affiliated with Anthropic or any model maker.", 12, FAINT);
        credits.setPadding(0, dp(14), 0, 0);
        c.addView(credits);

        TextView note = text("", 13, ERR);
        c.addView(note);
        LinearLayout btns = new LinearLayout(this);
        Button save = button("Save", true), back = button("Back", false);
        save.setOnClickListener(v -> {
            if (own[0] && base.getText().toString().trim().isEmpty()) { note.setText("Your own server needs its address."); return; }
            for (EditText f : new EditText[]{base, out, in, dash, stats, hands, studio}) {
                String bad = Config.addressProblem(f.getText().toString());
                if (bad != null) { note.setText(bad + " (" + f.getText().toString().trim() + ")"); f.requestFocus(); return; }
            }
            boolean home = false;
            for (EditText f : new EditText[]{base, out, in, dash, stats, hands, studio}) home |= Config.unencrypted(f.getText().toString());
            Config.own = own[0];
            Config.ownName = name.getText().toString().trim();
            Config.ownBase = base.getText().toString().trim();
            Config.ownModel = model.getText().toString().trim();
            if (!ownKey.getText().toString().trim().isEmpty()) prefs().ownKey(ownKey.getText().toString());
            Config.voiceOut = out.getText().toString().trim();
            Config.voiceIn = in.getText().toString().trim();
            Config.github = gh.getText().toString().trim();
            Config.homeDash = dash.getText().toString().trim();
            Config.homeStats = stats.getText().toString().trim();
            Config.homeHands = hands.getText().toString().trim();
            Config.studio = studio.getText().toString().trim();
            Config.callHours = hours.getText().toString().trim();
            Config.off.clear();
            for (java.util.Map.Entry<String, android.widget.CheckBox> e : boxes.entrySet()) if (!e.getValue().isChecked()) Config.off.add(e.getKey());
            prefs().saveConfig();
            Claude.useBrain(Config.own);
            if (Config.on("brief")) FeedAlarm.schedule(this); else FeedAlarm.cancel(this);
            VoiceOut.get(this).retryGateway();
            if (home) android.widget.Toast.makeText(this, "Saved. Home-network http isn't encrypted, and only works at home.", android.widget.Toast.LENGTH_LONG).show();
            render();
        });
        back.setOnClickListener(v -> render());
        btns.addView(save, new LinearLayout.LayoutParams(-2, dp(40)));
        btns.addView(back, gap);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, dp(12), 0, 0);
        c.addView(btns, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(16), 0, 0);
        list.addView(c, lp);
    }

    /** ⋯ Settings > Personality: his own wording of who Sprocket is, or back to the one the app comes with. */
    private void personaCard() {
        list.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView h = text("Personality", 17, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        c.addView(text("This goes to the brain with every question: who Sprocket is and how it talks. Change anything.", 13, MUTED));
        EditText e = new EditText(this);
        e.setText(persona);
        e.setTextColor(TEXT);
        e.setTextSize(13);
        e.setMinLines(12);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        c.addView(e);
        TextView example = text("Want a character? Load the example: a loud robot who is sure he was built for war, and now "
                + "sets your timers. Then change anything and Save.", 13, MUTED);
        example.setPadding(0, dp(8), 0, 0);
        c.addView(example);
        Button load = button("Load the example", false);
        load.setOnClickListener(v -> e.setText(examplePersona()));
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(-2, dp(40));
        lp0.setMargins(0, dp(6), 0, 0);
        c.addView(load, lp0);
        LinearLayout btns = new LinearLayout(this);
        Button save = button("Save", true), reset = button("Use the original", false), back = button("Back", false);
        save.setOnClickListener(v -> {
            String t = e.getText().toString();
            if (t.trim().isEmpty()) return;
            prefs().persona(t.equals(defaultPersona()) ? "" : t);
            persona = t;
            settingsCard();
        });
        reset.setOnClickListener(v -> { prefs().persona(""); persona = defaultPersona(); personaCard(); });
        back.setOnClickListener(v -> settingsCard());
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, dp(40));
        gap.setMargins(dp(10), 0, 0, 0);
        btns.addView(save, new LinearLayout.LayoutParams(-2, dp(40)));
        btns.addView(reset, gap);
        btns.addView(back, new LinearLayout.LayoutParams(gap));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, dp(12), 0, 0);
        c.addView(btns, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(16), 0, 0);
        list.addView(c, lp);
    }

    /** ⋯ Notion key: his "Sprocket" Notion connection's key, so his lists live in Notion (it stays on this phone). */
    private void notionCard() {
        list.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView h = text("Notion key (lists)", 17, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        c.addView(text("Your lists live in Notion once this is set: one table per list on the \u201cSprocket lists\u201d page. "
                + "Make the key at notion.so/developers/tokens: New token, name it Sprocket, tick Notion API, Create token. "
                + "Sprocket only ever touches that one page. The key stays on this phone.", 14, MUTED));
        EditText k = new EditText(this);
        k.setHint(prefs().notionKey().isEmpty() ? "ntn_..." : "saved (paste a new one to replace it)");
        k.setTextColor(TEXT);
        k.setHintTextColor(FAINT);
        k.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(k);
        TextView note = text("", 13, MUTED);
        c.addView(note);
        Runnable check = () -> notionWorker.execute(() -> {
            String r = syncLists();
            int n;
            synchronized (lists) { n = lists.lists.size(); }
            String said = r == null ? "" : r.isEmpty() ? "Connected: " + n + (n == 1 ? " list" : " lists") + " in Notion now." : "Not yet: " + r + ".";
            onScreen(x -> { note.setText(said); note.setTextColor(r == null || r.isEmpty() ? MUTED : ERR); });
        });
        LinearLayout btns = new LinearLayout(this);
        Button save = chip("Save");
        save.setBackground(round(TEXT, 18));
        save.setTextColor(0xFF111111);
        save.setOnClickListener(v -> {
            String s2 = k.getText().toString().trim();
            if (!s2.startsWith("ntn_") && !s2.startsWith("secret_")) { note.setTextColor(ERR); note.setText("That isn't a Notion key: they start with ntn_"); return; }
            prefs().notionKey(s2);
            k.setText("");
            k.setHint("saved (paste a new one to replace it)");
            note.setTextColor(MUTED);
            note.setText("Checking Notion\u2026");
            check.run();
        });
        btns.addView(save, new LinearLayout.LayoutParams(-2, dp(40)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(40));
        rp.setMargins(dp(10), 0, 0, 0);
        if (!prefs().notionKey().isEmpty()) {
            Button test = chip("Check");
            test.setBackground(round(MINE, 18));
            test.setTextColor(TEXT);
            test.setOnClickListener(v -> { note.setTextColor(MUTED); note.setText("Checking Notion\u2026"); check.run(); });
            btns.addView(test, rp);
            Button remove = chip("Remove key");
            remove.setBackground(round(MINE, 18));
            remove.setTextColor(TEXT);
            remove.setOnClickListener(v -> { prefs().notionKey(""); listsNote = null; notionCard(); });   // the lists stay on the phone
            btns.addView(remove, new LinearLayout.LayoutParams(rp));
        }
        Button back = chip("Back");
        back.setBackground(round(MINE, 18));
        back.setTextColor(TEXT);
        back.setOnClickListener(v -> render());
        btns.addView(back, new LinearLayout.LayoutParams(rp));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, dp(10), 0, 0);
        c.addView(btns, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(16), 0, 0);
        list.addView(c, lp);
    }

    private String key() { return testKey != null ? testKey : prefs().activeKey(); }

    /** A brain is set up (without the self-test's stand-in): what the morning brief's alarm waits for. */
    static boolean brainSetUp() {
        return Config.own ? !Config.ownBase.trim().isEmpty() : !new SharedPreferencesLike(app).key().isEmpty();
    }

    /** A brain he can ask: Claude with its key, or his own server with its address (a key only if it wants one). */
    private boolean ready() { return testKey != null || (Config.own ? !Config.ownBase.trim().isEmpty() : !prefs().key().isEmpty()); }

    /** A reply from his own server's model (not Claude's): free. */
    static boolean ownModel(String model) { return model != null && !model.isEmpty() && !model.startsWith("claude"); }

    /** An example personality to start from (assets/persona-example.md): the robot the app grew up with. */
    static String examplePersona() {
        try (InputStream in = app.getAssets().open("persona-example.md")) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception e) { return defaultPersona(); }
    }

    /** The personality the app comes with (assets/persona.md). */
    static String defaultPersona() {
        try (InputStream in = app.getAssets().open("persona.md")) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception e) { return "You are Sprocket, a friendly phone assistant. Answer in two to four plain spoken sentences."; }
    }

    // ------------------------------------------------------------------ asking
    private void sendTyped() {
        String s = box.getText().toString().trim();
        if ((s.isEmpty() && attached == null) || busy || !ready()) return;
        box.setText("");
        spokenTurn = false;
        if (Setup.running(setupAt) && attached == null && setupTyped(s)) return;
        ask(s, null);
    }

    private void ask(String said, SelfTest st) {
        String photo = attached;
        attached = null;
        showAttached();
        askQuestion(said, photo, st);
    }

    /** Main thread: a question asked, from this screen or the side-button card (spokenTurn set by whoever heard it). */
    static void askQuestion(String said, String photo, SelfTest st) {
        VoiceOut.get(app).stop();
        if (listen.on()) listen.cancel();
        Chat.Turn mine = chat.add("user", said, System.currentTimeMillis());
        if (photo != null) mine.photos.add(photo);
        Chat.Turn reply = chat.add("assistant", "", System.currentTimeMillis());
        pending = reply;
        startedInApp = resumed;
        askedNow = said == null ? "" : said;
        usedHands = false;
        ScreenHands.stay = false;
        boolean think = harder;
        harder = false;                          // "Think harder" is for the next question only
        busy = true;
        stopped = false;
        MainActivity a = current;
        if (a != null) { a.styleHarder(); a.styleSend(); a.render(); }
        Claude.Brain k = brain();
        List<Chat.Turn> history = chat.window(Claude.HISTORY_TURNS);
        String remembered;
        synchronized (memory) { remembered = Config.on("memory") ? memory.forClaude() : null; }
        if (Setup.running(setupAt)) remembered = remembered == null ? Setup.helpNote(setupAt) : Setup.helpNote(setupAt) + "\n\n" + remembered;
        String open;
        synchronized (goals) { open = goals.forClaude(); }    // his goals ride in the notes' block (no room for a fifth mark)
        if (open != null) remembered = remembered == null ? open : remembered + "\n\n" + open;
        String notes = remembered;
        boolean setupHelp = Setup.running(setupAt);
        String page = Memory.aboutForClaude(about);
        worker.execute(() -> {
            String error = null;
            Claude.Reply r = null;
            try {
                TimeZone tz = TimeZone.getDefault();
                String now = new SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US).format(new Date())
                        + " (" + tz.getID() + ")";
                JSONObject req = Claude.request(persona, page, notes, now, Place.where(app), history, think);
                if (setupHelp) req.remove("tools");   // a question about setup is answered, never acted on (Codex 042 #3)
                r = Claude.send(k, req, new Claude.Listener() {
                    public void text(String soFar) {
                        reply.text = soFar;
                        onScreen(x -> x.showPending());
                        Watch w = watch;
                        if (w != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> w.text(soFar));
                    }
                    public void sentence(String s, String mood) {
                        if (st != null) st.sentences.add(s);
                        if (voiceOn()) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> VoiceOut.get(app).add(s, mood));
                    }
                }, live, TOOLS);
            } catch (Exception e) {
                error = stopped ? null : (Net.offline(e) ? "no internet: Sprocket's brain lives in the cloud" : e.getMessage());
            }
            Claude.Reply done = r;
            String err = error;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> finish(reply, done, err, st));
        });
    }

    /** Runs on the main thread against the screen on show, if there is one. */
    private static void onScreen(java.util.function.Consumer<MainActivity> f) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> { if (current != null) f.accept(current); });
    }

    private void showPending() {
        if (pendingView == null || pending == null) return;
        pendingView.setText(pending.text.isEmpty() ? "\u2026" : pending.text);
        scrollDown();
    }

    /** Main thread. Static work first (the chat), then the screen on show, if any. */
    private static void finish(Chat.Turn reply, Claude.Reply r, String error, SelfTest st) {
        busy = false;
        pending = null;
        if (r != null) {
            reply.text = r.text;
            reply.model = r.model;
            reply.cost = r.cost();
        } else {
            if (!stopped) reply.text = "";           // a stop keeps what had arrived; a failure keeps nothing
            if (error != null) chat.add("error", "Couldn't reach him: " + error + ".", System.currentTimeMillis());
        }
        if (app.getPackageName().endsWith(".test"))   // Sprocket Test only: the stage 5 runner reads replies here, not off the
            android.util.Log.i("PA-REPLY", error != null ? "ERROR " + error   // screen (a screen dump suspends the hands)
                    : reply.text.trim().isEmpty() ? "(no words)" : reply.text.replace('\n', ' '));
        if (reply.text.trim().isEmpty()) chat.turns.remove(reply);
        saveChat();
        if (startedInApp && usedHands && !ScreenHands.stay && !PhoneTools.wantsToSee(askedNow) && !resumed)   // the hands left him in another app: come back
            app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        usedHands = false;
        if (st != null) st.write(app, r, error);
        Watch w = watch;
        if (w != null) w.done(reply.text, error == null ? null : "Couldn't reach him: " + error + ".");
        if (r != null && voiceOn()) {
            boolean followUp = spokenTurn;
            VoiceOut.get(app).end(() -> {
                if (st != null) st.writeVoice(app, followUp);
                if (!followUp || busy) return;   // the one listen after a spoken reply: on the card if it's up
                Watch w2 = watch;
                if (w2 != null) w2.spoken(); else if (current != null) current.followUp();
            });
        }
        MainActivity a = current;
        if (a != null) {
            a.styleSend();
            a.render();
        }
    }

    private void stop() { stopAnswer(); }

    /** Stop the answer coming in (and any card waiting for his tap), and quiet the voice. */
    static void stopAnswer() {
        stopped = true;
        Hands.answer(Hands.card, false);              // a card waiting for his tap: Stop means no
        HttpURLConnection h = live[0];
        if (h != null) new Thread(h::disconnect).start();   // not the worker: it's busy reading this very reply
        VoiceOut.get(app).stop();
    }

    static boolean busy() { return busy; }

    /** The card heard it said: the reply gets the follow-up listen. */
    static void spokenTurnFromCard() { spokenTurn = true; }

    static boolean voiceOn() {
        return app.getSharedPreferences("sprocket", Context.MODE_PRIVATE).getBoolean("voice", true);
    }

    // ------------------------------------------------------------------ storage
    private static File chatFile() { return new File(app.getFilesDir(), "chat.json"); }

    private static Chat loadChat() {
        try { return Chat.fromJson(new String(Files.readAllBytes(chatFile().toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new Chat(); }
    }

    private static File listsFile() { return new File(app.getFilesDir(), "lists.json"); }

    private static Lists loadLists() {
        try { return Lists.fromJson(new String(Files.readAllBytes(listsFile().toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new Lists(); }
    }

    static void saveLists() {
        try {
            File tmp = new File(app.getFilesDir(), "lists.json.tmp");
            Files.write(tmp.toPath(), lists.toJson().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(listsFile())) throw new Exception("rename failed");
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "lists not saved: " + e);
        }
    }

    // ------------------------------------------------------------------ his lists in Notion (NotionSync)
    /** The last Notion sync: null = Notion is off (or not checked yet), "" = fine, else what went wrong. */
    static volatile String listsNote;
    private static final java.util.concurrent.ExecutorService notionWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Worker threads: sends Notion the phone's changes and reads the lists back. null when Notion is off, "" when it
     * went fine, else why not (the changes wait on the phone for the next one). */
    static String syncLists() {
        SharedPreferencesLike p = new SharedPreferencesLike(app);
        String k = p.notionKey();
        if (k.isEmpty()) return listsNote = null;
        try {
            NotionSync.Api api = new NotionSync.Http(k);
            String page = p.notionPage();
            if (page.isEmpty()) {
                page = NotionSync.findPage(api);
                if (page == null) throw new Exception("Notion has no page called \"Sprocket lists\" that this key can see (make one in Notion)");
                p.notionPage(page);
            }
            for (int round = 1; ; round++) {             // on a copy: the screen never waits on Notion (Codex 040 #7)
                String before;
                Lists copy;
                synchronized (lists) { before = lists.toJson(); copy = Lists.fromJson(before); }
                Exception failed = null;
                try { NotionSync.sync(copy, api, page); } catch (Exception e) { failed = e; }
                boolean changed;
                synchronized (lists) {
                    changed = !lists.toJson().equals(before);   // he changed them while it ran: keep his changes too
                    if (changed) NotionSync.merge(Lists.fromJson(before), copy, lists); else lists.replaceWith(copy);
                    saveLists();
                }
                if (failed != null) throw failed;
                if (!changed || round == 3) break;
            }
            return listsNote = "";
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("page is gone")) p.notionPage("");    // look for it again next time
            android.util.Log.w("Sprocket", "notion: " + e);
            return listsNote = Net.offline(e) ? "no internet" : e.getMessage();
        }
    }

    /** ⋯ Lists: a change made on the phone (or null for none), shown at once, then sent to Notion and read back. On
     * the Notion worker, so a slow Notion never freezes the screen. */
    private void listChange(Runnable change) {
        notionWorker.execute(() -> {
            if (change != null) {
                synchronized (lists) { change.run(); saveLists(); }
                onScreen(x -> { if (showingLists) x.render(); });
            }
            syncLists();
            onScreen(x -> { if (showingLists) x.render(); });
        });
    }

    private static final int SOFT = 0xFFC9CBD0, LINE = 0xFF26272C, INNER = 0xFF17181B;

    private View divider() {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 1));
        lp.setMargins(0, dp(9), 0, dp(7));
        d.setLayoutParams(lp);
        return d;
    }

    private void say(String words) {
        VoiceOut v = VoiceOut.get(this);
        v.stop();
        v.sayThen(words, "deadpan", null);
    }

    /** The morning brief as a card (the mockup he picked): the bottom line, short sections, a line opens to its detail. */
    private View feedCard(Feed.Brief b) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(PANEL, 16));
        card.setPadding(dp(14), dp(14), dp(14), dp(10));
        LinearLayout head = new LinearLayout(this);
        head.addView(text("Morning brief \u00b7 " + new SimpleDateFormat("EEE MMM d", Locale.US).format(new Date(b.at)), 12, MUTED),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView play = text("\u25B6 Play", 12, TEXT);
        play.setOnClickListener(v -> say(Feed.spoken(b)));
        head.addView(play);
        card.addView(head);
        TextView label = text("BOTTOM LINE", 11, AMBER);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLetterSpacing(0.06f);
        label.setPadding(0, dp(8), 0, dp(2));
        card.addView(label);
        TextView bl = text(b.bottomLine, 16, TEXT);
        bl.setLineSpacing(0, 1.15f);
        card.addView(bl);
        for (Feed.Section sec : b.sections) {
            card.addView(divider());
            TextView name = text(sec.name.toUpperCase(Locale.US), 11, MUTED);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setLetterSpacing(0.08f);
            card.addView(name);
            boolean alert = sec.name.equalsIgnoreCase("Machines");
            for (Feed.Item it : sec.items) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(4), 0, dp(4));
                android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
                if (alert) {
                    line.append("\u25CF  ");
                    line.setSpan(new android.text.style.ForegroundColorSpan(AMBER), 0, 1, 0);
                }
                line.append(it.line);
                TextView lt = text("", 14, alert ? TEXT : SOFT);
                lt.setText(line);
                lt.setSingleLine(true);
                lt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(lt, new LinearLayout.LayoutParams(0, -2, 1));
                TextView chev = text("\u203A", 16, FAINT);
                chev.setPadding(dp(10), 0, dp(2), 0);
                row.addView(chev);
                card.addView(row);
                LinearLayout open = new LinearLayout(this);       // the detail, shown when he taps the line
                open.setOrientation(LinearLayout.VERTICAL);
                open.setBackground(round(INNER, 12));
                open.setPadding(dp(12), dp(10), dp(12), dp(10));
                TextView det = text(it.detail.isEmpty() ? it.line : it.detail, 14, SOFT);
                det.setLineSpacing(0, 1.15f);
                open.addView(det);
                if (!it.link.isEmpty()) {
                    TextView src = text("", 12, FAINT);
                    android.text.SpannableString ss = new android.text.SpannableString(Claude.site(it.link));
                    ss.setSpan(new android.text.style.URLSpan(it.link), 0, ss.length(), 0);
                    src.setText(ss);
                    src.setLinkTextColor(AMBER);
                    src.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    src.setPadding(0, dp(6), 0, 0);
                    open.addView(src);
                }
                LinearLayout chips = new LinearLayout(this);
                Button ask = chip("Ask Sprocket");
                ask.setBackground(round(TEXT, 16));
                ask.setTextColor(0xFF111111);
                ask.setOnClickListener(v -> { if (!busy) ask("Tell me more about this from my morning brief: " + it.line, null); });
                chips.addView(ask, new LinearLayout.LayoutParams(-2, dp(34)));
                Button read = chip("Read it out");
                read.setBackground(round(MINE, 16));
                read.setTextColor(TEXT);
                read.setOnClickListener(v -> say(it.line + ". " + it.detail));
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, dp(34));
                rp.setMargins(dp(8), 0, 0, 0);
                chips.addView(read, rp);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                cp.setMargins(0, dp(10), 0, 0);
                open.addView(chips, cp);
                open.setVisibility(View.GONE);
                LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
                op.setMargins(0, dp(4), 0, dp(4));
                card.addView(open, op);
                row.setOnClickListener(v -> {
                    boolean show = open.getVisibility() != View.VISIBLE;
                    open.setVisibility(show ? View.VISIBLE : View.GONE);
                    chev.setText(show ? "\u2304" : "\u203A");
                });
            }
        }
        return card;
    }

    private LinearLayout panelCard(String title, String hint) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView h = text(title, 17, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        if (hint != null) {
            TextView t = text(hint, 14, MUTED);
            t.setPadding(0, dp(3), 0, dp(4));
            c.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(12));
        c.setLayoutParams(lp);
        return c;
    }

    private Button doneChip(Runnable close) {
        Button done = chip("Done");
        done.setBackground(round(TEXT, 18));
        done.setTextColor(0xFF111111);
        done.setOnClickListener(v -> close.run());
        return done;
    }

    /** The Feed screen (the feed icon): when it comes and what's in it, what it watches for AI news, the last week. */
    private void feedScreen() {
        list.removeAllViews();
        LinearLayout c = panelCard("Every morning at 6:00", "About 5\u201315\u00a2 a day. A notification when it's ready; nothing is "
                + "said out loud until you press Play.");
        if (!FeedAlarm.exact(this)) {
            TextView warn = text("To come right at 6:00, Sprocket needs \u201cAlarms & reminders\u201d switched on.", 14, AMBER);
            warn.setPadding(0, dp(6), 0, dp(6));
            c.addView(warn);
            Button on = chip("Turn it on");
            on.setBackground(round(MINE, 18));
            on.setTextColor(TEXT);
            on.setOnClickListener(v -> FeedAlarm.openSwitch(this));
            c.addView(on, new LinearLayout.LayoutParams(-2, dp(36)));
        }
        for (String[] sec : Feed.SECTIONS) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            row.addView(text(sec[1], 15, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
            android.widget.Switch sw = new android.widget.Switch(this);
            synchronized (feed) { sw.setChecked(feed.on.get(sec[0])); }
            sw.setThumbDrawable(switchPart(true));      // drawn here, solid like the mockup (Android's track is see-through)
            sw.setTrackDrawable(switchPart(false));
            sw.setOnCheckedChangeListener((x, on) -> { synchronized (feed) { feed.on.put(sec[0], on); saveFeed(); } });
            row.addView(sw);
            c.addView(row);
        }
        Button now = chip("Make one now");
        now.setBackground(round(MINE, 18));
        now.setTextColor(TEXT);
        now.setOnClickListener(v -> { FeedService.start(this); note("Making it: about a minute"); });
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, dp(36));
        np.setMargins(0, dp(8), 0, 0);
        c.addView(now, np);
        list.addView(c);

        LinearLayout w = panelCard("Watching for AI news", "Sprocket adds the AI things you talk about. Take off any you don't want.");
        List<String> topics;
        synchronized (feed) { topics = new ArrayList<>(feed.topics); }
        for (String t : topics) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView tt = text(t, 15, TEXT);
            tt.setPadding(0, dp(7), 0, dp(7));
            row.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
            TextView x = text("\u00d7", 20, FAINT);
            x.setPadding(dp(14), 0, dp(6), 0);
            x.setOnClickListener(v -> { synchronized (feed) { feed.unwatch(t); saveFeed(); } feedScreen(); });
            row.addView(x);
            w.addView(row);
        }
        w.addView(adder("Add a topic", words -> { synchronized (feed) { feed.watch(words); saveFeed(); } feedScreen(); }));
        list.addView(w);

        LinearLayout wk = panelCard("This week", null);
        List<String> week;
        synchronized (feed) { week = new ArrayList<>(feed.briefs); }
        if (week.isEmpty()) wk.addView(text("The first one arrives at 6:00 tomorrow (or tap Make one now).", 14, MUTED));
        for (int i = week.size() - 1; i >= 0; i--) {
            Feed.Brief b = Feed.Brief.fromJson(week.get(i));
            if (b == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(9), 0, dp(9));
            TextView d = text(new SimpleDateFormat("EEE d", Locale.US).format(new Date(b.at)), 12, MUTED);
            d.setPadding(0, dp(2), 0, 0);
            row.addView(d, new LinearLayout.LayoutParams(dp(56), -2));
            row.addView(text(b.bottomLine, 14, SOFT), new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> { if (!openDays.remove(b.at)) openDays.add(b.at); feedScreen(); });
            wk.addView(row);
            if (openDays.contains(b.at)) wk.addView(feedCard(b));
        }
        list.addView(wk);
        list.addView(doneChip(() -> { showingFeed = false; render(); }), new LinearLayout.LayoutParams(-2, dp(40)));
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    /** A switch's thumb (a dot: dark when on) or track (a pill: light when on). */
    private android.graphics.drawable.Drawable switchPart(boolean thumb) {
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        for (boolean on : new boolean[]{true, false}) {
            GradientDrawable g = new GradientDrawable();
            if (thumb) {
                g.setShape(GradientDrawable.OVAL);
                g.setColor(on ? 0xFF111111 : FAINT);
                g.setSize(dp(16), dp(16));
            } else {
                g.setCornerRadius(dp(11));
                g.setColor(on ? TEXT : MINE);
                g.setSize(dp(40), dp(22));
            }
            android.graphics.drawable.Drawable d = thumb ? new android.graphics.drawable.InsetDrawable(g, dp(3)) : g;
            s.addState(on ? new int[]{android.R.attr.state_checked} : new int[]{}, d);
        }
        return s;
    }

    private TextView box(boolean done) {
        TextView b = text(done ? "\u2611" : "\u2610", 22, done ? SOFT : MUTED);
        b.setPadding(0, 0, dp(12), 0);
        return b;
    }

    /** The Goals screen (the goals icon): open goals to tick off or talk over, then the done ones. */
    private void goalsScreen() {
        list.removeAllViews();
        LinearLayout c = panelCard("Goals", "Say \u201cadd a goal\u2026\u201d or type one. Tap a goal to talk it over; hold one to remove it.");
        List<Goals.Goal> all;
        synchronized (goals) { all = new ArrayList<>(goals.goals); }
        int open = 0, done = 0;
        for (Goals.Goal g : all) if (g.done) done++; else open++;
        if (open == 0) c.addView(text("No open goals.", 14, MUTED));
        LinearLayout doneCard = panelCard("Done (" + done + ")", null);
        for (Goals.Goal g : all) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            TextView b = box(g.done);
            b.setOnClickListener(v -> { synchronized (goals) { goals.toggle(g.id, System.currentTimeMillis()); saveGoals(); } goalsScreen(); });
            row.addView(b);
            TextView t = text(g.text, 15, g.done ? FAINT : TEXT);
            if (g.done) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            t.setOnClickListener(v -> {
                if (busy) return;
                showingGoals = false;
                render();
                ask("Let's talk about my goal: " + g.text, null);
            });
            t.setOnLongClickListener(v -> {
                new android.app.AlertDialog.Builder(this).setMessage("Remove \u201c" + g.text + "\u201d?")
                        .setPositiveButton("Remove", (d, w) -> { synchronized (goals) { goals.delete(g.id); saveGoals(); } goalsScreen(); })
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            (g.done ? doneCard : c).addView(row);
        }
        c.addView(adder("Add a goal", words -> { synchronized (goals) { goals.add(words, System.currentTimeMillis()); saveGoals(); } goalsScreen(); }));
        list.addView(c);
        if (done > 0) list.addView(doneCard);
        list.addView(doneChip(() -> { showingGoals = false; render(); }), new LinearLayout.LayoutParams(-2, dp(40)));
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    /** ⋯ Lists: every list; tap an item to tick it off (or back on), x to take it off, add items or a new list. */
    private void listsCard() {
        list.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView h = text("Lists", 17, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        if (!prefs().notionKey().isEmpty()) {
            String n = listsNote;
            c.addView(text(n == null ? "Checking Notion\u2026" : n.isEmpty() ? "Kept in Notion (Sprocket lists): edit them there too."
                    : "Notion: " + n + ". Changes wait on the phone until it answers.", 13, n != null && !n.isEmpty() ? ERR : MUTED));
        }
        java.util.Map<String, List<Lists.Item>> all;
        synchronized (lists) { all = new java.util.LinkedHashMap<>(lists.lists); }
        if (all.isEmpty()) c.addView(text("None yet. Tell him \u201cadd milk to the grocery list\u201d, or make one below.", 14, MUTED));
        for (java.util.Map.Entry<String, List<Lists.Item>> e : all.entrySet()) {
            String name = e.getKey();
            TextView title = text(Character.toUpperCase(name.charAt(0)) + name.substring(1), 16, TEXT);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(0, dp(16), 0, dp(2));
            c.addView(title);
            java.util.Map<String, List<Lists.Item>> byAisle;
            synchronized (lists) { byAisle = lists.groups(name); }
            List<Object> rows = new ArrayList<>();                    // aisle headings (strings) and items, in order
            if (byAisle.isEmpty()) rows.addAll(e.getValue());
            else {
                for (java.util.Map.Entry<String, List<Lists.Item>> g : byAisle.entrySet()) { rows.add(g.getKey()); rows.addAll(g.getValue()); }
                for (Lists.Item it : e.getValue()) if (it.done) rows.add(it);
            }
            java.time.LocalDate today = java.time.LocalDate.now();
            for (Object r : rows) {
                if (r instanceof String) {
                    TextView ah = text((String) r, 12, MUTED);
                    ah.setPadding(0, dp(10), 0, 0);
                    c.addView(ah);
                    continue;
                }
                Lists.Item it = (Lists.Item) r;
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                String when = it.done || it.due.isEmpty() ? "" : "   " + (java.time.LocalDate.parse(it.due).isBefore(today) ? "overdue, " : "")
                        + java.time.LocalDate.parse(it.due).format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", Locale.US));
                TextView t = text((it.done ? "\u2611  " : "\u2610  ") + it.text + when, 15, it.done ? FAINT : TEXT);
                if (it.done) t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
                t.setPadding(0, dp(8), 0, dp(8));
                t.setOnClickListener(v -> listChange(() -> lists.check(name, it.text, !it.done)));
                row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
                TextView x = text("\u00d7", 20, FAINT);
                x.setPadding(dp(14), 0, dp(6), 0);
                x.setOnClickListener(v -> listChange(() -> lists.remove(name, it.text)));
                row.addView(x);
                c.addView(row);
            }
            c.addView(adder("Add to " + name, words -> listChange(() -> lists.add(name, new String[]{words}))));
            LinearLayout btns = new LinearLayout(this);
            Button clearDone = chip("Clear ticked");
            clearDone.setBackground(round(MINE, 18));
            clearDone.setTextColor(TEXT);
            clearDone.setOnClickListener(v -> listChange(() -> lists.clear(name, true)));
            btns.addView(clearDone, new LinearLayout.LayoutParams(-2, dp(36)));
            Button del = chip("Delete list");
            del.setBackground(round(MINE, 18));
            del.setTextColor(TEXT);
            del.setOnClickListener(v -> new android.app.AlertDialog.Builder(this).setMessage("Delete the " + name + " list?")
                    .setPositiveButton("Delete", (d, w) -> listChange(() -> lists.delete(name)))
                    .setNegativeButton("Cancel", null).show());
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(-2, dp(36));
            dp2.setMargins(dp(10), 0, 0, 0);
            btns.addView(del, dp2);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.setMargins(0, dp(6), 0, 0);
            c.addView(btns, bp);
        }
        TextView nl = text("New list", 16, TEXT);
        nl.setTypeface(Typeface.DEFAULT_BOLD);
        nl.setPadding(0, dp(18), 0, dp(2));
        c.addView(nl);
        c.addView(adder("Its name", words -> listChange(() -> lists.lists.putIfAbsent(Lists.key(words), new ArrayList<>()))));
        Button done = chip("Done");
        done.setBackground(round(TEXT, 18));
        done.setTextColor(0xFF111111);
        done.setOnClickListener(v -> { showingLists = false; render(); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(40));
        bp.setMargins(0, dp(16), 0, 0);
        c.addView(done, bp);
        list.addView(c);
    }

    /** A one-line text box with an Add button. */
    private View adder(String hint, java.util.function.Consumer<String> onAdd) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setTextSize(15);
        e.setSingleLine(true);
        row.addView(e, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = chip("Add");
        add.setBackground(round(MINE, 18));
        add.setTextColor(TEXT);
        add.setOnClickListener(v -> { String w = e.getText().toString().trim(); if (!w.isEmpty()) onAdd.accept(w); });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, dp(36));
        ap.setMargins(dp(8), 0, 0, 0);
        row.addView(add, ap);
        return row;
    }

    private static File memoryFile() { return new File(app.getFilesDir(), "memory.json"); }

    private static Memory loadMemory() {
        try { return Memory.fromJson(new String(Files.readAllBytes(memoryFile().toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new Memory(); }
    }

    private static void saveMemory() {
        try {
            File tmp = new File(app.getFilesDir(), "memory.json.tmp");
            Files.write(tmp.toPath(), memory.toJson().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(memoryFile())) throw new Exception("rename failed");
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "memory not saved: " + e);
        }
    }

    private static File aboutFile() { return new File(app.getFilesDir(), "about.md"); }

    private static String loadAbout() {
        try { return Memory.tidyAbout(new String(Files.readAllBytes(aboutFile().toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return ""; }
    }

    private static boolean saveAbout(String text) {
        try {
            File tmp = new File(app.getFilesDir(), "about.md.tmp");
            Files.write(tmp.toPath(), Memory.tidyAbout(text).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(aboutFile())) throw new Exception("rename failed");
            about = Memory.tidyAbout(text);
            return true;
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "about page not saved: " + e);
            return false;
        }
    }

    /** A page handed over from the computer (adb push to the app's own folder, which no other app can write):
     * becomes his About-you page, and the hand-over copy goes. True when there was one. */
    private static boolean importAbout() {
        File in = app.getExternalFilesDir(null) == null ? null : new File(app.getExternalFilesDir(null), "about-you.md");
        if (in == null || !in.isFile()) return false;
        try {
            if (!saveAbout(new String(Files.readAllBytes(in.toPath()), StandardCharsets.UTF_8))) return false;
            if (!in.delete()) android.util.Log.w("Sprocket", "about hand-over copy not removed");
            return true;
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "about page not imported: " + e);
            return false;
        }
    }

    /** ⋯ Memory: his About-you page (Edit), then every note, each with Delete. */
    private void memoryCard() {
        list.removeAllViews();
        list.addView(aboutCard());
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(12), 0, 0);
        c.setLayoutParams(cp);
        TextView h = text("What Sprocket remembers", 17, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        List<Memory.Note> notes;
        synchronized (memory) { notes = new ArrayList<>(memory.notes); }
        if (notes.isEmpty())
            c.addView(text("Nothing yet. Tell him \u201cremember\u2026\u201d, or just mention something worth keeping.", 14, MUTED));
        else c.addView(text("These go to Claude with each message so he can use them. Delete any.", 14, MUTED));
        for (Memory.Note n : notes) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, 0);
            row.addView(text(n.text, 15, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
            Button del = chip("Delete");
            del.setBackground(round(MINE, 18));
            del.setTextColor(TEXT);
            del.setOnClickListener(v -> {
                synchronized (memory) { memory.delete(n.id); saveMemory(); }
                memoryCard();
            });
            LinearLayout.LayoutParams delp = new LinearLayout.LayoutParams(-2, dp(36));
            delp.setMargins(dp(10), 0, 0, 0);
            row.addView(del, delp);
            c.addView(row);
        }
        Button done = chip("Done");
        done.setBackground(round(TEXT, 18));
        done.setTextColor(0xFF111111);
        done.setOnClickListener(v -> { showingMemory = false; render(); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, dp(40));
        bp.setMargins(0, dp(16), 0, 0);
        c.addView(done, bp);
        list.addView(c);
    }

    /** His About-you page: read (drawn like a brief) with Edit, or a text box with Save and Cancel. */
    private View aboutCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(PANEL, 16));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView h = text("About you", 17, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
        c.addView(top);
        c.addView(text("Goes to Claude with each message. Never passwords, keys, emails or IP addresses.", 14, MUTED));
        if (editingAbout) {
            EditText e = new EditText(this);
            e.setText(about);
            e.setTextColor(TEXT);
            e.setTextSize(14);
            e.setGravity(Gravity.TOP);
            e.setMinLines(8);
            e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(Memory.ABOUT_LONGEST)});
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
            ep.setMargins(0, dp(10), 0, 0);
            c.addView(e, ep);
            LinearLayout row = new LinearLayout(this);
            Button save = chip("Save");
            save.setBackground(round(TEXT, 18));
            save.setTextColor(0xFF111111);
            save.setOnClickListener(v -> {
                if (!saveAbout(e.getText().toString())) { note("Couldn't save it"); return; }
                editingAbout = false;
                hideKeyboard(e);
                render();
            });
            Button cancel = chip("Cancel");
            cancel.setBackground(round(MINE, 18));
            cancel.setTextColor(TEXT);
            cancel.setOnClickListener(v -> { editingAbout = false; hideKeyboard(e); render(); });
            row.addView(save, new LinearLayout.LayoutParams(-2, dp(40)));
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(-2, dp(40));
            xp.setMargins(dp(10), 0, 0, 0);
            row.addView(cancel, xp);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
            rp.setMargins(0, dp(12), 0, 0);
            c.addView(row, rp);
            return c;
        }
        Button edit = chip("Edit");
        edit.setBackground(round(MINE, 18));
        edit.setTextColor(TEXT);
        edit.setOnClickListener(v -> { editingAbout = true; render(); });
        top.addView(edit, new LinearLayout.LayoutParams(-2, dp(36)));
        if (about.isEmpty()) {
            TextView none = text("Nothing yet. Tap Edit to tell Sprocket about yourself: who you are, your people, your setup.", 15, TEXT);
            none.setPadding(0, dp(10), 0, 0);
            c.addView(none);
        } else markdownInto(c, about);
        return c;
    }

    private void hideKeyboard(View v) {
        android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private static void saveChat() {
        try {
            File tmp = new File(app.getFilesDir(), "chat.json.tmp");
            Files.write(tmp.toPath(), chat.toJson().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(chatFile())) throw new Exception("rename failed");
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "chat not saved: " + e);
        }
    }

    // ------------------------------------------------------------------ self-test (emulator)
    static final class SelfTest {
        final List<String> sentences = new ArrayList<>();
        final List<JSONObject> voiced = java.util.Collections.synchronizedList(new ArrayList<>());

        /** After the reply has been said: each piece, how it was voiced, and whether the follow-up listen came. */
        void writeVoice(Context c, boolean followUp) {
            try {
                JSONObject o = new JSONObject().put("pieces", new JSONArray(voiced)).put("follow_up_listen", followUp);
                Files.write(new File(c.getExternalFilesDir(null), "voicetest.json").toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                android.util.Log.w("Sprocket", "voice test not written: " + e);
            }
        }

        void write(Context c, Claude.Reply r, String error) {
            try {
                JSONObject o = new JSONObject().put("error", error == null ? "" : error)
                        .put("reply", r == null ? "" : r.text).put("model", r == null ? "" : r.model)
                        .put("cost", r == null ? 0 : r.cost()).put("sentences", new JSONArray(sentences));
                File f = new File(c.getExternalFilesDir(null), "selftest.json");
                Files.write(f.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                android.util.Log.w("Sprocket", "self-test not written: " + e);
            }
        }
    }

    /** The app's tools for Claude: the weather, for where he is (his phone's rough location) or a town he names. */
    private static final Claude.Tools TOOLS = new Claude.Tools() {
        @Override public String run(String name, JSONObject input) throws Exception {
            if (name.equals("screen")) usedHands = true;
            if (name.equals("remember") || name.equals("forget")) return remember(name, input);
            if (name.startsWith("list_")) return listTool(name, input);
            if (name.startsWith("goal")) return goalTool(name, input);
            if (name.equals("watch_ai_news")) return watchTool(input);
            if (name.equals("check_texts") || name.equals("read_texts")) return texts(name, input);
            if (name.equals("check_notifications")) return notices(input.optString("app"));
            if (name.equals("check_machines")) return Machines.check(input.optString("machine"));
            if (name.equals("studio_jobs")) return Studio.jobs(Studio.http("/jobs", null, 15_000).getJSONArray("jobs"), System.currentTimeMillis());
            if (name.equals("studio_make")) {
                String kind = input.optString("kind"), what = input.optString("prompt"), photo = null;
                if (input.optBoolean("use_photo") && kind.equals("video")) {
                    String p = lastPhoto();
                    if (p == null) return "Nothing was made: there's no photo from him in the last half hour to start the video from.";
                    photo = Photo.base64(app, p);
                }
                JSONObject job = Studio.http("/jobs", Studio.body(kind, what, input.optString("quality"), input.optString("shape"),
                        photo, input.optString("lyrics"), input.optString("title")), 60_000);
                StudioService.watch(app, job);
                Hands.record("Studio: making a " + kind + (photo != null ? " from his photo" : "") + " (" + Studio.brief(what) + ")");
                return Studio.queued(job);
            }
            if (name.equals("github_threads")) return GitHub.checkThreads(app);
            if (name.equals("github_repos")) return GitHub.checkRepos(input.optString("repo"));
            if (name.equals("machine_control")) {
                String job = input.optString("job");
                String[] card = Machines.card(job);
                if (card == null) throw new Exception("there is no job called " + job);
                Boolean yes = Hands.confirm(card[0], card[1]);
                if (yes == null) return "Nothing was done: the boss didn't answer the card.";
                if (!yes) return "Nothing was done: the boss cancelled.";
                String said = Machines.run(job);
                if (job.equals("resume_voice") || job.equals("restart_voice")) VoiceOut.get(app).retryGateway();
                Hands.record("Gateway: " + said);
                return said;
            }
            if (name.equals("look_at_screen")) {
                for (int i = 0; i < 30 && SprocketSession.shownAt > Photo.screenAt
                        && System.currentTimeMillis() - SprocketSession.shownAt < 10_000; i++) Thread.sleep(100);   // still on its way
                long ago = System.currentTimeMillis() - Photo.screenAt;
                if ((Photo.screenAt == 0 || ago > 10 * 60_000L) && ScreenHands.on())   // no side-button picture: a fresh one
                    return ScreenHands.run(new JSONObject().put("action", "look"));
                if (Photo.screenAt == 0 || ago > 10 * 60_000L)
                    return "No picture of his screen: Sprocket gets one only when he holds the side button over another app, "
                            + "with \"Use screenshot\" switched on in his digital assistant settings.";
                return Claude.PICTURE + Photo.SCREEN + "]]His screen when he held the side button, " + Math.max(1, ago / 1000) + " s ago.";
            }
            if (name.equals("research")) {
                String q = input.optString("question").trim(), u = input.optString("url").trim();
                if (q.isEmpty()) throw new Exception("there is nothing to research");
                ResearchService.start(app, q, u, q);
                Hands.record("Research started: " + q);
                return "Started. The brief will be in the chat in a minute or two, and he'll get a notification.";
            }
            if (!name.equals("weather")) return Phone.run(app, name, input);   // phase 3: the phone tools
            String asked = input.optString("place");
            if (asked.isEmpty()) Place.ensure(app);
            return Weather.lookup(asked, Place.coords(app), Place.where(app).town(), Place.usUnits(app));
        }

        @Override public boolean stopped() { return stopped; }   // Stop during a tool: no next round
    };

    static Context appContext() { return app; }

    /** His lists, with Notion on: the latest from Notion first (he may have changed them on the PC), the change, then
     * the change sent to Notion. */
    private static String listTool(String name, JSONObject in) throws Exception {
        String before = syncLists();
        String out = listToolHere(name, in);
        String after = name.equals("list_show") ? before : syncLists();
        if (after != null && !after.isEmpty())
            out += name.equals("list_show") ? " (Notion didn't answer (" + after + "): this is the phone's copy.)"
                    : " (Notion didn't answer (" + after + "): the change is kept on the phone and goes to Notion next time.)";
        return out;
    }

    /** His lists: each change shown in the chat; emptying a whole list only after he taps Clear on a card. */
    private static String listToolHere(String name, JSONObject in) throws Exception {
        String list = in.optString("list");
        JSONArray arr = in.optJSONArray("items");
        String[] items = new String[arr == null ? 0 : arr.length()];
        List<Lists.Item> adds = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {                // each item: its words, or {text, due, aisle}
            JSONObject o = arr.optJSONObject(i);
            items[i] = o != null ? o.optString("text") : arr.optString(i, "");
            String due = o == null ? "" : o.optString("due");
            try { if (!due.isEmpty()) java.time.LocalDate.parse(due); } catch (Exception e) { due = ""; }
            adds.add(new Lists.Item(items[i], false, due, o == null ? "" : o.optString("aisle")));
        }
        String k = Lists.key(list);
        switch (name) {
            case "list_add": {
                int n;
                synchronized (lists) { n = lists.addItems(list, adds); saveLists(); }
                if (n > 0) Hands.record("Added to " + k + ": " + String.join(", ", items));
                synchronized (lists) { return (n == items.length ? "Added. " : "Added " + n + " (the rest were on it already). ") + lists.show(list); }
            }
            case "list_change": {
                String it;
                synchronized (lists) {
                    it = lists.change(list, in.optString("item"), in.has("due") ? in.optString("due") : null,
                            in.has("aisle") ? in.optString("aisle") : null);
                    saveLists();
                }
                if (it == null) return "\u201c" + in.optString("item") + "\u201d isn't on the " + k + " list. " + lists.show(list);
                if (it.equals("bad date")) return "That due date isn't a date (YYYY-MM-DD). Nothing changed.";
                Hands.record("Changed on " + k + ": " + it);
                synchronized (lists) { return "Changed. " + lists.show(list); }
            }
            case "list_show":
                synchronized (lists) { return lists.show(list); }
            case "list_check": {
                boolean done = !in.has("done") || in.optBoolean("done", true);
                String it;
                synchronized (lists) { it = lists.check(list, in.optString("item"), done); saveLists(); }
                if (it == null) return "\u201c" + in.optString("item") + "\u201d isn't on the " + k + " list. " + lists.show(list);
                Hands.record((done ? "Ticked off on " : "Back on ") + k + ": " + it);
                return done ? "Ticked off." : "Back on the list.";
            }
            case "list_remove": {
                List<String> gone = new ArrayList<>(), missing = new ArrayList<>();
                synchronized (lists) {
                    for (String i : items) { String g = lists.remove(list, i); if (g == null) missing.add(i); else gone.add(g); }
                    saveLists();
                }
                if (!gone.isEmpty()) Hands.record("Removed from " + k + ": " + String.join(", ", gone));
                return (gone.isEmpty() ? "Nothing removed." : "Removed " + String.join(", ", gone) + ".")
                        + (missing.isEmpty() ? "" : " Not on it: " + String.join(", ", missing) + ".");
            }
            case "list_clear": {
                boolean onlyDone = in.optBoolean("only_done", false);
                int size;
                synchronized (lists) { size = lists.items(list).size(); }
                if (size == 0) return "The " + k + " list is empty already.";
                if (!onlyDone) {
                    Boolean yes = Hands.confirm("Clear the " + k + " list (" + size + (size == 1 ? " item" : " items") + ")?", "Clear");
                    if (yes == null || !yes) return "Not cleared: the boss " + (yes == null ? "didn't answer the card." : "cancelled.");
                }
                int n;
                synchronized (lists) { n = lists.clear(list, onlyDone); saveLists(); }
                Hands.record((onlyDone ? "Cleared ticked-off items from " : "Cleared the ") + k + (onlyDone ? "" : " list") + " (" + n + ")");
                return "Cleared " + n + ".";
            }
            default: throw new Exception("there is no tool called " + name);
        }
    }

    private static String goalTool(String name, JSONObject in) throws Exception {
        long now = System.currentTimeMillis();
        synchronized (goals) {
            switch (name) {
                case "goal_add": {
                    String g = in.optString("goal").trim();
                    if (g.isEmpty()) throw new Exception("there's no goal to add");
                    boolean added = goals.add(g, now);
                    saveGoals();
                    if (added) Hands.record("New goal: " + g);
                    return added ? "Added to his goals." : "He already has that goal (or the list is full).";
                }
                case "goal_done": {
                    boolean done = !in.has("done") || in.optBoolean("done");
                    String g = goals.done(in.optString("goal"), done, now);
                    saveGoals();
                    if (g == null) return "No goal matches that. " + goals.show();
                    Hands.record((done ? "Goal done: " : "Goal back on: ") + g);
                    return (done ? "Ticked off: " : "Back on: ") + g;
                }
                case "goal_remove": {
                    String g = goals.remove(in.optString("goal"));
                    saveGoals();
                    if (g == null) return "No goal matches that. " + goals.show();
                    Hands.record("Goal removed: " + g);
                    return "Removed: " + g;
                }
                default: return goals.show();
            }
        }
    }

    /** The morning brief's AI news topics: what he talks about gets added (he sees and removes them on the Feed screen). */
    private static String watchTool(JSONObject in) throws Exception {
        String action = in.optString("action"), topic = in.optString("topic").trim();
        synchronized (feed) {
            if (action.equals("add")) {
                if (topic.isEmpty()) return "No topic was given.";
                boolean added = feed.watch(topic);
                saveFeed();
                if (added) Hands.record("Watching AI news for: " + topic);
                return added ? "Watching for news on " + topic + "." : "Already watching that (or 30 topics already).";
            }
            if (action.equals("remove")) {
                String gone = feed.unwatch(topic);
                saveFeed();
                if (gone == null) return "Not watching anything like that. Watching: " + String.join("; ", feed.topics) + ".";
                Hands.record("Stopped watching: " + gone);
                return "Stopped watching " + gone + ".";
            }
            return "Watching for AI news: " + String.join("; ", feed.topics) + ".";
        }
    }

    private static String read(String name) {
        try { return new String(Files.readAllBytes(new File(app.getFilesDir(), name).toPath()), StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }

    private static void write(String name, String text) {
        try {
            File tmp = new File(app.getFilesDir(), name + ".tmp");
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(new File(app.getFilesDir(), name))) throw new Exception("rename failed");
        } catch (Exception e) {
            android.util.Log.w("Sprocket", name + " not saved: " + e);
        }
    }

    static void saveGoals() { try { write("goals.json", goals.toJson()); } catch (Exception e) { android.util.Log.w("Sprocket", "goals: " + e); } }

    static void saveFeed() { try { write("feed.json", feed.toJson()); } catch (Exception e) { android.util.Log.w("Sprocket", "feed: " + e); } }

    /** Phase 3b: a note saved or deleted, each shown in the chat. */
    private static String remember(String name, JSONObject input) throws Exception {
        if (name.equals("remember")) {
            String note = input.optString("note").trim();
            if (note.isEmpty()) throw new Exception("there was nothing to remember");
            boolean added;
            synchronized (memory) { added = memory.add(note, System.currentTimeMillis()); saveMemory(); }
            if (added) Hands.record("Remembered: " + note);
            return added ? "Saved." : "Already saved.";
        }
        List<String> gone;
        synchronized (memory) { gone = memory.forget(input.optString("about")); saveMemory(); }
        for (String g : gone) Hands.record("Forgot: " + g);
        return gone.isEmpty() ? "No saved note matches that." : "Deleted: " + String.join(" / ", gone);
    }

    void renderNow() { render(); }

    /** Main thread: a real action Sprocket did, recorded above the reply that is still coming in (Hands.record). */
    /** His texts (caught by MessageListener): who has new ones, or the words to read out. */
    private static String texts(String name, JSONObject input) {
        if (!MessageListener.on(app)) {
            MessageListener.openSwitch(app);
            return "Sprocket can't see his texts yet: he needs to switch on Sprocket's notification access (that switch is "
                    + "open on his screen now). Only texts that arrive after that can be read.";
        }
        Messages m = MessageListener.texts(app);
        String out;
        long now = System.currentTimeMillis();
        synchronized (m) {
            out = name.equals("check_texts") ? m.news(now, TimeZone.getDefault())
                    : m.read(input.optString("from"), input.optInt("count", 0), now, TimeZone.getDefault());
        }
        if (name.equals("read_texts")) MessageListener.save(app);   // what was read is no longer new
        return out;
    }

    /** What his notifications say right now (email included): nothing is kept. */
    private static String notices(String which) {
        if (!MessageListener.on(app)) {
            MessageListener.openSwitch(app);
            return "Sprocket can't see his notifications yet: he needs to switch on Sprocket's notification access (that "
                    + "switch is open on his screen now).";
        }
        List<Notices.Notice> showing = MessageListener.showing(app);
        if (showing == null) return "The notification reader is still starting; ask again in a few seconds.";
        return Notices.describe(showing, which, System.currentTimeMillis(), TimeZone.getDefault());
    }

    static void addAction(String what) {
        Chat.Turn t = new Chat.Turn("action", what, System.currentTimeMillis());
        int at = pending == null ? -1 : chat.turns.indexOf(pending);
        if (at < 0) chat.turns.add(t); else chat.turns.add(at, t);
        saveChat();
        if (current != null) current.render();
    }

    /** Location: asked once (rough area only); refused = searches by time zone, as before. */
    private void askLocationOnce() {
        android.content.SharedPreferences sp = getSharedPreferences("sprocket", Context.MODE_PRIVATE);
        if (!ready() || Setup.running(setupAt)) return;     // a new install: asked in setup instead
        if (!Config.on("location") || Place.allowed(this) || sp.getBoolean("asked_location", false)) return;
        sp.edit().putBoolean("asked_location", true).apply();
        requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, 7);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (code == 7 && Place.allowed(this)) Place.refresh(this);
        if (code == ASK_TOOL_PERMISSION) Hands.permissionsAnswered();
        if (code == ASK_SETUP && Setup.running(setupAt)) setupContinue();
        if (code == ASK_MIC && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) talk(false);
    }

    private void selfTest(Intent test) {
        if (test == null) return;
        String cards = test.getStringExtra("st_test_cards");   // Sprocket Test only: "no" = Send/Call cards cancel themselves
        if (cards != null && getPackageName().endsWith(".test")) { Hands.testCards = cards; return; }
        String call = test.getStringExtra("st_fake_call");   // Sprocket Test only: a pretend ringing call, "Name|number"
        if (call != null && getPackageName().endsWith(".test")) {
            String heard = test.getStringExtra("st_call_hear");   // what the listens "hear" in turn ("~4000" = silence); else the mic
            Listen.script = heard == null ? null : new java.util.ArrayDeque<>(java.util.Arrays.asList(heard.split("\\|")));
            CallAnnouncer.testCalls = true;
            CallAnnouncer.pretend(this, call);
            return;
        }
        if (test.getBooleanExtra("st_clear_lists", false) && getPackageName().endsWith(".test")) {   // test leftovers out
            synchronized (lists) { lists = new Lists(); saveLists(); }
            render();
            android.util.Log.i("PA-REPLY", "LISTS cleared");
            return;
        }
        if (test.getBooleanExtra("st_brief_now", false) && getPackageName().endsWith(".test")) {   // a real brief now, his brain
            FeedService.start(this);
            return;
        }
        String qk = test.getStringExtra("st_qwen_key");   // stage 2: Qwen's access code, only ever over adb
        if (qk != null) { prefs().ownKey(qk); Config.own = true; prefs().saveConfig(); Claude.useBrain(true); render(); return; }
        String cfg = test.getStringExtra("st_config");   // his settings (Config JSON, plus own_key), only ever over adb
        if (cfg != null) {
            Config.fromJson(cfg);
            try {
                JSONObject o = new JSONObject(cfg);
                if (!o.optString("own_key", "").isEmpty()) prefs().ownKey(o.optString("own_key"));
                if (o.has("persona")) { prefs().persona(o.optString("persona")); persona = o.optString("persona").trim().isEmpty() ? defaultPersona() : o.optString("persona"); }
            } catch (Exception ignored) { }
            prefs().saveConfig();
            Claude.useBrain(Config.own);
            if (ready()) startSetup();           // a coding agent set the brain up (AGENTS.md): the walkthrough still runs
            render();
            return;
        }
        // stage 1: run tools directly, results to logcat (tag PA-TOOL). st_steps is a JSON array of
        // {"tool": name, "input": {...}, "wait": ms before it}, run in order, so a test can leave Phone Assistant first.
        String tool = test.getStringExtra("st_tool"), steps = test.getStringExtra("st_steps");
        if (tool != null || steps != null) {
            String input = test.getStringExtra("st_input");
            new Thread(() -> {
                try {
                    org.json.JSONArray list = steps != null ? new org.json.JSONArray(steps) : new org.json.JSONArray()
                            .put(new org.json.JSONObject().put("tool", tool).put("input", new org.json.JSONObject(input == null ? "{}" : input)));
                    for (int i = 0; i < list.length(); i++) {
                        org.json.JSONObject s = list.getJSONObject(i);
                        Thread.sleep(s.optLong("wait", 0));
                        String out;
                        try { out = Phone.run(this, s.getString("tool"), s.optJSONObject("input") == null ? new org.json.JSONObject() : s.getJSONObject("input")); }
                        catch (Exception e) { out = "error: " + e.getMessage(); }
                        android.util.Log.i("PA-TOOL", "step " + (i + 1) + " " + s.getString("tool") + ": " + out);
                    }
                    android.util.Log.i("PA-TOOL", "done");
                } catch (Exception e) {
                    android.util.Log.i("PA-TOOL", "bad steps: " + e.getMessage());
                }
            }).start();
            return;
        }
        String say = test.getStringExtra("st_say"), base = test.getStringExtra("st_base"), k = test.getStringExtra("st_key");
        if (test.getBooleanExtra("st_feed_now", false) && base != null && k != null
                && k.startsWith("sk-standin")) {               // the morning brief, now, against the stand-in
            Claude.base = base;
            testKey = k;
            FeedService.start(this);
            return;
        }
        if (say == null) return;
        // A stand-in server only ever gets a made-up key: the saved real key is never sent anywhere but Anthropic.
        if (base != null) {
            if (k == null || !k.startsWith("sk-standin")) return;
            Claude.base = base;
            Studio.base = base;
            testKey = k;
            Hands.autoAnswer = test.getStringExtra("st_confirm");   // "yes" / "no": the card answers itself after 5 s
            Hands.testHeard = test.getStringExtra("st_card_heard");  // or the card "hears" these words (no mic here)
        }
        if (test.getBooleanExtra("st_new", false)) { chat = new Chat(); saveChat(); }
        String pic = test.getStringExtra("st_photo");          // a test picture in the app's own external folder
        if (pic != null) {
            try { attached = Photo.save(app, Uri.fromFile(new File(getExternalFilesDir(null), pic))); }
            catch (Exception e) { android.util.Log.w("Sprocket", "test photo: " + e); }
        }
        harder = test.getBooleanExtra("st_harder", false);
        render();
        SelfTest st = new SelfTest();
        VoiceOut.get(this).watch = (text, mood, how, ms) -> {
            try { st.voiced.add(new JSONObject().put("text", text).put("mood", mood).put("how", how).put("fetch_ms", ms)); }
            catch (Exception ignored) { }
        };
        spokenTurn = test.getBooleanExtra("st_spoken", false);   // as if he had said it (the mic can't be tested here)
        String hear = test.getStringExtra("st_hear");           // what the listens after the answer "hear" (no mic here)
        Listen.script = hear == null || hear.isEmpty() || base == null ? null : new java.util.ArrayDeque<>(java.util.Arrays.asList(hear.split("\\|")));
        if (test.getStringExtra("st_card_say") != null) {     // the card's next "heard" words (stand-in only)
            SprocketSession.testHeard = test.getStringExtra("st_card_say");
            return;
        }
        ask(say, st);
    }
}
