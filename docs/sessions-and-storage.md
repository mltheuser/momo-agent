# Sessions and storage

What a session is, how its status is derived, how requests are scoped to a
workspace, and what is on disk.

## Lifecycle

A session is one agent conversation over a harness and a workspace. Its source
of truth is its stored log plus metadata. A running agent and its environment
are an ephemeral runtime attachment on top.

| Status | Meaning |
| ------ | ------- |
| `running` | A run is in flight. |
| `idle` | Runtime attached, nothing running. |
| `closed` | No runtime attached. Resumable: the next prompt rebuilds it. |

- Status is derived from the tree's runtime, never stored or read from the log's tail.
- On startup the data directory is indexed; every stored session appears as `closed`.
- Close and server shutdown drop the attachment and keep the log. Delete removes the stored artifacts.
- `privilege` is reported only while a runtime exists; `null` when `closed`.
- Rename and select-model on a `closed` session append to the log directly; the session stays `closed`.
- Runtime, environment and lifecycle transitions belong to the whole subagent tree ([subagents.md](subagents.md)).

## Workspace scope

A session belongs to the workspace folder it works in. The scope travels as a
`workspace` query parameter naming an absolute path.

| Route | Parameter | Behaviour |
| ----- | --------- | --------- |
| `GET /v1/sessions` | required | Missing, blank or relative: `400 invalid_request`. |
| `/v1/sessions/{id}/*` (events included) | optional | Present and not the session's own: `404 unknown_session`. Blank or relative: `400`. Omitted: unscoped. |
| `POST /v1/sessions` | ignored | The workspace is in the body. |
| `/v1/sessions/changes`, `/v1/models`, `/v1/templates` | none | Global. |

Comparison rules:

- Two paths are one scope when lexically equal after `toAbsolutePath().normalize()`. Trailing slashes and `.`/`..` segments do not matter.
- Symlinks are not resolved: a symlink and its target are two scopes. The comparison never touches the filesystem, so a session whose folder was deleted stays listable and deletable.
- A child session resolves through its root.
- A session whose `session.json` is unreadable is in nobody's scope (`404` with any parameter). Unscoped `GET /{id}` reports `500 corrupt_session`.
- The listing skips unreadable sessions rather than failing.

## On-disk layout

Everything lives under the data directory (default `~/.momo-agent`). Nothing
is written into the workspace.

```text
<data-dir>/
  sessions/<session-id>/
    session.json    # metadata, see below
    events.jsonl    # one serialized AgentEvent per line, appended live
  templates/<name>.md
```

`session.json` is a tagged union (`SessionMetadata` in `SessionStore.kt`):

| `type` | Fields | Used for |
| ------ | ------ | -------- |
| `root` | `harnessPath`, `environment` (`{"type": "local", "workspace"}`) | Rebuilding the runtime; the tree's workspace scope |
| `child` | `parent` (session ID) | Its place in the tree |

A child's harness and environment resolve through its root.

## Persisted-format contract

Both files are read strictly. An unknown key, variant or serial name fails the
read and surfaces the session as corrupt (`500 corrupt_session`).

- The schema is `AgentEvent` (with `RunResult.Status`, `ReasoningEffort`) and `SessionMetadata` (with `EnvironmentSpec`).
- Any change to a `@SerialName`, a variant name or a field is a break with every stored session. Make it knowingly, with a wipe or a migration.
- Optional fields (`depth`, `model`, `attachments`, `error`, `subagentType`, `media`) default when absent.
- `Privilege` is a response contract only; it is never stored.
- A trailing line torn by process death mid-write is tolerated and never served.
- Writes to `session.json` and rewind cuts of `events.jsonl` are atomic replacements (`AtomicReplace.kt`).
