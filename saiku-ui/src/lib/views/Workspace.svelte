<script lang="ts">
  import { onMount } from "svelte";
  import type { SaikuSession } from "$lib/api/session";
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import CubePicker from "$lib/views/CubePicker.svelte";
  import DimensionList from "$lib/views/DimensionList.svelte";
  import WorkspaceToolbar from "$lib/views/WorkspaceToolbar.svelte";
  import QueryCanvas from "$lib/views/QueryCanvas.svelte";
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import {
    deserializeQueryFromHash,
    serializeQueryToHash,
    type ThinQuery,
  } from "$lib/api/query";
  import type { ChartType, ChartOptions } from "$lib/views/chartTypes";
  import { DEFAULT_CHART_OPTIONS } from "$lib/views/chartTypes";

  interface Props {
    session: SaikuSession;
  }

  let { session }: Props = $props();
  let aboutOpen = $state(false);

  // Guard so we don't immediately overwrite the URL before (or during) hydrate.
  let hydrated = $state(false);

  onMount(() => {
    if (typeof window === "undefined") return;
    const params = new URLSearchParams(window.location.search);
    const token = params.get("q");
    if (token) {
      const decoded = deserializeQueryFromHash(token);
      if (decoded) {
        // Mirror the cube into the selection store so the sidebar and
        // QueryCanvas $effects agree on what's active.
        selection.select(decoded.query.cube);
        query.hydrate(decoded.query as ThinQuery);
        query.viewMode = (decoded.view.viewMode as typeof query.viewMode) ?? "grid";
        query.chartType = (decoded.view.chartType as ChartType) ?? "bar";
        query.chartOptions = (decoded.view.chartOptions as ChartOptions) ?? { ...DEFAULT_CHART_OPTIONS };
        if (query.hasRunnableShape()) void query.run();
      }
    }
    hydrated = true;
  });

  /** Debounced write-back. history.replaceState — not pushState — so the
   *  back button doesn't step through every chip drop. */
  let urlTimer: ReturnType<typeof setTimeout> | null = null;
  function writeUrl() {
    if (typeof window === "undefined") return;
    if (!query.current) {
      // No query yet: clear the ?q= param if present.
      const url = new URL(window.location.href);
      if (url.searchParams.has("q")) {
        url.searchParams.delete("q");
        window.history.replaceState(null, "", url.toString());
      }
      return;
    }
    const token = serializeQueryToHash(query.current, {
      viewMode: query.viewMode,
      chartType: query.chartType,
      chartOptions: query.chartOptions,
    });
    const url = new URL(window.location.href);
    url.searchParams.set("q", token);
    window.history.replaceState(null, "", url.toString());
  }

  $effect(() => {
    // Depend on the mutation-tracking fields so we refire on any change.
    const _c = query.current;
    const _d = query.dirtyCount;
    const _v = query.viewMode;
    const _t = query.chartType;
    const _o = query.chartOptions;
    // `_*` are reactive reads; silence unused-var lint.
    void _c; void _d; void _v; void _t; void _o;
    if (!hydrated) return;
    if (urlTimer) clearTimeout(urlTimer);
    urlTimer = setTimeout(writeUrl, 300);
  });
</script>

<div class="workspace">
  <aside class="workspace__sidebar">
    <div class="workspace__sidebar-scroll">
      <CubePicker username={session.username} />
      <DimensionList username={session.username} />
    </div>
    <div class="workspace__sidebar-footer">
      <button type="button" class="btn" onclick={() => (aboutOpen = true)}>{i18n.t("modal.about.title")}</button>
    </div>
  </aside>
  <section class="workspace__main">
    <div class="tabset">
      <div class="tab tab--active">Unsaved query</div>
      <button type="button" class="tab tab--new" aria-label="New query">+</button>
    </div>
    <WorkspaceToolbar />
    <QueryCanvas />
  </section>
</div>

<Modal title={i18n.t("modal.about.title")} open={aboutOpen} size="sm" onClose={() => (aboutOpen = false)}>
  <p>{i18n.t("modal.about.tagline")}</p>
  <p>
    <strong>{session.username}</strong> · {session.roles.join(", ")}
  </p>
  {#snippet footer()}
    <button class="btn btn--primary" onclick={() => (aboutOpen = false)}>{i18n.t("modal.close")}</button>
  {/snippet}
</Modal>

<style>
  .workspace {
    flex: 1;
    min-height: 0;
    display: grid;
    grid-template-columns: 300px 1fr;
    gap: 1px;
    background: var(--border);
    overflow: hidden;
  }
  .workspace__sidebar,
  .workspace__main {
    background: var(--bg);
    min-height: 0;
    min-width: 0;
    overflow: hidden;
  }
  .workspace__sidebar {
    display: flex;
    flex-direction: column;
  }
  .workspace__sidebar-scroll {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    padding: var(--space-4);
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }
  .workspace__sidebar-footer {
    padding: var(--space-3) var(--space-4);
    border-top: 1px solid var(--border);
    background: var(--bg-muted);
  }
  .workspace__main {
    display: flex;
    flex-direction: column;
  }
  .tabset {
    display: flex;
    align-items: stretch;
    padding: 0 var(--space-2);
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
  }
  .tab {
    padding: var(--space-3) var(--space-4);
    color: var(--fg-muted);
    border-bottom: 2px solid transparent;
    background: transparent;
    border-top: 0;
    border-left: 0;
    border-right: 0;
    font: inherit;
    cursor: pointer;
  }
  .tab--active {
    color: var(--fg);
    border-bottom-color: var(--accent);
  }
  .tab--new {
    color: var(--fg-subtle);
  }
</style>
