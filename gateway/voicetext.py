"""The voice service's plain decisions (no model, no numpy): what gets said, in which pieces, with which pause and
mood. Tested by test_voicetext.py with plain python3. Settings picked by the owner 2026-10-17 (voice/INDEXTTS.md): one
take per sentence group, a one- or two-word sentence joined with the next, pauses by punctuation, emotion presets.
"""
import re

# The eight numbers IndexTTS 2.5 takes for emotion, in its order: happy angry sad afraid disgusted melancholic
# surprised calm. proud/outraged/scared/deadpan are what its own emotion model made of the words he heard and liked.
MOODS = {
    "proud":     [0.95, 0, 0, 0, 0, 0, 0.03, 0.02],
    "excited":   [0.7, 0, 0, 0, 0, 0, 0.3, 0],
    "outraged":  [0, 0.3, 0.05, 0, 0.5, 0.05, 0.05, 0.05],
    "angry":     [0, 0.8, 0, 0, 0.1, 0, 0.1, 0],
    "scared":    [0, 0, 0, 0.95, 0, 0, 0.03, 0.02],
    "sad":       [0, 0, 0.8, 0, 0, 0.2, 0, 0],
    "surprised": [0.1, 0, 0, 0, 0, 0, 0.85, 0.05],
    "deadpan":   [0, 0, 0, 0, 0, 0, 0, 1.0],
}
DEFAULT_MOOD = "excited"       # Sprocket's resting state is loud
# a mood's pace against the rest (duration_factor multiplier): deadpan's all-calm vector comes out ~10% slower than
# "excited" on the same words (measured 2026-10-17), and deadpan reads his texts, cards and plain facts
PACE = {"deadpan": 0.9}

PAUSE_MS = {".": 300, "!": 350, "?": 350, "...": 600, ",": 130}
LONG = 12    # words: a longer sentence is also split at its commas (a breath, and its first words sooner)
SHORT = 3    # words: a shorter piece joins the next one (a lone "Unmoving." comes out slow and drawn out)
MAX_CHARS = 600


def mood_vector(name):
    return MOODS.get((name or "").strip().lower(), MOODS[DEFAULT_MOOD])


def pace(name):
    return PACE.get((name or "").strip().lower(), 1.0)


def clean(text):
    """What is spoken: no mood tags, markdown, links or emoji; plain spaces."""
    t = re.sub(r"\[(?:%s)\]" % "|".join(MOODS), " ", text or "", flags=re.I)
    t = re.sub(r"https?://\S+", " ", t)
    t = re.sub(r"[*_#`>~|]+", " ", t)
    t = re.sub(r"[\U0001F000-\U0001FAFF☀-➿️]", " ", t)
    return re.sub(r"\s+", " ", t).strip()[:MAX_CHARS]


def pieces(text):
    """[(words, pause after in ms)]: one take each. Sentences; long ones split at their commas; a one- or two-word
    piece joined with the next one (or, at the very end, with the one before) -- but never across a "...", whose
    pause is the point."""
    out = []
    for m in re.finditer(r"[^.!?]+(\.\.\.|[.!?]+)?", clean(text)):
        s = m.group(0).strip()
        if not re.search(r"[A-Za-z0-9]", s):
            continue
        end = "..." if s.endswith("...") else s[-1] if s[-1] in ".!?" else "."
        parts = [s]
        if len(s.split()) > LONG and "," in s:
            bits = [b.strip() for b in s.split(",") if b.strip()]
            parts = [b + "," for b in bits[:-1]] + [bits[-1]]
        for i, p in enumerate(parts):
            out.append((p, PAUSE_MS[","] if i < len(parts) - 1 else PAUSE_MS.get(end, 300)))
    grouped, cur = [], None
    for words, pause in out:
        cur = (words, pause) if cur is None else (cur[0] + " " + words, pause)
        if len(cur[0].split()) >= SHORT or pause == PAUSE_MS["..."]:   # a "..." keeps its pause: never joined across
            grouped.append(cur)
            cur = None
    if cur is not None:
        if grouped and grouped[-1][1] != PAUSE_MS["..."]:
            grouped[-1] = (grouped[-1][0] + " " + cur[0], cur[1])
        else:
            grouped.append(cur)
    return grouped


def effect(sr):
    """Robot effect 11 (voice/EFFECTS.md) at the audio's own sample rate: smaller, 8-bit, buzz, tin can."""
    return (f"asetrate={sr}*1.19,aresample={sr},atempo=0.84,acrusher=bits=8:mode=log:aa=1,tremolo=f=55:d=0.5,"
            "aecho=0.8:0.9:5:0.6,highpass=f=350,lowpass=f=3800,acompressor=threshold=0.1:ratio=4")
