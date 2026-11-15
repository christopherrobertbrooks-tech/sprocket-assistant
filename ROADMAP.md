# Roadmap

Sprocket is a personal project, shared as-is, and grows with what its owner uses it for. This is what's likely,
what might happen, and what won't (with the reason). Ideas are welcome in [Discussions](../../discussions/categories/ideas).

## Planned
- **More phones tested.** It's tested on one Samsung phone. Reports from other phones, good or bad, help most.
- **Setup polish,** from what new users run into.
- **More cloud brains.** Today the cloud choice is Claude Sonnet (Opus for "think harder"). Planned: pick other
  Anthropic models (a cheaper, faster one, for instance), and DeepSeek and Google's Gemini through their own APIs,
  each with its own key, tested with Sprocket's tools before it's offered.

- **A practice ground.** Sprocket's brain works through hard, made-up phone situations before releases (two contacts
  with the same name, a tool that times out after it worked, a change of mind halfway, a card you cancel), with pretend
  apps and data only. A checker grades what actually happened, not what Sprocket said, and each failure becomes a fix.

- **Show me how.** Teach Sprocket a task by doing it once yourself: say "watch me", then tap through it (a setting
  buried three screens deep, a reorder in a food app). Sprocket records which screens you open and which buttons you
  tap, and saves them as a skill it follows the next time you ask, checking each step still matches as it goes. Only
  while you've asked it to watch, with a notice on screen the whole time. What you type and anything in banking or
  password apps is never recorded, and recordings stay on the phone.

- **A glow while it works.** A soft orange glow around the edge of the screen whenever Sprocket is tapping or reading
  in another app, so you can tell at a glance when it's in control and when it's done. Taps go straight through the
  glow, and it never shows up in what Sprocket reads.

## Maybe
- **Routines.** Save a few actions under one phrase and run them again later: "bedtime" sets the alarm, turns on Do Not
  Disturb and dims the screen. Anything risky in a routine still asks first, like it does today.
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
