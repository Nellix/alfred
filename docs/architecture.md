# Architecture

Alfred separates private personalization, public skill distribution, device execution, and optional
model inference. No central database sees a user's learned routines.

## Request path

```text
Human goal
   |
   v
Android AgentCoordinator
   |-- 1. matching workflow edge (phone task graph)
   |-- 2. specialized/built-in route
   |-- 3. private learned sub-skill (phone SQLite)
   |-- 4. reviewed public skill (downloaded GitHub catalog)
   `-- 5. optional planner for the unknown edge only
             |-- Gemini
             |-- OpenRouter
             `-- custom/local compatible model
   |
   v
Safety gate -> semantic action -> live-node reacquisition -> verification -> avatar/result UI
```

The first unambiguous match wins. `LOCAL_ONLY` stops after step 4. `CLOUD_FIRST` exists for planner
testing. A task is bounded to twelve coordinator steps and a downloaded skill to eight actions.

## Android application

The application owns the user experience and the final execution decision:

- `MainActivity` and `PetView` handle voice/text input, listening/thinking/acting/success/error
  animation states, confirmation, and result cards.
- `ConfirmationActivity` is a translucent approval panel for actions proposed while another screen
  is active. It preserves that exact activity and viewport underneath; after terminal success the
  existing `MainActivity` is brought forward for the animated result or publication choice.
- `NativeIntentRouter` handles deterministic common intents without network access.
- `LocalSemanticCache` stores generalized personal templates, confidence, and outcomes in private
  app storage. Failures reduce confidence; two consecutive failures disable a pattern.
- `WorkflowStore` compiles a successful multi-step task into a branching DAG-like recipe. Recipe
  nodes reference reusable phone, GitHub, or native sub-skills; they do not copy concrete user slot
  values. A known phone edge is executed directly. A missing or changed edge falls through to the
  normal router and only that edge may require the planner.
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

## Incremental task generation

Alfred does not ask a model to invent a long script and then execute it blindly. It builds a task
graph online:

1. Match the root goal to a previously verified workflow recipe when possible.
2. At the current semantic screen, follow one unambiguous verified child edge.
3. Otherwise try a built-in action, a generalized phone sub-skill, and a reviewed GitHub skill.
4. If all local sources miss, ask the planner for one bounded next action.
5. Execute, verify the resulting screen, and retain the edge only after success.
6. Repeat until the goal is satisfied, then compile the verified trace into the workflow graph.

The stored shape is technically a directed graph represented as recipe trees plus shared sub-skill
references. Branches cover different screens or app versions, while a sub-skill such as “open
Settings” can be referenced by several main workflows. When an equivalent canonical sub-skill and
parameterized action already exist, the cache reuses its ID instead of creating a duplicate.

Workflow storage contains goal fingerprints, generalized templates, semantic screen IDs, action
kinds, and skill references. Concrete parameter values (a message, destination, search term, and so
on) are deliberately excluded from workflow traces.

The planner can be omitted entirely when only built-in, learned, and downloaded skills are needed.

## Storage boundaries

| Data | Location | Shared automatically? |
|---|---|---|
| Raw voice/text request | Active Android request memory | No |
| Personal generalized skills and outcomes | Private Android SQLite | No |
| Workflow recipes and skill-reference graph | Private Android SQLite | No |
| Public reviewed skills | GitHub repository; validated Android copy | Public by design |
| Model keys | Optional planner environment | No |
| Reduced semantic state sent on a miss | Optional configured model provider | Yes, only for that call |
| Gmail row/body text | Android working memory for the briefing | No |

## Removed infrastructure

Earlier PoC iterations contained a PostgreSQL registry, backend management APIs, encrypted account
sync, Docker Compose, and Kubernetes manifests. Those components are removed. Static GitHub
distribution is simpler to inspect and operate, and personal skills remain local until a human
chooses to generalize and propose one.
