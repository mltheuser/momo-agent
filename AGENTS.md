# momo-agent

Kotlin library for defining and running file-system-based agents, plus the
agent server hosting sessions over HTTP. Two Gradle modules: `lib/` (the
embeddable library) and `server/` (depends on `lib`; never the reverse).
Shared build conventions live in the root build script.

## Build & verify

- `./gradlew build` — compile, detekt and every test tier. The live tier
  runs inside `check`, so a `build` needs a running ai-router and costs API
  spend.
- `./lint.sh` / `./fmt.sh` — detekt check / auto-fix formatting.

## Conventions

- Mirror the ai-router Kotlin SDK checkout's conventions — it is the style
  reference: warnings as errors, `explicitApi` Strict, detekt +
  detekt-formatting with zero tolerated findings. Every dependency version
  lives once, in `gradle/libs.versions.toml`.
- Narrow platform support is a declared invariant, never worked around in
  code — see the README's platform section.
- Comments and KDoc are minimal and purposeful: say only what naming and
  structure cannot, state each contract fact in exactly one place, and keep
  KDoc self-contained (no planning-doc or issue references).
- `abort`, `stop`, `rewind` and `retry` name different things and must
  never be swapped: aborting is close/delete/shutdown cancelling a tree's
  runs — in the transcript repair for unanswered tool calls it is the case
  with no recorded run outcome; stopping is the user's run-scoped command
  that records a `stopped` outcome; rewinding is the conversation moved
  back to an earlier point by cutting the stored log — never `revert` or
  `reverse`; retrying is the user's command that cuts a failed run's
  failure tail and resumes the run in place (a rewind plus an
  `Agent.retry`). The server's mocked cascade cases
  (`SubagentStopCascadeTest`) are where the stop/abort difference is
  pinned.
- Some catch arms deliberately stay narrower than `Throwable` — so
  widening one in a tidy-up silently converts a failed JVM into a run that
  merely errored. Contract in `Agent.send`'s KDoc.
- Stored session state is a persisted format: the `@SerialName`s on
  `AgentEvent` and `RunResult.Status`, and the `SessionMetadata` variant
  names in `session.json`, are a compatibility contract — never change them
  (`Privilege` is *not* in this class: response contract only, never
  stored). The **variant names** are that contract and the field set is not:
  `session.json` tolerates keys it does not know, so a file can outlive its
  schema and *dropping* a stored field is safe — an old file's leftover key
  is ignored rather than fatal (`favorite` was removed this way; the
  obsolete-key case in `StoredMetadataTest` is the pin). The event log
  deliberately does not tolerate the same move: there an unknown key is a
  wire-contract break worth failing on. Details in the event KDoc; storage
  layout in README, Sessions.
- Shared test helpers live once in one shared source set — never a
  per-suite copy. Which set, and how the suites are bound for `internal`
  access: [TESTING.md](TESTING.md), Source sets and fixtures.
- Control characters in source files are written as visible escapes
  (`\u0007`), never raw bytes — editors strip raw bytes silently and
  diffs don't show it.
- Work lands from an issue-named branch as **one squashed commit** with a
  single-line message — `feat:`/`refactor:`-style prefix, then an em-dash
  summary of design substance, no trailers — fast-forward-merged into
  `main`.

## Docs

- [TESTING.md](TESTING.md) — read when adding, moving or deleting a test,
  running one tier at a time, or getting a fresh checkout's `build` green: the
  three tiers, the rule deciding which one a test belongs to, what each needs,
  the wait a case commanding a running session has to use, and where a shared
  helper lives.
- [README.md](README.md) — read when setting up the build, pointing the live
  tier at another router or model, running or configuring the agent server,
  working on or against its HTTP API (endpoints and wire format),
  granting or explaining the host's command privileges (sudoers), or
  checking platform assumptions.
- [../planning/issues/agent-lib/README.md](../planning/issues/agent-lib/README.md) — read when
  picking up an issue or looking up how a delivered feature was designed:
  issue index, binding design decisions, per-issue Outcomes (lives in the
  enclosing momo-codes workspace, outside this repo). If a server feature
  isn't in that index, look in
  [vscode-client](../planning/issues/vscode-client/README.md) — the
  momo-agent gaps the client needed were specified and delivered inside
  the client's own issues.
- [lib/examples/coder/](lib/examples/coder/) — the reference harness folder;
  read when authoring a harness or working on harness loading.
