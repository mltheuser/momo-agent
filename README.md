# momo-agent

A Kotlin library for defining and running file-system-based agents, plus an
HTTP server hosting agent sessions for interactive clients. The only LLM
backend is the local [ai-router](https://github.com/mltheuser/ai-router)
project, consumed through its Kotlin SDK.

Two Gradle modules:

- `lib/` — the embeddable library.
- `server/` — the agent server, a runnable binary wrapping `lib`.

## Prerequisites: local ai-router checkout

The build includes the ai-router Kotlin SDK as a Gradle composite build, so a
local checkout of the ai-router repository is **required** — even for a plain
`./gradlew build`. The SDK path is hardcoded in `settings.gradle.kts` to
`~/Development/open source/ai-router/SDKs/kotlin`; if your checkout lives
elsewhere, edit the path there.

The checkout must be current: momo-agent relies on SDK changes made
alongside it (kotlinx-serialization-json exported at `api` scope; a public
`SchemaGenerator.generate(descriptor)` overload). A checkout without them
fails compilation.

If the directory does not exist (or contains no build file), the build fails
immediately during settings evaluation with:

```
ai-router SDK checkout not found at: <path> — clone https://github.com/mltheuser/ai-router there or edit the path in settings.gradle.kts.
```

## Building

```sh
./gradlew build
```

Runs compilation, detekt (with detekt-formatting) and the unit test suites
of both modules. It does **not** run the live or container integration
tests and does not require a running ai-router server — only the checkout
on disk.

## Linting & formatting

Both scripts wrap detekt (with detekt-formatting) and cover all source sets:

```sh
./lint.sh   # check for findings (same as ./gradlew detekt)
./fmt.sh    # auto-fix formatting findings; remaining findings need manual fixes
```

## Live integration tests

The `liveTest` suite (`lib/src/liveTest/kotlin`) exercises the real wiring
against a **running** local ai-router server. It is not part of `build` or
`check`; run it explicitly:

```sh
./gradlew liveTest
```

Start a server from the ai-router checkout (see its README; currently
`./bin/ai-router serve`); the configured model must be available locally
(e.g. pulled in Ollama).

The suite includes the end-to-end acceptance tests, which run the
`lib/examples/coder` harness through a full toy task; the container variant
additionally needs Docker (see the platform section below).

Configuration (Gradle property takes precedence over the environment
variable, which takes precedence over the default):

| Setting  | Gradle property     | Environment variable   | Default                     |
| -------- | ------------------- | ---------------------- | --------------------------- |
| Base URL | `aiRouterBaseUrl`   | `AI_ROUTER_BASE_URL`   | `http://localhost:8787`     |
| Model    | `aiRouterChatModel` | `AI_ROUTER_CHAT_MODEL` | `qwen3.5:9b:local@ollama`   |

Example with overrides:

```sh
./gradlew liveTest -PaiRouterBaseUrl=http://localhost:9999 -PaiRouterChatModel=some-model@provider
```

Live-test results are never cached; each invocation hits the server again.

## Container-backed execution

`ContainerExecutionEnvironment` runs agent tools inside a Docker container
whose filesystem contains only the workspace: the host workspace is copied
into the container's `/workspace` at construction and copied back out on
`close()` — nothing from the host is mounted. Images are always resolved
as `linux/amd64` — both when pulling and running — and are only fetched
when not already present locally. Commands run as root inside the
container, and the image chosen decides the runtime era (e.g. `node:12`).

Images must provide the POSIX userland the project assumes (see the
platform section below); any debian-based image qualifies. Startup fails
with a clear error otherwise.

`close()` removes the container; a JVM shutdown hook removes it on abnormal
JVM termination as a best effort, so a hard kill can still leak one. Every
container carries the `codes.momo.agent` label, making manual cleanup one
command:

```sh
docker rm -f $(docker ps -aq --filter label=codes.momo.agent)
```

## Container integration tests

The `containerTest` suites — `lib/src/containerTest/kotlin` exercising
`ContainerExecutionEnvironment` and `server/src/containerTest/kotlin`
exercising container-backed sessions — run against a local Docker daemon.
They are not part of `build` or `check`; run them explicitly:

```sh
./gradlew containerTest
```

The first run pulls the pinned test images (`debian:12-slim`, `node:12`,
`alpine:3.20`) and is slow; results are never cached. Docker requirements
are in the platform section below.

## Agent server

The `server` module hosts multiple concurrent agent sessions behind a
localhost-only HTTP API — the process boundary interactive clients talk
to. Run it with:

```sh
./gradlew :server:run --args="--port=8420"
```

Each setting resolves from its CLI argument, then its environment
variable, then the default:

| Setting            | CLI argument           | Environment variable | Default                 |
| ------------------ | ---------------------- | -------------------- | ----------------------- |
| Port               | `--port`               | `MOMO_AGENT_PORT`    | `8420`                  |
| Data directory     | `--data-dir`           | `MOMO_AGENT_DATA_DIR`| `~/.momo-agent`         |
| ai-router base URL | `--ai-router-base-url` | `AI_ROUTER_BASE_URL` | `http://localhost:8787` |

The server always binds `127.0.0.1`. There is no auth: remote access is
out of scope for v1.

### Sessions

A session is one agent conversation over a harness and an execution
environment. Its source of truth is on disk under the data directory —
`sessions/<session-id>/session.json` (for a root: harness path, model,
environment spec; for a subagent: its parent's session ID) plus
`sessions/<session-id>/events.jsonl` (the event log, one
serialized `AgentEvent` per line, appended live) — so every session
survives a server restart: on startup the data directory is indexed and
prior sessions appear as `closed`. A running agent and
its environment are an ephemeral runtime attachment on top; closing a
session drops the attachment (a container copies its workspace back to
the host and is removed) and keeps the stored log.

### Endpoints (v1)

| Method & path                 | Effect |
| ----------------------------- | ------ |
| `POST /v1/sessions`           | Create a session (request body below) → `201` with the session info. |
| `GET /v1/sessions`            | List the root sessions (ID, parent, title, model, harness path, environment, status, created-at, last run's budget consumption). Subagent sessions are omitted — fetch them by ID. |
| `GET /v1/sessions/{id}`       | One session's info. |
| `POST /v1/sessions/{id}/prompt` | Send the next user message; the run starts in the background → `202` with a snapshot of the session info. |
| `GET /v1/sessions/{id}/events`| The session's event log as an SSE stream: stored history, then live events. |
| `POST /v1/sessions/{id}/close`| Close the session's whole subagent tree; aborts in-flight work, tears the environment down, keeps the stored logs. Idempotent. |
| `DELETE /v1/sessions/{id}`    | Close the tree if needed, then remove the session and its descendants with their stored artifacts → `204`. |

The create body names a server-local harness folder, an environment, and
an optional title:

```json
{"harnessPath": "<dir>", "environment": {"type": "local", "workspace": "<dir>"}, "title": "..."}
{"harnessPath": "<dir>", "environment": {"type": "container", "image": "<img>", "workspace": "<dir>"}}
```

Session `status` is derived, never stored: `running` (a run is in
flight), `idle` (live, nothing running), `closed` (no runtime attached;
resumable).

Errors are structured JSON — `{"code": "...", "message": "..."}` — with
`400` for invalid harness/environment/request, `404` for an unknown
session, `409` for operations conflicting with an active run, and `500`
otherwise.

### Prompting

```json
{"prompt": "..."}
```

The body carries the next user message. Prompting a `closed` session
rebuilds its tree's runtime first — that is the resume path (see Subagent
sessions). No endpoint returns the run's outcome: its `run_finished`
event (status, final message verbatim, usage, turns used, elapsed) is the
record, observed via the event stream. The one run without that record is
one aborted by closing the session — the log ends mid-run and the session's
`status` is the indicator. A blank prompt is a `400 invalid_request`, a
prompt while a run is active a `409 conflict`, and a session whose event
log can no longer persist refuses new runs with a `500 event_log_failed`.

### The event stream

`GET /v1/sessions/{id}/events` serves the stored event log verbatim as
server-sent events: each frame's `id:` is the event's `sequenceId`
(gapless, 0-based) and its `data:` is the event JSON exactly as stored —
one serialized `AgentEvent` (its serialization is the wire contract —
see `AgentEvent`'s KDoc). No `event:` name is used. The
stream replays history — all of it, or strictly after the `Last-Event-ID`
request header on reconnect — then stays open and follows live events.
Any number of subscribers can follow one session, each at its own pace;
subscribing to a `closed` session serves its history without resuming it.
Deleting the session ends its streams.

### Subagent sessions

Agents can spawn subagents (the library's `spawn_subagent` /
`prompt_subagent` tools); each child is a full session under the same data
directory, created the moment its spawn is announced. Clients discover
children through the parent's `subagent_spawned` event, which carries the
child's session ID: `GET /{id}`, the event stream, and `POST /{id}/prompt`
all work on children, while the listing stays roots-only. A child's info
carries `parent` (`null` on a root); its `model`, `harnessPath`, and
`environment` are resolved through its root — children execute in the
root's environment and own none themselves.

The runtime attachment belongs to the whole tree and is dropped only by an
explicit close or server shutdown — close is how a tree's memory is
reclaimed. Closing or deleting *any* member affects the whole tree:
in-flight runs anywhere in it are aborted, the one environment is torn
down, and every member becomes `closed` with its log intact. Delete
additionally removes the target session and all its descendants; a deleted
child later prompted by its parent draws the unknown-subagent error
result, and the name is free to spawn anew. Prompting any dormant member —
root or child — rebuilds the tree's runtime and revives just the chain
from the root to the prompted session from the stored logs, so a
re-prompted parent can still converse with children spawned before a
restart. A human may prompt an idle child at any time; a running child
answers `409`, and a parent's `prompt_subagent` to a human-busy child gets
an error result in its own log.

## Supported platforms & system assumptions

- **Linux x86_64** — officially supported; the platform for eval runs.
- **macOS** (including arm64) — best-effort, for development only. Known
  divergence: BSD `sed` appends a newline when printing a final line that
  lacks one, so `read_file` output is byte-exact only on GNU `sed`.
- **Windows** — unsupported.

The project assumes a POSIX userland (`bash`, coreutils, `grep`, `find`,
`sed`), UTF-8 everywhere, LF line endings, and a `docker` CLI usable
without `sudo` (needed for container-backed execution and the
`containerTest` suite).
