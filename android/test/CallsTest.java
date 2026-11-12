package dev.sprocket.app;

import java.util.Arrays;

/** The call announcer's plain decisions on the desktop JDK: which notification is a ringing call, what Sprocket says,
 * and what his answer means. No phone. */
public class CallsTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) {
        // a ringing call: the call category with both Answer and Decline buttons (Samsung's and Google's wording)
        eq("Samsung's ringing call", Calls.isRinging("call", Arrays.asList("Decline", "Answer")), true);
        eq("Google's ringing call", Calls.isRinging("call", Arrays.asList("Decline", "Answer")), true);
        eq("a call in progress (Hang up, Speaker) isn't ringing", Calls.isRinging("call", Arrays.asList("Hang up", "Speaker")), false);
        eq("a text with a Reply button isn't a call", Calls.isRinging("msg", Arrays.asList("Reply", "Mark as read")), false);
        eq("no category: not a call", Calls.isRinging(null, Arrays.asList("Answer", "Decline")), false);
        eq("Answer is button 2", Calls.button(Arrays.asList("Decline", "Answer"), Calls.Choice.ANSWER), 1);
        eq("Decline is button 1", Calls.button(Arrays.asList("Decline", "Answer"), Calls.Choice.DECLINE), 0);
        eq("Reject counts as Decline", Calls.button(Arrays.asList("Reject", "Accept"), Calls.Choice.DECLINE), 0);
        eq("Text has no notification button", Calls.button(Arrays.asList("Decline", "Answer"), Calls.Choice.TEXT), -1);

        // who is calling: the title, unless the title is just "Incoming call"
        eq("a contact", Calls.caller("Toni", "Incoming call"), "Toni");
        eq("title says incoming call: the text names them", Calls.caller("Incoming call", "Alex"), "Alex");
        eq("nothing at all", Calls.caller("", ""), "");

        // what Sprocket says: a handful of lines in turn, each ending in the same question
        eq("line 1", Calls.announcement("Toni", 0),
                "Toni is calling. Answer, decline, or text?");
        eq("line 2", Calls.announcement("Toni", 1),
                "Call from Toni. Answer, decline, or text?");
        eq("a number is read digit by digit", Calls.announcement("(555) 123-4567", 0),
                "An unknown number, 5 5 5, 1 2 3, 4 5 6 7 is calling. Answer, decline, or text?");
        eq("no caller ID", Calls.announcement("", 0),
                "Someone is calling. Answer, decline, or text?");
        java.util.Set<String> lines = new java.util.HashSet<>();
        for (int i = 0; i < Calls.LINES; i++) {
            String l = Calls.announcement("Toni", i);
            lines.add(l);
            eq("line " + (i + 1) + " names her and asks", l.contains("Toni") && l.endsWith("Answer, decline, or text?"), true);
        }
        eq("every line is different", lines.size(), Calls.LINES);
        eq("four lines", Calls.LINES, 4);
        eq("no line puts the phone in Sprocket's pocket", java.util.stream.IntStream.range(0, Calls.LINES)
                .anyMatch(i -> Calls.announcement("Toni", i).contains("my pocket")), false);
        eq("past the last line: back to the first", Calls.announcement("Toni", Calls.LINES), Calls.announcement("Toni", 0));

