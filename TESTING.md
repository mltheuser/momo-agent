# Testing

Three tiers, and one rule deciding which tier a test belongs to:

> A test is **live** if its assertion holds for any competent model — or if it
> reaches no model at all and needs the real server process to mean anything.
> It is **mocked** only if the behaviour it protects is user-visible *and*
> cannot be provoked against a real router. It is a **unit** test only if the
> logic under test contains no HTTP call and is complex enough to fail on its
> own. A test of a **fixture** rather than of the project is kept only where
> that fixture failing quietly would cost more than the bug it hid — which
> here means one thing: a fake router that could hang. Nothing else is a test
> we keep.

| Tier             | Source sets                               | In `build`?       |
| ---------------- | ----------------------------------------- | ----------------- |
| Live             | `lib/src/liveTest`, `server/src/liveTest` | yes               |
| Mocked and units | `lib/src/test`, `server/src/test`         | yes               |
| Container        | `*/src/containerTest`                     | no — needs Docker |

## The live tier — the primary signal

The real `Agent`, the real ai-router SDK, a real ai-router, and for the
server suite the packaged server launched as an OS process from the
`installDist` start script — so `Main.kt` is exercised rather than bypassed.
These are the only tests that can notice ai-router changing underneath us,
and they are what says the system works.

Its budget is a design constraint, not an accident: model-driven work is what
the tier costs, while the cases crossing the server's real HTTP surface
without ever reaching a chat completion are milliseconds each. A budget
forces one canonical path per surface instead of a family of variations, and
it is what keeps the tier something people actually run.

What the tier holds itself to:

- **It never skips.** No `assumeTrue` turning model non-determinism into a
  silent pass. A case that cannot assert reliably against a real model does
  not belong here — it belongs nowhere.
- **Assertions key on planted tokens** — a needle in a workspace file, a
  pass phrase that exists only in a child harness's instructions — plus
  filesystem side effects, event-log structure and status transitions. Never
  on model prose. A prompt that fights the assertion it serves is the one
  reliable way to wedge a generation.
- **An unreachable or wrong-model router fails the run**, once per JVM, with
  a message naming the URL, the model, the models the router does offer, and
  the command that starts one.
- **One case depends on backend latency**, and that is a property of the
  tier rather than something it enforces: the 409 a prompt during a run draws
  needs the second prompt to land inside the first LLM call. A cloud round
  trip leaves a comfortable window, the trade deliberately taken when the
  pausable fake was deleted — but a backend fast or cached enough to answer
  inside the prompt's flight would turn it into a flake rather than a
  failure. The stop and the close mid-run carry no such dependency: each
  waits for its run's tool call to start, so its command lands inside a
  five-second `sleep` rather than wherever latency happens to put it.

## The mocked tier — the exception, not the rule

A fake router attached at the SDK's public `HttpClient` constructor
parameter, i.e. **below the network**: no fake sockets, no dependence on
accept ordering, no coupling between a reply's index and the request it
answers. Replies are built from the SDK's own types, so an ai-router
contract change breaks compilation instead of passing quietly; hand-rolled
wire JSON is confined to the cases genuinely testing malformed input.

Every such test is a liability as well as an asset — it encodes our belief
about ai-router's contract and will keep passing after that belief goes
stale — so the tier stays small and cannot hang. A request its script cannot
answer draws a non-retryable HTTP 400 carrying what arrived and what the
rules were still willing to answer: a direct SDK call throws it, and a run
driving the call ends `ERROR` with that text as its error and a
`RunFinished` like any other outcome — so a test waiting on that run fails
in about a second instead of waiting out its stream ceiling. A run the
server started is nobody's return value, so `withFakeSessionServer`
re-attaches the diagnostic to whatever then fails. `FakeLlmTest` pins the
in-process half inside a bound only an actual wait can breach, and
`FakeLlmServerTest` the half over the registry.

