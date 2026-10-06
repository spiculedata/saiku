#!/usr/bin/env node
// Auto-QA tuner (saiku#2123, ACMM criterion acmm:auto-qa-tuning).
//
// Zero-dependency Node ESM script. Reads the surefire / JaCoCo output that
// `mvn verify` leaves under each module's target/ directory, compares it with
// the declared ratchets (.github/test-floors.json, .coverage-thresholds.json)
// and PROPOSES floor bumps plus a flaky-test list as a Markdown (or JSON) report.
//
// It is advisory only: it NEVER edits a floor file, never pushes, and exits 0
// on a successful analysis, so it cannot block a build. A human applies any
// proposed bump in a normal PR. Tuning knobs live in .github/auto-qa-tuning.json.
//
//   mvn -B -ntp -DskipITs=false verify && node scripts/auto-qa-tuner.mjs
//   node scripts/auto-qa-tuner.mjs --json
//   node scripts/auto-qa-tuner.mjs --out tuning-report.md             # also write the report file
//   node scripts/auto-qa-tuner.mjs --out tuning-report.md --dry-run   # say what would be written, write nothing
//
// Exit codes: 0 analysed (with or without proposals), 1 bad usage/config.

import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const EXIT_OK = 0
const EXIT_USAGE = 1
const DEFAULT_CONFIG = '.github/auto-qa-tuning.json'
const FLAKY_TAGS = ['flakyFailure', 'flakyError', 'rerunFailure', 'rerunError']

export function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'))
}

function reportFiles(reportsDir) {
  if (!existsSync(reportsDir)) return []
  return readdirSync(reportsDir).filter((f) => /^TEST-.*\.xml$/.test(f))
}

/** Sum `tests="N"` over every <testsuite> in a module's surefire XML reports; null when none exist. */
export function countTests(reportsDir) {
  const files = reportFiles(reportsDir)
  if (files.length === 0) return null
  let total = 0
  for (const f of files) {
    const m = /<testsuite\b[^>]*?\btests="(\d+)"/.exec(readFileSync(join(reportsDir, f), 'utf8'))
    if (m) total += Number(m[1])
  }
  return total
}

/** Testcases carrying rerun/flaky markers, as "Class.method" strings. */
export function findFlaky(reportsDir) {
  const flaky = []
  const caseRe = /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g
  for (const f of reportFiles(reportsDir)) {
    const xml = readFileSync(join(reportsDir, f), 'utf8')
    for (const m of xml.matchAll(caseRe)) {
      const body = m[2] ?? ''
      if (!FLAKY_TAGS.some((t) => body.includes(`<${t}`))) continue
      const name = /\bname="([^"]*)"/.exec(m[1])?.[1] ?? '?'
      const cls = /\bclassname="([^"]*)"/.exec(m[1])?.[1] ?? '?'
      flaky.push(`${cls}.${name}`)
    }
  }
  return flaky
}

/** Aggregate JaCoCo CSV line coverage (percent); null when the report is missing/empty. */
export function lineCoverage(csvPath) {
  if (!existsSync(csvPath)) return null
  const rows = readFileSync(csvPath, 'utf8').trim().split('\n').slice(1)
  let missed = 0
  let covered = 0
  for (const row of rows) {
    const cols = row.split(',')
    missed += Number(cols[7]) || 0
    covered += Number(cols[8]) || 0
  }
  const total = missed + covered
  return total === 0 ? null : (100 * covered) / total
}

/** Proposed test floor for one module, or null when there is no worthwhile bump. */
export function proposeTestFloor(floor, measured, cfg) {
  if (measured === null || !cfg.enabled) return null
  if (measured - floor <= cfg.minHeadroomTests) return null
  const proposed = measured - cfg.keepHeadroomTests
  return proposed > floor ? proposed : null
}

/** Proposed coverage floor (whole percent) for one module, or null. */
export function proposeCoverageFloor(floor, measured, cfg) {
  if (measured === null || !cfg.enabled) return null
  if (measured - floor <= cfg.minHeadroomPercent) return null
  const proposed = Math.floor(measured) - cfg.keepHeadroomPercent
  return proposed > floor ? proposed : null
}

