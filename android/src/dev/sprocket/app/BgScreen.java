package dev.sprocket.app;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The hidden screen ("work in the background"): when the helper (android/helper, BgHelper, running as the
 * shell user) is up, Sprocket opens apps and works on a virtual display instead of the real screen, so they can keep
 * using the phone. The helper leaves {port, token, display} in bg.json in Sprocket's external files folder; each
 * command is one short local connection ("auth TOKEN", then the command). Off when there's no helper: everything
 * works on the real screen as before.
 */
final class BgScreen {
    private BgScreen() { }

    private static volatile int port = -1, display = -1;
    private static volatile String token = "";
    private static volatile long checked, fileTime;
    private static volatile boolean up;

    /** True when the hidden screen is there to work on (checked at most every few seconds). */
    static boolean on() {
        android.content.Context c = MainActivity.appContext();
        if (c == null) return false;
        long now = System.currentTimeMillis();
        boolean main = android.os.Looper.myLooper() == android.os.Looper.getMainLooper();
        if (!Screen.bgMayAsk(main, now, checked)) {
            if (main && now - checked >= 4000 && !refreshing) {   // the main thread reads the last answer; a worker refreshes it
                refreshing = true;
                new Thread(() -> { try { on(); } finally { refreshing = false; } }, "sprocket-bg-check").start();
            }
            return up;
        }
        synchronized (BgScreen.class) {
            if (!Screen.bgMayAsk(false, System.currentTimeMillis(), checked)) return up;
            boolean was = up, ok = check(c);
            boolean now2 = !Screen.bgDownAfter(was, ok, !ok && was && check(c));   // a hiccup is asked once more
            if (now2 != was) android.util.Log.i("Sprocket", "background: hidden screen " + (now2 ? "up" : "gone (" + why + ")"));
            up = now2;
            checked = System.currentTimeMillis();
            return up;
        }
    }

    private static volatile boolean refreshing;
    private static volatile String why = "";

    /** One look: bg.json there and the helper answering a ping. Worker threads only. */
    private static boolean check(android.content.Context c) {
        File dir = c.getExternalFilesDir(null), f = dir == null ? null : new File(dir, "bg.json");
        if (f == null || !f.exists()) { why = "no bg.json"; return false; }
        try {
            if (f.lastModified() != fileTime) {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                port = o.getInt("port"); token = o.getString("token"); display = o.getInt("display");
                fileTime = f.lastModified();
            }
            String r = cmd("ping");
            why = r;
            return r.startsWith("display ");
        } catch (Exception e) {
            why = e.getClass().getSimpleName();
            return false;
        }
    }

    static int display() { return display; }

    /** One command to the helper; its one-line answer ("ok ...", "error: ..."). */
    static String cmd(String line) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(15000);
            OutputStream o = s.getOutputStream();
            o.write(("auth " + token + "\n" + line + "\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
            String r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return r == null ? "error: no answer" : r;
        }
    }

    /** Runs a command and throws its error ("the hidden screen ..."), for the hands' steps. */
    static void must(String line) throws Exception {
        String r = cmd(line);
        if (!r.startsWith("ok")) throw new Exception("the hidden screen didn't take that (" + r + ")");
    }

    /**
     * am start's arguments for an intent's action, data, package and component (no extras: the apps and settings pages
     * Sprocket opens don't need them). Plain Java for the desktop tests.
     */
    static String amArgs(String action, String data, String pkg, String component) {
        List<String> a = new ArrayList<>();
        if (action != null && !action.isEmpty()) { a.add("-a"); a.add(action); }
        if (data != null && !data.isEmpty()) { a.add("-d"); a.add(data); }
        if (component != null && !component.isEmpty()) { a.add("-n"); a.add(component); }
        else if (pkg != null && !pkg.isEmpty()) { a.add("-p"); a.add(pkg); }
        for (String s : a) if (s.contains(" ")) throw new IllegalArgumentException("can't pass a space to the hidden screen: " + s);
        return String.join(" ", a);
    }

    /**
     * The shell command that starts the helper from Sprocket's own installed APK (stage 3: run over Sprocket's own
     * wireless-debugging connection), in the background, its output to a log the shell can read.
     */
    static String helperCommand(String apkPath, int width, int height, int dpi) {
        if (apkPath == null || !apkPath.matches("/[A-Za-z0-9_./=~+-]+\\.apk")) throw new IllegalArgumentException("odd APK path: " + apkPath);
        // adbd kills a session's whole process group when it ends: nohup + setsid in a backgrounded subshell (orphaned to
        // init), output to a file, then a moment for setsid to take hold (the way Mika's HelperHandoff starts its helper)
        return "(CLASSPATH=" + apkPath + " nohup setsid app_process / dev.sprocket.bg.BgHelper " + width + " " + height + " " + dpi
                + " > /data/local/tmp/sprocket-bg.log 2>&1 &); sleep 1";
    }

    /** Opens an intent on the hidden screen, as a fresh task (never moves one of theirs off the real screen). */
    static void start(android.content.Intent i) throws Exception {
        android.content.ComponentName cn = i.getComponent();
        must("start " + amArgs(i.getAction(), i.getDataString(), i.getPackage(), cn == null ? null : cn.flattenToShortString()));
    }
}
