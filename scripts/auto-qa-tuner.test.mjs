// Tests for the auto-QA tuner (saiku#2123). Run: `node --test scripts/`
import assert from 'node:assert/strict'
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { analyse, main, proposeCoverageFloor, proposeTestFloor } from './auto-qa-tuner.mjs'

const CFG = {
  signals: {
    testFloorHeadroom: { enabled: true, minHeadroomTests: 15, keepHeadroomTests: 5 },
    coverageHeadroom: { enabled: true, minHeadroomPercent: 3, keepHeadroomPercent: 1 },
    flakyTests: { enabled: true, maxReported: 20 },
  },
}

test('test floor is proposed only when headroom exceeds the minimum', () => {
  assert.equal(proposeTestFloor(100, 114, CFG.signals.testFloorHeadroom), null)
  assert.equal(proposeTestFloor(100, 130, CFG.signals.testFloorHeadroom), 125)
  assert.equal(proposeTestFloor(100, null, CFG.signals.testFloorHeadroom), null)
})

test('coverage floor keeps a one-point margin and never proposes a decrease', () => {
  assert.equal(proposeCoverageFloor(50, 52.9, CFG.signals.coverageHeadroom), null)
  assert.equal(proposeCoverageFloor(50, 60.4, CFG.signals.coverageHeadroom), 59)
  assert.equal(proposeCoverageFloor(80, 70, CFG.signals.coverageHeadroom), null)
})

test('analyse reports unmeasured modules and flaky tests', () => {
  const result = analyse({
    testFloors: { a: 10, b: 10 },
    coverageFloors: { a: 50 },
    measured: { a: { tests: 100, coverage: 70, flaky: ['X.y'] }, b: { tests: null, flaky: [] } },
    config: CFG,
  })
  assert.deepEqual(result.testProposals, [{ module: 'a', floor: 10, measured: 100, proposed: 95 }])
  assert.equal(result.coverageProposals[0].proposed, 69)
  assert.deepEqual(result.flaky, [{ module: 'a', test: 'X.y' }])
  assert.deepEqual(result.unmeasured, [{ module: 'b', signal: 'tests' }])
})

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'qa-tuner-'))
  mkdirSync(join(root, '.github'))
  cpSync(new URL('../.github/auto-qa-tuning.json', import.meta.url), join(root, '.github/auto-qa-tuning.json'))
  writeFileSync(join(root, '.github/test-floors.json'), JSON.stringify({ modules: { m: 10 } }))
  writeFileSync(join(root, '.coverage-thresholds.json'), JSON.stringify({ modules: { m: 40 } }))
  mkdirSync(join(root, 'm/target/surefire-reports'), { recursive: true })
  mkdirSync(join(root, 'm/target/site/jacoco'), { recursive: true })
  writeFileSync(
    join(root, 'm/target/surefire-reports/TEST-a.xml'),
    '<testsuite name="a" tests="50"><testcase name="t1" classname="A"><flakyFailure/></testcase><testcase name="t2" classname="A"/></testsuite>',
  )
  writeFileSync(
    join(root, 'm/target/site/jacoco/jacoco.csv'),
    'GROUP,PACKAGE,CLASS,IM,IC,BM,BC,LM,LC\ng,p,C,0,0,0,0,30,70\n',
  )
  return root
}

test('end to end: proposes bumps, lists flaky test, never edits floor files', () => {
  const root = fixture()
  const floorsBefore = readFileSync(join(root, '.github/test-floors.json'), 'utf8')
  let out = ''
  const code = main([], { root, stdout: (s) => (out += s) })
  assert.equal(code, 0)
  assert.match(out, /\| m \| 10 \| 50 \| 45 \|/)
  assert.match(out, /\| m \| 40 \| 70 \| 69 \|/)
  assert.match(out, /A\.t1/)
  assert.equal(readFileSync(join(root, '.github/test-floors.json'), 'utf8'), floorsBefore)
})

test('--dry-run with --out writes no report file', () => {
  const root = fixture()
  let out = ''
  main(['--out', 'report.md', '--dry-run'], { root, stdout: (s) => (out += s) })
  assert.match(out, /\[dry-run\] would write report/)
  assert.equal(existsSync(join(root, 'report.md')), false)
  main(['--out', 'report.md'], { root, stdout: () => {} })
  assert.equal(existsSync(join(root, 'report.md')), true)
})

test('unknown argument exits 1', () => {
  assert.equal(main(['--bogus'], { root: fixture(), stdout: () => {} }), 1)
})
