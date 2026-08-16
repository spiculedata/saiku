/*
 * Latest insight markdown, lifted out of AiQueryDrawer's local turn list so
 * other surfaces (the "email me this" modal) can read the most recent
 * analysis without owning the chat thread themselves.
 *
 * Single most-recent value only — no history. The drawer overwrites this on
 * every new insight turn; the email modal reads a snapshot when opened.
 */

class AiInsightStore {
	/** Markdown body of the most recent insight turn, or null before the
	 *  first one / after a clear. */
	latestMarkdown = $state<string | null>(null);

	/** Record a fresh insight. Called from the drawer's submit() handler when
	 *  an insight turn is produced — never from an $effect. */
	set(md: string): void {
		this.latestMarkdown = md;
	}

	/** Drop the stored insight. */
	clear(): void {
		this.latestMarkdown = null;
	}
}

export const aiInsight = new AiInsightStore();
