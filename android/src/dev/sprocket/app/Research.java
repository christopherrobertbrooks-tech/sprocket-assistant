package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Research on the phone (the owner 2026-10-17: "so if I see an article I think is interesting Sprocket could research";
 * started by sharing it to Sprocket or asking; Opus, as in the sample he pasted; keeps going in the background).
 * The research agent is his sample's: sub-questions, a targeted search each (primary sources first), a scannable
 * brief with ## headings, a comparison table and three takeaways, domain-only source links. Added: a shared article
 * is read first and its claims checked; the brief ends with one plain line Sprocket says aloud. Search and fetch are
 * Claude's own server tools, called directly (no code sandbox); web fetch opens only links that are in his message.
 * Plain Java: test/ResearchTest.java.
 */
final class Research {
    private Research() { }

    static final int MAX_TOKENS = 20000;

    static final String SYSTEM = "You are a research agent. Given a question:\n\n"
            + "0. If an article link is given, read it first with web_fetch: it is what he saw. Treat its claims as claims "
            + "to check, not as facts.\n"
            + "1. Break it into 2-3 sub-questions that cover the topic.\n"
            + "2. Run a targeted web search for each. Prefer primary sources and official docs over blog posts.\n"
            + "3. Write a brief that answers the original question directly.\n\n"
            + "The brief should be scannable markdown, not an essay. Start with a one-line title as '# Title'. Use ## headings, "
            + "short bullets, and a comparison table where you're contrasting options (at most 4 columns: it is read on a "
            + "phone). Aim for under 500 words of prose -- put detail in the table, not in paragraphs. Close with a 3-bullet "
            + "\"Takeaways\" section.\n\n"
            + "Cite claims inline with a short link -- just the domain as the link text, e.g. [anthropic.com](https://...). "
            + "Never use a sentence or page title as link text. If sources conflict, say which you trust and move on.\n\n"
            + "After the Takeaways, add one last line starting with 'SAY: ' and one plain sentence -- the single most "
            + "important finding -- to be read aloud. No markdown on that line.";

    /** Sprocket's tool for it: a research job in the background, its brief in the chat a minute or two later. */
    static JSONObject definition() throws Exception {
        return new JSONObject().put("name", "research")
                .put("description", "Start a deep research job on Opus (1-3 minutes, about $0.25-0.50) that ends in a written "
                        + "brief in the chat, with sources. Use it when the boss asks to research, dig into or look into "
                        + "something in depth, or to check an article. Not for quick facts: use web search for those. It "
                        + "runs in the background; say you're on it, don't wait for it.")
                .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("question", new JSONObject().put("type", "string").put("description", "What to find out, in full."))
                        .put("url", new JSONObject().put("type", "string").put("description",
                                "An article link he gave, if the research is about it.")))
                        .put("required", new JSONArray().put("question")));
    }

    /** What he shared: a link (the first one) and a title, or just words (then they are the question). */
    static final class Shared {
        final String url, title;

        Shared(String url, String title) { this.url = url; this.title = title; }
    }

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    static Shared shared(String subject, String text) {
        String t = text == null ? "" : text.trim();
        Matcher m = URL.matcher(t);
        if (!m.find()) return new Shared("", t.isEmpty() ? (subject == null ? "" : subject.trim()) : t);
        String url = m.group().replaceAll("[)\\].,;]+$", "");
        String rest = (t.substring(0, m.start()) + t.substring(m.end())).trim();
        String title = subject != null && !subject.trim().isEmpty() ? subject.trim() : rest.isEmpty() ? url : rest;
        return new Shared(url, title);
    }

    /** The Messages API body for one research job. */
    static JSONObject request(String question, String url, String today) throws Exception {
        StringBuilder ask = new StringBuilder();
        if (url != null && !url.isEmpty())
            ask.append("Article: ").append(url).append("\n\n").append(question == null || question.isEmpty()
                    ? "Research this article: what it claims, whether that holds up, and what matters about it." : question);
        else ask.append(question);
        ask.append("\n\n(Today is ").append(today).append(".)");
        JSONArray direct = new JSONArray().put("direct");
        JSONArray tools = new JSONArray()
                .put(new JSONObject().put("type", "web_search_20260318").put("name", "web_search").put("allowed_callers", direct)
                        .put("max_uses", 8))
                .put(new JSONObject().put("type", "web_fetch_20260318").put("name", "web_fetch").put("allowed_callers", new JSONArray().put("direct"))
                        .put("max_uses", 5).put("citations", new JSONObject().put("enabled", true)).put("max_content_tokens", 30000));
        return new JSONObject().put("model", Claude.OPUS).put("max_tokens", MAX_TOKENS).put("stream", true)
                .put("system", SYSTEM)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", ask.toString())))
                .put("tools", tools);
    }

    /** A finished brief: its markdown (the SAY line taken off), its title, and the line to say aloud. */
    static final class Brief {
        final String markdown, title, say;

        Brief(String markdown, String title, String say) { this.markdown = markdown; this.title = title; this.say = say; }
    }

    static Brief brief(String text) {
        String t = text == null ? "" : text.trim();
        String say = "";
        Matcher m = Pattern.compile("(?m)^\\s*SAY:\\s*(.+?)\\s*$").matcher(t);
        int at = -1;
        while (m.find()) { say = m.group(1).replaceAll("[*_#`]", "").trim(); at = m.start(); }
        if (at >= 0) t = t.substring(0, at).trim();
        Matcher h = Pattern.compile("(?m)^#\\s+(.+)$").matcher(t);
        String title = h.find() ? h.group(1).trim() : "Research brief";
        return new Brief(t, title, say);
    }
}
