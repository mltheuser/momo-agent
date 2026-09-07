# momo-agent

Kotlin library and localhost HTTP server for running file-system coding agents
through the local [ai-router](https://github.com/mltheuser/ai-router).

## What it is

| Module    | Role |
| --------- | ---- |
| `lib/`    | Embeddable library (`codes.momo.agent`): loads a harness, drives the LLM/tool loop, runs tools on the host, records an event log. |
| `server/` | Ktor HTTP + SSE server over `lib`: persisted sessions, run control, model catalog, prompt templates. |

## Quick start

Prerequisite: [ai-router](https://github.com/mltheuser/ai-router) running on `localhost:8787`. Details in [docs/building.md](docs/building.md).

```sh
./gradlew :server:run --args="--port=8420"

curl -X POST localhost:8420/v1/sessions -H 'content-type: application/json' \
  -d "{\"harnessPath\":\"$PWD/lib/examples/coder\",\"workspace\":\"/tmp/work\"}"
```

To build the project run `./gradlew build` this also runs the live test suite. It needs the running ai-router. See [docs/testing.md](docs/testing.md).

## Docs

| Doc | Read when |
| --- | --------- |
| [docs/building.md](docs/building.md) | Setting up the build: composite build, Gradle tasks, lint. |
| [docs/configuration.md](docs/configuration.md) | Configuring the server (CLI flags, env vars, defaults) or the live suite. |
| [docs/architecture.md](docs/architecture.md) | Finding your way around the code: modules, files, the event log. |
| [docs/harness.md](docs/harness.md) | Writing a harness folder: `harness.yaml` keys and loading rules. |
| [docs/sessions.md](docs/sessions.md) | Session lifecycle, status, workspace scope, on-disk layout, persisted-format contract. |
| [docs/execution-environment.md](docs/execution-environment.md) | Platforms, userland baseline, command privileges and sudoers. |
| [docs/testing.md](docs/testing.md) | Running or adding tests; what a fresh checkout needs for a green `build`. |
| [AGENTS.md](AGENTS.md) | Rules for editing the code. |
