# personal-agent

A harness-neutral safe execution and personal authority substrate for local AI agents.

## Modules

| Module | Purpose |
|---|---|
| `safe-run` | Command wrapper: output mediation, logging, timeout, JSON results |
| `runlog` | Log inspection CLI: head, tail, grep, range over command outputs |
| `person-service` | Trusted sidecar: personal/family state, approvals, credentials, goals |
| `person-cli` | Sandbox-safe CLI client for person-service |
| `runtime` | Skill catalogue: `skill list`, `skill search`, `skill show` over filesystem-backed skills |
| `mycroft` | Local-LLM agent harness: `POST /inbound` + `GET /outbound/stream` (SSE), native OpenAI tool calls, conversation state in person-service |
| `mycroft-repl` | Thin REPL channel adapter speaking mycroft's wire protocol (stand-in for a future WhatsApp/Signal gateway) |

## Quick Start

All builds run inside the container. The dev image is GraalVM (`native-image`
included) plus sbt; the source tree is mounted at `/workspace`.

### Build

```bash
docker compose run --rm dev sbt compile
```

### Test

```bash
docker compose run --rm dev sbt test
```

### Native binaries (GraalVM)

Build a real, JVM-free binary for any module inside the dev container:

```bash
docker compose run --rm dev sbt safeRun/nativeImage
# binary at: modules/safe-run/target/native-image/safe-run
```

The module → output mapping is:

| Module          | sbt task                  | Output                                              |
|-----------------|---------------------------|-----------------------------------------------------|
| `safe-run`      | `safeRun/nativeImage`     | `modules/safe-run/target/native-image/safe-run`     |
| `runlog`        | `runlog/nativeImage`      | `modules/runlog/target/native-image/runlog`         |
| `person-cli`    | `personCli/nativeImage`   | `modules/person-cli/target/native-image/person-cli` |
| `person-service`| `personService/nativeImage` | `modules/person-service/target/native-image/person-service` |
| `runtime`       | `runtime/nativeImage`     | `modules/runtime/target/native-image/runtime`       |
| `mycroft`       | `mycroft/nativeImage`     | `modules/mycroft/target/native-image/mycroft`       |

`mycroft-repl` is **not** native: it is an interactive JVM app (JLine terminal
UI) packaged as a fat jar via `mycroftRepl/assembly` and shipped in a small JRE
image (`docker compose build repl`). Only the binaries the agent executes on the
hot path need to be native; the REPL is started once, so JVM startup is fine.

To build all binaries and bake them into a minimal, JVM-free runtime image:

```bash
docker compose build native        # produces image personal-agent:native
docker run --rm personal-agent:native safe-run --help
```

### Run person-service

```bash
docker compose run --rm -p 8080:8080 dev sbt "personService/run"
```

With seed data:
```bash
PERSON_SERVICE_SEED=true docker compose run --rm -p 8080:8080 dev sbt "personService/run"
```

### Run safe-run

```bash
sbt "safeRun/run --cwd /tmp --timeout 30 --shell bash -- echo hello"
```

### Run person CLI

```bash
sbt "personCli/run health"
sbt "personCli/run commitment list --owner fred"
sbt 'personCli/run goal propose --owner fred --title "Approve Q3 report" --outcome "..." --evidence-rule "..."'
sbt "personCli/run goal list --owner fred --status open"
sbt 'personCli/run memory search "morning meetings" --person fred'
sbt "personCli/run memory context --person fred"
sbt "personCli/run memory profile --limit 50"
sbt 'personCli/run event record --action note.preference --category session_note --text "Fred mentioned morning meetings"'
sbt "personCli/run memory consolidate"
```

Durable state is one shared household store keyed by `person` and `entity` — there
are no privacy scopes. Build the household graph (persons, entities, typed
relationships) with:

```bash
sbt 'personCli/run entity propose --kind organization --name "MegaCorp" --source onboarding:work'
sbt "personCli/run entity list --status accepted"
sbt "personCli/run entity resolve megacorp"
sbt 'personCli/run relationship propose --from fred --from-kind person --type employed_by --to <entity-id> --to-kind entity --source onboarding:work --valid-from 2024-01-01T00:00:00Z'
sbt "personCli/run relationship list --from fred --type employed_by"
sbt "personCli/run household"   # accepted, currently-active graph
```

### Run skill catalogue

```bash
sbt "runtime/run list --skills-dir ./skills"
sbt 'runtime/run search --skills-dir ./skills "execute a shell command"'
sbt "runtime/run show --skills-dir ./skills safe-terminal"
```

`RUNTIME_SKILLS_DIR` overrides the default `./skills`.

### Run mycroft (LLM agent harness)

Mycroft needs a running person-service and a reachable OpenAI-compatible LM
Studio endpoint. Key env vars: `PERSON_SERVICE_URL`, `MYCROFT_LM_STUDIO_URL`,
`MYCROFT_DEFAULT_MODEL`, `MYCROFT_PORT` (default 8090), `MYCROFT_WORKDIR`
(cwd for `safe_run`). Sampling is tunable too — `MYCROFT_TEMPERATURE` (0.6),
`MYCROFT_TOP_P` (0.95), `MYCROFT_TOP_K` (20), `MYCROFT_MIN_P` (0),
`MYCROFT_PRESENCE_PENALTY` (1.0); the presence penalty breaks Qwen3 reasoning
loops, so raise it (e.g. 1.5) if you still see turns that think without answering.
`MYCROFT_TIMEZONE` (IANA zone id, default `UTC`; compose sets `Europe/Dublin`)
stamps the current date/time into the prompt so the agent can resolve relative
dates and tell past from future.

