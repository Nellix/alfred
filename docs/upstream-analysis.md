# Upstream Mobilerun analysis

Reviewed on 2026-10-13:

| Repository | `main` revision | License | SMA use |
|---|---|---|---|
| [`droidrun/mobilerun`](https://github.com/droidrun/mobilerun) | [`1935ada`](https://github.com/droidrun/mobilerun/commit/1935ada390f4e82c93daa97bd2c58f4794fc1699) | MIT | Interface/behavior reference only |
| [`droidrun/mobilerun-portal`](https://github.com/droidrun/mobilerun-portal) | [`cc3fa91`](https://github.com/droidrun/mobilerun-portal/commit/cc3fa91b15847ca9f7cc9a5afdd6b8e1db7e4ec2) | AGPL-3.0-or-later | Unmodified external local service |

Portal's documented HTTP API uses unauthenticated `GET /ping`, Bearer authentication elsewhere,
`GET /a11y_tree`, `/a11y_tree_full?filter=...`, `/state`, `/phone_state`, `/version`, `/packages`, and
`/screenshot`. Actions include `/tap`, `/swipe`, `/global`, `/app`, base64 keyboard input at
`/keyboard/input`, and authenticated `/app/deep-link`. Its WebSocket endpoint defaults to port 8081 and supports JSON-RPC-style actions and
device events. SMA isolates these revision-specific details in `MobilerunAdapter`.

Mobilerun already supplies general LLM agents, UI/screenshot inspection, mobile action tools,
application knowledge/task mechanisms, CLI/Python APIs, and tracing. Alfred's contribution is
narrower and measurable: a locally retained canonical state, revision-coupled MSL deltas,
scope/identity-validated handles, explicit zero-call guarded skills, and an observation-strategy
benchmark that never counts screenshot bytes as tokens.

No upstream source was copied into this repository. Calling an unmodified Portal over its documented
local API keeps the codebases separate. Modifying/distributing or network-serving a Portal fork can
trigger AGPL source and notice obligations; preserve its copyright/license and obtain counsel for a
distribution decision. This note is engineering guidance, not legal advice.

## Product decision

The MIT `mobilerun` Python framework may be reused if its copyright and license notice are retained.
It provides an agent loop, tools, app cards, tracing, and Portal clients, but its Android path still
requires the separate Portal application. SMA already has its own semantic protocol, policy layer,
verifier, cache, and benchmark, so importing that loop is optional and currently adds more overlap
than leverage.

The Portal is AGPL-3.0-or-later and contains the Android accessibility runtime, local servers,
overlays, streaming, keyboard, installers, notification events, and trigger runtime. Those sources
were not used for the new frontend. `android/app` is a clean-room implementation using Android
public APIs and the SMA HTTP contract. It intentionally implements only product-required semantic
capture and action execution; remote screen streaming, APK installation, unrestricted clipboard or
file access, overlays, and reverse remote control are excluded.

Sources: the upstream repositories' `LICENSE` files and Portal
[`docs/local-api.md`](https://github.com/droidrun/mobilerun-portal/blob/cc3fa91b15847ca9f7cc9a5afdd6b8e1db7e4ec2/docs/local-api.md) and
[`docs/websocket-events.md`](https://github.com/droidrun/mobilerun-portal/blob/cc3fa91b15847ca9f7cc9a5afdd6b8e1db7e4ec2/docs/websocket-events.md).
