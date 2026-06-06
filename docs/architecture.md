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

By placing all writes behind the sidecar's API, we enforce a proposal/approval boundary. The agent proposes; humans (or explicit approval flows) finalize.

### Why Credentials Must Not Be in the Sandbox

Credentials (OAuth tokens, API keys, SSH keys) in the sandbox would let a compromised agent:
- Send email as the user
- Access private repositories
- Modify cloud infrastructure

Credentials stay in `person-service`. Future integrations (Gmail, Calendar) will be server-side operations triggered by approved requests.

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

Goals are durable completion contracts kept in `person-service`. A goal carries `title`, `outcome`, `evidence_rule`, `status`, `blocked_reason`, evidence, source, timestamps. Status and evidence are mutable. **`outcome` and `evidence_rule` are immutable once created** — there are no service operations to edit them. This is deliberate: environmental text (a README, an email reply, an injected instruction) must not silently redefine the contract. If the user actually wants a different outcome, the agent cancels the goal and proposes a new one.

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
2. `person memory consolidate` walks recent `observation` + `session_note` events and proposes one `memory_item` per event with `origin_event_id` set
3. A human accepts or rejects (the proposal/approval boundary)
4. Accepted facts feed the **context bundle** (`person memory context`) — top-k ranked by `confidence × recency` plus recent relevant events — for harness injection
5. Active recall (`person memory search`, `person event search`) is FTS5-ranked and supports point-in-time `--as-of` queries that respect supersession and `valid_from`/`valid_until`
6. Provenance: from any accepted fact, follow `origin_event_id` to the event that produced it; from there, the audit trail explains the rest

`outcome` and `evidence_rule` are immutable on goals; `text` is immutable on memory items (replace via supersession). Both rules block environmental text from quietly rewriting durable state.

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
│  │  ├── Gmail OAuth (future)               ││
│  │  ├── Calendar API (future)              ││
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

- `shell_run(command, timeout?)` — executes a bash command via the in-process
  `safe-run` `ProcessRunner` and returns bounded preview + `runlog` reference.
- `skill_search(query, limit?)` / `skill_show(name)` — bootstrap procedure
  lookup so the agent finds CLI vocabulary by retrieval, not by enumeration.

Everything else — `person memory propose`, `person goal list`, `runlog show`,
unix utilities — is invoked through `shell_run`. Command vocabulary lives in
skills; the system prompt mentions only the available binaries. This is
progressive disclosure of tools, mirroring the substrate's progressive
disclosure of skills.

Every tool invocation writes a `decision`-category audit event so the turn is
fully reconstructable from the event log.

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
