package dev.sprocket.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Matching a name to his contacts by how it SOUNDS -- copied from the earlier test app .
 * Sprocket 2026-10-17 (the owner: "I told it to text Toni, it comes up as Tony and can't find the contact"): a name
 * typed, dictated or retold by Claude in another spelling still finds the contact when one clearly fits. Plain Java,
 * tested on the desktop by android/test/NameMatchTest.java.
 *
 * Each name gets a sound key: letters that sound alike are merged (ph/v -> f, c/q/ck -> k, z -> s, a silent h or
 * final e dropped) and each run of vowels becomes the one vowel it sounds like (ee/ie/ea/ei/y -> i, oo/ou -> u,
 * au -> o, else its first letter) -- so Dee, Dea and Dhee all key to "di", Jon and John to "jon", Steven and
 * Stephen to "stefen", while Jen and Jon stay apart (in a short name the vowel IS the difference). A heard name is
 * scored against each contact's first name and full name: same spelling 1.0, a spelling prefix 0.95, the same sound
 * key 0.9, else by how close the keys are.
 */
final class NameMatch {
    private NameMatch() { }

    /** The outcome: one clear contact (`unique`), two close ones to ask about (`choices`), or nothing close. */
    static final class Result {
        String unique;                         // the contact's name to use
        final List<String> choices = new ArrayList<>();   // ask "Did you mean A or B?"
        double score;
    }

    static String key(String name) {
        String s = name.toLowerCase(Locale.US).replaceAll("[^a-z]", "");
        if (s.isEmpty()) return "";
        s = s.replaceAll("^kn", "n").replaceAll("^wr", "r").replaceAll("^wh", "w")
                .replace("ph", "f").replace("ck", "k").replace("dg", "j").replace("gh", "g")
                .replaceAll("c(?=[eiy])", "s").replace('c', 'k').replace('q', 'k').replace("x", "ks")
                .replace('z', 's').replace('v', 'f');
        s = s.replaceAll("(?<=[^cstp])h", "");          // a silent h (John, Sarah); keep sh / th / ch
        s = s.replaceAll("(?<=.)y", "i");               // Katy = Katie (y as a vowel)
        if (s.length() > 2 && s.matches(".*[^aeiou]e")) s = s.substring(0, s.length() - 1);   // a silent final e: Jane
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[aeiou]+").matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {   // each run of vowels -> the one it sounds like
            String g = m.group();
            String v = g.matches("ee|ie|ea|ei|i.*") ? "i" : g.matches("oo|ou") ? "u" : g.equals("au") ? "o" : g.substring(0, 1);
            m.appendReplacement(out, v);
        }
        m.appendTail(out);
        return out.toString().replaceAll("(.)\\1+", "$1");   // doubled letters
    }

    static int distance(String a, String b) {
        int[] d = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) d[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = d[0];
            d[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cur = Math.min(Math.min(d[j] + 1, d[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                prev = d[j];
                d[j] = cur;
            }
        }
        return d[b.length()];
    }

    /** How well a heard name fits one contact name (0..1), against its first name and the whole name. */
    static double score(String heard, String contact) {
        String h = heard.toLowerCase(Locale.US).replaceAll("^(my|the)\\s+", "").trim();
        String c = contact.toLowerCase(Locale.US).trim();
        if (h.isEmpty() || c.isEmpty()) return 0;
        String first = c.split("\\s+")[0];
        if (h.equals(c) || h.equals(first)) return 1.0;
        if (c.startsWith(h + " ") || (h.length() >= 3 && first.startsWith(h))) return 0.95;
        double best = 0;
        for (String cand : new String[]{first, c}) {
            String kh = key(h), kc = key(cand);
            if (kh.isEmpty() || kc.isEmpty()) continue;
            if (kh.equals(kc)) { best = Math.max(best, 0.9); continue; }
            // 0.62 (the owner: "text Kris" -> "Text crisp"): a name the recognizer doesn't know becomes the
            // nearest real word -- the name with one consonant stuck on the end. That is still this contact.
            if (kc.length() >= 3 && kh.length() == kc.length() + 1 && kh.startsWith(kc)
                    && "aeiou".indexOf(kh.charAt(kh.length() - 1)) < 0) { best = Math.max(best, 0.85); continue; }
            double s = 1.0 - (double) distance(kh, kc) / Math.max(kh.length(), kc.length());
            best = Math.max(best, 0.8 * s);
        }
        return best;
    }

    /** The heard name against all his contacts. */
    static Result match(String heard, List<String> contacts) {
        Result r = new Result();
        String best = null, second = null;
        double b = 0, s2 = 0;
        for (String c : contacts) {
            double v = score(heard, c);
            if (v > b) { second = best; s2 = b; best = c; b = v; }
            else if (v > s2 && !c.equals(best)) { second = c; s2 = v; }
        }
        r.score = b;
        if (best == null || b < 0.6) return r;                    // nothing close: use the words as heard
        if (b >= 0.85 && s2 < b - 0.1) { r.unique = best; return r; }   // one clear winner
        r.choices.add(best);                                       // two close ones: ask
        if (second != null && s2 >= 0.6) r.choices.add(second);
        if (r.choices.size() == 1 && b >= 0.85) { r.unique = best; r.choices.clear(); }
        return r;
    }
}
