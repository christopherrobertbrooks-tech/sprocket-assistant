package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What Sprocket remembers about him (phase 3b, the owner 2026-10-17: when asked, plus lasting facts he mentions; every
 * save shown in the chat; all of them listed in the ⋯ menu to delete). Kept on the phone (files/memory.json).
 * Bigger memory (the owner 2026-11-13): thousands of notes. Always-on ones go in the system prompt, after the persona (its
 * own cache mark, so it stays cached); the rest go with his message only when their words fit what he asked, so a
 * long memory neither crowds every question nor changes the cached part. Plain Java: test/MemoryTest.java.
 */
final class Memory {
    static final int MAX = 2000, LONGEST = 300, PER_QUESTION = 12;

    static final class Note {
        final long id;
        final String text;
        boolean always;                          // goes with every message (pinned), not only when it fits

        Note(long id, String text, boolean always) { this.id = id; this.text = text; this.always = always; }
    }

    final List<Note> notes = new ArrayList<>();
    private long nextId = 1;

    private static String norm(String s) { return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim(); }

    boolean add(String text, long at) { return add(text, at, false); }

    /** A new note (false if he already has it). The oldest go once there are MAX. */
    boolean add(String text, long at, boolean always) {
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return false;
        if (t.length() > LONGEST) t = t.substring(0, LONGEST);
        for (Note n : notes) if (norm(n.text).equals(norm(t))) return false;
        long id = Math.max(nextId, at);              // unique, and rising: a note's id is when it was made, or after
        nextId = id + 1;
        notes.add(new Note(id, t, always));
        while (notes.size() > MAX) notes.remove(0);
        return true;
    }

