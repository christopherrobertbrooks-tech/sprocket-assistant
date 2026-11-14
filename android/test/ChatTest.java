package dev.sprocket.app;

import java.util.List;

/** Chat: what goes to Claude and what survives a restart. */
public class ChatTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static String roles(List<Chat.Turn> w) {
        StringBuilder b = new StringBuilder();
        for (Chat.Turn t : w) b.append(t.role.charAt(0));
        return b.toString();
    }

    public static void main(String[] a) throws Exception {
        Chat c = new Chat();
        c.add("user", "hi", 1);
        c.add("assistant", "BOSS!", 2);
        c.add("user", "weather?", 3);
        c.add("error", "Claude is busy right now", 4);       // shown, never sent
        c.add("user", "weather??", 5);                      // he asked again: joined with the first try
        eq("errors left out, his two tries joined", roles(c.window(24)), "uau");
        eq("joined text", c.window(24).get(2).text, "weather?\n\nweather??");
        c.add("assistant", "", 6);                          // a reply that never came
        eq("empty replies left out", roles(c.window(24)), "uau");
        c.add("assistant", "Rain.", 7);
        eq("window of 2 starts with him", roles(c.window(2)), "ua");
        eq("window of 3 skips a leading reply", roles(c.window(3)), "ua");
        eq("window of 4", roles(c.window(4)), "uaua");

        // a long chat: the window's start moves in steps of 12, not every message, so the history the cache holds
        // stays the same for several questions in a row
        Chat l = new Chat();
        for (int i = 0; i < 35; i++) l.add(i % 2 == 0 ? "user" : "assistant", "m" + i, i);
        eq("up to 35 messages all go", l.window(24).size() + " " + l.window(24).get(0).text, "35 m0");
        l.add("assistant", "m35", 35);
        eq("at 36 the start jumps 12", l.window(24).size() + " " + l.window(24).get(0).text, "24 m12");
        for (int i = 36; i < 47; i++) l.add(i % 2 == 0 ? "user" : "assistant", "m" + i, i);
        eq("then stays put", l.window(24).size() + " " + l.window(24).get(0).text, "35 m12");
        l.add("assistant", "m47", 47);
        eq("and jumps again at 48", l.window(24).size() + " " + l.window(24).get(0).text, "24 m24");

        Chat ph = new Chat();
        ph.add("user", "this?", 1).photos.add("a.jpg");
        ph.add("error", "Claude is busy right now", 2);
        ph.add("user", "and this?", 3).photos.add("b.jpg");
        eq("a retry joined with the failed try keeps both photos", ph.window(24).get(0).photos, "[a.jpg, b.jpg]");
        eq("photos saved and loaded", Chat.fromJson(ph.toJson()).turns.get(2).photos, "[b.jpg]");
        eq("a photo with no words still goes", Chat.fromJson("{\"turns\":[{\"role\":\"user\",\"text\":\"\",\"photos\":[\"c.jpg\"]}]}").window(24).size(), 1);

        // what his tools did goes with his next message, so Claude knows it's done and doesn't do it again (the owner
        // 2026-10-17: "after sending a text message the next prompt tries to send as a message to the same person")
        Chat tx = new Chat();
        tx.add("user", "text Chelsey I'm on my way", 1);
        tx.add("action", "Texted Chelsey: \u201cI'm on my way\u201d", 2);   // recorded before the reply, as the app does
        tx.add("assistant", "On it, boss.", 3);
        tx.add("user", "what's the weather", 4);
        List<Chat.Turn> tw = tx.window(24);
        eq("done, then his words", roles(tw) + " " + tw.get(2).text, "uau (From the app, not the boss: already done after their "
                + "last message, so don't do it again unless they ask: Texted Chelsey: \u201cI'm on my way\u201d.)\n\nwhat's the weather");
        eq("the message that asked is sent as it was (the cache keeps it)", tw.get(0).text, "text Chelsey I'm on my way");
        Chat nr = new Chat();
        nr.add("user", "text Chelsey hi", 1);
        nr.add("action", "Texted Chelsey: \u201chi\u201d", 2);
        nr.add("action", "Alarm: 5:00 PM", 3);
        nr.add("user", "what's the weather", 4);                // no reply came: his two messages joined, the record between
        eq("no reply: still told it was done", roles(nr.window(24)) + " " + nr.window(24).get(0).text, "u text Chelsey hi\n\n(From "
                + "the app, not the boss: already done after their last message, so don't do it again unless they ask: Texted Chelsey: "
                + "\u201chi\u201d; Alarm: 5:00 PM.)\n\nwhat's the weather");
        Chat pn = new Chat();
        pn.add("user", "text Toni and call Mom", 1);
        pn.add("action", "Texted Toni ((555) 123-4567): \u201csee you at (5)\u201d", 2);
        pn.add("action", "Called Mom (+15550100)", 3);
        pn.add("assistant", "Done.", 4);
        pn.add("user", "thanks", 5);
        eq("their numbers never go to Claude (his words do)", pn.window(24).get(2).text.split("\n")[0], "(From the app, not the boss: "
                + "already done after their last message, so don't do it again unless they ask: Texted Toni: \u201csee you at (5)\u201d; Called Mom.)");
        Chat la = new Chat();
        la.add("user", "flashlight", 1);
        la.add("action", "Flashlight on", 2);
        eq("nothing after: nothing added", la.window(24).get(0).text, "flashlight");

        Chat fd = new Chat();
        fd.add("assistant", "{\"at\": 1, \"bottom_line\": \"Rain after 5.\", \"sections\": []}", 1).kind = "feed";
        fd.add("user", "what about tomorrow?", 2);
        eq("a morning brief reaches Claude as plain lines", fd.window(24).size() + " " + fd.window(24).get(0).text, "1 what about tomorrow?");
        Chat fd2 = new Chat();
        fd2.add("user", "morning", 1);
        fd2.add("assistant", "{\"at\": 1, \"bottom_line\": \"Rain after 5.\", \"sections\": []}", 2).kind = "feed";
        eq("and as the brief, not its JSON", fd2.window(24).get(1).text, "Morning brief. Bottom line: Rain after 5.");

        Chat sd = new Chat();
        sd.add("user", "make me a picture of a robot", 1);
        sd.add("assistant", "The studio is making it.", 2);
        sd.add("assistant", "{\"kind\": \"picture\", \"prompt\": \"a robot\", \"file\": \"p.png\"}", 3).kind = "studio";
        sd.add("user", "make it bigger", 4);
        eq("a studio card reaches Claude as what it made, not its JSON", sd.window(24).get(1).text,
                "The studio is making it.\n\n(The studio made a picture from: \"a robot\")");
        eq("and is saved as one", Chat.fromJson(sd.toJson()).turns.get(2).kind, "studio");

        Chat br = new Chat();
        br.add("user", "Research: an article", 1);
        br.add("assistant", "# Brief\n- a", 2).kind = "brief";
        eq("a research brief: saved and loaded as one", Chat.fromJson(br.toJson()).turns.get(1).kind, "brief");
        eq("and Claude has it for follow-up questions", Chat.fromJson(br.toJson()).window(24).get(1).text, "# Brief\n- a");

        Chat ac = new Chat();
        ac.add("user", "text mom", 1);
        ac.add("action", "Texted Mom (+15550100): running late", 2);   // a record on screen, not a message to Claude
        ac.add("assistant", "Sent, boss.", 3);
        eq("action lines stay on the screen only", roles(ac.window(24)), "ua");

        c.turns.get(1).model = Claude.SONNET;
        c.turns.get(1).cost = 0.0042;
        Chat back = Chat.fromJson(c.toJson());
        eq("saved and loaded", back.turns.size() + " " + back.turns.get(1).text + " " + back.turns.get(1).model + " " + back.turns.get(1).cost,
                c.turns.size() + " BOSS! " + Claude.SONNET + " 0.0042");
        eq("a damaged file starts fresh", Chat.fromJson("{not json").turns.size(), 0);

        System.out.println(fails == 0 ? "chat: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
