# Security and privacy

Alfred controls another application through Android Accessibility, so a mistake can have real side
effects. The proof of concept uses defense in depth but has not received an independent security
audit.

## Execution controls

- Actions use a small typed vocabulary and a maximum coordinator step count.
- Interactive targets are semantic labels, not untrusted coordinates from a model or skill.
- The live accessibility node is reacquired before execution; stale or ambiguous matches fail.
- Post-action state is checked before a model result is eligible for learning.
- Message sends, deletion, purchases, payments, transfers, installs, uninstalls, and factory reset
  targets are rejected in downloaded skills.
- Interactive downloaded skills are confirmation-gated even if their document declares otherwise.
- Local patterns lose confidence after failures and disable after repeated failure.

These controls reduce risk; they do not make arbitrary automation safe. Production releases need a
formal action policy, threat model, audit, abuse testing, and per-domain confirmation rules.

## Privacy boundaries

Personal learned skills are stored in Android private app storage. They are not synchronized or
uploaded. The application can export a parameterized proposal only after an explicit tap; the user
then reviews and submits it through GitHub.

Raw prompts, parameter values, contacts, destinations, messages, screenshots, and accessibility
dumps must never enter the public skill repository. Public skills contain only generalized templates,
typed placeholders, bounded actions, and documentation.

The optional planner receives a goal and reduced semantic UI state only on a local miss. API keys
stay in its process environment. Free hosted model tiers may retain or train on submitted data under
their own terms, so do not use them for confidential screens.

Gmail inbox-row briefing does not open messages. Message-body summary requires explicit confirmation,
processes visible text on the phone, and does not log, learn, publish, or send body text to a model.

## GitHub catalog trust

The current catalog trust boundary is:

- pull-request review and repository branch rules;
- CI validation of schema and safety constraints;
- HTTPS transport from raw GitHub;
- monotonic revision checks;
- SHA-256 verification of every downloaded file;
- a second independent validation pass in Android;
- atomic replacement, retaining the last valid catalog on failure.

SHA-256 protects consistency, not maintainer-account compromise. Production work should add signed,
versioned release manifests, protected signing keys, recovery/revocation procedures, and reproducible
release evidence.

## Secrets

Never commit `.env`, provider keys, private keys, Android signing keys, local databases, captured
screens, or user traces. The repository `.gitignore` excludes the development forms of these files.
Run a secret scanner in CI before accepting outside contributions.

## Reporting

Do not open a public issue for an exploitable vulnerability or leaked credential. Use the private
security-reporting channel configured on the GitHub repository once available; until then contact
the repository owner privately.
