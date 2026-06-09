---
name: code-interpreter
description: Run small Python or shell snippets via safe_run for computation, parsing, and data wrangling — arithmetic, date math, JSON/CSV processing, quick transforms. Stateless per run.
version: 1.0.0
capabilities: [safe-run, runlog]
---

# Code interpreter

When a task needs real computation — arithmetic you shouldn't do in your head,
date math, parsing JSON/CSV, slicing a large tool output, a quick transform —
write a small **Python** (or shell) snippet and run it through `safe_run` instead
of guessing. `python3` is available in the runtime.

## Use it for

- Math you must get exactly right (sums, percentages, share/grant totals, FX).
- Date/time arithmetic ("how many weekdays until 2026-07-05").
- Parsing structured output: pull fields out of a `person … ` JSON response, a CSV
  attachment, a log.
- Reshaping/filtering data before you reason about it.

Prefer this over eyeballing a big blob or doing fragile mental arithmetic.

## How to run

Inline, for one-liners:

```
safe-run --cwd /workspace --timeout 20 --shell bash -- \
  "python3 -c 'import json,sys; d=json.loads(open(\"/workspace/x.json\").read()); print(len(d))'"
```

For anything non-trivial, **write a file then run it** (clearer, quoting-safe):

```
safe-run --cwd /workspace --timeout 20 --shell bash -- \
  "cat > /workspace/calc.py <<'PY'
import json
data = json.load(open('/workspace/inbox.json'))
print(sum(1 for m in data if m['triageStatus']=='pending'))
PY
python3 /workspace/calc.py"
```

Pipe a previous command's output straight in when handy:

```
safe-run --cwd /workspace --timeout 20 --shell bash -- \
  "person inbox list --owner fred --status pending | python3 -c 'import json,sys; print([m[\"subject\"] for m in json.load(sys.stdin)])'"
```

## Rules

1. **Each `safe_run` is a fresh process** — there is no persistent REPL/kernel
   between calls. To carry state across steps, write it to a file under
   `/workspace` and read it back; don't assume variables survive.
2. **Output is bounded.** `safe_run` returns a capped preview; if your script
   prints a lot, write results to a file and `runlog` into it, or have the script
   print only what you need.
3. **Stdlib only.** Use the Python standard library (`json`, `csv`, `datetime`,
   `re`, `math`, …). Don't rely on `pip install` / third-party packages.
4. Keep snippets small and single-purpose; print a clear, minimal result.
5. This is for *computation*, not for taking actions — privileged/outside-effect
   actions still go through `approvals`, and durable state through the `person`
   CLI. Don't shell out to bypass those.
