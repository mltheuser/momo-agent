# momo-agent

Kotlin library for defining and running file-system-based agents, plus the
agent server hosting sessions over HTTP. Two Gradle modules: `lib/` (the
embeddable library) and `server/` (depends on `lib`; never the reverse).
Shared build conventions live in the root build script.

## Build & verify

- `./gradlew build` — compile, detekt and the tests: one live suite driving
  the packaged server against a real router and model, plus one unit test.
  The live suite runs inside `check`, so a `build` needs a running ai-router
  and costs API spend.
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
  `Agent.retry`). `SubagentTreeLiveTest`'s stop cascade is where the
  stop/abort difference is pinned: both members end `STOPPED` and stay.
- Some catch arms deliberately stay narrower than `Throwable` — so
  widening one in a tidy-up silently converts a failed JVM into a run that
  merely errored. Contract in `Agent.send`'s KDoc.
- Stored session state is a persisted format, read strictly: both
  `events.jsonl` and `session.json` fail loudly on a key their schema does
  not know, surfacing the session as corrupt rather than guessing. So any
  change to `AgentEvent`, `RunResult.Status` or `SessionMetadata` — a
  `@SerialName`, a variant name, a field added or dropped — is a deliberate
  break with existing stored sessions, to be made knowingly (with a wipe or
  a migration), never by accident. `Privilege` is *not* stored: response
  contract only. Details in the event KDoc; storage layout in README,
  Sessions.
- Shared test helpers live once, in `server/src/liveTest` — never a
  per-class copy. What is there and how it is bound for `internal` access:
  [TESTING.md](TESTING.md), Source sets and helpers.
- Control characters in source files are written as visible escapes
  (`\u0007`), never raw bytes — editors strip raw bytes silently and
  diffs don't show it.
- Work lands from an issue-named branch as **one squashed commit** with a
  single-line message — `feat:`/`refactor:`-style prefix, then an em-dash
  summary of design substance, no trailers — fast-forward-merged into
  `main`.

## Docs

- [TESTING.md](TESTING.md) — read when adding, moving or deleting a test,
  running one case alone, or getting a fresh checkout's `build` green: the
  one tier and the rule deciding what a test is, the one permitted network
  stand-in and its scope, the scenario list, the wait a case commanding a
  running session has to use, and where a shared helper lives.
- [README.md](README.md) — read when setting up the build, pointing the live
  suite at another router or model, running or configuring the agent server,
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
