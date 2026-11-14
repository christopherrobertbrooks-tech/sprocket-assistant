package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** The Feed on the desktop JDK: what it watches, when it comes, what it's given, and the brief Claude writes back. */
public class FeedTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        ZoneId chicago = ZoneId.of("America/Chicago");
        long sat3pm = ZonedDateTime.of(2026, 10, 17, 15, 0, 0, 0, chicago).toInstant().toEpochMilli();
        long sat5am = ZonedDateTime.of(2026, 10, 17, 5, 0, 0, 0, chicago).toInstant().toEpochMilli();

        // when it comes: 6:00 every morning (his pick), the next one strictly after now, clocks changing or not
        eq("after 6: tomorrow at 6", ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(Feed.nextAt(sat3pm, chicago, 6, 0)), chicago),
                "2026-10-18T06:00-05:00[America/Chicago]");
        eq("before 6: today at 6", ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(Feed.nextAt(sat5am, chicago, 6, 0)), chicago),
                "2026-10-17T06:00-05:00[America/Chicago]");
        long beforeFallBack = ZonedDateTime.of(2026, 10, 31, 7, 0, 0, 0, chicago).toInstant().toEpochMilli();
        eq("across the clocks going back (Nov 1)", ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(Feed.nextAt(beforeFallBack, chicago, 6, 0)), chicago),
                "2026-11-01T06:00-06:00[America/Chicago]");

        // the news follows the topics the user picks: none to start (no AI bias), then what they add
        Feed f = new Feed();
        eq("no topics to start", f.topics, "[]");
        eq("one they asked for", f.watch("Chicago Cubs") + " " + f.watch("gardening") + " " + f.topics.size(), "true true 2");
        eq("not twice", f.watch("  chicago CUBS "), false);
        eq("dropped", f.unwatch("cubs") + " " + f.topics.size(), "Chicago Cubs 1");
        eq("sections on by default: Today and News, no Machines", f.on, "{today=true, news=true}");
        eq("a brief saved by 0.1.6: AI news off stays off as News, Machines and GitHub gone", Feed.fromJson("{\"topics\": [\"x\"], "
                + "\"on\": {\"today\": true, \"github\": true, \"machines\": true, \"ai_news\": false}}").on, "{today=true, news=false}");
        Feed back = Feed.fromJson(f.toJson());
        eq("saved and loaded", back.topics + " " + back.on + " " + back.lastAt, f.topics + " " + f.on + " " + f.lastAt);
        eq("a damaged file: the defaults", Feed.fromJson("{nope").topics.size(), 0);

        // machines: only what needs a look
        eq("machines all fine: nothing", Feed.alerts("the PC: CPU 3%...\nGateway: CPU 1%..."), "");
        eq("machines with trouble", Feed.alerts("the PC: ...\nGateway: ...\nNeeds a look: the gateway's Steam disk is 93% full."),
                "Needs a look: the gateway's Steam disk is 93% full.");

        // the request: Sonnet, web search, the facts and topics, only the sections they have on, no cache marks (once a day)
        Feed g = new Feed();
        g.watch("gardening");
        JSONObject req = Feed.request(g, "Saturday, October 18, 2026, 6:00 AM (America/Chicago)", "WEATHER FACTS", "CALENDAR FACTS",
                "MACHINE FACTS", "Springfield");
        String body = req.toString();
        eq("the brief's model and search", req.getString("model") + " " + req.getJSONArray("tools").getJSONObject(0).getString("type"),
                Claude.SONNET + " web_search_20250305");
        eq("list items due go into Today", Feed.request(g, "now", "", "", "", "", "due today: renew tags (to-do).").toString().contains("From their lists: due today: renew tags"), true);
        eq("nothing due, nothing said", body.contains("From their lists"), false);
        Config.own = true;
        Config.ownModel = "qwen";
        eq("with his own server as the brain, it writes it", Feed.request(g, "now", "", "", "", "").getString("model"), "qwen");
        Config.reset();
        Config.off.add("web");
        eq("web search off: no news section and no search", Feed.request(g, "now", "", "", "", "").toString().contains("web_search"), false);
        Config.reset();
        eq("its facts and topics", body.contains("WEATHER FACTS") && body.contains("CALENDAR FACTS") && body.contains("gardening"), true);
        eq("no machines in the brief", body.contains("MACHINE FACTS") || body.contains("Machines"), false);
        eq("no AI news in the brief's instructions", body.contains("AI news"), false);
        eq("no topics: no news section and no search", Feed.request(new Feed(), "now", "", "", "", "").toString().contains("web_search"), false);
        Feed off = new Feed();
        off.watch("gardening");
        off.on.put("news", false);
        eq("News switched off: no search", Feed.request(off, "now", "", "", "", "").toString().contains("web_search"), false);
        eq("no GitHub in the brief's instructions", body.contains("GitHub"), false);
        eq("no cache marks", body.contains("cache_control"), false);

        // the brief back from Claude: JSON (fences or chatter around it are fine)
        String reply = "Here you go:\n```json\n{\"bottom_line\": \"Rain after 5. One new GitHub reply.\", \"sections\": ["
                + "{\"name\": \"Today\", \"items\": [{\"line\": \"62° and sunny, rain from 5 PM\", \"detail\": \"High 64.\"}]},"
                + "{\"name\": \"AI news\", \"items\": [{\"line\": \"A new 2-bit model\", \"detail\": \"It fits one card.\", \"link\": \"https://example.com/a\"}]},"
                + "{\"name\": \"Machines\", \"items\": []}]}\n```";
        Feed.Brief b = Feed.parse(reply, sat3pm);
        eq("parsed", b.bottomLine + " | " + b.sections.size() + " sections (an empty one dropped) | " + b.sections.get(1).items.get(0).link,
                "Rain after 5. One new GitHub reply. | 2 sections (an empty one dropped) | https://example.com/a");
        eq("no brief in it: null", Feed.parse("Sorry, no.", sat3pm), null);
        eq("saved in the chat and read back", Feed.Brief.fromJson(b.toJson()).bottomLine, b.bottomLine);
        eq("what Claude sees of it later (for follow-up questions)", Feed.readable(b.toJson()),
                "Morning brief. Bottom line: Rain after 5. One new GitHub reply.\nToday: 62° and sunny, rain from 5 PM (High 64.)\n"
                        + "AI news: A new 2-bit model (It fits one card.) https://example.com/a");
        eq("what Play says", Feed.spoken(b), "Rain after 5. One new GitHub reply. Today: 62° and sunny, rain from 5 PM. AI news: A new 2-bit model.");

        JSONObject wt = Feed.watchTool();
        eq("the watch tool", wt.getString("name"), "watch_news");

        System.out.println(fails == 0 ? "feed: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
