/**
 * Canvas drag-and-drop + connection-handle parsing — pure helpers lifted out
 * of SchemaCanvasView.svelte (audit finding #1040).
 *
 * The component keeps the DOM event wiring (`ondrop`, `onconnect`, position
 * maths, store mutation); these functions own only the payload/handle
 * *parsing*, which is pure string work and unit-testable in isolation.
 */
import type { SourceTableCandidate } from "./types.js";

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
export function parseConnectionJoin(
  connection: ConnectionLike,
): ParsedConnectionJoin | null {
  if (!connection.sourceHandle || !connection.targetHandle) return null;
  const [, sourceColumnName] = connection.sourceHandle.split(":");
  const [, targetColumnName] = connection.targetHandle.split(":");
  if (!sourceColumnName || !targetColumnName) return null;
  return {
    sourceTableId: connection.source,
    sourceColumnName,
    targetTableId: connection.target,
    targetColumnName,
  };
}

/** A point in the flow coordinate space (post-pan/zoom). */
export interface FlowPoint {
  x: number;
  y: number;
}

/** SvelteFlow's `screenToFlowPosition`, or null when the flow isn't mounted. */
export type ScreenToFlow = ((screen: FlowPoint) => FlowPoint) | null | undefined;

/**
 * Resolve where a dropped node should land, in FLOW coordinates.
 *
 * saiku#1634 (#3): the drop handler lives on the pane wrapper, OUTSIDE the
 * SvelteFlow provider, so it can't call `useSvelteFlow().screenToFlowPosition`
 * directly. An in-flow child (JumpHandler) publishes that converter onto the
 * store; when present we map the pointer's viewport coords through it so a
 * panned/zoomed canvas drops the node under the cursor rather than off-screen.
 * When absent (SSR / tests / before the flow mounts) we fall back to
 * pane-relative pixels, which equal flow coords only at the identity viewport —
 * the exact stale behaviour this fixes, kept as a safe default.
 */
export function resolveDropOrigin(
  screenToFlow: ScreenToFlow,
  clientX: number,
  clientY: number,
  rect: { left: number; top: number },
): FlowPoint {
  if (screenToFlow) return screenToFlow({ x: clientX, y: clientY });
  return { x: clientX - rect.left, y: clientY - rect.top };
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
  singlePayload: string | undefined | null,
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
