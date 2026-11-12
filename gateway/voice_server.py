"""Sprocket's voice on the gateway (phase 4, the owner 2026-10-17): IndexTTS 2.5 copying voice A, at the settings he
picked (voice/INDEXTTS.md), always loaded (his exception to the gateway's no-auto-load rule), on port 8472 --
reachable only over Tailscale (the gateway's firewall lets nothing else in).

  POST /speak  {"text": "...", "mood": "scared"}  ->  audio/ogg (Opus): robot effect 11, each piece's pause included
  GET  /health                                    ->  {"ok": true, ...}

The phone sends each sentence group as Claude writes it; this makes it one take per piece (voicetext.pieces), with
an emotion preset (voicetext.MOODS). One request at a time (one GPU). IndexTTS itself is VoiceStudio's install
(~/.omnivoice/engines/indextts2), built the way VoiceStudio builds it; its 25 refinement steps are made 10 by a
patched copy of infer_v2_5.py loaded here (the install is never changed). Run with IndexTTS's own Python,
CUDA_DEVICE_ORDER=PCI_BUS_ID CUDA_VISIBLE_DEVICES=0 (its emotion parts fail on the V100):
  voice_server.py [--host 0.0.0.0] [--port 8472]
"""
import argparse
import builtins
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import types
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import voicetext as V  # noqa: E402

HERE = Path(__file__).parent
# Where things are (set these for your own machine; see gateway/README.md). It was built around the author's setup:
# IndexTTS 2.5 as installed by VoiceStudio, whose helper builds the model; expect to adapt load() for yours.
REPO = os.path.expanduser(os.environ.get("INDEXTTS_DIR", "~/.omnivoice/engines/indextts2/index-tts-2.5"))
VS = os.path.expanduser(os.environ.get("VOICESTUDIO_INDEXTTS",
                                       "~/.config/VoiceStudio/runtime/project/backend/engines/indextts/main.py"))
REF = os.path.expanduser(os.environ.get("VOICE_REF", str(HERE / "voice-A.wav")))   # the voice to copy: your own recording
os.environ.setdefault("HF_HUB_CACHE", REPO + "/checkpoints/hf_cache")
os.environ["HF_HUB_OFFLINE"] = "1"           # everything is downloaded: never fetch
sys.path.insert(0, REPO)

STEPS, BEAMS, SPEED = 10, 1, 0.85            # his picks: 10 refinement steps, 1 beam, 15% faster


def need(path, what):
    if not Path(path).exists():
        raise SystemExit(f"missing {what}: {path} (see gateway/README.md)")


def load():
    """IndexTTS 2.5 as VoiceStudio builds it, with torch.compile, and the step count from STEPS."""
    need(Path(REPO) / "indextts" / "infer_v2_5.py", "IndexTTS 2.5 (set INDEXTTS_DIR)")
    need(VS, "VoiceStudio's IndexTTS helper (set VOICESTUDIO_INDEXTTS)")
    need(REF, "the reference recording of the voice to use (set VOICE_REF)")
    path = Path(REPO) / "indextts" / "infer_v2_5.py"
    src = path.read_text()
    if src.count("diffusion_steps = 25") != 1:
        raise SystemExit("infer_v2_5.py changed (the 25-step constant moved): check it before running")
    builtins.__sprocket_steps__ = STEPS
    src = src.replace("diffusion_steps = 25", "diffusion_steps = __import__('builtins').__sprocket_steps__")
    mod = types.ModuleType("indextts.infer_v2_5")
    mod.__file__, mod.__package__ = str(path), "indextts"
    sys.modules["indextts.infer_v2_5"] = mod
    exec(compile(src, str(path), "exec"), mod.__dict__)
    spec = importlib.util.spec_from_file_location("vs_indextts", VS)
    vs = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vs)
    kw = vs._model_init_kwargs(REPO, version="2.5", reduced_precision=True)
    with vs._local_checkpoint_config(kw["cfg_path"], kw["model_dir"]) as cfg:
        return mod.IndexTTS2(**{**kw, "cfg_path": cfg, "use_torch_compile": True})


