import type { SaikuDimension, SaikuMeasure } from '$lib/api/discover';

/**
 * Cube-grounded schema fed to {@link buildMdxCompletions}. Callers (the MDX
 * workbench route, and eventually the toolbar MDXModal) populate this from
 * {@code datasources.metadata(username, cube)} — the same measures/dimensions
 * payload the sidebar's DimensionList already renders — so completions never
 * drift from what the cube actually exposes.
 */
export interface MdxCompletionContext {
	measures: SaikuMeasure[];
	dimensions: SaikuDimension[];
}

export const EMPTY_MDX_COMPLETION_CONTEXT: MdxCompletionContext = {
	measures: [],
	dimensions: []
};

export type MdxCompletionKind = 'measure' | 'dimension' | 'hierarchy' | 'level';

export interface MdxCompletionCandidate {
	kind: MdxCompletionKind;
	/** What the picker shows the user. */
	label: string;
	/** Secondary text (caption when it differs from the raw name, etc). */
	detail?: string;
	/**
	 * Text to splice in at the cursor, AFTER removing
	 * {@link MdxCompletionResult.replacePrefixLength} characters immediately
	 * before it. Already includes the closing `]` (and the opening `[` when
	 * the user hasn't typed one yet) so the member reference is left
	 * well-formed either way.
	 */
	insertText: string;
}

export interface MdxCompletionResult {
	/** Number of characters immediately before the cursor that the chosen
	 *  candidate's insertText replaces. 0 means "insert, don't replace". */
	replacePrefixLength: number;
	candidates: MdxCompletionCandidate[];
}

const NO_RESULT: MdxCompletionResult = { replacePrefixLength: 0, candidates: [] };

const MEASURES_UNIQUE_NAME = 'measures';

/** One completed `[segment].` in a bracket chain. */
const CHAIN_SEGMENT = /\[([^[\]]*)\]\./;

interface ParsedContext {
	/** Names of the fully-closed `[segment]` links leading up to the cursor
	 *  (e.g. `['Time', 'Calendar']` for `[Time].[Calendar].`). */
	chain: string[];
	/** True when the cursor sits right after an unmatched `[` — i.e. the user
	 *  is mid-way through typing the next bracketed name. */
	insideBracket: boolean;
	/** Whatever's already been typed for the in-progress segment — the text
	 *  after the last `[` (insideBracket) or after the last `.` (bare typing,
	 *  no bracket opened yet). */
	prefix: string;
}

function extractChain(segmentsText: string): string[] {
	const names: string[] = [];
	const re = new RegExp(CHAIN_SEGMENT, 'g');
	let m: RegExpExecArray | null;
	while ((m = re.exec(segmentsText))) names.push(m[1]);
	return names;
}

/**
 * Reads backward from the cursor to classify what the user is in the middle
 * of typing: a fresh bracket (`[Mea`), a segment right after a closed chain's
 * dot (`[Measures].` or `[Measures].Sa`), or neither (returns null — free
 * text, no completion to offer).
 */
