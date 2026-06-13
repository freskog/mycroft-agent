---
name: goals
description: "PARKED — not currently in use. Goals model an autonomous multi-step agent objective (outcome + evidence rule), not user-facing tracking. Do not route user tracking here; use events (calendar) or todos (commitments). Reintroduced only when a concrete autonomous workflow needs it."
version: 1.1.0
capabilities: [person, safe-run]
---

# Goals

> **⚠ PARKED — do not invoke for user tracking.**
> Goals are an *autonomous multi-step agent* construct: a durable, gated contract
> (`outcome` + `evidence_rule`) the agent works toward and proves done. They are the
> wrong shape for tracking what a person needs to do or attend — that's **todos
> (commitments → Google Tasks)** and **events (calendar)**. There is **no live
> autonomous workflow** using goals today, so triage and the user-facing skills do
> **not** create them. The schema, endpoints, and `goal.create` approval type remain
> in place, dormant; this skill is kept only as the design reference for when an
> autonomous-task use case actually arrives. Until then, ignore it.

## Purpose

A goal is a **durable completion contract**. It survives replanning, context compaction, and even restarting the agent. Goals live in the trusted person-service, not in your context window.

Use a goal when:
- the finish line is clear but the path is uncertain
- the work spans multiple sessions or days
- the user needs to be able to see progress without re-explaining the request

Do **not** use a goal for one-shot questions, single-command tasks, or anything you can finish before the next message.

## Anatomy

| Field           | Meaning                                                                                          |
|-----------------|--------------------------------------------------------------------------------------------------|
| `title`         | Short label, e.g. "Approve Q3 report"                                                            |
| `outcome`       | What "done" looks like in observable terms. Frozen at creation. **Cannot be edited later.**      |
| `evidence-rule` | How completion is verified. Frozen at creation. **Cannot be edited later.**                     |
| `status`        | `open` → `blocked` ⇄ `open` → `done` or `cancelled`                                              |
| `evidence`      | Appended over the goal's life: file paths, commitment ids, audit refs                            |
| `source`        | Provenance (email id, conversation, etc.)                                                        |

The immutability of `outcome` and `evidence-rule` is by design: it prevents environmental text or replanning from quietly redefining the contract. If the user actually wants a different outcome, cancel the goal and request a new one (goal creation is gated).

## Commands

### Request a goal (gated — you cannot create one directly)

Goal creation is **hard-gated**: unlike memory/entities (gateless, cheap,
reversible), a wrongly-derived goal sets a durable, immutable contract you'll
pursue across sessions, so a human must approve it first. You **request** it; the
goal does not exist until they approve. There is no `person goal propose`.

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person goal request \
    --owner fred \
    --title 'Approve Q3 report' \
    --outcome 'Q3 report committed to main branch and acknowledged by stakeholders' \
    --evidence-rule 'Git commit hash on main + stakeholder confirmation email' \
    --due 2026-06-30T17:00:00Z \
    --source email:gmail-msg-456"
```

You do **not** need `--channel` — it is set automatically to the current
conversation so the approval result comes back to the right place.

`--due` is **optional** — a target date for "done by …". It is a full **ISO-8601
instant** (`2026-06-30T17:00:00Z`), like commitments' `--due`; resolve a phrase
like "by Friday" against the clock line in your prompt (use a python3 snippet for
the date math rather than guessing), and never invent other flags — `--owner`,
`--title`, `--outcome`, `--evidence-rule`, `--due`, `--source`, `--required-person`,
`--channel` are the only ones. Unlike `outcome`/`evidence-rule`, the due date is
mutable (re-request to change it).

This creates a `goal.create` approval (see the `approvals` skill). Tell the user
you've requested the goal and **stop** — don't wait or poll. A human approves out
of band (inline in chat, or via a push notification); person-service then creates
the goal. You'll be re-invoked if a continuation was attached. You never approve
your own goal, and you can't create one without approval.

### List goals

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person goal list --owner fred --status open"
```

### Show a goal (with evidence)

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person goal show <goal-id>"
```

### Append evidence

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person goal evidence <goal-id> --kind file --ref /workspace/goals/<goal-id>/evidence/commit.txt --note 'merged to main'"
```

`kind` values: `file`, `commitment`, `note`, `audit`.

### Change a goal's status / cancel it (these are the ONLY verbs — do not probe `--help`)

To **cancel** a goal, use the `cancel` shortcut (preferred — simplest):
```
person goal cancel <goal-id> [--reason '...']
```

For any other transition, use `status --to <state>` (note: `--to`, and the id is a
**positional argument** — `person goal status <goal-id> --to <state>`):
```
person goal status <goal-id> --to blocked --reason 'waiting on stakeholder reply'
person goal status <goal-id> --to open
person goal status <goal-id> --to done
person goal status <goal-id> --to cancelled    # same as `goal cancel`
```

There is **no** `goal close`, `goal archive`, `goal abandon`, `goal reject`, or
`goal update`, and the flag is `--to`, not `--status`. Valid states: `open`,
`blocked`, `done`, `cancelled`. If you're unsure of a goal command, run
`skill show goals` — don't spelunk through `--help`.

## Rules

0. **Goal creation is gated — `person goal request`, never create directly.** A human approves before the goal exists; you can't approve your own. (Status/evidence updates below are *not* gated — only creation is.)
1. Outcome must be **observable**, not vague. "Reviewed" is bad; "PR merged to main with two approvals" is good.
2. Evidence-rule must be **testable**. Cite a concrete artifact (commit hash, email id, file path) the human can verify.
3. Never edit a goal's `outcome` or `evidence-rule` — the service rejects this, and the audit log would catch it anyway. Cancel and request a new goal instead.
4. Mark a goal `done` only when the evidence-rule is actually satisfied. If you're not sure, leave it `open` or `blocked` and append a note.
5. Use the `plans` skill for the working scratchpad. The goal is the contract; the plan is the path.
6. When the user expresses an obligation in passing (not a multi-step goal), use the `commitments` skill instead.
