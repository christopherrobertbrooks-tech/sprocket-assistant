package dev.sprocket.bg;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sprocket's hidden screen ("work in the background", stage 2; stage 1 notes in experiments/bgscreen). Runs as the
 * shell user (app_process, started over adb), makes a virtual display the size of the real screen, and takes
 * Sprocket's commands for it over a local socket guarded by a random token:
 *
 *   adb shell CLASSPATH=/data/local/tmp/sprocket-bg.dex app_process / dev.sprocket.bg.BgHelper W H DPI
 *
 * It's compiled into Sprocket's own app too, so once Sprocket has paired with the phone's wireless debugging (stage 3)
 * it starts this from its own installed copy: CLASSPATH=<Sprocket's base.apk> app_process / dev.sprocket.bg.BgHelper ...
 *
 * It writes {port, token, display} to bg.json in each installed Sprocket's external files folder (only that app and
 * the shell can read it). One command per connection, one line each way, after "auth TOKEN":
 *   ping | start AM_ARGS (am start on the hidden screen, a fresh task) | tap X Y | swipe X1 Y1 X2 Y2 MS | key CODE |
 *   shot PATH (a PNG of the hidden screen) | show (move the hidden screen's top app onto the real screen) | quit
 */
public final class BgHelper {
    static final int PUBLIC = 1, OWN_CONTENT_ONLY = 8, SUPPORTS_TOUCH = 64, ROTATES_WITH_CONTENT = 128,
            DESTROY_CONTENT_ON_REMOVAL = 256, TRUSTED = 1024, OWN_DISPLAY_GROUP = 2048, ALWAYS_UNLOCKED = 4096,
            OWN_FOCUS = 16384, STEAL_TOP_FOCUS_DISABLED = 65536;
    static final String[] APPS = {"dev.sprocket.app", "dev.sprocket.test"};

    static int display, w, h;
    static ImageReader reader;
    static VirtualDisplay vd;

    public static void main(String[] args) throws Exception {
        if (alreadyRunning()) { System.out.println("already running"); return; }   // one helper only (two ran at once)
        w = Integer.parseInt(args[0]); h = Integer.parseInt(args[1]);
        int dpi = Integer.parseInt(args[2]);
        Looper.prepareMainLooper();
        Constructor<DisplayManager> dmc = DisplayManager.class.getDeclaredConstructor(Context.class);
        dmc.setAccessible(true);
        DisplayManager dm = dmc.newInstance(shellContext());
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        int base = PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL, extra = 0;
        if (Build.VERSION.SDK_INT >= 33) extra |= TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED;
        if (Build.VERSION.SDK_INT >= 34) extra |= OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED;
        try {
            vd = dm.createVirtualDisplay("sprocket-bg", w, h, dpi, reader.getSurface(), base | extra);
        } catch (SecurityException e) {
            vd = dm.createVirtualDisplay("sprocket-bg", w, h, dpi, reader.getSurface(), base);
        }
        display = vd.getDisplay().getDisplayId();
        byte[] t = new byte[18];
        new SecureRandom().nextBytes(t);
        String token = android.util.Base64.encodeToString(t, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        String json = "{\"port\":" + server.getLocalPort() + ",\"token\":\"" + token + "\",\"display\":" + display
                + ",\"pid\":" + android.os.Process.myPid() + "}";
        for (String app : APPS) {
            File dir = new File("/sdcard/Android/data/" + app + "/files");
            if (!dir.isDirectory()) continue;
            File f = new File(dir, "bg.json");
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        }
        System.out.println("display " + display + " port " + server.getLocalPort());
        System.out.flush();
        Thread serve = new Thread(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> answer(s, token), "sprocket-bg-conn").start();   // a slow step never blocks a ping
                } catch (Exception e) {
                    System.out.println("accept: " + e);
                }
            }
        }, "sprocket-bg");
        serve.start();
        Looper.loop();
    }

    static final Object steps = new Object();

    /** One connection: "auth TOKEN", one command, one line back. Pings answer at once; screen steps go one at a time. */
    static void answer(Socket s, String token) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = sock.getOutputStream();
            String auth = in.readLine(), cmd = in.readLine();
            cmd = cmd == null ? "" : cmd.trim();
            String reply;
            if (!("auth " + token).equals(auth)) reply = "error: bad token";
            else if (cmd.equals("ping") || cmd.equals("quit")) reply = run(cmd);
            else synchronized (steps) { reply = run(cmd); }
            out.write((reply.replace('\n', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            if ("quit".equals(cmd)) { vd.release(); for (String app : APPS) new File("/sdcard/Android/data/" + app + "/files/bg.json").delete(); System.exit(0); }
        } catch (Exception e) {
            System.out.println("connection: " + e);
        }
    }

    /**
     * True when another helper already answers (it stays, this one leaves). One that's left in bg.json but doesn't
     * answer is stopped first, so there's never more than one.
     */
    static boolean alreadyRunning() {
        for (String app : APPS) {
            File f = new File("/sdcard/Android/data/" + app + "/files/bg.json");
            if (!f.exists()) continue;
            try {
                String j = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                int port = num(j, "port"), pid = num(j, "pid");
                String token = j.replaceAll("(?s).*\"token\":\"([^\"]*)\".*", "$1");
                if (port > 0 && ping(port, token)) return true;
                if (pid > 0 && pid != android.os.Process.myPid() && isHelper(pid)) android.os.Process.killProcess(pid);
            } catch (Exception e) {
                System.out.println("old helper check: " + e);
            }
        }
        return false;
    }

    static int num(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\":(\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static boolean ping(int port, String token) {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(5000);
            s.getOutputStream().write(("auth " + token + "\nping\n").getBytes(StandardCharsets.UTF_8));
            String r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return r != null && r.startsWith("display ");
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isHelper(int pid) {
        try {
            return new String(java.nio.file.Files.readAllBytes(new File("/proc/" + pid + "/cmdline").toPath()), StandardCharsets.UTF_8).contains("dev.sprocket.bg.BgHelper");
        } catch (Exception e) {
            return false;
        }
    }

    /** The app Sprocket is working in on the hidden screen, and whether they had it in front of them when it started. */
    static volatile String workPkg = "";
    static volatile boolean hisOwn;

    /**
     * After a step: if the app threw a window up on the real screen (and they didn't have it open there themselves), it goes
     * back to the hidden screen. Waits a moment for the app to finish starting it. The step's own answer is returned.
     */
    static String pullBack(String reply) {
        if (workPkg.isEmpty() || hisOwn) return reply;
        try {
            Thread.sleep(300);
            String list = sh(Arrays.asList("am", "stack", "list"));
            for (String t : tasksOf(list, workPkg, 0, true)) {
                sh(Arrays.asList("am", "display", "move-stack", t, String.valueOf(display)));
                System.out.println("pulled " + workPkg + " back off the real screen");
            }
        } catch (Exception e) {
            System.out.println("pull back: " + e);
        }
        return reply;
    }

    /**
     * The root tasks of an app on a display, from "am stack list": visible (in front of them) or not. Plain Java (the
     * desktop tests).
     */
    public static List<String> tasksOf(String stackList, String pkg, int onDisplay, boolean visible) {
        List<String> out = new ArrayList<>();
        if (stackList.startsWith("ok ")) stackList = stackList.substring(3);   // sh()'s answer: "ok " + the output (Codex 11-17 #2)
        String root = null;
        int disp = -1;
        java.util.regex.Pattern rp = java.util.regex.Pattern.compile("^RootTask id=(\\d+).*\\bdisplayId=(\\d+)");
        for (String line : stackList.split("\n")) {
            java.util.regex.Matcher m = rp.matcher(line);
            if (m.find()) { root = m.group(1); disp = Integer.parseInt(m.group(2)); continue; }
            String t = line.trim();
            if (root == null || disp != onDisplay || !t.startsWith("taskId=")) continue;
            int colon = t.indexOf(": "), slash = t.indexOf('/');
            if (colon < 0 || slash < colon) continue;
            if (t.substring(colon + 2, slash).equals(pkg) && t.contains(" visible=" + visible + " ")) {
                if (!out.contains(root)) out.add(root);
            }
            root = null;   // a root task's first (top) task decides
        }
        return out;
    }

    /** An app's windows to close before Sprocket opens it fresh: the owner's out of sight, then all on the hidden screen. Plain Java. */
    public static List<String> leftovers(String stackList, String pkg, int hidden) {
        List<String> out = new ArrayList<>(tasksOf(stackList, pkg, 0, false));
        out.addAll(tasksOf(stackList, pkg, hidden, false));
        out.addAll(tasksOf(stackList, pkg, hidden, true));
        return out;
    }

    /** The app an "am start" names ("-n pkg/cls" or "-p pkg"), "" for none. Plain Java. */
    public static String pkgOf(String amArgs) {
        String[] w = amArgs.trim().split("\\s+");
        for (int i = 0; i + 1 < w.length; i++) {
            if (w[i].equals("-n")) { int s = w[i + 1].indexOf('/'); return s > 0 ? w[i + 1].substring(0, s) : ""; }
            if (w[i].equals("-p")) return w[i + 1];
        }
        return "";
    }

    static String run(String cmd) throws Exception {
        String[] a = cmd.split(" ", 2);
        String rest = a.length > 1 ? a[1] : "";
        switch (a[0]) {
            case "ping": return "display " + display;
            case "quit": return "bye";
            case "start": {   // a fresh task on the hidden screen (never moves one of theirs off the real screen)
                workPkg = pkgOf(rest);
                if (!workPkg.isEmpty()) {
                    // 11-16: Walmart pulled its old, out-of-sight window up on the real screen mid-run, and each start
                    // left another window (7 piled up in the recent apps). The app's leftovers close first: the hidden
                    // screen's, and the owner's they aren't looking at; one they have in front of them stays. One fresh window follows.
                    String list = sh(Arrays.asList("am", "stack", "list"));
                    hisOwn = !tasksOf(list, workPkg, 0, true).isEmpty();
                    for (String t : leftovers(list, workPkg, display)) sh(Arrays.asList("am", "stack", "remove", t));
                }
                List<String> c = new ArrayList<>(Arrays.asList("am", "start", "--display", String.valueOf(display), "-f", "0x18000000"));
                c.addAll(Arrays.asList(rest.split(" ")));
                return sh(c);
            }
            case "tap": return pullBack(sh(Arrays.asList(("input -d " + display + " tap " + rest).split(" "))));
            case "swipe": return pullBack(sh(Arrays.asList(("input -d " + display + " swipe " + rest).split(" "))));
            case "key": return pullBack(sh(Arrays.asList(("input -d " + display + " keyevent " + rest).split(" "))));
            case "shot": return shot(rest);
            case "show": {   // the hidden screen's top app onto the real screen (an order's cart, for their checkout tap)
                String list = sh(Arrays.asList("am", "stack", "list"));
                String task = null;
                for (String line : list.split("\n")) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("RootTask id=(\\d+).*displayId=" + display + "\\b").matcher(line);
                    if (m.find()) { task = m.group(1); break; }   // listed top first
                }
                if (task == null) return "error: nothing open on the hidden screen";
                return sh(Arrays.asList("am", "display", "move-stack", task, "0"));
            }
            default: return "error: unknown command " + a[0];
        }
    }

    static String sh(List<String> c) throws Exception {
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        java.util.concurrent.FutureTask<String> read = new java.util.concurrent.FutureTask<>(
                () -> new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim());
        new Thread(read, "sprocket-bg-read").start();
        if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return "error: timed out"; }   // a stuck step never holds the rest up
        String out = read.get(5, java.util.concurrent.TimeUnit.SECONDS);
        int code = p.exitValue();
        return (code == 0 && !out.contains("Error") && !out.contains("Exception")) ? "ok " + out : "error: " + out;
    }

    static synchronized String shot(String path) throws Exception {
        Image img = reader.acquireLatestImage();
        if (img == null) return "error: no frame yet";
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int pad = (p.getRowStride() - p.getPixelStride() * w) / p.getPixelStride();
            Bitmap b = Bitmap.createBitmap(w + pad, h, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(buf);
            Bitmap crop = Bitmap.createBitmap(b, 0, 0, w, h);
            try (FileOutputStream o = new FileOutputStream(path)) { crop.compress(Bitmap.CompressFormat.PNG, 100, o); }
            new File(path).setReadable(true, false);
            return "ok " + path;
        } finally {
            img.close();
        }
    }

    /** A Context that says it's the shell, enough for DisplayManager; with the configuration Samsung checks. */
    static Context shellContext() throws Exception {
        Class<?> atc = Class.forName("android.app.ActivityThread");
        Constructor<?> c = atc.getDeclaredConstructor();
        c.setAccessible(true);
        Object at = c.newInstance();
        Field cur = atc.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, at);
        try {   // Samsung's CompatSandbox reads the configuration while making the system context
            Class<?> cc = Class.forName("android.app.ConfigurationController");
            Constructor<?> k = cc.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"));
            k.setAccessible(true);
            Field f = atc.getDeclaredField("mConfigurationController");
            f.setAccessible(true);
            f.set(at, k.newInstance(at));
        } catch (Throwable ignored) { }
        Method sys = atc.getDeclaredMethod("getSystemContext");
        sys.setAccessible(true);
        Context system = (Context) sys.invoke(at);
        return new ContextWrapper(system) {
            @Override public String getPackageName() { return "com.android.shell"; }
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public Context getApplicationContext() { return this; }
            @Override public android.content.AttributionSource getAttributionSource() {
                return new android.content.AttributionSource.Builder(2000).setPackageName("com.android.shell").build();
            }
        };
    }
}
