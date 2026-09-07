# Architecture

Where things live in the code and how the pieces fit.

## Modules

| Module | Package | Depends on |
| ------ | ------- | ---------- |
| `lib/` | `codes.momo.agent` | ai-router Kotlin SDK (`api`), kaml, coroutines, slf4j-api |
| `server/` | `codes.momo.agent.server` | `lib`, Ktor server (CIO, SSE, content negotiation, status pages), slf4j-simple |

`server` depends on `lib`, never the reverse. The lib has no test suite; it is
tested through the server ([testing.md](testing.md)).

## Library (`lib/src/main/kotlin/codes/momo/agent`)

| File | Holds |
| ---- | ----- |
| `Agent.kt` | `Agent`: one session over a `Harness`. `send` runs the LLM/tool loop, `retry` resumes a beheaded run, `stop` cancels, `load` rebuilds from a stored log. |
| `AgentEvent.kt` | The event log entries. Serialized form is the wire and storage contract. |
| `AgentEventListener.kt` | Embedder callback receiving events; supplies child listeners and stored logs for revival. |
| `RunResult.kt`, `RunSettings.kt` | A run's outcome (`Status`) and its model/effort settings. |
| `Budgets.kt`, `Retry.kt` | Fixed budgets and the transient-failure retry policy. |
| `Subagents.kt`, `SpawnModels.kt` | Spawned children by name; model-id validation against the router catalog. |
| `TranscriptRepair.kt` | Synthesized results for tool calls a cut-short run never answered. |
| `PromptAttachments.kt` | Resolves markdown image links in a prompt into image parts. |
| `SessionState.kt` | Fresh vs. restored session state derived from a log. |
| `harness/` | `Harness`, `HarnessLoader`, `HarnessValidationException`. See [harness.md](harness.md). |
| `environment/` | `ExecutionEnvironment`, `Privilege`, `PrivilegeProbe`, `UserlandBaseline`. See [execution-environment.md](execution-environment.md). |
| `tool/` | `Tool`, `ToolRegistry`, `ToolResult`, `BashTool`, `ViewImageTool`, `SpawnSubagentTool`, `PromptSubagentTool`. |

Tools available to a harness: `bash`, `view_image`, `spawn_subagent`, `prompt_subagent`.

## Server (`server/src/main/kotlin/codes/momo/agent/server`)

The HTTP surface:

| File | Holds |
| ---- | ----- |
| `Main.kt` | Entry point; wires `ServerConfig`, `AiRouterClient`, `SessionRegistry`, `TemplateStore`. |
| `Api.kt` | Routes, request/response types, the exception-to-status mapping. |
| `WorkspaceScope.kt` | The `?workspace=` parameter and the guard that hides foreign sessions. |
| `TemplateApi.kt`, `TemplateStore.kt` | `/v1/templates` and the files behind it. |

Sessions. `SessionRegistry` is the shared state: the `SessionStore`, the
`ChangeSignal` and one `SessionEntry` per known session id. Every operation on
it is an extension function in the file named after what it does, so a route
handler reads as `registry.<operation>(id, ...)` and the operation's file is
where its whole behaviour lives:

| File | Holds |
| ---- | ----- |
| `SessionRegistry.kt` | The registry and `SessionEntry`: per-tree mutex, the per-session event/truncation signals, the attached `TreeRuntime`. |
| `SessionLifecycle.kt` | `create`, `closeSession`, `delete`, `shutdown`. |
| `RunControl.kt` | `startRun`, `retryRun`, `stopRun`; attaches a dormant tree before launching. |
| `Rewind.kt` | `rewind` and the shared `cutTree` that applies a `RewindPlan` to a dormant or attached tree. |
| `SessionMetadata.kt` | `rename`, `selectModel`: recorded through the live agent, or appended to the dormant log. |
| `SessionInfo.kt` | The response model, `info`/`list`, and the read functions deriving them from a log. |
| `SessionTree.kt` | A session's path from its root and the tree navigation over stored `parent` links. |
| `TreeRuntime.kt` | An attached tree: root `Agent`, environment, coroutine scope, run claims, the listener that persists events; building and loading one. |
| `ChangeSignal.kt` | The doorbell behind `/v1/sessions/changes`. |
| `SessionStore.kt` | `<data-dir>/sessions/<id>/events.jsonl`: reading, appending, tailing, the atomic cut. |
| `CutPolicy.kt` | Which events survive a cut and which cut points are valid. |
| `RewindPlan.kt`, `RetryPlan.kt` | Pure log analysis for the rewind cascade and the retry cut. |
| `SessionExceptions.kt` | The exceptions `Api.kt` maps to status codes. |

Concurrency: a tree (a root session and its subagent descendants) is the unit
of locking; the root's `SessionEntry.mutex` serializes attach, detach, cut and
run launch. `TreeRuntime` owns the coroutine scope runs execute in; dropping
it (`detachRuntime`) cancels them without recording an outcome. Per-session
`StateFlow`s wake log tailers; the `ChangeSignal` wakes list views.

## The event log

Every session is an append-only log of `AgentEvent`s. Each event carries a
`sequenceId` (0-based, strictly increasing, gaps after a rewind) and a
`timestampMillis`. The `type` field is the `@SerialName`:

| Type | Meaning |
| ---- | ------- |
| `session_started` | Always first. `sessionId`, `title`, `harnessPath`, `workspace`, `parent`, `depth`. |
| `session_renamed`, `model_selected` | User metadata. Survive a rewind. |
| `run_started` | A prompt began a run. `userMessage`, `model`, `reasoningEffort`, `attachments`. |
| `run_resumed` | A retry resumed a run. `model`, `reasoningEffort`. |
| `run_finished` | Terminal. `status`, `finalMessage`, `usage`, `turnsUsed`, `elapsed`, `error?`. |
| `llm_call_started`, `llm_call_retried`, `llm_call_finished` | One LLM turn. `llm_call_finished` carries the assistant `message` verbatim. |
| `tool_call_started`, `tool_call_finished` | One tool dispatch. `resultText`, `outcome`, `truncated`, `media?`. |
| `subagent_spawned` | `name`, `sessionId`, `subagentType`, `modelId`, `reasoningEffort`. |
| `conversation_rewound` | A cut. `lastSurvivingSequenceId`. Closes any run the cut beheaded. |
| `budget_updated` | Per-turn accounting. `turnsUsed`, `turnsRemaining`, `elapsed`. |

The log is the single source of truth: `Agent.load` rebuilds the conversation
from it, the server derives `SessionInfo` from it, and the SSE stream serves
it verbatim. Field names and defaults are the data class fields in `AgentEvent.kt`.
