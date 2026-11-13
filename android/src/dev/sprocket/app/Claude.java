package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprocket's brain: the Claude Messages API, streamed. Sonnet answers normally; "think harder" hands the same
 * conversation to Opus. Web search is Claude's own server tool; the weather is the app's own tool (Weather.java),
 * answered by the app in a second round. The persona and the conversation are prompt-cached (see request()); the
 * date, time and town ride after the cache mark in his newest message.
 *
 * Plain Java apart from the network call, so the request shape, the stream parser and the cost sum are tested on the
 * desktop (test/ClaudeTest.java). Prices: Claude API pricing page, 2026-10-17 (per million tokens).
 */
final class Claude {
    private Claude() { }

    static final String SONNET = "claude-sonnet-5-5", OPUS = "claude-opus-5-5";
    static final String ANTHROPIC = "https://api.anthropic.com";
    static String base = ANTHROPIC;                         // the self-test points it at a stand-in server

    // The brain switch (stage 2, 2026-11-10): Claude, or the owner's own server answering the same Messages API --
    // for example Qwen through the included go-between (go-between/), which also does web search. Its address, name
    // and model are in Config. It's free, so it costs nothing; it may be slow to start (a model loading), so it waits
    // longer.

    /** Which brain is answering, said in each message's note (the owner 2026-11-11: "sprocket thinks it's running from claude
     * still"; Qwen guessed from the older Claude replies in the chat). */
    static String brainNow(boolean harder) {
        return Config.own ? "Your brain right now is " + ownName() + " (" + Config.model() + "), on the boss's own server, not Claude; "
                + "older replies in this chat may have come from another brain."
                : "Your brain right now is Claude " + (harder ? "Opus" : "Sonnet") + " (Anthropic's API, from his credits).";
    }

    /** The own brain's name as the owner gave it ("Qwen"), or a plain one. */
    static String ownName() { return Config.ownName.trim().isEmpty() ? "your own model" : Config.ownName.trim(); }

    static synchronized void useBrain(boolean own) {
        Config.own = own;
        base = Config.base();
    }

    /**
     * The brain one question goes to, with its key, fixed for every round of that question (Codex 040 #6: switching
     * brains mid-answer sent the old brain's key to the new brain's server). Take it with now() when the question starts.
     */
    static final class Brain {
        final String base, key, model;           // model: the own server's model name (unused for Claude)
        final boolean own;

        Brain(String base, String key, boolean own, String model) { this.base = base; this.key = key; this.own = own; this.model = model; }

        /** The brain switched on right now; keyFor gets true for the own server's key, false for Claude's. */
        static Brain now(java.util.function.Function<Boolean, String> keyFor) {
            synchronized (Claude.class) { return new Brain(Claude.base, keyFor.apply(Config.own), Config.own, Config.model()); }
        }
    }
    static final int HISTORY_TURNS = 24;                    // the last 24 to 35 messages go with each question (Chat.window)
    // Effort (2026-10-17 docs): Sonnet 5.5 defaults to "high", and Anthropic suggests "medium" or "low" for chat; a
    // phone assistant lives on speed, so plain questions run "low". Think harder = Opus at "high". Thinking counts
    // toward max_tokens, so both leave room for it (only what's used is billed).
    static final String EFFORT = "low", HARD_EFFORT = "high";
    static final int MAX_TOKENS = 2000, HARD_MAX_TOKENS = 8000;

    /** $ per million tokens: input, output, cache write (5 min), cache read; plus $0.01 per web search. */
    static double[] price(String model) {
        if (model == null || !model.startsWith("claude")) return new double[]{0, 0, 0, 0};   // his own server is free
        return OPUS.equals(model) ? new double[]{4, 20, 5, 0.20} : new double[]{2, 10, 2.50, 0.10};
    }

    static final class Reply {
        String text = "", stop = "", model = "";
        final List<String> sources = new ArrayList<>();
        int input, output, cacheWrite, cacheRead, searches;
        final JSONArray content = new JSONArray();          // everything he said this round, to send back with a tool's answer
        final List<JSONObject> calls = new ArrayList<>();   // the app's tools he asked for (tool_use blocks)

