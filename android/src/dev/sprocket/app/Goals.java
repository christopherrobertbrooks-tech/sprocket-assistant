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
 * What he's working toward (the owner 2026-10-17, from the Feed mockup: Goals on their own screen with an icon by the
 * title, not in the morning brief). Kept on the phone (files/goals.json); he says "add a goal..." or types one, ticks
 * them off, and Claude sees the open ones with his notes (the same cached block: the prompt has no room for another
 * cache mark). Plain Java: test/GoalsTest.java.
 */
final class Goals {
    static final int MAX = 50, LONGEST = 200;

    static final class Goal {
        final long id;
        final String text;
        boolean done;
        long doneAt;

        Goal(long id, String text, boolean done, long doneAt) { this.id = id; this.text = text; this.done = done; this.doneAt = doneAt; }
    }

    final List<Goal> goals = new ArrayList<>();
    private long nextId = 1;

    private static String norm(String s) { return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim(); }

    private static final Set<String> SMALL = new HashSet<>(Arrays.asList("a", "an", "the", "my", "to", "of", "and", "on", "in",
            "for", "with", "goal", "from", "it", "s"));

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) if (!w.isEmpty() && !SMALL.contains(w)) out.add(w);
        return out;
    }

    boolean add(String text, long at) {
        String t = text.trim().replaceAll("\\s+", " ").replaceAll("[.]+$", "");
        if (t.isEmpty()) return false;
        if (t.length() > LONGEST) t = t.substring(0, LONGEST);
        for (Goal g : goals) if (norm(g.text).equals(norm(t))) return false;
        if (goals.size() >= MAX) return false;
        long id = Math.max(nextId, at);
        nextId = id + 1;
        goals.add(new Goal(id, t, false, 0));
        return true;
    }

    /** The goal his words fit best (most of their meaningful words in it, at least 60%); -1 if none. */
    int find(String about) {
        List<String> want = words(about);
        if (want.isEmpty()) return -1;
        int best = -1;
        double top = 0;
        for (int i = 0; i < goals.size(); i++) {
            List<String> have = words(goals.get(i).text);
            int hit = 0;
            for (String w : want) if (have.contains(w)) hit++;
            double s = (double) hit / want.size();
            if (s > top) { top = s; best = i; }
        }
        return top >= 0.6 ? best : -1;
    }

    /** Ticked off (or back on): the goal, or null. */
    String done(String about, boolean done, long at) {
        int i = find(about);
        if (i < 0) return null;
        Goal g = goals.get(i);
        g.done = done;
        g.doneAt = done ? at : 0;
        return g.text;
    }

    String remove(String about) {
        int i = find(about);
        return i < 0 ? null : goals.remove(i).text;
    }

    boolean delete(long id) { return goals.removeIf(g -> g.id == id); }

    boolean toggle(long id, long at) {
        for (Goal g : goals) if (g.id == id) { g.done = !g.done; g.doneAt = g.done ? at : 0; return true; }
        return false;
    }

    List<Goal> open() {
        List<Goal> out = new ArrayList<>();
        for (Goal g : goals) if (!g.done) out.add(g);
        return out;
    }

    private List<String> texts(boolean done) {
        List<String> out = new ArrayList<>();
        for (Goal g : goals) if (g.done == done) out.add(g.text);
        return out;
    }

    String show() {
        if (goals.isEmpty()) return "No goals yet.";
        List<String> open = texts(false), done = texts(true);
        return (open.isEmpty() ? "No open goals." : "Open goals: " + String.join("; ", open) + ".")
                + (done.isEmpty() ? "" : " Done: " + String.join("; ", done) + ".");
    }

    /** For the prompt, with his notes; null when there are none. */
    String forClaude() {
        if (goals.isEmpty()) return null;
        StringBuilder b = new StringBuilder("Their goals (they see them in the app; tick them off with goal_done when they say one is done):");
        for (String t : texts(false)) b.append("\n- ").append(t);
        List<String> done = texts(true);
        if (!done.isEmpty()) b.append("\nDone lately: ").append(String.join("; ", done.subList(Math.max(0, done.size() - 5), done.size())));
        return b.toString();
    }

    String toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Goal g : goals) a.put(new JSONObject().put("id", g.id).put("text", g.text).put("done", g.done).put("done_at", g.doneAt));
        return new JSONObject().put("version", 1).put("goals", a).toString();
    }

    static Goals fromJson(String s) {
        Goals out = new Goals();
        try {
            JSONArray a = new JSONObject(s).getJSONArray("goals");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Goal g = new Goal(o.getLong("id"), o.getString("text"), o.optBoolean("done", false), o.optLong("done_at", 0));
                out.goals.add(g);
                out.nextId = Math.max(out.nextId, g.id + 1);
            }
        } catch (Exception e) {
            out.goals.clear();
        }
        return out;
    }

    private static JSONObject tool(String name, String what, JSONObject props, String... required) throws Exception {
        JSONArray r = new JSONArray();
        for (String x : required) r.put(x);
        return new JSONObject().put("name", name).put("description", what).put("input_schema", new JSONObject()
                .put("type", "object").put("properties", props).put("required", r));
    }

    private static JSONObject str(String what) throws Exception { return new JSONObject().put("type", "string").put("description", what); }

    static JSONArray definitions() throws Exception {
        return new JSONArray()
                .put(tool("goal_add", "Add something they're working toward to their goals (only when they ask to, or clearly sets one).",
                        new JSONObject().put("goal", str("The goal, short, in their words.")), "goal"))
                .put(tool("goal_done", "Tick a goal off when they say it's done (or back on with done=false).",
                        new JSONObject().put("goal", str("The goal, in their words.")).put("done", new JSONObject().put("type", "boolean")), "goal"))
                .put(tool("goal_remove", "Take a goal off completely (they don't want it at all any more).",
                        new JSONObject().put("goal", str("The goal, in their words.")), "goal"))
                .put(tool("goals_show", "Their goals: open ones, then done ones.", new JSONObject()));
    }
}
