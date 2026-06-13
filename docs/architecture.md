# Architecture

## Overview

This is a harness-neutral agent substrate. It provides safe command execution, log inspection, and a trusted personal authority sidecar. Any agent harness (Hermes, OpenClaw, Goose, Claude Code, custom) can use these tools without depending on them.

## Design Principles

### Harness-Neutral

The substrate does not know which harness is driving the agent. It exposes CLIs and a local HTTP API. The harness invokes `safe-run`, `runlog`, and `person` as commands. No harness-specific code exists in this repo.

### safe-run Is Not a Sandbox

`safe-run` only does output mediation:
- Captures stdout/stderr to files
- Enforces timeouts with process group killing
- Returns bounded JSON previews to the caller
- Never streams raw command output

It does **not** restrict what the command can access. Containment is the responsibility of a separate layer (OpenShell, Docker, nsjail) that wraps the entire agent session. `safe-run` runs inside that container; it is not the container.

### Why safe-run Bounds Output

LLM context windows are finite and expensive. A command that produces 10MB of stdout would flood the context and degrade agent performance. `safe-run` ensures the agent always gets a bounded, structured summary (first/last 80 lines, up to 16KB per preview) while preserving the full output on disk for incremental inspection via `runlog`.

### Why runlog Exists

When the agent needs to inspect more than the preview, it uses `runlog` to read specific sections:
- Head/tail of specific line counts
- Grep for patterns
- Line ranges

This keeps context usage proportional to what the agent actually needs, not proportional to command output size.

### Why person-service Exists

The agent sandbox must not have direct access to:
- SQLite database files
- OAuth tokens (Gmail, Calendar)
- Browser profiles
- SSH keys
- The host home directory

`person-service` is a trusted sidecar that runs **outside** the sandbox. It owns credentials, database access, and policy enforcement. The agent interacts only through the `person` CLI — a thin `curl`/`jq` script (`scripts/person`, not a compiled module) that makes HTTP calls to the sidecar.

### person CLI (script) vs person-service

| | person CLI (script) | person-service |
|---|---|---|
| Runs in | Agent sandbox | Host / trusted zone |
| Has credentials | No | Yes |
| Has DB access | No | Yes |
| Can record state (gateless) | Yes | N/A (it owns state) |
| Can request gated actions | Yes (request only) | N/A |
| Can decide/execute gated actions | No | Yes |

### Why Authoritative State Lives Outside the Sandbox

If the agent could directly write to the database, a compromised or confused agent could:
- Delete commitments
- Create or approve its own goals/actions (bypass the gate)
- Overwrite memory in place (destroy history)
- Corrupt family data

All writes go through the sidecar's API, but the gate strength is matched to risk (see "Human-in-the-Loop" below): reversible internal knowledge is written directly; durable contracts and outside-effect actions require a human decision the agent structurally cannot forge.

### Human-in-the-Loop: gateless writes (and a parked gate)

