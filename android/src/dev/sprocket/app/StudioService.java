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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches what Sprocket asked the studio for until it's made (a full video is 20-30 minutes): a small "Studio"
 * notification while it works, then the file saved in files/studio, a card in the chat (MainActivity.addStudio) and a
 * "ready" notification. The jobs it is waiting for are kept in files/studio/pending.json, so if Android ends the app
 * mid-wait, the next time Sprocket opens it picks them up again (resume).
 */
public final class StudioService extends Service {
    static final String WORK = "studio", DONE = "studio-done";
    private static final int WORK_ID = 42;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(150);
    private static final long GIVE_UP = 3 * 3600_000L;       // the studio's own limit is an hour a job, plus a queue
    private static boolean running;                            // these two under StudioService.class
    private static int lastStart;

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "studio");
        d.mkdirs();
        return d;
    }

    private static File pendingFile(Context c) { return new File(dir(c), "pending.json"); }

    static synchronized JSONArray pending(Context c) {
        try { return new JSONArray(new String(Files.readAllBytes(pendingFile(c).toPath()), StandardCharsets.UTF_8)); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static synchronized void savePending(Context c, JSONArray a) {
        try {
            File tmp = new File(dir(c), "pending.json.tmp");
            Files.write(tmp.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(pendingFile(c))) throw new Exception("rename failed");
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "studio jobs not saved: " + e);
        }
    }

    private static synchronized void drop(Context c, String id) {
        JSONArray a = pending(c), kept = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!a.optJSONObject(i).optString("id").equals(id)) kept.put(a.optJSONObject(i));
        savePending(c, kept);
    }

    /** A job the studio queued (its answer to POST /jobs): wait for it. */
    static void watch(Context c, JSONObject job) throws Exception {
        synchronized (StudioService.class) {
            JSONArray a = pending(c);
            a.put(new JSONObject().put("id", job.getString("id")).put("kind", job.optString("kind"))
                    .put("prompt", job.optString("prompt")).put("since", System.currentTimeMillis()).put("base", Studio.at())
                    .put("test", !Studio.base.isEmpty()));
            savePending(c, a);
        }
        start(c);
    }

    /** Sprocket opened: carry on waiting for anything left over. */
    static void resume(Context c) {
        synchronized (StudioService.class) { if (running) return; }
        if (pending(c).length() > 0) start(c);
    }

    private static void start(Context c) {
        try {
            Intent i = new Intent(c, StudioService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {                                // not allowed from the background: picked up on resume
            android.util.Log.w("Sprocket", "studio watch waits for the app: " + e);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        channels(this);
        Notification n = note(this, WORK, "Studio", working(pending(this)), true);
        if (Build.VERSION.SDK_INT >= 29) startForeground(WORK_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(WORK_ID, n);
        synchronized (StudioService.class) {
            lastStart = startId;
            if (running) return START_NOT_STICKY;
            running = true;
        }
        new Thread(this::loop, "studio-watch").start();
        return START_NOT_STICKY;
    }

    private static String working(JSONArray a) {
        if (a.length() == 0) return "Checking the studio";
        if (a.length() == 1) return "Making " + "a " + a.optJSONObject(0).optString("kind");
        return "Making " + a.length() + " things";
    }

    private void loop() {
        Context app = getApplicationContext();
        NotificationManager nm = getSystemService(NotificationManager.class);
        Handler main = new Handler(Looper.getMainLooper());
        int last = -1;
        try {
            while (true) {
                JSONArray a;
                synchronized (StudioService.class) {        // nothing left: stop, unless a new job came in meanwhile
                    a = pending(app);
                    if (a.length() == 0) { running = false; last = lastStart; break; }
                }
                for (int k = 0; k < a.length(); k++) {
                    JSONObject p = a.optJSONObject(k);
                    String id = p.optString("id"), at = p.optString("base", Config.studio.trim());
                    try {
                        JSONObject j = Studio.http(at, "/jobs/" + id, null, 15_000);
                        String status = j.optString("status");
                        if (status.equals("done")) {
                            String file = j.optString("file");
                            Studio.download(at, file, new File(dir(app), file));
                            drop(app, id);
                            main.post(() -> MainActivity.addStudio(j));
                            if (p.optBoolean("test")) selfTest(app, j);
                            nm.notify(NEXT_ID.getAndIncrement(), note(app, DONE, Studio.ready(j), shorter(j.optString("prompt")), false));
                        } else if (status.equals("failed")) {
                            drop(app, id);
                            String why = j.optString("error");
                            main.post(() -> MainActivity.addStudioError(j.optString("kind"), why));
                            nm.notify(NEXT_ID.getAndIncrement(), note(app, DONE, "The studio couldn't make your " + j.optString("kind"),
                                    shorter(why), false));
                        }
                    } catch (Exception e) {
                        String msg = String.valueOf(e.getMessage());
                        boolean gone = msg.contains("not found") || msg.contains("no such job");
                        if (gone || System.currentTimeMillis() - p.optLong("since") > GIVE_UP) {
                            drop(app, id);
                            String why = gone ? "the studio has no record of it" : "no answer from the studio for three hours";
                            main.post(() -> MainActivity.addStudioError(p.optString("kind"), why));
                        }                                    // otherwise offline for now: try again next round
                    }
                }
                JSONArray left = pending(app);
                if (left.length() == 0) continue;
                nm.notify(WORK_ID, note(app, WORK, "Studio", working(left), true));
                Thread.sleep(15_000);
            }
        } catch (InterruptedException ignored) {
        } finally {
            if (last < 0) synchronized (StudioService.class) { running = false; last = lastStart; }   // interrupted
            stopSelfResult(last);                             // not if a newer start came in: that one carries on
        }
    }

    /** Self-test (a stand-in studio): what landed on the phone, for scripts/emu-studio.sh. */
    private static void selfTest(Context c, JSONObject j) {
        try {
            File f = new File(dir(c), j.optString("file"));
            JSONObject o = new JSONObject().put("kind", j.optString("kind")).put("file", f.getName()).put("bytes", f.length());
            Files.write(new File(c.getExternalFilesDir(null), "studiotest-" + j.optString("kind") + ".json").toPath(),
                    o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private static String shorter(String s) { return s == null ? "" : s.length() > 80 ? s.substring(0, 77) + "..." : s; }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(WORK, "Studio at work", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(DONE, "Studio results", NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static Notification note(Context c, String channel, String title, String text, boolean ongoing) {
        return ResearchService.note(c, channel, title, text, ongoing, android.R.drawable.ic_menu_gallery);
    }
}
