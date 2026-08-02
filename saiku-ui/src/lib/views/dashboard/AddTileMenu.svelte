<script lang="ts">
  /*
   * "+ Add tile" toolbar dropdown. Click the button to reveal the four
   * tile-type buttons; clicking any one fires onPick with the type.
   * The parent handles tile placement + dashboardStore.addTile().
   *
   * Tile-specific config (cube binding, query mode, filter target) is
   * set later via the tile's ⚙ edit button — see TileEditorModal.
   * Keeping add-tile as "pick a type and drop it" matches old Saiku's
   * authoring rhythm.
   */

  import type { TileType } from "$lib/api/dashboards";
  // App Builder Phase 2 (saiku#1441): surface registered custom renderers as an
  // add-tile option. None are registered yet (they arrive in Tasks 5-7), so the
  // entry stays disabled until a renderer is installed by import side-effect.
  import { listTileRenderers } from "$lib/dashboard/tileRegistry";
  import { Button } from "$lib/components/ui";

  interface Props {
    onPick: (type: TileType) => void;
    /** Seed a custom-renderer tile of the given registered renderer id (App
     *  Builder Phase 2). When omitted, custom renderers aren't offered. */
    onAddCustom?: (rendererId: string) => void;
    disabled?: boolean;
    /** Dropdown edge alignment. "right" (default) opens leftward — correct for a
     *  right-anchored dashboard toolbar. "left" opens rightward — used by the App
     *  Builder page toolbar so the menu opens INTO the page content instead of
     *  over the left nav rail. */
    align?: "left" | "right";
  }

  let { onPick, onAddCustom, disabled = false, align = "right" }: Props = $props();

  let open = $state(false);

  // Registered custom renderers, snapshotted when the menu opens (all
  // import-side-effect registrations have run by then). Each is offered as its
  // own menu entry so picking it seeds a "custom" tile already bound to that
  // renderer — the tile's ⚙ editor then configures the cube/query/options.
  const customRenderers = $derived(open && onAddCustom ? listTileRenderers() : []);

  function pick(type: TileType): void {
    onPick(type);
    open = false;
  }

  function pickCustom(rendererId: string): void {
    onAddCustom?.(rendererId);
    open = false;
  }

  // Close on outside click via a global handler bound when open.
  $effect(() => {
    if (!open) return;
    function onDocClick(e: MouseEvent): void {
      const target = e.target as Node | null;
      if (target && !rootEl?.contains(target)) {
        open = false;
      }
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  });

  let rootEl = $state<HTMLDivElement | null>(null);
</script>

<div class="relative" bind:this={rootEl}>
  <Button variant="outline" {disabled} aria-haspopup="menu" aria-expanded={open} onclick={() => (open = !open)}>
    + Add tile
  </Button>

  {#if open}
    <div class="menu" class:menu--left={align === "left"} role="menu">
      <button type="button" class="menu-item" role="menuitem" onclick={() => pick("chart")}>
        <span class="icon" aria-hidden="true">📊</span>
        <span>
          <span class="block font-medium">Chart</span>
          <span class="hint">Render measures as bar / line / pie / area.</span>
        </span>
      </button>
      <button type="button" class="menu-item" role="menuitem" onclick={() => pick("table")}>
        <span class="icon" aria-hidden="true">🧮</span>
        <span>
          <span class="block font-medium">Table</span>
          <span class="hint">Records of measure cells with row headers.</span>
        </span>
      </button>
      <button type="button" class="menu-item" role="menuitem" onclick={() => pick("kpi")}>
        <span class="icon" aria-hidden="true">📈</span>
        <span>
          <span class="block font-medium">KPI</span>
          <span class="hint">A single measure as a big number, with optional comparison + sparkline.</span>
        </span>
      </button>
      <button type="button" class="menu-item" role="menuitem" onclick={() => pick("text")}>
        <span class="icon" aria-hidden="true">📝</span>
        <span>
          <span class="block font-medium">Text / note</span>
          <span class="hint">Markdown annotation; no data.</span>
        </span>
      </button>
      <button type="button" class="menu-item" role="menuitem" onclick={() => pick("image")}>
        <span class="icon" aria-hidden="true">🖼️</span>
        <span>
          <span class="block font-medium">Image</span>
          <span class="hint">A logo, diagram or screenshot from a URL or upload.</span>
        </span>
      </button>
      {#if onAddCustom}
        {#if customRenderers.length === 0}
          <button type="button" class="menu-item" role="menuitem" disabled>
            <span class="icon" aria-hidden="true">🧩</span>
            <span>
              <span class="block font-medium">Custom…</span>
              <span class="hint">No custom renderers installed.</span>
            </span>
          </button>
        {:else}
          {#each customRenderers as r (r.id)}
            <button type="button" class="menu-item" role="menuitem" onclick={() => pickCustom(r.id)}>
              <span class="icon" aria-hidden="true">{r.icon ?? "🧩"}</span>
              <span>
                <span class="block font-medium">{r.label}</span>
                <span class="hint">Custom renderer — configure in the tile's ⚙ editor.</span>
              </span>
            </button>
          {/each}
        {/if}
      {/if}
    </div>
  {/if}
</div>

<style>
.menu {
    position: absolute;
    top: calc(100% + 4px);
    right: 0;
    background: hsl(var(--bg));
    /* Explicit chrome foreground — the App Builder renders this menu inside a
       light-themed app canvas, so without this the item LABELS (which set no
       colour of their own) inherit the app's dark text and render nearly
       invisible on the dark popover. saiku#1636. */
    color: hsl(var(--fg));
    border: 1px solid hsl(var(--border));
    border-radius: 6px;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
    padding: 0.25rem;
    z-index: 10;
    min-width: 18rem;
  }
  .menu--left {
    right: auto;
    left: 0;
  }
  .menu-item {
    display: flex;
    align-items: flex-start;
    gap: 0.5rem;
    width: 100%;
    padding: 0.5rem;
    border: none;
    background: transparent;
    text-align: left;
    cursor: pointer;
    border-radius: 4px;
    font-size: 0.875rem;
  }
  .menu-item:hover, .menu-item:focus {
    background: hsl(var(--bg-subtle));
    outline: none;
  }
  .menu-item:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
  .menu-item:disabled:hover, .menu-item:disabled:focus {
    background: transparent;
  }
  .icon {
    font-size: 1.125rem;
    line-height: 1;
    padding-top: 0.125rem;
  }
  .hint {
    display: block;
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
    margin-top: 0.125rem;
  }
</style>
