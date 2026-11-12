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
 * save shown in the chat; all of them listed in the ⋯ menu to delete). Kept on the phone (files/memory.json); the
 * notes go to Claude in the system prompt, after the persona, so Sprocket can use them. Plain Java: test/MemoryTest.java.
 */
final class Memory {
    static final int MAX = 100, LONGEST = 300;

    static final class Note {
        final long id;
        final String text;

        Note(long id, String text) { this.id = id; this.text = text; }
    }

    final List<Note> notes = new ArrayList<>();
    private long nextId = 1;

    private static String norm(String s) { return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim(); }

    /** A new note (false if he already has it). The oldest go once there are MAX. */
    boolean add(String text, long at) {
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return false;
        if (t.length() > LONGEST) t = t.substring(0, LONGEST);
        for (Note n : notes) if (norm(n.text).equals(norm(t))) return false;
        long id = Math.max(nextId, at);              // unique, and rising: a note's id is when it was made, or after
        nextId = id + 1;
        notes.add(new Note(id, t));
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

    /** The system prompt's second block, or null when there is nothing to remember. */
    String forClaude() {
        if (notes.isEmpty()) return null;
        StringBuilder b = new StringBuilder("What you remember about the boss (he can see and delete these notes in the app):");
        for (Note n : notes) b.append("\n- ").append(n.text);
        return b.toString();
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
        return t.isEmpty() ? null : "About the boss (he approved every word and edits it in the app; trust it over a guess, use "
                + "it where it helps, and don't recite it unless he asks):\n\n" + t;
    }

    String toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Note n : notes) a.put(new JSONObject().put("id", n.id).put("text", n.text));
        return new JSONObject().put("version", 1).put("notes", a).toString();
    }

    static Memory fromJson(String s) {
        Memory m = new Memory();
        try {
            JSONArray a = new JSONObject(s).getJSONArray("notes");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Note n = new Note(o.getLong("id"), o.getString("text"));
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
                        .put("description", "Save a lasting fact about the boss, his people, his preferences or his routines: when "
                                + "he asks you to remember something, or when he mentions something worth keeping for later. Not "
                                + "passing things, and never a guess. He sees each save in the chat and can delete it.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("note", str("One short sentence, in plain words."))).put("required", new JSONArray().put("note"))))
                .put(new JSONObject().put("name", "forget")
                        .put("description", "Delete a saved note when he asks you to forget something or says a note is wrong.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("about", str("Words from the note to delete."))).put("required", new JSONArray().put("about"))));
    }
}
