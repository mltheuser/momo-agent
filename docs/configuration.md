# Configuration

## Server

Resolution order: CLI argument, then environment variable, then default.

CLI arguments take the form `--option=value`. Source: `ServerConfig` in the server's root package.

- There is no authentication.
- A blank environment variable counts as unset.
- Logging: slf4j-simple to stderr. Levels in `**/simplelogger.properties`.

Run:

```sh
./gradlew :server:run --args="--port=8420" # data in ~/.momo-agent
./gradlew :server:run --args="--port=8421 --data-dir=/tmp/momo" # a throwaway second instance
```

## Live test suite

Resolution order: Gradle property, then environment variable, then default.

Blank values count as unset. Source: root `build.gradle.kts`.

```sh
./gradlew :server:liveTest -PaiRouterBaseUrl=http://localhost:9999
```
