package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The conversation, kept on the phone (files/chat.json) and never anywhere else. A turn is his message, Sprocket's
 * reply, an error note, or a record of something Sprocket really did on the phone ("action"); errors and actions
 * are shown, never sent to Claude. Plain Java: test/ChatTest.java.
 */
final class Chat {
    static final class Turn {
        final String role;      // "user", "assistant", "error" or "action"
        String text;
        String model = "";
        double cost;
        final List<String> photos = new ArrayList<>();   // his photos (files in files/photos), sent with his words
        String kind = "";        // "brief": a research brief (markdown), drawn as one
        final long at;

        Turn(String role, String text, long at) { this.role = role; this.text = text; this.at = at; }
    }

    final List<Turn> turns = new ArrayList<>();

    Turn add(String role, String text, long at) {
        Turn t = new Turn(role, text, at);
        turns.add(t);
        return t;
    }

    /**
     * What goes to Claude: the user/assistant messages, errors and empty replies left out, starting with one of his
     * messages, with any two in a row from the same side joined (the API wants them to alternate). A long chat sends
     * its last `max` to `max + max/2 - 1` messages: the start moves in steps of max/2, not every message, so the
     * history Claude has cached stays the same for several questions in a row (a moved start is a new history).
     * What his tools did (the action records) goes at the start of his next message, so Claude knows a text was
     * sent and doesn't send it again; the message that asked is left as it was sent, so the cache still holds it.
     */
    List<Turn> window(int max) {
        List<Turn> ok = new ArrayList<>();
        List<String> done = new ArrayList<>();      // what his tools did since his last message (action records)
        for (Turn t : turns) {
            if (t.role.equals("action")) { done.add(unnumbered(t.text).replaceAll("[.\\s]+$", "")); continue; }
            if (t.role.equals("user") && !done.isEmpty()) {   // told with his next message, so it isn't done twice
                Turn told = new Turn("user", "(From the app, not the boss: already done after his last message, so don't do it "
                        + "again unless he asks: " + String.join("; ", done) + ".)" + (t.text.isEmpty() ? "" : "\n\n" + t.text), t.at);
                told.photos.addAll(t.photos);
                t = told;
            }
            if (t.role.equals("user")) done.clear();
            if (t.kind.equals("feed")) {                // a morning brief: Claude gets it as plain lines, not its JSON
                Turn plain = new Turn(t.role, Feed.readable(t.text), t.at);
                plain.kind = "feed";
                t = plain;
            }
            if (t.kind.equals("studio")) {              // something the studio made: what it was, not the job's JSON
                Turn plain = new Turn(t.role, Studio.readable(t.text), t.at);
                plain.kind = "studio";
                t = plain;
            }
            if ((t.role.equals("user") || t.role.equals("assistant")) && (!t.text.trim().isEmpty() || !t.photos.isEmpty())) {
                Turn last = ok.isEmpty() ? null : ok.get(ok.size() - 1);   // not errors or action records
                if (last != null && last.role.equals(t.role)) {
                    Turn joined = new Turn(t.role, last.text.isEmpty() ? t.text : t.text.isEmpty() ? last.text
                            : last.text + "\n\n" + t.text, t.at);
                    joined.photos.addAll(last.photos);
                    joined.photos.addAll(t.photos);
                    ok.set(ok.size() - 1, joined);
                } else {
                    ok.add(t);
                }
            }
        }
        int step = Math.max(1, max / 2);
        int from = ok.size() < max + step ? 0 : (ok.size() - max) / step * step;
        while (from < ok.size() && !ok.get(from).role.equals("user")) from++;
        return new ArrayList<>(ok.subList(from, ok.size()));
    }

    /** An action record without a contact's number ("Texted Toni (555...): ..."): numbers stay on the phone (the tools
     * never tell Claude them either). Only before the message's opening quote, so his words are kept as written. */
    static String unnumbered(String action) {
        int q = action.indexOf('\u201c');
        String head = q < 0 ? action : action.substring(0, q), tail = q < 0 ? "" : action.substring(q);
        return head.replaceAll(" \\((?:[^()a-zA-Z]|\\([^()a-zA-Z]*\\))*\\)", "") + tail;
    }

    String toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Turn t : turns)
            a.put(new JSONObject().put("role", t.role).put("text", t.text).put("model", t.model).put("cost", t.cost)
                    .put("at", t.at).put("photos", new JSONArray(t.photos)).put("kind", t.kind));
        return new JSONObject().put("version", 1).put("turns", a).toString();
    }

    static Chat fromJson(String s) {
        Chat c = new Chat();
        try {
            JSONArray a = new JSONObject(s).getJSONArray("turns");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Turn t = c.add(o.getString("role"), o.getString("text"), o.optLong("at"));
                t.model = o.optString("model");
                t.cost = o.optDouble("cost", 0);
                t.kind = o.optString("kind", "");
                JSONArray ph = o.optJSONArray("photos");
                for (int k = 0; ph != null && k < ph.length(); k++) t.photos.add(ph.getString(k));
            }
        } catch (Exception ignored) {   // a damaged file starts a fresh chat rather than crashing
            c.turns.clear();
        }
        return c;
    }
}
