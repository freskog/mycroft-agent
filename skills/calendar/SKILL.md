---
name: calendar
description: Read the owner's Google Calendar agenda (upcoming events) to reason about scheduling, conflicts, and whether dates mentioned elsewhere are already on the calendar. Read-only.
version: 1.0.0
capabilities: [person, safe_run]
---

# Calendar

## Purpose

Read the owner's calendar so you can answer "what's on this week?", check whether
a proposed time conflicts with an existing event, and tell whether a date you saw
elsewhere (e.g. in an email) is already scheduled. This is **read-only** — you
cannot create, move, or delete events.

## Command

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

## Rules

1. Read-only. To *schedule* something, propose a commitment (with a `--due`) or
   record an observation — do not claim you added a calendar event.
2. Resolve relative dates against the current date/time in the system prompt.
3. If the command reports that calendar access was not granted, tell the owner to
   re-run `person gmail auth --owner <owner>` (the Google consent now also covers
   calendar) — do not retry in a loop.
4. The calendar shares the owner's single Google authorization with Gmail; there
   is no separate calendar login.
