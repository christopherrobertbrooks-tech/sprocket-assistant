package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Machines on the desktop JDK: his two machines in a few plain lines, what needs a look, the jobs and their cards. */
public class MachinesTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static JSONObject gpu(String shortName, double temp, double util, double used, double total) throws Exception {
        return new JSONObject().put("short", shortName).put("temp", temp).put("util", util).put("vram_used_gib", used).put("vram_total_gib", total);
    }

    static JSONObject host(String key, String name, boolean online, double cpu, double cpuTemp, JSONArray gpus, double memUsed,
                           double memTotal, JSONArray disks, JSONObject services, JSONArray models) throws Exception {
        return new JSONObject().put("key", key).put("name", name).put("online", online).put("data", new JSONObject()
                .put("cpu_pct", cpu).put("cpu_temp", cpuTemp).put("gpus", gpus).put("mem_used_gib", memUsed).put("mem_total_gib", memTotal)
                .put("mem_pct", 100 * memUsed / memTotal).put("disks", disks).put("services", services).put("models", models));
    }

    static JSONObject disk(String label, String mount, double pct) throws Exception {
        return new JSONObject().put("label", label).put("mount", mount).put("pct", pct);
    }

    public static void main(String[] a) throws Exception {
        // the dashboard's /api/state, cut down to what Sprocket reads (the shape seen 2026-10-17)
        JSONObject state = new JSONObject().put("hosts", new JSONArray()
                .put(host("local", "the PC", true, 13.2, 40, new JSONArray().put(gpu("GTX 1070", 38, 13, 0.46, 8)), 6.04, 23.37,
                        new JSONArray().put(disk("Disk", "/", 60.6)).put(disk("Data", "/mnt/data", 13.48)),
                        new JSONObject().put("tailscaled", "active"), new JSONArray()))
                .put(host("gateway", "Home-Server", true, 0.2, 37, new JSONArray().put(gpu("RTX 4070", 41, 0, 7.87, 11.99))
                                .put(gpu("V100", 32, 0, 0.01, 32)), 5.26, 15.445,
                        new JSONArray().put(disk("Disk", "/", 72.5)).put(disk("Steam", "/mnt/steam", 65.7)),
                        new JSONObject().put("ollama", "active").put("home-server", "inactive"), new JSONArray())));
        JSONObject jobs = new JSONObject().put("song_maker", "inactive").put("strata", new JSONArray()).put("voice", "active");

        eq("the PC in one line", Machines.summary(state, null, "dev_console"),
                "the PC: CPU 13% at 40°C; GTX 1070 38°C, 13% busy, 0.5 of 8 GB in use; memory 6.0 of 23.4 GB; disks: main 61% full, "
                        + "Data 13% full; nothing loaded. (checks only.)");
        eq("the gateway, with its jobs", Machines.summary(state, jobs, "gateway"),
                "Gateway: CPU 0% at 37°C; RTX 4070 41°C, 0% busy, 7.9 of 12 GB in use; V100 32°C, 0% busy, 0.0 of 32 GB in use; "
                        + "memory 5.3 of 15.4 GB; disks: main 73% full, Steam 66% full; nothing loaded; services: ollama on, home-server off; "
                        + "Song Maker off, the model server has nothing loaded, Sprocket's voice on.");
        eq("both: two lines, and all is well", Machines.summary(state, jobs, "").split("\n").length + " "
                + Machines.summary(state, jobs, "").contains("Needs a look"), "2 false");

        // what needs a look: a hot GPU, a full disk, memory nearly gone, a machine not answering (a hot gateway CPU is
        // normal: 93-96°C under load)
        JSONObject hot = new JSONObject(state.toString());
        JSONObject gw = hot.getJSONArray("hosts").getJSONObject(1).getJSONObject("data");
        gw.getJSONArray("gpus").getJSONObject(1).put("temp", 88);
        gw.put("cpu_temp", 95);
        gw.getJSONArray("disks").getJSONObject(1).put("pct", 93.2);
        hot.getJSONArray("hosts").getJSONObject(0).put("online", false);
        String s = Machines.summary(hot, jobs, "");
        eq("needs a look", s.substring(s.indexOf("Needs a look")), "Needs a look: the PC isn't answering; the gateway's V100 is at 88°C; "
                + "the gateway's Steam disk is 93% full.");
        eq("a machine not answering", s.split("\n")[0], "the PC: not answering (it may be off or asleep).");

        // the gateway's own stats, when the dashboard (on the PC) doesn't answer
        JSONObject stats = new JSONObject().put("gpus", new JSONArray()
                        .put(new JSONObject().put("name", "NVIDIA GeForce RTX 4070").put("temp", 45).put("util", 3).put("vram_used", 8056).put("vram_total", 12282))
                        .put(new JSONObject().put("name", "Tesla V100-PCIE-32GB").put("temp", 32).put("util", 0).put("vram_used", 11).put("vram_total", 32768)))
                .put("cpu", new JSONObject().put("percent", 2.5).put("temp", 39))
                .put("system", new JSONObject().put("mem_total", 16584228864L).put("mem_available", 10877480960L))
                .put("disk", new JSONObject().put("total", 412432228352L).put("used", 299110359040L));
        eq("the gateway from its own stats", Machines.fromStats(stats, jobs),
                "Gateway: CPU 3% at 39°C; RTX 4070 45°C, 3% busy, 7.9 of 12 GB in use; V100 32°C, 0% busy, 0.0 of 32 GB in use; "
                        + "memory 5.3 of 15.4 GB; disks: main 73% full; Song Maker off, the model server has nothing loaded, Sprocket's voice on.");

        // the jobs: only these, each with its card
        JSONObject def = Machines.definitions().getJSONObject(1);
        eq("the tools", Machines.definitions().getJSONObject(0).getString("name") + " " + def.getString("name"), "check_machines machine_control");
        eq("only his jobs (Ember left out)", def.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("job").getJSONArray("enum"),
                "[\"start_song_maker\",\"stop_song_maker\",\"unload_strata\",\"restart_voice\",\"pause_voice\",\"resume_voice\"]");
        eq("pause and resume the voice (frees its graphics card)", Machines.card("pause_voice")[1] + " " + Machines.card("resume_voice")[1]
                + " " + Machines.card("pause_voice")[0].contains("graphics card"), "Pause Resume true");
        eq("a card for each", Machines.card("start_song_maker")[0] + " | " + Machines.card("stop_song_maker")[1] + " | "
                + Machines.card("unload_strata")[1] + " | " + Machines.card("restart_voice")[1], "Start Song Maker on your server? | Stop | Unload | Restart");
        eq("no card for anything else", Machines.card("wake_ember"), null);

        // saying the card's word: "stop" is a no-word, except on a Stop card
        eq("\"stop\" on a Stop card", PhoneTools.answer("stop", "Stop") + " " + PhoneTools.answer("stop it", "Stop") + " "
                + PhoneTools.answer("don't stop", "Stop") + " " + PhoneTools.answer("stop", "Send"), "true true false false");

        // the model server's list: name what's loaded, never the old dashboard name "Strata"
        JSONObject busy = new JSONObject().put("song_maker", "inactive").put("strata", new JSONArray().put("my-model")).put("voice", "active");
        String said = Machines.summary(state, busy, "gateway");
        eq("a loaded model is named, never called Strata's", said.contains("model server has my-model loaded") && !said.contains("Strata"), true);
        System.out.println(fails == 0 ? "machines: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