> **Status: the approval gate is currently parked.** The household decided not to
> gate calendar/task creation — those are now written **directly**, made safe by an
> `[M]` marker (so MyCroft's entries are visible and bulk-removable in Google),
> reversibility, and server-side idempotency rather than a sign-off (see *Direct,
> marked, idempotent writes* below). The approval **engine** — decision plane,
> one-time codes, parameterized options, executor — remains in code, **dormant and
> reserved for the one action that will always be gated: sending email to a third
> party.** Goals are likewise parked. The section below describes the engine for
> when it returns; today the agent has no `request`/approve verb.

Not everything needs the same gate. We grade by risk, and the agent's command surface mirrors it with **two verbs**: `record` (gateless) and `request` (gated, currently parked).

- **Gateless — `record` (memory, entities, relationships, commitments):** written **live** (`accepted`, or `open` for commitments). These are internal and reversible, so the safety net is provenance + reversibility, not a sign-off: `text`/`outcome` fields are immutable (you supersede, never edit), every item carries `origin_event_id`/`source`, a `confidence`, and a **trust level**, and a human (or the agent, on realising it erred) can `reject`/`archive`/`supersede`/`commitment cancel` after the fact. Requiring human acceptance of every learned fact was friction that fought continuous learning; we dropped it. There is no `accept`/`pending`/`accept-all`. A commitment's `open` means *tracked*, not *agreed*.
- **Hard-gated — `request` (goals, and any outside-effect action):** these go through the **approval mechanism**. A goal is a durable, immutable contract (a wrongly-inferred one is costly); an action (send mail, create a calendar event) is irreversible and outward. The agent can only **request** — it never approves or executes.

**Evidence ≠ belief ≠ authority.** A recorded belief carries a `trust` level — `user_stated`, `tool_confirmed`, `external_content`, or `agent_inference`. The agent may infer freely from email/web and reason with those inferences, but an `external_content` belief is *provenance-limited*: it never enters the authoritative profile (which is onboarding-sourced), is surfaced flagged "⚠ unverified", and can never authorize a `request`/action on its own. Memory policy is permissive; action policy is strict — the two are independent. Consolidation derives the trust of a belief from the originating event's `source` (an `email:`/`web:` source ⇒ `external_content`), so background ingestion cannot silently promote untrusted content to authoritative state.

The approval lifecycle, deliberately with **no suspended fibers** (so it survives restarts and overnight async waits):

1. **Request** — the agent creates an `Approval` (`requested`). Non-blocking; the turn ends. `person goal request` is sugar that creates a `goal.create` approval; `person approval request` covers other action types.
2. **Surface** — clients subscribe to person-service's `GET /approvals/stream` and render `requested` events. mycroft *also* subscribes, but only for `executed` (to run continuations). The agent can *see* approvals; it cannot decide them.
3. **Decide** — a human approves/rejects. This is gated two ways: the decision endpoint is served **only on a private network interface** the agent's plane (`agentnet`) cannot route to; and it requires a **one-time decision code** that person-service issues and delivers only to the human (over the private code endpoint, or a client push), hashed at rest and **never present on any agent-readable surface**. So even reaching the endpoint, the agent has no code. Codes are single-use, id-bound, and have a generous (default 48h) TTL so an overnight approval still works.
4. **Execute** — on approve, person-service runs the action server-side (it holds the credentials), records the result, and emits `executed`.
5. **Continue (saga)** — an approval may carry a continuation `{skill, params}`; on `executed`, mycroft runs it. A gated multi-step workflow is decomposed at its approval boundaries and resumes from durable state (the recorded result + the goal + memory), never from an in-memory paused process.

This keeps a *compromised* agent unable to bypass the gate — it can neither route to the decision endpoint nor produce a valid code — while the gateless path keeps everyday learning autonomous.

**Requests are non-blocking; batches stay decoupled from decisions.** Because a request only enqueues an `Approval` and returns, a producer like inbox triage fires *all* of a batch's calendar requests in one pass and ends with a single digest — it never waits on a human, and the next poll runs regardless of how many approvals are still pending. So a calendar HITL never blocks email triage. The only coupling risk was prompt wording telling the agent to "stop after requesting"; the skills now state that "stop" means *don't await the result*, not *end the turn*. **Reconnect backfill:** `GET /approvals/stream` only carries events from the moment of subscription, so a batch fired while the client was away would be invisible on reconnect; the REPL therefore fetches currently-`requested` approvals on connect and renders them (conflicts first), deduped against the live stream — so the returning user sees and approves the whole batch in one sitting.

**Parameterized decisions (trusted-core options).** A decision can carry a *choice* — e.g. *which* calendar an event lands on — without weakening the property that makes the gate safe: **a compromised agent must never be able to disguise what it's actually doing.** That property holds because the human reviews the **literal payload that executes** and executors read only known fields from that frozen payload — no gap between shown and run. The naïve risk a menu would add is **label↔value divergence**: an agent-authored option "Family calendar" whose value pointed elsewhere could lie. So the load-bearing invariant: **the agent never authors what the human sees, nor the gated choice values.** An `Approval.optionsJson` menu (`[{id,label,params}]`) is sourced and rendered by the **trusted core** (`PersonService.optionsFor`, e.g. from the owner's real Google calendar list) — never from the agent's request. On approve, the chosen option's `params` are merged into the frozen `payloadJson` (`resolveChosenOption`); that merge is the *only* way a value enters the payload after request, and it comes from the trusted option set. With 0–1 options no choice is forced (execution defaults, e.g. `primary`); with several, approving without a valid `chosenOptionId` is a `Validation` error. The agent supplies only structured params the core validates (for calendar create it has **no** calendar flag at all); the human approves the resolved, canonical effect.

### Untrusted content & prompt injection

The agent reads attacker-controllable content — email bodies, attachments, and (in future) web pages. Prompt injection is **not reliably solvable at the model layer**, so we assume the model can be fooled and defend in layers, strongest first:

