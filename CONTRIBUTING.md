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
