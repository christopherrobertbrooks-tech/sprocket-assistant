package dev.sprocket.app;

/** Goals on the desktop JDK: adding, finding by his words, ticking off, what Claude is told, saving. */
public class GoalsTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        Goals g = new Goals();
        eq("none yet: nothing for Claude", g.forClaude() + " | " + g.show(), "null | No goals yet.");
        eq("a goal", g.add("Test Sprocket for a few days", 1), true);
        eq("the same goal again is kept once", g.add("  test sprocket for a few days. ", 2), false);
        g.add("Decide on Sprocket's on-screen look", 3);
        g.add("Make a song from the phone", 4);
        eq("found by his words", g.done("the on-screen look", true, 5), "Decide on Sprocket's on-screen look");
        eq("nothing fits: nothing changes", g.done("buy a boat", true, 6), null);
        eq("what he sees and Claude is told", g.show(), "Open goals: Test Sprocket for a few days; Make a song from the phone. "
                + "Done: Decide on Sprocket's on-screen look.");
        eq("in the prompt, with his notes", g.forClaude(), "His goals (he sees them in the app; tick them off with goal_done when "
                + "he says one is done):\n- Test Sprocket for a few days\n- Make a song from the phone\nDone lately: Decide on Sprocket's on-screen look");
        eq("back on", g.done("on-screen look", false, 7) + " " + g.open().size(), "Decide on Sprocket's on-screen look 3");
        eq("removed", g.remove("make a song") + " " + g.goals.size(), "Make a song from the phone 2");
        eq("his own delete, by id", g.delete(g.goals.get(0).id) + " " + g.goals.size(), "true 1");
        eq("his tap ticks it", g.toggle(g.goals.get(0).id, 9) + " " + g.goals.get(0).done, "true true");

        Goals full = new Goals();
        for (int i = 0; i < Goals.MAX + 3; i++) full.add("goal number " + i, i);
        eq("at most " + Goals.MAX, full.goals.size(), Goals.MAX);
        eq("a long one is cut", new Goals().add("x".repeat(500), 1) && true, true);

        Goals back = Goals.fromJson(g.toJson());
        eq("saved and loaded", back.show(), g.show());
        eq("a damaged file starts empty", Goals.fromJson("{nope").goals.size(), 0);

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Goals.definitions().length(); i++) names.append(i > 0 ? " " : "").append(Goals.definitions().getJSONObject(i).getString("name"));
        eq("the tools", names, "goal_add goal_done goal_remove goals_show");

        System.out.println(fails == 0 ? "goals: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