1. **Capability containment (primary).** A hijacked agent still can't take any outside-effect action (send mail, create events/goals) without a human approving via a one-time code on a network it can't reach. So injection can't directly cause irreversible harm — at worst it produces an approval request the human scrutinises. **The exfiltration channel is closed at the network layer:** `mycroft` sits on a Docker `internal: true` network with no route off the host, so no binary it runs (`curl`, `python3`, `bash`) can reach the internet. Its only reachable destinations are `person-service` (the gated API) and `llm-proxy` (a single-upstream socat forwarder to the inference endpoint and nowhere else). A future web-fetch tool must route through this same controlled egress — there is no general outbound path to open.
2. **Instruction/data separation (general harness rule).** Only the system prompt and the sender's messages are authoritative. **All** tool output (`safe_run`/`runlog`/`run_skill` — email, web, attachments, command output) is fenced as `<<<UNTRUSTED_TOOL_OUTPUT … >>>` (`Loop.untrusted`) and the system prompt forbids obeying instructions found inside it.
3. **Sanitisation at the source (per-tool, covert-channel scrub).** Format-specific, so it lives in each reader: email bodies are scrubbed of invisible/format Unicode (zero-width, bidi overrides, the tag block) and hidden HTML in `MessageParser.sanitize`; a future web tool readability-extracts visible text. Kills *invisible* injection; cannot neutralise a plainly-worded one — that's what layers 1–2 are for.
4. **Provenance at the decision point.** An approval carries its `source` (e.g. `email:gmail-msg-X`), surfaced to the human so a request that originated in untrusted content is judged accordingly. Memory written from untrusted sources stays low-confidence/observation, never silently authoritative.

OS-level, `mycroft` (the component exposed to untrusted content) runs with `cap_drop: ALL` + `no-new-privileges`, skills mounted read-only, and **no host network or general egress** (internal Docker network; see layer 1); a non-root user + read-only root fs is the next hardening step.

### Why Credentials Must Not Be in the Sandbox

Credentials (OAuth tokens, API keys, SSH keys) in the sandbox would let a compromised agent:
- Send email as the user
- Access private repositories
- Modify cloud infrastructure

Credentials stay in `person-service`. Gmail and Google Calendar are both
integrated today as **read-only** server-side operations (the agent never sees the
OAuth token); calendar *writes* and any other write-side integrations remain
future work behind the same approval boundary.

## Domain Model

### Person and Scope

**Person** represents a real human (family member, colleague). Each person has a timezone and locale.

**Scope** partitions state. Scopes exist because:
- Privacy: Fred's work items shouldn't be visible in family context
- Permissions: Different people have different roles per scope
- Future multi-agent: Different agents may have access to different scopes

### Why Commitments Are Separate from Calendar/Reminders

Commitments are the **source of truth** for obligations. They represent "what someone needs to do."

Calendar events and reminders are **projections** — they help schedule and surface commitments at the right time, but they are not authoritative. A calendar event can be moved without changing the underlying obligation.

This separation means:
- Commitments persist even if calendar entries are deleted
- Multiple reminders can point to the same commitment
- The agent can reason about obligations without understanding calendar semantics

### The user-facing model: two tracking primitives, Google is the view

The user tracks exactly **two** kinds of thing, distinguished by **attend vs do**:

- **Events** — "be present at a time" (an appointment, a meeting, a child's parents' evening). Projected to **Google Calendar**.
- **Todos (commitments)** — "complete an action", dated (a deadline) or not. Projected to **Google Tasks**. *A deadline is not an appointment* — "pay fees by the 30th" is a todo with a due date, not a calendar event.

Both live in person-service with full provenance (`source`, owner, `trust`); **Google is the everyday UI, not the store.** "Primary UI = Google" is independent of "source of truth = Google": exactly as you never read the local events table but look at Google Calendar, the substrate stays authoritative and Google is a projection you view and tick. Keeping the substrate is what makes the agent's egress lockdown viable (state is local, not a Google round-trip per op), survives Google sunsetting a product, and preserves the trust/provenance model that a Google Task's title+notes can't hold. **Sync-back from Google is bounded** — todos sync status + due, events sync time + cancellation — never an arbitrary field merge (the tractable slice of conflict resolution). Both Calendar and Tasks sync back, symmetrically (the Tasks projection + Calendar event cache are the [Phase 2 work](#calendar-read-only-phase-1)).

Classification, non-blocking batch triage, and the digest are codified in the `inbox-triage`, `calendar`, and `approvals` skills.

### Why We Are Not Implementing Multi-Tenancy

