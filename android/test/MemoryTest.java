package dev.sprocket.app;

/** Memory on the desktop JDK: what Sprocket keeps about him, what he can delete, and what Claude is told. */
public class MemoryTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        // Codex 053 #2 (11-18): undoing a new note deleted an older similar one; undoing a new usual left none
        Memory mu = new Memory();
        mu.add("Peanut allergy; carries an epipen", 1);
        java.util.List<Memory.Note> mb = mu.copy();
        mu.add("Peanut allergy", 2);
        mu.applyPatch(Memory.patch(mb, mu.copy()));
        eq("undo removes only the new note", mu.notes.size() == 1 && mu.notes.get(0).text.contains("epipen"), true);
        mu.add("Usual at Cafe: tea", 3);
        java.util.List<Memory.Note> mb2 = mu.copy();
        mu.add("Usual at Cafe: coffee", 4);
        mu.applyPatch(Memory.patch(mb2, mu.copy()));
        boolean tea = false; for (Memory.Note n : mu.notes) tea |= n.text.equals("Usual at Cafe: tea");
        eq("undoing a new usual brings the old one back", tea && mu.notes.size() == 2, true);
        // Codex 053 #5: allergy notes saved before this update (version 2, not always on) are pinned once
        Memory v2 = Memory.fromJson("{\"version\":2,\"notes\":[{\"id\":5,\"text\":\"He is allergic to shellfish\",\"always\":false}]}");
        eq("an older allergy note is now always on", v2.notes.get(0).always, true);
        Memory again = new Memory();
        again.add("likes tea", 1);
        eq("saying it again with always pins it", again.add("likes tea", 2, true) && again.notes.get(0).always, true);
        // 11-18 roadmap "Remembers your food": allergies are always on, never pushed out; one usual per place
        Memory food = new Memory();
        food.add("He's allergic to peanuts", 1);
        eq("an allergy is always on, even when saved plainly", food.notes.get(0).always, true);
        food.add("Usual at Hardee's: a Monster Thickburger, no onions, large fries", 2);
        food.add("Usual at Hardee's: two Frisco burgers", 3);
        int hardees = 0; for (Memory.Note n : food.notes) if (n.text.startsWith("Usual at Hardee's")) hardees++;
        eq("a new usual replaces the old one at the same place", hardees, 1);
        eq("and it's the new one", food.notes.get(food.notes.size() - 1).text, "Usual at Hardee's: two Frisco burgers");
        food.add("Usual at Taco Bell: a Crunchwrap Supreme", 4);
        eq("other places keep their own usual", food.notes.size(), 3);
        for (int i = 0; i < Memory.MAX + 5; i++) food.add("filler note number " + i, 10 + i);
        eq("a full notebook never pushes an allergy out", food.notes.get(0).text, "He's allergic to peanuts");
        eq("allergy words", Memory.allergy("celiac, no gluten") && Memory.allergy("I have a shellfish intolerance")
                && !Memory.allergy("he likes peanuts"), true);
        // 11-18: the About card said "Goes to Claude with each message" even when the brain was your own server or Qwen
        eq("the About note names no one brain", Memory.ABOUT_NOTE.contains("Claude"), false);
        eq("the About note keeps its warning", Memory.ABOUT_NOTE.contains("Never passwords"), true);
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
