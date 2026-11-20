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
import android.os.IBinder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the scheduled tasks whose time has come (Schedules), one at a time, as a foreground service so it finishes with
 * the phone asleep: each is asked of the brain like his own question (MainActivity.runScheduled), quietly, and the
 * answer goes in a notification; tapping it opens Sprocket and says it (11-19). "Only tell me if" and no: no
 * notification. A card the task needs (send, call, buy, the backup brain) becomes a notification with Yes / No that
 * waits an hour at most (ask).
 */
public final class ScheduleService extends Service {
    static final String WORK = "sched-work", DONE = "sched", ASK = "sched-ask";
    static final String SPEAK = "sprocket_speak";
    private static final int WORK_ID = 71;
    static final long ASK_WAIT_S = 3600;   // an hour at most for a yes (11-19)
    private static final AtomicBoolean running = new AtomicBoolean();

    static void start(Context c) {
        Intent i = new Intent(c, ScheduleService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    /** The app came to the front: a task that's due (Android refused to start it by itself) runs now. */
    static void resume(Context c) {
        if (running.get()) return;
        boolean due;
        synchronized (MainActivity.schedules) {
            due = !MainActivity.schedules.due(LocalDateTime.now()).isEmpty() || !MainActivity.schedules.interrupted().isEmpty();
        }
        if (!due) return;
        c.getSystemService(NotificationManager.class).cancel(ScheduleAlarm.REFUSED_ID);
        try { start(c); } catch (Exception e) { android.util.Log.w("Sprocket", "scheduled task from the app: " + e); }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    /**
     * Android 15 gives this kind of service six hours a day; when that's up, stop now (Codex 055b #6): a question
     * waiting for his yes counts as no and the task says so, the rest wait for the next alarm.
     */
    @Override public void onTimeout(int startId, int fgsType) { timedOut(); }
    @Override public void onTimeout(int startId) { timedOut(); }

    private void timedOut() {
        android.util.Log.w("Sprocket", "scheduled tasks: Android's time limit for background work was reached");
        cancelAsk();
        if (MainActivity.scheduledRun()) MainActivity.stopAnswer();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        channels(this);
        Notification n = ResearchService.note(this, WORK, "Sprocket", "Doing a scheduled task", true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(WORK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(WORK_ID, n);
        if (running.compareAndSet(false, true)) new Thread(this::run, "sprocket-scheduled").start();
        return START_NOT_STICKY;
    }

    private void run() {
        Context app = getApplicationContext();
        android.os.PowerManager.WakeLock wake = getSystemService(android.os.PowerManager.class)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "sprocket:scheduled");
        wake.acquire(90 * 60 * 1000L);   // a task, plus an hour for his yes at most
        try {
            MainActivity.init(app);
            List<Schedules.Task> cut;   // a run the app was stopped in the middle of: say so, never do it twice (Codex 055b #5)
            synchronized (MainActivity.schedules) { cut = MainActivity.schedules.interrupted(); }
            for (Schedules.Task k : cut) {
                tell(app, k, "Interrupted: " + k.what, "Sprocket was stopped partway through this at " + Schedules.when(k.started)
                        + ". It may or may not have finished, so it wasn't run again.");
                finished(k);
            }
            while (true) {
                LocalDateTime now = LocalDateTime.now();
                List<Schedules.Task> due;
                synchronized (MainActivity.schedules) { due = MainActivity.schedules.due(now); }
                if (due.isEmpty()) break;
                Schedules.Task k = due.get(0);
                if (Schedules.tooLate(k, now)) {   // the phone was off past it: a repeat waits for next time, a one-off says so
                    if (k.repeat.equals("once")) tell(app, k, "Missed: " + k.what, "It was set for " + Schedules.when(k.at)
                            + " and the phone was off or asleep then.");
                    finished(k);
                    continue;
                }
                String[] r = null;
                for (int i = 0; i < 120; i++) {   // he's mid-question: after it (ten minutes at most)
                    if (MainActivity.busy()) { Thread.sleep(5000); continue; }
                    synchronized (MainActivity.schedules) {
                        if (!MainActivity.schedules.has(k)) break;   // cancelled while it waited its turn (Codex 055 #1)
                        MainActivity.schedules.claim(k, LocalDateTime.now());
                    }
                    MainActivity.saveSchedules();
                    ScheduleAlarm.schedule(app);   // the safety alarm, in case the app is stopped mid-run (Codex 055b #4)
                    r = MainActivity.runScheduled(k, now.isAfter(k.at.plusMinutes(10)));
                    if (r != MainActivity.BUSY) break;
                    synchronized (MainActivity.schedules) { k.started = null; }   // he started a question first: wait again
                    MainActivity.saveSchedules();
                    r = null;
                    Thread.sleep(5000);
                }
                boolean gone;
                synchronized (MainActivity.schedules) { gone = !MainActivity.schedules.has(k); }
                if (gone) { android.util.Log.i("Sprocket", "scheduled task #" + k.id + " was cancelled before it ran"); continue; }
                if (r == null) {
                    tell(app, k, "Skipped: " + k.what, "Sprocket was busy answering you at " + Schedules.when(k.at) + ".");
                    finished(k);
                    continue;
                }
                if (r[1] != null) tell(app, k, "Couldn't do: " + k.what, r[1]);
                else if (!Schedules.quiet(r[0]) && r[0] != null && !r[0].trim().isEmpty()) tell(app, k, k.what, r[0]);
                android.util.Log.i("Sprocket", "scheduled task #" + k.id + " done" + (r[1] != null ? " (error)" : Schedules.quiet(r[0]) ? " (nothing to report)" : ""));
                finished(k);
            }
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "scheduled tasks: " + e);
        } finally {
            running.set(false);
            ScheduleAlarm.schedule(app);
            if (wake.isHeld()) wake.release();
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
            stopSelf();
        }
    }

    private static void finished(Schedules.Task k) {
        synchronized (MainActivity.schedules) { if (MainActivity.schedules.has(k)) MainActivity.schedules.done(k, LocalDateTime.now()); }
        MainActivity.saveSchedules();
    }

    /** The answer as a notification; tapping it opens Sprocket and says it out loud. */
    private static void tell(Context c, Schedules.Task k, String title, String text) {
        channels(c);
        Intent open = new Intent(c, MainActivity.class).putExtra(SPEAK, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int code = 7100 + (k.id % 100);
        PendingIntent pi = PendingIntent.getActivity(c, code, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, DONE) : new Notification.Builder(c);
        Notification n = b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build();
        c.getSystemService(NotificationManager.class).notify(code, n);
    }

    // ------------------------------------------------------------------ "may I?" while he isn't watching
    private static final Object asks = new Object();
    private static int askId = 0;
    private static CountDownLatch waiting;
    private static int waitingId = -1;
    private static Boolean answered;

    /**
     * Worker thread, during a scheduled task: a card's question as a notification with Yes and No, waiting an hour at
     * most. true = yes, false = no, null = no answer (the task then skips that step and says so).
     */
    static Boolean ask(String text, String yes) throws InterruptedException {
        Context c = MainActivity.appContext();
        channels(c);
        CountDownLatch done = new CountDownLatch(1);
        int id;
        synchronized (asks) { id = ++askId; waiting = done; waitingId = id; answered = null; }
        PendingIntent y = PendingIntent.getBroadcast(c, 7200 + id % 100, new Intent(c, ScheduleAlarm.class).setAction(ScheduleAlarm.YES).putExtra("ask", id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent n = PendingIntent.getBroadcast(c, 7300 + id % 100, new Intent(c, ScheduleAlarm.class).setAction(ScheduleAlarm.NO).putExtra("ask", id),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String body = text + "\n(A scheduled task is asking. No answer within an hour: it's skipped.)";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ASK) : new Notification.Builder(c);
        Notification note = b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Sprocket needs your OK")
                .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(body)).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "No", n).build())
                .addAction(new Notification.Action.Builder(null, yes == null || yes.isEmpty() ? "Yes" : yes, y).build()).build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.notify(7400, note);
        boolean got = done.await(ASK_WAIT_S, TimeUnit.SECONDS);
        nm.cancel(7400);
        synchronized (asks) { waiting = null; waitingId = -1; return got ? answered : null; }
    }

    /** His tap on Yes or No (only the question still waiting counts; an old notification's button does nothing). */
    static void answer(int id, boolean yes) {
        synchronized (asks) {
            if (waiting == null || id != waitingId) return;
            answered = yes;
            waiting.countDown();
        }
    }

    /** Stop pressed mid-task: a waiting question is a no. */
    static void cancelAsk() {
        synchronized (asks) { if (waiting != null) { answered = false; waiting.countDown(); } }
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(WORK, "Doing a scheduled task", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(DONE, "Scheduled task answers", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(ASK, "Scheduled tasks asking for your OK", NotificationManager.IMPORTANCE_HIGH));
    }
}
