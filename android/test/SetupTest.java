package dev.sprocket.app;

import java.util.HashSet;
import java.util.Set;

/** First-time setup on the desktop JDK: every feature is asked about, short answers work the switches, anything else
 * goes to the brain, and GitHub usernames are read the way people type them. */
public class SetupTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    public static void main(String[] a) {
        // every switch in Settings is asked about, once
        Set<String> asked = new HashSet<>(), twice = new HashSet<>();
        for (Setup.Step s : Setup.STEPS) if (s.feature != null && !asked.add(s.feature)) twice.add(s.feature);
        Set<String> missing = new HashSet<>();
        for (String[] f : Config.FEATURES) if (!asked.contains(f[0])) missing.add(f[0]);
        eq("every feature in Settings is asked about", missing, "[]");
        eq("none twice", twice, "[]");
        Set<String> ids = new HashSet<>();
        for (Setup.Step s : Setup.STEPS) ids.add(s.id);
        eq("step ids are unique", ids.size(), Setup.STEPS.length);
        for (Setup.Step s : Setup.STEPS) {
            boolean needsPerms = s.way == Setup.Way.PERMISSIONS;
            if (needsPerms && s.perms.length == 0) eq(s.id + ": a permissions step names its permissions", 0, ">0");
            if (s.way == Setup.Way.NOTHING && s.perms.length > 0) eq(s.id + ": a nothing-extra step asks for nothing", s.perms.length, 0);
        }

        // the order
        eq("starts at the first question", Setup.running(0), true);
        eq("not running when off", Setup.running(Setup.OFF), false);
        eq("the last question ends it", Setup.next(Setup.STEPS.length - 1), Setup.OFF);
        eq("one at a time", Setup.next(0), 1);
        eq("numbered for them", Setup.ask(0).startsWith("(1 of " + Setup.STEPS.length + ") "), true);

        // typed answers: short ones work the switch, anything else is a question for the brain
        eq("yes", Setup.answer("yes"), true);
        eq("Sure!", Setup.answer("Sure!"), true);
        eq("ok go ahead", Setup.answer("ok go ahead"), true);
        eq("not now", Setup.answer("Not now"), false);
        eq("skip", Setup.answer("skip"), false);
        eq("no thanks", Setup.answer("no thanks"), false);
        eq("nope", Setup.answer("nope"), false);
        eq("later", Setup.answer("maybe later"), false);
        eq("a question with a yes in it goes to the brain", Setup.answer("yes but what does it read?"), null);
        eq("a question without a mark", Setup.answer("what does screen control actually do"), null);
        eq("a long answer goes to the brain", Setup.answer("ok but only if it never sends anything without asking"), null);
        eq("nothing", Setup.answer("  "), null);
        eq("a word that's neither", Setup.answer("hmm"), null);

        // GitHub usernames as typed
        eq("plain", Setup.githubName("octocat"), "octocat");
        eq("with an @", Setup.githubName("@octo-cat"), "octo-cat");
        eq("a profile link", Setup.githubName("https://github.com/octocat/"), "octocat");
        eq("two words aren't a name", Setup.githubName("my name"), null);
        eq("no leading dash", Setup.githubName("-octo"), null);
        eq("no double dash", Setup.githubName("oc--to"), null);
        eq("not too long", Setup.githubName("a234567890123456789012345678901234567890"), null);

        // what Sprocket says
        Setup.Step talk = Setup.STEPS[0], web = null;
        for (Setup.Step s : Setup.STEPS) if ("web".equals(s.id)) web = s;
        eq("a skipped permission is asked again later", Setup.ack(talk, false).contains("ask again"), true);
        eq("a skipped feature stays off", Setup.ack(web, false), "Okay, that stays off.");
        eq("the end points to Settings", Setup.DONE.contains("Settings"), true);

        // the brain's note while a question is on show
        String note = Setup.helpNote(0);
        eq("names the question on show", note.contains("\"" + talk.name + "\""), true);
        eq("says what it needs", note.contains(talk.needs), true);
        eq("no tools for a setup question", note.contains("don't use any tools"), true);
        eq("knows the restricted-settings way round", note.contains("Allow restricted settings"), true);
        eq("lists every question", note.split("\n- ").length - 1, Setup.STEPS.length);

        // a new install starts with every asked-about feature off, each waiting for its Yes (Codex 042 #3)
        java.util.Set<String> all = new java.util.TreeSet<>();
        for (String[] f : Config.FEATURES) all.add(f[0]);
        eq("all of Settings' switches wait for setup", new java.util.TreeSet<>(Setup.features()), all);

        System.out.println(fails == 0 ? "setup: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
