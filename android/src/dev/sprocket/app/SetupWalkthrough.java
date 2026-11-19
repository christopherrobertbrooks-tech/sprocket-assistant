package dev.sprocket.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The first-run walkthrough on screen: the welcome screen (pick a brain, paste a key), then the setup cards that explain each feature before Android asks (the words and steps are in Setup.java). Moved out of MainActivity (roadmap: tidier code).
 */
final class SetupWalkthrough {
    final MainActivity host;

    SetupWalkthrough(MainActivity host) { this.host = host; }

    void keyCard(boolean replacing) {
        host.list.removeAllViews();
        host.backSaves = null;   // only Settings and Personality set it again
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(host.round(MainActivity.PANEL, 16));
        c.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
        TextView h = host.text(replacing ? "Claude key" : "Sprocket needs a Claude key", 17, MainActivity.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        c.addView(host.text(MainActivity.CLAUDE_KEY_HOW, 14, MainActivity.MUTED));
        EditText k = new EditText(host);
        k.setHint("sk-ant-...");
        k.setTextColor(MainActivity.TEXT);
        k.setHintTextColor(MainActivity.FAINT);
        k.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(k);
        TextView note = host.text("", 13, MainActivity.ERR);
        c.addView(note);
        LinearLayout btns = new LinearLayout(host);
        Button save = host.chip("Save");
        save.setBackground(host.round(MainActivity.TEXT, 18));
        save.setTextColor(0xFF111111);
        save.setOnClickListener(v -> {
            String s = k.getText().toString().trim();
            if (!s.startsWith("sk-ant-")) { note.setText("That isn't a Claude key: they start with sk-ant-"); return; }
            host.prefs().key(s);
            host.render();
        });
        btns.addView(save, new LinearLayout.LayoutParams(-2, host.dp(40)));
        if (!host.prefs().key().isEmpty()) {
            Button remove = host.chip("Remove key");
            remove.setBackground(host.round(MainActivity.MINE, 18));
            remove.setTextColor(MainActivity.TEXT);
            remove.setOnClickListener(v -> { host.prefs().key(""); host.render(); });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, host.dp(40));
            rp.setMargins(host.dp(10), 0, 0, 0);
            btns.addView(remove, rp);
            Button back = host.chip("Back");
            back.setBackground(host.round(MainActivity.MINE, 18));
            back.setTextColor(MainActivity.TEXT);
            back.setOnClickListener(v -> host.render());
            btns.addView(back, new LinearLayout.LayoutParams(rp));
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, host.dp(10), 0, 0);
        c.addView(btns, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, host.dp(16), 0, 0);
        host.list.addView(c, lp);
    }

    /** A new install's first screen, and what shows whenever there's no brain: pick one, full screen (the owner,
     * 2026-11-11). Sprocket walks them through everything else in the chat (Setup). */
    void welcomeScreen() {
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(host.dp(8), host.dp(28), host.dp(8), host.dp(24));
        TextView h = host.text("Hi, I'm Sprocket", 26, MainActivity.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        TextView sub = host.text("First, pick my brain. I'll walk you through the rest in the chat.", 15, MainActivity.MUTED);
        sub.setPadding(0, host.dp(6), 0, 0);
        c.addView(sub);

        final boolean[] own = {Config.own};
        // which card: Claude, DeepSeek (a preset of "your own server": its Claude-style address, no go-between) or own
        final boolean[] ds = {Config.own && Config.deep}, oa = {Config.own && Config.oa};
        LinearLayout claude = option("Claude", "Anthropic's AI. Needs a Claude key; you pay Anthropic for what you use.");
        LinearLayout deep = option("DeepSeek", "DeepSeek's AI. Needs a DeepSeek key; you pay DeepSeek for what you use, usually "
                + "well under a cent a question. Tested with Sprocket's practice set: 68 of 68.");
        LinearLayout openai = option("OpenAI", "OpenAI's API (not a ChatGPT subscription). Needs an OpenAI API key; you pay "
                + "OpenAI for what you use. Tested with Sprocket's practice set: 64 of 68.");
        LinearLayout mine = option("Your own server", "A model you run yourself, through the included go-between or anything "
                + "that speaks Anthropic's Messages API. Tested with Qwen.");
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2);
        first.setMargins(0, host.dp(22), 0, 0);
        c.addView(claude, first);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, -2);
        second.setMargins(0, host.dp(10), 0, 0);
        c.addView(deep, second);
        LinearLayout.LayoutParams third = new LinearLayout.LayoutParams(-1, -2);
        third.setMargins(0, host.dp(10), 0, 0);
        c.addView(openai, third);
        LinearLayout.LayoutParams fourth = new LinearLayout.LayoutParams(-1, -2);
        fourth.setMargins(0, host.dp(10), 0, 0);
        c.addView(mine, fourth);

