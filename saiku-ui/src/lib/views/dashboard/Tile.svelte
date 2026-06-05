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
  import type { DashboardTile, DashboardFilter } from "$lib/api/dashboards";
  import ChartTile from "$lib/views/dashboard/tiles/ChartTile.svelte";
  import TableTile from "$lib/views/dashboard/tiles/TableTile.svelte";
  import TextTile from "$lib/views/dashboard/tiles/TextTile.svelte";
  import KpiTile from "$lib/views/dashboard/tiles/KpiTile.svelte";
  import ImageTile from "$lib/views/dashboard/tiles/ImageTile.svelte";
  import TileEditorModal from "$lib/views/dashboard/TileEditorModal.svelte";
  import { Copy, MoreVertical, Settings2, X, MessageCircle } from "lucide-svelte";
  // #942 PR2 — per-tile comments.
  import CommentsPanel from "$lib/views/dashboard/CommentsPanel.svelte";
  import { getComments } from "$lib/api/dashboards";

  interface Props {
    tile: DashboardTile;
    readOnly?: boolean;
  }

  let { tile, readOnly = false }: Props = $props();

  let editorOpen = $state(false);

  // #942: comments act on the SAVED dashboard. savedPath is empty for an
  // unsaved draft AND for the public share viewer (hydrated with ""), so the
  // badge naturally hides in both — guests can't comment.
  const commentsPath = $derived(dashboardStore.savedPath);
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
</script>

<!-- svelte-ignore a11y_no_static_element_interactions
     Right-click is a power-user shortcut for the overflow menu. The
     kebab button (⋮) in the header is the keyboard-accessible
     equivalent — same menu, same actions — so no a11y regression
     from suppressing the contextmenu-handler-needs-a-role rule. -->
<div class="tile" data-tile-type={tile.type} oncontextmenu={openContextMenu}>
  <header
    class="tile-header"
    class:tile-header--draggable={!readOnly}
    data-drag-handle={readOnly ? undefined : tile.id}
  >
    <span class="title">{tile.title ?? defaultTitle(tile)}</span>
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
  <div class="tile-body">
    {#if tile.type === "chart"}
      <ChartTile {tile} onClickFilter={readOnly ? undefined : handleClickFilter} />
    {:else if tile.type === "table"}
      <TableTile {tile} onClickFilter={readOnly ? undefined : handleClickFilter} />
    {:else if tile.type === "text"}
      <TextTile {tile} />
    {:else if tile.type === "kpi"}
      <KpiTile {tile} />
    {:else if tile.type === "image"}
      <ImageTile {tile} />
    {:else}
      <div class="unknown">Unknown tile type: {tile.type}</div>
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
    border: 1px solid var(--border);
    border-radius: 6px;
    background: var(--bg);
    overflow: hidden;
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
    background: var(--accent);
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
    border-bottom: 1px solid var(--border);
    background: var(--bg-muted);
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
  .title {
    font-weight: var(--weight-medium);
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .tile-actions {
    display: flex;
    gap: 0.25rem;
  }
  /* .icon-btn / .icon-btn--danger inherit shape from app.css */
  .tile-body {
    flex: 1;
    min-height: 0;
    overflow: auto;
  }
  .unknown {
    padding: 0.5rem;
    color: var(--danger);
    font-size: 0.8125rem;
  }

  /* Overflow menu — positioned: fixed so both kebab + right-click
     callers can drive (left, top) directly from viewport coords. */
  .tile-menu {
    position: fixed;
    min-width: 10rem;
    background: var(--bg);
    border: 1px solid var(--border);
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
    color: var(--fg);
    font: inherit;
    font-size: 0.8125rem;
  }
  .tile-menu__item:hover,
  .tile-menu__item:focus {
    background: var(--bg-subtle);
    outline: none;
  }
</style>
