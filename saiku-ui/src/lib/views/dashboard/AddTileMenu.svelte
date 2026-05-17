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

  interface Props {
    onPick: (type: TileType) => void;
    disabled?: boolean;
  }

  let { onPick, disabled = false }: Props = $props();

  let open = $state(false);

  function pick(type: TileType): void {
    onPick(type);
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

<div class="add-tile-menu" bind:this={rootEl}>
  <button
    type="button"
    class="btn"
    {disabled}
    aria-haspopup="menu"
    aria-expanded={open}
    onclick={() => (open = !open)}
  >
    + Add tile
  </button>

  {#if open}
    <div class="menu" role="menu">
      <button type="button" class="menu-item" role="menuitem" onclick={() => pick("chart")}>
        <span class="icon" aria-hidden="true">📊</span>
        <span>
          <span class="label">Chart</span>
          <span class="hint">Render measures as bar / line / pie / area.</span>
        </span>
      </button>
      <button type="button" class="menu-item" role="menuitem" onclick={() => pick("table")}>
        <span class="icon" aria-hidden="true">🧮</span>
        <span>
          <span class="label">Table</span>
          <span class="hint">Records of measure cells with row headers.</span>
        </span>
      </button>
      <button type="button" class="menu-item" role="menuitem" onclick={() => pick("filter")}>
        <span class="icon" aria-hidden="true">🔍</span>
        <span>
          <span class="label">Filter widget</span>
          <span class="hint">Pushes a filter across compatible tiles.</span>
        </span>
      </button>
      <button type="button" class="menu-item" role="menuitem" onclick={() => pick("text")}>
        <span class="icon" aria-hidden="true">📝</span>
        <span>
          <span class="label">Text / note</span>
          <span class="hint">Markdown annotation; no data.</span>
        </span>
      </button>
    </div>
  {/if}
</div>

<style>
  .add-tile-menu {
    position: relative;
  }
  .btn {
    padding: 0.375rem 0.75rem;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.875rem;
  }
  .btn:disabled { opacity: 0.5; cursor: not-allowed; }
  .menu {
    position: absolute;
    top: calc(100% + 4px);
    right: 0;
    background: var(--bg);
    border: 1px solid var(--border);
    border-radius: 6px;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
    padding: 0.25rem;
    z-index: 10;
    min-width: 18rem;
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
    background: var(--bg-subtle);
    outline: none;
  }
  .icon {
    font-size: 1.125rem;
    line-height: 1;
    padding-top: 0.125rem;
  }
  .label {
    display: block;
    font-weight: 500;
  }
  .hint {
    display: block;
    font-size: 0.75rem;
    color: var(--fg-muted);
    margin-top: 0.125rem;
  }
</style>
