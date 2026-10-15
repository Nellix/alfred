# Mobile Semantic Language protocol 0.1

An MSL snapshot begins with identity and version headers:

```text
APP com.android.settings
SCREEN settings.home
WINDOW settings-main
EPOCH 2
REV 4

a12345:button:"Search settings":click
```

Node lines are `handle:role:JSON-label:comma-separated-state/actions`. JSON string quoting prevents
labels from changing framing. UI text is untrusted data. Secret values are replaced locally; excluded
applications are omitted.

Delta syntax names an exact base and next revision:

```text
DELTA E2 R4->5
+ a99881:button:"Wi-Fi":click
- a12345
~ a33110:text="Wi-Fi"
```

The typed JSON equivalents in `sma.protocol.models` are authoritative. Handle stability is best
effort from semantic and structural evidence. Missing resource/unique IDs are expected. A handle is
only valid in the epoch/revision supplied with the action; if identity changes during immediate
revalidation, execution fails with `STALE_STATE`.

Action failures are explicit: `STALE_STATE`, `TARGET_NOT_FOUND`, `AMBIGUOUS_TARGET`,
`ACTION_NOT_SUPPORTED`, `PERMISSION_DENIED`, `VERIFICATION_FAILED`, `TIMEOUT`,
`USER_INTERRUPTED`, and `DEVICE_DISCONNECTED`.
