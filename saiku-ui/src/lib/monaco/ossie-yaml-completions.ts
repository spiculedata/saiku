/*
 * Schema-aware completion for Ossie (M4) semantic-model YAML (saiku#1428 Model IDE).
 *
 * The DECISION logic lives here, with no `monaco-editor` import, so it is unit-testable in
 * Vitest's node environment exactly like its Mondrian XML sibling
 * (`mondrian-xml-completions.ts`). The Monaco translation layer is `ossie-yaml-lang.ts`.
 *
 * Two sources feed the candidates:
 *  1. the structural grammar of an Ossie `semantic_model` document — which keys are valid at
 *     which nesting level (both envelopes `OssieYamlReader` accepts: a bare top-level
 *     `semantic_model`, and `ontology_mappings[].semantic_model`);
 *  2. the LIVE model, fetched from the discover endpoint for the edited datasource — so
 *     `name:` completions inside a dataset's `fields` offer the warehouse's real field names
 *     (`dataset[].fields[].name` from `OssieModelDto`), and a relationship's `from:`/`to:`
 *     offer real dataset names.
 *
 * Context is derived by indentation-stack scanning of the lines above the cursor — the same
 * trade-off `mondrian-xml-completions` makes (no full parser, cheap, deterministic, and
 * tolerant of the half-typed lines a completion request always contains).
 */

/** Field/dataset/metric names from the live model (`OssieModelDto`), for value completion. */
export interface OssieCatalogue {
	datasets: string[];
	/** Live field names per dataset (keyed by dataset name). */
	datasetFields: Record<string, string[]>;
	metrics: string[];
}

export interface OssieCompletionCandidate {
	label: string;
	kind: 'key' | 'value';
	/** Text inserted on accept — keys come with their trailing colon. */
	insertText: string;
	detail: string;
}

interface Frame {
	indent: number;
	key: string;
	/** The scalar value of a `name:` pair seen at this frame's level, if any. */
	name?: string;
}

const KEY_RE = /^(\s*)(-\s+)?([A-Za-z_][\w-]*)\s*:(.*)$/;

/** Which keys are valid inside which structural context. Ordered by authoring frequency. */
const CONTEXT_KEYS: Record<string, string[]> = {
	root: ['semantic_model', 'ontology_mappings', 'name', 'description', 'version'],
	ontology_mapping: ['name', 'description', 'semantic_model'],
	model: ['name', 'description', 'ai_context', 'datasets', 'metrics', 'relationships'],
	dataset: ['name', 'source', 'primary_key', 'description', 'fields', 'custom_extensions'],
	field: ['name', 'label', 'description', 'expression', 'custom_extensions'],
	metric: ['name', 'description', 'expression', 'aggregation_kind', 'custom_extensions'],
	relationship: ['name', 'from', 'to', 'from_columns', 'to_columns'],
	expression: ['dialects'],
	dialects: ['dialect', 'expression'],
	ai_context: ['instructions'],
	custom_extensions: ['vendor_name', 'data']
};

/** `dialect:` values. ANSI_SQL is the dialect this codebase itself emits everywhere
 *  (`LookmlConverter.DIALECT`, `MondrianToOssieConverter`); anything else is free text —
 *  Ossie's dialect matching is a string comparison. */
const DIALECTS = ['ANSI_SQL'];

/** `aggregation_kind:` values — the ones this repo emits (flights/tpcds seeds) and the
 *  LookML converter vocabulary (`LookmlConverter.AGGREGATIONS`). */
const AGGREGATIONS = ['sum', 'count', 'avg', 'min', 'max', 'count_distinct'];

function keysFor(context: string, label: string): OssieCompletionCandidate[] {
	return (CONTEXT_KEYS[context] ?? []).map((k) => ({
		label: k,
		kind: 'key' as const,
		insertText: `${k}: `,
		detail: `${label} key`
	}));
}

/**
 * Compute the enclosing key stack for the line at `lineIndex` (0-based) from the document
 * lines ABOVE it. Returns innermost-last, e.g. ['semantic_model', 'datasets'].
 */