        /** An earlier round of the same turn: its usage and sources count too. */
        void add(Reply earlier) {
            input += earlier.input; output += earlier.output; cacheWrite += earlier.cacheWrite; cacheRead += earlier.cacheRead;
            searches += earlier.searches;
            for (String x : earlier.sources) if (!sources.contains(x) && sources.size() < 3) sources.add(x);
        }

        double cost() {
            double[] p = price(model);
            return (input * p[0] + output * p[1] + cacheWrite * p[2] + cacheRead * p[3]) / 1e6 + (p[0] == 0 ? 0 : searches * 0.01);
        }
    }

    /** Where the phone is, roughly: for web search's location and the note to Claude. Empty strings when unknown. */
    static final class Where {
        final String timezone, country, city, region;

        Where(String timezone, String country, String city, String region) {
            this.timezone = timezone == null ? "" : timezone;
            this.country = country == null ? "" : country;
            this.city = city == null ? "" : city;
            this.region = region == null ? "" : region;
        }

        String town() { return city.isEmpty() ? "" : region.isEmpty() ? city : city + ", " + region; }
    }

    /** His photos as base64 JPEG, by file name (the app sets it; null = no photos). */
    interface Photos {
        String base64(String name) throws Exception;
    }

    static Photos photos;

    static final String PICTURE = "[[picture:";

    /** A tool's answer for Claude: words, or (an answer starting "[[picture:NAME]]") that saved picture, then the words
     * after it -- his screen, for look_at_screen. */
    static Object toolContent(String answer, Photos from) throws Exception {
        if (answer == null || !answer.startsWith(PICTURE) || !answer.contains("]]")) return answer;
        int end = answer.indexOf("]]");
        String name = answer.substring(PICTURE.length(), end), data = from == null ? null : from.base64(name);
        if (data == null) return "That picture is gone.";
        return new JSONArray()
                .put(new JSONObject().put("type", "image").put("source", new JSONObject().put("type", "base64")
                        .put("media_type", "image/jpeg").put("data", data)))
                .put(new JSONObject().put("type", "text").put("text", answer.substring(end + 2)));
    }

    /** The app's own tools (the weather), run when Claude asks for one. */
    interface Tools {
        String run(String name, JSONObject input) throws Exception;

        /** He pressed Stop while a tool ran: the turn ends there, with no next round. */
        default boolean stopped() { return false; }
    }

    /** What the screen and the voice hear while a reply streams in. */
    interface Listener {
        void text(String soFar);        // the visible reply so far (reset if Claude searches after starting to talk)
        void sentence(String s, String mood);   // each finished sentence for the voice, with its mood ("" = none)

        /** A search or a page Claude is opening (server tools): what research shows while it works. */
        default void looked(String tool, String what) { }
    }

