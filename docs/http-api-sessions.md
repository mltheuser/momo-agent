# HTTP API: sessions

The `/v1/sessions` routes: paths, bodies, response fields, status and error codes.
Streams, models and templates are in [http-api-streams.md](http-api-streams.md).
Source: `server/.../Api.kt`, `SessionInfo.kt`, `EnvironmentSpec.kt`.

## Routes

| Method and path | Body | Response |
| --------------- | ---- | -------- |
| `POST /v1/sessions` | create body | `201` session info |
| `GET /v1/sessions?workspace=<abs path>` | – | `200` array of root sessions in that workspace, oldest first. `workspace` required. |
| `GET /v1/sessions/{id}` | – | `200` session info |
| `POST /v1/sessions/{id}/prompt` | `{"prompt", "model", "reasoningEffort"?}` | `202` session info; the run continues in the background |
| `POST /v1/sessions/{id}/retry` | – | `202` session info. Retries a failed last run. |
| `POST /v1/sessions/{id}/rewind` | `{"firstDeletedSequenceId": <long>}` | `200` `{"session": <info>, "deletedSessionIds": [...]}` |
| `POST /v1/sessions/{id}/rename` | `{"title": "..."}` | `200` session info |
| `POST /v1/sessions/{id}/select-model` | `{"model", "reasoningEffort"?}` | `200` session info |
| `POST /v1/sessions/{id}/stop` | – | `200` session info, once the run has ended. Idempotent. |
| `POST /v1/sessions/{id}/close` | – | `200` session info. Closes the whole tree. Idempotent. |
| `DELETE /v1/sessions/{id}` | – | `204`. Removes the session and its descendants. |
| `GET /v1/sessions/{id}/events` | – | SSE ([http-api-streams.md](http-api-streams.md)) |
| `GET /v1/sessions/changes` | – | SSE ([http-api-streams.md](http-api-streams.md)) |

Every `/v1/sessions/{id}` route accepts an optional `?workspace=`. Semantics
in [sessions-and-storage.md](sessions-and-storage.md), Workspace scope. What
prompt, stop, retry and rewind do is in [runs.md](runs.md).

## Create body

```json
{"harnessPath": "<server-local dir>", "environment": {"type": "local", "workspace": "<absolute dir>"}, "title": "..."}
```

- `title` is optional; default is the harness folder's name.
- `local` is the only environment type.
- A `privilege` key anywhere is `400 invalid_request`; privilege is discovered, not chosen.

## Prompt body

- `model`: required, non-blank. The model every LLM call of the run goes to. An unknown id is accepted and ends the run with status `error`.
- `reasoningEffort`: optional; one of `none`, `low`, `medium`, `high`. Absent means provider default.
- `prompt`: required, non-blank. Markdown image links `![t](link)` are resolved into attachments ([runs.md](runs.md)).

## Session info

| Field | Type | Meaning |
| ----- | ---- | ------- |
| `id` | string | Session ID. |
| `parent` | string? | Parent session ID; `null` for a root. |
| `title` | string | Latest rename, else the creation title. |
| `harnessPath` | string | Folder the session's own harness runs from. For a child: resolved via the spawn chain, falling back to the root's stored path. |
| `environment` | object | `{"type": "local", "workspace": ...}`. A child reports its root's. |
| `privilege` | string? | `root`, `passwordless_sudo`, `unprivileged`; `null` when `closed`. |
| `status` | string | `running`, `idle`, `closed`. Derived, never stored. |
| `createdAtMillis` | long | `session_started` timestamp. |
| `updatedAtMillis` | long | Last logged event's timestamp. |
| `lastRun` | object? | `{"turnsUsed", "totalTokens", "elapsed"}` of the current or last run; `null` when the log holds no run. |
| `modelSelection` | object? | `{"model", "reasoningEffort"}`: latest of `model_selected`, `run_started`, `run_resumed` by sequence ID; for a child with none, its spawn's pin; else `null`. |

## Errors

Body: `{"code": "...", "message": "..."}`.

| Status | Code | When |
| ------ | ---- | ---- |
| 400 | `invalid_request` | Malformed JSON, unknown field, blank required field, unknown `reasoningEffort`, bad `workspace` parameter, invalid rewind point, invalid template name |
| 400 | `invalid_harness` | Harness folder fails to load |
| 400 | `invalid_environment` | Workspace missing, or host userland baseline incomplete |
| 404 | `unknown_session` | No such session, or session outside the given `workspace` |
| 404 | `unknown_template` | No file backs the template name |
| 409 | `conflict` | Run in flight where none may be; nothing to retry |
| 409 | `unrevivable_subagent` | Stored spawn has no type, or a type the harness does not declare |
| 500 | `corrupt_session` | Stored `session.json` or `events.jsonl` no longer parses |
| 500 | `event_log_failed` | The session's log stopped persisting; new runs refused |
| 500 | `internal_error` | Anything else |
