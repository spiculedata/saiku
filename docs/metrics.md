# Metrics

This document defines the metrics `scripts/pr-metrics.mjs` computes, and how
they are published. It exists because a number nobody can reproduce is not a
feedback loop — the definitions, the window and the denominator all have to be
written down for the trend to mean anything across months.

Implements the ACMM criterion `acmm:pr-acceptance-metric` (saiku#2097).

## The headline metric

```
acceptance rate = merged PRs / decided PRs
```

where a PR is **decided** when it reached a terminal state — merged, or closed
without merging. **Open PRs are excluded from the denominator.** An undecided PR
has not been rejected; counting it as one would make the rate move with pipeline
latency instead of with reviewer intent, which is the opposite of what a quality
gate needs to measure.

The exit-code contract:

| Exit | Meaning | Why not a failure |
| --- | --- | --- |
| `0` | Metrics computed | — |
| `1` | Bad usage / unreadable input | The caller is wrong; fail loudly |
| `2` | Window holds no decided PR | Nothing to measure yet (fresh repo, brand-new window). Reported, not fatal |

## Companion signals

These explain *why* the acceptance rate moved, and are all computed on merged PRs
unless noted:

| Metric | Meaning |
| --- | --- |
| `medianHoursToFirstReview` | Created → earliest review submission. Queue latency. |
| `medianHoursToMerge` | Created → merged. Total PR throughput time. |
| `medianHoursOpen` | Created → closed, over **decided** PRs (merged and rejected). |
| `meanReviewRoundsOnMerged` | Mean number of submitted reviews on merged PRs. |
| `draftShare` | Fraction of all PRs in the window opened as drafts. |
| `byAuthor[].acceptanceRate` | Per-author rate, to spot a single reviewer carrying the metric. |

Medians, not means: one PR that sat for a month would otherwise dominate a
90-day window.

## Running it locally

The script takes zero dependencies and reads the JSON shape `gh pr list --json`
already produces:

```bash
gh pr list --state all --limit 500 \
  --json number,title,author,createdAt,closedAt,mergedAt,reviews,isDraft \
  > /tmp/prs.json

node scripts/pr-metrics.mjs --input /tmp/prs.json --markdown      # human summary
node scripts/pr-metrics.mjs --input /tmp/prs.json --output metrics.json
gh pr list --state all --limit 200 --json ... | node scripts/pr-metrics.mjs --markdown
```

Tests (Node's built-in runner, no test framework dependency):

```bash
node --test 'scripts/*.test.mjs'
```

## Publication

`.github/workflows/pr-metrics.yml` runs the script on two triggers:

- **Weekly (Mondays 06:17 UTC)** and on manual dispatch — a rolling window over
  the last 500 PRs, written to the workflow run summary and uploaded as the
  `pr-metrics` artifact (`metrics.json` + the raw `prs.json`, so any later
  re-analysis can be reproduced from the same inputs).
- **On `pull_request: closed`** — the acceptance rate is commented onto the PR
  that just closed, so the number is visible where the decision was made.
  The comment carries an HTML marker and the workflow refuses to post a second
  one.

The workflow is read-only apart from that single PR comment: it never pushes,
and it holds no write scopes beyond `pull-requests: write`.

## Known limits

- The window is the **last 500 PRs**, not a calendar period. On a repo this busy
  that is roughly the last several months; on a quiet one it is everything, and
  the "rolling window" label should be read as "recent history".
- Reviews are attributed from the `reviews` array only. Reviews submitted after
  merge, and review comments that never turn into a review, are not counted.
  `gh pr list --json reviews` is not always populated (it is a per-PR field the
  list endpoint frequently leaves empty); when `medianHoursToFirstReview` and
  `meanReviewRoundsOnMerged` come back `n/a` on a repo with real review activity,
  that is the cause, not a bug in the metric.
- An author with a single merged PR shows `acceptanceRate: 1.0`. The per-author
  table is for spotting outliers, not for ranking individuals — a rate over a
  handful of PRs is noise.
