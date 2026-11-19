package dev.sprocket.app;

/** Lists on the desktop JDK: names, adding, ticking off, removing, clearing, what Claude is told. */
public class ListsTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        // Codex 054 #2 (11-18): an item removed and put back by undo kept its words but lost its Notion row
        Lists nl = new Lists();
        nl.add("grocery", new String[]{"milk"});
        nl.copyOf("grocery");
        nl.lists.get("grocery").get(0).id = "page-1";            // as if Notion had it
        java.util.List<Lists.Item> nb = nl.copyOf("grocery");
        nl.remove("grocery", "milk");                              // queued to be deleted in Notion
        String said = nl.applyPatch(Lists.patch("grocery", nb, nl.copyOf("grocery")));
        eq("put back before Notion heard: same row", nl.lists.get("grocery").get(0).id, "page-1");
        eq("and Notion won't delete it", nl.goneItems.contains("page-1"), false);
        Lists nl2 = new Lists();
        nl2.add("grocery", new String[]{"milk"});
        nl2.lists.get("grocery").get(0).id = "page-2";
        java.util.List<Lists.Item> nb2 = nl2.copyOf("grocery");
        nl2.remove("grocery", "milk");
        nl2.goneItems.clear();                                     // Notion already deleted that row
        String said2 = nl2.applyPatch(Lists.patch("grocery", nb2, nl2.copyOf("grocery")));
        eq("after Notion deleted it: back as a new row, said plainly", said2.contains("new row in Notion"), true);
        // Codex 053 #1 (11-18): milk was on the list, "add milk and eggs", undo took BOTH off. Undo now puts back exactly
        // what changed, by the exact item, never by similar words.
        Lists un = new Lists();
        un.add("grocery", new String[]{"milk", "chocolate milk"});
        un.check("grocery", "chocolate milk", true);
        java.util.List<Lists.Item> before = un.copyOf("grocery");
        un.add("grocery", new String[]{"milk", "eggs", "chocolate milk"});   // eggs new; chocolate milk comes back on
        org.json.JSONObject p = Lists.patch("grocery", before, un.copyOf("grocery"));
        un.applyPatch(p);
        eq("undo keeps what was there before", un.items("grocery"), java.util.Arrays.asList("milk", "chocolate milk"));
        eq("undo puts a ticked item back as ticked", un.copyOf("grocery").get(1).done, true);
        // an exact item only: the new milk already gone, the older chocolate milk is never taken instead (053b)
        Lists un2 = new Lists();
        un2.add("grocery", new String[]{"chocolate milk"});
        java.util.List<Lists.Item> b2 = un2.copyOf("grocery");
        un2.add("grocery", new String[]{"milk"});
        org.json.JSONObject p2 = Lists.patch("grocery", b2, un2.copyOf("grocery"));
        un2.remove("grocery", "milk");
        un2.applyPatch(p2);
        eq("undo never takes a similar item instead", un2.items("grocery"), java.util.Arrays.asList("chocolate milk"));
        // clearing a list can be undone too
        Lists un3 = new Lists();
        un3.add("todo", new String[]{"call the vet", "buy crickets"});
        java.util.List<Lists.Item> b3 = un3.copyOf("todo");
        un3.clear("todo", false);
        un3.applyPatch(Lists.patch("todo", b3, un3.copyOf("todo")));
        eq("a cleared list comes back", un3.items("todo"), java.util.Arrays.asList("call the vet", "buy crickets"));
        eq("nothing changed: nothing to undo", Lists.patch("todo", b3, b3), null);
        eq("names are forgiving", Lists.key("My Grocery List") + "|" + Lists.key("groceries") + "|" + Lists.key("the grocery"),
                "grocery|grocery|grocery");
        eq("to-do in any spelling", Lists.key("todo") + "|" + Lists.key("To Do list") + "|" + Lists.key("to-do"), "to-do|to-do|to-do");
        eq("other names kept", Lists.key("Packing List for Iowa"), "packing list for iowa");

        Lists l = new Lists();
        eq("add several", l.add("groceries", new String[]{"milk", "eggs", "Milk ", "oat milk"}), 3);
        eq("one list, in order, no repeats", l.items("grocery"), "[milk, eggs, oat milk]");
        eq("ticked off", l.check("my grocery list", "eggs", true), "eggs");
        eq("the same words first", l.check("grocery", "milk", true), "milk");
        eq("then one containing the other", l.find("grocery", "oat"), 2);
        eq("a/the/some don't matter", l.find("grocery", "some eggs"), 1);
        eq("not there", l.check("grocery", "bread", true), null);
        eq("what Claude is told: open items, then done", l.show("grocery"), "Grocery list: oat milk. Done: milk, eggs.");
        eq("removed", l.remove("grocery", "oat milk") + " " + l.items("grocery"), "oat milk [milk, eggs]");
        eq("clear only the ticked-off ones", l.clear("grocery", true) + " " + l.items("grocery"), "2 []");
        l.add("to do", new String[]{"call the plumber"});
        l.add("grocery", new String[]{"bread"});
        eq("every list at a glance", l.show(""), "Lists: grocery (1 open), to-do (1 open).");
        eq("an empty or missing list", new Lists().show("packing"), "There is no packing list.");
        eq("clear a whole list", l.clear("to-do", false) + " " + l.show(""), "1 Lists: grocery (1 open).");
        eq("delete a list", l.delete("grocery") + " " + l.show(""), "true No lists yet.");

        Lists back = new Lists();
        back.add("grocery", new String[]{"milk", "eggs"});
        back.check("grocery", "milk", true);
        Lists again = Lists.fromJson(back.toJson());
        eq("saved and loaded, ticks kept", again.show("grocery"), "Grocery list: eggs. Done: milk.");
        eq("a damaged file starts empty", Lists.fromJson("{nope").show(""), "No lists yet.");

        // due dates (the owner 2026-11-10: "due dates and reminders")
        java.time.LocalDate tue = java.time.LocalDate.parse("2026-11-10");
        Lists d = new Lists();
        d.addItems("to-do", java.util.Arrays.asList(new Lists.Item("renew tags", false, "2026-11-14", ""),
                new Lists.Item("call the vet", false, "2026-11-10", ""), new Lists.Item("mow", false, "", "")));
        eq("due dates said plainly", d.show("to-do", tue), "To-do list: renew tags (due Sat, Nov 14), call the vet (due today), mow.");
        eq("due today, for the morning", d.due(tue), "[call the vet (to-do)]");
        eq("nothing overdue yet", d.overdue(tue), "[]");
        eq("a day later it's overdue", d.overdue(tue.plusDays(1)) + " " + d.show("to-do", tue.plusDays(1)).contains("call the vet (overdue since Tue, Nov 10)"),
                "[call the vet (to-do, due Tue, Nov 10)] true");
        d.check("to-do", "call the vet", true);
        eq("done items aren't due", d.due(tue) + " " + d.overdue(tue.plusDays(1)), "[] []");
        eq("change a due date", d.change("to-do", "renew", "2026-11-11", null) + " " + d.due(tue.plusDays(1)), "renew tags [renew tags (to-do)]");
        eq("clear a due date", d.change("to-do", "renew tags", "", null) + " " + d.due(tue.plusDays(1)), "renew tags []");
        eq("a bad date is refused", d.change("to-do", "mow", "next friday", null), "bad date");
        eq("not on the list", d.change("to-do", "paint", "2026-11-11", null), null);
        eq("adding it again with a date sets the date", d.addItems("to-do", java.util.Collections.singletonList(new Lists.Item("mow", false, "2026-11-12", "")))
                + " " + d.show("to-do", tue).contains("mow (due Thu, Nov 12)"), "0 true");

        // groceries by aisle (the owner 2026-11-10: "groceries sorted by aisle")
        Lists g = new Lists();
        g.addItems("grocery", java.util.Arrays.asList(new Lists.Item("milk", false, "", "Dairy & eggs"), new Lists.Item("apples", false, "", "Produce"),
                new Lists.Item("eggs", false, "", "dairy and eggs"), new Lists.Item("batteries", false, "", "nonsense aisle"), new Lists.Item("bread", false, "", "")));
        eq("grouped in store order, unknown aisles last", g.show("grocery", tue),
                "Grocery list: Produce: apples. Dairy & eggs: milk, eggs. Other: batteries, bread.");
        eq("aisle names are forgiving", Lists.aisle("frozen foods") + "|" + Lists.aisle("Meat") + "|" + Lists.aisle("") + "|" + Lists.aisle("zzz"),
                "Frozen|Meat & seafood||Other");
        g.check("grocery", "milk", true);
        eq("done items after the aisles", g.show("grocery", tue), "Grocery list: Produce: apples. Dairy & eggs: eggs. Other: batteries, bread. Done: milk.");
        eq("change an aisle", g.change("grocery", "batteries", null, "Household") + " " + g.show("grocery", tue).contains("Household: batteries"), "batteries true");
        eq("the screen's groups", g.groups("grocery").keySet(), "[Produce, Dairy & eggs, Household, Other]");
        eq("other lists aren't grouped", d.groups("to-do").keySet(), "[]");

        Lists kept = Lists.fromJson(g.toJson());
        eq("dates and aisles saved and loaded", kept.show("grocery", tue), g.show("grocery", tue));
        Lists dk = Lists.fromJson(d.toJson());
        eq("due dates saved and loaded", dk.show("to-do", tue), d.show("to-do", tue));

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Lists.definitions().length(); i++) names.append(i > 0 ? " " : "").append(Lists.definitions().getJSONObject(i).getString("name"));
        eq("the tools", names, "list_add list_show list_check list_remove list_clear list_change");
        String defs = Lists.definitions().toString();
        eq("the brain is told the aisles", defs.contains("Produce") && defs.contains("Frozen"), true);

        System.out.println(fails == 0 ? "lists: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
