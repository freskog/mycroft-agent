---
name: inbox-triage
description: Process incoming email and messages, extracting commitments and memory proposals from actionable items.
version: 1.0.0
capabilities: [person-cli, safe-run]
---

# Inbox Triage

## Purpose

Process incoming email and messages to extract commitments and surface actionable items.

## Workflow

1. Read email content (provided by future Gmail integration).
2. Identify obligations, requests, deadlines, and action items.
3. For each actionable item, propose a commitment:

```
safe-run --cwd /tmp/workspace --timeout 10 --shell bash -- \
  "person commitment propose \
    --owner fred \
    --scope fred_work \
    --text 'Reply to Sarah about Q3 budget' \
    --source email:gmail-msg-456 \
    --evidence 'Can you review and get back to me by EOD?' \
    --due 2026-05-25T17:00:00Z"
```

## Rules

1. Never send email. Only propose commitments.
2. Never create calendar events directly.
3. Never mark emails as read or archive them.
4. Distinguish between:
   - **Requests to the person** → propose commitment with person as owner
   - **FYI/informational** → propose memory item if worth remembering
   - **Spam/irrelevant** → skip
5. Use the email message ID as the `--source` (e.g., `email:gmail-msg-123`).
6. Quote the relevant sentence as `--evidence`.
7. Infer deadlines from phrases like "by Friday", "EOD", "next week".
8. When unsure about scope, default to the owner's private scope.