This is a personal/family substrate. There is one deployment, one family. Multi-tenancy (separate databases, auth between tenants) adds complexity without value at this scale. Scopes and roles provide sufficient isolation within the family unit.

### Goals Are Durable; Plans Are Replaceable

> **Goals are currently parked.** They model an *autonomous multi-step agent objective* (`outcome` + `evidence_rule`, gated creation, evidence accumulation), not user-facing tracking — the wrong shape for "parents' evening" or "pay the gas bill", which are events and todos (above). With no live autonomous workflow using them, triage and the user-facing skills do **not** create goals; the tables, endpoints, and the `goal.create` approval type remain in place but dormant, to be revived when a concrete autonomous-task need appears. The design below stands for that eventual use.

Goals are durable completion contracts kept in `person-service`. A goal carries `title`, `outcome`, `evidence_rule`, `status`, `blocked_reason`, evidence, source, timestamps. Status and evidence are mutable. **`outcome` and `evidence_rule` are immutable once created** — there are no service operations to edit them. This is deliberate: environmental text (a README, an email reply, an injected instruction) must not silently redefine the contract. If the user actually wants a different outcome, the agent cancels the goal and requests a new one. **Goal *creation* is hard-gated** (see Human-in-the-Loop): the agent `goal request`s; the goal exists only after a human approves the `goal.create` action. Status/evidence updates are not gated.

Plans, by contrast, are working artifacts. They live as files in the workspace at `/workspace/goals/<goal-id>/PLANS.md`, with older versions snapshotted under `plans/`. Plans are agent-editable, replaceable, and not durable across sandbox resets. The `plans` skill codifies the convention; no code enforces it.

### Skills as a Catalogue

Skills live under `skills/<name>/SKILL.md` in the Agent Skills spec format: YAML frontmatter (`name`, `description`, optional `version`, optional `capabilities`) followed by markdown body. Optional sibling directories (`scripts/`, `references/`, `assets/`) carry resources loaded only when needed — progressive disclosure.

The `runtime` module's `skill` CLI exposes three operations:

- `skill list` — name + description for every skill, metadata only.
- `skill search <query>` — natural-language query against the **full** body of every skill (per the SkillRouter finding that metadata-only routing collapses at scale), using SQLite FTS5 with BM25 ranking and OR-of-prefix tokens for natural-language friendliness. Returns ranked candidates with name, description, path, score.
- `skill show <name>` — full SKILL.md body, or just the path with `--path`.

The catalogue is filesystem-backed and rebuilt in-memory on every search. For dozens of skills this is sub-100ms; if the corpus grows past hundreds, swap to a persisted index in `SkillCatalog`.

The agent's discovery flow:
1. `skill search "..."` → metadata-only candidates
2. Pick one, `skill show <name>` → activate (read the procedural body)
3. Follow the procedure, executing through `safe-run` / `person` / `runlog`

### Episodic Events vs. Semantic Memory

Memory in this substrate has two complementary forms:

- **Episodic events** (`audit_events`, broadened): an append-only log of *what happened*. Every state mutation in person-service writes here. Categories distinguish purpose: `state` (commitments, goals, memory lifecycle, approvals), `observation` (the agent saw or inferred something), `utterance` (user speech worth preserving verbatim), `decision` (agent branch points), `session_note` (cognitive notes intended for consolidation).
- **Semantic facts** (`memory_items`, extended): durable, mutable beliefs about people, projects, preferences. Each fact has a `kind`, optional `valid_from`/`valid_until` for world-time validity, a `superseded_by_id` for replacement chains, and an `origin_event_id` linking back to the event that produced it.

Both tables are indexed with persistent SQLite FTS5 virtual tables kept in sync via triggers, so search is fast and stays consistent with the source rows.

The flow between them:
1. Agent logs raw experience as events (cheap, high-volume)
2. `person memory consolidate` walks recent `observation` + `session_note` events and records one `memory_item` per event with `origin_event_id` set
3. Facts are written `accepted` (gateless — see Human-in-the-Loop), with `trust` derived from the originating event's `source` (an `email:`/`web:` source ⇒ `external_content`, surfaced flagged); a human can later `reject`/`supersede` a wrong one
4. Accepted facts feed the **context bundle** (`person memory context`) — top-k ranked by `confidence × recency` plus recent relevant events — for harness injection
5. Active recall (`person memory search`, `person event search`) is FTS5-ranked and supports point-in-time `--as-of` queries that respect supersession and `valid_from`/`valid_until`
6. Provenance: from any accepted fact, follow `origin_event_id` to the event that produced it; from there, the audit trail explains the rest

