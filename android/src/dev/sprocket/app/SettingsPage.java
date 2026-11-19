package dev.sprocket.app;

import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Settings pages: brain and keys, features, voice, the personality editor and the Notion connection. Moved out of MainActivity (roadmap: tidier code).
 */
final class SettingsPage {
    final MainActivity host;

    SettingsPage(MainActivity host) { this.host = host; }

    /** ⋯ Settings (the open-source cleanup, 2026-11-11): the brain, voices, features, optional servers, personality. */
    void settingsCard() {
        host.list.removeAllViews();
        host.backSaves = null;   // only Settings and Personality set it again
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(host.round(MainActivity.PANEL, 16));
        c.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
        TextView h = host.text("Settings", 17, MainActivity.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);

        host.heading(c, "Brain");
        final boolean[] own = {Config.own}, ds = {Config.own && Config.deep}, oa = {Config.own && Config.oa};   // which answers first
        LinearLayout pick = new LinearLayout(host);
        Button claude = host.button("Claude", !own[0]), deep = host.button("DeepSeek", ds[0]), openai = host.button("OpenAI", oa[0]),
                mine = host.button("Your own server", own[0] && !ds[0] && !oa[0]);
        pick.addView(claude, new LinearLayout.LayoutParams(-2, host.dp(38)));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, host.dp(38));
        gap.setMargins(host.dp(10), 0, 0, 0);
        pick.addView(deep, gap);
        LinearLayout.LayoutParams gap2 = new LinearLayout.LayoutParams(-2, host.dp(38));
        gap2.setMargins(host.dp(10), 0, 0, 0);
        pick.addView(openai, gap2);
        c.addView(pick);
        LinearLayout pick2 = new LinearLayout(host);   // a second row: four don't fit across a phone
        pick2.setPadding(0, host.dp(8), 0, 0);
        pick2.addView(mine, new LinearLayout.LayoutParams(-2, host.dp(38)));
        c.addView(pick2);
        TextView backupHow = host.text("The one you pick answers. Set up the others too, and when it doesn't answer, Sprocket asks "
                + "on a card whether the next one may take that question: your own server first, then DeepSeek, OpenAI, Claude.", 12, MainActivity.FAINT);
        backupHow.setPadding(0, host.dp(6), 0, 0);
        c.addView(backupHow);
        TextView deepLabel = host.text("", 14, MainActivity.MUTED);
        deepLabel.setPadding(0, host.dp(10), 0, 0);
        c.addView(deepLabel);
        EditText deepKey = host.field(c, "Its key", "", host.prefs().deepKey().isEmpty() ? "sk-..." : "saved", true);
        c.addView(host.text(MainActivity.DEEPSEEK_KEY_HOW, 12, MainActivity.FAINT));
        TextView oaLabel = host.text("", 14, MainActivity.MUTED);
        oaLabel.setPadding(0, host.dp(10), 0, 0);
        c.addView(oaLabel);
        EditText oaKey = host.field(c, "Its key", "", host.prefs().openAiKey().isEmpty() ? "sk-..." : "saved", true);
        c.addView(host.text(MainActivity.OPENAI_KEY_HOW, 12, MainActivity.FAINT));
        TextView ownLabel = host.text("", 14, MainActivity.MUTED);
        ownLabel.setPadding(0, host.dp(10), 0, 0);
        c.addView(ownLabel);
        LinearLayout ownBox = new LinearLayout(host);
        ownBox.setOrientation(LinearLayout.VERTICAL);
        EditText name = host.field(ownBox, "Its name (what Sprocket calls it)", Config.ownName, "Qwen", false);
        EditText base = host.field(ownBox, "Its address (speaks Anthropic's Messages API, or the included go-between)", Config.ownBase,
                "https://my-server:8448", false);
        EditText model = host.field(ownBox, "Model name (blank if the server has one)", Config.ownModel, "qwen", false);
        EditText ownKey = host.field(ownBox, "Its access code (blank if it needs none)", "", host.prefs().ownKey().isEmpty() ? "none" : "saved", true);
        ownBox.addView(host.text(MainActivity.ADDRESS_HOW, 12, MainActivity.FAINT));
        c.addView(ownBox);
        Button claudeKey = host.button(host.prefs().key().isEmpty() ? "Add Claude key" : "Claude key", false);
        claudeKey.setOnClickListener(v -> host.keyCard(true));
        LinearLayout.LayoutParams top = new LinearLayout.LayoutParams(-2, host.dp(38));
        top.setMargins(0, host.dp(8), 0, 0);
        c.addView(claudeKey, top);
        final boolean[] hk = {Config.haiku};   // Claude's everyday model (11-16: Haiku passed the practice set, 62 of 68 judged)
        LinearLayout claudeModel = new LinearLayout(host);
        claudeModel.setPadding(0, host.dp(8), 0, 0);
        Button sonnetB = host.button("Sonnet", !hk[0]), haikuB = host.button("Haiku (cheapest)", hk[0]);
        claudeModel.addView(sonnetB, new LinearLayout.LayoutParams(-2, host.dp(38)));
        LinearLayout.LayoutParams gap3 = new LinearLayout.LayoutParams(-2, host.dp(38));
        gap3.setMargins(host.dp(10), 0, 0, 0);
        claudeModel.addView(haikuB, gap3);
        c.addView(claudeModel);
        c.addView(host.text("Claude's model, same key: Sonnet is smarter; Haiku costs about a twentieth as much. \"Think harder\" "
                + "always uses Opus.", 12, MainActivity.FAINT));
        Runnable showModel = () -> {
            for (Object[] b : new Object[][]{{sonnetB, !hk[0]}, {haikuB, hk[0]}}) {
                ((Button) b[0]).setBackground(host.round((Boolean) b[1] ? MainActivity.TEXT : MainActivity.MINE, 18));
                ((Button) b[0]).setTextColor((Boolean) b[1] ? 0xFF111111 : MainActivity.TEXT);
            }
        };
        sonnetB.setOnClickListener(v -> { hk[0] = false; showModel.run(); });
        haikuB.setOnClickListener(v -> { hk[0] = true; showModel.run(); });
        showModel.run();
        Runnable showBrain = () -> {                       // all stay on show: the others are the backups (11-13, 11-15)
            boolean onClaude = !own[0], onDeep = own[0] && ds[0], onOa = own[0] && oa[0], onMine = own[0] && !ds[0] && !oa[0];
            deepLabel.setText(onDeep ? "DeepSeek" : "DeepSeek, as a backup (optional)");
            oaLabel.setText(onOa ? "OpenAI" : "OpenAI, as a backup (optional)");
            ownLabel.setText(onMine ? "Your own server" : "Your own server, as a backup (optional)");
            boolean noKey = host.prefs().key().isEmpty();
            claudeKey.setText(!onClaude ? (noKey ? "Add a Claude key, as a backup (optional)" : "Claude key (backup)")
                    : (noKey ? "Add Claude key" : "Claude key"));
            for (Object[] b : new Object[][]{{claude, onClaude}, {deep, onDeep}, {openai, onOa}, {mine, onMine}}) {
                ((Button) b[0]).setBackground(host.round((Boolean) b[1] ? MainActivity.TEXT : MainActivity.MINE, 18));
                ((Button) b[0]).setTextColor((Boolean) b[1] ? 0xFF111111 : MainActivity.TEXT);
            }
        };
        claude.setOnClickListener(v -> { own[0] = false; showBrain.run(); });
        deep.setOnClickListener(v -> { own[0] = true; ds[0] = true; oa[0] = false; showBrain.run(); });
        openai.setOnClickListener(v -> { own[0] = true; oa[0] = true; ds[0] = false; showBrain.run(); });
        mine.setOnClickListener(v -> { own[0] = true; ds[0] = false; oa[0] = false; showBrain.run(); });
        showBrain.run();
        c.addView(host.text("Tested with Claude, DeepSeek, OpenAI, and Qwen through the included go-between. Other models may work if they speak "
                + "Anthropic's Messages API, or sit behind the go-between (any OpenAI-compatible server).", 12, MainActivity.FAINT));

