# Reflections

Cross-session knowledge for people and agents working on Saiku: short write-ups of **what we learned the hard way and why**, kept so the next session doesn't relearn it.

A reflection is not a rule and not a hand-off note. It is the reasoning behind a lesson, with enough evidence that a reader can judge whether it applies to a case the lesson doesn't name.

## Where this fits

Three other places hold agent-facing knowledge. Use the narrowest one that fits.

| File | Holds | Lifetime |
|---|---|---|
| [`AGENTS.md`](../../AGENTS.md) | Durable conventions and how to build, test and run | Permanent, edited in place |
| [`.claude/memory/corrections.md`](../../.claude/memory/corrections.md) | One-line rules a maintainer had to correct an agent on, with a short *Why* | Short; promote to `AGENTS.md` or delete when stale |
| [`.claude/session-summary.md`](../../.claude/session-summary.md) | Where the last session left off and what to do first | Short; keep roughly the last 10 entries |
| `docs/reflections/` (here) | The longer story and generalisable lesson behind a rule or an incident | Kept until the lesson stops being true |

Rule of thumb: if it fits in a line and says "do X, not Y", it is a **correction**. If it is "state of play right now", it is a **session summary**. If someone could reasonably ask "why do we do it that way?" and the answer takes a paragraph, it is a **reflection**, and the correction or `AGENTS.md` line should link to it.

## When to add one

Add a reflection when any of these is true:

- An incident or a near miss cost real time (a misdiagnosed CI failure, a corrupted local home, tagging the wrong commit) and the cause generalises beyond the one occurrence.
- You reversed an earlier belief and the old belief is still written down somewhere (a stale note, a doc, an issue).
- You found a trap that `AGENTS.md` only mentions in passing and the *mechanism* is what lets people avoid the next variant.
- A design decision was made against a tempting alternative and the reason isn't obvious from the code. Architectural decisions with a wider audience also go in the project wiki (`pages/decisions/`).

Do **not** add one for a fix whose cause is fully captured by the commit message, for a session that only read code, or for something that is already one line in `corrections.md`.

## Entry format

One file per reflection, named `YYYY-MM-<short-slug>.md`. Keep it under about a page.

```markdown
# <The lesson, as a sentence>

**Date:** YYYY-MM-DD
**Area:** <module or topic, e.g. testing, launcher, ci, saiku-ui>
**Refs:** <issues / PRs / commits / files, so the claims can be checked>

## What happened
<The situation and the wrong turn, in a few sentences. Facts, not blame.>

## Why it happened
<The mechanism. This is the part that lets a reader apply the lesson to a new case.>

## What to do instead
<Concrete behaviour. If it is now a convention, say where it landed.>

## Where it lives now
<Links to the AGENTS.md section, corrections.md entry or code that carries the rule, if any.>
```

## Maintenance

- Cite sources. A reflection that can't point at an issue, PR, commit or file is an opinion; verify the claim against the tree before writing it.
- If a reflection's rule is promoted into `AGENTS.md`, keep the reflection (it is the *why*) and link it from the rule.
- If a reflection stops being true, edit or delete it in the same PR that makes it untrue. A wrong reflection is worse than none.
- Do not paste secrets, tokens, customer data or full logs. Link to them.
- Agent guidance changes (`AGENTS.md`, `CLAUDE.md`) are separate edits; a reflection never replaces the rule it explains.

## Index

- [2026-10 Restated numbers rot, so link to the source](./2026-10-restated-numbers-rot.md)
