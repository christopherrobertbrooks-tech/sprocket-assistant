package dev.sprocket.app;

import java.util.Locale;

/**
 * First-time setup for someone who just installed Sprocket (the owner, 2026-11-11: "full screen for the model, we'll have
 * them select a model and then sprocket will walk the new user through the rest in the chat. Ask for each feature up
 * front, have it to where they can ask sprocket about the setup if they have questions"). The code owns the order and
 * every setting; the brain only answers questions typed along the way. Plain Java: test/SetupTest.java.
 */
final class Setup {
    private Setup() { }

    static final int OFF = -1;                           // no setup running

    /** How a step gets what it needs once they say yes. */
    enum Way { NOTHING, PERMISSIONS, NOTIFICATION_ACCESS, SCREEN_CONTROL, BOTH_SWITCHES, GITHUB_NAME, ASSISTANT }

    static final class Step {
        final String id, feature, name, ask, needs;      // feature: the Config switch it turns on or off (null: none)
        final Way way;
        final String[] perms;

        Step(String id, String feature, String name, String ask, String needs, Way way, String... perms) {
            this.id = id;
            this.feature = feature;
            this.name = name;
            this.ask = ask;
            this.needs = needs;
            this.way = way;
            this.perms = perms;
        }
    }

    static final String MIC = "android.permission.RECORD_AUDIO", CONTACTS = "android.permission.READ_CONTACTS",
            SMS = "android.permission.SEND_SMS", CALL = "android.permission.CALL_PHONE",
            CAL_READ = "android.permission.READ_CALENDAR", CAL_WRITE = "android.permission.WRITE_CALENDAR",
            WHERE = "android.permission.ACCESS_COARSE_LOCATION", POST = "android.permission.POST_NOTIFICATIONS";

    /** Every question, in order: the plain ones first, the Android switches after. */
    static final Step[] STEPS = {
            new Step("talk", null, "Talking out loud",
                    "Want to talk to me out loud? I'll need the microphone. I only listen when you tap Talk.",
                    "the microphone", Way.PERMISSIONS, MIC),
            new Step("people", null, "Texts and calls",
                    "Want me to text and call people for you? I need your contacts, and permission to send texts and place "
                            + "calls. You always get a card to check before anything goes out.",
                    "contacts, sending texts and placing calls", Way.PERMISSIONS, CONTACTS, SMS, CALL),
            new Step("calendar", null, "Your calendar",
                    "Should I see your calendar, so I can tell you what's coming up and add things when you ask?",
                    "calendar access", Way.PERMISSIONS, CAL_READ, CAL_WRITE),
            new Step("location", "location", "Your town",
                    "Can I know roughly where you are? Just the town, for the weather and nearby searches.",
                    "your rough location", Way.PERMISSIONS, WHERE),
            new Step("memory", "memory", "Memory",
                    "Want me to remember things when you ask me to? The notes stay on this phone and go along with each question.",
                    "nothing extra", Way.NOTHING),
            new Step("web", "web", "Web search",
                    "Should I search the web when a question needs it?",
                    "nothing extra", Way.NOTHING),
            new Step("texts", "texts", "Reading your texts",
                    "Want me to read your incoming texts to you? That needs notification access, a switch in Android's own "
                            + "settings. I'll open the page: find Sprocket, switch it on, then come back here.",
                    "notification access (a switch in Android's settings)", Way.NOTIFICATION_ACCESS),
            new Step("notifications", "notifications", "Reading your notifications",
                    "Should I be able to tell you what your other notifications say? It uses the same notification access.",
                    "notification access (a switch in Android's settings)", Way.NOTIFICATION_ACCESS),
            new Step("screen", "screen", "Screen control",
                    "Want me to tap through other apps for you when there's no shortcut? That's screen control, an "
                            + "Accessibility switch in Android's settings. I'll open it: find Sprocket (some phones list it under Installed apps) "
                            + "and switch it on. I still "
                            + "ask before anything that sends, buys or deletes.",
                    "screen control (an Accessibility switch in Android's settings)", Way.SCREEN_CONTROL),
            new Step("calls", "calls", "Call announcer",
                    "Want me to say who's calling when your phone is on vibrate, and listen for answer, decline or text? It "
                            + "needs screen control and notification access. You can set its hours later in Settings.",
                    "screen control and notification access, plus the microphone", Way.BOTH_SWITCHES, MIC),
            new Step("brief", "brief", "Morning brief",
                    "Want a morning brief at 6 am: the weather, your calendar, what's due, and news on topics you pick? I'll "
                            + "send a notification when it's ready.",
                    "permission to send notifications", Way.PERMISSIONS, POST),
            new Step("research", "research", "Research",
                    "Want me to write longer research reports in the background, when you ask or share an article with me? "
                            + "I'll send a notification when one is ready.",
                    "permission to send notifications", Way.PERMISSIONS, POST),
            new Step("github", "github", "GitHub",
                    "Do you use GitHub? I can keep an eye on your threads and repositories. I'll need your username.",
                    "your GitHub username", Way.GITHUB_NAME),
            new Step("assistant", null, "The side button",       // the owner, 2026-11-13: "do both" (README and setup)
                    "Want to open me by holding your phone's side button (or the home button), from any app and even the "
                            + "lock screen? That makes me your phone's digital assistant. I'll open Android's Default apps "
                            + "page: tap Digital assistant app, pick Sprocket, then come back here.",
                    "Sprocket picked as the phone's digital assistant (a choice in Android's settings)", Way.ASSISTANT),
    };

