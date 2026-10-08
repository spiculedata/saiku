#!/usr/bin/env node
// PR acceptance metric (saiku#2097, ACMM criterion acmm:pr-acceptance-metric).
//
// Zero-dependency Node ESM script: reads pull requests as JSON (either a raw
// array or { "pullRequests": [...] }, as produced by `gh pr list --json`)
// and computes the acceptance feedback-loop metric — plus the companion
// latency/review signals that explain it. See docs/metrics.md.
//
//   node scripts/pr-metrics.mjs --input prs.json --markdown
//   node scripts/pr-metrics.mjs --input prs.json --output metrics.json
//   gh pr list --state all --limit 200 --json \
//     number,title,author,createdAt,closedAt,mergedAt,reviewDecision,reviews \
//     | node scripts/pr-metrics.mjs --markdown
//
// Exits 0 on success, 1 on bad input, 2 when the window holds no decided PR
// (nothing to measure yet — not a failure of the pipeline).

import { readFileSync, writeFileSync } from 'node:fs'

const EXIT_OK = 0
const EXIT_USAGE = 1
const EXIT_NO_DATA = 2

/** Pull requests that reached a terminal, unmerged state ("was rejected"). */
export function isRejected(pr) {
  return Boolean(pr.closedAt) && !pr.mergedAt
}

/** Pull requests that reached a terminal state at all ("decided"). */
export function isDecided(pr) {
  return Boolean(pr.mergedAt) || isRejected(pr)
}

export function hoursBetween(from, to) {
  if (!from || !to) return null
  const delta = (new Date(to).getTime() - new Date(from).getTime()) / 3_600_000
  return Number.isFinite(delta) && delta >= 0 ? delta : null
}

/** Median of a numeric array; null when empty. Even counts average the middle pair. */
export function median(values) {
  const sorted = values.filter((v) => typeof v === 'number' && Number.isFinite(v)).sort((a, b) => a - b)
  if (sorted.length === 0) return null
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

export function mean(values) {
  const nums = values.filter((v) => typeof v === 'number' && Number.isFinite(v))
  return nums.length === 0 ? null : nums.reduce((a, b) => a + b, 0) / nums.length
}

/** Number of human review rounds: a round is a review plus any push after it. */
export function reviewRounds(pr) {
  const reviews = Array.isArray(pr.reviews) ? pr.reviews : []
  return reviews.length
}

/** Normalise assorted gh shapes into one flat record. */
export function normalise(pr) {
  const author = typeof pr.author === 'string' ? pr.author : (pr.author?.login ?? pr.user?.login ?? 'unknown')
  return {
    number: pr.number ?? null,
    title: pr.title ?? '',
    author,
    createdAt: pr.createdAt ?? null,
    closedAt: pr.closedAt ?? null,
    mergedAt: pr.mergedAt ?? null,
    state: pr.mergedAt ? 'MERGED' : pr.closedAt ? 'CLOSED' : 'OPEN',
    reviewDecision: pr.reviewDecision ?? null,
    reviews: Array.isArray(pr.reviews) ? pr.reviews : [],
    additions: Number.isFinite(pr.additions) ? pr.additions : null,
    deletions: Number.isFinite(pr.deletions) ? pr.deletions : null,
    changedFiles: Number.isFinite(pr.changedFiles) ? pr.changedFiles : null,
    isDraft: Boolean(pr.isDraft),
  }
}

/**
 * The core metric set.
 *
 * `acceptanceRate` is the headline: merged PRs over *decided* PRs. Open PRs are
 * excluded from the denominator — an undecided PR has not been rejected yet,
 * and counting it would make the rate drift with pipeline latency rather than
 * with reviewer intent.
 */
export function computeMetrics(prs) {
  const all = (Array.isArray(prs) ? prs : []).map(normalise)
  const decided = all.filter(isDecided)
  const merged = decided.filter((pr) => Boolean(pr.mergedAt))
  const rejected = decided.filter(isRejected)

  const byAuthor = new Map()
  for (const pr of decided) {
    const entry = byAuthor.get(pr.author) ?? { merged: 0, rejected: 0 }
    if (pr.mergedAt) entry.merged += 1
    else entry.rejected += 1
    byAuthor.set(pr.author, entry)
  }

  const acceptedRounds = merged.map((pr) => reviewRounds(pr)).filter((n) => n > 0)
  const acceptedAuthors = new Set(merged.map((pr) => pr.author))

  return {
    total: all.length,
    open: all.length - decided.length,
    decided: decided.length,
    merged: merged.length,
    rejected: rejected.length,
    acceptanceRate: decided.length === 0 ? null : merged.length / decided.length,
    medianHoursToFirstReview: median(merged.map((pr) => hoursBetween(pr.createdAt, firstReviewAt(pr)))),
    medianHoursToMerge: median(merged.map((pr) => hoursBetween(pr.createdAt, pr.mergedAt))),
    medianHoursOpen: median(decided.map((pr) => hoursBetween(pr.createdAt, pr.closedAt))),
    meanReviewRoundsOnMerged: mean(acceptedRounds),
    draftShare: all.length === 0 ? null : all.filter((pr) => pr.isDraft).length / all.length,
    authorsWithMergedPrs: acceptedAuthors.size,
    byAuthor: [...byAuthor.entries()]
      .map(([author, e]) => ({
        author,
        merged: e.merged,
        rejected: e.rejected,
        acceptanceRate: (e.merged + e.rejected) === 0 ? null : e.merged / (e.merged + e.rejected),
      }))
      .sort((a, b) => b.merged - a.merged || a.author.localeCompare(b.author)),
  }
}

function firstReviewAt(pr) {
  const stamps = pr.reviews.map((r) => r.submittedAt ?? r.createdAt).filter(Boolean)
  if (stamps.length === 0) return null
  return stamps.sort()[0]
}

/** Human-facing summary, GitHub-flavoured markdown (step summary or PR comment). */
export function toMarkdown(metrics) {
  const pct = (v) => (v === null ? 'n/a' : `${(v * 100).toFixed(1)}%`)
  const hrs = (v) => (v === null ? 'n/a' : `${v.toFixed(1)} h`)
  const lines = [
    '## PR acceptance metric',
    '',
    `**Acceptance rate: ${pct(metrics.acceptanceRate)}** — ${metrics.merged} merged / ${metrics.decided} decided PRs ` +
      `(${metrics.open} still open, excluded from the denominator).`,
    '',
    '| Metric | Value |',
    '| --- | --- |',
    `| Acceptance rate | ${pct(metrics.acceptanceRate)} |`,
    `| Accepted / rejected / open | ${metrics.merged} / ${metrics.rejected} / ${metrics.open} |`,
    `| Median time to first review (merged) | ${hrs(metrics.medianHoursToFirstReview)} |`,
    `| Median time to merge (merged) | ${hrs(metrics.medianHoursToMerge)} |`,
    `| Median time open (decided) | ${hrs(metrics.medianHoursOpen)} |`,
    `| Mean review rounds on merged PRs | ${metrics.meanReviewRoundsOnMerged === null ? 'n/a' : metrics.meanReviewRoundsOnMerged.toFixed(1)} |`,
    `| Authors with at least one merged PR | ${metrics.authorsWithMergedPrs} |`,
    '',
    ...(metrics.byAuthor.length
      ? [
          '| Author | Merged | Rejected | Acceptance |',
          '| --- | --- | --- | --- |',
          ...metrics.byAuthor.slice(0, 15).map((e) => `| ${e.author} | ${e.merged} | ${e.rejected} | ${pct(e.acceptanceRate)} |`),
        ]
      : []),
    '',
    '_Generated by `scripts/pr-metrics.mjs` — see `docs/metrics.md`._',
  ]
  return lines.join('\n')
}

function parseArgs(argv) {
  const args = { input: null, output: null, markdown: false }
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i]
    if (arg === '--input' || arg === '-i') args.input = argv[++i]
    else if (arg === '--output' || arg === '-o') args.output = argv[++i]
    else if (arg === '--markdown' || arg === '-m') args.markdown = true
    else if (arg === '--help' || arg === '-h') args.help = true
    else throw new Error(`Unknown argument: ${arg}`)
  }
  return args
}

