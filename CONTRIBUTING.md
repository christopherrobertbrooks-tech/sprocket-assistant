# Contributing

Thanks for wanting to help.

- **Questions** go in [Discussions > Q&A](../../discussions/categories/q-a).
- **Feature ideas** go in [Discussions > Ideas](../../discussions/categories/ideas); check [ROADMAP.md](ROADMAP.md) first.
- **Bugs** go in [Issues](../../issues/new/choose), through the bug form. Leave out keys and anything personal.
- **Code:** please open a discussion before a large change, so we can agree on it first. Plain Java, no Gradle:
  `android/build.sh` builds it and `scripts/test.sh` runs the tests; add a test that fails before your change and
  passes after. For the go-between: `python3 test_messages.py && python3 test_proxy.py`.

Sprocket controls a phone, so changes that send, call, buy, delete or switch something on without the owner's yes
won't be merged.

## Where things are
The app is plain Java in `android/src/dev/sprocket/app/`, one job per file:

- **The chat screen:** `MainActivity.java` (the screen, sending a question, showing the reply). Its pages each have
  their own file: `SettingsPage`, `MemoryPage`, `ListsPage`, `GoalsPage`, `FeedPage`, the first-run walkthrough
  `SetupWalkthrough` (its words and steps are in `Setup`), and the special chat cards `ChatCards` (Studio, the
  morning brief).
- **Talking to the brain:** `Claude.java` (requests, tools, replies; also DeepSeek, OpenAI and your own server).
- **Phone tools:** `PhoneTools.java` (what the brain can ask for) and `Phone.java` (doing it); tools that live inside
  the app (lists, goals, memory, texts) are in `ToolsHere.java`.
- **Screen control and its safety rules:** `ScreenHands.java` (reading and tapping) and `Screen.java` (what needs a
  yes, what's off limits).
- **Texts and calls:** `Messages.java`, `Calls.java`, `CallAnnouncer.java`. **Voice:** `Listen.java`, `VoiceOut.java`,
  `Speech.java`. **The side-button card:** `SprocketSession.java`.
- **Background work:** `BgScreen.java`, `BgSetup.java` and `adb/` (ported from Mika).
- **Tests:** `android/test/`, run with `scripts/test.sh`. The adb-only test door is `TestHooks.java`.
- **The go-between** (for your own model): `go-between/`.

## Using an AI coding agent
AI help is welcome: Sprocket itself is built with one. Because it controls a phone, the bar is the same as for any
change, and these points matter most:

- **You're responsible for every line.** Read it, understand it and test it before you send it. Say in the pull
  request which tool helped.
- **Small, focused changes.** One fix or feature per pull request. Open a discussion first for anything large.
- **Run the build, not just the tests.** `scripts/test.sh` covers the parts that run on a computer, not the whole
  app, so also run `android/build.sh` and make sure it finishes.
- **Try it on a real phone** if it touches screen control, texts, calls or anything else that acts on the phone, and
  say in the pull request what you tried and on which phone.
- **Never weaken the safety rules:** the cards that ask before anything sends, calls, buys, deletes or installs
  (`Screen.java`); banking, wallet and password apps staying off limits; never typing into password fields; and never
  switching on permissions or screen control from code. A change that loosens any of these won't be merged.
- **No secrets or personal details** anywhere: not in code, tests, logs, screenshots, issues or commit messages. No
  API keys, access codes, phone numbers, addresses or private screens.
- **No new places the app talks to,** no tracking, and no new libraries without agreeing it in a discussion first.
- **Your own words.** Write issues, pull requests and replies to reviews yourself. Pasted agent output, issues opened
  in bulk, and drive-by pull requests nobody tested will be closed.

If your agent reads `AGENTS.md` (Claude Code, Codex and Gemini CLI do), it gets these rules too.
