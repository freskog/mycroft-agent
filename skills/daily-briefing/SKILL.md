---
name: daily-briefing
description: Compose the owner's daily summary — today's agenda, todos due soon, and forward heads-ups (birthdays/anniversaries with a present nudge, looming deadlines) — and submit it for delivery. You compose only; person-service delivers. Run me for the daily briefing.
version: 1.0.0
capabilities: [person, safe_run]
---

# Daily Briefing

## Purpose

Compose a short, friendly daily summary for the owner and hand it to person-service
to deliver. **You only compose and submit** — you do not send anything. There is no
email/send tool; your single action here is `person briefing submit`. person-service
owns delivery (email now; other channels later) to a fixed, configured address.

The task names the `owner` (default `fred`). The calendar and tasks have already
been synced before you're invoked, so the substrate is current — read it, don't
re-sync.

## Gather (read-only)

Pull the three inputs, resolving everything against the clock + timezone in your
system prompt:

1. **Today's agenda** and the next ~7 days (for "coming up"):
   ```
   person calendar agenda --owner <owner> --days 7
   ```
   Times are UTC instants — convert to the owner's local timezone for display.
2. **Todos due soon** — open commitments, especially those due within ~7 days:
   ```
   person commitment list --owner <owner> --status open
   ```
3. **Forward heads-ups** — pull the household profile/memory to spot birthdays,
   anniversaries, and other dated facts:
   ```
   person memory profile
   person memory search "birthday" --person <owner>
   ```
   Birth-dates are stored as facts (e.g. "born 2018-06-17") — compute how far off
   the **next** occurrence is from today. Flag ones roughly **3 weeks out** with a
   gentle present nudge ("Nora turns 8 in ~3 weeks — time to think about a present?").

## Compose

Write a concise markdown body with three short sections (skip any that are empty):

- **Today** — today's events (time + title), in local time.
- **Coming up** — notable events in the next 7 days, and todos due within ~7 days
  (with their due dates). Lead with anything time-sensitive.
- **Heads-up** — birthdays/anniversaries ~3 weeks out (with the present nudge),
  and any looming deadline worth lead time.

Keep it warm and brief — it's a morning glance, not a report. Use the owner's first
name. Don't include items that are pure noise.

## Submit (your only action)

```
person briefing submit --owner <owner> \
  --subject "Your day — <weekday> <date>" \
  --body "<the markdown you composed>"
```

person-service stores it and delivers it on the owner's channel. After submitting,
you're done — **do not** attempt to send, email, or deliver anything yourself
(there is no such tool, by design). Finish with a one-line confirmation of what you
submitted.

## Rules

1. Compose from the **substrate** (`person …`) only. Never call Google or any
   network tool directly — you can't, and you mustn't try.
2. `briefing submit` is the **only** briefing action. There is no send/email verb.
3. Resolve all dates/times to the owner's local timezone; derive ages/countdowns
   from stored birth-dates rather than guessing.
4. If there's genuinely nothing for today and nothing upcoming, still submit a brief
   "nothing on the calendar today; no todos due soon" so the daily cadence holds.
