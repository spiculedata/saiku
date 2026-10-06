# Session summary

The handoff between one agent session and the next. `AGENTS.md` says *how* to
build, test and work in this repo; this file says *where the last session left
off* — what was in flight, what was learned that isn't in `AGENTS.md`, and what
to do first when you start.

Read it at the start of a session, and write to it at the end of one.

## When to write

- **End of any session that touched the repo** — especially one that was cut
  short: context ran out, a build went red, a wrong assumption was found, or
  you stopped mid-investigation. The interrupted sessions are the ones that
  cost the most when they are not written down.
- **Before handing work to a human or a different agent** — a CI failure you
  couldn't reproduce, a design question you couldn't answer alone, a branch
  that needs a decision.
- **After a correction** — if a human had to tell you how this repo works, the
  rule itself belongs in [`.claude/memory/corrections.md`](./memory/corrections.md)
  (in the same PR), and the *state you were in when it happened* belongs here.

Do **not** write an entry for a session that only read code and reported back.
If a session produced a diff, opened a PR, or left a question unanswered, it
gets an entry.

## How to write

Append one entry per session, **newest first**, under *Log* below, in this
shape:

```
## <YYYY-MM-DD> — <branch> — <one-line outcome>
**Was doing:** <what the session was working on, and its state at the end: shipped,
handed off, or abandoned mid-edit>
**Now:** <what the next session should do first — a command to run, a file to
read, a question to answer>
**Watch out:** <the trap, dead end, or wrong assumption that cost time, one or
two lines. Omit the line if there wasn't one.>
**Refs:** <issue / PR / branch / file, if any>
```

Rules that keep the file useful rather than a wall of text:

- **State, not narrative.** "Was doing" records where the work is, not a
  step-by-step account of how it got there — `git log` has that.
- **Uncommitted work is the headline.** If a session left a dirty tree, say so
  in the first line and name the files. A half-done edit picked up by a later
  `git checkout -b` has silently corrupted work before; don't be the one that
  causes it again.
- **Keep it short.** Four lines is a good entry. If an entry needs more, the
  detail belongs in a plan under `docs/plans/`, an issue, or a PR description —
  link it instead of inlining it.
- **Cap the log at the last ~10 entries** and delete the oldest. A session
  summary is a hand-off note, not a history; anything that still matters a
  month later belongs in `AGENTS.md` (durable conventions), in
  `.claude/memory/corrections.md` (things agents got wrong), or in `CHANGELOG.md`
  (user-visible changes).
- **Prune on read.** If an entry describes work that has since landed or been
  thrown away, delete it in the same PR that lands or discards that work. A log
  of finished work is noise.
- **Link, don't copy.** A path, an issue number, or a branch name is enough;
  pasted diffs and full error logs rot immediately.

## Checkpoint variant

If a session ends because work is genuinely mid-flight — uncommitted edits, a
red build, an unanswered design question — write the same entry as a
**checkpoint** instead: keep only the newest entry, replace it on the next
session rather than appending, and say plainly in `Now:` what the next session
picks up. That is the only case where an older entry is superseded instead of
pushed down; normal sessions append and the history stays useful.

---

## Log

## 2026-09-28 — feature/2101-session-summary-artifact — added this file
**Was doing:** created the session-summary artifact for saiku#2101 (ACMM L3
`acmm:session-summary`) and referenced it from the *Agent resources* section of
`AGENTS.md`, alongside the corrections log and skills.
**Now:** nothing — the file ships empty of session history on purpose. Add the
first real entry at the end of your next session that touches the repo.
**Watch out:** a session summary that is written at the *start* of a session, or
written from the conversation rather than from `git status` / `git log`, records
what the agent remembers doing instead of what the tree actually contains. Check
the tree first.
**Refs:** #2101
