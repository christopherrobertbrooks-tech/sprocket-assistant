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
        final boolean clickable, editable, scrollable, checked, password, enabled;
        /** Where a slider (or anything with a range) is now, "" for none (sliderValue). */
        final String value;

        Item(String role, String label, boolean clickable, boolean editable, boolean scrollable, boolean checked, boolean password) {
            this(role, label, clickable, editable, scrollable, checked, password, true);
        }

        /** enabled false: greyed out, the phone won't let it change right now (11-15: Battery Saver while charging). */
        Item(String role, String label, boolean clickable, boolean editable, boolean scrollable, boolean checked, boolean password,
             boolean enabled) {
            this(role, label, clickable, editable, scrollable, checked, password, enabled, "");
        }

        Item(String role, String label, boolean clickable, boolean editable, boolean scrollable, boolean checked, boolean password,
             boolean enabled, String value) {
            this.role = role;
            this.label = label == null ? "" : label.trim().replace('\n', ' ').replaceAll("[,;]+$", "").trim();
            this.clickable = clickable; this.editable = editable; this.scrollable = scrollable;
            this.checked = checked; this.password = password; this.enabled = enabled;
            this.value = value == null ? "" : value.trim();
        }
    }

    static final int MAX_ITEMS = 120;
    private static final int MAX_LABEL = 80;

    /** The numbered screen the brain reads: "[3] button "Send" (tap)". Numbers are 1-based, in the order found. */
    static String describe(String app, List<Item> items) {
        StringBuilder b = new StringBuilder("Screen of ").append(app == null || app.isEmpty() ? "an unknown app" : app).append(":\n");
        int shown = Math.min(items.size(), MAX_ITEMS);
        boolean grey = false;
        for (int i = 0; i < shown; i++) {
            Item it = items.get(i);
            String label = it.password ? "(password field)" : it.label;
            if (label.length() > MAX_LABEL) label = label.substring(0, MAX_LABEL) + "...";
            b.append('[').append(i + 1).append("] ").append(it.role);
            if (!label.isEmpty()) b.append(" \"").append(label).append('"');
            if (!it.value.isEmpty()) b.append(" (").append(it.value).append(')');
            if (it.checked) b.append(" (on)");
            if (!it.enabled) { b.append(" (greyed out)"); grey = true; }
            else if (it.editable) b.append(" (type)");
            else if (it.clickable) b.append(" (tap)");
            if (it.scrollable) b.append(" (scrolls)");
            b.append('\n');
        }
        if (items.size() > shown) b.append("... and ").append(items.size() - shown).append(" more; scroll to see them.\n");
        if (items.isEmpty()) b.append("(nothing readable on this screen)\n");
        if (grey) b.append(GREYED);
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
     * One line about this phone for the brain, so a how-to search when it's stuck in an app finds steps for this
     * phone and this texting app ("remove chat background Google Messages Samsung One UI 9"), not an old or different
     * one. oneUi is Samsung's ro.build.version.oneui (90000 = 9.0, 80500 = 8.5; empty off Samsung). Nothing personal:
     * the model code and versions only, never the phone's own name.
     */
    static String phoneLine(String maker, String model, String android, String oneUi, String smsPkg, String smsLabel) {
        String m = maker == null || maker.isEmpty() ? "" : Character.toUpperCase(maker.charAt(0)) + maker.substring(1);
        StringBuilder b = new StringBuilder("This phone: ").append((m + " " + (model == null ? "" : model)).trim())
                .append(", Android ").append(android);
        try {
            int v = Integer.parseInt(oneUi == null ? "" : oneUi.trim());
            if (v > 0) b.append(", One UI ").append(v / 10000).append('.').append(v % 10000 / 100);
        } catch (NumberFormatException e) { /* not Samsung */ }
        b.append('.');
        if (smsPkg != null && smsLabel != null && !smsLabel.isEmpty()) {
            String by = smsPkg.startsWith("com.google.") ? "Google" : smsPkg.startsWith("com.samsung.") ? "Samsung" : "";
            String name = by.isEmpty() || smsLabel.startsWith(by) ? smsLabel : by + " " + smsLabel;
            b.append(" Its texting app is ").append(name).append('.');
        }
        return b.toString();
    }

    /**
     * True when Android's list of switched-on accessibility services (Settings.Secure enabled_accessibility_services)
     * holds this app's screen control (11-13: Android switched it off by itself once, and he only found out from a
     * failed task).
     */
    static boolean handsOn(String enabled, String pkg) {
        if (enabled == null) return false;
        for (String s : enabled.split(":"))
            if (s.equals(pkg + "/.ScreenHands") || s.equals(pkg + "/dev.sprocket.app.ScreenHands")) return true;
        return false;
    }

    /**
     * Android's switched-on list with this app's screen control added (11-14: with WRITE_SECURE_SETTINGS granted,
     * Sprocket switches itself back on). Nothing else in the list changes; null when it's already there.
     */
    static String withHands(String enabled, String pkg) {
        if (handsOn(enabled, pkg)) return null;
        String mine = pkg + "/dev.sprocket.app.ScreenHands";
        return enabled == null || enabled.trim().isEmpty() || enabled.equals("null") ? mine : enabled + ":" + mine;
    }

    /** Switch back on by itself only when Android switched it off (not the user), and not again within 10 minutes. */
    static boolean autoOn(boolean heSwitchedOff, long lastAutoOn, long now) {
        return !heSwitchedOff && now - lastAutoOn > 10 * 60_000L;
    }

    /** The card before Sprocket switches its own screen control off (when the user asks). */
    static final String OFF_CARD = "Switch screen control off? Sprocket can't read or tap other apps until you turn it back on "
            + "in Settings (the notice that follows takes you there).";

    /** The notice when screen control is found off; when: the time it went off, or null if not known. */
    static String handsOffNotice(String when) {
        return "Screen control went off" + (when == null ? "" : " at " + when) + ". Tap here, then Installed apps > Sprocket: "
                + "screen control, and switch it on.";
    }

    /**
     * True when the item is really what a finger on its middle would touch (11-12: a menu button hidden behind Gmail's
     * search page still read as visible, and the tap landed on a recent search; 11-13: what covered it there was the
     * page, not a button). top: what's drawn at the item's middle, then what it sits in (empty: nothing there); item:
     * the item, then what it sits in. Only the item itself or something inside it counts.
     */
    static <T> boolean onTop(List<T> top, List<T> item) {
        return !item.isEmpty() && top.contains(item.get(0));
    }

    /**
     * Close another app's keyboard before Sprocket looks at it, once per app per question (11-14: Gmail kept its search
     * page up under the keyboard, and the brain tapped a recent search twice instead of the menu).
     */
    static boolean closeKeyboardFirst(boolean keyboardUp, String pkg, String alreadyClosedIn, boolean mine) {
        return keyboardUp && pkg != null && !pkg.isEmpty() && !mine && !pkg.equals(alreadyClosedIn) && !blockedApp(pkg);
    }

    /**
     * Under a screen with greyed-out items (11-15 playground: Battery Saver's switch is greyed out while the phone
     * charges; it read as a plain switch and the brain said it was on).
     */
    static final String GREYED = "(Greyed out: the phone won't let that item be used or changed right now, often for a "
            + "reason shown nearby, like charging. It can't be changed right now: tell the boss that and why, never that it's done.)\n";

    /** A tap pressed again and again (a quantity +) goes at most this many times in one step. */
    static final int MAX_REPEAT = 50;

    /**
     * Where the button being pressed again is now (1-based), or -1 to stop (a phone test: 42 cheeseburgers meant 41
     * taps of a delivery app's +, past the step limit). The screen is read again after every tap: the same spot if it still
     * holds that button, else the one button with the same role and label; gone or ambiguous stops. An unlabelled
     * button is only trusted where it was.
     */
    static int repeatTarget(List<Item> items, Item wanted, int oldIndex) {
        if (oldIndex >= 1 && oldIndex <= items.size() && same(items.get(oldIndex - 1), wanted)) return oldIndex;
        if (wanted.label.isEmpty()) return -1;
        int found = -1;
        for (int i = 0; i < items.size(); i++) {
            if (!same(items.get(i), wanted)) continue;
            if (found != -1) return -1;
            found = i + 1;
        }
        return found;
    }

    private static boolean same(Item a, Item b) { return a.role.equals(b.role) && a.label.equals(b.label); }

    /**
     * Null when this button may be pressed again and again, or why not. Only a real + (a food-order test: "Add"
     * pressed 4 times put in one too many, since each press adds one; "1 ×", a quantity menu, went in circles).
     */
    static String refuseRepeat(Item it) {
        if (risky(it)) return "a button that needs his OK is never pressed more than once in a go";
        if (!it.enabled) return "that button is greyed out";
        if (!it.clickable) return "that item isn't a button";
        if (!PLUS.matcher(it.label.trim()).matches())
            return "only a + button (Increment, Increase quantity) is pressed again and again; press this once, then use the "
                    + "+ that appears, or type the number if the quantity is a box";
        return null;
    }

    private static final java.util.regex.Pattern PLUS = java.util.regex.Pattern.compile(
            "(?i)\\+|plus|increment|increase( quantity)?|increase( the)? quantity.*|add one( more)?|one more.*|more");

    /** Food and shopping apps, where taking something out of the cart is easy to undo. */
    private static final String[] SHOP_APP_WORDS = {"doordash", "ubercab.eats", "grubhub", "instacart", "postmates",
            "seamless", "caviar", "gopuff", "shipt", "amazon.mshop", "walmart", "target.ui", "kroger", "costco", "playground.eats"};

    /** Words that keep the card even in a cart app: the account, its addresses and payment, the order itself. */
    private static final java.util.regex.Pattern NOT_CART = java.util.regex.Pattern.compile(
            "(?i).*\\b(account|address|card|payment|wallet|profile|history|saved|all|everything|order|orders|sign|log)\\b.*");

    /**
     * True when a tap needs their OK on a card first (risky), except taking an item out of a food or shopping cart (a food-order test:
     * deleting the extra item they'd just asked to remove asked again and again).
     */
    static boolean needsCard(String packageName, Item it) {
        if (!risky(it)) return false;
        String p = packageName == null ? "" : packageName.toLowerCase(Locale.US);
        boolean shop = false;
        for (String w : SHOP_APP_WORDS) if (p.contains(w)) { shop = true; break; }
        String l = it.label.trim();
        boolean cartEdit = l.matches("(?i)(remove|delete)\\b.*") && !NOT_CART.matcher(l).matches()
                && !has(it, new String[]{"pay", "buy", "purchase", "place order", "checkout", "check out", "send", "call"});
        return !(shop && cartEdit);
    }

    /**
     * Where a slider is, for the screen list (11-15 sealed set, Codex 047: the font size slider read as a bare
     * "slider", so the brain guessed its position from a picture and said "back to normal" when it wasn't). Android's
     * own words for it (stateDescription, "130%") win; else its range: type 0 = whole numbers, 1 = decimals, 2 = percent.
     */
    static String sliderValue(CharSequence state, boolean hasRange, float now, float min, float max, int type) {
        if (state != null && state.toString().trim().length() > 0) return state.toString().trim();
        if (!hasRange) return "";
        if (type == 2) return num(now) + "%";
        return "at " + num(now) + ", from " + num(min) + " to " + num(max);
    }

    private static String num(float f) {
        if (f == Math.rint(f) && Math.abs(f) < 1e9) return String.valueOf((long) f);
        String t = String.format(Locale.US, "%.2f", f);
        return t.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /**
     * A tap on a spot in a picture can land somewhere else (11-15 playground: it opened the wrong page and the brain
     * said "both done"), so its screen comes back with a reminder.
     */
    static String afterPoint(String screen) {
        return screen + "(That was a tap on a spot in a picture, so it may have landed somewhere else: say it worked only "
                + "if this screen shows the change.)\n";
    }

    /**
     * Under Sprocket's own screen (a phone test: asked for hash browns, it read its own screen 8 times in a row, a
     * third of its steps, before going to the delivery app).
     */
    static final String OWN_SCREEN = "(This is Sprocket's own screen, which is up while he asks; reading again shows it "
            + "again. To use another app, open it first: open_app, go_to or open_screen.)\n";

    static final String KEYBOARD = "(The keyboard is open over the bottom of the screen and hides what's under it: back "
            + "closes it.)\n";

    /** A saved way's button that's covered gets looked at again this many times, this far apart (11-14: a menu still
     * sliding in read as covered; ~1.5 s in all), before the brain takes over. */
    static final int COVERED_TRIES = 3;
    static final long COVERED_WAIT_MS = 500;

    static boolean retryCovered(String why, int tries) {
        return why != null && why.contains(COVERED.trim().substring(0, 20)) && tries < COVERED_TRIES;
    }

    static final String COVERED = " is covered by something else on the screen (a menu, a search page or a pop-up is on top "
            + "of it); read again, or go back first";

    /**
     * A screenshot tap asks first only when the spot or the brain's words for it are risky (pay, install, delete, send,
     * ...). it: what the screen list calls the spot, or null; what: the brain's words for it. 11-12 it also asked for
     * every spot Sprocket can't read (Play Store's page read as nothing), which meant a card for every plain icon in apps
     * whose buttons have no names. So an unreadable spot asks only when the brain's words for it are risky.
     */
    static boolean pointNeedsCard(Item it, String what) {
        return (it != null && risky(it)) || risky(new Item("button", what == null ? "" : what, true, false, false, false, false));
    }

    /**
     * A screenshot tap clicks the button under the spot instead of a finger, when there is one and it's a button, not
     * a whole screen (11-14: Gmail's account switcher ignored finger taps on the right row). Up to half the screen.
     */
    static boolean clickUnderPoint(boolean containsSpot, long buttonArea, long screenArea) {
        return containsSpot && buttonArea > 0 && buttonArea * 2 <= screenArea;
    }

    /** The card for a screenshot tap that asks first. */
    static String pointCard(String app, Item it, String what) {
        if (it != null) return tapCard(app, it, null);
        String card = "Tap a spot Sprocket can't read" + (app == null || app.isEmpty() ? "" : " in " + app) + "?";
        return what == null || what.trim().isEmpty() ? card : card + "\nIt says it's tapping: “" + what.trim() + "”";
    }

    /**
     * Null when the screen is still the one the brain was shown, or why the hands must look again first (Codex 040
     * #3: a number or a picture spot from an old screen must never land on a new one). readApp: the app when it was
     * read; nowApp: the app on screen now; stillShown: the item is still there (null for a picture spot).
     */
    static String changed(String readApp, String nowApp, Boolean stillShown) {
        if (readApp == null || readApp.isEmpty()) return "read the screen first";
        if (!readApp.equals(nowApp)) return "the screen changed since it was read (another app is up now); read it again";
        if (stillShown != null && !stillShown) return "that item is gone from the screen now; read it again";
        return null;
    }

    /** At most this many back presses while looking for a saved way's first button. */
    static final int START_BACKS = 2;

    /**
     * A saved way's first button wasn't on screen right after it opened the app (an email app reopened inside a
     * message). The next move: "go" (the button is there), "back" (press back inside the app and look again), "reopen"
     * (back left the app: open it once more, which lands on its main screen), or "give_up" (take the slow way, as
     * before). If back on an app's main screen closes the app, it reopens it once, and it never presses back anywhere
     * but in that app.
     */
    static String findStart(boolean found, boolean inApp, int backs, boolean reopened, boolean blocked) {
        if (found) return "go";
        if (blocked) return "give_up";
        if (!inApp) return reopened ? "give_up" : "reopen";
        if (reopened || backs >= START_BACKS) return "give_up";
        return "back";
    }

    /** Null when the tap may go ahead (after a card if risky()), or why it never may. */
    static String refuseTap(String packageName, Item it) {
        if (blockedApp(packageName)) return "that app is off limits to Sprocket (money or passwords)";
        if (it != null && !it.enabled) return "that item is greyed out: the phone won't let it change right now (look for a "
                + "reason on the screen, like charging); tell the boss that instead of saying it's done";
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
