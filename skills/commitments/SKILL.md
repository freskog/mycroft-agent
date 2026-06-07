---
name: commitments
description: Propose and list durable obligations (commitments) for people via the person-service.
version: 1.0.0
capabilities: [person-cli, safe-run]
---

# Commitment Tracking

## Purpose

Track obligations and open loops for people. Commitments are the source of truth for what someone needs to do. Calendar events and reminders are future projections from commitments, not the source of truth.

## Commands

### Propose a commitment

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person commitment propose \
    --owner fred \
    --text 'Send Graham update by Friday' \
    --source email:gmail-msg-123 \
    --evidence 'Could you send me the update by Friday?' \
    --due 2026-05-29T17:00:00Z"
```

### List commitments

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person commitment list --owner fred --status proposed"
```

## Rules

1. Incoming email, messages, and conversations should create **proposed** commitments.
2. Never mark a commitment as done or accepted — only humans can do that.
3. Never delete a commitment.
4. Always include `--source` to trace where the commitment came from.
5. Always include `--evidence` with the relevant quote or context.
6. Include `--due` when there is an explicit or implied deadline.
7. Set `--owner` to the person the obligation belongs to (commitments are one shared household store keyed by person, not by privacy scope).
8. Commitments may be proposed — they do not imply the person has agreed to do them.

## Status Values

- `proposed` — suggested by agent, not yet reviewed
- `open` — accepted by person, actively tracked
- `done` — completed
- `ignored` — person decided not to act
- `cancelled` — no longer relevant
