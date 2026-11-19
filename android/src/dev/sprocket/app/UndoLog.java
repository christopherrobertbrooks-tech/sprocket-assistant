package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * "What did you just do? Undo that." (roadmap, 11-18). What Sprocket did for each of his last
 * requests, kept on the phone (undo.json, the last 20 requests), each step with how to put it back:
 * AUTO -- the reverse tool call Sprocket runs itself (a setting back to what it was, a list item back on);
 * SCREEN -- done inside another app (a switch it flipped, an alarm, a calendar event): offered, and changed back with
 *   the screen tool only after his yes;
 * NEVER -- a sent text, a call, a tap he approved on a card (pay, send, delete...): can't be taken back;
 * INFO -- nothing to put back (an app opened, a search started). Plain Java: test/UndoLogTest.java.
 */
final class UndoLog {
    static final String AUTO = "auto", SCREEN = "screen", NEVER = "never", INFO = "info";
    static final int KEEP = 20;

    static final class Step {
        final String did, kind, tool;
        final JSONObject input;
        boolean done;                            // put back already (or reported as final): never replayed
        boolean offered;                         // SCREEN: offered back, waiting for his yes (not an undo yet; Codex 054 #3)

        Step(String did, String kind, String tool, JSONObject input) {
            this.did = did; this.kind = kind; this.tool = tool; this.input = input;
        }
    }

    static final class Entry {
        final String asked;
        final long at;
        final List<Step> steps = new ArrayList<>();
        boolean undone;                          // every step put back
        boolean handled;                         // nothing left for undo to do itself (the rest waits for his yes)

        Entry(String asked, long at) { this.asked = asked == null ? "" : asked.trim(); this.at = at; }

        boolean undoable() {
            for (Step s : steps) if (!s.kind.equals(INFO)) return true;
            return false;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private Entry current;
    /** True while an undo runs: the reverse steps show in the chat but aren't logged as a new request. */
    volatile boolean undoing;

    /** A new request from him starts (each "undo that" goes back one of these). */
    synchronized void begin(String asked, long now) { current = new Entry(asked, now); }

    /** One thing Sprocket did for the current request. tool/input: the reverse call for AUTO, else null. */
    synchronized void add(String did, String kind, String tool, JSONObject input, long now) {
        if (undoing || did == null || did.trim().isEmpty()) return;
        if (current == null) current = new Entry("", now);
        if (!entries.contains(current)) {
            entries.add(current);
            while (entries.size() > KEEP) entries.remove(0);
        }
        current.steps.add(new Step(did.trim(), kind, AUTO.equals(kind) ? tool : null, AUTO.equals(kind) ? input : null));
    }

    /** The latest request before this one with something to put back, not undone yet; null for none. */
    synchronized Entry toUndo() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (e != current && !e.undone && !e.handled && e.undoable()) return e;
        }
        return null;
    }

    synchronized void markUndone(Entry e) { e.undone = e.handled = true; }

    /** After an undo ran: the request counts as undone only when every step is back; a failed step stays to be retried
     * by the next "undo that", not skipped for an older request (Codex 053 #3). */
    synchronized void finish(Entry e) {
        boolean all = true, pending = false;
        for (Step s : e.steps) {
            if (s.kind.equals(INFO)) continue;
            if (s.kind.equals(SCREEN)) { all = false; if (!s.offered) pending = true; }   // an offer is never an undo
            else if (!s.done) { all = false; pending = true; }
        }
        e.undone = all;
        e.handled = !pending;
    }

    private static boolean partly(Entry e) {
        for (Step s : e.steps) if (s.done) return true;
        return false;
    }

