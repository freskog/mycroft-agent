---
name: plans
description: Maintain a versioned PLANS.md scratchpad in the workspace for an active goal, keeping evidence files alongside.
version: 1.0.0
capabilities: [safe-run]
---

# Plans

## Purpose

A plan is the **replaceable, working scratchpad** for an active goal. It captures the current strategy, decisions, open questions, and next steps. Unlike a goal, a plan is allowed to change — that's the point.

Plans live as files in the workspace, not in the person-service. The goal carries the durable contract; the plan carries the current path.

## Convention

For a goal with id `<goal-id>`:

```
/workspace/goals/<goal-id>/
  PLANS.md           # current plan (latest)
  plans/
    v1.md
    v2.md            # older versions, kept for history
  evidence/          # files cited as completion evidence
    ...
```

## Workflow

### 1. Set up the workspace

```
safe-run --cwd /tmp/workspace --timeout 5 --shell bash -- \
  "mkdir -p /workspace/goals/<goal-id>/plans /workspace/goals/<goal-id>/evidence"
```

### 2. Write the current plan

`PLANS.md` is markdown. A useful skeleton:

```
# Plan for <title>

## Current understanding
<one paragraph: what is the user asking for, what's the constraint>

## Strategy
<3-5 bullets: how we'll get there>

## Next step
<the one concrete action right now>

## Open questions
<things to ask the user or verify>
```

### 3. Snapshot before significant rewrites

Before you materially change strategy, snapshot the current `PLANS.md`:

```
safe-run --cwd /tmp/workspace --timeout 5 --shell bash -- \
  "N=$(ls /workspace/goals/<goal-id>/plans 2>/dev/null | wc -l); cp /workspace/goals/<goal-id>/PLANS.md /workspace/goals/<goal-id>/plans/v$((N+1)).md"
```

Then edit `PLANS.md` freely.

### 4. Drop evidence as you go

Files cited in evidence (commit logs, screenshots, transcripts) live under `evidence/`. Reference them via `person goal evidence` (see the `goals` skill).

## Rules

1. The plan is **not** the contract. Read the goal first; use the plan only to track *how* you're going to satisfy the evidence-rule.
2. Snapshot **before** rewriting, not after. The intent of versioning is to recover the path-you-tried-first.
3. Keep `PLANS.md` short — a working document, not a journal. Move detailed notes into `evidence/` if they matter.
4. Never store credentials, tokens, or PII in the plan or evidence directory. The workspace is sandbox-internal but not encrypted.
5. If the plan diverges far enough from the goal that the original outcome no longer applies, cancel the goal and request a new one (goal creation is gated) — don't try to bend the plan to a different finish line.
