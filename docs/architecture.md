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
