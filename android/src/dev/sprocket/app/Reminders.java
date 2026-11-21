package dev.sprocket.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reminders (11-20: "Remind me at the drugstore at 4:30" set a Clock alarm that never got saved, and the owner wanted
 * a reminder, not an alarm): a Sprocket notification at the time, kept in reminders.json and woken by ReminderAlarm, so
 * nothing depends on another app taking a hand-off. No Android here: local wall-clock times, tested on the desktop.
 */
final class Reminders {
    static final int MOST = 50;
    /** How late a reminder is still shown (the phone was off past its time): later than this it's shown as missed. */
    static final int LATE_HOURS = 12;
    private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SAY = DateTimeFormatter.ofPattern("h:mm a", Locale.US),
            DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);

    static final class Item {
        final int id;
        final String text;
        final LocalDateTime at;

        Item(int id, String text, LocalDateTime at) { this.id = id; this.text = text; this.at = at; }

        JSONObject json() throws Exception { return new JSONObject().put("id", id).put("text", text).put("at", AT.format(at)); }
    }

    final List<Item> items = new ArrayList<>();
    int nextId = 1;

    static Reminders load(String json) {
        Reminders r = new Reminders();
        try {
            JSONObject o = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
            r.nextId = Math.max(1, o.optInt("next_id", 1));
            JSONArray a = o.optJSONArray("reminders");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject x = a.getJSONObject(i);
                LocalDateTime at = parse(x.optString("at"));
                if (at != null && !x.optString("text").isEmpty()) r.items.add(new Item(x.getInt("id"), x.getString("text"), at));
            }
        } catch (Exception ignored) { }
        return r;
    }

    JSONObject toJson() throws Exception {
        JSONArray a = new JSONArray();
        for (Item i : items) a.put(i.json());
        return new JSONObject().put("next_id", nextId).put("reminders", a);
    }

    static LocalDateTime parse(String s) {
        try { return LocalDateTime.parse(s.trim(), AT); } catch (Exception e) { return null; }
    }

    /** Adds one; throws a plain reason the brain can tell them. */
    Item add(String text, String at, LocalDateTime now) throws Exception {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) throw new Exception("say what to remind them of");
        LocalDateTime when = parse(at == null ? "" : at);
        if (when == null) throw new Exception("the time must be YYYY-MM-DDTHH:MM");
        if (!when.isAfter(now)) throw new Exception("that time has already passed");
        if (items.size() >= MOST) throw new Exception("they already have " + MOST + " reminders waiting; cancel some first");
        Item i = new Item(nextId++, t, when);
        items.add(i);
        items.sort((a, b) -> a.at.compareTo(b.at));
        return i;
    }

    Item cancel(int id) {
        for (Item i : items) if (i.id == id) { items.remove(i); return i; }
        return null;
    }

    /** Takes out and returns every reminder whose time has come. */
    List<Item> takeDue(LocalDateTime now) {
        List<Item> due = new ArrayList<>();
        for (Item i : new ArrayList<>(items)) if (!i.at.isAfter(now)) { due.add(i); items.remove(i); }
        return due;
    }

    LocalDateTime soonest() { return items.isEmpty() ? null : items.get(0).at; }

    /** "4:30 PM today", "8:00 AM tomorrow", "9:00 AM Tue Nov 24". */
    static String when(LocalDateTime at, LocalDateTime now) {
        String day = at.toLocalDate().equals(now.toLocalDate()) ? "today"
                : at.toLocalDate().equals(now.toLocalDate().plusDays(1)) ? "tomorrow" : DAY.format(at);
        return SAY.format(at) + " " + day;
    }

    /** The notification's title: on time, or how late it is (the phone was off). */
    static String title(Item i, LocalDateTime now) {
        long late = java.time.Duration.between(i.at, now).toMinutes();
        if (late >= LATE_HOURS * 60) return "Missed reminder (" + SAY.format(i.at) + ")";
        return late >= 5 ? "Reminder (for " + SAY.format(i.at) + ")" : "Reminder";
    }

    String list(LocalDateTime now) {
        if (items.isEmpty()) return "No reminders waiting.";
        StringBuilder b = new StringBuilder();
        for (Item i : items) b.append(b.length() > 0 ? "\n" : "").append('#').append(i.id).append(' ').append(when(i.at, now)).append(": ").append(i.text);
        return b.toString();
    }
}
