/*
 * Share-view response injection (#941 PR2). The public /share viewer prefetches
 * each tile's data via the guest endpoint and stashes it in a per-tile map set
 * as a Svelte context. Data tiles (ChartTile/TableTile/KpiTile) look up their
 * own response here at init and, if present, render from it instead of running
 * the normal live fetch — so the same tile components serve the read-only guest
 * view without a Saiku session or access to /ai/query.
 *
 * Outside the share viewer the context is absent, so getShareViewResponse()
 * returns undefined and tiles fetch as usual — zero impact on normal mode.
 */
import { getContext, setContext } from 'svelte';
import type { AiQueryResponse } from '$lib/api/aiQuery';

const SHARE_VIEW_KEY = Symbol('saiku.shareViewResponses');

/** Provide the prefetched per-tile responses to descendant tiles. Call once in
 *  the share viewer page before rendering the dashboard. */
export function setShareViewResponses(responses: Map<string, AiQueryResponse>): void {
	setContext(SHARE_VIEW_KEY, responses);
}

/** The prefetched response for `tileId`, or undefined when not in a share view
 *  (normal dashboard mode — the tile then fetches live). Must be called during
 *  component init (getContext requirement). */
export function getShareViewResponse(tileId: string): AiQueryResponse | undefined {
	const responses = getContext<Map<string, AiQueryResponse> | undefined>(SHARE_VIEW_KEY);
	return responses?.get(tileId);
}
