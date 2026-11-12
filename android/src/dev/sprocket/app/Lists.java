package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * His lists (the owner 2026-10-17: "I would like for sprocket to handle lists as well"): shopping, to-do, anything, kept
 * on the phone (files/lists.json). the earlier test app's rules, copied: forgiving names ("my grocery list" = "groceries"),
 * items found by the same words first, then one containing the other ("oat" finds "oat milk"), a/the/some ignored.
 * New here: items are ticked off rather than lost, and ⋯ Lists shows and edits them. 2026-11-10 (his picks): items
 * can have a due date (said in the morning brief, a notice that morning) and grocery items an aisle (shown in store
 * order). Plain Java: test/ListsTest.java.
 */
final class Lists {
    static final class Item {
        final String text;
        boolean done;
        String due = "", aisle = "";             // "2026-11-14" or ""; one of AISLES or ""
        String id = "";                          // its Notion page, once it lives there
        boolean dirty;                           // changed on the phone since Notion last heard (NotionSync)
        boolean tried;                           // sent to Notion, but its answer never came: look for it there before sending again

        Item(String text, boolean done) { this.text = text; this.done = done; }

        Item(String text, boolean done, String due, String aisle) {
            this(text, done);
            this.due = due == null ? "" : due;
            this.aisle = Lists.aisle(aisle);
        }
    }

    /** A grocery store's aisles, in the order he walks them; Other last. */
    static final List<String> AISLES = java.util.Arrays.asList("Produce", "Bakery", "Deli", "Meat & seafood", "Dairy & eggs",
            "Frozen", "Pantry", "Snacks", "Drinks", "Household", "Personal care", "Pet", "Other");
    private static final String[][] AISLE_WORDS = {
            {"produce", "Produce"}, {"fruit", "Produce"}, {"vegetable", "Produce"}, {"veggie", "Produce"},
            {"bakery", "Bakery"}, {"bread", "Bakery"}, {"deli", "Deli"},
            {"meat", "Meat & seafood"}, {"seafood", "Meat & seafood"}, {"fish", "Meat & seafood"},
            {"dairy", "Dairy & eggs"}, {"egg", "Dairy & eggs"}, {"frozen", "Frozen"},
            {"pantry", "Pantry"}, {"canned", "Pantry"}, {"dry goods", "Pantry"}, {"baking", "Pantry"},
            {"snack", "Snacks"}, {"drink", "Drinks"}, {"beverage", "Drinks"},
            {"household", "Household"}, {"cleaning", "Household"}, {"personal", "Personal care"}, {"health", "Personal care"},
            {"beauty", "Personal care"}, {"pharmacy", "Personal care"}, {"pet", "Pet"}, {"other", "Other"}};

