package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The studio on the gateway (the owner 2026-10-17: "lets build the studio and give yourself and sprocket access"): songs
 * (through Song Maker), pictures (Qwen-Image 2.1) and videos (Wan 2.2; FastWan for a quick draft), one at a time on the
 * V100 (the studio server). Sprocket asks for one, StudioService watches it, and the finished file lands in the chat as a
 * card. Videos and songs are full quality unless he says quick (his choice). Plain Java: test/StudioTest.java.
 */
final class Studio {
    static String base = "";                                         // a self-test points it at the stand-in

    private Studio() { }

    private static JSONObject str(String what) throws Exception { return new JSONObject().put("type", "string").put("description", what); }

    static JSONArray definitions() throws Exception {
        return new JSONArray()
                .put(new JSONObject().put("name", "studio_make")
                        .put("description", "Make a song (with lyrics and vocals), a picture, or a short video (5 s) in his studio "
                                + "on the gateway; it lands in the chat when done, with a notification. One job at a time: a song "
                                + "takes minutes, a picture two or three, a full video 20-30 minutes. A video can start from "
                                + "the photo he sent with this message (use_photo). Write the prompt as a clear description of what "
                                + "to make, in his words plus the detail a model needs. For a song, write the lyrics and a short "
                                + "title yourself (the studio's own lyric writer repeats lines); the prompt is then the sound: "
                                + "genre, mood, voice, instruments, tempo.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                                        .put("kind", new JSONObject().put("type", "string").put("enum", new JSONArray().put("song").put("picture").put("video")))
                                        .put("prompt", str("What to make: the song's idea, the picture, or what happens in the video."))
                                        .put("quality", new JSONObject().put("type", "string").put("enum", new JSONArray().put("full").put("draft"))
                                                .put("description", "full unless he says quick or draft (a draft video is 2-3 minutes but drifts from a photo)."))
                                        .put("shape", new JSONObject().put("type", "string").put("enum", new JSONArray().put("landscape").put("portrait").put("square")))
                                        .put("use_photo", new JSONObject().put("type", "boolean").put("description", "Start the video from the photo he just sent."))
                                        .put("lyrics", str("A song's lyrics, with [Verse], [Chorus] and [Bridge] lines; about two to three minutes of singing."))
                                        .put("title", str("A song's title.")))
                                .put("required", new JSONArray().put("kind").put("prompt"))))
                .put(new JSONObject().put("name", "studio_jobs")
                        .put("description", "What the studio is making, what's waiting, and what finished or failed lately.")
                        .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject())));
    }

    static JSONObject body(String kind, String prompt, String quality, String shape, String photoBase64, String lyrics,
                           String title) throws Exception {
        JSONObject b = new JSONObject().put("kind", kind).put("prompt", prompt)
                .put("quality", quality == null || quality.isEmpty() ? "full" : quality)
                .put("shape", shape == null || shape.isEmpty() ? "landscape" : shape).put("who", "sprocket");
        if (photoBase64 != null) b.put("image", photoBase64);
        if (kind.equals("song") && lyrics != null && !lyrics.trim().isEmpty()) b.put("lyrics", lyrics.trim());
        if (kind.equals("song") && title != null && !title.trim().isEmpty()) b.put("title", title.trim());
        return b;
    }

    private static String a(String kind) { return "a " + kind; }

    static String queued(JSONObject job) {
        int ahead = job.optInt("ahead");
        return "The studio is making it: " + a(job.optString("kind")) + ", " + job.optString("estimate") + " once it starts"
                + (ahead > 0 ? " (" + ahead + " job" + (ahead == 1 ? "" : "s") + " ahead of it)" : "")
                + ". It lands in the chat with a notification when it's done.";
    }

    static String title(JSONObject j) {
        JSONObject note = j.optJSONObject("note");
        return note == null ? "" : note.optString("title", "");
    }

    /** The studio's recent jobs, for Claude: what's on, waiting, done, failed. */
    static String jobs(JSONArray all, long now) {
        List<String> making = new ArrayList<>(), waiting = new ArrayList<>(), done = new ArrayList<>(), failed = new ArrayList<>();
        for (int i = 0; i < all.length() && i < 12; i++) {
            JSONObject j = all.optJSONObject(i);
            String what = a(j.optString("kind")) + " (\"" + j.optString("prompt").replaceAll("\\s+", " ") + "\")";
            switch (j.optString("status")) {
                case "running":
                    long mins = Math.max(0, Math.round((now / 1000.0 - j.optDouble("started", now / 1000.0)) / 60));
                    making.add(what + ", started " + (mins <= 1 ? "a minute" : mins + " minutes") + " ago");
                    break;
                case "queued": waiting.add(what); break;
                case "done":
                    String t = title(j);
                    done.add(a(j.optString("kind")) + (t.isEmpty() ? " (\"" + j.optString("prompt") + "\")" : ", \"" + t + "\"")
                            + " (" + j.optInt("seconds") + " s)");
                    break;
                default: failed.add(what + ": " + j.optString("error"));
            }
        }
        StringBuilder b = new StringBuilder();
        if (!making.isEmpty()) b.append("Making now: ").append(String.join("; ", making)).append(".");
        if (!waiting.isEmpty()) b.append(b.length() == 0 ? "" : "\n").append("Waiting: ").append(String.join("; ", waiting)).append(".");
        if (!done.isEmpty()) b.append(b.length() == 0 ? "" : "\n").append("Done lately: ").append(String.join("; ", done.subList(0, Math.min(3, done.size())))).append(".");
        if (!failed.isEmpty()) b.append(b.length() == 0 ? "" : "\n").append("Failed: ").append(String.join("; ", failed.subList(0, Math.min(2, failed.size()))));
        return b.length() == 0 ? "The studio has nothing on." : b.toString();
    }

    /** What Claude sees of a finished job in the chat's history. */
    static String readable(String json) {
        try {
            JSONObject j = new JSONObject(json);
            JSONObject note = j.optJSONObject("note");
            String t = note == null ? "" : note.optString("title", ""), lyrics = note == null ? "" : note.optString("lyrics", "");
            return "(The studio made " + a(j.optString("kind")) + (t.isEmpty() ? "" : ", \"" + t + "\",") + " from: \"" + j.optString("prompt") + "\""
                    + (lyrics.isEmpty() ? "" : ". Lyrics: " + lyrics.replaceAll("\\s+", " ")) + ")";
        } catch (Exception e) {
            return "(something the studio made)";
        }
    }

    /** A prompt, short enough for an action record. */
    static String brief(String prompt) {
        String p = prompt == null ? "" : prompt.replaceAll("\\s+", " ").trim();
        return p.length() > 60 ? p.substring(0, 57) + "..." : p;
    }

    static String ready(JSONObject j) {
        String t = title(j);
        return "Your " + j.optString("kind") + " is ready" + (t.isEmpty() ? "" : ": " + t);
    }

    // ------------------------------------------------------------------ over Tailscale (worker threads)
    static JSONObject http(String path, JSONObject post, int readMs) throws Exception { return http(at(), path, post, readMs); }

    /** The studio's address: the stand-in in a self-test, else his from Settings. */
    static String at() throws Exception {
        String a = base.isEmpty() ? Config.studio.trim().replaceAll("/+$", "") : base;
        if (a.isEmpty()) throw new Exception("the studio's address isn't set (Settings)");
        return a;
    }

    /** At a given studio: a job is watched at the one that made it, even after the app restarts. */
    static JSONObject http(String at, String path, JSONObject post, int readMs) throws Exception {
        String bad = Config.destinationProblem(at);
        if (bad != null) throw new Exception(bad);
        HttpURLConnection h = (HttpURLConnection) new URL(at + path).openConnection();
        h.setInstanceFollowRedirects(false);    // a redirect would skip the address check (Codex 043 #1)
        try {
            h.setConnectTimeout(5000);
            h.setReadTimeout(readMs);
            if (post != null) {
                h.setDoOutput(true);
                h.setRequestProperty("content-type", "application/json");
                try (OutputStream o = h.getOutputStream()) { o.write(post.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int code = h.getResponseCode();
            try (InputStream in = code / 100 == 2 ? h.getInputStream() : h.getErrorStream()) {
                JSONObject r = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (code / 100 != 2) throw new Exception(r.optString("error", "the studio answered " + code));
                return r;
            }
        } finally {
            h.disconnect();
        }
    }

    static void download(String at, String file, File to) throws Exception {
        String bad = Config.destinationProblem(at);
        if (bad != null) throw new Exception(bad);
        HttpURLConnection h = (HttpURLConnection) new URL(at + "/files/" + file).openConnection();
        h.setInstanceFollowRedirects(false);    // a redirect would skip the address check (Codex 043 #1)
        try {
            h.setConnectTimeout(5000);
            h.setReadTimeout(120_000);
            if (h.getResponseCode() != 200) throw new Exception("the studio has no file " + file);
            File tmp = new File(to.getPath() + ".part");
            try (InputStream in = h.getInputStream()) { Files.write(tmp.toPath(), in.readAllBytes()); }
            if (!tmp.renameTo(to)) throw new Exception("couldn't save " + file);
        } finally {
            h.disconnect();
        }
    }
}