`outcome` and `evidence_rule` are immutable on goals; `text` is immutable on memory items (replace via supersession). Both rules block environmental text from quietly rewriting durable state.

### Email Ingestion (Inbox)

`person-service` ingests email as a read-only source feeding triage. Gmail OAuth
tokens live only in the sidecar (in the `credentials` table); the agent never
holds them.

- Gmail authorization is a one-time, operator-driven OAuth flow completed
  **server-side**: the operator opens the consent URL (`GET /gmail/auth-url`),
  and Google redirects the browser back to person-service's
  `GET /gmail/oauth/callback`, which exchanges the code and stores the tokens.
  The agent has no auth verb. `person gmail sync` then pulls recent message
  **metadata + plain-text body** into the `inbox_messages` table and refreshes
  the access token as needed.
- Each inbox row carries triage state (`pending` / `triaged` / `skipped`) so the
  `inbox-triage` skill can process the oldest-pending messages and mark them.
- **Attachments are stored as metadata only** — `attachmentId`, `filename`,
  `mimeType`, `sizeBytes` (in the row's `attachments_json`). The bytes are *not*
  persisted. When a task actually needs an attachment's contents, the agent runs
  `person inbox download <id> --out <dir>`: the sidecar fetches the bytes from
  Gmail on demand (refreshing the token), returns them base64-encoded, and the
  `person` CLI writes the file into the agent's workspace where `safe_run` can
  read it. This keeps large blobs out of both the database and the LLM context
  until they are explicitly requested.

The agent's surface is the `person inbox` / `person gmail` CLI verbs (read +
on-demand download); it can never send mail or mutate the mailbox.

### Calendar & Tasks (Google projections)

Calendar and Tasks reuse the **same Google OAuth grant** as Gmail: one consent
requests `gmail.modify` + `calendar.events` + `calendar.readonly` + `tasks`,
yielding one access/refresh token (stored in the single `gmail` credential row)
that works against all three APIs. There is no separate calendar/tasks login.
Changing the scope set requires a one-time operator re-consent (the server-side
Gmail OAuth flow); tokens minted under the old scopes won't carry the new
permissions.

Phase 1 is **on-demand and uncached**: `person calendar agenda --owner <p>
[--days N | --from --to]` live-queries the owner's Google calendars
(`events.list`, single-events expanded, ordered by start) and returns a JSON array
of events. It fans out across **all** the owner's calendars (`calendarList.list` →
per-calendar `events.list`, merged + sorted), so a conflict check sees every
calendar, not just `primary`. `person calendar list --owner <p>` returns the
owner's calendars (`{id, summary}`) so the agent can *answer* "what calendars do I
have?" — it can see them but never selects one for a write. The agent pulls the
agenda when it needs scheduling context — answering "what's on this week", or,
during triage, grounding a date found in an email ("already on the calendar" /
"conflicts with X"). This keeps calendar consistent with the substrate's
tool-on-demand philosophy rather than always injecting a schedule into the prompt.

**Local mirror + sync-back (done).** `person-service` keeps a `calendar_events`
table — the substrate's event store. `syncCalendar(owner)` (poller-driven, the
`projection-sync` compose profile) fans the live agenda across all calendars,
upserts events into the mirror, and marks vanished in-window events `cancelled` —
each change recorded as an audit event (`calendar.event.imported/updated/cancelled`).
The create write-through populates the mirror immediately. So a Google-side change
(an event the user moves or deletes) reaches the substrate, the symmetry to the
Tasks projection. (`calendarAgenda` still reads **live** for freshness; switching it
to the mirror — and per-turn context injection — is a later step, deferred until the
poller is guaranteed-on.)

**Write — direct, `[M]`-marked, idempotent (no approval).** `person calendar create`
→ `POST /calendar/events` → `createCalendarEventDirect`, which writes to Google
immediately (no gate) and write-throughs to the mirror. person-service prepends
`[M] ` to the summary so the entry is visibly MyCroft's; the user manages it in
Google. Creation is **idempotent**: the agent passes a stable `--source`, and
person-service dedups per target on `source` (else `(owner, calendarId, summary,
start)`), so a retry or re-triage never duplicates. Needs the `calendar.events`
scope. Safety here is the marker + reversibility + dedup + the routing below — not a
gate (see the parked HITL note above).

