package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Claude on the desktop JDK: the request, the stream reader, the cost sum, the error wording. No network. */
public class ClaudeTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static String ev(String json) { return "data: " + json; }

    static String types(JSONArray blocks) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < blocks.length(); i++) b.append(i > 0 ? " " : "").append(blocks.getJSONObject(i).getString("type"));
        return b.toString();
    }

    static final Claude.Where HERE = new Claude.Where("America/Chicago", "US", "Des Moines", "Iowa");

    /** The first n messages as the cache sees them: the cache marks taken out, and his newest message's time note
     * (sent after the mark) dropped. */
    static String cachedPart(JSONArray msgs, int n) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < n; i++) {
            JSONObject m = new JSONObject(msgs.getJSONObject(i).toString());
            JSONArray c = m.optJSONArray("content");
            if (c != null) {
                JSONArray keep = new JSONArray();
                for (int k = 0; k < c.length(); k++) {
                    JSONObject b = c.getJSONObject(k);
                    if (b.has("cache_control")) { b.remove("cache_control"); keep.put(b); break; }   // the mark ends the cached part
                    keep.put(b);
                }
                m.put("content", keep);
            }
            out.put(m);
        }
        return out.toString();
    }

    public static void main(String[] a) throws Exception {
        Chat c = new Chat();
        c.add("user", "what's the weather", 1);
        c.add("assistant", "SUNNY. Suspiciously sunny.", 2);
        c.add("user", "and tomorrow?", 3);
        JSONObject r = Claude.request("PERSONA", null, null, "Friday, October 17, 2026, 3:10 AM", HERE, c.window(24), false);
        eq("normal = Sonnet", r.getString("model"), Claude.SONNET);
        eq("streamed", r.optBoolean("stream", false), true);
        eq("room for thinking + a short answer", r.getInt("max_tokens"), 2000);
        eq("fast: low effort", r.getJSONObject("output_config").getString("effort"), "low");
        JSONObject sys0 = r.getJSONArray("system").getJSONObject(0);
        eq("one system block, never changing", r.getJSONArray("system").length(), 1);
        eq("persona first", sys0.getString("text"), "PERSONA");
        eq("persona cached", sys0.getJSONObject("cache_control").getString("type"), "ephemeral");
        JSONArray newest = r.getJSONArray("messages").getJSONObject(2).getJSONArray("content");
        eq("history in order", r.getJSONArray("messages").length() + " " + newest.getJSONObject(0).getString("text"), "3 and tomorrow?");
        eq("roles alternate", r.getJSONArray("messages").getJSONObject(1).getString("role"), "assistant");
        eq("history cached up to his newest words", newest.getJSONObject(0).getJSONObject("cache_control").getString("type"), "ephemeral");
        eq("the time comes after the cache mark, uncached", newest.getJSONObject(1).getString("text").contains("Friday, October 17, 2026, 3:10 AM")
                + " " + newest.getJSONObject(1).has("cache_control"), "true false");
        eq("one cache mark in the history", r.getJSONArray("messages").toString().split("cache_control", -1).length - 1, 1);
        // the next question: everything before it must be sent exactly as the cache stored it
        c.add("assistant", "RAIN. From the SKY.", 4);
        c.add("user", "ugh", 5);
        JSONObject r2 = Claude.request("PERSONA", null, null, "Friday, October 17, 2026, 3:12 AM", HERE, c.window(24), false);
        eq("same system next time", r2.getJSONArray("system").toString(), r.getJSONArray("system").toString());
        eq("same history next time", cachedPart(r2.getJSONArray("messages"), 3), cachedPart(r.getJSONArray("messages"), 3));
        JSONObject tool = r.getJSONArray("tools").getJSONObject(0);
        eq("web search", tool.getString("type") + " " + tool.getInt("max_uses"), "web_search_20250305 3");
        eq("rough place only", tool.getJSONObject("user_location").getString("timezone") + " " + tool.getJSONObject("user_location").getString("country"), "America/Chicago US");
        eq("searches know his town", tool.getJSONObject("user_location").getString("city") + ", " + tool.getJSONObject("user_location").getString("region"), "Des Moines, Iowa");
        eq("so does Claude, after the cache mark", newest.getJSONObject(1).getString("text").contains("near Des Moines, Iowa"), true);
        JSONObject wx = r.getJSONArray("tools").getJSONObject(1);
        eq("a weather tool", wx.getString("name") + " " + wx.getJSONObject("input_schema").getString("type"), "weather object");
        eq("weather: a town is optional", wx.getJSONObject("input_schema").getJSONObject("properties").has("place")
                + " " + wx.getJSONObject("input_schema").has("required"), "true false");
        JSONObject h = Claude.request("P", null, null, "now", new Claude.Where("", "", "", ""), c.window(24), true);
        eq("think harder = Opus", h.getString("model"), Claude.OPUS);
        eq("think harder: room to think", h.getInt("max_tokens"), 8000);
        eq("think harder: high effort", h.getJSONObject("output_config").getString("effort"), "high");
        JSONArray hn = h.getJSONArray("messages");
        eq("think harder: told", hn.getJSONObject(hn.length() - 1).getJSONArray("content").getJSONObject(1).getString("text").contains("THINK HARDER"), true);
        eq("think harder: one-off Opus writes no cache", h.toString().contains("cache_control"), false);
        eq("no place without facts", h.getJSONArray("tools").getJSONObject(0).has("user_location"), false);
        eq("no \"near\" without a town", hn.getJSONObject(hn.length() - 1).getJSONArray("content").getJSONObject(1).getString("text").contains("near"), false);

        // a tool round: he thinks, says a remark, asks for the weather; the app answers the tool and asks again
        List<String> seen = new ArrayList<>();
        Claude.Stream ts = new Claude.Stream(Claude.SONNET, new Claude.Listener() {
            public void text(String soFar) { seen.add(soFar); }
            public void sentence(String x, String mood) { }
        });
        ts.line(ev("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":30,\"cache_read_input_tokens\":1000}}}"));
        ts.line(ev("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"));
        ts.line(ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Weather. Use the tool.\"}}"));
        ts.line(ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"SIG\"}}"));
        ts.line(ev("{\"type\":\"content_block_stop\",\"index\":0}"));
        ts.line(ev("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        ts.line(ev("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Checking the SKY. \"}}"));
        ts.line(ev("{\"type\":\"content_block_stop\",\"index\":1}"));
        ts.line(ev("{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"weather\",\"input\":{}}}"));
        ts.line(ev("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"pla\"}}"));
        ts.line(ev("{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"ce\\\": \\\"Paris\\\"}\"}}"));
        ts.line(ev("{\"type\":\"content_block_stop\",\"index\":2}"));
        ts.line(ev("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":25}}"));
        Claude.Reply tr = ts.finish();
        eq("tool round: stopped for a tool", tr.stop, "tool_use");
        eq("tool round: the call, its input from the pieces", tr.calls.size() + " " + tr.calls.get(0).getString("id") + " "
                + tr.calls.get(0).getJSONObject("input").getString("place"), "1 toolu_1 Paris");
        eq("tool round: everything he said, in order", types(tr.content), "thinking text tool_use");
        eq("tool round: thinking kept whole, with its signature", tr.content.getJSONObject(0).getString("thinking") + " "
                + tr.content.getJSONObject(0).getString("signature"), "Weather. Use the tool. SIG");
        eq("tool round: no trailing space sent back", tr.content.getJSONObject(1).getString("text"), "Checking the SKY.");
        eq("tool round: the remark cleared from the screen", seen.get(seen.size() - 1), "");
        JSONArray results = new JSONArray().put(new JSONObject().put("type", "tool_result").put("tool_use_id", "toolu_1").put("content", "61F"));
        JSONObject again = Claude.next(r2, tr, results);
        JSONArray am = again.getJSONArray("messages");
        eq("next: two more messages", am.length() - r2.getJSONArray("messages").length(), 2);
        eq("next: his words, then the tool's answer", am.getJSONObject(am.length() - 2).getString("role") + " "
                + types(am.getJSONObject(am.length() - 2).getJSONArray("content")) + " / " + am.getJSONObject(am.length() - 1).getString("role") + " "
                + am.getJSONObject(am.length() - 1).getJSONArray("content").getJSONObject(0).getString("tool_use_id"), "assistant thinking text tool_use / user toolu_1");
        eq("next: the first request untouched", r2.getJSONArray("messages").length(), 5);
        JSONObject resumed = Claude.next(r2, tr, null);
        eq("a paused turn goes back as it was, to be continued", resumed.getJSONArray("messages").length() + " "
                + resumed.getJSONArray("messages").getJSONObject(5).getString("role"), "6 assistant");
        Claude.Reply sum = new Claude.Reply();
        sum.model = Claude.SONNET;
        sum.input = 10; sum.cacheRead = 900; sum.searches = 1; sum.sources.add("a.com");
        tr.add(sum);
        eq("a turn's rounds add up", tr.input + " " + tr.cacheRead + " " + tr.output + " " + tr.searches + " " + tr.sources, "40 1900 25 1 [a.com]");

        // phase 3b: what he's remembered goes in the prompt, cached after the persona; photos go with his message
        JSONObject mem = Claude.request("PERSONA", null, "What you remember about the boss (...):\n- Note one.", "now", HERE, c.window(24), false);
        JSONArray ms = mem.getJSONArray("system");
        eq("memory: a second system block, cached", ms.length() + " " + ms.getJSONObject(1).getString("text").startsWith("What you remember")
                + " " + ms.getJSONObject(1).has("cache_control") + " " + ms.getJSONObject(0).has("cache_control"), "2 true true true");
        eq("memory: three cache marks in all (the limit is four)", mem.toString().split("cache_control", -1).length - 1, 3);
        eq("memory: none for a one-off Opus question either", Claude.request("P", null, "notes", "now", HERE, c.window(24), true).toString().contains("cache_control"), false);
        // his About-you page: its own block between the persona and the notes, with its own mark (four in all, the most)
        JSONObject ab = Claude.request("PERSONA", "About the boss (...):\n\nPAGE", "What you remember about the boss (...):\n- Note one.",
                "now", HERE, c.window(24), false);
        JSONArray as = ab.getJSONArray("system");
        eq("about: persona, page, notes, each cached", as.length() + " " + as.getJSONObject(0).getString("text") + " "
                + as.getJSONObject(1).getString("text").endsWith("PAGE") + " " + as.getJSONObject(2).getString("text").startsWith("What you remember")
                + " " + as.getJSONObject(0).has("cache_control") + as.getJSONObject(1).has("cache_control") + as.getJSONObject(2).has("cache_control"),
                "3 PERSONA true true truetruetrue");
        eq("about: four cache marks, the API's limit", ab.toString().split("cache_control", -1).length - 1, 4);
        eq("about: no notes yet, still its own block", Claude.request("P", "About the boss (...):\n\nPAGE", null, "now", HERE, c.window(24), false)
                .getJSONArray("system").length(), 2);
        eq("about: no marks for a one-off Opus question", Claude.request("P", "page", "notes", "now", HERE, c.window(24), true).toString().contains("cache_control"), false);
        JSONArray toolNames = mem.getJSONArray("tools");
        eq("memory tools last", toolNames.getJSONObject(toolNames.length() - 2).getString("name") + " "
                + toolNames.getJSONObject(toolNames.length() - 1).getString("name"), "remember forget");
        Chat pc = new Chat();
        Chat.Turn shot = pc.add("user", "what's this?", 1);
        shot.photos.add("p1.jpg");
        Claude.photos = name -> "B64:" + name;
        JSONArray pm = Claude.request("P", null, null, "now", HERE, pc.window(24), false).getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        Claude.photos = null;
        JSONObject img = pm.getJSONObject(0);
        eq("a photo: an image block first", img.getString("type") + " " + img.getJSONObject("source").getString("type") + " "
                + img.getJSONObject("source").getString("media_type") + " " + img.getJSONObject("source").getString("data"), "image base64 image/jpeg B64:p1.jpg");
        eq("then his words (cache-marked), then the note", types(pm) + " " + pm.getJSONObject(1).has("cache_control"), "image text text true");

        // one question, three rounds: two tools at once (a text and a reminder), the answers go back together, then the reply
        List<String> ran = new ArrayList<>();
        List<JSONObject> sent = new ArrayList<>();
        Claude.Transport saved = Claude.transport;
        Claude.transport = (key, body, l2, cancel) -> {
            sent.add(body);
            Claude.Reply x = new Claude.Reply();
            x.model = Claude.SONNET;
            x.input = 10;
            if (sent.size() == 1) {
                x.stop = "tool_use";
                for (String[] t : new String[][]{{"toolu_a", "send_text"}, {"toolu_b", "set_reminder"}}) {
                    JSONObject b = new JSONObject().put("type", "tool_use").put("id", t[0]).put("name", t[1]).put("input", new JSONObject().put("n", t[0]));
                    x.content.put(b);
                    x.calls.add(b);
                }
            } else {
                x.stop = "end_turn";
                x.text = "Done, boss.";
                x.content.put(new JSONObject().put("type", "text").put("text", "Done, boss."));
            }
            return x;
        };
        Claude.Reply both = Claude.send(new Claude.Brain(Claude.base, "k", false, ""), r2, null, null, (name, in) -> {
            ran.add(name + ":" + in.getString("n"));
            if (name.equals("set_reminder")) throw new Exception("no clock app");
            return "sent";
        });
        Claude.transport = saved;
        eq("two tools at once: both run, in order", ran, "[send_text:toolu_a, set_reminder:toolu_b]");
        JSONArray back = sent.get(1).getJSONArray("messages");
        JSONArray answers = back.getJSONObject(back.length() - 1).getJSONArray("content");
        eq("both answers in one message", answers.length() + " " + answers.getJSONObject(0).getString("tool_use_id") + " "
                + answers.getJSONObject(1).getString("tool_use_id"), "2 toolu_a toolu_b");
        eq("a failed tool says so", answers.getJSONObject(1).optBoolean("is_error", false) + " " + answers.getJSONObject(1).getString("content"),
                "true That didn't work: no clock app");
        eq("the turn's reply and its cost add up", both.text + " " + both.input + " " + sent.size(), "Done, boss. 20 2");

        // a tool turn whose last round has no words (Claude said it before acting): the earlier words are the reply,
        // not "no answer" (the owner 2026-10-17: the lost reply joined his next message to the texting one, which re-sent)
        Claude.Transport keep = Claude.transport;
        int[] calls = {0};
        Claude.transport = (key, body, l2, cancel) -> {
            Claude.Reply x = new Claude.Reply();
            x.model = Claude.SONNET;
            if (++calls[0] == 1) {
                x.stop = "tool_use";
                x.text = "On it, boss.";
                JSONObject b = new JSONObject().put("type", "tool_use").put("id", "toolu_t").put("name", "send_text").put("input", new JSONObject());
                x.content.put(new JSONObject().put("type", "text").put("text", "On it, boss.")).put(b);
                x.calls.add(b);
            } else {
                x.stop = "end_turn";
            }
            return x;
        };
        String before;
        try { before = Claude.send(new Claude.Brain(Claude.base, "k", false, ""), r2, null, null, (name, in) -> "Sent.").text; }
        catch (Exception e) { before = e.getMessage(); }
        eq("words before the tool, none after: those words", before, "On it, boss.");
        calls[0] = 0;
        Claude.transport = (key, body, l2, cancel) -> {
            Claude.Reply x = new Claude.Reply();
            x.model = Claude.SONNET;
            if (++calls[0] == 1) {
                x.stop = "tool_use";
                JSONObject b = new JSONObject().put("type", "tool_use").put("id", "toolu_t").put("name", "flashlight").put("input", new JSONObject());
                x.content.put(b);
                x.calls.add(b);
            } else {
                x.stop = "end_turn";
            }
            return x;
        };
        String quiet;
        try { quiet = "[" + Claude.send(new Claude.Brain(Claude.base, "k", false, ""), r2, null, null, (name, in) -> "Flashlight on.").text + "]"; }
        catch (Exception e) { quiet = e.getMessage(); }
        eq("no words at all, but it acted: not an error", quiet, "[]");
        Claude.transport = keep;

        // the brain is switched while a tool runs (Codex 040 #6): every round still goes to the brain and key it began with
        List<String> went = new ArrayList<>();
        Claude.transport = (brain, body, l2, cancel) -> {
            went.add(brain.base + " " + brain.key + " " + body.getString("model"));
            Claude.Reply x = new Claude.Reply();
            if (went.size() == 1) {
                x.stop = "tool_use";
                JSONObject call = new JSONObject().put("type", "tool_use").put("id", "toolu_s").put("name", "phone_control").put("input", new JSONObject());
                x.content.put(call);
                x.calls.add(call);
            } else {
                x.stop = "end_turn";
                x.text = "On.";
            }
            return x;
        };
        Config.ownBase = "https://box.example:8448";
        Config.ownModel = "qwen";
        Claude.useBrain(true);
        Claude.Brain started = Claude.Brain.now(q -> q ? "QWEN-KEY" : "CLAUDE-KEY");
        try {
            Claude.send(started, Claude.request("PERSONA", null, null, "Friday, October 17, 2026, 3:10 AM", HERE, c.window(24), false),
                    null, null, (name, in) -> { Claude.useBrain(false); return "On."; });
        } catch (Exception e) { went.add("ERROR " + e.getMessage()); }
        eq("switching brains mid-answer: both rounds stay on his server with its key", went,
                "[https://box.example:8448 QWEN-KEY qwen, https://box.example:8448 QWEN-KEY qwen]");
        eq("a question started on Claude takes Claude's key", Claude.Brain.now(q -> q ? "QWEN-KEY" : "CLAUDE-KEY").key, "CLAUDE-KEY");
        Claude.Brain mixed = new Claude.Brain(Claude.ANTHROPIC, "k", false, "");
        JSONObject wrong = new JSONObject().put("model", "qwen").put("messages", new JSONArray());
        went.clear();
        try { Claude.send(mixed, wrong, null, null, null); } catch (Exception e) { }
        eq("a request for his model sent to Claude goes as Sonnet", went.get(0), Claude.ANTHROPIC + " k " + Claude.SONNET);
        Config.reset();
        Claude.useBrain(false);
        Claude.transport = keep;

        // busy or overloaded (429/529): wait and try again, up to twice, before giving up (Magpie's pick 1, 2026-11-12)
        eq("wait: what the server asks, else 2 s then 5 s, never over 20 s", Claude.retryWait("3", 1) + " " + Claude.retryWait(null, 1)
                + " " + Claude.retryWait(null, 2) + " " + Claude.retryWait("120", 1) + " " + Claude.retryWait("soon", 2), "3000 2000 5000 20000 5000");
        List<Long> naps = new ArrayList<>();
        Claude.nap = naps::add;
        int[] tries = {0};
        Claude.transport = (key, body, l2, cancel) -> {
            if (++tries[0] < 3) throw new Claude.Busy(Claude.why(529, ""), tries[0] == 1 ? "7" : null, false);
            Claude.Reply x = new Claude.Reply();
            x.model = Claude.SONNET;
            x.stop = "end_turn";
            x.text = "Here you go.";
            return x;
        };
        Claude.Brain cb = new Claude.Brain(Claude.base, "k", false, "");
        String got;
        try { got = Claude.send(cb, r2, null, null, null).text; } catch (Exception e) { got = "FAILED: " + e.getMessage(); }
        eq("busy twice, then an answer", got + " | tries " + tries[0] + " | waited " + naps, "Here you go. | tries 3 | waited [7000, 5000]");
        tries[0] = 0;
        naps.clear();
        Claude.transport = (key, body, l2, cancel) -> { tries[0]++; throw new Claude.Busy(Claude.why(429, ""), null, false); };
        try { got = Claude.send(cb, r2, null, null, null).text; } catch (Exception e) { got = e.getMessage(); }
        eq("still busy after three tries: says so", got + " | tries " + tries[0], "Claude is busy right now; try again in a moment | tries 3");
        tries[0] = 0;
        Claude.transport = (key, body, l2, cancel) -> { tries[0]++; throw new Claude.Busy("Claude is busy right now", null, true); };
        try { got = Claude.send(cb, r2, null, null, null).text; } catch (Exception e) { got = e.getMessage(); }
        eq("busy after words were already said: no retry (they'd be said twice)", got + " | tries " + tries[0], "Claude is busy right now | tries 1");
        Claude.nap = Claude::sleep;
        Claude.transport = keep;

        // his screen (the owner 2026-10-17: screenshots): a tool answer can carry a picture, which goes to Claude as an image
        Object pic = Claude.toolContent("[[picture:screen.jpg]]His screen.", n2 -> "B64:" + n2);
        JSONArray sc = pic instanceof JSONArray ? (JSONArray) pic : new JSONArray();
        eq("a picture answer: the image, then the words", sc.length() + " " + sc.getJSONObject(0).getString("type") + " "
                + sc.getJSONObject(0).getJSONObject("source").getString("data") + " " + sc.getJSONObject(1).getString("text"),
                "2 image B64:screen.jpg His screen.");
        eq("a plain answer stays words", Claude.toolContent("Paused.", n2 -> "x"), "Paused.");
        eq("a picture that's gone: said so", Claude.toolContent("[[picture:gone.jpg]]His screen.", n2 -> null), "That picture is gone.");
        Config.github = "someone";                       // the optional servers set up, so their tools are offered
        Config.homeStats = "http://box/stats";
        Config.homeHands = "http://box";
        Config.studio = "http://box";
        JSONArray toolNames2 = Claude.tools(new JSONObject().put("type", "web_search_20250305").put("name", "web_search"));
        Config.reset();
        StringBuilder tn = new StringBuilder();
        for (int i = 0; i < toolNames2.length(); i++) tn.append(toolNames2.getJSONObject(i).getString("name")).append(' ');
        eq("his screen, texts and notifications are tools", tn.toString().contains("check_texts read_texts check_notifications look_at_screen "), true);
        eq("GitHub is back (stage 6), after the machines' tools", tn.toString().contains("machine_control github_threads github_repos studio_make studio_jobs goal"), true);

        // phase 4: mood tags lead his sentences for the voice; they never reach the screen or the chat
        List<String> shown = new ArrayList<>(), voiced = new ArrayList<>();
        Claude.Stream ms2 = new Claude.Stream(Claude.SONNET, new Claude.Listener() {
            public void text(String soFar) { shown.add(soFar); }
            public void sentence(String x, String mood) { voiced.add(mood + ":" + x); }
        });
        for (String piece : new String[]{"[pro", "ud] Welcome back, boss! I held the desk. [scar", "ed] Is that the vacuum? Boss!"})
            ms2.line(ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + piece + "\"}}"));
        Claude.Reply moody = ms2.finish();
        eq("moods: each piece of speech with its mood, carried until the next tag", voiced,
                "[proud:Welcome back, boss! I held the desk., scared:Is that the vacuum?, scared:Boss!]");
        eq("moods: no tag on screen, not even half of one", String.join("|", shown).contains("["), false);
        eq("moods: what he saw while it streamed", shown.get(1), "Welcome back, boss! I held the desk.");
        eq("moods: none in the saved reply", moody.text, "Welcome back, boss! I held the desk. Is that the vacuum? Boss!");
        List<String> big = new ArrayList<>();
        Claude.Stream bs = new Claude.Stream(Claude.SONNET, new Claude.Listener() {
            public void text(String soFar) { }
            public void sentence(String x, String mood) { big.add(mood + ":" + x); }
        });
        bs.line(ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"[proud] Done, boss. [scared] Run! \"}}"));
        bs.finish();
        eq("moods: a change inside one piece of the stream splits it", big, "[proud:Done, boss., scared:Run!]");
        List<String> looked = new ArrayList<>();
        Claude.Stream ls = new Claude.Stream(Claude.OPUS, new Claude.Listener() {
            public void text(String soFar) { }
            public void sentence(String x, String mood) { }
            public void looked(String tool, String what) { looked.add(tool + ":" + what); }
        });
        ls.line(ev("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"s1\",\"name\":\"web_search\",\"input\":{}}}"));
        ls.line(ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\": \\\"v100 bf16\\\"}\"}}"));
        ls.line(ev("{\"type\":\"content_block_stop\",\"index\":0}"));
        ls.line(ev("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"s2\",\"name\":\"web_fetch\",\"input\":{}}}"));
        ls.line(ev("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"url\\\": \\\"https://nvidia.com/a\\\"}\"}}"));
        ls.line(ev("{\"type\":\"content_block_stop\",\"index\":1}"));
        eq("research progress: each search and page as it starts", looked, "[web_search:v100 bf16, web_fetch:https://nvidia.com/a]");
        eq("moods: only the eight are tags; other brackets stay", Claude.untag("[sad] Use [brackets] here [Deadpan]."), " Use [brackets] here .");
        List<String> plain = new ArrayList<>();
        Claude.Stream ns = new Claude.Stream(Claude.SONNET, new Claude.Listener() {
            public void text(String soFar) { }
            public void sentence(String x, String mood) { plain.add("(" + mood + ")" + x); }
        });
        ns.line(ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"No tags at all.\"}}"));
        ns.finish();
        eq("moods: none given, none passed (the voice uses his resting mood)", plain, "[()No tags at all.]");

        // a streamed reply: a remark, a search, then the answer in pieces, sentences handed to the voice as they end
        List<String> texts = new ArrayList<>(), said = new ArrayList<>();
        Claude.Listener l = new Claude.Listener() {
            public void text(String soFar) { texts.add(soFar); }
            public void sentence(String s, String mood) { said.add(s); }
        };
        Claude.Stream st = new Claude.Stream(Claude.SONNET, l);
        st.line("event: message_start");
        st.line(ev("{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":40,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":1500}}}"));
        st.line(ev("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"));
        st.line(ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"He wants tomorrow's weather. Search.\"}}"));
        st.line(ev("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"));
        st.line(ev("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Checking the SKY. \"}}"));
        st.line(ev("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"server_tool_use\",\"name\":\"web_search\"}}"));
        st.line(ev("{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"web_search_tool_result\"}}"));
        st.line(ev("{\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"text_delta\",\"text\":\"Rain tomorrow, boss. WATER. From the\"}}"));
        st.line(ev("{\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"url\":\"https://www.weather.gov/x\"}}}"));
        st.line(ev("{\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"text_delta\",\"text\":\" sky! Stay inside\"}}"));
        st.line(ev("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":30}}"));
        st.line(ev("{\"type\":\"message_stop\"}"));
        Claude.Reply rep = st.finish();
        eq("answer = the text after the search", rep.text, "Rain tomorrow, boss. WATER. From the sky! Stay inside");
        eq("the screen was cleared when he searched", texts.contains(""), true);
        // sentences that end inside the same piece of the stream go to the voice together
        eq("voice: the remark, then the finished sentences as they end, then the rest", said,
                "[Checking the SKY., Rain tomorrow, boss. WATER., From the sky!, Stay inside]");
        eq("sources", rep.sources, "[weather.gov]");
        eq("one search counted", rep.searches, 1);
        eq("stop", rep.stop, "end_turn");
        // 40 in x $2 + 30 out x $10 + 1500 cached x $0.10, per million, + $0.01 per search
        eq("cost", String.format(java.util.Locale.US, "%.6f", rep.cost()), String.format(java.util.Locale.US, "%.6f", (40 * 2 + 30 * 10 + 1500 * 0.10) / 1e6 + 0.01));
        Claude.Reply o = new Claude.Reply();
        o.model = Claude.OPUS; o.output = 1_000_000;
        eq("Opus output price", o.cost(), 20.0);

        try {
            Claude.Stream bad = new Claude.Stream(Claude.SONNET, null);
            bad.line(ev("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
            eq("overloaded mid-stream throws", "no", "yes");
        } catch (Exception e) {
            eq("overloaded, plainly", e.getMessage(), "Claude is busy right now");
        }
        eq("401", Claude.why(401, ""), "the Claude key isn't valid");
        eq("his server down, said as his server", Claude.serverDown(502), "your server didn't answer (502): it may be off or restarting");
        eq("credits", Claude.why(400, "{\"error\":{\"message\":\"Your credit balance is too low\"}}"), "the Claude credits have run out for this month");
        eq("other: Claude's own words", Claude.why(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"messages: roles must alternate\"}}"),
                "Claude answered 400: messages: roles must alternate");
        eq("tidy keeps paragraphs", Claude.tidy("one  two\n\n\n\nthree"), "one two\n\nthree");

        // the brain switch: his own server (free) or Claude
        Config.ownBase = "https://box.example:8448";
        Config.ownModel = "qwen";
        Config.ownName = "Qwen";
        Claude.useBrain(true);
        eq("his own server's requests name its model", Claude.request("PERSONA", null, null, "Friday, October 17, 2026, 3:10 AM", HERE,
                c.window(24), false).getString("model"), "qwen");
        eq("they go to his server", Claude.base, "https://box.example:8448");
        eq("it's free", java.util.Arrays.toString(Claude.price("qwen")), "[0.0, 0.0, 0.0, 0.0]");
        Claude.useBrain(false);
        eq("Claude again goes to Anthropic", Claude.base, Claude.ANTHROPIC);
        eq("Claude again = Sonnet", Claude.request("PERSONA", null, null, "Friday, October 17, 2026, 3:10 AM", HERE,
                c.window(24), false).getString("model"), Claude.SONNET);
        // the hands: more rounds once the screen is used, Qwen more than Claude
        eq("plain questions: 6 rounds", Claude.roundLimit(false, false), Claude.MAX_ROUNDS);
        eq("screen on his own model: 25", Claude.roundLimit(true, true), 25);
        eq("screen on Claude: 12", Claude.roundLimit(true, false), 12);
        // each message says which brain is answering (the owner 2026-11-11: "sprocket thinks it's running from claude still")
        Config.own = true;
        eq("on his server it's told its name", Claude.brainNow(false).startsWith("Your brain right now is Qwen (qwen)") && Claude.brainNow(false).contains("not Claude"), true);
        Config.ownName = "";
        eq("no name given: a plain one", Claude.brainNow(false).startsWith("Your brain right now is your own model"), true);
        Config.own = false;
        eq("on Claude it's told it's Claude", Claude.brainNow(false) + " | " + Claude.brainNow(true).contains("Opus"),
                "Your brain right now is Claude Sonnet (Anthropic's API, from their credits). | true");
        // a tool that wasn't offered never runs, whatever the model asks for (Codex 042 #2)
        Config.reset();
        Config.off.add("screen");
        JSONObject noScreen = Claude.request("PERSONA", null, null, "Friday, October 17, 2026, 3:10 AM", HERE, c.window(24), false);
        eq("a switched-off feature's tool isn't offered", Claude.offered(noScreen).contains("screen") + " " + Claude.offered(noScreen).contains("phone_control"), "false true");
        int[] hiddenRan = {0};
        java.util.List<String> fed = new java.util.ArrayList<>();
        Claude.transport = (brain, body, l2, cancel) -> {
            Claude.Reply x = new Claude.Reply();
            JSONArray m = body.getJSONArray("messages");
            Object last = m.getJSONObject(m.length() - 1).opt("content");
            if (String.valueOf(last).contains("tool_result")) { fed.add(String.valueOf(last)); x.stop = "end_turn"; x.text = "No."; return x; }
            x.stop = "tool_use";
            JSONObject call = new JSONObject().put("type", "tool_use").put("id", "toolu_h").put("name", "screen").put("input", new JSONObject().put("action", "read"));
            x.content.put(call);
            x.calls.add(call);
            return x;
        };
        try {
            Claude.send(Claude.Brain.now(q -> "K"), noScreen, null, null, (name, in) -> { hiddenRan[0]++; return "read the screen"; });
        } catch (Exception e) { fed.add("ERROR " + e.getMessage()); }
        eq("the model asks for it anyway: it doesn't run", hiddenRan[0], 0);
        eq("and the model is told so", fed.size() == 1 && fed.get(0).contains("isn't available right now"), true);
        // switched off while the answer was on its way: it still doesn't run (Codex 043 #2)
        Config.reset();
        JSONObject withScreen = Claude.request("PERSONA", null, null, "Friday, October 17, 2026, 3:10 AM", HERE, c.window(24), false);
        int[] lateRan = {0};
        Claude.transport = (brain, body, l2, cancel) -> {
            Claude.Reply x = new Claude.Reply();
            JSONArray m = body.getJSONArray("messages");
            if (String.valueOf(m.getJSONObject(m.length() - 1).opt("content")).contains("tool_result")) { x.stop = "end_turn"; x.text = "No."; return x; }
            Config.off.add("screen");             // he saves Settings with screen control off, mid-answer
            x.stop = "tool_use";
            JSONObject call = new JSONObject().put("type", "tool_use").put("id", "toolu_l").put("name", "screen").put("input", new JSONObject().put("action", "read"));
            x.content.put(call);
            x.calls.add(call);
            return x;
        };
        try { Claude.send(Claude.Brain.now(q -> "K"), withScreen, null, null, (name, in) -> { lateRan[0]++; return "read"; }); } catch (Exception e) { }
        eq("offered, then switched off before it ran: it doesn't run", Claude.offered(withScreen).contains("screen") + " " + lateRan[0], "true 0");
        JSONObject setupTurn = new JSONObject(noScreen.toString());
        setupTurn.remove("tools");
        eq("a turn with no tools runs none", Claude.offered(setupTurn).isEmpty(), true);
        Config.reset();
        // the backup brain (the owner 2026-11-13: "ask first each time"): only a brain that never answered can be swapped
        Claude.Transport keepB = Claude.transport;
        eq("no connection: a backup may take it", Claude.backupOk(new Claude.Down("no connection")), true);
        eq("busy after the retries, nothing said: yes", Claude.backupOk(new Claude.Busy("busy", null, false)), true);
        eq("busy after words were said: no", Claude.backupOk(new Claude.Busy("busy", null, true)), false);
        eq("a wrong key isn't a down server", Claude.backupOk(new Exception("Anthropic says that key isn't valid")), false);
        eq("server errors are down", Claude.downCode(500) + " " + Claude.downCode(502) + " " + Claude.downCode(503) + " "
                + Claude.downCode(401) + " " + Claude.downCode(400) + " " + Claude.downCode(529), "true true true false false false");
        int[] rounds = {0};
        Claude.transport = (brain, body, l2, cancel) -> {
            if (++rounds[0] == 1) {
                Claude.Reply x = new Claude.Reply();
                x.stop = "tool_use";
                JSONObject call = new JSONObject().put("type", "tool_use").put("id", "toolu_f").put("name", "flashlight").put("input", new JSONObject());
                x.content.put(call);
                x.calls.add(call);
                return x;
            }
            throw new Claude.Down("no connection");
        };
        Exception after = null;
        try { Claude.send(new Claude.Brain("https://my-server:8448", "k", true, "qwen"), r2, null, null, (name, in) -> "On."); } catch (Exception e) { after = e; }
        eq("down after it already acted: never swapped (nothing done twice)", after != null && !Claude.backupOk(after), true);
        eq("the card from their own server", Claude.backupCard(true), "Your server didn't answer. Ask Claude this one instead? It uses your Claude credit.");
        Config.ownName = "Qwen";
        eq("the card from Claude names their own brain", Claude.backupCard(false), "Claude isn't answering. Ask Qwen this one instead?");
        List<String> toBackup = new ArrayList<>();
        Claude.transport = (brain, body, l2, cancel) -> {
            toBackup.add(body.getString("model") + " | " + body.toString().contains(Claude.brainNow(false, false)));
            Claude.Reply x = new Claude.Reply();
            x.stop = "end_turn";
            x.text = "Sunny.";
            return x;
        };
        Config.ownBase = "https://my-server:8448";
        Claude.useBrain(true);
        JSONObject onQwen = Claude.request("PERSONA", null, null, "Friday, November 13, 2026, 7:30 PM", HERE, c.window(24), false);
        Claude.useBrain(false);
        try { Claude.send(new Claude.Brain(Claude.ANTHROPIC, "k", false, "qwen"), onQwen, null, null, null); } catch (Exception e) { toBackup.add("ERROR " + e); }
        Config.reset();
        eq("their own server's question sent to Claude says Claude is answering", toBackup, "[" + Claude.SONNET + " | true]");
        Claude.transport = keepB;

        System.out.println(fails == 0 ? "claude: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
