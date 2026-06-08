---
name: safe-terminal
description: Execute shell commands safely with bounded output, timeouts, and a log inspector for large results.
version: 1.0.0
capabilities: [safe-run, runlog]
---

# Safe Terminal Execution

## Purpose

Execute shell commands safely without flooding context with raw output.

## Commands

### Execute a command

```
safe-run --cwd /tmp/workspace --timeout 30 --shell bash -- "your command here"
```

Returns JSON with:
- `runId` — unique identifier for this execution
- `exitCode` — null if timed out
- `timedOut` — true if the command exceeded the timeout
- `stdoutHead` / `stdoutTail` — first/last 80 lines preview
- `stderrHead` / `stderrTail` — error output preview
- `stdoutBytes` / `stderrBytes` — total output size
- `stdoutLog` / `stderrLog` — paths to full output files

### Inspect output with runlog

The `<run_id>` is the id printed in the `(full output: runlog <run_id> …)`
line of a truncated `safe_run` preview. `--cwd` is optional and defaults to the
working directory, so you normally omit it:

```
runlog list
runlog show <run_id> --stream stdout --head 100
runlog show <run_id> --stream stderr --tail 50
runlog grep <run_id> --stream stdout --pattern "ERROR"
runlog range <run_id> --stream stdout --start-line 100 --end-line 200
```

## Rules

1. Always use `safe-run` for command execution. Never run commands directly.
2. Check `exitCode` and `timedOut` in the JSON response before proceeding.
3. If output is large (stdoutBytes > 16KB), use `runlog` to inspect specific sections.
4. Use `runlog grep` to find specific patterns rather than reading entire outputs.
5. Supported shells: `bash`, `zsh`, `sh`. Default is `bash`.
6. Timeout default is 30 seconds. Increase for known long-running commands.
7. The `--cwd` must exist and be writable. Logs are stored under `<cwd>/.agent/runs/`.
