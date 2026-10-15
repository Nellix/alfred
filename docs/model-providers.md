# Model providers

Alfred calls a model only after built-in actions, the on-device generalized cache, and downloaded
GitHub skills all miss. Providers are tried in the order declared by `LLM_PROVIDER_CHAIN`. API keys
never enter the Android app.

The recommended chain is:

```bash
LLM_PROVIDER_CHAIN=gemini,openrouter
```

Gemini handles the first request. Authentication, quota/credit, rate-limit, transport, invalid
response-envelope, or provider errors advance to OpenRouter. If every provider fails, Android gets
one sanitized unavailable error. Add `custom` at the end only when a local server is configured:

```bash
LLM_PROVIDER_CHAIN=gemini,openrouter,custom
```

## Gemini free tier (recommended for development)

Create a key in Google AI Studio and start the planner with:

```bash
LLM_PROVIDER_CHAIN=gemini,openrouter \
GEMINI_API_KEY='...' \
GEMINI_MODEL=gemini-3.5-flash-lite \
OPENROUTER_API_KEY='...' \
OPENROUTER_MODEL=openrouter/free \
.venv/bin/sma serve --host 127.0.0.1 --port 8765
```

The implementation uses Google's native `generateContent` endpoint with JSON-schema-constrained
output and identifies itself with `x-goog-api-client`. `gemini-3.5-flash-lite` is the development default because Google
currently lists free-tier input and output for it. Quotas vary by project and model and should be
checked in AI Studio. A `429` is returned to Android as a free-tier rate-limit error.

Official references: [Gemini API](https://ai.google.dev/gemini-api/docs),
[structured output](https://ai.google.dev/gemini-api/docs/structured-output),
[pricing](https://ai.google.dev/gemini-api/docs/pricing), and
[rate limits](https://ai.google.dev/gemini-api/docs/rate-limits).

## OpenRouter free tier

```bash
LLM_PROVIDER_CHAIN=openrouter \
OPENROUTER_API_KEY='...' \
OPENROUTER_MODEL=openrouter/free \
OPENROUTER_APP_NAME=Alfred \
.venv/bin/sma serve --host 127.0.0.1 --port 8765
```

`openrouter/free` lets OpenRouter choose an available free model. Alfred sends
`provider.require_parameters=true` with its JSON Schema, preventing routing to an endpoint that does
not support the structured output required for safe action validation. A specific `:free` model can
be pinned with `OPENROUTER_MODEL`, but free-model availability changes over time.

`OPENROUTER_SITE_URL` is optional and adds the documented `HTTP-Referer` attribution header. The
backend always sends `X-OpenRouter-Title`.

Official references: [free models](https://openrouter.ai/collections/free-models/),
[structured outputs](https://openrouter.ai/docs/guides/features/structured-outputs), and
[pricing/rate limits](https://openrouter.ai/pricing/).

## Local or custom server

The original local-model route remains available for offline/private installations:

```bash
LLM_PROVIDER_CHAIN=custom \
LLM_BASE_URL=http://127.0.0.1:8000/v1 \
LLM_MODEL=your-local-model \
LLM_API_KEY=local \
.venv/bin/sma serve --host 127.0.0.1 --port 8765
```

## Privacy and learning boundary

On a cache miss the selected provider receives the user's current goal and the reduced, redacted
semantic UI state. It does not receive screenshots, the complete accessibility tree, GitHub write
credentials, or stored private skill artifacts. Gemini's free-tier
pricing page currently states that free-tier content may be used to improve Google products;
OpenRouter's data handling depends on the selected routed provider. Do not use a free shared model
for confidential screens. Banking, authentication, password-manager, wallet, OTP, and payment-like
content remain excluded or locally redacted.

The returned JSON is parsed into the strict semantic-action schema and still passes Android policy,
confirmation, live-node reacquisition, and post-action verification. It is learned only after a
verified successful execution. A later parameterized variation then resolves from the private cache
with zero model calls.
