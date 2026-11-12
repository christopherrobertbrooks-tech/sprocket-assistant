package dev.sprocket.app;

import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * What a phone tool needs from the screen while Claude waits for its answer (phase 3): a card he must tap before a
 * text is sent or a call placed, a permission asked at first use, and a record of each real action in the chat.
 * The tools run on the worker thread; the screen is MainActivity.current, when there is one.
 */
final class Hands {
    private Hands() { }

    /** A card on screen until he taps one of its buttons or says yes or no (or it times out: nothing happens). */
    static final class Card {
        final String text, yes, spoken;
        final CountDownLatch done = new CountDownLatch(1);
        volatile Boolean answer;
        volatile boolean listening;             // for his spoken "send" / "cancel" right now

        Card(String text, String yes) { this.text = text; this.yes = yes; this.spoken = PhoneTools.spoken(text, yes); }
    }

    static volatile Card card;
    static volatile String autoAnswer;          // self-test only (emulator, made-up key): "yes" or "no", 5 s after the card shows
    static volatile String testHeard;           // self-test only (stand-in): what the card "hears" instead of the mic
    static volatile String testCards;           // Sprocket Test only (set over adb): "no" = cards cancel themselves, for runs
    private static final Listen ears = new Listen();   // main thread only
    private static final long CARD_WAIT_S = 180;
    private static volatile CountDownLatch asking;

    private static void onMain(Runnable r) { new Handler(Looper.getMainLooper()).post(r); }

    private static void redraw() {
        onMain(() -> {
            if (MainActivity.current != null) MainActivity.current.renderNow();
            SprocketSession.cardChanged();                      // the side-button card shows it too
        });
    }

    /** Worker thread: shows the card and waits for his tap. true = yes, false = cancelled, null = no answer. */
    static Boolean confirm(String text, String yes) throws InterruptedException {
        Card c = new Card(text, yes);
        card = c;
        redraw();
        // a card answers itself only in a self-test against the stand-in: never when the real API is in use
        String auto = MainActivity.standIn() ? autoAnswer : null;
        if (auto == null && testCards != null && MainActivity.appContext().getPackageName().endsWith(".test")) {
            auto = testCards;
            android.util.Log.i("PA-REPLY", "CARD " + text.replace('\n', ' ') + " -> " + auto);
        }
        final String answerWith = auto;
        if (answerWith != null) onMain(() -> new Handler(Looper.getMainLooper()).postDelayed(() -> answer(c, answerWith.equals("yes")), 3000));
        else onMain(() -> listenFor(c));
        boolean tapped = c.done.await(CARD_WAIT_S, TimeUnit.SECONDS);
        card = null;
        onMain(() -> { c.listening = false; ears.cancel(); });
        redraw();
        return tapped ? c.answer : null;
    }

    /**
     * Main thread: the card said out loud (when his voice is on, after what Sprocket was already saying), then a
     * listen for his "send" or "cancel" (the owner 2026-10-17: "I should be able to say send as well as click the send
     * button"). Something unclear or silence: one more listen, then the card just waits for his tap.
     */
    private static void listenFor(Hands.Card c) {
        android.content.Context app = MainActivity.appContext();
        if (testHeard == null && app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        Runnable go = () -> hear(c, 2);
        if (MainActivity.voiceOn()) VoiceOut.get(app).sayThen(c.spoken, "deadpan", go); else go.run();
    }

    private static void hear(Hands.Card c, int tries) {
        if (card != c || c.done.getCount() == 0 || tries <= 0) { c.listening = false; redraw(); return; }
        String test = MainActivity.standIn() ? testHeard : null;
        if (test != null) { testHeard = null; settle(c, test, tries); return; }
        c.listening = ears.start(MainActivity.appContext(), new Listen.Heard() {
            @Override public void partial(String soFar) { }
            @Override public void heard(String words) { settle(c, words, tries); }
            @Override public void nothing() { hear(c, tries - 1); }
        });
        redraw();
    }

    private static void settle(Hands.Card c, String words, int tries) {
        Boolean yes = PhoneTools.answer(words, c.yes);
        if (yes == null) { hear(c, tries - 1); return; }
        c.listening = false;
        answer(c, yes);
    }

    /** Main thread: his tap (or Stop, which cancels). */
    static void answer(Card c, boolean yes) {
        if (c == null || c.done.getCount() == 0) return;
        c.answer = yes;
        c.done.countDown();
    }

    /** Worker thread: the permissions, asked on the screen if not yet granted; true once all are granted. */
    static boolean need(String... perms) throws InterruptedException {
        if (granted(perms)) return true;
        MainActivity a = MainActivity.current;
        if (a == null) return false;
        CountDownLatch l = new CountDownLatch(1);
        asking = l;
        onMain(() -> a.requestPermissions(perms, MainActivity.ASK_TOOL_PERMISSION));
        l.await(90, TimeUnit.SECONDS);
        return granted(perms);
    }

    static void permissionsAnswered() {
        CountDownLatch l = asking;
        if (l != null) l.countDown();
    }

    static boolean granted(String... perms) {
        for (String p : perms)
            if (MainActivity.appContext().checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    /** A real action, recorded in the chat above the reply that tells him about it. */
    static void record(String what) { onMain(() -> MainActivity.addAction(what)); }
}
