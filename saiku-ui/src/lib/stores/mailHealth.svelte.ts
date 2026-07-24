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

  /** True iff an admin has configured the fixed self-send recipient. The
   *  composer disables Send (and shows a "not configured" recipient line)
   *  when this is false. */
  selfConfigured = $state(false);

  /** The configured self-send recipient, or null when unconfigured. Rendered
   *  as read-only text in the composer. */
  to = $state<string | null>(null);

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
    this.selfConfigured = h.selfConfigured;
    this.to = h.to;
    this.loading = false;
  }
}

export const mailHealth = new MailHealthStore();
