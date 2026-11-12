package dev.sprocket.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.time.ZoneId;

/**
 * The 6:00 alarm for the morning brief, set again after every brief, restart, app update or clock change. Exact when
 * he has switched on "Alarms & reminders" for Sprocket (an exact alarm is also what lets the brief's foreground
 * service start while the phone sleeps); otherwise Android's nearest-it-can, and a "tap to make it" notification if
 * Android won't let it start by itself.
 */
public final class FeedAlarm extends BroadcastReceiver {
    static final String FIRE = "dev.sprocket.app.MORNING_BRIEF";

    @Override
    public void onReceive(Context c, Intent i) {
        MainActivity.init(c);
        if (!Config.on("brief") || !MainActivity.brainSetUp()) return;   // switched off, or no brain set up yet
        if (!FIRE.equals(i.getAction())) { schedule(c); return; }   // restart, update, clock change: set it again
        try {
            FeedService.start(c);
        } catch (Exception e) {                                     // Android said no to starting it from here
            FeedService.channels(c);
            c.getSystemService(NotificationManager.class).notify(62, ResearchService.note(c, FeedService.READY,
                    "Your morning brief is waiting", "Tap to make it now.", false));
            schedule(c);
        }
    }

    static boolean exact(Context c) {
        return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static long schedule(Context c) {
        long at = Feed.nextAt(System.currentTimeMillis(), ZoneId.systemDefault(), Feed.HOUR, Feed.MINUTE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(c, FeedAlarm.class).setAction(FIRE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (exact(c)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        return at;
    }

    /** The morning brief switched off in Settings: no 6:00 alarm. */
    static void cancel(Context c) {
        c.getSystemService(AlarmManager.class).cancel(PendingIntent.getBroadcast(c, 7, new Intent(c, FeedAlarm.class).setAction(FIRE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    /** His "Alarms & reminders" switch for Sprocket. */
    static void openSwitch(Context c) {
        if (Build.VERSION.SDK_INT < 31) return;
        c.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
