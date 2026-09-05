# Testing

One tier, and one rule deciding what a test is:

> A test drives the **packaged server as an OS process** over its real HTTP
> API, backed by the **real ai-router** and a **real cloud model**, and its
> assertion holds for any competent model. Nothing is mocked in code. The
> one exception is a *network* stand-in for router failures, scoped below.
> The one unit test left is `RewindPlanTest` — pure log analysis of the
> rewind cascade, whose cases cannot be staged cheaply against a model.
> Nothing else is a test we keep.

| Suite                    | Source set            | In `build`? | Hang ceiling |
| ------------------------ | --------------------- | ----------- | ------------ |
| Live (the suite)         | `server/src/liveTest` | yes         | 15 min       |
| `RewindPlanTest` (unit)  | `server/src/test`     | yes         | 2 min        |

The ceiling is a preemptive JUnit timeout on every test, applied by the root
build script to every `Test` task, needing
`thread.mode.default = SEPARATE_THREAD` because Jupiter otherwise reports an
overrun only once the test returns — which a hang never does. It is a
backstop, never a budget: a case that wants more than its ceiling is a case
to reconsider.

The `lib` module has no test suite of its own. It is tested only through the
server: lib-only surface — the embedding API, `RunResult` as a return value,
`Agent.load` called directly — stays untested by decision.

## Why one tier

