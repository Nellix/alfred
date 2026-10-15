# Development

## Repository layout

```text
android/                 Independent Android frontend and device runtime
src/sma/                 Optional Python planner, semantic protocol, replay tools
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

`sma serve` is optional and provides only planning/model inference. It is not needed for GitHub skill
refresh and it does not provide skill-management APIs.

## Android build and tests

```bash
cd android
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew \
  testDebugUnitTest assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a planner running on the development machine:

```bash
adb reverse tcp:8765 tcp:8765
```

Then use `http://127.0.0.1:8765` as the optional planner URL in Alfred settings. The GitHub catalog
URL is independent and defaults to the raw `main` branch of `Nellix/alfred-skills`.

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

Clone `alfred-skills`, add a generalized JSON skill, increment its version and the catalog revision,
then run:

```bash
python3 scripts/build_registry.py --write --revision <next>
python3 scripts/build_registry.py --check
```

Submit the change as a pull request. Do not add original prompts, contacts, message content, account
identifiers, screenshots, or traces.

## Dependency policy

There is no required container runtime, cluster, PostgreSQL instance, or registry server. Python is
needed only for development tools and the optional model planner; Android can operate locally with
built-in, personal, and downloaded skills.
