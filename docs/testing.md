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
access to the API's request and response types. The root package holds the
test classes: plain JUnit 5 classes whose methods run inside `withLiveServer`.
Two sub-packages hold what they stand on.

`rig` is everything a test needs to talk to a real server. It starts the
installed server as a child process, points it at the live ai-router, and
gives the test an HTTP client with one function per API call. Most tests share
one server for the whole suite; a test that restarts or crashes the server
starts its own. The rig can also put a fake router between server and
ai-router to script failures the live router would never produce.

`fixtures` holds what a test writes into its `@TempDir` before it starts: the
harness folder and the workspace the agent runs against.

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
