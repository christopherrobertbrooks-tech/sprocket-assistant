package dev.sprocket.app;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

/** UndoLog on the desktop JDK: "what did you just do?" and "undo that" (roadmap, 11-18). */
public class UndoLogTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        TimeZone tz = TimeZone.getTimeZone("America/Chicago");
        long t = 1795050000000L;   // 2026-11-18, evening in Chicago
        UndoLog log = new UndoLog();

        // request 1: two settings and a list item
        log.begin("make it quiet and add milk", t);
        log.add("Media volume 20%", UndoLog.AUTO, "phone_control", new JSONObject().put("setting", "volume").put("percent", 70), t);
        log.add("Do Not Disturb on", UndoLog.AUTO, "phone_control", new JSONObject().put("setting", "do_not_disturb").put("on", false), t);
        log.add("Added to grocery: milk", UndoLog.AUTO, "list_remove", new JSONObject().put("list", "grocery").put("items", new org.json.JSONArray().put("milk")), t);
        // request 2: only opened an app (nothing to put back)
        log.begin("open YouTube", t + 60_000);
        log.add("Opened YouTube", UndoLog.INFO, null, null, t + 60_000);
        // request 3: he asks what Sprocket did
        log.begin("what did you just do?", t + 120_000);
        String did = log.whatIDid(2, t + 120_000, tz);
        eq("newest first, the asking request left out", did.startsWith("“open YouTube”"), true);
        eq("each request's steps listed", did.contains("Media volume 20%; Do Not Disturb on; Added to grocery: milk."), true);
        eq("times in 12-hour form", did.matches("(?s).*\\(\\d{1,2}:\\d\\d [ap]m\\).*"), true);

        // "undo that" skips the request that only opened an app, and the current one
        UndoLog.Entry e = log.toUndo();
        eq("undo goes to the last request with something to put back", e.asked, "make it quiet and add milk");
        eq("its reverse calls are kept", e.steps.get(0).tool + " " + e.steps.get(0).input.optInt("percent"), "phone_control 70");
        log.markUndone(e);
        eq("after undoing it, nothing older left", log.toUndo(), null);
        eq("what_i_did says it was undone", log.whatIDid(5, t + 120_000, tz).contains("(Undone since.)"), true);

        // the reverse steps an undo runs are not logged as new things Sprocket did
        log.begin("undo that", t + 180_000);
        log.undoing = true;
        log.add("Media volume 70%", UndoLog.AUTO, "phone_control", new JSONObject().put("percent", 20), t + 180_000);
        log.undoing = false;
        eq("undo's own steps not logged", log.whatIDid(1, t + 180_000, tz).startsWith("“open YouTube”"), true);

        // texts and approved taps can't be undone; switches in other apps are offered
        log.begin("text mom I'm late and turn off bluetooth", t + 240_000);
        log.add("Texted Mom: “I'm late”", UndoLog.NEVER, null, null, t + 240_000);
        log.add("Settings: turned Bluetooth off", UndoLog.SCREEN, null, null, t + 240_000);
        log.begin("undo that", t + 300_000);
        UndoLog.Entry e2 = log.toUndo();
        eq("a text-and-switch request can be undone (in part)", e2.asked, "text mom I'm late and turn off bluetooth");
        eq("SCREEN and NEVER steps keep no reverse call", e2.steps.get(0).tool + " " + e2.steps.get(1).tool, "null null");
        String r = UndoLog.report(e2.asked, Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList("Settings: turned Bluetooth off"), Collections.singletonList("Texted Mom: “I'm late”"));
        eq("switch offered, only after his yes", r.contains("Done inside another app: Settings: turned Bluetooth off. Ask him whether to change it back; only after his yes, do it with the screen tool."), true);
        eq("a text can't be put back, said plainly", r.contains("Can't be put back: Texted Mom"), true);
        String r2 = UndoLog.report("x", Arrays.asList(UndoLog.undid("Media volume 30%", "Media volume set to 0%.")),
                Arrays.asList("Brightness 30% (no permission)"), Collections.emptyList(), Collections.emptyList());
        // 11-18 on his phone: "Put back: Media volume 30%" made Sprocket say the volume was at 30 (it was back at 0)
        eq("what went back says where it is now, and what failed", r2, "Undoing \u201cx\u201d. Reversed (each change Sprocket made, then "
                + "where it is now): had done \u201cMedia volume 30%\u201d, undone, now: Media volume set to 0%. Couldn't put back: "
                + "Brightness 30% (no permission) (the next \u201cundo that\u201d tries again).");

        // Codex 053 #3 (11-18): a failed reversal marked the request undone, so the next undo skipped to an older one
        UndoLog part = new UndoLog();
        part.begin("older", t); part.add("Flashlight on", UndoLog.AUTO, "phone_control", new JSONObject(), t);
        part.begin("newer", t + 1); part.add("Brightness 30%", UndoLog.AUTO, "phone_control", new JSONObject(), t + 1);
        part.add("Media volume 20%", UndoLog.AUTO, "phone_control", new JSONObject(), t + 1);
        part.begin("undo that", t + 2);
        UndoLog.Entry pe = part.toUndo();
        pe.steps.get(1).done = true;            // volume went back; brightness failed
        part.finish(pe);
        eq("a partly failed undo stays to be retried", part.toUndo().asked, "newer");
        eq("what_i_did says partly", part.whatIDid(1, t + 2, tz).contains("(Partly undone.)"), true);
        pe.steps.get(0).done = true;
        part.finish(pe);
        eq("once every step is back, the next undo goes further back", part.toUndo().asked, "older");
        eq("step progress is kept", UndoLog.fromJson(part.toJson().toString()).whatIDid(2, t + 2, tz).contains("(Undone since.)"), true);
        // Codex 054 #3 (11-18): a switch only OFFERED back was already called undone
        UndoLog off = new UndoLog();
        off.begin("turn off bluetooth", t); off.add("Settings: turned Bluetooth off", UndoLog.SCREEN, null, null, t);
        off.begin("undo that", t + 1);
        UndoLog.Entry oe = off.toUndo();
        oe.steps.get(0).offered = true;
        off.finish(oe);
        eq("an offer isn't an undo", off.whatIDid(1, t + 1, tz).contains("(Undone since.)"), false);
        eq("it says it waits for his yes", off.whatIDid(1, t + 1, tz).contains("waits for his yes"), true);
        eq("and a further undo doesn't offer it again", off.toUndo(), null);
        // kept on the phone across restarts, the last 20 requests only
        UndoLog back = UndoLog.fromJson(log.toJson().toString());
        eq("saved and read back", back.whatIDid(5, t + 300_000, tz), log.whatIDid(5, t + 300_000, tz));
        UndoLog many = new UndoLog();
        for (int i = 0; i < 30; i++) { many.begin("ask " + i, t + i); many.add("Flashlight on", UndoLog.AUTO, "phone_control", new JSONObject(), t + i); }
        eq("only the last 20 requests kept", many.toJson().getJSONArray("requests").length(), 20);
        eq("a damaged file starts fresh", UndoLog.fromJson("{not json").toUndo(), null);
        eq("nothing done: said plainly", new UndoLog().whatIDid(1, t, tz).startsWith("Sprocket hasn't done anything"), true);
        eq("both tools defined", UndoLog.definitions().getJSONObject(0).getString("name") + " "
                + UndoLog.definitions().getJSONObject(1).getString("name"), "what_i_did undo");

        System.out.println(fails == 0 ? "undo log: all " + n + " checks pass" : "undo log: " + fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
