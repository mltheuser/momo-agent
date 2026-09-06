# Runs: prompt, stop, retry, rewind

What each run-control operation does to a session and its log. Route
signatures are in [http-api-sessions.md](http-api-sessions.md).

## Prompt

`POST /v1/sessions/{id}/prompt` starts a run in the background.

- The run's `model` and `reasoningEffort` are recorded on its `run_started` event. There is no session or harness default.
- Prompting a `closed` session rebuilds its tree's runtime first (resume). Nothing else is needed to resume.
- Refused: blank prompt or model (`400`), run already in flight (`409 conflict`), log no longer persisting (`500 event_log_failed`).
- The outcome is not returned by any endpoint. Read the `run_finished` event from the event stream.

### Image links in a prompt

A markdown image link `![title](link)` anywhere in the prompt is resolved
into an image the model sees next to the text.

| Link form | Resolved as |
| --------- | ----------- |
| Relative path | Against the session workspace |
| Absolute path | Loaded from the host |
| `http(s)://` URL | Fetched |

A link that fails to load, for any reason, stays plain text. The prompt string
is stored verbatim; resolved images ride `run_started.attachments`. Image size
cap: 5 MiB.

## Run outcomes (`run_finished.status`)

| Status | Meaning |
| ------ | ------- |
| `completed` | The model answered without tool calls; `finalMessage` is the answer. |
| `stopped` | The stop endpoint ended the run. |
| `turns_exhausted` | Turn budget spent while the model still wanted tools. |
| `timeout` | Wall-clock budget spent. |
| `error` | An LLM call failed terminally. `error` carries `message`, and `type`/`statusCode` when ai-router reported them. Retryable. |

A run cut short by close, delete, server shutdown or a JVM error has no
`run_finished`; the log ends mid-run and the session's `status` is the
indicator. Transient LLM failures (429, 5xx, connection errors) are retried
automatically with backoffs of 5 s, 1 min and 5 min; each retry logs
`llm_call_retried`.

## Stop

`POST /{id}/stop` cancels the in-flight run and returns once it has ended.

- The run ends with status `stopped`; a tool mid-execution has its process tree killed.
- Cascades only downward into child runs the stopped run is blocked on. Each records its own `stopped` end.
- The tree stays attached; the session is `idle` and promptable at once.
- No run in flight: no-op success.

## Retry

`POST /{id}/retry` retries a failed last run in place. No body.

- Requires the log's tail (ignoring `session_renamed`/`model_selected`) to be a `run_finished` with status `error`, and no run in flight anywhere in the tree. Otherwise `409 conflict`.
- Cuts the log back to before the failed LLM call: its `llm_call_started`, any `llm_call_retried` and the `run_finished` go. A `conversation_rewound` is appended.
- Resumes the run under the settings its opening recorded. The resumed run opens with `run_resumed` (no user message) and ends with `run_finished`.
- Progress before the failed call (completed turns, tool results) survives. Automatic retries start over with a fresh backoff schedule.

## Rewind

`POST /{id}/rewind` with `{"firstDeletedSequenceId": N}` deletes event `N`
and every conversation event after it. Permanent; no undo.

- Requires the whole tree idle; otherwise `409 conflict`, nothing changes.
- `400 invalid_request` when `N` is not in the log (deleted IDs included), is the log's first event, or names a `session_renamed`/`model_selected`.
- `session_renamed` and `model_selected` events survive wherever they sit. Title and recorded selection do not change. `modelSelection` can still change: a deleted `run_started` stops contributing.
- A `conversation_rewound` naming the last surviving sequence ID is appended above every deleted ID. Sequence IDs are never reused.
- Naming the first `run_started` empties the conversation; the session reports no `lastRun` and is promptable as fresh.
- A cut landing mid-run closes that run; no dangling `run_started` reads as running.
- A `closed` session stays closed. The session keeps identity, environment and resume path.
- The workspace is not rewound. Files stay as the deleted turns left them.
- Cascade into subagents and the `deletedSessionIds` response field: [subagents.md](subagents.md).
