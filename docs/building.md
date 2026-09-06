# Building

How to get the build configured and green: prerequisites, Gradle tasks, lint.

## Prerequisite: a local ai-router checkout

The ai-router Kotlin SDK is consumed as a Gradle composite build. The build
needs the checkout even for a plain `./gradlew build`.

- Path: `~/Develop/Private/ai-router/SDKs/kotlin`, hardcoded in `settings.gradle.kts`. Edit it there if your checkout lives elsewhere.
- A missing directory (or one without a build file) fails settings evaluation with an error naming the expected path.
- Keep the checkout current. momo-agent tracks SDK changes made alongside it; a stale checkout fails compilation.
- The same checkout is the router the live suite talks to. What it needs on top (running router, API key): [testing.md](testing.md).

### Gotcha: a fresh ai-router clone does not build

ai-router's `.gitignore` has a bare `ai-router` pattern without a leading
slash. git matches it at any depth, so the clone omits `cmd/ai-router/`, the
directory holding the entry point, and `make build` fails on a missing
directory.

Fix: recreate `cmd/ai-router/main.go` as `package main` calling
`cli.Execute()`.

## Gradle tasks

| Command | Does |
| ------- | ---- |
| `./gradlew build` | Compile, detekt, the unit tests, the live suite. Needs a running router; costs API spend. |
| `./gradlew :server:run --args="--port=8420"` | Run the server. Flags in [configuration.md](configuration.md). |
| `./gradlew :server:installDist` | Install the server distribution to `server/build/install/server/bin/server`. |
| `./gradlew :server:test` | Unit tests alone. Needs no router. |
| `./gradlew :server:liveTest` | The live suite alone. |
| `./gradlew detekt` | Lint all source sets. |

Test tasks are never `UP-TO-DATE`; every invocation runs again.

## Lint and format

Both scripts wrap detekt with detekt-formatting over every source set.

```sh
./lint.sh   # report findings; same as ./gradlew detekt
./fmt.sh    # auto-fix formatting findings; exits non-zero if any remain
```

## Build conventions

Set in the root `build.gradle.kts` for every module:

- JVM toolchain 21.
- `explicitApi = Strict`; all warnings are errors.
- detekt config in `detekt.yml`; zero tolerated findings.
- Every dependency version lives once, in `gradle/libs.versions.toml`.
- Per-test preemptive JUnit timeout: 15 min for `liveTest`, 2 min otherwise.

## Platforms

- Linux x86_64: supported; the platform for eval runs.
- macOS (arm64 included): best effort, development only.
- Windows: unsupported.

Host requirements are in [execution-environment.md](execution-environment.md).
