<script lang="ts">
  /*
   * 12-column CSS grid that holds the dashboard's tiles.
   *
   * Auto-stacks to a single column below ~768px so the grid stays
   * readable on phones (read-only mode forces the same stack).
   *
   * Edit-mode interactions (saiku#911):
   *   - Drag-to-move: the tile header (rendered with data-drag-handle
   *     by Tile.svelte) starts a pointer-driven drag. The grid renders
   *     a ghost overlay at the snapped target cell while the pointer
   *     moves, and on release commits the new (x, y) through
   *     repositionTile + dashboardStore.replaceTiles. The cascade in
   *     repositionTile pushes overlapping siblings down rather than
   *     allowing overlap, mirroring the editor modal's behaviour.
   *   - Resize: a small grip in each cell's bottom-right corner drags
   *     to adjust (w, h). Same snap + commit pipeline; the grip is
   *     rendered as a sibling of <Tile/> inside the cell so the tile
   *     body never sees the pointer events that drive the resize.
   *
   * The single source of truth for "what cell does this pointer
   * position correspond to" is the dragging tile's own bounding rect:
   * cellUnitW = rect.width / tile.w, cellUnitH = rect.height / tile.h.
   * Cheaper than reading the grid's computed track sizing and works
   * regardless of how the browser actually solved the 1fr columns.
   */

  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { compactUpward, repositionTile } from "$lib/dashboard/tilePlacement";
  import { tileSelection } from "$lib/stores/tileSelection.svelte";
  import Tile from "$lib/views/dashboard/Tile.svelte";

  interface Props {
    readOnly?: boolean;
  }

  let { readOnly = false }: Props = $props();

  let gridEl = $state<HTMLDivElement | null>(null);

  /** Origin captured when a drag or resize starts. The fields are
   *  shared between both modes — drag updates x/y, resize updates w/h
   *  — so a single union state covers the active interaction. */
  interface GestureState {
    mode: "drag" | "resize";
    tileId: string;
    startPointerX: number;
    startPointerY: number;
    startX: number;
    startY: number;
    startW: number;
    startH: number;
    /** Per-column / per-row pixel size, derived from the dragging
     *  tile's bounding rect at gesture-start. Stays constant for the
     *  whole gesture even if the layout reflows around the ghost. */
    cellUnitW: number;
    cellUnitH: number;
    /** Snapped target (col offset, row offset) — drag updates x/y,
     *  resize updates w/h. Recomputed on every pointermove; rendered
     *  as the ghost overlay. */
    targetX: number;
    targetY: number;
    targetW: number;
    targetH: number;
  }

  let gesture = $state<GestureState | null>(null);

  function onPointerDown(e: PointerEvent): void {
    if (readOnly) return;
    if (e.button !== 0) return; // primary button only
    const target = e.target as HTMLElement | null;
    if (!target) return;

    // Don't start a drag when the user is interacting with a button
    // inside the header (Edit / Remove) — those have their own click
    // handlers and shouldn't be hijacked.
    if (target.closest("button")) return;

    const resizeHandle = target.closest<HTMLElement>("[data-resize-handle]");
    const dragHandle = !resizeHandle ? target.closest<HTMLElement>("[data-drag-handle]") : null;

    const tileId = resizeHandle?.dataset.resizeHandle ?? dragHandle?.dataset.dragHandle;
    if (!tileId) return;

    const layout = dashboardStore.current?.layout;
    if (!layout) return;
    const tile = layout.tiles.find((t) => t.id === tileId);
    if (!tile) return;

    const cellEl = (resizeHandle ?? dragHandle)!.closest<HTMLElement>(".cell");
    if (!cellEl) return;
    const rect = cellEl.getBoundingClientRect();
    if (rect.width === 0 || rect.height === 0) return;

    gesture = {
      mode: resizeHandle ? "resize" : "drag",
      tileId,
      startPointerX: e.clientX,
      startPointerY: e.clientY,
      startX: tile.x,
      startY: tile.y,
      startW: tile.w,
      startH: tile.h,
      cellUnitW: rect.width / tile.w,
      cellUnitH: rect.height / tile.h,
      targetX: tile.x,
      targetY: tile.y,
      targetW: tile.w,
      targetH: tile.h,
    };

    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    e.preventDefault();
  }

  function onPointerMove(e: PointerEvent): void {
    if (!gesture) return;
    const g = gesture;
    const layout = dashboardStore.current?.layout;
    if (!layout) return;
    const cols = layout.cols || 12;

    const dx = e.clientX - g.startPointerX;
    const dy = e.clientY - g.startPointerY;
    const colDelta = Math.round(dx / g.cellUnitW);
    const rowDelta = Math.round(dy / g.cellUnitH);

    if (g.mode === "drag") {
      let nx = g.startX + colDelta;
      let ny = g.startY + rowDelta;
      // Clamp x so the tile stays inside the grid; y can grow freely
      // (the grid expands downward with auto-rows).
      nx = Math.max(0, Math.min(nx, cols - g.startW));
      ny = Math.max(0, ny);
      if (nx !== g.targetX || ny !== g.targetY) {
        gesture = { ...g, targetX: nx, targetY: ny };
      }
    } else {
      // Resize from bottom-right: width and height grow with positive
      // deltas; clamp width to fit the right edge of the grid, height
      // to a sane minimum of 1 row.
      let nw = Math.max(1, g.startW + colDelta);
      let nh = Math.max(1, g.startH + rowDelta);
      nw = Math.min(nw, cols - g.startX);
      if (nw !== g.targetW || nh !== g.targetH) {
        gesture = { ...g, targetW: nw, targetH: nh };
      }
    }
  }

  function onPointerUp(e: PointerEvent): void {
    if (!gesture) return;
    const g = gesture;
    const layout = dashboardStore.current?.layout;
    if (!layout) {
      gesture = null;
      return;
    }

    const moved =
      g.targetX !== g.startX ||
      g.targetY !== g.startY ||
      g.targetW !== g.startW ||
      g.targetH !== g.startH;
    if (moved) {
      const result = repositionTile(layout, g.tileId, {
        x: g.targetX,
        y: g.targetY,
        w: g.targetW,
        h: g.targetH,
      });
      if (result.ok && result.tiles) {
        // After a resize-shrink, pull every other tile upward to
        // reclaim the freed space — anchored on the resized tile so
        // it stays put. Drag drops are NOT compacted; an explicit
        // drag-down should preserve the gap above the dragged tile.
        const shrunk =
          g.mode === "resize" && (g.targetW < g.startW || g.targetH < g.startH);
        const tiles = shrunk ? compactUpward(result.tiles, g.tileId) : result.tiles;
        dashboardStore.replaceTiles(tiles);
      }
      // Validation failures (e.g. width overruns the grid past clamp)
      // are silently dropped — the gesture ends and the tile snaps
      // back to its previous (x, y, w, h).
    }

    (e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId);
    gesture = null;
  }

  function onPointerCancel(): void {
    gesture = null;
  }

  // issue #915: a click on the empty canvas (not on any tile) clears the
  // multi-selection. Tile clicks set the selection and bubble up here, so
  // we only clear when the click did NOT land inside a .tile.
  function onGridClick(e: MouseEvent): void {
    if (readOnly) return;
    const target = e.target as HTMLElement | null;
    if (!target?.closest(".tile")) {
      tileSelection.clear();
    }
  }

  // issue #915: keep the selection honest — drop any ids whose tiles have
  // been removed (delete, bulk-delete, filter migration). Tracks the live
  // tile-id list reactively.
  $effect(() => {
    const ids = dashboardStore.current?.layout.tiles.map((t) => t.id) ?? [];
    tileSelection.prune(ids);
  });
