# Sessions

## What a session is

A session is one conversation between a user and an agent. On disk it is one
file, `<data-dir>/sessions/<id>/events.jsonl`: the event log, one JSON
`AgentEvent` per line, appended as the agent works.

The log is the source of truth. Everything is derived from it, and a
session is rebuilt from it after a server restart. It is read strictly: a
change to a field or serial name in `AgentEvent` or `RunResult.Status` breaks
every stored session and needs a wipe of the data directory.

Nothing lives in memory between runs. A prompt loads the agent from the log,
runs it, and drops it again; the next prompt loads the log as it stands then.
A session's `status` is `running` while this server executes a run on it and
`idle` otherwise.

The log is settled whenever it is read: every run has its `run_finished`, and
every tool call the model asked for has its `tool_call_finished` — a call a
stop cut short is answered with an error saying so before the run ends. A
kill leaves a run open; the first read of that tree afterwards answers the
open call and ends the run `interrupted`, on disk, before anything is served
from it. Nothing is patched in memory: the agent refuses an unsettled log.

## Lifecycle by example

A user opens a project, creates a session over the `coder` harness, and works:

1. **Prompt** "Add a test for the parser". The run's model and reasoning effort travel with the prompt; the harness sets neither. The run appends events until the model answers without tool calls (`completed`), a budget ends it (`turns_exhausted`, `timeout`), or an LLM call fails terminally (`error`). The client follows the event stream; no endpoint returns the outcome.
2. **Stop** while the agent is still running `./gradlew test`. The tool's process tree is killed, the run ends `stopped`, the session is `idle` and promptable at once. Nothing else changes.
3. **Retry** after a run ended `error` (the router was down). The failed LLM call and its outcome are cut from the log; the run resumes from the turn before it under its recorded settings. Progress before the failure survives.
4. **Rewind** to before "Add a test for the parser" because the approach was wrong. That prompt and every event after it are deleted from the log permanently and the agent forgets them. The workspace is not rewound: files stay as the deleted turns left them. Title and model selection survive a rewind. A rewind cuts from a user or an assistant message, never from inside a turn.
5. **Kill** the server mid-run (a crash, a forced update). The run is recorded as `interrupted`, the tool call it cut short as an error; the next prompt resumes the conversation with that knowledge. An interrupted run is not retryable: a retry replays only an LLM call that got no answer, never a tool call that may have half-run.
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
