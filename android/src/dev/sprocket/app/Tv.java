package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TVs on the home Wi-Fi (2026-11-19): find them, pair once (the TV shows a code, he reads it out), then power, volume,
 * mute, input, channels and the remote's arrow keys over the TV's own network door. Vizio (SmartCast, port 7345) and
 * LG (webOS, port 3001: Lg.java) are built; Samsung and Roku TVs are recognised and reported as "not supported yet".
 *
 * What it never does: send the pairing key anywhere but that TV (the key stays in tv.json in the app's private files and
 * is never in a tool answer), talk to a TV whose certificate changed since pairing (pinned at pairing), or claim a
 * result the TV didn't read back. Plain Java (the network part too, so it runs on the desktop): test/TvTest.java.
 */
final class Tv {
    /** The network, so tests can stand in for the TVs. */
    interface Net {
        boolean open(String ip, int port, int ms);
        Resp http(String method, String url, String body, String auth, String pin, int ms) throws Exception;
        void wake(String mac, String ip) throws Exception;
        void sleep(long ms);
        /** A websocket (LG): wss with the same certificate rule as http. */
        Ws ws(String url, String pin, int ms) throws Exception;
    }

    interface Ws {
        void send(String text) throws Exception;
        String recv(int ms) throws Exception;
        String cert();
        void close();
    }

    static final class Resp {
        final int code; final String body, cert;
        Resp(int code, String body, String cert) { this.code = code; this.body = body; this.cert = cert; }
    }

    /** A TV seen on the Wi-Fi. */
    static final class Found {
        final String brand, name, model, ip; final int port;
        Found(String brand, String name, String model, String ip, int port) {
            this.brand = brand; this.name = name; this.model = model; this.ip = ip; this.port = port;
        }
    }

    /** A paired TV. The key never leaves the phone except to this TV. */
    static final class Paired {
        String brand, name, model, ip, mac, key, cert; int port;
    }

    static final int VIZIO = 7345, VIZIO_OLD = 9000, LG = 3001, SAMSUNG = 8002, ROKU = 8060;
    static final int[] PORTS = {VIZIO, VIZIO_OLD, LG, SAMSUNG, ROKU};
    static final String NOT_WIFI = "His phone isn't on Wi-Fi, so it can't reach the TVs at home. Nothing was done.";

    final List<Paired> paired = new ArrayList<>();
    final List<Found> found = new ArrayList<>();
    String deviceId = "";
    JSONObject pending;   // a pairing waiting for the code on the TV's screen
    Ws held;              // an LG pairing's open connection (the code goes back on the same one); never saved

    void dropHeld() { if (held != null) held.close(); held = null; }

    static final java.util.Set<String> CONTROLLABLE = new java.util.HashSet<>(java.util.Arrays.asList("Vizio", "LG"));

    // ---- saving ----------------------------------------------------------------------------------------------------

