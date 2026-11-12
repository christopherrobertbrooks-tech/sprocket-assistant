package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * What differs from one owner to the next (the open-source cleanup, 2026-11-11): which brain answers (Claude, or the
 * owner's own server speaking the same Messages API, such as Qwen through the included go-between), the optional
 * servers (a voice to speak with, a speech-to-text server, home machines, a studio), his GitHub name, and the features
 * he has switched off. Kept in the app's settings on the phone (keys separately); blank means "not set up", and a tool
 * that needs something not set up is never offered to the brain. Plain Java: test/ConfigTest.java.
 */
final class Config {
    private Config() { }

    static boolean own;                                  // his own server instead of Claude
    static String ownName = "", ownBase = "", ownModel = "";
    static String voiceOut = "", voiceIn = "";           // POST /speak {text, mood} -> audio; OpenAI-style /v1/audio/transcriptions
    static String homeDash = "", homeStats = "", homeHands = "", studio = "", github = "";
    static String callHours = "";                        // the call announcer's hours (Calls.onDuty); blank = always
    static final Set<String> off = new HashSet<>();

    /** The switches in Settings: id, name, what it does. */
    static final String[][] FEATURES = {
            {"screen", "Screen control", "Sprocket reads and taps other apps' screens when you ask it to."},
            {"calls", "Call announcer", "Says who is calling when the phone is on vibrate, and listens for answer, decline or text."},
            {"texts", "Read your texts", "Keeps your incoming texts on the phone so Sprocket can read them to you."},
            {"notifications", "Read your notifications", "Lets Sprocket say what your notifications say."},
            {"memory", "Memory", "Sprocket remembers things you tell it to, and sends those notes with each question."},
            {"web", "Web search", "Sprocket searches the web when a question needs it."},
            {"location", "Your town", "Your rough location for the weather and nearby searches."},
            {"brief", "Morning brief", "A brief at 6 am: weather, calendar, what's due, and news on topics you pick."},
            {"research", "Research", "Longer write-ups on a question or a shared article, in the background."},
            {"github", "GitHub", "Your GitHub threads and repositories (needs your GitHub username)."},
    };

    static boolean on(String feature) { return !off.contains(feature); }

    static void reset() {
        own = false;
        ownName = ownBase = ownModel = voiceOut = voiceIn = homeDash = homeStats = homeHands = studio = github = callHours = "";
        off.clear();
    }

    /** Why Sprocket won't use this server address, or null if it will (blank = not set up, also fine). https goes
     * anywhere; plain http only to the owner's own networks: Tailscale (encrypted underneath) or the home network
     * (the owner, 2026-11-11: "add it"), where it is unencrypted and only works at home. */
    static String addressProblem(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return null;
        String low = u.toLowerCase(java.util.Locale.US);
        if (low.startsWith("https://")) return host(u).isEmpty() ? "That address is missing its server name." : null;
        if (!low.startsWith("http://")) return "Addresses start with https:// (or http:// on Tailscale or your home network).";
        String h = host(u);
        if (h.isEmpty()) return "That address is missing its server name.";
        return tailscale(h) || home(h) ? null
                : "Plain http:// only works for a Tailscale (.ts.net) or home-network address. Use https:// for anything else.";
    }

    /** Plain http on the home network: allowed, but nothing protects it on the way (Settings says so). */
    static boolean unencrypted(String url) {
        String u = url == null ? "" : url.trim();
        return u.toLowerCase(java.util.Locale.US).startsWith("http://") && addressProblem(u) == null && !tailscale(host(u));
    }

    /** addressProblem, plus where a plain http:// name really leads: every address it resolves to must be on the home
     * network or Tailscale. Checked right before each connection to the owner's servers (Codex 042 #4). */
    static String destinationProblem(String url) {
        String bad = addressProblem(url);
        if (bad != null || url == null || !url.trim().toLowerCase(java.util.Locale.US).startsWith("http://")) return bad;
        try {
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host(url.trim())))
                if (!local(a)) return "That http:// address leads outside your home network and Tailscale (" + a.getHostAddress() + "). Use https://.";
        } catch (java.net.UnknownHostException e) {
            return "Can't find " + host(url.trim()) + " on your network.";   // unchecked is never used (Codex 043 #1)
        }
        return null;
    }

    /** A private, loopback, link-local, Tailscale (100.64.0.0/10) or IPv6 unique-local address. */
    static boolean local(java.net.InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xff) >= 64 && (b[1] & 0xff) <= 127;
        return (b[0] & 0xfe) == 0xfc;
    }

    static String host(String url) {
        String h = url.replaceFirst("(?i)^https?://", "");
        int end = h.length();
        for (char c : new char[]{'/', '?', '#'}) { int i = h.indexOf(c); if (i >= 0 && i < end) end = i; }
        h = h.substring(0, end);
        if (h.contains("@")) h = h.substring(h.lastIndexOf('@') + 1);    // user:password@host
        if (h.startsWith("[")) return h.contains("]") ? h.substring(1, h.indexOf(']')).toLowerCase(java.util.Locale.US) : "";
        if (h.contains(":")) h = h.substring(0, h.indexOf(':'));
        return h.toLowerCase(java.util.Locale.US);
    }

    /** A Tailscale name or address (100.64.0.0/10): WireGuard-encrypted underneath. */
    private static boolean tailscale(String h) {
        if (h.endsWith(".ts.net")) return true;
        int[] ip = ipv4(h);
        return ip != null && ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127;
    }

    /** The home network: private addresses, .local and .lan names, and plain one-word names like "desktop". */
    private static boolean home(String h) {
        if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true;
        int[] ip = ipv4(h);
        if (ip != null) return ip[0] == 10 || ip[0] == 127 || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) || (ip[0] == 192 && ip[1] == 168);
        if (h.startsWith("fd") && h.contains(":")) return true;          // IPv6 private (fd00::/8)
        return h.matches("[a-z][a-z0-9-]*");    // a one-word name starts with a letter: "134744072" is 8.8.8.8 (Codex 042 #4)
    }

    private static int[] ipv4(String h) {
        if (!h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return null;
        String[] p = h.split("\\.");
        int[] ip = new int[4];
        for (int i = 0; i < 4; i++) {
            if (p[i].length() > 1 && p[i].startsWith("0")) return null;   // "010" can mean 8 to the network library
            ip[i] = Integer.parseInt(p[i]);
            if (ip[i] > 255) return null;
        }
        return ip;
    }

    static String base() { return own ? ownBase.trim().replaceAll("/+$", "") : Claude.ANTHROPIC; }

    static String model() { return ownModel.trim().isEmpty() ? "own" : ownModel.trim(); }

    /** Whether a tool may run right now: its feature still on and its server still set up (Codex 043 #2: switching a
     * feature off stops a tool even in an answer already on its way). */
    static boolean toolOn(String tool) { return wanted(tool); }

    /** Which feature or server each of the brain's tools needs (tools not named here are always offered). */
    private static boolean wanted(String tool) {
        switch (tool) {
            case "screen": case "look_at_screen": return on("screen");
            case "check_texts": case "read_texts": return on("texts");
            case "check_notifications": return on("notifications");
            case "remember": case "forget": return on("memory");
            case "web_search": case "web_fetch": return on("web");
            case "research": return on("research");
            case "watch_ai_news": return on("brief");
            case "github_threads": case "github_repos": return on("github") && !github.trim().isEmpty();
            case "check_machines": return !homeDash.trim().isEmpty() || !homeStats.trim().isEmpty();
            case "machine_control": return !homeHands.trim().isEmpty();
            case "studio_make": case "studio_jobs": return !studio.trim().isEmpty();
            default: return true;
        }
    }

    /** The brain's tools, without the ones switched off or not set up. */
    static JSONArray allowed(JSONArray tools) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t != null && wanted(t.optString("name"))) out.put(t);
        }
        return out;
    }

    static String toJson() {
        try {
            return new JSONObject().put("own", own).put("own_name", ownName).put("own_base", ownBase).put("own_model", ownModel)
                    .put("voice_out", voiceOut).put("voice_in", voiceIn).put("home_dash", homeDash).put("home_stats", homeStats)
                    .put("home_hands", homeHands).put("studio", studio).put("github", github).put("call_hours", callHours).put("off", new JSONArray(new java.util.ArrayList<>(off)))
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String server(JSONObject o, String key, String was) {
        String v = o.optString(key, was);
        return addressProblem(v) == null ? v : was;
    }

    /** Loads saved settings; a damaged one changes nothing. */
    static void fromJson(String s) {
        JSONObject o;
        try { o = new JSONObject(s); } catch (Exception e) { return; }
        own = o.optBoolean("own", own);
        ownName = o.optString("own_name", ownName);
        ownBase = server(o, "own_base", ownBase);
        ownModel = o.optString("own_model", ownModel);
        voiceOut = server(o, "voice_out", voiceOut);   // an address the policy refuses is never loaded (Codex 042 #4)
        voiceIn = server(o, "voice_in", voiceIn);
        homeDash = server(o, "home_dash", homeDash);
        homeStats = server(o, "home_stats", homeStats);
        homeHands = server(o, "home_hands", homeHands);
        studio = server(o, "studio", studio);
        github = o.optString("github", github);
        callHours = o.optString("call_hours", callHours);
        JSONArray f = o.optJSONArray("off");
        if (f != null) {
            off.clear();
            for (int i = 0; i < f.length(); i++) off.add(f.optString(i));
        }
    }
}
