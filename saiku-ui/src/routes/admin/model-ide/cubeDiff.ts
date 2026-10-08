/**
 * Pure diff of two cube-name snapshots — split out of `+page.svelte` for unit testing.
 *
 * Used after Save → refresh to answer "what changed" for the datasource(s) bound to the
 * schema just edited: cubes present after refresh that weren't there before are additions,
 * cubes that were there before and are gone are removals. This is intentionally a much
 * smaller claim than the issue's "branch preview" (a temporary saiku-home overlay + a
 * discover-refresh run against it, diffed before merge) — that needs new backend work this
 * PR doesn't add (see the PR description). What this gives instead is a real, live
 * before/after comparison against the datasource(s) actually attached to the file being
 * edited, using the refresh endpoint that already exists.
 */

export interface CubeDiff {
	added: string[];
	removed: string[];
	unchanged: string[];
}

export function diffCubeNames(before: string[], after: string[]): CubeDiff {
	const beforeSet = new Set(before);
	const afterSet = new Set(after);
	return {
		added: after.filter((n) => !beforeSet.has(n)),
		removed: before.filter((n) => !afterSet.has(n)),
		unchanged: after.filter((n) => beforeSet.has(n))
	};
}
