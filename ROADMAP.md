# Roadmap

Sprocket is a personal project, shared as-is, and grows with what its owner uses it for. This is what's likely,
what might happen, and what won't (with the reason). Ideas are welcome in [Discussions](../../discussions/categories/ideas).

## Planned
- **Where's my food?** "Where's my order?" opens your delivery app or the restaurant's own app and reads the live
  status: being made, picked up, or a few minutes away.

- **Food spending.** "How much have I spent on DoorDash this month?" reads the order history in your delivery apps and
  adds it up, which fits with lunch budgets and remembering your food.

- **Scheduled and conditional tasks.** Ask once and Sprocket does it later, or on repeat: "at 8 tomorrow, check the
  weather and only tell me if it'll rain", "every Friday at 5, see if my usual pizza place has a deal". It runs on
  time even when you're not in the app, and anything that sends, calls or buys still waits for your yes (it asks with
  a notification). The idea comes from [Mika](https://github.com/wangzan101/Mika).

- **Correct it mid-task.** Talk to Sprocket while it's working to change the plan ("make that a large", "skip the
  fries") without starting over, and after you stop it, "keep going" picks up where it left off. Also an idea from Mika.

- **Smaller models tested.** Sprocket is tested on Qwen 3.8 27B, which needs a big graphics card. Planned: run the
  practice tests on smaller models that fit everyday graphics cards (8 to 16 GB) through the go-between, and list
  which ones handle Sprocket's tools well, with their scores and speed, so you can pick one for the computer you have.

- **More phones tested.** It's tested on one Samsung phone. Reports from other phones, good or bad, help most.
- **Setup polish,** from what new users run into.
- **More cloud brains.** Today the cloud choices are Claude (Sonnet, or Haiku to save money; Opus for "think
  harder"), DeepSeek and OpenAI. Planned: Google's Gemini, with its own key, tested with Sprocket's tools before it's
  offered.

- **A practice ground.** Sprocket's brain works through hard, made-up phone situations before releases (two contacts
  with the same name, a tool that times out after it worked, a change of mind halfway, a card you cancel), with pretend
  apps and data only. A checker grades what actually happened, not what Sprocket said, and each failure becomes a fix.

- **Show me how.** Teach Sprocket a task by doing it once yourself: say "watch me", then tap through it (a setting
  buried three screens deep, a reorder in a food app). Sprocket records which screens you open and which buttons you
  tap, and saves them as a skill it follows the next time you ask, checking each step still matches as it goes. Only
  while you've asked it to watch, with a notice on screen the whole time. What you type and anything in banking or
  password apps is never recorded, and recordings stay on the phone.

- **Home devices.** Control the smart-home and TV apps already on your phone by voice. Remote apps are often all
  picture buttons, so this needs testing app by app. (Checking your cameras already works: see the README.)

## Maybe
- **Routines.** Save a few actions under one phrase and run them again later: "bedtime" sets the alarm, turns on Do Not
  Disturb and dims the screen. Anything risky in a routine still asks first, like it does today.
- **What did I miss?** A spoken run-through of the notifications that came in since you last looked, leaving out the
  noise.
- Other languages for talking and listening.

## Not planned
- **Google Play.** Play's rules don't allow an app to use Android's Accessibility service to carry out actions on
  its own, which is what screen control does. Sprocket stays a GitHub download.
- **iPhone.** iOS doesn't let an app tap through other apps, which screen control needs.
- **An always-listening wake word.** Sprocket listens when you tap Talk, for a moment after it answers out loud (so you
  can reply), when a card asks for a yes or no, and for a few seconds after announcing a call (if the call announcer
  is on). It never listens in the background otherwise.
- **Analytics or tracking** of any kind.
