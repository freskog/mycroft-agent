---
name: calendar
description: Read the owner's Google Calendar agenda (conflicts, what's scheduled) and create new events directly ([M]-marked, idempotent via --source — no approval). Use only for attend-at-a-time; deadlines/todos are commitments.
version: 2.0.0
capabilities: [person, safe_run]
---

# Calendar

## Purpose

Read the owner's calendar so you can answer "what's on this week?", check whether
a proposed time conflicts with an existing event, and tell whether a date you saw
elsewhere (e.g. in an email) is already scheduled. You can also **create a new
event directly** — it's written straight to the calendar, `[M]`-marked and
idempotent (no approval). You cannot move or delete events.

person-service keeps a **local mirror** of Google Calendar (the substrate's event
store) in sync, so a change the user makes in Google — moving or cancelling an
event — reaches the substrate. You always go through `person calendar …`; the
mirror is automatic infrastructure you don't invoke. Google is the view, the
substrate is the source of truth.

## Read the agenda

```
person calendar agenda --owner <owner> [--days N] [--from <ISO-8601>] [--to <ISO-8601>]
```

- Defaults to the next 14 days from now. `--days N` changes the window length.
- `--from` / `--to` (ISO-8601 instants, e.g. `2026-06-10T00:00:00Z`) set an
  explicit range and override `--days`.
- Returns a JSON array of events: `summary`, `start`, `end`, `allDay`,
  `location`, `htmlLink`, `status`. Times are UTC instants; convert to the
  current timezone (in the system prompt) when talking to the user.

Example:
```
person calendar agenda --owner fred --days 7
```

The agenda spans **all** of the owner's calendars (primary, family, etc.), so a
conflict check sees every calendar — not just the primary one.

## See the owner's calendars

```
person calendar list --owner <owner>
```

Returns the owner's calendars as `{id, summary}`. Use this to answer "what
calendars do I have?". You don't choose which calendar an event lands on — events
go to the owner's primary calendar.

## Create an event (direct — no approval)

**Create an event only for "attend at a time" — when someone must be present, show
up, or block a specific slot** (appointment, meeting, parents' evening, an exam the
child sits, a flight). An *action to complete* — even with a hard deadline ("pay
fees by the 30th") — is a **todo, not an event**: record it as a `person commitment`
(with `--due` if dated), not a calendar event. A deadline is not an appointment.

```
person calendar create --owner <owner> --summary '...' \
  --start <ISO-8601 instant> --end <ISO-8601 instant> --source <stable-key> \
  --visibility private-busy|family|private \
  [--all-day] [--location '...'] [--description '...']
```

- **`--visibility` is how you route the event — you classify, the system places it.**
  You never choose a calendar (no calendar flag exists). Pick:
  - **`family`** — household-relevant, shareable: kids' school events, family
    logistics, trips, anything others need the *details* of → goes on the shared
    **Family** calendar.
  - **`private-busy`** (the **default** — use it when unsure) — the owner's own
    appointment (medical, dentist, a 1:1): full details on their **private** calendar
    **plus** a redacted `[M] Busy` block on Family, so the household sees they're
    unavailable without seeing what it is.
  - **`private`** — fully private; only the owner's calendar, nothing on Family. Use
    only when the household needn't even know the time is taken.
  When in doubt, prefer `private-busy` over `family` — under-sharing is safe,
  leaking a private detail onto the shared calendar is not. Anything medical /
  health / financial / personal → never `family`.
- The event is written **directly** to the calendar (no approval). person-service
  prepends **`[M] `** to the title so the user can see it's MyCroft's and manage it
  in Google. You never claim a *different* state — once the command succeeds (exit 0),
  it's on the calendar.
- **`--source` is your idempotency key** — a stable string (e.g.
  `email:gmail-msg-123#dentist`, or a deterministic slug like `chat:dentist-2026-06-26`).
  person-service dedups on it, so re-running the exact create (a retry, a re-triage)
  does **not** make a duplicate. Always pass it.
- `--start` / `--end` are full **UTC ISO-8601 instants** (`2026-06-20T18:00:00Z`).
  **Resolve local times yourself** against the owner's timezone (in the household
  profile) — e.g. "6pm Dublin on 20 June" → the matching UTC instant. Use a
  `python3` snippet for the conversion rather than guessing.
- `--all-day` switches to a whole-day event; pass `--start`/`--end` as the day
  boundaries (end is the day *after* the last day, per Google's convention).

## Check for conflicts before creating

Before `calendar create`, read **just that slot** to ground it
(`person calendar agenda --from <start> --to <end>` — don't pull a broad agenda).
If it **overlaps** an existing event:

- **Interactive turn (user present):** tell them about the clash and ask whether
  the two can run concurrently or they want a different time, before creating.
- **Unattended batch (e.g. triage):** don't block — still create the event, but put
  `⚠ clashes with <existing event> on <date>` in `--description` and lead the run's
  digest with the clash so the user sees it first.

Example:
```
person calendar create --owner fred --summary "Parents' evening (St Kilians)" \
  --start 2026-06-20T17:00:00Z --end 2026-06-20T18:00:00Z \
  --location "St Kilians" --description "From school newsletter"
```

## Rules

1. To *schedule* an attend-at-a-time thing, create it with `person calendar create`
   (direct, `[M]`-marked, idempotent via `--source`). Don't move/delete events
   (unsupported). For a to-do without a fixed slot, record a commitment instead.
   Events land on the owner's primary calendar; the user re-files/edits in Google.
2. Resolve relative dates against the current date/time in the system prompt.
3. If a calendar command reports access was not granted, that's a **one-time setup
   the user does** (a browser consent — you cannot do it). **Tell the user** their
   Google authorization needs the calendar permission (it's in the project README),
   and **stop** — do **not** run any auth command yourself and do not retry.
