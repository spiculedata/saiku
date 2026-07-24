/*
 * AI dashboard-build store — owns the slow "build a dashboard" async flow so
 * the loading UX survives the AI drawer unmounting/remounting (which happens
 * on window resize). The drawer merely KICKS OFF the build; this singleton runs
 * it to completion and drives the full-screen building overlay
 * (AiDashboardBuildingOverlay.svelte), which is mounted at the workspace-page
 * level ABOVE/OUTSIDE the drawer.
 *
 * REVIEW-THEN-SAVE is preserved: on success we assemble → beginReview →
 * navigate to the editor; we NEVER persist (no auto-save). On degrade/error we
 * surface a generic reason via a toast and hide the overlay.
 */

import { goto } from "$app/navigation";
import { base } from "$app/paths";
import { buildAiDashboard } from "$lib/api/aiDashboard";
import type { AiCubeRef } from "$lib/api/aiAsk";
import { assembleDashboard } from "$lib/dashboard/aiDashboardAssembler";
import { dashboardStore } from "$lib/stores/dashboard.svelte";
import { session } from "$lib/stores/session.svelte";
import { toasts } from "$lib/stores/toasts.svelte";
import { i18n } from "$lib/stores/i18n.svelte";

class AiDashboardBuildStore {
  /** True while a build is in flight — drives the full-screen overlay. */
  building = $state(false);
  /** The user's request text — rendered as TEXT in the overlay (never @html). */
  request = $state("");
  /** Epoch ms the build started, or null when idle. The overlay ticks its own
   *  interval off this to render the m:ss elapsed timer (cleanup lives there). */
  startedAt = $state<number | null>(null);
  /** Last failure reason (generic, user-safe), surfaced via toast. */
  error = $state<string | null>(null);

  /** Derived whole-seconds elapsed since {@link startedAt}. Pure computation
   *  off Date.now(); the overlay owns the 1 Hz re-render tick. */
  get elapsedSeconds(): number {
    if (this.startedAt == null) return 0;
    return Math.max(0, Math.floor((Date.now() - this.startedAt) / 1000));
  }

  /** Enter the building state. */
  start(request: string): void {
    this.building = true;
    this.request = request;
    this.error = null;
    this.startedAt = Date.now();
  }

  /** Leave the building state after a successful hand-off to the editor. */
  finish(): void {
    this.building = false;
    this.request = "";
    this.startedAt = null;
  }

  /** Leave the building state on degrade / error, recording the reason. */
  fail(reason: string): void {
    this.building = false;
    this.error = reason;
    this.startedAt = null;
  }

  /**
   * Run the whole build: request → assemble → stage for review → navigate.
   * Store-driven so it does NOT depend on the drawer staying mounted. Re-entry
   * guarded. Never throws — degrade/error are turned into fail() + a toast.
   */
  async run(request: string, cube: AiCubeRef): Promise<void> {
    if (this.building) return;
    this.start(request);

    let spec;
    try {
      spec = await buildAiDashboard({ question: request, cube });
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      this.fail(message);
      toasts.danger(
        i18n.t("workspace.aiDashboard.buildFailedTitle", "Dashboard build failed"),
        i18n.t(
          "workspace.aiDashboard.buildFailedBody",
          "Something went wrong while building the dashboard. Please try again.",
        ),
      );
      return;
    }

    if (spec.degraded) {
      const reason = spec.reason ?? i18n.t("workspace.aiQuery.unknownError", "AI request failed.");
      this.fail(reason);
      toasts.danger(
        i18n.t("workspace.aiDashboard.buildDegradedTitle", "Couldn't build the dashboard"),
        reason,
      );
      return;
    }

    // Success — assemble the real Dashboard and stage it for REVIEW-THEN-SAVE,
    // then open the editor. beginReview() does NOT persist.
    const dashboard = assembleDashboard(spec, cube);
    const savePath = suggestedDashboardPath(spec.title);
    dashboardStore.beginReview(dashboard, savePath);
    await goto(`${base}/dashboards/${savePath}`);
    // Dismiss after navigation completes so there is no blank-workspace flash
    // between hiding the overlay and the editor mounting.
    this.finish();
  }
}

/** Suggest a repository path for the reviewed dashboard to Save to. Mirrors the
 *  New-dashboard flow's home default + slug, plus a short unique suffix so
 *  opening a review never lands on (and Save never overwrites) an existing
 *  dashboard. */
function suggestedDashboardPath(title: string | undefined): string {
  const user = session.current?.username ?? "";
  const home = user ? `homes/${user}` : "homes";
  const slug =
    (title ?? "")
      .toLowerCase()
      .trim()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "")
      .slice(0, 48) || "dashboard";
  const unique = Date.now().toString(36).slice(-5);
  return `${home}/ai-${slug}-${unique}.saikudash`;
}

export const aiDashboardBuild = new AiDashboardBuildStore();
