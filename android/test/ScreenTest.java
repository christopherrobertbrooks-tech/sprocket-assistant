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

        eq("a spot it can't name asks first (Codex 042 #1)", Screen.unknownSpotCard("Maps"), "Tap a spot in Maps? I can't tell what it is, so I'm checking first.");
        System.out.println("ScreenTest: " + (n - fails) + "/" + n + " passed");
        if (fails > 0) System.exit(1);
    }
}
