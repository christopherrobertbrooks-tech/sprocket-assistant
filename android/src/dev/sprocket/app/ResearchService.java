package dev.sprocket.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A research job that keeps going when he leaves Sprocket (the owner 2026-10-17: "Keeps going, tells me"): a foreground
 * service with a small "Researching" notification that shows each search and page as it happens, then the brief in
 * the chat (MainActivity.addBrief), a "ready" notification, and -- if he is in the app -- the one line said aloud.
 * Jobs run one after another; the service ends when the last one does.
 */
public final class ResearchService extends Service {
    static final String WORK = "research", DONE = "research-done";
    private static final int WORK_ID = 41;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(50);
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private final AtomicInteger left = new AtomicInteger();

    /** Start a job (from the screen, a shared article, or Sprocket's research tool). */
    static void start(Context c, String question, String url, String title) {
        Intent i = new Intent(c, ResearchService.class).putExtra("question", question).putExtra("url", url)
                .putExtra("title", title == null || title.isEmpty() ? question : title);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        channels(this);
        String title = i == null ? "" : i.getStringExtra("title");
        Notification n = note(this, WORK, "Researching", shorter(title), true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(WORK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(WORK_ID, n);
        if (i == null) { stopIfDone(); return START_NOT_STICKY; }
        left.incrementAndGet();
        String question = i.getStringExtra("question"), url = i.getStringExtra("url");
        jobs.execute(() -> run(question == null ? "" : question, url == null ? "" : url, title == null ? "" : title));
        return START_NOT_STICKY;
    }

    private void run(String question, String url, String title) {
        Context app = getApplicationContext();
        NotificationManager nm = getSystemService(NotificationManager.class);
        try {
            String today = new SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(new Date());
            int[] steps = {0};
            Claude.Reply r = Claude.send(MainActivity.brain(), Research.request(question, url, today), new Claude.Listener() {
                public void text(String soFar) { }
                public void sentence(String s, String mood) { }
                public void looked(String tool, String what) {
                    steps[0]++;
                    String doing = ("web_fetch".equals(tool) ? "Reading " + Claude.site(what) : "Searching: " + what);
                    nm.notify(WORK_ID, note(app, WORK, "Researching: " + shorter(title), steps[0] + ". " + doing, true));
                }
            }, null, null);
            Research.Brief b = Research.brief(r.text);
            new Handler(Looper.getMainLooper()).post(() -> MainActivity.addBrief(b, r, title));
            nm.notify(NEXT_ID.getAndIncrement(), note(app, DONE, "Brief ready", b.title, false));
        } catch (Exception e) {
            String why = Net.offline(e) ? "no internet" : e.getMessage();
            new Handler(Looper.getMainLooper()).post(() -> MainActivity.addResearchError(title, why));
            nm.notify(NEXT_ID.getAndIncrement(), note(app, DONE, "Research didn't finish", shorter(title) + ": " + why, false));
        } finally {
            if (left.decrementAndGet() <= 0) stopIfDone();
        }
    }

    private void stopIfDone() {
        if (left.get() > 0) return;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    private static String shorter(String s) { return s == null ? "" : s.length() > 80 ? s.substring(0, 77) + "..." : s; }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(WORK, "Research in progress", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(DONE, "Research briefs", NotificationManager.IMPORTANCE_DEFAULT));
    }

    /** A notification that opens Sprocket when tapped. */
    static Notification note(Context c, String channel, String title, String text, boolean ongoing) {
        return note(c, channel, title, text, ongoing, android.R.drawable.ic_menu_search);
    }

    static Notification note(Context c, String channel, String title, String text, boolean ongoing, int icon) {
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
        return b.setSmallIcon(icon).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(ongoing)
                .setAutoCancel(!ongoing).build();
    }
}
