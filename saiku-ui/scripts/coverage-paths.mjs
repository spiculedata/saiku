import { dirname, relative, resolve, sep } from 'node:path';

export function sourcePath(root, path) {
	const normalized = relative(root, resolve(path)).split(sep).join('/');
	return normalized.startsWith('src/') && !normalized.includes('/node_modules/')
		? normalized
		: null;
}

// SvelteKit copies client chunks from .svelte-kit/output/client to dist without
// rewriting the source-map paths. Resolve them against their build location.
export function restoreSourceMapPaths(root, dist, script, sourceMap) {
	const buildDirectory = resolve(
		root,
		'.svelte-kit/output/client',
		relative(dist, dirname(script))
	);
	return {
		...sourceMap,
		sourceRoot: undefined,
		sources: sourceMap.sources.map((source) => resolve(buildDirectory, source))
	};
}

export function statementLines(coverage) {
	const lines = new Map();
	for (const [id, statement] of Object.entries(coverage.statementMap)) {
		const line = statement.start.line;
		lines.set(line, Math.max(lines.get(line) ?? 0, coverage.s[id] ?? 0));
	}
	return lines;
}
