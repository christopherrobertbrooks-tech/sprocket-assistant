package dev.sprocket.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Stage 1 hands on the desktop JDK: how the screen reads to the brain, and what the hands refuse. No phone. */
public class ScreenTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static Screen.Item button(String label) { return new Screen.Item("button", label, true, false, false, false, false); }

    public static void main(String[] a) {
        List<Screen.Item> items = Arrays.asList(
                new Screen.Item("text", "Wi-Fi", false, false, false, false, false),
                new Screen.Item("switch", "Wi-Fi", true, false, false, true, false),
                new Screen.Item("box", "Search settings", true, true, false, false, false),
                new Screen.Item("list", "", false, false, true, false, false),
                new Screen.Item("box", "hunter2", true, true, false, false, true));
        eq("numbered, with what each can do", Screen.describe("Settings", items),
                "Screen of Settings:\n[1] text \"Wi-Fi\"\n[2] switch \"Wi-Fi\" (on) (tap)\n[3] box \"Search settings\" (type)\n"
                        + "[4] list (scrolls)\n[5] box \"(password field)\" (type)\n");
        // 11-15 playground h08: Battery Saver's switch is greyed out while charging; it read as a plain "switch (on)"
        // and Sprocket said it was on. Greyed-out items say so, the list explains, and a tap on one is refused.
        Screen.Item grey = new Screen.Item("switch", "Use Battery Saver", true, false, false, true, false, false);
        eq("a greyed-out item says so, and the list says what that means", Screen.describe("Settings", Arrays.asList(grey)),
                "Screen of Settings:\n[1] switch \"Use Battery Saver\" (on) (greyed out)\n" + Screen.GREYED);
        eq("an item is usable unless told otherwise", button("OK").enabled, true);
        eq("a tap on a greyed-out item is refused, saying why", Screen.refuseTap("com.android.settings", grey) != null
                && Screen.refuseTap("com.android.settings", grey).contains("greyed out"), true);
        eq("the greyed-out note tells it not to claim the change", Screen.GREYED.contains("can't be changed right now"), true);
        // 11-15 playground h07: a tap on a spot in a picture landed on the wrong page and Sprocket said "both done".
        eq("a tap on a spot reminds it to check the screen before saying it worked",
                Screen.afterPoint("Screen of Settings:\n").startsWith("Screen of Settings:\n")
                        && Screen.afterPoint("Screen of Settings:\n").contains("only if this screen shows the change"), true);
        // 11-15 sealed set (Codex 047): Settings' font size slider read as plain "slider "Font size,"", so Sprocket
        // guessed its position from a picture and said "back to normal" three times when it wasn't.
        Screen.Item slider = new Screen.Item("slider", "Font size,", true, false, false, false, false, true,
                Screen.sliderValue("", true, 3f, 0f, 6f, 0));
        eq("a slider says where it is", Screen.describe("Settings", Arrays.asList(slider)),
                "Screen of Settings:\n[1] slider \"Font size\" (at 3, from 0 to 6) (tap)\n");
        eq("Android's own words for the value win", Screen.sliderValue("130%", true, 3f, 0f, 6f, 0), "130%");
        eq("a percent range", Screen.sliderValue(null, true, 42.5f, 0f, 100f, 2), "42.5%");
        eq("a fractional range keeps its decimals", Screen.sliderValue("", true, 1.15f, 0.85f, 2f, 1), "at 1.15, from 0.85 to 2");
        eq("no value, nothing added", Screen.sliderValue("", false, 0f, 0f, 0f, 0), "");
        eq("an item has no value unless told", button("OK").value, "");
        // a phone test: "add 42 cheeseburgers" ran out of steps; a quantity + takes 41 taps. One tap
        // action can now press the same button again and again, finding it by its label after each screen change.
        List<Screen.Item> qtyScreen = Arrays.asList(button("Decrease quantity"), new Screen.Item("text", "1", false, false, false, false, false),
                button("Increase quantity"), button("Add to cart"));
        eq("the same button where it was", Screen.repeatTarget(qtyScreen, button("Increase quantity"), 3), 3);
        List<Screen.Item> shifted = Arrays.asList(new Screen.Item("text", "Max 50", false, false, false, false, false),
                button("Decrease quantity"), new Screen.Item("text", "2", false, false, false, false, false), button("Increase quantity"));
        eq("the same button after the list shifted", Screen.repeatTarget(shifted, button("Increase quantity"), 3), 4);
        eq("gone: stop", Screen.repeatTarget(Arrays.asList(button("Add to cart")), button("Increase quantity"), 1), -1);
        eq("two the same and neither where it was: stop", Screen.repeatTarget(Arrays.asList(button("x"), button("+"), button("+")),
                button("+"), 1), -1);
        eq("an unlabelled button only where it was", Screen.repeatTarget(Arrays.asList(button(""), button("")), button(""), 2), 2);
        eq("a risky button is never repeated", Screen.refuseRepeat(button("Place order")) != null, true);
        eq("a plain + is fine to repeat", Screen.refuseRepeat(button("Increase quantity")), null);
        eq("a greyed-out + isn't", Screen.refuseRepeat(new Screen.Item("button", "+", true, false, false, false, false, false)) != null, true);
        eq("Sprocket's own screen says how to get out of it", Screen.OWN_SCREEN.contains("open_app"), true);
        eq("an empty screen says so", Screen.describe("", new ArrayList<>()), "Screen of an unknown app:\n(nothing readable on this screen)\n");

        List<Screen.Item> many = new ArrayList<>();
        for (int i = 0; i < Screen.MAX_ITEMS + 5; i++) many.add(button("b" + i));
        eq("long screens are cut with a scroll hint", Screen.describe("X", many).endsWith("... and 5 more; scroll to see them.\n"), true);

        eq("a blank row takes the texts inside it", Screen.rowLabel(Arrays.asList("Wi-Fi", "", "Connected")), "Wi-Fi · Connected");
        eq("repeats are dropped", Screen.rowLabel(Arrays.asList("Ember Dash", "Ember Dash")), "Ember Dash");
        eq("no texts, no label", Screen.rowLabel(new ArrayList<>()), "");

        eq("a bank app is blocked", Screen.blockedApp("com.chase.sig.android"), true);
        eq("a password manager is blocked", Screen.blockedApp("com.x8bit.bitwarden"), true);
        eq("Google Wallet is blocked", Screen.blockedApp("com.google.android.apps.walletnfcrel"), true);
        eq("Settings is fine", Screen.blockedApp("com.android.settings"), false);
        eq("Sprocket itself is fine", Screen.blockedApp("dev.sprocket.app"), false);

        eq("Send isn't refused outright (the card asks)", Screen.refuseTap("com.google.android.apps.messaging", button("Send")), null);
        eq("Wi-Fi switch is fine", Screen.refuseTap("com.android.settings", button("Wi-Fi")), null);
        eq("Display is fine (not 'pay')", Screen.refuseTap("com.android.settings", button("Display")), null);
        eq("Send in another app asks first (Sprocket's own Send card doesn't cover it)", Screen.risky(button("Send")), true);
        eq("Send SMS asks first", Screen.risky(button("Send SMS")), true);
        eq("Call asks first", Screen.risky(button("Call")), true);
        eq("Video call asks first", Screen.risky(button("Video call")), true);
        eq("Answer asks first", Screen.risky(button("Answer")), true);
        eq("Post asks first", Screen.risky(button("Post")), true);
        eq("Calls (a tab) doesn't", Screen.risky(button("Calls")), false);
        eq("Send's card shows the words going out", Screen.sends(button("Send")), true);
        eq("Delete's card doesn't", Screen.sends(button("Delete")), false);
        eq("the Send card quotes the message", Screen.tapCard("Messages", button("Send"), " on my way "),
                "Tap \u201cSend\u201d in Messages?\n\u201con my way\u201d");
        eq("Display isn't risky (not 'pay')", Screen.risky(button("Display")), false);
        eq("Pay now asks first", Screen.risky(button("Pay now")), true);
        eq("Place order asks first", Screen.risky(button("Place order")), true);
        eq("Delete asks first", Screen.risky(button("Delete")), true);
        eq("Uninstall asks first", Screen.risky(button("Uninstall")), true);
        eq("Sign out asks first", Screen.risky(button("Sign out")), true);
        eq("a risky tap isn't refused outright (the card asks)", Screen.refuseTap("com.google.android.apps.photos", button("Delete")), null);
        eq("the card names the button and the app", Screen.tapCard("Photos", button("Delete"), null), "Tap \u201cDelete\u201d in Photos?");
        eq("a tap on the screen it was read from goes ahead", Screen.changed("com.android.settings", "com.android.settings", true), null);
        eq("a tap after another app came up doesn't", Screen.changed("com.android.settings", "com.chase.sig.android", true) != null, true);
        eq("a tap on an item that's gone doesn't", Screen.changed("com.android.settings", "com.android.settings", false) != null, true);
        eq("a picture spot after another app came up doesn't", Screen.changed("com.google.android.apps.maps", "com.android.dialer", null) != null, true);
        eq("nothing read yet, nothing tapped", Screen.changed("", "com.android.settings", true) != null, true);
        eq("anything in a bank app is refused", Screen.refuseTap("com.chase.sig.android", button("Accounts")) != null, true);

        Screen.Item box = new Screen.Item("box", "Message", true, true, false, false, false);
        Screen.Item pw = new Screen.Item("box", "", true, true, false, false, true);
        eq("typing a message is fine", Screen.refuseType("com.google.android.apps.messaging", box), null);
        eq("never into a password field", Screen.refuseType("com.android.settings", pw) != null, true);
        eq("not into a button", Screen.refuseType("com.android.settings", button("OK")) != null, true);

        // a tap only lands on what's really on top (11-12: a menu button hidden behind Gmail's search page was tapped,
        // and the finger landed on a recent search; 11-13: what covered it there wasn't a button, just the page).
        // Chains: what's drawn at the item's middle, then what it sits in; the item, then what it sits in.
        List<String> drawer = Arrays.asList("drawer button", "toolbar", "inbox"), row = Arrays.asList("recent search", "search page");
        eq("covered by a button on another layer", Screen.onTop(row, drawer), false);
        eq("covered by a page that isn't a button", Screen.onTop(Arrays.asList("search page", "root"), drawer), false);
        eq("nothing drawn there (off the window)", Screen.onTop(new ArrayList<String>(), drawer), false);
        eq("the item itself is drawn there", Screen.onTop(drawer, drawer), true);
        eq("something inside the item is drawn there", Screen.onTop(Arrays.asList("icon", "drawer button", "toolbar"), drawer), true);
        eq("what the item sits in, not the item", Screen.onTop(Arrays.asList("toolbar", "inbox"), drawer), false);
        eq("the keyboard note says how to close it", Screen.KEYBOARD.contains("back"), true);

        // a screenshot tap asks first when Sprocket can't read the spot, or the spot or the brain's words say risky
        // (11-12: Play Store's page read as nothing, so its spots skipped the card)
        eq("an unreadable plain spot doesn't ask", Screen.pointNeedsCard(null, "the search icon (magnifying glass) at the bottom left"), false);
        eq("an unreadable spot the brain calls risky still asks", Screen.pointNeedsCard(null, "the Install button"), true);
        eq("so does one it says sends", Screen.pointNeedsCard(null, "the Send arrow"), true);
        eq("a plain readable spot doesn't", Screen.pointNeedsCard(button("Top charts"), "Top charts"), false);
        eq("a risky readable spot asks", Screen.pointNeedsCard(button("Install"), "a game"), true);
        eq("the brain's words count too", Screen.pointNeedsCard(button("Open"), "the Install button"), true);
        eq("the card for an unreadable spot says what the brain meant", Screen.pointCard("Google Play Store", null, "the Install button"),
                "Tap a spot Sprocket can't read in Google Play Store?\nIt says it's tapping: \u201cthe Install button\u201d");
        eq("and when it didn't say", Screen.pointCard("Maps", null, " "), "Tap a spot Sprocket can't read in Maps?");
        eq("a risky readable spot gets the usual card", Screen.pointCard("Photos", button("Delete"), "trash"), "Tap \u201cDelete\u201d in Photos?");

        // is screen control on: Android's list of switched-on services (11-13: Android switched it off by itself once)
        eq("on, short name", Screen.handsOn("com.x/.Talk:dev.sprocket.app/.ScreenHands", "dev.sprocket.app"), true);
        eq("on, full name", Screen.handsOn("dev.sprocket.app/dev.sprocket.app.ScreenHands", "dev.sprocket.app"), true);
        eq("Sprocket Test's own name", Screen.handsOn("dev.sprocket.test/dev.sprocket.app.ScreenHands", "dev.sprocket.test"), true);
        eq("only the other app's on", Screen.handsOn("dev.sprocket.test/dev.sprocket.app.ScreenHands", "dev.sprocket.app"), false);
        eq("nothing on", Screen.handsOn(null, "dev.sprocket.app"), false);
        eq("the notice says the way: Installed apps > Sprocket", Screen.handsOffNotice("3:12 am").contains("Installed apps > Sprocket: screen control"), true);
        eq("and when", Screen.handsOffNotice("3:12 am").contains("3:12 am"), true);

        // a saved way's first button missing after opening the app (an app reopened inside a message)
        eq("button there: go on", Screen.findStart(true, true, 0, false, false), "go");
        eq("not there: press back inside the app", Screen.findStart(false, true, 0, false, false), "back");
        eq("still not there: back once more", Screen.findStart(false, true, 1, false, false), "back");
        eq("never more than two backs", Screen.findStart(false, true, 2, false, false), "give_up");
        eq("back closed the app: open it again", Screen.findStart(false, false, 1, false, false), "reopen");
        eq("found after reopening", Screen.findStart(true, true, 1, true, false), "go");
        eq("reopened and still missing: slow way, no more backs", Screen.findStart(false, true, 1, true, false), "give_up");
        eq("reopened and still not in the app: slow way", Screen.findStart(false, false, 1, true, false), "give_up");
        eq("never presses back in a money or password app", Screen.findStart(false, true, 0, false, true), "give_up");

        // switching itself off (11-14): a card first, and it says how to get it back
        eq("the switch-off card says what stops and how to undo it", Screen.OFF_CARD.contains("can't read or tap other apps") && Screen.OFF_CARD.contains("Settings"), true);

        // switching itself back on with the adb grant (11-14)
        eq("added to an empty list", Screen.withHands(null, "dev.sprocket.app"), "dev.sprocket.app/dev.sprocket.app.ScreenHands");
        eq("added after the others, nothing else touched", Screen.withHands("com.x/.Talk", "dev.sprocket.app"), "com.x/.Talk:dev.sprocket.app/dev.sprocket.app.ScreenHands");
        eq("already on: nothing to write", Screen.withHands("com.x/.Talk:dev.sprocket.app/.ScreenHands", "dev.sprocket.app"), null);
        eq("Android switched it off: back on by itself", Screen.autoOn(false, 0, 1_000_000_000L), true);
        eq("the user switched it off: stays off", Screen.autoOn(true, 0, 1_000_000_000L), false);
        eq("not twice in 10 minutes (no fight with Android)", Screen.autoOn(false, 1_000_000_000L - 60_000L, 1_000_000_000L), false);

        // another app's keyboard is closed before Sprocket looks (11-14: Gmail's search page)
        eq("Gmail with its keyboard up: close it first", Screen.closeKeyboardFirst(true, "com.google.android.gm", "", false), true);
        eq("only once per app per question", Screen.closeKeyboardFirst(true, "com.google.android.gm", "com.google.android.gm", false), false);
        eq("no keyboard: nothing to close", Screen.closeKeyboardFirst(false, "com.google.android.gm", "", false), false);
        eq("never in Sprocket itself", Screen.closeKeyboardFirst(true, "dev.sprocket.app", "", true), false);

        // a covered button in a saved way: look again a few times (11-14: Gmail's menu still sliding in)
        eq("covered: try again", Screen.retryCovered("item 4" + Screen.COVERED, 0), true);
        eq("but not forever", Screen.retryCovered("item 4" + Screen.COVERED, Screen.COVERED_TRIES), false);
        eq("other refusals: no retry", Screen.retryCovered("that app is off limits to Sprocket (money or passwords)", 0), false);

        // a picture tap clicks the button under it (11-14: Gmail's account switcher ignored finger taps)
        eq("an account row under the spot: click it", Screen.clickUnderPoint(true, 984L * 168, 1080L * 2340), true);
        eq("a whole-screen layer: use a finger", Screen.clickUnderPoint(true, 1080L * 2000, 1080L * 2340), false);
        eq("the spot isn't on it: use a finger", Screen.clickUnderPoint(false, 984L * 168, 1080L * 2340), false);


        // the phone's own details, so a how-to search finds steps for this phone and this texting app (2026-11-14)
        eq("phone line, Samsung", Screen.phoneLine("samsung", "SM-S936U", "17", "90000", "com.google.android.apps.messaging", "Messages"),
                "This phone: Samsung SM-S936U, Android 17, One UI 9.0. Its texting app is Google Messages.");
        eq("phone line, One UI point release", Screen.phoneLine("samsung", "SM-S931U", "16", "80500", "com.samsung.android.messaging", "Messages"),
                "This phone: Samsung SM-S931U, Android 16, One UI 8.5. Its texting app is Samsung Messages.");
        eq("phone line, not Samsung, no texting app", Screen.phoneLine("Google", "Pixel 10", "17", "", null, null),
                "This phone: Google Pixel 10, Android 17.");
        eq("phone line, label already says the maker", Screen.phoneLine("Google", "Pixel 10", "17", null, "com.google.android.apps.messaging", "Google Messages"),
                "This phone: Google Pixel 10, Android 17. Its texting app is Google Messages.");
        eq("phone line, a junk One UI value is left out", Screen.phoneLine("samsung", "SM-S936U", "17", "abc", null, null),
                "This phone: Samsung SM-S936U, Android 17.");
        try {
            String persona = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("android/assets/persona.md")), "UTF-8");
            eq("persona: stuck in an app, search with this phone's details", persona.contains("Stuck in an app") && persona.contains("This phone"), true);
            eq("persona: one conversation's look is in its own menu", persona.contains("that conversation's own menu"), true);
        } catch (java.io.IOException e) { eq("persona readable", e, ""); }

        System.out.println("ScreenTest: " + (n - fails) + "/" + n + " passed");
        if (fails > 0) System.exit(1);
    }
}
