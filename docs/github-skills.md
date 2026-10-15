# GitHub skill lifecycle

Alfred uses a separate GitHub repository as the public skill registry. It replaces the former
PostgreSQL control plane, management API, Docker Compose stack, and Kubernetes deployment.

## Repository structure

```text
skills/<publisher>/<application>/<intent>.json
registry/index.json
schema/skill.schema.json
scripts/build_registry.py
docs/
```

The directory layout groups skills by publisher/platform and application. A skill has a stable
dotted ID, monotonically increasing version, generalized goal template, typed parameters, source
screen constraint, safety level, and bounded semantic actions.

## Publishing workflow

1. A contributor exports or writes a generalized skill; observed user data is removed.
2. The contributor forks `alfred-skills` and opens a pull request.
3. CI validates every file and checks that `registry/index.json` is deterministic and current.
4. A maintainer reviews behavior, privacy, parameterization, and safety.
5. Merge to protected `main` publishes the static catalog.

No client writes directly to `main`, and the Android app never carries a GitHub write token.

## Client refresh

The Skill Library shows the installed revision, last successful refresh, last error, downloaded
skills, and enable state. A user can force refresh; the app also refreshes a stale catalog when the
library opens.

The client downloads `registry/index.json`, enforces a monotonic revision, downloads every declared
skill, verifies its digest and schema, applies local safety policy, and commits the complete set in
one SQLite transaction. Partial or invalid downloads do not replace the previous catalog.

The app currently reads raw GitHub content. Git itself is not embedded in the APK and a complete
repository clone is unnecessary.

## Personal-to-public promotion

A personal learned pattern remains local. For parameterized patterns, **Prepare GitHub pull
request** copies a generalized JSON proposal and opens the contribution guide. The human must inspect
it, remove any private values, test it, and submit it to GitHub.

This is deliberately not automatic self-publishing. Execution history proves that a pattern worked
for one device; it does not prove universal compatibility or public safety.

## Versioning rules

- Increment a skill's `version` whenever its behavior changes.
- Increment the registry `revision` for every merged catalog change.
- Never change content under an existing skill version.
- Removing a skill from the index removes it from clients on their next successful refresh.
- A local enable/disable choice is preserved when a downloaded skill is updated.

## Future hardening

Before production: signed release manifests, multiple maintainers, mandatory reviews for sensitive
skills, publisher ownership rules, compatibility metadata, revocation advisories, staged channels,
and automated Android replay evidence attached to pull requests.
