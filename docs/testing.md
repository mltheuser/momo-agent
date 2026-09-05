# Testing

What a test is in this repo, what exists, how to run it, and what a fresh
checkout needs.

## The rule

A test drives the packaged server as an OS process over its real HTTP API,
against the real ai-router and a real cloud model. Its assertion holds for
any competent model. Nothing is mocked in code.

Two exceptions:

- `RewindPlanTest` (`server/src/test`): pure log analysis of the rewind cascade, not stageable cheaply against a model.
- `FaultyRouter` (`server/src/liveTest/FaultyRouter.kt`): a network stand-in for router failures. Scope below.

| Suite | Source set | In `build`? | Per-test timeout |
| ----- | ---------- | ----------- | ---------------- |
| Live suite | `server/src/liveTest` | yes | 15 min |
| `RewindPlanTest` | `server/src/test` | yes | 2 min |

The `lib` module has no tests. Lib-only surface (`Agent.load` called directly, `RunResult` as a return value) stays untested by decision.

## What the live suite holds itself to

- Never skips. No `assumeTrue` on model non-determinism. A flaky prompt is tightened (dictated commands, distinctive tokens), never its assertion loosened.
- Assertions key on planted tokens, filesystem side effects, event-log structure and status transitions. Never on model prose.
- Few rich scenarios along realistic user flows; every assertion message names the fact it pins.
- An unreachable or wrong-model router fails the run once per JVM, naming the URL, model, offered models and the command that starts a router (`LiveRouter.kt`).
- The whole suite runs in about 90 s of test time. A new scenario must cover a flow nothing else does.

## `FaultyRouter`

A Ktor `embeddedServer` on a loopback port that a dedicated server process is
pointed at over `--ai-router-base-url`. It serves a minimal `GET /v1/models`
and, per `POST /v1/chat/completions`, consumes the next scripted reply (an
HTTP error in ai-router's envelope, a 200 with a non-JSON body, or a 200 with
`finish_reason: error`). Once the script is spent it forwards to the live
router. It never scripts conversations. `RouterFailureLiveTest` is its only
consumer. The transient case waits out the lib's first backoff (5 s).

## Scenarios

| Class | Model? | Covers |
| ----- | ------ | ------ |
| `SessionSurfaceLiveTest` | no | Lifecycle with change-stream signals; 400s and 404s on every route; foreign workspace; rename/select-model live and closed; privilege; corrupt state; `/v1/models`; templates |
| `ConversationLiveTest` | yes | Create, prompt, stream, complete with a planted token and truncated tool result; continuation; stream replay, fan-out, end on delete |
| `RunControlLiveTest` | yes | Mid-run 409s (prompt/rewind/retry), stop to `STOPPED` and promptable; close mid-run aborts, next prompt resumes |
| `FailedRunLiveTest` | short | Unknown model to `ERROR` with the router's 404; `/retry` cuts and resumes; a completed run is not retryable |
| `RouterFailureLiveTest` | yes | Over `FaultyRouter`: 503 retried then completed; `finish_reason: error`; malformed body |
| `RewindLiveTest` | yes | Rewind from mid-run and to the first message; promptable at once; deleted turns unknown; parked subscriber told |
| `SubagentTreeLiveTest` | yes | Child becomes a session (typed harness, parent link, rename, human prompt, deleted by rewind); stopping the parent cascades as a stop, not an abort |
| `PersistenceLiveTest` | yes | Restart keeps conversation, title and selection; crash mid-run leaves a resumable session |
| `VisionLiveTest` | yes | Prompt image link becomes a `run_started` attachment; `view_image` carries media |

The suite shares one server process (`LiveServerSupport.kt`). `PersistenceLiveTest` and `RouterFailureLiveTest` start their own.

## Writing a case

- Wait on registry state (`awaitRunEnd` in `ServerApiClient`) or a specific event (`streamEvents(until = ...)`), never on the `RunFinished` frame alone and never with a sleep. The server's claim on a run outlives the terminal frame; a command sent on that frame's heels can draw a `409`.
- Treat the change stream as a doorbell: wrap a mutation in `ChangeStream.signalled(...)` and assert the re-read state. Never count frames.
- In-flight guards are exercised inside a dictated `sleep 5` tool call, waited for via its `ToolCallStarted`.
- Shared helpers live once in `server/src/liveTest`: `ServerApiClient`, `LiveServerProcess`, `LiveServerSupport`, `LiveRouter`, `FaultyRouter`, `Harnesses`, `WordImage`. The compilation is `associateWith`-bound to `main` for `internal` access.
- Known dependency: ai-router's Anthropic provider coalesces consecutive same-role turns. A strict-alternation provider would surface in the rewind case first.

## Running

```sh
./gradlew build                    # everything; ~80-120 s
./gradlew :server:liveTest         # live suite alone
./gradlew :server:test             # RewindPlanTest alone; needs no router
./gradlew :server:liveTest --tests '*RunControlLiveTest.inFlightGuardsThenStop*' --rerun-tasks
```

Live results are never cached. Use `--rerun-tasks` for a single case, or a cached green answers instead. Run a suspect case alone, twice.

## What a fresh checkout needs

1. A current ai-router checkout at the path in `settings.gradle.kts` ([building.md](building.md), including the clone gotcha).
2. That router running: `set -a && source .env && set +a && ./bin/ai-router serve` from the checkout.
3. `AI_ROUTER_ANTHROPIC_API_KEY` in ai-router's `.env`. The default model is a cloud model; a build costs API spend. A local model can be substituted ([configuration.md](configuration.md)) at a measured reliability cost.
