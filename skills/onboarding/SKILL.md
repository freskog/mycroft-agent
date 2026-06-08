---
name: onboarding
description: Interview the user to build (or refresh) the household graph — the family members, the entities they relate to (employers, schools, clubs, GPs, vehicles), typed relationships, and pinned profile facts. Run me when asked to "onboard", "set up my profile/household", or when the household graph is empty.
version: 1.0.0
capabilities: [person, safe_run]
---

# Onboarding

## Purpose

Populate the **household graph** so every later turn (and skills like
`inbox-triage`) has rich personal context: who the family members are, the
organisations/schools/clubs/medical practices/vehicles they relate to, the typed
relationships between them, and a handful of pinned profile facts. Everything you
create is **proposed** — a human accepts before it becomes durable.

There are no privacy scopes; this is one shared household store keyed by `person`
and `entity`. Read the `memory` and `person-service` skills for the underlying
commands and the belief-revision rules; this skill is the **interview playbook**.

## When to run

- The user explicitly asks (`/onboard`, "set up my household", "update my profile").
- The household graph / profile is empty (the system prompt shows the gentle
  nudge instead of a profile). Offer onboarding; never force it or block the
  user's actual request.

Pick the mode:
- **Initial** — graph is empty. Run the full interview.
- **Diff / refresh** — graph exists. Confirm what's there, ask only what's
  changed, and record changes as **transitions** (not overwrites).

Always start by reading current state so you don't re-ask what you already know:
```
person household
person memory profile --limit 100
```

## What to capture

Interview adaptively — ask in natural batches, don't interrogate. Aim to capture:

- **People**: each family member (adults, children, relevant extended family) —
  created as person-nodes with `person person create` (see Commands). Capture
  who is a parent/child/spouse as relationships.
- **Entities** the people relate to:
  - `organization` — employers, agencies
  - `school` — nursery/primary/secondary/university
  - `club` — sports/social/activity groups
  - `medical` — GP, dentist, clinic
  - `vehicle` — cars
  - `place` — home, recurring venues
- **Relationships** (typed, time-bound edges):
  - person↔person: `spouse`, `parent_of`, `child_of`
  - person↔entity: `employed_by`, `attends`, `patient_of`, `member_of`, `owns`, `lives_in`
- **Profile facts** worth pinning (preferences, constraints, defaults) — store
  these as memory with `--source onboarding:<topic>` so they inject every turn
  without decay.

Capture the enabling facts for future coordination too: who the adults are, that
children need supervision, default childcare/coverage, and shared resources (one
car, etc.). Don't design a calendar here — just record the facts.

## Order of operations

Work in this order so ids exist before they're referenced — don't go exploring:

1. `person person list` and `person household` — see what already exists.
2. **Create the people** with `person person create` (reuse existing ids like
   `fred`/`paula`; pick lowercase slugs for new ones). Note each id.
3. **Propose the entities** (`person entity propose`); note the returned ids.
4. **Propose the relationships** (`person relationship propose`) wiring the
   person/entity ids together (`spouse`, `parent_of`, `employed_by`, `attends`…).
5. **Propose pinned facts** (`person memory propose --source onboarding:<topic>`),
   each `--person <id>` referencing a person you created in step 2.
6. Log a `session_note` event.
7. Summarise and **ask the user to accept** the proposals. When they approve,
   run `person accept-all --source onboarding` (see "Accepting").

Capture in batches; you don't need every detail before you start proposing.

## Prefer derivable facts over snapshots

Store the **stable** fact and let the clock derive the rest:
- birth-date, **not** "age 7"
- school-start year, **not** "Year 3"
- employment `valid-from`, **not** "started recently"

This way nothing silently goes stale between sessions.

## Commands

These are the only commands you need — they're listed here so you do **not** have
to explore `--help` (its output is long and colour-coded; reading it repeatedly
wastes your turn). Run them via `safe_run`. Use the exact flags below.

