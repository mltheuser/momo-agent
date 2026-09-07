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

These four name different things. Never swap them.

| Word | Meaning |
| ---- | ------- |
| abort | Close, delete or shutdown cancelling a tree's runs. No recorded outcome. |
| stop | The user's run-scoped command. Records a `stopped` outcome. |
| rewind | Cutting the stored log back to an earlier event. |
| retry | Cutting a failed run's failure tail and resuming it in place (a rewind plus `Agent.retry`). |
