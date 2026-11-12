package dev.sprocket.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The little markdown a research brief uses, read into blocks for the chat to draw (phase 4 research): a title (#),
 * headings (##, ###), bullets (- or *), tables (| a | b |, the dashes line dropped) and paragraphs; inside a line,
 * **bold** and [link](url). Nothing else. Plain Java: test/ResearchTest.java.
 */
final class Markdown {
    private Markdown() { }

    static final class Block {
        final String kind;               // "title", "heading", "bullet", "paragraph", "rows" (a table)
        final String text;
        final List<List<String>> rows;

        Block(String kind, String text, List<List<String>> rows) { this.kind = kind; this.text = text; this.rows = rows; }
    }

    static final class Span {
        final String text, url;          // url: a link (null otherwise)
        final boolean bold;

        Span(String text, boolean bold, String url) { this.text = text; this.bold = bold; this.url = url; }
    }

    static List<Block> parse(String md) {
        List<Block> out = new ArrayList<>();
        List<List<String>> table = null;
        StringBuilder para = new StringBuilder();
        for (String raw : (md == null ? "" : md).split("\n")) {
            String line = raw.trim();
            boolean row = line.startsWith("|") && line.endsWith("|") && line.length() > 1;
            if (!row && table != null) { out.add(new Block("rows", "", table)); table = null; }
            if (row) {
                flush(out, para);
                if (line.replaceAll("[|:\\-\\s]", "").isEmpty()) continue;   // the |---|---| line
                if (table == null) table = new ArrayList<>();
                List<String> cells = new ArrayList<>();
                for (String c : line.substring(1, line.length() - 1).split("\\|", -1)) cells.add(c.trim());
                table.add(cells);
            } else if (line.isEmpty()) {
                flush(out, para);
            } else if (line.startsWith("# ")) {
                flush(out, para);
                out.add(new Block("title", line.substring(2).trim(), null));
            } else if (line.startsWith("## ") || line.startsWith("### ")) {
                flush(out, para);
                out.add(new Block("heading", line.replaceFirst("^#+\\s*", ""), null));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                flush(out, para);
                out.add(new Block("bullet", line.substring(2).trim(), null));
            } else {
                para.append(para.length() > 0 ? " " : "").append(line);
            }
        }
        if (table != null) out.add(new Block("rows", "", table));
        flush(out, para);
        return out;
    }

    private static void flush(List<Block> out, StringBuilder para) {
        if (para.length() > 0) out.add(new Block("paragraph", para.toString(), null));
        para.setLength(0);
    }

    private static final Pattern INLINE = Pattern.compile("\\*\\*(.+?)\\*\\*|\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)");

    /** A line's pieces: plain, **bold**, or [a link](url). */
    static List<Span> spans(String text) {
        List<Span> out = new ArrayList<>();
        Matcher m = INLINE.matcher(text == null ? "" : text);
        int at = 0;
        while (m.find()) {
            if (m.start() > at) out.add(new Span(text.substring(at, m.start()), false, null));
            if (m.group(1) != null) out.add(new Span(m.group(1), true, null));
            else out.add(new Span(m.group(2), false, m.group(3)));
            at = m.end();
        }
        if (at < (text == null ? 0 : text.length())) out.add(new Span(text.substring(at), false, null));
        return out;
    }
}
