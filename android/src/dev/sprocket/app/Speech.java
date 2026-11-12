package dev.sprocket.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text made ready for the voice. Sprocket SHOUTS in capitals, and a phone voice spells short capitalised words out
 * letter by letter ("W-A-R"), so shouted words go back to lower case unless they are real abbreviations; markdown
 * marks and emoji are dropped, and card names are said the way he says them. Plain Java: test/SpeechTest.java. (Phase 4 replaces the phone voice with his own.)
 */
final class Speech {
    private Speech() { }

    /** Abbreviations that should still be spelled out. */
    static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "AI", "API", "CEO", "CPU", "DNS", "ETA", "EU", "FAQ", "FBI", "GPS", "GPU", "GTX", "HDD", "HTML", "IP",
            "JSON", "LED", "LLM", "NASA", "NPU", "OK", "OS", "PC", "PDF", "RAM", "RTX", "SQL", "SSD", "TV", "UK", "URL", "AM", "PM",
            "US", "USA", "USB", "VPN"));

    private static final Pattern SHOUT = Pattern.compile("\\b[A-Z]{2,}\\b");
    // card names as he says them (the owner 2026-10-17: the voice said "v 1 new new" and "four thousand and seventy"):
    // V100 = "V one hundred"; a 4070 = "forty seventy", after RTX/GTX for any series, bare only for 10/30/40/50 cards
    private static final Pattern HUNDRED = Pattern.compile("\\b([VAH])100\\b");
    private static final Pattern BRANDED = Pattern.compile("\\b(RTX|GTX) ?([1-5]0)([5-9]0)\\b");
    private static final Pattern BARE = Pattern.compile("(?<![\\d.,])(10|30|40|50)([5-9]0)(?!\\d|[.,]\\d)");
    private static final String[] TENS = {"", "ten", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};

    // clock times as words (his session 2026-10-17: "6:30 AM" came out "six, three zero AM"): 6:30 AM = "six thirty
    // AM", 9:05 = "nine oh five", 6:00 = "six o'clock", 18:45 = "six forty five PM"; "a.m." ending a sentence keeps
    // its full stop
    private static final Pattern CLOCK = Pattern.compile("(?<![\\d:.])([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])(\\s*([AaPp])\\.?\\s?[Mm](?:\\.(?=\\s+[a-z]))?(?![a-zA-Z]))?");
    private static final String[] ONES = {"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
            "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};

    private static String upTo59(int n) {
        return n < 20 ? ONES[n] : TENS[n / 10] + (n % 10 == 0 ? "" : " " + ONES[n % 10]);
    }

    private static String clocks(String t) {
        Matcher m = CLOCK.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
            String half = m.group(4) == null ? null : m.group(4).equalsIgnoreCase("a") ? "AM" : "PM";
            if (half == null && (h > 12 || h == 0)) half = h >= 12 ? "PM" : "AM";      // 24-hour
            int h12 = h % 12 == 0 ? 12 : h % 12;
            String words = ONES[h12] + (min == 0 ? (half == null ? " o'clock" : "") : min < 10 ? " oh " + ONES[min] : " " + upTo59(min));
            m.appendReplacement(out, Matcher.quoteReplacement(words + (half == null ? "" : " " + half)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String pair(String a, String b) { return TENS[a.charAt(0) - '0'] + " " + TENS[b.charAt(0) - '0']; }

    private static String cards(String t) {
        t = HUNDRED.matcher(t).replaceAll("$1 one hundred");
        Matcher m = BRANDED.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, m.group(1) + " " + pair(m.group(2), m.group(3)));
        m.appendTail(out);
        m = BARE.matcher(out.toString());
        out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, pair(m.group(1), m.group(2)));
        m.appendTail(out);
        return out.toString();
    }
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");

    static String forVoice(String s) {
        String t = EMOJI.matcher(s).replaceAll("");
        t = t.replaceAll("[*_#`~>|]", "").replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        Matcher m = SHOUT.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String w = m.group();
            m.appendReplacement(out, KEEP.contains(w) ? w : w.toLowerCase(Locale.US));
        }
        m.appendTail(out);
        return clocks(cards(out.toString())).replaceAll("\\s+", " ").trim();
    }
}
