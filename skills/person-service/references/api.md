# person-service reference

This reference accompanies the `person-service` skill. The skill body covers when and how to interact with the trusted sidecar; this file lists the available primitives so the agent only loads it when actually composing a call.

## Memory kinds

- `preference` — how the person likes things done
- `fact` — factual information about the person or their world
- `project_note` — context about ongoing work
- `procedure_note` — how to do a specific recurring task

## Seeded scopes

- `fred_private` — Fred's personal items
- `fred_work` — Fred's work items
- `paula_private` — Paula's personal items
- `family_shared` — Shared family items

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

The agent **cannot**:
- Accept memory items (only humans accept)
- Accept or mark commitments done
- Delete commitments, memory, events, or goals
- Edit the `text` of an existing memory item — supersede instead
- Edit a goal's `outcome` or `evidence_rule` after creation
- Write `state` category events directly (those come from service mutations)
- Write calendar events
- Send email
- Mutate credentials

## Event categories

- `state` — service-managed state mutations (commitments, goals, memory lifecycle, approvals)
- `observation` — something the agent saw or inferred
- `utterance` — user speech worth preserving verbatim
- `decision` — agent branch points worth tracing
- `session_note` — cognitive notes intended for memory consolidation