</script>

{#if dashboardStore.current}
  {@const dash = dashboardStore.current}
  {#if dash.layout.tiles.length === 0}
    <div class="empty">
      <p>No tiles yet.</p>
      <p class="hint">Use <em>Add tile</em> in the toolbar to start building.</p>
    </div>
  {:else}
    <!-- svelte-ignore a11y_click_events_have_key_events a11y_no_noninteractive_element_interactions
         The grid's onclick only clears the multi-selection when the user
         clicks the empty canvas (#915) — a power-user layout aid, not a
         primary control. Tile selection itself has keyboard-reachable
         per-tile actions (edit / remove / kebab) so this background-clear
         shortcut carries no keyboard responsibility of its own. -->
    <div
      class="grid"
      class:grid--gesturing={gesture !== null}
      style:--cols={dash.layout.cols}
      role="region"
      aria-label="Dashboard tiles"
      bind:this={gridEl}
      onpointerdown={onPointerDown}
      onpointermove={onPointerMove}
      onpointerup={onPointerUp}
      onpointercancel={onPointerCancel}
      onclick={onGridClick}
    >
      {#each dash.layout.tiles as tile (tile.id)}
        {@const isGestureTarget = gesture?.tileId === tile.id}
        <div
          class="cell"
          class:cell--dragging={isGestureTarget}
          style:grid-column="{tile.x + 1} / span {tile.w}"
          style:grid-row="{tile.y + 1} / span {tile.h}"
        >
          <Tile {tile} {readOnly} />
          {#if !readOnly}
            <span
              class="resize-handle"
              data-resize-handle={tile.id}
              aria-label="Resize tile"
              title="Drag to resize"
            ></span>
          {/if}
        </div>
      {/each}
      {#if gesture}
        <div
          class="ghost"
          class:ghost--resize={gesture.mode === "resize"}
          style:grid-column="{gesture.targetX + 1} / span {gesture.targetW}"
          style:grid-row="{gesture.targetY + 1} / span {gesture.targetH}"
          aria-hidden="true"
        ></div>
      {/if}
    </div>
  {/if}
{/if}

<style>
  .grid {
    display: grid;
    /* Fixed N-column grid; rows auto-size to tile h × baseline row height. */
    grid-template-columns: repeat(var(--cols, 12), 1fr);
    grid-auto-rows: minmax(80px, auto);
    gap: 0.5rem;
    flex: 1;
    min-height: 0;
    /* Scroll vertically when the grid's natural height exceeds the
       column flex parent — without this, tiles pushed past the
       viewport (commonly the filter widgets at the bottom) get
       clipped and there's no way for the user to reach them. */
    overflow-y: auto;
    overflow-x: hidden;
    /* Padding-right so the resize grip on rightmost cells doesn't
       sit right against the scrollbar. align-content:start keeps the
       grid packed to the top when there's leftover space. */
    align-content: start;
    padding-right: 4px;
    position: relative;
    touch-action: none;
  }
  .grid--gesturing {
    /* Suppress text selection while dragging. */
    user-select: none;
  }
  .cell {
    /* Tiles render their own border / chrome; the cell is just positioning. */
    min-width: 0;
    position: relative;
  }
  .cell--dragging {
    opacity: 0.45;
    pointer-events: none;
  }
  .resize-handle {
    position: absolute;
    right: 2px;
    bottom: 2px;
    width: 14px;
    height: 14px;
    cursor: se-resize;
    /* Visible-but-restrained grip — three small diagonal strokes. */
    background-image: linear-gradient(
      135deg,
      transparent 0,
      transparent 6px,
      var(--fg-muted) 6px,
      var(--fg-muted) 7px,
      transparent 7px,
      transparent 9px,
      var(--fg-muted) 9px,
      var(--fg-muted) 10px,
      transparent 10px,
      transparent 12px,
      var(--fg-muted) 12px,
      var(--fg-muted) 13px,
      transparent 13px
    );
    opacity: 0.5;
    transition: opacity 80ms ease;
    z-index: 1;
  }
  .resize-handle:hover {
    opacity: 1;
  }
  .ghost {
    border: 2px dashed var(--accent, color-mix(in srgb, var(--fg) 30%, transparent));
    background: color-mix(in srgb, var(--accent, var(--fg)) 12%, transparent);
    border-radius: 6px;
    pointer-events: none;
    z-index: 2;
  }
  .ghost--resize {
    /* Slightly different cue for resize so the user can tell drag
       vs resize apart at a glance. */
    border-style: solid;
    border-width: 2px;
  }
  /* Auto-stack: below ~768px every tile collapses to a single column
     and drag-resize is disabled (the grid template loses its column
     basis, so the snap math wouldn't be meaningful anyway). */
  @media (max-width: 768px) {
    .grid {
      grid-template-columns: 1fr;
      touch-action: auto;
    }
    .cell {
      grid-column: 1 / span 1 !important;
      grid-row: auto / auto !important;
    }
    .resize-handle,
    .ghost {
      display: none;
    }
  }
  .empty {
    padding: 3rem 1rem;
    text-align: center;
    color: var(--fg-muted);
    border: 2px dashed var(--border);
    border-radius: 8px;
  }
  .empty p { margin: 0.25rem 0; }
  .empty .hint { font-size: 0.875rem; }
</style>
