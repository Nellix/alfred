# Architecture

Alfred separates private personalization, public skill distribution, device execution, and optional
model inference. No central database sees a user's learned routines.

## Request path

```text
Human goal
   |
   v
Android AgentCoordinator
   |-- 1. specialized/built-in route
   |-- 2. private learned pattern (phone SQLite)
   |-- 3. reviewed public skill (downloaded GitHub catalog)
   `-- 4. optional planner on a cache miss
             |-- Gemini
             |-- OpenRouter
             `-- custom/local compatible model
   |
   v
Safety gate -> semantic action -> live-node reacquisition -> verification -> avatar/result UI
```

The first unambiguous match wins. `LOCAL_ONLY` stops after step 3. `CLOUD_FIRST` exists for planner
testing. A task is bounded to twelve coordinator steps and a downloaded skill to eight actions.

## Android application

The application owns the user experience and the final execution decision:

- `MainActivity` and `PetView` handle voice/text input, listening/thinking/acting/success/error
  animation states, confirmation, and result cards.
- `NativeIntentRouter` handles deterministic common intents without network access.
- `LocalSemanticCache` stores generalized personal templates, confidence, and outcomes in private
  app storage. Failures reduce confidence; two consecutive failures disable a pattern.
- `GitHubSkillStore` stores only the currently validated public catalog and per-device enable flags.
- `SemanticAccessibilityService` exposes a reduced semantic screen, reacquires live nodes before an
  action, rejects ambiguous/destructive targets, and verifies the result.

The Gmail paths are purpose-built. Inbox-row briefing never opens mail. Message-body summary asks
for confirmation and processes visible content locally; raw content is not learned or sent to the
planner.

## Public skill registry

The separate [`alfred-skills`](https://github.com/Nellix/alfred-skills) repository is a static,
reviewed registry:

```text
pull request -> schema/safety validation -> review -> merge
     -> deterministic registry/index.json -> raw GitHub HTTPS
     -> Android digest/schema/policy verification -> atomic local replacement
```

Each index entry contains an ID, version, path, and SHA-256 digest. The client rejects malformed
paths, duplicate IDs, oversized catalogs/files, unsupported actions, unsafe URIs, destructive
targets, revision rollback, and same-revision content changes. A failed refresh leaves the previous
working catalog intact.

GitHub is a distribution and review mechanism, not personal synchronization. Users can export a
generalized proposal to the clipboard and explicitly open the contribution guide. No background
upload exists.

## Optional planner

The Python FastAPI component exposes planning endpoints for novel requests. It receives the goal
and a reduced semantic state, validates structured model output, and returns one bounded action at
a time. Provider credentials stay in the planner environment.

After Android reports a verified successful action, the planner may produce a generalized artifact
for the phone cache. Its local SQLite result cache is an optimization for a development planner,
not the public skill registry and not a cross-device personal-skill service.

The planner can be omitted entirely when only built-in, learned, and downloaded skills are needed.

## Storage boundaries

| Data | Location | Shared automatically? |
|---|---|---|
| Raw voice/text request | Active Android request memory | No |
| Personal generalized skills and outcomes | Private Android SQLite | No |
| Public reviewed skills | GitHub repository; validated Android copy | Public by design |
| Model keys | Optional planner environment | No |
| Reduced semantic state sent on a miss | Optional configured model provider | Yes, only for that call |
| Gmail row/body text | Android working memory for the briefing | No |

## Removed infrastructure

Earlier PoC iterations contained a PostgreSQL registry, backend management APIs, encrypted account
sync, Docker Compose, and Kubernetes manifests. Those components are removed. Static GitHub
distribution is simpler to inspect and operate, and personal skills remain local until a human
chooses to generalize and propose one.
