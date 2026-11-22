/*
 * Client-side structural lint for Ossie (M4) semantic-model YAML (saiku#1428 Model IDE).
 *
 * Mirrors the role of `mondrian-xml-lint.ts` on the XML side: deliberately NOT a full server
 * compile. The server already rejects a malformed document at save time (`OssieSchemaResource`
 * runs `OssieYamlReader.readString` and 400s), but that only fires after you press Save — this
 * linter catches the class of mistake that would otherwise surface as an opaque save rejection
 * or a silently empty model after refresh, on every keystroke, as Monaco squiggles + a
 * Problems pane:
 *
 *  - YAML syntax errors, positioned via the `yaml` parser's own error offsets;
 *  - the envelope questions: is there a `semantic_model` (or `ontology_mappings` wrapping one,
 *    both shapes `OssieYamlReader` accepts) at all;
 *  - the identity questions: dataset / field / metric / relationship entries missing a `name`,
 *    or carrying duplicate names within their collection;
 *  - the dangling-reference question: a relationship whose `from`/`to` names a dataset the
 *    model doesn't declare (warning — the model still loads, the join just never resolves).
 *
 * When the document has syntax errors the structural walk is skipped: the `yaml` package
 * recovers partial trees, but positions recovered from a broken tree point at the wrong
 * thing more often than not, and the parse errors themselves already dominate the pane.
 */

import { parseDocument, isMap, isSeq, isScalar, type Node, type Pair } from 'yaml';

export type LintSeverity = 'error' | 'warning';

export interface LintIssue {
	severity: LintSeverity;
	message: string;
	startLine: number;
	startColumn: number;
	endLine: number;
	endColumn: number;
}

function lineStarts(text: string): number[] {
	const starts = [0];
	for (let i = 0; i < text.length; i++) {
		if (text[i] === '\n') starts.push(i + 1);
	}
	return starts;
}

/** 1-based line/column of a char offset, matching Monaco's coordinate system. */
function offsetToPos(starts: number[], offset: number): { line: number; column: number } {
	// Binary search for the last line start <= offset.
	let lo = 0;
	let hi = starts.length - 1;
	while (lo < hi) {
		const mid = (lo + hi + 1) >> 1;
		if (starts[mid] <= offset) lo = mid;
		else hi = mid - 1;
	}
	return { line: lo + 1, column: offset - starts[lo] + 1 };
}

function issueAtOffset(
	starts: number[],
	text: string,
	severity: LintSeverity,
	message: string,
	offset: number
): LintIssue {
	const at = offsetToPos(starts, Math.min(offset, Math.max(text.length - 1, 0)));
	return {
		severity,
		message,
		startLine: at.line,
		startColumn: at.column,
		endLine: at.line,
		endColumn: at.column + 1
	};
}

function issueAtNode(
	starts: number[],
	text: string,
	severity: LintSeverity,
	message: string,
	node: Node | null
): LintIssue {
	const offset = node?.range ? node.range[0] : 0;
	return issueAtOffset(starts, text, severity, message, offset);
}

/* --------------------------- node helpers --------------------------- */

function scalarText(node: Node | undefined | null): string | null {
	if (!node || !isScalar(node)) return null;
	const v = node.value;
	if (v === null || v === undefined) return null;
	return String(v);
}

function mapPairs(node: Node | undefined | null): Pair[] {
	return node && isMap(node) ? node.items : [];
}

function pairValue(map: Node, key: string): Node | undefined {
	for (const p of mapPairs(map)) {
		if (isScalar(p.key) && p.key.value === key) return p.value as Node | undefined;
	}
	return undefined;
}

function hasPair(map: Node, key: string): boolean {
	return mapPairs(map).some((p) => isScalar(p.key) && p.key.value === key);
}

/** The YAML nodes of every item of a sequence-valued key, or `[]` when absent/not a seq. */
function seqItems(map: Node, key: string): Node[] {
	const v = pairValue(map, key);
	return v && isSeq(v) ? (v.items as Node[]) : [];
}

/** Items of a sequence-valued node anywhere in the walk (same narrowing workaround). */
function asSeq(v: Node | undefined | null): Node[] {
	return v && isSeq(v) ? (v.items as Node[]) : [];
}

/* ------------------------- structural walk -------------------------- */

function checkNamedEntry(
	issues: LintIssue[],
	starts: number[],
	text: string,
	entry: Node,
	kind: string,
	names: Map<string, Node>
): void {
	if (!isMap(entry)) {
		issues.push(issueAtNode(starts, text, 'error', `${kind} entry is not a mapping`, entry));
		return;
	}
	const name = scalarText(pairValue(entry, 'name'));
	if (!name) {
		issues.push(
			issueAtNode(
				starts,
				text,
				'error',
				`${kind} entry is missing a "name" — it cannot be referenced anywhere`,
				entry
			)
		);
		return;
	}
	const prior = names.get(name);
	if (prior) {
		issues.push(
			issueAtNode(
				starts,
				text,
				'error',
				`Duplicate ${kind} name "${name}" — the later entry shadows the first on load`,
				entry
			)
		);
		return;
	}
	names.set(name, entry);
}

