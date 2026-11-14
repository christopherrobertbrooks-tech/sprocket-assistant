package dev.sprocket.app;

/** App memory for screen control on the desktop JDK: short facts per app, read before tapping, learned after it worked. */
public class AppNotesTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) throws Exception {
        AppNotes notes = new AppNotes();
        String settings = "com.android.settings";

        // nothing noted: the screen read gets nothing extra
        eq("nothing noted yet", notes.forScreen(settings), "");

        // a fact learned after a task that worked, then handed back with every read of that app
        eq("learned", notes.learn(settings, "Settings", "  Battery health: Battery and device care > Battery > Battery information  ", 100),
                "Noted for Settings: Battery health: Battery and device care > Battery > Battery information");
        notes.learn(settings, "Settings", "Bluetooth is the second row on the first page", 200);
        eq("read with the screen, newest last", notes.forScreen(settings),
                "\nWhat you noted about Settings on earlier visits (check it still matches the screen):\n"
                        + "- Battery health: Battery and device care > Battery > Battery information\n"
                        + "- Bluetooth is the second row on the first page\n");
        eq("another app's screen gets none of it", notes.forScreen("com.google.android.youtube"), "");

        // never twice, never empty, never long
        eq("same fact again", notes.learn(settings, "Settings", "bluetooth is the SECOND row on the first page.", 300), "Already noted.");
        eq("nothing to note", notes.learn(settings, "Settings", "   ", 300), "There was nothing to note.");
        String longFact = "x".repeat(400);
        notes.learn("com.example", "Example", longFact, 300);
        eq("kept short", notes.apps.get(notes.apps.size() - 1).facts.get(0).text.length(), AppNotes.LONGEST);

        // a better way to the same thing replaces the old route (11-12: old scrolling routes kept steering it wrong)
        AppNotes better = new AppNotes();
        better.learn(settings, "Settings", "Software version: Settings > scroll down 3 > About phone > Software information", 1);
        better.learn(settings, "Settings", "Wi-Fi: open wifi_settings", 2);
        eq("replaced", better.learn(settings, "Settings", "software version: open about_phone_settings > tap Software information", 3),
                "Noted for Settings (replacing the old way): software version: open about_phone_settings > tap Software information");
        eq("one route per thing, newest last", better.forScreen(settings), "\nWhat you noted about Settings on earlier visits (check it "
                + "still matches the screen):\n- Wi-Fi: open wifi_settings\n- software version: open about_phone_settings > tap Software information\n");

        // nothing private: long numbers, email addresses, passwords and codes are refused
        eq("a phone number", notes.learn(settings, "Settings", "Mom's number is 5551234567", 400),
                "Not noted: app notes are about where things are, not numbers, addresses, passwords or codes.");
        eq("an email address", notes.learn(settings, "Settings", "account is bob@example.com", 400).startsWith("Not noted"), true);
        eq("a password", notes.learn(settings, "Settings", "the wifi password is hunter2", 400).startsWith("Not noted"), true);
        eq("a short number is fine (a menu position)", notes.learn(settings, "Settings", "Display is about 12 rows down", 400).startsWith("Noted"), true);

        // money and password apps are off limits to the hands, and to notes about them
        eq("a blocked app", notes.learn("com.chase.sig.android", "Chase", "Transfers are under Pay", 500),
                "Not noted: that app is off limits to screen control.");

        // the oldest go once an app has MAX_FACTS
        AppNotes full = new AppNotes();
        for (int i = 0; i < AppNotes.MAX_FACTS + 3; i++) full.learn("pkg.a", "A", "fact number " + (char) ('a' + i), i);
        eq("at most MAX_FACTS per app", full.apps.get(0).facts.size(), AppNotes.MAX_FACTS);
        eq("the oldest went", full.apps.get(0).facts.get(0).text, "fact number d");

        // the least recently used app goes once there are MAX_APPS
        AppNotes many = new AppNotes();
        for (int i = 0; i < AppNotes.MAX_APPS + 2; i++) many.learn("pkg." + i, "App " + i, "where things are", i);
        eq("at most MAX_APPS", many.apps.size(), AppNotes.MAX_APPS);
        eq("the least recently used went", many.apps.get(0).pkg, "pkg.2");

        // deleted from the Memory screen, by its id
        AppNotes.App set = null;
        for (AppNotes.App x : notes.apps) if (x.pkg.equals(settings)) set = x;
        long id = set.facts.get(0).id;
        eq("deleted", notes.delete(settings, id) + " " + notes.forScreen(settings).contains("Battery health"), "true false");
        eq("deleting what's gone", notes.delete(settings, id), false);

        // what the Memory screen lists
        eq("listed for the Memory screen", notes.listing().contains("Settings: Bluetooth is the second row on the first page"), true);

        // saved and loaded
        AppNotes back = AppNotes.fromJson(notes.toJson());
        eq("saved and loaded", back.forScreen(settings), notes.forScreen(settings));
        eq("a damaged file: empty", AppNotes.fromJson("{nope").apps.size(), 0);

