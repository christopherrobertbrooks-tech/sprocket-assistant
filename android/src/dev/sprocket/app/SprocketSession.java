package dev.sprocket.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The side button (the owner 2026-10-17: a card over any app; everything works on the lock screen too; it closes by
 * itself when done). Holding the side button slides this card up over whatever is on screen: it listens at once,
 * shows his words and Sprocket's reply as it arrives, speaks it (VoiceOut), then listens once more for a follow-up and
 * closes a few seconds after silence. Same conversation, voice and tools as the app (MainActivity.askQuestion);
 * Send/Call cards appear here. Open chat goes to the full app; a tap above the card closes it.
 */
public class SprocketSession extends VoiceInteractionSession {
    private static final int BG = 0xFF111214, PANEL = 0xFF1C1D21, MINE = 0xFF2A2B30, TEXT = 0xFFE6E7EA,
            MUTED = 0xFF8B8F97, FAINT = 0xFF5D6169, AMBER = 0xFFE3B341;
    private static final long QUIET_CLOSE_MS = 3500, IDLE_CLOSE_MS = 9000;

    static volatile SprocketSession current;
    static volatile long shownAt;                // when the card last opened (his screen arrives just after)
    static volatile String testHeard;            // self-test only (stand-in): the next words "heard", no mic

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listen listen = new Listen();
    private final Runnable closer = this::hide;
    private final Conversation convo = new Conversation();         // stop phrases and "Still there?" (V2 item 3)
    private final Runnable stillThereTimer = this::timeUp;
    private boolean hearingWords;                                  // words are coming in this listen
    private final List<String> rounds = Collections.synchronizedList(new ArrayList<>());   // self-test: what happened
    private TextView said, reply, status;
    private LinearLayout cardBox;
    private Button talk;
    private boolean asked;
    private final List<JSONObject> voiced = Collections.synchronizedList(new ArrayList<>());
    private String heardForTest = "";

    SprocketSession(Context c) { super(c); }

