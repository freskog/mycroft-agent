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
sbt 'personCli/run goal propose --owner fred --scope fred_work --title "Approve Q3 report" --outcome "..." --evidence-rule "..."'
sbt "personCli/run goal list --owner fred --status open"
sbt 'personCli/run memory search "morning meetings" --scope fred_work'
sbt "personCli/run memory context --scope fred_work --person fred"
sbt 'personCli/run event record --action note.preference --category session_note --scope fred_work --text "Fred mentioned morning meetings"'
sbt "personCli/run memory consolidate --scope fred_work"
```

### Run skill catalogue

```bash
sbt "runtime/run list --skills-dir ./skills"
sbt 'runtime/run search --skills-dir ./skills "execute a shell command"'
sbt "runtime/run show --skills-dir ./skills safe-terminal"
```

`RUNTIME_SKILLS_DIR` overrides the default `./skills`.

## Architecture

See [docs/architecture.md](docs/architecture.md).

## Skills

Agent-facing procedural docs in `skills/` (Agent Skills spec format — YAML frontmatter + body):
- [safe-terminal](skills/safe-terminal/SKILL.md) — command execution
- [commitments](skills/commitments/SKILL.md) — obligation tracking
- [inbox-triage](skills/inbox-triage/SKILL.md) — email processing
- [person-service](skills/person-service/SKILL.md) — API reference
- [goals](skills/goals/SKILL.md) — durable completion contracts
- [plans](skills/plans/SKILL.md) — workspace planning conventions
- [memory](skills/memory/SKILL.md) — semantic facts with supersession and point-in-time recall
- [events](skills/events/SKILL.md) — episodic log + consolidation flow
