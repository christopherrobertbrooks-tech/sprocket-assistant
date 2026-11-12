package dev.sprocket.app;

import java.util.Arrays;
import java.util.List;

/** NameMatch on the desktop JDK: names in another spelling against a made-up contact list (the earlier test app's cases, plus
 * Sprocket's own: "Tony" for Toni, 2026-10-17). */
public class NameMatchTest {
    static int fails, n;

    static void check(boolean ok, String what) {
        n++;
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    static void unique(String heard, List<String> contacts, String want) {
        NameMatch.Result r = NameMatch.match(heard, contacts);
        check(want.equals(r.unique), heard + " -> " + want + " (got " + r.unique + ", choices " + r.choices + ", " + r.score + ")");
    }

    static void ask(String heard, List<String> contacts, String a, String b) {
        NameMatch.Result r = NameMatch.match(heard, contacts);
        check(r.unique == null && r.choices.contains(a) && r.choices.contains(b),
                heard + " -> ask " + a + " or " + b + " (got " + r.unique + ", " + r.choices + ")");
    }

    static void none(String heard, List<String> contacts) {
        NameMatch.Result r = NameMatch.match(heard, contacts);
        check(r.unique == null && r.choices.isEmpty(), heard + " -> nobody (got " + r.unique + ", " + r.choices + ")");
    }

    public static void main(String[] a) {
        List<String> book = Arrays.asList("Dee Thompson", "Mom", "Dad", "Jon Miller", "Katie", "Stephen Lee", "Sarah Cole",
                "Dr. Patel", "Mike's work", "Jennifer", "Toni");
        unique("Tony", book, "Toni");                 // The owner on his phone, 2026-10-17
        unique("Tonie", book, "Toni");
        unique("toni", book, "Toni");
        unique("Dea", book, "Dee Thompson");
        unique("Dhee", book, "Dee Thompson");
        unique("John", book, "Jon Miller");
        unique("Katy", book, "Katie");
        unique("Steven", book, "Stephen Lee");
        unique("Sara", book, "Sarah Cole");
        unique("my mom", book, "Mom");
        unique("Jen", book, "Jennifer");
        List<String> two = Arrays.asList("Dee Thompson", "Dian Wood", "Mom");
        unique("Dhee", two, "Dee Thompson");
        unique("Dian", two, "Dian Wood");
        ask("John", Arrays.asList("Jon Miller", "John Smith", "Mom"), "Jon Miller", "John Smith");
        ask("Katie", Arrays.asList("Katy Lane", "Katie Ross"), "Katy Lane", "Katie Ross");
        ask("Tony", Arrays.asList("Toni", "Tony Cole"), "Toni", "Tony Cole");   // both real: ask, don't guess
        none("Grandpa", book);
        none("the plumber", book);
        System.out.println(fails == 0 ? "names: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
