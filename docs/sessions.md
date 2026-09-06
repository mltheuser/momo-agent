# Sessions

## What a session is

A session is one conversation between a user and an agent. On disk it is one
file, `<data-dir>/sessions/<id>/events.jsonl`: the event log, one JSON
`AgentEvent` per line, appended as the agent works.

The log is the source of truth. Everything is derived from it, and a
session is rebuilt from it after a server restart. It is read strictly: a
change to a field or serial name in `AgentEvent` or `RunResult.Status` breaks
every stored session and needs a wipe of the data directory.

A session's `status` is computed dynamically. It is `running` while a run is in
flight, `idle` while a runtime is attached, and `closed` when none is. The
server starts every stored session as `closed`; the next prompt rebuilds it.

## Lifecycle by example

A user opens a project, creates a session over the `coder` harness, and works:

1. **Prompt** "Add a test for the parser". The run's model and reasoning effort travel with the prompt; the harness sets neither. The run appends events until the model answers without tool calls (`completed`), a budget ends it (`turns_exhausted`, `timeout`), or an LLM call fails terminally (`error`). The client follows the event stream; no endpoint returns the outcome.
2. **Stop** while the agent is still running `./gradlew test`. The tool's process tree is killed, the run ends `stopped`, the session is `idle` and promptable at once. Nothing else changes.
3. **Retry** after a run ended `error` (the router was down). The failed LLM call and its outcome are cut from the log; the run resumes from the turn before it under its recorded settings. Progress before the failure survives.
4. **Rewind** to before "Add a test for the parser" because the approach was wrong. That prompt and every event after it are deleted from the log permanently and the agent forgets them. The workspace is not rewound: files stay as the deleted turns left them. Title and model selection survive a rewind.
5. **Close** at the end of the day. The runtime and its environment are dropped; the log stays. Tomorrow's prompt resumes the conversation.
6. **Delete** once the work is merged. The log is removed.

A prompt while a run is in flight, or a rewind or retry while any run in the
session's tree is in flight, is refused.

## Parent and subagent sessions

An agent whose harness declares `subagents` can spawn children. A child is a
full session: its own log, streamable, renameable and promptable by ID.

- The parent's log records each spawn as `subagent_spawned`.
- The child's `session_started` records the parent's session ID.
- Rewind cascades. A deleted `subagent_spawned` on rewind deletes that child and its subtree. A deleted `prompt_subagent` call cuts the child's log back to before the run it drove.
