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

| File | Holds |
| ---- | ----- |
| `Main.kt` | Entry point; wires `ServerConfig`, `AiRouterClient`, `SessionRegistry`, `TemplateStore`. |
| `Api.kt` | Routes, request/response types, error mapping. |
| `TemplateApi.kt`, `TemplateStore.kt` | `/v1/templates` and the files behind it. |
| `SessionRegistry.kt` | Every session, live or dormant; tree runtime attachment; run control. |
| `SessionStore.kt` | On-disk `session.json` and `events.jsonl`; log tailing; rewind cut. |
| `SessionInfo.kt` | The response model and the read functions deriving it from a log. |
| `RewindPlan.kt`, `RetryPlan.kt` | Pure log analysis for the rewind cascade and the retry cut. |
| `WorkspaceScope.kt` | The `?workspace=` parameter and its guard. |
| `EnvironmentSpec.kt` | The `{"type": "local", "workspace": ...}` union. |

## The event log

Every session is an append-only log of `AgentEvent`s. Each event carries a
`sequenceId` (0-based, strictly increasing, gaps after a rewind) and a
`timestampMillis`. The `type` field is the `@SerialName`:

| Type | Meaning |
| ---- | ------- |
| `session_started` | Always first. `sessionId`, `title`, `depth`. |
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
