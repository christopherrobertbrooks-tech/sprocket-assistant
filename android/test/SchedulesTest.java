package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;

/** Scheduled tasks (11-19 roadmap): times, repeats, cancelling by number or words, late runs, the quiet answer. */
public class SchedulesTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static String err(Runnable r) { try { r.run(); return "no error"; } catch (RuntimeException e) { return e.getCause() == null ? e.getMessage() : e.getCause().getMessage(); } }

    interface Th { void run() throws Exception; }
    static String fails(Th t) { try { t.run(); return "no error"; } catch (Exception e) { return e.getMessage(); } }

    public static void main(String[] a) throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 19, 21, 30);   // a Thursday
        Schedules s = new Schedules();
        String r = s.add("check today's weather here", "2026-11-20T08:00", "once", "it will rain today", now);
        eq("scheduled once, with the day and time", r.contains("#1: check today's weather here — Friday, November 20 at 8:00 AM"), true);
        eq("the condition is in it", r.contains("only tells him if: it will rain today"), true);
        eq("a time in the past is refused", fails(() -> s.add("x", "2026-11-19T08:00", "once", "", now)).contains("already passed"), true);
        eq("a bad time is refused", fails(() -> s.add("x", "tomorrow 8", "once", "", now)).contains("YYYY-MM-DDTHH:MM"), true);
        eq("hourly isn't possible", fails(() -> s.add("x", "2026-11-20T08:00", "hourly", "", now)).contains("aren't possible yet"), true);
        s.add("see if Rosie's Burgers has a deal on Eats", "2026-11-20T17:00", "weekly", "", now);
        eq("weekly says the day", s.tasks.get(1).line().contains("then every Friday"), true);
        // a repeat whose first time already passed today starts at the next one
        s.add("check my cameras", "2026-11-19T07:00", "daily", "", now);
        eq("a passed daily starts tomorrow", s.tasks.get(2).at.toString(), "2026-11-20T07:00");
        // weekdays skip the weekend
        Schedules w = new Schedules();
        w.add("check the lunch specials", "2026-11-21T11:00", "weekdays", "", now);   // Saturday -> Monday
        eq("weekdays: Saturday moves to Monday", w.tasks.get(0).at.toString(), "2026-11-23T11:00");
        w.done(w.tasks.get(0), LocalDateTime.of(2026, 11, 23, 11, 1));
        eq("then Tuesday", w.tasks.get(0).at.toString(), "2026-11-24T11:00");
        eq("Friday's next is Monday", Schedules.after(LocalDateTime.of(2026, 11, 27, 11, 0), "weekdays").toString(), "2026-11-30T11:00");

        // the list, soonest first
        String l = s.list();
        eq("listed soonest first", l.indexOf("#3") < l.indexOf("#1") && l.indexOf("#1") < l.indexOf("#2"), true);
        eq("nothing scheduled", new Schedules().list(), "Nothing scheduled.");

        // cancel by number or by his words
        eq("by words: the Friday one", s.find("the Friday deal one").id, 2);
        eq("by number", s.find("#3").id, 3);
        eq("unclear words ask which", fails(() -> s.find("the one")).contains("no scheduled task matches"), true);
        Schedules two = new Schedules();
        two.add("check the weather", "2026-11-20T08:00", "once", "", now);
        two.add("check the weather at work", "2026-11-20T12:00", "once", "", now);
        eq("two fit: ask which", fails(() -> two.find("weather")).contains("ask him which"), true);
        eq("unknown number lists them", fails(() -> s.find("9")).contains("Scheduled (soonest first)"), true);
        Schedules.Task gone = s.cancel("pizza deal Rosie's");
        eq("cancelled the deal one", gone.id, 2);
        eq("2 left", s.tasks.size(), 2);
        // undo of a cancel: the same task scheduled again from its input
        JSONObject back = gone.input();
        s.add(back.getString("what"), back.getString("at"), back.getString("repeat"), back.getString("only_tell_if"), now);
        eq("undo puts it back (new number)", s.tasks.get(2).line().contains("see if Rosie's Burgers has a deal on Eats — Friday, November 20 at 5:00 PM, then every Friday"), true);

        // due, late, done
        LocalDateTime eight = LocalDateTime.of(2026, 11, 20, 8, 0);
        eq("at 8 tomorrow: weather and cameras due (cameras at 7 first)", s.due(eight).size() + " " + s.due(eight).get(0).id, "2 3");
        Schedules.Task weather = s.find("#1");
        eq("an hour late is fine", Schedules.tooLate(weather, eight.plusHours(1)), false);
        eq("seven hours late is too late", Schedules.tooLate(weather, eight.plusHours(7)), true);
        s.done(weather, eight);
        eq("a one-off goes after it runs", s.tasks.contains(weather), false);
        Schedules.Task cams = s.find("cameras");
        s.done(cams, LocalDateTime.of(2026, 11, 22, 9, 0));   // the phone was off two days: next is after now, not every missed day
        eq("a repeat moves past now", cams.at.toString(), "2026-11-23T07:00");
        eq("the soonest for the alarm", s.soonest().toString(), "2026-11-20T17:00");

        // the quiet answer and the note
        eq("quiet", Schedules.quiet("NOTHING TO REPORT"), true);
        eq("quiet with a dot", Schedules.quiet("Nothing to report."), true);
        eq("a real answer isn't", Schedules.quiet("It'll rain at 3, nothing to report otherwise"), false);
        Schedules.Task rain = new Schedules.Task(5, "check today's weather", "it will rain today", "once", eight);
        String note = Schedules.note(rain, true);
        eq("the note says he isn't watching", note.contains("He isn't watching"), true);
        eq("the note carries the quiet word", note.contains("exactly NOTHING TO REPORT"), true);
        eq("couldn't find out is worth telling", note.contains("if you couldn't find out, say that instead"), true);
        eq("late says so", note.contains("later than set"), true);
        eq("no condition, no quiet word", Schedules.note(new Schedules.Task(6, "x", "", "once", eight), false).contains(Schedules.QUIET), false);
        eq("its message in the chat", Schedules.question(rain), "⏰ Scheduled: check today's weather");

        // saved and loaded
        Schedules again = Schedules.load(s.toJson().toString());
        eq("round trip", again.list(), s.list());
        eq("next number kept", again.nextId, s.nextId);
        eq("bad file = nothing", Schedules.load("{oops").tasks.size(), 0);
        Schedules full = new Schedules();
        for (int i = 0; i < Schedules.MOST; i++) full.add("t" + i, "2026-11-20T08:00", "once", "", now);
        eq("at most 20", fails(() -> full.add("one more", "2026-11-20T08:00", "once", "", now)).contains("cancel one first"), true);

        // Codex 055 #7: an impossible date is an error, not another day
        {
            Schedules z = new Schedules();
            LocalDateTime t0 = LocalDateTime.of(2026, 11, 20, 9, 0);
            eq("February 30 refused", fails(() -> z.add("x", "2027-02-30T08:00", "once", "", t0)), "at must be a local date and time, YYYY-MM-DDTHH:MM");
            eq("25 o'clock refused", fails(() -> z.add("x", "2027-02-03T25:00", "once", "", t0)), "at must be a local date and time, YYYY-MM-DDTHH:MM");
            eq("nothing saved for them", z.tasks.size(), 0);
            eq("a real date still works", fails(() -> z.add("x", "2028-02-29T08:00", "once", "", t0)), "no error");
            eq("seconds on the end still fine", fails(() -> z.add("y", "2027-03-01T08:00:00", "once", "", t0)), "no error");
        }

        // Codex 055 #1, 055b #4 #5: cancelled while waiting, a run cut off, the safety alarm
        {
            Schedules z = new Schedules();
            LocalDateTime t0 = LocalDateTime.of(2026, 11, 20, 9, 0);
            z.add("turn the volume up", "2026-11-20T10:00", "daily", "", t0);
            z.add("check the weather", "2026-11-20T11:00", "once", "", t0);
            Schedules.Task va = z.tasks.get(0);
            eq("still scheduled", z.has(va), true);
            z.cancel("1");
            eq("cancelled: not scheduled any more (the service skips it)", z.has(va), false);
            z.add("turn the volume up", "2026-11-20T10:00", "daily", "", t0);
            va = z.tasks.get(1);
            LocalDateTime at10 = LocalDateTime.of(2026, 11, 20, 10, 0);
            eq("due at 10", z.due(at10).size(), 1);
            z.claim(va, at10);
            eq("a started run isn't due again", z.due(at10.plusMinutes(5)).size(), 0);
            eq("while it runs, the alarm is only its safety time", z.soonest(), at10.plusMinutes(Schedules.MAX_RUN_MIN));
            eq("nothing cut off yet in memory", z.interrupted().size(), 1);
            Schedules back2 = Schedules.load(z.toJson().toString());   // the app was stopped mid-run, then started again
            eq("the start is saved", back2.interrupted().size(), 1);
            eq("saved start time", back2.interrupted().get(0).started, at10);
            eq("a cut-off run is not due again (it's reported, not done twice)", back2.due(at10.plusMinutes(30)).size(), 0);
            Schedules.Task cut = back2.interrupted().get(0);
            back2.done(cut, at10.plusMinutes(Schedules.MAX_RUN_MIN));
            eq("reported: the start cleared", back2.interrupted().size(), 0);
            eq("a daily one moves to tomorrow", cut.at, at10.plusDays(1));
            eq("the alarm goes back2 to the next task", back2.soonest(), LocalDateTime.of(2026, 11, 20, 11, 0));
            eq("no start saved when not running", back2.toJson().toString().contains("started"), false);
            z.done(va, at10.plusMinutes(2));
            eq("a finished run clears its start", z.interrupted().size(), 0);
            eq("a scheduled task can't schedule tasks: the words", Schedules.NOT_WHILE_SCHEDULED.contains("can't add or cancel"), true);
        }

        // the tools
        JSONArray defs = new JSONArray();
        Schedules.definitions(defs);
        eq("three tools", defs.length(), 3);
        eq("schedule_task says set_reminder is for reminders", defs.getJSONObject(0).getString("description").contains("set_reminder, not this"), true);
        boolean inAll = false;
        JSONArray all = Claude.tools(new JSONObject().put("type", "web_search_20250305").put("name", "web_search"));
        for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getString("name").equals("schedule_task")) inAll = true;
        eq("in Sprocket's tool list", inAll, true);

        System.out.println(fails == 0 ? "schedules: all " + n + " checks pass" : "schedules: " + fails + " of " + n + " checks FAILED");
        if (fails > 0) System.exit(1);
    }
}
