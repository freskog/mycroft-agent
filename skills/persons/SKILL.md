---
name: persons
description: Manage household members — list, create, and update people (the person-nodes of the household graph). Personal facts about them live in memory; this skill is identity/metadata.
version: 1.0.0
capabilities: [person, safe-run]
---

# Persons

A **person** is a household member (or pet) — a node in the household graph with a
stable `id`, a display name, a timezone, and a locale. This skill is for the
person records themselves; *facts about* a person (job, birth date, preferences)
are `memory` items, and *links between* people/entities are `relationship`s.

## Ids are lowercase slugs — and `--as` / `--owner` take an id, not a name

A person's `id` is a short lowercase slug: `fred`, `paula`, `liam`. The display
name (`"Fredrik"`, `"Nora 'Pepis'"`) is just a label. Anywhere a command wants a
person — `--owner`, `--person`, `--from`, the REPL's `--as` — pass the **id**,
never the display name. Passing a display name that isn't an id will fail
(there's no person `Fredrik`; there is `fred`).

## Commands

### List

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- "person person list"
```

### Create

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person person create --id liam --display-name 'Liam' --timezone Europe/Dublin --locale en-IE"
```

`--locale` is optional. Pick a fresh lowercase slug for `--id`; reuse the existing
one if the person already exists (don't create a duplicate under a new id).

### Update (existing person — gateless)

Change mutable metadata of someone who already exists. Only the flags you pass are
changed; the rest are left as-is. The `id` is a positional argument.

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person person update fred --timezone Europe/Dublin --locale en-IE"
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person person update fred --display-name 'Fred'"
```

Use this to fix a timezone/locale/name on a seeded or previously-created person —
do **not** try to `create` them again (that fails on the duplicate id), and never
report a metadata change as done unless the command actually succeeded.

## Rules

1. `id` is a lowercase slug and is immutable; you can update display-name/timezone/
   locale but not the id. To "rename", update the display name.
2. Resolve before creating: if someone may already exist, `person person list`
   first and reuse their id.
3. Person *facts* (birth date, role, preferences) go in `memory`; person *links*
   (spouse, parent_of, employed_by) go in `relationship`. Keep identity metadata
   here minimal.
