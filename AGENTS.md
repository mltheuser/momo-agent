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
| settled | A log whose last run has its `run_finished` and whose last turn has every `tool_call_finished`. The server settles a tree before every read. |
| stop | The user's run-scoped command. Records a `stopped` outcome. |
| rewind | Cutting the stored log back to before a user message. |
| retry | Cutting a failed run back to before its failed LLM call and making that call again under the run's own settings; no trace of the failure remains. |
