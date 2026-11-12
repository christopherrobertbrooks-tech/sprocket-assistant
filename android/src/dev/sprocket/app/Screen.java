package dev.sprocket.app;

import java.util.List;
import java.util.Locale;

/**
 * Stage 1 "hands" (the owner 2026-11-10: "rebuild sprocket with it", after testing OpenDroid): the plain decisions behind
 * the screen tool. ScreenHands.java reads the screen and taps; this turns what it found into the numbered list the
 * brain reads, and says no to what the hands must never touch. Plain Java: test/ScreenTest.java.
 *
 * Shortcuts come first (PhoneTools): the screen tool is for apps and settings with no shortcut.
 */
final class Screen {
    private Screen() { }

    /** One thing on the screen, as ScreenHands found it. */
    static final class Item {
        final String role, label;
        final boolean clickable, editable, scrollable, checked, password;

        Item(String role, String label, boolean clickable, boolean editable, boolean scrollable, boolean checked, boolean password) {
            this.role = role; this.label = label == null ? "" : label.trim().replace('\n', ' ');
            this.clickable = clickable; this.editable = editable; this.scrollable = scrollable;
            this.checked = checked; this.password = password;
        }
    }

    static final int MAX_ITEMS = 120;
    private static final int MAX_LABEL = 80;

    /** The numbered screen the brain reads: "[3] button "Send" (tap)". Numbers are 1-based, in the order found. */
    static String describe(String app, List<Item> items) {
        StringBuilder b = new StringBuilder("Screen of ").append(app == null || app.isEmpty() ? "an unknown app" : app).append(":\n");
        int shown = Math.min(items.size(), MAX_ITEMS);
        for (int i = 0; i < shown; i++) {
            Item it = items.get(i);
            String label = it.password ? "(password field)" : it.label;
            if (label.length() > MAX_LABEL) label = label.substring(0, MAX_LABEL) + "...";
            b.append('[').append(i + 1).append("] ").append(it.role);
            if (!label.isEmpty()) b.append(" \"").append(label).append('"');
            if (it.checked) b.append(" (on)");
            if (it.editable) b.append(" (type)");
            else if (it.clickable) b.append(" (tap)");
            if (it.scrollable) b.append(" (scrolls)");
            b.append('\n');
        }
        if (items.size() > shown) b.append("... and ").append(items.size() - shown).append(" more; scroll to see them.\n");
        if (items.isEmpty()) b.append("(nothing readable on this screen)\n");
        return b.toString();
    }

    /**
     * The label of a tappable row that has none of its own: the texts inside it, joined ("Wi-Fi · Connected"), so the
     * brain sees one item to tap instead of a blank row followed by loose text.
     */
    static String rowLabel(List<String> innerTexts) {
        StringBuilder b = new StringBuilder();
        for (String t : innerTexts) {
            String x = t == null ? "" : t.trim().replace('\n', ' ');
            if (x.isEmpty() || b.indexOf(x) >= 0) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(x);
            if (b.length() >= MAX_LABEL) break;
        }
        return b.toString();
    }

    /** Apps the hands never read or touch: banking, payments, wallets, password managers. */
    private static final String[] BLOCKED_APP_WORDS = {"bank", "wallet", "pay", "venmo", "cashapp", "zelle", "coinbase",
            "robinhood", "fidelity", "schwab", "vanguard", "chase", "wellsfargo", "capitalone", "citi", "discover",
            "bitwarden", "1password", "onepassword", "lastpass", "dashlane", "keepass", "authenticator", "authy"};

    static boolean blockedApp(String packageName) {
        if (packageName == null) return false;
        String p = packageName.toLowerCase(Locale.US).replace(".", "").replace("_", "");
        if (p.startsWith("devphoneassistant") || p.startsWith("devsprocket")) return false;   // its own screens
        for (String w : BLOCKED_APP_WORDS) if (p.contains(w)) return true;
        return false;
    }

    /**
     * Buttons the hands press only after his OK on a card (stage 3): they spend money, delete, install, sign him out,
     * or send, post, call or answer for him. Sprocket's own Send and Call cards don't cover a tap in another app, so
     * those buttons get this card too (Codex 040 #1).
     */
    private static final String[] RISKY_WORDS = {"pay", "buy", "purchase", "place order", "checkout", "check out",
            "subscribe", "transfer", "send money", "delete", "uninstall", "install", "remove", "erase", "factory reset",
            "reset phone", "sign out", "log out", "logout", "clear data", "clear storage", "format",
            "send", "post", "publish", "submit", "call", "dial", "answer"};

    /** Buttons whose card shows the words about to go out. */
    private static final String[] SENDING_WORDS = {"send", "post", "publish", "submit"};

    /** True when tapping this needs his OK on a card first. */
    static boolean risky(Item it) { return has(it, RISKY_WORDS); }

    /** True when this button sends words (a message, a post): its card shows them. */
    static boolean sends(Item it) { return has(it, SENDING_WORDS); }

    private static boolean has(Item it, String[] words) {
        String l = it == null ? "" : " " + it.label.toLowerCase(Locale.US) + " ";
        for (String w : words) if (l.matches(".*[^a-z]" + w.replace(" ", "[^a-z]+") + "[^a-z].*")) return true;
        return false;
    }

    /** The card for a risky tap; typed = the words in the box about to go out, or null. */
    static String tapCard(String app, Item it, String typed) {
        String card = "Tap \u201c" + it.label + "\u201d" + (app == null || app.isEmpty() ? "" : " in " + app) + "?";
        if (typed == null || typed.trim().isEmpty()) return card;
        String t = typed.trim();
        return card + "\n\u201c" + (t.length() > 200 ? t.substring(0, 200) + "..." : t) + "\u201d";
    }

    /**
     * Null when the screen is still the one the brain was shown, or why the hands must look again first (Codex 040
     * #3: a number or a picture spot from an old screen must never land on a new one). readApp: the app when it was
     * read; nowApp: the app on screen now; stillShown: the item is still there (null for a picture spot).
     */
    /** The card for a tap at a spot the screen list can't name (a picture, a map, a game, an unlabelled button). */
    static String unknownSpotCard(String app) {
        return "Tap a spot in " + (app == null || app.isEmpty() ? "this app" : app) + "? I can't tell what it is, so I'm checking first.";
    }

    static String changed(String readApp, String nowApp, Boolean stillShown) {
        if (readApp == null || readApp.isEmpty()) return "read the screen first";
        if (!readApp.equals(nowApp)) return "the screen changed since it was read (another app is up now); read it again";
        if (stillShown != null && !stillShown) return "that item is gone from the screen now; read it again";
        return null;
    }

    /** Null when the tap may go ahead (after a card if risky()), or why it never may. */
    static String refuseTap(String packageName, Item it) {
        if (blockedApp(packageName)) return "that app is off limits to Sprocket (money or passwords)";
        return null;
    }

    /** Null when typing is fine, or why not. */
    static String refuseType(String packageName, Item it) {
        if (blockedApp(packageName)) return "that app is off limits to Sprocket (money or passwords)";
        if (it != null && it.password) return "Sprocket never types into password fields";
        if (it != null && !it.editable) return "item is not a text box";
        return null;
    }
}