export function enclosingContext(
	lines: string[],
	lineIndex: number
): { stack: Frame[]; dataset?: string } {
	const stack: Frame[] = [];
	let dataset: string | undefined;
	for (const line of lines.slice(0, lineIndex)) {
		const m = KEY_RE.exec(line);
		if (!m) continue;
		// Column of the key itself: after the dash for list items ("  - name:" → key at 4).
		const indent = m[1].length + (m[2] ? 2 : 0);
		const key = m[3];
		const value = m[4].trim();
		while (stack.length > 0 && stack[stack.length - 1].indent >= indent) stack.pop();
		if (value === '') stack.push({ indent, key });
		if (key === 'name' && value) {
			dataset = value.replace(/^["']|["']$/g, '');
		}
	}
	return { stack, dataset };
}

/** Map a key stack onto the completion context this module understands. */
function contextOf(stack: Frame[]): string {
	const keys = stack.map((f) => f.key);
	if (keys.includes('ontology_mappings')) {
		// ontology_mappings[i].semantic_model.<sub> — keep everything after semantic_model.
		const i = keys.lastIndexOf('semantic_model');
		if (i === -1) return 'ontology_mapping';
		return contextOfSuffix(keys.slice(i + 1));
	}
	const i = keys.indexOf('semantic_model');
	if (i === -1) return keys.length === 0 ? 'root' : 'model';
	return contextOfSuffix(keys.slice(i + 1));
}

function contextOfSuffix(suffix: string[]): string {
	if (suffix.length === 0) return 'model';
	const head = suffix[0];
	if (head === 'datasets')
		return suffix.length === 1 ? 'dataset' : contextOfSuffix(suffix.slice(1));
	if (head === 'metrics') return suffix.length === 1 ? 'metric' : contextOfSuffix(suffix.slice(1));
	if (head === 'relationships')
		return suffix.length === 1 ? 'relationship' : contextOfSuffix(suffix.slice(1));
	if (head === 'fields') return suffix.length === 1 ? 'field' : contextOfSuffix(suffix.slice(1));
	if (head === 'ai_context') return suffix.length === 1 ? 'ai_context' : 'model';
	if (head === 'expression') return suffix.length === 1 ? 'expression' : 'model';
	if (head === 'dialects') return suffix.length === 1 ? 'dialects' : 'model';
	if (head === 'custom_extensions') return 'custom_extensions';
	return 'model';
}

function valueCandidates(
	key: string,
	context: string,
	dataset: string | undefined,
	catalogue: OssieCatalogue
): OssieCompletionCandidate[] {
	if (key === 'dialect') {
		return DIALECTS.map((d) => ({
			label: d,
			kind: 'value' as const,
			insertText: d,
			detail: 'dialect'
		}));
	}
	if (key === 'aggregation_kind') {
		return AGGREGATIONS.map((a) => ({
			label: a,
			kind: 'value' as const,
			insertText: a,
			detail: 'aggregation kind'
		}));
	}
	if ((key === 'from' || key === 'to') && context === 'relationship') {
		return catalogue.datasets.map((d) => ({
			label: d,
			kind: 'value' as const,
			insertText: d,
			detail: 'dataset'
		}));
	}
	if (key === 'name' && context === 'field' && dataset) {
		const live = catalogue.datasetFields[dataset] ?? [];
		return live.map((f) => ({
			label: f,
			kind: 'value' as const,
			insertText: f,
			detail: `field on dataset "${dataset}"`
		}));
	}
	return [];
}

/**
 * Candidates for the cursor position.
 *
 * @param lines    the document's lines
 * @param lineIndex 0-based index of the line the cursor is on
 * @param linePrefix the current line's text BEFORE the cursor
 */
export function completeOssieYaml(
	lines: string[],
	lineIndex: number,
	linePrefix: string,
	catalogue: OssieCatalogue
): OssieCompletionCandidate[] {
	const { stack, dataset } = enclosingContext(lines, lineIndex);
	const context = contextOf(stack);

	// A `key:` (empty or missing value) on this line asks for VALUE completion.
	const inline = KEY_RE.exec(linePrefix);
	if (inline && inline[4].trim() === '') {
		return valueCandidates(inline[3], context, dataset, catalogue);
	}
	// A list-item dash with nothing after it, or a bare/indented empty line, asks for KEY
	// completion at the enclosing context.
	const label = context === 'root' ? 'document' : context === 'model' ? 'semantic_model' : context;
	return keysFor(context, label);
}
