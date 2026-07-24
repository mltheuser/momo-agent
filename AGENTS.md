# momo-agent

Kotlin library for defining and running file-system-based agents, plus the
agent server hosting sessions over HTTP. Two Gradle modules: `lib/` (the
embeddable library) and `server/` (depends on `lib`; never the reverse).
Shared build conventions live in the root build script.

## Build & verify

- `./gradlew build` — compile + detekt + unit tests of both modules (needs
  the ai-router checkout on disk — see README). The server's SSE tests
  flake under load, a different one each run: re-run before suspecting a
  change, and never infer one from *which* test failed (evidence in
  README, Building).
- `./lint.sh` / `./fmt.sh` — detekt check / auto-fix formatting.
- `./gradlew liveTest` — live tests against a running local ai-router
  server; the e2e container variant also needs Docker (not in
  `build`/`check`).
- `./gradlew containerTest` — container integration tests of both modules
  against local Docker (not in `build`/`check`).

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
- `abort` and `stop` name different things and must never be swapped:
  aborting is close/delete/shutdown cancelling a tree's runs, plus the
  transcript repair for tool calls a run ended before answering; stopping
  is the user's run-scoped command that records a `stopped` outcome.
- Stored session state is a persisted format: the `@SerialName`s on
  `AgentEvent` and `RunResult.Status` and the `SessionMetadata` variant
  names in `session.json` are a compatibility contract — never change
  them (details in the event KDoc; storage layout in README, Sessions).
- Test compilations are `associateWith`-bound for `internal` access (see
  the module build scripts): the lib's suites and `testFixtures` to its
  main, the server's `containerTest` to its main and test.
- Shared test helpers live once, in the lib's `testFixtures` source set
  (`lib/src/testFixtures/kotlin`), consumed by every lib suite and by the
  server's tests — no per-suite fixture copies.
- Control characters in source files are written as visible escapes
  (`\u0007`), never raw bytes — editors strip raw bytes silently and
  diffs don't show it.
- Work lands from an issue-named branch as **one squashed commit** with a
  single-line message — `feat:`/`refactor:`-style prefix, then an em-dash
  summary of design substance, no trailers — fast-forward-merged into
  `main`.

## Docs

- [README.md](README.md) — read when setting up the build, running the
  live/container test suites, running or configuring the agent server,
  working on or against its HTTP API (endpoints and wire format),
  working with container-backed execution, or checking platform
  assumptions.
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