    /** The what_i_did tool: what Sprocket did for his last few requests (before this one), newest first. */
    synchronized String whatIDid(int requests, long now, TimeZone tz) {
        List<String> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < Math.max(1, Math.min(5, requests)); i--) {
            Entry e = entries.get(i);
            if (e == current) continue;
            List<String> did = new ArrayList<>();
            for (Step s : e.steps) did.add(s.did);
            out.add((e.asked.isEmpty() ? "A request" : "“" + e.asked + "”") + " (" + when(e.at, now, tz) + "): "
                    + String.join("; ", did) + "." + (e.undone ? " (Undone since.)"
                    : e.handled ? (partly(e) ? " (Partly undone; switching back in the other app waits for his yes.)"
                    : " (Undo offered; switching back in the other app waits for his yes.)")
                    : partly(e) ? " (Partly undone.)" : ""));
        }
        if (out.isEmpty()) return "Sprocket hasn't done anything on the phone for his recent requests (only talked).";
        return String.join("\n", out);
    }

    private static String when(long at, long now, TimeZone tz) {
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd", Locale.US), time = new SimpleDateFormat("h:mm a", Locale.US);
        day.setTimeZone(tz); time.setTimeZone(tz);
        String t = time.format(new Date(at)).toLowerCase(Locale.US);
        if (day.format(new Date(at)).equals(day.format(new Date(now)))) return t;
        SimpleDateFormat d = new SimpleDateFormat("EEE MMM d", Locale.US);
        d.setTimeZone(tz);
        return d.format(new Date(at)) + ", " + t;
    }

    /**
     * The undo tool's answer once the AUTO steps ran: what went back, what failed, the switches he can have flipped back
     * (ask first), and what can't be taken back. back: each as undid(change, now) -- 11-18 it said "Put back: Media volume
     * 30%" and Sprocket told him the volume was at 30 when it was back at 0.
     */
    static String report(String asked, List<String> back, List<String> failed, List<String> screen, List<String> never) {
        StringBuilder s = new StringBuilder("Undoing " + (asked.isEmpty() ? "his last request" : "“" + asked + "”") + ".");
        if (!back.isEmpty()) s.append(" Reversed (each change Sprocket made, then where it is now): ").append(String.join("; ", back)).append(".");
        if (!failed.isEmpty()) s.append(" Couldn't put back: ").append(String.join("; ", failed))
                .append(" (the next \u201cundo that\u201d tries again).");
        if (!screen.isEmpty()) s.append(" Done inside another app: ").append(String.join("; ", screen))
                .append(". Ask him whether to change ").append(screen.size() == 1 ? "it" : "them")
                .append(" back; only after his yes, do it with the screen tool.");
        if (!never.isEmpty()) s.append(" Can't be put back: ").append(String.join("; ", never))
                .append(" (a sent text, a call or an approved tap stays done; a setting whose earlier state wasn't known isn't "
                + "guessed). Say so plainly.");
        if (back.isEmpty() && failed.isEmpty() && screen.isEmpty() && never.isEmpty()) s.append(" Nothing to put back.");
        return s.toString();
    }

    /** One reversed change for report(): what Sprocket had done, and the reverse tool's own answer (where it is now). */
    static String undid(String change, String now) {
        String n = now == null ? "" : now.trim();
        while (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        return "had done \u201c" + change + "\u201d, undone, now: " + (n.isEmpty() ? "back as it was" : n);
    }

    static final String NOTHING = "Nothing to undo: Sprocket hasn't changed anything for his recent requests (or it's "
            + "all undone already). Tell him so.";

    JSONObject toJson() throws Exception {
        JSONArray a = new JSONArray();
        synchronized (this) {
            for (Entry e : entries) {
                JSONArray steps = new JSONArray();
                for (Step s : e.steps) {
                    JSONObject o = new JSONObject().put("did", s.did).put("kind", s.kind).put("done", s.done).put("offered", s.offered);
                    if (s.tool != null) o.put("tool", s.tool).put("input", s.input);
                    steps.put(o);
                }
                a.put(new JSONObject().put("asked", e.asked).put("at", e.at).put("undone", e.undone).put("handled", e.handled).put("steps", steps));
            }
        }
        return new JSONObject().put("requests", a);
    }

    static UndoLog fromJson(String json) {
        UndoLog log = new UndoLog();
        if (json == null || json.trim().isEmpty()) return log;
        try {
            JSONArray a = new JSONObject(json).optJSONArray("requests");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry(o.optString("asked"), o.optLong("at"));
                e.undone = o.optBoolean("undone");
                e.handled = o.optBoolean("handled", e.undone);
                JSONArray steps = o.optJSONArray("steps");
                for (int j = 0; steps != null && j < steps.length(); j++) {
                    JSONObject s = steps.getJSONObject(j);
                    String kind = s.optString("kind", INFO);
                    Step st = new Step(s.optString("did"), kind, s.has("tool") ? s.optString("tool") : null, s.optJSONObject("input"));
                    st.done = s.optBoolean("done");
                    st.offered = s.optBoolean("offered");
                    e.steps.add(st);
                }
                if (!e.steps.isEmpty()) log.entries.add(e);
            }
            while (log.entries.size() > KEEP) log.entries.remove(0);
        } catch (Exception ignored) { }   // a damaged file: start fresh rather than fail
        return log;
    }

    static JSONArray definitions() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("name", "what_i_did")
                        .put("description", "What Sprocket did on the phone for his last requests: what it tapped, opened, "
                                + "changed, added or sent, each request with its time. Use it when he asks what you did "
                                + "(\"what did you just do?\", \"what did you change?\"). Answer from it, never from memory.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("requests", new JSONObject().put("type", "integer")
                                        .put("description", "How many of his last requests, 1 to 5. Default 1.")))))
                .put(new JSONObject().put("name", "undo")
                        .put("description", "Undo what Sprocket did for his last request (each call goes back one more "
                                + "request): settings it changed go back to what they were, list, goal and memory changes "
                                + "are reversed. It says what went back, which switches in other apps to offer to flip back, "
                                + "and what can't be taken back. Use it when he says \"undo that\", \"put it back\" or "
                                + "\"change it back\".")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject())));
    }
}
