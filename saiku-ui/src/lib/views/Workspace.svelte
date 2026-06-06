<script lang="ts">
  import { onMount } from "svelte";
  import type { SaikuSession } from "$lib/api/session";
  import Modal from "$lib/components/Modal.svelte";
  import { i18n } from "$lib/stores/i18n.svelte";
  import CubePicker from "$lib/views/CubePicker.svelte";
  import DimensionList from "$lib/views/DimensionList.svelte";
  import WorkspaceToolbar from "$lib/views/WorkspaceToolbar.svelte";
  import QueryCanvas from "$lib/views/QueryCanvas.svelte";
  import AiQueryDrawer from "$lib/views/AiQueryDrawer.svelte";
  import PrefsMenu from "$lib/components/PrefsMenu.svelte";
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { tabs } from "$lib/stores/tabs.svelte";
  import { embed } from "$lib/stores/embed.svelte";
  import {
    deserializeQueryFromHash,
    serializeQueryToHash,
    type ThinQuery,
  } from "$lib/api/query";
  import { datasources } from "$lib/stores/datasources.svelte";
  import {
    findCubeByRef,
    parseStarterCubeRef,
    pickStarterMeasureAndLevel,
  } from "$lib/api/starterCube";
  import type { ChartType, ChartOptions } from "$lib/views/chartTypes";
  import { DEFAULT_CHART_OPTIONS } from "$lib/views/chartTypes";

  interface Props {
    session: SaikuSession;
  }

  let { session }: Props = $props();
  let aboutOpen = $state(false);
  let aiDrawerOpen = $state(false);

  /** Hand the model's MDX to the active query tab via the same path
   *  MDXModal.onRun uses — set type to MDX, store the raw expression, and
   *  re-run. The canvas re-renders with the result; all existing grid /
   *  chart / drill features keep working. Closes the drawer so the user
   *  sees their cellset. */
  async function handleEditInCanvas(mdx: string): Promise<void> {
    if (!query.current) return;
    query.current.mdx = mdx;
    query.current.type = "MDX";
    aiDrawerOpen = false;
    await query.run();
  }

  // Tab labels derived from each tab's saved-path snapshot. The active
  // tab reads the live query store, others read their captured
  // savedPath. Dirty marker on the active tab driven by live query.dirty;
  // other tabs read their snapshotted dirty flag.
  function deriveTabLabel(path: string | null): string {
    if (!path) return i18n.t("workspace.unsavedQuery");
    const base = path.split("/").pop() ?? path;
    return base.endsWith(".saiku") ? base.slice(0, -".saiku".length) : base;
  }

  function tabLabelFor(i: number): string {
    if (i === tabs.activeIndex) return deriveTabLabel(query.savedPath);
    return deriveTabLabel(tabs.list[i].query.savedPath);
  }

  function tabTitleFor(i: number): string {
    if (i === tabs.activeIndex) {
      return query.savedPath ?? i18n.t("workspace.unsavedQuery");
    }
    return tabs.list[i].query.savedPath ?? i18n.t("workspace.unsavedQuery");
  }

  function tabDirtyFor(i: number): boolean {
    if (i === tabs.activeIndex) return query.dirty;
    return tabs.list[i].query.dirty;
  }

  /** Add a new in-app tab. The outgoing tab's state is snapshotted by
   *  the tabs store so we can come back to it; the new one starts
   *  blank (no cube, no query). */
  function handleNewTab(): void {
    tabs.newTab();
    // After newTab() the live stores reflect the blank snapshot —
    // clear the URL ?q= param so it doesn't immediately re-hydrate
    // from the previous tab.
    if (typeof window !== "undefined") {
      const url = new URL(window.location.href);
      if (url.searchParams.has("q")) {
        url.searchParams.delete("q");
        window.history.replaceState(null, "", url.toString());
      }
    }
  }

  function handleSwitchTab(i: number): void {
    tabs.switchTo(i);
  }

  function handleCloseTab(i: number, e: MouseEvent): void {
    e.stopPropagation();
    if (tabs.list.length <= 1) return;
    // If the tab is dirty, confirm before closing.
    const dirty = tabDirtyFor(i);
    if (dirty) {
      // eslint-disable-next-line no-alert
      const ok = window.confirm(i18n.t("confirm.discardUnsaved") ?? "Discard unsaved changes?");
      if (!ok) return;
    }
    tabs.closeTab(i);
  }

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
      hydrated = true;
      return;
    }

    // saiku-cloud#450 — starter-cube bootstrap. When the launch URL
    // carries the four starterCube* params, resolve the cube via the
    // discover endpoint, pick first measure + first dim level
    // (preferring a time-typed hierarchy), and hydrate the workbench
    // with a fully-formed query model. Customer can immediately drag
    // a different measure / drill / filter — Studio re-fires the query
    // on every interaction. No flat MDX preload — that would freeze
    // the workbench because there's no MDX → model inverse parser.
    const ref = parseStarterCubeRef(params);
    if (ref) {
      void hydrateStarterCube(ref).finally(() => {
        hydrated = true;
      });
      return;
    }

    hydrated = true;
  });

  /** Resolve starter-cube ref → SaikuCube → metadata → query.hydrate.
   *  Every failure falls through silently: a malformed or missing cube
   *  must not show an error toast, just leave the empty workbench
   *  rendered as if the URL params were absent. */
  async function hydrateStarterCube(ref: ReturnType<typeof parseStarterCubeRef>): Promise<void> {
    if (!ref) return;
    try {
      // Ensure the connection tree is loaded. The session+CubePicker
      // mount sequence usually does this, but the timing is racey and
      // we don't want to depend on it.
      if (!datasources.loaded) {
        await datasources.load(session.username);
      }
      const cube = findCubeByRef(datasources.connections, ref);
      if (!cube) return;
      // Fetch dimensions + measures (cached after first call).
      const metadata = await datasources.metadata(session.username, cube);
      const pick = pickStarterMeasureAndLevel(metadata.measures, metadata.dimensions);
      if (!pick) return;

      // Hydrate the query model the same way a manual chip-drop would:
      // initFor(cube) → addMeasure → includeLevel(ROWS, drop). Studio
      // sees an ordinary user-built query, so drag/drill/filter all
      // work natively from this starting point.
      selection.select(cube);
      query.initFor(cube);
      query.addMeasure(pick.measure);
      query.includeLevel("ROWS", pick.drop);
      if (query.hasRunnableShape()) void query.run();
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn("saiku: starter-cube hydrate failed, falling back to empty workbench", err);
    }
  }

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
    const _s = query.savedPath;
    // `_*` are reactive reads; silence unused-var lint.
    void _c; void _d; void _v; void _t; void _o; void _s;
    if (!hydrated) return;
    // Active-tab label is derived from live query.savedPath directly
    // in tabLabelFor(); inactive-tab labels read each tab's snapshot,
    // which is captured at switch/close time via captureActive().
    // No need to write tabs.list from this effect — doing so caused
    // an effect_update_depth_exceeded loop because the write re-fires
    // every $effect that read tabs.list during the same tick.
    if (urlTimer) clearTimeout(urlTimer);
    urlTimer = setTimeout(writeUrl, 300);
  });
