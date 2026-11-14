package dev.sprocket.app;

/** Memory on the desktop JDK: what Sprocket keeps about him, what he can delete, and what Claude is told. */
public class MemoryTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        Memory m = new Memory();
        String empty = m.forClaude();
        eq("nothing yet: the block is there anyway (the first note mustn't change the cached part)", empty,
                "What you remember about the boss (they can see and delete these notes in the app; the notes speak to them as \"you\"):"
                        + "\nOther notes you saved come with their message when they fit what they ask.");
        eq("a note", m.add("His sister is Toni.", 1), true);
        eq("the same note twice is kept once", m.add("  his sister is toni. ", 2), false);
        m.add("Works nights on weekends.", 3);
        eq("kept in order", m.notes.size() + " " + m.notes.get(0).text, "2 His sister is Toni.");
        // bigger memory (the owner 2026-11-13): always-on notes go with every message; the rest only when they fit
        String fixed = m.forClaude();
        eq("plain notes aren't in the fixed block, but Claude knows there are more", fixed,
                "What you remember about the boss (they can see and delete these notes in the app; the notes speak to them as \"you\"):"
                        + "\nOther notes you saved come with their message when they fit what they ask.");
        m.add("Grows basil.", 3);
        eq("a new plain note leaves the fixed block as it was (it stays cached)", m.forClaude(), fixed);
        m.delete(m.notes.get(m.notes.size() - 1).id);
        m.add("He likes short answers.", 4, true);
        String told = m.forClaude();
        eq("an always-on note goes every time", told.contains("\n- He likes short answers."), true);
        eq("the plain ones don't", told.contains("sister"), false);
        String fits = m.forQuestion("what's my sister's name again?");
        eq("a plain note that fits goes with the question", fits.contains("- His sister is Toni."), true);
        eq("one that doesn't fit stays home", fits.contains("weekends"), false);
        eq("always-on notes aren't sent twice", fits.contains("short answers"), false);
        eq("nothing fits: nothing added", m.forQuestion("what's the weather tomorrow"), "");
        eq("nothing asked: nothing added", m.forQuestion(null), "");
        eq("word endings don't matter", m.forQuestion("do I work at night").contains("Works nights"), true);
        Memory many = new Memory();
        for (int i = 0; i < 40; i++) many.add("Garden bed " + i + " has tomatoes.", i + 1);
        eq("at most " + Memory.PER_QUESTION + " per question, the newest first", many.forQuestion("tomatoes").split("\n- ").length - 1
                + " " + many.forQuestion("tomatoes").contains("bed 39"), Memory.PER_QUESTION + " true");
        long id = m.notes.get(0).id;
        eq("pinned from the Memory screen", m.setAlways(id, true) + " " + m.forClaude().contains("Toni"), "true true");
        m.setAlways(id, false);
        eq("searched on the Memory screen", m.search("SISTER").size() + " " + m.search("").size(), "1 " + m.notes.size());
        Memory old = Memory.fromJson("{\"version\":1,\"notes\":[{\"id\":1,\"text\":\"Old note.\"}]}");
        eq("notes from before stay always-on (nothing changes until he unpins)", old.notes.get(0).always, true);
        eq("the flag is saved", Memory.fromJson(m.toJson()).notes.get(m.notes.size() - 1).always, true);
        m.delete(m.notes.get(m.notes.size() - 1).id);

        // forgetting: by the words he uses, the closest note; nothing when nothing fits
        eq("forget by a part", m.forget("weekends"), "[Works nights on weekends.]");
        eq("gone", m.notes.size(), 1);
        eq("nothing fits: nothing goes", m.forget("his car"), "[]");
        eq("still there", m.notes.size(), 1);
        m.add("Likes the robot voice.", 4);
        eq("his own delete, by id", m.delete(m.notes.get(0).id) + " " + m.notes.size() + " " + m.notes.get(0).text, "true 1 Likes the robot voice.");

        // too many: the oldest go first
        Memory full = new Memory();
        for (int i = 0; i < Memory.MAX + 5; i++) full.add("note " + i, i);
        eq("at most " + Memory.MAX + ", the newest kept", full.notes.size() + " " + full.notes.get(0).text, Memory.MAX + " note 5");
        eq("too long is cut", new Memory().add("x".repeat(600), 1), true);
        Memory cut = new Memory();
        cut.add("y".repeat(600), 1);
        eq("to " + Memory.LONGEST + " characters", cut.notes.get(0).text.length(), Memory.LONGEST);

        Memory back = Memory.fromJson(m.toJson());
        eq("saved and loaded", back.notes.size() + " " + back.notes.get(0).text + " " + (back.notes.get(0).id == m.notes.get(0).id), "1 Likes the robot voice. true");
        eq("a damaged file starts empty", Memory.fromJson("{oops").notes.size(), 0);

        // the tools Claude has for it: no example facts about him in their words
        String defs = Memory.definitions().toString();
        eq("remember and forget", Memory.definitions().getJSONObject(0).getString("name") + " "
                + Memory.definitions().getJSONObject(1).getString("name"), "remember forget");
        eq("remember can mark a note always-on", Memory.definitions().getJSONObject(0).getJSONObject("input_schema")
                .getJSONObject("properties").has("always"), true);
        eq("notes are said to them, not about 'the boss'", defs.contains("not about \\\"the boss\\\""), true);
        eq("no made-up facts in the examples", defs.toLowerCase().contains("vegetarian") || defs.contains("Toni"), false);

        // his About-you page (⋯ Memory): his text, tidied, with a line telling Claude what it is; nothing when empty
        eq("no page: nothing for Claude", Memory.aboutForClaude("  \n ") + " " + Memory.aboutForClaude(null), "null null");
        String page = Memory.aboutForClaude("## Who he is\r\n- Designer.   \r\n\r\n\r\n\r\n## His people\n- A sister.\n");
        eq("the page: said what it is, then his text", page.startsWith("About the boss (they approved every word and edit it in the app;"), true);
        eq("tidied: line ends, trailing spaces, blank runs", page.substring(page.indexOf("\n\n") + 2), "## Who he is\n- Designer.\n\n## His people\n- A sister.");
        eq("kept to " + Memory.ABOUT_LONGEST + " characters", Memory.tidyAbout("z".repeat(9000)).length(), Memory.ABOUT_LONGEST);
        eq("tidy of nothing", "[" + Memory.tidyAbout(null) + "]", "[]");

        System.out.println(fails == 0 ? "memory: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
