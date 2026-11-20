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
    static final int BG = 0xFF111214, PANEL = 0xFF1C1D21, MINE = 0xFF2A2B30, TEXT = 0xFFE6E7EA,
            MUTED = 0xFF8B8F97, FAINT = 0xFF5D6169, AMBER = 0xFFE3B341, ERR = 0xFFE5786D;

    // The conversation and the reply in progress belong to the app, not to one screen: Android can rebuild the screen
    // mid-reply (emulator test, 2026-10-17: the new screen loaded the chat before the old one saved it), and the new
    // screen must carry on with the same live conversation.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final HttpURLConnection[] live = new HttpURLConnection[1];
    static Chat chat;
    static boolean busy, stopped;
    private static Chat.Turn pending;          // the reply streaming in right now
    static Memory memory;                      // phase 3b: what he's had remembered (files/memory.json)
    static Lists lists;                        // his lists (files/lists.json)
    static AppNotes appNotes;                  // app memory for screen control (files/app-notes.json)
    static Goals goals;                        // what he's working toward (files/goals.json)
    static Schedules schedules = new Schedules();   // scheduled tasks (files/schedules.json; ScheduleService runs them)
    /** A scheduled task is the question now being answered: quiet (no voice), its cards ask by notification. */
    private static volatile boolean scheduled;
    private static java.util.function.BiConsumer<String, String> scheduledDone;
    private static Chat.Turn scheduledTurn;      // the reply the scheduled run is waiting for: only that one finishes it
    private static Runnable afterFinish;         // his question, held while a scheduled run stops for it
    /** runScheduled's answer when he started a question first: the service waits and tries again. */
    static final String[] BUSY = new String[2];
    static Tv tv = new Tv();                   // TVs on the home Wi-Fi he paired (files/tv.json: their keys never leave the phone)
    static UndoLog undoLog = new UndoLog();    // what Sprocket did for each request, and how to put it back (files/undo.json)
    static Feed feed;                          // the morning brief: settings, news topics, the last week (files/feed.json)
    static volatile String about = "";         // his About-you page (files/about.md), edited in ⋯ Memory
    static String attached;            // a photo waiting to go with his next message (its file name)
    private static final ExecutorService photoWork = Executors.newSingleThreadExecutor();
    static final android.util.LruCache<String, Bitmap> thumbs = new android.util.LruCache<>(12);
    private static final int TAKE_PHOTO = 21, PICK_PHOTO = 22;
    boolean showingMemory, showingLists, editingAbout, showingFeed, showingGoals;
    final java.util.Set<Long> openDays = new java.util.HashSet<>();   // the Feed screen's opened days
    private LinearLayout attachRow;
    static MainActivity current;              // the screen on show, if any (Hands shows its cards there)
    static final int ASK_TOOL_PERMISSION = 8;       // the screen on show, if any
    static Context app;                // for saving when no screen is on show
    private TextView pendingView;
    static String persona = "";
    LinearLayout list;
    ScrollView scroll;
    EditText box;
    private Button send;
    static boolean harder;

    /** The side-button card (SprocketSession) while it shows: what it hears back about the question it asked. */
    interface Watch {
        void text(String soFar);
        void done(String text, String error);
        void spoken();              // the reply has been said: listen once for his follow-up
    }

    static volatile Watch watch;
    private static final Listen listen = new Listen();   // phase 4: Talk, and the one follow-up
    static boolean spokenTurn;          // the question now being answered was said, not typed
    static final int ASK_MIC = 9;
    static final int ASK_SETUP = 11;
    private LinearLayout bottomBar;               // hidden on the first-open screen, with the top bar's buttons
    private final java.util.List<View> topButtons = new java.util.ArrayList<>();
    // first-time setup (Setup): the question on show, whether its username is awaited, what this step already tried
    static int setupAt = Setup.OFF;
    static boolean setupGithub, setupReturning;
    static final java.util.Set<String> setupTried = new java.util.HashSet<>();
    static String testKey;     // self-test only: a made-up key, never saved

    /** True only in a self-test against the stand-in server: a made-up key, not Claude and not his own server. The self-test's
     * pretend answers (cards that answer themselves, words "heard" without the mic) work only then (Codex 041 #2). */
    static boolean standIn() {
        String b = Claude.base;
        return testKey != null && testKey.startsWith("sk-standin") && !b.startsWith(Claude.ANTHROPIC) && !Config.own;
    }

    // ------------------------------------------------------------------ setup
    /** Everything Sprocket needs, loaded once per run of the app: by this screen or by the side-button card, whichever
     * comes first (the card can open when the app hasn't been opened since the phone started). */
    /** This phone's maker, model, Android and One UI versions and texting app, for how-to searches (Screen.phoneLine). */
    static String phoneLine(Context c) {
        String oneUi = "", smsPkg = null, smsLabel = null;
        try {
            oneUi = (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class, String.class)
                    .invoke(null, "ro.build.version.oneui", "");
        } catch (Exception e) { /* not Samsung, or hidden */ }
        try {
            smsPkg = android.provider.Telephony.Sms.getDefaultSmsPackage(c);
            if (smsPkg != null) {
                android.content.pm.PackageManager pm = c.getPackageManager();
                smsLabel = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(smsPkg, 0)));
            }
        } catch (Exception e) { smsPkg = null; }
        return Screen.phoneLine(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, oneUi, smsPkg, smsLabel);
    }

    static synchronized void init(Context c) {
        if (app != null && chat != null) return;
        app = c.getApplicationContext();
        SharedPreferencesLike sp = new SharedPreferencesLike(app);
        sp.loadConfig();
        Claude.useBrain(Config.own);
        persona = sp.persona().trim().isEmpty() ? defaultPersona() : sp.persona();
        persona = persona.trim() + "\n\n" + phoneLine(app);   // fixed per phone, so the cached prompt stays the same
        chat = loadChat();
        memory = loadMemory();
        about = loadAbout();
        importAbout();
        lists = loadLists();
        appNotes = loadAppNotes();
        goals = Goals.fromJson(read("goals.json"));
        undoLog = UndoLog.fromJson(read("undo.json"));
        tv = Tv.load(read("tv.json"));
        schedules = Schedules.load(read("schedules.json"));
        Phone.watchTorch(app);                  // the flashlight's real state, for undo
        feed = Feed.fromJson(read("feed.json"));
        FeedService.channels(app);
        StudioService.channels(app);
        if (Config.on("brief") && brainSetUp())   // a new install schedules nothing before its brain and setup (Codex 043 #3)
            try { FeedAlarm.schedule(app); } catch (Exception e) { android.util.Log.w("Sprocket", "brief alarm: " + e); }
        ScheduleService.channels(app);
        try { ScheduleAlarm.schedule(app); } catch (Exception e) { android.util.Log.w("Sprocket", "scheduled alarm: " + e); }
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
            takeSpeak(getIntent());
        }
        takeTextBack();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        Intent test = takeTest();
        selfTest(test);
        shared(i, test);
        takeSpeak(i);
        takeTextBack();
    }

    /** A scheduled task's answer notification, tapped: Sprocket opens and says it (11-19). */
    private void takeSpeak(Intent i) {
        String say = i == null ? null : i.getStringExtra(ScheduleService.SPEAK);
        if (say == null || say.trim().isEmpty()) return;
        i.removeExtra(ScheduleService.SPEAK);   // said once, not again when the screen is rebuilt
        VoiceOut.get(app).sayThen(say, "", () -> { });
    }

    /**
     * Worker thread (ScheduleService): asks the task like his own question, quietly, and waits for the answer.
     * Returns {reply, error}: error null when it answered.
     */
    static String[] runScheduled(Schedules.Task k, boolean late) throws InterruptedException {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        String[] out = new String[2];
        boolean[] busyNow = {false};
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            if (busy) { busyNow[0] = true; done.countDown(); return; }   // checked here, where questions start (Codex 055 #2)
            scheduledDone = (text, err) -> { out[0] = text; out[1] = err; done.countDown(); };
            scheduled = true;
            spokenTurn = false;
            Claude.scheduledNow = Schedules.note(k, late);
            scheduledTurn = askQuestion(Schedules.question(k), null, null);
        });
        // its cards may wait an hour for his yes (ScheduleService.ask), so the whole task gets a little more
        if (!done.await(ScheduleService.ASK_WAIT_S + 20 * 60, java.util.concurrent.TimeUnit.SECONDS)) {
            stopAnswer();
            out[1] = "it took too long and was stopped";
        }
        return busyNow[0] ? BUSY : out;
    }

    static boolean scheduledRun() { return scheduled; }

    static void saveSchedules() {
        try { synchronized (schedules) { write("schedules.json", schedules.toJson().toString()); } }
        catch (Exception e) { android.util.Log.w("Sprocket", "schedules: " + e); }
        exportForPractice();
    }

    static void saveTv() {
        try { write("tv.json", tv.toJson()); } catch (Exception e) { android.util.Log.w("Sprocket", "tv: " + e); }
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
        if (backSaves != null) { backSaves.run(); return; }                                              // Back: save
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
        return Claude.Brain.now(id -> testKey != null ? testKey : id.equals("deepseek") ? p.deepKey() : id.equals("openai") ? p.openAiKey()
                : id.equals("own") ? p.ownKey() : p.key(), testKey != null ? Claude.base : null);
    }

    /** Main thread: this morning's brief into the chat (a card) and the Feed screen's week. */
    static void addFeed(Feed.Brief b) {
        try {
            Chat.Turn t = chat.add("assistant", b.toJson(), b.at);
            t.kind = "feed";
            t.model = Config.own ? Config.model() : Claude.claudeModel();
            t.cost = b.cost;
            synchronized (feed) { feed.keep(b); saveFeed(); }
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "brief not kept: " + Claude.scrub(String.valueOf(e), allKeys(null)));
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
    static MediaPlayer song;              // the studio song playing from a card (one at a time)
    static String songFile;
    static TextView songLabel;            // its card's Play/Pause, changed in place (a redraw jumps to the end)
    static final java.util.Set<String> lyricsOpen = new java.util.HashSet<>();

    private View studioCard(JSONObject j) { return new ChatCards(this).studioCard(j); }

    private static String studioMeta(JSONObject j) { return ChatCards.studioMeta(j); }






    private View briefCard(Chat.Turn t) { return new ChatCards(this).briefCard(t); }

    void markdownInto(LinearLayout card, String md) { new ChatCards(this).markdownInto(card, md); }



    // Back to Sprocket after the hands (the owner 2026-11-10: "if the actual sprocket app was open when this ran it should
    // return to the app"): a question asked on Sprocket's screen that used the screen tool comes back here when it's
    // answered, unless the brain chose to leave what he asked to see on screen (screen action "stay").
    private static volatile boolean resumed, startedInApp, usedHands, handsMoved;
    /** Until when a pause is Sprocket's own doing (it moved its hidden-screen result onto the real screen, the reply went silent). */
    private static volatile long ownMoveUntil;
    private static volatile String askedNow = "";

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        boolean ownMove = System.currentTimeMillis() < ownMoveUntil;   // Sprocket just moved its result onto the real screen
        if (PhoneTools.stopVoiceOnLeave(busy || ownMove, handsMoved || ownMove)) VoiceOut.get(this).stop();   // they left: stop talking (not when Sprocket itself opened or moved an app)
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
        ScheduleService.resume(this);         // a scheduled task Android wouldn't start by itself: now, with the app open
        Place.refresh(this);                  // the town, if it's older than 20 minutes (background)
        ScreenHands.check(this);              // screen control switched off since (11-13): a notice that opens the page
        if (prefs().bg()) BgSetup.keepUp(app);   // background work on, helper gone (a restart): bring it back
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

    SharedPreferencesLike prefs() { return new SharedPreferencesLike(this); }

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
        String activeKey() { return Config.own ? (Config.deep ? deepKey() : Config.oa ? openAiKey() : ownKey()) : key(); }
        /** Their OpenAI API key (11-15: OpenAI as a brain of its own). */
        String openAiKey() { return p.getString("openai_key", ""); }
        void openAiKey(String k) { p.edit().putString("openai_key", k.trim()).apply(); }
        /** Their DeepSeek key (11-15: DeepSeek is a brain of its own, beside Claude and their own server). */
        String deepKey() { return p.getString("deepseek_key", ""); }
        void deepKey(String k) { p.edit().putString("deepseek_key", k.trim()).apply(); }
        /** Background work on the hidden screen (BgSetup, BgScreen): set up from the menu (Android 12 and up). */
        boolean bg() { return p.getBoolean("bg", false); }
        void bg(boolean on) { p.edit().putBoolean("bg", on).apply(); }
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

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    TextView text(String t, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    Button chip(String t) {
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

    void note(String s) { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }

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
        if (android.os.Build.VERSION.SDK_INT >= 31)   // pairing with the phone's own wireless debugging needs Android 12
            m.getMenu().add(0, 9, 5, "Background work (hidden screen)").setCheckable(true).setChecked(prefs().bg());
        m.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 9) {           // background work: set it up, or switch it off
                if (prefs().bg()) {
                    prefs().bg(false);
                    new Thread(() -> { try { BgScreen.cmd("quit"); } catch (Exception ignored) { } }, "sprocket-bg-off").start();
                    note("Background work is off: Sprocket works on your screen again.");
                } else {
                    note("Setting up background work...");
                    new Thread(() -> {
                        boolean ok;
                        try { ok = BgSetup.setup(app, s -> handler.post(() -> { chat.add("error", s, System.currentTimeMillis()); saveChat(); render(); })); }
                        catch (Exception e) { ok = false; android.util.Log.w("Sprocket", "background setup: " + e); }
                        if (ok) prefs().bg(true);
                    }, "sprocket-bg-setup").start();
                }
            } else if (it.getItemId() == 7) {
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
    /**
     * What the phone's Back does on a page with a Save button: save, the way Android's own Settings keep what you
     * changed (11-15: Back left Settings without a word, so a ticked "Screen control" and a new address were thrown
     * away, and Sprocket then said it couldn't use the screen). A save that fails (a bad address) keeps the page open
     * with the reason. Cancel is the way out without saving. Null on every other page.
     */
    Runnable backSaves;

    void render() {
        backSaves = null;
        list.removeAllViews();
        backSaves = null;   // only Settings and Personality set it again
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
            TextView meta = text("Morning brief  \u00b7  " + (ownModel(t.model) ? Claude.ownName() + (Claude.paid(t.model) ? String.format(Locale.US, "  \u00b7  $%.2f", t.cost) : "  \u00b7  free")
                    : (Claude.HAIKU.equals(t.model) ? "Haiku" : "Sonnet") + "  \u00b7  " + String.format(Locale.US, "$%.2f", t.cost)), 11, FAINT);
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
        if (ownModel(t.model) && Claude.paid(t.model)) return Claude.nameFor(t.model) + String.format(Locale.US, "  ·  $%.4f", t.cost);
        if (ownModel(t.model)) return Claude.nameFor(t.model) + ", your server  ·  free";
        String who = Claude.OPUS.equals(t.model) ? "Opus, thinking harder" : Claude.HAIKU.equals(t.model) ? "Haiku" : "Sonnet";
        return who + String.format(Locale.US, "  ·  $%.4f", t.cost);
    }

    private void scrollDown() { scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }

    void keyCard(boolean replacing) { new SetupWalkthrough(this).keyCard(replacing); }

    // ------------------------------------------------------------------ first open (Setup)
    static final String ADDRESS_HOW = "The go-between's start.py prints exactly what to type here. Its address is a Tailscale one "
            + "(https://my-computer.my-tailnet.ts.net:8448), another https:// one, or a home-network one "
            + "(http://192.168.1.50:8048: only at home, and unencrypted). The README shows how to set each one up.";
    // Codex's review 2026-11-12: a newcomer needs the steps, and the surprise said up front.
    static final String CLAUDE_KEY_HOW = "How to get one: 1. Sign in at console.anthropic.com. The API is paid for what you use. A "
            + "Claude Max, Ultra or Team plan includes monthly API credit: claim it at claude.ai under Settings > Billing > API "
            + "credits > Link organization. 2. Or, under Billing in the Console, add a little credit (and set a "
            + "monthly limit under Limits, if you like). 3. Under API keys, create a key, copy it and paste it here. It stays "
            + "on this phone.";
    static final String CONSOLE_KEYS = "https://console.anthropic.com/settings/keys";
    static final String OPENAI_KEY_HOW = "How to get one: 1. Sign in at platform.openai.com. A ChatGPT subscription doesn't cover "
            + "this: the API is paid separately, for what you use. 2. Under Billing, add a little credit. 3. Under API keys, "
            + "create a key, copy it and paste it here. It stays on this phone. What you ask goes to OpenAI.";
    static final String DEEPSEEK_KEY_HOW = "How to get one: 1. Sign in at platform.deepseek.com. 2. Add a little credit (a few "
            + "dollars goes a long way: most questions cost well under a cent). 3. Under API keys, create a key, copy it and "
            + "paste it here. It stays on this phone. What you ask goes to DeepSeek, a company in China.";

    private void welcomeScreen() { new SetupWalkthrough(this).welcomeScreen(); }



    void startSetup() { new SetupWalkthrough(this).startSetup(); }


    private void addSetupCard() { new SetupWalkthrough(this).addSetupCard(); }


    private boolean setupTyped(String s) { return new SetupWalkthrough(this).setupTyped(s); }


    private void setupContinue() { new SetupWalkthrough(this).setupContinue(); }





    // ------------------------------------------------------------------ settings (Config)
    EditText field(LinearLayout c, String label, String value, String hint, boolean secret) {
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

    TextView heading(LinearLayout c, String t) {
        TextView h = text(t, 16, TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dp(18), 0, dp(2));
        c.addView(h);
        return h;
    }

    Button button(String t, boolean main) {
        Button b = chip(t);
        b.setBackground(round(main ? TEXT : MINE, 18));
        b.setTextColor(main ? 0xFF111111 : TEXT);
        return b;
    }

    private void settingsCard() { new SettingsPage(this).settingsCard(); }


    private void notionCard() { new SettingsPage(this).notionCard(); }

    private String key() { return testKey != null ? testKey : prefs().activeKey(); }

    /** A brain is set up (without the self-test's stand-in): what the morning brief's alarm waits for. */
    static boolean brainSetUp() {
        SharedPreferencesLike sp = new SharedPreferencesLike(app);
        return Config.own ? (Config.deep ? !sp.deepKey().isEmpty() : Config.oa ? !sp.openAiKey().isEmpty() : !Config.ownBase.trim().isEmpty()) : !sp.key().isEmpty();
    }

    /** A brain he can ask: Claude with its key, or his own server with its address (a key only if it wants one). */
    boolean ready() {
        return testKey != null || (Config.own ? (Config.deep ? !prefs().deepKey().isEmpty() : Config.oa ? !prefs().openAiKey().isEmpty()
                : !Config.ownBase.trim().isEmpty()) : !prefs().key().isEmpty());
    }

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

    void ask(String said, SelfTest st) {
        String photo = attached;
        attached = null;
        showAttached();
        askQuestion(said, photo, st);
    }

    /** Main thread: a question asked, from this screen or the side-button card (spokenTurn set by whoever heard it). */
    static Chat.Turn askQuestion(String said, String photo, SelfTest st) {
        if (busy && scheduled) {   // he asks while a scheduled task runs: it stops (a waiting yes is a no), then his question
            afterFinish = () -> askQuestion(said, photo, st);
            stopAnswer();
            return null;
        }
        VoiceOut.get(app).stop();
        if (listen.on()) listen.cancel();
        Chat.Turn mine = chat.add("user", said, System.currentTimeMillis());
        if (photo != null) mine.photos.add(photo);
        Chat.Turn reply = chat.add("assistant", "", System.currentTimeMillis());
        pending = reply;
        startedInApp = resumed;
        askedNow = said == null ? "" : said;
        undoLog.begin(askedNow, System.currentTimeMillis());   // "undo that" goes back one of these at a time
        Claude.phoneNow = standIn() ? "" : Phone.settingsNow(app);   // the real settings, not what the chat last said
        Claude.foodNow = PhoneTools.foodAsk(askedNow);                // food rules: usuals and allergies
        {   // can they see the screen? (locked, on the hidden screen, it said the screen was left up for them)
            android.app.KeyguardManager km = (android.app.KeyguardManager) app.getSystemService(KEYGUARD_SERVICE);
            android.os.PowerManager pm = (android.os.PowerManager) app.getSystemService(POWER_SERVICE);
            Claude.situation = Screen.situation(km != null && km.isKeyguardLocked(), pm == null || pm.isInteractive(), BgScreen.on());
        }
        if (new SharedPreferencesLike(app).bg()) BgSetup.keepUp(app);   // helper gone (Wi-Fi was off): back for the next question
        usedHands = false;
        handsMoved = false;
        ScreenHands.startTrail();
        ScreenHands.stay = false;
        boolean think = harder;
        harder = false;                          // "Think harder" is for the next question only
        busy = true;
        stopped = false;
        MainActivity a = current;
        if (a != null) { a.styleHarder(); a.styleSend(); a.render(); }
        Claude.Brain k = brain();
        List<Chat.Turn> history = withHint(chat.window(Claude.HISTORY_TURNS), said);
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
            JSONObject[] req = {null};                  // kept for the backup brain, if this one fails
            Claude.Listener[] listener = {null};
            try {
                TimeZone tz = TimeZone.getDefault();
                String now = new SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US).format(new Date())
                        + " (" + tz.getID() + ")";
                req[0] = Claude.request(persona, page, notes, now, Place.where(app), history, think);
                if (setupHelp) req[0].remove("tools");   // a question about setup is answered, never acted on (Codex 042 #3)
                listener[0] = new Claude.Listener() {
                    public void text(String soFar) {
                        reply.text = soFar;
                        onScreen(x -> x.showPending());
                        Watch w = watch;
                        if (w != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> w.text(soFar));
                    }
                    public void sentence(String s, String mood) {
                        if (st != null) st.sentences.add(s);
                        if (voiceOn() && !scheduled) new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> VoiceOut.get(app).add(s, mood));
                    }
                };
                r = Claude.send(k, req[0], listener[0], live, TOOLS);
            } catch (Exception e) {
                Claude.Reply backup = null;
                try { backup = stopped ? null : askBackup(k, e, req[0], listener[0]); }
                catch (Exception e2) { e = e2; }
                if (backup != null) r = backup;
                else error = stopped ? null : (Net.offline(e) ? (k.own ? "your server didn't answer: check it's running and the phone can reach it" : "no internet: Sprocket's brain lives in the cloud")
                        : Claude.scrub(e.getMessage(), allKeys(k.key)));   // Codex 049 #3
            }
            Claude.Reply done = r;
            String err = error;
            android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
            main.post(() -> finish(reply, done, err, st));
        });
        return reply;
    }

    /**
     * The backup brain (the owner 2026-11-13: "ask first each time"): when the brain that took the question failed before
     * saying or doing anything, and the other one is set up, a card asks whether the other one may answer it. Returns
     * its reply, or null (not offered, or he said no: the first error stands). Worker thread.
     */
    /** Every key this phone holds (and the one in use), for blanking out of error text (Codex 049 #3). */
    static String[] allKeys(String inUse) {
        SharedPreferencesLike p = new SharedPreferencesLike(app);
        return new String[]{inUse, p.key(), p.deepKey(), p.openAiKey(), p.ownKey()};
    }

    private static Claude.Reply askBackup(Claude.Brain failed, Exception e, JSONObject req, Claude.Listener l) throws Exception {
        if (req == null || !Claude.backupOk(e) || standIn()) return null;
        SharedPreferencesLike p = new SharedPreferencesLike(app);
        // the others they set up, cheapest first: their own server, DeepSeek, OpenAI, Claude (11-15: "fall back to
        // whichever models they chose to set up"); each one asks first, and a "no" stops there
        List<Claude.Brain> others = new ArrayList<>();
        if (!Config.ownBase.trim().isEmpty())
            others.add(new Claude.Brain(Config.ownBase.trim().replaceAll("/+$", ""), p.ownKey(), true, Config.ownModel.trim().isEmpty() ? "own" : Config.ownModel.trim()));
        if (!p.deepKey().isEmpty()) others.add(new Claude.Brain(Claude.DEEPSEEK_BASE, p.deepKey(), true, Claude.DEEPSEEK_MODEL));
        if (!p.openAiKey().isEmpty()) others.add(new Claude.Brain(Claude.OPENAI_BASE, p.openAiKey(), true, Claude.OPENAI_MODEL));
        if (!p.key().isEmpty()) others.add(new Claude.Brain(Claude.ANTHROPIC, p.key(), false, ""));
        Exception last = e;
        Claude.Brain down = failed;
        for (Claude.Brain other : others) {
            if (other.own == down.own && other.base.equals(down.base)) continue;     // the one that just failed
            if (other.own == failed.own && other.base.equals(failed.base)) continue;
            if (!Claude.backupOk(last)) break;                                         // it said or did something: no redo
            android.util.Log.i("Sprocket", "brain down (" + Claude.brainName(down) + "): asking about " + Claude.brainName(other));
            Boolean yes = Hands.confirm(Claude.backupCard(down, other), "Ask " + Claude.brainName(other));
            if (yes != Boolean.TRUE || stopped) return null;
            try { return Claude.send(other, req, l, live, TOOLS); }
            catch (Exception next) { last = next; down = other; }
        }
        if (last != e) throw last;
        return null;
    }

    /** The history with what fits his question added to his latest message, for this request only (not the chat): a
     * saved way in an app that might fit, and his notes that fit (the bigger memory, 11-13). */
    private static List<Chat.Turn> withHint(List<Chat.Turn> history, String said) {
        String hint = "";
        if (appNotes != null) synchronized (appNotes) { hint = appNotes.hint(said); }
        if (memory != null && Config.on("memory")) synchronized (memory) { hint = memory.forQuestion(said) + hint; }
        int last = history.size() - 1;
        while (last >= 0 && !history.get(last).role.equals("user")) last--;
        if (hint.isEmpty() || last < 0) return history;
        List<Chat.Turn> out = new ArrayList<>(history);
        Chat.Turn his = out.get(last), with = new Chat.Turn("user", his.text + hint, his.at);
        with.photos.addAll(his.photos);
        out.set(last, with);
        return out;
    }

    /** Runs on the main thread against the screen on show, if there is one. */
    static void onScreen(java.util.function.Consumer<MainActivity> f) {
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
        boolean wasScheduled = scheduled && reply == scheduledTurn;   // this reply, not just "a run is on" (Codex 055 #2)
        if (wasScheduled) { scheduled = false; scheduledTurn = null; Claude.scheduledNow = ""; }
        ScreenHands.glow(false);   // answered (or stopped): the glow goes
        pending = null;
        if (r != null) {
            reply.text = r.text;
            reply.model = r.model;
            reply.cost = r.cost();
        } else {
            if (!stopped) reply.text = "";           // a stop keeps what had arrived; a failure keeps nothing
            if (error != null) chat.add("error", "Couldn't get an answer: " + error + ".", System.currentTimeMillis());
        }
        if (app.getPackageName().endsWith(".test"))   // Sprocket Test only: the stage 5 runner reads replies here, not off the
            android.util.Log.i("PA-REPLY", error != null ? "ERROR " + error   // screen (a screen dump suspends the hands)
                    : reply.text.trim().isEmpty() ? "(no words)" : reply.text.replace('\n', ' '));
        if (reply.text.trim().isEmpty()) chat.turns.remove(reply);
        if (wasScheduled && r != null && Schedules.quiet(reply.text)) {   // "only tell me if" and it isn't so: no trace
            chat.turns.remove(reply);
            for (int i = chat.turns.size() - 1; i >= 0; i--)
                if (chat.turns.get(i).role.equals("user") && chat.turns.get(i).text.startsWith("\u23f0")) { chat.turns.remove(i); break; }
        }
        exportForPractice();
        saveChat();
        if (handsMoved && BgScreen.on() && (ScreenHands.stay || PhoneTools.wantsToSee(askedNow))) {   // worked on the hidden screen:
            ownMoveUntil = System.currentTimeMillis() + 5000;   // the reply keeps talking over the move
            new Thread(() -> {   // what they asked to see (an order's cart, for their checkout tap) onto the real screen
                try { android.util.Log.i("Sprocket", "hidden screen -> the screen: " + BgScreen.cmd("show")); }
                catch (Exception e) { android.util.Log.w("Sprocket", "hidden screen show: " + e); }
            }, "sprocket-show").start();
        }
        if (startedInApp && usedHands && !ScreenHands.stay && !PhoneTools.wantsToSee(askedNow) && !resumed)   // the hands left him in another app: come back
            app.startActivity(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        if (r != null && error == null && usedHands) learnRoute(askedNow, reply.text);
        usedHands = false;
        handsMoved = false;
        if (st != null) st.write(app, r, error);
        Watch w = watch;
        if (w != null) w.done(reply.text, error == null ? null : "Couldn't get an answer: " + error + ".");
        java.util.function.BiConsumer<String, String> sd = scheduledDone;
        if (wasScheduled && sd != null) {
            scheduledDone = null;
            String why = afterFinish != null ? "you asked Sprocket something while it was running, so it stopped" : error == null ? "stopped" : error;
            sd.accept(r == null ? null : reply.text, r == null ? why : null);
        }
        if (r != null && voiceOn() && !wasScheduled) {
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
        Runnable then = afterFinish;   // his question that stopped a scheduled task
        afterFinish = null;
        if (then != null) then.run();
    }

    private void stop() { stopAnswer(); }

    /** Stop the answer coming in (and any card waiting for his tap), and quiet the voice. */
    static void stopAnswer() {
        stopped = true;
        Hands.answer(Hands.card, false);              // a card waiting for his tap: Stop means no
        ScheduleService.cancelAsk();                  // the same for a scheduled task's notification
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

    // ------------------------------------------------------------------ app memory for screen control (AppNotes)
    private static File appNotesFile() { return new File(app.getFilesDir(), "app-notes.json"); }

    private static AppNotes loadAppNotes() {
        try { return AppNotes.fromJson(new String(Files.readAllBytes(appNotesFile().toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new AppNotes(); }
    }

    static void saveAppNotes() {
        try {
            File tmp = new File(app.getFilesDir(), "app-notes.json.tmp");
            Files.write(tmp.toPath(), appNotes.toJson().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(appNotesFile())) throw new Exception("rename failed");
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "app notes not saved: " + e);
        }
    }

    private static final java.util.Set<String> REPLAYABLE = new java.util.HashSet<>(java.util.Arrays.asList("open_app",
            "open_screen", "tap", "type", "scroll", "back"));

    /**
     * After a question that tapped through an app: ask the brain, in the background, which of the steps it took make
     * the shortest way there and what to call the place, and save that route for go_to to replay (Qwen never used the
     * note action itself in the first live test, 2026-11-12). Nothing to learn when a saved way got all the way there.
     */
    private static void learnRoute(String asked, String said) {
        ScreenHands.Trail t = ScreenHands.takeTrail();
        if (!t.tapped || t.replayed || t.pkg.isEmpty()) return;
        Claude.Brain b = brain();
        new Thread(() -> {
            try {
                JSONObject req = new JSONObject().put("model", b.own ? b.model : Claude.claudeModel()).put("max_tokens", 400)
                        .put("stream", true).put("messages", new JSONArray().put(new JSONObject().put("role", "user")
                                .put("content", AppNotes.routePrompt(asked, t.steps, t.end, said))));
                AppNotes.Choice ch = AppNotes.choice(Claude.send(b, req, null, null, null).text, t.steps.size());
                String saved;
                if (ch == null) saved = "no route (it didn't get there, or the answer wasn't a route)";
                else {
                    List<AppNotes.Step> steps = AppNotes.pick(t.steps, ch.steps);
                    boolean ok = true;
                    for (AppNotes.Step st : steps)
                        if (!REPLAYABLE.contains(st.kind) || ((st.kind.equals("tap") || st.kind.equals("type")) && st.label.isEmpty())) ok = false;
                    if (!ok) saved = "no route (a step can't be done again by name)";
                    else synchronized (appNotes) {
                        saved = appNotes.learnRoute(t.pkg, t.app, ch.goal, steps, System.currentTimeMillis());
                        if (saved.startsWith("Noted")) saveAppNotes();
                    }
                }
                android.util.Log.i("Sprocket", "app memory: " + saved);
            } catch (Exception e) {
                android.util.Log.w("Sprocket", "app memory: couldn't learn the route: " + Claude.scrub(e.getMessage(), allKeys(null)));
            }
        }, "sprocket-route").start();
    }

    /** The go_to tool: a saved way to the place he asked for, replayed by the hands; or why not. */
    private static String goTo(String place) throws Exception {
        AppNotes.Route r;
        String appName;
        synchronized (appNotes) {
            r = appNotes.find(place);
            AppNotes.App a = r == null ? null : appNotes.appOf(r);
            appName = a == null ? "" : a.name;
        }
        if (r == null) return "There's no saved way to that yet. Use a shortcut or the screen tool; Sprocket saves the way once it works.";
        return ScreenHands.replay(r, appName);
    }

    /** The screen tool's note action: one fact about the app on screen, for next time. */
    static String noteApp(String pkg, String name, String fact) {
        if (appNotes == null) return "App notes aren't ready yet.";
        synchronized (appNotes) {
            String said = appNotes.learn(pkg, name, fact, System.currentTimeMillis());
            if (said.startsWith("Noted")) saveAppNotes();
            return said;
        }
    }

    /** What a read of this app's screen carries: the facts noted about it, or "". */
    static String appNotesFor(String pkg) {
        if (appNotes == null) return "";
        synchronized (appNotes) { return appNotes.forScreen(pkg); }
    }

    // ------------------------------------------------------------------ his lists in Notion (NotionSync)
    /** The last Notion sync: null = Notion is off (or not checked yet), "" = fine, else what went wrong. */
    static volatile String listsNote;
    static final java.util.concurrent.ExecutorService notionWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

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
    void listChange(Runnable change) {
        notionWorker.execute(() -> {
            if (change != null) {
                synchronized (lists) { change.run(); saveLists(); }
                onScreen(x -> { if (showingLists) x.render(); });
            }
            syncLists();
            onScreen(x -> { if (showingLists) x.render(); });
        });
    }

    static final int SOFT = 0xFFC9CBD0, LINE = 0xFF26272C, INNER = 0xFF17181B;

    View divider() {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2 + 1));
        lp.setMargins(0, dp(9), 0, dp(7));
        d.setLayoutParams(lp);
        return d;
    }

    void say(String words) {
        VoiceOut v = VoiceOut.get(this);
        v.stop();
        v.sayThen(words, "deadpan", null);
    }

    private View feedCard(Feed.Brief b) { return new FeedPage(this).feedCard(b); }

    LinearLayout panelCard(String title, String hint) { return new FeedPage(this).panelCard(title, hint); }

    Button doneChip(Runnable close) { return new FeedPage(this).doneChip(close); }

    private void feedScreen() { new FeedPage(this).feedScreen(); }


    TextView box(boolean done) { return new FeedPage(this).box(done); }

    private void goalsScreen() { new GoalsPage(this).goalsScreen(); }

    private void listsCard() { new ListsPage(this).listsCard(); }

    /** A one-line text box with an Add button. */
    View adder(String hint, java.util.function.Consumer<String> onAdd) {
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

    /** Practice runs only (the stand-in, a made-up key): tools that fail on purpose, by name (PhoneTools.faults). */
    static volatile java.util.Map<String, String> testFaults = new java.util.HashMap<>();

    /**
     * Sprocket Test only: its lists and notes copied where the practice checker can read them over adb (the app's own
     * external folder), after each answer, so a test can check a list really changed, not just what Sprocket said.
     */
    private static void exportForPractice() {
        if (!app.getPackageName().endsWith(".test")) return;
        File out = app.getExternalFilesDir(null);
        if (out == null) return;
        for (File f : new File[]{listsFile(), memoryFile(), new File(app.getFilesDir(), "schedules.json")}) {
            try {
                byte[] b = f.exists() ? java.nio.file.Files.readAllBytes(f.toPath()) : "{}".getBytes();
                java.nio.file.Files.write(new File(out, f.getName()).toPath(), b);
            } catch (Exception e) { android.util.Log.w("Sprocket", "practice export: " + e); }
        }
    }

    private static File memoryFile() { return new File(app.getFilesDir(), "memory.json"); }

    private static Memory loadMemory() {
        try { return Memory.fromJson(new String(Files.readAllBytes(memoryFile().toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new Memory(); }
    }

    static void saveMemory() {
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

    static boolean saveAbout(String text) {
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

    private void memoryCard() { new MemoryPage(this).memoryCard(); }

    static String memorySearch = "";
    static final int NOTES_SHOWN = 60;   // thousands of notes: the newest 60, or the search's first 60



    void hideKeyboard(View v) {
        android.view.inputmethod.InputMethodManager im = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    static void saveChat() {
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
            String fault = standIn() ? testFaults.get(name) : null;   // practice only: this tool fails on purpose
            if ("refused".equals(fault)) throw new Exception(PhoneTools.FAULT_REFUSED);
            if ("no_confirm".equals(fault)) {   // it did happen, but the phone never said so
                try { runTool(name, input); } catch (Exception ignored) { }   // (in the practice box a text can't really go)
                throw new Exception(PhoneTools.FAULT_NO_CONFIRM);
            }
            String out = runTool(name, input);
            if (name.equals("open_app") || name.equals("open_screen") || name.equals("go_to"))   // so the reply says where it ends up
                out += PhoneTools.whereItEnds(BgScreen.on(), ScreenHands.stay || PhoneTools.wantsToSee(askedNow));
            return out;
        }

        private String runTool(String name, JSONObject input) throws Exception {
            if (name.equals("screen")) { usedHands = true; ScreenHands.glow(true); }   // the glow: Sprocket is working the screen
            if (name.equals("screen") || name.equals("open_app") || name.equals("open_screen") || name.equals("go_to")
                    || name.equals("maps") || name.equals("email")) handsMoved = true;   // the next pause is its own doing
            if (name.equals("open_app")) ScreenHands.opened("open_app", input.optString("name"));
            if (name.equals("open_screen")) ScreenHands.opened("open_screen", input.optString("screen"));
            if (name.equals("go_to")) { usedHands = true; ScreenHands.glow(true); return goTo(input.optString("place")); }
            if (name.equals("remember") || name.equals("forget")) return remember(name, input);
            if (name.equals("what_i_did")) return undoLog.whatIDid(input.optInt("requests", 1), System.currentTimeMillis(), java.util.TimeZone.getDefault());
            if (name.equals("undo")) return undo();
            if (name.equals("undo_patch")) return undoPatch(input);
            if (name.startsWith("list_")) return listTool(name, input);
            if (name.startsWith("goal")) return goalTool(name, input);
            if (name.equals("watch_news")) return watchTool(input);
            if (name.equals("check_texts") || name.equals("read_texts")) return texts(name, input);
            if (name.equals("check_notifications")) return notices(input.optString("app"));
            if (name.equals("check_machines")) return Machines.check(input.optString("machine"));
            if (name.equals("tv_find") || name.equals("tv_pair") || name.equals("tv")) return ToolsHere.tvTool(name, input);
            if (name.equals("schedule_task") || name.equals("scheduled_tasks") || name.equals("cancel_scheduled")) return ToolsHere.scheduleTool(name, input);
            if (name.equals("studio_jobs")) return Studio.jobs(Studio.http("/jobs", null, 15_000).getJSONArray("jobs"), System.currentTimeMillis());
            if (name.equals("studio_make")) {
                String kind = input.optString("kind"), what = input.optString("prompt"), photo = null;
                if (input.optBoolean("use_photo") && kind.equals("video")) {
                    String p = lastPhoto();
                    if (p == null) return "Nothing was made: there's no photo from them in the last half hour to start the video from.";
                    photo = Photo.base64(app, p);
                }
                JSONObject job = Studio.http("/jobs", Studio.body(kind, what, input.optString("quality"), input.optString("shape"),
                        photo, input.optString("lyrics"), input.optString("title")), 60_000);
                StudioService.watch(app, job);
                Hands.record("Studio: making a " + kind + (photo != null ? " from their photo" : "") + " (" + Studio.brief(what) + ")");
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
                    return "No picture of their screen: Sprocket gets one only when they hold the side button over another app, "
                            + "with \"Use screenshot\" switched on in their digital assistant settings.";
                return Claude.PICTURE + Photo.SCREEN + "]]Their screen when they held the side button, " + Math.max(1, ago / 1000) + " s ago.";
            }
            if (name.equals("research")) {
                String q = input.optString("question").trim(), u = input.optString("url").trim();
                if (q.isEmpty()) throw new Exception("there is nothing to research");
                ResearchService.start(app, q, u, q);
                Hands.record("Research started: " + q);
                return "Started. The brief will be in the chat in a minute or two, and they'll get a notification.";
            }
            if (!name.equals("weather")) return Phone.run(app, name, input);   // phase 3: the phone tools
            String asked = input.optString("place");
            if (asked.isEmpty()) Place.ensure(app);
            return Weather.lookup(asked, Place.coords(app), Place.where(app).town(), Place.usUnits(app));
        }

        @Override public boolean stopped() { return stopped; }   // Stop during a tool: no next round
    };

    static Context appContext() { return app; }

    private static String listTool(String name, JSONObject in) throws Exception { return ToolsHere.listTool(name, in); }


    private static String goalTool(String name, JSONObject in) throws Exception { return ToolsHere.goalTool(name, in); }

    private static String watchTool(JSONObject in) throws Exception { return ToolsHere.watchTool(in); }

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

    /** Any thread: one thing Sprocket did, into the undo log (see Hands.record). */
    static void logStep(String what, String kind, String tool, JSONObject undoInput) {
        undoLog.add(what, kind, tool, undoInput, System.currentTimeMillis());
        saveUndo();
    }

    static void saveUndo() { try { write("undo.json", undoLog.toJson().toString()); } catch (Exception e) { android.util.Log.w("Sprocket", "undo: " + e); } }

    /** A list change for the undo log: exactly what to put back (Lists.patch), or nothing if the list didn't change. */
    static void recordList(String what, String list, List<Lists.Item> before) throws Exception {
        JSONObject p;
        synchronized (lists) { p = Lists.patch(list, before, lists.copyOf(list)); }
        if (p == null) Hands.record(what);
        else Hands.record(what, UndoLog.AUTO, "undo_patch", new JSONObject().put("kind", "lists").put("patch", p));
    }

    /** recordList, but only when the list really changed (an item already there, ticked off, came back on). */
    static void recordListIfChanged(String what, String list, List<Lists.Item> before) throws Exception {
        JSONObject p;
        synchronized (lists) { p = Lists.patch(list, before, lists.copyOf(list)); }
        if (p != null) Hands.record(what, UndoLog.AUTO, "undo_patch", new JSONObject().put("kind", "lists").put("patch", p));
    }

    static void recordGoal(String what, List<Goals.Goal> before) throws Exception {
        JSONObject p = Goals.patch(before, goals.copy());
        if (p == null) Hands.record(what);
        else Hands.record(what, UndoLog.AUTO, "undo_patch", new JSONObject().put("kind", "goals").put("patch", p));
    }

    static void recordMemory(String what, List<Memory.Note> before) throws Exception {
        JSONObject p;
        synchronized (memory) { p = Memory.patch(before, memory.copy()); }
        if (p == null) Hands.record(what);
        else Hands.record(what, UndoLog.AUTO, "undo_patch", new JSONObject().put("kind", "memory").put("patch", p));
    }

    /** undo's own step: a list, goal or memory change put back exactly. Only while an undo runs (never a brain tool). */
    private static String undoPatch(JSONObject in) throws Exception {
        if (!undoLog.undoing) throw new Exception("there is no tool called undo_patch");
        JSONObject p = in.getJSONObject("patch");
        String r;
        switch (in.optString("kind")) {
            case "lists": synchronized (lists) { r = lists.applyPatch(p); saveLists(); } syncLists(); return r;
            case "goals": synchronized (goals) { r = goals.applyPatch(p); saveGoals(); } return r;
            case "memory": synchronized (memory) { r = memory.applyPatch(p); saveMemory(); } return r;
            default: throw new Exception("nothing to put back");
        }
    }

    /** The undo tool: runs the last request's reverse calls through the same tools, then says what went back. */
    private static String undo() {
        UndoLog.Entry e = undoLog.toUndo();
        if (e == null) return UndoLog.NOTHING;
        List<String> back = new ArrayList<>(), failed = new ArrayList<>(), screen = new ArrayList<>(), never = new ArrayList<>();
        undoLog.undoing = true;
        try {
            for (int i = e.steps.size() - 1; i >= 0; i--) {   // last change first; a step already back is never replayed
                UndoLog.Step s = e.steps.get(i);
                if (s.done) continue;
                if (s.kind.equals(UndoLog.AUTO) && s.tool != null) {
                    try { back.add(UndoLog.undid(s.did, TOOLS.run(s.tool, s.input == null ? new JSONObject() : new JSONObject(s.input.toString())))); s.done = true; }
                    catch (Exception x) { failed.add(s.did + " (" + x.getMessage() + ")"); }   // stays, for the next "undo that"
                } else if (s.kind.equals(UndoLog.SCREEN)) { if (!s.offered) screen.add(s.did); s.offered = true; }   // offered, not done
                else if (s.kind.equals(UndoLog.NEVER)) { never.add(s.did); s.done = true; }
            }
        } finally { undoLog.undoing = false; }
        undoLog.finish(e);
        saveUndo();
        return UndoLog.report(e.asked, back, failed, screen, never);
    }

    static void saveGoals() { try { write("goals.json", goals.toJson()); } catch (Exception e) { android.util.Log.w("Sprocket", "goals: " + e); } }

    static void saveFeed() { try { write("feed.json", feed.toJson()); } catch (Exception e) { android.util.Log.w("Sprocket", "feed: " + e); } }

    private static String remember(String name, JSONObject input) throws Exception { return ToolsHere.remember(name, input); }

    void renderNow() { render(); }

    private static String texts(String name, JSONObject input) { return ToolsHere.texts(name, input); }

    private static String notices(String which) { return ToolsHere.notices(which); }

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

    private void selfTest(Intent test) { new TestHooks(this).selfTest(test); }
}
