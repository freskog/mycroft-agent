---
name: memory
description: Semantic memory — propose, supersede, accept/reject/archive durable facts about people, projects, and preferences, with point-in-time recall.
version: 1.0.0
capabilities: [person-cli, safe-run]
---

# Memory

## Purpose

Semantic memory is the agent's evolving store of **durable facts** — preferences, project notes, role/relationship details, procedure knowledge. Facts can be added, accepted, superseded, archived, and recalled. Each fact's `outcome`-equivalent is its `text`; it carries optional temporal validity and links back to the event that produced it.

Memory is **not** the conversation log. For raw "this happened" content, use the `events` skill — the consolidator turns relevant events into proposed memory items.

## Anatomy

| Field             | Meaning                                                                              |
|-------------------|--------------------------------------------------------------------------------------|
| `id`              | Stable identifier                                                                    |
| `personId`        | Subject of the fact (about whom). Optional for scope-wide facts.                     |
| `scopeId`         | Privacy partition: `fred_private`, `fred_work`, `family_shared`, etc.                |
| `kind`            | `preference` / `fact` / `project_note` / `procedure_note`                            |
| `text`            | The fact itself, in prose                                                            |
| `status`          | `proposed` → `accepted`/`rejected` → `archived`                                      |
| `confidence`      | 0..1 quality estimate. Defaults to 0.5 when consolidating from events.               |
| `validFrom`       | World-time start (optional). Use when a fact holds over a period.                    |
| `validUntil`      | World-time end (optional, exclusive).                                                |
| `supersededById`  | Set when a newer item replaces this one. Old item is preserved.                      |
| `originEventId`   | The event in the audit/episodic log that produced this fact.                         |

`proposed` and `rejected` are not in normal recall results. Only `accepted` facts (that aren't superseded at the query's `as-of` time) show up in `search` and `context`.

## Commands

### Propose a fact

For most cases prefer logging a `session_note` event and letting the consolidator propose the memory item. Propose directly only when the user is explicitly stating a durable fact.

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person memory propose \
    --person fred \
    --scope fred_work \
    --kind preference \
    --text 'Prefers morning meetings to afternoon ones' \
    --source 'chat:2026-06-01'"
```

### Conflict-check before proposing

```
person memory conflicts --scope fred_work --person fred \
  --kind preference --text 'morning meetings'
```

Returns accepted items with matching kind and overlapping text. If something close already exists, prefer `supersede` over a fresh proposal.

### Lifecycle transitions

```
person memory accept    <id>
person memory reject    <id> --reason "user said no, they meant the opposite"
person memory archive   <id>
person memory supersede --new <new-id> --old <old-id>
```

Only humans should typically accept; the agent can reject its own proposals when it realises they're wrong before review.

### Search

```
person memory search "morning meetings" \
  --scope fred_work --kind preference --as-of 2026-06-01T00:00:00Z --limit 5
```

Returns ranked accepted facts current at `as-of`. Sorted by `confidence × recency`.

### Context bundle (harness injection)

```
person memory context --scope fred_work --person fred --fact-limit 10 --event-limit 5
```

Returns `{facts: [...], events: [...]}` — top facts plus recent observation/decision/session_note events. Use this once per session, not per turn, unless context drifted.

### Consolidate

```
person memory consolidate --scope fred_work --since 2026-05-25T00:00:00Z
```

Reads `observation` and `session_note` events since the cutoff and proposes one `memory_item` per event with `originEventId` set. Idempotent — events with an existing referencing memory item are skipped. New items are `proposed`; humans accept.

## Rules

1. Prefer `session_note` events for raw observations. Propose `memory_item` directly only when the user explicitly states a durable fact.
2. Run `memory conflicts` before proposing anything subject-specific. If there's a near-match, supersede instead of creating a parallel fact.
3. Never edit the `text` of an existing fact. Supersede with a new one; the old one is preserved with the link.
4. Set `validFrom`/`validUntil` for facts that bind to world-time (employment, addresses, relationships). Leave them null for timeless preferences and traits.
5. Never store credentials, tokens, or PII you wouldn't want re-injected as context. Memory is reused by future agent turns.
6. Memory recall respects scopes. Don't fish across scopes hoping to find something.
