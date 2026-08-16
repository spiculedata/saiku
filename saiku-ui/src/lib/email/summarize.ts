/*
 * On-demand email summary. Reuses the gated /ai/ask path with forceTool:"insight"
 * so the model returns prose analysis of the current cellset (never a query/view
 * change). Whether the digest reaches the LLM is governed by the server-side
 * SAIKU_AI_LLM_EGRESS posture — this module adds no new egress surface.
 */
import { askAi, AiAskTransportError, type AiCubeRef, type AskRequest } from '$lib/api/aiAsk';

export const SUMMARIZE_PROMPT =
	'Summarise this analysis for the body of an email. State the headline figure(s) and any ' +
	'notable comparison in 2–3 short sentences of plain prose. No greeting, no preamble, no markdown headings.';

export function buildSummarizeRequest(
	cube: AiCubeRef,
	cellsetDigest: string | undefined
): AskRequest {
	return { cube, question: SUMMARIZE_PROMPT, cellsetDigest, forceTool: 'insight' };
}

/** Generate a summary of the current cellset, or null if none could be produced
 *  (no insight in the response, or a transport failure). Non-transport errors bubble. */
export async function generateSummary(
	cube: AiCubeRef,
	cellsetDigest: string | undefined
): Promise<string | null> {
	try {
		const resp = await askAi(buildSummarizeRequest(cube, cellsetDigest));
		return resp.insight?.markdown ?? null;
	} catch (e) {
		if (e instanceof AiAskTransportError) return null;
		throw e;
	}
}
