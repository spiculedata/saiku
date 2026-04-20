/*
 * Pure view-model helpers for the schema-generator admin page.
 *
 * Page composition lives in `+page.svelte`, but the small pile of decisions
 * around "is Start enabled?", "is Save enabled?", "what colour is the stage
 * pill?" is easier to get right if it's testable in isolation. This module
 * holds those helpers so `+page.svelte` can stay declarative.
 *
 * The page represents the store as `Stage | null` — `null` means "no session
 * has been kicked off yet" (the store has a default stage of PENDING once a
 * session exists, so we use the session id in the page to decide null vs
 * PENDING; see +page.svelte).
 */

import type { Stage } from "$lib/api/schemaGen";

/** Semantic colour token for the stage pill; maps to CSS variables in the page. */
export type PillColor = "muted" | "info" | "success" | "danger";

/**
 * Start is allowed when no session is active. Once a run finishes in FAILED
 * we also re-enable Start so the user can retry without a full reload.
 */
export function canStart(stage: Stage | null): boolean {
  return stage === null || stage === "FAILED";
}

/** Save is only meaningful once the pipeline has produced a complete draft. */
export function canSave(stage: Stage | null): boolean {
  return stage === "READY";
}

/**
 * Cancel is available while a session is in flight, and at READY (user can
 * walk away without saving). Hidden at SAVED (done) and FAILED (nothing to
 * cancel — user will hit Start again).
 */
export function canCancel(stage: Stage | null): boolean {
  if (stage === null) return false;
  if (stage === "SAVED" || stage === "FAILED") return false;
  return true;
}

/** Pill colour token; mapped to CSS in the page template. */
export function stagePillColor(stage: Stage | null): PillColor {
  if (stage === null) return "muted";
  switch (stage) {
    case "PENDING":
      return "muted";
    case "INTROSPECTING":
    case "INFERRING":
    case "ENRICHING":
      return "info";
    case "READY":
    case "SAVED":
      return "success";
    case "FAILED":
      return "danger";
  }
}

/** Human-readable label for the stage pill. */
export function stageLabel(stage: Stage | null): string {
  if (stage === null) return "Idle";
  switch (stage) {
    case "PENDING":
      return "Pending";
    case "INTROSPECTING":
      return "Introspecting schema";
    case "INFERRING":
      return "Inferring structure";
    case "ENRICHING":
      return "Enriching with suggestions";
    case "READY":
      return "Ready to save";
    case "SAVED":
      return "Saved";
    case "FAILED":
      return "Failed";
  }
}