        // ------------------------------------------------------------------ replay (11-12: a note can't shorten a route
        // that's already shortest; replaying it without a brain round trip per step can)
        java.util.List<AppNotes.Step> trail = java.util.Arrays.asList(
                AppNotes.Step.open("open_app", "Gmail"),
                AppNotes.Step.tap("Gmail", "Inbox, 12 unread"),
                AppNotes.Step.back(),
                AppNotes.Step.tap("Gmail", "Open navigation drawer"),
                AppNotes.Step.scroll("Gmail", "down"),
                AppNotes.Step.tap("Gmail", "Spam, 246"));
        eq("steps as the brain reads them", trail.get(3) + " | " + trail.get(0) + " | " + trail.get(4),
                "In Gmail: tapped \"Open navigation drawer\" | opened the app Gmail | In Gmail: scrolled down");
        String pick = AppNotes.routePrompt("open my gmail spam folder", trail, "Screen of Gmail:\n[1] text \"Spam\"", "There it is.");
        eq("the route question numbers the steps and asks for a name and the step numbers", pick.contains("4. In Gmail: tapped \"Open navigation drawer\"")
                && pick.contains("NONE") && pick.contains("|"), true);
        AppNotes.Choice ch = AppNotes.choice("Gmail spam folder | 1, 4, 5, 6", trail.size());
        eq("the choice: a name and the steps, wrong turns left out", ch.goal + " " + ch.steps, "Gmail spam folder [1, 4, 5, 6]");
        eq("NONE", AppNotes.choice("NONE", 6), null);
        eq("a step that isn't there", AppNotes.choice("x | 1, 9", 6), null);
        eq("no steps", AppNotes.choice("x |", 6), null);
        eq("its thinking left out, quotes too", AppNotes.choice("<think>ok</think>\"Spam | 1,2\"", 6).goal, "Spam");

        AppNotes r = new AppNotes();
        eq("route saved", r.learnRoute("com.google.android.gm", "Gmail", ch.goal, AppNotes.pick(trail, ch.steps), 10),
                "Noted for Gmail: Gmail spam folder: open the app Gmail > tap \"Open navigation drawer\" > scroll down > tap \"Spam, 246\"");
        eq("shown with the app's screen", r.forScreen("com.google.android.gm").contains("- Gmail spam folder: open the app Gmail > tap"), true);
        eq("listed for the Memory screen", r.listing().get(0).startsWith("Gmail: Gmail spam folder:"), true);

        // found by what he asks, from any screen
        eq("found by his words", r.find("open my spam folder in gmail").goal, "Gmail spam folder");
        eq("found with a few words", r.find("gmail spam").goal, "Gmail spam folder");
        eq("not found for something else", r.find("open my youtube downloads"), null);
        eq("not found for one shared word", r.find("open gmail"), null);

        // the same goal again replaces the old way; private text in a typed step refuses the route
        r.learnRoute("com.google.android.gm", "Gmail", "gmail spam folder", java.util.Arrays.asList(AppNotes.Step.open("open_app", "Gmail"),
                AppNotes.Step.tap("Gmail", "Spam")), 11);
        eq("one route per goal", r.listing().size() + " " + r.find("gmail spam").steps.size(), "1 2");
        eq("a private typed step refuses it", r.learnRoute("com.x", "X", "write to mom", java.util.Arrays.asList(AppNotes.Step.type("X", "To",
                "mom@example.com")), 12).startsWith("Not noted"), true);

        // a button is the same button when only its counts changed ("Spam, 246" then "Spam, 250")
        eq("same label, new count", AppNotes.sameLabel("Spam, 246", "Spam, 250"), true);
        eq("same label, other case and spacing", AppNotes.sameLabel("Open navigation drawer", " open  Navigation drawer "), true);
        eq("a longer label holding all its words", AppNotes.sameLabel("Spam", "Spam, 12 unread"), true);
        eq("a different button", AppNotes.sameLabel("Spam", "Snoozed"), false);
        eq("nothing matches an empty label", AppNotes.sameLabel("", "Spam"), false);

        // which item to tap: the first whose label is the same, else none
        eq("item found", AppNotes.itemFor("Spam, 246", java.util.Arrays.asList("Inbox, 12", "Starred", "Spam, 250", "Spam folder help")), 3);
        eq("item missing", AppNotes.itemFor("Trash", java.util.Arrays.asList("Inbox", "Spam")), 0);

        // a hint with his question when a saved way might fit (11-12: "show me my spam emails" got "it's already on your
        // screen" with no look). His words, the place's words without the app's name: half of them is enough.
        eq("hint for other words", r.hint("show me my spam emails").toLowerCase().contains("go_to \"gmail spam folder\""), true);
        eq("the hint says what's on screen", r.hint("show me my spam emails").contains("Sprocket's own screen"), true);
        eq("no hint for the app's name alone", r.hint("open gmail"), "");
        eq("no hint for something else", r.hint("what's the weather"), "");
        eq("no hint with nothing asked", r.hint(null), "");

        // a scroll is skipped once the button after it shows (11-13: five saved scrolls went past Spam)
        java.util.List<AppNotes.Step> five = java.util.Arrays.asList(AppNotes.Step.open("open_app", "Gmail"), AppNotes.Step.tap("Gmail", "Open navigation drawer"),
                AppNotes.Step.scroll("Gmail", "down"), AppNotes.Step.scroll("Gmail", "down"), AppNotes.Step.tap("Gmail", "Spam, 244"));
        eq("the scroll looks for the next tap", AppNotes.nextTarget(five, 1), "Spam, 244");
        eq("through more scrolls", AppNotes.nextTarget(five, 2), "Spam, 244");
        eq("nothing after the last step", AppNotes.nextTarget(five, 4), "");
        eq("not past a back", AppNotes.nextTarget(java.util.Arrays.asList(AppNotes.Step.scroll("X", "down"), AppNotes.Step.back(),
                AppNotes.Step.tap("X", "Y")), 0), "");

        // saved and loaded
        AppNotes rb = AppNotes.fromJson(r.toJson());
        eq("routes saved and loaded", rb.find("gmail spam").steps.toString(), r.find("gmail spam").steps.toString());
        eq("deleted by id", rb.delete("com.google.android.gm", rb.apps.get(0).routes.get(0).id) + " " + rb.find("gmail spam"), "true null");

        System.out.println(fails == 0 ? "appnotes: all " + n + " checks pass" : "appnotes: " + fails + " of " + n + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
