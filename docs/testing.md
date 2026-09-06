# Testing

## Live tests over unit tests

The default test is a live end-to-end test: it starts the packaged server as
an OS process, talks to it over HTTP, and lets a
real model answer through a real ai-router. Nothing is mocked.

One test walks one realistic user flow and pins several facts; each assertion message names the fact.

Write a unit test only for pure logic over in-memory data that a live test
cannot stage cheaply.

## Running live tests

```sh
./gradlew :server:liveTest
./gradlew :server:liveTest --tests '*RunControlLiveTest.inFlightGuardsThenStop*'
```

## How live tests are structured

Source set `server/src/liveTest`, compiled against `main` for `internal`
access to the API's request and response types. A
test is a plain JUnit 5 class whose methods run inside `withLiveServer`.

The foundation, bottom up:

1. **The router** (`LiveRouter.kt`). Base URL and model come from system properties the Gradle task sets. The first use probes the router; an unreachable or wrong-model router fails every test once per JVM.
2. **A server process** (`LiveServerProcess.kt`). Starts the installed distribution (`installDist` runs first) as a child process with `--data-dir` and `--ai-router-base-url`, waits for it to listen, and kills it on close.
3. **The shared server** (`LiveServerSupport.kt`). One process for the whole suite, started lazily on first use over a temp data dir, torn down by a JVM shutdown hook. `withLiveServer { http -> ... }` wraps a test body in `runBlocking` with an HTTP client pointed at it. Tests share the process but each owns only the sessions it creates.
4. **The API client** (`ServerApiClient.kt`). Extension functions on `HttpClient` speaking the server's API: typed calls that decode responses (`createSession`, `prompt`, `rewindSession`), `*Response` variants returning the raw response for status and error-code assertions, `raw*` variants taking a JSON string for malformed input, and the waits (`awaitRunEnd`, `streamEvents(until = ...)`, `withChangeStream`).
5. **Fixtures**. `writeHarness` writes a harness folder into the test's `@TempDir`; `localWorkspace` makes a workspace folder there; `WordImage` renders a word into a PNG. `FaultyRouter` is a loopback stand-in for router failures: it scripts HTTP-level replies per request and forwards to the live router once the script is spent. It never scripts model output.

Waiting for a run: use `awaitRunEnd` or `streamEvents(until = ...)`, never a `RunFinished` frame alone and never a sleep. The server's claim on a run outlives the terminal frame, so a command sent on that frame's heels can draw a `409`. Treat the change stream as a doorbell: `ChangeStream.signalled { mutation }` then re-read; never count frames.

Adding a test: one class per user flow, one `@TempDir`, a `@DisplayName` stating the flow. Write the harness, `createSession(harnessPath, localWorkspace(tempDir))`, drive it through the API client, assert on events and `SessionInfo`.

## Running unit tests

```sh
./gradlew :server:test
```

Needs no router. Part of `./gradlew build`.

## How unit tests are structured

Source set `server/src/test`, bound to `main` for `internal` access. JUnit 5
with `kotlin.test` assertions. A test class builds its inputs in memory with
small factory functions and asserts on the returned value; no process,
router, filesystem or model.
