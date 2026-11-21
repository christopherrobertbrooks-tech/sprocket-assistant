package dev.sprocket.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * The wake-up for the soonest reminder (Reminders): posts every reminder that's due as a notification, then sets
 * itself for the next. Set again after a restart, app update or clock change. Nothing else is involved: no other app,
 * no brain, no network.
 */
public final class ReminderAlarm extends BroadcastReceiver {
    static final String FIRE = "dev.sprocket.app.REMINDER", CHANNEL = "reminders";

    @Override
    public void onReceive(Context c, Intent i) {
        MainActivity.init(c);
        if (FIRE.equals(i.getAction())) fire(c);
        schedule(c);
    }

    static void fire(Context c) {
        LocalDateTime now = LocalDateTime.now();
        List<Reminders.Item> due;
        synchronized (MainActivity.reminders) { due = MainActivity.reminders.takeDue(now); }
        if (due.isEmpty()) return;
        channel(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        for (Reminders.Item r : due)
            nm.notify(9000 + r.id % 1000, ResearchService.note(c, CHANNEL, Reminders.title(r, now), r.text, false, android.R.drawable.ic_popup_reminder));
        MainActivity.saveReminders();
        android.util.Log.i("Sprocket", "reminders shown: " + due.size());
    }

    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Reminders you asked Sprocket for");
        c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    /** Sets (or clears) the wake-up for the soonest reminder. Exact when Android lets Sprocket ("Alarms & reminders"). */
    static void schedule(Context c) {
        MainActivity.init(c);
        LocalDateTime next;
        synchronized (MainActivity.reminders) { next = MainActivity.reminders.soonest(); }
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 72, new Intent(c, ReminderAlarm.class).setAction(FIRE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (next == null) { am.cancel(pi); return; }
        long at = Math.max(System.currentTimeMillis() + 1000, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        if (FeedAlarm.exact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }
}
