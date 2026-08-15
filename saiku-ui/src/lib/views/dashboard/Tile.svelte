<script lang="ts">
  /*
   * Polymorphic tile shell. Renders the title-bar frame and dispatches
   * the body to the matching sub-tile component by `tile.type`.
   *
   * Edit / remove buttons live in this shell so every tile type gets
   * them for free. The edit modal is co-located here — keeping the
   * open/closed state per-tile-instance avoids prop-drilling up to the
   * grid and back.
   *
   * Secondary actions (Duplicate, future cut/paste/lock) live in a
   * shared overflow menu that opens from either the ⋮ kebab button on
   * the header OR a right-click anywhere on the tile (issue #913).
   */

  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  // issue #924: highlight tiles a hovered panel filter narrows, dim the rest.
  import { filterAffinityHover } from "$lib/stores/filterAffinity.svelte";
  // issue #915: multi-select tiles for bulk operations.
  import { tileSelection, type SelectClickMode } from "$lib/stores/tileSelection.svelte";
  import type { DashboardTile, DashboardFilter } from "$lib/api/dashboards";
  import ChartTile from "$lib/views/dashboard/tiles/ChartTile.svelte";
  import TableTile from "$lib/views/dashboard/tiles/TableTile.svelte";
  import TextTile from "$lib/views/dashboard/tiles/TextTile.svelte";
  import KpiTile from "$lib/views/dashboard/tiles/KpiTile.svelte";
  import ImageTile from "$lib/views/dashboard/tiles/ImageTile.svelte";
  // App Builder Phase 2 (saiku#1441): open the tile dispatch to pluggable
  // renderers looked up by tile.custom.renderer. Built-ins keep their branches.
  import { getTileRenderer } from "$lib/dashboard/tileRegistry";
  import TileEditorModal from "$lib/views/dashboard/TileEditorModal.svelte";
  import { Copy, MoreVertical, Settings2, X, MessageCircle, Minus, Plus } from "@lucide/svelte";
  // #942 PR2 — per-tile comments.
  import CommentsPanel from "$lib/views/dashboard/CommentsPanel.svelte";
  import { getComments } from "$lib/api/dashboards";

  interface Props {
    tile: DashboardTile;
    readOnly?: boolean;
  }

  let { tile, readOnly = false }: Props = $props();

  /* saiku#1788: does this chart draw a title of its own inside the plot? The App
     Builder skin hides the generic tile header in view mode only when it would
     duplicate that title (#1776). Stamped as an attribute rather than inferred
     from DOM shape — the previous CSS guessed via :has(tbody), which chart tiles
     satisfy through their sr-only accessibility table, so the rule both missed
     charts and swallowed the titles of text / ranked-list / graph tiles. */
  const chartDrawsOwnTitle = $derived(
    tile.type === "chart" && (tile.chartOptions?.title ?? "").trim().length > 0,
  );

  let editorOpen = $state(false);

  // #942: comments act on a dashboard that ACTUALLY EXISTS on the server.
  // Gate on the store's commentsPath getter (persisted ? savedPath : ""), not
  // on savedPath directly: an AI-assembled review and a 404 fallback both carry
  // a non-empty savedPath while still unsaved, and fetching comments against
  // that not-yet-written path 403s (canReadDashboard ACL) — which the global
  // interceptor mis-surfaces as a "Session expired" banner. persisted flips
  // true once the dashboard is fetched or saved, so the badge appears then.
  // Empty for the public share viewer too (hydrated ""), so guests can't comment.
  const commentsPath = $derived(dashboardStore.commentsPath);
  let commentsOpen = $state(false);
  let commentCount = $state(0);
  let countLoaded = $state(false);
  $effect(() => {
    const path = commentsPath;
    if (!path || countLoaded) return;
    countLoaded = true;
    void getComments(path, tile.id)
      .then((cs) => (commentCount = cs.length))
      .catch(() => {});
  });

  // Overflow menu: opened from the kebab button or right-click. Coords
  // are viewport-relative (position: fixed) so the same menu element
  // covers both the kebab-anchored open and the cursor-anchored open
  // without a second DOM node.
  let menuOpen = $state(false);
  let menuX = $state(0);
  let menuY = $state(0);
  let menuEl = $state<HTMLDivElement | null>(null);

  // Freshly-duplicated tiles arrive with their id stamped on the
  // store's pendingEditTileId signal — open the editor immediately so
  // the analyst can rename / re-bind without a second click (issue #913).
  $effect(() => {
    if (!readOnly && dashboardStore.consumeEditSignal(tile.id)) {
      editorOpen = true;
    }
  });

  // Close on outside click / Escape while the menu is open.
  $effect(() => {
    if (!menuOpen) return;
    function onDocClick(e: MouseEvent): void {
      const target = e.target as Node | null;
      if (target && !menuEl?.contains(target)) {
        menuOpen = false;
      }
    }
    function onKey(e: KeyboardEvent): void {
      if (e.key === "Escape") menuOpen = false;
    }
    document.addEventListener("mousedown", onDocClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDocClick);
      document.removeEventListener("keydown", onKey);
    };
  });

  function handleEdit(): void {
    if (readOnly) return;
    editorOpen = true;
  }

  function handleDuplicate(): void {
    if (readOnly) return;
    dashboardStore.duplicateTile(tile.id);
  }

  function handleRemove(): void {
    if (readOnly) return;
    dashboardStore.removeTile(tile.id);
  }

  /** Open the overflow menu anchored to the kebab button — pinned to
   *  the button's bottom-right corner so the menu hangs below + left,
   *  keeping it inside the tile chrome rather than overflowing right. */
  function openKebabMenu(e: MouseEvent): void {
    if (readOnly) return;
    e.stopPropagation();
    const btn = e.currentTarget as HTMLElement;
    const rect = btn.getBoundingClientRect();
    menuX = rect.right - 160; // 160px ≈ menu width; align right edge
    menuY = rect.bottom + 4;
    menuOpen = true;
  }

  /** Right-click on the tile → open the overflow menu at the cursor.
   *  Suppresses the browser's native context menu only when we have
   *  something to offer (skip in readOnly so the analyst still gets
   *  Inspect / Save Image etc. on read-only dashboards). */
  function openContextMenu(e: MouseEvent): void {
    if (readOnly) return;
    e.preventDefault();
    e.stopPropagation();
    menuX = e.clientX;
    menuY = e.clientY;
    menuOpen = true;
  }

  function duplicateFromMenu(): void {
    menuOpen = false;
    handleDuplicate();
  }

  /** Click-filter capture from chart / table sub-tiles. Push onto the
   *  active-filter set tagged with this tile's id; the chip bar shows
   *  the new filter and every compatible tile recomputes its effective
   *  query on the activeFilters store's tick. */
  function handleClickFilter(filter: DashboardFilter): void {
    activeFilters.pushClick(filter, tile.id);
  }

  /** #1085: brush cross-filter capture. Pushed as a "cross" source tagged with
   *  this tile's id so effectiveQueryFor excludes the SOURCE tile (it keeps full
   *  context) while every other compatible tile narrows. */
  function handleCrossFilter(filter: DashboardFilter): void {
    activeFilters.pushCross(filter, tile.id);
  }

  /** #1085: clear this tile's brush cross-filter (empty brush / Esc). */
  function handleClearCross(): void {
    activeFilters.clearCrossesFrom(tile.id);
  }

  // issue #915: multi-select. A click on the tile body (not a button, not
  // the chart/table click-filter affordances) toggles/extends/replaces the
  // selection depending on modifier keys. Selection is an edit-mode-only
  // concern — read-only viewers never select. Clicks that land on an
  // interactive control (buttons, links, inputs) are left alone so existing
  // tile actions keep working.
  function handleSelectClick(e: MouseEvent): void {
    if (readOnly) return;
    const target = e.target as HTMLElement | null;
    if (target?.closest("button, a, input, select, textarea, [data-resize-handle]")) {
      return;
    }
    const mode: SelectClickMode =
      e.ctrlKey || e.metaKey ? "toggle" : e.shiftKey ? "extend" : "replace";
    tileSelection.click(tile.id, mode);
  }

  const selected = $derived(!readOnly && tileSelection.isSelected(tile.id));
