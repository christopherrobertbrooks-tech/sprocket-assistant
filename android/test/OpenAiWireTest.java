package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** OpenAiWire: Sprocket's request in OpenAI's Responses shape, and two real OpenAI answers (recorded 2026-11-15,
 * gpt-6.1-sol, low thinking) replayed through Claude.Stream exactly as the phone reads them. */
public class OpenAiWireTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        boolean ok = got == null ? want == null : got.equals(want);
        if (!ok) { fails++; System.out.println("FAIL " + what + ": got " + got + ", want " + want); }
    }

    static List<String> sentences = new ArrayList<>();

    static Claude.Reply replay(String file) throws Exception {
        sentences.clear();
        Claude.Stream st = new Claude.Stream("gpt-6.1-sol", new Claude.Listener() {
            public void text(String soFar) { }
            public void sentence(String s, String mood) { sentences.add(s); }
        });
        OpenAiWire.Events ev = new OpenAiWire.Events();
        for (String line : Files.readAllLines(Paths.get("android/test/fixtures/" + file), StandardCharsets.UTF_8))
            for (String e : ev.feed(line)) st.line(e);
        return st.finish();
    }

    public static void main(String[] a) throws Exception {
        // the request: what Sprocket sends Claude, as OpenAI wants it
        JSONObject req = new JSONObject().put("model", "gpt-6.1-sol").put("max_tokens", 2000).put("stream", true)
                .put("output_config", new JSONObject().put("effort", "low"))
                .put("system", new JSONArray().put(new JSONObject().put("type", "text").put("text", "You are Sprocket."))
                        .put(new JSONObject().put("type", "text").put("text", "Be brief.").put("cache_control", new JSONObject().put("type", "ephemeral"))))
                .put("tools", new JSONArray()
                        .put(new JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3))
                        .put(new JSONObject().put("name", "screen").put("description", "Use the screen")
                                .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()))))
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "user").put("content", "turn off dark mode"))
                        .put(new JSONObject().put("role", "assistant").put("content", new JSONArray()
                                .put(new JSONObject().put("type", "thinking").put("thinking", "hm").put("signature", "x"))
                                .put(new JSONObject().put("type", "text").put("text", "On it."))
                                .put(new JSONObject().put("type", "tool_use").put("id", "call_1").put("name", "screen")
                                        .put("input", new JSONObject().put("action", "read")))))
                        .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                                .put(new JSONObject().put("type", "tool_result").put("tool_use_id", "call_1").put("content", new JSONArray()
                                        .put(new JSONObject().put("type", "text").put("text", "Screen of Settings: [7] switch (on)"))
                                        .put(new JSONObject().put("type", "image").put("source", new JSONObject()
                                                .put("type", "base64").put("media_type", "image/png").put("data", "AAAA"))))))));
        JSONObject r = OpenAiWire.request(req, "gpt-6.1-sol");
        eq("model", r.optString("model"), "gpt-6.1-sol");
        eq("low thinking", r.getJSONObject("reasoning").optString("effort"), "low");
        eq("output cap", r.optInt("max_output_tokens"), 2000);
        eq("not stored at OpenAI", r.optBoolean("store", true), false);
        eq("system -> instructions", r.optString("instructions"), "You are Sprocket.\n\nBe brief.");
        JSONArray in = r.getJSONArray("input");
        eq("the question", in.getJSONObject(0).toString(), "{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"turn off dark mode\"}]}");
        eq("its words, without the thinking", in.getJSONObject(1).getJSONArray("content").getJSONObject(0).optString("text"), "On it.");
        eq("its words are output text", in.getJSONObject(1).getJSONArray("content").getJSONObject(0).optString("type"), "output_text");
        eq("the tool call", in.getJSONObject(2).optString("type") + " " + in.getJSONObject(2).optString("call_id") + " "
                + in.getJSONObject(2).optString("name") + " " + in.getJSONObject(2).optString("arguments"), "function_call call_1 screen {\"action\":\"read\"}");
        eq("the tool's answer", in.getJSONObject(3).optString("type") + " " + in.getJSONObject(3).optString("output"),
                "function_call_output Screen of Settings: [7] switch (on)");
        eq("the tool's picture follows as a message", in.getJSONObject(4).getJSONArray("content").getJSONObject(1).optString("image_url"), "data:image/png;base64,AAAA");
        JSONArray tools = r.getJSONArray("tools");
        eq("Claude's search -> OpenAI's search", tools.getJSONObject(0).optString("type"), "web_search");
        eq("a phone tool, flat", tools.getJSONObject(1).optString("type") + " " + tools.getJSONObject(1).optString("name"), "function screen");
        eq("think harder", OpenAiWire.request(new JSONObject(req.toString()).put("output_config", new JSONObject().put("effort", "high")), "gpt-6.1-sol")
                .getJSONObject("reasoning").optString("effort"), "high");

        // a real answer that calls a tool
        Claude.Reply call = replay("openai-tool-call.sse");
        eq("tool call: stop reason", call.stop, "tool_use");
        eq("tool call: one call", call.calls.size(), 1);
        eq("tool call: its name", call.calls.isEmpty() ? "" : call.calls.get(0).optString("name"), "set_timer");
        eq("tool call: its arguments", call.calls.isEmpty() ? -1.0 : call.calls.get(0).getJSONObject("input").optDouble("minutes"), 5.0);
        eq("tool call: an id to answer it with", call.calls.isEmpty() ? false : call.calls.get(0).optString("id").startsWith("call_"), true);
        eq("tool call: input counted", call.input > 0, true);
        eq("tool call: output counted", call.output > 0, true);

        // a real answer in words: shown and spoken a sentence at a time
        Claude.Reply words = replay("openai-text.sse");
        eq("text: stop reason", words.stop, "end_turn");
        eq("text: hello", words.text.toLowerCase().contains("hello"), true);
        eq("text: goodbye", words.text.toLowerCase().contains("goodbye"), true);
        eq("text: spoken in sentences", sentences.size() >= 2, true);
        eq("text: it costs something", words.cost() > 0, true);

        // Codex 049 #4: text OpenAI saves to its cache is billed at its own rate, not dropped into plain input
        Claude.Stream ws = new Claude.Stream("gpt-6.1-sol", null);
        OpenAiWire.Events wev = new OpenAiWire.Events();
        for (String e : wev.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1000000,"
                + "\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":1000000},\"output_tokens\":0}}}")) ws.line(e);
        Claude.Reply wr = ws.finish();
        eq("cache writes counted as writes", wr.cacheWrite + " " + wr.input, "1000000 0");
        eq("a million written tokens cost $2.50", Math.round(wr.cost() * 100) / 100.0, 2.5);

        // Codex 050 #4: 12-hour cache writes cost twice the 30-minute ones
        Claude.Stream ls = new Claude.Stream("gpt-6.1-sol", null);
        OpenAiWire.Events lev = new OpenAiWire.Events();
        for (String e : lev.feed("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":1000000,"
                + "\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":1000000,\"cache_write_tokens_details\":{\"ttl_30m_tokens\":0,\"ttl_12h_tokens\":1000000}},\"output_tokens\":0}}}")) ls.line(e);
        eq("a million 12-hour writes cost $5.00", Math.round(ls.finish().cost() * 100) / 100.0, 5.0);
        // Codex 050 #2: a failure mid-answer is blanked of the key that request carried
        Exception leaky = new Exception("OpenAI: Incorrect API key provided: sk-proj-OldKeyThatWasReplaced123");
        eq("a request's error is blanked of its own key", Claude.scrubbed(leaky, "sk-proj-OldKeyThatWasReplaced123").getMessage(), "OpenAI: Incorrect API key provided: [your key]");
        Claude.Busy busy = new Claude.Busy("busy sk-proj-OldKeyThatWasReplaced123", "3", false);
        Exception b2 = Claude.scrubbed(busy, "sk-proj-OldKeyThatWasReplaced123");
        eq("a busy answer stays busy (retries still work)", b2 instanceof Claude.Busy && ((Claude.Busy) b2).retryAfter.equals("3"), true);
        eq("no key in it: the same failure, untouched", Claude.scrubbed(leaky, "something-else-entirely") == leaky, true);

        System.out.println("openai wire: " + (fails == 0 ? "all " + n + " checks pass" : fails + " of " + n + " failed"));
        System.exit(fails == 0 ? 0 : 1);
    }
}
