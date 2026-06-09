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

`person-service` is a trusted sidecar that runs **outside** the sandbox. It owns credentials, database access, and policy enforcement. The agent interacts only through the `person` CLI, which makes HTTP calls to the sidecar.

### person-cli vs person-service

| | person-cli | person-service |
|---|---|---|
| Runs in | Agent sandbox | Host / trusted zone |
| Has credentials | No | Yes |
| Has DB access | No | Yes |
| Can propose state | Yes | N/A (it owns state) |
| Can finalize state | No | Yes (via admin) |

### Why Authoritative State Lives Outside the Sandbox

If the agent could directly write to the database, a compromised or confused agent could:
- Delete commitments
- Accept its own proposals
- Overwrite memory
- Corrupt family data

All writes go through the sidecar's API, but the gate strength is matched to risk (see "Human-in-the-Loop" below): reversible internal knowledge is written directly; durable contracts and outside-effect actions require a human decision the agent structurally cannot forge.

### Human-in-the-Loop: gateless writes, gated actions & goals

Not everything needs the same gate. We grade by risk:

- **Gateless (memory, entities, relationships):** written **live** (`accepted` on write). These are internal and reversible, so the safety net is provenance + reversibility, not a sign-off: `text`/`outcome` fields are immutable (you supersede, never edit), every item carries `origin_event_id`/`source` and a `confidence`, and a human (or the agent, on realising it erred) can `reject`/`archive`/`supersede` after the fact. Requiring human acceptance of every learned fact was friction that fought continuous learning; we dropped it. There is no `accept`/`pending`/`accept-all`.
- **Hard-gated (goals, and any outside-effect action):** these go through the **approval mechanism**. A goal is a durable, immutable contract (a wrongly-inferred one is costly); an action (send mail, create a calendar event) is irreversible and outward. The agent can only **request** — it never approves or executes.

The approval lifecycle, deliberately with **no suspended fibers** (so it survives restarts and overnight async waits):

1. **Propose** — the agent creates an `Approval` (`requested`). Non-blocking; the turn ends. `person goal request` is sugar that creates a `goal.create` approval; `person approval request` covers other action types.
2. **Surface** — clients subscribe to person-service's `GET /approvals/stream` and render `requested` events. mycroft *also* subscribes, but only for `executed` (to run continuations). The agent can *see* approvals; it cannot decide them.
3. **Decide** — a human approves/rejects. This is gated two ways: the decision endpoint is served **only on a private network interface** the agent's plane (`agentnet`) cannot route to; and it requires a **one-time decision code** that person-service issues and delivers only to the human (over the private code endpoint, or a client push), hashed at rest and **never present on any agent-readable surface**. So even reaching the endpoint, the agent has no code. Codes are single-use, id-bound, and have a generous (default 48h) TTL so an overnight approval still works.
4. **Execute** — on approve, person-service runs the action server-side (it holds the credentials), records the result, and emits `executed`.
5. **Continue (saga)** — an approval may carry a continuation `{skill, params}`; on `executed`, mycroft runs it. A gated multi-step workflow is decomposed at its approval boundaries and resumes from durable state (the recorded result + the goal + memory), never from an in-memory paused process.

This keeps a *compromised* agent unable to bypass the gate — it can neither route to the decision endpoint nor produce a valid code — while the gateless path keeps everyday learning autonomous.

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

### Why We Are Not Implementing Multi-Tenancy

This is a personal/family substrate. There is one deployment, one family. Multi-tenancy (separate databases, auth between tenants) adds complexity without value at this scale. Scopes and roles provide sufficient isolation within the family unit.

### Goals Are Durable; Plans Are Replaceable

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
3. Facts are written `accepted` (gateless — see Human-in-the-Loop); a human can later `reject`/`supersede` a wrong one
4. Accepted facts feed the **context bundle** (`person memory context`) — top-k ranked by `confidence × recency` plus recent relevant events — for harness injection
5. Active recall (`person memory search`, `person event search`) is FTS5-ranked and supports point-in-time `--as-of` queries that respect supersession and `valid_from`/`valid_until`
6. Provenance: from any accepted fact, follow `origin_event_id` to the event that produced it; from there, the audit trail explains the rest

`outcome` and `evidence_rule` are immutable on goals; `text` is immutable on memory items (replace via supersession). Both rules block environmental text from quietly rewriting durable state.

### Email Ingestion (Inbox)

`person-service` ingests email as a read-only source feeding triage. Gmail OAuth
tokens live only in the sidecar (in the `credentials` table); the agent never
holds them.

- `person gmail auth` runs a one-time OAuth loopback flow; `person gmail sync`
  pulls recent message **metadata + plain-text body** into the `inbox_messages`
  table and refreshes the access token as needed.
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

### Calendar (read-only, Phase 1)

Calendar reuses the **same Google OAuth grant** as Gmail: the consent requests
`gmail.readonly` + `calendar.readonly` together, yielding one access/refresh token
(stored in the single `gmail` credential row) that works against both APIs. There
is no separate calendar login.

Phase 1 is **on-demand and uncached**: `person calendar agenda --owner <p>
[--days N | --from --to]` live-queries the owner's primary Google Calendar
(`events.list`, single-events expanded, ordered by start) and returns a JSON array
of events. The agent pulls the agenda when it needs scheduling context — answering
"what's on this week", or, during triage, grounding a date found in an email
("already on the calendar" / "conflicts with X"). This keeps calendar consistent
with the substrate's tool-on-demand philosophy rather than always injecting a
schedule into the prompt.

This preserves the existing boundary: **calendar events are projections, not
authoritative state**, and the agent still cannot write them. Two deliberate
follow-ups:

- **Phase 1.5 — cache + context injection**: sync events into a local
  `calendar_events` table (like `inbox_messages`) and fold upcoming events into
  the per-turn context bundle, so the agent always knows the schedule without a
  tool call.
- **Phase 2 — write via approval**: a `calendar.events` scope where the agent
  *proposes* an event (an `Approval` of type `calendar.create_event`); a human
  approves and `person-service` creates it, linking it back to the commitment or
  goal it projects.

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

Everything else — `person memory propose`, `person goal list`, the `skill`
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

Auto-summarize-on-drop (rolling-out messages become `session_note` events that
later consolidate into memory items) is deferred to v1.1; for now, important
context must be promoted explicitly via `person memory propose`.

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
