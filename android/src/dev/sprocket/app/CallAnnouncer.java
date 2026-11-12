package dev.sprocket.app;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * The call announcer (the owner 2026-11-10): with the phone on vibrate, a ringing call is said out loud ("Toni is
 * calling, boss. Answer, decline, or text?"), a card shows over everything (Answer on speaker / Decline / Text), and
 * Sprocket listens a few seconds for his answer. Answer and Decline press the call notification's own buttons; Text
 * declines, then Sprocket asks what to say and a Send card shows the text before it goes. All of it works over the
 * lock screen (the owner: "it would have to work over the lock screen"): the cards are screen control's, which draw over
 * it, and Sprocket's own screen is only the fallback. Calls.java holds the plain decisions. Main thread
 * (MessageListener's callbacks), except the Speaker tap, the contact lookup and the sending.
 */
final class CallAnnouncer {
    private CallAnnouncer() { }

    private static String ringing;                       // the ringing call's notification key, while it rings
    private static Notification.Action[] buttons;
    private static String caller = "", number = "";
    private static int lastLine = -1;
    private static Runnable card;
    private static final Listen ears = new Listen();
    private static final Handler main = new Handler(Looper.getMainLooper());
    static volatile boolean testCalls;                   // Sprocket Test only (adb): Sprocket's own pretend calls count, any ringer

    /** A notification arrived: announce it if it is a call ringing while the phone is on vibrate. */
    static void posted(Context c, StatusBarNotification s) {
        Notification n = s.getNotification();
        switch (Calls.event(s.getKey(), Calls.isRinging(n.category, titles(n.actions)), ringing)) {
            case IGNORE: return;
            case END: quiet(); return;                   // the announced call stopped ringing (answered, missed)
            case UPDATE: buttons = n.actions; return;    // the same call, updated
            default: break;
        }
        MainActivity.init(c);                            // the app may not have been opened since the phone started
        if (!Config.on("calls")) return;                 // switched off in Settings
        boolean pretend = s.getPackageName().equals(c.getPackageName());
        if (pretend && !(testCalls && c.getPackageName().endsWith(".test"))) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        if (!pretend && (am == null || am.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE)) return;   // his ringer will tell him
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (!pretend && !Calls.onDuty(Config.callHours, now.getDayOfWeek(), now.getHour(), now.getMinute())) return;     // off hours: just the buzz
        if (ringing != null) quiet();                    // a newer call: the old call's card goes first
        String key = s.getKey();
        ringing = key;
        buttons = n.actions;
        Bundle x = n.extras;
        caller = Calls.caller(str(x.getCharSequence(Notification.EXTRA_TITLE)), str(x.getCharSequence(Notification.EXTRA_TEXT)));
        number = numberOf(n, caller);
        log("CALL " + caller + (number.isEmpty() ? "" : " " + number) + (ScreenHands.on() ? "" : " (no card: screen control is off)"));
        card = ScreenHands.showCard(Calls.card(caller), Calls.BUTTONS, i -> { if (key.equals(ringing)) pick(c, Calls.BUTTON_CHOICES[i], "tapped"); });
        int line = (lastLine + 1 + new java.util.Random().nextInt(Calls.LINES - 1)) % Calls.LINES;   // never the same line twice running
        lastLine = line;
        log("SAY " + Calls.announcement(caller, line));
        VoiceOut.get(c).sayThen(Calls.announcement(caller, line), "deadpan", () -> { if (key.equals(ringing)) hear(c, key, 2); });
    }

    /** A notification went away: if it was the ringing call (answered on the phone, hung up, gone to voicemail), stop. */
    static void removed(StatusBarNotification s) {
        if (s.getKey().equals(ringing)) quiet();
    }

    private static void quiet() {
        ringing = null;
        ears.cancel();
        VoiceOut.get(MainActivity.appContext()).stop();
        Runnable r = card;
        card = null;
        if (r != null) r.run();
    }

    /** A few seconds' listen for "answer", "decline" or "text" (twice if he's unclear); the card stays either way. */
    private static void hear(Context c, String key, int tries) {
        if (!key.equals(ringing) || tries <= 0) return;
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        ears.start(c, new Listen.Heard() {
            @Override public void partial(String soFar) { }
            @Override public void heard(String words) {
                Calls.Choice ch = Calls.choice(words);
                log("HEARD " + words + " -> " + ch);
                if (ch == null) hear(c, key, tries - 1); else if (key.equals(ringing)) pick(c, ch, "said");
            }
            @Override public void nothing() { log("HEARD nothing"); hear(c, key, tries - 1); }
        });
    }

    private static void pick(Context c, Calls.Choice ch, String how) {
        if (ringing == null) return;
        Notification.Action[] b = buttons;
        String who = caller, num = number;
        quiet();
        log("PICK " + ch + " (" + how + ")");
        if (ch == Calls.Choice.ANSWER) {
            boolean pressed = press(c, b, Calls.Choice.ANSWER);
            new Thread(() -> {                           // the in-call screen's Speaker button, once it is up
                try {
                    boolean on = pressed && ScreenHands.tapLabel("Speaker");
                    log(on ? "SPEAKER on" : "SPEAKER not found" + (pressed ? "" : " (Answer didn't go through)"));
                } catch (InterruptedException ignored) { }
            }, "sprocket-speaker").start();
        } else {
            press(c, b, Calls.Choice.DECLINE);
            if (ch == Calls.Choice.TEXT) askWords(c, who, num, 2);
        }
    }

    // ------------------------------------------------------------------ Text: what to say, then the Send card
    private static final Listen wordEars = new Listen();
    private static volatile Runnable sendCard;

    /** "What should I say to Toni?", then a listen for the words (twice if he says nothing). Still nothing: the
     * question waits in Sprocket's chat for him to type the words. */
    private static void askWords(Context c, String who, String num, int tries) {
        Runnable listen = () -> wordEars.start(c, new Listen.Heard() {
            @Override public void partial(String soFar) { }
            @Override public void heard(String words) { log("WORDS " + words); confirm(c, who, num, words); }
            @Override public void nothing() {
                if (tries > 1) { askWords(c, who, num, tries - 1); return; }
                log("WORDS nothing: left in Sprocket");
                MainActivity.textBack(who, num);
                VoiceOut.get(c).sayThen("Nothing heard. It's waiting in Sprocket when you want to type it.", "deadpan", () -> { });
            }
        });
        if (c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            MainActivity.textBack(who, num);
            return;
        }
        VoiceOut.get(c).sayThen(Calls.whatToSay(who), "deadpan", listen);
    }

    /** The Send card over everything, said out loud with a listen for "send" or "cancel"; nothing goes without it. */
    private static void confirm(Context c, String who, String num, String words) {
        new Thread(() -> {
            String[] w;
            try { w = Phone.who(c, num.isEmpty() ? who : num); }
            catch (Exception e) {
                log("TEXT no number: " + e.getMessage());
                main.post(() -> { MainActivity.textBack(who, num); VoiceOut.get(c).sayThen("I couldn't find their number. It's waiting in Sprocket.", "deadpan", () -> { }); });
                return;
            }
            String text = PhoneTools.textCard(w[0], w[1], words);
            main.post(() -> {
                Runnable shown = ScreenHands.showCard(text, new String[]{"Cancel", "Send"}, i -> sendOrNot(c, w, words, i == 1, "tapped"));
                if (shown == null) { MainActivity.textBack(who, num); return; }   // no card, no sending
                sendCard = shown;
                log("CARD " + text.replace('\n', ' '));
                String auto = Hands.testCards;                 // Sprocket Test runs: the card answers itself, nothing is sent
                if (auto != null && c.getPackageName().endsWith(".test")) {
                    main.postDelayed(() -> sendOrNot(c, w, words, auto.equals("yes"), "test"), 3000);
                    return;
                }
                VoiceOut.get(c).sayThen(PhoneTools.spoken(text, "Send"), "deadpan", () -> hearSend(c, w, words, shown, 2));
            });
        }, "sprocket-text-back").start();
    }

    private static void hearSend(Context c, String[] w, String words, Runnable shown, int tries) {
        if (sendCard != shown || tries <= 0) return;           // tapped already, or the card just waits for a tap now
        wordEars.start(c, new Listen.Heard() {
            @Override public void partial(String soFar) { }
            @Override public void heard(String said) {
                Boolean yes = PhoneTools.answer(said, "Send");
                if (yes == null) hearSend(c, w, words, shown, tries - 1); else if (sendCard == shown) sendOrNot(c, w, words, yes, "said");
            }
            @Override public void nothing() { hearSend(c, w, words, shown, tries - 1); }
        });
    }

    private static void sendOrNot(Context c, String[] w, String words, boolean send, String how) {
        Runnable r = sendCard;
        sendCard = null;
        if (r == null) return;
        r.run();
        wordEars.cancel();
        log("SEND " + (send ? "yes" : "no") + " (" + how + ")");
        if (!send) { VoiceOut.get(c).sayThen("Okay, nothing sent.", "deadpan", () -> { }); return; }
        new Thread(() -> {
            String said;
            try {
                String failed = Phone.sms(c, w[1], words);
                if (failed == null) {
                    Hands.record("Texted " + w[0] + (w[0].equals(w[1]) ? "" : " (" + w[1] + ")") + ": \u201c" + words + "\u201d");
                    said = "Sent.";
                } else said = "The text didn't go: " + failed + ".";
            } catch (Exception e) { said = "The text didn't go: " + e.getMessage() + "."; }
            log("SENT " + said);
            String out = said;
            main.post(() -> VoiceOut.get(c).sayThen(out, "deadpan", () -> { }));
        }, "sprocket-sms").start();
    }

    /** Presses the call notification's own Answer or Decline button. */
    private static boolean press(Context c, Notification.Action[] b, Calls.Choice ch) {
        int i = b == null ? -1 : Calls.button(titles(b), ch);
        if (i < 0) { log("NO " + ch + " button"); return false; }
        PendingIntent pi = b[i].actionIntent;
        try {
            // the dialer's button may open its in-call screen: Sprocket's screen control lets it start from the background
            Bundle opts = Build.VERSION.SDK_INT >= 34 ? ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle() : null;
            pi.send(c, 0, null, null, null, null, opts);
            return true;
        } catch (Exception e) {
            log("PRESS " + ch + " failed: " + e);
            return false;
        }
    }

    /** The caller's number: from the notification's person (tel:), or the caller ID itself when it is a number. */
    private static String numberOf(Notification n, String caller) {
        if (Build.VERSION.SDK_INT >= 28) {
            List<android.app.Person> people = n.extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST);
            if (people != null) for (android.app.Person p : people) {
                String u = p.getUri();
                if (u != null && u.startsWith("tel:")) return android.net.Uri.decode(u.substring(4));
            }
        }
        return Messages.isNumber(caller) ? caller : "";
    }

    /** Sprocket Test only (adb): a pretend ringing call from Sprocket itself, "Name|number", with Decline and Answer
     * buttons that just log what was pressed. It "stops ringing" after 40 s, like a call going to voicemail. */
    static void pretend(Context c, String spec) {
        String[] p = spec.split("\\|", 2);
        String name = p[0], num = p.length > 1 ? p[1] : "";
        android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
        nm.createNotificationChannel(new android.app.NotificationChannel("pretend_calls", "Pretend calls (test)", android.app.NotificationManager.IMPORTANCE_HIGH));
        String action = c.getPackageName() + ".PRETEND_CALL";
        int id = 4242;
        android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context x, android.content.Intent i) {
                log("PRETEND " + i.getStringExtra("button") + " pressed");
                nm.cancel(id);
                try { c.getApplicationContext().unregisterReceiver(this); } catch (Exception ignored) { }
            }
        };
        c.getApplicationContext().registerReceiver(r, new android.content.IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        Notification.Builder b = new Notification.Builder(c, "pretend_calls").setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(name).setContentText("Incoming call").setCategory(Notification.CATEGORY_CALL).setOngoing(true);
        if (!num.isEmpty()) b.addPerson(new android.app.Person.Builder().setName(name).setUri("tel:" + num).build());
        String[] labels = {"Decline", "Answer"};
        for (int k = 0; k < labels.length; k++) {
            PendingIntent pi = PendingIntent.getBroadcast(c, 900 + k, new android.content.Intent(action).setPackage(c.getPackageName())
                    .putExtra("button", labels[k]), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, labels[k], pi).build());
        }
        nm.notify(id, b.build());
        main.postDelayed(() -> nm.cancel(id), 40000);
    }

    private static List<String> titles(Notification.Action[] actions) {
        List<String> out = new ArrayList<>();
        if (actions != null) for (Notification.Action a : actions) out.add(str(a.title));
        return out;
    }

    private static void log(String what) {
        android.util.Log.i("Sprocket", "calls: " + what);
        if (MainActivity.appContext().getPackageName().endsWith(".test")) android.util.Log.i("PA-REPLY", what);
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString(); }
}
