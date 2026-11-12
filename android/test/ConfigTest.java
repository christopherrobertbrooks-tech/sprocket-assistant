package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Sprocket's settings on the desktop JDK: the brain, the optional servers and the feature switches, and which of the
 * brain's tools each of them hides. */
public class ConfigTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static JSONArray tools(String... names) throws Exception {
        JSONArray a = new JSONArray();
        for (String s : names) a.put(s.startsWith("web_") ? new JSONObject().put("type", s + "_x").put("name", s) : new JSONObject().put("name", s));
        return a;
    }

    static String names(JSONArray a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length(); i++) b.append(i > 0 ? " " : "").append(a.optJSONObject(i).optString("name"));
        return b.toString();
    }

    public static void main(String[] a) throws Exception {
        Config.reset();
        eq("out of the box: Claude, no servers, every feature on", Config.own + " " + Config.voiceOut.isEmpty() + " " + Config.on("screen") + Config.on("calls"),
                "false true truetrue");
        JSONArray all = tools("web_search", "get_weather", "set_timer", "screen", "check_texts", "read_texts", "check_notifications",
                "look_at_screen", "check_machines", "machine_control", "github_threads", "github_repos", "studio_make", "studio_jobs",
                "goal_add", "watch_ai_news", "list_add", "research", "remember", "forget");
        eq("with no servers set, their tools are hidden", names(Config.allowed(all)),
                "web_search get_weather set_timer screen check_texts read_texts check_notifications look_at_screen goal_add "
                        + "watch_ai_news list_add research remember forget");
        Config.github = "someone";
        Config.homeStats = "http://box:8020/stats";
        Config.homeHands = "http://box:8473";
        Config.studio = "http://box:8474";
        eq("servers set: their tools come back", names(Config.allowed(all)).contains("check_machines machine_control github_threads github_repos studio_make studio_jobs"), true);
        Config.off.add("screen");
        Config.off.add("texts");
        Config.off.add("web");
        Config.off.add("memory");
        eq("switched off: gone", names(Config.allowed(all)),
                "get_weather set_timer check_notifications check_machines machine_control github_threads github_repos studio_make "
                        + "studio_jobs goal_add watch_ai_news list_add research");
        eq("every feature has a name and a plain line", Config.FEATURES.length >= 8 && java.util.Arrays.stream(Config.FEATURES).allMatch(f -> f.length == 3 && !f[2].isEmpty()), true);

        // the brain: his own server instead of Claude
        Config.own = true;
        Config.ownName = "Qwen";
        Config.ownBase = "https://box.example:8448/";
        Config.ownModel = "";
        eq("its address, tidied", Config.base(), "https://box.example:8448");
        eq("a model name when none is set", Config.model(), "own");
        Config.ownModel = "qwen";
        eq("its model", Config.model(), "qwen");
        Config.own = false;
        eq("Claude's address", Config.base(), "https://api.anthropic.com");

        // kept and read back
        Config.own = true;
        String saved = Config.toJson();
        Config.reset();
        Config.fromJson(saved);
        eq("saved and loaded", Config.own + " " + Config.ownName + " " + Config.ownBase + " " + Config.github + " " + Config.on("screen") + " " + Config.on("calls"),
                "true Qwen https://box.example:8448/ someone false true");
        Config.fromJson("{nope");
        eq("a damaged setting changes nothing", Config.ownName, "Qwen");
        eq("the key is never in the saved settings", saved.contains("key"), false);

        // server addresses: https anywhere, plain http only on Tailscale or the home network
        eq("https anywhere", Config.addressProblem("https://my-server.example.com:8448"), null);
        eq("blank is fine (not set up)", Config.addressProblem("  "), null);
        eq("Tailscale name over http", Config.addressProblem("http://box.tail1234.ts.net:8048"), null);
        eq("Tailscale address over http", Config.addressProblem("http://100.101.102.103:8048"), null);
        eq("home Wi-Fi address", Config.addressProblem("http://192.168.1.50:8080"), null);
        eq("10.x", Config.addressProblem("http://10.0.0.7:8048/"), null);
        eq("172.16-31", Config.addressProblem("http://172.20.1.2"), null);
        eq(".local name", Config.addressProblem("http://desktop.local:8048"), null);
        eq("one-word name", Config.addressProblem("http://desktop:8048"), null);
        eq("plain http to the internet is refused", Config.addressProblem("http://my-server.example.com:8048") != null, true);
        eq("a public address over http is refused", Config.addressProblem("http://8.8.8.8") != null, true);
        eq("172.32 isn't private", Config.addressProblem("http://172.32.0.1") != null, true);
        eq("a fake .ts.net inside the path doesn't count", Config.addressProblem("http://evil.example.com/x.ts.net") != null, true);
        eq("nor a user name that looks local", Config.addressProblem("http://192.168.1.5@evil.example.com") != null, true);
        eq("no scheme", Config.addressProblem("my-server:8448") != null, true);
        eq("home http is marked unencrypted", Config.unencrypted("http://192.168.1.50:8080"), true);
        eq("Tailscale http isn't", Config.unencrypted("http://box.tail1234.ts.net:8048"), false);
        eq("https isn't", Config.unencrypted("https://my-server.example.com"), false);

        // numbers that only look local (Codex 042 #4)
        eq("a bare number is a public address (134744072 = 8.8.8.8)", Config.addressProblem("http://134744072:8048") != null, true);
        eq("so is hex", Config.addressProblem("http://0x08080808") != null, true);
        eq("leading zeros can mean octal", Config.addressProblem("http://010.010.010.010") != null, true);
        eq("three-part numbers", Config.addressProblem("http://8.8.2056") != null, true);
        eq("a normal one-word name still works", Config.addressProblem("http://nas:8048"), null);
        eq("where it really leads: a home address", Config.destinationProblem("http://192.168.1.50:8048"), null);
        eq("where it really leads: Tailscale", Config.destinationProblem("http://100.101.102.103:8048"), null);
        eq("https isn't looked up", Config.destinationProblem("https://my-server.example.com"), null);
        eq("a name that can't be found isn't used unchecked (Codex 043 #1)", Config.destinationProblem("http://sprocketnohostxyz:8048") != null, true);
        eq("a public address in disguise is caught however it's written", Config.local(java.net.InetAddress.getByName("134744072")), false);
        eq("loading settings never takes a refused address",
                ((java.util.function.Supplier<String>) () -> { Config.reset(); Config.fromJson("{\"voice_out\":\"http://evil.example.com:8472\",\"voice_in\":\"http://192.168.1.50:8080\"}");
                    return Config.voiceOut + "|" + Config.voiceIn; }).get(), "|http://192.168.1.50:8080");

        System.out.println(fails == 0 ? "config: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
