package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scheduled tasks (roadmap, 11-19): "at 8 tomorrow, check the weather and only tell me if it'll rain", "every Friday at
 * 5, see if Rosie's has a deal". Kept in schedules.json; at the time, ScheduleService asks the brain his words while he
 * isn't watching and posts the answer as a notification (tap it: Sprocket opens and says it). Anything that sends,
 * calls or buys still waits for his yes, asked by notification, an hour at most (11-19). No Android here: the
 * times are local wall-clock times, so the tests run on the desktop.
 */
final class Schedules {
    static final int MOST = 20;
    /** What the brain answers when "only tell me if" isn't met: no notification then. */
    static final String QUIET = "NOTHING TO REPORT";
    /** How late a task may still run (the phone was off or asleep past its time). */
    static final int LATE_HOURS = 6;
    static final String[] REPEATS = {"once", "daily", "weekdays", "weekly"};
    /** A scheduled task asked to add or cancel scheduled tasks: only he does that, while he's there. */
    static final String NOT_WHILE_SCHEDULED = "Not done: a scheduled task can't add or cancel scheduled tasks. Only he can, "
            + "by asking Sprocket himself. Tell him in the answer if it matters.";
    /** Strict: February 30 is an error, not February 28 (Codex 055 #7). */
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    /**
     * The longest one run can take: the brain plus an hour for his yes (ScheduleService.ASK_WAIT_S) and a little more.
     * A run started longer ago than this was cut off (the app was stopped); the safety alarm wakes for it then.
     */
    static final int MAX_RUN_MIN = 100;

    static final class Task {
        final int id;
        final String what, onlyIf, repeat;
        LocalDateTime at;            // the next run, local wall-clock time
        LocalDateTime started;       // set (and saved) just before a run, cleared when it's done: a run cut off shows
                                     // here, and is reported instead of done twice (Codex 055b #4, #5)

        Task(int id, String what, String onlyIf, String repeat, LocalDateTime at) {
            this.id = id; this.what = what; this.onlyIf = onlyIf == null ? "" : onlyIf.trim();
            this.repeat = repeat == null || repeat.isEmpty() ? "once" : repeat; this.at = at;
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject().put("id", id).put("what", what).put("only_tell_if", onlyIf).put("repeat", repeat).put("at", AT.format(at));
            if (started != null) o.put("started", AT.format(started));
            return o;
        }

        /** The input that schedules this same task again (cancel's undo). */
        JSONObject input() throws Exception {
            return new JSONObject().put("what", what).put("at", AT.format(at)).put("repeat", repeat).put("only_tell_if", onlyIf);
        }

        /** "#3: check the weather -- Friday, November 21 at 5:00 PM, then every Friday (only tells him if: rain)" */
        String line() {
            String r = repeat.equals("daily") ? ", then every day" : repeat.equals("weekdays") ? ", then every weekday"
                    : repeat.equals("weekly") ? ", then every " + cap(at.getDayOfWeek().name().toLowerCase(Locale.US)) : "";
            return "#" + id + ": " + what + " — " + when(at) + r + (onlyIf.isEmpty() ? "" : " (only tells him if: " + onlyIf + ")");
        }
    }

    final List<Task> tasks = new ArrayList<>();
    int nextId = 1;

    static String when(LocalDateTime t) {
        return t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d 'at' h:mm a", Locale.US));
    }

    private static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** The next time after this one for a repeating task; null for once. */
    static LocalDateTime after(LocalDateTime t, String repeat) {
        switch (repeat) {
            case "daily": return t.plusDays(1);
            case "weekly": return t.plusWeeks(1);
            case "weekdays": {
                LocalDateTime n = t.plusDays(1);
                while (n.getDayOfWeek() == DayOfWeek.SATURDAY || n.getDayOfWeek() == DayOfWeek.SUNDAY) n = n.plusDays(1);
                return n;
            }
            default: return null;
        }
    }

