# personal-agent

A harness-neutral safe execution and personal authority substrate for local AI agents.

## Modules

| Module | Purpose |
|---|---|
| `safe-run` | Command wrapper: output mediation, logging, timeout, JSON results |
| `runlog` | Log inspection CLI: head, tail, grep, range over command outputs |
| `person-service` | Trusted sidecar: personal/family state, approvals, credentials |
| `person-cli` | Sandbox-safe CLI client for person-service |

## Quick Start

### Build

```bash
docker compose run --rm dev sbt compile
```

### Test

```bash
docker compose run --rm dev sbt test
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
sbt "personCli/run commitment list --owner fred --scope fred_work"
```

## Architecture

See [docs/architecture.md](docs/architecture.md).

## Skills

Agent-facing procedural docs in `skills/`:
- [safe-terminal](skills/safe-terminal/SKILL.md) — command execution
- [commitments](skills/commitments/SKILL.md) — obligation tracking
- [inbox-triage](skills/inbox-triage/SKILL.md) — email processing
- [person-service](skills/person-service/SKILL.md) — API reference
