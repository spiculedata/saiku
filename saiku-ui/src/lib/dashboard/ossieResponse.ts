/*
 * Ossie response → AiQueryResponse adapter (saiku#1803).
 *
 * The two AI query surfaces DO NOT share a response envelope. That is worth
 * saying plainly, because it is easy to assume they do — the cells are
 * identical, the docs describe the surfaces as "the same shape", and a tile
 * bound to a model looks like any other tile:
 *
 *   MDX   /ai/query        { status, metadata: { columns, rows, measures },
 *                            data: [...], totalRows, runtimeMs }
 *   Ossie /ai/ossie/query  { queryId, columns: [...], records: [...],
 *                            meta: { rowCount, truncated }, runtime }
 *
 * Rows are `records` not `data`; the column descriptors are top-level, not
 * under `metadata`; there is no `status` at all on success. What DOES match is
 * the thing that matters most: a measure cell is `{value, formatted}` and a
 * row-header value is a plain string, which is exactly what
 * projectFromAiQueryResponse() keys off to tell a category from a measure.
 *
 * So the adaptation is a wrapper rename, and every renderer downstream stays
 * untouched — which is the property the feature rests on.
 *
 * ## Record keys become their labels
 *
 * A record arrives keyed by the query's own coordinates —
 * `{"carrier.carrier_name": "Delta", "flight_count": {...}}` — and those keys
 * are what a chart draws on its category axis. The `columns` descriptors carry
 * a human `label` ("Carrier"), so keys are renamed to labels where that is
 * unambiguous, giving the same shape an MDX result has
 * (`{"Store City": "...", "Store Sqft": {...}}`). Where two columns would
 * collide on one label the raw keys are kept — a duplicated key would silently
 * drop a column.
 *
 * Pure: no DOM, no fetches.
 */

import type { AiQueryResponse } from '$lib/api/aiQuery';

/** One column descriptor in an Ossie response. */
interface OssieColumn {
	key?: string;
	label?: string;
	type?: string;
	aggregationKind?: string;
}

/** The raw Ossie envelope, as far as this module reads it. */
interface RawOssieResponse {
	queryId?: string;
	columns?: OssieColumn[];
	records?: Array<Record<string, unknown>>;
	meta?: { rowCount?: number; truncated?: boolean };
	runtime?: number;
	/* Error variants — see toAiQueryResponse. */
	status?: string;
	error?: string;
	message?: string;
	field?: string;
	available?: string[];
}

/** True when the payload is one of the endpoint's error shapes rather than a result. */
function isErrorPayload(raw: RawOssieResponse): boolean {
	// Two shapes reach the client:
	//   1. the validator's own  { error: "VALIDATION_ERROR", field, message, available }
	//   2. the generic mapper's { status: "VALIDATION_ERROR", error: "...", field, available }
	// Neither carries `records`, which is the reliable discriminator.
	return !Array.isArray(raw.records) && (raw.error != null || raw.status != null);
}

/** Map column key → display label, keeping keys when labels would collide. */
function labelByKey(columns: OssieColumn[]): Record<string, string> {
	const labels = columns.map((c) => c.label ?? c.key ?? '');
	const out: Record<string, string> = {};
	for (const c of columns) {
		const key = c.key;
		if (!key) continue;
		const label = c.label ?? key;
		const ambiguous = labels.filter((l) => l === label).length > 1;
		out[key] = ambiguous || label.length === 0 ? key : label;
	}
	return out;
}

/**
 * Adapt an Ossie query payload into the {@link AiQueryResponse} every tile
 * renderer already consumes.
 *
 * Errors are normalised into the same self-correcting envelope the MDX path
 * produces — `status` + `error` + `field` + `available` — so a tile bound to a
 * model renders a structured validation message instead of a bare failure.
 */
export function toAiQueryResponse(raw: unknown): AiQueryResponse {
	const r = (raw ?? {}) as RawOssieResponse;

	if (isErrorPayload(r)) {
		return {
			queryId: r.queryId ?? null,
			// The validator's shape puts the CODE in `error` and the prose in
			// `message`; the mapper's puts the code in `status`. Prefer a real code
			// over "ERROR" so the tile can distinguish a fixable validation problem.
			status: (r.status ?? r.error ?? 'ERROR') as AiQueryResponse['status'],
			metadata: undefined,
			format: 'records',
			data: [],
			matrix: [],
			totalRows: 0,
			error: r.message ?? r.error ?? 'Query failed',
			field: r.field,
			available: r.available
		} as AiQueryResponse;
	}

	const columns = r.columns ?? [];
	const rename = labelByKey(columns);
	const records = r.records ?? [];
	const data = records.map((row) => {
		const out: Record<string, unknown> = {};
		for (const [k, v] of Object.entries(row)) out[rename[k] ?? k] = v;
		return out;
	});

	return {
		queryId: r.queryId ?? null,
		status: 'SUCCESS',
		metadata: {
			// Only the measure columns belong in `columns` — that is what the MDX
			// metadata means by it, and what the chart legend and the table's column
			// formatting read.
			columns: columns
				.filter((c) => c.type !== 'dimension')
				.map((c) => ({
					name: rename[c.key ?? ''] ?? c.key ?? '',
					caption: c.label ?? c.key ?? ''
				})),
			rows: [],
			measures: columns.filter((c) => c.type !== 'dimension').map((c) => c.label ?? c.key ?? '')
		},
		format: 'records',
		data,
		matrix: [],
		totalRows: r.meta?.rowCount ?? data.length,
		runtimeMs: r.runtime
	} as AiQueryResponse;
}