    private static final Set<String> SMALL = new HashSet<>(Arrays.asList("a", "an", "the", "his", "her", "he", "she", "is",
            "are", "was", "my", "i", "me", "to", "of", "and", "or", "that", "about", "on", "in", "it", "for", "with", "be"));

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) if (!w.isEmpty() && !SMALL.contains(w)) out.add(w);
        return out;
    }

    /** Forgets the note(s) that best fit his words (most of their meaningful words in it); returns what went. */
    List<String> forget(String about) {
        List<String> want = words(about), gone = new ArrayList<>();
        if (want.isEmpty()) return gone;
        double best = 0;
        double[] score = new double[notes.size()];
        for (int i = 0; i < notes.size(); i++) {
            List<String> have = words(notes.get(i).text);
            int hit = 0;
            for (String w : want) if (have.contains(w)) hit++;
            score[i] = (double) hit / want.size();
            best = Math.max(best, score[i]);
        }
        if (best < 0.6) return gone;
        for (int i = notes.size() - 1; i >= 0; i--)
            if (score[i] == best && gone.size() < 3) gone.add(0, notes.remove(i).text);
        return gone;
    }

    /** His delete, from the Memory screen. */
    boolean delete(long id) { return notes.removeIf(n -> n.id == id); }

    /** His pin, from the Memory screen: always-on or only when it fits. */
    boolean setAlways(long id, boolean always) {
        for (Note n : notes) if (n.id == id) { n.always = always; return true; }
        return false;
    }

    /** The system prompt's notes block: the always-on notes, then the same closing line every time, even with no notes
     * yet (so a plain note, added or deleted, or the first one ever, never changes this cached block: 11-13, each change
     * made Qwen re-read all 13,700 tokens of instructions). */
    String forClaude() {
        StringBuilder b = new StringBuilder("What you remember about the boss (they can see and delete these notes in the app; "
                + "the notes speak to them as \"you\"):");
        for (Note n : notes) if (n.always) b.append("\n- ").append(n.text);
        return b.append("\nOther notes you saved come with their message when they fit what they ask.").toString();
    }

    /** A word without its ending, so "nights" fits "night" and "working" fits "work". */
    private static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) return w.substring(0, w.length() - 3);
        if (w.length() > 4 && w.endsWith("ed")) return w.substring(0, w.length() - 2);
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static Set<String> stems(String s) {
        Set<String> out = new HashSet<>();
        for (String w : words(s)) if (w.length() > 1) out.add(stem(w));
        return out;
    }

    /**
     * The plain notes whose words fit his question, to go with his message (up to PER_QUESTION: most words shared
     * first, then the newest); "" when none fit.
     */
    String forQuestion(String asked) {
        Set<String> want = stems(asked == null ? "" : asked);
        want.removeAll(QUESTION_WORDS);
        if (want.isEmpty()) return "";
        List<Note> hits = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (Note n : notes) {
            if (n.always) continue;
            Set<String> have = stems(n.text);
            int hit = 0;
            for (String w : want) if (have.contains(w)) hit++;
            if (hit > 0) { hits.add(n); scores.add(hit); }
        }
        if (hits.isEmpty()) return "";
        Integer[] order = new Integer[hits.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> scores.get(y).equals(scores.get(x)) ? Long.compare(hits.get(y).id, hits.get(x).id)
                : Integer.compare(scores.get(y), scores.get(x)));
        StringBuilder b = new StringBuilder("\n\n(From the app, not the boss: notes you saved that may fit this question:");
        for (int i = 0; i < Math.min(PER_QUESTION, order.length); i++) b.append("\n- ").append(hits.get(order[i]).text);
        return b.append(")").toString();
    }

    // words that say it's a question, not what it's about
    private static final Set<String> QUESTION_WORDS = new HashSet<>(Arrays.asList("what", "whats", "when", "where", "who",
            "why", "how", "do", "doe", "did", "can", "could", "would", "should", "you", "your", "again", "tell", "know", "name",
            "remember", "please", "this", "there", "have", "has", "had", "get", "thing", "today", "tomorrow"));

    /** The Memory screen's search: notes holding all his words, newest first (all of them for an empty search). */
    List<Note> search(String q) {
        String want = norm(q == null ? "" : q);
        List<Note> out = new ArrayList<>();
        for (int i = notes.size() - 1; i >= 0; i--) {
            String have = norm(notes.get(i).text);
            boolean all = true;
            for (String w : want.split(" ")) if (!w.isEmpty() && !have.contains(w)) { all = false; break; }
            if (all) out.add(notes.get(i));
        }
        return out;
    }

    static final int ABOUT_LONGEST = 6000;

    /** His About-you page (the owner 2026-10-17: "should it know more about me, and the network setup?"), tidied: line
     * ends, trailing spaces, runs of blank lines, at most ABOUT_LONGEST characters. "" when there is none. */
    static String tidyAbout(String s) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        return t.length() > ABOUT_LONGEST ? t.substring(0, ABOUT_LONGEST).trim() : t;
    }

    /** The system prompt's block for his page (before the notes: it changes less), or null when he has none. */
    static String aboutForClaude(String about) {
        String t = tidyAbout(about);
        return t.isEmpty() ? null : "About the boss (they approved every word and edit it in the app; trust it over a guess, use "
                + "it where it helps, and don't recite it unless they ask):\n\n" + t;
    }

    String toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Note n : notes) a.put(new JSONObject().put("id", n.id).put("text", n.text).put("always", n.always));
        return new JSONObject().put("version", 2).put("notes", a).toString();
    }

    static Memory fromJson(String s) {
        Memory m = new Memory();
        try {
            JSONObject j = new JSONObject(s);
            JSONArray a = j.getJSONArray("notes");
            boolean before = j.optInt("version", 1) < 2;   // notes from before the bigger memory all went every time: still do
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Note n = new Note(o.getLong("id"), o.getString("text"), before || o.optBoolean("always"));
                m.notes.add(n);
                m.nextId = Math.max(m.nextId, n.id + 1);
            }
        } catch (Exception e) {
            m.notes.clear();
        }
        return m;
    }

    private static JSONObject str(String what) throws Exception { return new JSONObject().put("type", "string").put("description", what); }

    /** Claude's tools for it. Their words carry no example facts (the owner: an example could be taken for the truth). */
    static JSONArray definitions() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("name", "remember")
                        .put("description", "Save a lasting fact about the boss, their people, their preferences or their routines: when "
                                + "they ask you to remember something, or when they mention something worth keeping for later. Not "
                                + "passing things, and never a guess. They see each save in the chat and can delete it. Notes "
                                + "come back when they fit what they ask; set always only when they say to always keep it in mind.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("note", str("One short sentence, in plain words, said to them (starting with "
                                        + "Your or You), not about \"the boss\": they read these in the app."))
                                .put("always", new JSONObject().put("type", "boolean").put("description",
                                        "True only when they say to always remember it: it then goes with every message.")))
                                .put("required", new JSONArray().put("note"))))
                .put(new JSONObject().put("name", "forget")
                        .put("description", "Delete a saved note when they ask you to forget something or says a note is wrong.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("about", str("Words from the note to delete."))).put("required", new JSONArray().put("about"))));
    }
}
