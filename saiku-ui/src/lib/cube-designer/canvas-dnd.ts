/**
 * Canvas drag-and-drop + connection-handle parsing — pure helpers lifted out
 * of SchemaCanvasView.svelte (audit finding #1040).
 *
 * The component keeps the DOM event wiring (`ondrop`, `onconnect`, position
 * maths, store mutation); these functions own only the payload/handle
 * *parsing*, which is pure string work and unit-testable in isolation.
 */
import type { SourceTableCandidate } from './types.js';

/** A join's endpoints parsed from a SvelteFlow connection — ready to hand to
 *  `store.addJoin` (the component adds `kind`). */
export interface ParsedConnectionJoin {
	sourceTableId: string;
	sourceColumnName: string;
	targetTableId: string;
	targetColumnName: string;
}

/** Minimal shape of SvelteFlow's `Connection` we depend on. */
export interface ConnectionLike {
	source: string;
	target: string;
	sourceHandle?: string | null;
	targetHandle?: string | null;
}

/**
 * Parse a SvelteFlow connection into join endpoints. Handles are formatted
 * `<tableId>:<columnName>:in|out`; we pull the column names back out. Returns
 * `null` when either handle is missing or malformed (no column segment) so the
 * caller can bail without creating a bogus join.
 */
export function parseConnectionJoin(connection: ConnectionLike): ParsedConnectionJoin | null {
	if (!connection.sourceHandle || !connection.targetHandle) return null;
	const [, sourceColumnName] = connection.sourceHandle.split(':');
	const [, targetColumnName] = connection.targetHandle.split(':');
	if (!sourceColumnName || !targetColumnName) return null;
	return {
		sourceTableId: connection.source,
		sourceColumnName,
		targetTableId: connection.target,
		targetColumnName
	};
}

/**
 * Parse the table candidates from a pane-drop's dataTransfer payloads.
 * Prefers the multi-table payload (`application/x-saiku-tables`), falling back
 * to the single-table payload (`application/x-saiku-table`) from any legacy
 * drop source. Returns `[]` when neither payload is present or the JSON is
 * malformed — the caller treats an empty result as "nothing to drop".
 */
export function parseDroppedTableCandidates(
	arrayPayload: string | undefined | null,
	singlePayload: string | undefined | null
): SourceTableCandidate[] {
	if (!arrayPayload && !singlePayload) return [];
	try {
		return arrayPayload
			? (JSON.parse(arrayPayload) as SourceTableCandidate[])
			: [JSON.parse(singlePayload!) as SourceTableCandidate];
	} catch {
		return [];
	}
}
