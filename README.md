# momo-agent

A Kotlin library for defining and running file-system-based agents. The
library's only LLM backend is the local [ai-router](https://github.com/mltheuser/ai-router)
project, consumed through its Kotlin SDK.

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

Runs compilation, detekt (with detekt-formatting) and the unit test suite.
It does **not** run the live integration tests and does not require a running
ai-router server — only the checkout on disk.

## Linting & formatting

Both scripts wrap detekt (with detekt-formatting) and cover all source sets:

```sh
./lint.sh   # check for findings (same as ./gradlew detekt)
./fmt.sh    # auto-fix formatting findings; remaining findings need manual fixes
```

## Live integration tests

The `liveTest` suite (`src/liveTest/kotlin`) exercises the real wiring
against a **running** local ai-router server. It is not part of `build` or
`check`; run it explicitly:

```sh
./gradlew liveTest
```

Start a server from the ai-router checkout (see its README; currently
`./bin/ai-router serve`); the configured model must be available locally
(e.g. pulled in Ollama).

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

## Supported platforms & system assumptions

- **Linux x86_64** — officially supported; the platform for eval runs.
- **macOS** (including arm64) — best-effort, for development only. Known
  divergence: BSD `sed` appends a newline when printing a final line that
  lacks one, so `read_file` output is byte-exact only on GNU `sed`.
- **Windows** — unsupported.

The project assumes a POSIX userland (`bash`, coreutils, `grep`, `find`,
`sed`), UTF-8 everywhere, LF line endings, and a `docker` CLI usable
without `sudo` (needed later for container-based agent execution).
