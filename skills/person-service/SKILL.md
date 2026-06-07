---
name: person-service
description: Interact with the trusted person-service sidecar for durable household state — people, the entity/relationship graph, commitments, memory, approvals, goals, audit.
version: 1.0.0
capabilities: [person-cli, safe-run]
---

# Person Service

## Purpose

Interact with the trusted person-service sidecar for durable personal and family state. The sidecar runs outside the sandbox and owns the database, credentials, and approval policy. The agent never touches the database directly — it always goes through the `person` CLI (which must itself run inside `safe-run`).

## Commands

### Health check

```
safe-run --cwd /tmp/workspace --timeout 5 --shell bash -- "person health"
```

### Propose memory

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person memory propose \
    --person fred \
    --kind preference \
    --text 'Prefer draft-only email actions' \
    --source chat:local"
```

### Request approval

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person approval request \
    --action-type calendar.propose_event \
    --payload-json '{\"summary\":\"Family dinner\",\"date\":\"2026-05-30\"}'"
```

### Household graph (entities & relationships)

```
person entity propose --kind organization --name 'MegaCorp' --source onboarding:work
person entity list --status accepted
person relationship propose --from fred --from-kind person --type employed_by \
  --to <entity-id> --to-kind entity --source onboarding:work --valid-from 2024-01-01T00:00:00Z
person household        # accepted, currently-active persons/entities/relationships
```

Building and updating the graph is covered in the dedicated `onboarding` skill. Commitments are covered in `commitments`. Goals and plans are covered in `goals` and `plans`. Semantic facts and the episodic event log are covered in `memory` and `events`.

## Rules

1. Always check health first if unsure whether the service is running.
2. Memory proposals are not automatically accepted — they require human review.
3. Never propose memory that contains credentials, tokens, or passwords.
4. Approval requests are for actions that need human authorization before execution.
5. The `person` CLI must be used inside `safe-run` — never call the HTTP API directly.
6. If the service is unavailable, report the error and stop — do not retry endlessly.

## Reference

For memory kinds, entity/relationship kinds, and full policy boundaries see `references/api.md` in this skill directory.
