# Roadmap

Sprocket is a personal project, shared as-is, and grows with what its owner uses it for. This is what's likely,
what might happen, and what won't (with the reason). Ideas are welcome in [Discussions](../../discussions/categories/ideas).

## Planned
- **More phones tested.** It's tested on one Samsung phone. Reports from other phones, good or bad, help most.
- **Setup polish,** from what new users run into.
- **Faster first words on your own model.** Before answering, your model reads Sprocket's instructions (about 16,000
  words), which takes 15 seconds or more on a home graphics card when it hasn't just read them. Two ways to cut that:
  start sending the instructions the moment you tap Talk, so only your words are left when you finish speaking; and
  send shorter instructions, with only the tools a question is likely to need.
- **More cloud brains.** Today the cloud choice is Claude Sonnet (Opus for "think harder"). Planned: pick other
  Anthropic models (a cheaper, faster one, for instance), and DeepSeek and Google's Gemini through their own APIs,
  each with its own key, tested with Sprocket's tools before it's offered.
- **Morning brief news on any topic.** Today the brief's news section is about AI. You'll pick your own topics
  instead (a sports team, gardening, your town, a hobby), in the walkthrough and in Settings, and it searches those.

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
