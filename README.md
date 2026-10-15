# Alfred

Alfred is a local-first AI assistant for Android. A human speaks or types a goal; Alfred chooses the
cheapest trusted way to complete it, drives the phone through semantic Android controls, verifies
the result, and presents the outcome through an animated pet interface.

The project is a proof of concept, not a production accessibility or security product.

## Why Alfred

General mobile agents repeatedly spend model tokens rediscovering the same workflows. Alfred turns
verified repetitions into reusable, parameterized skills and resolves every request in this order:

1. built-in deterministic Android routes;
2. personal skills learned and stored only on the phone;
3. reviewed public skills downloaded from
   [`alfred-skills`](https://github.com/Nellix/alfred-skills);
4. an optional LLM planner, with Gemini → OpenRouter → custom/local failover.

A successful model-generated action can become a generalized on-device template, so the next
matching request uses zero model calls. Personal prompts and slot values are not published. Sharing
a generalized skill is an explicit GitHub pull request.

## What is included

- A clean-room Android frontend with voice input and an animated Alfred avatar.
- A bounded accessibility runtime that uses semantic labels, reacquires live nodes, and verifies
  actions instead of replaying raw screen coordinates.
- Built-in routes for common Settings, Maps, YouTube, mail, and messaging-draft tasks.
- Two distinct Gmail briefings: inbox rows only, or confirmed message-body summaries processed on
  the phone.
- A private SQLite semantic cache for self-improving personal patterns.
- A direct, digest-verified GitHub skill catalog with refresh, enable/disable, and contribution UI.
- An optional Python planner for novel requests. Model credentials stay outside the APK.
- Deterministic replay fixtures and tests for routing, safety, caching, and token reduction.

Alfred does **not** require Docker, Kubernetes, PostgreSQL, or a hosted skill-management API. GitHub
is the reviewed public control plane; the phone is the private personal-skill store. Only the
optional LLM planner is a network service.

## Architecture

```text
voice/text goal
      |
      v
Android app
  built-ins -> personal phone cache -> downloaded GitHub skills -> optional LLM planner
      |                |                       |                         |
      |          private SQLite       static JSON + SHA-256       Gemini/OpenRouter/
      |                                   from GitHub               local endpoint
      v
bounded semantic actions -> Android AccessibilityService -> post-action verification
```

See [Architecture](docs/architecture.md) and [GitHub skill lifecycle](docs/github-skills.md).

## Android quick start

Requirements: Android Studio/JDK 17, Android SDK platform 35, ADB, and an Android 8+ device or
emulator.

```bash
cd android
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open Alfred, grant microphone permission, and enable **Alfred Device Actions** in Android
Accessibility settings. The built-in routes, local cache, and downloaded GitHub skills do not need
the Python planner.

## Optional LLM planner

The planner is used only after local routes and skills miss. It keeps API keys off the phone.

```bash
python3 -m venv .venv
.venv/bin/pip install -e '.[dev]'
cp .env.example .env
# Add at least one provider key to .env, then export/load those values.
.venv/bin/sma serve --host 127.0.0.1 --port 8765
adb reverse tcp:8765 tcp:8765
```

The default provider order is Gemini, then OpenRouter. A compatible local endpoint can be appended
as `custom`. See [Model providers](docs/model-providers.md).

## Development checks

```bash
.venv/bin/pytest -q
.venv/bin/ruff check .
cd android
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew testDebugUnitTest assembleDebug lintDebug
```

The independent skill repository validates its catalog with:

```bash
python3 scripts/build_registry.py --check
```

## Documentation

- [Architecture and trust boundaries](docs/architecture.md)
- [GitHub skill lifecycle](docs/github-skills.md)
- [Human acceptance tests](docs/human-acceptance-use-cases.md)
- [Development guide](docs/development.md)
- [Security and privacy](docs/security.md)
- [Model providers](docs/model-providers.md)
- [Semantic protocol](docs/protocol.md)
- [Mobilerun license and clean-room decision](docs/upstream-analysis.md)

## Project status

The core routing, Android frontend, local learning, GitHub catalog client, Gmail briefing paths, and
optional planner are implemented. Production work still includes signed release manifests,
stronger publisher governance, broader device/application testing, accessibility hardening, and a
formal security review.

Apache-2.0. See [LICENSE](LICENSE). Third-party services and applications retain their own terms.