**Visibility routing (the agent classifies; the trusted core places).** The agent
never names a calendar id — it passes `--visibility ∈ {family, private-busy,
private}` and person-service maps that to the actual calendar(s) from config. This
keeps the privacy-sensitive *where* decision in the trusted core: a mis-firing model
can at worst pick the wrong intent, and the default biases to safety.
- `family` → full event on the shared **Family** calendar (kids' events, trips,
  shared logistics).
- `private-busy` (**default**) → full event on the owner's **private** calendar **+**
  a redacted `[M] Busy` block on Family (same time, no detail) — for the owner's
  personal appointments: the household sees they're unavailable without the details.
- `private` → owner's private calendar only.
The Family calendar id is config (`FAMILY_CALENDAR_ID`, else a name match on
`FAMILY_CALENDAR_NAME`); if it can't be resolved, `family`/`private-busy` **degrade
to private-only** rather than writing the wrong place. Each target is idempotent on
its own `source` (the busy-block uses a `#fam-busy` suffix). **Bounded by topology:**
only the owner's personal Google account is connected, so the writable calendars are
his primary (private) + Family — work calendars and other accounts aren't reachable,
so multi-account routing (e.g. a trip on both family and work, Paula's calendars) is
deferred until those connect.

### Commitments ↔ Google Tasks

Todos are commitments (substrate SoT); **Google Tasks is the view.**
`syncTasks(owner)` (poller-driven, the `projection-sync` profile) reconciles the
two: it **pushes** open commitments to the owner's default task list (insert new,
patch changed title/due, complete/remove resolved — tracked by `google_task_id` /
`projected_at` columns on `commitments`, kept off the domain model), and **pulls**
Google-side changes back, bounded to **status + due** — a task ticked off on the
phone marks the commitment `done`, a deleted task cancels it, a due change updates
it. A task the user creates directly in Google is **imported** as a commitment
(`--source tasks:<id>`). This is infrastructure, not a gated action: a projected
task is the user's own already-recorded, reversible, private commitment going to
their own list (gateless, mirroring the commitment itself). The agent always reads
and writes through `person commitment …`, never the Tasks API. The Task title is
`[M]`-marked like calendar events.

### Daily briefing (agent composes; person-service delivers — no agent egress)

The owner gets a daily summary (today's agenda, todos due soon, forward heads-ups
like a birthday a few weeks out with a present nudge). The defining constraint:
**the agent never sends anything.** It *composes* and hands off; person-service owns
scheduling and delivery.

- **Flow.** The `daily-briefing` compose profile (`briefing-poll.sh`) calls
  `POST /briefing/run?owner` once a day. `runDailyBriefing` (idempotent per owner per
  day) syncs calendar+tasks for fresh data, then pings mycroft `/inbound` to run the
  `daily-briefing` skill. The agent reads the substrate, composes `{subject, body}`,
  and calls its **only** briefing action — `person briefing submit` → `POST /briefing`.
  `submitBriefing` stores the briefing `pending` and **delivers it on submit**;
  a delivery failure keeps it `pending` (error recorded) so `deliverPending` retries
  on the next tick.
- **Delivery is pluggable, recipient is config.** A `DeliveryChannel` trait abstracts
  the channel; only `email` is implemented now — `GmailClient.sendMessage`
  (`messages.send`, `gmail.modify`) to the owner's **own account address**
  (non-redirectable). WhatsApp/Signal are future impls behind the same trait. The
  recipient/channel are resolved by person-service, never chosen by the agent.
- **Why this keeps the egress lockdown intact.** The agent has no send/email/network
  capability — it writes a row via `person briefing submit` and stops. Email send
  lives only in person-service (the trusted sidecar, outside the sandbox), only for
  the briefing, only to a configured/self address. It is **not** a general send verb
  and **not** an agent egress route. (General outward email — arbitrary recipients —
  is the future case reserved behind the dormant approval gate.)
- **Multi-user.** Everything is per-`owner` (run, briefings, delivery config), so a
  second user is onboarded by adding their person + delivery preference. Only Fred is
  configured now (email-to-self).

Both projections sync **bounded, not field-level**: Tasks ⇄ status+due, Calendar →
time+cancellation. That is the tractable slice of the unavoidable conflict
resolution — we never attempt an arbitrary two-way merge.

## Execution Model

### Stateless Command Execution (Default)

Each `safe-run` invocation is independent. There is no persistent terminal session. The agent issues a command, gets a result, and decides what to do next.

This is intentional:
- Reproducible: re-running the same command gives the same result
- Auditable: every execution is logged with full context
- Safe: no accumulated shell state that could mask errors

