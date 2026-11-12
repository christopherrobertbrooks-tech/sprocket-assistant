package dev.sprocket.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which pieces of a streaming reply go to the voice, and when (phase 4, the owner 2026-10-17: "group very short
 * sentences with their neighbours" -- a lone "Unmoving." came out slow and drawn out). A sentence of one or two
 * words waits for the next one and goes with it (never across a "...", whose pause is the point, or a change of
 * mood, which would act the next sentence in the wrong one); anything longer
 * goes at once, so the first words are made while Claude is still writing. The gateway groups inside each piece the
 * same way. Plain Java: test/VoiceGroupsTest.java.
 */
final class VoiceGroups {
    static final int SHORT = 3;

    static final class Piece {
        final String text, mood;

        Piece(String text, String mood) { this.text = text; this.mood = mood == null ? "" : mood; }
    }

    private Piece held;

    /** What the voice should say now, given the next sentence(s) from the stream. */
    List<Piece> add(String text, String mood) {
        String w = words(text);
        if (w.isEmpty()) return Collections.emptyList();
        List<Piece> out = new ArrayList<>();
        String m = mood == null ? "" : mood;
        if (held != null && !held.mood.equals(m)) {   // a new mood: the short one goes alone, in its own mood
            out.add(held);
            held = null;
        }
        Piece p = held == null ? new Piece(w, m) : new Piece(held.text + " " + w, held.mood);
        held = null;
        if (p.text.split("\\s+").length < SHORT && !p.text.endsWith("...")) {
            held = p;
            return out;
        }
        out.add(p);
        return out;
    }

    /** The reply ended: whatever is held goes now. */
    List<Piece> end() {
        List<Piece> out = new ArrayList<>();
        if (held != null) out.add(held);
        held = null;
        return out;
    }

    /** The words as spoken: Speech's cleaning (no markdown or emoji), trimmed. */
    static String words(String s) { return Speech.forVoice(s == null ? "" : s).trim(); }
}
