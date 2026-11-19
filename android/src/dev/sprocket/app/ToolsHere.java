package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * The tools that run inside the app itself: lists (and their Notion copy), goals, the AI news topics, memory notes, his texts and notifications. Moved out of MainActivity (roadmap: tidier code).
 */
final class ToolsHere {
    final MainActivity host;

    ToolsHere(MainActivity host) { this.host = host; }

    /** His lists, with Notion on: the latest from Notion first (he may have changed them on the PC), the change, then
     * the change sent to Notion. */
    static String listTool(String name, JSONObject in) throws Exception {
        String before = MainActivity.syncLists();
        String out = listToolHere(name, in);
        String after = name.equals("list_show") ? before : MainActivity.syncLists();
        if (after != null && !after.isEmpty())
            out += name.equals("list_show") ? " (Notion didn't answer (" + after + "): this is the phone's copy.)"
                    : " (Notion didn't answer (" + after + "): the change is kept on the phone and goes to Notion next time.)";
        return out;
    }

    /** His lists: each change shown in the chat; emptying a whole list only after he taps Clear on a card. */
    static String listToolHere(String name, JSONObject in) throws Exception {
        String list = in.optString("list");
        JSONArray arr = in.optJSONArray("items");
        String[] items = new String[arr == null ? 0 : arr.length()];
        List<Lists.Item> adds = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {                // each item: its words, or {text, due, aisle}
            JSONObject o = arr.optJSONObject(i);
            items[i] = o != null ? o.optString("text") : arr.optString(i, "");
            String due = o == null ? "" : o.optString("due");
            try { if (!due.isEmpty()) java.time.LocalDate.parse(due); } catch (Exception e) { due = ""; }
            adds.add(new Lists.Item(items[i], false, due, o == null ? "" : o.optString("aisle")));
        }
        String k = Lists.key(list);
        switch (name) {
            case "list_add": {
                int n;
                synchronized (MainActivity.lists) { n = MainActivity.lists.addItems(list, adds); MainActivity.saveLists(); }
                if (n > 0) Hands.record("Added to " + k + ": " + String.join(", ", items));
                synchronized (MainActivity.lists) { return (n == items.length ? "Added. " : "Added " + n + " (the rest were on it already). ") + MainActivity.lists.show(list); }
            }
            case "list_change": {
                String it;
                synchronized (MainActivity.lists) {
                    it = MainActivity.lists.change(list, in.optString("item"), in.has("due") ? in.optString("due") : null,
                            in.has("aisle") ? in.optString("aisle") : null);
                    MainActivity.saveLists();
                }
                if (it == null) return "\u201c" + in.optString("item") + "\u201d isn't on the " + k + " list. " + MainActivity.lists.show(list);
                if (it.equals("bad date")) return "That due date isn't a date (YYYY-MM-DD). Nothing changed.";
                Hands.record("Changed on " + k + ": " + it);
                synchronized (MainActivity.lists) { return "Changed. " + MainActivity.lists.show(list); }
            }
            case "list_show":
                synchronized (MainActivity.lists) { return MainActivity.lists.show(list); }
            case "list_check": {
                boolean done = !in.has("done") || in.optBoolean("done", true);
                String it;
                synchronized (MainActivity.lists) { it = MainActivity.lists.check(list, in.optString("item"), done); MainActivity.saveLists(); }
                if (it == null) return "\u201c" + in.optString("item") + "\u201d isn't on the " + k + " list. " + MainActivity.lists.show(list);
                Hands.record((done ? "Ticked off on " : "Back on ") + k + ": " + it);
                return done ? "Ticked off." : "Back on the list.";
            }
            case "list_remove": {
                List<String> gone = new ArrayList<>(), missing = new ArrayList<>();
                synchronized (MainActivity.lists) {
                    for (String i : items) { String g = MainActivity.lists.remove(list, i); if (g == null) missing.add(i); else gone.add(g); }
                    MainActivity.saveLists();
                }
                if (!gone.isEmpty()) Hands.record("Removed from " + k + ": " + String.join(", ", gone));
                return (gone.isEmpty() ? "Nothing removed." : "Removed " + String.join(", ", gone) + ".")
                        + (missing.isEmpty() ? "" : " Not on it: " + String.join(", ", missing) + ".");
            }
            case "list_clear": {
                boolean onlyDone = in.optBoolean("only_done", false);
                int size;
                synchronized (MainActivity.lists) { size = MainActivity.lists.items(list).size(); }
                if (size == 0) return "The " + k + " list is empty already.";
                if (!onlyDone) {
                    Boolean yes = Hands.confirm("Clear the " + k + " list (" + size + (size == 1 ? " item" : " items") + ")?", "Clear");
                    if (yes == null || !yes) return "Not cleared: the boss " + (yes == null ? "didn't answer the card." : "cancelled.");
                }
                int n;
                synchronized (MainActivity.lists) { n = MainActivity.lists.clear(list, onlyDone); MainActivity.saveLists(); }
                Hands.record((onlyDone ? "Cleared ticked-off items from " : "Cleared the ") + k + (onlyDone ? "" : " list") + " (" + n + ")");
                return "Cleared " + n + ".";
            }
            default: throw new Exception("there is no tool called " + name);
        }
    }

