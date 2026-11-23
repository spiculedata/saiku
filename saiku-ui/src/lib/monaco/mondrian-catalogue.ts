/*
 * In-memory projection of the live AI Query cube catalogue, shaped for Monaco
 * completion (saiku#1428 Model IDE). Kept separate from `$lib/api/aiCubes` so
 * the completion provider depends on a small, synchronous, easily-mocked
 * surface rather than juggling promises inside `provideCompletionItems`.
 */
import type { AiCubeDetail, AiCubeSummary } from '$lib/api/aiCubes';

export interface CatalogueCube {
	cubeName: string;
	cubeId: string;
	connectionName: string;
	measures: string[];
	dimensions: string[];
	levels: string[];
}

/** Populated incrementally: summaries arrive first (cube names for `<Cube name="…">`),
 *  then per-cube detail (measure/dimension/level names) is fetched for whichever cubes
 *  the open file actually defines — see `extractCubeNames` / `findEnclosingCubeName`
 *  below, used together by the IDE page to prefetch just those cubes' detail. */
export class MondrianCatalogue {
	private cubes = new Map<string, CatalogueCube>();

	setSummaries(summaries: AiCubeSummary[]): void {
		for (const s of summaries) {
			const existing = this.cubes.get(s.cubeName);
			this.cubes.set(s.cubeName, {
				cubeName: s.cubeName,
				cubeId: [s.connectionName, s.catalog, s.schema, s.cubeName].join('/'),
				connectionName: s.connectionName,
				measures: existing?.measures ?? [],
				dimensions: existing?.dimensions ?? [],
				levels: existing?.levels ?? []
			});
		}
	}

	setDetail(cubeName: string, detail: AiCubeDetail): void {
		const existing = this.cubes.get(cubeName);
		if (!existing) return;
		const dimensions = Object.keys(detail.dimensions ?? {});
		const levels: string[] = [];
		for (const dim of Object.values(detail.dimensions ?? {})) {
			for (const hier of Object.values(dim.hierarchies ?? {})) {
				levels.push(...Object.keys(hier.levels ?? {}));
			}
		}
		this.cubes.set(cubeName, {
			...existing,
			measures: Object.keys(detail.measures ?? {}),
			dimensions,
			levels
		});
	}

	get(cubeName: string): CatalogueCube | undefined {
		return this.cubes.get(cubeName);
	}

	cubeNames(): string[] {
		return Array.from(this.cubes.keys());
	}

	isEmpty(): boolean {
		return this.cubes.size === 0;
	}
}

/**
 * Find the name of the `<Cube name="…">` block that contains `offset`, so completion
 * inside `<Measure>`/`<Dimension>`/`<Level>` can be scoped to the right cube instead of
 * mixing every cube in the file together.
 *
 * Deliberately a plain scan rather than a real XML parser: the buffer being edited is
 * frequently malformed mid-keystroke (that's what the linter is for), and a strict
 * parser would throw away completion on exactly the documents where it's most wanted.
 * Self-closing `<Cube .../>` blocks contain nothing, so they never become "enclosing".
 */
export function findEnclosingCubeName(text: string, offset: number): string | null {
	const head = text.slice(0, offset);
	const cubeTagRe = /<Cube\b([^>]*?)(\/?)>/g;
	const closeTagRe = /<\/Cube>/g;
	const opens: { pos: number; name: string | null }[] = [];
	let m: RegExpExecArray | null;
	while ((m = cubeTagRe.exec(head))) {
		const selfClosing = m[2] === '/';
		if (selfClosing) continue;
		const nameMatch = /\bname\s*=\s*"([^"]*)"/.exec(m[1]) ?? /\bname\s*=\s*'([^']*)'/.exec(m[1]);
		opens.push({ pos: m.index, name: nameMatch ? nameMatch[1] : null });
	}
	let closeCount = 0;
	while (closeTagRe.exec(head)) closeCount++;
	// Each </Cube> closes the innermost still-open <Cube>. Mondrian schemas don't nest
	// cubes, so in practice `opens` has at most one unclosed entry, but this stays
	// correct even for a malformed buffer with stray tags.
	const openStack = opens.slice(closeCount);
	if (openStack.length === 0) return null;
	return openStack[openStack.length - 1].name;
}

/**
 * Every `<Cube name="…">` (and self-closing `<Cube name="…"/>`) declared anywhere in
 * `text`, in document order, de-duplicated. The IDE page uses this on file load to know
 * which cubes' detail to prefetch from `/ai/schema/{cubeId}` — the alternative, fetching
 * detail lazily as the cursor enters each block, would need Monaco cursor-position
 * events wired through the editor component for a saving that only matters on very large
 * multi-cube schema files.
 */
export function extractCubeNames(text: string): string[] {
	const names = new Set<string>();
	const cubeTagRe = /<Cube\b([^>]*?)\/?>/g;
	let m: RegExpExecArray | null;
	while ((m = cubeTagRe.exec(text))) {
		const nameMatch = /\bname\s*=\s*"([^"]*)"/.exec(m[1]) ?? /\bname\s*=\s*'([^']*)'/.exec(m[1]);
		if (nameMatch) names.add(nameMatch[1]);
	}
	return Array.from(names);
}
