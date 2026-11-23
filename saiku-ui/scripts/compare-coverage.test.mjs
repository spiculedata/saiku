import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compare, parseLcov } from './compare-coverage.mjs';
import { restoreSourceMapPaths, sourcePath, statementLines } from './coverage-paths.mjs';

test('compares line hits from both suites, retaining one-suite lines', () => {
	const unit = parseLcov('SF:src/example.ts\nDA:2,3\nDA:4,0\nend_of_record\n');
	const browser = parseLcov('SF:src/example.ts\nDA:2,0\nDA:3,1\nend_of_record\n');
	assert.deepEqual(compare(unit, browser), [
		{ path: 'src/example.ts', line: 2, unit: 3, browser: 0 },
		{ path: 'src/example.ts', line: 3, unit: 0, browser: 1 },
		{ path: 'src/example.ts', line: 4, unit: 0, browser: 0 }
	]);
});

test('ignores records outside the application source tree', () => {
	const records = parseLcov('SF:node_modules/tool.js\nDA:1,1\nend_of_record\n');
	assert.equal(records.size, 0);
});

test('restores copied SvelteKit source-map paths to application source', () => {
	const root = '/checkout/saiku-ui';
	const dist = `${root}/dist`;
	const script = `${dist}/_app/immutable/chunks/app.js`;
	const map = restoreSourceMapPaths(root, dist, script, {
		sources: ['../../../../../../src/lib/example.ts']
	});
	assert.equal(sourcePath(root, map.sources[0]), 'src/lib/example.ts');
});

test('uses the highest statement hit count for a mapped source line', () => {
	const lines = statementLines({
		statementMap: {
			0: { start: { line: 4 } },
			1: { start: { line: 4 } },
			2: { start: { line: 5 } }
		},
		s: { 0: 0, 1: 3, 2: 0 }
	});
	assert.deepEqual(
		[...lines],
		[
			[4, 3],
			[5, 0]
		]
	);
});
