package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * App memory for screen control (the owner, 2026-11-12: "go ahead and start", after Magpie's research on Mika's per-app
 * knowledge files and the TAG-Nav paper). Short facts per app about where things are ("Battery health: Battery and
 * device care > Battery"), learned with the screen tool's note action after a task that worked, and handed back with
 * every read of that app, so a repeat skips the re-discovery taps. Facts only, small, nothing private; all of them on
 * the Memory screen to delete. Kept on the phone (files/app-notes.json). Plain Java: test/AppNotesTest.java.
 */
final class AppNotes {
    static final int MAX_FACTS = 12, MAX_APPS = 40, LONGEST = 160;

    static final class Fact {
        final long id;
        final String text;

        Fact(long id, String text) { this.id = id; this.text = text; }
    }

    /** One step on the phone, as Sprocket took it: what replay does again, by name, not by number. */
    static final class Step {
        final String kind, app, label, text;          // kind: open_app, open_screen, tap, type, scroll, back

        Step(String kind, String app, String label, String text) {
            this.kind = kind; this.app = app == null ? "" : app; this.label = label == null ? "" : label; this.text = text == null ? "" : text;
        }

        static Step open(String kind, String name) { return new Step(kind, "", name, ""); }
        static Step tap(String app, String label) { return new Step("tap", app, label, ""); }
        static Step type(String app, String label, String text) { return new Step("type", app, label, text); }
        static Step scroll(String app, String direction) { return new Step("scroll", app, direction, ""); }
        static Step back() { return new Step("back", "", "", ""); }

        /** As the brain reads it in the route question. */
        @Override public String toString() {
            switch (kind) {
                case "open_app": return "opened the app " + label;
                case "open_screen": return "opened " + label;
                case "tap": return "In " + app + ": tapped \"" + label + "\"";
                case "type": return "In " + app + ": typed \"" + text + "\" into \"" + label + "\"";
                case "scroll": return "In " + app + ": scrolled " + label;
                case "back": return "went back";
                default: return kind;
            }
        }

        /** As he reads it on the Memory screen, and the brain with the app's screen. */
        String said() {
            switch (kind) {
                case "open_app": return "open the app " + label;
                case "open_screen": return "open " + label;
                case "tap": return "tap \"" + label + "\"";
                case "type": return "type \"" + text + "\" into \"" + label + "\"";
                case "scroll": return "scroll " + label;
                case "back": return "go back";
                default: return kind;
            }
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("kind", kind).put("app", app).put("label", label).put("text", text);
        }

        static Step fromJson(JSONObject o) {
            return new Step(o.optString("kind"), o.optString("app"), o.optString("label"), o.optString("text"));
        }
    }

    /** A way to one place, learned from a task that worked: replayed by the go_to tool without the brain. */
    static final class Route {
        final long id;
        final String goal;
        final List<Step> steps = new ArrayList<>();

        Route(long id, String goal) { this.id = id; this.goal = goal; }

        String text() {
            List<String> said = new ArrayList<>();
            for (Step st : steps) said.add(st.said());
            return goal + ": " + String.join(" > ", said);
        }
    }

    static final class App {
        final String pkg;
        String name;
        long used;                                    // when a fact was last added: the least recently used app goes first
        final List<Fact> facts = new ArrayList<>();
        final List<Route> routes = new ArrayList<>();

        App(String pkg, String name) { this.pkg = pkg; this.name = name; }
    }

    final List<App> apps = new ArrayList<>();
    private long nextId = 1;

    // a phone or card number, an email address, or a secret: notes say where things are, never these
    private static final Pattern PRIVATE = Pattern.compile("\\d{5,}|\\d[\\d\\s().-]{6,}\\d|\\S+@\\S+\\.\\S+|"
            + "(?i)\\b(password|passcode|pin|code|cvv|ssn|security answer)\\b");

    private static String norm(String s) { return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim(); }

    private App app(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    /** Saves a fact about the app on screen; says what happened, for the brain. */
    String learn(String pkg, String appName, String fact, long at) {
        String t = fact == null ? "" : fact.trim().replaceAll("\\s+", " ");
        if (t.isEmpty() || pkg == null || pkg.isEmpty()) return "There was nothing to note.";
        if (Screen.blockedApp(pkg)) return "Not noted: that app is off limits to screen control.";
        if (PRIVATE.matcher(t).find())
            return "Not noted: app notes are about where things are, not numbers, addresses, passwords or codes.";
        if (t.length() > LONGEST) t = t.substring(0, LONGEST);
        App a = app(pkg);
        if (a == null) { a = new App(pkg, appName == null || appName.isEmpty() ? pkg : appName); apps.add(a); }
        for (Fact f : a.facts) if (norm(f.text).equals(norm(t))) return "Already noted.";
        String goal = goal(t);                         // "Software version: ..." -> a newer way replaces the old one
        boolean replaced = !goal.isEmpty() && a.facts.removeIf(f -> goal.equals(goal(f.text)));
        long id = Math.max(nextId, at);
        nextId = id + 1;
        a.facts.add(new Fact(id, t));
        a.used = id;
        if (appName != null && !appName.isEmpty()) a.name = appName;
        while (a.facts.size() > MAX_FACTS) a.facts.remove(0);
        apps.sort((x, y) -> Long.compare(x.used, y.used));
        while (apps.size() > MAX_APPS) apps.remove(0);
        return "Noted for " + a.name + (replaced ? " (replacing the old way)" : "") + ": " + t;
    }

    /** What a route is for: the words before its colon, normalised ("" when it has none). */
    private static String goal(String fact) {
        int colon = fact.indexOf(':');
        return colon <= 0 || colon > 60 ? "" : norm(fact.substring(0, colon));
    }

    /** What goes after a read of this app's screen: its facts, or "" when there are none. */
    String forScreen(String pkg) {
        App a = app(pkg);
        if (a == null || (a.facts.isEmpty() && a.routes.isEmpty())) return "";
        StringBuilder b = new StringBuilder("\nWhat you noted about ").append(a.name)
                .append(" on earlier visits (check it still matches the screen):\n");
        for (Fact f : a.facts) b.append("- ").append(f.text).append('\n');
        for (Route r : a.routes) b.append("- ").append(r.text()).append('\n');
        return b.toString();
    }

    // ------------------------------------------------------------------ learning after a screen task, and replay
    // Qwen never used the note action in the first live test (2026-11-12), so Sprocket asks after a task that tapped
    // through an app: code records the steps and decides when; the model only names the place and picks the steps.
    // Then a note couldn't shorten a route that was already shortest, so the route is replayed without the model.

    /** The question after a screen task: what he asked, the numbered steps, where it ended and the answer he got. */
    static String routePrompt(String asked, List<Step> steps, String endScreen, String reply) {
        StringBuilder b = new StringBuilder("They asked their phone assistant: \"").append(asked == null ? "" : asked.trim())
                .append("\"\nThe steps it took on the phone:\n");
        for (int i = 0; i < steps.size(); i++) b.append(i + 1).append(". ").append(steps.get(i)).append('\n');
        b.append("Where it ended:\n").append(endScreen == null ? "(not read)" : endScreen.trim()).append('\n')
                .append("Its answer: ").append(reply == null ? "" : reply.trim()).append("\n\n")
                .append("If these steps reached what they asked for, answer on ONE line: a short name for the place, then |, then ")
                .append("the numbers of the steps that make the shortest way there, in order, leaving out wrong turns. For ")
                .append("example: Gmail spam folder | 1, 4, 5, 6. If they didn't reach it, answer NONE. Nothing else.");
        return b.toString();
    }

    /** The place's name and the steps the brain picked. */
    static final class Choice {
        final String goal;
        final List<Integer> steps;

        Choice(String goal, List<Integer> steps) { this.goal = goal; this.steps = steps; }
    }

    /** The choice in that answer, or null for NONE, nothing, or steps that weren't taken. */
    static Choice choice(String answer, int taken) {
        if (answer == null) return null;
        for (String line : answer.replaceAll("(?s)<think>.*?</think>", "").split("\n")) {
            String t = line.trim().replaceAll("^[\"'\u201c]+|[\"'\u201d]+$", "").trim();
            if (t.isEmpty()) continue;
            int bar = t.indexOf('|');
            if (t.toUpperCase(Locale.US).startsWith("NONE") || bar <= 0) return null;
            String goal = t.substring(0, bar).trim();
            List<Integer> picked = new ArrayList<>();
            for (String n : t.substring(bar + 1).split("[,\\s]+")) {
                if (n.isEmpty()) continue;
                try { picked.add(Integer.parseInt(n)); } catch (NumberFormatException e) { return null; }
            }
            for (int k : picked) if (k < 1 || k > taken) return null;
            return goal.isEmpty() || picked.isEmpty() ? null : new Choice(goal, picked);
        }
        return null;
    }

    /** The steps the choice names, in its order. */
    static List<Step> pick(List<Step> trail, List<Integer> numbers) {
        List<Step> out = new ArrayList<>();
        for (int k : numbers) out.add(trail.get(k - 1));
        return out;
    }

    /** Saves a route for an app (a newer way to the same place replaces the old one); says what happened. */
    String learnRoute(String pkg, String appName, String goal, List<Step> steps, long at) {
        if (pkg == null || pkg.isEmpty() || goal == null || goal.trim().isEmpty() || steps.isEmpty()) return "There was nothing to note.";
        if (Screen.blockedApp(pkg)) return "Not noted: that app is off limits to screen control.";
        Route r = new Route(0, goal.trim());
        r.steps.addAll(steps);
        if (PRIVATE.matcher(r.text()).find())
            return "Not noted: app notes are about where things are, not numbers, addresses, passwords or codes.";
        App a = app(pkg);
        if (a == null) { a = new App(pkg, appName == null || appName.isEmpty() ? pkg : appName); apps.add(a); }
        String g = norm(r.goal);
        boolean replaced = a.routes.removeIf(x -> norm(x.goal).equals(g));
        long id = Math.max(nextId, at);
        nextId = id + 1;
        Route keep = new Route(id, r.goal);
        keep.steps.addAll(steps);
        a.routes.add(keep);
        a.used = id;
        if (appName != null && !appName.isEmpty()) a.name = appName;
        while (a.routes.size() > MAX_FACTS) a.routes.remove(0);
        apps.sort((x, y) -> Long.compare(x.used, y.used));
        while (apps.size() > MAX_APPS) apps.remove(0);
        return "Noted for " + a.name + (replaced ? " (replacing the old way)" : "") + ": " + keep.text();
    }

    private static final java.util.Set<String> SMALL = new java.util.HashSet<>(java.util.Arrays.asList("a", "an", "the", "my",
            "his", "to", "of", "and", "in", "on", "for", "it", "open", "show", "me", "go", "page", "screen", "please", "app"));

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : norm(s).split(" ")) if (!w.isEmpty() && !SMALL.contains(w)) out.add(w);
        return out;
    }

    /** The saved route his words fit best (most of its name's words in them), or null. */
    Route find(String asked) {
        List<String> have = words(asked == null ? "" : asked);
        Route best = null;
        double top = 0;
        for (App a : apps)
            for (Route r : a.routes) {
                List<String> want = words(r.goal);
                if (want.isEmpty()) continue;
                int hit = 0;
                for (String w : want) if (have.contains(w)) hit++;
                double score = (double) hit / want.size();
                if (score > top) { top = score; best = r; }
            }
        return top >= 0.6 ? best : null;
    }

    /**
     * A line to go with his question when a saved way might fit it, or "" (11-12: "show me my spam emails" got "it's
     * already on your screen" without a look, and no go_to). Looser than find(): his own words, and half of the place's
     * words once the app's name is left out ("spam folder" for "Gmail spam folder").
     */
    String hint(String asked) {
        List<String> have = words(asked == null ? "" : asked);
        Route best = null;
        double top = 0;
        for (App a : apps)
            for (Route r : a.routes) {
                List<String> want = words(r.goal);
                want.removeAll(words(a.name));
                if (want.isEmpty()) continue;
                int hit = 0;
                for (String w : want) if (have.contains(w)) hit++;
                double score = (double) hit / want.size();
                if (score > top) { top = score; best = r; }
            }
        if (top < 0.5) return "";
        return "\n\n(Sprocket: a saved way might fit this: go_to \"" + best.goal + "\". While they ask, Sprocket's own screen "
                + "is up, so nothing else is on their screen until you go there.)";
    }

    /** The app a route belongs to. */
    App appOf(Route r) {
        for (App a : apps) if (a.routes.contains(r)) return a;
        return null;
    }

    private static String bare(String label) {
        return label == null ? "" : label.toLowerCase(Locale.US).replaceAll("[0-9]", " ").replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** The same button: its words without the counts ("Spam, 246" and "Spam, 250"), all there. */
    static boolean sameLabel(String recorded, String now) {
        String r = bare(recorded), n = bare(now);
        if (r.isEmpty()) return false;
        if (r.equals(n)) return true;
        List<String> have = java.util.Arrays.asList(n.split(" "));
        for (String w : r.split(" ")) if (!have.contains(w)) return false;
        return true;
    }

    /** The 1-based number of the first item with the recorded label, or 0. */
    static int itemFor(String recorded, List<String> labels) {
        for (int i = 0; i < labels.size(); i++) if (sameLabel(recorded, labels.get(i))) return i + 1;
        return 0;
    }

    /**
     * The label replay looks for after step i (0-based) skips its scrolls: the next tap or type's label, or "" (11-13:
     * a route saved after a fumble had five scrolls in a row and replay did them all, past Spam).
     */
    static String nextTarget(List<Step> steps, int i) {
        for (int k = i + 1; k < steps.size(); k++) {
            String kind = steps.get(k).kind;
            if (kind.equals("tap") || kind.equals("type")) return steps.get(k).label;
            if (!kind.equals("scroll")) return "";
        }
        return "";
    }

    boolean delete(String pkg, long id) {
        App a = app(pkg);
        if (a == null) return false;
        boolean gone = a.facts.removeIf(f -> f.id == id) | a.routes.removeIf(r -> r.id == id);
        if (a.facts.isEmpty() && a.routes.isEmpty()) apps.remove(a);
        return gone;
    }

    /** "App: fact" a line each, for the Memory screen. */
    List<String> listing() {
        List<String> out = new ArrayList<>();
        for (App a : apps) {
            for (Fact f : a.facts) out.add(a.name + ": " + f.text);
            for (Route r : a.routes) out.add(a.name + ": " + r.text());
        }
        return out;
    }

    String toJson() throws Exception {
        JSONArray all = new JSONArray();
        for (App a : apps) {
            JSONArray facts = new JSONArray();
            for (Fact f : a.facts) facts.put(new JSONObject().put("id", f.id).put("text", f.text));
            JSONArray routes = new JSONArray();
            for (Route r : a.routes) {
                JSONArray steps = new JSONArray();
                for (Step st : r.steps) steps.put(st.toJson());
                routes.put(new JSONObject().put("id", r.id).put("goal", r.goal).put("steps", steps));
            }
            all.put(new JSONObject().put("pkg", a.pkg).put("name", a.name).put("used", a.used).put("facts", facts).put("routes", routes));
        }
        return new JSONObject().put("version", 1).put("next_id", nextId).put("apps", all).toString();
    }

    static AppNotes fromJson(String s) {
        AppNotes n = new AppNotes();
        try {
            JSONObject j = new JSONObject(s);
            n.nextId = j.optLong("next_id", 1);
            JSONArray all = j.getJSONArray("apps");
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                App a = new App(o.getString("pkg"), o.optString("name", o.getString("pkg")));
                a.used = o.optLong("used");
                JSONArray facts = o.optJSONArray("facts");
                for (int k = 0; facts != null && k < facts.length(); k++)
                    a.facts.add(new Fact(facts.getJSONObject(k).optLong("id"), facts.getJSONObject(k).optString("text")));
                JSONArray routes = o.optJSONArray("routes");
                for (int k = 0; routes != null && k < routes.length(); k++) {
                    JSONObject ro = routes.getJSONObject(k);
                    Route r = new Route(ro.optLong("id"), ro.optString("goal"));
                    JSONArray steps = ro.optJSONArray("steps");
                    for (int m = 0; steps != null && m < steps.length(); m++) r.steps.add(Step.fromJson(steps.getJSONObject(m)));
                    if (!r.steps.isEmpty()) a.routes.add(r);
                }
                if (!a.facts.isEmpty() || !a.routes.isEmpty()) n.apps.add(a);
            }
        } catch (Exception e) {
            return new AppNotes();
        }
        return n;
    }
}