export function loadPullRequests(source) {
  const text = source === '-' ? readFileSync(0, 'utf8') : readFileSync(source, 'utf8')
  const parsed = JSON.parse(text)
  if (Array.isArray(parsed)) return parsed
  if (parsed && Array.isArray(parsed.pullRequests)) return parsed.pullRequests
  if (parsed && Array.isArray(parsed.items)) return parsed.items
  throw new Error('Expected a JSON array of pull requests, or an object with a "pullRequests" array')
}

const USAGE = `Usage: pr-metrics.mjs --input <prs.json|-> [--output <metrics.json>] [--markdown]`

export function main(argv, { log = console.log, error = console.error } = {}) {
  let args
  try {
    args = parseArgs(argv)
  } catch (e) {
    error(`pr-metrics: ${e.message}\n${USAGE}`)
    return EXIT_USAGE
  }
  if (args.help) {
    log(USAGE)
    return EXIT_OK
  }

  let prs
  try {
    const raw = args.input ?? '-'
    prs = loadPullRequests(raw)
  } catch (e) {
    error(`pr-metrics: could not read pull requests (${args.input ?? '<stdin>'}): ${e.message}`)
    return EXIT_USAGE
  }

  const metrics = computeMetrics(prs)
  if (args.output) writeFileSync(args.output, `${JSON.stringify(metrics, null, 2)}\n`)
  if (args.markdown) log(toMarkdown(metrics))
  else log(JSON.stringify(metrics, null, 2))

  // No decided PR yet (fresh repo, or a brand new window): report, don't fail CI.
  if (metrics.decided === 0) {
    error('pr-metrics: no decided pull requests in the window — nothing to measure yet')
    return EXIT_NO_DATA
  }
  return EXIT_OK
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)))
}
