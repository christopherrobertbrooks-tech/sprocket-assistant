package dev.sprocket.app;

import java.util.ArrayList;
import java.util.List;

/** VoiceGroups on the desktop JDK: which pieces of a streaming reply go to the voice, and when. */
public class VoiceGroupsTest {
    static int fails, n;

    static void eq(String what, Object got, Object want) {
        n++;
        if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + ">, wanted <" + want + ">"); }
    }

    static String all(List<VoiceGroups.Piece> ps) {
        List<String> out = new ArrayList<>();
        for (VoiceGroups.Piece p : ps) out.add(p.mood + ":" + p.text);
        return out.toString();
    }

    public static void main(String[] a) {
        VoiceGroups g = new VoiceGroups();
        eq("a full sentence goes at once", all(g.add("Is that the vacuum?", "scared")), "[scared:Is that the vacuum?]");
        eq("a one- or two-word sentence waits for the next", all(g.add("Boss. Boss!", "scared")), "[]");
        eq("then goes with it", all(g.add("Do not let it near me.", "scared")), "[scared:Boss. Boss! Do not let it near me.]");
        eq("a short one at the end goes when the reply ends", all(g.add("Ten minutes.", "deadpan")) + all(g.end()), "[][deadpan:Ten minutes.]");
        eq("nothing held: nothing at the end", all(g.end()), "[]");
        eq("a long one goes at once", all(g.add("It's seventy two degrees and sunny, which is suspicious.", "")),
                "[:It's seventy two degrees and sunny, which is suspicious.]");
        VoiceGroups d = new VoiceGroups();
        d.add("I have seen things...", "scared");
        eq("never joined across a dramatic pause", all(d.add("Terrible.", "scared")) + all(d.end()), "[][scared:Terrible.]");
        VoiceGroups m = new VoiceGroups();
        m.add("Unmoving.", "proud");
        eq("never joined across a change of mood: each in its own", all(m.add("Is that the vacuum?", "scared")),
                "[proud:Unmoving., scared:Is that the vacuum?]");
        eq("words for the voice: markdown and emoji out", VoiceGroups.words("**Done!** \uD83D\uDE00"), "Done!");
        System.out.println(fails == 0 ? "voice groups: all " + n + " checks pass" : fails + " of " + n + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
