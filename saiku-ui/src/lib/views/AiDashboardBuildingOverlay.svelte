<script lang="ts">
  /*
   * Full-screen "Building your dashboard…" loader. Driven ENTIRELY by the
   * aiDashboardBuild store, and mounted at the workspace-page level ABOVE /
   * OUTSIDE the AI drawer — so a drawer unmount/remount (window resize) can
   * neither interrupt nor hide it. Reveals the finished dashboard when the
   * store's build completes (building flips false as the editor route mounts).
   *
   * No @html: the request text is rendered as plain text interpolation.
   */
  import { aiDashboardBuild } from "$lib/stores/aiDashboardBuild.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import { Button } from "$lib/components/ui";
  import { Loader2 } from "@lucide/svelte";

  // Local 1 Hz tick so the elapsed timer re-renders. The interval is owned by
  // this component and cleared on unmount / when the build ends (the $effect
  // return runs on both), so there is no leak. It reads the store's startedAt
  // and never writes store state.
  let elapsed = $state(0);
  $effect(() => {
    if (!aiDashboardBuild.building) {
      elapsed = 0;
      return;
    }
    const started = aiDashboardBuild.startedAt ?? Date.now();
    const compute = () => Math.max(0, Math.floor((Date.now() - started) / 1000));
    elapsed = compute();
    const id = setInterval(() => {
      elapsed = compute();
    }, 1000);
    return () => clearInterval(id);
  });

  const mmss = $derived.by(() => {
    const m = Math.floor(elapsed / 60);
    const s = String(elapsed % 60).padStart(2, "0");
    return `${m}:${s}`;
  });
</script>

{#if aiDashboardBuild.building}
  <div
    class="fixed inset-0 z-[1200] flex items-center justify-center bg-bg-overlay p-4"
    role="status"
    aria-live="polite"
  >
    <div
      class="flex w-[420px] max-w-[90vw] flex-col items-center gap-4 rounded-lg border border-border bg-bg px-8 py-7 text-center shadow-lg"
    >
      <Loader2 size={32} class="animate-spin text-primary" aria-hidden="true" />
      <div class="text-lg font-semibold text-fg">
        {i18n.t("workspace.aiDashboard.buildingTitle", "Building your dashboard…")}
      </div>
      {#if aiDashboardBuild.request}
        <div class="max-w-full break-words text-sm text-fg-muted">{aiDashboardBuild.request}</div>
      {/if}
      <div class="text-sm tabular-nums text-fg-subtle">{mmss}</div>
      <div class="text-xs text-fg-subtle">
        {i18n.t("workspace.aiDashboard.buildingHint", "This can take a minute or two on a local model.")}
      </div>
      <Button variant="outline" size="sm" onclick={() => aiDashboardBuild.cancel()}>
        {i18n.t("modal.cancel", "Cancel")}
      </Button>
    </div>
  </div>
{/if}