    static boolean running(int at) { return at >= 0 && at < STEPS.length; }

    /** The Settings switches setup asks about: all off on a new install until each one's Yes. */
    static java.util.List<String> features() {
        java.util.List<String> f = new java.util.ArrayList<>();
        for (Step s : STEPS) if (s.feature != null) f.add(s.feature);
        return f;
    }

    /** The step after this one, or OFF at the end. */
    static int next(int at) { return at + 1 < STEPS.length ? at + 1 : OFF; }

    /** The question as Sprocket asks it in the chat. */
    static String ask(int at) {
        return "(" + (at + 1) + " of " + STEPS.length + ") " + STEPS[at].ask;
    }

    static final String HELLO = "Hi, I'm Sprocket. Let's set me up: I'll go through what I can do, one thing at a time. "
            + "Tap Yes or Not now, or ask me anything about it first.";

    static final String DONE = "That's everything. You can change any of it in ⋯ > Settings, along with my voice, a "
            + "Notion key for your lists, and my personality. Ask me anything now. Try: what's the weather tomorrow?";

    static final String STILL_OFF = "That's still off. If Android said it's a restricted setting: open Sprocket's App "
            + "info, tap ⋮, choose Allow restricted settings, then switch it on. You can do that any time; I'll "
            + "move on for now.";

    static final String DENIED = "Android didn't allow it, so that part won't work until you allow it in Android's "
            + "settings. Moving on.";

    static final String ASSISTANT_NOT_YET = "I'm not the phone's assistant yet. You can pick me any time: Android's "
            + "Settings > Apps > Default apps > Digital assistant app (Choose default apps on a Samsung). Moving on.";

    static final String GITHUB_ASK = "What's your GitHub username? Type it below.";

    /** What Sprocket says after their answer, before the next question. */
    static String ack(Step s, boolean yes) {
        if (yes && s.way == Way.ASSISTANT)
            return "Done: hold the side button any time. On a Samsung, the side button opens Bixby until you change it: "
                    + "Settings > Advanced features > Side button > Press and hold > Digital assistant.";
        if (yes) return "Done.";
        return s.feature == null ? "Okay, skipped. I'll ask again if you ever need it." : "Okay, that stays off.";
    }

    /** A typed answer to the question on show: true = yes, false = not now, null = something else (a question for the
     * brain). Only a short reply counts, so "yes, but what does it read?" goes to the brain, not the switch. */
    static Boolean answer(String typed) {
        String t = typed == null ? "" : typed.trim().toLowerCase(Locale.US).replace('’', '\'');
        if (t.isEmpty() || t.contains("?") || t.split("\\s+").length > 4) return null;
        String w = " " + t.replaceAll("[^a-z' ]", " ").replaceAll("\\s+", " ").trim() + " ";
        for (String no : new String[]{"not now", "skip", "later", "no thanks", "no thank you"}) if (w.contains(" " + no + " ")) return false;
        return PhoneTools.answer(t, "yes");
    }

    /** A GitHub username as typed (one word, GitHub's letters), or null. */
    static String githubName(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (t.startsWith("@")) t = t.substring(1);
        if (t.matches("(?i)https?://(www\\.)?github\\.com/[A-Za-z0-9-]+/?")) t = t.replaceAll("(?i)https?://(www\\.)?github\\.com/", "").replace("/", "");
        return t.matches("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}") ? t : null;
    }

    /** For the brain, while a setup question is on show and they typed a question instead of answering it. */
    static String helpNote(int at) {
        Step s = STEPS[at];
        StringBuilder b = new StringBuilder("(From the app: the owner just installed Sprocket and is going through first-time "
                + "setup in this chat. The app asks one question at a time, with Yes and Not now buttons. Right now it is asking "
                + "about \"" + s.name + "\", which needs " + s.needs + ". They asked something instead of answering. Answer "
                + "it in two to four plain sentences, don't use any tools for it, and end by saying they can tap Yes or Not "
                + "now. Everything can be changed later in the ⋯ menu, under Settings. If Android blocks notification "
                + "access or screen control as a \"restricted setting\" (apps installed from outside the Play Store): open "
                + "Sprocket's App info, tap the ⋮ menu, choose Allow restricted settings, then switch it on. The whole list:");
        for (Step x : STEPS) b.append("\n- ").append(x.name).append(": ").append(x.ask);
        return b.append(")").toString();
    }
}
