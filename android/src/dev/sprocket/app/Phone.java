package dev.sprocket.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.SmsManager;

import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 3: the phone tools, done on the phone (worker thread). Timers, alarms, opening apps, the flashlight, volume
 * and Do Not Disturb follow the earlier test app's Act.java (copied, not shared). New here: texts are SENT and calls PLACED by
 * Sprocket, but only after he taps the card that shows exactly what will happen (the owner 2026-10-17); the calendar is
 * read and written directly. Each tool answers Claude in plain words; anything real it did is also recorded in the
 * chat. Failures throw with a plain reason, which Claude hears as the tool's error.
 */
final class Phone {
    private Phone() { }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US),
            TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    static String run(Context c, String name, JSONObject in) throws Exception {
        switch (name) {
            case "set_timer": return timer(c, in.optInt("seconds"), in.optString("label"));
            case "set_alarm": {
                int[] t = PhoneTools.hhmm(in.optString("time"));
                if (t == null) throw new Exception("the time must be HH:MM, 24-hour");
                return alarm(c, t[0], t[1], in.optString("label"), PhoneTools.days(in.optJSONArray("repeat_days")));
            }
            case "set_reminder": return reminder(c, in.optString("text"), in.optString("at"));
            case "send_text": return text(c, in.optString("to"), in.optString("message"));
            case "call": return call(c, in.optString("to"));
            case "find_contact": return findContact(c, in.optString("name"));
            case "check_calendar": return calendar(c, in.optString("from"), in.optString("to"));
            case "add_event": {
                long start = PhoneTools.at(in.optString("start"), ZoneId.systemDefault());
                if (start < 0) throw new Exception("the start must be YYYY-MM-DDTHH:MM or YYYY-MM-DD");
                return addEvent(c, in.optString("title"), start, in.optString("start").trim().length() <= 10,
                        in.optInt("minutes", 60), in.optString("location"), false);
            }
            case "open_app": return openApp(c, in.optString("name"));
            case "phone_control": return control(c, in);
            case "phone_status": return status(c);
            case "media": return media(c, in.optString("command"), in.optString("query"));
            case "maps": return maps(c, in.optString("action"), in.optString("place"), in.optString("mode", "drive"));
            case "open_screen": return openScreen(c, in.optString("screen"));
            case "clock_control": return clockControl(c, in);
            case "missed_calls": return missedCalls(c);
            case "email": return email(c, in.optString("to"), in.optString("subject"), in.optString("body"));
            case "add_contact": return addContact(c, in.optString("name"), in.optString("number"), in.optString("email"));
            case "share_location": return shareLocation(c, in.optString("to"));
            case "screen": return ScreenHands.run(in);
            default: throw new Exception("there is no tool called " + name);
        }
    }

    private static void start(Context c, Intent i) throws Exception {
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));   // its own app, not inside Sprocket
        } catch (ActivityNotFoundException e) {
            throw new Exception("no app on this phone can do that");
        }
    }

    private static String length(int secs) {
        int h = secs / 3600, m = secs % 3600 / 60, s = secs % 60;
        StringBuilder b = new StringBuilder();
        if (h > 0) b.append(h).append(h == 1 ? " hour" : " hours");
        if (m > 0) b.append(b.length() > 0 ? " " : "").append(m).append(m == 1 ? " minute" : " minutes");
        if (s > 0 || b.length() == 0) b.append(b.length() > 0 ? " " : "").append(s).append(s == 1 ? " second" : " seconds");
        return b.toString();
    }

    private static String clock(int h, int m) { return LocalDateTime.of(2000, 1, 1, h, m).format(TIME); }

    // ---------------------------------------------------------------- the Clock app (the earlier test app's way: no Clock screen)
    private static String timer(Context c, int secs, String label) throws Exception {
        if (secs <= 0 || secs > 24 * 3600) throw new Exception("a timer must be between 1 second and 24 hours");
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        start(c, i);
        String what = "Timer: " + length(secs) + (label.isEmpty() ? "" : ", " + label);
        Hands.record(what);
        return what + " (started in the Clock app).";
    }

    private static String alarm(Context c, int h, int m, String label, List<Integer> days) throws Exception {
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        if (!days.isEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, new ArrayList<>(days));
        start(c, i);
        StringBuilder rep = new StringBuilder();
        for (int d : days) rep.append(rep.length() > 0 ? ", " : "").append(PhoneTools.DAY_NAMES[d - 1].substring(0, 3));
        String what = "Alarm: " + clock(h, m) + (label.isEmpty() ? "" : ", " + label) + (days.isEmpty() ? "" : " (every " + rep + ")");
        Hands.record(what);
        return what + " (set in the Clock app).";
    }

    private static String reminder(Context c, String text, String at) throws Exception {
        ZoneId z = ZoneId.systemDefault();
        long when = PhoneTools.at(at, z), now = System.currentTimeMillis();
        if (when < 0) throw new Exception("the time must be YYYY-MM-DDTHH:MM");
        switch (PhoneTools.route(now, when)) {
            case "past": throw new Exception("that time has already passed");
            case "timer": return timer(c, (int) Math.round((when - now) / 1000.0), text) + " It rings then, as a reminder.";
            case "alarm": {
                LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(when), z);
                return alarm(c, t.getHour(), t.getMinute(), text, new ArrayList<>()) + " It rings then, as a reminder.";
            }
            default: return addEvent(c, text, when, false, 15, "", true);
        }
    }

    // ---------------------------------------------------------------- contacts, texts, calls
    /** Matching contacts: name -> their numbers (mobile first). */
    private static Map<String, List<String>> contacts(Context c, String name) throws Exception {
        if (!Hands.need(Manifest.permission.READ_CONTACTS)) throw new Exception("Sprocket isn't allowed to read contacts");
        String n = name.replaceFirst("(?i)^(my|the)\\s+", "").trim();
        Map<String, List<String>> out = new LinkedHashMap<>();
        String[] cols = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE};
        for (String like : new String[]{n + "%", "% " + n + "%"}) {
            try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
                    cols[0] + " LIKE ?", new String[]{like}, cols[0])) {
                while (cur != null && cur.moveToNext()) {
                    List<String> nums = out.computeIfAbsent(cur.getString(0), k -> new ArrayList<>());
                    String num = cur.getString(1).replaceAll("[^0-9+]", "");
                    if (nums.contains(num)) continue;
                    if (cur.getInt(2) == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) nums.add(0, num); else nums.add(num);
                }
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /** Every contact's name (on the phone only), for matching a name by how it sounds. */
    private static List<String> allNames(Context c) {
        List<String> out = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI,
                new String[]{ContactsContract.Contacts.DISPLAY_NAME_PRIMARY}, ContactsContract.Contacts.HAS_PHONE_NUMBER + "=1",
                null, null)) {
            while (cur != null && cur.moveToNext()) {
                String n = cur.getString(0);
                if (n != null && !n.trim().isEmpty() && !n.matches("[0-9 ()+.-]+")) out.add(n.trim());
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {name, number} for who he means, or a plain reason Claude can ask him about. */
    static String[] who(Context c, String to) throws Exception {
        if (to.trim().isEmpty()) throw new Exception("who to? No name or number was given");
        String num = PhoneTools.number(to);
        if (num != null) return new String[]{num, num};
        Map<String, List<String>> spelled = contacts(c, to);
        PhoneTools.Pick p = PhoneTools.resolve(to, spelled.keySet(), spelled.isEmpty() ? allNames(c) : new ArrayList<>());
        if (p.name == null && !p.choices.isEmpty())
            throw new Exception("more than one contact could be " + to + ": " + String.join(", ", p.choices) + ". Ask the boss which");
        if (p.name == null) throw new Exception("no contact called " + to + " (or anything like it) on this phone; ask the boss for the number");
        List<String> nums = spelled.containsKey(p.name) ? spelled.get(p.name) : contacts(c, p.name).get(p.name);
        if (nums == null || nums.isEmpty()) throw new Exception(p.name + " has no phone number");
        return new String[]{p.name, nums.get(0)};
    }

    private static String text(Context c, String to, String message) throws Exception { return text(c, to, message, null); }

    /** recordAs: what the chat's record says instead of the words (his location link stays out of it). */
    private static String text(Context c, String to, String message, String recordAs) throws Exception {
        if (message.trim().isEmpty()) throw new Exception("there is no message to send");
        String[] w = who(c, to);
        if (!Hands.need(Manifest.permission.SEND_SMS)) throw new Exception("Sprocket isn't allowed to send texts");
        Boolean yes = Hands.confirm(PhoneTools.textCard(w[0], w[1], message), "Send");
        if (yes == null) return "Nothing was sent: the boss didn't answer the card.";
        if (!yes) return "Nothing was sent: the boss cancelled.";
        String result = sms(c, w[1], message);
        if (result == null) {
            Hands.record(recordAs != null ? recordAs + " " + w[0] : "Texted " + w[0] + (w[0].equals(w[1]) ? "" : " (" + w[1] + ")") + ": “" + message + "”");
            return "Sent to " + w[0] + ".";
        }
        throw new Exception("the text did not go: " + result);
    }

    /** Sends and waits for the phone to say each part went (null) or why not. */
    static String sms(Context c, String number, String message) throws Exception {
        SmsManager sm = Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
        ArrayList<String> parts = sm.divideMessage(message);
        String action = "dev.sprocket.app.SMS_SENT." + System.nanoTime();
        CountDownLatch left = new CountDownLatch(parts.size());
        AtomicInteger bad = new AtomicInteger(0);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context x, Intent i) {
                if (getResultCode() != android.app.Activity.RESULT_OK) bad.set(getResultCode());
                left.countDown();
            }
        };
        if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        else c.registerReceiver(r, new IntentFilter(action));
        try {
            ArrayList<PendingIntent> sent = new ArrayList<>();
            for (int k = 0; k < parts.size(); k++)
                sent.add(PendingIntent.getBroadcast(c, k, new Intent(action).setPackage(c.getPackageName()), PendingIntent.FLAG_IMMUTABLE));
            sm.sendMultipartTextMessage(number, null, parts, sent, null);
            if (!left.await(45, TimeUnit.SECONDS)) return "the phone never confirmed it went out (no signal?)";
            return bad.get() == 0 ? null : "the phone refused it (error " + bad.get() + ")";
        } finally {
            c.unregisterReceiver(r);
        }
    }

    private static String call(Context c, String to) throws Exception {
        String[] w = who(c, to);
        if (!Hands.need(Manifest.permission.CALL_PHONE)) throw new Exception("Sprocket isn't allowed to place calls");
        Boolean yes = Hands.confirm(PhoneTools.callCard(w[0], w[1]), "Call");
        if (yes == null) return "No call: the boss didn't answer the card.";
        if (!yes) return "No call: the boss cancelled.";
        start(c, new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(w[1]))));
        Hands.record("Called " + w[0] + (w[0].equals(w[1]) ? "" : " (" + w[1] + ")"));
        return "Calling " + w[0] + " now.";
    }

    private static String findContact(Context c, String name) throws Exception {
        Map<String, List<String>> found = contacts(c, name);
        if (found.isEmpty()) {   // no spelling match: the ones it sounds like
            PhoneTools.Pick p = PhoneTools.resolve(name, found.keySet(), allNames(c));
            List<String> close = p.name != null ? java.util.Collections.singletonList(p.name) : p.choices;
            for (String k : close) found.putAll(contacts(c, k));
            if (found.isEmpty()) return "No contact matches " + name + ".";
        }
        StringBuilder b = new StringBuilder();
        int k = 0;
        for (Map.Entry<String, List<String>> e : found.entrySet())
            if (k++ < 8) b.append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append('\n');
        return b.toString().trim();
    }

    // ---------------------------------------------------------------- the calendar
    private static String calendar(Context c, String from, String to) throws Exception {
        if (!Hands.need(Manifest.permission.READ_CALENDAR)) throw new Exception("Sprocket isn't allowed to read the calendar");
        ZoneId z = ZoneId.systemDefault();
        LocalDate a = LocalDate.parse(from.trim()), b = to.trim().isEmpty() ? a : LocalDate.parse(to.trim());
        long begin = a.atStartOfDay(z).toInstant().toEpochMilli(), end = b.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
        Uri.Builder u = CalendarContract.Instances.CONTENT_URI.buildUpon();
        android.content.ContentUris.appendId(u, begin);
        android.content.ContentUris.appendId(u, end);
        String[] cols = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION};
        StringBuilder out = new StringBuilder();
        int n = 0;
        try (Cursor cur = c.getContentResolver().query(u.build(), cols, null, null, CalendarContract.Instances.BEGIN)) {
            while (cur != null && cur.moveToNext() && n < 30) {
                n++;
                boolean allDay = cur.getInt(3) == 1;
                ZoneId shown = allDay ? ZoneOffset.UTC : z;   // all-day events are stored at UTC midnight
                LocalDateTime s = LocalDateTime.ofInstant(Instant.ofEpochMilli(cur.getLong(1)), shown),
                        e = LocalDateTime.ofInstant(Instant.ofEpochMilli(cur.getLong(2)), shown);
                out.append(s.format(DAY)).append(": ").append(allDay ? "all day" : s.format(TIME) + " to " + e.format(TIME))
                        .append(", ").append(cur.getString(0));
                String where = cur.getString(4);
                if (where != null && !where.isEmpty()) out.append(" (at ").append(where).append(')');
                out.append('\n');
            }
        }
        return n == 0 ? "No events from " + a.format(DAY) + (b.equals(a) ? "" : " to " + b.format(DAY)) + "." : out.toString().trim();
    }

    /** The calendar to write to: his primary one, else a Google one he can edit, else any visible one he can edit. */
    private static long calendarId(ContentResolver cr) throws Exception {
        String[] cols = {CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE};
        long best = -1;
        int score = -1;
        try (Cursor cur = cr.query(CalendarContract.Calendars.CONTENT_URI, cols, CalendarContract.Calendars.VISIBLE + "=1 AND "
                + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=" + CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR, null, null)) {
            while (cur != null && cur.moveToNext()) {
                int s = (cur.getInt(1) == 1 ? 2 : 0) + ("com.google".equals(cur.getString(2)) ? 1 : 0);
                if (s > score) { score = s; best = cur.getLong(0); }
            }
        }
        if (best < 0) throw new Exception("there is no calendar on this phone Sprocket can write to");
        return best;
    }

    private static String addEvent(Context c, String title, long start, boolean allDay, int minutes, String where, boolean alert)
            throws Exception {
        if (title.trim().isEmpty()) throw new Exception("the event needs a title");
        if (!Hands.need(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            throw new Exception("Sprocket isn't allowed to change the calendar");
        ContentResolver cr = c.getContentResolver();
        ZoneId z = ZoneId.systemDefault();
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.CALENDAR_ID, calendarId(cr));
        v.put(CalendarContract.Events.TITLE, title.trim());
        if (!where.isEmpty()) v.put(CalendarContract.Events.EVENT_LOCATION, where);
        LocalDateTime local = LocalDateTime.ofInstant(Instant.ofEpochMilli(start), z);
        if (allDay) {   // all-day events live at UTC midnight
            long utc = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            v.put(CalendarContract.Events.ALL_DAY, 1);
            v.put(CalendarContract.Events.DTSTART, utc);
            v.put(CalendarContract.Events.DTEND, utc + 86400000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        } else {
            v.put(CalendarContract.Events.DTSTART, start);
            v.put(CalendarContract.Events.DTEND, start + Math.max(5, minutes) * 60000L);
            v.put(CalendarContract.Events.EVENT_TIMEZONE, z.getId());
        }
        Uri made = cr.insert(CalendarContract.Events.CONTENT_URI, v);
        if (made == null) throw new Exception("the calendar refused the event");
        if (alert) {
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, Long.parseLong(made.getLastPathSegment()));
            r.put(CalendarContract.Reminders.MINUTES, 0);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
        }
        String what = (alert ? "Reminder (calendar): " : "Calendar: ") + title.trim() + ", " + local.format(DAY)
                + (allDay ? ", all day" : " " + local.format(TIME));
        Hands.record(what);
        return what + (alert ? ", with an alert at that time." : ".");
    }

    // ---------------------------------------------------------------- apps and settings (the earlier test app's ways)
    private static String norm(String t) { return t.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", ""); }

    private static String openApp(Context c, String name) throws Exception {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        List<String> labels = new ArrayList<>();
        for (ResolveInfo r : apps) labels.add(String.valueOf(r.loadLabel(pm)));
        int pick = PhoneTools.pickApp(name, labels);
        if (pick < 0) throw new Exception("no app called " + name + " on this phone");
        ResolveInfo best = apps.get(pick);
        Intent i = pm.getLaunchIntentForPackage(best.activityInfo.packageName);
        if (i == null) throw new Exception(name + " can't be opened");
        start(c, i);
        String label = String.valueOf(best.loadLabel(pm));
        Hands.record("Opened " + label);
        return "Opened " + label + ".";
    }

    private static String control(Context c, JSONObject in) throws Exception {
        switch (in.optString("setting")) {
            case "flashlight": {
                CameraManager cm = c.getSystemService(CameraManager.class);
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                    Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                    if (Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) && facing != null
                            && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cm.setTorchMode(id, in.optBoolean("on"));
                        Hands.record("Flashlight " + (in.optBoolean("on") ? "on" : "off"));
                        return "Flashlight " + (in.optBoolean("on") ? "on." : "off.");
                    }
                }
                throw new Exception("this phone has no flashlight Sprocket can reach");
            }
            case "brightness": return brightness(c, in);
            case "volume": {
                AudioManager am = c.getSystemService(AudioManager.class);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int now = Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
                int pct = in.has("direction") ? PhoneTools.step(now, in.optString("direction"))
                        : Math.max(0, Math.min(100, in.optInt("percent", 50)));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * pct / 100f),
                        AudioManager.FLAG_SHOW_UI);
                Hands.record("Media volume " + pct + "%");
                return "Media volume set to " + pct + "%.";
            }
            case "do_not_disturb": case "ringer": {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                if (!nm.isNotificationPolicyAccessGranted()) {
                    start(c, new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
                    throw new Exception("Sprocket needs Do Not Disturb access first: the settings list is open on the phone; "
                            + "the boss turns Sprocket on there, then asks again");
                }
                if (in.optString("setting").equals("do_not_disturb")) {
                    nm.setInterruptionFilter(in.optBoolean("on") ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            : NotificationManager.INTERRUPTION_FILTER_ALL);
                    Hands.record("Do Not Disturb " + (in.optBoolean("on") ? "on" : "off"));
                    return "Do Not Disturb is " + (in.optBoolean("on") ? "on." : "off.");
                }
                String mode = in.optString("mode", "normal");
                c.getSystemService(AudioManager.class).setRingerMode(mode.equals("silent") ? AudioManager.RINGER_MODE_SILENT
                        : mode.equals("vibrate") ? AudioManager.RINGER_MODE_VIBRATE : AudioManager.RINGER_MODE_NORMAL);
                Hands.record("Ringer: " + mode);
                return "Ringer set to " + mode + ".";
            }
            default: throw new Exception("unknown setting " + in.optString("setting"));
        }
    }

    /** Screen brightness: needs his "Modify system settings" switch for Sprocket (opened for him the first time). */
    private static String brightness(Context c, JSONObject in) throws Exception {
        if (!Settings.System.canWrite(c)) {
            start(c, new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName())));
            throw new Exception("Sprocket needs the \"Modify system settings\" switch first: it is open on the phone; the boss "
                    + "turns it on for Sprocket, then asks again");
        }
        ContentResolver r = c.getContentResolver();
        if (in.has("auto")) {
            boolean auto = in.optBoolean("auto");
            Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, auto ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                    : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            if (auto) { Hands.record("Brightness: automatic"); return "Brightness is automatic."; }
        }
        int now = PhoneTools.percent(Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128));
        int pct = in.has("direction") ? PhoneTools.step(now, in.optString("direction")) : Math.max(0, Math.min(100, in.optInt("percent", now)));
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, PhoneTools.brightness(pct));
        Hands.record("Brightness " + pct + "%");
        return "Brightness set to " + pct + "% (automatic brightness off).";
    }

    // ---------------------------------------------------------------- the rest of the earlier test app's commands
    /** Media keys go to whatever was playing last; "play X" asks his music app to find and play it. */
    private static String media(Context c, String command, String query) throws Exception {
        AudioManager am = c.getSystemService(AudioManager.class);
        if (command.equals("play") && !query.trim().isEmpty()) {
            start(c, new Intent(android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(android.app.SearchManager.QUERY, query.trim()));
            Thread.sleep(2500);
            Hands.record("Asked the music app to play " + query.trim());
            return "Asked his music app to play " + query.trim() + (am.isMusicActive() ? ". Something is playing now." : "; nothing is playing yet "
                    + "(the app may want a tap).");
        }
        int key = command.equals("pause") ? android.view.KeyEvent.KEYCODE_MEDIA_PAUSE : command.equals("next")
                ? android.view.KeyEvent.KEYCODE_MEDIA_NEXT : command.equals("previous") ? android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key));
        am.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key));
        Thread.sleep(800);
        boolean playing = am.isMusicActive();
        Hands.record("Media: " + command);
        if (command.equals("pause")) return playing ? "Sent pause, but something is still playing." : "Paused.";
        return playing ? (command.equals("play") ? "Playing." : "Skipped to the " + command + " one; playing.")
                : "Sent " + command + ", but nothing is playing (no music app has anything loaded).";
    }

    private static String maps(Context c, String action, String place, String mode) throws Exception {
        if (place.trim().isEmpty()) throw new Exception("where to? No place was given");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(PhoneTools.mapsUri(action, place, mode)));
        i.setPackage("com.google.android.apps.maps");
        try { start(c, i); }
        catch (Exception e) { start(c, i.setPackage(null)); }
        Hands.record(action.equals("nearby") ? "Maps: " + place.trim() + " nearby" : "Directions to " + place.trim());
        return action.equals("nearby") ? "Maps is showing " + place.trim() + " near him." : "Maps is starting directions to " + place.trim() + ".";
    }

    private static String openScreen(Context c, String name) throws Exception {
        String[] s = PhoneTools.screen(name);
        if (s == null) throw new Exception("there is no screen called " + name);
        Intent i = name.equals("photos") ? Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
                : name.equals("home_screen") ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME) : new Intent(s[0]);
        if (name.equals("selfie_camera"))
            i.putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    .putExtra("android.intent.extra.USE_FRONT_CAMERA", true);
        try { start(c, i); }
        catch (Exception e) {
            if (!name.endsWith("_settings")) throw e;
            start(c, new Intent(Settings.ACTION_SETTINGS));              // a page this phone doesn't have: the settings
            Hands.record("Opened the settings");
            return "This phone has no direct way to " + s[1] + ", so the settings are open instead.";
        }
        Hands.record("Opened " + s[1]);
        return "Opened " + s[1] + ".";
    }

    /** The Clock app does the cancelling (Android gives no list of alarms); it may show the alarm for him to confirm. */
    private static String clockControl(Context c, JSONObject in) throws Exception {
        switch (in.optString("action")) {
            case "snooze_alarm":
                start(c, new Intent(AlarmClock.ACTION_SNOOZE_ALARM));
                Hands.record("Snoozed the alarm");
                return "Asked the Clock app to snooze the ringing alarm.";
            case "stop_timer":
                if (Build.VERSION.SDK_INT < 28) throw new Exception("this phone can't stop timers by voice");
                start(c, new Intent(AlarmClock.ACTION_DISMISS_TIMER));
                Hands.record("Stopped the timer");
                return "Asked the Clock app to stop the ringing timer.";
            case "cancel_alarm": {
                String time = in.optString("time"), label = in.optString("label");
                String mode = PhoneTools.alarmSearch(time, label, in.optBoolean("all"));
                Intent i = new Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, mode);
                String which = "the next alarm";
                if (mode.equals("android.time")) {
                    int[] t = PhoneTools.hhmm(time);
                    i.putExtra(AlarmClock.EXTRA_HOUR, t[0]).putExtra(AlarmClock.EXTRA_MINUTES, t[1]);
                    which = "the " + clock(t[0], t[1]) + " alarm";
                } else if (mode.equals("android.label")) {
                    i.putExtra(AlarmClock.EXTRA_MESSAGE, label.trim());
                    which = "the \u201c" + label.trim() + "\u201d alarm";
                } else if (mode.equals("android.all")) which = "every alarm";
                start(c, i);
                Hands.record("Asked the Clock to cancel " + which);
                return "Asked the Clock app to cancel " + which + "; it may show it for him to confirm.";
            }
            default: throw new Exception("unknown clock action " + in.optString("action"));
        }
    }

    private static String missedCalls(Context c) throws Exception {
        if (!Hands.need(Manifest.permission.READ_CALL_LOG)) throw new Exception("Sprocket isn't allowed to see the call log");
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        int n = 0;
        try (Cursor cur = c.getContentResolver().query(android.provider.CallLog.Calls.CONTENT_URI,
                new String[]{android.provider.CallLog.Calls.CACHED_NAME, android.provider.CallLog.Calls.NUMBER, android.provider.CallLog.Calls.DATE},
                android.provider.CallLog.Calls.TYPE + "=? AND " + android.provider.CallLog.Calls.DATE + ">?",
                new String[]{String.valueOf(android.provider.CallLog.Calls.MISSED_TYPE), String.valueOf(now - 7 * 24 * 3600_000L)},
                android.provider.CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext() && n++ < 10) {
                String name = cur.getString(0);
                if (name == null || name.trim().isEmpty()) name = nameFor(c, cur.getString(1));
                b.append(b.length() == 0 ? "Missed calls: " : "; ").append(name).append(", ")
                        .append(Messages.when(cur.getLong(2), now, java.util.TimeZone.getDefault()));
            }
        }
        return b.length() == 0 ? "No missed calls in the last week." : b.append('.').toString();
    }

    /** A number as his contacts name it, or the number. */
    private static String nameFor(Context c, String number) {
        if (number == null || number.isEmpty()) return "an unknown number";
        try (Cursor cur = c.getContentResolver().query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
        } catch (Exception ignored) {
        }
        return number;
    }

    /** An email, filled in, in his email app: he checks it and sends it himself. */
    private static String email(Context c, String to, String subject, String body) throws Exception {
        String address = to.trim(), name = to.trim();
        if (!PhoneTools.isEmail(address)) {
            if (!Hands.need(Manifest.permission.READ_CONTACTS)) throw new Exception("Sprocket isn't allowed to read contacts");
            Map<String, String> emails = new LinkedHashMap<>();
            try (Cursor cur = c.getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Email.ADDRESS},
                    null, null, null)) {
                while (cur != null && cur.moveToNext()) if (cur.getString(0) != null) emails.putIfAbsent(cur.getString(0).trim(), cur.getString(1));
            }
            NameMatch.Result m = NameMatch.match(to.replaceFirst("(?i)^(my|the)\\s+", "").trim(), new ArrayList<>(emails.keySet()));
            if (m.unique == null && !m.choices.isEmpty())
                throw new Exception("more than one contact could be " + to + ": " + String.join(", ", m.choices) + ". Ask the boss which");
            if (m.unique == null) throw new Exception("no contact called " + to + " has an email address on this phone; ask the boss for it");
            name = m.unique;
            address = emails.get(name);
        }
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, new String[]{address})
                .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body);
        start(c, i);
        Hands.record("Email to " + name + " ready in his email app");
        return "The email to " + name + " is open in his email app, filled in; he checks it and taps send there.";
    }

    private static String addContact(Context c, String name, String number, String email) throws Exception {
        if (name.trim().isEmpty()) throw new Exception("the contact needs a name");
        Intent i = new Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE)
                .putExtra(ContactsContract.Intents.Insert.NAME, name.trim());
        if (!number.trim().isEmpty()) i.putExtra(ContactsContract.Intents.Insert.PHONE, number.trim());
        if (!email.trim().isEmpty()) i.putExtra(ContactsContract.Intents.Insert.EMAIL, email.trim());
        start(c, i);
        Hands.record("New contact " + name.trim() + " ready to save");
        return "The new contact " + name.trim() + " is open, filled in; he checks it and taps Save.";
    }

    /** His location now (precise: asked for at first use), texted as a Maps link through the usual Send card. */
    private static String shareLocation(Context c, String to) throws Exception {
        if (!Hands.need(Manifest.permission.ACCESS_FINE_LOCATION)) throw new Exception("Sprocket isn't allowed to know his precise location");
        android.location.LocationManager lm = c.getSystemService(android.location.LocationManager.class);
        android.location.Location got = null;
        if (Build.VERSION.SDK_INT >= 30) {
            CountDownLatch l = new CountDownLatch(1);
            android.location.Location[] box = new android.location.Location[1];
            String provider = Build.VERSION.SDK_INT >= 31 && lm.hasProvider(android.location.LocationManager.FUSED_PROVIDER)
                    ? android.location.LocationManager.FUSED_PROVIDER : android.location.LocationManager.GPS_PROVIDER;
            lm.getCurrentLocation(provider, null, c.getMainExecutor(), loc -> { box[0] = loc; l.countDown(); });
            l.await(20, TimeUnit.SECONDS);
            got = box[0];
        }
        if (got == null) for (String p : lm.getProviders(true)) {
            android.location.Location last = lm.getLastKnownLocation(p);
            if (last != null && (got == null || last.getTime() > got.getTime())) got = last;
        }
        if (got == null) throw new Exception("the phone couldn't get a location fix (location off, or no signal)");
        return text(c, to, "My location: " + PhoneTools.mapsLink(got.getLatitude(), got.getLongitude()), "Shared his location with");
    }

    private static String status(Context c) {
        BatteryManager bm = c.getSystemService(BatteryManager.class);
        StringBuilder b = new StringBuilder("Battery ").append(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)).append('%')
                .append(bm.isCharging() ? ", charging" : ", not charging").append(". ");
        AlarmManager.AlarmClockInfo next = c.getSystemService(AlarmManager.class).getNextAlarmClock();
        if (next == null) b.append("No alarm set. ");
        else {
            LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(next.getTriggerTime()), ZoneId.systemDefault());
            b.append("Next alarm ").append(t.format(DAY)).append(' ').append(t.format(TIME)).append(". ");
        }
        StatFs f = new StatFs(Environment.getDataDirectory().getPath());
        b.append(String.format(Locale.US, "%.0f GB free of %.0f GB.", f.getAvailableBytes() / 1e9, f.getTotalBytes() / 1e9));
        return b.toString();
    }
}
