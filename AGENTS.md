# momo-agent

Kotlin library for defining and running file-system-based agents.

## Build & verify

- `./gradlew build` — compile + detekt + unit tests (needs the ai-router
  checkout on disk — see README).
- `./lint.sh` / `./fmt.sh` — detekt check / auto-fix formatting.
- `./gradlew liveTest` — live tests against a running local ai-router server
  (not in `build`/`check`).

## Conventions

- Mirror the ai-router Kotlin SDK checkout's conventions — it is the style
  reference: warnings as errors, `explicitApi` Strict, detekt +
  detekt-formatting with zero tolerated findings. Versions per
  `build.gradle.kts`.
- Narrow platform support is a declared invariant, never worked around in
  code — see the README's platform section.
- Comments and KDoc are minimal and purposeful: say only what naming and
  structure cannot, state each contract fact in exactly one place, and keep
  KDoc self-contained (no planning-doc or issue references).
- Stored session event logs are a persisted format: the `@SerialName`s on
  `AgentEvent` and `PromptResult.Status` are a compatibility contract —
  never change them (details in their KDoc).
- Test source sets are `associateWith`-bound to `main` (see
  build.gradle.kts), so `internal` declarations are visible to both the
  unit and live tests.

## Docs

- [README.md](README.md) — read when setting up the ai-router checkout,
  running or configuring the live tests, or checking platform assumptions.
- [../planning/issues/agent-lib/README.md](../planning/issues/agent-lib/README.md) — read when
  picking up an issue, checking what has landed vs. what is pending, or
  looking up how a delivered feature was designed (each done issue's
  Outcome): issue index + binding design decisions (lives in the
  enclosing momo-codes workspace, outside this repo).
- [examples/coder/](examples/coder/) — the reference harness folder; read
  when authoring a harness or working on harness loading.
