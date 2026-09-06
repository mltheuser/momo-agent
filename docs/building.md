# Building

## Prerequisite: a local ai-router checkout

The ai-router Kotlin SDK is consumed as a Gradle composite build.

- Path: `~/Develop/Private/ai-router/SDKs/kotlin`, hardcoded in `settings.gradle.kts`.
- The SDK is compiled from that folder's current source on every build; there is no version pin.
- The same checkout is the router the live suite talks to. See the docs of the ai-router project for how to run it.

## Gradle tasks

| Command | Does |
| ------- | ---- |
| `./gradlew build` | Compile, detekt, the unit tests, the live suite. Needs a running router. |
| `./gradlew :server:run --args="--port=8420"` | Run the server. Flags in [configuration.md](configuration.md). |
| `./gradlew :server:installDist` | Install the server distribution to `server/build/install/server/bin/server`. |
| `./gradlew :server:test` | Unit tests alone. Needs no router. |
| `./gradlew :server:liveTest` | The live suite alone. |
| `./gradlew detekt` | Lint all source sets. |

Test tasks are never cached; every invocation runs again.

## Lint and format

Both scripts wrap detekt with detekt-formatting over every source set.

```sh
./lint.sh   # report findings; same as ./gradlew detekt
./fmt.sh    # auto-fix formatting findings; exits non-zero if any remain
```

## Platforms

- Linux x86_64: supported; the platform for eval runs.
- macOS (arm64 included): best effort, development only.
- Windows: unsupported.
