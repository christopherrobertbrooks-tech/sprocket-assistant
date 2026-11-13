# Roadmap

Sprocket is a personal project, shared as-is, and grows with what its owner uses it for. This is what's likely,
what might happen, and what won't (with the reason). Ideas are welcome in [Discussions](../../discussions/categories/ideas).

## Planned
- **More phones tested.** It's tested on one Samsung phone. Reports from other phones, good or bad, help most.
- **Setup polish,** from what new users run into.
- **A bigger memory.** Sprocket keeps up to 100 short notes and sends all of them with every message. The plan is to
  hold far more and bring up only the notes that matter for what you're asking, still stored on your phone.
- **Faster first words on your own model.** Before answering, your model reads Sprocket's instructions (about 16,000
  words), which takes 15 seconds or more on a home graphics card when it hasn't just read them. Two ways to cut that:
  start sending the instructions the moment you tap Talk, so only your words are left when you finish speaking; and
  send shorter instructions, with only the tools a question is likely to need.
- **A backup brain.** If your own server doesn't answer, Sprocket switches to Claude for that question (when you have
  a Claude key), and if Claude is down, it switches to your own server. It tells you when it does.
- **App memory for screen control** (being built). Today Sprocket works out where things are in an app every time it
  taps through it. It will keep a short note per app ("Battery is under Device care"), read it before tapping, and add
  what it learns after a task that worked. The notes stay on your phone, and you can read or delete them under
  ⋯ > Memory. Ideas from [Mika](https://github.com/wangzan101/Mika)'s per-app knowledge files and the
  [TAG-Nav](https://arxiv.org/abs/2610.22672) paper.

## Maybe
- Other languages for talking and listening.
- More brains tested through the go-between (it should already work with any OpenAI-style server that handles
  tool calls well).

## Not planned
- **Google Play.** Play's rules don't allow an app to use Android's Accessibility service to carry out actions on
  its own, which is what screen control does. Sprocket stays a GitHub download.
- **iPhone.** iOS doesn't let an app tap through other apps, which screen control needs.
- **An always-listening wake word.** Sprocket listens when you tap Talk, for a moment after it answers out loud (so you
  can reply), when a card asks for a yes or no, and for a few seconds after announcing a call (if the call announcer
  is on). It never listens in the background otherwise.
- **Analytics or tracking** of any kind.
