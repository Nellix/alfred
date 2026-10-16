# GitHub skill and workflow lifecycle

Alfred uses a separate GitHub repository as the public skill registry. It replaces the former
PostgreSQL control plane, management API, Docker Compose stack, and Kubernetes deployment.

## Repository structure

```text
skills/<publisher>/<application>/<intent>.json
workflows/<publisher>/<application>/<intent>.json
registry/index.json
schema/skill.schema.json
schema/workflow.schema.json
scripts/build_registry.py
docs/
```

The directory layout groups skills by publisher/platform and application. A skill has a stable
dotted ID, monotonically increasing version, generalized goal template, typed parameters, source
screen constraint, safety level, and bounded semantic actions.

A workflow adds an entry node, a bounded acyclic graph of semantic-screen branches, and
device-verification evidence. Nodes contain generalized actions and child IDs; they never contain
raw prompts, concrete parameter values, screenshots, or accessibility dumps.

## Publishing workflow

1. A contributor exports or writes a generalized skill/workflow; observed user data is removed.
2. The contributor forks `alfred-skills` and opens a pull request.
3. CI validates every file and checks that `registry/index.json` is deterministic and current.
4. A maintainer reviews behavior, privacy, parameterization, and safety.
5. Merge to protected `main` publishes the static catalog.

No client writes directly to `main`, and the Android app never carries a GitHub write token.

## Client refresh

The Skill Library shows the installed revision, last successful refresh, last error, downloaded
skills, workflows, and enable state. A user can force refresh; the app also refreshes a stale catalog when the
library opens.

The client downloads `registry/index.json`, enforces a monotonic revision, downloads every declared
artifact, verifies its digest, schema, graph, and safety policy, and commits the complete set in
one SQLite transaction. Partial or invalid downloads do not replace the previous catalog.

The app currently reads raw GitHub content. Git itself is not embedded in the APK and a complete
repository clone is unnecessary.

## Personal-to-public promotion

A personal learned pattern or graph remains local. **Prepare GitHub pull request** and **Prepare
workflow GitHub PR** copy generalized JSON and open GitHub's new-file flow. The human must inspect
the proposal, remove any private values, test it, and submit it as a pull request.

This is deliberately not automatic self-publishing. Execution history proves that a pattern worked
for one device; it does not prove universal compatibility or public safety.

## Versioning rules

- Increment a skill or workflow `version` whenever its behavior changes.
- Increment the registry `revision` for every merged catalog change.
- Never change content under an existing skill version.
- Removing an artifact from the index removes it from clients on their next successful refresh.
- A local enable/disable choice is preserved when a downloaded skill or workflow is updated.

## Future hardening

Before production: signed release manifests, multiple maintainers, mandatory reviews for sensitive
skills, publisher ownership rules, compatibility metadata, revocation advisories, staged channels,
and automated Android replay evidence attached to pull requests.