        // the hours, from Settings: his schedule written the settings way
        String his = "mon-thu 6-23, fri 6-24, sat 0-24, sun 0-23";
        java.time.DayOfWeek SAT = java.time.DayOfWeek.SATURDAY, SUN = java.time.DayOfWeek.SUNDAY, MON = java.time.DayOfWeek.MONDAY,
                FRI = java.time.DayOfWeek.FRIDAY;
        eq("Saturday 3 am", Calls.onDuty(his, SAT, 3, 0), true);
        eq("Saturday 11:30 pm", Calls.onDuty(his, SAT, 23, 30), true);
        eq("Sunday 2 am", Calls.onDuty(his, SUN, 2, 0), true);
        eq("Sunday 10:59 pm", Calls.onDuty(his, SUN, 22, 59), true);
        eq("Sunday 11 pm: off", Calls.onDuty(his, SUN, 23, 0), false);
        eq("Monday 5:59 am: off", Calls.onDuty(his, MON, 5, 59), false);
        eq("Monday 6 am", Calls.onDuty(his, MON, 6, 0), true);
        eq("Monday 10:59 pm", Calls.onDuty(his, MON, 22, 59), true);
        eq("Monday 11 pm: off", Calls.onDuty(his, MON, 23, 0), false);
        eq("Friday 11:30 pm: on, into Saturday", Calls.onDuty(his, FRI, 23, 30), true);
        eq("Friday 5 am: off", Calls.onDuty(his, FRI, 5, 0), false);
        eq("Thursday 11:30 pm: off", Calls.onDuty(his, java.time.DayOfWeek.THURSDAY, 23, 30), false);
        eq("blank: always", Calls.onDuty("", MON, 3, 0), true);
        eq("a day not named is off", Calls.onDuty("sat 0-24", MON, 12, 0), false);
        eq("days wrapping the week", Calls.onDuty("fri-mon 8-20", SUN, 12, 0) + " " + Calls.onDuty("fri-mon 8-20", java.time.DayOfWeek.WEDNESDAY, 12, 0), "true false");
        eq("full day names work", Calls.onDuty("Saturday 9-17", SAT, 10, 0), true);
        eq("nonsense is skipped, not a crash", Calls.onDuty("whenever, mon 6-x", MON, 7, 0), false);
        eq("the card", Calls.card("Toni"), "Toni is calling");
        eq("the card, no caller ID", Calls.card(""), "Someone is calling");

        // what his answer means
        eq("answer", Calls.choice("answer it"), Calls.Choice.ANSWER);
        eq("pick up", Calls.choice("pick up"), Calls.Choice.ANSWER);
        eq("speaker", Calls.choice("put it on speaker"), Calls.Choice.ANSWER);
        eq("yes", Calls.choice("yes"), Calls.Choice.ANSWER);
        eq("decline", Calls.choice("decline"), Calls.Choice.DECLINE);
        eq("ignore", Calls.choice("ignore it"), Calls.Choice.DECLINE);
        eq("don't answer = decline", Calls.choice("don't answer"), Calls.Choice.DECLINE);
        eq("no", Calls.choice("no"), Calls.Choice.DECLINE);
        eq("don\u2019t answer (a curly apostrophe) = decline", Calls.choice("Don\u2019t answer"), Calls.Choice.DECLINE);
        eq("never answer = decline", Calls.choice("Never answer"), Calls.Choice.DECLINE);
        eq("dont answer (no apostrophe) = decline", Calls.choice("dont answer"), Calls.Choice.DECLINE);
        eq("do not pick up = decline", Calls.choice("do not pick up"), Calls.Choice.DECLINE);
        eq("I can\u2019t talk = decline", Calls.choice("I can\u2019t talk"), Calls.Choice.DECLINE);
        eq("not now = decline", Calls.choice("not now"), Calls.Choice.DECLINE);
        eq("a new ringing call starts", Calls.event("b", true, null), Calls.Event.START);
        eq("a second call while one is announced starts (the old card goes)", Calls.event("b", true, "a"), Calls.Event.START);
        eq("the same call ringing on updates", Calls.event("a", true, "a"), Calls.Event.UPDATE);
        eq("the announced call stops ringing: it ends", Calls.event("a", false, "a"), Calls.Event.END);
        eq("some other notification is ignored", Calls.event("b", false, "a"), Calls.Event.IGNORE);
        eq("text", Calls.choice("text them"), Calls.Choice.TEXT);
        eq("decline and text = text", Calls.choice("decline and send a text"), Calls.Choice.TEXT);
        eq("message", Calls.choice("send a message"), Calls.Choice.TEXT);
        eq("unclear", Calls.choice("what"), null);
        eq("nothing", Calls.choice(""), null);
        eq("know isn't no", Calls.choice("I know"), null);

        // the text back: what Sprocket asks, then what the brain is asked to send
        eq("the question", Calls.whatToSay("Toni"), "What should I say to Toni?");
        eq("the question, no caller ID", Calls.whatToSay(""), "What should I say to them?");
        eq("a contact and number", Calls.textAsk("Toni", "+15551234567", "I'll call you back in ten"),
                "Text Toni (+15551234567): I'll call you back in ten");
        eq("a number only", Calls.textAsk("+15551234567", "+15551234567", "who is this?"), "Text +15551234567: who is this?");
        eq("a name, no number", Calls.textAsk("Toni", "", "busy"), "Text Toni: busy");

        System.out.println(fails == 0 ? "calls: all " + n + " checks pass" : "calls: " + fails + " of " + n + " checks FAIL");
        if (fails > 0) System.exit(1);
    }
}