### Named Sessions / Background Jobs (Future)

Some tasks need persistent state (e.g., a REPL, a dev server). Future work may add:
- Named sessions with `tmux` or `screen`
- Background job tracking
- Session lifecycle management

These would be additional commands, not changes to `safe-run`.

## Future Architecture

### Sidecar Model for Web/Email/Calendar

```
┌─────────────────────────────────────────────┐
│ Agent Sandbox                                │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │ safe-run  │  │  runlog  │  │  person  │  │
│  └──────────┘  └──────────┘  └──────────┘  │
└────────────────────────┬────────────────────┘
                         │ HTTP (localhost)
┌────────────────────────┴────────────────────┐
│ Trusted Zone                                 │
│  ┌─────────────────────────────────────────┐│
│  │ person-service                           ││
│  │  ├── SQLite DB                          ││
│  │  ├── Gmail OAuth (readonly)             ││
│  │  ├── Calendar API (readonly)            ││
│  │  └── Approval engine                    ││
│  └─────────────────────────────────────────┘│
└──────────────────────────────────────────────┘
```

### Future Harness Integration

When connecting to Hermes, OpenClaw, or another harness:
1. The harness starts an OpenShell/Docker container
2. `safe-run`, `runlog`, and `person` binaries are available inside
3. The harness writes skill files to the container
4. `person-service` runs on the host, accessible from the container
5. The harness orchestrates the LLM loop; this substrate provides the tools

## Mycroft and Channels

Mycroft is the first concrete agent harness over the substrate. It accepts
inbound messages from one or more *channel adapters* (the REPL today, a future
WhatsApp/Signal gateway tomorrow), drives an LLM turn against LM Studio, and
emits a stream of typed events back. The substrate stays harness-neutral; only
this module knows about LLMs, channels, and conversation history.

### Topology

```
external apps ── messaging clients (whatsapp, signal, …)
                          │
                          ▼  (their protocols; gateway translates)
                ┌────────────────────┐
                │ gateway container  │  (future; not part of v1)
                └─────────┬──────────┘
                          │  POST /inbound, GET /outbound/stream
                          ▼  internal docker network only
                ┌────────────────────┐
                │   mycroft (v1)     │  modules/mycroft, port 8090
                └─────────┬──────────┘
                          │  HTTP
                          ▼
                ┌────────────────────┐    ┌──────────────────────┐
                │  person-service    │    │  LM Studio (off-host)│
                └────────────────────┘    └──────────────────────┘
```

Trust is the network: mycroft and person-service expose ports only to the
internal docker network. Gateways (and the REPL) are the only thing that can
reach mycroft. No per-message auth in v1; the magic-link 2FA approval flow is
the future direction for human-consent gates.

### Channels

A *channel* is a durable conversation grouping with an audience. Per-message
`from` carries the sender. The scopes mycroft may read or write in a given turn
are derived from the sender's roles (`ScopeRoleRepo.findByPerson(from)`), not
from a single `channel.scope_id`. This means the family channel can flow into
the family scope when Fred speaks and into a different scope when Paula does;
a personal channel constrains naturally to the sender's private scopes.

The gateway maps external IDs (phone numbers, Signal UUIDs) → channel names;
mycroft maps channel names → audiences and senders → scopes. Two layers of
indirection, but each has one responsibility.

### Wire protocol

`POST /inbound` is a synchronous ack; the assistant's reply streams as
Server-Sent Events on the long-lived `GET /outbound/stream`. Event types:

