package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LG webOS TVs (2026-11-19), for Tv: one live connection (secure websocket, port 3001) per action. Pairing is a code
 * on the TV's screen (or, on TVs without codes, an "Allow?" question he answers with the LG remote); the TV then hands
 * over a key, kept in tv.json and only ever sent back to the TV whose certificate was seen at pairing.
 * The messages are LG's "second screen" protocol, as LG's own open-source Connect SDK (Apache 2.0) uses it.
 * Plain Java: test/TvTest.java (with a pretend LG).
 */
final class Lg {
    static final int PORT = 3001;
    /** Only what Sprocket's controls use (no texts, no notifications, no app list, no channel list). */
    static final String[] PERMISSIONS = {"LAUNCH", "CONTROL_AUDIO", "CONTROL_POWER", "CONTROL_INPUT_TV", "READ_INPUT_DEVICE_LIST",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "READ_RUNNING_APPS", "CONTROL_MOUSE_AND_KEYBOARD", "READ_NETWORK_STATE", "CONTROL_TV_SCREEN"};
    static final String REPAIR = "the TV no longer accepts Sprocket's pairing (it was reset or unpaired): pair it again with tv_pair";

    static JSONObject register(String key, boolean pin) throws Exception {
        JSONObject manifest = new JSONObject().put("manifestVersion", 1).put("permissions", new JSONArray(java.util.Arrays.asList(PERMISSIONS)));
        JSONObject payload = new JSONObject().put("forcePairing", false).put("manifest", manifest);
        if (key != null && !key.isEmpty()) payload.put("client-key", key);
        else if (pin) payload.put("pairingType", "PIN");
        return new JSONObject().put("type", "register").put("id", "reg").put("payload", payload);
    }