def trim(pcm, sr, db=-40, keep_ms=30):
    """A take without its quiet start and end, so the pauses are what voicetext sets."""
    x = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768
    hop = max(1, sr // 100)
    rms = np.sqrt(np.convolve(x ** 2, np.ones(hop) / hop, mode="same")) + 1e-9
    loud = np.where(20 * np.log10(rms) > db)[0]
    if len(loud) == 0:
        return pcm
    keep = int(sr * keep_ms / 1000)
    return pcm[max(0, loud[0] - keep) * 2:min(len(x), loud[-1] + keep) * 2]


class Voice:
    def __init__(self):
        t = time.time()
        self.model = load()
        self.lock = threading.Lock()
        self.tmp = Path(tempfile.mkdtemp(prefix="sprocket-voice-")) / "take.wav"
        for _ in range(2):                    # torch.compile happens on the first calls
            self.take("Warming up the circuits.", V.MOODS["deadpan"], V.pace("deadpan"))
        self.loaded_s = round(time.time() - t, 1)

    def take(self, words, vec, pace=1.0):
        self.model.infer(spk_audio_prompt=REF, text=words, output_path=str(self.tmp), lang="en", verbose=False,
                         num_beams=BEAMS, emo_vector=vec, interval_silence=0, duration_factor=SPEED * pace)
        with wave.open(str(self.tmp)) as w:
            return w.readframes(w.getnframes()), w.getframerate()

    def speak(self, text, mood):
        """(ogg bytes, pieces, ms to make) -- or (None, 0, 0) when there is nothing to say."""
        parts = V.pieces(text)
        if not parts:
            return None, 0, 0
        vec, pace = V.mood_vector(mood), V.pace(mood)
        t = time.time()
        with self.lock:
            audio, sr = bytearray(), 22050
            for words, pause in parts:
                pcm, sr = self.take(words, vec, pace)
                audio += trim(pcm, sr) + b"\0\0" * int(sr * pause / 1000)
        made = int((time.time() - t) * 1000)
        ogg = subprocess.run(["ffmpeg", "-v", "error", "-f", "s16le", "-ar", str(sr), "-ac", "1", "-i", "-",
                              "-af", V.effect(sr), "-c:a", "libopus", "-b:a", "32k", "-f", "ogg", "-"],
                             input=bytes(audio), capture_output=True, check=True).stdout
        return ogg, len(parts), made


VOICE = None


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def reply(self, code, body, ctype="application/json", extra=None):
        self.send_response(code)
        self.send_header("content-type", ctype)
        self.send_header("content-length", str(len(body)))
        for k, v in (extra or {}).items():
            self.send_header(k, str(v))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, json.dumps({"ok": True, "engine": "indextts-2.5", "loaded_s": VOICE.loaded_s,
                                               "moods": list(V.MOODS), "default_mood": V.DEFAULT_MOOD}).encode())
        self.reply(404, b'{"error":"not here"}')

    def do_POST(self):
        if self.path != "/speak":
            return self.reply(404, b'{"error":"not here"}')
        try:
            n = int(self.headers.get("content-length", 0))
            if n > 20000:
                return self.reply(413, b'{"error":"too long"}')
            req = json.loads(self.rfile.read(n) or b"{}")
            ogg, parts, made = VOICE.speak(str(req.get("text", "")), str(req.get("mood", "")))
        except Exception as e:                # a bad request or a failed take: say so, keep serving
            return self.reply(500 if not isinstance(e, ValueError) else 400, json.dumps({"error": str(e)[:300]}).encode())
        if ogg is None:
            return self.reply(204, b"")
        self.reply(200, ogg, "audio/ogg", {"x-made-ms": made, "x-pieces": parts})

    def log_message(self, fmt, *args):       # lengths and times only: never what was said
        sys.stderr.write(f"{time.strftime('%H:%M:%S')} {self.command} {self.path} {args[1] if len(args) > 1 else ''}\n")


def main():
    global VOICE
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8472)
    a = ap.parse_args()
    probe = ThreadingHTTPServer((a.host, a.port), Handler)   # one copy only: a second start fails to bind, BEFORE
    probe.server_close()                                      # it loads ~7 GB onto the 4070
    VOICE = Voice()
    print(f"sprocket-voice: IndexTTS loaded in {VOICE.loaded_s}s; serving on {a.host}:{a.port}", flush=True)
    ThreadingHTTPServer((a.host, a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