| event | meaning |
|---|---|
| `started` | turn opened (carries model, channel, message_id) |
| `reasoning` | reasoning-token chunk (from LM Studio's `reasoning_content`) |
| `content` | user-visible token chunk |
| `tool_call` | mycroft is about to invoke a tool |
| `tool_result` | tool returned |
| `done` | reply complete — deliver via the channel transport |
| `error` | terminal failure for this turn |

`done` is the unambiguous "send this now" signal. SSE deltas are ephemeral;
durable replay is via `GET /messages?channel=X&since=…` against persisted
assistant messages — gateways track the last delivered `message_id` per
channel and backfill on reconnect, rather than relying on event IDs.

### Tool surface

Mycroft uses **native OpenAI tool calls** (reasoning models on this build
return reasoning in a dedicated `reasoning_content` field, not inline
`<think>` tags). The native tool set is intentionally tiny:

- `safe_run(command, timeout?)` — executes a bash command via the in-process
  `safe-run` `ProcessRunner` and returns bounded preview + `runlog` reference.
- `runlog(args)` — zooms into the full output of an earlier `safe_run` when its
  preview was truncated. It is its own tool (not invoked through `safe_run`,
  which would be circular).
- `run_skill(name, task, params?)` — **control-plane** tool that runs a skill as
  an isolated sub-task (own context + budget) and returns a structured result
  summary. It composes skills; it does not touch the OS, so the OS surface stays
  `safe_run` + `runlog`.

Everything else — `person memory record`, `person goal list`, the `skill`
catalogue, unix utilities — is invoked through `safe_run`. Command vocabulary
lives in skills; the system prompt mentions only the entry points. This is
progressive disclosure of tools, mirroring the substrate's progressive
disclosure of skills.

Every tool invocation writes a `decision`-category audit event so the turn is
fully reconstructable from the event log.

### LLM sampling

Mycroft sends explicit sampling parameters with every completion (Qwen3
thinking-mode defaults: `temperature 0.6`, `top_p 0.95`, `top_k 20`, `min_p 0`)
plus a `presence_penalty` (default `1.0`). The presence penalty is load-bearing:
without it the reasoning model can fall into a repetition loop that burns the
whole `max_tokens` budget without ever emitting an answer or a tool call. All are
env-tunable (`MYCROFT_TEMPERATURE`, `MYCROFT_TOP_P`, `MYCROFT_TOP_K`,
`MYCROFT_MIN_P`, `MYCROFT_PRESENCE_PENALTY`). As a backstop, a turn that finishes
with no tool call and empty content returns a clear fallback message rather than a
blank reply.

### Clock

The model has no inherent sense of time, so every system prompt — top-level turns
and skill sub-tasks alike — is stamped with the current date/time in a configured
timezone (`MYCROFT_TIMEZONE`, an IANA zone id; default `UTC`). The "now" itself
comes from ZIO's `Clock`; only zone parsing and formatting touch `java.time`,
which ZIO has no substitute for. Without this the
agent cannot resolve relative dates ("by Friday", "next week") or tell whether a
date referenced in an email is in the past or the future — essential for inbox
triage and the planned calendar work.

### Compaction

Two-tier context on every turn:

- **Long-term**: the durable knowledge in person-service — top-ranked accepted
  memory items + recent observation/decision/session_note events for each
  scope the sender has access to. Always injected as a context bundle.
- **Short-term**: a rolling window of recent user/assistant messages from this
  channel, sized to fit the dynamic budget
  `model_context − system − bundle − max_output − margin`.

Intra-turn `tool` and assistant-tool_call messages are kept in-memory for the
turn only — never persisted as part of the conversation history that re-enters
the window on the next turn, which avoids malformed tool sequences on reload.

- **Intra-turn working set**: a long agentic turn appends an assistant+tool pair
  every iteration (up to `MYCROFT_MAX_TOOL_ITERATIONS`, each tool preview up to
  4 KB), which would grow unbounded and overflow the model mid-turn. Before each
  model call `Compaction.fit` bounds the message list to `MYCROFT_INNER_TOKEN_BUDGET`
  by **progressively degrading the oldest tool outputs to just their `runlog`
  pointer** (keeping the most recent `MYCROFT_KEEP_RECENT_TOOLS` verbatim and never
  touching the system message, user turns, or assistant→tool pairing). This is
  lossless w.r.t. evidence: the full output stays on disk and the model can
  re-read it via `runlog <id>`. The cross-turn budget above is applied once at
  turn start; this is the per-call bound inside the loop.

Auto-summarize-on-drop (rolling-out messages become `session_note` events that
later consolidate into memory items) is deferred to v1.1; for now, important
context must be promoted explicitly via `person memory record`.

## GraalVM Native-Image Notes

- **zio-json**: Uses compile-time macro derivation. No reflection. Native-image safe.
- **zio-cli**: Combinator-based, no reflection. Native-image safe.
- **zio-http/Netty**: Requires `--initialize-at-run-time=io.netty` flag. Known pattern.
- **sqlite-jdbc**: Ships `META-INF/native-image` config since 3.40.1.0. Auto-detected.
- **setsid**: Must be available in the runtime image. Install `util-linux` in Docker.

## Technology Stack

- Scala 2.13.18
- ZIO 2.1.26 (core, streams, test)
- zio-json 0.9.2
- zio-http 3.11.2 (person-service only)
- zio-cli 0.8.1
- xerial sqlite-jdbc 3.53.1.0
- sbt 1.10.9
- sbt-native-image 0.3.2
