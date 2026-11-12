"""The voice service's plain decisions: python3 gateway/test_voicetext.py"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import voicetext as V  # noqa: E402

fails = n = 0


def eq(what, got, want):
    global fails, n
    n += 1
    if got != want:
        fails += 1
        print(f"FAIL {what}: got {got!r}, wanted {want!r}")


eq("sentences, pauses by punctuation", V.pieces("Is that the vacuum? Do not let it near me. I have seen it!"),
   [("Is that the vacuum?", 350), ("Do not let it near me.", 300), ("I have seen it!", 350)])
eq("a one- or two-word sentence joins the next", V.pieces("You want a timer? A timer? I was built for war! It's set. Ten minutes."),
   [("You want a timer?", 350), ("A timer? I was built for war!", 350), ("It's set. Ten minutes.", 300)])
eq("short at the very end joins the one before", V.pieces("I will be staying inside today. Safe."),
   [("I will be staying inside today. Safe.", 300)])
eq("one short sentence alone is still said", V.pieces("Done!"), [("Done!", 350)])
eq("a long sentence breathes at its commas",
   V.pieces("It's seventy two degrees and sunny, which is suspicious, because the sky is never this nice."),
   [("It's seventy two degrees and sunny,", 130), ("which is suspicious,", 130), ("because the sky is never this nice.", 300)])
eq("a dramatic pause", V.pieces("I have seen things... terrible things."), [("I have seen things...", 600), ("terrible things.", 300)])
eq("no end mark: a period's pause", V.pieces("Rain tomorrow boss"), [("Rain tomorrow boss", 300)])
eq("tags, markdown, links and emoji are never spoken",
   V.clean("[scared] **Boss!** Look at https://example.com now \U0001F631 [Deadpan] fine."), "Boss! Look at now fine.")
eq("nothing to say", V.pieces("[proud] *** "), [])
eq("too long is cut", len(V.clean("word " * 300)) <= V.MAX_CHARS, True)
eq("a mood by name", V.mood_vector("Scared"), V.MOODS["scared"])
eq("an unknown mood: his resting state", V.mood_vector("melodramatic"), V.MOODS[V.DEFAULT_MOOD])
# deadpan's calm vector drags IndexTTS ~10% slower than his resting mood (measured 2026-10-17: card 6.62 s vs 6.18 s,
# a read-out text 7.85 s vs 7.11 s; the owner: "sprocket speaks slower while talking to it about a text message")
eq("deadpan at the others' pace", (V.pace("deadpan"), V.pace("Deadpan "), V.pace("proud"), V.pace("melodramatic")), (0.9, 0.9, 1.0, 1.0))
eq("every mood has eight numbers", {k: len(v) for k, v in V.MOODS.items() if len(v) != 8}, {})
eq("the effect at the engine's rate", V.effect(22050).startswith("asetrate=22050*1.19,aresample=22050,"), True)
print(f"voicetext: all {n} checks pass" if fails == 0 else f"{fails} of {n} FAILED")
sys.exit(1 if fails else 0)