/** Pure analysis over already-loaded inputs: no filesystem access. */
export function analyse({ testFloors, coverageFloors, measured, config }) {
  const { testFloorHeadroom, coverageHeadroom, flakyTests } = config.signals
  const testProposals = []
  const coverageProposals = []
  const unmeasured = []
  for (const [module, floor] of Object.entries(testFloors)) {
    const tests = measured[module]?.tests ?? null
    if (tests === null) {
      unmeasured.push({ module, signal: 'tests' })
      continue
    }
    const proposed = proposeTestFloor(floor, tests, testFloorHeadroom)
    if (proposed !== null) testProposals.push({ module, floor, measured: tests, proposed })
  }
  for (const [module, floor] of Object.entries(coverageFloors)) {
    const coverage = measured[module]?.coverage ?? null
    if (coverage === null) {
      unmeasured.push({ module, signal: 'coverage' })
      continue
    }
    const proposed = proposeCoverageFloor(floor, coverage, coverageHeadroom)
    if (proposed !== null) coverageProposals.push({ module, floor, measured: Number(coverage.toFixed(2)), proposed })
  }
  const flaky = flakyTests.enabled
    ? Object.entries(measured).flatMap(([module, m]) => (m.flaky ?? []).map((test) => ({ module, test })))
    : []
  return { testProposals, coverageProposals, flaky: flaky.slice(0, flakyTests.maxReported), unmeasured }
}

function proposalTable(proposals) {
  if (proposals.length === 0) return ['None: no module has enough headroom.', '']
  return [
    '| Module | Floor | Measured | Proposed |',
    '|---|---|---|---|',
    ...proposals.map((p) => `| ${p.module} | ${p.floor} | ${p.measured} | ${p.proposed} |`),
    '',
  ]
}

export function toMarkdown(result) {
  const lines = ['# Auto-QA tuning report', '', '_Advisory only. Nothing was edited; apply any bump in a normal PR._', '']
  lines.push('## Test-count floor proposals', '', ...proposalTable(result.testProposals))
  lines.push('## Coverage floor proposals (line %)', '', ...proposalTable(result.coverageProposals))
  lines.push('## Flaky tests', '')
  if (result.flaky.length === 0) lines.push('None detected in the surefire reports.', '')
  else lines.push(...result.flaky.map((f) => `- ${f.module}: ${f.test}`), '')
  if (result.unmeasured.length > 0) {
    lines.push('## Not measured', '', 'No report found (run `mvn verify` first):', '')
    lines.push(...result.unmeasured.map((u) => `- ${u.module} (${u.signal})`), '')
  }
  return lines.join('\n')
}

function parseArgs(argv) {
  const opts = { config: DEFAULT_CONFIG, json: false, dryRun: false, out: null }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--json') opts.json = true
    else if (a === '--dry-run') opts.dryRun = true
    else if (a === '--out') opts.out = argv[++i]
    else if (a === '--config') opts.config = argv[++i]
    else throw new Error(`unknown argument: ${a}`)
  }
  return opts
}

export function main(argv, { root = process.cwd(), stdout = (s) => process.stdout.write(s) } = {}) {
  try {
    const opts = parseArgs(argv)
    const config = readJson(resolve(root, opts.config))
    const testFloors = readJson(resolve(root, config.floorsFile)).modules ?? {}
    const coverageFloors = readJson(resolve(root, config.coverageThresholdsFile)).modules ?? {}
    const measured = {}
    for (const module of new Set([...Object.keys(testFloors), ...Object.keys(coverageFloors)])) {
      const reports = resolve(root, config.surefireReportsGlob.replace('{module}', module))
      const csv = resolve(root, config.jacocoCsv.replace('{module}', module))
      measured[module] = { tests: countTests(reports), flaky: findFlaky(reports), coverage: lineCoverage(csv) }
    }
    const result = analyse({ testFloors, coverageFloors, measured, config })
    const output = opts.json ? JSON.stringify(result, null, 2) + '\n' : toMarkdown(result)
    stdout(output)
    if (opts.out && opts.dryRun) stdout(`\n[dry-run] would write report to ${opts.out}; no file written\n`)
    else if (opts.out) writeFileSync(resolve(root, opts.out), output)
    return EXIT_OK
  } catch (err) {
    process.stderr.write(`auto-qa-tuner: ${err.message}\n`)
    return EXIT_USAGE
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2), { root: resolve(dirname(fileURLToPath(import.meta.url)), '..') })
}
