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
4. the Android client planner for one unknown edge, with Gemini → OpenRouter failover.

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
- An on-device task generator that learns verified workflow graphs and reuses their component
  sub-skills; only an unknown graph edge falls through to the LLM.
- A direct, digest-verified GitHub skill/workflow catalog with refresh, enable/disable, and contribution UI.
- An Android-resident planner for novel edges, with Keystore-encrypted BYOK credentials.
- A Python planning gateway retained as a development reference and protocol test harness.
- Deterministic replay fixtures and tests for routing, safety, caching, and token reduction.

Alfred does **not** require Docker, Kubernetes, PostgreSQL, or a hosted skill-management API. GitHub
is the reviewed public control plane; the phone is the private personal-skill store. On a miss, the
phone can call the selected model provider directly without an Alfred backend.

## Architecture

```text
voice/text goal
      |
      v
Android app
  active workflow edge -> built-ins -> personal phone cache -> GitHub workflows/skills -> client planner
      |                |                       |                         |
      |          private SQLite       static JSON + SHA-256       Gemini -> OpenRouter
      |                                   from GitHub              unknown edge only
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
Accessibility settings. The built-in routes, local cache, and downloaded GitHub skills need no
model key or Python service.

## Optional model fallback

Planning logic runs in the Android app. In **Settings → Inference**, optionally enter a Gemini key
and an OpenRouter key. Android Keystore encrypts both at rest. Gemini is tried first and OpenRouter
is used when Gemini is unavailable, quota-limited, or returns an invalid plan.

Only the goal, a bounded redacted semantic screen, the recent verified path, and at most five
relevant skill hints are sent. The model supplies one missing edge; Android validates, confirms,
executes, verifies, and learns it locally. See [Model providers](docs/model-providers.md).

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

The core routing, Android frontend, client planner, local learning, GitHub catalog client, and Gmail
briefing paths are implemented. Production work still includes signed release manifests,
stronger publisher governance, broader device/application testing, accessibility hardening, and a
formal security review.

Apache-2.0. See [LICENSE](LICENSE). Third-party services and applications retain their own terms.
