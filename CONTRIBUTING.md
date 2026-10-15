# Contributing

Thank you for helping improve Alfred. Open an issue before a large architectural change. Keep pull
requests focused, explain user-visible behavior and safety impact, and include tests for changed
logic.

Before submitting code, run:

```bash
.venv/bin/pytest -q
.venv/bin/ruff check .
.venv/bin/mypy src/sma
cd android
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest assembleDebug lintDebug
```

Never commit provider keys, signing keys, `.env`, personal prompts, contacts, messages, screenshots,
accessibility captures, or device traces containing user data.

Public skill additions belong in
[`alfred-skills`](https://github.com/Nellix/alfred-skills) and must follow that repository's pull
request and privacy rules.
