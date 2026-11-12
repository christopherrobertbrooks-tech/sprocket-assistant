package dev.sprocket.app;

import java.util.List;
import java.util.Locale;

/**
 * The call announcer's plain decisions (the owner 2026-11-10: "I leave my phone on vibrate, I would like for sprocket to
 * announce when I receive a call"): which notification is a ringing call, what Sprocket says and shows, and what his
 * spoken answer means. CallAnnouncer does the phone side.
 */
final class Calls {
    private Calls() { }

    enum Choice { ANSWER, DECLINE, TEXT }

    /** The card's buttons, in this order. */
    static final String[] BUTTONS = {"Answer on speaker", "Decline", "Text"};
    static final Choice[] BUTTON_CHOICES = {Choice.ANSWER, Choice.DECLINE, Choice.TEXT};

    private static final String[] ANSWER_BUTTON = {"answer", "accept"};
    private static final String[] DECLINE_BUTTON = {"decline", "reject"};

    /** A ringing call's notification: the call category, with both an Answer and a Decline button. */
    static boolean isRinging(String category, List<String> buttons) {
        return "call".equals(category) && button(buttons, Choice.ANSWER) >= 0 && button(buttons, Choice.DECLINE) >= 0;
    }

    /** Which of the notification's buttons does this, or -1 (Text has none: it declines, then Sprocket texts). */
    static int button(List<String> buttons, Choice c) {
        String[] words = c == Choice.ANSWER ? ANSWER_BUTTON : c == Choice.DECLINE ? DECLINE_BUTTON : new String[0];
        for (int i = 0; i < buttons.size(); i++) {
            String b = buttons.get(i) == null ? "" : buttons.get(i).toLowerCase(Locale.US);
            for (String w : words) if (b.contains(w)) return i;
        }
        return -1;
    }

    /** Who is calling: the notification's title, or its text when the title only says it's a call. */
    static String caller(String title, String text) {
        String t = title == null ? "" : title.trim(), x = text == null ? "" : text.trim();
        return t.toLowerCase(Locale.US).contains("incoming") || t.toLowerCase(Locale.US).equals("call") ? x : t;
    }

    /** What Sprocket says, in turn; {who} is the caller. Plain, to suit any personality. */
    private static final String[] SAY = {
            "{who} is calling.",
            "Call from {who}.",
            "{who} is ringing you.",
            "You've got a call from {who}.",
    };
    static final int LINES = SAY.length;

    /** Line n (any number: it wraps), then the question. */
    static String announcement(String caller, int n) {
        String who = caller.isEmpty() ? "someone" : Messages.isNumber(caller) ? "an unknown number, " + digits(caller) : caller;
        String line = SAY[Math.floorMod(n, LINES)].replace("{who}", who);
        return Character.toUpperCase(line.charAt(0)) + line.substring(1) + " Answer, decline, or text?";
    }

    /** The hours it announces, from Settings: up to seven "day from-to" parts, e.g. "mon-thu 6-23, fri 6-24, sat 0-24,
     * sun 0-23" (hours 0-24; a day not named is off). Blank = all day, every day. Unreadable parts are skipped. */
    static boolean onDuty(String hours, java.time.DayOfWeek day, int hour, int minute) {
        if (hours == null || hours.trim().isEmpty()) return true;
        String[] names = {"mon", "tue", "wed", "thu", "fri", "sat", "sun"};
        for (String part : hours.toLowerCase(Locale.US).split(",")) {
            String[] w = part.trim().split("\\s+");
            if (w.length != 2) continue;
            String[] days = w[0].split("-"), span = w[1].split("-");
            if (span.length != 2) continue;
            int d0 = java.util.Arrays.asList(names).indexOf(days[0].length() >= 3 ? days[0].substring(0, 3) : days[0]);
            int d1 = days.length > 1 ? java.util.Arrays.asList(names).indexOf(days[1].length() >= 3 ? days[1].substring(0, 3) : days[1]) : d0;
            int from, to;
            try { from = Integer.parseInt(span[0]); to = Integer.parseInt(span[1]); } catch (NumberFormatException e) { continue; }
            if (d0 < 0 || d1 < 0) continue;
            int today = day.getValue() - 1;
            boolean inDays = d0 <= d1 ? today >= d0 && today <= d1 : today >= d0 || today <= d1;
            if (inDays && hour >= from && hour < to) return true;
        }
        return false;
    }

    static String card(String caller) { return caller.isEmpty() ? "Someone is calling" : caller + " is calling"; }

    /** A number said digit by digit, a pause between its groups: "5 5 5, 1 2 3, 4 5 6 7". */
    private static String digits(String number) {
        StringBuilder out = new StringBuilder();
        for (String group : number.replaceAll("[^0-9 ()\\-.]", "").split("[ ()\\-.]+")) {
            if (group.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(String.join(" ", group.split("")));
        }
        return out.toString();
    }

    /** His spoken answer, or null when it isn't clear. Text first ("decline and text him" texts), then the no's
     * ("don't answer" declines: any no-word wins over a yes-word, so a refusal never answers), then the yes's. */
    static Choice choice(String said) {
        String t = " " + (said == null ? "" : said.toLowerCase(Locale.US).replaceAll("[\u2018\u2019\u02bc`]", "'")
                .replaceAll("[^a-z' ]", " ")) + " ";
        if (has(t, "text", "message")) return Choice.TEXT;
        if (has(t, "decline", "ignore", "reject", "don't", "dont", "do not", "no", "nope", "not", "never", "can't", "cant",
                "cannot", "won't", "hang up", "voicemail", "busy"))
            return Choice.DECLINE;
        if (has(t, "answer", "pick up", "pick it up", "accept", "speaker", "yes", "yeah", "take it")) return Choice.ANSWER;
        return null;
    }

    private static boolean has(String t, String... words) {
        for (String w : words) if (t.contains(" " + w + " ")) return true;
        return false;
    }

    /** What a call notification means for the announcer (Codex 040 #4). */
    enum Event { START, UPDATE, END, IGNORE }

    /** key: this notification's; ringingNow: it is a ringing call now; tracked: the call being announced, or null.
     * A new ringing call STARTs (the old card goes first), the announced one UPDATEs while it rings and ENDs when it
     * stops ringing; anything else is IGNOREd. */
    static Event event(String key, boolean ringingNow, String tracked) {
        if (key.equals(tracked)) return ringingNow ? Event.UPDATE : Event.END;
        return ringingNow ? Event.START : Event.IGNORE;
    }

    static String whatToSay(String caller) { return "What should I say to " + (caller.isEmpty() ? "them" : caller) + "?"; }

    /** What the brain is asked, once he has said the words: it sends them through the usual Send card. */
    static String textAsk(String caller, String number, String words) {
        boolean both = !number.isEmpty() && !number.equals(caller);
        return "Text " + caller + (both ? " (" + number + ")" : "") + ": " + words;
    }
}
