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
    private static final String WHO = "A contact's name exactly as they wrote or said it (\"Mom\", \"Toni\"; never "
            + "re-spelled), or a phone number. Keep a number they named with it: \"Mom at work\", \"Sam's cell\".";

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
                .put(tool("set_reminder", "Remind the boss of something at a date and time: a Sprocket notification pops up "
                                + "then (no alarm rings). Use it for \"remind me\"; set_alarm only when they ask for an alarm.",
                        new JSONObject().put("text", str("What to remind them of, in their words, e.g. \"Call Mom\" or \"Drugstore\"."))
                                .put("at", str("Local date and time, YYYY-MM-DDTHH:MM.")), "text", "at"))
                .put(tool("reminders", "List the reminders waiting, or cancel one by its number from the list.",
                        new JSONObject().put("cancel", new JSONObject().put("type", "integer")
                                .put("description", "The reminder's number to cancel; leave out to list them."))))
                .put(tool("send_text", "Send a text message (SMS) from the boss's number. They see a card with the recipient "
                                + "and the exact words and must tap Send; the result says whether it was sent or they cancelled. "
                                + "Write the message as they would, plainly: never in Sprocket's voice unless they ask.",
                        new JSONObject().put("to", str(WHO)).put("message", str("The exact text to send.")), "to", "message"))
                .put(tool("call", "Phone someone. They see a card and must tap Call; the result says whether the call was "
                                + "placed or they cancelled.",
                        new JSONObject().put("to", str(WHO)), "to"))
                .put(tool("find_contact", "Look up contacts by name: their names and phone numbers.",
                        new JSONObject().put("name", str("A name or part of one, exactly as they wrote or said it.")), "name"))
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
                                + "something by name in their music app. The result says whether anything is playing afterwards.",
                        new JSONObject().put("command", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("play").put("pause").put("next").put("previous")))
                                .put("query", str("With play: a song, artist, album, playlist or podcast to play by name. Leave "
                                        + "out to resume.")), "command"))
                .put(tool("maps", "Google Maps on the phone: directions to a place (it starts navigating), or places of a kind "
                                + "near them.",
                        new JSONObject().put("action", new JSONObject().put("type", "string").put("enum", new JSONArray().put("directions").put("nearby")))
                                .put("place", str("Where to, or what kind of place (\"gas station\", \"coffee\")."))
                                .put("mode", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("drive").put("walk").put("bike").put("transit")).put("description", "For directions; default drive.")),
                        "action", "place"))
                .put(tool("open_screen", "Open a part of the phone for them: the camera, their photos, the voice recorder, the "
                                + "Clock's timers or alarms, a settings page, or the home screen (that is how to get them out of "
                                + "an app: Sprocket can't close apps). It only opens it; it changes nothing there. Use it before the "
                                + "screen tool for any settings page listed here; the software version is in about_phone_settings "
                                + "> Software information. When they ask to turn screen control (back) on, use "
                                + "screen_control_settings: Sprocket switches it on itself when it can; otherwise it opens "
                                + "Accessibility and you tell them: Installed apps, then Sprocket: screen control, then the switch.",
                        new JSONObject().put("screen", new JSONObject().put("type", "string").put("enum", new JSONArray(SCREENS.keySet()))), "screen"))
                .put(tool("clock_control", "Cancel alarms in the Clock app (by time, by name, the next one, or all), snooze a "
                                + "ringing alarm, or stop a ringing timer. The Clock app may show the alarm for them to confirm.",
                        new JSONObject().put("action", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("cancel_alarm").put("snooze_alarm").put("stop_timer")))
                                .put("time", str("For cancel_alarm: the alarm's time, 24-hour HH:MM."))
                                .put("label", str("For cancel_alarm: the alarm's name."))
                                .put("all", new JSONObject().put("type", "boolean").put("description", "For cancel_alarm: every alarm.")),
                        "action"))
                .put(tool("missed_calls", "Their missed calls from the last week: who and when, newest first.", new JSONObject()))
                .put(tool("email", "Write an email in their email app, filled in, for them to check and send themselves (Sprocket "
                                + "can't send email). Write it as they would.",
                        new JSONObject().put("to", str("A contact's name exactly as they wrote or said it, or an email address."))
                                .put("subject", str("The subject line.")).put("body", str("The email's text.")), "to"))
                .put(tool("add_contact", "Open a new contact, filled in, for them to check and save.",
                        new JSONObject().put("name", str("The person's name.")).put("number", str("Their phone number, if they gave one."))
                                .put("email", str("Their email address, if they gave one.")), "name"))
                .put(tool("share_location", "Text their current location (a Google Maps link) to someone, through the same Send card "
                                + "as any text.",
                        new JSONObject().put("to", str(WHO)), "to"))
                .put(tool("go_to", "Go to a place in an app the way Sprocket got there before: Sprocket does the taps itself, much "
                                + "faster than the screen tool. Name the place they asked for (\"Gmail spam folder\"). Try it FIRST "
                                + "for any place inside an app; if there's no saved way yet it says so, then use the screen tool "
                                + "(the way is saved for next time). It returns the screen it ended on; if it stops partway, carry "
                                + "on from there with the screen tool.",
                        new JSONObject().put("place", str("The place, in a few words (\"Gmail spam folder\").")), "place"))
                .put(tool("screen", "Use the phone's screen like a person, for apps and settings no other tool covers (use "
                                + "the other tools first). read: a numbered list of what's on screen. tap / type: an item number "
                                + "from the last read; tap with times presses the same button again and again in one step (a "
                                + "quantity +, when the quantity isn't a box you can type in), up to 50. scroll up/down, back, home. find: looks through the whole list for an item with these words (it does the scrolling, top to bottom, in one step): use it to look for something in a long menu or list, and trust what it says. read_all: every row of a long list in one step (it scrolls top to bottom itself): use it to count, add up or compare things in a list (order history, spending, a long menu), never your own scrolling. For the cheapest or priciest thing, sort by price when the app can and read the top of the list instead. look: a screenshot, for screens the list "
                                + "can't describe (maps, pictures); then tap_point at x, y in that picture, saying what you're tapping "
                                + "(it asks them first only when it sounds risky). Each action returns the screen after it. Never "
                                + "tell them something is on their screen unless you read it, or go_to returned it, in this "
                                + "question: while they ask, Sprocket's own screen is up. When you're done Sprocket comes back to its own screen by itself; if they asked to open or see "
                                + "something (photos, an app, a map), call stay last so it stays on screen. note: once you've reached what they "
                                + "asked for in an app, save ONE short fact for next time about where it was, as a route (\"Battery "
                                + "health: Battery and device care > Battery > Battery information\"); only facts you saw work, never "
                                + "personal details. Notes you saved come back with that app's screen: use them, and check they still "
                                + "match. Banking, payment, wallet and password apps are off limits; shopping and food-delivery apps are fine "
                                + "(fill the cart, check it against what they asked, the checkout button asks them; taking an item out of the cart needs no card). Pay, buy, delete, install, sign-out, "
                                + "send, post, call and answer buttons show them a card first, and only go ahead if they tap it. switch_off: when "
                                + "they ask to turn screen control off, switches Sprocket's own screen control off (their card first); "
                                + "only they can turn it back on, in Settings or from the notice that follows.",
                        new JSONObject().put("action", new JSONObject().put("type", "string").put("enum",
                                        new JSONArray().put("read").put("tap").put("type").put("scroll").put("back").put("home")
                                                .put("look").put("tap_point").put("stay").put("note").put("switch_off").put("find").put("read_all")))
                                .put("item", new JSONObject().put("type", "integer").put("description", "For tap and type: the item's number."))
                                .put("times", new JSONObject().put("type", "integer").put("description", "For tap: press it this many times (default 1, at most 50)."))
                                .put("text", str("For type: what to type. For note: the fact to save. For find: the words to look for (\"pickle spear\")."))
                                .put("direction", new JSONObject().put("type", "string").put("enum", new JSONArray().put("up").put("down"))
                                        .put("description", "For scroll; default down."))
                                .put("x", new JSONObject().put("type", "integer").put("description", "For tap_point: pixels from the left of the screenshot."))
                                .put("y", new JSONObject().put("type", "integer").put("description", "For tap_point: pixels from the top of the screenshot."))
                                .put("what", str("For tap_point: what you're tapping, as it looks in the picture (\"the Install button\").")),
                        "action"));
    }

    /** He asked to see or open something ("show me my photos", "open Maps"): after the hands, stay on it. */
    /** A food ask (an order, a delivery app, "my usual"): it carries the food rules (Claude.FOOD_NOTE). */
    static boolean foodAsk(String said) {
        String t = " " + (said == null ? "" : said.toLowerCase(java.util.Locale.US)) + " ";
        return t.replaceAll("\\bin order to\\b", " ").matches("(?s).*\\b(order|orders|ordering|doordash|door dash|uber eats|ubereats|grubhub|"
                + "instacart|eats|cart|checkout|my usual|usual from|hungry|lunch|dinner|breakfast|takeout|take out|delivery|food|"
                + "eat|eating|safe for me|allergic|allergy|allergen|allergens|ingredients|menu|restaurant)\\b.*")
                || t.matches("(?s).*\\b(get|grab|bring) me\\b.*\\bfrom\\b.*");
    }

    static boolean wantsToSee(String said) {
        String t = " " + (said == null ? "" : said.toLowerCase(java.util.Locale.US)) + " ";
        for (String w : new String[]{" show me ", " show my ", " pull up ", " take me to ", " let me see ", " bring up ", " go to "})
            if (t.contains(w)) return true;
        // a food or shopping order (a food-order test: the cart was filled, then Sprocket jumped back to its own screen and
        // the owner couldn't check out): leave them on the cart
        String noInOrder = t.replaceAll("\\bin order to\\b", " ");
        if (noInOrder.matches("(?s).*\\b(order|orders|ordering|doordash|door dash|uber eats|ubereats|grubhub|instacart|eats|cart|checkout)\\b.*")
                || noInOrder.matches("(?s).*\\b(get|grab|bring) me\\b.*\\bfrom\\b.*")) return true;
        // "open X and tell me / read / check ..." opens it to get an answer, not to look at it (11-18: Sprocket said "I'm
        // not leaving it open" and then YouTube landed on his screen anyway)
        if (t.matches("(?s).*\\bopen\\b.*\\band\\s+(tell|read|check|find|say|count|look up|let me know|what|how|which|who|whether|if|is|are|does|do)\\b.*"))
            return false;
        // "open" as an ask ("open Firefox", "can you open my photos"), not "how many open tabs" (Codex 041 #5)
        return t.matches("(?s)(\\s*|.*([.,!?;:]\\s*|\\b(and|then|please|pls|you|just|now|also|go|to|quickly)\\s+))open\\b.*");
    }

    /**
     * The phone's settings as they really are, sent with every question (11-18: Sprocket said "it's already at 20, boss"
     * from the chat while the volume was at 0). volume/brightness in percent; ringer normal, vibrate or silent.
     */
    static String settingsLine(int volume, int brightness, boolean autoBrightness, boolean dnd, String ringer) {
        return "Right now on the phone (read just now; earlier messages may be out of date): media volume " + volume
                + "%, screen brightness " + brightness + "%" + (autoBrightness ? " (automatic)" : "") + ", Do Not Disturb "
                + (dnd ? "on" : "off") + ", ringer " + ringer + ".";
    }

    /**
     * What Background work says when it's on, plus a tip while USB debugging is off (11-18 test: wireless debugging stops
     * when Wi-Fi goes away and takes the hidden screen with it, but with USB debugging on it kept working on 5G). The
     * switch is his to flip; Sprocket never flips it.
     */
    static String bgOn(String said, boolean usbDebugging) {
        if (usbDebugging) return said;
        return said + " To keep it working when you leave Wi-Fi, also turn on USB debugging: Settings, Developer options, "
                + "USB debugging. (If you ever plug your phone into a computer you don't know, tap Deny when it asks.)";
    }

    /**
     * Added to an app-opening tool's answer while Sprocket works on the hidden screen, so its reply says what really
     * happens next (11-18: it said "I'm not leaving it open" and the app then moved onto his screen). hidden: working on
     * the hidden screen; shown: wantsToSee() says it moves onto his real screen when the answer is done.
     */
    static String whereItEnds(boolean hidden, boolean shown) {
        if (!hidden) return "";
        return shown ? " It's on Sprocket's hidden screen; when you finish answering it moves onto his real screen, because he "
                + "asked to see it, so it's left open for him. Don't tell him it was closed or hidden."
                : " It's on Sprocket's hidden screen, out of his sight, and stays there when you finish, so he won't see it "
                + "(the screen tool's stay puts it on his screen, if he wants it).";
    }

    /**
     * Whether Sprocket stops talking when its screen goes to the background (11-15: its first sentence was cut off
     * because the hands opening a delivery app looked like the owner leaving). Mid-question, after one of its own tools opened
     * another app, it keeps talking; any other time, he left: quiet.
     */
    static boolean stopVoiceOnLeave(boolean questionRunning, boolean toolOpenedAnApp) {
        return !(questionRunning && toolOpenedAnApp);
    }

    /** A practice run's tools that fail on purpose: "send_text:refused;add_event:no_confirm" -> {send_text: refused, ...}. */
    static java.util.Map<String, String> faults(String spec) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (spec == null) return out;
        for (String part : spec.split(";")) {
            String[] kv = part.trim().split(":");
            if (kv.length == 2 && (kv[1].trim().equals("refused") || kv[1].trim().equals("no_confirm"))) out.put(kv[0].trim(), kv[1].trim());
        }
        return out;
    }

    static final String FAULT_REFUSED = "the phone refused; it didn't go through";
    static final String FAULT_NO_CONFIRM = unconfirmed("them");

    /**
     * A text the phone never confirmed (this said "the text did not go", though such texts often do go, and
     * Sprocket offered to send it again: a double text). Its own answer, so the brain neither claims it nor resends it.
     */
    static String unconfirmed(String who) {
        return "the phone never confirmed the text to " + who + " went out (weak signal?), but it may have gone anyway: "
                + "don't send it again unless they say to, and tell them to check their messages";
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
                {"photos", "android.intent.action.MAIN", "their photos"},
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
                // straight to Sprocket's own switch when it can switch itself on
                {"screen_control_settings", "android.settings.ACCESSIBILITY_SETTINGS", "Sprocket's screen control: switched "
                        + "back on (or its Accessibility page)"},
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

    /** Opens an app on the hidden screen only when it's up, the app isn't Sprocket, and the intent has no extras: the
     * hidden screen's helper passes only the action, data and app, so extras (an alarm's time) would be lost. */
    static boolean viaHiddenScreen(boolean hiddenUp, boolean ownApp, boolean hasExtras) {
        return hiddenUp && !ownApp && !hasExtras;
    }

    /** When an alarm at h:m next rings from now: today if still ahead, else the next day it's on (Calendar days,
     * 1 = Sunday; none = every day is fine, a one-time alarm). */
    static long nextRing(long now, int h, int m, List<Integer> days, ZoneId z) {
        java.time.ZonedDateTime t = java.time.Instant.ofEpochMilli(now).atZone(z).toLocalDate().atTime(h, m).atZone(z);
        for (int i = 0; i < 9; i++) {
            java.time.ZonedDateTime d = t.toLocalDate().plusDays(i).atTime(h, m).atZone(z);
            int cal = d.getDayOfWeek().getValue() % 7 + 1;
            if (d.toInstant().toEpochMilli() > now && (days.isEmpty() || days.contains(cal))) return d.toInstant().toEpochMilli();
        }
        return -1;
    }

    /** Did the Clock app save the alarm? "yes": the phone's next alarm is it (to the minute); "unsure": an earlier alarm
     * comes first, so it can't be seen; "no": there's no alarm, or the next one is later than this one. */
    static String alarmTaken(long want, long next) {
        if (next >= 0 && Math.abs(next - want) < 60_000) return "yes";
        if (next >= 0 && next < want) return "unsure";
        return "no";
    }

    /** The digits of a phone number he said or typed (a leading + kept); null if it's a name. */
    /**
     * "Mom at work" -> {Mom, work}; "Sam's cell number" -> {Sam, mobile}; "the work number for Grandpa" -> {Grandpa,
     * work}; plain "Mom" -> {Mom, ""}. Labels: mobile, work, home (a contact with three numbers).
     */
    static String[] splitLabel(String to) {
        String t = to == null ? "" : to.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)^(?:the |my )?(cell|cellphone|mobile|work|office|home|house)\\s+(?:phone |number |line )?(?:number |phone )?(?:of|for)\\s+(.+)$").matcher(t);
        if (m.matches()) return new String[]{m.group(2).trim(), label(m.group(1))};
        m = java.util.regex.Pattern.compile(
                "(?i)^(.+?)(?:'s|s'|\u2019s)?\\s+(?:at\\s+|on (?:her|his|their)\\s+)?(cell|cellphone|mobile|work|office|home|house)(?:\\s+(?:phone|number|line|cell))?(?:\\s+number)?$").matcher(t);
        if (m.matches() && (t.matches("(?i).*('s|s'|\u2019s|\\bat|\\bon (her|his|their))\\s+\\w+.*") || t.matches("(?i).*\\s(phone|number|line)$")))
            return new String[]{m.group(1).trim(), label(m.group(2))};
        return new String[]{t, ""};
    }

    private static String label(String w) {
        w = w.toLowerCase(java.util.Locale.US);
        return w.startsWith("cell") || w.equals("mobile") ? "mobile" : w.equals("work") || w.equals("office") ? "work" : "home";
    }

    /**
     * Which of a contact's numbers ({number, label: mobile / work / home / other}, mobile first) to use. A label they
     * said wins; a text goes only to a cell (none: say which numbers there are and ask, since a home line can't take
     * a text); a call with no label takes the cell, else the first.
     */
    static String pickNumber(String name, java.util.List<String[]> nums, String wanted, boolean forText) throws Exception {
        java.util.List<String> have = new ArrayList<>();
        for (String[] n : nums) if (!have.contains(n[1])) have.add(n[1]);
        String kinds = String.join(" and ", have).replace("mobile", "cell");
        if (wanted != null && !wanted.isEmpty()) {
            for (String[] n : nums) if (n[1].equals(wanted)) return n[0];
            throw new Exception(name + " has no " + (wanted.equals("mobile") ? "cell" : wanted) + " number (only " + kinds + "). Ask the boss which");
        }
        for (String[] n : nums) if (n[1].equals("mobile")) return n[0];
        if (forText && nums.size() > 1)
            throw new Exception(name + " has no cell number, only " + kinds + ", and a text needs a cell. Ask the boss which to use");
        return nums.get(0)[0];
    }

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
