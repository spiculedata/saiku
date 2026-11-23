import { readFile, readdir, writeFile } from 'node:fs/promises';
import { resolve, sep, join } from 'node:path';
import v8ToIstanbul from 'v8-to-istanbul';
import { restoreSourceMapPaths, sourcePath, statementLines } from './coverage-paths.mjs';

const root = process.cwd();
const dist = resolve(root, 'dist');
const raw = join(root, 'coverage', 'browser', 'raw');
const reports = (await readdir(raw)).filter((name) => name.endsWith('.json'));
if (!reports.length) throw new Error('No mocked-browser V8 coverage reports were recorded');

export function lcov(files) {
	return (
		[...files.entries()]
			.sort(([a], [b]) => a.localeCompare(b))
			.map(([path, lines]) => {
				const entries = [...lines.entries()].sort(([a], [b]) => a - b);
				return [
					`SF:${path}`,
					...entries.map(([line, count]) => `DA:${line},${count}`),
					`LF:${entries.length}`,
					`LH:${entries.filter(([, count]) => count > 0).length}`,
					'end_of_record'
				].join('\n');
			})
			.join('\n') + '\n'
	);
}

const files = new Map();
let mappedScripts = 0;
for (const report of reports) {
	const entries = JSON.parse(await readFile(join(raw, report), 'utf8'));
	for (const entry of entries) {
		let pathname;
		try {
			pathname = new URL(entry.url).pathname;
		} catch {
			continue;
		}
		if (!pathname.endsWith('.js')) continue;
		const script = resolve(dist, `.${decodeURIComponent(pathname)}`);
		if (!script.startsWith(dist + sep)) continue;
		let sourceMap;
		try {
			sourceMap = JSON.parse(await readFile(`${script}.map`, 'utf8'));
		} catch {
			continue;
		}
		const restored = restoreSourceMapPaths(root, dist, script, sourceMap);
		const converter = v8ToIstanbul(script, 0, {
			source: entry.source,
			sourceMap: { sourcemap: restored }
		});
		await converter.load();
		converter.applyCoverage(entry.functions);
		for (const [original, coverage] of Object.entries(converter.toIstanbul())) {
			const path = sourcePath(root, original);
			if (!path) continue;
			mappedScripts++;
			const lines = files.get(path) ?? new Map();
			for (const [line, count] of statementLines(coverage)) {
				// A line can appear in several chunks/tests. Keep presence and a
				// useful hit count without treating an uncovered duplicate as zero.
				lines.set(line, (lines.get(line) ?? 0) + count);
			}
			files.set(path, lines);
		}
	}
}
if (!mappedScripts)
	throw new Error('No browser scripts mapped back to saiku-ui/src; check the source-map build');
await writeFile(join(root, 'coverage', 'browser', 'lcov.info'), lcov(files));
console.log(`Mapped browser coverage for ${files.size} source files`);
