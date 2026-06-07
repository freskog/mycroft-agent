---
name: inbox-triage
description: Triage pending inbox messages (synced from Gmail) into commitments, goals, and observations, then mark each message triaged. Run me when asked to triage email or clear the inbox.
version: 2.1.0
capabilities: [person, safe_run]
---

# Inbox Triage

## Purpose

Process the oldest pending inbox messages for an owner: classify each, propose
the right durable state (commitment / goal / observation), and mark the message
triaged. You run as an isolated sub-task; finish with a short markdown summary of
the action taken per message — that summary is all the caller sees.

## Inputs

The task names the `owner` and a `limit` (how many of the **oldest pending**
messages to process). Default to `owner=fred`, `limit=5` if unspecified.

For every proposal, use the email's id as the source:
`--source email:gmail-msg-<externalId>`. When you extract more than one item from
the same email, append a distinct suffix per item
(`email:gmail-msg-<externalId>#<slug>`) so each gets its own durable record.

## Workflow

1. Sync, then load the oldest pending messages (process oldest first):
   ```
   person gmail sync --owner <owner>
   person inbox list --owner <owner> --status pending --limit <limit> --order asc
   ```
2. For each message (oldest → newest), **read it before classifying**. If the
   list preview is truncated, or the message is long / bulk / from a trusted
   institution (school, childcare, club, doctor, government, employer), open the
   full body with `person inbox show <inbox-id>` first. One email can produce
   **several** proposals — extract every distinct action and date, not just one.
   Resolve senders and names against the **household graph** (injected in your
   prompt; `person household` / `person entity resolve <name>` for more) — this is
   how you judge relevance and attribution: which person an email is about, whether
   a domain belongs to a known employer, whether "Emma" is a child, which school a
   newsletter comes from. An item that maps to a known person/entity is almost
   always relevant; an unrecognised commercial sender with no action usually is not.
3. After acting on a message, mark its inbox row:
   - extracted anything / recorded → `person inbox mark-triaged <inbox-id>`
   - genuine noise (nothing to keep) → `person inbox skip <inbox-id>`
4. Stop when every fetched message has been marked. Summarise.

## Classify by content, not by form

The **shape** of an email (newsletter, automated digest, bulk send) is NOT the
signal — the **content** is. A school/childcare newsletter or a club digest looks
like noise but routinely buries real obligations: permission slips to sign and
return, fees to pay, exam dates, deadlines, events to attend, forms to complete.
Read these in full and mine them. Only skip an email when, after reading it, there
is truly nothing the owner needs to do, attend, prepare for, or remember.

When one email contains multiple items, propose one record per item and give each
a **distinct source suffix** so server-side dedup doesn't collapse them:
`--source email:gmail-msg-<externalId>#<slug>` (e.g. `#permission-slip`,
`#exam-maths`, `#bake-sale`). Re-running triage with the same suffixes updates the
same records instead of duplicating.

## Check the calendar for dated items

When an email references a specific date or event (an exam, parents' evening, a
deadline), pull the owner's agenda once to ground it:
```
person calendar agenda --owner <owner> --days 60
```
Use it to (a) note in your summary if the item is **already on the calendar** (so
you don't imply it's unscheduled), and (b) flag conflicts ("this clashes with X").
You still record the commitment/observation as normal — the calendar is read-only
context, not a substitute for durable state. See the `calendar` skill for detail.

## Decision tree (apply per extracted item)

| Signal in the item | Action |
|-----------------------|--------|
| An action the owner must take, with a deadline ("return the slip by Friday", "pay fees by the 30th") | `person commitment propose` (quote the ask as `--evidence`, infer `--due`) |
| A dated event to attend / be aware of (parents' evening, exam, sports day, school closure) | `person event record --category observation` with the date in the note (or a commitment if the owner must actively prepare/respond) |
| Multi-step durable outcome ("prepare and approve the Q3 report") | `person goal propose` with an observable `--outcome` + testable `--evidence-rule` |
| Preference / context worth remembering long-term | `person event record --category observation` |
| Genuinely actionable nothing — marketing, promotions, social/login notifications, receipts with no future action | `person inbox skip <inbox-id>` |

## Dedup is automatic — do NOT pre-check

Proposing by source is **idempotent server-side**: re-proposing the same
`--source email:gmail-msg-<id>` updates the existing item instead of creating a
duplicate. So you never need to run `person commitment list` / `person goal list`
to dedup first — just propose. This keeps the loop short.

## Examples

Commitment (single obligation):
```
person commitment propose --owner <owner> \
  --text 'Reply to Sarah about the Q3 budget' \
  --source email:gmail-msg-456 \
  --evidence 'Can you review and get back to me by EOD?' \
  --due 2026-05-25T17:00:00Z
```

Goal (multi-step contract):
```
person goal propose --owner <owner> \
  --title 'Approve Q3 report' \
  --outcome 'Q3 report committed to main and acknowledged by stakeholders' \
  --evidence-rule 'Git commit on main + stakeholder confirmation email' \
  --source email:gmail-msg-456
```

Mark triaged after acting:
```
person inbox mark-triaged <inbox-id>
```

If a message body was truncated in the list and you need the full text, use
`runlog` on the `person inbox list` run, or `person inbox show <inbox-id>` for a
single message.

## Attachments

`person inbox show <inbox-id>` (and `inbox list`) include an `attachments` array
with each attachment's `attachmentId`, `filename`, `mimeType`, and `sizeBytes` —
metadata only, no bytes. To read the actual file, download it to disk first, then
open it with `safe_run`:
```
person inbox download <inbox-id> --out /tmp/attachments
# or a single one:
person inbox download <inbox-id> --out /tmp/attachments --attachment <attachmentId>
```
This writes each attachment under the `--out` directory and prints the paths
written. Only download when the task actually needs the file's contents.

## Rules

1. Never send email, create calendar events, or modify Gmail — person-service is
   read-only ingestion and your writes are propose-only.
2. Read before you classify. Never skip an email based on its format
   (newsletter / digest / automated) — only skip after confirming the content has
   no action, date, or fact worth keeping for the owner.
3. Extract every distinct item from an email. Requests/deadlines → commitment;
   multi-step outcomes → goal; dated events and facts to remember → observation.
4. Use `--source email:gmail-msg-<externalId>` (with a `#<slug>` suffix per item
   when an email yields several) so dedup works.
5. Infer deadlines from phrases like "by Friday", "EOD", "next week", "return by",
   resolving them against the current date in the system prompt. If a referenced
   date has **already passed**, don't propose a commitment with a past due —
   record it as an `observation` noting it has passed (or skip if now irrelevant).
6. Check attachments — a permission slip, form, or invoice carrying the real
   action is often a PDF. Note it; download with `person inbox download` only if
   you must read its contents to extract the action.
7. Attribute, don't route by scope. Set `--owner` to the household member the item
   concerns (resolved via the household graph); there are no privacy scopes. If an
   email reveals a durable household fact (a new employer, a child's new school, a
   changed address), consider proposing the entity/relationship update too — follow
   the belief-revision rules in the `memory` / `onboarding` skills (resolve, then
   transition rather than overwrite). Do not hardcode domains to scopes.
8. Every fetched message must end either `mark-triaged` or `skip`.
