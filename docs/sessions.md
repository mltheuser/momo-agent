# Sessions

## What a session is

A session is one conversation between a user and an agent. On disk it is one
file, `<data-dir>/sessions/<id>/events.jsonl`: the event log, one JSON
`AgentEvent` per line, appended as the agent works.

The log is the single source of truth. Nothing lives in memory between runs: a prompt
loads the agent from the log, runs it, and drops it again.

A session's `status` is `running` while this server executes a run on it and
`idle` otherwise.

Every graceful end of a run leaves the log settled. Killing the server mid-run is the one
way to leave a log unsettled. Before serving any read of a session the server
settles it on disc. An open run on disk therefore means this server is executing or
resuming it now.

The server serves the whole log on `GET /v1/sessions/{id}/events` and rings
`GET /v1/sessions/changes` (SSE, one dataless `change` frame per mutation, coalesced)
whenever any session's log or the session list changes. A client that shows a session
refetches its log on every ring.

## Lifecycle by example

A user opens a project, creates a session over the `coder` harness, and works:

1. **Prompt** "Add a test for the parser". The run's model and reasoning effort travel with the prompt; the harness sets neither. The run appends events until the model answers without tool calls (`completed`), a budget ends it (`turns_exhausted`, `timeout`), or an LLM call fails terminally (`error`). The client refetches the log as the change stream rings; no endpoint returns the outcome.
2. **Stop** while the agent is still running `./gradlew test`. The running command is terminated, its call is answered with an error saying a user stopped the run, the run records `stopped`, and the log ends up settled.
3. **Retry** after a run ended `error` (the router was down). The failed LLM call and its outcome are cut from the log; the run resumes from the turn before it under its recorded settings. Progress before the failure survives. The cut leaves the run open and the resumption reopens it in the same step under the tree's lock, so the run's `run_finished` is the one the retry records.
4. **Rewind** to before "Add a test for the parser" because the approach was wrong. That prompt and every event after it are deleted from the log permanently and the agent forgets them. The workspace is not rewound: files stay as the deleted turns left them. Title and model selection survive a rewind. A rewind cuts from a user message; naming an event that is not a user message is a `400`. Since a user message opens a run, the log after a rewind ends where the previous run ended and is settled without any marker.
5. **Kill** the server mid-run. Nothing gets to finish: the log is left in an incomplete state. The next read of the session repairs and settles it.
6. **Delete** once the session is no longer needed. The log is removed.

A prompt while a run is in flight, or a rewind or retry while any run in the
session's tree is in flight, is refused.

## Parent and subagent sessions

An agent whose harness declares `subagents` can spawn children. A child is a
full session: its own log, readable, renameable and promptable by ID.

- The parent's log records each spawn as `subagent_spawned`.
- The child's `session_started` records the parent's session ID.
- Rewind cascades. A deleted `subagent_spawned` on rewind deletes that child and its subtree. A deleted `prompt_subagent` call cuts the child's log back to before the run it drove.
- Stop cascades down, not up: stopping the parent stops its children's runs; stopping a child ends only that run, and the parent sees the stop as a tool result error.
