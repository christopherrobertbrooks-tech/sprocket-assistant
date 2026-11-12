package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * His lists in Notion (the owner 2026-11-10: "elaborate on the lists, maybe tie this to notion", then "Lists live in
 * Notion"): each list is a table (Item, Done, Due, Aisle) inside his "Sprocket lists" page, reached with a personal
 * access token he makes in Notion's developer portal (notion.so/developers/tokens). Notion is where the lists live; the phone keeps a copy for when Notion can't be reached.
 * A sync first sends Notion what changed on the phone (new items, ticks, dates, aisles, removals), then reads every
 * table back, so edits he made in Notion on the PC come back to the phone. The HTTP part is behind Api, so the whole
 * sync is tested against a pretend Notion (test/NotionSyncTest.java). Worker thread only; the caller holds the lists.
 */
final class NotionSync {
    private NotionSync() { }

    /** One Notion call: method, path under /v1, body (null for none); the answer, or null for a 404. */
    interface Api { JSONObject call(String method, String path, JSONObject body) throws Exception; }

    static final String PAGE_TITLE = "Sprocket lists", VERSION = "2022-06-28";

    /** The real Notion, with his "Sprocket" connection's key (kept on the phone, entered by him under ⋯ > Notion key). */
    static final class Http implements Api {
        private final String key;

        Http(String key) { this.key = key; }

        public JSONObject call(String method, String path, JSONObject body) throws Exception {
            java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL("https://api.notion.com/v1" + path).openConnection();
            try {
                h.setRequestMethod(method);
                h.setConnectTimeout(8000);
                h.setReadTimeout(20000);
                h.setRequestProperty("Authorization", "Bearer " + key);
                h.setRequestProperty("Notion-Version", VERSION);
                h.setRequestProperty("Content-Type", "application/json");
                if (body != null) {
                    h.setDoOutput(true);
                    try (java.io.OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                }
                int code = h.getResponseCode();
                if (code == 404) return null;
                java.io.InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
                String text = in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (code == 401) throw new Exception("Notion didn't accept the key (check it under \u22ef > Notion key)");
                if (code == 429) throw new Exception("Notion asked Sprocket to slow down; it tries again next time");
                if (code / 100 != 2) {
                    String why = "";
                    try { why = new JSONObject(text).optString("message"); } catch (Exception ignored) { }
                    throw new Exception("Notion answered " + code + (why.isEmpty() ? "" : ": " + why));
                }
                return new JSONObject(text);
            } finally {
                h.disconnect();
            }
        }
    }

    /** His "Sprocket lists" page (its id), or null if Sprocket can't see one. Only that page: his personal access token
     * (Notion's way since 2026) can see his whole workspace, and Sprocket never writes anywhere else. */
    static String findPage(Api api) throws Exception {
        JSONObject r = api.call("POST", "/search", new JSONObject().put("query", PAGE_TITLE)
                .put("filter", new JSONObject().put("property", "object").put("value", "page")));
        JSONArray res = r == null ? null : r.optJSONArray("results");
        for (int i = 0; res != null && i < res.length(); i++) {   // that exact page: a personal token sees all of his
            JSONObject p = res.getJSONObject(i);
            if (!p.optBoolean("archived") && PAGE_TITLE.equalsIgnoreCase(pageTitle(p).trim())) return p.getString("id");
        }
        return null;
    }

    private static String pageTitle(JSONObject page) {
        JSONObject props = page.optJSONObject("properties");
        if (props == null) return "";
        for (java.util.Iterator<String> k = props.keys(); k.hasNext(); ) {
            JSONObject p = props.optJSONObject(k.next());
            if (p != null && "title".equals(p.optString("type"))) return plain(p.optJSONArray("title"));
        }
        return "";
    }

    static String plain(JSONArray rich) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; rich != null && i < rich.length(); i++) {
            JSONObject t = rich.optJSONObject(i);
            String s = t.optString("plain_text", "");
            if (s.isEmpty() && t.optJSONObject("text") != null) s = t.optJSONObject("text").optString("content");
            b.append(s);
        }
        return b.toString();
    }

    private static JSONArray rich(String s) throws Exception {
        return new JSONArray().put(new JSONObject().put("text", new JSONObject().put("content", s)));
    }

    /** A list's table name: "Grocery", "To-do", "Packing list for iowa". */
    static String tableName(String key) { return Character.toUpperCase(key.charAt(0)) + key.substring(1); }

    /** An item as a row's columns. */
    static JSONObject columns(Lists.Item it) throws Exception {
        return new JSONObject()
                .put("Item", new JSONObject().put("title", rich(it.text)))
                .put("Done", new JSONObject().put("checkbox", it.done))
                .put("Due", new JSONObject().put("date", it.due.isEmpty() ? JSONObject.NULL : new JSONObject().put("start", it.due)))
                .put("Aisle", new JSONObject().put("select", it.aisle.isEmpty() ? JSONObject.NULL : new JSONObject().put("name", it.aisle)));
    }

    /** A row back as an item (null for a row with no words). */
    static Lists.Item item(JSONObject row) {
        JSONObject p = row.optJSONObject("properties");
        if (p == null) return null;
        String text = plain(p.optJSONObject("Item") == null ? null : p.optJSONObject("Item").optJSONArray("title")).trim();
        if (text.isEmpty()) return null;
        JSONObject done = p.optJSONObject("Done"), due = p.optJSONObject("Due"), aisle = p.optJSONObject("Aisle");
        JSONObject d = due == null ? null : due.optJSONObject("date"), a = aisle == null ? null : aisle.optJSONObject("select");
        String start = d == null ? "" : d.optString("start", "");
        Lists.Item it = new Lists.Item(text, done != null && done.optBoolean("checkbox"), start.length() >= 10 ? start.substring(0, 10) : "",
                a == null ? "" : a.optString("name", ""));
        it.id = row.optString("id");
        return it;
    }

    private static JSONObject newTable(String page, String key) throws Exception {
        JSONArray options = new JSONArray();
        for (String a : Lists.AISLES) options.put(new JSONObject().put("name", a));
        return new JSONObject().put("parent", new JSONObject().put("type", "page_id").put("page_id", page))
                .put("title", rich(tableName(key)))
                .put("properties", new JSONObject()
                        .put("Item", new JSONObject().put("title", new JSONObject()))
                        .put("Done", new JSONObject().put("checkbox", new JSONObject()))
                        .put("Due", new JSONObject().put("date", new JSONObject()))
                        .put("Aisle", new JSONObject().put("select", new JSONObject().put("options", options))));
    }

    /** The tables on his page, in page order: list key -> table id. */
    private static Map<String, String> tablesOn(Api api, String page) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        String cursor = null;
        do {
            JSONObject r = api.call("GET", "/blocks/" + page + "/children?page_size=100" + (cursor == null ? "" : "&start_cursor=" + cursor), null);
            if (r == null) throw new Exception("the \"" + PAGE_TITLE + "\" page is gone from Notion (or no longer shared with Sprocket)");
            JSONArray res = r.optJSONArray("results");
            for (int i = 0; res != null && i < res.length(); i++) {
                JSONObject b = res.getJSONObject(i);
                if (!"child_database".equals(b.optString("type")) || b.optBoolean("archived")) continue;
                String title = b.getJSONObject("child_database").optString("title").trim();
                if (!title.isEmpty()) out.putIfAbsent(Lists.key(title), b.getString("id"));
            }
            cursor = r.optBoolean("has_more") ? r.optString("next_cursor", null) : null;
        } while (cursor != null);
        return out;
    }

    private static List<Lists.Item> rows(Api api, String table) throws Exception {
        List<Lists.Item> out = new ArrayList<>();
        String cursor = null;
        do {
            JSONObject body = new JSONObject().put("page_size", 100)
                    .put("sorts", new JSONArray().put(new JSONObject().put("timestamp", "created_time").put("direction", "ascending")));
            if (cursor != null) body.put("start_cursor", cursor);
            JSONObject r = api.call("POST", "/databases/" + table + "/query", body);
            if (r == null) return null;                          // the table is gone
            JSONArray res = r.optJSONArray("results");
            for (int i = 0; res != null && i < res.length(); i++) {
                Lists.Item it = item(res.getJSONObject(i));
                if (it != null) out.add(it);
            }
            cursor = r.optBoolean("has_more") ? r.optString("next_cursor", null) : null;
        } while (cursor != null);
        return out;
    }

    /** A Notion id without its dashes (Notion writes ids both ways). */
    private static String bare(String id) { return id == null ? "" : id.replace("-", ""); }

    /** True when this row still sits in one of the tables on his page. */
    private static boolean onPage(Api api, String row, java.util.Set<String> mine) throws Exception {
        JSONObject p = api.call("GET", "/pages/" + row, null);
        JSONObject parent = p == null ? null : p.optJSONObject("parent");
        return parent != null && !p.optBoolean("archived") && mine.contains(bare(parent.optString("database_id")));
    }

    /** An item whose create was sent but never answered: its row in Notion if the create went through (same words, not
     * another item's), else "" to send it again. A table's rows are read once per sync. */
    private static String lost(Api api, String table, Lists.Item it, Map<String, List<Lists.Item>> there, java.util.Set<String> held) throws Exception {
        List<Lists.Item> rows = there.get(table);
        if (rows == null) {
            rows = rows(api, table);
            if (rows == null) rows = new ArrayList<>();
            there.put(table, rows);
        }
        for (Lists.Item r : rows) if (!held.contains(r.id) && r.text.equalsIgnoreCase(it.text.trim())) {
            held.add(r.id);
            return r.id;
        }
        return "";
    }

    /**
     * Brings what a sync learned into the lists as they are now, when he changed them while it ran (Codex 040 #7: the
     * sync works on a copy, so the screen never waits on Notion). before: the copy as the sync began; synced: the copy
     * after it. Kept: his new changes (they go next sync). Taken: the Notion ids of rows and tables it made, and the
     * removals it already sent. A row or table it made for something he removed meanwhile goes next sync too.
     */
    static void merge(Lists before, Lists synced, Lists live) {
        for (String id : before.goneItems) if (!synced.goneItems.contains(id)) live.goneItems.remove(id);
        for (String id : before.goneTables) if (!synced.goneTables.contains(id)) live.goneTables.remove(id);
        for (Map.Entry<String, String> e : synced.tables.entrySet()) {
            String k = e.getKey(), id = e.getValue();
            if (id.equals(before.tables.get(k)) || live.tables.containsValue(id)) continue;
            if (live.lists.containsKey(k)) live.tables.put(k, id);
            else if (before.lists.containsKey(k)) live.goneTables.add(id);   // made for a list he deleted meanwhile
        }
        java.util.Set<String> held = new java.util.HashSet<>(live.goneItems);
        for (List<Lists.Item> items : live.lists.values()) for (Lists.Item it : items) if (!it.id.isEmpty()) held.add(it.id);
        for (Map.Entry<String, List<Lists.Item>> e : synced.lists.entrySet()) {
            List<Lists.Item> now = live.lists.get(e.getKey()), was = before.lists.get(e.getKey());
            for (Lists.Item s : e.getValue()) {
                if (s.id.isEmpty() ? !s.tried : held.contains(s.id)) continue;
                Lists.Item match = null;
                for (int i = 0; now != null && i < now.size() && match == null; i++) {
                    Lists.Item c = now.get(i);
                    if (c.id.isEmpty() && c.text.equalsIgnoreCase(s.text)) match = c;
                }
                if (match != null) {
                    if (s.id.isEmpty()) match.tried = true; else { match.id = s.id; match.tried = false; held.add(s.id); }
                    continue;
                }
                boolean madeNow = false;                 // was it new on the phone when the sync began?
                for (int i = 0; was != null && i < was.size() && !madeNow; i++) madeNow = was.get(i).id.isEmpty() && was.get(i).text.equalsIgnoreCase(s.text);
                if (madeNow && !s.id.isEmpty()) { live.goneItems.add(s.id); held.add(s.id); }   // he removed it meanwhile
            }
        }
    }

    /**
     * Sends what changed on the phone, then reads all of it back. Afterwards the phone's lists are exactly what's in
     * Notion. A list made on the phone gets its own table; one he made in Notion (a table on the page with an Item
     * column) shows up on the phone.
     */
    static void sync(Lists l, Api api, String page) throws Exception {
        // first, what is on his page now: only its tables and their rows are ever written (Codex 041 #1)
        Map<String, String> remote = tablesOn(api, page);
        java.util.Set<String> mine = new java.util.HashSet<>();
        for (String t : remote.values()) mine.add(bare(t));
        // 1. what was removed on the phone (a row or table he has since moved off the page is left alone)
        for (String id : new ArrayList<>(l.goneItems)) {
            if (onPage(api, id, mine)) api.call("PATCH", "/pages/" + id, new JSONObject().put("archived", true));
            l.goneItems.remove(id);
        }
        for (String id : new ArrayList<>(l.goneTables)) {
            if (mine.remove(bare(id))) api.call("DELETE", "/blocks/" + id, null);
            remote.values().removeIf(id::equals);
            l.goneTables.remove(id);
        }
        // 2. lists made on the phone: a table each (or the one of that name he already has)
        for (String k : l.lists.keySet()) {
            String t = l.tables.get(k);
            if (t != null && remote.containsValue(t)) continue;
            if (t != null && !remote.containsKey(k)) {           // its table was deleted in Notion: so is the list...
                l.tables.remove(k);
                List<Lists.Item> items = l.lists.get(k);
                items.removeIf(it -> !it.id.isEmpty() && !it.dirty);   // (what Notion already had went with it)
                if (items.isEmpty()) continue;
                for (Lists.Item it : items) { it.id = ""; it.tried = false; }   // ...except what he added or changed since:
            }                                                    // the list comes back with that (Codex 041 #3)
            if (remote.containsKey(k)) { l.tables.put(k, remote.get(k)); continue; }
            JSONObject made = api.call("POST", "/databases", newTable(page, k));
            l.tables.put(k, made.getString("id"));
            remote.put(k, made.getString("id"));
        }
        for (String t : remote.values()) mine.add(bare(t));       // the tables just made are his page's too
        // 3. new and changed items
        java.util.Set<String> held = new java.util.HashSet<>();
        for (List<Lists.Item> items : l.lists.values()) for (Lists.Item it : items) if (!it.id.isEmpty()) held.add(it.id);
        Map<String, List<Lists.Item>> there = new java.util.HashMap<>();
        for (Map.Entry<String, List<Lists.Item>> e : l.lists.entrySet()) {
            String t = l.tables.get(e.getKey());
            if (t == null) continue;
            for (Lists.Item it : e.getValue()) {
                if (it.id.isEmpty() && it.tried) it.id = lost(api, t, it, there, held);
                if (it.id.isEmpty()) {
                    it.tried = true;                     // saved even if the answer never comes (Codex 040 #8)
                    JSONObject made = api.call("POST", "/pages", new JSONObject().put("parent", new JSONObject().put("database_id", t))
                            .put("properties", columns(it)));
                    it.id = made.getString("id");
                    held.add(it.id);
                } else if (it.dirty) {
                    if (!onPage(api, it.id, mine)) continue;     // moved off his page in Notion: not Sprocket's to change
                    if (api.call("PATCH", "/pages/" + it.id, new JSONObject().put("properties", columns(it))) == null) continue;   // gone there
                }
                it.tried = false;
                it.dirty = false;
            }
        }
        // 4. everything, read back: Notion wins
        Map<String, List<Lists.Item>> back = new LinkedHashMap<>();
        Map<String, String> tables = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : remote.entrySet()) {
            List<Lists.Item> items = rows(api, e.getValue());
            if (items == null) continue;
            back.put(e.getKey(), items);
            tables.put(e.getKey(), e.getValue());
        }
        l.lists.clear();
        l.lists.putAll(back);
        l.tables.clear();
        l.tables.putAll(tables);
    }
}
