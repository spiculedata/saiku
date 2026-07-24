/*
 * Reactive flag for whether the backend mail sender is configured. Probed
 * once on first import via {@link fetchMailHealth}. The toolbar uses
 * `mailHealth.configured` to decide whether to render the "Email me this"
 * button — instances that haven't wired an SMTP/mail provider get the
 * feature hidden entirely rather than showing a button that fails on click.
 *
 * The probe is fire-and-forget: a failure (transport / 404) is interpreted as
 * "not configured" so the feature stays hidden.
 */

import { fetchMailHealth } from "$lib/api/emailSelf";

class MailHealthStore {
  /** True iff the backend reports mail sending is wired. Defaults to false
   *  until the probe completes so the button doesn't flash visible→hidden. */
  configured = $state(false);

  /** True until the first probe completes — lets the toolbar defer rendering
   *  until we know the answer. Most surfaces can ignore this. */
  loading = $state(true);

  constructor() {
    void this.refresh();
  }

  async refresh(): Promise<void> {
    this.loading = true;
    const h = await fetchMailHealth();
    this.configured = h.configured;
    this.loading = false;
  }
}

export const mailHealth = new MailHealthStore();
