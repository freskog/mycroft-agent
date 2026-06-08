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

### Review queue: list & accept proposals

Everything proposed sits in **`proposed`** status (that literal string — not
"pending"). `person household` and `person memory profile` show **accepted**
items only, so use these to see and act on what's awaiting review:

```
person pending                              # all proposed memory + entities + relationships, with ids
person pending --source onboarding          # by source prefix (startsWith)
person pending --type relationship          # by type: memory | entity | relationship
person pending --type entity --kind school  # by kind (memory/entity kind; excludes relationships)
person accept-all                           # accept every proposal in one call
person accept-all --source onboarding:work  # accept only matching proposals
person accept-all --type entity --kind school   # accept by category
person accept-all --ids <id1>,<id2>,<id3>   # accept an explicit subset (ids from `person pending`)
person memory list --status proposed        # just proposed memory (also --person / --kind filters)
```

`--type` selects which buckets to act on; `--kind` filters memory/entity kind
(relationships have no kind, so a `--kind` filter excludes them). `--ids` takes a
comma-separated list and overrides the filters — use it to accept an arbitrary
subset precisely. To accept one item at a time you can also run the per-type verb
with an id from `person pending`: `person memory accept <id>`,
`person entity accept <id>`, `person relationship accept <id>` (likewise `… reject <id>`).

Building and updating the graph is covered in the dedicated `onboarding` skill. Commitments are covered in `commitments`. Goals and plans are covered in `goals` and `plans`. Semantic facts and the episodic event log are covered in `memory` and `events`.

## Rules

1. Always check health first if unsure whether the service is running.
2. Memory/entity/relationship proposals are not auto-accepted — they require human review. List them with `person pending`; accept on the user's approval with `person accept-all` (or per-item `… accept <id>`). The status value is `proposed`.
3. Never propose memory that contains credentials, tokens, or passwords.
4. Approval requests are for actions that need human authorization before execution.
5. The `person` CLI must be used inside `safe-run` — never call the HTTP API directly.
6. If the service is unavailable, report the error and stop — do not retry endlessly.

## Reference

For memory kinds, entity/relationship kinds, and full policy boundaries see `references/api.md` in this skill directory.
