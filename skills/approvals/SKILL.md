---
name: approvals
description: Request human approval for a privileged action (send mail, create a calendar event, anything with outside effect). Propose-only — you never approve or execute.
version: 1.0.0
capabilities: [person-cli, safe-run]
---

# Approvals (Human-in-the-Loop)

## Purpose

Some actions have outside effect — sending an email, creating a calendar event,
spending money, messaging a third party. These must never happen without a
human's explicit consent. The approval mechanism is how you **propose** such an
action and let a human gate it.

Three roles, and you only play the first:

- **You propose** the action (create an approval). That is all you do.
- **A human approves** it, out of band, in their own client.
- **person-service executes** it server-side (it holds the credentials; you never
  do) and records the result.

You **never** approve an action and **never** execute one yourself. Do not poll,
wait, or retry after proposing — the turn ends, and you may be re-invoked later to
continue.

## When to use

Use an approval for any action with an effect outside this system, **and for
creating a goal** (a durable, immutable contract — use the `person goal request`
sugar, which creates a `goal.create` approval; see the `goals` skill). Do **not**
use it for reads, for writing memory / entities / relationships (those are
gateless — write them directly, they're reversible), or for anything you can just
answer.

Registered action types today: `calendar.create_event`, `goal.create` (and
`approval.ping`, a test no-op). The executor for each lives in person-service.

## The decision is gated by a one-time code you never see

When a human decides, their client echoes back a **one-time code** that
person-service issued and delivered only to them (over a private channel / push).
You never have access to that code — it never appears on any surface you can read
(`approval list`/`show`, the event stream). This is *why* you can't approve your
own request even though you can see it exists: deciding requires a secret you don't
hold. Don't look for the code or try to reach the decision endpoint.

## Propose an action

```bash
person approval request \
  --action-type calendar.create_event \
  --payload-json '{"summary":"Team lunch","start":"2026-06-15T12:00:00Z","end":"2026-06-15T13:00:00Z"}' \
  --required-person fred \
  --channel <this conversation's channel> \
  --source email:gmail-msg-456
```

| Flag                   | Meaning                                                                       |
|------------------------|-------------------------------------------------------------------------------|
| `--action-type`        | The action to perform. Determines which executor runs on approval.            |
| `--payload-json`       | The action's parameters. **Frozen at proposal** — the approved payload is the one that executes; you cannot change it later. If the user wants something different, propose a new one. |
| `--required-person`    | Who must approve (optional). Only that person can decide it.                   |
| `--channel`            | The conversation the approval arose from, so the result is surfaced in context.|
| `--source`             | **Provenance** — where this request came from (`email:…`, `chat`, a URL). Always set it when the request derives from untrusted content (an email/web page); it's shown to the human at decision time so they can judge an attacker-originated request. |
| `--continuation-skill` | (Optional, saga) the skill to run once the action executes — see below.       |
| `--continuation-params`| (Optional, saga) JSON params passed to that skill.                            |

After proposing, **tell the user you've requested their approval and stop.** Never
claim the action is done — it is only proposed.

Re-proposing the same action (same type + payload) is idempotent: it returns the
existing pending approval rather than creating a duplicate.

## Multi-step workflows that need approval in the middle (sagas)

There is no pausing. A workflow with a human gate in the middle is **decomposed at
the approval boundary** and resumes from durable state:

1. Do the safe preparation, then **propose** the gated action, attaching a
   continuation: `--continuation-skill <this-or-another-skill>` and
   `--continuation-params '{"phase":"confirm", ...}'`. Then stop.
2. The human approves; person-service executes the action and records its result.
3. The continuation skill is run automatically. It must **rehydrate from durable
   state, not memory** — read the approval's recorded result (the proof the action
   happened), any linked goal, and its `params` (e.g. a `phase`) to know where it
   is. Then continue — proposing the next gated step if there is one.

So a gated workflow is a chain of skill segments linked by approvals. Keep the
pre-gate steps idempotent: a segment may be re-run.

## Inspecting (read-only)

```bash
person approval list --status requested   # what's awaiting a human
person approval show <id>                  # full record incl. result_json once executed
```

## What you must not do

- You **cannot** approve or reject — there is no such command in your CLI, and the
  decision endpoint is on a private network your sandbox can't reach. Deciding is
  the human's act, on their own channel. Don't try to find a way around it.
- Do **not** wait for or poll an approval after proposing.
- Do **not** describe a proposed action as completed.
