package dev.sprocket.app;

/** The back-and-forth after a spoken answer: what ends it, what still goes to Claude, and when "Still there?" comes. */
public class ConversationTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) {
        // his stop phrases (V2.md), as the recognizer writes them: capitals, curly apostrophes, a name or thanks around them
        for (String s : new String[]{"That's all for now", "that’s all for now Sprocket", "That's it.", "Thanks, I'm done",
                "never mind", "Nevermind", "Goodbye", "bye Sprocket", "OK that's all", "no that's it", "Stop listening",
                "nothing else", "No thanks", "thank you", "Thanks Sprocket", "That'll be all, boss", "I'm good thanks",
                "Good night Sprocket", "okay bye"})
            eq("ends it: " + s, Conversation.isStop(s), true);
        // things that must still reach Claude: answers to its questions, and real questions that start the same way
        for (String s : new String[]{"no", "okay", "yes", "OK do it", "never mind the timer, set an alarm instead",
                "that's it for the grocery list?", "is that all for now?", "what's the weather", "done", "later",
                "thanks, and what about tomorrow?", "goodbye in Spanish"})
            eq("goes to Claude: " + s, Conversation.isStop(s), false);
        eq("here", Conversation.isHere("Yeah I'm here") + " " + Conversation.isHere("still here") + " " + Conversation.isHere("yes")
                + " " + Conversation.isHere("what's the time"), "true true true false");

        // after a spoken answer: quiet listens follow each other until 12 s of quiet, then "Still there?", then the end
        Conversation c = new Conversation();
        c.begin(0);
        eq("quiet at 6 s: listen on", c.nothing(0, 6_000), Conversation.Next.LISTEN);
        eq("quiet at 12 s: Still there?", c.nothing(6_000, 12_000), Conversation.Next.STILL_THERE);
        eq("quiet after asking: the end", c.nothing(14_000, 19_000), Conversation.Next.END);

        c.begin(0);
        c.nothing(0, 6_000);
        eq("a question in the quiet goes to Claude", c.heard("what about tomorrow", 8_000), Conversation.Next.ASK);
        c.begin(30_000);
        eq("a new answer starts a new 12 s", c.nothing(30_000, 36_000), Conversation.Next.LISTEN);

        c.begin(0);
        c.nothing(0, 6_000);
        c.nothing(6_000, 12_000);
        eq("\"I'm here\" after Still there: listen again, nothing sent", c.heard("I'm here", 14_000), Conversation.Next.LISTEN);
        eq("and the quiet starts over", c.nothing(14_000, 20_000), Conversation.Next.LISTEN);
        eq("so Still there can come again", c.nothing(20_000, 26_500), Conversation.Next.STILL_THERE);

        c.begin(0);
        eq("\"yes\" before any Still there is an answer for Claude", c.heard("yes", 3_000), Conversation.Next.ASK);
        c.begin(0);
        eq("a stop phrase ends it", c.heard("that's all for now", 3_000), Conversation.Next.END);
        c.begin(0);
        eq("the 12 s timer: due in 12 s", c.due(0), 12_000);
        eq("the timer while he's mid-sentence: keep listening", c.timeUp(12_000, true), Conversation.Next.LISTEN);
        eq("the timer in the quiet: Still there?", c.timeUp(12_000, false), Conversation.Next.STILL_THERE);
        eq("and only once", c.timeUp(13_000, false), Conversation.Next.LISTEN);
        eq("then quiet: the end", c.nothing(12_100, 18_000), Conversation.Next.END);
        c.begin(0);
        eq("a timer left over from an earlier round does nothing", c.timeUp(5_000, false), Conversation.Next.LISTEN);
        c.begin(0);
        eq("a recognizer that fails at once doesn't spin", c.nothing(1_000, 1_400), Conversation.Next.END);

        System.out.println(fails == 0 ? "conversation: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
