import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ui = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'saiku-ui');
const read = (path) => readFileSync(join(ui, path), 'utf8');
const pkg = JSON.parse(read('package.json'));

// C1: both commands and browser instrumentation are available, and each suite
// writes the provenance needed to compare reports from the same revision.
assert.match(pkg.scripts['coverage:unit'], /coverage-run\.mjs unit/);
assert.match(pkg.scripts['coverage:browser'], /coverage-run\.mjs browser/);
assert.match(read('scripts/coverage-run.mjs'), /suite, revision: sha, command, generatedAt/);
assert.match(read('scripts/coverage-run.mjs'), /--coverage\.reporter=lcov/);
assert.match(read('scripts/coverage-run.mjs'), /--sourcemap/);
assert.match(read('e2e/fixtures/coverage.ts'), /startJSCoverage/);
assert.match(read('scripts/browser-coverage.mjs'), /lcov\.info/);

// C2: run the real comparison CLI against two small LCOV reports. This also
// verifies that the command rejects a browser report from another revision.
const dir = mkdtempSync(join(tmpdir(), 'saiku-ui-coverage-'));
try {
	for (const suite of ['unit', 'browser']) mkdirSync(join(dir, 'coverage', suite), { recursive: true });
	const provenance = (suite, revision) =>
		writeFileSync(join(dir, 'coverage', suite, 'provenance.json'), JSON.stringify({ suite, revision }));
	provenance('unit', 'same-revision');
	provenance('browser', 'same-revision');
	writeFileSync(join(dir, 'coverage', 'unit', 'lcov.info'), 'SF:src/example.ts\nDA:2,3\nDA:4,0\nend_of_record\n');
	writeFileSync(join(dir, 'coverage', 'browser', 'lcov.info'), 'SF:src/example.ts\nDA:2,0\nDA:3,1\nend_of_record\n');
	const compare = () => spawnSync('node', [join(ui, 'scripts/compare-coverage.mjs')], { cwd: dir, encoding: 'utf8' });
	const result = compare();
	assert.equal(result.status, 0, result.stderr);
	const report = JSON.parse(readFileSync(join(dir, 'coverage', 'comparison.json'), 'utf8'));
	assert.equal(report.revision, 'same-revision');
	assert.deepEqual(report.rows, [
		{ path: 'src/example.ts', line: 2, unit: 3, browser: 0 },
		{ path: 'src/example.ts', line: 3, unit: 0, browser: 1 },
		{ path: 'src/example.ts', line: 4, unit: 0, browser: 0 }
	]);
	provenance('browser', 'different-revision');
	assert.notEqual(compare().status, 0);
} finally {
	rmSync(dir, { recursive: true, force: true });
}
assert.match(read('COVERAGE.md'), /coverage:all/);
assert.match(read('COVERAGE.md'), /source.map|source map|sourcemap/i);
process.stdout.write('acceptance/2196: coverage wiring and line comparison pass\n');
