package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Research on the desktop JDK: the request, a shared article, the brief's markdown, the spoken line. No network. */
public class ResearchTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        // what he shared: Chrome sends the title and the link together, some apps only the link
        Research.Shared s = Research.shared("Why V100s still matter", "Why V100s still matter https://example.com/v100?utm_source=x");
        eq("a shared article: its link", s.url, "https://example.com/v100?utm_source=x");
        eq("and its title", s.title, "Why V100s still matter");
        eq("a bare link: the link is the title too", Research.shared(null, "https://example.com/a").title, "https://example.com/a");
        eq("no link: what he shared is the question", Research.shared(null, "is fp8 safe for fine-tuned models").url + "|"
                + Research.shared(null, "is fp8 safe for fine-tuned models").title, "|is fp8 safe for fine-tuned models");

        JSONObject r = Research.request("Is this right?", "https://example.com/v100", "Saturday, October 17, 2026");
        eq("Opus, as in his sample", r.getString("model"), Claude.OPUS);
        eq("room for a long brief", r.getInt("max_tokens"), 20000);
        eq("streamed", r.getBoolean("stream"), true);
        JSONArray tools = r.getJSONArray("tools");
        eq("search and fetch, the newest versions, called directly",
                tools.getJSONObject(0).getString("type") + " " + tools.getJSONObject(0).getJSONArray("allowed_callers") + " "
                        + tools.getJSONObject(1).getString("type") + " " + tools.getJSONObject(1).getJSONArray("allowed_callers"),
                "web_search_20260318 [\"direct\"] web_fetch_20260318 [\"direct\"]");
        eq("fetched pages cited, and capped", tools.getJSONObject(1).getJSONObject("citations").getBoolean("enabled") + " "
                + tools.getJSONObject(1).getInt("max_content_tokens"), "true 30000");
        String user = r.getJSONArray("messages").getJSONObject(0).getString("content");
        eq("the link is in his message (web fetch only opens links he gave)", user.contains("https://example.com/v100"), true);
        String sys = r.getString("system");
        eq("his sample's brief: headings, table, takeaways, domain links", sys.contains("## headings") && sys.contains("comparison table")
                && sys.contains("Takeaways") && sys.contains("just the domain as the link text"), true);
        eq("the article first, its claims checked", sys.contains("read it first"), true);
        eq("a plain line to say aloud at the end", sys.contains("SAY:"), true);
        eq("no link: a question only", Research.request("Is fp8 safe?", "", "today").getJSONArray("messages").getJSONObject(0)
                .getString("content").contains("http"), false);

        String brief = "# V100s in 2026\n\nIntro line with **bold** and [nvidia.com](https://nvidia.com/x).\n\n## Speed\n"
                + "- fp16 is fast\n- bf16 is **slow**\n\n| Card | fp16 | bf16 |\n|---|---|---|\n| V100 | 125 | emulated |\n| 4070 | 165 | 165 |\n\n"
                + "## Takeaways\n- one\n- two\n- three\nSAY: The V100 is still fast in fp16, but avoid bf16.";
        Research.Brief b = Research.brief(brief);
        eq("the spoken line comes off the brief", b.say, "The V100 is still fast in fp16, but avoid bf16.");
        eq("and is not left in it", b.markdown.contains("SAY:"), false);
        eq("its title", b.title, "V100s in 2026");
        List<Markdown.Block> blocks = Markdown.parse(b.markdown);
        StringBuilder kinds = new StringBuilder();
        for (Markdown.Block k : blocks) kinds.append(k.kind.charAt(0));
        eq("blocks: title, paragraph, heading, 2 bullets, a table, heading, 3 bullets", kinds, "tphbbrhbbb");
        Markdown.Block table = blocks.get(5);
        eq("the table: rows of cells, the dashes line dropped", table.rows.size() + " " + table.rows.get(0) + " " + table.rows.get(2),
                "3 [Card, fp16, bf16] [4070, 165, 165]");
        List<Markdown.Span> spans = Markdown.spans(blocks.get(1).text);
        StringBuilder sp = new StringBuilder();
        for (Markdown.Span x : spans) sp.append(x.bold ? "B:" : x.url != null ? "L(" + x.url + "):" : "").append(x.text).append('|');
        eq("bold and links inside a line", sp, "Intro line with |B:bold| and |L(https://nvidia.com/x):nvidia.com|.|");
        eq("no SAY line: nothing to say, brief as is", Research.brief("# T\n- a").say + "|" + Research.brief("# T\n- a").markdown, "|# T\n- a");
        eq("a headline for the notification", Research.brief("no title here\n- a").title, "Research brief");

        System.out.println(fails == 0 ? "research: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
