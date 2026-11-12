package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** His lists in Notion, on the desktop JDK against a pretend Notion that answers the calls NotionSync makes. */
public class NotionSyncTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    /** A pretend Notion: one shared page, its tables, their rows. */
    static final class Fake implements NotionSync.Api {
        final Map<String, String> tables = new LinkedHashMap<>();          // id -> title, in page order
        final Map<String, JSONObject> rows = new LinkedHashMap<>();        // id -> row (with "parent" table id)
        final List<String> calls = new ArrayList<>();
        int next = 1;
        boolean down;

        static JSONObject page(String id, String title) throws Exception {
            JSONObject t = new JSONObject().put("type", "title").put("title", new JSONArray().put(new JSONObject().put("plain_text", title)));
            return new JSONObject().put("id", id).put("properties", new JSONObject().put("title", t));
        }

        String addTable(String title) { String id = "t" + next++; tables.put(id, title); return id; }

        String addRow(String table, Lists.Item it) throws Exception {
            String id = "r" + next++;
            rows.put(id, new JSONObject().put("id", id).put("table", table).put("properties", NotionSync.columns(it)));
            return id;
        }

        public JSONObject call(String method, String path, JSONObject body) throws Exception {
            if (down) throw new java.io.IOException("no internet");
            calls.add(method + " " + path.replaceAll("\\?.*", ""));
            if (path.equals("/search")) return new JSONObject().put("results", new JSONArray().put(page("other", "another app")).put(page("PAGE", "Sprocket lists")));
            if (path.startsWith("/blocks/PAGE/children")) {
                JSONArray res = new JSONArray();
                for (Map.Entry<String, String> t : tables.entrySet())
                    res.put(new JSONObject().put("id", t.getKey()).put("type", "child_database").put("child_database", new JSONObject().put("title", t.getValue())));
                return new JSONObject().put("results", res).put("has_more", false);
            }
            if (method.equals("POST") && path.equals("/databases")) {
                String title = NotionSync.plain(body.getJSONArray("title"));
                return new JSONObject().put("id", addTable(title));
            }
            if (path.startsWith("/databases/") && path.endsWith("/query")) {
                String t = path.split("/")[2];
                if (!tables.containsKey(t)) return null;
                JSONArray res = new JSONArray();
                for (JSONObject r : rows.values()) if (r.getString("table").equals(t)) res.put(r);
                return new JSONObject().put("results", res).put("has_more", false);
            }
            if (method.equals("POST") && path.equals("/pages")) {
                String id = "r" + next++;
                rows.put(id, new JSONObject().put("id", id).put("table", body.getJSONObject("parent").getString("database_id"))
                        .put("properties", body.getJSONObject("properties")));
                return new JSONObject().put("id", id);
            }
            if (method.equals("GET") && path.startsWith("/pages/")) {
                JSONObject r = rows.get(path.substring(7));
                return r == null ? null : new JSONObject().put("id", r.getString("id"))
                        .put("parent", new JSONObject().put("type", "database_id").put("database_id", r.getString("table")));
            }
            if (method.equals("PATCH") && path.startsWith("/pages/")) {
                String id = path.substring(7);
                if (!rows.containsKey(id)) return null;
                if (body.optBoolean("archived")) rows.remove(id);
                else rows.get(id).put("properties", body.getJSONObject("properties"));
                return new JSONObject().put("id", id);
            }
            if (method.equals("DELETE") && path.startsWith("/blocks/")) {
                String id = path.substring(8);
                tables.remove(id);
                rows.values().removeIf(r -> r.optString("table").equals(id));
                return new JSONObject();
            }
            throw new Exception("the pretend Notion has no " + method + " " + path);
        }

        List<String> words(String title) throws Exception {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> t : tables.entrySet()) if (t.getValue().equals(title))
                for (JSONObject r : rows.values()) if (r.getString("table").equals(t.getKey())) {
                    Lists.Item it = NotionSync.item(r);
                    out.add(it.text + (it.done ? " [x]" : "") + (it.due.isEmpty() ? "" : " due " + it.due) + (it.aisle.isEmpty() ? "" : " @" + it.aisle));
                }
            return out;
        }
    }

    public static void main(String[] a) throws Exception {
        java.time.LocalDate tue = java.time.LocalDate.parse("2026-11-10");
        Fake notion = new Fake();
        eq("his Sprocket lists page is found by its name (never another of his pages)", NotionSync.findPage(notion), "PAGE");

        // first connect: the phone's lists go to Notion, a table each
        Lists l = new Lists();
        l.addItems("grocery", java.util.Arrays.asList(new Lists.Item("milk", false, "", "Dairy & eggs"), new Lists.Item("apples", false, "", "Produce")));
        l.addItems("to-do", java.util.Collections.singletonList(new Lists.Item("renew tags", false, "2026-11-13", "")));
        NotionSync.sync(l, notion, "PAGE");
        eq("a table per list, named plainly", notion.tables.values(), "[Grocery, To-do]");
        eq("the grocery rows, with aisles", notion.words("Grocery"), "[milk @Dairy & eggs, apples @Produce]");
        eq("the to-do row, with its date", notion.words("To-do"), "[renew tags due 2026-11-13]");
        eq("the phone's copy is the same afterwards", l.show("grocery", tue) + " | " + l.show("to-do", tue),
                "Grocery list: Produce: apples. Dairy & eggs: milk. | To-do list: renew tags (due Fri, Nov 13).");

        // changes on the phone reach Notion
        l.check("grocery", "milk", true);
        l.addItems("grocery", java.util.Collections.singletonList(new Lists.Item("frozen pizza", false, "", "Frozen")));
        l.remove("grocery", "apples");
        l.change("to-do", "renew tags", "2026-11-14", null);
        notion.calls.clear();
        NotionSync.sync(l, notion, "PAGE");
        eq("ticked, added, removed", notion.words("Grocery"), "[milk [x] @Dairy & eggs, frozen pizza @Frozen]");
        eq("a new date", notion.words("To-do"), "[renew tags due 2026-11-14]");
        eq("only what changed is sent (no rewriting every row)", notion.calls.stream().filter(c -> c.startsWith("PATCH")).count(), 3L);

        // changes made in Notion (on the PC) come back to the phone
        String todo = null;
        for (Map.Entry<String, String> t : notion.tables.entrySet()) if (t.getValue().equals("To-do")) todo = t.getKey();
        notion.addRow(todo, new Lists.Item("call the vet", false, "2026-11-10", ""));
        for (JSONObject r : notion.rows.values()) if (NotionSync.item(r).text.equals("frozen pizza")) r.put("properties", NotionSync.columns(new Lists.Item("frozen pizza", true, "", "Frozen")));
        String packing = notion.addTable("Packing");
        notion.addRow(packing, new Lists.Item("charger", false, "", ""));
        NotionSync.sync(l, notion, "PAGE");
        eq("a row he added in Notion", l.due(tue), "[call the vet (to-do)]");
        eq("a tick he made in Notion", l.show("grocery", tue), "Grocery list: nothing open. Done: milk, frozen pizza.");
        eq("a list he made in Notion", l.show("packing", tue), "Packing list: charger.");

        // a list deleted in Notion goes from the phone; one deleted on the phone goes from Notion
        notion.tables.remove(packing);
        l.delete("to-do");
        NotionSync.sync(l, notion, "PAGE");
        eq("lists on both sides", l.show("", tue) + " | " + notion.tables.values(), "Lists: grocery (0 open). | [Grocery]");

        // no internet: the change waits on the phone, then goes
        l.add("grocery", new String[]{"bread"});
        l.clear("grocery", true);                                // milk and frozen pizza, ticked off
        notion.down = true;
        try { NotionSync.sync(l, notion, "PAGE"); eq("offline: the sync says so", "no error", "an error"); }
        catch (Exception e) { eq("offline: the sync says so", e.getMessage(), "no internet"); }
        eq("still on the phone", l.show("grocery", tue), "Grocery list: bread.");
        Lists saved = Lists.fromJson(l.toJson());                // the phone restarts before the internet is back
        notion.down = false;
        NotionSync.sync(saved, notion, "PAGE");
        eq("it catches up later", notion.words("Grocery"), "[bread]");
        eq("nothing left waiting", saved.goneItems.size() + saved.goneTables.size(), 0);

        // a list he names the same as one in Notion uses that table
        Lists fresh = new Lists();
        fresh.add("groceries", new String[]{"eggs"});
        NotionSync.sync(fresh, notion, "PAGE");
        eq("one Grocery table, not two", notion.tables.values() + " " + notion.words("Grocery"), "[Grocery] [bread, eggs]");

        eq("an empty row in Notion is skipped", NotionSync.item(new JSONObject().put("id", "x").put("properties", new JSONObject()
                .put("Item", new JSONObject().put("title", new JSONArray())))), null);

        // Notion made the row but its answer was lost (Codex 040 #8): the retry finds that row instead of making another
        Fake n2 = new Fake();
        NotionSync.Api lossy = new NotionSync.Api() {
            boolean lost;
            public JSONObject call(String method, String path, JSONObject body) throws Exception {
                JSONObject r = n2.call(method, path, body);
                if (!lost && method.equals("POST") && path.equals("/pages")) { lost = true; throw new java.io.IOException("timed out"); }
                return r;
            }
        };
        Lists one = new Lists();
        one.add("packing", new String[]{"charger"});
        try { NotionSync.sync(one, lossy, "PAGE"); eq("the lost answer shows as an error", "no error", "an error"); }
        catch (Exception e) { eq("the lost answer shows as an error", e.getMessage(), "timed out"); }
        Lists retry = Lists.fromJson(one.toJson());              // saved, the phone restarts, then the next sync
        NotionSync.sync(retry, n2, "PAGE");
        eq("one charger in Notion, not two", n2.words("Packing"), "[charger]");
        eq("one charger on the phone", retry.show("packing", tue), "Packing list: charger.");
        Lists fresh2 = new Lists();
        fresh2.add("packing", new String[]{"socks"});
        NotionSync.sync(fresh2, n2, "PAGE");
        eq("a normal create still goes once", n2.words("Packing"), "[charger, socks]");

        // he changes the lists while a sync runs on its copy (Codex 040 #7): his changes and the sync's ids both survive
        Fake n3 = new Fake();
        Lists live = new Lists();
        live.add("grocery", new String[]{"milk"});
        NotionSync.sync(live, n3, "PAGE");
        live.add("grocery", new String[]{"bread", "charger"});
        String before = live.toJson();
        Lists copy = Lists.fromJson(before);
        NotionSync.sync(copy, n3, "PAGE");                       // the sync, on its copy...
        live.add("grocery", new String[]{"eggs"});               // ...while he adds eggs,
        live.remove("grocery", "charger");                       // takes off charger (new, its row just made),
        live.check("grocery", "milk", true);                     // and ticks milk
        NotionSync.merge(Lists.fromJson(before), copy, live);
        NotionSync.sync(live, n3, "PAGE");
        eq("every change made during a sync reaches Notion, nothing doubled", n3.words("Grocery"), "[milk [x], bread, eggs]");
        eq("and the phone matches", live.show("grocery", tue), "Grocery list: bread, eggs. Done: milk.");
        Lists gone = new Lists();
        gone.add("party", new String[]{"cups"});
        String b2 = gone.toJson();
        Lists c2 = Lists.fromJson(b2);
        NotionSync.sync(c2, n3, "PAGE");                         // a table made for a list...
        gone.delete("party");                                    // ...he deletes while it's made
        NotionSync.merge(Lists.fromJson(b2), c2, gone);
        NotionSync.sync(gone, n3, "PAGE");
        eq("a list deleted during its first sync goes from Notion too", n3.tables.values(), "[Grocery]");

        // a row he moved off his page in Notion is never written again (Codex 041 #1)
        Fake n4 = new Fake();
        Lists moved = new Lists();
        moved.add("packing", new String[]{"charger", "socks"});
        NotionSync.sync(moved, n4, "PAGE");
        JSONObject chargerRow = null, socksRow = null;
        for (JSONObject r : n4.rows.values()) {
            if (NotionSync.item(r).text.equals("charger")) chargerRow = r;
            if (NotionSync.item(r).text.equals("socks")) socksRow = r;
        }
        chargerRow.put("table", "ELSEWHERE");                    // moved to another of his databases
        socksRow.put("table", "ELSEWHERE");
        moved.check("packing", "charger", true);                 // the phone still has them: one ticked,
        moved.remove("packing", "socks");                        // one taken off
        n4.calls.clear();
        NotionSync.sync(moved, n4, "PAGE");
        eq("no write to rows moved off the page", n4.calls.stream().filter(x -> x.startsWith("PATCH")).count(), 0L);
        eq("the moved rows are untouched", NotionSync.item(chargerRow).done + " " + n4.rows.containsKey(socksRow.getString("id")), "false true");
        eq("and they leave the phone's list", moved.show("packing", tue), "Packing list: nothing open.");

        // a list deleted in Notion while he added to it offline comes back with what he added (Codex 041 #3)
        Fake n5 = new Fake();
        Lists trip = new Lists();
        trip.add("packing", new String[]{"charger"});
        NotionSync.sync(trip, n5, "PAGE");
        trip.add("packing", new String[]{"passport"});           // offline: not sent yet
        n5.tables.clear();                                       // meanwhile he deletes the Packing table on the PC
        NotionSync.sync(trip, n5, "PAGE");
        eq("the offline addition isn't lost", trip.show("packing", tue), "Packing list: passport.");
        eq("it's in a new Packing table in Notion", n5.tables.values() + " " + n5.words("Packing"), "[Packing] [passport]");
        Lists quiet = new Lists();
        quiet.add("packing", new String[]{"charger"});
        Fake n6 = new Fake();
        NotionSync.sync(quiet, n6, "PAGE");
        n6.tables.clear();
        NotionSync.sync(quiet, n6, "PAGE");
        eq("nothing new on the phone: the deleted list just goes", quiet.show("", tue) + " " + n6.tables.values(), "No lists yet. []");

        System.out.println(fails == 0 ? "notion: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
