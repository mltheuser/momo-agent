# momo-agent

Kotlin library and localhost HTTP server for running file-system coding agents
through the local [ai-router](https://github.com/mltheuser/ai-router).

## What it is

| Module    | Role |
| --------- | ---- |
| `lib/`    | Embeddable library (`codes.momo.agent`): loads a harness, drives the LLM/tool loop, runs tools on the host, records an event log. |
| `server/` | Ktor HTTP + SSE server over `lib`: persisted sessions, run control, model catalog, prompt templates. Its client is the `momo-vscode` extension. |

Execution is always local: agent commands run on the host, in the session's
workspace folder, as the OS account the server runs as.

## Quick start

Prerequisite: a current ai-router checkout at `~/Develop/Private/ai-router`
and a router running on `localhost:8787`. Details in [docs/building.md](docs/building.md).

```sh
./gradlew :server:run --args="--port=8420"

curl -X POST localhost:8420/v1/sessions -H 'content-type: application/json' \
  -d "{\"harnessPath\":\"$PWD/lib/examples/coder\",\"environment\":{\"type\":\"local\",\"workspace\":\"/tmp/work\"}}"
```

`./gradlew build` also runs the live test suite. It needs the running router
and costs API spend. See [docs/testing.md](docs/testing.md).

## Docs

| Doc | Read when |
| --- | --------- |
| [docs/building.md](docs/building.md) | Setting up the build: composite build, the ai-router clone gotcha, Gradle tasks, lint. |
| [docs/configuration.md](docs/configuration.md) | Configuring the server (CLI flags, env vars, defaults) or the live suite. |
| [docs/architecture.md](docs/architecture.md) | Finding your way in the code: modules, packages, key classes, event types. |
| [docs/harness.md](docs/harness.md) | Writing a harness folder: `harness.yaml` keys and loading rules. |
| [docs/http-api-sessions.md](docs/http-api-sessions.md) | Calling the session routes: paths, bodies, response fields, error codes. |
| [docs/http-api-streams.md](docs/http-api-streams.md) | Consuming the SSE streams, `/v1/models` and templates. |
| [docs/runs.md](docs/runs.md) | What a prompt, stop, retry or rewind does to a session. |
| [docs/subagents.md](docs/subagents.md) | Spawning subagents and how child sessions behave. |
| [docs/sessions-and-storage.md](docs/sessions-and-storage.md) | Session lifecycle, status, workspace scope, on-disk layout, persisted-format contract. |
| [docs/execution-environment.md](docs/execution-environment.md) | Platforms, userland baseline, command privileges and sudoers. |
| [docs/testing.md](docs/testing.md) | Running or adding tests; what a fresh checkout needs for a green `build`. |
| [AGENTS.md](AGENTS.md) | Rules for editing the code. |

Reference harnesses: [lib/examples/coder/](lib/examples/coder/), [lib/examples/teacher/](lib/examples/teacher/).
