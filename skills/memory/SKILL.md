---
name: memory
description: Write and consolidate durable facts — propose, supersede, accept/reject/archive memory about people, projects, and preferences. Recall is auto-injected each turn; this skill is for the WRITE side.
version: 1.1.0
capabilities: [person, safe_run]
---

# Memory

## Purpose

Semantic memory is the agent's evolving store of **durable facts** — preferences, project notes, role/relationship details, procedure knowledge. This skill covers the **write side**: proposing, superseding, accepting/rejecting/archiving, and consolidating facts.

**Recall is not your job.** The harness auto-injects the relevant memory (one global, relevance-ranked context bundle plus a task-relevant search) into your context every turn — you never choose "memory vs the task." The `search` / `context` commands below exist only for the rare case where you need to look something up beyond what was injected.

There are no privacy scopes — this is one shared household store keyed by `person` and (for graph nodes) `entity`. Onboarding-sourced profile facts (`source` starting `onboarding:`) are **pinned**: they are injected every turn without recency decay, so prefer them for stable household facts. See the `onboarding` skill for the household graph (persons, entities, relationships).

Memory is **not** the conversation log. For raw "this happened" content, use the `events` skill — the consolidator turns relevant events into proposed memory items.

## Anatomy

| Field             | Meaning                                                                              |
|-------------------|--------------------------------------------------------------------------------------|
| `id`              | Stable identifier                                                                    |
| `personId`        | Subject of the fact (about whom). Optional for household-wide facts.                 |
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
    --kind preference \
    --text 'Prefers morning meetings to afternoon ones' \
    --source 'chat:2026-06-01'"
```

### Conflict-check before proposing

```
person memory conflicts --person fred \
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

### Recall (only if needed — normally auto-injected)

Recall is injected for you each turn, so you rarely call these. Reach for them
only to look beyond what was injected:

```
person memory search "morning meetings" --person fred --kind preference --limit 5
person memory context --person fred
person memory profile --limit 50   # pinned onboarding facts only, no decay
```

`search` returns ranked accepted facts current at `as-of` (sorted by
`confidence × recency`); `context` returns the same bundle the harness injects.

### Consolidate

```
person memory consolidate --since 2026-05-25T00:00:00Z
```

Reads `observation` and `session_note` events since the cutoff and proposes one `memory_item` per event with `originEventId` set. Idempotent — events with an existing referencing memory item are skipped. New items are `proposed`; humans accept.

## Rules

1. Prefer `session_note` events for raw observations. Propose `memory_item` directly only when the user explicitly states a durable fact.
2. Run `memory conflicts` before proposing anything subject-specific. If there's a near-match, supersede instead of creating a parallel fact.
3. Never edit the `text` of an existing fact. Supersede with a new one; the old one is preserved with the link.
4. Set `validFrom`/`validUntil` for facts that bind to world-time (employment, addresses, relationships). Leave them null for timeless preferences and traits.
5. Never store credentials, tokens, or PII you wouldn't want re-injected as context. Memory is reused by future agent turns.
6. **Prefer derivable facts over snapshots.** Store the birth-date, not "age 7"; the school-start year, not "Year 3". The clock line in your prompt lets you derive the current value, so the fact never silently goes stale.

## Belief revision — new facts are evidence, not ground truth

A new fact (from an email, a chat, an observation) **proposes** a revision; it never overwrites the graph or an accepted fact in place. Before you propose:

1. **Resolve the subject.** Match the person/entity against existing nodes (`person entity resolve <name>`, the injected household graph). Unknown → propose a new node at low confidence. Genuinely ambiguous → do **not** auto-merge (the one truly destructive error); attach to the best match and flag it, or ask.
2. **Find the prior belief.** Run `memory conflicts` (and, for graph edges, `relationship list --from <id> --type <t>`).
   - Nothing there → additive: propose the new fact/edge with `validFrom`.
   - A different value for the same slot → a **transition**, not a contradiction: set `validUntil` on the old (close it) and propose the new with `validFrom`. History is preserved and `--as-of` stays correct.
   - Same value already there → idempotent; just refine `validFrom`/confidence if needed.
3. **Attach provenance + confidence.** Use the originating email/chat as `--source` and a single-source confidence; let the consolidator/origin event link the observation.
4. **Propose → human accepts.** The old accepted belief wins recall until acceptance. A future-dated `validFrom` is remembered now but only becomes active on its date.

Only flag (don't auto-apply) a genuine **same-time-window** contradiction against a high-confidence or human-stated fact.
