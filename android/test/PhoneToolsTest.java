package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Calendar;

/** Phase 3 on the desktop JDK: the phone tools' definitions and the plain decisions behind them. No phone. */
public class PhoneToolsTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        JSONArray defs = PhoneTools.definitions();
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < defs.length(); i++) names.append(i > 0 ? " " : "").append(defs.getJSONObject(i).getString("name"));
        eq("the everyday set, then the rest of the earlier test app's", names, "set_timer set_alarm set_reminder send_text call find_contact "
                + "check_calendar add_event open_app phone_control phone_status media maps open_screen clock_control missed_calls "
                + "email add_contact share_location go_to screen");
        for (int i = 0; i < defs.length(); i++) {
            JSONObject d = defs.getJSONObject(i);
            eq(d.getString("name") + ": a description and an object schema",
                    !d.getString("description").isEmpty() && d.getJSONObject("input_schema").getString("type").equals("object"), true);
        }
        eq("a text needs who and what", req(defs, "send_text"), "[\"to\",\"message\"]");

        // 2026-11-15, the practice ground: "money apps are off limits" made Sprocket refuse every food order ("I don't
        // have a food-ordering tool"). Only banking, payment, wallet and password apps are; food apps fill a cart and
        // the checkout button asks him.
        String screenTool = desc(defs, "screen");
        eq("screen: food and shopping apps are allowed", screenTool.contains("shopping and food-delivery apps are fine"), true);
        eq("screen: not 'money apps' (it covered food apps)", screenTool.contains("Money and password apps"), false);
        String persona = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("android/assets/persona.md")), "UTF-8");
        eq("persona: food orders fill the cart and stop for their tap", persona.contains("Food and shopping orders: you can do them")
                && persona.contains("the order is placed only when they tap it"), true);
        eq("an alarm needs a time", req(defs, "set_alarm"), "[\"time\"]");

        // repeat days: Claude names them, the Clock wants Calendar's numbers (Sunday = 1)
        eq("weekdays", PhoneTools.days(new JSONArray().put("monday").put("tuesday").put("wednesday").put("thursday").put("friday")),
                "[2, 3, 4, 5, 6]");
        eq("unordered, repeated, odd case", PhoneTools.days(new JSONArray().put("Saturday").put("sunday").put("saturday")), "[1, 7]");
        eq("no days", PhoneTools.days(null), "[]");
        eq("a 24-hour time", java.util.Arrays.toString(PhoneTools.hhmm("18:05")), "[18, 5]");
        eq("a bad time", PhoneTools.hhmm("6pm"), null);
        eq("a bad hour", PhoneTools.hhmm("25:00"), null);

        // a reminder: soon = a labelled timer, within a day = a labelled alarm, later = a calendar entry with an alert
        ZoneId z = ZoneId.of("America/Chicago");
        long now = LocalDateTime.of(2026, 10, 17, 9, 0).atZone(z).toInstant().toEpochMilli();
        eq("in 20 minutes", PhoneTools.route(now, PhoneTools.at("2026-10-17T09:20", z)), "timer");
        eq("tonight at 6", PhoneTools.route(now, PhoneTools.at("2026-10-17T18:00", z)), "alarm");
        eq("tomorrow at 8", PhoneTools.route(now, PhoneTools.at("2026-10-18T08:00", z)), "alarm");
        eq("next week", PhoneTools.route(now, PhoneTools.at("2026-10-24T08:00", z)), "calendar");
        eq("already past", PhoneTools.route(now, PhoneTools.at("2026-10-17T08:00", z)), "past");
        eq("a date-time he can't mean", PhoneTools.at("tonight", z), -1L);
        eq("a whole day", PhoneTools.at("2026-10-20", z), LocalDateTime.of(2026, 10, 20, 0, 0).atZone(z).toInstant().toEpochMilli());

        // the card before anything is sent or called: exactly what will happen
        eq("text card", PhoneTools.textCard("Mom", "+15550100", "I'm running late"), "Text Mom (+15550100)\n“I'm running late”");
        eq("call card", PhoneTools.callCard("Mom", "+15550100"), "Call Mom (+15550100)");
        eq("a number stays a number", PhoneTools.number("(555) 010-0100"), "5550100100");
        eq("a name is not a number", PhoneTools.number("Mom"), null);
        eq("short codes too", PhoneTools.number("911"), "911");

        // who he means: the spelling first, then how it sounds (the owner: "Toni" came through as "Tony" and wasn't found)
        java.util.List<String> book = java.util.Arrays.asList("Toni", "Mom", "Mike's work", "Jon Miller", "John Smith");
        eq("a spelling match", pick(PhoneTools.resolve("Mom", java.util.Arrays.asList("Mom"), book)), "Mom");
        eq("another spelling of a contact", pick(PhoneTools.resolve("Tony", java.util.Collections.emptyList(), book)), "Toni");
        eq("\"my\" ignored", pick(PhoneTools.resolve("my mom", java.util.Arrays.asList("Mom"), book)), "Mom");
        eq("two that fit: ask, the closer first", pick(PhoneTools.resolve("John", java.util.Collections.emptyList(), book)), "ask [John Smith, Jon Miller]");
        eq("several spellings start the same: the exact one", pick(PhoneTools.resolve("mike", java.util.Arrays.asList("Mike's work", "Mike"), book)), "Mike");
        eq("several, none exact: ask", pick(PhoneTools.resolve("Jo", java.util.Arrays.asList("Jon Miller", "John Smith"), book)), "ask [Jon Miller, John Smith]");
        eq("nobody", pick(PhoneTools.resolve("the plumber", java.util.Collections.emptyList(), book)), "none");
        eq("the tools ask for the name as he wrote it", defs.toString().contains("exactly as they wrote or said it"), true);

        eq("Calendar.SUNDAY is 1", Calendar.SUNDAY, 1);
        // the rest of the commands (the owner 2026-10-17: "start the rest of the commands"): the plain decisions behind them
        eq("directions by car", PhoneTools.mapsUri("directions", "Des Moines airport", "drive"), "google.navigation:q=Des%20Moines%20airport&mode=d");
        eq("on foot, by bike", PhoneTools.mapsUri("directions", "the park", "walk") + " " + PhoneTools.mapsUri("directions", "the park", "bike"),
                "google.navigation:q=the%20park&mode=w google.navigation:q=the%20park&mode=b");
        eq("by bus or train", PhoneTools.mapsUri("directions", "Union Station", "transit"),
                "https://www.google.com/maps/dir/?api=1&destination=Union%20Station&travelmode=transit");
        eq("nearby", PhoneTools.mapsUri("nearby", "coffee & donuts", ""), "geo:0,0?q=coffee%20%26%20donuts");
        JSONArray screens = null;
        for (int i = 0; i < defs.length(); i++)
            if (defs.getJSONObject(i).getString("name").equals("open_screen"))
                screens = defs.getJSONObject(i).getJSONObject("input_schema").getJSONObject("properties").getJSONObject("screen").getJSONArray("enum");
        StringBuilder unmapped = new StringBuilder();
        for (int i = 0; screens != null && i < screens.length(); i++)
            if (PhoneTools.screen(screens.getString(i)) == null) unmapped.append(screens.getString(i)).append(' ');
        eq("every screen he can ask for opens something", screens != null && screens.length() >= 15 ? "[" + unmapped + "]" : "no screens", "[]");
        eq("the wifi page", PhoneTools.screen("wifi_settings")[0], "android.settings.WIFI_SETTINGS");
        eq("the alarms list", PhoneTools.screen("alarms")[0], "android.intent.action.SHOW_ALARMS");
        // the settings pages a person asks for by name, opened directly instead of scrolling (app memory test, 11-12:
        // ten screen steps to reach About phone)
        eq("About phone", PhoneTools.screen("about_phone_settings")[0], "android.settings.DEVICE_INFO_SETTINGS");
        eq("Do not disturb", PhoneTools.screen("do_not_disturb_settings")[0], "android.settings.ZEN_MODE_PRIORITY_SETTINGS");
        eq("default apps", PhoneTools.screen("default_apps_settings")[0], "android.settings.MANAGE_DEFAULT_APPS_SETTINGS");
        int pages = 0;
        for (String k : PhoneTools.SCREENS.keySet()) if (k.endsWith("_settings")) pages++;
        eq("every settings page falls back to the settings when a phone lacks it (the _settings names)", pages >= 30, true);
        eq("brightness: percent to the phone's 0-255", PhoneTools.brightness(0) + " " + PhoneTools.brightness(50) + " " + PhoneTools.brightness(100)
                + " " + PhoneTools.brightness(140), "1 128 255 255");
        eq("brightness: the phone's level back to percent", PhoneTools.percent(128) + " " + PhoneTools.percent(255), "50 100");
        eq("a step up or down", PhoneTools.step(50, "up") + " " + PhoneTools.step(50, "down") + " " + PhoneTools.step(95, "up") + " "
                + PhoneTools.step(5, "down"), "65 35 100 0");
        eq("which alarm to cancel", PhoneTools.alarmSearch("06:30", "", false) + " " + PhoneTools.alarmSearch("", "gym", false) + " "
                + PhoneTools.alarmSearch("", "", true) + " " + PhoneTools.alarmSearch("", "", false), "android.time android.label android.all android.next");
        eq("an email address", PhoneTools.isEmail("toni@example.com") + " " + PhoneTools.isEmail("Toni") + " " + PhoneTools.isEmail("a@b"),
                "true false false");
        eq("his location as a link", PhoneTools.mapsLink(41.5868353, -93.6249593), "https://maps.google.com/?q=41.58684,-93.62496");

        // the card out loud, and his spoken answer (the owner 2026-10-17: "I should be able to say send as well as click")
        eq("a text card, said", PhoneTools.spoken(PhoneTools.textCard("Toni", "+15550123", "Dinner at 6?"), "Send"),
                "Text Toni: \u201cDinner at 6?\u201d Say send, or cancel.");
        eq("a call card, said", PhoneTools.spoken(PhoneTools.callCard("Mom", "+15550100"), "Call"), "Call Mom? Say call, or cancel.");
        eq("a list card, said", PhoneTools.spoken("Clear the grocery list (3 items)?", "Clear"), "Clear the grocery list (3 items)? Say clear, or cancel.");
        String[][] said = {
                {"send", "Send", "true"}, {"Send it.", "Send", "true"}, {"yes", "Send", "true"}, {"yeah go ahead", "Send", "true"},
                {"sent", "Send", "true"}, {"okay", "Send", "true"}, {"call", "Call", "true"}, {"call her", "Call", "true"},
                {"clear it", "Clear", "true"},
                {"cancel", "Send", "false"}, {"no", "Send", "false"}, {"don't send it", "Send", "false"}, {"wait", "Send", "false"},
                {"no, send it", "Send", "false"}, {"never mind", "Send", "false"}, {"not yet", "Send", "false"},
                {"what time is it", "Send", "null"}, {"", "Send", "null"}, {"I know", "Send", "null"}, {"sending", "Send", "null"},
        };
        StringBuilder wrong = new StringBuilder();
        for (String[] t : said) {
            Boolean got = PhoneTools.answer(t[0], t[1]);
            if (!String.valueOf(got).equals(t[2])) wrong.append(" [").append(t[0]).append(" -> ").append(got).append("]");
        }
        eq("his spoken answers (any no-word cancels; unclear leaves the card)", wrong.length() == 0 ? "all right" : wrong, "all right");

        // open_app picks the closest name (2026-11-10: "YouTube Music" opened YouTube)
        java.util.List<String> apps = java.util.Arrays.asList("YouTube", "YT Music", "Messages", "Maps", "Settings", "Samsung Notes");
        eq("YouTube Music is YT Music", PhoneTools.pickApp("YouTube Music", apps), 1);
        eq("YT Music exactly", PhoneTools.pickApp("yt music", apps), 1);
        eq("YouTube is still YouTube", PhoneTools.pickApp("YouTube", apps), 0);
        eq("Google Messages is Messages", PhoneTools.pickApp("Google Messages", apps), 2);
        eq("notes finds Samsung Notes", PhoneTools.pickApp("notes", apps), 5);
        eq("nothing fits", PhoneTools.pickApp("Spotify", apps), -1);
        // after the hands: stay on what he asked to see, come back to Sprocket otherwise
        eq("show me photos stays", PhoneTools.wantsToSee("show me photos from yesterday"), true);
        eq("open Maps stays", PhoneTools.wantsToSee("Open Maps"), true);
        eq("is Bluetooth on comes back", PhoneTools.wantsToSee("is Bluetooth on?"), false);
        eq("delete the meeting comes back", PhoneTools.wantsToSee("delete the Meeting at 3pm tomorrow"), false);
        eq("how many open tabs comes back (open isn't an ask there)", PhoneTools.wantsToSee("How many open tabs do I have in Firefox?"), false);
        // a food-order test: Sprocket filled the cart, then jumped back to its own screen, so the owner couldn't check out
        eq("an order stays on the cart", PhoneTools.wantsToSee("i need some food, lets order doordash, put 42 hashbrowns from mcdonalds"), true);
        eq("put it in my cart stays", PhoneTools.wantsToSee("put a veggie burrito from Casa Verde in my Eats cart"), true);
        eq("get me food from a place stays", PhoneTools.wantsToSee("get me a burger and fries from Rosie's"), true);
        eq("'in order to' isn't an order", PhoneTools.wantsToSee("turn on battery saver in order to save power"), false);
        eq("his own voice keeps going when Sprocket opens an app mid-question", PhoneTools.stopVoiceOnLeave(true, true), false);
        eq("he left on his own: quiet", PhoneTools.stopVoiceOnLeave(true, false), true);
        eq("he left after the answer: quiet", PhoneTools.stopVoiceOnLeave(false, true), true);
        // practice runs: tools that fail on purpose
        eq("faults: two tools", new java.util.TreeMap<>(PhoneTools.faults("send_text:refused; add_event:no_confirm")).toString(), "{add_event=no_confirm, send_text=refused}");
        eq("faults: unknown kinds are dropped", PhoneTools.faults("send_text:explode;call:refused").toString(), "{call=refused}");
        eq("faults: none", PhoneTools.faults(null).isEmpty(), true);
        // a text with no confirmation said "the text did not go", though it often did; Sprocket then offered to
        // send it again (a double text). Unconfirmed is its own answer: it may have gone, don't resend, ask them to check.
        String unconfirmed = PhoneTools.unconfirmed("Mom");
        eq("unconfirmed: may have gone", unconfirmed.contains("may have gone"), true);
        eq("unconfirmed: never 'did not go'", unconfirmed.contains("did not go"), false);
        eq("unconfirmed: don't send it again", unconfirmed.contains("don't send it again"), true);
        eq("the practice fault says the same", PhoneTools.FAULT_NO_CONFIRM, PhoneTools.unconfirmed("them"));
        // a contact with work, cell and home numbers. "at work" / "'s cell" picks that one; a text
        // goes only to a cell (no cell: say which numbers there are and ask); a call with no label: the cell first.
        eq("label: at work", String.join("|", PhoneTools.splitLabel("Mom at work")), "Mom|work");
        eq("label: 's cell number", String.join("|", PhoneTools.splitLabel("Sam's cell number")), "Sam|mobile");
        eq("label: work number for", String.join("|", PhoneTools.splitLabel("the work number for Grandpa")), "Grandpa|work");
        eq("label: home phone", String.join("|", PhoneTools.splitLabel("Grandpa's home phone")), "Grandpa|home");
        eq("label: none", String.join("|", PhoneTools.splitLabel("Mom")), "Mom|");
        eq("label: a name that just ends in Home stays", String.join("|", PhoneTools.splitLabel("Sweet Home")), "Sweet Home|");
        java.util.List<String[]> mom = java.util.Arrays.asList(new String[]{"+12025550131", "work"}, new String[]{"+12025550111", "mobile"},
                new String[]{"+12025550141", "home"});
        java.util.List<String[]> grandpa = java.util.Arrays.asList(new String[]{"+12025550151", "home"}, new String[]{"+12025550161", "work"});
        try {
            eq("text Mom: her cell", PhoneTools.pickNumber("Mom", mom, "", true), "+12025550111");
            eq("text Mom at work: her work", PhoneTools.pickNumber("Mom", mom, "work", true), "+12025550131");
            eq("call Mom: her cell", PhoneTools.pickNumber("Mom", mom, "", false), "+12025550111");
            eq("call Mom at home: home", PhoneTools.pickNumber("Mom", mom, "home", false), "+12025550141");
            eq("call Grandpa (no cell): the first", PhoneTools.pickNumber("Grandpa", grandpa, "", false), "+12025550151");
        } catch (Exception e) { eq("number picking threw", e.getMessage(), ""); }
        String why = "";
        try { PhoneTools.pickNumber("Grandpa", grandpa, "", true); } catch (Exception e) { why = e.getMessage(); }
        eq("text Grandpa (no cell): ask, naming their numbers", why.contains("no cell") && why.contains("home") && why.contains("work") && why.contains("Ask the boss"), true);
        why = "";
        try { PhoneTools.pickNumber("Grandpa", grandpa, "mobile", false); } catch (Exception e) { why = e.getMessage(); }
        eq("call Grandpa's cell (none): say so", why.contains("no cell number"), true);
        eq("is the store open comes back", PhoneTools.wantsToSee("is the hardware store open today?"), false);
        eq("can you open my photos stays", PhoneTools.wantsToSee("can you open my photos"), true);
        eq("then open Maps stays", PhoneTools.wantsToSee("find a gas station and then open Maps"), true);
        eq("a new sentence opening with open stays", PhoneTools.wantsToSee("It's late. Open the alarm app"), true);
        // the screen tool can switch Sprocket's own screen control off when the user asks
        eq("switch_off is a screen action", PhoneTools.definitions().toString().contains("\"switch_off\""), true);

        eq("Sprocket can open its own screen-control switch (11-14)", PhoneTools.screen("screen_control_settings") != null, true);
        eq("and the brain is told when to open it (11-14: it said it couldn't)", PhoneTools.definitions().toString().contains("When they ask to turn screen control (back) on, use screen_control_settings"), true);

        System.out.println(fails == 0 ? "phone tools: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static String pick(PhoneTools.Pick p) {
        return p.name != null ? p.name : p.choices.isEmpty() ? "none" : "ask " + p.choices;
    }

    static String desc(JSONArray defs, String name) throws Exception {
        for (int i = 0; i < defs.length(); i++)
            if (defs.getJSONObject(i).getString("name").equals(name)) return defs.getJSONObject(i).getString("description");
        return "";
    }

    static String req(JSONArray defs, String name) throws Exception {
        for (int i = 0; i < defs.length(); i++)
            if (defs.getJSONObject(i).getString("name").equals(name))
                return defs.getJSONObject(i).getJSONObject("input_schema").getJSONArray("required").toString();
        return "missing";
    }
}