    /** The aisle he (or the brain) named, as one of AISLES; "" for none, Other when it isn't one. */
    static String aisle(String said) {
        String a = said == null ? "" : said.toLowerCase(Locale.US).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        if (a.isEmpty()) return "";
        for (String[] w : AISLE_WORDS) if (a.contains(w[0])) return w[1];
        return "Other";
    }

    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US);

    private static String day(String iso) { return java.time.LocalDate.parse(iso).format(DAY); }

    private static String dueNote(Item it, java.time.LocalDate today) {
        if (it.due.isEmpty() || it.done) return "";
        java.time.LocalDate d = java.time.LocalDate.parse(it.due);
        if (d.equals(today)) return " (due today)";
        if (d.isBefore(today)) return " (overdue since " + d.format(DAY) + ")";
        if (d.equals(today.plusDays(1))) return " (due tomorrow)";
        return " (due " + d.format(DAY) + ")";
    }

    final Map<String, List<Item>> lists = new LinkedHashMap<>();
    /** With Notion on (NotionSync): each list's Notion table, and what was removed on the phone but not yet there. */
    final Map<String, String> tables = new LinkedHashMap<>();
    final List<String> goneItems = new ArrayList<>(), goneTables = new ArrayList<>();

    /** Becomes exactly o (after a Notion sync worked on a copy). */
    void replaceWith(Lists o) {
        lists.clear(); lists.putAll(o.lists);
        tables.clear(); tables.putAll(o.tables);
        goneItems.clear(); goneItems.addAll(o.goneItems);
        goneTables.clear(); goneTables.addAll(o.goneTables);
    }

    private void forget(Item it) { if (!it.id.isEmpty()) goneItems.add(it.id); }

    private void forgetList(String k) {
        String t = tables.remove(k);
        if (t != null) goneTables.add(t);
    }

    /** A list's name as kept: lower case, without "my", "the" or a trailing "list"; grocery and to-do in any spelling. */
    static String key(String name) {
        String k = (name == null ? "" : name).toLowerCase(Locale.US).replaceAll("[^a-z0-9 \\-]", " ").replaceAll("\\s+", " ").trim();
        k = k.replaceFirst("^(my|the|our)\\s+", "").replaceFirst("\\s*list$", "").trim();
        if (k.matches("groceries|grocery|groceries list|shopping")) return "grocery";
        if (k.matches("to ?-? ?do|to dos|todos")) return "to-do";
        return k.isEmpty() ? "list" : k;
    }

    private static String phrase(String s) {
        return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim()
                .replaceFirst("^(the|some|a|an)\\s+", "");
    }

    /** Adds what isn't on it yet; how many were added. */
    int add(String list, String[] items) {
        List<Item> l = new ArrayList<>();
        for (String raw : items) l.add(new Item(raw == null ? "" : raw, false));
        return addItems(list, l);
    }

    /** Adds what isn't on it yet (an item already there comes back on, with any new due date or aisle); how many were
     * added. */
    int addItems(String list, List<Item> items) {
        List<Item> l = lists.computeIfAbsent(key(list), k -> new ArrayList<>());
        int added = 0;
        for (Item in : items) {
            String t = in.text == null ? "" : in.text.trim().replaceAll("\\s+", " ");
            if (t.isEmpty()) continue;
            boolean have = false;
            for (Item it : l) if (phrase(it.text).equals(phrase(t))) {      // again: back on
                have = true;
                it.dirty |= it.done || (!in.due.isEmpty() && !in.due.equals(it.due)) || (!in.aisle.isEmpty() && !in.aisle.equals(it.aisle));
                it.done = false;
                if (!in.due.isEmpty()) it.due = in.due;
                if (!in.aisle.isEmpty()) it.aisle = in.aisle;
            }
            if (!have) { l.add(new Item(t, false, in.due, in.aisle)); added++; }
        }
        return added;
    }

    /** A new due date ("" clears it) and/or aisle (null = leave it); the item, null if not there, "bad date". */
    String change(String list, String item, String due, String aisle) {
        int i = find(list, item);
        if (i < 0) return null;
        Item it = lists.get(key(list)).get(i);
        if (due != null && !due.isEmpty()) {
            try { java.time.LocalDate.parse(due); } catch (Exception e) { return "bad date"; }
        }
        if (due != null) it.due = due;
        if (aisle != null) it.aisle = aisle(aisle);
        it.dirty = true;
        return it.text;
    }

    /** Open items due that day, "renew tags (to-do)", for the morning. */
    List<String> due(java.time.LocalDate day) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Item>> e : lists.entrySet())
            for (Item it : e.getValue()) if (!it.done && it.due.equals(day.toString())) out.add(it.text + " (" + e.getKey() + ")");
        return out;
    }

    /** Open items whose day has passed, "renew tags (to-do, due Fri, Nov 14)". */
    List<String> overdue(java.time.LocalDate day) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<Item>> e : lists.entrySet())
            for (Item it : e.getValue())
                if (!it.done && !it.due.isEmpty() && java.time.LocalDate.parse(it.due).isBefore(day))
                    out.add(it.text + " (" + e.getKey() + ", due " + day(it.due) + ")");
        return out;
    }

    /** A list's open items by aisle, in store order (Other last); empty when none of its items has an aisle. */
    Map<String, List<Item>> groups(String list) {
        Map<String, List<Item>> out = new LinkedHashMap<>();
        List<Item> l = lists.getOrDefault(key(list), new ArrayList<>());
        boolean any = false;
        for (Item it : l) any |= !it.aisle.isEmpty();
        if (!any) return out;
        for (String a : AISLES)
            for (Item it : l) if (!it.done && (it.aisle.isEmpty() ? "Other" : it.aisle).equals(a)) out.computeIfAbsent(a, k -> new ArrayList<>()).add(it);
        return out;
    }

    /** The item's place on the list: the same words first, then one containing the other; -1 if not there. */
    int find(String list, String item) {
        List<Item> l = lists.get(key(list));
        if (l == null) return -1;
        String want = phrase(item);
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < l.size(); i++) {
                String have = phrase(l.get(i).text);
                if (pass == 0 ? have.equals(want) : !want.isEmpty() && (have.contains(want) || want.contains(have))) return i;
            }
        return -1;
    }

    /** Ticked off (or back on); the item, or null. */
    String check(String list, String item, boolean done) {
        int i = find(list, item);
        if (i < 0) return null;
        Item it = lists.get(key(list)).get(i);
        it.dirty |= it.done != done;
        it.done = done;
        return it.text;
    }

    String remove(String list, String item) {
        int i = find(list, item);
        if (i < 0) return null;
        Item it = lists.get(key(list)).remove(i);
        forget(it);
        return it.text;
    }

    /** Clears a list: only its ticked-off items, or all of it (then the list itself goes; adding makes it again).
     * How many items went. */
    int clear(String list, boolean onlyDone) {
        List<Item> l = lists.get(key(list));
        if (l == null) return 0;
        int before = l.size();
        if (!onlyDone) { lists.remove(key(list)); forgetList(key(list)); return before; }
        for (Item it : l) if (it.done) forget(it);
        l.removeIf(it -> it.done);
        return before - l.size();
    }

    boolean delete(String list) {
        forgetList(key(list));
        return lists.remove(key(list)) != null;
    }

    List<String> items(String list) {
        List<String> out = new ArrayList<>();
        for (Item it : lists.getOrDefault(key(list), new ArrayList<>())) out.add(it.text);
        return out;
    }

    String show(String list) { return show(list, java.time.LocalDate.now()); }

    /** What Claude is told: one list (open items, with due dates, by aisle when it has them; then done), or every list
     * with how many are open. */
    String show(String list, java.time.LocalDate today) {
        if (list == null || list.trim().isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, List<Item>> e : lists.entrySet()) {
                int open = 0;
                for (Item it : e.getValue()) if (!it.done) open++;
                b.append(b.length() == 0 ? "Lists: " : ", ").append(e.getKey()).append(" (").append(open).append(" open)");
            }
            return b.length() == 0 ? "No lists yet." : b.append('.').toString();
        }
        String k = key(list);
        List<Item> l = lists.get(k);
        if (l == null) return "There is no " + k + " list.";
        StringBuilder open = new StringBuilder(), done = new StringBuilder();
        Map<String, List<Item>> byAisle = groups(list);
        if (!byAisle.isEmpty()) {
            for (Map.Entry<String, List<Item>> e : byAisle.entrySet()) {
                List<String> names = new ArrayList<>();
                for (Item it : e.getValue()) names.add(it.text + dueNote(it, today));
                open.append(open.length() == 0 ? "" : ". ").append(e.getKey()).append(": ").append(String.join(", ", names));
            }
            for (Item it : l) if (it.done) done.append(done.length() == 0 ? "" : ", ").append(it.text);
        } else {
            for (Item it : l) (it.done ? done : open).append((it.done ? done : open).length() == 0 ? "" : ", ").append(it.text).append(dueNote(it, today));
        }
        String name = Character.toUpperCase(k.charAt(0)) + k.substring(1) + " list";
        return name + ": " + (open.length() == 0 ? "nothing open" : open) + "." + (done.length() == 0 ? "" : " Done: " + done + ".");
    }

    String toJson() throws Exception {
        JSONObject all = new JSONObject();
        for (Map.Entry<String, List<Item>> e : lists.entrySet()) {
            JSONArray a = new JSONArray();
            for (Item it : e.getValue()) {
                JSONObject o = new JSONObject().put("text", it.text).put("done", it.done);
                if (!it.due.isEmpty()) o.put("due", it.due);
                if (!it.aisle.isEmpty()) o.put("aisle", it.aisle);
                if (!it.id.isEmpty()) o.put("id", it.id);
                if (it.dirty) o.put("dirty", true);
                if (it.tried) o.put("tried", true);
                a.put(o);
            }
            all.put(e.getKey(), a);
        }
        JSONObject t = new JSONObject();
        for (Map.Entry<String, String> e : tables.entrySet()) t.put(e.getKey(), e.getValue());
        return new JSONObject().put("version", 1).put("lists", all).put("order", new JSONArray(new ArrayList<>(lists.keySet())))
                .put("tables", t).put("gone_items", new JSONArray(goneItems)).put("gone_tables", new JSONArray(goneTables)).toString();
    }

    static Lists fromJson(String s) {
        Lists l = new Lists();
        try {
            JSONObject o = new JSONObject(s);
            JSONObject all = o.getJSONObject("lists");
            JSONArray order = o.getJSONArray("order");
            for (int i = 0; i < order.length(); i++) {
                String k = order.getString(i);
                JSONArray a = all.getJSONArray(k);
                List<Item> items = new ArrayList<>();
                for (int j = 0; j < a.length(); j++) {
                    JSONObject it = a.getJSONObject(j);
                    Item x = new Item(it.getString("text"), it.optBoolean("done", false), it.optString("due", ""), it.optString("aisle", ""));
                    x.id = it.optString("id", "");
                    x.dirty = it.optBoolean("dirty", false);
                    x.tried = it.optBoolean("tried", false);
                    items.add(x);
                }
                l.lists.put(k, items);
            }
            JSONObject t = o.optJSONObject("tables");
            if (t != null) for (java.util.Iterator<String> ks = t.keys(); ks.hasNext(); ) { String k = ks.next(); l.tables.put(k, t.getString(k)); }
            JSONArray gi = o.optJSONArray("gone_items"), gt = o.optJSONArray("gone_tables");
            for (int i = 0; gi != null && i < gi.length(); i++) l.goneItems.add(gi.getString(i));
            for (int i = 0; gt != null && i < gt.length(); i++) l.goneTables.add(gt.getString(i));
        } catch (Exception e) {
            l.lists.clear();
            l.tables.clear();
        }
        return l;
    }

    private static JSONObject str(String what) throws Exception { return new JSONObject().put("type", "string").put("description", what); }

    private static JSONObject words(String what) throws Exception {
        return new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string")).put("description", what);
    }

    private static JSONObject tool(String name, String what, JSONObject props, String... required) throws Exception {
        JSONArray r = new JSONArray();
        for (String x : required) r.put(x);
        return new JSONObject().put("name", name).put("description", what).put("input_schema", new JSONObject()
                .put("type", "object").put("properties", props).put("required", r));
    }

    /** Claude's tools for them. */
    static JSONArray definitions() throws Exception {
        String list = "The list's name as he says it: \"grocery\", \"to-do\", \"packing\"...";
        String due = "A due date as YYYY-MM-DD (work it out from today's date: \"Friday\" = the coming Friday), when he gives one; "
                + "he hears about it in his morning brief that day.";
        String aisle = "Grocery items only: the store aisle, one of " + String.join(", ", AISLES) + ".";
        JSONObject item = new JSONObject().put("type", "object").put("properties", new JSONObject()
                .put("text", str("The item, short.")).put("due", str(due)).put("aisle", str(aisle)))
                .put("required", new JSONArray().put("text"));
        return new JSONArray()
                .put(tool("list_add", "Put items on one of his lists (made if it doesn't exist). For a picture of a recipe, a note "
                                + "or a list, add each thing in it.",
                        new JSONObject().put("list", str(list)).put("items", new JSONObject().put("type", "array").put("items", item)
                                .put("description", "Each item; grocery items with their aisle.")), "list", "items"))
                .put(tool("list_show", "What is on a list, or (no name) which lists he has.",
                        new JSONObject().put("list", str(list + " Leave out for all of them."))))
                .put(tool("list_check", "Tick an item off (got it, done), or back on with done=false.",
                        new JSONObject().put("list", str(list)).put("item", str("The item, in his words."))
                                .put("done", new JSONObject().put("type", "boolean")), "list", "item"))
                .put(tool("list_remove", "Take items off a list completely (he doesn't want them there at all).",
                        new JSONObject().put("list", str(list)).put("items", words("The items, in his words.")), "list", "items"))
                .put(tool("list_clear", "Empty a list: only the ticked-off items, or everything (he confirms that on a card).",
                        new JSONObject().put("list", str(list)).put("only_done", new JSONObject().put("type", "boolean")
                                .put("description", "true: only the ticked-off items.")), "list"))
                .put(tool("list_change", "Give an item on a list a due date (or clear it with \"\"), or move a grocery item to another aisle.",
                        new JSONObject().put("list", str(list)).put("item", str("The item, in his words.")).put("due", str(due + " \"\" clears it."))
                                .put("aisle", str(aisle)), "list", "item"));
    }
}