        LinearLayout claudeBox = new LinearLayout(host);
        claudeBox.setOrientation(LinearLayout.VERTICAL);
        EditText key = host.field(claudeBox, "Your Claude key", "", "sk-ant-...", true);
        claudeBox.addView(host.text(MainActivity.CLAUDE_KEY_HOW, 12, MainActivity.FAINT));
        TextView console = host.text("Open the Claude Console", 13, MainActivity.TEXT);
        console.setPadding(0, host.dp(8), 0, host.dp(4));
        console.setOnClickListener(v -> {
            try { host.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(MainActivity.CONSOLE_KEYS))); } catch (Exception ignored) { }
        });
        claudeBox.addView(console);
        c.addView(claudeBox);
        LinearLayout deepBox = new LinearLayout(host);
        deepBox.setOrientation(LinearLayout.VERTICAL);
        EditText deepKey = host.field(deepBox, "Your DeepSeek key", "", "sk-...", true);
        deepBox.addView(host.text(MainActivity.DEEPSEEK_KEY_HOW, 12, MainActivity.FAINT));
        TextView deepSite = host.text("Open DeepSeek's API keys page", 13, MainActivity.TEXT);
        deepSite.setPadding(0, host.dp(8), 0, host.dp(4));
        deepSite.setOnClickListener(v -> {
            try { host.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Claude.DEEPSEEK_KEYS))); } catch (Exception ignored) { }
        });
        deepBox.addView(deepSite);
        c.addView(deepBox);
        LinearLayout oaBox = new LinearLayout(host);
        oaBox.setOrientation(LinearLayout.VERTICAL);
        EditText oaKey = host.field(oaBox, "Your OpenAI API key", "", "sk-...", true);
        oaBox.addView(host.text(MainActivity.OPENAI_KEY_HOW, 12, MainActivity.FAINT));
        TextView oaSite = host.text("Open OpenAI's API keys page", 13, MainActivity.TEXT);
        oaSite.setPadding(0, host.dp(8), 0, host.dp(4));
        oaSite.setOnClickListener(v -> {
            try { host.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Claude.OPENAI_KEYS))); } catch (Exception ignored) { }
        });
        oaBox.addView(oaSite);
        c.addView(oaBox);
        LinearLayout ownBox = new LinearLayout(host);
        ownBox.setOrientation(LinearLayout.VERTICAL);
        EditText name = host.field(ownBox, "Its name (what I call it)", Config.ownName, "Qwen", false);
        EditText base = host.field(ownBox, "Its address", Config.ownBase, "https://my-server:8448", false);
        EditText model = host.field(ownBox, "Model name (blank if the server has one)", Config.ownModel, "qwen", false);
        EditText ownKey = host.field(ownBox, "Its access code (blank if it needs none)", "", "none", true);
        ownBox.addView(host.text(MainActivity.ADDRESS_HOW, 12, MainActivity.FAINT));
        c.addView(ownBox);
        TextView note = host.text("", 13, MainActivity.ERR);
        note.setPadding(0, host.dp(8), 0, 0);
        TextView anyway = host.text("Start anyway", 13, MainActivity.FAINT);   // after a failed test: their server may just be off right now
        anyway.setPadding(0, host.dp(12), 0, 0);
        anyway.setVisibility(View.GONE);
        final int[] asked = {0};                              // which check is current: switching brains drops the last one
        Runnable show = () -> {
            boolean onMine = own[0] && !ds[0] && !oa[0];
            pick(claude, !own[0]);
            pick(deep, own[0] && ds[0]);
            pick(openai, own[0] && oa[0]);
            pick(mine, onMine);
            claudeBox.setVisibility(own[0] ? View.GONE : View.VISIBLE);
            deepBox.setVisibility(own[0] && ds[0] ? View.VISIBLE : View.GONE);
            oaBox.setVisibility(own[0] && oa[0] ? View.VISIBLE : View.GONE);
            ownBox.setVisibility(onMine ? View.VISIBLE : View.GONE);
        };
        Runnable switched = () -> {   // the other brain's message would read as this one's (the owner's phone test, 2026-11-12)
            asked[0]++;
            note.setText("");
            anyway.setVisibility(View.GONE);
            show.run();
        };
        claude.setOnClickListener(v -> { if (own[0]) { own[0] = false; switched.run(); } });
        deep.setOnClickListener(v -> { if (!own[0] || !ds[0]) { own[0] = true; ds[0] = true; oa[0] = false; switched.run(); } });
        openai.setOnClickListener(v -> { if (!own[0] || !oa[0]) { own[0] = true; oa[0] = true; ds[0] = false; switched.run(); } });
        mine.setOnClickListener(v -> { if (!own[0] || ds[0] || oa[0]) { own[0] = true; ds[0] = false; oa[0] = false; switched.run(); } });
        show.run();

        c.addView(note);
        Button start = host.button("Start", true);
        start.setOnClickListener(v -> {
            final boolean useOwn = own[0], useDeep = own[0] && ds[0], useOa = own[0] && oa[0];
            final String k = key.getText().toString().trim();
            final String b = useDeep ? Claude.DEEPSEEK_BASE : useOa ? Claude.OPENAI_BASE : base.getText().toString().trim();
            final String n = useDeep ? "DeepSeek" : useOa ? "OpenAI" : name.getText().toString().trim();
            final String m = useDeep ? Claude.DEEPSEEK_MODEL : useOa ? Claude.OPENAI_MODEL : model.getText().toString().trim();
            final String typed = (useDeep ? deepKey : useOa ? oaKey : ownKey).getText().toString().trim();
            note.setTextColor(MainActivity.ERR);
            if (useDeep || useOa) {
                String who = useDeep ? "DeepSeek" : "OpenAI";
                if (typed.isEmpty()) { note.setText("Paste your " + who + " key first."); return; }
                if (typed.startsWith("sk-ant-")) { note.setText("That's a Claude key. Pick Claude above, or paste a " + who + " key."); return; }
            } else if (useOwn) {
                if (b.isEmpty()) { note.setText("Your own server needs its address."); return; }
                String bad = Config.addressProblem(b);
                if (bad != null) { note.setText(bad); return; }
            } else if (!k.startsWith("sk-ant-")) { note.setText("That isn't a Claude key: they start with sk-ant-"); return; }
            Runnable go = () -> {
                synchronized (Claude.class) {   // the whole switch at once: nothing reads half of it (Codex 049 #1)
                    if (useDeep) host.prefs().deepKey(typed);       // DeepSeek and OpenAI have their own slots: their server stays as it was
                    else if (useOa) host.prefs().openAiKey(typed);
                    else if (useOwn) {
                        Config.ownName = n;
                        Config.ownBase = b;
                        Config.ownModel = m;
                        if (!typed.isEmpty()) host.prefs().ownKey(typed);
                    } else host.prefs().key(k);
                    Config.own = useOwn;
                    Config.deep = useDeep;
                    Config.oa = useOa;
                    host.prefs().saveConfig();
                    Claude.useBrain(Config.own);
                }
                startSetup();
                host.render();
            };
            // test it first (Codex's review 2026-11-12): a wrong key or an unreachable server is said here, not mid-setup
            Claude.Brain test = useOwn ? new Claude.Brain(b.replaceAll("/+$", ""), typed.isEmpty() ? host.prefs().ownKey() : typed, true,
                    m.isEmpty() ? "own" : m) : new Claude.Brain(Claude.ANTHROPIC, k, false, "");
            note.setTextColor(MainActivity.MUTED);
            note.setText(useDeep ? "Checking the key with DeepSeek..." : useOa ? "Checking the key with OpenAI..." : useOwn ? "Checking your server... A model can take a minute to load the first time." : "Checking the key with Anthropic...");
            start.setEnabled(false);
            anyway.setVisibility(View.GONE);
            final int ticket = ++asked[0];
            new Thread(() -> {
                String problem = Claude.check(test);
                host.runOnUiThread(() -> {
                    start.setEnabled(true);
                    if (ticket != asked[0]) return;   // he switched brains while it checked: this answer is for the other one
                    if (problem == null) { go.run(); return; }
                    note.setTextColor(MainActivity.ERR);
                    note.setText(problem);
                    anyway.setOnClickListener(x -> go.run());
                    anyway.setVisibility(View.VISIBLE);
                });
            }).start();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, host.dp(44));
        sp.setMargins(0, host.dp(12), 0, 0);
        c.addView(start, sp);
        c.addView(anyway);
        host.list.addView(c, new LinearLayout.LayoutParams(-1, -2));
    }

    /** One of the two brains on the first screen: a panel to tap. */
    LinearLayout option(String title, String what) {
        LinearLayout o = new LinearLayout(host);
        o.setOrientation(LinearLayout.VERTICAL);
        o.setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(14));
        o.setClickable(true);
        TextView t = host.text(title, 17, MainActivity.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        o.addView(t);
        TextView w = host.text(what, 13, MainActivity.MUTED);
        w.setPadding(0, host.dp(4), 0, 0);
        o.addView(w);
        return o;
    }

    void pick(LinearLayout o, boolean picked) {
        GradientDrawable g = host.round(MainActivity.PANEL, 16);
        g.setStroke(host.dp(2), picked ? MainActivity.TEXT : MainActivity.PANEL);
        o.setBackground(g);
    }

    /** Only on a brand-new install (a brain removed and put back later doesn't start it again). */
    void startSetup() {
        android.content.SharedPreferences sp = host.getSharedPreferences("sprocket", Context.MODE_PRIVATE);
        if (sp.getBoolean("setup_offered", false) || !MainActivity.chat.turns.isEmpty()) return;
        sp.edit().putBoolean("setup_offered", true).putBoolean("asked_location", true).apply();
        Config.off.addAll(Setup.features());      // each feature waits for its own Yes (Codex 042 #3)
        host.prefs().saveConfig();
        FeedAlarm.cancel(host);
        setupGo(0);
        MainActivity.chat.add("assistant", Setup.HELLO + "\n\n" + Setup.ask(0), System.currentTimeMillis());
        MainActivity.saveChat();
    }

    void setupGo(int at) {
        MainActivity.setupAt = at;
        MainActivity.setupGithub = false;
        MainActivity.setupReturning = false;
        MainActivity.setupTried.clear();
        host.getSharedPreferences("sprocket", Context.MODE_PRIVATE).edit().putInt("setup_at", at).apply();
    }

    /** Under the question on show: Yes / Not now (or Skip while a username is awaited), and a way out of setup. */
    void addSetupCard() {
        LinearLayout panel = new LinearLayout(host);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = host.round(MainActivity.PANEL, 16);
        g.setStroke(host.dp(1), MainActivity.FAINT);
        panel.setBackground(g);
        panel.setPadding(host.dp(16), host.dp(12), host.dp(16), host.dp(12));
        Setup.Step st = Setup.STEPS[MainActivity.setupAt];
        TextView t = host.text(MainActivity.setupGithub ? "Type your username below, or skip this one." : st.name, 15, MainActivity.setupGithub ? MainActivity.MUTED : MainActivity.TEXT);
        if (!MainActivity.setupGithub) t.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(t);
        LinearLayout btns = new LinearLayout(host);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, host.dp(42));
        gap.setMargins(host.dp(10), 0, 0, 0);
        if (MainActivity.setupGithub) {
            Button skip = host.button("Skip", false);
            skip.setOnClickListener(v -> setupTapped(false));
            btns.addView(skip, new LinearLayout.LayoutParams(-2, host.dp(42)));
        } else {
            Button yes = host.button("Yes", true), no = host.button("Not now", false);
            yes.setOnClickListener(v -> setupTapped(true));
            no.setOnClickListener(v -> setupTapped(false));
            btns.addView(yes, new LinearLayout.LayoutParams(-2, host.dp(42)));
            btns.addView(no, gap);
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, host.dp(10), 0, 0);
        panel.addView(btns, bp);
        TextView out = host.text("Start with basic chat (set up the rest later)", 13, MainActivity.FAINT);
        out.setPadding(0, host.dp(10), 0, 0);
        out.setOnClickListener(v -> setupSkipRest());
        panel.addView(out);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, host.dp(10), 0, 0);
        host.list.addView(panel, lp);
    }

    void setupTapped(boolean yes) {
        if (MainActivity.busy || !Setup.running(MainActivity.setupAt)) return;
        MainActivity.chat.add("user", yes ? "Yes" : MainActivity.setupGithub ? "Skip" : "Not now", System.currentTimeMillis());
        if (MainActivity.setupGithub) { Config.off.add("github"); host.prefs().saveConfig(); setupNext(Setup.ack(Setup.STEPS[MainActivity.setupAt], false)); return; }
        setupAnswer(yes);
    }

    /** Typed during setup: true if it answered the question on show; false = a question, for the brain. */
    boolean setupTyped(String s) {
        Boolean yes = Setup.answer(s);
        if (MainActivity.setupGithub) {
            String name = yes == Boolean.FALSE ? null : Setup.githubName(s);
            if (yes != Boolean.FALSE && name == null) return false;
            MainActivity.chat.add("user", s, System.currentTimeMillis());
            if (name == null) { Config.off.add("github"); host.prefs().saveConfig(); setupNext(Setup.ack(Setup.STEPS[MainActivity.setupAt], false)); return true; }
            Config.github = name;
            Config.off.remove("github");
            host.prefs().saveConfig();
            setupNext("Got it: " + name + ".");
            return true;
        }
        if (yes == null) return false;
        MainActivity.chat.add("user", s, System.currentTimeMillis());
        setupAnswer(yes);
        return true;
    }

    void setupAnswer(boolean yes) {
        Setup.Step st = Setup.STEPS[MainActivity.setupAt];
        if (st.feature != null) {
            if (yes) Config.off.remove(st.feature); else Config.off.add(st.feature);
            host.prefs().saveConfig();
            if (st.feature.equals("brief")) { if (yes) FeedAlarm.schedule(host); else FeedAlarm.cancel(host); }
        }
        if (!yes) { setupNext(Setup.ack(st, false)); return; }
        host.render();                                 // their Yes on screen before Android's question
        if (st.way == Setup.Way.GITHUB_NAME) {
            MainActivity.setupGithub = true;
            MainActivity.chat.add("assistant", Setup.GITHUB_ASK, System.currentTimeMillis());
            MainActivity.saveChat();
            host.render();
            return;
        }
        setupContinue();
    }

    /** Asks Android for whatever the step on show still lacks, one thing at a time and each only once, then moves on. */
    void setupContinue() {
        Setup.Step st = Setup.STEPS[MainActivity.setupAt];
        boolean listener = st.way == Setup.Way.NOTIFICATION_ACCESS || st.way == Setup.Way.BOTH_SWITCHES;
        boolean screen = st.way == Setup.Way.SCREEN_CONTROL || st.way == Setup.Way.BOTH_SWITCHES;
        String[] missing = missingPerms(st.perms);
        if (missing.length > 0 && MainActivity.setupTried.add("perms")) { host.requestPermissions(missing, MainActivity.ASK_SETUP); return; }
        if (listener && !MessageListener.on(host) && MainActivity.setupTried.add("listener")) {
            MainActivity.setupReturning = true;
            MessageListener.openSwitch(host);
            return;
        }
        if (screen && !ScreenHands.on() && MainActivity.setupTried.add("screen")) {
            MainActivity.setupReturning = true;
            host.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (st.way == Setup.Way.ASSISTANT) {     // Android lets no app ask for this role: its own Default apps page
            if (!isAssistant() && MainActivity.setupTried.add("assistant")) {
                MainActivity.setupReturning = true;
                host.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
                return;
            }
            setupNext(isAssistant() ? Setup.ack(st, true) : Setup.ASSISTANT_NOT_YET);
            return;
        }
        boolean switches = (!listener || MessageListener.on(host)) && (!screen || ScreenHands.on());
        if (Place.allowed(host)) Place.refresh(host);
        setupNext(!switches ? Setup.STILL_OFF : missingPerms(st.perms).length > 0 ? Setup.DENIED : Setup.ack(st, true));
    }

    /** True when Sprocket is the phone's digital assistant (the side button), as far as this Android can tell. */
    boolean isAssistant() {
        if (Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager rm = host.getSystemService(android.app.role.RoleManager.class);
            return rm != null && rm.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT);
        }
        String a = android.provider.Settings.Secure.getString(host.getContentResolver(), "assistant");
        return a != null && a.startsWith(host.getPackageName() + "/");
    }

    String[] missingPerms(String[] perms) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : perms) {
            if (p.equals(Setup.POST) && Build.VERSION.SDK_INT < 33) continue;       // no such question before Android 13
            if (host.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) out.add(p);
        }
        return out.toArray(new String[0]);
    }

    void setupNext(String ack) {
        int n = Setup.next(MainActivity.setupAt);
        setupGo(n);
        MainActivity.chat.add("assistant", ack + "\n\n" + (Setup.running(n) ? Setup.ask(n) : Setup.DONE), System.currentTimeMillis());
        MainActivity.saveChat();
        host.render();
    }

    void setupSkipRest() {
        if (MainActivity.busy) return;
        setupGo(Setup.OFF);
        MainActivity.chat.add("assistant", "Okay, that's it for setup. What we didn't get to stays off: switch it on in ⋯ > Settings, "
                        + "and ask me about any of it.",
                System.currentTimeMillis());
        MainActivity.saveChat();
        host.render();
    }
}
