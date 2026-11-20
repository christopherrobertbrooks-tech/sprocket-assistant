package dev.sprocket.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * The alarm for the soonest scheduled task (Schedules), set again after every run, change, restart, app update or
 * clock change; and the Yes / No buttons on a scheduled task's "may I?" notification (ScheduleService.ask).
 */
public final class ScheduleAlarm extends BroadcastReceiver {
    static final String FIRE = "dev.sprocket.app.SCHEDULED_TASK", YES = "dev.sprocket.app.SCHEDULED_YES", NO = "dev.sprocket.app.SCHEDULED_NO";

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction() == null ? "" : i.getAction();
        if (a.equals(YES) || a.equals(NO)) { ScheduleService.answer(i.getIntExtra("ask", -1), a.equals(YES)); return; }
        if (a.equals(FIRE)) {
            try { ScheduleService.start(c); }
            catch (Exception e) {   // Android said no to starting it from here: tell him, and try again in 15 minutes, not
                android.util.Log.w("Sprocket", "scheduled task: " + e);   // every second (Codex 055b #3)
                ScheduleService.channels(c);
                c.getSystemService(android.app.NotificationManager.class).notify(REFUSED_ID, ResearchService.note(c, ScheduleService.DONE,
                        "A scheduled task is waiting", "Android didn't let Sprocket start it by itself. Tap to open Sprocket and run it now.",
                        false, android.R.drawable.ic_lock_idle_alarm));
                schedule(c, RETRY_MS);
            }
            return;
        }
        schedule(c);   // restart, update, clock change: set it again
    }

    /** Sets (or clears) the alarm for the soonest task. Exact when Android lets Sprocket ("Alarms & reminders"). */
    static final int REFUSED_ID = 7099;
    static final long RETRY_MS = 15 * 60 * 1000L;

    static void schedule(Context c) { schedule(c, 1000); }

    /** notBefore: the soonest the alarm may go off from now (a refused start waits RETRY_MS before trying again). */
    static void schedule(Context c, long notBefore) {
        MainActivity.init(c);
        LocalDateTime next;
        synchronized (MainActivity.schedules) { next = MainActivity.schedules.soonest(); }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 71, new Intent(c, ScheduleAlarm.class).setAction(FIRE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (next == null) { am.cancel(pi); return; }
        long at = Math.max(System.currentTimeMillis() + notBefore, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        if (FeedAlarm.exact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        android.util.Log.i("Sprocket", "scheduled task alarm set (" + (FeedAlarm.exact(c) ? "exact" : "nearest") + ")");
    }
}
