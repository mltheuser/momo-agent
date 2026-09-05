# momo-agent

A Kotlin library for defining and running file-system-based agents, plus an
HTTP server hosting agent sessions for interactive clients. The only LLM
backend is the local [ai-router](https://github.com/mltheuser/ai-router)
project, consumed through its Kotlin SDK.

Two Gradle modules:

- `lib/` — the embeddable library.
- `server/` — the agent server, a runnable binary wrapping `lib`.

## Prerequisites: local ai-router checkout

The build includes the ai-router Kotlin SDK as a Gradle composite build, so a
local checkout of the ai-router repository is **required** — even for a plain
`./gradlew build`. The SDK path is hardcoded in `settings.gradle.kts` to
`~/Develop/Private/ai-router/SDKs/kotlin`; if your checkout lives elsewhere,
edit the path there.

The checkout must be current: momo-agent relies on SDK changes made
alongside it, and a stale checkout fails compilation — update both
together.

If the directory does not exist (or contains no build file), the build
fails immediately during settings evaluation with an error naming the
expected path.

That same checkout is also the router `./gradlew build` talks to, since the
build runs the live suite — one checkout, both roles. The rest of what a fresh
checkout needs to get `build` green, including the API key, is in
[TESTING.md](TESTING.md).

### A fresh ai-router clone does not build

Worth knowing before losing an hour to it: ai-router's `.gitignore` carries a
bare `ai-router` pattern with no leading slash, which git matches at any
depth — so the clone silently omits `cmd/ai-router/`, the directory holding
the entry point, and `make build` fails on a missing directory. The fix is to
recreate `cmd/ai-router/main.go` as a `package main` calling `cli.Execute()`.
Upstream bug in another repo; recorded here because it blocks this build.

## Building

```sh
./gradlew build
```

Runs compilation, detekt (with detekt-formatting) and the tests — one live
suite driving the packaged server end to end, plus one unit test. What it
runs, what it needs of the machine and what it costs: [TESTING.md](TESTING.md).

## Linting & formatting

Both scripts wrap detekt (with detekt-formatting) and cover all source sets:

```sh
./lint.sh   # check for findings (same as ./gradlew detekt)
./fmt.sh    # auto-fix formatting findings; remaining findings need manual fixes
```

## Live tests

The live suite runs inside `build`, and on its own with:

```sh
./gradlew :server:liveTest
```

What the suite is for, what it refuses to do and what it needs running is in
[TESTING.md](TESTING.md).

Configuration (Gradle property takes precedence over the environment
variable, which takes precedence over the default):

| Setting  | Gradle property     | Environment variable   | Default                           |
| -------- | ------------------- | ---------------------- | --------------------------------- |
| Base URL | `aiRouterBaseUrl`   | `AI_ROUTER_BASE_URL`   | `http://localhost:8787`           |
| Model    | `aiRouterChatModel` | `AI_ROUTER_CHAT_MODEL` | `claude-sonnet-5:cloud@anthropic` |

Example with overrides:

```sh
./gradlew :server:liveTest -PaiRouterBaseUrl=http://localhost:9999 -PaiRouterChatModel=some-model@provider
```

The default model is a cloud one reached *through* the local router, so the
router needs the matching provider key and a build costs API spend — see
[TESTING.md](TESTING.md) for why, and for what substituting a local model
costs in reliability.

## Execution is always local

Agent commands run directly on the host the process runs on, in the
session's workspace folder — there is no isolation layer and no container
mode. Where isolation is wanted (a benchmark harness, a cloud runner), the
boundary is a container the embedder owns, with the whole stack running
inside it: from in there the same local execution covers everything, and
the container's teardown reaps whatever a run leaked. Give that container a
reaping pid 1 (`docker run --init`, or `init: true` in compose): background
processes a run leaves behind are reparented to pid 1 and, once they exit,
stay zombies until it reaps them — `sleep infinity` as pid 1 never does.

## Agent server

The `server` module hosts multiple concurrent agent sessions behind a
localhost-only HTTP API — the process boundary interactive clients talk
to. Run it with:

```sh
./gradlew :server:run --args="--port=8420"
```

Each setting resolves from its CLI argument, then its environment
variable, then the default:

| Setting            | CLI argument           | Environment variable | Default                 |
| ------------------ | ---------------------- | -------------------- | ----------------------- |
| Port               | `--port`               | `MOMO_AGENT_PORT`    | `8420`                  |
| Data directory     | `--data-dir`           | `MOMO_AGENT_DATA_DIR`| `~/.momo-agent`         |
| ai-router base URL | `--ai-router-base-url` | `AI_ROUTER_BASE_URL` | `http://localhost:8787` |

The server always binds `127.0.0.1`. There is no auth: remote access is
out of scope for v1. It logs warnings and errors to stderr (slf4j-simple;
level in `server/src/main/resources/simplelogger.properties`), so a run
ending in `error` names its reason without a client attached.

### Sessions

A session is one agent conversation over a harness and an execution
environment. Its source of truth is on disk under the data directory —
`sessions/<session-id>/session.json` (for a root: harness path and
environment spec; for a subagent: its parent's session ID) plus
`sessions/<session-id>/events.jsonl` (the event log, one
serialized `AgentEvent` per line, appended live) — so every session
survives a server restart: on startup the data directory is indexed and
prior sessions appear as `closed`. A running agent and
its environment are an ephemeral runtime attachment on top; closing a
session drops the attachment and keeps the stored log.

A session **belongs to the workspace folder it works in**, and that folder
is the scope every request is read in: see *Workspace scope* below. Nothing
is stored inside the workspace itself — the data directory holds it all — so
a project with sessions has nothing of the server's in it.

### Endpoints (v1)

| Method & path                 | Effect |
| ----------------------------- | ------ |
| `POST /v1/sessions`           | Create a session (request body below) → `201` with the session info. |
| `GET /v1/sessions`            | List the root sessions of one workspace (ID, parent, title, harness path, environment, privilege, status, created-at, updated-at, last run's budget consumption). Takes a **required** `?workspace=<absolute path>` (see *Workspace scope*). Subagent sessions are omitted — fetch them by ID. |
| `GET /v1/sessions/{id}`       | One session's info. |
| `POST /v1/sessions/{id}/prompt` | Send the next user message; the run starts in the background → `202` with a snapshot of the session info. |
| `POST /v1/sessions/{id}/rename` | Set the session's title (request body below) → `200` with the updated session info. |
| `POST /v1/sessions/{id}/select-model` | Record the model selected for the session's next prompt (request body below) → `200` with the updated session info. |
| `POST /v1/sessions/{id}/rewind` | Cut the session's log back to an earlier event (request body below) → `200` with the updated session info plus the IDs of every session the cascade deleted. |
| `POST /v1/sessions/{id}/retry` | Retry the session's failed last run — no request body → `202` with a snapshot of the session info (see *Retrying a failed run*). |
| `GET /v1/sessions/changes`    | An SSE stream signalling that the session listing is worth re-reading (below). |
| `GET /v1/sessions/{id}/events`| The session's event log as an SSE stream: stored history, then live events. |
| `POST /v1/sessions/{id}/stop` | Stop the session's in-flight run — no request body → `200` with the session info once the run has ended. Idempotent. |
| `POST /v1/sessions/{id}/close`| Close the session's whole subagent tree; aborts in-flight work without recording its end, tears the environment down, keeps the stored logs. Idempotent. |
| `DELETE /v1/sessions/{id}`    | Close the tree if needed, then remove the session and its descendants with their stored artifacts → `204`. |
| `GET /v1/models`              | ai-router's model catalog in its own response shape, filtered to the models an agent can run (capabilities include both `chat` and `tools`). Each entry's `model` field is the fully-qualified string to send as a prompt's `model`. |
| `GET /v1/templates`           | Every stored prompt template's name, as a sorted JSON array (see *Prompt templates*). |
| `GET /v1/templates/{name}`    | One template → `200` `{"name": "...", "body": "..."}`; a name no file backs is a `404 unknown_template`. |
| `PUT /v1/templates/{name}`    | Create or overwrite a template with body `{"body": "..."}` → `200` with the template as stored. Idempotent; a blank body is a `400 invalid_request`. |

Every `/v1/sessions/{id}` route takes an **optional** `?workspace=`, and
answers `404` when it names a folder that is not the session's own (see
*Workspace scope*).

The create body names a server-local harness folder, an environment, and
an optional title:

```json
{"harnessPath": "<dir>", "environment": {"type": "local", "workspace": "<dir>"}, "title": "..."}
```

`local` is the only environment type; execution is always local (see
*Execution is always local*).

A `privilege` key in the body is a `400 invalid_request`: a session's
privilege is discovered, not chosen, and reported read-only (see the derived
fields below, and *Command privileges* for what decides it).

Rename takes a one-field body:

```json
{"title": "..."}
```

Select-model records what a client's model picker shows — the selection the
session's next prompt is expected to carry; `reasoningEffort` is optional
(absent or null means the provider default), and a missing or blank `model`
is a `400 invalid_request`:

```json
{"model": "...", "reasoningEffort": "high"}
```

The selection is user metadata the event log carries as a `model_selected`
event — appended directly for a `closed` session, which stays closed, exactly
like a rename — and read back as the derived `modelSelection` field below.
The agent itself never reads it: every run still carries its own prompt's
model settings (see *Prompting*).

Rewind takes a one-field body too, naming the first event the cut deletes:

```json
{"firstDeletedSequenceId": 42}
```

A rewind is destructive: the named event and the conversation after it are
deleted from the stored log permanently — no copy, no undo — and a
`conversation_rewound` event is appended as the new tail (see *The event
stream*). Two kinds of event survive the cut wherever they sit: a
`session_renamed` and a `model_selected` stay in the log with their original
sequence IDs, so a rewind never changes the session's title or its recorded
model selection. The session keeps its identity,
environment and resume path, and is promptable the moment the call returns;
rewinding a `closed` session leaves it closed, the next prompt resuming
from the cut log. The whole tree must be idle — a run in flight anywhere
in it is a `409` and nothing changes. A sequence ID the log does not hold —
one an earlier rewind deleted included — is a `400 invalid_request`, and so
is the log's first event: every read of a log derives from the
`session_started` opening it, so no cut may delete it. An event the cut
would preserve is a `400` too: what a cut keeps cannot be what it cuts
from, or the call would delete nothing at all. Cutting back to the very
start *is* allowed, by naming the log's first `run_started` — every run
goes and the conversation is left empty: a session whose history is the
`session_started` and the announcement above it, reporting no last run at
all and promptable as if fresh. The cut may land mid-run: the appended
event closes what remains of it, so a dangling `run_started` above the cut
never reads as still running. One caveat worth knowing: the **workspace is
not rewound** — files stay exactly as the deleted turns left them; the
agent is not told, it simply no longer remembers. What a rewind does to
spawned subagents — and which sessions the response's `deletedSessionIds`
names — is under *Subagent sessions*.

Session `status` is derived, never stored: `running` (a run is in
flight), `idle` (live, nothing running), `closed` (no runtime attached;
resumable). So is `privilege` — `unprivileged`, `passwordless_sudo` or
`root` — which reports what the session's built environment found the host
to grant, and which the agent is told too. It is `null` for a `closed`
session, where no environment exists to have asked and the answer could
differ by the time one does.

Session info also carries `updatedAtMillis` — the last logged event's
timestamp, for ordering by recency. A rename is logged: its
`session_renamed` event lands in the log — appended directly for a `closed`
session, which stays closed — and the title is derived from the last such
event. A blank title is a `400 invalid_request`.

`modelSelection` (`{"model": ..., "reasoningEffort": ...}`, nullable) is
derived the same way, never stored: it is the latest by sequence ID of the
log's own `model_selected` picks and `run_started` records naming a model —
so a run updates the shown selection to what actually ran, and a later
explicit pick overrides it. A rewind can therefore change the derived value:
only the `model_selected` events survive a cut, so a deleted `run_started`
stops contributing and an older pick can stand again. A child session whose
log names neither falls back to what its spawn pinned — the `modelId`
together with any `reasoning_effort` pinned beside it (see *Subagent
sessions*). With nothing at all it is `null`, and the client's own default
stands.

Errors are structured JSON — `{"code": "...", "message": "..."}` — with
`400` for invalid harness/environment/request, `404` for an unknown
session, `409` for operations conflicting with an active run or with the
current harness configuration, and `500` otherwise.

### Prompt templates

A prompt template is a recurring prompt text saved under a name, for a
client to recall instead of retyping. Each is one plain markdown file,
`<data-dir>/templates/<name>.md`, holding the body verbatim. A name is 1 to
100 characters from `[A-Za-z0-9._-]` and does not start with `.` — no
whitespace, no path separators, no `..` — and anything else is a
`400 invalid_request`. Templates are global: they take no `?workspace=`
scope, and one set serves every project. There is no delete endpoint —
a template is removed by deleting its file by hand.

### Workspace scope

One server serves every project on the machine, and a session belongs to the
workspace folder it works in — so that folder is the scope a request is read
in. It travels as a `workspace` query parameter naming an **absolute path**:

```sh
curl "http://127.0.0.1:8420/v1/sessions?workspace=/home/me/project"
```

**Required on `GET /v1/sessions`.** An unscoped listing would be every
project's sessions at once, which is no answer to any client's question, so a
request without the parameter — or with a blank or relative one — is a
`400 invalid_request` rather than a silent everything. It is a query
parameter rather than a header because HTTP headers carry only Latin-1: a
project path like `/home/me/プロジェクト` cannot be spelled in one, while a
query parameter percent-encodes anything.

**Optional on every `/v1/sessions/{id}` route**, including the event stream.
Named, it must be the session's own or the response is `404 unknown_session`
— *not* a `403`: a session outside the scope has to look nonexistent, since
its existence is precisely what the scope hides. Omitted, the route is
unscoped, which is what keeps `curl` debugging and the server's own tests
working. Present but blank or relative is a `400 invalid_request` here as
well — a malformed scope is a client bug, not a request to widen it. A
subagent resolves through its tree's root, so a child is in scope exactly
when its root is.

Creation is the one route with a workspace and no scope to check: `POST
/v1/sessions` names its workspace in the body, so the query parameter is
ignored there. A session whose stored workspace cannot be read at all — a
corrupt `session.json` — is in nobody's scope and reads as `404` for any
parameter; unscoped, it still reports its corruption, which is where a
repair starts.

Two paths are the same scope when they are lexically equal once made
absolute and normalized, so a trailing slash and a `.`/`..` segment cost
nothing. **Symlinks are deliberately not resolved**, which has two
consequences worth knowing: two spellings of one folder — a symlink and its
target — are two separate scopes; and a session whose workspace folder has
been *deleted* stays listable and deletable, because the comparison never
touches the filesystem. The unresolved path is also the one the agent's
commands actually run in, so it is the honest thing to compare.

`GET /v1/models` takes no scope, and neither does the change stream — see
*The change stream* for why it stays global.

### Prompting

```json
{"prompt": "...", "model": "...", "reasoningEffort": "high"}
```

The body carries the next user message plus the run's model settings:
`model` (required) names the model the run's LLM calls go to, and an
optional `reasoningEffort` (one of `none`, `low`, `medium`, `high`) sets
those calls' reasoning effort. The run's `run_started` event records the
model and effort it used. A missing or blank `model` and an unknown
`reasoningEffort` value are a `400 invalid_request`; an unknown model id
is accepted and surfaces as a failed run. The settings also cover subagent
runs this run drives — unless the child was spawned with a `model_id` or
`reasoning_effort`, which pin its settings (see Subagent sessions); a
directly prompted child uses only its own prompt's settings.

A markdown image link — `![title](link)` — anywhere in the prompt is
resolved best-effort into an image the model sees alongside the text: a
relative path resolves against the session workspace, an absolute path
loads likewise (both through the session's execution environment), and an http(s) URL is
fetched. A link that fails to load — for any reason —
stays plain prompt text. The
prompt string itself is stored and echoed verbatim either way; the resolved
images ride the run's `run_started` event as its `attachments`, so replay
rebuilds the exact message without re-reading the sources.

Prompting a `closed` session rebuilds its tree's runtime first — that is
the resume path (see Subagent sessions). No endpoint returns the run's
outcome: its `run_finished` event (status, final message verbatim, usage,
turns used, elapsed, and — on an `error` status — a structured error naming
what failed) is the record, observed via the event stream. A run's
`status` there is one of `completed`, `stopped` (the stop endpoint ended
it), `turns_exhausted`, `timeout`, or `error` — a failed run can be
retried in place (see *Retrying a failed run*). The one run without that
record is one cut short by closing or deleting the session, or by a server
shutdown — the log ends mid-run and the session's `status` is the
indicator. A rewind can also take the record away when its cut lands
mid-run — see the rewind endpoint under *Endpoints (v1)*. A blank prompt
is a `400 invalid_request`, a prompt while a run
is active a `409 conflict`, and a session whose event log can no longer
persist refuses new runs with a `500 event_log_failed`.

### Retrying a failed run

`POST /{id}/retry` takes no body: everything is derived from the stored
log, whose tail must be a `run_finished` with status `error` — anything
else (a completed run, a stopped one, a fresh session, a run in flight
anywhere in the tree) is a `409 conflict` and nothing changes. The retry
is a rewind and a resume in one request: the log is cut back to before the
LLM call that failed — the failed call's `llm_call_started`, its
`llm_call_retried` tail and the `run_finished` all go, announced by the
same `conversation_rewound` tail a rewind appends — and the beheaded run
resumes under the settings its own opening recorded. Progress the run made
before the failed call (completed turns, tool results) survives. The
resumed run opens on the stream as a `run_resumed` event — carrying the
run's model and effort like a `run_started`, but no user message: the run
it continues already has one — and ends with a `run_finished` like any
other. Automatic transient-failure retries start over with a fresh backoff
schedule, so retrying is also how a run that exhausted them is given
another round.

### The event stream

`GET /v1/sessions/{id}/events` serves the stored event log verbatim as
server-sent events: each frame's `id:` is the event's `sequenceId`
(0-based and strictly increasing — with gaps where a rewind deleted
events, since sequence IDs are never reused) and its `data:` is the event
JSON exactly as stored — one serialized `AgentEvent` (its serialization is
the wire contract — see `AgentEvent`'s KDoc). No `event:` name is used.
The stream replays history — all of it, or strictly after the
`Last-Event-ID` request header on reconnect — then stays open and follows
live events. Any number of subscribers can follow one session, each at its
own pace; subscribing to a `closed` session serves its history without
resuming it. Deleting the session ends its streams.

A rewind announces itself on the stream as the `conversation_rewound`
event it appends: it names the last surviving sequence ID and is itself
numbered above everything deleted, so replaying subscribers, live tails,
and `Last-Event-ID` reconnects from inside the deleted range all converge
on it without reconnect gymnastics. Other frames can stand above the cut
point too: a `session_renamed` or `model_selected` the cut kept (see the
rewind endpoint) holds its original sequence ID, and a subscriber is served
it before the announcement — so a client that reacts to the announcement by
dropping state numbered past the cut would discard a rename it has just been
handed.
The announcement carries no conversation content and renders nothing; for
what it does to a run the cut landed in, see the rewind endpoint under
*Endpoints (v1)*.

### The change stream

`GET /v1/sessions/changes` is a notification channel for a client that
renders the session listing: a `change` frame every time a listed session's
identity or state changes — one created or deleted, a title, a model
selection, and a `status`, so twice per run, as it starts and as it ends. What an
in-flight run keeps moving is deliberately not signalled: a client following
`updatedAtMillis` or `lastRun` as they climb follows the session's own event
stream.

Every frame means the one thing — re-read the listing — and carries no state
at all, not even which session changed, so the only way to answer one is to
read. That is what frees the stream of a replay contract: frames carry no
`id:`, `Last-Event-ID` is not read, and missed signals cost nothing a fresh
read does not restore. Each connection opens with a frame of its own, before
anything has changed, so a client re-reads on every connect and reconnect —
which is exactly the recovery for whatever it missed while away. A frame
stands behind the change it reports: a read taken on its heels already
accounts for that change, so a `status` read on a run's closing frame sees
the run over.

Signals are dropped rather than queued when a subscriber cannot keep up:
every frame says the same thing, so a subscriber too slow for a burst loses
only frames a later one already stands for. Any number of subscribers can
follow the stream.

**The stream is global, not scoped to a workspace** (see *Workspace scope*):
a client is signalled by traffic to any project's sessions, and answers with
a read that is scoped anyway — so the cost of the extra frames is a re-read
that finds its own listing unchanged. Scoping the stream is cleanly additive
as an optional parameter if that ever stops being cheap enough.

### Subagent sessions

Agents spawn subagents through the library's `spawn_subagent` /
`prompt_subagent` tools. The tools are offered only to a harness whose
`harness.yaml` declares a `subagents` map — each entry names a subagent
type the harness may spawn:

```yaml
tools:
  - bash
subagents:
  reviewer:
    path: ../reviewer
    description: Reviews a change and reports findings.
  self:
    path: .
    description: An agent like this one, for delegating subtasks.
```

`path` names another harness folder by a relative path, resolved against
the declaring folder — absolute paths are rejected — so a composition
stays one copyable artifact. Loading a harness loads and validates every
referenced folder recursively — each folder once, so self-references and
reference cycles terminate — and a broken referenced harness fails the
load naming the reference. The subagent tools are never listed under
`tools` (doing so is a load error); a declared map implies them, withheld
again from agents at the library-fixed nesting-depth cap.

`spawn_subagent(name, type, model_id?, reasoning_effort?)` spawns a child
of a declared `type`, running that type's harness. The optional `model_id`
and `reasoning_effort` pin the model and effort of the runs the parent
drives through `prompt_subagent` — an omitted one inherits the driving
run's setting (`none` is a real effort pin, not the omitted default) — and
both survive dormancy: a revived child keeps its pins. A `model_id` must
address an entry of the router's catalog of chat+tools models, as its
fully-qualified `model` string with or without the `@provider` suffix; an
unknown one is rejected with up to ten of the closest usable ids, so the
error is how a model discovers a valid id. A directly prompted child
always uses its own prompt's settings.

Each child is a full session under the same data directory, created the
moment its spawn is announced. Clients discover children through the
parent's `subagent_spawned` event, which carries the child's session ID
plus the spawn's `subagentType`, `modelId` and `reasoningEffort` (each
`null` in logs predating it): `GET /{id}`, the event stream, and
`POST /{id}/prompt` all work on children, while the listing stays
roots-only. A child's info carries `parent` (`null` on a root); its
`environment` is its root's — children execute in the root's environment
and own none themselves — while its `harnessPath` names the harness
folder the child itself runs, resolved by following each stored spawn's
type from the root's harness down; when the chain cannot be resolved,
the info degrades to the root's stored harness folder rather than
erroring.

The runtime attachment belongs to the whole tree and is dropped only by an
explicit close or server shutdown — close is how a tree's memory is
reclaimed. Closing or deleting *any* member affects the whole tree:
in-flight runs anywhere in it are aborted, the one environment is torn
down, and every member becomes `closed` with its log intact. Delete
additionally removes the target session and all its descendants; a deleted
child later prompted by its parent draws the unknown-subagent error
result, and the name is free to spawn anew.

A stop, by contrast, is run-scoped and leaves the tree attached: it
cascades only downward, into the child runs the stopped run is blocked on
— each recording its own `stopped` end. A stopped parent-driven child
hands its parent an error result naming the stop, like every unfinished
child run hands one naming its own outcome, and the parent runs on. A
`prompt_subagent` call the cut run never answered gets a synthesized
result in the parent's transcript saying what ended the run and whether
the subagent received the message — received when the call had started
executing, never delivered when it was still queued.

A rewind of any member cascades along what its deleted range caused,
recursively: a deleted `subagent_spawned` removes that child and its whole
subtree exactly as a delete would — stored artifacts and all, the name
free to spawn anew — with every removed session named in the response's
`deletedSessionIds`; a deleted `prompt_subagent` call cuts the child's own
log to strictly before the run that call drove, taking everything after it
— runs a human prompted directly in between included. A descendant the
deleted ranges never touch keeps its log untouched, and every cut log gets
its own `conversation_rewound` tail. An attached tree is reloaded from the
cut logs over the same environment: live descendants drop back to dormant,
revived again on use.

Prompting any dormant member — root or child — rebuilds the tree's runtime
and revives just the chain from the root to the prompted session from the
stored logs, so a re-prompted parent can still converse with children
spawned before a restart. Revival can fail by configuration: a stored
spawn without a type (a log predating typed spawning), or one whose type
the harness no longer declares, is a `409 unrevivable_subagent` when
prompted or renamed directly, and a parent's `prompt_subagent` to it draws
an error result. A human may prompt an idle child at any time; a running
child answers `409`, and a parent's `prompt_subagent` to a human-busy
child gets an error result in its own log.

## Supported platforms & system assumptions

- **Linux x86_64** — officially supported; the platform for eval runs.
- **macOS** (including arm64) — best-effort, for development only.
- **Windows** — unsupported.

The project assumes a POSIX userland (`bash`, coreutils — `base64`
included — `grep`, `find`, `sed`), UTF-8 everywhere, and LF line endings.

### Command privileges

An environment *discovers* what it has when it is constructed and tells the
model that, so the prompt cannot disagree with the host.

Nothing is ever granted or elevated by the library, and none of this is a
sandbox: what a command can reach follows entirely from the OS account the
server process runs as. That is why the posture is not configurable — asking
for a smaller number would change only what the model is told. To run an
agent that genuinely cannot elevate, remove the grant from the account, or
run the whole stack inside a container.

Passwordless sudo is granted by host configuration: a sudoers entry for the
OS user the server process runs as, added with `visudo -f`, never a plain
editor.

```text
# /etc/sudoers.d/momo-agent
momo-agent ALL=(ALL) NOPASSWD: ALL
```

sudoers is keyed on user, host, run-as user and command list — it **cannot
be scoped to a working directory**, so per-workspace elevation is not
expressible: the unit of granting is the account, in practice the server
process. Run the server under a dedicated account for it rather than a
developer's own login, and where two workspaces need different postures,
run two server processes under two accounts.

Detection is at most two probes per environment, most-privileged first: an
effective-uid check for root, then `sudo -n -k true` for passwordless sudo
(`-k` ignores any warm credential cache, so a password typed minutes ago
cannot pass for a NOPASSWD entry). Neither succeeding is `unprivileged` —
the floor detection never falls below, which is also where a host with no
`sudo` at all lands. Detection therefore never fails a session; it only
ever reports something less than you hoped for.
