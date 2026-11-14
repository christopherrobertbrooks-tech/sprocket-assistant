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
        eq("an unreadable spot asks", Screen.pointNeedsCard(null, "the Manage apps row"), true);
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
        eq("the notice says how", Screen.handsOffNotice("3:12 am").contains("Installed apps"), true);
        eq("and when", Screen.handsOffNotice("3:12 am").contains("3:12 am"), true);

        System.out.println("ScreenTest: " + (n - fails) + "/" + n + " passed");
        if (fails > 0) System.exit(1);
    }
}