    static String goalTool(String name, JSONObject in) throws Exception {
        long now = System.currentTimeMillis();
        synchronized (MainActivity.goals) {
            switch (name) {
                case "goal_add": {
                    String g = in.optString("goal").trim();
                    if (g.isEmpty()) throw new Exception("there's no goal to add");
                    boolean added = MainActivity.goals.add(g, now);
                    MainActivity.saveGoals();
                    if (added) Hands.record("New goal: " + g);
                    return added ? "Added to their goals." : "They already have that goal (or the list is full).";
                }
                case "goal_done": {
                    boolean done = !in.has("done") || in.optBoolean("done");
                    String g = MainActivity.goals.done(in.optString("goal"), done, now);
                    MainActivity.saveGoals();
                    if (g == null) return "No goal matches that. " + MainActivity.goals.show();
                    Hands.record((done ? "Goal done: " : "Goal back on: ") + g);
                    return (done ? "Ticked off: " : "Back on: ") + g;
                }
                case "goal_remove": {
                    String g = MainActivity.goals.remove(in.optString("goal"));
                    MainActivity.saveGoals();
                    if (g == null) return "No goal matches that. " + MainActivity.goals.show();
                    Hands.record("Goal removed: " + g);
                    return "Removed: " + g;
                }
                default: return MainActivity.goals.show();
            }
        }
    }

    /** The morning brief's news topics: added when they ask (they see and remove them on the Feed screen). */
    static String watchTool(JSONObject in) throws Exception {
        String action = in.optString("action"), topic = in.optString("topic").trim();
        synchronized (MainActivity.feed) {
            if (action.equals("add")) {
                if (topic.isEmpty()) return "No topic was given.";
                boolean added = MainActivity.feed.watch(topic);
                MainActivity.saveFeed();
                if (added) Hands.record("Following in the morning news: " + topic);
                return added ? "Watching for news on " + topic + "." : "Already watching that (or 30 topics already).";
            }
            if (action.equals("remove")) {
                String gone = MainActivity.feed.unwatch(topic);
                MainActivity.saveFeed();
                if (gone == null) return "Not watching anything like that. Watching: " + String.join("; ", MainActivity.feed.topics) + ".";
                Hands.record("Stopped watching: " + gone);
                return "Stopped watching " + gone + ".";
            }
            return MainActivity.feed.topics.isEmpty() ? "No news topics yet." : "Following in the morning news: " + String.join("; ", MainActivity.feed.topics) + ".";
        }
    }

    /** Phase 3b: a note saved or deleted, each shown in the chat. */
    static String remember(String name, JSONObject input) throws Exception {
        if (name.equals("remember")) {
            String note = input.optString("note").trim();
            if (note.isEmpty()) throw new Exception("there was nothing to remember");
            boolean added, always = input.optBoolean("always");
            synchronized (MainActivity.memory) { added = MainActivity.memory.add(note, System.currentTimeMillis(), always); MainActivity.saveMemory(); }
            if (added) Hands.record((always ? "Always remembering: " : "Remembered: ") + note);
            return added ? "Saved." : "Already saved.";
        }
        List<String> gone;
        synchronized (MainActivity.memory) { gone = MainActivity.memory.forget(input.optString("about")); MainActivity.saveMemory(); }
        for (String g : gone) Hands.record("Forgot: " + g);
        return gone.isEmpty() ? "No saved note matches that." : "Deleted: " + String.join(" / ", gone);
    }

    /** Main thread: a real action Sprocket did, recorded above the reply that is still coming in (Hands.record). */
    /** His texts (caught by MessageListener): who has new ones, or the words to read out. */
    static String texts(String name, JSONObject input) {
        if (!MessageListener.on(MainActivity.app)) {
            MessageListener.openSwitch(MainActivity.app);
            return "Sprocket can't see their texts yet: they need to switch on Sprocket's notification access (that switch is "
                    + "open on their screen now). Only texts that arrive after that can be read.";
        }
        Messages m = MessageListener.texts(MainActivity.app);
        String out;
        long now = System.currentTimeMillis();
        synchronized (m) {
            out = name.equals("check_texts") ? m.news(now, TimeZone.getDefault())
                    : m.read(input.optString("from"), input.optInt("count", 0), now, TimeZone.getDefault());
        }
        if (name.equals("read_texts")) MessageListener.save(MainActivity.app);   // what was read is no longer new
        return out;
    }

    /** What his notifications say right now (email included): nothing is kept. */
    static String notices(String which) {
        if (!MessageListener.on(MainActivity.app)) {
            MessageListener.openSwitch(MainActivity.app);
            return "Sprocket can't see their notifications yet: they need to switch on Sprocket's notification access (that "
                    + "switch is open on their screen now).";
        }
        List<Notices.Notice> showing = MessageListener.showing(MainActivity.app);
        if (showing == null) return "The notification reader is still starting; ask again in a few seconds.";
        return Notices.describe(showing, which, System.currentTimeMillis(), TimeZone.getDefault());
    }
}
