package dev.sprocket.app;

import java.time.LocalDateTime;
import java.util.List;

/** Reminders on the desktop JDK: adding, the times said back, what's due, saving and loading. No phone. */
public class RemindersTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static String err(Reminders r, String text, String at, LocalDateTime now) {
        try { r.add(text, at, now); return "added"; } catch (Exception e) { return e.getMessage(); }
    }

    public static void main(String[] a) throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 20, 15, 0);   // a Friday, 3 pm
        Reminders r = new Reminders();
        Reminders.Item d = r.add("Drugstore", "2026-11-20T16:30", now);
        eq("the drugstore reminder", d.id + " " + d.text + " " + Reminders.when(d.at, now), "1 Drugstore 4:30 PM today");
        r.add("Call Mom", "2026-11-21T09:00", now);
        r.add("Trash out", "2026-11-24T19:15", now);
        eq("said back: tomorrow", Reminders.when(LocalDateTime.of(2026, 11, 21, 9, 0), now), "9:00 AM tomorrow");
        eq("the list, soonest first", r.list(now), "#1 4:30 PM today: Drugstore\n#2 9:00 AM tomorrow: Call Mom\n#3 7:15 PM Tue Nov 24: Trash out");

        eq("a time already gone", err(r, "x", "2026-11-20T14:00", now), "that time has already passed");
        eq("a time nobody can mean", err(r, "x", "4:30", now), "the time must be YYYY-MM-DDTHH:MM");
        eq("February 30 isn't a day", err(r, "x", "2027-02-30T09:00", now), "the time must be YYYY-MM-DDTHH:MM");
        eq("nothing to remind", err(r, "  ", "2026-11-20T16:30", now), "say what to remind them of");

        eq("the soonest wakes the phone", r.soonest(), LocalDateTime.of(2026, 11, 20, 16, 30));
        eq("nothing due at 4:29", r.takeDue(now.withHour(16).withMinute(29)).size(), 0);
        List<Reminders.Item> due = r.takeDue(LocalDateTime.of(2026, 11, 20, 16, 30));
        eq("due at 4:30: the drugstore, and only it", due.size() + " " + due.get(0).text, "1 Drugstore");
        eq("shown once: gone from the list", r.list(now).startsWith("#2"), true);
        eq("on time", Reminders.title(due.get(0), LocalDateTime.of(2026, 11, 20, 16, 30)), "Reminder");
        eq("late (the phone was off)", Reminders.title(due.get(0), LocalDateTime.of(2026, 11, 20, 17, 10)), "Reminder (for 4:30 PM)");
        eq("very late", Reminders.title(due.get(0), LocalDateTime.of(2026, 11, 21, 8, 0)), "Missed reminder (4:30 PM)");

        Reminders back = Reminders.load(r.toJson().toString());
        eq("saved and loaded", back.list(now), r.list(now));
        eq("numbers keep counting after a load", back.add("Pills", "2026-11-22T08:00", now).id, 4);
        eq("cancel by number", back.cancel(2).text, "Call Mom");
        eq("cancel a number that isn't there", back.cancel(2), null);
        eq("a broken file loads empty", Reminders.load("{not json").list(now), "No reminders waiting.");
        eq("an empty file loads empty", Reminders.load("").items.size(), 0);

        System.out.println("RemindersTest: " + (n - fails) + "/" + n + " passed");
        if (fails > 0) System.exit(1);
    }
}
