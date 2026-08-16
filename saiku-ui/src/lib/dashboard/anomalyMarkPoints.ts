/*
 * Issue #907 — build ECharts markPoint overlays for anomalous data points.
 *
 * Given an AiQueryResponse whose cells were augmented by POST /ai/anomaly
 * (each flagged cell carries an `anomaly:{score,expected,direction}`), this
 * derives, per measure-series, the list of markPoints to overlay on the chart.
 * It mirrors projectFromAiQueryResponse's row/measure-key derivation so the
 * series indexes line up 1:1 with the chart the shared builder produced.
 *
 * Pure: no DOM, no ECharts import. The Svelte tile merges the result into the
 * already-built option and supplies the (HTML-escaping) tooltip formatter.
 */

import type { AiCell, AiQueryResponse, AnomalyPoint } from '$lib/api/aiQuery';

function isAiCell(v: unknown): v is AiCell {
	return typeof v === 'object' && v !== null && 'formatted' in (v as object);
}

/** One anomalous point to mark on a series. */
export interface AnomalyMark {
	/** Category label (x-axis value) of the point — already the raw member name. */
	category: string;
	/** Plotted numeric value at this point. */
	value: number;
	/** The detector verdict for the tooltip. */
	anomaly: AnomalyPoint;
	/** Pre-formatted display value from the cell. */
	formatted: string;
	/** Measure (series) caption this point belongs to. */
	series: string;
}

/** markPoints grouped by series index (aligned with the chart's series order =
 *  measure column order). Series with no anomalies are simply absent. */
export type AnomalyMarksBySeries = Map<number, AnomalyMark[]>;

/**
 * Extract anomaly marks from an augmented response, keyed by series index.
 * Returns an empty map when there are no anomalies — callers treat that as
 * "render the plain chart unchanged".
 */
export function anomalyMarksBySeries(response: AiQueryResponse | null): AnomalyMarksBySeries {
	const out: AnomalyMarksBySeries = new Map();
	const rows = response?.data ?? [];
	if (rows.length === 0) return out;

	// Same key derivation as projectFromAiQueryResponse: header columns (plain
	// string) vs measure columns (AiCell), in insertion order.
	const firstRow = rows[0];
	const headerKeys: string[] = [];
	const measureKeys: string[] = [];
	for (const k of Object.keys(firstRow)) {
		if (isAiCell(firstRow[k])) measureKeys.push(k);
		else headerKeys.push(k);
	}

	const categoryOf = (r: Record<string, AiCell | string>, i: number): string => {
		const parts = headerKeys.map((k) => String(r[k] ?? '')).filter((s) => s.length > 0);
		return parts.length > 0 ? parts.join(' / ') : `row ${i + 1}`;
	};

	for (let si = 0; si < measureKeys.length; si++) {
		const key = measureKeys[si];
		const marks: AnomalyMark[] = [];
		for (let ri = 0; ri < rows.length; ri++) {
			const cell = rows[ri][key];
			if (!isAiCell(cell) || !cell.anomaly || cell.value == null) continue;
			marks.push({
				category: categoryOf(rows[ri], ri),
				value: cell.value,
				anomaly: cell.anomaly,
				formatted: cell.formatted,
				series: key
			});
		}
		if (marks.length > 0) out.set(si, marks);
	}
	return out;
}

/** True when any series carries at least one anomaly mark. */
export function hasAnyAnomaly(marks: AnomalyMarksBySeries): boolean {
	for (const list of marks.values()) if (list.length > 0) return true;
	return false;
}
