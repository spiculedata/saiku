import { spawnSync } from 'node:child_process';
import { mkdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const mode = process.argv[2];
if (!['unit', 'browser', 'all'].includes(mode)) {
	throw new Error('Usage: node scripts/coverage-run.mjs unit|browser|all');
}

function run(command, args, env = {}) {
	const result = spawnSync(command, args, {
		stdio: 'inherit',
		env: { ...process.env, ...env },
		shell: process.platform === 'win32'
	});
	if (result.error) throw result.error;
	if (result.status !== 0) process.exit(result.status ?? 1);
}

const revision = spawnSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' });
if (revision.status !== 0) throw new Error('Cannot determine source revision');
const sha = revision.stdout.trim();

async function provenance(suite, command) {
	const directory = join('coverage', suite);
	await mkdir(directory, { recursive: true });
	await writeFile(
		join(directory, 'provenance.json'),
		JSON.stringify(
			{ suite, revision: sha, command, generatedAt: new Date().toISOString() },
			null,
			2
		) + '\n'
	);
}

if (mode === 'unit' || mode === 'all') {
	const command =
		'npx vitest run --coverage --coverage.provider=v8 --coverage.reporter=lcov --coverage.reportsDirectory=coverage/unit';
	run('npx', [
		'vitest',
		'run',
		'--coverage',
		'--coverage.provider=v8',
		'--coverage.reporter=lcov',
		'--coverage.reportsDirectory=coverage/unit'
	]);
	await provenance('unit', command);
}
if (mode === 'browser' || mode === 'all') {
	const command =
		'npm run build:app -- --sourcemap && npx playwright test && node scripts/browser-coverage.mjs';
	await rm(join('coverage', 'browser'), { recursive: true, force: true });
	run('npm', ['run', 'build:app', '--', '--sourcemap']);
	run('npx', ['playwright', 'test'], { SAIKU_COVERAGE: '1' });
	run('node', ['scripts/browser-coverage.mjs']);
	await provenance('browser', command);
}
if (mode === 'all') run('node', ['scripts/compare-coverage.mjs']);
