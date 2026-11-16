# Scripts

A script is a Python program the server runs. It automates the creation of sessions. A script
belongs to one workspace folder, like a session, and the sessions it creates
are ordinary sessions of that workspace.

## On disk

`<data-dir>/scripts/<id>/` holds one script:

| File | Purpose |
| ---- | ------- |
| `<title>.py` | The code. Its file name is the single source of truth for the script's title. |
| `run.log` | Everything the latest run printed, stdout and stderr. Each start begins it anew. |
| `script.json` | Stores the workspace the script belongs to. |

`<data-dir>` is the server's data folder ([configuration.md](configuration.md)),
and `<id>` is a UUID the server picks when the script is created.

- A script runs with its own `<data-dir>/scripts/<id>/` folder as working directory. `momo.workspace` is the workspace's path.
- `import momo` works because the server starts every script with `script-runtime/` on its `PYTHONPATH`. The server writes that folder's two files anew each time it starts.

## Lifecycle

A script is `inactive` until it is started, `running` while its process
lives, and `failed` when its last run ended on its own with a non-zero exit
code. The state is the `state` field of each entry of `GET /v1/scripts`.

`failed` differs from `inactive` only in name. It stays `failed` until its
next start.

- Saving a code change does nothing until script restart.
- A stop request blocks until the process is gone.
- Rename and delete are refused while the script runs.
- No script state is stored. After a server restart every script is `inactive`.
- Every change to a script's state rings `GET /v1/changes`.
- A script never outlives the server.

The server has no scheduler: a script that should act every ten minutes loops and sleeps.

## The `momo` module

A script imports `momo`, a thin client of this server's HTTP API, defined in
[momo.py](../server/src/main/resources/script-runtime/momo.py).

## Limits

- A script is one file: it cannot import other modules from its folder. A script folder needs to hold exactly one `.py` file or is ignored.
- The log grows for as long as the script runs.
- Process leak: A process the script detaches into a session of its own survives a stop.
