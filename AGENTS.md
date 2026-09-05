# momo-agent

Rules for editing this repo's code. Orientation and reference are in
[README.md](README.md) and `docs/`.

## Layout

Two Gradle modules: `lib/` (library, `codes.momo.agent`) and `server/`
(HTTP server over `lib`). `server` depends on `lib`, never the reverse.
Shared build conventions live in the root `build.gradle.kts`.
Map of the code: [docs/architecture.md](docs/architecture.md).

## Verify

- `./gradlew build`: compile, detekt, `RewindPlanTest`, the live suite. Needs a running ai-router and costs API spend ([docs/testing.md](docs/testing.md)).
- `./lint.sh` / `./fmt.sh`: detekt check / auto-fix.

## Code conventions

- Style reference is the ai-router Kotlin SDK checkout: warnings as errors, `explicitApi` Strict, detekt with zero findings. Dependency versions live once, in `gradle/libs.versions.toml`.
- Narrow platform support is a declared invariant; never work around it in code ([docs/execution-environment.md](docs/execution-environment.md)).
- The code carries almost no comments. Contracts and rationale live in `docs/`; a comment is allowed only for a non-obvious local hazard that a reader of the line would otherwise undo. No KDoc, no planning-doc or issue references.
- Control characters in source are written as visible escapes (`\u0007`), never raw bytes.
- In `Agent.executeRun` the `Exception` and `Throwable` catch arms stay separate. Merging them converts a failed JVM into a run that merely errored: a non-`Exception` throwable is rethrown after the `run_finished` event is emitted.

## Vocabulary

These four name different things. Never swap them.

| Word | Meaning |
| ---- | ------- |
| abort | Close, delete or shutdown cancelling a tree's runs. No recorded outcome. |
| stop | The user's run-scoped command. Records a `stopped` outcome. |
| rewind | Cutting the stored log back to an earlier event. Never `revert` or `reverse`. |
| retry | Cutting a failed run's failure tail and resuming it in place (a rewind plus `Agent.retry`). |

`SubagentTreeLiveTest`'s stop cascade pins stop vs. abort: both members end `STOPPED` and stay.

## Persisted format

`events.jsonl` and `session.json` are read strictly; an unknown key surfaces
the session as corrupt. Any change to `AgentEvent`, `RunResult.Status` or
`SessionMetadata` (a `@SerialName`, a variant, a field) breaks existing stored
sessions. Make it knowingly, with a wipe or a migration. `Privilege` is not
stored. Details: [docs/sessions-and-storage.md](docs/sessions-and-storage.md).

## Tests

- One tier: live tests over the real server process, router and model. `RewindPlanTest` and `FaultyRouter` are the only exceptions. Rules and helpers: [docs/testing.md](docs/testing.md).
- Shared test helpers live once, in `server/src/liveTest`. Never a per-class copy.

## Commits

Work lands from an issue-named branch as one squashed commit with a
single-line message: `feat:`/`refactor:`-style prefix, then a summary of the
design substance. No trailers. Fast-forward merged into `main`.

## Outside this repo

Issue index, design decisions and per-issue outcomes live in the enclosing
momo-codes workspace: `../planning/issues/agent-lib/README.md`, and
`../planning/issues/vscode-client/README.md` for server gaps delivered
through the client's issues. Not present in a standalone clone.
