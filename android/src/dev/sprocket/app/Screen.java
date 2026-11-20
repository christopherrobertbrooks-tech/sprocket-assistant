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

    /** Camera apps (security cameras, doorbells, baby monitors...): their screens and pictures carry cameraNote(). */
    static final java.util.Set<String> CAMERA_APPS = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.arlo.app", "com.roku.rokuhome", "com.ringapp", "com.hualai", "com.immediasemi.android.blink",
            "com.google.android.apps.chromecast.app", "com.nest.android", "com.oceanwing.battery.cam", "com.simplisafe.mobile",
            "com.tplink.iot", "com.mcu.reolink", "com.tplink.kasa_android", "com.xiaomi.smarthome", "com.wyze.wyzeapp"));
    private static final java.util.regex.Pattern AGO = java.util.regex.Pattern.compile(
            "\\b\\d+\\s*(s|sec|secs|seconds?|m|min|mins|minutes?|h|hr|hrs|hours?|d|days?)\\s+ago\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The camera rule (11-18: Sprocket looked at Arlo's saved snapshot -- Play button, "3m ago" -- and told him the camera
     * was live, and read "3m ago · Spider · Motion Trigger Armed" as motion three minutes ago). "" outside camera apps.
     */
    static String cameraNote(String pkg, List<Item> items) {
        if (pkg == null || !CAMERA_APPS.contains(pkg)) return "";
        String age = null;
        boolean offline = false;
        for (Item it : items) {
            java.util.regex.Matcher m = AGO.matcher(it.label);
            if (age == null && m.find()) age = m.group();
            String l = it.label.toLowerCase(java.util.Locale.US);
            if (l.contains("offline") || l.contains("unavailable") || l.contains("disconnected") || l.contains("camera is off")) offline = true;
        }
        StringBuilder b = new StringBuilder("\n(Camera app: a picture with a Play button or a time on it is a saved snapshot, not live "
                + "video. To see now, tap Play or the picture, wait until it's playing, then look again. If it won't play, say it's a "
                + "snapshot and how old. Words like Armed or Motion Trigger are settings, not something that happened. Describe "
                + "only what's in the picture; never guess what's behind it. What earlier messages or the camera's name suggest "
                + "isn't proof: don't name an animal, a person or a room you can't see in this picture.)");
        if (age != null) b.append("\n(This screen shows a picture time-stamped \u201c").append(age).append("\u201d: that is its age, not an event.)");
        if (offline) b.append("\n(A camera here shows as offline: tell him which one, and don't describe its old picture as now.)");
        return b.toString();
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
    private static final String[] RISKY_WORDS = {"pay", "buy", "purchase", "place order", "place your order", "place my order",
            "confirm order", "confirm your order", "complete order", "complete purchase", "submit order", "order now",
            "checkout", "check out",
            "subscribe", "transfer", "send money", "delete", "uninstall", "install", "remove", "erase", "factory reset",
            "reset phone", "sign out", "log out", "logout", "clear data", "clear storage", "format",
            "send", "post", "publish", "submit", "call", "dial", "answer",
            // Codex 055b #1: cancelling an order or a booking, tips, and a bare "Confirm" (whatever it confirms)
            "cancel order", "cancel my order", "cancel your order", "cancel this order", "cancel subscription",
            "cancel membership", "cancel booking", "cancel reservation", "cancel ride", "cancel trip",
            "tip", "add tip", "add to tip", "donate", "redeem", "confirm"};

    /** A button that adds or gives money: "Add $2.00", "Tip $3" (Codex 055b #1). */
    private static final java.util.regex.Pattern MONEY_BUTTON = java.util.regex.Pattern.compile(
            "(?i)\\s*(add|tip|give|donate|top up|reload)\\b[^$]{0,20}\\$\\s*\\d.*");

    /** Buttons whose card shows the words about to go out. */
    private static final String[] SENDING_WORDS = {"send", "post", "publish", "submit"};

    /** True when tapping this needs his OK on a card first. */
    static boolean risky(Item it) {
        return has(it, RISKY_WORDS) || (it != null && MONEY_BUTTON.matcher(it.label).matches() && !CART_ADD.matcher(it.label).matches());
    }

    /** "Add 42 to cart · $503.58": filling a cart isn't paying (the order button keeps its card). 11-20 practice: the
     *  money rule caught it and every cart test came back empty. */
    private static final java.util.regex.Pattern CART_ADD = java.util.regex.Pattern.compile("(?i).*\\b(cart|bag|basket|order|list)\\b.*");

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
            + "reason shown nearby, like charging. It can't be changed right now: tell the boss that and why, never that it's done. "
            + "No reason on the screen? phone_status says whether the phone is charging (Battery Saver can't turn on while it charges).)\n";

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
            "seamless", "caviar", "gopuff", "shipt", "amazon.mshop", "walmart", "target.ui", "kroger", "costco", "playground.eats",
            // restaurants' own apps (McDonald's, Taco Bell, Hardee's...)
            "mcdonalds", "tacobell", "hardees", "carlsjr", "dominospizza", "dominos", "pizzahut", "papajohns", "wendys",
            "chickfila", "starbucks", "chipotle", "burgerking", "subway", "kfc", "sonic", "dunkin", "panera", "popeyes",
            "jackinthebox", "whataburger", "arbys", "dairyqueen", "littlecaesars"};

    /** Words that keep the card even in a cart app: the account, its addresses and payment, the order itself. */
    private static final java.util.regex.Pattern NOT_CART = java.util.regex.Pattern.compile(
            "(?i).*\\b(account|address|card|payment|wallet|profile|history|saved|all|everything|order|orders|sign|log)\\b.*");

    /**
     * True when a tap needs their OK on a card first (risky), except taking an item out of a food or shopping cart (a food-order test:
     * deleting the extra item they'd just asked to remove asked again and again).
     */
    static boolean needsCard(String packageName, Item it) {
        // 11-16: giving an app a permission (Amazon's notification prompt mid-run) is the owner's call: any answer on Android's
        // permission pop-up except "Don't allow" waits for their OK
        if (packageName != null && packageName.endsWith("permissioncontroller"))
            return !it.label.trim().matches("(?i)(don.t allow|deny|no thanks|not now|cancel)\\b.*");
        if (!risky(it)) return false;
        String p = packageName == null ? "" : packageName.toLowerCase(Locale.US);
        boolean shop = false;
        for (String w : SHOP_APP_WORDS) if (p.contains(w)) { shop = true; break; }
        String l = it.label.trim();
        // opening checkout only shows the total (the fees are only on that page); paying is the Place order button, which
        // keeps its card
        if (shop && l.matches("(?i)(go to |continue to |proceed to )?check ?out( now)?")) return false;
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

    /**
     * The first item (1-based) whose label has every word they're looking for (small words like "a" and "the" skipped), or
     * -1. The list itself never counts. For the screen tool's find (practice: it gave up on a long menu and said
     * it had checked it all).
     */
    static int findItem(List<Item> items, String want) {
        List<String> words = new java.util.ArrayList<>();
        for (String w : (want == null ? "" : want).toLowerCase(Locale.US).split("[^a-z0-9']+"))
            if (!w.isEmpty() && !w.matches("a|an|the|some|of|my|and")) words.add(w);
        if (words.isEmpty()) return -1;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.scrollable || it.label.isEmpty()) continue;
            String l = it.label.toLowerCase(Locale.US);
            boolean all = true;
            for (String w : words) if (!l.matches("(?s).*\\b" + java.util.regex.Pattern.quote(w) + ".*")) { all = false; break; }
            if (all) return i + 1;
        }
        return -1;
    }

    /** True when something on the screen scrolls (a list find can go through). */
    static boolean hasList(List<Item> items) {
        for (Item it : items) if (it.scrollable) return true;
        return false;
    }

    /**
     * find's answer when the words never showed. Only a screen with a list got a full top-to-bottom look (11-17 practice:
     * find ran on an item's own page, no list, and "looked from the top to the bottom" made it say the deli had no
     * pickles; the pickle spear was at the bottom of the menu one screen back).
     */
    static String findMiss(String text, boolean hadList) {
        if (hadList) return "No “" + text + "” anywhere in this list: Sprocket looked from the top to the bottom. Say so plainly.";
        return "No “" + text + "” on this screen, and nothing on it scrolls, so this was NOT a look through a list or menu"
                + " (it may be an item's own page). Don't tell them it isn't there: finish here, go back to the list or menu,"
                + " and find it there.";
    }

    // ------------------------------------------------------------------ read_all: the whole list in one step (11-19)
    /** Most rows read_all hands back (a long order history is a few hundred). */
    static final int MAX_LIST_LINES = 400;

    /** One screen's rows as plain lines, without numbers (they change as the list moves) or the scrolling list itself. */
    static List<String> listLines(List<Item> items) {
        List<String> out = new java.util.ArrayList<>();
        for (Item it : items) { String l = line(it); if (l != null) out.add(l); }
        return out;
    }

    /** One row as read_all lists it, or null for one it leaves out (the list itself, a password, an empty one). */
    static String line(Item it) {
        if (it.scrollable || it.password) return null;
        String label = it.label.trim().replace('\n', ' ');
        if (label.isEmpty() && it.value.isEmpty()) return null;
        StringBuilder b = new StringBuilder(it.role);
        if (!label.isEmpty()) b.append(" \"").append(label).append('"');
        if (!it.value.isEmpty()) b.append(" (").append(it.value).append(')');
        if (!it.enabled) b.append(" (greyed out)");
        return b.toString();
    }

    /**
     * Adds a screen's rows that haven't been seen yet, each known by its key: which row of the list it is (Android's
     * row number for the list, or the row's own on-screen element), plus its words. So a header that stays put isn't
     * counted twice, and two orders that look alike are still two (Codex 055 #5). keys[i] goes with items[i]. Returns
     * how many rows were new: none after a scroll means the list didn't move.
     */
    static int mergeKeyed(List<String> acc, java.util.Set<String> seen, List<Item> items, List<String> keys) {
        int added = 0;
        for (int i = 0; i < items.size() && acc.size() < MAX_LIST_LINES; i++) {
            String l = line(items.get(i));
            if (l == null || !seen.add(keys.get(i) + "|" + l)) continue;
            acc.add(l);
            added++;
        }
        return added;
    }

    /**
     * Adds the next screen's rows after the ones so far, dropping the rows the two screens share (a scroll overlaps: the
     * bottom of one screen is the top of the next). The overlap is the longest run where the end of acc equals the
     * start of next, so repeated rows ("Reorder" under every order) are kept.
     */
    static void mergeScreen(List<String> acc, List<String> next) {
        int best = 0;
        for (int k = Math.min(acc.size(), next.size()); k > 0; k--) {
            if (acc.subList(acc.size() - k, acc.size()).equals(next.subList(0, k))) { best = k; break; }
        }
        for (int i = best; i < next.size() && acc.size() < MAX_LIST_LINES; i++) acc.add(next.get(i));
    }

    /**
     * What read_all says (11-19 practice: asked what he'd spent on Eats in 30 days, the brain scrolled the order history
     * itself, stopped one screen early with "(more below)" still showing, and said 17 orders for $417.45; it was 18 for
     * $434.98). The code does the scrolling; the brain gets every row once, top to bottom.
     */
    static String wholeList(List<String> lines, int screens, boolean reachedEnd) { return wholeList(lines, screens, reachedEnd, false, true); }

    /**
     * stuck: the list still said "more below" but scrolling didn't move it. keyed (sure): every row had the app's own
     * row number and the read started at the top; false = told apart by position or words only, so a row may be
     * missing or doubled, and it says so rather than promising every row (Codex 055 #5, 056 #2).
     */
    static String wholeList(List<String> lines, int screens, boolean reachedEnd, boolean stuck, boolean keyed) {
        StringBuilder b = new StringBuilder("The whole list, top to bottom (").append(screens).append(screens == 1 ? " screen" : " screens")
                .append(stuck ? ", stopped: the list says there's more below but it wouldn't scroll further, so this may NOT be all of it"
                        : reachedEnd ? ", to the end" : ", stopped before the end: more below")
                .append("). ").append(keyed ? "Every row is here once."
                        : "Sprocket couldn't be sure of every row (this app doesn't number its rows, or the top couldn't be "
                        + "confirmed): a row may be missing or counted twice, so say a count or total is from what could be read.")
                .append(" Count and add from these rows only, keeping just the ones that fit what he asked "
                        + "(the dates, the place, not cancelled or refunded); work sums out step by step and check them before you say them.\n");
        for (String l : lines) b.append("- ").append(l).append('\n');
        if (lines.size() >= MAX_LIST_LINES) b.append("(stopped at ").append(MAX_LIST_LINES).append(" rows: there may be more)\n");
        return b.toString();
    }

    /**
     * What a scroll in a list adds (11-19 practice: with read_all in the tool's description, 9 of 10 spending questions
     * still scrolled by hand, and one stopped 4 orders short: $350.06 instead of $434.98). "" off a list.
     */
    static String scrollNote(List<Item> items) {
        if (!hasList(items)) return "";
        return "\n(Scrolling shows one screen at a time. To count, add up or compare rows across a list, use read_all "
                + "instead: it reads every row, top to bottom, in one step.)";
    }

    /** True while some list on the screen says it has more below. */
    static boolean moreBelow(List<Item> items) {
        for (Item it : items) if ("more below".equals(it.value)) return true;
        return false;
    }

    /**
     * Where a list is (practice: three scrolls into a 30-item menu it said "I checked every line" and the item
     * was one scroll further): more below, at the bottom, or "" when it can't tell or doesn't scroll at all.
     */
    static String scrollState(boolean canScrollDown, boolean canScrollUp) {
        if (canScrollDown) return "more below";
        if (canScrollUp) return "at the bottom";
        return "";
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

    /** The hidden screen with nothing open on it yet (BgScreen: Sprocket works there while they use the phone). */
    static final String HIDDEN_EMPTY = "Sprocket is working on its own hidden screen (they keep using their phone meanwhile), "
            + "and nothing is open on it yet: open the app first with open_app or open_screen. Screen control is on.\n";

    /**
     * Whether the working glow stays hidden: when Sprocket works on its hidden screen, nothing shows while the phone is
     * locked or its screen is off (the glow lit up the always-on display). On the real screen it always shows.
     */
    static boolean glowHidden(boolean hiddenScreen, boolean locked, boolean screenOn) {
        return hiddenScreen && (locked || !screenOn);
    }

    /**
     * One line for the brain about whether they can see the screen (locked, working on the hidden screen, it said
     * "the screen's left up so you can see it"). "" when they're looking at their unlocked phone as usual.
     */
    static String situation(boolean locked, boolean screenOn, boolean hiddenScreen) {
        boolean away = locked || !screenOn;
        if (hiddenScreen && away)
            return "Their phone is locked with the screen off, and Sprocket works on its own hidden screen: they can't see anything "
                    + "you open. Tell them what you did and what you found. What you leave up with stay (a cart for their checkout "
                    + "tap) moves onto the real screen when you're done: say it'll be there when they unlock, not that it's up now.";
        if (hiddenScreen)
            return "Sprocket works on its own hidden screen while they keep using their phone: they can't see what you open there "
                    + "until you're done. Tell them the result. What you leave up with stay (a cart for their checkout tap) moves "
                    + "onto the real screen when you finish.";
        if (away)
            return "Their phone is locked with the screen off: apps and settings on the screen can't be used until they unlock it. "
                    + "Say so if their question needs them.";
        return "";
    }

    /** "stay" on the hidden screen: there's nothing they can see to leave up. */
    static final String STAY_HIDDEN = "This moves from Sprocket's hidden screen onto the real screen when you're done (if their "
            + "phone is locked, it's there when they unlock). Tell them what it shows.";

    /** The phone's own Settings app (Google's, Samsung's...), where the wireless-debugging pairing window lives. */
    static boolean isSettingsApp(String pkg) {
        return pkg != null && (pkg.equals("com.android.settings") || pkg.endsWith(".settings") || pkg.equals("com.samsung.android.settings"));
    }

    /** A pairing code (exactly 6 digits, maybe spaced like "123 456") in a piece of screen text, or null. */
    static String pairingCodeIn(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.matches("\\d{6}")) return t;
        if (t.matches("\\d{3} \\d{3}")) return t.replace(" ", "");
        return null;
    }

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
     * Whether the hidden-screen check may ask the helper now: never from the main thread (Android refuses a socket
     * there; 11-16 the glow's check failed that way, read as "no hidden screen", and the hands worked on his real
     * screen: pressed Home, scrolled his apps), and only when the last answer is 4 seconds old.
     */
    static boolean bgMayAsk(boolean mainThread, long now, long checked) {
        return !mainThread && now - checked >= 4000;
    }

    /** Gone after a ping? A failed ping while it was up is asked once more first (one hiccup must not move the hands). */
    static boolean bgDownAfter(boolean wasUp, boolean pingOk, boolean againOk) {
        return !pingOk && !(wasUp && againOk);
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

    /**
     * What to tell the brain after tapping a switch (or a row with one in it): nothing when it changed or can't be read
     * again; else that it didn't change (11-16: cloud brains tapped Dark mode, Bigger text and Battery saver and believed
     * they were on when they weren't). after: the switch's state read again once the screen settled (null = gone).
     */
    static String switchNote(String label, boolean before, Boolean after) {
        if (after == null || after != before) return null;
        return "The switch \u201c" + label + "\u201d still shows " + (after ? "ON" : "OFF")
                + ": the tap didn't change it. Look at the screen: a pop-up may need an answer, or tap it again.";
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
