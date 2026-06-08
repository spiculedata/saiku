/*
 * Reactive flag for whether the backend NL Ask provider is configured. Probed
 * once on first import via {@link fetchAskHealth}. The workspace toolbar uses
 * `aiAskHealth.configured` to decide whether to render the "Ask the AI" button
 * — instances that haven't wired an LLM key get the feature hidden entirely
 * rather than showing a button that opens a drawer announcing it's unavailable.
 *
 * The probe is fire-and-forget: a failure (transport / 404) is interpreted as
 * "not configured" so the feature stays hidden.
 */

import { fetchAskHealth } from "$lib/api/aiAsk";

class AiAskHealthStore {
  /** True iff the backend reports the provider is wired. Defaults to false until
   *  the probe completes so the button doesn't flash visible→hidden. */
  configured = $state(false);

  /** True until the first probe completes — lets the toolbar defer rendering
   *  until we know the answer. Most surfaces can ignore this. */
  loading = $state(true);

  constructor() {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading = true;
    const h = await fetchAskHealth();
    this.configured = h.configured;
    this.loading = false;
  }
}

export const aiAskHealth = new AiAskHealthStore();
