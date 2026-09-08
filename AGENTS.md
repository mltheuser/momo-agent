# momo-agent

Rules for editing this repo's code. Orientation and reference are in
[README.md](README.md) and `docs/`.

## Layout

Two Gradle modules: `lib/` (library, `codes.momo.agent`) and `server/`
(HTTP server over `lib`). `server` depends on `lib`, never the reverse.
Shared build conventions live in the root `build.gradle.kts`.
Each module's root package is its outward face (`lib`: the embedder's API;
`server`: the HTTP surface, on which nothing depends back); sub-packages
hold the internals.

## Verify

- `./gradlew build`: compile, detekt, the unit tests (`:server:test`), the live suite (`:server:liveTest`). Needs a running ai-router and costs API spend ([docs/testing.md](docs/testing.md)).
- `./lint.sh` / `./fmt.sh`: detekt check / auto-fix.

## Vocabulary

| Word | Meaning |
| ---- | ------- |
| abort | Delete, shutdown or a kill cancelling a run. Records no outcome itself. |
| interrupted | The outcome recorded for an aborted run when its log is next read. |
| settled | A log that ends with `run_finished` and whose every tool call has a result. The only state the agent will load. |
| stop | The user's run-scoped command. Records a `stopped` outcome. |
| rewind | Cutting the stored log back to an earlier event. |
| retry | Cutting a failed run's failure tail and resuming it in place (a rewind plus `Agent.retry`). |