```bash
PERSON_SERVICE_URL=http://127.0.0.1:8080 \
MYCROFT_LM_STUDIO_URL=http://fredriks-mac-mini.gledswood.org:1234 \
sbt "mycroft/run"
```

Drive it with the REPL adapter (registers the channel on first run). On a real
terminal it shows live grey "thinking" and "tool" boxes with the answer typed
below; multi-line pastes are sent as one message and **Ctrl-D twice** quits.

```bash
# JVM, from source:
sbt 'mycroftRepl/run --channel fred --as fred --register'

# or via the JRE image against the compose stack:
docker compose build repl
docker compose run --rm repl --channel fred --as fred --register
```

Or speak the wire protocol directly:

```bash
curl -X POST localhost:8090/channels -d '{"id":"fred","members":["fred"]}'
curl -N localhost:8090/outbound/stream &                       # SSE: started/reasoning/content/tool_call/tool_result/done
curl -X POST localhost:8090/inbound \
  -d '{"channel":"fred","from":"fred","content":"What goals are open?"}'
```

### Gmail inbox triage

**Credentials** — pick one (env overrides file):

1. **Recommended:** download your Google OAuth desktop client JSON as
   `modules/person-service/src/main/resources/client-secret.json` (gitignored).
   person-service loads `installed.client_id` / `client_secret` from it automatically.
2. Or set `GMAIL_CLIENT_ID` and `GMAIL_CLIENT_SECRET` env vars.
3. Or set `GMAIL_CLIENT_SECRET_FILE=/path/to/client-secret.json`.

**Scopes** are not stored in the client secret. They are requested at auth time
(`gmail.readonly` **and** `calendar.readonly` — one consent covers both) and must be
allowed on your OAuth consent screen in Google Cloud. Adding scopes in the Cloud
Console after creating the client is fine — no secret edit needed. If you authed
before calendar was added, re-run `person gmail auth` to grant the calendar scope.

**Redirect URI:** `person gmail auth` uses `http://localhost:8765/oauth/callback` by default.
Add that exact URI under your OAuth client's authorized redirect URIs (desktop apps support this).

One-time OAuth:

```bash
sbt "personService/run"   # terminal 1

sbt 'personCli/run gmail auth --owner fred'   # terminal 2 — opens browser
sbt 'personCli/run gmail sync --owner fred'
sbt 'personCli/run inbox list --owner fred --status pending'
sbt 'personCli/run inbox show <inbox-id>'     # body + headers + attachment metadata
```

Attachments are synced as metadata only (filename, mimeType, size, attachmentId);
their bytes are fetched on demand and written to disk for the agent to read:

```bash
sbt 'personCli/run inbox download <inbox-id> --out /tmp/attachments'
# or just one attachment:
sbt 'personCli/run inbox download <inbox-id> --out /tmp/attachments --attachment <attachmentId>'
```

From the REPL, run `/triage` to sync Gmail, fetch pending messages, and start a Mycroft triage turn:

```bash
docker compose run --rm repl --channel fred --as fred --register
# at prompt:
/triage
```

Optional scheduled poll (every 15 min by default):

```bash
docker compose --profile inbox-sync up inbox-sync
```

### Calendar (read-only)

The same Google authorization also grants `calendar.readonly`, so once you've run
`person gmail auth` you can read the owner's primary calendar. It's on-demand
(no sync/cache in Phase 1):

```bash
sbt 'personCli/run calendar agenda --owner fred --days 7'
# explicit window:
sbt 'personCli/run calendar agenda --owner fred --from 2026-06-10T00:00:00Z --to 2026-06-20T00:00:00Z'
```

Triage uses this to ground dated items ("already on the calendar" / conflicts).
Writing events (propose → human approve) is planned but not yet implemented.

## Architecture

See [docs/architecture.md](docs/architecture.md).

## Skills

Agent-facing procedural docs in `skills/` (Agent Skills spec format — YAML frontmatter + body):
- [safe-terminal](skills/safe-terminal/SKILL.md) — command execution
- [commitments](skills/commitments/SKILL.md) — obligation tracking
- [inbox-triage](skills/inbox-triage/SKILL.md) — email processing
- [calendar](skills/calendar/SKILL.md) — read-only Google Calendar agenda
- [person-service](skills/person-service/SKILL.md) — API reference
- [goals](skills/goals/SKILL.md) — durable completion contracts
- [plans](skills/plans/SKILL.md) — workspace planning conventions
- [memory](skills/memory/SKILL.md) — semantic facts with supersession and point-in-time recall
- [events](skills/events/SKILL.md) — episodic log + consolidation flow
- [agent-protocol](skills/agent-protocol/SKILL.md) — how Mycroft uses `safe_run` + `runlog` + the trusted CLIs, and its propose-only authority
