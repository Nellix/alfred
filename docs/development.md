# Development

## Repository layout

```text
android/                 Independent Android frontend and device runtime
src/sma/                 Python planner reference, semantic protocol, replay tools
fixtures/                Deterministic screen and skill fixtures
tests/                   Python unit/integration tests
scripts/                 Live-device smoke helpers
docs/                    Product and engineering documentation
```

The public catalog lives in the separate `alfred-skills` repository.

## Python environment

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
.venv/bin/pytest -q
.venv/bin/ruff check .
.venv/bin/mypy src/sma
```

Useful commands:

```bash
.venv/bin/sma doctor
.venv/bin/sma inspect --mode semantic
.venv/bin/sma benchmark
.venv/bin/sma report
.venv/bin/sma serve --host 127.0.0.1 --port 8765
```

`sma serve` is a development reference for planning/model inference. The Android app does not call
it; it is not needed for GitHub skill refresh and it provides no skill-management APIs.

## Android build and tests

```bash
cd android
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew \
  testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For model fallback testing, enter development Gemini/OpenRouter keys in Alfred Settings. They are
encrypted with Android Keystore and are not stored in normal preferences or build artifacts. The
GitHub catalog URL is independent and defaults to the raw `main` branch of
`Nellix/alfred-skills`.

## Live-device smoke tests

With exactly one authorized device visible in `adb devices`:

```bash
.venv/bin/python scripts/live_native_route_smoke.py --serial <serial>
.venv/bin/python scripts/live_intent_cache_smoke.py --serial <serial>
.venv/bin/python scripts/live_self_improvement_smoke.py --serial <serial>
```

Live tests can change device UI state. Do not run them on banking, authentication, wallet, or other
sensitive screens.

## Changing the public catalog

Clone `alfred-skills`, add a generalized JSON skill or device-verified workflow, increment its
version and the catalog revision, then run:

```bash
python3 scripts/build_registry.py --write --revision <next>
python3 scripts/build_registry.py --check
```

Submit the change as a pull request. Workflows must be acyclic, fully reachable, and include device
verification evidence. Do not add original prompts, contacts, message content, account identifiers,
screenshots, accessibility dumps, traces, or concrete parameter values.

## Dependency policy

There is no required container runtime, cluster, PostgreSQL instance, or registry server. Python is
needed only for development tools and the legacy planner test harness; Android planning and
execution do not depend on a Python service.
