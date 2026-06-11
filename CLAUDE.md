# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A **harness-neutral safe-execution and personal-authority substrate** for local AI agents (Scala 2.13 / ZIO 2). It gives any agent harness a small set of tools — bounded command execution, log inspection, and a trusted personal-state sidecar — without those tools depending on the harness. `mycroft` is the first concrete harness built on top.

The defining constraint, which explains most design decisions: **the agent runs in a sandbox; credentials and authoritative state do not.** The agent reaches state only through a CLI that calls a trusted sidecar over localhost HTTP: it `record`s reversible knowledge directly and `request`s gated actions/goals that a human finalizes. Read `docs/architecture.md` before making structural changes — it documents the *why* behind the boundaries below.

## Build & test

All builds run **inside the dev container** (GraalVM + sbt, source mounted at `/workspace`). The sbt project names differ from the directory names — use the sbt names below.

```bash
docker compose run --rm dev sbt compile
docker compose run --rm dev sbt test

# Single module's tests:
docker compose run --rm dev sbt "mycroft/test"
# Single spec (ZIO Test, ZTestFramework):
docker compose run --rm dev sbt "personService/testOnly *HouseholdGraphSpec*"
```

Module directory → sbt project name: `safe-run`→`safeRun`, `runlog`→`runlog`, `person-service`→`personService`, `person-cli`→`personCli`, `runtime`→`runtime`, `mycroft`→`mycroft`, `mycroft-repl`→`mycroftRepl`, `common`→`common`.

Native binaries (GraalVM, JVM-free) are built per module via `<module>/nativeImage`; `docker compose build native` bakes them all into `personal-agent:native`. `mycroft-repl` is the exception — it is **not** native (JLine needs a JVM); it ships as a fat jar via `mycroftRepl/assembly` in the `repl` image. Only hot-path binaries the agent executes need to be native.

If you compile/run on the host (Metals/Bloop are configured), GraalVM is still required for `nativeImage`. The README has the full run-command catalogue for each module — consult it rather than guessing CLI verbs.

## Module map

```
common         shared model, JSON codecs, AgentError, output preview, Time (fixed-precision ISO-8601)
safe-run       command wrapper: timeout + process-group kill, output→files, bounded JSON preview
runlog         reads sections (head/tail/grep/range) of safe-run's on-disk output logs
person-service TRUSTED SIDECAR — SQLite, OAuth tokens, approval engine; zio-http API. Runs OUTSIDE sandbox.
person-cli     sandbox-safe HTTP client for person-service ("person" verbs)
runtime        skill catalogue CLI (list/search/show) over filesystem skills; FTS5 BM25 search
mycroft        local-LLM agent harness: POST /inbound + SSE GET /outbound/stream; native OpenAI tool calls
mycroft-repl   JVM REPL channel adapter speaking mycroft's wire protocol
```

