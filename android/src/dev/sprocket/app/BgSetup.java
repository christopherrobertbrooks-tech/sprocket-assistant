package dev.sprocket.app;

import android.content.Context;

import dev.sprocket.app.adb.AdbConnection;
import dev.sprocket.app.adb.AdbPairing;
import dev.sprocket.app.adb.AdbPairingException;
import dev.sprocket.app.adb.AdbServiceTypes;
import dev.sprocket.app.adb.AdbTls;
import dev.sprocket.app.adb.AndroidAdb;
import dev.sprocket.app.adb.NsdAdbServiceFinder;

import java.util.List;

/**
 * Stage 3 of "work in the background": Sprocket starts its own hidden-screen helper, no computer. Once, it pairs with
 * this phone's own wireless debugging (the code read off the Settings pairing window through screen control, the port
 * found by mDNS); after that it connects to 127.0.0.1, grants itself what it needs to turn wireless debugging back on
 * after a restart, and starts BgHelper from its own installed APK. The adb client is ported from Mika (adb/NOTICE.md).
 * Worker threads only.
 */
final class BgSetup {
    private BgSetup() { }

    /**
     * Waits up to waitMs for the "Pair device with pairing code" window, then pairs. "paired" or why not. The code is
     * never logged.
     */
    static String pair(Context c, long waitMs) throws Exception {
        AdbTls tls = AndroidAdb.tls(AndroidAdb.pairingIdentity(c));
        NsdAdbServiceFinder finder = new NsdAdbServiceFinder(c);
        long end = System.currentTimeMillis() + waitMs;
        String lastCode = null;
        long nextNote = 0;
        while (System.currentTimeMillis() < end) {
            String code = ScreenHands.pairingCode();
            if (System.currentTimeMillis() >= nextNote) {   // progress, never the code itself
                android.util.Log.i("Sprocket", "background: waiting for the pairing window; screen control "
                        + (ScreenHands.isOn() ? "on" : "OFF") + ", code " + (code == null ? "not seen" : "seen"));
                nextNote = System.currentTimeMillis() + 10_000;
            }
            if (code != null && !code.equals(lastCode)) {
                List<Integer> ports = finder.findPorts(AdbServiceTypes.PAIRING, 4000);
                android.util.Log.i("Sprocket", "background: pairing ports found: " + ports.size());
                for (int port : ports) {
                    try {
                        new AdbPairing(tls).pair("127.0.0.1", port, code);
                        android.util.Log.i("Sprocket", "background: paired with this phone's wireless debugging");
                        return "paired";
                    } catch (AdbPairingException e) {
                        android.util.Log.w("Sprocket", "background: pairing on " + port + " failed: " + e.reason);
                        if (e.reason == AdbPairingException.Reason.WRONG_CODE) lastCode = code;   // wait for a fresh code
                    }
                }
            }
            Thread.sleep(1000);
        }
        return "no pairing window: open Wireless debugging and tap \"Pair device with pairing code\"";
    }

    /**
     * The one-tap setup from Sprocket's menu ("we should have sprocket open that page, that is difficult to
     * find"): starts the helper if it's paired already; otherwise opens Developer options, taps through to Wireless
     * debugging (asking them to tap Allow if it has to switch it on) and "Pair device with pairing code", pairs, starts.
     * say() reports each step to them. True when the hidden screen is up.
     */
    static boolean setup(Context c, java.util.function.Consumer<String> say) throws Exception {
        if (BgScreen.on()) { say.accept("Background work is already on."); return true; }
        if (wirelessDebuggingOn(c) || android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1) {
            String r = startHelper(c);   // paired before: just start it
            if (r.equals("started")) { say.accept("Background work is on."); return true; }
        }
        if (android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 1) {
            say.accept("First turn on Developer options: Settings, About phone, Software information, tap Build number seven times. Then tap Background work again.");
            return false;
        }
        if (!ScreenHands.isOn()) { say.accept("Screen control has to be on for this. Turn it on, then tap Background work again."); return false; }
        ScreenHands.glow(true);   // it's working the screen now (no glow during setup)
        try { return setupOnScreen(c, say); } finally { ScreenHands.glow(false); }
    }

