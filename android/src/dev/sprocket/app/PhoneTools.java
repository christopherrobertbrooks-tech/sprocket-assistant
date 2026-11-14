package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Phase 3 "hands" (the owner 2026-10-17: the everyday set first; texts and calls on a card, sent by Sprocket after his
 * tap): the phone tools Claude can use, and the plain decisions behind them. Phone.java does them on the phone.
 * Plain Java: test/PhoneToolsTest.java.
 */
final class PhoneTools {
    private PhoneTools() { }

    private static JSONObject str(String what) throws Exception { return new JSONObject().put("type", "string").put("description", what); }

    private static JSONObject tool(String name, String what, JSONObject props, String... required) throws Exception {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", props);
        if (required.length > 0) {
            JSONArray r = new JSONArray();
            for (String x : required) r.put(x);
            schema.put("required", r);
        }
        return new JSONObject().put("name", name).put("description", what).put("input_schema", schema);
    }

    /** His spelling is the one to look up: the phone finds the contact it sounds like if the spelling differs. */
    private static final String WHO = "A contact's name exactly as he wrote or said it (\"Mom\", \"Toni\"; never "
            + "re-spelled), or a phone number.";

    static final String[] DAY_NAMES = {"sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"};

    /** The tools, in a fixed order (they are part of the cached prompt: the same bytes every time). */
    static JSONArray definitions() throws Exception {
        JSONArray days = new JSONArray();
        for (String d : DAY_NAMES) days.put(d);
        return new JSONArray()
                .put(tool("set_timer", "Start a countdown timer in the phone's Clock app.",
                        new JSONObject().put("seconds", new JSONObject().put("type", "integer").put("description", "Length in seconds."))
                                .put("label", str("Optional name, e.g. \"pasta\".")), "seconds"))
                .put(tool("set_alarm", "Set an alarm in the phone's Clock app.",
                        new JSONObject().put("time", str("24-hour HH:MM, e.g. \"06:30\" or \"18:00\"."))
                                .put("label", str("Optional name."))
                                .put("repeat_days", new JSONObject().put("type", "array").put("items",
                                        new JSONObject().put("type", "string").put("enum", days))
                                        .put("description", "Days it repeats on; leave out for a one-time alarm.")), "time"))
                .put(tool("set_reminder", "Remind the boss of something at a date and time: within 90 minutes it is a labelled "
                                + "timer, within a day a labelled alarm, later a calendar entry with an alert.",
                        new JSONObject().put("text", str("What to remind him of, e.g. \"Call Mom\"."))
                                .put("at", str("Local date and time, YYYY-MM-DDTHH:MM.")), "text", "at"))
                .put(tool("send_text", "Send a text message (SMS) from the boss's number. He sees a card with the recipient "
                                + "and the exact words and must tap Send; the result says whether it was sent or he cancelled. "
                                + "Write the message as he would, plainly: never in Sprocket's voice unless he asks.",
                        new JSONObject().put("to", str(WHO)).put("message", str("The exact text to send.")), "to", "message"))
                .put(tool("call", "Phone someone. He sees a card and must tap Call; the result says whether the call was "
                                + "placed or he cancelled.",
                        new JSONObject().put("to", str(WHO)), "to"))
                .put(tool("find_contact", "Look up contacts by name: their names and phone numbers.",
                        new JSONObject().put("name", str("A name or part of one, exactly as he wrote or said it.")), "name"))
                .put(tool("check_calendar", "List the boss's calendar events between two dates (inclusive).",
                        new JSONObject().put("from", str("YYYY-MM-DD.")).put("to", str("YYYY-MM-DD; leave out for one day.")), "from"))
                .put(tool("add_event", "Add an event to the boss's calendar.",
                        new JSONObject().put("title", str("What it is."))
                                .put("start", str("YYYY-MM-DDTHH:MM, or YYYY-MM-DD for an all-day event."))
                                .put("minutes", new JSONObject().put("type", "integer").put("description", "Length; default 60."))
                                .put("location", str("Optional place.")), "title", "start"))
                .put(tool("open_app", "Open an app on the phone by its name.",
                        new JSONObject().put("name", str("The app's name, e.g. \"Spotify\".")), "name"))
                .put(tool("phone_control", "The flashlight, the media volume, the screen brightness, Do Not Disturb or the ringer.",
                        new JSONObject().put("setting", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("flashlight").put("volume").put("brightness").put("do_not_disturb").put("ringer")))
                                .put("on", new JSONObject().put("type", "boolean").put("description", "For flashlight and do_not_disturb."))
                                .put("percent", new JSONObject().put("type", "integer").put("description", "For volume or brightness, 0-100."))
                                .put("direction", new JSONObject().put("type", "string").put("enum", new JSONArray().put("up").put("down"))
                                        .put("description", "For volume or brightness: a step up or down from where it is, instead of a percent."))
                                .put("auto", new JSONObject().put("type", "boolean").put("description", "For brightness: true = automatic."))
                                .put("mode", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("normal").put("vibrate").put("silent")).put("description", "For ringer.")),
                        "setting"))
                .put(tool("phone_status", "The phone's battery level and charging, its next alarm, and free storage.",
                        new JSONObject()))
                .put(tool("media", "Control what is playing (music, podcasts, videos): play, pause, next, previous; or play "
                                + "something by name in his music app. The result says whether anything is playing afterwards.",
                        new JSONObject().put("command", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("play").put("pause").put("next").put("previous")))
                                .put("query", str("With play: a song, artist, album, playlist or podcast to play by name. Leave "
                                        + "out to resume.")), "command"))
                .put(tool("maps", "Google Maps on the phone: directions to a place (it starts navigating), or places of a kind "
                                + "near him.",
                        new JSONObject().put("action", new JSONObject().put("type", "string").put("enum", new JSONArray().put("directions").put("nearby")))
                                .put("place", str("Where to, or what kind of place (\"gas station\", \"coffee\")."))
                                .put("mode", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("drive").put("walk").put("bike").put("transit")).put("description", "For directions; default drive.")),
                        "action", "place"))
                .put(tool("open_screen", "Open a part of the phone for him: the camera, his photos, the voice recorder, the "
                                + "Clock's timers or alarms, a settings page, or the home screen (that is how to get him out of "
                                + "an app: Sprocket can't close apps). It only opens it; it changes nothing there. Use it before the "
                                + "screen tool for any settings page listed here; the software version is in about_phone_settings "
                                + "> Software information.",
                        new JSONObject().put("screen", new JSONObject().put("type", "string").put("enum", new JSONArray(SCREENS.keySet()))), "screen"))
                .put(tool("clock_control", "Cancel alarms in the Clock app (by time, by name, the next one, or all), snooze a "
                                + "ringing alarm, or stop a ringing timer. The Clock app may show the alarm for him to confirm.",
                        new JSONObject().put("action", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("cancel_alarm").put("snooze_alarm").put("stop_timer")))
                                .put("time", str("For cancel_alarm: the alarm's time, 24-hour HH:MM."))
                                .put("label", str("For cancel_alarm: the alarm's name."))
                                .put("all", new JSONObject().put("type", "boolean").put("description", "For cancel_alarm: every alarm.")),
                        "action"))
                .put(tool("missed_calls", "His missed calls from the last week: who and when, newest first.", new JSONObject()))
                .put(tool("email", "Write an email in his email app, filled in, for him to check and send himself (Sprocket "
                                + "can't send email). Write it as he would.",
                        new JSONObject().put("to", str("A contact's name exactly as he wrote or said it, or an email address."))
                                .put("subject", str("The subject line.")).put("body", str("The email's text.")), "to"))
                .put(tool("add_contact", "Open a new contact, filled in, for him to check and save.",
                        new JSONObject().put("name", str("The person's name.")).put("number", str("Their phone number, if he gave one."))
                                .put("email", str("Their email address, if he gave one.")), "name"))
                .put(tool("share_location", "Text his current location (a Google Maps link) to someone, through the same Send card "
                                + "as any text.",
                        new JSONObject().put("to", str(WHO)), "to"))
                .put(tool("go_to", "Go to a place in an app the way Sprocket got there before: Sprocket does the taps itself, much "
                                + "faster than the screen tool. Name the place he asked for (\"Gmail spam folder\"). Try it FIRST "
                                + "for any place inside an app; if there's no saved way yet it says so, then use the screen tool "
                                + "(the way is saved for next time). It returns the screen it ended on; if it stops partway, carry "
                                + "on from there with the screen tool.",
                        new JSONObject().put("place", str("The place, in a few words (\"Gmail spam folder\").")), "place"))
                .put(tool("screen", "Use the phone's screen like a person, for apps and settings no other tool covers (use "
                                + "the other tools first). read: a numbered list of what's on screen. tap / type: an item number "
                                + "from the last read. scroll up/down, back, home. look: a screenshot, for screens the list "
                                + "can't describe (maps, pictures); then tap_point at x, y in that picture, saying what you're tapping "
                                + "(a spot the list can't read asks him first). Each action returns the screen after it. Never "
                                + "tell him something is on his screen unless you read it, or go_to returned it, in this "
                                + "question: while he asks, Sprocket's own screen is up. When you're done Sprocket comes back to its own screen by itself; if he asked to open or see "
                                + "something (photos, an app, a map), call stay last so it stays on screen. note: once you've reached what he "
                                + "asked for in an app, save ONE short fact for next time about where it was, as a route (\"Battery "
                                + "health: Battery and device care > Battery > Battery information\"); only facts you saw work, never "
                                + "personal details. Notes you saved come back with that app's screen: use them, and check they still "
                                + "match. Money and password apps are off limits. Pay, buy, delete, install, sign-out, "
                                + "send, post, call and answer buttons show him a card first, and only go ahead if he taps it.",
                        new JSONObject().put("action", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("read").put("tap").put("type").put("scroll").put("back").put("home")
                                                .put("look").put("tap_point").put("stay").put("note")))
                                .put("item", new JSONObject().put("type", "integer").put("description", "For tap and type: the item's number."))
                                .put("text", str("For type: what to type. For note: the fact to save."))
                                .put("direction", new JSONObject().put("type", "string").put("enum", new JSONArray().put("up").put("down"))
                                        .put("description", "For scroll; default down."))
                                .put("x", new JSONObject().put("type", "integer").put("description", "For tap_point: pixels from the left of the screenshot."))
                                .put("y", new JSONObject().put("type", "integer").put("description", "For tap_point: pixels from the top of the screenshot."))
                                .put("what", str("For tap_point: what you're tapping, as it looks in the picture (\"the Install button\").")),
                        "action"));
    }

    /** He asked to see or open something ("show me my photos", "open Maps"): after the hands, stay on it. */
    static boolean wantsToSee(String said) {
        String t = " " + (said == null ? "" : said.toLowerCase(java.util.Locale.US)) + " ";
        for (String w : new String[]{" show me ", " show my ", " pull up ", " take me to ", " let me see ", " bring up ", " go to "})
            if (t.contains(w)) return true;
        // "open" as an ask ("open Firefox", "can you open my photos"), not "how many open tabs" (Codex 041 #5)
        return t.matches("(?s)(\\s*|.*([.,!?;:]\\s*|\\b(and|then|please|pls|you|just|now|also|go|to|quickly)\\s+))open\\b.*");
    }

    /** Other names people use for an app, by its name on the phone (normalised: lowercase letters and digits). */
    private static final java.util.Map<String, String> APP_ALIASES = new java.util.HashMap<>();
    static {
        APP_ALIASES.put("youtubemusic", "ytmusic");   // YT Music's old name, and what Qwen calls it
        APP_ALIASES.put("ytmusic", "ytmusic");
        APP_ALIASES.put("googlemessages", "messages");
        APP_ALIASES.put("googlemaps", "maps");
    }

    static String appKey(String name) { return name == null ? "" : name.toLowerCase(java.util.Locale.US).replaceAll("[^a-z0-9]", ""); }

    /**
     * Which app he means, among the phone's app names: an exact name first (after aliases), then a name that contains
     * what he said (the shortest such), then the longest name contained in what he said ("YouTube Music" is not
     * "YouTube"). -1 when nothing fits.
     */
    static int pickApp(String asked, List<String> labels) {
        String want = appKey(asked);
        if (want.isEmpty()) return -1;
        String alias = APP_ALIASES.getOrDefault(want, want);
        int best = -1, bestScore = 0, bestLen = 0;
        for (int i = 0; i < labels.size(); i++) {
            String l = appKey(labels.get(i));
            if (l.length() <= 2) continue;
            int score, len = l.length();
            if (l.equals(want) || l.equals(alias)) score = 4;
            else if (l.contains(want)) score = 3;
            else if (want.contains(l)) score = 2;
            else continue;
            boolean better = score > bestScore
                    || (score == bestScore && score == 3 && len < bestLen)    // the closest name containing it
                    || (score == bestScore && score == 2 && len > bestLen);   // the most specific name inside it
            if (better) { best = i; bestScore = score; bestLen = len; }
        }
        return best;
    }

    /** The screens open_screen knows: {intent action, what he sees}. Settings pages only open; nothing changes. */
    static final java.util.Map<String, String[]> SCREENS = new java.util.LinkedHashMap<>();
    static {
        String[][] all = {
                {"camera", "android.media.action.STILL_IMAGE_CAMERA", "the camera"},
                {"selfie_camera", "android.media.action.STILL_IMAGE_CAMERA", "the selfie camera"},
                {"video_camera", "android.media.action.VIDEO_CAMERA", "the video camera"},
                {"photos", "android.intent.action.MAIN", "his photos"},
                {"voice_recorder", "android.provider.MediaStore.RECORD_SOUND", "the voice recorder"},
                {"timers", "android.intent.action.SHOW_TIMERS", "the Clock's timers"},
                {"alarms", "android.intent.action.SHOW_ALARMS", "the Clock's alarms"},
                {"wifi_settings", "android.settings.WIFI_SETTINGS", "the Wi-Fi settings"},
                {"bluetooth_settings", "android.settings.BLUETOOTH_SETTINGS", "the Bluetooth settings"},
                {"airplane_mode_settings", "android.settings.AIRPLANE_MODE_SETTINGS", "the airplane mode settings"},
                {"mobile_data_settings", "android.settings.DATA_ROAMING_SETTINGS", "the mobile data settings"},
                {"hotspot_settings", "android.settings.WIRELESS_SETTINGS", "the connections settings (the hotspot is there)"},
                {"location_settings", "android.settings.LOCATION_SOURCE_SETTINGS", "the location settings"},
                {"battery_settings", "android.intent.action.POWER_USAGE_SUMMARY", "the battery settings"},
                {"display_settings", "android.settings.DISPLAY_SETTINGS", "the display settings"},
                {"sound_settings", "android.settings.SOUND_SETTINGS", "the sound settings"},
                {"notification_settings", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "the notification settings"},
                {"storage_settings", "android.settings.INTERNAL_STORAGE_SETTINGS", "the storage settings"},
                // asked for by name: opened straight away instead of found by scrolling (11-12: ten screen steps to About phone)
                {"about_phone_settings", "android.settings.DEVICE_INFO_SETTINGS", "About phone (model, phone number; Software information is in it)"},
                {"apps_settings", "android.settings.MANAGE_APPLICATIONS_SETTINGS", "the list of apps"},
                {"default_apps_settings", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", "the default apps settings"},
                {"accessibility_settings", "android.settings.ACCESSIBILITY_SETTINGS", "the accessibility settings"},
                {"security_settings", "android.settings.SECURITY_SETTINGS", "the security and privacy settings"},
                {"privacy_settings", "android.settings.PRIVACY_SETTINGS", "the privacy settings"},
                {"date_time_settings", "android.settings.DATE_SETTINGS", "the date and time settings"},
                {"language_settings", "android.settings.LOCALE_SETTINGS", "the language settings"},
                {"keyboard_settings", "android.settings.INPUT_METHOD_SETTINGS", "the keyboard settings"},
                {"developer_options_settings", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "the developer options"},
                {"vpn_settings", "android.settings.VPN_SETTINGS", "the VPN settings"},
                {"nfc_settings", "android.settings.NFC_SETTINGS", "the NFC settings"},
                {"battery_saver_settings", "android.settings.BATTERY_SAVER_SETTINGS", "the power saving settings"},
                {"dark_mode_settings", "android.settings.DARK_THEME_SETTINGS", "the dark mode settings"},
                {"eye_comfort_settings", "android.settings.NIGHT_DISPLAY_SETTINGS", "the eye comfort (night light) settings"},
                {"do_not_disturb_settings", "android.settings.ZEN_MODE_PRIORITY_SETTINGS", "the Do not disturb settings"},
                {"accounts_settings", "android.settings.SYNC_SETTINGS", "the accounts settings"},
                {"cast_settings", "android.settings.CAST_SETTINGS", "the screen casting settings"},
                {"data_usage_settings", "android.settings.DATA_USAGE_SETTINGS", "the data usage settings"},
                {"mobile_network_settings", "android.settings.NETWORK_OPERATOR_SETTINGS", "the mobile network settings"},
                {"all_settings", "android.settings.SETTINGS", "the settings"},
                {"home_screen", "android.intent.action.MAIN", "the home screen"},
        };
        for (String[] x : all) SCREENS.put(x[0], new String[]{x[1], x[2]});
    }

    /** {intent action, what he sees} for a screen, or null. */
    static String[] screen(String name) { return SCREENS.get(name); }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return s; }
    }

    /** What Maps is asked: navigation by car, on foot or by bike; transit by the web link (navigation has none); a
     * search near him. */
    static String mapsUri(String action, String place, String mode) {
        if (action.equals("nearby")) return "geo:0,0?q=" + enc(place);
        if ("transit".equals(mode)) return "https://www.google.com/maps/dir/?api=1&destination=" + enc(place) + "&travelmode=transit";
        String m = "walk".equals(mode) ? "w" : "bike".equals(mode) ? "b" : "d";
        return "google.navigation:q=" + enc(place) + "&mode=" + m;
    }

    /** Brightness: his percent to the phone's 1-255 (0 would be a black screen), and back. */
    static int brightness(int percent) { return Math.max(1, Math.min(255, Math.round(Math.max(0, percent) * 2.55f))); }

    static int percent(int level) { return Math.round(Math.max(0, Math.min(255, level)) / 2.55f); }

    /** A step up or down (15 points), kept within 0-100. */
    static int step(int percent, String direction) {
        return Math.max(0, Math.min(100, percent + ("down".equals(direction) ? -15 : 15)));
    }

    /** The Clock's alarm search mode (AlarmClock.ALARM_SEARCH_MODE_*): by time, by name, all, or the next one. */
    static String alarmSearch(String time, String label, boolean all) {
        if (all) return "android.all";
        if (hhmm(time) != null) return "android.time";
        if (label != null && !label.trim().isEmpty()) return "android.label";
        return "android.next";
    }

    static boolean isEmail(String to) { return to != null && to.trim().matches("[^@\\s]+@[^@\\s]+\\.[a-zA-Z]{2,}"); }

    /** His location as a Maps link (5 decimals: about a metre). */
    static String mapsLink(double lat, double lng) { return String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", lat, lng); }

    /** Claude's day names -> the Clock's day numbers (Calendar.SUNDAY = 1 .. SATURDAY = 7), sorted, once each. */
    static List<Integer> days(JSONArray names) {
        List<Integer> out = new ArrayList<>();
        if (names == null) return out;
        for (int i = 0; i < names.length(); i++) {
            String d = names.optString(i, "").toLowerCase(Locale.US).trim();
            for (int k = 0; k < 7; k++) if (DAY_NAMES[k].equals(d) && !out.contains(k + 1)) out.add(k + 1);
        }
        Collections.sort(out);
        return out;
    }

    /** "18:05" -> {18, 5}; anything else -> null. */
    static int[] hhmm(String t) {
        if (t == null || !t.trim().matches("\\d{1,2}:\\d{2}")) return null;
        String[] p = t.trim().split(":");
        int h = Integer.parseInt(p[0]), m = Integer.parseInt(p[1]);
        return h < 24 && m < 60 ? new int[]{h, m} : null;
    }

    /** "YYYY-MM-DDTHH:MM" (or a bare date: its midnight) in the phone's time zone -> epoch ms; -1 if it isn't one. */
    static long at(String s, ZoneId zone) {
        try {
            String t = s.trim();
            LocalDateTime d = t.length() <= 10 ? LocalDate.parse(t).atStartOfDay() : LocalDateTime.parse(t);
            return d.atZone(zone).toInstant().toEpochMilli();
        } catch (Exception e) {
            return -1L;
        }
    }

    /** How a reminder is kept: "timer" (within 90 min), "alarm" (within a day: the Clock's alarms are times of day),
     * "calendar" (later), or "past". */
    static String route(long now, long when) {
        long ahead = when - now;
        if (ahead <= 0) return "past";
        if (ahead <= 90 * 60 * 1000L) return "timer";
        if (ahead < 24 * 3600 * 1000L) return "alarm";
        return "calendar";
    }

    /** The digits of a phone number he said or typed (a leading + kept); null if it's a name. */
    static String number(String to) {
        if (to == null || !to.trim().matches("\\+?[0-9 ().-]{3,}")) return null;
        return to.trim().replaceAll("[^0-9+]", "");
    }

    /** Who he means: one contact (name), several to ask him about (choices), or nobody. */
    static final class Pick {
        String name;
        final List<String> choices = new ArrayList<>();
    }

    /**
     * Who "to" is: among the contacts whose names start with it (spelled), the exact one, or the only one, else ask;
     * with none spelled that way, the one it SOUNDS like among all his contacts (NameMatch, the earlier test app's), or the
     * close ones to ask about (the owner 2026-10-17: "Toni" came through as "Tony" and wasn't found).
     */
    static Pick resolve(String to, java.util.Collection<String> spelled, List<String> everyone) {
        Pick p = new Pick();
        String want = to.replaceFirst("(?i)^(my|the)\\s+", "").trim();
        if (!spelled.isEmpty()) {
            for (String k : spelled) if (k.equalsIgnoreCase(want)) p.name = k;
            if (p.name == null && spelled.size() == 1) p.name = spelled.iterator().next();
            if (p.name == null) for (String k : spelled) if (p.choices.size() < 6) p.choices.add(k);
            return p;
        }
        NameMatch.Result m = NameMatch.match(want, everyone);
        p.name = m.unique;
        if (p.name == null) p.choices.addAll(m.choices);
        return p;
    }

    static String textCard(String name, String number, String message) {
        return "Text " + name + (name.equals(number) ? "" : " (" + number + ")") + "\n“" + message + "”";
    }

    static String callCard(String name, String number) {
        return "Call " + name + (name.equals(number) ? "" : " (" + number + ")");
    }

    /** A card said out loud (no numbers), with what to say back: "Text Toni: “...” Say send, or cancel." */
    static String spoken(String card, String yes) {
        String s = Chat.unnumbered(card.replace("\n", ": ")).trim();
        if (!s.endsWith("?") && !s.endsWith("\u201d") && !s.endsWith(".")) s += "?";
        return s + " Say " + yes.toLowerCase(Locale.US) + ", or cancel.";
    }

    private static final String[] NO = {"cancel", "no", "nope", "don't", "do not", "dont", "stop", "never mind", "nevermind",
            "wait", "hold on", "not"};
    private static final String[] YES = {"yes", "yeah", "yep", "yup", "sure", "ok", "okay", "go", "go ahead", "do it", "confirm"};

    /** His spoken answer to a card: true = do it, false = don't, null = not clear (the card stays for a tap). Any
     * no-word wins ("no, send it" cancels): a wrong cancel costs a repeat, a wrong send can't be taken back. */
    static Boolean answer(String heard, String yes) {
        String h = " " + (heard == null ? "" : heard).toLowerCase(Locale.US).replace('\u2019', '\'')
                .replaceAll("[^a-z' ]", " ").replaceAll("\\s+", " ").trim() + " ";
        if (h.trim().isEmpty()) return null;
        String y = yes.toLowerCase(Locale.US);
        for (String w : NO) if (!w.equals(y) && h.contains(" " + w + " ")) return false;   // "stop" is a yes on a Stop card
        if (h.contains(" " + y + " ") || (y.equals("send") && h.contains(" sent "))) return true;   // "sent": a common mishearing
        for (String w : YES) if (h.contains(" " + w + " ")) return true;
        return null;
    }
}
