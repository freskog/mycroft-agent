# person-service reference

This reference accompanies the `person-service` skill. The skill body covers when and how to interact with the trusted sidecar; this file lists the available primitives so the agent only loads it when actually composing a call.

## Memory kinds

- `preference` — how the person likes things done
- `fact` — factual information about the person or their world
- `project_note` — context about ongoing work
- `procedure_note` — how to do a specific recurring task

## Household graph

There are **no privacy scopes** — durable state is one shared household store keyed by `person` (and, for graph nodes, `entity`).

**Entity kinds** (`person entity propose --kind …`):
- `organization` — employer, company, agency
- `school` — nursery/primary/secondary/university
- `club` — sports/social/activity group
- `medical` — GP, dentist, clinic, hospital
- `vehicle` — a car or other vehicle
- `place` — a home, venue, recurring location
- `other` — anything that doesn't fit above

**Relationship types** (`person relationship propose --type …`):
- person↔person: `spouse`, `parent_of`, `child_of`
- person↔entity: `employed_by`, `attends`, `patient_of`, `member_of`, `owns`, `lives_in`

`--from-kind`/`--to-kind` are `person` or `entity`. Edges are time-bound (`--valid-from`/`--valid-until`) and supersedable, so job/school changes are recorded as transitions (close the old, open the new) rather than overwrites. `person household` returns the accepted, currently-active graph.

## Policy boundaries

The agent **can**:
- Propose commitments
- List commitments
- Propose memory; supersede; reject its own proposals before review
- Search and recall memory; pull a context bundle
- Log events (observation, utterance, decision, session_note)
- Consolidate session-note events into proposed memory items
- Propose goals (see the `goals` skill)
- Append goal evidence
- Transition goal status
- Request approval
- Propose entities and relationships; supersede them; reject its own proposals before review

The agent **cannot**:
- Accept memory items, entities, or relationships (only humans accept)
- Accept or mark commitments done
- Delete commitments, memory, events, goals, entities, or relationships
- Edit the `text` of an existing memory item, or an entity/relationship in place — supersede instead
- Edit a goal's `outcome` or `evidence_rule` after creation
- Write `state` category events directly (those come from service mutations)
- Write calendar events
- Send email
- Mutate credentials

## Inbox / email (read-only)

Gmail is ingested by the sidecar; the agent reads it but never holds the OAuth
token and cannot send or modify mail.

- `person gmail sync --owner <p>` — pull recent messages into the inbox.
- `person inbox list --owner <p> [--status pending|triaged|skipped] [--limit N] [--order asc|desc]`
- `person inbox show <inbox-id>` — one message: body, headers, and an
  `attachments` array (`attachmentId`, `filename`, `mimeType`, `sizeBytes` —
  metadata only, no bytes).
- `person inbox download <inbox-id> --out <dir> [--attachment <attachmentId>]` —
  fetch attachment bytes on demand and write them under `<dir>` (all attachments,
  or just one). Prints the paths written; open them afterwards with `safe-run`.
  Only download when the task actually needs the file's contents.
- `person inbox mark-triaged <inbox-id>` / `person inbox skip <inbox-id>` — close
  out a message after acting on it.

Triage workflow (classify → propose → mark) lives in the `inbox-triage` skill.

## Calendar (read-only)

Reuses the same Google authorization as Gmail (one consent grants both
`gmail.readonly` and `calendar.readonly`).

- `person calendar agenda --owner <p> [--days N] [--from <ISO>] [--to <ISO>]` —
  live agenda from the owner's primary calendar (default: next 14 days). Returns
  events with `summary`, `start`, `end`, `allDay`, `location`, `htmlLink`,
  `status`. Read-only — there is no command to create/move/delete events.

See the `calendar` skill for usage detail.

## Event categories

- `state` — service-managed state mutations (commitments, goals, memory lifecycle, approvals)
- `observation` — something the agent saw or inferred
- `utterance` — user speech worth preserving verbatim
- `decision` — agent branch points worth tracing
- `session_note` — cognitive notes intended for memory consolidation
