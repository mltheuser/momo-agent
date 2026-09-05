# Configuration

Settings of the server process and the live test suite, with their sources and defaults.

## Server

Resolution order: CLI argument, then environment variable, then default.
CLI arguments take the form `--option=value`. Source: `server/.../ServerConfig.kt`.

| Setting            | CLI argument           | Environment variable  | Default                 |
| ------------------ | ---------------------- | --------------------- | ----------------------- |
| Port               | `--port`               | `MOMO_AGENT_PORT`     | `8420`                  |
| Data directory     | `--data-dir`           | `MOMO_AGENT_DATA_DIR` | `~/.momo-agent`         |
| ai-router base URL | `--ai-router-base-url` | `AI_ROUTER_BASE_URL`  | `http://localhost:8787` |

Rules:

- The server binds `127.0.0.1` only. There is no authentication.
- An unknown option, a malformed argument or a port outside `1..65535` fails startup with a message naming the known options.
- A blank environment variable counts as unset.
- Logging: slf4j-simple at `warn` and above to stderr. Level in `server/src/main/resources/simplelogger.properties`.

Run:

```sh
./gradlew :server:run --args="--port=8420 --data-dir=/tmp/momo"
```

The data directory layout is in [sessions-and-storage.md](sessions-and-storage.md).

## Live test suite

Resolution order: Gradle property, then environment variable, then default.
Blank values count as unset. Source: root `build.gradle.kts`.

| Setting  | Gradle property     | Environment variable   | Default                           |
| -------- | ------------------- | ---------------------- | --------------------------------- |
| Base URL | `aiRouterBaseUrl`   | `AI_ROUTER_BASE_URL`   | `http://localhost:8787`           |
| Model    | `aiRouterChatModel` | `AI_ROUTER_CHAT_MODEL` | `claude-sonnet-5:cloud@anthropic` |

```sh
./gradlew :server:liveTest -PaiRouterBaseUrl=http://localhost:9999 -PaiRouterChatModel=some-model@provider
```

The default model is a cloud model reached through the local router, so the
router needs the provider key and a build costs API spend. See
[testing.md](testing.md) for what a fresh checkout needs.

## Library budgets

Fixed in `lib/.../Budgets.kt`; not configurable.

| Budget | Value |
| ------ | ----- |
| Turns per run (one turn = one LLM call) | 256 |
| Wall clock per run | 3 days |
| Single tool execution | 24 hours |
| Subagent nesting depth | 5 |
| LLM call retry backoffs | 5 s, 1 min, 5 min (`Retry.kt`) |