Dependency edges: everything depends on `common`; `mycroft` additionally depends on `safeRun` + `runtime` (it runs `safe-run`'s `ProcessRunner` in-process rather than shelling out).

## Architecture invariants (do not violate)

- **safe-run is not a sandbox.** It only mediates output (bounds previews, persists full output to disk, kills on timeout). It never restricts what a command can access — containment is a separate outer layer (Docker/nsjail). Don't add access-control logic here.
- **The agent never holds credentials or DB handles.** Gmail/Calendar OAuth tokens live only in `person-service` (the `credentials` table); Gmail and Calendar are server-side **read-only**. The agent's only surface is `person` CLI verbs. Never thread tokens or DB access into sandbox-side modules (`safe-run`, `runlog`, `person-cli`, `mycroft`).
- **Gate by risk, not uniformly (HITL) — two verbs: `record` vs `request`.** Memory/entities/relationships/**commitments** are **gateless** — `record`ed live (`accepted`, or `open` for commitments), made safe by immutability + provenance + reversibility (`reject`/`archive`/`supersede`/`commitment cancel|ignore|done`); there is no `accept`/`pending`/`accept-all` (removed) and **no `propose` verb** (renamed to `record`). **Goals and outside-effect actions are hard-gated** through the approval mechanism: the agent only `request`s; a human decides; person-service executes (`performApproved`). Don't add a direct goal-create, a way for the agent to approve/execute, or reintroduce `propose`/an accept step.
- **Evidence ≠ belief ≠ authority (memory provenance).** Every `memory_item` carries a typed `TrustLevel` (`user_stated`/`tool_confirmed`/`external_content`/`agent_inference`) + optional `sender`; `audit_events` carry a first-class `source`. `consolidateOne` derives trust from the origin event's `source` (`email:`/`web:`/`http` ⇒ `external_content`). External-content beliefs stay recall-visible but flagged "⚠ unverified" (see `Prompt.renderFact`), never enter the authoritative profile (`profileFacts` = onboarding-sourced), and never authorize a gated action. Memory policy is permissive; action policy is strict — keep them independent.
- **The decision is structurally unforgeable by the agent.** `decideApproval` requires a **one-time code** (hashed, single-use, id-bound, TTL) that's issued/delivered only to the human and kept off every agent-readable surface (stream, `approval list`/`show`). person-service serves the decision + code-issuance routes (`Routes.decideRoutes`) on a **private interface** (`PERSON_SERVICE_PRIVATE_HOST`, compose `approvalnet`) that `mycroft` is not attached to. Never put a code on the shared `/approvals/stream` or in an Approval JSON, and never move `decide`/`code` onto the public routes.
- **Immutable contract fields.** A goal's `outcome` and `evidence_rule`, and a memory item's `text`, are immutable by design — there are no service ops to edit them; replace via cancel-and-recreate or supersession chains. Stops environmental text from silently rewriting durable state. Don't add edit endpoints for them.
- **Approvals don't suspend; they resume as a fresh turn (saga).** A turn proposes and ends — no held fibers. mycroft subscribes to `/approvals/stream` and, on `executed`, runs the approval's optional continuation `{skill, params}`, rehydrating from durable state. Don't add fiber suspension/checkpointing for approvals.
- **Commitments are source of truth; calendar/reminders are projections.** Reasoning about obligations must not depend on calendar semantics.
- **Episodic vs. semantic memory.** `audit_events` = append-only log of what happened (every mutation writes one); `memory_items` = durable beliefs, linked back via `origin_event_id`. Consolidation walks recent observation/session_note events → records `accepted` memory items (gateless). Both have SQLite FTS5 mirrors kept in sync by triggers — keep them consistent when touching those tables (see `persistence/Migrations.scala`).

## Conventions

- **ZIO throughout.** Effects are `IO[AgentError, A]` (see `common/AgentError.scala`); repos are traits in `person-service/persistence/Repos.scala` with SQLite impls. Tests use `zio-test` (`ZTestFramework`).
- **JSON via zio-json** compile-time derivation (no reflection — required for native-image). Same for zio-cli (combinator-based). When adding deps, check the "GraalVM Native-Image Notes" in `docs/architecture.md`; netty needs `--initialize-at-run-time=io.netty`.
- **Timestamps** must round-trip through `common.Time.format` (fixed-nanosecond ISO-8601) — stored as TEXT and compared lexicographically in SQL, so variable-precision `Instant.toString` would break ordering.
- **Skills** live in `skills/<name>/SKILL.md` (Agent Skills spec: YAML frontmatter + body). They are the agent-facing command vocabulary; the `runtime` catalogue indexes their *full body*. When you add/change agent-invokable behavior, update the relevant skill — the system prompt only names entry points, so capability discovery flows through skills.
- **mycroft's OS tool surface is intentionally tiny:** `safe_run`, `runlog`, `run_skill`. Everything else (`person ...`, the `skill` catalogue, unix tools) is invoked *through* `safe_run`. Don't add native tools for things expressible as commands — that's deliberate progressive disclosure.

## Local services & deployment

`docker-compose.yml` wires the stack on an internal network (`agentnet`): `person-service` (8080), `mycroft` (8090), `repl` (interactive), optional `inbox-sync` (profile). Trust is the network — services expose ports only internally; the REPL/gateway is the only reachable entry. Production runs the `:native` image on a Mac mini. Seed data: `PERSON_SERVICE_SEED=true`.
