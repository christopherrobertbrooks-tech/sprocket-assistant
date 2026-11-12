package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * His two machines (the owner 2026-10-17: "check and control the home machines"): the gateway and the PC, read from
 * the dashboard he already has (ember-dash's /api/state, served through the gateway; the gateway's own :8020 stats when
 * the dashboard -- which runs on the PC -- doesn't answer), and the gateway's few jobs through sprocket-hands
 * (gateway/hands_server.py), each after his yes on a card. the PC is frozen: checks only. Ember's brain is left
 * out (his choice: it and Sprocket's voice don't fit on the 4070 together). Plain Java: test/MachinesTest.java.
 */
final class Machines {
    // his servers' addresses, from Settings (Config): the dashboard's /api/state, the server's /stats, its job runner
    private static String url(String s, String what) throws Exception {
        if (s.trim().isEmpty()) throw new Exception("the " + what + " address isn't set (Settings)");
        return s.trim().replaceAll("/+$", "");
    }

    private Machines() { }

    private static String one(double v) { return String.format(Locale.US, "%.1f", v); }

    private static String gpuLine(String name, double temp, double util, double used, double total) {
        return name + " " + Math.round(temp) + "°C, " + Math.round(util) + "% busy, " + one(used) + " of " + Math.round(total) + " GB in use";
    }

    private static String jobsLine(JSONObject jobs) {
        if (jobs == null) return "";
        JSONArray strata = jobs.optJSONArray("strata");
        List<String> loaded = new ArrayList<>();
        for (int i = 0; strata != null && i < strata.length(); i++) loaded.add(strata.optString(i));
        return "; Song Maker " + ("active".equals(jobs.optString("song_maker")) ? "on" : "off")
                // the model server's list ("strata" is the dashboard's old name for it): whatever model is loaded
                + ", the model server " + (strata == null ? "unknown" : loaded.isEmpty() ? "has nothing loaded" : "has " + String.join(", ", loaded) + " loaded")
                + ", Sprocket's voice " + ("active".equals(jobs.optString("voice")) ? "on" : "off");
    }

    /** One machine from the dashboard, and what about it needs a look (added to `look`). */
    private static String line(JSONObject h, JSONObject jobs, List<String> look) {
        boolean gateway = "gateway".equals(h.optString("key"));
        String name = gateway ? "Gateway" : h.optString("name", "the PC");
        String whose = gateway ? "the gateway's " : name + "'s ";
        if (!h.optBoolean("online")) {
            look.add(name + " isn't answering");
            return name + ": not answering (it may be off or asleep).";
        }
        JSONObject d = h.optJSONObject("data");
        if (d == null) d = new JSONObject();
        StringBuilder b = new StringBuilder(name).append(": CPU ").append(Math.round(d.optDouble("cpu_pct"))).append("% at ")
                .append(Math.round(d.optDouble("cpu_temp"))).append("°C");
        if (d.optDouble("cpu_temp") >= 99) look.add(whose + "CPU is at " + Math.round(d.optDouble("cpu_temp")) + "°C");
        JSONArray gpus = d.optJSONArray("gpus");
        for (int i = 0; gpus != null && i < gpus.length(); i++) {
            JSONObject g = gpus.optJSONObject(i);
            b.append("; ").append(gpuLine(g.optString("short", g.optString("name")), g.optDouble("temp"), g.optDouble("util"),
                    g.optDouble("vram_used_gib"), g.optDouble("vram_total_gib")));
            if (g.optDouble("temp") >= 85) look.add(whose + g.optString("short") + " is at " + Math.round(g.optDouble("temp")) + "°C");
        }
        b.append("; memory ").append(one(d.optDouble("mem_used_gib"))).append(" of ").append(one(d.optDouble("mem_total_gib"))).append(" GB");
        if (d.optDouble("mem_pct") >= 92) look.add(whose + "memory is " + Math.round(d.optDouble("mem_pct")) + "% used");
        JSONArray disks = d.optJSONArray("disks");
        for (int i = 0; disks != null && i < disks.length(); i++) {
            JSONObject k = disks.optJSONObject(i);
            String label = "Disk".equals(k.optString("label")) ? "main" : k.optString("label");
            b.append(i == 0 ? "; disks: " : ", ").append(label).append(' ').append(Math.round(k.optDouble("pct"))).append("% full");
            if (k.optDouble("pct") >= 90) look.add(whose + label + " disk is " + Math.round(k.optDouble("pct")) + "% full");
        }
        JSONArray models = d.optJSONArray("models");
        if (models != null) {
            List<String> m = new ArrayList<>();
            for (int i = 0; i < models.length(); i++) {
                Object x = models.opt(i);
                m.add(x instanceof JSONObject ? ((JSONObject) x).optString("name", ((JSONObject) x).optString("model")) : String.valueOf(x));
            }
            b.append(m.isEmpty() ? "; nothing loaded" : "; loaded: " + String.join(", ", m));
        }
        JSONObject services = d.optJSONObject("services");
        if (gateway && services != null && services.length() > 0) {
            List<String> s = new ArrayList<>();
            for (java.util.Iterator<String> it = services.keys(); it.hasNext(); ) {
                String k = it.next();
                s.add(k + ("active".equals(services.optString(k)) ? " on" : " off"));
            }
            b.append("; services: ").append(String.join(", ", s));
        }
        if (gateway) b.append(jobsLine(jobs));
        b.append('.');
        if (!gateway) b.append(" (checks only.)");
        return b.toString();
    }

    /** The dashboard's state as a few plain lines: which = "gateway", "dev_console", or "" for both. */
    static String summary(JSONObject state, JSONObject jobs, String which) {
        List<String> lines = new ArrayList<>(), look = new ArrayList<>();
        JSONArray hosts = state.optJSONArray("hosts");
        for (int i = 0; hosts != null && i < hosts.length(); i++) {
            JSONObject h = hosts.optJSONObject(i);
            boolean gateway = "gateway".equals(h.optString("key"));
            if (which.equals("gateway") && !gateway || which.equals("dev_console") && gateway) continue;
            lines.add(line(h, jobs, look));
        }
        String out = String.join("\n", lines);
        return look.isEmpty() ? out : out + "\nNeeds a look: " + String.join("; ", look) + ".";
    }

    private static String shortName(String gpu) {
        if (gpu.contains("V100")) return "V100";
        return gpu.replaceFirst("^NVIDIA (GeForce )?", "").trim();
    }

    /** The gateway from its own stats (:8020), for when the dashboard doesn't answer. */
    static String fromStats(JSONObject s, JSONObject jobs) {
        JSONObject cpu = s.optJSONObject("cpu"), sys = s.optJSONObject("system"), disk = s.optJSONObject("disk");
        StringBuilder b = new StringBuilder("Gateway: CPU ").append(Math.round(cpu == null ? 0 : cpu.optDouble("percent")))
                .append("% at ").append(Math.round(cpu == null ? 0 : cpu.optDouble("temp"))).append("°C");
        JSONArray gpus = s.optJSONArray("gpus");
        for (int i = 0; gpus != null && i < gpus.length(); i++) {
            JSONObject g = gpus.optJSONObject(i);
            b.append("; ").append(gpuLine(shortName(g.optString("name")), g.optDouble("temp"), g.optDouble("util"),
                    g.optDouble("vram_used") / 1024, g.optDouble("vram_total") / 1024));
        }
        if (sys != null) {
            double total = sys.optDouble("mem_total") / (1L << 30), used = total - sys.optDouble("mem_available") / (1L << 30);
            b.append("; memory ").append(one(used)).append(" of ").append(one(total)).append(" GB");
        }
        if (disk != null && disk.optDouble("total") > 0)
            b.append("; disks: main ").append(Math.round(100 * disk.optDouble("used") / disk.optDouble("total"))).append("% full");
        return b.append(jobsLine(jobs)).append('.').toString();
    }

    /** {card text, the yes word} for one of his jobs; null for anything else. */
    static String[] card(String job) {
        switch (job) {
            case "start_song_maker": return new String[]{"Start Song Maker on your server?", "Start"};
            case "stop_song_maker": return new String[]{"Stop Song Maker on your server?", "Stop"};
            case "unload_strata": return new String[]{"Unload the model on your server? It loads again on its next request.", "Unload"};
            case "pause_voice": return new String[]{"Pause Sprocket's voice on your server? That frees its graphics card; "
                    + "the phone's own voice fills in until you resume.", "Pause"};
            case "resume_voice": return new String[]{"Resume Sprocket's voice on your server? It takes about 30 seconds to load.", "Resume"};
            case "restart_voice": return new String[]{"Restart Sprocket's voice on your server? The phone's own voice fills in for about a minute.", "Restart"};
            default: return null;
        }
    }

    static JSONArray definitions() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("name", "check_machines")
                        .put("description", "His home machines right now, as his dashboard reports them: his server (\"gateway\") "
                                + "and his desk computer (\"dev_console\"): CPU, GPU temperatures and memory, disks, what's "
                                + "loaded, and the server's Song Maker, model server (say the model name it gives) and your voice. "
                                + "Tell him what matters, not every number.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("machine", new JSONObject().put("type", "string").put("enum", new JSONArray().put("both").put("gateway").put("dev_console"))))))
                .put(new JSONObject().put("name", "machine_control")
                        .put("description", "One of the few jobs Sprocket may do on his server, after he says yes on a card: "
                                + "start or stop Song Maker, unload its model (unload_strata), restart your own voice, or "
                                + "pause it (frees its graphics card; the phone's voice fills in) and resume it. "
                                + "Nothing else, and nothing on the desk computer (checks only).")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                .put("job", new JSONObject().put("type", "string").put("enum", new JSONArray().put("start_song_maker")
                                        .put("stop_song_maker").put("unload_strata").put("restart_voice").put("pause_voice").put("resume_voice"))))
                                .put("required", new JSONArray().put("job"))));
    }

    // ------------------------------------------------------------------ over Tailscale (worker thread)
    private static String http(String url, String post, int readMs) throws Exception {
        String bad = Config.destinationProblem(url);
        if (bad != null) throw new Exception(bad);
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(false);    // a redirect would skip the address check (Codex 043 #1)
        try {
            h.setConnectTimeout(5000);
            h.setReadTimeout(readMs);
            if (post != null) {
                h.setDoOutput(true);
                h.setRequestProperty("content-type", "application/json");
                try (OutputStream o = h.getOutputStream()) { o.write(post.getBytes(StandardCharsets.UTF_8)); }
            }
            if (h.getResponseCode() / 100 != 2 && h.getResponseCode() != 409) throw new Exception(url + " answered " + h.getResponseCode());
            try (InputStream in = h.getResponseCode() == 409 ? h.getErrorStream() : h.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            h.disconnect();
        }
    }

    /** check_machines: the dashboard, else the gateway's own stats; the gateway's jobs when sprocket-hands answers. */
    static String check(String which) throws Exception {
        String w = which == null || which.equals("both") ? "" : which;
        JSONObject jobs = null;
        try { jobs = new JSONObject(http(url(Config.homeHands, "job runner") + "/status", null, 8000)); } catch (Exception ignored) { }
        try {
            return summary(new JSONObject(http(url(Config.homeDash, "dashboard"), null, 8000)), jobs, w);
        } catch (Exception dashDown) {
            String dev = "the PC: the dashboard isn't answering (it runs on the PC, so the PC may be off or asleep).";
            if (w.equals("dev_console")) return dev;
            try {
                String gw = fromStats(new JSONObject(http(url(Config.homeStats, "server stats"), null, 8000)), jobs);
                return w.isEmpty() ? dev + "\n" + gw : gw;
            } catch (Exception gatewayDown) {
                throw new Exception("neither machine is answering (is the phone on Tailscale?)");
            }
        }
    }

    /** machine_control, after his yes: what the gateway says happened. */
    static String run(String job) throws Exception {
        JSONObject r = new JSONObject(http(url(Config.homeHands, "job runner") + "/do", new JSONObject().put("job", job).toString(), 150_000));
        return r.optString("said", r.optBoolean("ok") ? "Done." : "The gateway didn't say what happened.");
    }
}
