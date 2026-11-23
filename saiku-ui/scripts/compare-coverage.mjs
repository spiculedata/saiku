import { readFile, writeFile } from 'node:fs/promises';
import { join, resolve, relative, sep } from 'node:path';

const root = process.cwd();
export function normalizeSource(path) {
	const absolute = resolve(root, path);
	const normalized = relative(root, absolute).split(sep).join('/');
	return normalized.startsWith('src/') ? normalized : null;
}

export function parseLcov(input) {
	const files = new Map();
	let path;
	for (const record of input.split(/\r?\n/)) {
		if (record.startsWith('SF:')) path = normalizeSource(record.slice(3));
		else if (record.startsWith('DA:') && path) {
			const [line, count] = record.slice(3).split(',').map(Number);
			if (!files.has(path)) files.set(path, new Map());
			const lines = files.get(path);
			lines.set(line, (lines.get(line) ?? 0) + count);
		} else if (record === 'end_of_record') path = undefined;
	}
	return files;
}

export function compare(unit, browser) {
	const rows = [];
	for (const path of new Set([...unit.keys(), ...browser.keys()])) {
		const unitLines = unit.get(path) ?? new Map();
		const browserLines = browser.get(path) ?? new Map();
		for (const line of new Set([...unitLines.keys(), ...browserLines.keys()])) {
			rows.push({
				path,
				line,
				unit: unitLines.get(line) ?? 0,
				browser: browserLines.get(line) ?? 0
			});
		}
	}
	return rows.sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
}

if (process.argv[1] && resolve(process.argv[1]) === new URL(import.meta.url).pathname) {
	const [unitProvenance, browserProvenance, unitLcov, browserLcov] = await Promise.all([
		readFile(join(root, 'coverage', 'unit', 'provenance.json'), 'utf8').then(JSON.parse),
		readFile(join(root, 'coverage', 'browser', 'provenance.json'), 'utf8').then(JSON.parse),
		readFile(join(root, 'coverage', 'unit', 'lcov.info'), 'utf8'),
		readFile(join(root, 'coverage', 'browser', 'lcov.info'), 'utf8')
	]);
	if (unitProvenance.revision !== browserProvenance.revision) {
		throw new Error('Unit and browser coverage came from different revisions');
	}
	const rows = compare(parseLcov(unitLcov), parseLcov(browserLcov));
	await writeFile(
		join(root, 'coverage', 'comparison.json'),
		JSON.stringify({ revision: unitProvenance.revision, rows }, null, 2) + '\n'
	);
	console.log(`Compared ${rows.length} source lines from ${unitProvenance.revision}`);
}