    /** schedule_task: the text the brain reads back. Throws for what can't be scheduled (it says why). */
    String add(String what, String at, String repeat, String onlyIf, LocalDateTime now) throws Exception {
        if (what == null || what.trim().isEmpty()) throw new Exception("schedule_task needs what to do");
        if (repeat == null || repeat.isEmpty()) repeat = "once";
        if (!java.util.Arrays.asList(REPEATS).contains(repeat))
            throw new Exception("repeat is once, daily, weekdays or weekly (other patterns, like every 2 hours, aren't possible yet)");
        LocalDateTime t;
        try { t = LocalDateTime.parse(at.trim().length() <= 16 ? at.trim() : at.trim().substring(0, 16), AT); }
        catch (Exception e) { throw new Exception("at must be a local date and time, YYYY-MM-DDTHH:MM"); }
        if (repeat.equals("weekdays")) while (t.getDayOfWeek() == DayOfWeek.SATURDAY || t.getDayOfWeek() == DayOfWeek.SUNDAY) t = t.plusDays(1);
        if (!t.isAfter(now)) {
            if (repeat.equals("once")) throw new Exception("that time (" + when(t) + ") has already passed; it's " + when(now));
            while (!t.isAfter(now)) t = after(t, repeat);
        }
        if (tasks.size() >= MOST) throw new Exception("he already has " + MOST + " scheduled tasks; cancel one first");
        Task k = new Task(nextId++, what.trim(), onlyIf, repeat, t);
        tasks.add(k);
        return "Scheduled " + k.line() + ". At that time Sprocket does it while he isn't watching and tells him in a notification"
                + (k.onlyIf.isEmpty() ? "" : " (none if the answer is no)") + "; anything that sends, calls or buys still waits for his yes.";
    }

    /** scheduled_tasks: what's on, soonest first. */
    String list() {
        if (tasks.isEmpty()) return "Nothing scheduled.";
        List<Task> s = new ArrayList<>(tasks);
        s.sort((a, b) -> a.at.compareTo(b.at));
        StringBuilder b = new StringBuilder("Scheduled (soonest first):\n");
        for (Task k : s) b.append("- ").append(k.line()).append('\n');
        return b.toString().trim();
    }

    /** The task his words point to: "#3" / "3", or words from what it does ("the Friday one", "the pizza deal"). */
    Task find(String which) throws Exception {
        String w = which == null ? "" : which.trim().toLowerCase(Locale.US);
        if (tasks.isEmpty()) throw new Exception("nothing is scheduled");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^#?\\s*(\\d+)$").matcher(w);
        if (m.matches()) {
            for (Task k : tasks) if (k.id == Integer.parseInt(m.group(1))) return k;
            throw new Exception("no scheduled task #" + m.group(1) + "\n" + list());
        }
        List<Task> hits = new ArrayList<>();
        int best = 0;
        for (Task k : tasks) {
            String hay = (k.line()).toLowerCase(Locale.US);
            int score = 0;
            for (String word : w.split("[^a-z0-9']+"))
                if (word.length() > 2 && !STOP.contains(word) && hay.contains(word)) score++;
            if (score > best) { best = score; hits.clear(); }
            if (score == best && score > 0) hits.add(k);
        }
        if (hits.size() == 1) return hits.get(0);
        throw new Exception((hits.isEmpty() ? "no scheduled task matches “" + which + "”" : "more than one matches “" + which + "”: ask him which")
                + "\n" + list());
    }