        host.heading(c, "Voices");
        EditText out = host.field(c, "Voice server (blank = the phone's own voice)", Config.voiceOut, "http://my-server:8472", false);
        EditText in = host.field(c, "Speech-to-text server (blank = the phone's own recognizer)", Config.voiceIn, "http://my-server:8080", false);
        c.addView(host.text("The voice server takes POST /speak {text, mood} and returns audio. The speech-to-text server takes "
                + "an OpenAI-style /v1/audio/transcriptions upload (whisper.cpp's server does).", 12, MainActivity.FAINT));

        host.heading(c, "Features");
        java.util.Map<String, android.widget.CheckBox> boxes = new java.util.LinkedHashMap<>();
        for (String[] f : Config.FEATURES) {
            android.widget.CheckBox b = new android.widget.CheckBox(host);
            b.setText(f[1]);
            b.setTextColor(MainActivity.TEXT);
            b.setChecked(Config.on(f[0]));
            c.addView(b);
            TextView d = host.text(f[2], 12, MainActivity.FAINT);
            d.setPadding(host.dp(32), 0, 0, host.dp(4));
            c.addView(d);
            boxes.put(f[0], b);
        }

        EditText hours = host.field(c, "Call announcer hours (blank = always)", Config.callHours, "mon-thu 6-23, fri 6-24, sat 0-24, sun 0-23", false);