    /**
     * The Messages API body. history: the conversation so far, oldest first, ending with his new message.
     *
     * Prompt caching: cache marks on the persona, his About-you page, his notes and his newest words (four, the
     * API's most), so each question reads the persona and the whole conversation before it from the cache (a
     * twentieth of the input price on Sonnet 5.5, and a sooner first word) and only the newest exchange is new. The
     * page and the notes each have their own mark, so a new note leaves the persona and the page cached. Anything
     * that changes every time (the clock, the think-harder note) goes AFTER the last mark, in his newest message
     * only, or it would make every request a new one. A think-harder question is a one-off on Opus (its own cache), so it marks nothing: a cache write costs
     * more than plain input and would never be read.
     */
    static JSONObject request(String persona, String about, String memory, String now, Where where, List<Chat.Turn> history,
                              boolean harder) throws Exception {
        String model = Config.own ? Config.model() : harder ? OPUS : SONNET;
        JSONObject who = new JSONObject().put("type", "text").put("text", persona);
        if (!harder) who.put("cache_control", new JSONObject().put("type", "ephemeral"));
        JSONArray system = new JSONArray().put(who);
        if (about != null) {    // his About-you page: rarely edited, so before the notes
            JSONObject page = new JSONObject().put("type", "text").put("text", about);
            if (!harder) page.put("cache_control", new JSONObject().put("type", "ephemeral"));
            system.put(page);
        }
        if (memory != null) {   // what he's had remembered: its own cache mark, so a new note leaves the persona cached
            JSONObject notes = new JSONObject().put("type", "text").put("text", memory);
            if (!harder) notes.put("cache_control", new JSONObject().put("type", "ephemeral"));
            system.put(notes);
        }
        JSONArray messages = new JSONArray();
        for (int i = 0; i < history.size(); i++) {
            Chat.Turn t = history.get(i);
            if (!t.role.equals("user")) {
                messages.put(new JSONObject().put("role", t.role).put("content", t.text));
                continue;
            }
            // his messages always as blocks, so an older one is sent exactly as it was when it was the newest: his
            // photos first (the same file gives the same bytes every time), then his words
            JSONArray content = new JSONArray();
            for (String name : t.photos) {
                String data = photos == null ? null : photos.base64(name);
                if (data == null) continue;
                content.put(new JSONObject().put("type", "image").put("source", new JSONObject().put("type", "base64")
                        .put("media_type", "image/jpeg").put("data", data)));
            }
            if (!t.text.trim().isEmpty() || content.length() == 0)
                content.put(new JSONObject().put("type", "text").put("text", t.text.trim().isEmpty() ? "(no words)" : t.text));
            JSONObject words = content.getJSONObject(content.length() - 1);
            if (i == history.size() - 1) {
                if (!harder) words.put("cache_control", new JSONObject().put("type", "ephemeral"));
                content.put(new JSONObject().put("type", "text").put("text", "(From the app, not the boss: it is " + now
                        + (where.town().isEmpty() ? "" : ", near " + where.town()) + "."
                        + (harder ? " The boss pressed THINK HARDER: take the time to get this one right." : "")
                        + " " + brainNow(harder) + ")"));
            }
            messages.put(new JSONObject().put("role", "user").put("content", content));
        }
        // tools come first in the cache's order: these change only when he travels to another town
        JSONObject loc = new JSONObject().put("type", "approximate");
        if (!where.city.isEmpty()) loc.put("city", where.city);
        if (!where.region.isEmpty()) loc.put("region", where.region);
        if (where.country.length() == 2) loc.put("country", where.country);
        if (!where.timezone.isEmpty()) loc.put("timezone", where.timezone);
        JSONObject search = new JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3);
        if (loc.length() > 1) search.put("user_location", loc);
        return new JSONObject().put("model", model).put("max_tokens", harder ? HARD_MAX_TOKENS : MAX_TOKENS).put("stream", true)
                .put("output_config", new JSONObject().put("effort", harder ? HARD_EFFORT : EFFORT))
                .put("system", system).put("messages", messages).put("tools", tools(search));
    }

    /** Web search, the weather, the phone tools (phase 3), his texts, lists, research, then memory (3b), always in this
     * order: they lead the cached prompt. */
    static JSONArray tools(JSONObject search) throws Exception {
        JSONArray all = new JSONArray().put(search).put(weatherTool());
        JSONArray phone = PhoneTools.definitions(), mem = Memory.definitions();
        for (int i = 0; i < phone.length(); i++) all.put(phone.getJSONObject(i));
        JSONArray texts = Messages.definitions();
        for (int i = 0; i < texts.length(); i++) all.put(texts.getJSONObject(i));
        all.put(Notices.definition());
        all.put(new JSONObject().put("name", "look_at_screen")
                .put("description", "A picture of what was on his screen when he held the side button: the app or page he "
                        + "was looking at. Use it when he asks about what's on his screen (\"what's this?\", \"summarise this "
                        + "page\", \"what does this say?\").")
                .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject())));
        JSONArray machines = Machines.definitions();
        for (int i = 0; i < machines.length(); i++) all.put(machines.getJSONObject(i));
        JSONArray github = GitHub.definitions();
        for (int i = 0; i < github.length(); i++) all.put(github.getJSONObject(i));
        JSONArray studio = Studio.definitions();
        for (int i = 0; i < studio.length(); i++) all.put(studio.getJSONObject(i));
        JSONArray goals = Goals.definitions();
        for (int i = 0; i < goals.length(); i++) all.put(goals.getJSONObject(i));
        all.put(Feed.watchTool());
        JSONArray lists = Lists.definitions();
        for (int i = 0; i < lists.length(); i++) all.put(lists.getJSONObject(i));
        all.put(Research.definition());
        for (int i = 0; i < mem.length(); i++) all.put(mem.getJSONObject(i));
        return Config.allowed(all);                 // without what he switched off or hasn't set up
    }

    static JSONObject weatherTool() throws Exception {
        return new JSONObject().put("name", "weather")
                .put("description", "Live weather now, the next hours and the next three days (Open-Meteo), for where the boss "
                        + "is (his phone's rough location) or for a town he names. Use it for every weather question "
                        + "instead of web search.")
                .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("place", new JSONObject().put("type", "string").put("description",
                                "A town, with its state or country if needed (\"Paris, Texas\"), only when he asks about "
                                        + "somewhere else. Leave it out for where he is."))));
    }

    /**
     * The next request of the same turn: everything Claude said this round goes back as his message, then (after a
     * tool call) the tools' answers as the boss's. With no answers, a paused turn is handed back to be continued.
     */
    static JSONObject next(JSONObject req, Reply round, JSONArray results) throws Exception {
        JSONObject n = new JSONObject(req.toString());
        JSONArray msgs = n.getJSONArray("messages");
        msgs.put(new JSONObject().put("role", "assistant").put("content", new JSONArray(round.content.toString())));
        if (results != null && results.length() > 0) msgs.put(new JSONObject().put("role", "user").put("content", results));
        return n;
    }

    /** Reads the server-sent events of one streamed reply. */
    static final class Stream {
        private static final Pattern END = Pattern.compile("[.!?][\"')\\]]*\\s");
        final Reply r = new Reply();
        private final Listener l;
        private final StringBuilder run = new StringBuilder();
        private int said;
        private final java.util.TreeMap<Integer, JSONObject> blocks = new java.util.TreeMap<>();
        private final java.util.Map<Integer, StringBuilder> args = new java.util.HashMap<>();   // tool input, in pieces

        Stream(String model, Listener l) { this.l = l; r.model = model; }

        void line(String line) throws Exception {
            if (!line.startsWith("data:")) return;
            JSONObject e = new JSONObject(line.substring(5).trim());
            switch (e.optString("type")) {
                case "message_start": {
                    JSONObject u = e.optJSONObject("message") == null ? null : e.getJSONObject("message").optJSONObject("usage");
                    if (u != null) {
                        r.input += u.optInt("input_tokens");
                        r.cacheWrite += u.optInt("cache_creation_input_tokens");
                        r.cacheRead += u.optInt("cache_read_input_tokens");
                    }
                    break;
                }
                case "content_block_start": {
                    JSONObject b = new JSONObject(e.getJSONObject("content_block").toString());
                    blocks.put(e.optInt("index"), b);
                    String t = b.optString("type");
                    if (t.equals("server_tool_use") || t.equals("tool_use")) {   // he talked before looking: not the answer
                        if (t.equals("server_tool_use") && b.optString("name", "web_search").equals("web_search")) r.searches++;
                        args.put(e.optInt("index"), new StringBuilder());
                        run.setLength(0);
                        said = 0;
                        if (l != null) l.text("");
                    }
                    break;
                }
                case "content_block_delta": {
                    JSONObject d = e.getJSONObject("delta");
                    JSONObject b = blocks.get(e.optInt("index"));
                    switch (d.optString("type")) {
                        case "text_delta":
                            run.append(d.optString("text"));
                            if (b != null) b.put("text", b.optString("text") + d.optString("text"));
                            if (l != null) l.text(shown(run.toString()));
                            speak(false);
                            break;
                        case "citations_delta": {
                            String site = site(d.getJSONObject("citation").optString("url"));
                            if (!site.isEmpty() && !r.sources.contains(site) && r.sources.size() < 3) r.sources.add(site);
                            if (b != null) {
                                JSONArray cites = b.optJSONArray("citations");
                                if (cites == null) b.put("citations", cites = new JSONArray());
                                cites.put(d.getJSONObject("citation"));
                            }
                            break;
                        }
                        case "thinking_delta":
                            if (b != null) b.put("thinking", b.optString("thinking") + d.optString("thinking"));
                            break;
                        case "signature_delta":
                            if (b != null) b.put("signature", d.optString("signature"));
                            break;
                        case "input_json_delta": {
                            StringBuilder a = args.get(e.optInt("index"));
                            if (a != null) a.append(d.optString("partial_json"));
                            break;
                        }
                        default:
                    }
                    break;
                }
                case "content_block_stop":
                    closeArgs(e.optInt("index"));
                    break;
                case "message_delta": {
                    r.stop = e.getJSONObject("delta").optString("stop_reason", r.stop);
                    JSONObject u = e.optJSONObject("usage");
                    if (u != null) r.output = Math.max(r.output, u.optInt("output_tokens"));
                    break;
                }
                case "error": {
                    JSONObject er = e.optJSONObject("error");
                    String type = er == null ? "" : er.optString("type");
                    if (type.contains("overloaded")) throw new Busy("Claude is busy right now", null, run.length() > 0);
                    throw new Exception("Claude stopped: " + (er == null ? "an error" : er.optString("message")));
                }
                default:
            }
        }

        private String mood = "";               // the last mood tag: it lasts until the next one

        private void speak(boolean end) {
            String t = run.toString();
            int cut = said;
            Matcher m = END.matcher(t);
            while (m.find()) if (m.end() > cut) cut = m.end();
            if (end) cut = t.length();
            if (cut <= said) return;
            String raw = t.substring(said, cut);
            said = cut;
            Matcher tag = MOOD.matcher(raw);     // a tag leads the sentences it belongs to: split at each one
            int from = 0;
            while (tag.find()) {
                say(raw.substring(from, tag.start()));
                mood = tag.group(1).toLowerCase(Locale.US);
                from = tag.end();
            }
            say(raw.substring(from));
        }

        private void say(String raw) {
            String piece = tidy(raw);
            if (!piece.isEmpty() && l != null) l.sentence(piece, mood);
        }

        /** A tool block's input, once its pieces are all in. */
        private void closeArgs(int i) throws Exception {
            StringBuilder a = args.remove(i);
            JSONObject b = blocks.get(i);
            if (a == null || b == null) return;
            b.put("input", a.toString().trim().isEmpty() ? new JSONObject() : new JSONObject(a.toString()));
            if (l != null && "server_tool_use".equals(b.optString("type"))) {
                JSONObject in = b.getJSONObject("input");
                l.looked(b.optString("name"), in.optString("query", in.optString("url")));
            }
        }

        Reply finish() throws Exception {
            speak(true);
            r.text = tidy(untag(run.toString()));
            for (Integer i : new ArrayList<>(args.keySet())) closeArgs(i);
            for (JSONObject b : blocks.values()) {
                if (b.optString("type").equals("text")) {
                    // sent back as is, except an empty block (refused) or trailing spaces (refused at the end)
                    String t = b.optString("text").replaceAll("\\s+$", "");
                    if (t.isEmpty()) continue;
                    b.put("text", t);
                }
                if (b.optString("type").equals("tool_use") && !b.has("input")) b.put("input", new JSONObject());
                r.content.put(b);
                if (b.optString("type").equals("tool_use")) r.calls.add(b);
            }
            return r;
        }
    }

    /** The eight moods Sprocket tags his sentences with for the voice (phase 4; the gateway's presets). */
    static final Pattern MOOD = Pattern.compile("\\[(proud|excited|outraged|angry|scared|sad|surprised|deadpan)\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HALF_TAG = Pattern.compile("\\[[A-Za-z]{0,10}$");

    /** Text without its mood tags (other brackets stay). */
    static String untag(String s) { return MOOD.matcher(s).replaceAll(""); }

    /** What the screen shows while it streams: no tags, not even the start of one still arriving. */
    static String shown(String s) { return tidy(HALF_TAG.matcher(untag(s)).replaceAll("")); }

    /** One space between words, paragraphs kept. */
    static String tidy(String s) {
        return s.replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    static String site(String url) {
        String h = url.replaceFirst("^[a-zA-Z]+://", "").replaceFirst("[/?#].*$", "").toLowerCase(Locale.US);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    /** Says why a request failed, plainly. */
    static String why(int code, String body) {
        if (code == 401) return "the Claude key isn't valid";
        if (body != null && body.contains("credit balance")) return "the Claude credits have run out for this month";
        if (code == 429 || code == 529) return "Claude is busy right now; try again in a moment";
        if (body != null && body.contains("not scoped to a workspace"))
            return "this key isn't in a workspace. In the Claude Console, open Workspaces, then make the key inside one";
        String msg = errorMessage(body);
        return "Claude answered " + code + (msg.isEmpty() ? "" : ": " + msg);
    }

    /** The error's own message from an error reply's body, short, or "". */
    static String errorMessage(String body) {
        String msg = "";
        try { msg = new JSONObject(body).getJSONObject("error").optString("message"); } catch (Exception ignored) { }
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > 160 ? msg.substring(0, 157) + "..." : msg;
    }

    // The first screen's connection test (Codex's review 2026-11-12: Start took a wrong key or an unreachable server,
    // and it only showed later, in the middle of setup).
    static final int CHECK_WAIT_OWN = 120000;   // a model may need a minute or two to load

    /** One tiny request (max_tokens 1) to the brain about to be saved: null when it answers, else what's wrong, in
     * plain words. On Claude it's a few tokens, a tiny fraction of a cent; the own server is free. */
    static String check(Brain b) {
        String bad = Config.destinationProblem(b.base);
        if (bad != null) return bad;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(b.base + "/v1/messages").openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(10000);
            h.setReadTimeout(b.own ? CHECK_WAIT_OWN : 30000);
            h.setDoOutput(true);
            h.setRequestProperty("x-api-key", b.key);
            h.setRequestProperty("anthropic-version", "2023-06-01");
            h.setRequestProperty("content-type", "application/json");
            JSONObject req = new JSONObject().put("model", b.own ? b.model : SONNET).put("max_tokens", 1)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "Hi")));
            try (OutputStream o = h.getOutputStream()) { o.write(req.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = h.getResponseCode();
            InputStream in = code == 200 ? h.getInputStream() : h.getErrorStream();
            String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            h.disconnect();
            return checkProblem(b.own, code, body);
        } catch (Exception e) {
            return checkFailed(b.own, e, b.base);
        }
    }

    /** What an answer to the test means: null when it worked. */
    static String checkProblem(boolean own, int code, String body) {
        if (code == 200) return null;
        String msg = errorMessage(body);
        if (own) {
            if (code == 401 || code == 403)
                return "Your server answered, but turned the access code down. Copy it again: for the go-between, it's the "
                        + "text in the file named token in its folder.";
            if (code == 404)
                return "Something answered at that address, but not the go-between. Check the address and its port number.";
            if (code == 502 || code == 503 || code == 504)
                return "The go-between is running, but your model didn't answer" + (msg.isEmpty() ? "" : " (" + msg + ")")
                        + ". Is the model server running, and does the go-between's UPSTREAM point at it?";
            return "Your server answered " + code + (msg.isEmpty() ? "" : ": " + msg);
        }
        if (code == 401) return "Anthropic says that key isn't valid. Copy it again from the Claude Console, under API keys.";
        if (body != null && body.contains("credit balance"))
            return "Your Anthropic account has no credit yet. Add some in the Claude Console under Billing, then tap Start again.";
        String w = why(code, body);
        return Character.toUpperCase(w.charAt(0)) + w.substring(1) + ".";
    }

    /** What a failed connection means, for the test. */
    static String checkFailed(boolean own, Exception e, String base) {
        String where = Config.host(base);
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
        if (e instanceof java.net.UnknownHostException)
            return own ? "This phone can't find " + where + ". If it's a Tailscale address, is Tailscale switched on in its "
                    + "app on this phone?" : "This phone can't reach Anthropic. Is it online?";
        if (e instanceof java.net.ConnectException || (e instanceof java.net.SocketTimeoutException && m.contains("connect")))
            return own ? "Nothing answered at " + where + ". Is the go-between running on that computer, and can this phone "
                    + "reach it (the same Wi-Fi for a home address, Tailscale on for a Tailscale one)?"
                    : "This phone couldn't reach Anthropic. Is it online?";
        if (e instanceof java.net.SocketTimeoutException)
            return own ? "Your server took over two minutes to answer. A model can be slow the first time, while it loads: "
                    + "give it a minute and tap Start again." : "Anthropic didn't answer in time. Try again in a moment.";
        if (e instanceof javax.net.ssl.SSLException)
            return "The connection to " + where + " isn't secure: its https certificate didn't check out."
                    + (own ? " For a Tailscale address, turn on HTTPS certificates in Tailscale's admin console." : "");
        return (own ? "Couldn't reach your server: " : "Couldn't reach Anthropic: ") + e.getMessage();
    }

    /** At most this many requests for one question (tool answers and paused turns continue it). */
    static final int MAX_ROUNDS = 6;
    // The hands (stage 1) take a step per tap or scroll, so a question that uses the screen gets more rounds: plenty on
    // Qwen (free), fewer on Claude (every round re-sends the whole prompt, at a price).
    static final int SCREEN_ROUNDS_QWEN = 25, SCREEN_ROUNDS_CLAUDE = 12;
    static final String OUT_OF_STEPS = "That took more steps than I'm allowed. I stopped partway; ask again and I'll pick it up.";

    static int roundLimit(boolean usedScreen, boolean onOwn) {
        return !usedScreen ? MAX_ROUNDS : onOwn ? SCREEN_ROUNDS_QWEN : SCREEN_ROUNDS_CLAUDE;
    }

    /** One streamed request; the desktop tests put a stand-in here. */
    interface Transport {
        Reply once(Brain b, JSONObject req, Listener l, HttpURLConnection[] cancel) throws Exception;
    }

    static Transport transport = Claude::once;

    /** Busy or overloaded (429/529, or an "overloaded" error mid-stream): worth another try, unless words were already
     * said (said = true), which a second try would say again. retryAfter: the server's retry-after header, or null. */
    static final class Busy extends Exception {
        final String retryAfter;
        final boolean said;

        Busy(String message, String retryAfter, boolean said) { super(message); this.retryAfter = retryAfter; this.said = said; }
    }

    static final int TRIES = 3;   // the first try and two more (Magpie's pick 1, 2026-11-12: one busy moment ended the answer)

    /** How long to wait before try number attempt + 1: what the server asked (at most 20 s), else 2 s, then 5 s. */
    static long retryWait(String retryAfter, int attempt) {
        try { return Math.min(20_000L, Math.max(0, Long.parseLong(retryAfter.trim())) * 1000L); }
        catch (Exception e) { return attempt <= 1 ? 2_000L : 5_000L; }
    }

    static java.util.function.LongConsumer nap = Claude::sleep;   // the test makes it instant

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** One round, tried again while Claude is busy. */
    private static Reply onceRetrying(Brain b, JSONObject req, Listener l, HttpURLConnection[] cancel, Tools tools) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try { return transport.once(b, req, l, cancel); }
            catch (Busy e) {
                if (e.said || attempt >= TRIES) throw e;
                nap.accept(retryWait(e.retryAfter, attempt));
                if (tools != null && tools.stopped()) throw new Exception("stopped");
            }
        }
    }

    /**
     * One question, answered (network: call it off the main thread): a streamed reply, and while Claude stops to use
     * one of the app's tools (or the API pauses a long turn), the next round with the tool's answer. cancel[0] = the
     * open connection, for the stop button.
     */
    /** The tools this request offered, by name: a call to anything else is refused, whatever the model asks for
     * (a switched-off feature's tool is never offered, so it never runs: Codex 042 #2). */
    static java.util.Set<String> offered(JSONObject req) {
        java.util.Set<String> names = new java.util.HashSet<>();
        JSONArray t = req.optJSONArray("tools");
        for (int i = 0; t != null && i < t.length(); i++) names.add(t.optJSONObject(i) == null ? "" : t.optJSONObject(i).optString("name"));
        return names;
    }

    static Reply send(Brain b, JSONObject req, Listener l, HttpURLConnection[] cancel, Tools tools) throws Exception {
        String model = req.optString("model");   // the model goes with the brain the question started on
        if (b.own && !b.model.equals(model)) req.put("model", b.model);
        else if (!b.own && !model.startsWith("claude")) req.put("model", SONNET);
        Reply total = null;
        boolean acted = false, usedScreen = false;
        for (int round = 1; ; round++) {
            Reply rep = onceRetrying(b, req, l, cancel, tools);
            if (total != null) {
                rep.add(total);
                if (rep.text.isEmpty()) rep.text = total.text;   // said it before acting, nothing after: that's the reply
            }
            total = rep;
            for (JSONObject call : rep.calls) if ("screen".equals(call.optString("name"))) usedScreen = true;
            if (round >= roundLimit(usedScreen, b.own)) {
                if ("tool_use".equals(rep.stop) && rep.text.isEmpty()) rep.text = OUT_OF_STEPS;   // never stop silently
                break;
            }
            if ("tool_use".equals(rep.stop) && !rep.calls.isEmpty()) {
                acted = true;
                JSONArray results = new JSONArray();
                for (JSONObject call : rep.calls) {
                    JSONObject res = new JSONObject().put("type", "tool_result").put("tool_use_id", call.optString("id"));
                    try {
                        if (tools == null) throw new Exception("this app has no tools here");
                        if (!offered(req).contains(call.optString("name")) || !Config.toolOn(call.optString("name")))
                            throw new Exception("that tool isn't available right now");
                        res.put("content", toolContent(tools.run(call.optString("name"), call.optJSONObject("input") == null
                                ? new JSONObject() : call.getJSONObject("input")), photos));
                    } catch (Exception ex) {
                        res.put("content", "That didn't work: " + ex.getMessage()).put("is_error", true);
                    }
                    results.put(res);
                }
                if (tools != null && tools.stopped()) throw new Exception("stopped");
                req = next(req, rep, results);
            } else if ("pause_turn".equals(rep.stop)) {
                req = next(req, rep, null);       // a long search paused the turn: hand it back to be continued
            } else {
                break;
            }
        }
        if (total.text.isEmpty() && !acted) throw new Exception("Claude sent no answer");   // acted silently: not an error
        return total;
    }

    /** One streamed request. */
    static Reply once(Brain b, JSONObject req, Listener l, HttpURLConnection[] cancel) throws Exception {
        String bad = Config.destinationProblem(b.base);   // the app allows plain http, so the check is here too
        if (bad != null) throw new Exception(bad);
        HttpURLConnection h = (HttpURLConnection) new URL(b.base + "/v1/messages").openConnection();
        h.setInstanceFollowRedirects(false);    // a redirect would skip the address check (Codex 043 #1)
        if (cancel != null) cancel[0] = h;
        h.setConnectTimeout(10000);
        h.setReadTimeout(b.own ? 300000 : 60000);   // his own model may answer in one piece, after loading
        h.setDoOutput(true);
        h.setRequestProperty("x-api-key", b.key);
        h.setRequestProperty("anthropic-version", "2023-06-01");
        h.setRequestProperty("content-type", "application/json");
        try (OutputStream o = h.getOutputStream()) { o.write(req.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = h.getResponseCode();
        if (code != 200) {
            InputStream in = h.getErrorStream();
            String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (code == 429 || code == 529) throw new Busy(why(code, body), h.getHeaderField("retry-after"), false);
            throw new Exception(why(code, body));
        }
        Stream st = new Stream(req.optString("model"), l);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) st.line(line);
        }
        return st.finish();
    }
}
