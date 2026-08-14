/*
 * What the header's status badge says and how it reads, for a given connection
 * state.
 *
 * Pure so the mapping is testable without a backend. The author still supplies
 * the CONNECTED wording (their product name belongs there — "Live · Saiku",
 * "Connected to Acme BI"); the other states are ours, because an author can't
 * be expected to write copy for a failure they'll never see while authoring.
 */

import type { ConnectionState } from "$lib/stores/appConnection.svelte";

/** Tone drives the dot colour: positive = green, warning = amber, danger =
 *  red, neutral = muted while the first probe is in flight. */
export type BadgeTone = "positive" | "warning" | "danger" | "neutral";

export interface BadgeView {
  text: string;
  tone: BadgeTone;
  /** Longer explanation for the title attribute. */
  hint: string;
}

/**
 * Resolve the badge for a connection state.
 *
 * `configured` is the author's connected-state text. When it's absent the badge
 * is hidden entirely — returns null — because a status indicator nobody asked
 * for is just noise.
 */
export function badgeFor(
  configured: string | null | undefined,
  state: ConnectionState,
): BadgeView | null {
  const text = (configured ?? "").trim();
  if (!text) return null;

  switch (state) {
    case "live":
      return { text, tone: "positive", hint: "Connected — tiles are showing live data." };
    case "demo":
      return {
        text: "Demo data",
        tone: "warning",
        hint: "This instance is running in demo mode; figures are sample data.",
      };
    case "offline":
      return {
        text: "Offline",
        tone: "danger",
        hint: "Can't reach Saiku — tiles are showing the last data they fetched.",
      };
    case "checking":
    default:
      // Don't assert "Live" before anything has answered — that's the original
      // bug in miniature.
      return { text, tone: "neutral", hint: "Checking the connection…" };
  }
}