What the rule admits here, and all it admits: the LLM failure taxonomy
(transient retry, terminal failure, a reported `finish_reason` of `error`,
an unparseable body), timeout and budget expiry at millisecond scale,
per-run model and reasoning-effort selection — which the live tier
structurally cannot see, because any model answers — how a child run's
outcome reaches its parent as a tool result, subagent registry integrity
and the revival fallbacks, and event-log integrity (torn tails, a failed
log refusing new runs).

Content scripting is not a subject. Positional request indices and exact
N-element event sequences are gone outright: a rule answers requests that
*look* a stated way, so no test reads a request by its place in a list. A
scripted model id or assistant text survives only as a means — the
selection cases above name models to prove a request carried one, and a few
assert a completion travelled back verbatim — never as the thing under
test. A case whose point is a particular string the model said does not
qualify.

The server's mocked cases run the real routing and serialization over a real
`embeddedServer(CIO)` on a loopback port: they need a controlled LLM *and*
the HTTP surface, and Ktor's test host is not in the tree.

## Units — complex logic, no HTTP

Harness loading, validation and composition; retry classification;
transcript repair; result rendering; process handling and privilege
detection; config resolution; the persisted wire forms. Cheap, and they age
well — most of what predates the three tiers already qualified.

The tree's only two `Assumptions.assumeFalse` guards sit here and in the
server's mocked suite, both for one reason: a process running as root ignores
POSIX permissions, so `HarnessTest`'s unreadable `instructions.md` and
`EventLogFailureTest`'s unwritable event log cannot be staged there at all.
They guard a platform capability, not a model's mood.

## The container tier

`lib/src/containerTest` exercises `ContainerExecutionEnvironment` and
`server/src/containerTest` container-backed sessions, both against a local
Docker daemon. Deliberately outside `build`: the build must not acquire a
dependency on a Docker daemon.

One case needs more than that daemon: `EndToEndAcceptanceContainerTest`,
which runs the live tier's toy task inside a container and therefore needs a
**running ai-router as well** — which is why `lib`'s `containerTest` shares
the `liveTest` suite's configuration (router coordinates and
`momo.examplesDir`) in the module build script.

## Running them

```sh
./gradlew build                    # compile + detekt + units, mocks and the live tier
./gradlew :lib:test :server:test   # the units and mocks alone — the one combination needing no router
./gradlew liveTest                 # the live tier alone
./gradlew containerTest            # the container tier
```

`build` takes ~85–90 s end to end. Live and container results are never
cached and never `UP-TO-DATE`: every invocation hits the backend again.

## What a fresh checkout needs

`./gradlew build` runs the live tier, so it needs a **running ai-router** —
and it will never silently skip past a missing one.

1. **An ai-router checkout on disk**, current: momo-agent tracks SDK changes
   made alongside it, and a stale checkout fails compilation. One checkout
   serves both roles — the SDK the build compiles against and the router the
   live tier talks to. Path in [README.md](README.md), Prerequisites.
2. **That router running**, started from the checkout with:

   ```sh
   set -a && source .env && set +a && ./bin/ai-router serve
   ```

3. **An `AI_ROUTER_ANTHROPIC_API_KEY` in ai-router's `.env`**, because the
   live tier's default model is a cloud model reached through the local
   router. A build therefore costs API spend — accepted deliberately: the
   local 12b model this replaced was measured failing builds outright,
   answering "the file does not exist" on a planted-token read and wedging
   single generations past the tier's call ceiling. The cloud model is also
   the faster of the two. Substituting a local model is one property away
   (see the README's live-test configuration table) at that measured
   reliability cost.
4. **Docker**, for `containerTest` only — whose one end-to-end case wants the
   running router of 2 and 3 on top of it.

### A fresh ai-router clone does not build

Worth knowing before losing an hour to it: ai-router's `.gitignore` carries
a bare `ai-router` pattern with no leading slash, which git matches at any
depth — so the clone silently omits `cmd/ai-router/`, the directory holding
the entry point, and `make build` fails on a missing directory. The fix is
to recreate `cmd/ai-router/main.go` as a `package main` calling
`cli.Execute()`. Upstream bug in another repo; recorded here because it
blocks this build.
