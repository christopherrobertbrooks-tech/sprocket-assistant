package dev.sprocket.app;

import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;

/**
 * The adb-only test door (SelfTestActivity hands the intent over): practice runs, stand-in servers, test cards and seeded lists. Never reachable from another app. Moved out of MainActivity (roadmap: tidier code).
 */
final class TestHooks {
    final MainActivity host;

    TestHooks(MainActivity host) { this.host = host; }

    void selfTest(Intent test) {
        if (test == null) return;
        String cards = test.getStringExtra("st_test_cards");   // Sprocket Test only: "no" = Send/Call cards cancel themselves
        if (cards != null && host.getPackageName().endsWith(".test")) { Hands.testCards = cards; return; }
        String call = test.getStringExtra("st_fake_call");   // Sprocket Test only: a pretend ringing call, "Name|number"
        if (call != null && host.getPackageName().endsWith(".test")) {
            String heard = test.getStringExtra("st_call_hear");   // what the listens "hear" in turn ("~4000" = silence); else the mic
            Listen.script = heard == null ? null : new java.util.ArrayDeque<>(java.util.Arrays.asList(heard.split("\\|")));
            CallAnnouncer.testCalls = true;
            CallAnnouncer.pretend(host, call);
            return;
        }
        if (test.getBooleanExtra("st_clear_lists", false) && host.getPackageName().endsWith(".test")) {   // test leftovers out
            synchronized (MainActivity.lists) { MainActivity.lists = new Lists(); MainActivity.saveLists(); }
            host.render();
            android.util.Log.i("PA-REPLY", "LISTS cleared");
            return;
        }
        if (test.getBooleanExtra("st_brief_now", false) && host.getPackageName().endsWith(".test")) {   // a real brief now, his brain
            FeedService.start(host);
            return;
        }
        String qk = test.getStringExtra("st_qwen_key");   // stage 2: Qwen's access code, only ever over adb
        if (qk != null) { synchronized (Claude.class) { host.prefs().ownKey(qk); Claude.choose(true, false, false); host.prefs().saveConfig(); } Claude.useBrain(true); host.render(); return; }
        String cfg = test.getStringExtra("st_config");   // his settings (Config JSON, plus own_key), only ever over adb
        if (cfg != null) {
            synchronized (Claude.class) {   // the whole switch at once (Codex 049 #1)
                Config.fromJson(cfg);
                try {
                    JSONObject o = new JSONObject(cfg);
                    if (!o.optString("own_key", "").isEmpty()) host.prefs().ownKey(o.optString("own_key"));
                    if (!o.optString("deepseek_key", "").isEmpty()) host.prefs().deepKey(o.optString("deepseek_key"));
                    if (!o.optString("openai_key", "").isEmpty()) host.prefs().openAiKey(o.optString("openai_key"));
                    if (o.has("persona")) { host.prefs().persona(o.optString("persona")); MainActivity.persona = o.optString("persona").trim().isEmpty() ? MainActivity.defaultPersona() : o.optString("persona"); }
                } catch (Exception ignored) { }
                host.prefs().saveConfig();
                Claude.useBrain(Config.own);
            }
            if (host.ready()) host.startSetup();           // a coding agent set the brain up (AGENTS.md): the walkthrough still runs
            host.render();
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
                        try { out = Phone.run(host, s.getString("tool"), s.optJSONObject("input") == null ? new org.json.JSONObject() : s.getJSONObject("input")); }
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
        String bg = test.getStringExtra("st_bg");   // stage 3 over adb: "pair" (waits for the pairing window) or "start"
        if (bg != null) {
            new Thread(() -> {
                String r;
                try { r = bg.equals("pair") ? BgSetup.pair(MainActivity.app, 180_000) : BgSetup.startHelper(MainActivity.app); }
                catch (Exception e) { r = "error: " + e; }
                android.util.Log.i("PA-REPLY", "BG " + bg + " -> " + r);
            }, "sprocket-bg-setup").start();
            return;
        }
        String say = test.getStringExtra("st_say"), base = test.getStringExtra("st_base"), k = test.getStringExtra("st_key");
        if (test.getBooleanExtra("st_feed_now", false) && base != null && k != null
                && k.startsWith("sk-standin")) {               // the morning brief, now, against the stand-in
            Claude.base = base;
            MainActivity.testKey = k;
            FeedService.start(host);
            return;
        }
        if (say == null) return;
        // A stand-in server only ever gets a made-up key: the saved real key is never sent anywhere but Anthropic.
        if (base != null) {
            if (k == null || !k.startsWith("sk-standin")) return;
            Claude.base = base;
            Studio.base = base;
            MainActivity.testKey = k;
            Hands.autoAnswer = test.getStringExtra("st_confirm");   // "yes" / "no": the card answers itself after 5 s
            Hands.testHeard = test.getStringExtra("st_card_heard");  // or the card "hears" these words (no mic here)
            MainActivity.testFaults = PhoneTools.faults(test.getStringExtra("st_fault"));   // practice only: "send_text:refused;add_event:no_confirm"
        }
        if (test.getBooleanExtra("st_new", false)) { MainActivity.chat = new Chat(); MainActivity.saveChat(); }
        if (test.getBooleanExtra("st_fresh", false) && MainActivity.standIn()) {   // practice only: nothing carried over from the last test
            synchronized (MainActivity.lists) { MainActivity.lists = new Lists(); MainActivity.saveLists(); }
            MainActivity.memory = new Memory(); MainActivity.saveMemory();
            MainActivity.appNotes = new AppNotes(); MainActivity.saveAppNotes();
            File cardLog = new File(host.getExternalFilesDir(null), "cards.txt");
            if (cardLog.exists() && !cardLog.delete()) android.util.Log.w("Sprocket", "practice: cards.txt stayed");
        }
        String pic = test.getStringExtra("st_photo");          // a test picture in the app's own external folder
        if (pic != null) {
            try { MainActivity.attached = Photo.save(MainActivity.app, Uri.fromFile(new File(host.getExternalFilesDir(null), pic))); }
            catch (Exception e) { android.util.Log.w("Sprocket", "test photo: " + e); }
        }
        MainActivity.harder = test.getBooleanExtra("st_harder", false);
        host.render();
        MainActivity.SelfTest st = new MainActivity.SelfTest();
        VoiceOut.get(host).watch = (text, mood, how, ms) -> {
            try { st.voiced.add(new JSONObject().put("text", text).put("mood", mood).put("how", how).put("fetch_ms", ms)); }
            catch (Exception ignored) { }
        };
        MainActivity.spokenTurn = test.getBooleanExtra("st_spoken", false);   // as if he had said it (the mic can't be tested here)
        String hear = test.getStringExtra("st_hear");           // what the listens after the answer "hear" (no mic here)
        Listen.script = hear == null || hear.isEmpty() || base == null ? null : new java.util.ArrayDeque<>(java.util.Arrays.asList(hear.split("\\|")));
        if (test.getStringExtra("st_card_say") != null) {     // the card's next "heard" words (stand-in only)
            SprocketSession.testHeard = test.getStringExtra("st_card_say");
            return;
        }
        host.ask(say, st);
    }
}