        host.heading(c, "Optional servers");
        EditText gh = host.field(c, "Your GitHub username", Config.github, "for the GitHub feature", false);
        EditText dash = host.field(c, "Home dashboard (/api/state)", Config.homeDash, "blank = off", false);
        EditText stats = host.field(c, "Server stats (/stats)", Config.homeStats, "blank = off", false);
        EditText hands = host.field(c, "Server job runner", Config.homeHands, "blank = off", false);
        EditText studio = host.field(c, "Studio", Config.studio, "blank = off", false);

        host.heading(c, "More");
        LinearLayout more = new LinearLayout(host);
        Button notion = host.button("Notion key (lists)", false), who = host.button("Personality", false);
        notion.setOnClickListener(v -> notionCard());
        who.setOnClickListener(v -> personaCard());
        more.addView(notion, new LinearLayout.LayoutParams(-2, host.dp(38)));
        more.addView(who, gap);
        c.addView(more);
        TextView credits = host.text("Weather data by Open-Meteo.com (CC BY 4.0); place names from GeoNames. Sprocket is not "
                + "affiliated with Anthropic or any model maker.", 12, MainActivity.FAINT);
        credits.setPadding(0, host.dp(14), 0, 0);
        c.addView(credits);

        TextView note = host.text("", 13, MainActivity.ERR);
        c.addView(note);
        LinearLayout btns = new LinearLayout(host);
        Button save = host.button("Save", true), back = host.button("Cancel", false);
        save.setOnClickListener(v -> {
            if (own[0] && !ds[0] && !oa[0] && base.getText().toString().trim().isEmpty()) { note.setText("Your own server needs its address."); return; }
            if (own[0] && oa[0] && oaKey.getText().toString().trim().isEmpty() && host.prefs().openAiKey().isEmpty()) { note.setText("OpenAI needs its key."); return; }
            if (own[0] && ds[0] && deepKey.getText().toString().trim().isEmpty() && host.prefs().deepKey().isEmpty()) { note.setText("DeepSeek needs its key."); return; }
            for (EditText f : new EditText[]{base, out, in, dash, stats, hands, studio}) {
                String bad = Config.addressProblem(f.getText().toString());
                if (bad != null) { note.setText(bad + " (" + f.getText().toString().trim() + ")"); f.requestFocus(); return; }
            }
            boolean home = false;
            for (EditText f : new EditText[]{base, out, in, dash, stats, hands, studio}) home |= Config.unencrypted(f.getText().toString());
            synchronized (Claude.class) {   // the whole switch at once: nothing reads half of it (Codex 049 #1)
                Config.own = own[0];
                Config.deep = own[0] && ds[0];
                Config.oa = own[0] && oa[0];
                Config.haiku = hk[0];
                if (!deepKey.getText().toString().trim().isEmpty()) host.prefs().deepKey(deepKey.getText().toString());
                if (!oaKey.getText().toString().trim().isEmpty()) host.prefs().openAiKey(oaKey.getText().toString());
                Config.ownName = name.getText().toString().trim();
                Config.ownBase = base.getText().toString().trim();
                Config.ownModel = model.getText().toString().trim();
                if (!ownKey.getText().toString().trim().isEmpty()) host.prefs().ownKey(ownKey.getText().toString());
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
                host.prefs().saveConfig();
                Claude.useBrain(Config.own);
            }
            if (Config.on("brief")) FeedAlarm.schedule(host); else FeedAlarm.cancel(host);
            VoiceOut.get(host).retryGateway();
            if (home) android.widget.Toast.makeText(host, "Saved. Home-network http isn't encrypted, and only works at home.", android.widget.Toast.LENGTH_LONG).show();
            host.render();
        });
        back.setOnClickListener(v -> host.render());
        host.backSaves = save::performClick;
        btns.addView(save, new LinearLayout.LayoutParams(-2, host.dp(40)));
        btns.addView(back, gap);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, host.dp(12), 0, 0);
        c.addView(btns, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, host.dp(16), 0, 0);
        host.list.addView(c, lp);
    }

    /** ⋯ Settings > Personality: his own wording of who Sprocket is, or back to the one the app comes with. */
    void personaCard() {
        host.list.removeAllViews();
        host.backSaves = null;   // only Settings and Personality set it again
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(host.round(MainActivity.PANEL, 16));
        c.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
        TextView h = host.text("Personality", 17, MainActivity.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        c.addView(host.text("This goes to the brain with every question: who Sprocket is and how it talks. Change anything.", 13, MainActivity.MUTED));
        EditText e = new EditText(host);
        e.setText(MainActivity.persona);
        e.setTextColor(MainActivity.TEXT);
        e.setTextSize(13);
        e.setMinLines(12);
        e.setGravity(Gravity.TOP);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        c.addView(e);
        TextView example = host.text("Want a character? Load the example: a loud robot who is sure he was built for war, and now "
                + "sets your timers. Then change anything and Save.", 13, MainActivity.MUTED);
        example.setPadding(0, host.dp(8), 0, 0);
        c.addView(example);
        Button load = host.button("Load the example", false);
        load.setOnClickListener(v -> e.setText(MainActivity.examplePersona()));
        LinearLayout.LayoutParams lp0 = new LinearLayout.LayoutParams(-2, host.dp(40));
        lp0.setMargins(0, host.dp(6), 0, 0);
        c.addView(load, lp0);
        LinearLayout btns = new LinearLayout(host);
        Button save = host.button("Save", true), reset = host.button("Use the original", false), back = host.button("Cancel", false);
        save.setOnClickListener(v -> {
            String t = e.getText().toString();
            if (t.trim().isEmpty()) return;
            host.prefs().persona(t.equals(MainActivity.defaultPersona()) ? "" : t);
            MainActivity.persona = t;
            settingsCard();
        });
        reset.setOnClickListener(v -> { host.prefs().persona(""); MainActivity.persona = MainActivity.defaultPersona(); personaCard(); });
        back.setOnClickListener(v -> settingsCard());
        host.backSaves = save::performClick;
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, host.dp(40));
        gap.setMargins(host.dp(10), 0, 0, 0);
        btns.addView(save, new LinearLayout.LayoutParams(-2, host.dp(40)));
        btns.addView(reset, gap);
        btns.addView(back, new LinearLayout.LayoutParams(gap));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, host.dp(12), 0, 0);
        c.addView(btns, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, host.dp(16), 0, 0);
        host.list.addView(c, lp);
    }

    /** ⋯ Notion key: his "Sprocket" Notion connection's key, so his lists live in Notion (it stays on this phone). */
    void notionCard() {
        host.list.removeAllViews();
        host.backSaves = null;   // only Settings and Personality set it again
        LinearLayout c = new LinearLayout(host);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(host.round(MainActivity.PANEL, 16));
        c.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
        TextView h = host.text("Notion key (lists)", 17, MainActivity.TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(h);
        c.addView(host.text("Your lists live in Notion once this is set: one table per list on the \u201cSprocket lists\u201d page. "
                + "Make the key at notion.so/developers/tokens: New token, name it Sprocket, tick Notion API, Create token. "
                + "Sprocket only ever touches that one page. The key stays on this phone.", 14, MainActivity.MUTED));
        EditText k = new EditText(host);
        k.setHint(host.prefs().notionKey().isEmpty() ? "ntn_..." : "saved (paste a new one to replace it)");
        k.setTextColor(MainActivity.TEXT);
        k.setHintTextColor(MainActivity.FAINT);
        k.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        c.addView(k);
        TextView note = host.text("", 13, MainActivity.MUTED);
        c.addView(note);
        Runnable check = () -> MainActivity.notionWorker.execute(() -> {
            String r = MainActivity.syncLists();
            int n;
            synchronized (MainActivity.lists) { n = MainActivity.lists.lists.size(); }
            String said = r == null ? "" : r.isEmpty() ? "Connected: " + n + (n == 1 ? " list" : " lists") + " in Notion now." : "Not yet: " + r + ".";
            MainActivity.onScreen(x -> { note.setText(said); note.setTextColor(r == null || r.isEmpty() ? MainActivity.MUTED : MainActivity.ERR); });
        });
        LinearLayout btns = new LinearLayout(host);
        Button save = host.chip("Save");
        save.setBackground(host.round(MainActivity.TEXT, 18));
        save.setTextColor(0xFF111111);
        save.setOnClickListener(v -> {
            String s2 = k.getText().toString().trim();
            if (!s2.startsWith("ntn_") && !s2.startsWith("secret_")) { note.setTextColor(MainActivity.ERR); note.setText("That isn't a Notion key: they start with ntn_"); return; }
            host.prefs().notionKey(s2);
            k.setText("");
            k.setHint("saved (paste a new one to replace it)");
            note.setTextColor(MainActivity.MUTED);
            note.setText("Checking Notion\u2026");
            check.run();
        });
        btns.addView(save, new LinearLayout.LayoutParams(-2, host.dp(40)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, host.dp(40));
        rp.setMargins(host.dp(10), 0, 0, 0);
        if (!host.prefs().notionKey().isEmpty()) {
            Button test = host.chip("Check");
            test.setBackground(host.round(MainActivity.MINE, 18));
            test.setTextColor(MainActivity.TEXT);
            test.setOnClickListener(v -> { note.setTextColor(MainActivity.MUTED); note.setText("Checking Notion\u2026"); check.run(); });
            btns.addView(test, rp);
            Button remove = host.chip("Remove key");
            remove.setBackground(host.round(MainActivity.MINE, 18));
            remove.setTextColor(MainActivity.TEXT);
            remove.setOnClickListener(v -> { host.prefs().notionKey(""); MainActivity.listsNote = null; notionCard(); });   // the lists stay on the phone
            btns.addView(remove, new LinearLayout.LayoutParams(rp));
        }
        Button back = host.chip("Back");
        back.setBackground(host.round(MainActivity.MINE, 18));
        back.setTextColor(MainActivity.TEXT);
        back.setOnClickListener(v -> host.render());
        btns.addView(back, new LinearLayout.LayoutParams(rp));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMargins(0, host.dp(10), 0, 0);
        c.addView(btns, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, host.dp(16), 0, 0);
        host.list.addView(c, lp);
    }
}