    static Tv load(String json) {
        Tv t = new Tv();
        try {
            JSONObject o = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            t.deviceId = o.optString("device_id");
            t.pending = o.optJSONObject("pending");
            JSONArray a = o.optJSONArray("paired");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject p = a.getJSONObject(i);
                Paired x = new Paired();
                x.brand = p.optString("brand"); x.name = p.optString("name"); x.model = p.optString("model");
                x.ip = p.optString("ip"); x.port = p.optInt("port", VIZIO); x.mac = p.optString("mac");
                x.key = p.optString("key"); x.cert = p.optString("cert");
                t.paired.add(x);
            }
        } catch (Exception ignored) { }
        if (t.deviceId.isEmpty()) t.deviceId = "sprocket-" + Long.toHexString(new java.security.SecureRandom().nextLong());
        return t;
    }

    String toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Paired p : paired)
            a.put(new JSONObject().put("brand", p.brand).put("name", p.name).put("model", p.model).put("ip", p.ip)
                    .put("port", p.port).put("mac", p.mac).put("key", p.key).put("cert", p.cert));
        JSONObject o = new JSONObject().put("device_id", deviceId).put("paired", a);
        if (pending != null) o.put("pending", pending);
        return o.toString();
    }

    // ---- finding ---------------------------------------------------------------------------------------------------

    /** Every address on his Wi-Fi's /24 (a bigger network is cut to the /24 around the phone), the phone left out. */
    static List<String> hosts(String phoneIp) {
        List<String> out = new ArrayList<>();
        int dot = phoneIp == null ? -1 : phoneIp.lastIndexOf('.');
        if (dot < 0) return out;
        String base = phoneIp.substring(0, dot + 1);
        for (int i = 1; i < 255; i++) if (!(base + i).equals(phoneIp)) out.add(base + i);
        return out;
    }

    /** Looks for TVs: every address, each TV door, then asks each TV its name. */
    List<Found> scan(Net net, String phoneIp) {
        Map<String, List<Integer>> open = new TreeMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(160);
        for (String ip : hosts(phoneIp))
            for (int port : PORTS)
                pool.execute(() -> {
                    if (net.open(ip, port, 500)) synchronized (open) { open.computeIfAbsent(ip, k -> new ArrayList<>()).add(port); }
                });
        pool.shutdown();
        try { pool.awaitTermination(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        List<Found> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : open.entrySet()) {
            Found f = identify(net, e.getKey(), e.getValue());
            if (f != null) out.add(f);
        }
        found.clear();
        found.addAll(out);
        return out;
    }

    static Found identify(Net net, String ip, List<Integer> ports) {
        try {
            for (int port : new int[]{VIZIO, VIZIO_OLD}) {
                if (!ports.contains(port)) continue;
                JSONObject v = new JSONObject(net.http("GET", "https://" + ip + ":" + port + "/state/device/deviceinfo", null, null, null, 4000).body)
                        .getJSONArray("ITEMS").getJSONObject(0).getJSONObject("VALUE");
                return new Found("Vizio", v.optString("CAST_NAME", "Vizio TV"), v.optString("MODEL_NAME"), ip, port);
            }
        } catch (Exception ignored) { }
        try {
            if (ports.contains(ROKU)) {
                String x = net.http("GET", "http://" + ip + ":" + ROKU + "/query/device-info", null, null, null, 4000).body;
                if (!"true".equals(tag(x, "is-tv"))) return null;   // a Roku stick or other box, not a TV
                String n = tag(x, "user-device-name");
                return new Found("Roku", n.isEmpty() ? "Roku TV" : n, tag(x, "model-name"), ip, ROKU);
            }
        } catch (Exception ignored) { }
        try {
            if (ports.contains(SAMSUNG)) {
                JSONObject d = new JSONObject(net.http("GET", "https://" + ip + ":" + SAMSUNG + "/api/v2/", null, null, null, 4000).body).optJSONObject("device");
                return new Found("Samsung", d == null ? "Samsung TV" : d.optString("name", "Samsung TV"), d == null ? "" : d.optString("modelName"), ip, SAMSUNG);
            }
        } catch (Exception e) {
            if (ports.contains(SAMSUNG)) return new Found("Samsung", "Samsung TV", "", ip, SAMSUNG);
        }
        if (ports.contains(LG)) {
            String name = "LG TV", model = "";
            try {   // webOS describes itself on port 1298 ("[LG] webOS TV" and its model)
                String x = net.http("GET", "http://" + ip + ":1298/", null, null, null, 3000).body;
                String fn = tag(x, "friendlyName").replace("[LG]", "").trim();
                if (!fn.isEmpty()) name = fn.toLowerCase(Locale.US).contains("lg") ? fn : "LG " + fn;
                model = tag(x, "modelNumber");
            } catch (Exception ignored) { }
            return new Found("LG", name, model, ip, LG);
        }
        return null;
    }

    static String tag(String xml, String t) {
        int a = xml == null ? -1 : xml.indexOf("<" + t + ">"), b = a < 0 ? -1 : xml.indexOf("</" + t + ">", a);
        return a < 0 || b < 0 ? "" : xml.substring(a + t.length() + 2, b).trim();
    }

    /** tv_find: what's on his Wi-Fi, in plain words for the brain. */
    String find(Net net, String phoneIp) {
        List<Found> fs = scan(net, phoneIp);
        if (fs.isEmpty())
            return "No TVs found on his Wi-Fi. A TV that's fully off or unplugged, or one on another network, doesn't show up."
                    + (paired.isEmpty() ? "" : " Paired before: " + names() + " (maybe asleep: \"turn on\" still tries to wake it).");
        StringBuilder b = new StringBuilder("TVs on his Wi-Fi:");
        for (Found f : fs) {
            Paired p = pairedNamed(f.name, f.brand);
            if (p == null) for (Paired x : paired) if (x.brand.equals(f.brand) && x.ip.equals(f.ip)) p = x;
            if (p != null && !p.ip.equals(f.ip)) p.ip = f.ip;   // its address moved
            b.append("\n- ").append(f.name).append(" (").append(f.brand).append(f.model.isEmpty() ? "" : " " + f.model).append("): ")
                    .append(p != null ? "paired, Sprocket can control it."
                            : CONTROLLABLE.contains(f.brand) ? "not paired yet (tv_pair, once: the TV shows a code)."
                            : "Sprocket can't control " + f.brand + " TVs yet.");
        }
        return b.toString();
    }

    String names() {
        List<String> n = new ArrayList<>();
        for (Paired p : paired) n.add(p.name);
        return String.join(", ", n);
    }

    Paired pairedNamed(String name, String brand) {
        for (Paired p : paired) if (p.name.equalsIgnoreCase(name) && (brand == null || p.brand.equals(brand))) return p;
        return null;
    }

    /** Which paired TV he means: by a word of its name ("kitchen"), or the only one. Null when it isn't clear. */
    Paired pick(String said) {
        if (paired.size() == 1 && (said == null || said.trim().isEmpty())) return paired.get(0);
        Paired hit = null;
        String s = said == null ? "" : said.toLowerCase(Locale.US).replace("tv", "").replace("the ", "").trim();
        if (s.isEmpty()) return paired.size() == 1 ? paired.get(0) : null;
        for (Paired p : paired) {
            String n = (p.brand + " " + p.name).toLowerCase(Locale.US);
            if (n.equals(s)) return p;
            boolean all = true;
            for (String w : s.split("\\s+")) if (!w.isEmpty() && !n.contains(w)) all = false;
            if (all) { if (hit != null) return null; hit = p; }
        }
        return hit;
    }

    // ---- pairing (Vizio) -------------------------------------------------------------------------------------------

    /** tv_pair: without a code, the TV puts a code on its screen; with the code, the pairing is saved. */
    String pair(Net net, String phoneIp, String which, String code) throws Exception {
        code = code == null ? "" : code.replaceAll("\\D", "");
        if (pending != null && pending.optString("brand").equals("LG") && (!code.isEmpty() || pending.optString("mode").equals("PROMPT")))
            return Lg.pairFinish(this, net, code);
        if (!code.isEmpty()) {
            if (pending == null) return "There's no pairing waiting. Call tv_pair without a code first, so the TV shows a new one.";
            String ip = pending.getString("ip"); int port = pending.getInt("port");
            JSONObject body = new JSONObject().put("DEVICE_ID", deviceId).put("CHALLENGE_TYPE", pending.getInt("challenge"))
                    .put("RESPONSE_VALUE", code).put("PAIRING_REQ_TOKEN", pending.getLong("token"));
            Resp r = net.http("PUT", "https://" + ip + ":" + port + "/pairing/pair", body.toString(), null, null, 8000);
            JSONObject o = new JSONObject(r.body);
            String result = o.optJSONObject("STATUS") == null ? "" : o.getJSONObject("STATUS").optString("RESULT");
            if (!"SUCCESS".equals(result)) {
                return "Pairing didn't work (the TV said " + (result.isEmpty() ? "nothing clear" : result)
                        + "). The code may be mistyped or expired: call tv_pair without a code to get a new one on the TV.";
            }
            if (r.cert == null || r.cert.isEmpty())   // the key only ever goes to the certificate seen at pairing
                return "Pairing didn't finish: the TV didn't show a certificate to remember, so Sprocket won't keep its key.";
            Paired p = new Paired();
            p.brand = "Vizio"; p.name = pending.getString("name"); p.model = pending.optString("model"); p.ip = ip; p.port = port;
            p.key = o.getJSONObject("ITEM").getString("AUTH_TOKEN"); p.cert = r.cert == null ? "" : r.cert;
            p.mac = mac(net, p);
            Paired old = pairedNamed(p.name, p.brand);
            if (old != null) paired.remove(old);
            paired.add(p);
            pending = null;
            return "Paired with " + p.name + ". Sprocket can control it now" + (p.mac.isEmpty()
                    ? " while it's on (its Wi-Fi address for waking it couldn't be read, so \"turn on\" works only from standby with quick start)."
                    : ", including turning it on.");
        }
        if (found.isEmpty()) scan(net, phoneIp);
        Found f = null;
        List<Found> ours = new ArrayList<>();
        for (Found x : found) if (CONTROLLABLE.contains(x.brand)) ours.add(x);
        String s = which == null ? "" : which.toLowerCase(Locale.US).replace("tv", "").trim();
        List<Found> hits = new ArrayList<>();
        for (Found x : ours) if (s.isEmpty() || (x.brand + " " + x.name).toLowerCase(Locale.US).contains(s)) hits.add(x);
        if (hits.size() == 1) f = hits.get(0);
        if (hits.size() > 1) {   // "kitchen" with Kitchen Left and Kitchen Right: ask, never pick one (Codex 055 #4)
            List<String> n = new ArrayList<>();
            for (Found x : hits) n.add(x.name);
            return (s.isEmpty() ? "Which TV? Found: " : "Which TV? More than one fits: ") + String.join(", ", n) + ".";
        }
        if (f == null) {
            for (Found x : found)
                if (!CONTROLLABLE.contains(x.brand) && !s.isEmpty() && (x.brand + " " + x.name).toLowerCase(Locale.US).contains(s))
                    return x.name + " is a " + x.brand + " TV; Sprocket can only pair with Vizio and LG TVs so far.";
            if (ours.isEmpty()) return "No Vizio or LG TV found on his Wi-Fi to pair with. Is it on, and on the same Wi-Fi as his phone?";
            List<String> n = new ArrayList<>();
            for (Found x : ours) n.add(x.name);
            return "Which TV? Found: " + String.join(", ", n) + ".";
        }
        if (f.brand.equals("LG")) return Lg.pairStart(this, net, f);
        JSONObject body = new JSONObject().put("DEVICE_ID", deviceId).put("DEVICE_NAME", "Sprocket");
        JSONObject o = new JSONObject(net.http("PUT", "https://" + f.ip + ":" + f.port + "/pairing/start", body.toString(), null, null, 8000).body);
        String result = o.optJSONObject("STATUS") == null ? "" : o.getJSONObject("STATUS").optString("RESULT");
        if (!"SUCCESS".equals(result)) {
            return "The TV wouldn't start pairing (it said " + (result.isEmpty() ? "nothing clear" : result) + "). "
                    + ("BLOCKED".equals(result) ? "It may already be showing a pairing screen: ask him to close it with Back, then try again." : "Is it turned on?");
        }
        JSONObject item = o.getJSONObject("ITEM");
        pending = new JSONObject().put("ip", f.ip).put("port", f.port).put("name", f.name).put("model", f.model)
                .put("token", item.getLong("PAIRING_REQ_TOKEN")).put("challenge", item.optInt("CHALLENGE_TYPE", 1));
        return "A pairing code is on " + f.name + "'s screen now. Ask him to read it out, then call tv_pair again with that code.";
    }

    /** The TV's Wi-Fi hardware address (for waking it), from its network page; empty if it can't be read. */
    static String mac(Net net, Paired p) {
        try {
            JSONArray items = vizioGet(net, p, "/menu_native/dynamic/tv_settings/network/network_information").getJSONArray("ITEMS");
            String wired = "";
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                if (it.optString("CNAME").equals("wlan_mac")) return it.optString("VALUE");
                if (it.optString("CNAME").equals("rj45_mac")) wired = it.optString("VALUE");
            }
            JSONObject type = null;
            for (int i = 0; i < items.length(); i++) if (items.getJSONObject(i).optString("CNAME").equals("connection_type")) type = items.getJSONObject(i);
            if (type != null && type.optString("VALUE").toLowerCase(Locale.US).contains("wired")) return wired;
        } catch (Exception ignored) { }
        return "";
    }

    // ---- controls (Vizio) ------------------------------------------------------------------------------------------

    /** What one control did, and how to put it back (the undo log). */
    static final class Result {
        final String text, did, undoKind; final JSONObject undo;
        Result(String text, String did, String undoKind, JSONObject undo) { this.text = text; this.did = did; this.undoKind = undoKind; this.undo = undo; }
    }

    static final Map<String, int[]> KEYS = new TreeMap<>();
    static {
        KEYS.put("home", new int[]{4, 15}); KEYS.put("back", new int[]{4, 0}); KEYS.put("menu", new int[]{4, 8});
        KEYS.put("exit", new int[]{9, 0}); KEYS.put("ok", new int[]{3, 2}); KEYS.put("up", new int[]{3, 8});
        KEYS.put("down", new int[]{3, 0}); KEYS.put("left", new int[]{3, 1}); KEYS.put("right", new int[]{3, 7});
        KEYS.put("play", new int[]{2, 3}); KEYS.put("pause", new int[]{2, 2});
        KEYS.put("channel_up", new int[]{8, 1}); KEYS.put("channel_down", new int[]{8, 0}); KEYS.put("input_next", new int[]{7, 1});
    }
    static final int[] POWER_ON = {11, 1}, POWER_OFF = {11, 0}, MUTE_ON = {5, 3}, MUTE_OFF = {5, 2}, VOL_UP = {5, 1}, VOL_DOWN = {5, 0};
    static final String[] ACTIONS = {"on", "off", "volume", "volume_up", "volume_down", "mute", "unmute", "input",
            "input_next", "channel_up", "channel_down", "home", "back", "menu", "exit", "ok", "up", "down", "left", "right", "play", "pause"};

    static String keyBody(int[] k) throws Exception {
        return new JSONObject().put("KEYLIST", new JSONArray().put(new JSONObject().put("CODESET", k[0]).put("CODE", k[1]).put("ACTION", "KEYPRESS"))).toString();
    }

    static String base(Paired p) { return "https://" + p.ip + ":" + p.port; }

    static JSONObject vizioGet(Net net, Paired p, String path) throws Exception {
        return ok(net.http("GET", base(p) + path, null, p.key, p.cert.isEmpty() ? null : p.cert, 5000));
    }

    static JSONObject ok(Resp r) throws Exception {
        JSONObject o = new JSONObject(r.body);
        String result = o.optJSONObject("STATUS") == null ? "" : o.getJSONObject("STATUS").optString("RESULT");
        if (result.equals("REQUIRES_PAIRING")) throw new Exception("the TV no longer accepts Sprocket's pairing (it was reset or unpaired): pair it again with tv_pair");
        if (!result.equals("SUCCESS")) throw new Exception("the TV said " + (result.isEmpty() ? "nothing clear" : result));
        return o;
    }

    static void key(Net net, Paired p, int[] k) throws Exception {
        ok(net.http("PUT", base(p) + "/key_command/", keyBody(k), p.key, p.cert.isEmpty() ? null : p.cert, 5000));
    }

    /** On (true), off/standby (false), or null when it doesn't answer (asleep with its Wi-Fi off, or unplugged). */
    static Boolean power(Net net, Paired p) {
        try {
            JSONObject o = new JSONObject(net.http("GET", base(p) + "/state/device/power_mode", null, null, p.cert.isEmpty() ? null : p.cert, 3000).body);
            return o.getJSONArray("ITEMS").getJSONObject(0).optInt("VALUE", 0) == 1;
        } catch (Exception e) { return null; }
    }

    static JSONObject setting(Net net, Paired p, String path) throws Exception {
        return vizioGet(net, p, "/menu_native/dynamic/tv_settings/" + path).getJSONArray("ITEMS").getJSONObject(0);
    }

    static void modify(Net net, Paired p, String path, Object value, long hash) throws Exception {
        String body = new JSONObject().put("REQUEST", "MODIFY").put("VALUE", value).put("HASHVAL", hash).toString();
        ok(net.http("PUT", base(p) + "/menu_native/dynamic/tv_settings/" + path, body, p.key, p.cert.isEmpty() ? null : p.cert, 5000));
    }

    static JSONObject undo(String tv, String action) throws Exception { return new JSONObject().put("tv", tv).put("action", action); }

    /** How far volume_up / volume_down move: 3 when not said, never more than the whole scale (Codex 055 #9). */
    static int step(int value) { return value > 0 ? Math.min(value, 100) : 3; }

    /** tv: one control on a paired TV, with what the TV reads back afterwards. */
    Result control(Net net, String which, String action, int value, String input) throws Exception {
        if (paired.isEmpty()) return new Result("No TV is paired with Sprocket yet: find it with tv_find, then pair it with tv_pair.", null, null, null);
        Paired p = pick(which);
        if (p == null) return new Result("Which TV? Paired: " + names() + ".", null, null, null);
        action = action == null ? "" : action.trim().toLowerCase(Locale.US);
        if (p.brand.equals("LG")) return Lg.control(net, p, action, value, input);
        String n = p.name;
        switch (action) {
            case "on": {
                Boolean before = power(net, p);
                if (Boolean.TRUE.equals(before)) return new Result(n + " was already on.", null, null, null);
                // off (it says so, or asleep: not even reachable) before anything is sent: undo may turn it off again
                boolean wasOff = Boolean.FALSE.equals(before) || (before == null && !net.open(p.ip, p.port, 800));
                if (!p.mac.isEmpty()) net.wake(p.mac, p.ip);
                Boolean up = null;
                for (int i = 0; i < 10 && up == null; i++) { net.sleep(1000); up = power(net, p); }
                if (up == null) return new Result("Sent the wake-up signal to " + n + ", but it didn't answer: it may be unplugged or "
                        + "off the Wi-Fi. Don't say it's on.", null, null, null);
                key(net, p, POWER_ON);
                Boolean now = null;
                for (int i = 0; i < 5 && !Boolean.TRUE.equals(now); i++) { net.sleep(1000); now = power(net, p); }
                if (!Boolean.TRUE.equals(now)) return new Result("Pressed power on for " + n + ", but it still says it's off. Don't say it's on.", null, null, null);
                // it was off (said so, or asleep: not even reachable): undo turns it off. Reachable but no answer: it may
                // have been on all along, so undo must not turn off a TV this didn't turn on (Codex 055b #7)
                return wasOff
                        ? new Result("Turned on " + n + " (the TV says it's on).", "Turned on the TV " + n, UndoLog.AUTO, undo(n, "off"))
                        : new Result("Turned on " + n + " (the TV says it's on).", "Turned on the TV " + n, UndoLog.INFO, null);
            }
            case "off": {
                Boolean was = power(net, p);
                if (was == null || !was) return new Result(n + " is already off" + (was == null ? " (it isn't answering at all)." : "."), null, null, null);
                key(net, p, POWER_OFF);
                net.sleep(1500);
                return Boolean.TRUE.equals(power(net, p))
                        ? new Result("Pressed power off for " + n + ", but it still says it's on.", null, null, null)
                        : new Result("Turned off " + n + ".", "Turned off the TV " + n, UndoLog.AUTO, undo(n, "on"));
            }
            case "volume": case "volume_up": case "volume_down": {
                JSONObject v = setting(net, p, "audio/volume");
                int was = v.optInt("VALUE"), to = action.equals("volume") ? value
                        : was + (action.equals("volume_up") ? 1 : -1) * step(value);
                to = Math.max(0, Math.min(100, to));
                if (to == was) return new Result(n + "'s volume is already " + was + ".", null, null, null);
                if (action.equals("volume")) modify(net, p, "audio/volume", to, v.getLong("HASHVAL"));   // an exact level: silent
                else for (int i = 0; i < Math.abs(to - was); i++) {   // up/down: the remote's key, so the TV shows its volume bar
                    if (i > 0) net.sleep(150);
                    key(net, p, to > was ? VOL_UP : VOL_DOWN);
                }
                int now = setting(net, p, "audio/volume").optInt("VALUE");
                return new Result(n + " volume " + was + " -> " + now + " (read back from the TV).",
                        "TV volume " + was + " -> " + now + " on " + n, UndoLog.AUTO, undo(n, "volume").put("value", was));
            }
            case "mute": case "unmute": {
                boolean want = action.equals("mute");
                boolean was = setting(net, p, "audio/mute").optString("VALUE").equalsIgnoreCase("On");
                if (was == want) return new Result(n + " is already " + (want ? "muted." : "unmuted."), null, null, null);
                key(net, p, want ? MUTE_ON : MUTE_OFF);
                net.sleep(500);
                boolean now = setting(net, p, "audio/mute").optString("VALUE").equalsIgnoreCase("On");
                if (now != want) return new Result("Pressed " + action + " on " + n + ", but it still says " + (now ? "muted." : "unmuted."), null, null, null);
                return new Result((want ? "Muted " : "Unmuted ") + n + ".", (want ? "Muted the TV " : "Unmuted the TV ") + n,
                        UndoLog.AUTO, undo(n, want ? "unmute" : "mute"));
            }
            case "input": {
                List<Input> ins = inputList(net, p);
                Input was = null, want = match(ins, input);
                for (Input x : ins) if (x.selected) was = x;
                if (want == null) return new Result(n + " has no input called \"" + input + "\". Its inputs: " + names(ins) + ".", null, null, null);
                if (want == was) return new Result(n + " says it's already on " + want.name + ".", null, null, null);
                switchInput(net, p, want);
                net.sleep(1500);
                Input now = null;
                for (Input x : inputList(net, p)) if (x.selected) now = x;
                if (now == null || !now.id.equals(want.id))
                    return new Result("Asked " + n + " to switch to " + want.name + ", but the TV says it's on "
                            + (now == null ? "nothing it can name" : now.name) + ". Ask him what the screen shows.", null, null, null);
                return new Result("Switched " + n + " to " + want.name + " (the TV says it's on " + now.name + " now).",
                        "Switched the TV " + n + " to " + want.name, was == null ? UndoLog.INFO : UndoLog.AUTO,
                        was == null ? null : undo(n, "input").put("input", was.id));
            }
            default: {
                int[] k = KEYS.get(action);
                if (k == null) return new Result("There's no TV control called \"" + action + "\".", null, null, null);
                int times = Math.max(1, Math.min(10, value));
                for (int i = 0; i < times; i++) { if (i > 0) net.sleep(400); key(net, p, k); }
                String label = action.replace('_', ' ');
                String said = "Pressed " + label + (times > 1 ? " " + times + " times" : "") + " on " + n + ".";
                if (action.equals("input_next")) said += " The TV can't be trusted to say which input it landed on: ask him what the screen shows before pressing again.";
                return new Result(said, "Pressed " + label + " on the TV " + n, UndoLog.INFO, null);
            }
        }
    }

    /** One input: the TV's id for it ("hdmi2"), the name it shows ("HDMI-2", or his own name for it), and the older menu name. */
    static final class Input {
        final String id, name, menuName; final boolean selected;
        Input(String id, String name, String menuName, boolean selected) { this.id = id; this.name = name; this.menuName = menuName; this.selected = selected; }
    }

    /**
     * The TV's inputs and which one is on. Newer Vizios (API 3.7+, as the Vizio app itself asks)
     * list them at devices/current_inputs; older ones at devices/name_input + devices/current_input.
     */
    static List<Input> inputList(Net net, Paired p) throws Exception {
        List<Input> out = new ArrayList<>();
        try {
            JSONArray v = vizioGet(net, p, "/menu_native/dynamic/tv_settings/devices/current_inputs")
                    .getJSONArray("ITEMS").getJSONObject(0).getJSONArray("VALUE");
            for (int i = 0; i < v.length(); i++) {
                JSONObject x = v.getJSONObject(i);
                if (!x.optBoolean("VISIBLE", true)) continue;
                String shown = x.optString("CUSTOM_NAME", "");
                out.add(new Input(x.optString("CNAME"), shown.isEmpty() ? x.optString("NAME") : shown, x.optString("NAME"), x.optBoolean("SELECTED")));
            }
            if (!out.isEmpty()) return out;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("pair it again")) throw e;
        }
        String cur = setting(net, p, "devices/current_input").optString("VALUE");
        JSONArray items = vizioGet(net, p, "/menu_native/dynamic/tv_settings/devices/name_input").getJSONArray("ITEMS");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.getJSONObject(i), v = it.optJSONObject("VALUE");
            String nick = v == null ? "" : v.optString("NAME");
            out.add(new Input(it.optString("CNAME"), nick.isEmpty() ? it.optString("NAME") : nick, it.optString("NAME"), it.optString("NAME").equalsIgnoreCase(cur)));
        }
        return out;
    }

    static String names(List<Input> ins) {
        List<String> n = new ArrayList<>();
        for (Input x : ins) n.add(x.name);
        return String.join(", ", n);
    }

    /** "hdmi 2", "HDMI2", "xbox" (his own name for it), "cast", "tv"/"antenna" -> that input, or null. */
    static Input match(List<Input> ins, String said) {
        String s = squash(said);
        if (s.isEmpty()) return null;
        for (Input x : ins)
            if (s.equals(squash(x.id)) || s.equals(squash(x.name)) || s.equals(squash(x.menuName))
                    || (x.id.equals("tuner") && (s.equals("tv") || s.equals("antenna") || s.equals("cable")))
                    || (x.id.equals("cast") && s.equals("smartcast")))
                return x;
        return null;
    }

    /** The newer door the Vizio app uses (system/input/current_input, by id); older TVs take a menu change instead. */
    static void switchInput(Net net, Paired p, Input want) throws Exception {
        String body = new JSONObject().put("VALUE", want.id).toString();
        JSONObject o = new JSONObject(net.http("PUT", base(p) + "/system/input/current_input", body, p.key, p.cert.isEmpty() ? null : p.cert, 5000).body);
        String result = o.optJSONObject("STATUS") == null ? "" : o.getJSONObject("STATUS").optString("RESULT");
        if (result.equals("SUCCESS")) return;
        if (result.equals("REQUIRES_PAIRING")) ok(new Resp(200, o.toString(), null));
        JSONObject cur = setting(net, p, "devices/current_input");
        modify(net, p, "devices/current_input", want.menuName, cur.getLong("HASHVAL"));
    }

    static String squash(String s) { return s == null ? "" : s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", ""); }

    // ---- the brain's tools -----------------------------------------------------------------------------------------

    static JSONArray definitions() throws Exception {
        JSONObject none = new JSONObject().put("type", "object").put("properties", new JSONObject());
        return new JSONArray()
                .put(new JSONObject().put("name", "tv_find")
                        .put("description", "Finds the TVs on his home Wi-Fi and says which ones Sprocket can control, which need "
                                + "pairing first, and which brands it can't control yet. Only works when his phone is on Wi-Fi.")
                        .put("input_schema", none))
                .put(new JSONObject().put("name", "tv_pair")
                        .put("description", "Pairs Sprocket with a TV, once per TV (Vizio and LG so far). Call it without a code: "
                                + "the TV shows a code on its screen (or an Allow question); do what the answer says, then call again.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("tv", new JSONObject().put("type", "string").put("description", "The TV's name or a word of it, if there's more than one."))
                                .put("code", new JSONObject().put("type", "string").put("description", "The code he read off the TV's screen.")))))
                .put(new JSONObject().put("name", "tv")
                        .put("description", "Controls a paired TV on his home Wi-Fi: on, off, volume (set, up, down), mute, "
                                + "unmute, switch input, channel up/down, and the remote's keys. Say only what the answer says "
                                + "happened. Not paired yet? Use tv_find and tv_pair.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("tv", new JSONObject().put("type", "string").put("description", "Which TV (a word of its name), if more than one is paired."))
                                .put("action", new JSONObject().put("type", "string").put("enum", new JSONArray(java.util.Arrays.asList(ACTIONS))))
                                .put("value", new JSONObject().put("type", "integer").put("description",
                                        "For volume: the level (0-100). For volume_up/volume_down: how many steps (default 3). For a key: how many presses (default 1)."))
                                .put("input", new JSONObject().put("type", "string").put("description", "For input: which one (\"HDMI 1\", \"HDMI 2\", \"TV\", \"cast\").")))
                                .put("required", new JSONArray().put("action"))));
    }

    // ---- the real network ------------------------------------------------------------------------------------------

    static final Net REAL = new Net() {
        @Override public boolean open(String ip, int port, int ms) {
            try (Socket s = new Socket()) { s.connect(new InetSocketAddress(ip, port), ms); return true; } catch (Exception e) { return false; }
        }

        @Override public Resp http(String method, String url, String body, String auth, String pin, int ms) throws Exception {
            if (url.startsWith("http://")) return plainGet(url, auth, body, ms);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            final String[] seen = {null};
            if (c instanceof javax.net.ssl.HttpsURLConnection) {
                javax.net.ssl.HttpsURLConnection h = (javax.net.ssl.HttpsURLConnection) c;
                h.setSSLSocketFactory(tls(pin, seen));
                h.setHostnameVerifier((host, session) -> true);   // a numbered address; the fingerprint is the check
            }
            c.setRequestMethod(method);
            c.setConnectTimeout(ms);
            c.setReadTimeout(ms);
            c.setRequestProperty("Accept", "*/*");   // Java's default Accept list makes a Vizio answer 500 (found live, 11-19)
            if (auth != null) c.setRequestProperty("AUTH", auth);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            if (in != null) try (InputStream i = in) {   // a TV's answer is small: stop reading at 1 MB (Codex 055b Q4)
                byte[] buf = new byte[8192]; int n;
                while ((n = i.read(buf)) > 0) { b.write(buf, 0, n); if (b.size() > 1 << 20) throw new java.io.IOException("the TV's answer was too long"); }
            }
            return new Resp(code, b.toString("UTF-8"), seen[0]);
        }

        @Override public void wake(String mac, String ip) throws Exception {
            byte[] p = magic(mac);
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                String bcast = ip.substring(0, ip.lastIndexOf('.') + 1) + "255";
                for (String to : new String[]{"255.255.255.255", bcast, ip})
                    for (int port : new int[]{9, 7}) s.send(new DatagramPacket(p, p.length, InetAddress.getByName(to), port));
            }
        }

        @Override public void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

        @Override public Ws ws(String url, String pin, int ms) throws Exception { return RealWs.open(url, pin, ms); }
    };

    /**
     * TVs use their own self-made certificates. The first look and the pairing take what the TV shows and remember its
     * fingerprint; after that, a TV showing a different certificate gets nothing (no key, no command).
     */
    static javax.net.ssl.SSLSocketFactory tls(String pin, String[] seen) throws Exception {
        javax.net.ssl.X509TrustManager tm = new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] ch, String a) { }
            public void checkServerTrusted(X509Certificate[] ch, String a) throws java.security.cert.CertificateException {
                if (ch == null || ch.length == 0) throw new java.security.cert.CertificateException("no certificate");
                seen[0] = sha(ch[0]);
                if (pin != null && !pin.equalsIgnoreCase(seen[0]))
                    throw new java.security.cert.CertificateException("not the TV Sprocket paired with (its certificate changed)");
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
        ctx.init(null, new javax.net.ssl.TrustManager[]{tm}, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }

    /** A small websocket client (text messages only), enough for LG's protocol. Android has no built-in one. */
    static final class RealWs implements Ws {
        private final Socket s; private final InputStream in; private final OutputStream out; private final String cert;
        private final java.security.SecureRandom rnd = new java.security.SecureRandom();

        private RealWs(Socket s, String cert) throws Exception { this.s = s; in = s.getInputStream(); out = s.getOutputStream(); this.cert = cert; }

        static RealWs open(String url, String pin, int ms) throws Exception {
            java.net.URI u = new java.net.URI(url);
            boolean secure = u.getScheme().equals("wss");
            if (!secure) throw new IllegalArgumentException("only secure websockets (wss) are used");
            int port = u.getPort() > 0 ? u.getPort() : (secure ? 443 : 80);
            String[] seen = {null};
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(u.getHost(), port), ms);
            raw.setSoTimeout(ms);
            Socket s = raw;
            if (secure) {
                javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) tls(pin, seen).createSocket(raw, u.getHost(), port, true);
                ssl.startHandshake();
                s = ssl;
            }
            RealWs ws = new RealWs(s, seen[0]);
            byte[] k = new byte[16];
            ws.rnd.nextBytes(k);
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            ws.out.write(("GET " + path + " HTTP/1.1\r\nHost: " + u.getHost() + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + java.util.Base64.getEncoder().encodeToString(k) + "\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ws.out.flush();
            StringBuilder head = new StringBuilder();
            while (!head.toString().endsWith("\r\n\r\n")) {
                int c = ws.in.read();
                if (c < 0 || head.length() > 16384) { ws.close(); throw new java.io.IOException("the TV closed the connection"); }
                head.append((char) c);
            }
            if (!head.toString().startsWith("HTTP/1.1 101")) { ws.close(); throw new java.io.IOException("the TV refused the connection: " + head.toString().split("\r\n")[0]); }
            return ws;
        }

        @Override public synchronized void send(String text) throws Exception { frame(1, text.getBytes(StandardCharsets.UTF_8)); }

        private void frame(int op, byte[] data) throws Exception {
            ByteArrayOutputStream f = new ByteArrayOutputStream();
            f.write(0x80 | op);
            if (data.length < 126) f.write(0x80 | data.length);
            else if (data.length < 65536) { f.write(0x80 | 126); f.write(data.length >> 8); f.write(data.length & 0xff); }
            else { f.write(0x80 | 127); for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >> (8 * i)) & 0xff); }
            byte[] mask = new byte[4];
            rnd.nextBytes(mask);
            f.write(mask);
            for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i % 4]);
            out.write(f.toByteArray());
            out.flush();
        }

        @Override public String recv(int ms) throws Exception {
            s.setSoTimeout(Math.max(1, ms));
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (true) {
                int b0 = read(), b1 = read();
                int op = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) len = (read() << 8) | read();
                else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | read(); }
                if (len > (8 << 20)) throw new java.io.IOException("message too big");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) { mask = new byte[4]; for (int i = 0; i < 4; i++) mask[i] = (byte) read(); }
                byte[] data = new byte[(int) len];
                for (int i = 0; i < len; i++) data[i] = (byte) (read() ^ (mask == null ? 0 : mask[i % 4]));
                if (op == 9) { synchronized (this) { frame(10, data); } continue; }   // ping -> pong
                if (op == 10) continue;
                if (op == 8) throw new java.io.IOException("the TV closed the connection");
                msg.write(data);
                if ((b0 & 0x80) != 0) return msg.toString("UTF-8");
            }
        }

        private int read() throws Exception {
            int c = in.read();
            if (c < 0) throw new java.io.IOException("the TV closed the connection");
            return c;
        }

        @Override public String cert() { return cert; }

        @Override public void close() { try { s.close(); } catch (Exception ignored) { } }
    }

    /**
     * A plain-http read on the home network (a Roku TV's "who are you"). Android only allows plain http to the
     * addresses in network_security_config, and a TV's address isn't known ahead; this asks with a bare GET that
     * carries nothing (no key, no body), so nothing private can travel unencrypted.
     */
    static Resp plainGet(String url, String auth, String body, int ms) throws Exception {
        if (auth != null || body != null) throw new IllegalArgumentException("plain http is only for empty reads");
        URL u = new URL(url);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(u.getHost(), u.getPort()), ms);
            s.setSoTimeout(ms);
            String path = u.getFile().isEmpty() ? "/" : u.getFile();
            s.getOutputStream().write(("GET " + path + " HTTP/1.0\r\nHost: " + u.getHost() + ":" + u.getPort()
                    + "\r\nAccept: */*\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0 && b.size() < 1 << 20) b.write(buf, 0, n);
            String all = b.toString("UTF-8");
            int split = all.indexOf("\r\n\r\n");
            String head = split < 0 ? all : all.substring(0, split);
            int code = 0;
            try { code = Integer.parseInt(head.split(" ")[1].trim()); } catch (Exception ignored) { }
            return new Resp(code, split < 0 ? "" : all.substring(split + 4), null);
        }
    }

    static String sha(X509Certificate c) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { return ""; }
    }

    /** The Wake-on-LAN packet: six 0xFF, then the hardware address sixteen times. */
    static byte[] magic(String mac) {
        String[] parts = mac.split("[:-]");
        if (parts.length != 6) throw new IllegalArgumentException("not a hardware address: " + mac);
        byte[] m = new byte[6];
        for (int i = 0; i < 6; i++) m[i] = (byte) Integer.parseInt(parts[i], 16);
        byte[] p = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) p[i] = (byte) 0xff;
        for (int i = 0; i < 16; i++) System.arraycopy(m, 0, p, 6 + i * 6, 6);
        return p;
    }
}