    private static final java.util.Set<String> STOP = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "one", "that", "this", "task", "scheduled", "cancel", "delete", "remove", "stop", "my", "for", "and", "every"));

    Task cancel(String which) throws Exception {
        Task k = find(which);
        tasks.remove(k);
        return k;
    }

    /** Tasks whose time has come (at or before now), soonest first; not one already started. */
    List<Task> due(LocalDateTime now) {
        List<Task> d = new ArrayList<>();
        for (Task k : tasks) if (k.started == null && !k.at.isAfter(now)) d.add(k);
        d.sort((a, b) -> a.at.compareTo(b.at));
        return d;
    }

    /** True when it's too late to run this one now (more than LATE_HOURS past its time). */
    static boolean tooLate(Task k, LocalDateTime now) { return k.at.plusHours(LATE_HOURS).isBefore(now); }

    /** Still scheduled (not cancelled while it waited its turn: Codex 055 #1). */
    boolean has(Task k) { return tasks.contains(k); }

    /** Marks a run as started (saved before the brain is asked). */
    void claim(Task k, LocalDateTime now) { k.started = now; }

    /** Runs that were started and never finished (the app was stopped partway): reported, never run again. */
    List<Task> interrupted() {
        List<Task> d = new ArrayList<>();
        for (Task k : tasks) if (k.started != null) d.add(k);
        return d;
    }

    /** After a run (or a skip): a repeating task moves to its next time after now; a one-time task goes. */
    void done(Task k, LocalDateTime now) {
        k.started = null;
        LocalDateTime n = after(k.at, k.repeat);
        if (n == null) { tasks.remove(k); return; }
        while (!n.isAfter(now)) n = after(n, k.repeat);
        k.at = n;
    }

    /**
     * When the alarm should wake next, or null. While a run is going, only its safety time (start + MAX_RUN_MIN): the
     * tasks after it run in the same pass, and if the app is stopped mid-run the safety alarm still wakes it to report
     * that and carry on (Codex 055b #4).
     */
    LocalDateTime soonest() {
        for (Task k : tasks) if (k.started != null) return k.started.plusMinutes(MAX_RUN_MIN);
        LocalDateTime s = null;
        for (Task k : tasks) if (s == null || k.at.isBefore(s)) s = k.at;
        return s;
    }

    /** What the brain is asked at the time: his task, as his message in the chat. */
    static String question(Task k) { return "⏰ Scheduled: " + k.what; }

    /** The app's note with it: he isn't watching; how to answer; the "only tell me if" rule. */
    static String note(Task k, boolean late) {
        return "This is a task he scheduled earlier, running now by itself" + (late ? " (later than set: the phone was asleep or off)" : "")
                + ". He isn't watching: do it, then answer in one to three short sentences for a notification. Anything that "
                + "sends, calls or buys shows him a notification to approve, as usual."
                + (k.onlyIf.isEmpty() ? "" : " He only wants to hear about it if: " + k.onlyIf + ". If that isn't so, answer with "
                + "exactly " + QUIET + " and nothing else; if you couldn't find out, say that instead (that's worth telling him).");
    }

    /** True for the "nothing to tell him" answer. */
    static boolean quiet(String reply) {
        return reply != null && reply.trim().replaceAll("[^A-Za-z ]", "").trim().equalsIgnoreCase(QUIET);
    }

    JSONObject toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Task k : tasks) a.put(k.json());
        return new JSONObject().put("next_id", nextId).put("tasks", a);
    }

    static Schedules load(String json) {
        Schedules s = new Schedules();
        if (json == null || json.trim().isEmpty()) return s;
        try {
            JSONObject o = new JSONObject(json);
            s.nextId = o.optInt("next_id", 1);
            JSONArray a = o.optJSONArray("tasks");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject t = a.getJSONObject(i);
                Task k = new Task(t.getInt("id"), t.getString("what"), t.optString("only_tell_if"), t.optString("repeat", "once"),
                        LocalDateTime.parse(t.getString("at"), AT));
                if (!t.optString("started").isEmpty()) k.started = LocalDateTime.parse(t.getString("started"), AT);
                s.tasks.add(k);
                s.nextId = Math.max(s.nextId, t.getInt("id") + 1);
            }
        } catch (Exception ignored) { }
        return s;
    }

    /** The three tools (PhoneTools adds them to the list). */
    static void definitions(JSONArray tools) throws Exception {
        tools.put(new JSONObject().put("name", "schedule_task").put("description",
                "Do something LATER, once or on repeat: at that time Sprocket runs his words by itself while he isn't watching "
                        + "(checks, look-ups, things in his apps), then tells him in a notification; tapping it opens Sprocket and "
                        + "says it. Use it for “at 8 tomorrow check...”, “every Friday see if...”, “tell me "
                        + "at noon if...”. Anything that sends, calls or buys still waits for his yes (a notification asks; "
                        + "an hour without an answer skips it). A plain reminder for HIM to do something is set_reminder, not this.")
                .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("what", new JSONObject().put("type", "string").put("description",
                                "What to do then, as a request to Sprocket, complete on its own (“check today's weather here”, "
                                        + "“see if Rosie's Burgers has a deal on Eats”)."))
                        .put("at", new JSONObject().put("type", "string").put("description", "The first time, local: YYYY-MM-DDTHH:MM."))
                        .put("repeat", new JSONObject().put("type", "string").put("enum", new JSONArray(java.util.Arrays.asList(REPEATS)))
                                .put("description", "once (default), daily, weekdays, or weekly (same day each week)."))
                        .put("only_tell_if", new JSONObject().put("type", "string").put("description",
                                "Only when he said “only tell me if...”: the condition (“it will rain today”). Leave out otherwise.")))
                        .put("required", new JSONArray().put("what").put("at"))));
        tools.put(new JSONObject().put("name", "scheduled_tasks").put("description",
                "List the tasks he has scheduled: number, what, when, how often.")
                .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject())));
        tools.put(new JSONObject().put("name", "cancel_scheduled").put("description",
                "Cancel one scheduled task. If his words could fit more than one, it lists them: ask him which.")
                .put("input_schema", new JSONObject().put("type", "object").put("properties", new JSONObject()
                        .put("task", new JSONObject().put("type", "string").put("description",
                                "Its number from scheduled_tasks (“3”), or his words for it (“the pizza deal”).")))
                        .put("required", new JSONArray().put("task"))));
    }
}
