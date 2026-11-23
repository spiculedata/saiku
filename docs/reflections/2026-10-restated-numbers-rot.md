# A number copied next to its source will drift, so link to the source

**Date:** 2026-10-02
**Area:** docs, testing, agent guidance
**Refs:** `AGENTS.md` (*Testing baseline*), `TESTING.md`, `.github/test-floors.json`, saiku#2022

## What happened

`AGENTS.md` once carried its own snapshot of the test baseline. It claimed the repo had 10 tests in total and "zero coverage" for `saiku-core/saiku-web`. By then that module ran around 500 green tests behind a CI floor of 330. Agents reading the file made decisions from a picture of the repo that had stopped being true long before.

The section was rewritten to say that `TESTING.md` is the source of truth and to give only an orientation figure ("~1,480 tests green at 4.6.1") that is explicitly labelled as a point-in-time number.

The same drift is visible again today in miniature. `AGENTS.md` says `saiku-core/saiku-service` and `saiku-core/saiku-web` are the floored modules, while `.github/test-floors.json` now also floors `saiku-launcher` (32), and the `saiku-web` floor has moved from 330 to 684. The file that CI actually reads is the only one that is guaranteed to be right.

## Why it happened

A figure that is restated in a second place has no mechanism to update when the first changes. Nothing fails when they diverge, so the copy silently goes stale, and it goes stale in the worst direction: it sits in the file agents are told to trust first, and it sounds authoritative. The longer a repo moves, the larger the gap.

## What to do instead

- State a count, a version, a floor or a threshold in one place, ideally the place a tool already reads (`.github/test-floors.json`, the root pom's `<version>`, `AGENTS.md`'s own `mvn help:evaluate` command for the version).
- Everywhere else, point at that place or at the command that prints the value. If an orientation figure helps, label it with when it was true.
- Before "correcting" a doc from a remembered number, read the source. The same habit applies to code claims; see the 2026-08-16 entry in `.claude/memory/corrections.md`, where an old note nearly caused correct docs to be rewritten.
- When you add a test, bump the floor in `.github/test-floors.json` (it is a hard CI gate), and do not touch a count in prose.

## Where it lives now

- `AGENTS.md`, *Testing baseline*: "`TESTING.md` is the source of truth — read it rather than trusting any count restated here."
- `AGENTS.md`, *Test floors are a hard gate*.
- `.claude/memory/corrections.md`, 2026-08-16 entry (check the code before "correcting" docs).
