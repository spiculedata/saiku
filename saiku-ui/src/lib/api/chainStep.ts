/*
 * Pure classifier for a chained-ask (`askAiStream`) envelope — decides which
 * kind of turn/UI action an intermediate `step` or terminal `final` envelope
 * represents. Kept separate from AiQueryDrawer.svelte so the dispatch logic
 * is unit-testable without a component harness.
 *
 * Mirrors the intent-priority order `submit()` in AiQueryDrawer.svelte uses
 * for the single-turn path: degraded first (it can co-occur with a partial
 * request), then insight / emailDraft / viewChange, then a plain built query.
 */

import type { AskResponse } from './aiAsk';

export type ChainStepKind =
	'query' | 'report' | 'degraded' | 'viewChange' | 'emailDraft' | 'unknown';

/** Classify one AskResponse envelope from the chain stream (a `step` or `final` event's payload). */
export function classifyChainEnvelope(env: AskResponse): ChainStepKind {
	if (env.degraded) return 'degraded';
	if (env.insight) return 'report';
	if (env.emailDraft) return 'emailDraft';
	if (env.viewChange) return 'viewChange';
	if (env.request) return 'query';
	return 'unknown';
}
