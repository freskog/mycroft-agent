---
name: commitments
description: Record and list durable obligations (commitments) for people via the person-service.
version: 2.0.0
capabilities: [person-cli, safe-run]
---

# Commitment Tracking

## Purpose

Track obligations and open loops for people. Commitments are the source of truth for what someone needs to do. Calendar events and reminders are future projections from commitments, not the source of truth.

Commitments are **gateless**: recording one writes it live as `open` (tracked) and is reversible (`done` / `ignore` / `cancel`). There is no accept step. `open` means *tracked*, **not** that the person has agreed to do it.

## Commands

### Record a commitment

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person commitment record \
    --owner fred \
    --text 'Send Graham update by Friday' \
    --source email:gmail-msg-123 \
    --evidence 'Could you send me the update by Friday?' \
    --due 2026-05-29T17:00:00Z"
```

### List commitments

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person commitment list --owner fred --status open"
```

### Resolve / reverse a commitment

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person commitment done fred-commitment-id"        # completed
safe-run ... -- "person commitment ignore <id> --reason '...'"   # decided not to act
safe-run ... -- "person commitment cancel <id> --reason '...'"   # no longer relevant
```

## Rules

1. Incoming email, messages, and conversations should be **recorded** as commitments when they create an obligation. They are written live as `open`.
2. `open` ≠ agreed. Recording a commitment tracks it; it does not imply the person has committed to doing it.
3. Never delete a commitment — resolve it (`done`/`ignore`/`cancel`), which keeps the audit trail.
4. Always include `--source` to trace where the commitment came from (e.g. `email:gmail-msg-<id>`). Re-recording the same `--source` updates the existing commitment instead of duplicating it.
5. Always include `--evidence` with the relevant quote or context.
6. Include `--due` when there is an explicit or implied deadline.
7. Set `--owner` to the person the obligation belongs to (commitments are one shared household store keyed by person, not by privacy scope).
8. A commitment inferred from an **email** is the person's *apparent* obligation, not a confirmed one — say so when you surface it, and confirm before acting on it.

## Status Values

- `open` — tracked (recorded live); not necessarily agreed by the person
- `done` — completed
- `ignored` — person decided not to act
- `cancelled` — no longer relevant
- `proposed` — legacy; no longer written (commitments are recorded `open`)