function parseContext(textBeforeCursor: string): ParsedContext | null {
	// Mid-bracket: `(...[seg].)*[partial` with no closing `]` yet.
	const openMatch = /((?:\[[^[\]]*\]\.)*)\[([^[\]]*)$/.exec(textBeforeCursor);
	if (openMatch) {
		return { chain: extractChain(openMatch[1]), insideBracket: true, prefix: openMatch[2] };
	}
	// Bare typing right after a closed chain's dot: `(...[seg].)+partial?`
	// where partial has no brackets/dots of its own.
	const dotMatch = /((?:\[[^[\]]*\]\.)+)([A-Za-z0-9_ ]*)$/.exec(textBeforeCursor);
	if (dotMatch) {
		return { chain: extractChain(dotMatch[1]), insideBracket: false, prefix: dotMatch[2] };
	}
	return null;
}

function matchesPrefix(name: string, prefix: string): boolean {
	return !prefix || name.toLowerCase().includes(prefix.toLowerCase());
}

/** True when `name` names the given dimension — matched against its schema
 *  name (the bracket-safe identifier), not the display caption. */
function dimensionMatches(dim: SaikuDimension, name: string): boolean {
	return dim.name.toLowerCase() === name.toLowerCase();
}

function hierarchyMatches(hier: { name: string }, name: string): boolean {
	return hier.name.toLowerCase() === name.toLowerCase();
}

function wrap(name: string, insideBracket: boolean): string {
	return insideBracket ? `${name}]` : `[${name}]`;
}

/**
 * Cube-grounded MDX member completions (saiku#1106 phase 2).
 *
 * Pure function over the raw editor text so it's testable without Monaco:
 * given everything typed up to the cursor and the active cube's schema,
 * returns the member/measure candidates for whatever bracket segment the
 * user is currently typing, plus how many trailing characters a chosen
 * candidate should replace.
 *
 * Scope matches the issue: measures, dimensions, hierarchies, levels. Member
 * VALUES (e.g. `[Time].[2024]`) aren't offered — that would need a live
 * member-listing round-trip per keystroke, which the phase-2 spec didn't ask
 * for.
 */
export function buildMdxCompletions(
	textBeforeCursor: string,
	ctx: MdxCompletionContext
): MdxCompletionResult {
	const parsed = parseContext(textBeforeCursor);
	if (!parsed) return NO_RESULT;
	const { chain, insideBracket, prefix } = parsed;
	const replacePrefixLength = prefix.length;

	if (chain.length === 0) {
		const candidates: MdxCompletionCandidate[] = [];
		if (matchesPrefix('Measures', prefix)) {
			candidates.push({
				kind: 'measure',
				label: 'Measures',
				insertText: wrap('Measures', insideBracket)
			});
		}
		for (const dim of ctx.dimensions) {
			if (!matchesPrefix(dim.name, prefix) && !matchesPrefix(dim.caption ?? '', prefix)) continue;
			candidates.push({
				kind: 'dimension',
				label: dim.caption || dim.name,
				detail: dim.name,
				insertText: wrap(dim.name, insideBracket)
			});
		}
		return { replacePrefixLength, candidates };
	}

	if (chain.length === 1 && chain[0].toLowerCase() === MEASURES_UNIQUE_NAME) {
		const candidates: MdxCompletionCandidate[] = ctx.measures
			.filter((m) => matchesPrefix(m.name, prefix) || matchesPrefix(m.caption ?? '', prefix))
			.map((m) => ({
				kind: 'measure' as const,
				label: m.caption || m.name,
				detail: m.name,
				insertText: wrap(m.name, insideBracket)
			}));
		return { replacePrefixLength, candidates };
	}

	if (chain.length === 1) {
		const dim = ctx.dimensions.find((d) => dimensionMatches(d, chain[0]));
		if (!dim) return NO_RESULT;
		const candidates: MdxCompletionCandidate[] = dim.hierarchies
			.filter((h) => matchesPrefix(h.name, prefix) || matchesPrefix(h.caption ?? '', prefix))
			.map((h) => ({
				kind: 'hierarchy' as const,
				label: h.caption || h.name,
				detail: h.name,
				insertText: wrap(h.name, insideBracket)
			}));
		return { replacePrefixLength, candidates };
	}

	if (chain.length === 2) {
		const dim = ctx.dimensions.find((d) => dimensionMatches(d, chain[0]));
		const hier = dim?.hierarchies.find((h) => hierarchyMatches(h, chain[1]));
		if (!hier?.levels) return NO_RESULT;
		const candidates: MdxCompletionCandidate[] = hier.levels
			.filter((l) => matchesPrefix(l.name, prefix) || matchesPrefix(l.caption ?? '', prefix))
			.map((l) => ({
				kind: 'level' as const,
				label: l.caption || l.name,
				detail: l.name,
				insertText: wrap(l.name, insideBracket)
			}));
		return { replacePrefixLength, candidates };
	}

	// Deeper than dimension.hierarchy.level (member navigation) is out of scope.
	return NO_RESULT;
}
