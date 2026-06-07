---
name: goals
description: Propose, track, and resolve durable goals — the user-controlled completion contract that survives replanning.
version: 1.0.0
capabilities: [person-cli, safe-run]
---

# Goals

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

The immutability of `outcome` and `evidence-rule` is by design: it prevents environmental text or replanning from quietly redefining the contract. If the user actually wants a different outcome, cancel the goal and propose a new one.

## Commands

### Propose a goal

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person goal propose \
    --owner fred \
    --title 'Approve Q3 report' \
    --outcome 'Q3 report committed to main branch and acknowledged by stakeholders' \
    --evidence-rule 'Git commit hash on main + stakeholder confirmation email' \
    --source email:gmail-msg-456"
```

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

### Update status

```
person goal status <goal-id> --to blocked --reason 'waiting on stakeholder reply'
person goal status <goal-id> --to open
person goal status <goal-id> --to done
person goal status <goal-id> --to cancelled
```

## Rules

1. Outcome must be **observable**, not vague. "Reviewed" is bad; "PR merged to main with two approvals" is good.
2. Evidence-rule must be **testable**. Cite a concrete artifact (commit hash, email id, file path) the human can verify.
3. Never edit a goal's `outcome` or `evidence-rule` — the service rejects this, and the audit log would catch it anyway. Cancel and propose a new goal instead.
4. Mark a goal `done` only when the evidence-rule is actually satisfied. If you're not sure, leave it `open` or `blocked` and append a note.
5. Use the `plans` skill for the working scratchpad. The goal is the contract; the plan is the path.
6. When the user expresses an obligation in passing (not a multi-step goal), use the `commitments` skill instead.
