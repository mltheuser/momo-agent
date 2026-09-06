# Harness format

How to write a harness folder.

## Folder contents

| File | Purpose |
| ---- | ------- |
| `harness.yaml` | Manifest: tools and subagent types. |
| `instructions.md` | System prompt text, passed to the model verbatim. |

Both files are required.

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

Unknown keys are rejected (kaml strict mode). Declaring `subagents` automatically adds the `spawn_subagent` and `prompt_subagent` tools.


## What a harness does not control

- The model and reasoning effort: set per prompt.
- Command privileges: discovered from the host ([execution-environment.md](execution-environment.md)).