    /** The next message with this id (other messages, like subscriptions, are skipped). */
    static JSONObject until(Tv.Ws ws, String id, int ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (true) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) throw new java.net.SocketTimeoutException("the TV didn't answer");
            JSONObject m = new JSONObject(ws.recv((int) left));
            if (id.equals(m.optString("id"))) return m;
        }
    }

    /** One connection, registered with the saved key. */
    static final class Session implements AutoCloseable {
        final Tv.Ws ws; final Tv.Net net; final Tv.Paired p; int next = 1;

        Session(Tv.Net net, Tv.Paired p) throws Exception {
            this.net = net; this.p = p;
            ws = net.ws("wss://" + p.ip + ":" + p.port + "/", p.cert.isEmpty() ? null : p.cert, 5000);
            try {
                ws.send(register(p.key, false).toString());
                JSONObject m;
                do m = until(ws, "reg", 6000); while (m.optString("type").equals("response") && !m.optJSONObject("payload").has("pairingType"));
                if (m.optString("type").equals("registered")) return;
                if (m.optString("type").equals("response")) throw new Exception(REPAIR);   // it wants a new pairing
                throw new Exception("the TV refused: " + m.optString("error", m.toString()));
            } catch (Exception e) { ws.close(); throw e; }
        }

        JSONObject request(String uri, JSONObject payload) throws Exception {
            String id = "r" + next++;
            ws.send(new JSONObject().put("type", "request").put("id", id).put("uri", uri).put("payload", payload == null ? new JSONObject() : payload).toString());
            JSONObject m = until(ws, id, 6000);
            JSONObject pl = m.optJSONObject("payload");
            if (m.optString("type").equals("error") || (pl != null && pl.has("returnValue") && !pl.optBoolean("returnValue")))
                throw new Exception("the TV said no to " + uri.replace("ssap://", "") + ": "
                        + (pl != null && pl.has("errorText") ? pl.optString("errorText") : m.optString("error", "no reason given")));
            return pl == null ? new JSONObject() : pl;
        }

        /** A remote-control button (HOME, BACK, UP, ENTER, ...) through the TV's pointer socket. */
        void button(String name, int times) throws Exception {
            String path = request("ssap://com.webos.service.networkinput/getPointerInputSocket", null).getString("socketPath")
                    .replaceFirst("^ws://([^/:]+):3000/", "wss://$1:3001/");   // always the secure door, same certificate
            Tv.Ws pointer = net.ws(path, p.cert.isEmpty() ? null : p.cert, 5000);
            try {
                for (int i = 0; i < times; i++) { if (i > 0) net.sleep(300); pointer.send("type:button\nname:" + name + "\n\n"); }
                net.sleep(200);
            } finally { pointer.close(); }
        }

        @Override public void close() { ws.close(); }
    }

    // ---- pairing ---------------------------------------------------------------------------------------------------

    /** Starts pairing: the connection stays open (Tv.held) until the code comes back. */
    static String pairStart(Tv t, Tv.Net net, Tv.Found f) throws Exception {
        t.dropHeld();
        Tv.Ws ws = net.ws("wss://" + f.ip + ":" + PORT + "/", null, 5000);
        ws.send(register(null, true).toString());
        JSONObject m = until(ws, "reg", 8000);
        String mode = m.optJSONObject("payload") == null ? "" : m.getJSONObject("payload").optString("pairingType");
        if (m.optString("type").equals("error") || mode.isEmpty()) {
            ws.close();
            return "The TV wouldn't start pairing (it said " + m.optString("error", "nothing clear") + "). Is it turned on?";
        }
        t.held = ws;
        t.pending = new JSONObject().put("brand", "LG").put("ip", f.ip).put("port", PORT).put("name", f.name).put("model", f.model)
                .put("cert", ws.cert() == null ? "" : ws.cert()).put("mode", mode.toUpperCase(Locale.US));
        return mode.equalsIgnoreCase("PIN")
                ? "A pairing code is on " + f.name + "'s screen now. Ask him to read it out, then call tv_pair again with that code."
                : "A question is on " + f.name + "'s screen asking to allow Sprocket. Ask him to choose Allow (or press OK) with the LG "
                        + "remote, then call tv_pair again (no code needed).";
    }

    /** Finishes pairing with the code (or after he allowed it on the TV). */
    static String pairFinish(Tv t, Tv.Net net, String code) throws Exception {
        JSONObject pend = t.pending;
        if (t.held == null) {
            t.pending = null;
            return "The pairing on the TV timed out or Sprocket restarted. Call tv_pair without a code to start again.";
        }
        boolean pin = pend.optString("mode").equals("PIN");
        if (pin && code.isEmpty()) return "The TV is showing a code: call tv_pair with the code he reads off the screen.";
        JSONObject m;
        try {
            if (pin) t.held.send(new JSONObject().put("type", "request").put("id", "pin").put("uri", "ssap://pairing/setPin")
                    .put("payload", new JSONObject().put("pin", code)).toString());
            long end = System.currentTimeMillis() + (pin ? 15000 : 8000);
            do {
                m = new JSONObject(t.held.recv((int) Math.max(1, end - System.currentTimeMillis())));
                if (m.optString("type").equals("error") || (m.optJSONObject("payload") != null && m.getJSONObject("payload").has("returnValue")
                        && !m.getJSONObject("payload").optBoolean("returnValue"))) {
                    t.dropHeld(); t.pending = null;
                    return "Pairing didn't work (the TV said " + m.optString("error", "no") + "). The code may be mistyped or expired: "
                            + "call tv_pair without a code to get a new one on the TV.";
                }
            } while (!m.optString("type").equals("registered"));
        } catch (java.net.SocketTimeoutException e) {
            if (!pin) return "The TV hasn't been allowed yet. Ask him to choose Allow with the LG remote, then call tv_pair again.";
            t.dropHeld(); t.pending = null;
            return "The TV didn't take the code. Call tv_pair without a code to get a new one on the TV.";
        }
        String cert = pend.optString("cert");
        t.dropHeld(); t.pending = null;
        if (cert.isEmpty()) return "Pairing didn't finish: the TV didn't show a certificate to remember, so Sprocket won't keep its key.";
        Tv.Paired p = new Tv.Paired();
        p.brand = "LG"; p.name = pend.getString("name"); p.model = pend.optString("model"); p.ip = pend.getString("ip");
        p.port = pend.getInt("port"); p.key = m.getJSONObject("payload").getString("client-key"); p.cert = cert; p.mac = "";
        try (Session s = new Session(net, p)) { p.mac = mac(s.request("ssap://com.webos.service.connectionmanager/getinfo", null)); }
        catch (Exception ignored) { }
        Tv.Paired old = t.pairedNamed(p.name, p.brand);
        if (old != null) t.paired.remove(old);
        t.paired.add(p);
        return "Paired with " + p.name + ". Sprocket can control it now" + (p.mac.isEmpty()
                ? " while it's on (its Wi-Fi address for waking it couldn't be read, so \"turn on\" may not work from standby)."
                : ", including turning it on.");
    }

    /** The connected network's hardware address from connectionmanager/getinfo. */
    static String mac(JSONObject info) {
        JSONObject wifi = info.optJSONObject("wifiInfo"), wired = info.optJSONObject("wiredInfo");
        if (wired != null && "connected".equalsIgnoreCase(wired.optString("state")) && !wired.optString("macAddress").isEmpty())
            return wired.optString("macAddress");
        return wifi == null ? "" : wifi.optString("macAddress");
    }

    // ---- controls --------------------------------------------------------------------------------------------------

    static int volume(JSONObject v) {
        JSONObject s = v.optJSONObject("volumeStatus");
        return s != null ? s.optInt("volume") : v.optInt("volume");
    }

    static boolean muted(JSONObject v) {
        JSONObject s = v.optJSONObject("volumeStatus");
        return s != null ? s.optBoolean("muteStatus") : v.optBoolean("muted", v.optBoolean("mute"));
    }

    /** "Active" is on; "Screen Off" is on with the picture off; anything else (Active Standby, Suspend) is off. */
    static String powerState(Session s) {
        try { return s.request("ssap://com.webos.service.tvpower/power/getPowerState", null).optString("state", "Active"); }
        catch (Exception e) { return "Active"; }   // older TVs don't say: a working connection means it's on
    }

    static final class Input {
        final String id, name, app;
        Input(String id, String name, String app) { this.id = id; this.name = name; this.app = app; }
    }

    static List<Input> inputs(Session s) throws Exception {
        List<Input> out = new ArrayList<>();
        JSONArray d = s.request("ssap://tv/getExternalInputList", null).optJSONArray("devices");
        for (int i = 0; d != null && i < d.length(); i++) {
            JSONObject x = d.getJSONObject(i);
            out.add(new Input(x.optString("id"), x.optString("label", x.optString("id")), x.optString("appId")));
        }
        out.add(new Input("livetv", "Live TV", "com.webos.app.livetv"));
        return out;
    }

    static Input match(List<Input> ins, String said) {
        String s = Tv.squash(said);
        if (s.isEmpty()) return null;
        for (Input x : ins)
            if (s.equals(Tv.squash(x.id)) || s.equals(Tv.squash(x.name))
                    || (x.id.equals("livetv") && (s.equals("tv") || s.equals("antenna") || s.equals("cable") || s.equals("livetv"))))
                return x;
        return null;
    }

    static String foreground(Session s) {
        try { return s.request("ssap://com.webos.applicationManager/getForegroundAppInfo", null).optString("appId"); }
        catch (Exception e) { return ""; }
    }

    static final String[][] BUTTONS = {{"home", "HOME"}, {"back", "BACK"}, {"menu", "MENU"}, {"exit", "EXIT"}, {"ok", "ENTER"},
            {"up", "UP"}, {"down", "DOWN"}, {"left", "LEFT"}, {"right", "RIGHT"}};

    static Tv.Result control(Tv.Net net, Tv.Paired p, String action, int value, String input) throws Exception {
        String n = p.name;
        if (action.equals("on")) {
            try (Session s = new Session(net, p)) {
                String st = powerState(s);
                if (st.equals("Active")) return new Tv.Result(n + " was already on.", null, null, null);
                if (st.equals("Screen Off")) {
                    s.request("ssap://com.webos.service.tvpower/power/turnOnScreen", null);
                    // undo would be "screen off", which Sprocket has no control for: "off" would shut the whole TV down
                    // (Codex 055b #7), so this one is only reported
                    return new Tv.Result("Turned " + n + "'s screen back on.", "Turned " + n + "'s screen back on", UndoLog.INFO, null);
                }
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("pair it again")) throw e;
            }
            if (p.mac.isEmpty()) return new Tv.Result(n + " isn't answering and Sprocket doesn't have its Wi-Fi address to wake it. "
                    + "He can turn it on with its remote; after that Sprocket can control it.", null, null, null);
            net.wake(p.mac, p.ip);
            for (int i = 0; i < 20; i++) {
                net.sleep(1000);
                if (!net.open(p.ip, p.port, 800)) continue;
                try (Session s = new Session(net, p)) {
                    if (powerState(s).equals("Active"))
                        return new Tv.Result("Turned on " + n + " (the TV says it's on).", "Turned on the TV " + n, UndoLog.AUTO, Tv.undo(n, "off"));
                } catch (Exception ignored) { }
            }
            return new Tv.Result("Sent the wake-up signal to " + n + ", but it didn't answer: it may be unplugged or off the Wi-Fi. Don't say it's on.", null, null, null);
        }
        try (Session s = new Session(net, p)) {
            switch (action) {
                case "off": {
                    if (!powerState(s).equals("Active") && !powerState(s).equals("Screen Off")) return new Tv.Result(n + " is already off.", null, null, null);
                    s.request("ssap://system/turnOff", null);
                    net.sleep(2500);
                    String st;
                    try (Session after = new Session(net, p)) { st = powerState(after); } catch (Exception e) { st = "gone"; }
                    return st.equals("Active")
                            ? new Tv.Result("Asked " + n + " to turn off, but it still says it's on.", null, null, null)
                            : new Tv.Result("Turned off " + n + ".", "Turned off the TV " + n, UndoLog.AUTO, Tv.undo(n, "on"));
                }
                case "volume": case "volume_up": case "volume_down": {
                    int was = volume(s.request("ssap://audio/getVolume", null));
                    int to = action.equals("volume") ? value : was + (action.equals("volume_up") ? 1 : -1) * Tv.step(value);
                    to = Math.max(0, Math.min(100, to));
                    if (to == was) return new Tv.Result(n + "'s volume is already " + was + ".", null, null, null);
                    if (action.equals("volume")) s.request("ssap://audio/setVolume", new JSONObject().put("volume", to));
                    else for (int i = 0; i < Math.abs(to - was); i++) {   // the remote's way: the TV shows its volume bar
                        if (i > 0) net.sleep(150);
                        s.request(to > was ? "ssap://audio/volumeUp" : "ssap://audio/volumeDown", null);
                    }
                    int now = volume(s.request("ssap://audio/getVolume", null));
                    return new Tv.Result(n + " volume " + was + " -> " + now + " (read back from the TV).",
                            "TV volume " + was + " -> " + now + " on " + n, UndoLog.AUTO, Tv.undo(n, "volume").put("value", was));
                }
                case "mute": case "unmute": {
                    boolean want = action.equals("mute");
                    if (muted(s.request("ssap://audio/getVolume", null)) == want) return new Tv.Result(n + " is already " + (want ? "muted." : "unmuted."), null, null, null);
                    s.request("ssap://audio/setMute", new JSONObject().put("mute", want));
                    boolean now = muted(s.request("ssap://audio/getVolume", null));
                    if (now != want) return new Tv.Result("Asked " + n + " to " + action + ", but it still says " + (now ? "muted." : "unmuted."), null, null, null);
                    return new Tv.Result((want ? "Muted " : "Unmuted ") + n + ".", (want ? "Muted the TV " : "Unmuted the TV ") + n,
                            UndoLog.AUTO, Tv.undo(n, want ? "unmute" : "mute"));
                }
                case "input": {
                    List<Input> ins = inputs(s);
                    Input want = match(ins, input), was = null;
                    String fg = foreground(s);
                    for (Input x : ins) if (x.app.equals(fg)) was = x;
                    if (want == null) {
                        List<String> names = new ArrayList<>();
                        for (Input x : ins) names.add(x.name);
                        return new Tv.Result(n + " has no input called \"" + input + "\". Its inputs: " + String.join(", ", names) + ".", null, null, null);
                    }
                    if (want == was) return new Tv.Result(n + " says it's already on " + want.name + ".", null, null, null);
                    if (want.id.equals("livetv")) s.request("ssap://system.launcher/launch", new JSONObject().put("id", want.app));
                    else s.request("ssap://tv/switchInput", new JSONObject().put("inputId", want.id));
                    net.sleep(1500);
                    String now = foreground(s);
                    if (!now.equals(want.app))
                        return new Tv.Result("Asked " + n + " to switch to " + want.name + ", but the TV says something else is showing. Ask him what the screen shows.", null, null, null);
                    return new Tv.Result("Switched " + n + " to " + want.name + " (the TV says it's showing " + want.name + " now).",
                            "Switched the TV " + n + " to " + want.name, was == null ? UndoLog.INFO : UndoLog.AUTO,
                            was == null ? null : Tv.undo(n, "input").put("input", was.id));
                }
                case "channel_up": case "channel_down": case "play": case "pause": {
                    String uri = action.equals("channel_up") ? "ssap://tv/channelUp" : action.equals("channel_down") ? "ssap://tv/channelDown"
                            : "ssap://media.controls/" + action;
                    int times = Math.max(1, Math.min(10, value));
                    for (int i = 0; i < times; i++) { if (i > 0) net.sleep(400); s.request(uri, null); }
                    String label = action.replace('_', ' ');
                    return new Tv.Result("Pressed " + label + (times > 1 ? " " + times + " times" : "") + " on " + n + ".", "Pressed " + label + " on the TV " + n, UndoLog.INFO, null);
                }
                case "input_next":
                    return new Tv.Result("LG TVs have no \"next input\" button: say which input (\"HDMI 2\").", null, null, null);
                default: {
                    String button = null;
                    for (String[] b : BUTTONS) if (b[0].equals(action)) button = b[1];
                    if (button == null) return new Tv.Result("There's no TV control called \"" + action + "\".", null, null, null);
                    int times = Math.max(1, Math.min(10, value));
                    s.button(button, times);
                    String label = action.replace('_', ' ');
                    return new Tv.Result("Pressed " + label + (times > 1 ? " " + times + " times" : "") + " on " + n + ".", "Pressed " + label + " on the TV " + n, UndoLog.INFO, null);
                }
            }
        }
    }
}
