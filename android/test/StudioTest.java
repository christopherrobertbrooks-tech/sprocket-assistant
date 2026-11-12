package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Studio on the desktop JDK: the tools, what is sent, what Sprocket says, and what Claude sees of a result later. */
public class StudioTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        JSONArray defs = Studio.definitions();
        eq("two tools", defs.getJSONObject(0).getString("name") + " " + defs.getJSONObject(1).getString("name"), "studio_make studio_jobs");
        JSONObject p = defs.getJSONObject(0).getJSONObject("input_schema").getJSONObject("properties");
        eq("what it makes", p.getJSONObject("kind").getJSONArray("enum"), "[\"song\",\"picture\",\"video\"]");
        eq("full unless he says quick (his choice)", p.getJSONObject("quality").getString("description").contains("full unless he says quick"), true);

        JSONObject b = Studio.body("video", "the dog blinks", "", "", "BASE64", null, null);
        eq("what is sent", b.getString("kind") + " " + b.getString("quality") + " " + b.getString("shape") + " " + b.getString("who") + " "
                + b.getString("image"), "video full landscape sprocket BASE64");
        eq("a draft, and no photo", Studio.body("picture", "x", "draft", "portrait", null, null, null).has("image") + " "
                + Studio.body("picture", "x", "draft", "portrait", null, null, null).getString("quality"), "false draft");
        JSONObject song = Studio.body("song", "slow blues, gravelly voice", "", "", null, " [Verse]\nGuard the bench ", "Bench Blues");
        eq("a song's own lyrics and title go too", song.getString("lyrics") + "|" + song.getString("title"), "[Verse]\nGuard the bench|Bench Blues");
        eq("lyrics only for songs", Studio.body("picture", "x", "", "", null, "la", "T").has("lyrics"), false);
        eq("the tool asks for lyrics", p.has("lyrics") && p.has("title"), true);

        JSONObject q = new JSONObject().put("id", "j1").put("kind", "song").put("ahead", 1).put("estimate", "about 5 minutes");
        eq("queued", Studio.queued(q), "The studio is making it: a song, about 5 minutes once it starts (1 job ahead of it). "
                + "It lands in the chat with a notification when it's done.");

        long now = 1_000_000_000_000L;
        JSONArray jobs = new JSONArray()
                .put(new JSONObject().put("id", "j3").put("kind", "video").put("quality", "full").put("prompt", "the dog blinks").put("status", "running")
                        .put("started", (now - 180_000) / 1000.0))
                .put(new JSONObject().put("id", "j2").put("kind", "picture").put("quality", "full").put("prompt", "a robot").put("status", "queued"))
                .put(new JSONObject().put("id", "j1").put("kind", "song").put("quality", "draft").put("prompt", "an anthem").put("status", "done")
                        .put("seconds", 75).put("note", new JSONObject().put("title", "Workbench Guardian")))
                .put(new JSONObject().put("id", "j0").put("kind", "picture").put("quality", "full").put("prompt", "x").put("status", "failed")
                        .put("error", "CUDA out of memory"));
        eq("the jobs, said plainly", Studio.jobs(jobs, now), "Making now: a video (\"the dog blinks\"), started 3 minutes ago.\n"
                + "Waiting: a picture (\"a robot\").\nDone lately: a song, \"Workbench Guardian\" (75 s).\n"
                + "Failed: a picture (\"x\"): CUDA out of memory");
        eq("nothing", Studio.jobs(new JSONArray(), now), "The studio has nothing on.");

        JSONObject done = new JSONObject().put("id", "j1").put("kind", "song").put("prompt", "an anthem").put("file", "j1.mp3")
                .put("seconds", 75).put("note", new JSONObject().put("title", "Workbench Guardian").put("lyrics", "Guard the bench"));
        eq("what Claude sees of a finished song later", Studio.readable(done.toString()),
                "(The studio made a song, \"Workbench Guardian\", from: \"an anthem\". Lyrics: Guard the bench)");
        eq("a picture", Studio.readable(new JSONObject().put("kind", "picture").put("prompt", "a robot").put("file", "p.png").toString()),
                "(The studio made a picture from: \"a robot\")");
        eq("ready line", Studio.ready(done) + " | " + Studio.ready(new JSONObject().put("kind", "video").put("prompt", "x")),
                "Your song is ready: Workbench Guardian | Your video is ready");

        eq("a prompt for an action record", Studio.brief("a  very long prompt that keeps going and going and going past sixty characters") + "|"
                + Studio.brief(" short "), "a very long prompt that keeps going and going and going p...|short");

        System.out.println(fails == 0 ? "studio: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
