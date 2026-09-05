# Execution environment

Where agent commands run, what the host must provide, and how command
privileges are discovered and granted.

## Local execution

`ExecutionEnvironment` (`lib/.../environment/`) runs every command directly
on the host, in the workspace folder, with the host's environment variables.

- There is no isolation layer and no container mode. A command can touch anything the server's OS account can.
- For isolation, run the whole stack inside a container you own. Give it a reaping pid 1 (`docker run --init`, or `init: true` in compose) so background processes a run leaves behind do not stay zombies.
- Timeouts and cancellation kill the process tree best-effort. Processes that daemonize away can leak on the host.
- Commands run as an argv vector; the `bash` tool passes `["bash", "-c", script]`. stdin is closed.

## Host requirements

| Requirement | Detail |
| ----------- | ------ |
| Platform | Linux x86_64 supported; macOS best effort; Windows unsupported. |
| Userland baseline on `PATH` | `base64`, `bash`, `cat`, `cp`, `find`, `grep`, `ls`, `mkdir`, `mv`, `rm`, `sed` |
| Encoding | UTF-8 everywhere, LF line endings. |

Building an environment checks the workspace exists and every baseline binary
is on `PATH`. A miss is an `EnvironmentStartupException`, mapped by the
server to `400 invalid_environment` with every missing binary named.

## Command privileges

The environment discovers what the host grants when it is built and tells the
model. Nothing is granted or elevated by the library; the posture is not
configurable.

| `privilege` | Meaning | Detection |
| ----------- | ------- | --------- |
| `root` | Commands already run as root. | `bash -c '[ "$EUID" -eq 0 ]'` |
| `passwordless_sudo` | `sudo` elevates without a password. | `sudo -n -k true` |
| `unprivileged` | No way up. Also the answer on a host without `sudo`. | Neither probe succeeded |

- Probes run most-privileged first, in the workspace, with a 10 s timeout each. Detection never fails a session.
- `-k` ignores a warm credential cache, so a recently typed password cannot pass for a `NOPASSWD` entry.
- The value is reported in session info and is `null` for a `closed` session.

### Granting passwordless sudo

Add a sudoers entry for the OS user the server runs as. Use `visudo -f`,
never a plain editor.

```text
# /etc/sudoers.d/momo-agent
momo-agent ALL=(ALL) NOPASSWD: ALL
```

- sudoers is keyed on user, host, run-as user and command list. It cannot be scoped to a working directory, so per-workspace elevation is not expressible.
- Run the server under a dedicated account, not a developer login. Two postures need two server processes under two accounts.
- To run an agent that cannot elevate, remove the grant from the account or run the stack in a container.
