# Execution environment

## Local execution

`ExecutionEnvironment` runs every command directly
on the host, in the workspace folder, with the host's environment variables.

- For isolation, run the whole stack inside a container you own. Give it a reaping pid 1 so background processes a run leaves behind do not stay zombies.

## Host requirements

| Requirement | Detail |
| ----------- | ------ |
| Platform | Linux x86_64 supported; macOS best effort; Windows unsupported. |
| Encoding | UTF-8 everywhere, LF line endings. |

Building an environment checks the workspace exists and every baseline binary
is on `PATH`. A miss is an `EnvironmentStartupException`, mapped by the
server to `400 invalid_environment` with every missing binary named.

## Command privileges

The environment discovers what the host grants (root, sudo, unprivileged) when it is built and tells the
model.

### Granting passwordless sudo

Add a sudoers entry for the OS user the server runs as.

```text
# /etc/sudoers.d/momo-agent
momo-agent ALL=(ALL) NOPASSWD: ALL
```
