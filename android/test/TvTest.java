package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tv on the desktop JDK, against a pretend Vizio (made-up name, model and addresses). */
public class TvTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static void has(String what, String got, String part) {
        n++;
        if (got == null || !got.contains(part)) { fails++; System.out.println("FAIL " + what + ": <" + got + "> lacks <" + part + ">"); }
    }

    static void lacks(String what, String got, String part) {
        n++;
        if (got != null && got.contains(part)) { fails++; System.out.println("FAIL " + what + ": <" + got + "> has <" + part + ">"); }
    }

    static final String KEY = "Zsecret-key-123", CERT = "c3rt", CODE = "4821";

    /** A pretend Vizio (plus an LG, a Samsung, a Roku TV and a Roku stick) on 192.168.1.x. */
    static final class FakeNet implements Tv.Net {
        boolean asleep, on = true, muted, neverWakes, ignorePowerKey, blocked, pairingRequired, oldApi, stuckInput;
        int powerTimeouts;   // the next n power reads time out (a hiccup on a TV that's on)
        int volume = 16; String input = "HDMI-1", cert = CERT;
        long reqToken = 777; boolean showingCode;
        final List<String> keys = new ArrayList<>(), log = new ArrayList<>(), pins = new ArrayList<>();
        final List<String> woke = new ArrayList<>(), unpinned = new ArrayList<>();
        final Map<String, int[]> ports = new HashMap<>();
        { ports.put("192.168.1.42", new int[]{7345}); ports.put("192.168.1.105", new int[]{3001}); ports.put("192.168.1.50", new int[]{8002});
          ports.put("192.168.1.60", new int[]{8060}); ports.put("192.168.1.61", new int[]{8060}); ports.put("192.168.1.9", new int[]{80}); }

        @Override public boolean open(String ip, int port, int ms) {
            int[] p = ports.get(ip);
            if (p == null || (ip.equals("192.168.1.42") && asleep) || (ip.equals("192.168.1.105") && !lg.on)) return false;
            for (int x : p) if (x == port) return true;
            return false;
        }

        static String status(String r) { return "{\"STATUS\":{\"RESULT\":\"" + r + "\",\"DETAIL\":\"\"}"; }

        @Override public Tv.Resp http(String method, String url, String body, String auth, String pin, int ms) throws Exception {
            log.add(method + " " + url + (auth != null ? " AUTH" : ""));
            if (url.startsWith("http://192.168.1.60:8060")) return new Tv.Resp(200, "<device-info><is-tv>true</is-tv><user-device-name>Den TV</user-device-name><model-name>TCL 5</model-name></device-info>", null);
            if (url.equals("http://192.168.1.105:1298/"))
                return new Tv.Resp(200, "<root><device><friendlyName>[LG] webOS TV TX5050ABC</friendlyName><modelNumber>50TX5050ABC</modelNumber></device></root>", null);
            if (url.startsWith("http://192.168.1.61:8060")) return new Tv.Resp(200, "<device-info><is-tv>false</is-tv><user-device-name>Stick</user-device-name></device-info>", null);
            if (url.startsWith("https://192.168.1.50:8002")) return new Tv.Resp(200, "{\"device\":{\"name\":\"Office TV\",\"modelName\":\"QN55\"}}", null);
            if (!url.startsWith("https://192.168.1.42:7345")) throw new java.net.ConnectException("refused");
            if (asleep) throw new java.net.SocketTimeoutException("asleep");
            if (auth != null && pin == null) unpinned.add(method + " " + url);   // the key must only go with the pinned certificate
            if (pin != null) { pins.add(pin); if (!pin.equals(cert)) throw new javax.net.ssl.SSLHandshakeException("not the TV Sprocket paired with"); }
            String path = url.substring("https://192.168.1.42:7345".length());
            if (path.equals("/state/device/deviceinfo"))
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEMS\":[{\"VALUE\":{\"MODEL_NAME\":\"VZ40T-2000\",\"CAST_NAME\":\"Guest Room\"}}]}", cert);
            if (path.equals("/state/device/power_mode") && powerTimeouts > 0) { powerTimeouts--; throw new java.net.SocketTimeoutException("hiccup"); }
            if (path.equals("/state/device/power_mode"))
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEMS\":[{\"VALUE\":" + (on ? 1 : 0) + "}]}", cert);
            if (path.equals("/pairing/start")) {
                if (blocked) return new Tv.Resp(200, status("BLOCKED") + "}", cert);
                JSONObject b = new JSONObject(body);
                eq("pairing names Sprocket", b.getString("DEVICE_NAME"), "Sprocket");
                showingCode = true;
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEM\":{\"PAIRING_REQ_TOKEN\":" + reqToken + ",\"CHALLENGE_TYPE\":1}}", cert);
            }
            if (path.equals("/pairing/pair")) {
                JSONObject b = new JSONObject(body);
                if (!showingCode || !b.getString("RESPONSE_VALUE").equals(CODE) || b.getLong("PAIRING_REQ_TOKEN") != reqToken)
                    return new Tv.Resp(200, status("INVALID_PARAMETER") + "}", cert);
                showingCode = false;
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEM\":{\"AUTH_TOKEN\":\"" + KEY + "\"}}", cert);
            }
            // everything below needs the key
            if (pairingRequired || !KEY.equals(auth)) return new Tv.Resp(200, status("REQUIRES_PAIRING") + "}", cert);
            if (path.equals("/key_command/")) {
                JSONObject k = new JSONObject(body).getJSONArray("KEYLIST").getJSONObject(0);
                String code = k.getInt("CODESET") + "/" + k.getInt("CODE");
                keys.add(code);
                if (code.equals("11/1") && !ignorePowerKey) on = true;
                if (code.equals("11/0")) on = false;
                if (code.equals("5/3")) muted = true;
                if (code.equals("5/2")) muted = false;
                if (code.equals("5/1")) volume = Math.min(100, volume + 1);
                if (code.equals("5/0")) volume = Math.max(0, volume - 1);
                return new Tv.Resp(200, status("SUCCESS") + "}", cert);
            }
            if (path.endsWith("/network/network_information"))
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEMS\":[{\"CNAME\":\"connection_type\",\"VALUE\":\"WiFi 5GHz\"},"
                        + "{\"CNAME\":\"rj45_mac\",\"VALUE\":\"02:1A:2B:3C:4D:5F\"},{\"CNAME\":\"wlan_mac\",\"VALUE\":\"02:1A:2B:3C:4D:5E\"}]}", cert);
            if (path.endsWith("/audio/volume")) {
                if (method.equals("PUT")) { JSONObject b = new JSONObject(body); eq("volume hash sent", b.getLong("HASHVAL"), 4242); volume = b.getInt("VALUE"); return new Tv.Resp(200, status("SUCCESS") + "}", cert); }
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEMS\":[{\"CNAME\":\"volume\",\"VALUE\":" + volume + ",\"HASHVAL\":4242}]}", cert);
            }
            if (path.endsWith("/audio/mute"))
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEMS\":[{\"CNAME\":\"mute\",\"VALUE\":\"" + (muted ? "On" : "Off") + "\",\"HASHVAL\":5}]}", cert);
            if (path.endsWith("/devices/current_inputs")) {
                if (oldApi) return new Tv.Resp(200, status("URI_NOT_FOUND") + "}", cert);
                String[][] ins = {{"cast", "SMARTCAST", "SMARTCAST"}, {"watchfree", "WatchFree+", "WatchFree+"}, {"hdmi1", "HDMI-1", "HDMI-1"},
                        {"hdmi2", "HDMI-2", "Xbox"}, {"tuner", "Antenna", "Antenna"}, {"Tuner 4", "Tuner 4", "Tuner 4"}};
                JSONArray v = new JSONArray();
                for (String[] x : ins)
                    v.put(new JSONObject().put("CNAME", x[0]).put("NAME", x[1]).put("CUSTOM_NAME", x[2])
                            .put("VISIBLE", !x[0].equals("Tuner 4")).put("SELECTED", x[1].equals(input)));
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEMS\":[{\"CNAME\":\"current_inputs\",\"VALUE\":" + v + "}]}", cert);
            }
            if (path.equals("/system/input/current_input")) {
                if (oldApi) return new Tv.Resp(200, status("URI_NOT_FOUND") + "}", cert);
                String id = new JSONObject(body).getString("VALUE");
                Map<String, String> byId = new HashMap<>();
                byId.put("cast", "SMARTCAST"); byId.put("hdmi1", "HDMI-1"); byId.put("hdmi2", "HDMI-2"); byId.put("tuner", "Antenna"); byId.put("watchfree", "WatchFree+");
                if (byId.get(id) == null) return new Tv.Resp(200, status("INVALID_PARAMETER") + "}", cert);
                if (!stuckInput) input = byId.get(id);
                return new Tv.Resp(200, status("SUCCESS") + "}", cert);
            }
            if (path.endsWith("/devices/current_input")) {
                if (method.equals("PUT")) { JSONObject b = new JSONObject(body); eq("input hash sent", b.getLong("HASHVAL"), 99); input = b.getString("VALUE"); return new Tv.Resp(200, status("SUCCESS") + "}", cert); }
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEMS\":[{\"CNAME\":\"current_input\",\"VALUE\":\"" + input + "\",\"HASHVAL\":99}]}", cert);
            }
            if (path.endsWith("/devices/name_input"))
                return new Tv.Resp(200, status("SUCCESS") + ",\"ITEMS\":[{\"CNAME\":\"cast\",\"NAME\":\"SMARTCAST\",\"VALUE\":{\"NAME\":\"SMARTCAST\"}},"
                        + "{\"CNAME\":\"hdmi1\",\"NAME\":\"HDMI-1\",\"VALUE\":{\"NAME\":\"HDMI-1\"}},{\"CNAME\":\"hdmi2\",\"NAME\":\"HDMI-2\",\"VALUE\":{\"NAME\":\"Xbox\"}},"
                        + "{\"CNAME\":\"tuner\",\"NAME\":\"TV\",\"VALUE\":{\"NAME\":\"TV\"}}]}", cert);
            return new Tv.Resp(200, status("URI_NOT_FOUND") + "}", cert);
        }

        final FakeLg lg = new FakeLg();

        @Override public void wake(String mac, String ip) {
            woke.add(mac + "@" + ip);
            if (ip.equals("192.168.1.105")) { if (mac.equals(FakeLg.MAC) && !lg.neverWakes) lg.on = true; return; }
            if (!neverWakes) asleep = false;
        }
        @Override public Tv.Ws ws(String url, String pin, int ms) throws Exception { return lg.ws(url, pin); }
        @Override public void sleep(long ms) { }
    }

    /** A pretend LG webOS TV at 192.168.1.105 (it calls itself "[LG] webOS TV TX5050ABC"). */
    static final class FakeLg {
        static final String MAC = "02:aa:bb:cc:dd:01", LGKEY = "lg-client-key-9", LGCERT = "lgcert", PIN = "7788";
        boolean on = true, muted, promptMode, allowed, revoked, neverWakes, stuckInput, keyedWithoutPin, screenOff;
        int volume = 10; String fg = "com.webos.app.hdmi1", cert = LGCERT;
        final List<String> calls = new ArrayList<>(), buttons = new ArrayList<>();

        Tv.Ws ws(String url, String pin) throws Exception {
            if (!url.startsWith("wss://192.168.1.105:3001/")) throw new java.net.ConnectException("refused " + url);
            if (!on) throw new java.net.ConnectException("the TV is off");
            if (pin != null && !pin.equals(cert)) throw new javax.net.ssl.SSLHandshakeException("not the TV Sprocket paired with");
            boolean pointer = url.endsWith("netinput.pointer.sock");
            java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
            boolean[] waitingAllow = {false};
            return new Tv.Ws() {
                public void send(String text) throws Exception {
                    if (pointer) { buttons.add(text); return; }
                    JSONObject m = new JSONObject(text);
                    String id = m.optString("id");
                    if (m.optString("type").equals("register")) {
                        JSONObject pl = m.getJSONObject("payload");
                        eq("LG asks only for its few permissions", pl.getJSONObject("manifest").getJSONArray("permissions").length(), Lg.PERMISSIONS.length);
                        String k = pl.optString("client-key");
                        if (!k.isEmpty()) {
                            if (pin == null) keyedWithoutPin = true;
                            q.add(k.equals(LGKEY) && !revoked ? "{\"type\":\"registered\",\"id\":\"reg\",\"payload\":{\"client-key\":\"" + LGKEY + "\"}}"
                                    : "{\"type\":\"response\",\"id\":\"reg\",\"payload\":{\"pairingType\":\"PROMPT\",\"returnValue\":true}}");
                        } else {
                            q.add("{\"type\":\"response\",\"id\":\"reg\",\"payload\":{\"pairingType\":\"" + (promptMode ? "PROMPT" : "PIN") + "\",\"returnValue\":true}}");
                            waitingAllow[0] = promptMode;
                        }
                        return;
                    }
                    String uri = m.optString("uri").replace("ssap://", "");
                    JSONObject in = m.optJSONObject("payload");
                    calls.add(uri);
                    JSONObject out = new JSONObject().put("returnValue", true);
                    switch (uri) {
                        case "pairing/setPin":
                            if (!in.getString("pin").equals(PIN)) { q.add("{\"type\":\"error\",\"id\":\"pin\",\"error\":\"500 Application error\",\"payload\":{\"returnValue\":false}}"); return; }
                            q.add("{\"type\":\"response\",\"id\":\"pin\",\"payload\":{\"returnValue\":true}}");
                            q.add("{\"type\":\"registered\",\"id\":\"reg\",\"payload\":{\"client-key\":\"" + LGKEY + "\"}}");
                            return;
                        case "audio/getVolume": out.put("volumeStatus", new JSONObject().put("volume", volume).put("muteStatus", muted)); break;
                        case "audio/volumeUp": volume++; break;
                        case "audio/volumeDown": volume--; break;
                        case "audio/setVolume": volume = in.getInt("volume"); break;
                        case "audio/setMute": muted = in.getBoolean("mute"); break;
                        case "com.webos.service.tvpower/power/getPowerState": out.put("state", !on ? "Active Standby" : screenOff ? "Screen Off" : "Active"); break;
                        case "com.webos.service.tvpower/power/turnOnScreen": screenOff = false; break;
                        case "system/turnOff": on = false; break;
                        case "tv/getExternalInputList":
                            out.put("devices", new JSONArray()
                                    .put(new JSONObject().put("id", "HDMI_1").put("label", "HDMI 1").put("appId", "com.webos.app.hdmi1"))
                                    .put(new JSONObject().put("id", "HDMI_2").put("label", "HDMI 2").put("appId", "com.webos.app.hdmi2"))); break;
                        case "tv/switchInput": if (!stuckInput) fg = "com.webos.app." + in.getString("inputId").toLowerCase().replace("_", ""); break;
                        case "system.launcher/launch": if (!stuckInput) fg = in.getString("id"); break;
                        case "com.webos.applicationManager/getForegroundAppInfo": out.put("appId", fg); break;
                        case "com.webos.service.connectionmanager/getinfo":
                            out.put("wifiInfo", new JSONObject().put("state", "connected").put("macAddress", MAC))
                               .put("wiredInfo", new JSONObject().put("state", "disconnected").put("macAddress", "aa:bb:cc:dd:ee:ff")); break;
                        case "com.webos.service.networkinput/getPointerInputSocket": out.put("socketPath", "ws://192.168.1.105:3000/resources/p/netinput.pointer.sock"); break;
                        case "tv/channelUp": case "tv/channelDown": case "media.controls/play": case "media.controls/pause": break;
                        default: q.add("{\"type\":\"error\",\"id\":\"" + id + "\",\"error\":\"404 no such service\"}"); return;
                    }
                    q.add(new JSONObject().put("type", "response").put("id", id).put("payload", out).toString());
                }
                public String recv(int ms) throws Exception {
                    if (q.isEmpty() && waitingAllow[0] && allowed) {
                        waitingAllow[0] = false;
                        q.add("{\"type\":\"registered\",\"id\":\"reg\",\"payload\":{\"client-key\":\"" + LGKEY + "\"}}");
                    }
                    if (q.isEmpty()) throw new java.net.SocketTimeoutException("nothing");
                    return q.poll();
                }
                public String cert() { return cert; }
                public void close() { }
            };
        }
    }

    static Tv pairedLg(FakeNet net) throws Exception {
        Tv t = Tv.load("");
        t.find(net, "192.168.1.53");
        t.pair(net, "192.168.1.53", "lg", null);
        t.pair(net, "192.168.1.53", null, FakeLg.PIN);
        return t;
    }

    static Tv paired(FakeNet net) throws Exception {
        Tv t = Tv.load("");
        t.pair(net, "192.168.1.53", "guest", null);
        t.pair(net, "192.168.1.53", null, CODE);
        return t;
    }

    public static void main(String[] a) throws Exception {
        // finding
        List<String> hosts = Tv.hosts("192.168.1.53");
        eq("a /24 without the phone", hosts.size(), 253);
        eq("phone left out", hosts.contains("192.168.1.53"), false);
        eq("starts at .1", hosts.get(0), "192.168.1.1");
        byte[] m = Tv.magic("02:1A:2B:3C:4D:5E");
        eq("wake packet length", m.length, 102);
        eq("wake packet starts with FF", m[5] & 0xff, 255);
        eq("wake packet repeats the address", m[6 + 15 * 6] & 0xff, 0x02);

        FakeNet net = new FakeNet();
        Tv t = Tv.load("");
        has("a device id is made", t.deviceId, "sprocket-");
        String found = t.find(net, "192.168.1.53");
        has("Vizio found by its name", found, "Guest Room (Vizio VZ40T-2000): not paired yet");
        has("LG named from its page, pairable", found, "LG webOS TV TX5050ABC (LG 50TX5050ABC): not paired yet");
        has("Samsung named from its API", found, "Office TV (Samsung QN55): Sprocket can't control Samsung TVs yet.");
        has("a Roku TV", found, "Den TV (Roku TCL 5)");
        lacks("a Roku stick isn't a TV", found, "Stick");
        lacks("the router isn't a TV", found, "192.168.1.9");

        // pairing
        String start = t.pair(net, "192.168.1.53", "guest", null);
        has("pairing puts a code on the TV", start, "A pairing code is on Guest Room's screen now");
        eq("the TV is showing the code", net.showingCode, true);
        has("a wrong code says so", t.pair(net, "192.168.1.53", null, "1111"), "Pairing didn't work");
        net.showingCode = true;
        String done = t.pair(net, "192.168.1.53", null, "48 21");
        has("the right code pairs", done, "Paired with Guest Room");
        has("it can turn it on (address read)", done, "including turning it on");
        eq("one paired TV", t.paired.size(), 1);
        Tv.Paired p = t.paired.get(0);
        eq("the key is kept", p.key, KEY);
        eq("the certificate is pinned", p.cert, CERT);
        eq("the Wi-Fi address, not the wired one", p.mac, "02:1A:2B:3C:4D:5E");
        eq("nothing waiting any more", t.pending == null, true);
        has("a code with no pairing waiting", t.pair(net, "192.168.1.53", null, CODE), "There's no pairing waiting");
        has("a Samsung can't be paired yet", t.pair(net, "192.168.1.53", "office", null), "Office TV is a Samsung TV; Sprocket can only pair with Vizio and LG TVs so far.");
        FakeNet b = new FakeNet(); b.blocked = true;
        Tv tb = Tv.load(""); tb.find(b, "192.168.1.53");
        has("a TV already showing a pairing screen", tb.pair(b, "192.168.1.53", "guest", null), "ask him to close it with Back");
        {   // Codex 055 #4: "kitchen" with two kitchen TVs asks which, and no TV is contacted
            FakeNet two = new FakeNet(); Tv tt = Tv.load("");
            tt.found.add(new Tv.Found("Vizio", "Kitchen Left", "V1", "192.168.1.42", 7345));
            tt.found.add(new Tv.Found("Vizio", "Kitchen Right", "V2", "192.168.1.25", 7345));
            has("two fit: asks which", tt.pair(two, "192.168.1.53", "kitchen", null), "Which TV? More than one fits: Kitchen Left, Kitchen Right.");
            eq("two fit: no TV put in pairing", two.showingCode, false);
            has("two found, none named: asks which", tt.pair(two, "192.168.1.53", null, null), "Which TV?");
            eq("the exact one still pairs", tt.pair(two, "192.168.1.53", "left", null).contains("pairing code is on Kitchen Left"), true);
        }

        // saving: the key and pin survive; a fresh phone id stays the same
        Tv back = Tv.load(t.toJson());
        eq("saved key", back.paired.get(0).key, KEY);
        eq("saved pin", back.paired.get(0).cert, CERT);
        eq("saved address", back.paired.get(0).mac, p.mac);
        eq("same device id", back.deviceId, t.deviceId);
        lacks("tv_find never shows the key", back.find(net, "192.168.1.53"), KEY);
        has("paired shows as controllable", back.find(net, "192.168.1.53"), "Guest Room (Vizio VZ40T-2000): paired, Sprocket can control it.");

        // which TV
        eq("a word of its name", t.pick("guest tv").name, "Guest Room");
        eq("the only one, unnamed", t.pick("").name, "Guest Room");
        eq("a TV that isn't paired", t.pick("kitchen") == null, true);

        // power
        net = new FakeNet(); t = paired(net);
        net.on = false; net.asleep = true;
        Tv.Result r = t.control(net, null, "on", 0, null);
        eq("asleep: woken by its address", net.woke.toString(), "[02:1A:2B:3C:4D:5E@192.168.1.42]");
        eq("then power on pressed", net.keys.toString(), "[11/1]");
        has("on, read back", r.text, "Turned on Guest Room (the TV says it's on).");
        eq("undo turns it off", r.undoKind + " " + r.undo, "auto {\"tv\":\"Guest Room\",\"action\":\"off\"}");
        r = t.control(net, null, "on", 0, null);
        eq("already on: no undo", r.text + " " + r.undo, "Guest Room was already on. null");
        net.on = false; net.asleep = true; net.neverWakes = true;
        r = t.control(net, null, "on", 0, null);
        has("never answers: no claim", r.text, "Don't say it's on.");
        eq("never answers: nothing to undo", r.undo == null, true);
        // Codex 055b #7: on all along, but the first power read timed out: the TV still turns out on, and undo mustn't turn it off
        net = new FakeNet(); t = paired(net); net.powerTimeouts = 1;
        r = t.control(net, null, "on", 0, null);
        eq("a hiccup on a TV that was on: undo only reported", r.undoKind + " " + r.undo, "info null");
        net = new FakeNet(); t = paired(net); net.on = false; net.ignorePowerKey = true;
        has("power key ignored: no claim", t.control(net, null, "on", 0, null).text, "it still says it's off. Don't say it's on.");
        net = new FakeNet(); t = paired(net);
        r = t.control(net, null, "off", 0, null);
        eq("off pressed", net.keys.toString(), "[11/0]");
        has("off, read back", r.text, "Turned off Guest Room.");
        eq("undo turns it back on", r.undo.optString("action"), "on");
        eq("already off", t.control(net, null, "off", 0, null).text, "Guest Room is already off.");

        // volume, mute, input
        net = new FakeNet(); t = paired(net);
        r = t.control(net, "guest", "volume_up", 0, null);
        eq("up 3 by default, read back", r.text, "Guest Room volume 16 -> 19 (read back from the TV).");
        eq("undo puts back the old level", r.undo.optInt("value"), 16);
        eq("up/down uses the remote's key (the TV shows its volume bar)", net.keys.toString(), "[5/1, 5/1, 5/1]");
        eq("set exactly", t.control(net, null, "volume", 25, null).text, "Guest Room volume 19 -> 25 (read back from the TV).");
        eq("down 5", t.control(net, null, "volume_down", 5, null).text, "Guest Room volume 25 -> 20 (read back from the TV).");
        eq("no change: no undo", t.control(net, null, "volume", 20, null).undo == null, true);
        eq("never above 100", t.control(net, null, "volume", 300, null).text, "Guest Room volume 20 -> 100 (read back from the TV).");
        t.control(net, null, "volume", 16, null);
        net.keys.clear();   // Codex 055 #9: a huge step mustn't wrap round and turn it DOWN
        eq("a huge step up goes up", t.control(net, null, "volume_up", Integer.MAX_VALUE, null).text, "Guest Room volume 16 -> 100 (read back from the TV).");
        eq("step capped", Tv.step(Integer.MAX_VALUE) + " " + Tv.step(0) + " " + Tv.step(-5) + " " + Tv.step(7), "100 3 3 7");
        net.keys.clear();
        r = t.control(net, null, "mute", 0, null);
        eq("muted, read back", r.text + " " + net.keys, "Muted Guest Room. [5/3]");
        eq("undo unmutes", r.undo.optString("action"), "unmute");
        eq("already muted", t.control(net, null, "mute", 0, null).text, "Guest Room is already muted.");
        r = t.control(net, null, "input", 0, "hdmi 2");
        eq("input by its plain name, shown by his name for it", r.text, "Switched Guest Room to Xbox (the TV says it's on Xbox now).");
        eq("input switched", net.input, "HDMI-2");
        eq("undo goes back to the old input", r.undoKind + " " + r.undo, "auto {\"tv\":\"Guest Room\",\"action\":\"input\",\"input\":\"hdmi1\"}");
        eq("input by the name he gave it", t.control(net, null, "input", 0, "xbox").text, "Guest Room says it's already on Xbox.");
        eq("tv means the antenna", t.control(net, null, "input", 0, "TV").text, "Switched Guest Room to Antenna (the TV says it's on Antenna now).");
        eq("the undo input works", t.control(net, null, "input", 0, "hdmi1").text, "Switched Guest Room to HDMI-1 (the TV says it's on HDMI-1 now).");
        eq("an input that doesn't exist (hidden ones left out)", t.control(net, null, "input", 0, "hdmi 4").text,
                "Guest Room has no input called \"hdmi 4\". Its inputs: SMARTCAST, WatchFree+, HDMI-1, Xbox, Antenna.");
        net.stuckInput = true;
        r = t.control(net, null, "input", 0, "cast");
        has("TV didn't change: no claim", r.text, "but the TV says it's on HDMI-1. Ask him what the screen shows.");
        eq("TV didn't change: nothing to undo", r.undo == null, true);
        net.stuckInput = false; net.oldApi = true;
        r = t.control(net, null, "input", 0, "hdmi 2");
        eq("an older Vizio: the menu way", r.text, "Switched Guest Room to Xbox (the TV says it's on Xbox now).");

        // keys
        net = new FakeNet(); t = paired(net);
        eq("home key", t.control(net, null, "home", 0, null).text + " " + net.keys, "Pressed home on Guest Room. [4/15]");
        r = t.control(net, null, "down", 3, null);
        eq("three presses", r.text, "Pressed down 3 times on Guest Room.");
        has("input_next: ask what the screen shows", t.control(net, null, "input_next", 0, null).text, "ask him what the screen shows");
        has("unknown control", t.control(net, null, "dance", 0, null).text, "There's no TV control called \"dance\".");
        eq("keys carry nothing to undo", r.undoKind, "info");

        // the key goes only with the pin; a TV with a new certificate gets nothing
        eq("every keyed call was pinned", net.pins.isEmpty() ? "none" : "pinned " + net.pins.stream().allMatch(CERT::equals), "pinned true");
        eq("the key never went without the pin", net.unpinned.toString(), "[]");
        net.cert = "someone-else";
        String err = "";
        try { t.control(net, null, "volume_up", 0, null); } catch (Exception e) { err = e.getMessage(); }
        has("changed certificate refused", err, "not the TV Sprocket paired with");
        net.cert = CERT; net.pairingRequired = true;
        err = "";
        try { t.control(net, null, "mute", 0, null); } catch (Exception e) { err = e.getMessage(); }
        has("unpaired on the TV: pair again", err, "pair it again with tv_pair");

        // nothing paired; two paired
        has("nothing paired yet", Tv.load("").control(net, null, "on", 0, null).text, "No TV is paired with Sprocket yet");
        Tv two = Tv.load(t.toJson());
        Tv.Paired other = new Tv.Paired(); other.brand = "Vizio"; other.name = "Living Room"; other.ip = "192.168.1.30"; other.port = 7345;
        other.key = "k"; other.cert = ""; other.mac = ""; other.model = "";
        two.paired.add(other);
        has("two paired, none named", two.control(net, null, "on", 0, null).text, "Which TV? Paired: Guest Room, Living Room.");

        // ---- LG ----
        net = new FakeNet();
        t = Tv.load("");
        t.find(net, "192.168.1.53");
        has("two TVs to choose from", t.pair(net, "192.168.1.53", null, null), "Which TV? Found: LG webOS TV TX5050ABC, Guest Room.");
        has("LG pairing shows a code", t.pair(net, "192.168.1.53", "lg", null), "A pairing code is on LG webOS TV TX5050ABC's screen now.");
        eq("its connection is held for the code", t.held != null, true);
        has("a wrong LG code", t.pair(net, "192.168.1.53", null, "1234"), "Pairing didn't work");
        eq("a failed pairing lets go", t.held == null && t.pending == null, true);
        has("code with nothing waiting", t.pair(net, "192.168.1.53", null, "7788"), "There's no pairing waiting");
        t.pair(net, "192.168.1.53", "lg", null);
        String lgDone = t.pair(net, "192.168.1.53", null, "77 88");
        has("LG paired", lgDone, "Paired with LG webOS TV TX5050ABC. Sprocket can control it now, including turning it on.");
        Tv.Paired lp = t.pairedNamed("LG webOS TV TX5050ABC", "LG");
        eq("LG key kept", lp.key, FakeLg.LGKEY);
        eq("LG certificate pinned", lp.cert, FakeLg.LGCERT);
        eq("LG Wi-Fi address, not the unplugged wired one", lp.mac, FakeLg.MAC);
        eq("nothing held after pairing", t.held == null, true);
        has("now shows as paired", t.find(net, "192.168.1.53"), "LG webOS TV TX5050ABC (LG 50TX5050ABC): paired");
        eq("\"the LG\" picks it", t.pick("the lg").name, "LG webOS TV TX5050ABC");
        lacks("LG key never in answers", lgDone + t.find(net, "192.168.1.53"), FakeLg.LGKEY);

        FakeNet pn = new FakeNet(); pn.lg.promptMode = true;
        Tv tp = Tv.load(""); tp.find(pn, "192.168.1.53");
        has("an LG that asks Allow instead of a code", tp.pair(pn, "192.168.1.53", "lg", null), "choose Allow (or press OK) with the LG remote");
        has("not allowed yet", tp.pair(pn, "192.168.1.53", null, null), "The TV hasn't been allowed yet.");
        pn.lg.allowed = true;
        has("allowed: paired", tp.pair(pn, "192.168.1.53", null, null), "Paired with LG webOS TV TX5050ABC.");

        net = new FakeNet(); t = pairedLg(net); FakeLg lg = net.lg;
        r = t.control(net, "lg", "volume_up", 0, null);
        eq("LG up 3, read back", r.text, "LG webOS TV TX5050ABC volume 10 -> 13 (read back from the TV).");
        eq("LG up/down presses volumeUp (the bar shows)", lg.calls.stream().filter("audio/volumeUp"::equals).count(), 3L);
        eq("LG volume undo", r.undo.optInt("value"), 10);
        eq("LG set exactly", t.control(net, "lg", "volume", 20, null).text, "LG webOS TV TX5050ABC volume 13 -> 20 (read back from the TV).");
        r = t.control(net, "lg", "mute", 0, null);
        eq("LG muted", r.text + " " + r.undo.optString("action"), "Muted LG webOS TV TX5050ABC. unmute");
        eq("LG already muted", t.control(net, "lg", "mute", 0, null).text, "LG webOS TV TX5050ABC is already muted.");
        r = t.control(net, "lg", "input", 0, "hdmi 2");
        eq("LG input switched, read back", r.text, "Switched LG webOS TV TX5050ABC to HDMI 2 (the TV says it's showing HDMI 2 now).");
        eq("LG input undo", r.undoKind + " " + r.undo.optString("input"), "auto HDMI_1");
        eq("LG back by id (the undo's way)", t.control(net, "lg", "input", 0, "HDMI_1").text, "Switched LG webOS TV TX5050ABC to HDMI 1 (the TV says it's showing HDMI 1 now).");
        eq("LG live TV", t.control(net, "lg", "input", 0, "tv").text, "Switched LG webOS TV TX5050ABC to Live TV (the TV says it's showing Live TV now).");
        has("LG unknown input", t.control(net, "lg", "input", 0, "hdmi 9").text, "Its inputs: HDMI 1, HDMI 2, Live TV.");
        lg.stuckInput = true;
        r = t.control(net, "lg", "input", 0, "hdmi 2");
        has("LG input didn't change: no claim", r.text, "Ask him what the screen shows.");
        eq("LG input didn't change: no undo", r.undo == null, true);
        lg.stuckInput = false;
        r = t.control(net, "lg", "home", 0, null);
        eq("LG home through the pointer socket", r.text + " " + lg.buttons, "Pressed home on LG webOS TV TX5050ABC. [type:button\nname:HOME\n\n]");
        t.control(net, "lg", "ok", 2, null);
        eq("LG ok = ENTER, twice", lg.buttons.size() + " " + lg.buttons.get(2), "3 type:button\nname:ENTER\n\n");
        has("LG has no next-input button", t.control(net, "lg", "input_next", 0, null).text, "say which input");
        eq("LG channel up", t.control(net, "lg", "channel_up", 0, null).text, "Pressed channel up on LG webOS TV TX5050ABC.");
        r = t.control(net, "lg", "off", 0, null);
        eq("LG off, confirmed", r.text + " " + r.undo.optString("action"), "Turned off LG webOS TV TX5050ABC. on");
        r = t.control(net, "lg", "on", 0, null);
        eq("LG woken by its Wi-Fi address", net.woke.toString(), "[" + FakeLg.MAC + "@192.168.1.105]");
        eq("LG on, confirmed", r.text, "Turned on LG webOS TV TX5050ABC (the TV says it's on).");
        eq("LG already on", t.control(net, "lg", "on", 0, null).text, "LG webOS TV TX5050ABC was already on.");
        lg.screenOff = true;   // Codex 055b #7: screen back on; undo must not shut the whole TV down
        r = t.control(net, "lg", "on", 0, null);
        eq("LG screen back on", r.text, "Turned LG webOS TV TX5050ABC's screen back on.");
        eq("LG screen: undo only reported", r.undoKind + " " + r.undo, "info null");
        lg.on = false; lg.neverWakes = true;
        has("LG never wakes: no claim", t.control(net, "lg", "on", 0, null).text, "Don't say it's on.");
        lg.on = true; lg.neverWakes = false;
        eq("LG keyed connections always pinned", lg.keyedWithoutPin, false);
        lg.cert = "intruder";
        err = "";
        try { t.control(net, "lg", "mute", 0, null); } catch (Exception e) { err = e.getMessage(); }
        has("LG changed certificate refused", err, "not the TV Sprocket paired with");
        lg.cert = FakeLg.LGCERT; lg.revoked = true;
        err = "";
        try { t.control(net, "lg", "volume_up", 0, null); } catch (Exception e) { err = e.getMessage(); }
        has("LG unpaired on the TV: pair again", err, "pair it again with tv_pair");
        Tv lgBack = Tv.load(t.toJson());
        eq("LG survives saving", lgBack.pairedNamed("LG webOS TV TX5050ABC", "LG").key, FakeLg.LGKEY);

        // the brain's tools
        JSONArray d = Tv.definitions();
        eq("three tools", d.getJSONObject(0).getString("name") + " " + d.getJSONObject(1).getString("name") + " " + d.getJSONObject(2).getString("name"), "tv_find tv_pair tv");
        has("actions listed", d.getJSONObject(2).toString(), "\"volume_up\"");
        lacks("no key in the tools", d.toString(), KEY);

        System.out.println("TvTest: " + (n - fails) + "/" + n + " passed");
        if (fails > 0) System.exit(1);
    }
}
