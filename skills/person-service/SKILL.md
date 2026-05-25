# Person Service API

## Purpose

Interact with the trusted person-service sidecar for durable personal/family state.

## Commands

All commands go through the `person` CLI, wrapped in `safe-run`.

### Health check

```
safe-run --cwd /tmp/workspace --timeout 5 --shell bash -- "person health"
```

### Propose memory

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person memory propose \
    --person fred \
    --scope fred_work \
    --kind preference \
    --text 'Prefer draft-only email actions' \
    --source chat:local"
```

### Request approval

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person approval request \
    --action-type calendar.propose_event \
    --scope family_shared \
    --payload-json '{\"summary\":\"Family dinner\",\"date\":\"2026-05-30\"}'"
```

## Memory Kinds

- `preference` — how the person likes things done
- `fact` — factual information about the person or their world
- `project_note` — context about ongoing work
- `procedure_note` — how to do a specific recurring task

## Rules

1. Always check health first if unsure whether service is running.
2. Memory proposals are not automatically accepted — they require human review.
3. Never propose memory that contains credentials, tokens, or passwords.
4. Approval requests are for actions that need human authorization before execution.
5. The `person` CLI must be used inside `safe-run` — never call the HTTP API directly.
6. If the service is unavailable, report the error and stop — do not retry endlessly.

## Available Scopes

- `fred_private` — Fred's personal items
- `fred_work` — Fred's work items
- `paula_private` — Paula's personal items
- `family_shared` — Shared family items

## Policy Boundaries

The agent **can**:
- Propose commitments
- List commitments
- Propose memory
- Request approval

The agent **cannot**:
- Accept or mark commitments done
- Delete commitments or memory
- Write calendar events
- Send email
- Mutate credentials
