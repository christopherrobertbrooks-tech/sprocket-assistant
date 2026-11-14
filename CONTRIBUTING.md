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
