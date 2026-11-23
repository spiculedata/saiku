// Tests for the PR acceptance metric (saiku#2097). Run: `node --test scripts/`
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { computeMetrics, hoursBetween, isDecided, isRejected, main, median, toMarkdown } from './pr-metrics.mjs'

const SCRIPT = new URL('./pr-metrics.mjs', import.meta.url).pathname

test('rejected = closed without merge; decided = either terminal state', () => {
  assert.equal(isRejected({ closedAt: '2026-01-02T00:00:00Z', mergedAt: null }), true)
  assert.equal(isRejected({ closedAt: '2026-01-02T00:00:00Z', mergedAt: '2026-01-02T00:00:00Z' }), false)
  assert.equal(isRejected({ closedAt: null, mergedAt: null }), false)
  assert.equal(isDecided({ closedAt: null, mergedAt: null }), false)
  assert.equal(isDecided({ closedAt: null, mergedAt: '2026-01-02T00:00:00Z' }), true)
})

test('open pull requests are excluded from the acceptance denominator', () => {
  const metrics = computeMetrics([
    { number: 1, mergedAt: '2026-01-02T00:00:00Z', createdAt: '2026-01-01T00:00:00Z', author: { login: 'a' } },
    { number: 2, closedAt: '2026-01-02T00:00:00Z', mergedAt: null, createdAt: '2026-01-01T00:00:00Z', author: { login: 'a' } },
    { number: 3, closedAt: null, mergedAt: null, createdAt: '2026-01-01T00:00:00Z', author: { login: 'b' } },
  ])
  assert.equal(metrics.decided, 2)
  assert.equal(metrics.merged, 1)
  assert.equal(metrics.rejected, 1)
  assert.equal(metrics.open, 1)
  assert.equal(metrics.acceptanceRate, 0.5)
})

test('latency signals are measured on merged pull requests', () => {
  const metrics = computeMetrics([
    {
      number: 1,
      author: { login: 'a' },
      createdAt: '2026-01-01T00:00:00Z',
      mergedAt: '2026-01-01T12:00:00Z',
      reviews: [{ submittedAt: '2026-01-01T02:00:00Z' }, { submittedAt: '2026-01-01T09:00:00Z' }],
    },
    {
      number: 2,
      author: { login: 'a' },
      createdAt: '2026-01-01T00:00:00Z',
      mergedAt: '2026-01-01T20:00:00Z',
      reviews: [{ submittedAt: '2026-01-01T06:00:00Z' }],
    },
  ])
  assert.equal(metrics.acceptanceRate, 1)
  assert.equal(hoursBetween('2026-01-01T00:00:00Z', '2026-01-01T12:00:00Z'), 12)
  assert.equal(metrics.medianHoursToFirstReview, 4) // median of 2h, 6h
  assert.equal(metrics.medianHoursToMerge, 16) // median of 12h, 20h
  assert.equal(metrics.meanReviewRoundsOnMerged, 1.5)
})

test('median handles odd, even and empty inputs', () => {
  assert.equal(median([5, 1, 3]), 3)
  assert.equal(median([1, 2, 3, 4]), 2.5)
  assert.equal(median([]), null)
  assert.equal(median([null, 'x', 2]), 2)
})

test('per-author breakdown is sorted by merged count', () => {
  const metrics = computeMetrics([
    { number: 1, author: 'zoe', mergedAt: '2026-01-02T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    { number: 2, author: 'amy', mergedAt: '2026-01-02T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    { number: 3, author: 'amy', mergedAt: '2026-01-03T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
    { number: 4, author: 'bob', closedAt: '2026-01-03T00:00:00Z', mergedAt: null, createdAt: '2026-01-01T00:00:00Z' },
  ])
  assert.deepEqual(
    metrics.byAuthor.map((e) => e.author),
    ['amy', 'zoe', 'bob'],
  )
  assert.equal(metrics.byAuthor[0].acceptanceRate, 1)
  assert.equal(metrics.byAuthor[2].acceptanceRate, 0)
})

test('markdown summary leads with the acceptance rate', () => {
  const md = toMarkdown(computeMetrics([
    { number: 1, author: 'amy', createdAt: '2026-01-01T00:00:00Z', mergedAt: '2026-01-02T00:00:00Z', reviews: [{ submittedAt: '2026-01-01T01:00:00Z' }] },
    { number: 2, author: 'bob', createdAt: '2026-01-01T00:00:00Z', closedAt: '2026-01-02T00:00:00Z', mergedAt: null },
  ]))
  assert.match(md, /\*\*Acceptance rate: 50\.0%\*\*/)
  assert.match(md, /\| Acceptance rate \| 50\.0% \|/)
  assert.match(md, /\| amy \|/)
})

test('CLI reads a {pullRequests:[...]} file, writes JSON and exits 0', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pr-metrics-'))
  const input = join(dir, 'prs.json')
  const output = join(dir, 'metrics.json')
  writeFileSync(
    input,
    JSON.stringify({
      pullRequests: [
        { number: 1, author: { login: 'amy' }, createdAt: '2026-01-01T00:00:00Z', mergedAt: '2026-01-02T00:00:00Z' },
      ],
    }),
  )
  const stdout = execFileSync(process.execPath, [SCRIPT, '--input', input, '--output', output], { encoding: 'utf8' })
  assert.equal(JSON.parse(stdout).acceptanceRate, 1)
  assert.equal(JSON.parse(execFileSync('cat', [output], { encoding: 'utf8' })).merged, 1)
})

test('CLI exits 2 (not 1) when the window holds no decided pull requests', () => {
  const dir = mkdtempSync(join(tmpdir(), 'pr-metrics-open-'))
  const input = join(dir, 'prs.json')
  writeFileSync(input, JSON.stringify([{ number: 1, createdAt: '2026-01-01T00:00:00Z', closedAt: null, mergedAt: null }]))

  const logs = []
  const errors = []
  const code = main(['--input', input, '--markdown'], { log: (m) => logs.push(m), error: (m) => errors.push(m) })
  assert.equal(code, 2)
  assert.match(logs[0], /Acceptance rate: n\/a/)
  assert.match(errors[0], /nothing to measure yet/)

  const metrics = computeMetrics([{ number: 1, createdAt: '2026-01-01T00:00:00Z' }])
  assert.equal(metrics.decided, 0)
  assert.equal(metrics.acceptanceRate, null)
  assert.match(toMarkdown(metrics), /Acceptance rate: n\/a/)
})

test('CLI reports usage errors as exit 1', () => {
  const errors = []
  const code = main(['--nope'], { log: () => {}, error: (m) => errors.push(m) })
  assert.equal(code, 1)
  assert.match(errors[0], /Unknown argument: --nope/)
})
