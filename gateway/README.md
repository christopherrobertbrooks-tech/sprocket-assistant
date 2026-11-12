# Voice server (optional)

`voice_server.py` is the voice server Sprocket's owner uses: it turns each piece of a reply into speech with
**IndexTTS** (bilibili), in a robot style, and returns Opus audio.

- `POST /speak {"text": "...", "mood": "scared"}` -> `audio/ogg`; `GET /health`.

**It's an example to adapt, not a one-step install.** It was built around its owner's machine: IndexTTS 2.5 as
the VoiceStudio app installs it, with VoiceStudio's helper building the model. To run it you need:
- IndexTTS 2.5, with `INDEXTTS_DIR` pointing at it;
- VoiceStudio's IndexTTS helper (`VOICESTUDIO_INDEXTTS`), or your own version of `load()` that builds the model
  the way IndexTTS's README shows;
- **a short recording of the voice you want** (`VOICE_REF`, a WAV file). None is included: use your own voice or
  one you have the right to use.

It stops at start-up and says which of these is missing.
- `voicetext.py` splits text into speakable pieces and picks an emotion preset per mood; `test_voicetext.py` tests
  it with plain `python3`.

**IndexTTS is not included** and has its own licence: the *bilibili Model Use License Agreement*
(`INDEXTTS-LICENSE.txt`, copied here as that licence asks). Install IndexTTS yourself from
https://github.com/index-tts/index-tts and accept its terms. At start-up `voice_server.py` reads IndexTTS's own
`infer_v2_5.py` and runs it with fewer refinement steps (faster); that counts as a modification under the licence, so:

> Any modifications made to the original model in this Derivative Work are not endorsed, warranted, or guaranteed
> by the original right-holder of the original model, and the original right-holder disclaims all liability related
> to this Derivative Work.

Any other server works too, as long as it answers `POST /speak` with audio.
