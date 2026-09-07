# momo-agent

Rules for editing this repo's code. Orientation and reference are in
[README.md](README.md) and `docs/`.

## Layout

Two Gradle modules: `lib/` (library, `codes.momo.agent`) and `server/`
(HTTP server over `lib`). `server` depends on `lib`, never the reverse.
Shared build conventions live in the root `build.gradle.kts`.

Within each module, packages are layered: a package depends only on the
packages listed after it. In `lib` the sub-packages also use the root's types
(`AgentEvent`, `RunResult`), and `subagent` calls back into `Agent` to spawn
children. Keep `explicitApi` honest: a declaration is `public` only when
`server` or an embedder needs it.

`lib` (`codes.momo.agent`):

| Package | Holds |
| ------- | ----- |
| root | The embedder's API: `Agent`, its events and listener, run results and settings. |
| `internal` | The agent loop's private parts: session restore, retry, budgets, message building. |
| `subagent` | Child agents: the registry a parent keeps and the two tools that drive it. |
| `tool` | The tool contract, the registry that runs tools, and the host tools. |
| `harness` | Loading and validating a harness folder. |
| `environment` | Running commands on the host and probing its privileges. |

`server` (`codes.momo.agent.server`):

| Package | Holds |
| ------- | ----- |
| root | The HTTP surface: routes, request and response types, workspace scoping, config, `main`. |
| `session` | Operations on sessions and their live agent trees; every route calls into here. |
| `cut` | Pure decisions over an event log: what a rewind or retry cuts, and what survives. |
| `storage` | The files under the data directory: event logs and their tailing, templates, exceptions the files raise. |

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
