# Harness format

How to write a harness folder, the unit a session runs.

## Folder contents

| File | Purpose |
| ---- | ------- |
| `harness.yaml` | Manifest: tools and subagent types. |
| `instructions.md` | System prompt text, passed to the model verbatim. |

Both files are required. Reference folders: `lib/examples/coder/`, `lib/examples/teacher/`.

## `harness.yaml` keys

```yaml
tools:
  - bash
  - view_image
subagents:
  reviewer:
    path: ../reviewer
    description: Reviews a change and reports findings.
  self:
    path: .
    description: An agent like this one, for delegating subtasks.
```

| Key | Required | Rules |
| --- | -------- | ----- |
| `tools` | yes | At least one name. No blanks, whitespace or duplicates. Available: `bash`, `view_image`. |
| `subagents` | no | Map of type name to entry. A non-empty map offers the agent `spawn_subagent` and `prompt_subagent`. |
| `subagents.<type>.path` | yes | Another harness folder, relative to this folder. Absolute paths are rejected. `.` references the folder itself. |
| `subagents.<type>.description` | yes | Non-blank one-liner the model sees when choosing a type. |

Unknown keys are rejected (kaml strict mode). Two keys draw specific errors:

- `model`: retired. A run's model comes with each prompt, not from the harness.
- `spawn_subagent` or `prompt_subagent` under `tools`: retired. Declaring `subagents` offers them.

## Loading rules

`Harness.load(folder)` (`lib/.../harness/HarnessLoader.kt`):

- Loads every referenced folder recursively, each folder once by canonical path. Self-references and cycles terminate.
- A broken referenced harness fails the whole load; the message names the referencing manifest and type.
- Every failure is a `HarnessValidationException` naming the offending file. The server maps it to `400 invalid_harness`.
- A tool name the library does not provide fails at agent construction, not at first use.

## What a harness does not control

- The model and reasoning effort: set per prompt ([runs.md](runs.md)).
- Budgets (turns, wall clock, tool timeout, nesting depth): library-fixed ([configuration.md](configuration.md)).
- Command privileges: discovered from the host ([execution-environment.md](execution-environment.md)).
- Subagent tools at nesting depth 5: withheld regardless of the manifest ([subagents.md](subagents.md)).