    private static boolean setupOnScreen(Context c, java.util.function.Consumer<String> say) throws Exception {
        c.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Thread.sleep(2000);
        say.accept("Opening Wireless debugging...");
        if (!ScreenHands.findAndTap("Wireless debugging")) { say.accept("I couldn't find Wireless debugging in Developer options. Open it yourself, then tap Background work again."); return false; }
        Thread.sleep(1500);
        if (android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) != 1) {
            ScreenHands.findAndTap("Use wireless debugging");
            say.accept("Tap Allow on Android's question to turn on wireless debugging.");
            for (int i = 0; i < 60 && android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) != 1; i++) Thread.sleep(1000);
            if (android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) != 1) { say.accept("Wireless debugging is still off (it needs Wi-Fi). Try again on Wi-Fi."); return false; }
            Thread.sleep(1500);
        }
        if (!ScreenHands.findAndTap("Pair device with pairing code")) { say.accept("I couldn't find \"Pair device with pairing code\". Tap it yourself; I'll read the code."); }
        String p = pair(c, 90_000);
        if (!p.equals("paired")) { say.accept("Pairing didn't happen: " + p); return false; }
        String r = startHelper(c);
        if (!r.equals("started")) { say.accept("Paired, but the helper didn't start: " + r); return false; }
        c.startActivity(new android.content.Intent(c, MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        say.accept("Background work is on. I'll work on a hidden screen from now on, so you can keep using your phone.");
        return true;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean starting = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Background work is on but the helper isn't running (after a restart, or Wi-Fi went away and took wireless
     * debugging with it): bring it back quietly, once at a time. Off Wi-Fi it doesn't try (wireless debugging only runs
     * on Wi-Fi; Sprocket works on the real screen meanwhile) and comes back by itself when Wi-Fi does.
     */
    static void keepUp(Context c) {
        watchWifi(c);
        if (BgScreen.on() || !onWifi(c) || !starting.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                if (wirelessDebuggingOn(c)) {
                    boolean kicked = false;
                    for (int i = 0; i < 10 && BgScreen.on() == false; i++) {   // wireless debugging takes a moment to come up
                        String r = startHelper(c);
                        android.util.Log.i("Sprocket", "background: keep up -> " + r);
                        if (r.equals("started") || r.equals("already running")) break;
                        if (!kicked && r.startsWith(NO_PORT)) { kicked = kick(c); continue; }
                        Thread.sleep(3000);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("Sprocket", "background: keep up failed: " + e);
            } finally {
                starting.set(false);
            }
        }, "sprocket-bg-keepup").start();
    }

    static boolean onWifi(Context c) {
        try {
            android.net.ConnectivityManager cm = c.getSystemService(android.net.ConnectivityManager.class);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return true;   // can't tell: try anyway
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean watching = new java.util.concurrent.atomic.AtomicBoolean();

    /** Once per app run: when Wi-Fi comes back and background work is on, start the helper again. */
    static void watchWifi(Context c) {
        if (!watching.compareAndSet(false, true)) return;
        Context app = c.getApplicationContext();
        try {
            app.getSystemService(android.net.ConnectivityManager.class).registerNetworkCallback(
                    new android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build(),
                    new android.net.ConnectivityManager.NetworkCallback() {
                        @Override public void onAvailable(android.net.Network n) {
                            if (!app.getSharedPreferences("sprocket", Context.MODE_PRIVATE).getBoolean("bg", false)) return;
                            android.util.Log.i("Sprocket", "background: Wi-Fi is back");
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> keepUp(app), 3000);
                        }
                    });
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "background: can't watch Wi-Fi: " + e);
        }
    }

    /** Connects to this phone's own wireless debugging and starts the helper; "started" or why not. */
    static String startHelper(Context c) throws Exception {
        if (BgScreen.on()) return "already running";
        AdbTls tls = AndroidAdb.tls(AndroidAdb.identity(c));
        List<Integer> ports = new NsdAdbServiceFinder(c).findPorts(AdbServiceTypes.CONNECT, 5000);
        if (ports.isEmpty()) return NO_PORT + " (no port found)";
        Exception last = null;
        for (int port : ports) {
            try (AdbConnection conn = AdbConnection.open("127.0.0.1", port, tls)) {
                String pkg = c.getPackageName();
                String path = conn.shell("pm path " + pkg).readText().trim();
                String apk = path.split("\n")[0].replace("package:", "").trim();
                conn.shell("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS").readText();   // to turn it back on after a restart
                android.view.WindowManager wm = c.getSystemService(android.view.WindowManager.class);
                android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
                android.util.DisplayMetrics real = new android.util.DisplayMetrics();
                c.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(real);
                int dpi = real.densityDpi;
                conn.shell(BgScreen.helperCommand(apk, b.width(), b.height(), dpi)).readText();
                for (int i = 0; i < 20; i++) {   // the helper takes a few seconds to say it's ready
                    Thread.sleep(500);
                    if (BgScreen.on()) return "started";
                }
                return "the helper didn't come up";
            } catch (Exception e) {
                last = e;
                android.util.Log.w("Sprocket", "background: connect on " + port + " failed: " + e);
            }
        }
        return "couldn't connect to wireless debugging (" + (last == null ? "?" : last.getMessage()) + "); pair again?";
    }

    static final String NO_PORT = "wireless debugging isn't on";

    /**
     * After a phone restarts the switch read on but nothing was listening ("IP address: unavailable") until it
     * was turned off and on again. Does that once. True when it ended up on.
     */
    static boolean kick(Context c) {
        try {
            android.util.Log.i("Sprocket", "background: wireless debugging on but not listening; turning it off and on");
            android.provider.Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 0);
            Thread.sleep(2000);
            android.provider.Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
            Thread.sleep(3000);
            return true;
        } catch (Exception e) {
            android.util.Log.w("Sprocket", "background: couldn't turn wireless debugging off and on: " + e);
            return true;   // tried; don't keep at it
        }
    }

    /**
     * After a restart wireless debugging is off: turn it back on (needs Wi-Fi and the WRITE_SECURE_SETTINGS grant from
     * the first start). True when it's on.
     */
    static boolean wirelessDebuggingOn(Context c) {
        try {
            if (android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1) return true;
            return android.provider.Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
        } catch (Exception e) {
            return false;
        }
    }
}