People are graph **person-nodes**. Create them directly (they're structural
identities, not proposals); use a **lowercase slug** as the id:
```
person person list                    # who already exists (check before creating)
person person create --id liam --display-name "Liam" --timezone Europe/Dublin --locale en-IE
```
Reuse an existing id (e.g. seeded `fred`, `paula`) rather than creating a duplicate.
`--timezone` is required — reuse the primary user's zone for family members unless
told otherwise. There is no "person" entity kind; family members are person-nodes,
not entities.

Entities (employers, schools, clubs, GPs, vehicles, places — a **fixed** set of
kinds: `organization` / `school` / `club` / `medical` / `vehicle` / `place` /
`other`; do not invent others):
```
person entity propose --kind school --name 'Oakwood Primary' \
  --attributes-json '{"phase":"primary"}' --source onboarding:children
person entity resolve 'oakwood'       # before proposing, check for an existing node
```

Relationships (use the entity/person ids):
```
person relationship propose \
  --from <child-person-id> --from-kind person \
  --type attends \
  --to <school-entity-id> --to-kind entity \
  --source onboarding:children \
  --valid-from 2024-09-01T00:00:00Z
```

Pinned profile facts:
```
person memory propose --person fred --kind fact \
  --text 'Lives in London; default childcare is the maternal grandparents on Tuesdays' \
  --source onboarding:household
```

Log a session note so the interview itself is traceable / consolidatable:
```
person event record --action onboarding.session --category session_note \
  --text 'Initial onboarding: captured 2 adults, 2 children, employer + school'
```

## Accepting (when the user approves)

Everything you proposed sits in **`proposed`** status (the status value is
literally `proposed`, not "pending"). Profile (`person memory profile`) and
`person household` show **accepted** items only, so proposals won't appear there
until accepted. To review and accept:

```
person pending --source onboarding              # list ALL proposed memory/entities/relationships (optionally by source prefix)
person accept-all --source onboarding           # accept everything proposed under that source in one call
```

`--source` is a prefix filter, so `onboarding` matches `onboarding:children`,
`onboarding:work`, etc. Omit it to act on every proposal. When the user says
"looks good / approved", run `person accept-all --source onboarding` — do **not**
go exploring `--help`. To accept individually instead, use the per-type verbs
with the id from `person pending`:
```
person memory accept <id>
person entity accept <id>
person relationship accept <id>
```

## Refresh / diff mode (job change, new grade, new club, move)

A change is a **transition**, not an overwrite — preserve history so `--as-of`
queries stay correct. For each change:

1. **Resolve** the node first (`person entity resolve`, the injected graph). Reuse
   the existing node; only propose a new one when it's genuinely new. Never
   auto-merge an ambiguous match — attach to the best and flag, or ask.
2. **Find the prior edge** (`person relationship list --from <id> --type <t>`).
3. **Close + open**: supersede/close the old edge with `--valid-until <cutover>`
   and propose the new edge with `--valid-from <cutover>`. For a superseding node,
   `person entity supersede --new <new-id> --old <old-id>`.
4. For profile facts, `person memory conflicts` then `supersede` rather than
   editing text.

(See the belief-revision section of the `memory` skill for the full rules.)

## Rules

1. Everything you create is **proposed** (status value `proposed`). End the
   session by asking the user to review/accept — only humans accept. On approval,
   `person accept-all --source onboarding`; review with `person pending`.
2. Resolve before you create. Don't duplicate an existing person/entity; don't
   auto-merge ambiguous matches.
3. Prefer derivable facts (dates) over snapshots (ages, grades).
4. Record changes as transitions (`valid-until` on the old, `valid-from` on the
   new); never silently overwrite or delete history.
5. Tag pinned profile facts with `--source onboarding:<topic>` so they inject
   every turn without decay.
6. Never store credentials, tokens, or sensitive data you wouldn't want
   re-injected as context.
7. Keep it conversational and incremental. Capture what the user offers, propose
   it, and let them fill gaps over time — don't block on a complete profile.
