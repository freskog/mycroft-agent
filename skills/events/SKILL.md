---
name: events
description: Episodic event log — record observations, utterances, decisions, and session notes that feed memory consolidation and provenance.
version: 1.0.0
capabilities: [person-cli, safe-run]
---

# Events

## Purpose

The event log is the agent's **append-only memory of what happened**. It complements semantic memory (`memory` skill) by recording raw, time-stamped occurrences. Some events feed consolidation into durable facts; others stay as pure timeline records ("the user said X at T", "I decided Y because Z").

The log lives in the broadened `audit_events` table. Every state mutation in person-service already writes here; the categories below extend it with cognitive entries.

## Categories

| Category       | When to log                                                                                  |
|----------------|----------------------------------------------------------------------------------------------|
| `state`        | State mutations — written automatically by person-service. Do not produce manually.          |
| `observation`  | Something the agent saw or inferred: "Inbox contains a Q3 thread", "Calendar is empty Fri".  |
| `utterance`    | User speech worth preserving verbatim. Quote the user; do not paraphrase.                    |
| `decision`     | Branch points where the agent committed to an approach. Used to explain later actions.       |
| `session_note` | Cognitive notes you want consolidation to consider as candidate facts.                       |

`observation` and `session_note` events feed `person memory consolidate`. `utterance` and `decision` are for the timeline and don't auto-consolidate.

## Commands

### Record an event

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person event record \
    --actor agent \
    --action note.preference \
    --category session_note \
    --text 'Fred mentioned again that morning meetings work better' \
    --payload-json '{\"messageId\":\"gmail-msg-456\"}'"
```

`--actor` defaults to `agent`. `--action` is a free-form snake-case label for the event type; pick something stable so later queries can filter. Set `--source` to where the event came from (e.g. `email:gmail-msg-<id>`, `chat`) — it is first-class provenance and decides the trust of any belief consolidation derives from this event (an `email:`/`web:` source ⇒ unverified `external-content`).

### List recent events

```
person event log --category session_note --since 2026-05-25T00:00:00Z --limit 50
```

### Search events

```
person event search "morning meetings" --since 2026-05-01T00:00:00Z
```

FTS5 over `text` and `payload_json`. Useful for "where did the user say X" timeline questions.

## Rules

1. Log liberally — events are cheap. Stage observations as `session_note` so consolidation can decide whether they're durable.
2. Categories carry semantics: state events come from person-service automatically; never write `state` events from the CLI.
3. Keep `text` short and self-contained (one sentence). Put structured detail in `payload-json`.
4. Don't paraphrase the user in `utterance` events; quote.
5. Use stable `action` names (`note.preference`, `obs.calendar.empty`, `decision.skip-email`) so timelines stay queryable. Avoid putting unique ids in the action — those go in `target-id` or the payload.
6. Don't log credentials, raw passwords, or PII you wouldn't want recalled.

## Provenance flow

```
event (observation / session_note, with --source)
   └── memory consolidate
        └── memory_item (accepted live, with originEventId + trust derived from --source)
             └── context bundle (email/web-sourced items are flagged "⚠ unverified")
```

Consolidation is gateless — there is no human accept step. An event's `--source`
sets the resulting belief's trust: an `email:`/`web:` source makes it
`external-content` (usable for reasoning, surfaced flagged); anything else is
`agent-inference`. When the agent later wonders *why do I believe this*, it follows
`originEventId` from the memory item back to the originating event.