</script>

<!-- Right-click is a power-user shortcut for the overflow menu, and click
     drives edit-mode multi-select (#915). The kebab button (⋮) in the header
     is the keyboard-accessible equivalent; tile selection is a power-user
     layout aid, not a primary navigation path — so suppressing these rules is
     intentional. NB: this svelte-check honours only the FIRST code in a
     multi-code svelte-ignore, so they must be one-per-comment (#920). -->
<!-- svelte-ignore a11y_no_static_element_interactions -->
<!-- svelte-ignore a11y_click_events_have_key_events -->
<div
  class="tile"
  class:tile--selected={selected}
  class:tile--filter-hit={filterAffinityHover.active && filterAffinityHover.isAffected(tile.id)}
  class:tile--filter-miss={filterAffinityHover.active && !filterAffinityHover.isAffected(tile.id)}
  data-tile-type={tile.type}
  data-chart-titled={chartDrawsOwnTitle ? "" : undefined}
  oncontextmenu={openContextMenu}
  onclick={handleSelectClick}
>
  <header
    class="tile-header"
    class:tile-header--draggable={!readOnly}
    data-drag-handle={readOnly ? undefined : tile.id}
  >
    <span class="font-medium flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{tile.title ?? defaultTitle(tile)}</span>
    <!-- #942: comment badge — shown for a saved dashboard in both edit + viewer
         (hidden in the share view, where savedPath is empty). -->
    {#if commentsPath}
      <button
        type="button"
        class="icon-btn tile-comment-badge"
        aria-label="Comments"
        title="Comments"
        onclick={() => (commentsOpen = true)}
      >
        <MessageCircle size={14} />
        {#if commentCount > 0}<span class="tile-comment-count">{commentCount}</span>{/if}
      </button>
    {/if}
    {#if !readOnly}
      <div class="tile-actions">
        <button type="button" class="icon-btn" aria-label="Edit tile" onclick={handleEdit}>
          <Settings2 size={14} />
        </button>
        <button
          type="button"
          class="icon-btn"
          aria-label="Tile actions"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          onclick={openKebabMenu}
        >
          <MoreVertical size={14} />
        </button>
        <button type="button" class="icon-btn icon-btn--danger" aria-label="Remove tile" onclick={handleRemove}>
          <X size={14} />
        </button>
      </div>
    {/if}
  </header>
  <div class="flex-1 min-h-0 overflow-auto">
    {#if tile.type === "chart"}
      <ChartTile
        {tile}
        onClickFilter={readOnly ? undefined : handleClickFilter}
        onCrossFilter={readOnly ? undefined : handleCrossFilter}
        onClearCross={readOnly ? undefined : handleClearCross}
      />
    {:else if tile.type === "table"}
      <TableTile {tile} onClickFilter={readOnly ? undefined : handleClickFilter} />
    {:else if tile.type === "text"}
      <TextTile {tile} />
    {:else if tile.type === "kpi"}
      <KpiTile {tile} />
    {:else if tile.type === "image"}
      <ImageTile {tile} />
    {:else if tile.type === "custom"}
      {@const r = tile.custom ? getTileRenderer(tile.custom.renderer) : undefined}
      {#if r?.component}
        {@const Renderer = r.component}
        <Renderer {tile} onClickFilter={readOnly ? undefined : handleClickFilter} />
      {:else}
        <div class="p-2 text-fg-muted text-sm">Unknown renderer: {tile.custom?.renderer ?? "(none)"}</div>
      {/if}
    {:else}
      <div class="p-2 text-danger text-sm">Unknown tile type: {tile.type}</div>
    {/if}
  </div>
</div>

{#if menuOpen}
  <div
    class="tile-menu"
    role="menu"
    bind:this={menuEl}
    style="left: {menuX}px; top: {menuY}px;"
  >
    <button type="button" class="tile-menu__item" role="menuitem" onclick={duplicateFromMenu}>
      <Copy size={14} aria-hidden="true" />
      <span>Duplicate</span>
    </button>
    <!-- #932/#1175: height stepper — a touch-friendly way to grow/shrink a
         tile without dragging, especially in the mobile stacked layout. Stays
         open so repeated taps work; closes on outside click / Escape. -->
    <div class="tile-menu__row" role="group" aria-label="Tile height">
      <span class="flex-1 text-fg-muted text-sm">Height</span>
      <button
        type="button"
        class="tile-menu__step"
        aria-label="Decrease height"
        title="Decrease height"
        onclick={() => dashboardStore.adjustTileHeight(tile.id, -1)}
        disabled={tile.h <= 1}
      >
        <Minus size={14} aria-hidden="true" />
      </button>
      <span class="tile-menu__val" aria-live="polite">{tile.h}</span>
      <button
        type="button"
        class="tile-menu__step"
        aria-label="Increase height"
        title="Increase height"
        onclick={() => dashboardStore.adjustTileHeight(tile.id, 1)}
        disabled={tile.h >= 24}
      >
        <Plus size={14} aria-hidden="true" />
      </button>
    </div>
  </div>
{/if}

{#if editorOpen}
  <TileEditorModal {tile} onClose={() => (editorOpen = false)} />
{/if}

{#if commentsPath}
  <CommentsPanel
    dashboardPath={commentsPath}
    tileId={tile.id}
    tileTitle={tile.title ?? defaultTitle(tile)}
    open={commentsOpen}
    onClose={() => (commentsOpen = false)}
    onCountChange={(n) => (commentCount = n)}
  />
{/if}

<script module lang="ts">
  import type { DashboardTile as DT } from "$lib/api/dashboards";

  /** Fallback title when the analyst hasn't set one — keeps the chrome
   *  populated so the tile isn't a mystery rectangle. */
  function defaultTitle(tile: DT): string {
    switch (tile.type) {
      case "chart":
        return tile.chartType ? `${tile.chartType} chart` : "Chart";
      case "table":
        return "Table";
      case "text":
        return "Note";
      case "filter":
        return tile.target?.level ? `Filter: ${tile.target.level}` : "Filter";
      case "kpi":
        return tile.kpi?.measureCaption ?? tile.kpi?.measure ?? "KPI";
      case "image":
        return "Image";
      default:
        return tile.type;
    }
  }
</script>

<style>
.tile {
    display: flex;
    flex-direction: column;
    height: 100%;
    border: 1px solid hsl(var(--border));
    border-radius: 6px;
    background: hsl(var(--bg));
    overflow: hidden;
    /* issue #924: smooth the highlight/dim when a filter is hovered. */
    transition:
      opacity 0.12s ease,
      box-shadow 0.12s ease;
  }
  /* issue #924: a hovered panel filter previews its reach — compatible
     tiles get a 1px accent ring (inset, so layout doesn't shift), tiles
     it won't touch fade back. Purely transient; clears on mouse-out. */
  .tile--filter-hit {
    box-shadow: inset 0 0 0 1px hsl(var(--primary));
    border-color: hsl(var(--primary));
  }
  /* issue #915: multi-select outline. A 2px accent ring drawn with
     box-shadow (not border) so it never shifts the tile's layout, plus a
     matching border colour for a crisp edge. Sits above the filter-hit
     ring naturally — both use box-shadow, last-declared wins on overlap,
     and a selected tile reads as selected first. */
  .tile--selected {
    box-shadow: inset 0 0 0 2px hsl(var(--primary));
    border-color: hsl(var(--primary));
  }
  /* #942: comment badge sits between the title and the edit actions. */
  .tile-comment-badge {
    position: relative;
    margin-left: auto;
  }
  .tile-comment-badge ~ :global(.tile-actions) {
    margin-left: 0;
  }
  .tile-comment-count {
    position: absolute;
    top: -3px;
    right: -3px;
    min-width: 14px;
    height: 14px;
    padding: 0 3px;
    border-radius: 999px;
    background: hsl(var(--primary));
    color: #fff;
    font-size: 0.625rem;
    line-height: 14px;
    text-align: center;
    box-sizing: border-box;
  }
  .tile-header {
    display: flex;
    align-items: center;
    padding: 0.375rem 0.5rem;
    border-bottom: 1px solid hsl(var(--border));
    background: hsl(var(--bg-muted));
    font-size: 0.8125rem;
    /* Hint that the header doubles as the drag handle in edit mode.
       Read-only dashboards opt out via .tile-header--draggable. */
    user-select: none;
  }
  .tile-header--draggable {
    cursor: grab;
  }
  .tile-header--draggable:active {
    cursor: grabbing;
  }
  .tile-actions {
    display: flex;
    gap: 0.25rem;
  }
  /* .icon-btn / .icon-btn--danger inherit shape from app.css */
  /* Overflow menu — positioned: fixed so both kebab + right-click
     callers can drive (left, top) directly from viewport coords. */
  .tile-menu {
    position: fixed;
    min-width: 10rem;
    background: hsl(var(--bg));
    border: 1px solid hsl(var(--border));
    border-radius: 6px;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
    padding: 0.25rem;
    z-index: 50;
  }
  .tile-menu__item {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    width: 100%;
    padding: 0.5rem 0.625rem;
    border: 0;
    background: transparent;
    text-align: left;
    cursor: pointer;
    border-radius: 4px;
    color: hsl(var(--fg));
    font: inherit;
    font-size: 0.8125rem;
  }
  .tile-menu__item:hover,
  .tile-menu__item:focus {
    background: hsl(var(--bg-subtle));
    outline: none;
  }
  /* #932/#1175: height stepper row. */
  .tile-menu__row {
    display: flex;
    align-items: center;
    gap: 0.375rem;
    padding: 0.25rem 0.625rem;
  }
  .tile-menu__step {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 1.5rem;
    height: 1.5rem;
    border: 1px solid var(--border-strong, hsl(var(--border)));
    background: hsl(var(--bg));
    border-radius: 4px;
    color: hsl(var(--fg));
    cursor: pointer;
  }
  .tile-menu__step:hover:not(:disabled) {
    background: hsl(var(--bg-subtle));
  }
  .tile-menu__step:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
  .tile-menu__val {
    min-width: 1.25rem;
    text-align: center;
    font-size: 0.8125rem;
    color: hsl(var(--fg));
    font-variant-numeric: tabular-nums;
  }
</style>
