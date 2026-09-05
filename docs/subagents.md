# Subagents

How an agent spawns children, and how child sessions behave in the server.

## Tools

Offered to a harness that declares a `subagents` map ([harness.md](harness.md)),
except at nesting depth 5 (`Budgets.MAX_SUBAGENT_DEPTH`).

| Tool | Arguments | Effect |
| ---- | --------- | ------ |
| `spawn_subagent` | `name`, `type`, `model_id?`, `reasoning_effort?` | Creates a child of a declared type running that type's harness. Sends nothing. |
| `prompt_subagent` | `name`, `message` | One blocking round: sends the message, returns the child's final message. |

Spawn rules:

- `name` is unique within the parent. `type` must be declared. Errors return as tool results, not exceptions.
- `model_id` and `reasoning_effort` pin the child's parent-driven runs. Omitted: the driving run's setting is inherited. `none` is a real effort pin.
- `model_id` must match a `/v1/models` entry's `model` string, with or without the `@provider` suffix. An unknown id is rejected with up to ten closest valid ids.
- Pins persist: a revived child keeps them.

Prompt results:

- `completed`: the child's final message.
- Any other outcome: an error result naming the outcome. `stopped` tells the parent not to re-prompt.
- Child busy (a human prompted it): an error result.
- Child deleted or unknown: an error result; the name is free to spawn anew.

## Child sessions

Each child is a full session under the same data directory, created when its
spawn is announced.

| Aspect | Behaviour |
| ------ | --------- |
| Discovery | The parent's `subagent_spawned` event: `sessionId`, `subagentType`, `modelId`, `reasoningEffort`. |
| Listing | `GET /v1/sessions` omits children. Fetch by ID. |
| Info | `parent` set; `environment` is the root's; `harnessPath` resolved through the spawn chain. |
| Prompt | A human may prompt an idle child directly. It uses only that prompt's settings. Running child: `409`. |
| Workspace scope | A child is in scope exactly when its root is. |
| `modelSelection` | Falls back to the spawn's pin when the child's own log names none. |

## Tree operations

| Operation on any member | Effect on the tree |
| ----------------------- | ------------------ |
| Close | Aborts in-flight runs anywhere (no `run_finished`), tears the one environment down, every member becomes `closed`. Logs kept. |
| Delete | Close, then remove the target and its descendants with their stored artifacts. The rest of the tree stays stored, closed. |
| Stop | Run-scoped. Cascades only downward into child runs the stopped run awaits; each records `stopped`. Tree stays attached. |
| Server shutdown | Like close for every tree. |

Vocabulary: abort (close/delete/shutdown, no recorded outcome) is not stop
(recorded `stopped`). See [AGENTS.md](../AGENTS.md).

## Revival

- Prompting any dormant member rebuilds the tree's runtime and revives the chain from the root to that member from stored logs. Other children stay dormant until used.
- A stored spawn without a type, or with a type the harness does not declare, is `409 unrevivable_subagent` when prompted or renamed directly. A parent's `prompt_subagent` to it gets an error result.
- A `prompt_subagent` call the run never answered (abort, stop, crash) gets a synthesized result in the parent's transcript on load: what ended the run, and whether the child received the message.

## Rewind cascade

A rewind of any member cascades along what its deleted range caused:

- A deleted `subagent_spawned` removes that child and its subtree like a delete. Removed IDs are returned in `deletedSessionIds`.
- A deleted `prompt_subagent` call cuts the child's log to before the run that call drove, including later human-prompted runs.
- Untouched descendants keep their logs. Every cut log gets its own `conversation_rewound`.
- An attached tree reloads from the cut logs over the same environment; live descendants drop to dormant.

The cascade is computed in `server/.../RewindPlan.kt`, tested by `RewindPlanTest`.
