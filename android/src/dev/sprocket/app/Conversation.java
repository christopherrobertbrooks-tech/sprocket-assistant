package dev.sprocket.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The back-and-forth after a spoken answer (V2.md item 3, the owner 2026-10-17): Sprocket keeps listening after each answer
 * until he says he's done ("that's all for now", "never mind", "goodbye"...): those end it on the phone, never sent to
 * Claude. After 12 s of quiet Sprocket asks "Still there?" (said by the phone, no Claude), listens once more,
 * and if he still says nothing it stops. "Yes"/"I'm here" then just keeps it going; anything else is his next question.
 * Plain Java (test/ConversationTest.java); the card and the app's Talk drive it.
 */
final class Conversation {
    enum Next { ASK, LISTEN, STILL_THERE, END }

    static final long STILL_THERE_AFTER_MS = 12_000;
    static final long SHORTEST_LISTEN_MS = 1_500;   // a listen that ended sooner failed: stop rather than spin

    // whole sayings only, once a few polite words around them are taken off: "never mind the timer, ..." is a question
    private static final Set<String> STOPS = new HashSet<>(Arrays.asList(
            "thats all", "that is all", "thats it", "that is it", "thats everything", "thatll be all", "that will be all",
            "im done", "i am done", "were done", "never mind", "nevermind", "goodbye", "good bye", "bye", "bye bye",
            "stop listening", "nothing", "nothing else", "im good", "i am good", "all set", "im all set", "good night",
            "goodnight", "see you", "see ya", "see you later", "talk to you later"));
    private static final Set<String> HERE = new HashSet<>(Arrays.asList(
            "yes", "yeah", "yep", "yup", "here", "im here", "i am here", "still here", "im still here", "yes im here",
            "yeah im here", "yes still here", "yeah still here", "uh huh", "mhm"));
    private static final String[] BEFORE = {"ok", "okay", "alright", "all right", "no", "nope", "nah", "thanks", "thank you",
            "cool", "great", "perfect", "awesome", "got it", "well", "um", "uh"};
    private static final String[] AFTER = {"sprocket", "boss", "buddy", "thanks", "thank you", "for now"};

    private long quietSince;
    private boolean askedIfThere;

    /** Sprocket has just finished talking (or he said he's still here): the quiet starts now. */
    void begin(long now) {
        quietSince = now;
        askedIfThere = false;
    }

    /** When the "Still there?" timer should go off, from now. */
    long due(long now) { return Math.max(0, quietSince + STILL_THERE_AFTER_MS - now); }

    /** He said something. */
    Next heard(String words, long now) {
        if (isStop(words)) return Next.END;
        if (askedIfThere && isHere(words)) {
            begin(now);
            return Next.LISTEN;
        }
        return Next.ASK;
    }

    /** A listen ended with nothing heard. */
    Next nothing(long listenStarted, long now) {
        if (now - listenStarted < SHORTEST_LISTEN_MS || askedIfThere) return Next.END;
        return timeUp(now, false) == Next.STILL_THERE ? Next.STILL_THERE : Next.LISTEN;
    }

    /** The timer went off: "Still there?", unless he's mid-sentence, it was asked already, or it's an old timer. */
    Next timeUp(long now, boolean hearingWords) {
        if (askedIfThere || hearingWords || now - quietSince < STILL_THERE_AFTER_MS) return Next.LISTEN;
        askedIfThere = true;
        return Next.STILL_THERE;
    }

    static boolean isStop(String said) {
        if (said == null || said.contains("?")) return false;
        boolean[] thanked = {false};
        String core = core(said, thanked);
        return STOPS.contains(core) || core.isEmpty() && thanked[0];
    }

    static boolean isHere(String said) {
        return said != null && !said.contains("?") && HERE.contains(core(said, new boolean[1]));
    }

    /** Lower case, no apostrophes or punctuation, and the polite words around the saying taken off. */
    private static String core(String said, boolean[] thanked) {
        String s = " " + said.toLowerCase(Locale.US).replace("’", "").replace("'", "")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim() + " ";
        for (boolean again = true; again; ) {
            again = false;
            for (String w : BEFORE)
                if (s.startsWith(" " + w + " ")) { s = s.substring(w.length() + 1); again = true; if (w.startsWith("thank")) thanked[0] = true; }
            for (String w : AFTER)
                if (s.endsWith(" " + w + " ")) { s = s.substring(0, s.length() - w.length() - 1); again = true; if (w.startsWith("thank")) thanked[0] = true; }
        }
        return s.trim();
    }
}
