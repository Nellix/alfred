# Model providers

Alfred calls a model only after its workflow graph, built-in actions, private generalized cache,
and downloaded GitHub skills all miss. Planning logic stays in the Android app; no Alfred backend
is required.

The client provider order is fixed and intentional:

1. Gemini;
2. OpenRouter.

If Gemini rejects credentials, exhausts quota, is unavailable, or returns an invalid action,
Alfred tries OpenRouter. If both providers fail, the current workflow stops with a sanitized error
and no unverified edge is learned.

## Configuration

Open **Settings → Inference** and enter one or both provider keys. The key fields are write-only:
after saving, the UI reports that a key exists but never displays it again. Keys are AES-GCM
encrypted with a non-exportable Android Keystore key. Removing the saved model keys deletes both
encrypted values.

The defaults are:

```text
Gemini:    gemini-3.5-flash-lite
OpenRouter: openrouter/free
```

Model names remain editable because free-tier availability and quotas can change. At least one key
is needed only for novel edges; every deterministic and learned route continues to work without
provider configuration.

## What a provider receives

For one unknown edge the app sends:

- the authorized user goal;
- current package and stable semantic screen ID;
- up to 80 visible, non-secret semantic controls;
- a compact summary of at most five verified workflow steps;
- at most five relevant private/public skill descriptions selected locally.

The app does not send screenshots, the complete accessibility tree, the full skill registry,
stored workflow graphs, GitHub credentials, Gmail body text, or provider keys belonging to another
provider.

## Structured response and local enforcement

Both requests require a JSON-schema result containing a canonical intent, typed parameters, and
exactly one proposed semantic action. The Android client then independently:

1. rejects blocked intents and unsupported actions;
2. resolves interactive handles against the current reduced screen;
3. rejects missing, ambiguous, destructive, or sensitive targets;
4. converts unique targets to stable semantic labels;
5. asks for confirmation before clicks and text entry;
6. reacquires the live accessibility node and executes the action;
7. verifies a semantic screen change;
8. saves a parameterized local artifact only after success.

The model is a constrained gap-filler, not the authority that executes or learns an action.

## Gemini

The client uses Google's native `generateContent` endpoint with JSON-schema-constrained output and
the API key in the `x-goog-api-key` header. Create a development key in Google AI Studio and paste
it into Alfred Settings. Quotas vary by account, project, and model.

Official references: [Gemini API](https://ai.google.dev/gemini-api/docs),
[structured output](https://ai.google.dev/gemini-api/docs/structured-output),
[pricing](https://ai.google.dev/gemini-api/docs/pricing), and
[rate limits](https://ai.google.dev/gemini-api/docs/rate-limits).

## OpenRouter

The default `openrouter/free` route lets OpenRouter select an available free model. Alfred requests
strict structured output and sets `provider.require_parameters=true`, preventing routing to a
provider that cannot honor the required schema. A specific compatible model can be entered in
Settings instead.

Official references: [free models](https://openrouter.ai/collections/free-models/),
[structured outputs](https://openrouter.ai/docs/guides/features/structured-outputs), and
[pricing/rate limits](https://openrouter.ai/pricing/).

## Privacy note

Free hosted tiers can have different retention or model-improvement terms. Do not use them for
confidential screens. Alfred locally redacts secret/excluded controls, and Gmail message bodies are
handled by the separate on-device briefing path and never enter this planner.

## Python reference gateway

The repository still contains the original FastAPI planning gateway and provider clients for
protocol tests, replay experiments, and comparison during migration. It is not used by the Android
application and is not part of the runtime architecture.
