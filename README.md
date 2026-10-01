# momo-agent

Kotlin library and localhost HTTP server for running file-system coding agents
through the local [ai-router](https://github.com/mltheuser/ai-router).

## What it is

| Module    | Role |
| --------- | ---- |
| `lib/`    | Embeddable library (`codes.momo.agent`): loads a harness, drives the LLM/tool loop, runs its tools, records an event log. |
| `server/` | Ktor HTTP server over `lib`: persisted sessions, run control, a change stream, model catalog, prompt templates. |

## Quick start

Prerequisite: [ai-router](https://github.com/mltheuser/ai-router) running on `localhost:8787`. Details in [docs/building.md](docs/building.md).

```sh
./gradlew :server:run --args="--port=8420"

curl -X POST localhost:8420/v1/sessions -H 'content-type: application/json' \
  -d "{\"harnessPath\":\"$PWD/lib/examples/coder\",\"workspace\":\"/tmp/work\"}"
```

To build the project run `./gradlew build` this also runs the live test suite. It needs the running ai-router. See [docs/testing.md](docs/testing.md).
