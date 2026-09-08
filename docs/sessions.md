# Sessions

## What a session is

A session is one conversation between a user and an agent. On disk it is one
file, `<data-dir>/sessions/<id>/events.jsonl`: the event log, one JSON
`AgentEvent` per line, appended as the agent works.

The log is the single source of truth. Nothing lives in memory between runs: a prompt
loads the agent from the log, runs it, and drops it again.

A session's `status` is `running` while this server executes a run on it and
`idle` otherwise.

Every way a run ends leaves the log settled: a run that ends with a call
still executing (a stop, a budget) first answers that call with an error
saying why, then records its outcome. Killing the server mid-run is the one
way to leave a log unsettled. Before serving any read of a tree the server
settles it: unless a run is in flight in that tree, every member log left open
by a kill gets its cut-short calls answered and a `run_finished(interrupted)`,
on disk. Nothing is patched in memory.

## Lifecycle by example

A user opens a project, creates a session over the `coder` harness, and works:

1. **Prompt** "Add a test for the parser". The run's model and reasoning effort travel with the prompt; the harness sets neither. The run appends events until the model answers without tool calls (`completed`), a budget ends it (`turns_exhausted`, `timeout`), or an LLM call fails terminally (`error`). The client follows the event stream; no endpoint returns the outcome.
2. **Stop** while the agent is still running `./gradlew test`. The tool's process tree is killed, the call is answered with an error ("tool execution cut short — a user stopped the run"), the run ends `stopped`, and the session is `idle` and promptable at once.
3. **Retry** after a run ended `error` (the router was down). The failed LLM call and its outcome are cut from the log; the run resumes from the turn before it under its recorded settings. Progress before the failure survives.
4. **Rewind** to before "Add a test for the parser" because the approach was wrong. That prompt and every event after it are deleted from the log permanently and the agent forgets them. The workspace is not rewound: files stay as the deleted turns left them. Title and model selection survive a rewind. A rewind cuts from a user message (`run_started`) or an assistant message (`llm_call_finished`); naming any other event is a `400 invalid_request`.
5. **Kill** the server mid-run (a crash, a forced update). The log is left with an open run. The next read of the session — the sidebar listing it, the chat panel streaming it, the next prompt — settles it first: the cut-short call is answered ("the server went down"), the run ends `interrupted`, and the next prompt resumes the conversation with that knowledge. An interrupted run is not retryable: a retry replays only an LLM call that got no answer, never a tool call that may have half-run.
6. **Delete** once the work is merged. The log is removed.

A prompt while a run is in flight, or a rewind or retry while any run in the
session's tree is in flight, is refused.

## Parent and subagent sessions

An agent whose harness declares `subagents` can spawn children. A child is a
full session: its own log, streamable, renameable and promptable by ID.

- The parent's log records each spawn as `subagent_spawned`.
- The child's `session_started` records the parent's session ID.
- Rewind cascades. A deleted `subagent_spawned` on rewind deletes that child and its subtree. A deleted `prompt_subagent` call cuts the child's log back to before the run it drove.
- Stop cascades down, not up: stopping the parent stops its children's runs; stopping a child ends only that run, and the parent sees the stop as a tool result error.
- A child is loaded from its log when its parent first prompts it during a run, and dropped with the parent's run. Prompting a child by ID loads it alone, without its parent.
- A tree is settled as a unit: a kill mid-`prompt_subagent` leaves both the parent's and the child's log open, and reading either settles both.
