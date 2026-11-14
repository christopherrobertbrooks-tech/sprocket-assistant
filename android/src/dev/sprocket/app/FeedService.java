package dev.sprocket.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes the morning brief (Feed.java says what's in it): started by FeedAlarm at 6:00, or by "Make one now" on the
 * Feed screen. A foreground service, like research, so it finishes with the phone asleep; it gathers the facts on the
 * phone, has Claude write the brief, puts it in the chat, posts a notification and sets tomorrow's alarm.
 */
public final class FeedService extends Service {
    static final String WORK = "feed-work", READY = "feed";
    private static final int WORK_ID = 61, READY_ID = 62, DUE_ID = 63;
    private static final AtomicBoolean running = new AtomicBoolean();

    static void start(Context c) {
        Intent i = new Intent(c, FeedService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        channels(this);
        Notification n = ResearchService.note(this, WORK, "Making your morning brief", "Weather, calendar, news", true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(WORK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(WORK_ID, n);
        if (running.compareAndSet(false, true)) new Thread(this::run, "sprocket-feed").start();   // one brief at a time
        return START_NOT_STICKY;
    }

    private void run() {
        Context app = getApplicationContext();
        NotificationManager nm = getSystemService(NotificationManager.class);
        try {
            MainActivity.init(app);
            ScreenHands.check(app);            // screen control switched off overnight (11-13): told with the brief
            Feed f = MainActivity.feed;
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            String when = new SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US).format(new Date(now)) + " (" + tz.getID() + ")";
            boolean today, machines;
            synchronized (f) { today = f.on.get("today"); machines = f.on.get("machines"); }
            machines = machines && (!Config.homeDash.trim().isEmpty() || !Config.homeStats.trim().isEmpty());   // only if set up
            String weather = "", calendar = "", alerts = "";
            if (today) {
                try { weather = Weather.lookup("", Place.coords(app), Place.where(app).town(), Place.usUnits(app)); }
                catch (Exception e) { weather = "not available (" + e.getMessage() + ")"; }
                try { calendar = Phone.run(app, "check_calendar", new JSONObject().put("from", LocalDate.now().toString())); }
                catch (Exception e) { calendar = "not available"; }
            }
            if (machines) {
                try { alerts = Feed.alerts(Machines.check("both")); }
                catch (Exception e) { alerts = "Needs a look: neither home machine is answering."; }
            }
            MainActivity.syncLists();                      // lists he changed in Notion on the PC (no-op with Notion off)
            String due = "";                               // his list items due today or overdue: a notice now, and in the brief
            java.time.LocalDate day = LocalDate.now();
            synchronized (MainActivity.lists) {
                java.util.List<String> d = MainActivity.lists.due(day), late = MainActivity.lists.overdue(day);
                if (!d.isEmpty()) due = "due today: " + String.join(", ", d) + ".";
                if (!late.isEmpty()) due += (due.isEmpty() ? "" : " ") + "Overdue: " + String.join(", ", late) + ".";
            }
            if (!due.isEmpty()) nm.notify(DUE_ID, ResearchService.note(app, READY, "From your lists", Character.toUpperCase(due.charAt(0)) + due.substring(1), false));
            JSONObject req;
            synchronized (f) { req = Feed.request(f, when, weather, calendar, alerts, Place.where(app).town(), due); }
            Claude.Reply r = Claude.send(MainActivity.brain(), req, new Claude.Listener() {
                public void text(String soFar) { }
                public void sentence(String s, String mood) { }
            }, null, null);
            if (app.getPackageName().endsWith(".test"))   // Sprocket Test: the brief as written, and how long it took, for the runner
                android.util.Log.i("PA-REPLY", "BRIEF " + (System.currentTimeMillis() - now) / 1000 + "s " + r.text.replace('\n', ' '));
            Feed.Brief b = Feed.parse(r.text, now);
            if (b == null) throw new Exception("the brief came back unreadable");
            b.cost = r.cost();
            new Handler(Looper.getMainLooper()).post(() -> MainActivity.addFeed(b));
            nm.notify(READY_ID, ResearchService.note(app, READY, "Morning brief", b.bottomLine, false));
        } catch (Exception e) {
            String why = Net.offline(e) ? "no internet" : e.getMessage();
            android.util.Log.w("Sprocket", "morning brief: " + why);
            nm.notify(READY_ID, ResearchService.note(app, READY, "No morning brief today", why, false));
        } finally {
            FeedAlarm.schedule(app);                       // tomorrow's
            running.set(false);
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            stopSelf();
        }
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(WORK, "Making the morning brief", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(READY, "Morning brief", NotificationManager.IMPORTANCE_DEFAULT));
    }
}