The system is the server process, and only a test crossing its real surface
can say it works, or notice ai-router changing underneath it. Mocked and
unit tests encoded our belief about the router's contract and kept passing
after that belief went stale; they also coupled the suite to internals
(`RunBudgets`, `CommandRunner`, the SDK's `HttpClient` seam) that the
refactor since has shown to be the parts that move. The trade is
deliberate: a slower, spend-costing suite that cannot lie about the
contract, kept fast enough to run on every change by design.

What the suite holds itself to:

- **It never skips.** No `assumeTrue` turning model non-determinism into a
  silent pass. A case that cannot assert reliably against a real model does
  not belong here — it belongs nowhere. A flaky prompt is tightened —
  dictated commands, distinctive tokens — never its assertion loosened.
- **Assertions key on planted tokens** — a needle in a workspace file, a
  pass phrase that exists only in a child harness's instructions, a word
  rendered into a PNG — plus filesystem side effects, event-log structure
  and status transitions. Never on model prose.
- **Few rich scenarios along realistic user flows**, each still diagnosable:
  every assertion message names the fact it pins.
- **An unreachable or wrong-model router fails the run**, once per JVM, with
  a message naming the URL, the model, the models the router does offer, and
  the command that starts one (`LiveRouter.kt`).
- **Time is the constraint.** The whole live suite runs in about a minute
  and a half of test time. A new scenario earns its place by covering a flow
  nothing else does; a single-purpose case is folded into the flow it
  belongs to.

## The one permitted fake: `FaultyRouter`

`FaultyRouter.kt` is a tiny Ktor `embeddedServer` on a loopback port that a
dedicated server process is pointed at over `--ai-router-base-url`. It
serves a minimal usable `GET /v1/models` catalog and, for each
`POST /v1/chat/completions`, consumes the next scripted reply — an HTTP
error with ai-router's envelope, a 200 with a non-JSON body, or a 200 whose
completion reports `finish_reason: error` — and once the script is spent
forwards every request verbatim to the live router. It is a **network
stand-in for router failures, never for model behaviour**: it scripts no
conversations, and what the model says is never its business. It exists
because a healthy router cannot be asked to fail. `RouterFailureLiveTest` is
its only consumer; a case that wants to script what the model says does not
qualify.

The transient case waits out the lib's production first backoff (5 s)
before the retry, because `RunBudgets` is internal to the lib and the server
takes no test-only knob for it — accepted, at ~5 s.

## Scenarios

| Class                     | Model? | Covers                                                                                                                                                                                                                                             |
| ------------------------- | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `SessionSurfaceLiveTest`  | no     | Lifecycle with the change stream signalling each mutation once; 400s on every surface; 404s on every route and for a foreign workspace; rename/select-model on live and closed sessions; privilege and corrupt stored state; `/v1/models`; templates |
| `ConversationLiveTest`    | yes    | Create → prompt → stream → complete with a planted token and a truncated tool result, continuation across runs; the event stream's replay, fan-out and end on delete                                                                                |
| `RunControlLiveTest`      | yes    | Mid-run 409s (prompt/rewind/retry) then a stop → `STOPPED`, idle, promptable; a close mid-run aborts and the next prompt resumes                                                                                                                    |
| `FailedRunLiveTest`       | short  | Unknown model → `ERROR` with the router's 404, zero retries; `/retry` cuts and resumes in place; a completed run is not retryable                                                                                                                   |
| `RouterFailureLiveTest`   | yes    | Over `FaultyRouter`: one 503 retried then completed; `finish_reason: error` → `ERROR`, promptable; malformed body → `ERROR`, nothing dangling                                                                                                        |
| `RewindLiveTest`          | yes    | A rewind from an assistant message mid-run and one to the log's first message: promptable at once, the deleted turns unknown, the parked subscriber told                                                                                            |
| `SubagentTreeLiveTest`    | yes    | Delegation makes the child a session (typed harness, parent link, rename, human prompt, deleted by the rewind); stopping the parent cascades as a stop, not an abort                                                                                |
| `PersistenceLiveTest`     | yes    | Restart keeps conversation, title and selection; a crash mid-run leaves a resumable session                                                                                                                                                       |
| `VisionLiveTest`          | yes    | A prompt image link becomes a `run_started` attachment, `view_image` carries media, both planted words come back                                                                                                                                    |

The suite shares one server process (`LiveServerSupport.kt`); each case owns
only the sessions it creates. `PersistenceLiveTest` and
`RouterFailureLiveTest` start processes of their own, because a stored
session is indexed at startup and a stand-in needs a process pointed at it.

## Wait for the run, not for its last frame

A case that prompts and then issues a command — another prompt, a stop, a
rewind, a retry — waits on **registry state**, via `awaitRunEnd` in
`ServerApiClient`, or on a specific event, via `streamEvents(until = …)`.
Waiting on the `RunFinished` frame alone returns sooner: the server's claim
on the run outlives the terminal frame, so a command sent on that frame's
heels can still draw a `409`. Never a sleep where such a wait exists.

The in-flight guards are exercised inside a dictated `sleep 5` tool call,
waited for through its `ToolCallStarted` — so the 409s land mid-execution
wherever the model's latency happens to put the first LLM call, and the
suite carries no dependency on backend latency.

What the suite depends on and does not enforce: **the provider coalescing
consecutive same-role turns.** One rewind case leaves the conversation
ending in a user message, so the next prompt sends a second user turn behind
it. The library does not merge them; ai-router's Anthropic provider does. A
provider enforcing strict role alternation would surface here first.

## Source sets and helpers

`server/src/liveTest` holds the suite and everything it shares, once, never
as per-class copies: `ServerApiClient` (the API as the tests speak it —
typed calls, raw-body variants for what the request types cannot express,
the waits), `LiveServerProcess` and `LiveServerSupport` (the process and
the shared one), `LiveRouter` (coordinates and the reachability probe),
`FaultyRouter`, `Harnesses` (`writeHarness`), `WordImage`. The compilation
is `associateWith`-bound to `main` for `internal` access, because the API's
request and response types are `internal`.

## Running them

```sh
./gradlew build                    # compile + detekt + RewindPlanTest + the live suite
./gradlew :server:liveTest         # the live suite alone
./gradlew :server:test             # RewindPlanTest alone — the one thing needing no router

# One case, alone and uncached — the only way to catch an order-dependent pass
./gradlew :server:liveTest --tests '*RunControlLiveTest.inFlightGuardsThenStop*' --rerun-tasks
```

`build` takes ~80–120 s end to end. Live results are never cached and never
`UP-TO-DATE`: every invocation hits the backend again. `--rerun-tasks`
matters for the last form: without it a green cached result answers instead
of the run you asked for. A suspect case is worth running alone, twice: a
window small enough to pass inside its class can fail once nothing ahead of
it has warmed the JVM.

## What a fresh checkout needs

`./gradlew build` runs the live suite, so it needs a **running ai-router** —
and it will never silently skip past a missing one.

1. **An ai-router checkout on disk**, current — a stale one fails
   compilation. Its path, and why one checkout serves both roles:
   [README.md](README.md), Prerequisites.
2. **That router running**, started from the checkout with:

   ```sh
   set -a && source .env && set +a && ./bin/ai-router serve
   ```

3. **An `AI_ROUTER_ANTHROPIC_API_KEY` in ai-router's `.env`**, because the
   suite's default model is a cloud model reached through the local router.
   A build therefore costs API spend — accepted deliberately: the local 12b
   model this replaced was measured failing builds outright, answering "the
   file does not exist" on a planted-token read and wedging single
   generations past the call ceiling. The cloud model is also the
   faster of the two. Substituting a local model is one property away (see
   the README's live-test configuration table) at that measured reliability
   cost.

A freshly cloned ai-router does not build, for a reason worth knowing before
losing an hour to it — see [README.md](README.md), Prerequisites.
