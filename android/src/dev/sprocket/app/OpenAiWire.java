package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI as a brain (Chris 11-15: "so nobody with an openai account can use sprocket?"). Sprocket speaks Anthropic's
 * Messages API; OpenAI's newer models only take tools with thinking on its Responses API (chat completions refuse:
 * "Function tools with reasoning_effort are not supported"). So this turns Sprocket's request into a Responses
 * request, and OpenAI's streamed answer back into the Messages stream events Claude.Stream already reads: the voice,
 * the tool rounds, the sources and the cost work as they do for Claude. Plain Java, for the desktop tests.
 * The same translation passed Sprocket's practice set through the go-between (53 of 62) and the tablet's dark-mode test.
 */
final class OpenAiWire {
    private OpenAiWire() { }

    /** A Messages request as a Responses request for this model. */
    static JSONObject request(JSONObject req, String model) throws Exception {
        JSONObject out = new JSONObject().put("model", model).put("stream", true).put("store", false);
        String instructions = text(req.opt("system"));
        if (!instructions.isEmpty()) out.put("instructions", instructions);
        JSONArray input = new JSONArray();
        JSONArray messages = req.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = m.optString("role");
            Object content = m.opt("content");
            if (content instanceof String) {
                if (!((String) content).isEmpty())
                    input.put(new JSONObject().put("role", role).put("content", new JSONArray().put(part(role, (String) content))));
                continue;
            }
            JSONArray blocks = m.optJSONArray("content");
            JSONArray parts = new JSONArray();
            List<String> pictures = new ArrayList<>();          // pictures a tool sent back: OpenAI gets them as a message
            for (int j = 0; blocks != null && j < blocks.length(); j++) {
                JSONObject b = blocks.getJSONObject(j);
                switch (b.optString("type")) {
                    case "text":
                        if (!b.optString("text").isEmpty()) parts.put(part(role, b.optString("text")));
                        break;
                    case "image":
                        if (!role.equals("assistant")) parts.put(new JSONObject().put("type", "input_image").put("image_url", dataUrl(b)));
                        break;
                    case "tool_use":
                        flush(input, role, parts);
                        parts = new JSONArray();
                        input.put(new JSONObject().put("type", "function_call").put("call_id", b.optString("id"))
                                .put("name", b.optString("name")).put("arguments", String.valueOf(b.optJSONObject("input") == null ? new JSONObject() : b.getJSONObject("input"))));
                        break;
                    case "tool_result": {
                        flush(input, role, parts);
                        parts = new JSONArray();
                        Object c = b.opt("content");
                        StringBuilder said = new StringBuilder();
                        if (c instanceof String) said.append((String) c);
                        else if (c instanceof JSONArray) {
                            JSONArray cs = (JSONArray) c;
                            for (int k = 0; k < cs.length(); k++) {
                                JSONObject x = cs.getJSONObject(k);
                                if (x.optString("type").equals("text")) said.append(said.length() > 0 ? "\n" : "").append(x.optString("text"));
                                else if (x.optString("type").equals("image")) pictures.add(dataUrl(x));
                            }
                        }
                        input.put(new JSONObject().put("type", "function_call_output").put("call_id", b.optString("tool_use_id"))
                                .put("output", said.length() == 0 ? "(done)" : said.toString()));
                        break;
                    }
                    default:   // thinking, signatures, Claude's own search blocks: nothing OpenAI can use
                }
            }
            flush(input, role, parts);
            if (!pictures.isEmpty()) {
                JSONArray pic = new JSONArray().put(new JSONObject().put("type", "input_text").put("text", "(the picture from that tool)"));
                for (String p : pictures) pic.put(new JSONObject().put("type", "input_image").put("image_url", p));
                input.put(new JSONObject().put("role", "user").put("content", pic));
            }
        }
        out.put("input", input);
        JSONArray tools = new JSONArray();
        JSONArray theirs = req.optJSONArray("tools");
        for (int i = 0; theirs != null && i < theirs.length(); i++) {
            JSONObject t = theirs.getJSONObject(i);
            if (t.optString("type").startsWith("web_search")) { tools.put(new JSONObject().put("type", "web_search")); continue; }
            if (t.has("type") && !t.optString("type").equals("custom")) continue;   // another server tool: none on OpenAI
            tools.put(new JSONObject().put("type", "function").put("name", t.optString("name"))
                    .put("description", t.optString("description"))
                    .put("parameters", t.optJSONObject("input_schema") == null ? new JSONObject().put("type", "object").put("properties", new JSONObject()) : t.getJSONObject("input_schema")));
        }
        if (tools.length() > 0) out.put("tools", tools);
        JSONObject oc = req.optJSONObject("output_config");
        out.put("reasoning", new JSONObject().put("effort", oc == null ? "low" : oc.optString("effort", "low")));
        if (req.has("max_tokens")) out.put("max_output_tokens", req.optInt("max_tokens"));
        return out;
    }

    private static JSONObject part(String role, String text) throws Exception {
        return new JSONObject().put("type", role.equals("assistant") ? "output_text" : "input_text").put("text", text);
    }

    private static void flush(JSONArray input, String role, JSONArray parts) throws Exception {
        if (parts.length() > 0) input.put(new JSONObject().put("role", role).put("content", parts));
    }

    private static String dataUrl(JSONObject image) {
        JSONObject src = image.optJSONObject("source");
        if (src == null) return "";
        if (src.optString("type").equals("url")) return src.optString("url");
        return "data:" + src.optString("media_type", "image/jpeg") + ";base64," + src.optString("data");
    }

    private static String text(Object system) {
        if (system instanceof String) return (String) system;
        if (!(system instanceof JSONArray)) return "";
        StringBuilder b = new StringBuilder();
        JSONArray a = (JSONArray) system;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && !x.optString("text").isEmpty()) b.append(b.length() > 0 ? "\n\n" : "").append(x.optString("text"));
        }
        return b.toString();
    }

    /**
     * OpenAI's stream, one line at a time, as Messages stream lines ("data: {...}") for Claude.Stream. Text becomes a
     * text block; each function call a tool_use block with its arguments; a web search a server_tool_use block; a link
     * it cites a citation; the finish its stop reason and usage (input without the cached part, as Anthropic counts it).
     */
    static final class Events {
        private int index = -1;
        private boolean textOpen;
        private final Map<Integer, Integer> callBlock = new java.util.HashMap<>();   // OpenAI output_index -> block index
        private boolean calls;
        private String event = "";

        List<String> feed(String raw) throws Exception {
            List<String> out = new ArrayList<>();
            String line = raw.trim();
            if (line.startsWith("event:")) { event = line.substring(6).trim(); return out; }
            if (!line.startsWith("data:")) return out;
            String body = line.substring(5).trim();
            if (body.isEmpty() || body.equals("[DONE]")) return out;
            JSONObject d = new JSONObject(body);
            String t = d.optString("type", event);
            switch (t) {
                case "response.output_text.delta": {
                    if (!textOpen) {
                        index++;
                        textOpen = true;
                        out.add(ev(new JSONObject().put("type", "content_block_start").put("index", index)
                                .put("content_block", new JSONObject().put("type", "text").put("text", ""))));
                    }
                    out.add(ev(new JSONObject().put("type", "content_block_delta").put("index", index)
                            .put("delta", new JSONObject().put("type", "text_delta").put("text", d.optString("delta")))));
                    break;
                }
                case "response.output_text.annotation.added": {
                    JSONObject a = d.optJSONObject("annotation");
                    if (textOpen && a != null && !a.optString("url").isEmpty())
                        out.add(ev(new JSONObject().put("type", "content_block_delta").put("index", index)
                                .put("delta", new JSONObject().put("type", "citations_delta")
                                        .put("citation", new JSONObject().put("type", "web_search_result_location").put("url", a.optString("url"))
                                                .put("title", a.optString("title"))))));
                    break;
                }
                case "response.output_item.added": {
                    JSONObject item = d.optJSONObject("item");
                    String kind = item == null ? "" : item.optString("type");
                    if (kind.equals("function_call")) {
                        closeText(out);
                        index++;
                        calls = true;
                        callBlock.put(d.optInt("output_index"), index);
                        out.add(ev(new JSONObject().put("type", "content_block_start").put("index", index)
                                .put("content_block", new JSONObject().put("type", "tool_use").put("id", item.optString("call_id"))
                                        .put("name", item.optString("name")).put("input", new JSONObject()))));
                    } else if (kind.equals("web_search_call")) {
                        closeText(out);
                        index++;
                        out.add(ev(new JSONObject().put("type", "content_block_start").put("index", index)
                                .put("content_block", new JSONObject().put("type", "server_tool_use").put("id", item.optString("id"))
                                        .put("name", "web_search").put("input", new JSONObject()))));
                        out.add(ev(new JSONObject().put("type", "content_block_stop").put("index", index)));
                    } else if (kind.equals("message")) {
                        closeText(out);   // a new message after a search: its own text block
                    }
                    break;
                }
                case "response.function_call_arguments.delta": {
                    Integer b = callBlock.get(d.optInt("output_index"));
                    if (b != null)
                        out.add(ev(new JSONObject().put("type", "content_block_delta").put("index", b)
                                .put("delta", new JSONObject().put("type", "input_json_delta").put("partial_json", d.optString("delta")))));
                    break;
                }
                case "response.output_item.done": {
                    Integer b = callBlock.remove(d.optInt("output_index"));
                    if (b != null) out.add(ev(new JSONObject().put("type", "content_block_stop").put("index", b)));
                    break;
                }
                case "response.completed":
                case "response.incomplete": {
                    closeText(out);
                    for (Integer b : callBlock.values()) out.add(ev(new JSONObject().put("type", "content_block_stop").put("index", b)));
                    callBlock.clear();
                    JSONObject r = d.optJSONObject("response") == null ? new JSONObject() : d.getJSONObject("response");
                    JSONObject u = r.optJSONObject("usage") == null ? new JSONObject() : r.getJSONObject("usage");
                    JSONObject det = u.optJSONObject("input_tokens_details") == null ? new JSONObject() : u.getJSONObject("input_tokens_details");
                    int in = u.optInt("input_tokens"), cached = det.optInt("cached_tokens"), written = det.optInt("cache_write_tokens");   // Codex 049 #4
                    JSONObject ttl = det.optJSONObject("cache_write_tokens_details");
                    int longer = ttl == null ? 0 : ttl.optInt("ttl_12h_tokens");   // 12-hour writes, at their own rate (Codex 050 #4)
                    boolean cut = r.optString("status").equals("incomplete") && r.optJSONObject("incomplete_details") != null
                            && r.getJSONObject("incomplete_details").optString("reason").equals("max_output_tokens");
                    out.add(ev(new JSONObject().put("type", "message_start").put("message", new JSONObject().put("usage",
                            new JSONObject().put("input_tokens", Math.max(0, in - cached - written)).put("cache_read_input_tokens", cached)
                                    .put("cache_creation_input_tokens", written)
                                    .put("cache_creation", new JSONObject().put("ephemeral_5m_input_tokens", Math.max(0, written - longer))
                                            .put("ephemeral_1h_input_tokens", longer))))));
                    out.add(ev(new JSONObject().put("type", "message_delta")
                            .put("delta", new JSONObject().put("stop_reason", cut ? "max_tokens" : calls ? "tool_use" : "end_turn"))
                            .put("usage", new JSONObject().put("output_tokens", u.optInt("output_tokens")))));
                    out.add(ev(new JSONObject().put("type", "message_stop")));
                    break;
                }
                case "response.failed":
                case "error": {
                    JSONObject er = d.optJSONObject("response") != null ? d.getJSONObject("response").optJSONObject("error") : d.optJSONObject("error");
                    String msg = er == null ? d.optString("message", "an error") : er.optString("message", "an error");
                    String code = er == null ? d.optString("code") : er.optString("code");
                    out.add(ev(new JSONObject().put("type", "error").put("error", new JSONObject()
                            .put("type", code.contains("rate") || code.contains("overloaded") || code.contains("server_is_overloaded") ? "overloaded_error" : "api_error")
                            .put("message", "OpenAI: " + msg))));
                    break;
                }
                default:
            }
            return out;
        }

        private void closeText(List<String> out) throws Exception {
            if (textOpen) {
                out.add(ev(new JSONObject().put("type", "content_block_stop").put("index", index)));
                textOpen = false;
            }
        }

        private static String ev(JSONObject e) { return "data: " + e; }
    }
}