    private int dp(int v) { return Math.round(v * getContext().getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private TextView text(String t, int sp, int color) {
        TextView v = new TextView(getContext());
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        return v;
    }

    private Button button(String t, int bg, int fg) {
        Button b = new Button(getContext());
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setBackground(round(bg, 20));
        b.setTextColor(fg);
        return b;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        MainActivity.init(getContext());
    }

    @Override
    public View onCreateContentView() {
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(0x88000000);                     // the app underneath, dimmed
        root.setOnClickListener(v -> hide());                   // a tap above the card closes it
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(BG);
        float r = dp(22);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(g);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        card.setClickable(true);                                 // taps on the card stay on the card
        LinearLayout top = new LinearLayout(getContext());
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("Sprocket", 17, TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        status = text("", 13, MUTED);
        top.addView(status);
        Button open = button("Open chat", MINE, TEXT);
        open.setOnClickListener(v -> openChat());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, dp(36));
        op.setMargins(dp(10), 0, 0, 0);
        top.addView(open, op);
        card.addView(top);
        said = text("", 20, TEXT);
        said.setPadding(0, dp(12), 0, 0);
        card.addView(said);
        ScrollView sc = new ScrollView(getContext());
        reply = text("", 16, 0xFFC9CBD0);
        reply.setLineSpacing(0, 1.15f);
        reply.setPadding(0, dp(10), 0, 0);
        sc.addView(reply);
        card.addView(sc, new LinearLayout.LayoutParams(-1, -2));
        ((LinearLayout.LayoutParams) sc.getLayoutParams()).height = -2;
        sc.post(() -> {                                          // at most 40% of the screen; the rest scrolls
            int max = (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.4f);
            if (sc.getHeight() > max) { sc.getLayoutParams().height = max; sc.requestLayout(); }
        });
        cardBox = new LinearLayout(getContext());
        cardBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(cardBox);
        talk = button("Talk", TEXT, 0xFF111111);
        talk.setTextSize(15);
        talk.setOnClickListener(v -> talkPressed());
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(46));
        tp.setMargins(0, dp(14), 0, 0);
        card.addView(talk, tp);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(card, cp);
        root.setOnApplyWindowInsetsListener((v, insets) -> {    // above the gesture bar / navigation buttons
            @SuppressWarnings("deprecation") int bottom = insets.getSystemWindowInsetBottom();
            card.setPadding(dp(18), dp(16), dp(18), dp(18) + bottom);
            return insets;
        });
        return root;
    }

    @Override
    public void onHandleScreenshot(android.graphics.Bitmap screenshot) {   // his screen, when "Use screenshot" is on
        android.util.Log.i("Sprocket", "screen handed over: " + (screenshot == null ? "none" : screenshot.getWidth() + "x" + screenshot.getHeight()));
        if (screenshot == null) return;
        android.content.Context c = getContext().getApplicationContext();
        new Thread(() -> Photo.saveScreen(c, screenshot), "sprocket-screen").start();
    }

    @Override
    public void onShow(Bundle args, int flags) {
        shownAt = System.currentTimeMillis();
        android.util.Log.i("Sprocket", "card shown, flags " + flags + " (screenshot " + ((flags & SHOW_WITH_SCREENSHOT) != 0) + ")");
        super.onShow(args, flags);
        // the screen stays on while the card is up (the owner 2026-10-17: on the lock screen "audio is getting cut off when
        // the screen goes blank" -- a dark screen hides the card, and hiding it stops the voice); it sleeps as usual
        // once the card closes itself
        android.view.Window w = getWindow() == null ? null : getWindow().getWindow();
        if (w != null) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        current = this;
        asked = false;
        voiced.clear();
        rounds.clear();
        said.setText("");
        said.setTextColor(MUTED);
        reply.setText("");
        cardChanged();
        MainActivity.watch = new MainActivity.Watch() {
            public void text(String soFar) { reply.setText(soFar); status.setText(""); }
            public void done(String text, String error) {
                reply.setText(error != null ? error : text);
                styleTalk();
                if (!voiceOn() || error != null) closeIn(IDLE_CLOSE_MS);   // nothing to say aloud: close after a while
            }
            public void spoken() { convo.begin(System.currentTimeMillis()); startListening(true); }
        };
        startListening(false);
    }

    @Override
    public void onHide() {
        super.onHide();
        android.util.Log.i("Sprocket", "card hidden");
        main.removeCallbacks(closer);
        main.removeCallbacks(stillThereTimer);
        listen.cancel();
        if (current == this) current = null;
        MainActivity.watch = null;
        VoiceOut.get(getContext()).stop();
        if (!heardForTest.isEmpty()) writeTest();
    }

    private void closeIn(long ms) { main.removeCallbacks(closer); main.postDelayed(closer, ms); }

    private static boolean voiceOn() {
        return MainActivity.appContext().getSharedPreferences("sprocket", Context.MODE_PRIVATE).getBoolean("voice", true);
    }

    /** Listen (now, or once more after a spoken reply); his words go to Sprocket as he finishes them. */
    private void startListening(boolean followUp) {
        main.removeCallbacks(closer);
        String test = MainActivity.standIn() ? testHeard : null;
        if (test != null) {                       // self-test: words given, no mic (the stand-in, made-up key)
            testHeard = null;
            heardForTest = test;
            VoiceOut.get(getContext()).watch = (t, mood, how, ms) -> {
                try { voiced.add(new JSONObject().put("text", t).put("mood", mood).put("how", how).put("fetch_ms", ms)); }
                catch (Exception ignored) { }
            };
            onHeard(test);
            return;
        }
        if (!Listen.scripted() && getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("");
            said.setText("Open Sprocket once and tap Talk to allow the microphone.");
            return;
        }
        VoiceOut.get(getContext()).stop();
        long began = System.currentTimeMillis();
        hearingWords = false;
        boolean started = listen.start(getContext(), new Listen.Heard() {
            public void partial(String soFar) { hearingWords = true; said.setTextColor(TEXT); said.setText(soFar); }
            public void heard(String words) { onHeard(words); }
            public void nothing() {
                styleTalk();
                if (!followUp) {
                    if (asked) closeIn(QUIET_CLOSE_MS);
                    else { said.setText("Didn't catch that. Tap Talk."); closeIn(IDLE_CLOSE_MS); }
                    return;
                }
                Conversation.Next next = convo.nothing(began, System.currentTimeMillis());
                rounds.add("quiet: " + next.name().toLowerCase());
                if (next == Conversation.Next.LISTEN) startListening(true);           // still quiet: keep listening
                else if (next == Conversation.Next.STILL_THERE) stillThere();
                else { status.setText(""); closeIn(QUIET_CLOSE_MS); }               // no answer: the card goes
            }
        });
        if (!started) said.setText("This phone has no speech recognizer.");
        status.setText(started ? (followUp ? "Anything else?" : "Listening…") : "");
        if (started && followUp) {
            main.removeCallbacks(stillThereTimer);
            main.postDelayed(stillThereTimer, convo.due(System.currentTimeMillis()));
        }
        styleTalk();
    }

    /** His words: a stop phrase closes the card (nothing sent to Claude), "I'm here" keeps listening, anything else
     * is his next question. */
    private void onHeard(String words) {
        main.removeCallbacks(stillThereTimer);
        Conversation.Next next = convo.heard(words, System.currentTimeMillis());
        rounds.add("heard \"" + words + "\": " + next.name().toLowerCase());
        said.setTextColor(TEXT);
        said.setText(words);
        if (next == Conversation.Next.END) {
            listen.cancel();
            status.setText("");
            closeIn(700);                         // his words on the card for a moment, then it goes
        } else if (next == Conversation.Next.LISTEN) {
            startListening(true);
        } else {
            ask(words);
        }
    }

    /** 12 s of quiet after an answer: "Still there?" -- unless he's in the middle of saying something. */
    private void timeUp() {
        if (!listen.on() || convo.timeUp(System.currentTimeMillis(), hearingWords) != Conversation.Next.STILL_THERE) return;
        rounds.add("timer: still_there");
        listen.cancel();
        stillThere();
    }

    /** Said by the phone itself (no Claude, no cost), then one more listen; the mic is off while it talks. */
    private void stillThere() {
        main.removeCallbacks(stillThereTimer);
        status.setText("Still there?");
        Runnable then = () -> { startListening(true); status.setText("Still there?"); };
        if (voiceOn()) VoiceOut.get(getContext()).sayThen("Still there, boss?", "deadpan", then);
        else then.run();
    }

    private void ask(String words) {
        asked = true;
        said.setTextColor(TEXT);
        said.setText(words);
        reply.setText("…");
        status.setText("");
        MainActivity.spokenTurnFromCard();
        MainActivity.askQuestion(words, null, null);
        styleTalk();
    }

    private void talkPressed() {
        main.removeCallbacks(closer);
        if (listen.on()) { listen.finish(); return; }
        if (MainActivity.busy()) { MainActivity.stopAnswer(); styleTalk(); return; }
        startListening(false);
    }

    private void styleTalk() {
        boolean busy = MainActivity.busy();
        talk.setText(busy ? "Stop" : listen.on() ? "Listening…" : "Talk");
        talk.setBackground(round(busy ? MINE : listen.on() ? AMBER : TEXT, 23));
        talk.setTextColor(busy ? TEXT : 0xFF111111);
    }

    private void openChat() {
        Intent i = new Intent(getContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 29) startAssistantActivity(i); else getContext().startActivity(i);
        hide();
    }

    /** A Send/Call (or Clear) card waiting for his tap, shown here as in the app. Main thread. */
    static void cardChanged() {
        SprocketSession s = current;
        if (s == null || s.cardBox == null) return;
        s.main.removeCallbacks(s.closer);
        s.cardBox.removeAllViews();
        Hands.Card c = Hands.card;
        if (c == null) return;
        LinearLayout panel = new LinearLayout(s.getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = s.round(PANEL, 16);
        g.setStroke(s.dp(2), AMBER);
        panel.setBackground(g);
        panel.setPadding(s.dp(16), s.dp(14), s.dp(16), s.dp(14));
        panel.addView(s.text(c.text, 16, TEXT));
        LinearLayout btns = new LinearLayout(s.getContext());
        Button yes = s.button(c.yes, AMBER, 0xFF111111);
        yes.setOnClickListener(v -> Hands.answer(c, true));
        btns.addView(yes, new LinearLayout.LayoutParams(-2, s.dp(42)));
        Button no = s.button("Cancel", MINE, TEXT);
        no.setOnClickListener(v -> Hands.answer(c, false));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, s.dp(42));
        np.setMargins(s.dp(10), 0, 0, 0);
        btns.addView(no, np);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, s.dp(12), 0, 0);
        panel.addView(btns, bp);
        if (c.listening) panel.addView(s.text("Listening: say \u201c" + c.yes.toLowerCase(java.util.Locale.US) + "\u201d or \u201ccancel\u201d", 13, MUTED));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.setMargins(0, s.dp(12), 0, 0);
        s.cardBox.addView(panel, pp);
    }

    private void writeTest() {
        try {
            JSONObject o = new JSONObject().put("heard", heardForTest).put("reply", reply.getText().toString())
                    .put("pieces", new JSONArray(new ArrayList<>(voiced))).put("rounds", new JSONArray(new ArrayList<>(rounds)));
            Files.write(new File(getContext().getExternalFilesDir(null), "cardtest.json").toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "card test not written: " + e);
        }
        heardForTest = "";
    }
}
