---
name: calendar
description: Read the owner's Google Calendar agenda (conflicts, what's scheduled) and propose new events — creating an event is gated (you request, a human approves, person-service writes it).
version: 2.0.0
capabilities: [person, safe_run]
---

# Calendar

## Purpose

Read the owner's calendar so you can answer "what's on this week?", check whether
a proposed time conflicts with an existing event, and tell whether a date you saw
elsewhere (e.g. in an email) is already scheduled. You can also **propose a new
event** — creation is gated (you request, a human approves, person-service writes
it). You cannot move or delete events.

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
calendars do I have?". You can **see** them, but you do **not** choose which one
an event goes on when you create it — that is the human's decision at the approval
gate (see below).

## Create an event (gated — you request, a human approves)

```
person calendar create --owner <owner> --summary '...' \
  --start <ISO-8601 instant> --end <ISO-8601 instant> \
  [--all-day] [--location '...'] [--description '...']
```

- `--start` / `--end` are full **UTC ISO-8601 instants** (`2026-06-20T18:00:00Z`).
  **Resolve local times yourself** against the owner's timezone (in the household
  profile) before passing them — e.g. "6pm Dublin on 20 June" → the matching UTC
  instant. Use a `python3` snippet for the conversion rather than guessing.
- `--all-day` switches to a whole-day event; pass `--start`/`--end` as the day
  boundaries (end is the day *after* the last day, per Google's convention).
- You do **not** pass `--channel` — it's set automatically.
- There is **no calendar flag, by design.** You cannot pick which calendar the
  event lands on. If the owner has more than one calendar, person-service shows the
  human a menu of their real calendars at the approval step and *they* choose;
  with a single calendar there's nothing to pick. Never name a target calendar in
  your request or imply you chose one.
- This **creates an approval, not an event.** Tell the owner you've requested it
  and **stop** — a human approves out of band (with a one-time code), they pick the
  calendar, then person-service writes it and you may be re-invoked to confirm.
  Never claim the event is on the calendar until it has been approved.

## Check for conflicts before proposing

Before `calendar create`, read the agenda for the event's window
(`person calendar agenda --from <start> --to <end>`). If the slot **overlaps** an
existing event, do not silently double-book: **tell the user about the clash and
ask** whether the two can run concurrently (or whether they want a different time)
before you request the event.

Example:
```
person calendar create --owner fred --summary "Parents' evening (St Kilians)" \
  --start 2026-06-20T17:00:00Z --end 2026-06-20T18:00:00Z \
  --location "St Kilians" --description "From school newsletter"
```

## Rules

1. To *schedule* something, **propose** it with `person calendar create` (gated) —
   don't claim you added an event, and don't move/delete events (unsupported).
   For a personal to-do without a calendar slot, record a commitment instead.
   You never choose the target calendar — the human picks it at the approval gate.
2. Resolve relative dates against the current date/time in the system prompt.
3. If a calendar command reports access was not granted, that's a **one-time setup
   the user does** (a browser consent — you cannot do it). **Tell the user** their
   Google authorization needs the calendar permission (it's in the project README),
   and **stop** — do **not** run any auth command yourself and do not retry.
