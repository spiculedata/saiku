/*
 * Pure completion logic for Mondrian schema XML (saiku#1428 Model IDE), split out of
 * `mondrian-xml-lang.ts` so it can be unit tested without importing `monaco-editor` —
 * that package touches `window` at module load time and cannot be imported under
 * Vitest's `node` test environment (see `mondrian-xml-lang.ts` for the Monaco-facing
 * half that maps this module's output onto `monaco.languages.CompletionItem`).
 */
import { findEnclosingCubeName, type MondrianCatalogue } from './mondrian-catalogue';

/** Element → attribute names Mondrian actually reads, for structural (non-catalogue)
 *  completion. Not exhaustive — the long tail of rarely-used attributes is left to the
 *  user; this covers what an author reaches for constantly. */
export const ELEMENT_ATTRIBUTES: Record<string, string[]> = {
	Schema: ['name', 'description'],
	Cube: ['name', 'caption', 'description', 'visible', 'cache'],
	VirtualCube: ['name', 'caption', 'description'],
	Table: ['name', 'schema', 'alias'],
	View: ['alias'],
	Dimension: ['name', 'caption', 'description', 'foreignKey', 'type', 'visible'],
	DimensionUsage: ['name', 'source', 'foreignKey', 'visible'],
	Hierarchy: ['name', 'hasAll', 'allMemberName', 'primaryKey', 'caption'],
	Level: [
		'name',
		'column',
		'nameColumn',
		'ordinalColumn',
		'captionColumn',
		'type',
		'uniqueMembers',
		'levelType',
		'caption'
	],
	Measure: ['name', 'column', 'aggregator', 'formatString', 'caption', 'visible'],
	MeasureGroup: ['name', 'table'],
	NamedSet: ['name', 'formula'],
	CalculatedMember: ['name', 'dimension', 'formula', 'caption', 'visible'],
	Join: ['leftKey', 'rightKey', 'leftAlias', 'rightAlias']
};

export const ELEMENT_NAMES = Object.keys(ELEMENT_ATTRIBUTES);

/**
 * Elements whose `name="…"` attribute is worth completing from the live catalogue.
 *
 * The issue's proposed shape asks for completion on `<Measure column="…">` / `<Level
 * column="…">` (physical DB column names) as well — this deliberately does not attempt
 * that: `column` names the underlying table/view column, which the `/ai/cubes` catalogue
 * doesn't carry (it describes the semantic model, not the physical one) and there is no
 * live datasource-profiling wired into the Model IDE yet. Completing `name="…"` against
 * the same catalogue's measure/dimension/level names is the well-formed live-schema
 * completion this data actually supports; DB-column completion is follow-up work that
 * needs the cube-designer's profiling path (`ossCubeDesignerBackend.profileConnection`)
 * wired in here too.
 */
const NAME_REF_ATTRS = new Set(['Cube', 'Dimension', 'Hierarchy', 'Level', 'Measure']);

export interface AttributeContext {
	element: string;
	attr: string | null;
	inValue: boolean;
}

/** Attribute context at the cursor, parsed from the current line's text up to the cursor —
 *  good enough for completion, which only ever looks at one line. Returns null when the
 *  cursor isn't inside a tag at all. */
export function parseAttributeContext(lineToCursor: string): AttributeContext | null {
	const tagMatch = /<([A-Za-z_][\w.-]*)\s+((?:[^<>]*))$/.exec(lineToCursor);
	if (!tagMatch) return null;
	const element = tagMatch[1];
	const tail = tagMatch[2];
	const valueMatch = /([A-Za-z_][\w.-]*)\s*=\s*"([^"]*)$/.exec(tail);
	if (valueMatch) {
		return { element, attr: valueMatch[1], inValue: true };
	}
	return { element, attr: null, inValue: false };
}

/** True when the cursor is positioned to start (or is mid-typing) an element name after `<`. */
export function isElementOpenContext(lineToCursor: string): boolean {
	return /<([A-Za-z_][\w.-]*)?$/.test(lineToCursor);
}

export type CompletionKind = 'element' | 'attribute' | 'value';

export interface CompletionCandidate {
	label: string;
	/** Plain text to insert for `element`/`value`; a `$1`-style snippet for `attribute`. */
	insertText: string;
	kind: CompletionKind;
	detail?: string;
}

/**
 * Compute completion candidates for the cursor position described by `lineToCursor` /
 * `fullText` / `offset`. Framework-agnostic: the Monaco-facing caller supplies the text
 * slices (Monaco's own APIs for "text on this line up to the cursor" and "offset at
 * position") and turns the result into `monaco.languages.CompletionItem[]`.
 */
export function computeCompletionCandidates(params: {
	lineToCursor: string;
	fullText: string;
	offset: number;
	catalogue: MondrianCatalogue;
}): CompletionCandidate[] {
	const { lineToCursor, fullText, offset, catalogue } = params;

	if (isElementOpenContext(lineToCursor)) {
		return ELEMENT_NAMES.map((name) => ({ label: name, insertText: name, kind: 'element' }));
	}

	const ctx = parseAttributeContext(lineToCursor);
	if (!ctx) return [];

	if (!ctx.inValue) {
		const attrs = ELEMENT_ATTRIBUTES[ctx.element] ?? [];
		return attrs.map((name) => ({
			label: name,
			insertText: `${name}="$1"`,
			kind: 'attribute'
		}));
	}

	if (ctx.attr !== 'name' || !NAME_REF_ATTRS.has(ctx.element)) return [];

	if (ctx.element === 'Cube') {
		return catalogue.cubeNames().map((name) => ({
			label: name,
			insertText: name,
			kind: 'value',
			detail: 'live cube'
		}));
	}

	const cubeName = findEnclosingCubeName(fullText, offset);
	const cube = cubeName ? catalogue.get(cubeName) : undefined;
	if (!cube) return [];

	const pool =
		ctx.element === 'Measure'
			? cube.measures
			: ctx.element === 'Level'
				? cube.levels
				: cube.dimensions;
	return pool.map((name) => ({
		label: name,
		insertText: name,
		kind: 'value',
		detail: `live ${ctx.element.toLowerCase()} · ${cubeName}`
	}));
}