export function lintOssieYaml(text: string): LintIssue[] {
	const starts = lineStarts(text);
	const issues: LintIssue[] = [];
	const doc = parseDocument(text);

	for (const err of doc.errors) {
		const offset = Array.isArray(err.pos) ? err.pos[0] : 0;
		issues.push(
			issueAtOffset(
				starts,
				text,
				'error',
				`YAML syntax: ${err.message.split(' at line')[0]}`,
				offset
			)
		);
	}
	if (doc.errors.length > 0) return issues;

	const root = doc.contents;
	if (!root || !isMap(root)) {
		issues.push(
			issueAtOffset(
				starts,
				text,
				'error',
				'No semantic model here — expected a top-level "semantic_model" (or "ontology_mappings" wrapping one)',
				0
			)
		);
		return issues;
	}

	// Collect model maps from both accepted envelopes: a bare top-level `semantic_model`
	// (map, or a list of maps — the tpcds seed uses the list form) and/or
	// `ontology_mappings[].semantic_model` (the flights seed form).
	const modelNodes: Node[] = [];
	const smTop = pairValue(root, 'semantic_model');
	const omTop = pairValue(root, 'ontology_mappings');
	if (smTop) modelNodes.push(...asSeq(smTop));
	if (omTop && isSeq(omTop)) {
		for (const wrapper of omTop.items as Node[]) {
			const sm = isMap(wrapper) ? pairValue(wrapper, 'semantic_model') : undefined;
			if (sm) modelNodes.push(...asSeq(sm));
		}
	}
	if (!smTop && !omTop) {
		issues.push(
			issueAtNode(
				starts,
				text,
				'error',
				'Expected a top-level "semantic_model" or "ontology_mappings" key — nothing here will load as a model',
				root
			)
		);
		return issues;
	}

	for (const model of modelNodes) {
		lintModel(issues, starts, text, model);
	}
	return issues;
}

function lintModel(issues: LintIssue[], starts: number[], text: string, model: Node): void {
	if (!isMap(model)) {
		issues.push(issueAtNode(starts, text, 'error', 'semantic_model entry is not a mapping', model));
		return;
	}
	const name = scalarText(pairValue(model, 'name'));
	if (!name) {
		issues.push(
			issueAtNode(
				starts,
				text,
				'error',
				'semantic_model is missing a "name" — datasources bind to it by model name',
				model
			)
		);
	}

	const datasets = seqItems(model, 'datasets');
	if (datasets.length === 0) {
		issues.push(
			issueAtNode(
				starts,
				text,
				'warning',
				'Model declares no datasets — it will load but the workbench will have nothing to query',
				model
			)
		);
	}

	const datasetNames = new Map<string, Node>();
	for (const ds of datasets) {
		if (!isMap(ds)) {
			issues.push(issueAtNode(starts, text, 'error', 'dataset entry is not a mapping', ds));
			continue;
		}
		checkNamedEntry(issues, starts, text, ds, 'dataset', datasetNames);
		const dsName = scalarText(pairValue(ds, 'name')) ?? '';

		const fields = seqItems(ds, 'fields');
		if (fields.length === 0) {
			issues.push(
				issueAtNode(
					starts,
					text,
					'warning',
					`Dataset${dsName ? ` "${dsName}"` : ''} declares no fields — nothing to drag onto a shelf`,
					ds
				)
			);
		}
		const fieldNames = new Map<string, Node>();
		for (const field of fields) {
			checkNamedEntry(issues, starts, text, field, 'field', fieldNames);
		}
	}

	const metricNames = new Map<string, Node>();
	for (const metric of seqItems(model, 'metrics')) {
		if (!isMap(metric)) {
			issues.push(issueAtNode(starts, text, 'error', 'metric entry is not a mapping', metric));
			continue;
		}
		checkNamedEntry(issues, starts, text, metric, 'metric', metricNames);
		if (!hasPair(metric, 'expression')) {
			issues.push(
				issueAtNode(
					starts,
					text,
					'warning',
					`Metric${scalarText(pairValue(metric, 'name')) ? ` "${scalarText(pairValue(metric, 'name'))}"` : ''} has no expression — it will not aggregate anything`,
					metric
				)
			);
		}
	}

	const knownDatasets = new Set(datasetNames.keys());
	for (const rel of seqItems(model, 'relationships')) {
		if (!isMap(rel)) {
			issues.push(issueAtNode(starts, text, 'error', 'relationship entry is not a mapping', rel));
			continue;
		}
		for (const end of ['from', 'to'] as const) {
			const target = scalarText(pairValue(rel, end));
			if (!target) {
				issues.push(
					issueAtNode(
						starts,
						text,
						'error',
						`relationship is missing "${end}" — the join cannot be built`,
						rel
					)
				);
			} else if (!knownDatasets.has(target)) {
				issues.push(
					issueAtNode(
						starts,
						text,
						'warning',
						`relationship ${end}="${target}" names no dataset declared in this model — the join never resolves`,
						rel
					)
				);
			}
		}
	}
}
