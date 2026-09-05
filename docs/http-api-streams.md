# HTTP API: streams, models, templates

The SSE streams, the model catalog and the prompt-template routes.
Session routes are in [http-api-sessions.md](http-api-sessions.md).
Source: `server/.../Api.kt`, `TemplateApi.kt`, `TemplateStore.kt`.

## Event stream: `GET /v1/sessions/{id}/events`

Serves the session's stored event log as server-sent events, then follows
live events.

| Frame part | Content |
| ---------- | ------- |
| `id:` | The event's `sequenceId`. 0-based, strictly increasing, gaps where a rewind deleted events. |
| `data:` | The event JSON exactly as stored: one serialized `AgentEvent` ([architecture.md](architecture.md), The event log). |
| `event:` | Not used. |

Rules:

- Without `Last-Event-ID`: full history, then live. With it: events strictly after that sequence ID.
- Any number of subscribers; each reads at its own pace.
- Subscribing to a `closed` session serves history without resuming it.
- Deleting the session ends its streams. An unknown session is a `404` before the stream opens.
- Periodic SSE comment frames serve as heartbeat.
- After a rewind every subscriber receives the appended `conversation_rewound` frame, numbered above everything deleted. Frames the cut preserved (`session_renamed`, `model_selected`) keep their original IDs and arrive before it. Do not drop state numbered past the cut on its account.

## Change stream: `GET /v1/sessions/changes`

A notification channel for a client rendering the session listing.

- Frame: `event: change` with no data. No `id:`; `Last-Event-ID` is not read.
- Sent once on connect, then after every listing mutation: create, delete, rename, select-model, run start, run end.
- Not sent for in-flight progress (`updatedAtMillis`, `lastRun`). Follow the session's event stream for that.
- A frame means "re-read the listing". A read taken after the frame sees the change.
- Frames may coalesce under load; the last of a burst stands for all.
- Global: not scoped to a workspace. A client re-reads its own scoped listing.

## Models: `GET /v1/models`

ai-router's model catalog in ai-router's own response shape, filtered to
models whose capabilities include both `chat` and `tools`. Each entry's
`model` field is the fully-qualified string to send as a prompt's `model`.
Takes no `workspace` parameter.

## Templates

A prompt template is a named prompt text a client recalls instead of retyping.

| Method and path | Body | Response |
| --------------- | ---- | -------- |
| `GET /v1/templates` | – | `200` sorted JSON array of names |
| `GET /v1/templates/{name}` | – | `200` `{"name": "...", "body": "..."}`; `404 unknown_template` |
| `PUT /v1/templates/{name}` | `{"body": "..."}` | `200` the template as stored. Creates or overwrites. Blank body is `400 invalid_request`. |

Rules:

- Name: 1 to 100 characters from `[A-Za-z0-9._-]`, not starting with `.`. Anything else is `400 invalid_request`.
- Storage: `<data-dir>/templates/<name>.md`, body verbatim.
- Global; no `workspace` parameter.
- No delete route. Remove a template by deleting its file.