</script>

<div class="workspace" class:workspace--embed={embed.active}>
  {#if !embed.active}
    <aside class="workspace__sidebar">
      <div class="workspace__sidebar-scroll">
        <CubePicker username={session.username} />
        <DimensionList username={session.username} />
      </div>
      <div class="workspace__sidebar-footer">
        <PrefsMenu />
        <button type="button" class="btn" onclick={() => (aboutOpen = true)}>{i18n.t("modal.about.title")}</button>
      </div>
    </aside>
  {/if}
  <section class="workspace__main">
    {#if !embed.active}
      <div class="tabset" role="tablist">
        {#each tabs.list as t, i (t.id)}
          <button
            type="button"
            class="tab"
            class:tab--active={i === tabs.activeIndex}
            role="tab"
            aria-selected={i === tabs.activeIndex}
            title={tabTitleFor(i)}
            onclick={() => handleSwitchTab(i)}
          >
            <span class="tab__label">{tabLabelFor(i)}</span>
            {#if tabDirtyFor(i)}<span class="tab__dirty" aria-label="unsaved changes">•</span>{/if}
            {#if tabs.list.length > 1}
              <span
                class="tab__close"
                role="button"
                tabindex="0"
                aria-label="Close tab"
                onclick={(e) => handleCloseTab(i, e)}
                onkeydown={(e) => {
                  if (e.key === "Enter" || e.key === " ") handleCloseTab(i, e as unknown as MouseEvent);
                }}
              >×</span>
            {/if}
          </button>
        {/each}
        <button
          type="button"
          class="tab tab--new"
          aria-label={i18n.t("toast.newQuery")}
          title={i18n.t("toast.newQuery")}
          onclick={handleNewTab}
        >+</button>
      </div>
      <WorkspaceToolbar onAskAi={() => (aiDrawerOpen = true)} />
    {/if}
    <QueryCanvas />
  </section>
</div>

{#if !embed.active}
  <AiQueryDrawer
    open={aiDrawerOpen}
    onClose={() => (aiDrawerOpen = false)}
    onEditInCanvas={(mdx) => void handleEditInCanvas(mdx)}
  />
{/if}

<Modal title={i18n.t("modal.about.title")} open={aboutOpen} size="sm" onClose={() => (aboutOpen = false)}>
  <p>{i18n.t("modal.about.tagline")}</p>
  <p>
    <strong>{session.username}</strong> · {session.roles.join(", ")}
  </p>
  <p>
    <a
      href="https://join.slack.com/t/saikucloud/shared_invite/zt-3yor7kgiv-YUPhqK0pvd4WljctIQVOTA"
      target="_blank"
      rel="noopener noreferrer">{i18n.t("modal.about.community")}</a>
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
  .workspace--embed {
    grid-template-columns: 1fr;
    gap: 0;
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
    border-top: 1px solid var(--border-strong);
    background: var(--bg-subtle);
    display: flex;
    align-items: center;
    gap: var(--space-2);
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
    /* .workspace__main has overflow:hidden; without horizontal scroll
       here a third (or further) tab gets clipped past the right edge
       and the "+" button becomes unreachable, which presents to the
       user as "can't open more than 2 tabs". Allow the strip to scroll
       and make each tab non-shrinking so the labels don't compact
       into an unreadable column either. */
    overflow-x: auto;
    overflow-y: hidden;
    scrollbar-width: thin;
  }
  .tab {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    color: var(--fg-muted);
    border-bottom: 2px solid transparent;
    background: transparent;
    border-top: 0;
    border-left: 0;
    border-right: 0;
    font: inherit;
    font-size: var(--fs-sm);
    cursor: pointer;
    max-width: 18rem;
    flex-shrink: 0;
  }
  .tab:hover:not(.tab--active) {
    color: var(--fg);
    background: color-mix(in srgb, var(--bg) 60%, transparent);
  }
  .tab--active {
    color: var(--fg);
    border-bottom-color: var(--accent);
  }
  .tab__label {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 12rem;
  }
  .tab__dirty {
    color: var(--accent);
  }
  .tab__close {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 1.25rem;
    height: 1.25rem;
    border-radius: 50%;
    color: var(--fg-muted);
    font-size: 1rem;
    line-height: 1;
    user-select: none;
  }
  .tab__close:hover {
    background: color-mix(in srgb, var(--danger) 18%, transparent);
    color: var(--danger);
  }
  .tab--new {
    color: var(--fg-subtle);
  }
</style>
