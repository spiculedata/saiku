<script lang="ts">
  /*
   * Filter-suggestion picker. Scans the dashboard's inline tiles via
   * suggestFiltersForTiles(), shows the candidates as a checklist, and
   * lets the author batch-add filter-widget tiles for the targets they
   * want to expose. Targets already covered by an existing filter-widget
   * tile are pre-filtered out so the panel never offers duplicates.
   *
   * The author can uncheck any candidate to skip it — Tom's design ask:
   * "offer up the panel populated and a user could then delete filters
   * they don't want to expose."
   */

  import { untrack } from "svelte";
  import { Button } from "$lib/components/ui";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import type { PanelFilter } from "$lib/api/dashboards";
  import {
    suggestFiltersForTilesAsync,
    pruneAlreadyExposed,
    type FilterSuggestion,
  } from "$lib/dashboard/filterSuggestions";

  interface Props {
    open: boolean;
    onClose: () => void;
  }

  let { open, onClose }: Props = $props();

  // Async scan — reference tiles need their saved .saiku fetched + parsed
  // before we can see what dims they touch.
  let suggestions = $state<FilterSuggestion[]>([]);
  let loading = $state(false);
  let scanError = $state<string | null>(null);

  // Track which suggestions the user wants to add. Default ON — Tom's
  // ask was "user could then delete filters they don't want to expose",
  // so we start everything selected.
  let selected = $state<Map<string, boolean>>(new Map());

  $effect(() => {
    // Refire whenever the modal transitions to open. Untrack the
    // dashboard tiles read so a re-fetch only happens on open, not on
    // every tile mutation while the modal is shown.
    if (!open) {
      suggestions = [];
      scanError = null;
      return;
    }
    const tiles = untrack(() => dashboardStore.current?.layout.tiles ?? []);
    const panelFilters = untrack(() => dashboardStore.current?.filterPanel?.filters ?? []);
    loading = true;
    scanError = null;
    void (async () => {
      try {
        const raw = await suggestFiltersForTilesAsync(tiles);
        const pruned = pruneAlreadyExposed(raw, panelFilters);
        suggestions = pruned;
        const next = new Map<string, boolean>();
        for (const s of pruned) next.set(s.id, true);
        selected = next;
      } catch (e: unknown) {
        scanError = e instanceof Error ? e.message : String(e);
      } finally {
        loading = false;
      }
    })();
  });

  function toggle(id: string): void {
    const next = new Map(selected);
    next.set(id, !next.get(id));
    selected = next;
  }

  function selectAll(): void {
    const next = new Map<string, boolean>();
    for (const s of suggestions) next.set(s.id, true);
    selected = next;
  }

  function selectNone(): void {
    const next = new Map<string, boolean>();
    for (const s of suggestions) next.set(s.id, false);
    selected = next;
  }

  function handleAdd(): void {
    // saiku#996: suggestions now flow into the unified filter panel
    // instead of dropping per-filter tiles into the grid.
    for (const s of suggestions) {
      if (!selected.get(s.id)) continue;
      const filter: PanelFilter = {
        id: `sug-${cryptoUuid()}`,
        widget: "single-select",
        cube: s.cube,
        dimension: s.dimension,
        hierarchy: s.hierarchy,
        level: s.level,
        members: [],
      };
      dashboardStore.addPanelFilter(filter);
    }
    onClose();
  }

  function cryptoUuid(): string {
    return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
      const r = (Math.random() * 16) | 0;
      const v = c === "x" ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }

  function describeTileCount(s: FilterSuggestion): string {
    const n = s.contributingTileIds.length;
    return `${n} tile${n === 1 ? "" : "s"}`;
  }
</script>

{#if open}
  <div class="backdrop" role="presentation" onclick={onClose}></div>
  <div class="modal" role="dialog" aria-modal="true" aria-label="Suggest filter widgets">
    <header class="head">
      <h2>Suggest filter widgets</h2>
      <button type="button" class="border-0 bg-transparent cursor-pointer text-xl leading-none text-fg-muted" aria-label="Close" onclick={onClose}>×</button>
    </header>

    {#if loading}
      <p class="hint">Scanning tiles…</p>
    {:else if scanError}
      <p class="empty">Scan failed: {scanError}</p>
    {:else if suggestions.length === 0}
      <p class="empty">
        Nothing to suggest — your tiles don't use any dimensions yet, or every
        candidate already has a filter widget on the dashboard.
      </p>
    {:else}
      <p class="hint">
        These dimensions/levels are used by your dashboard's chart and table
        tiles. Uncheck the ones you don't want to expose as a filter widget.
      </p>
      <div class="bulk">
        <button type="button" class="link" onclick={selectAll}>Select all</button>
        <span class="sep">·</span>
        <button type="button" class="link" onclick={selectNone}>Select none</button>
      </div>
      <ul class="list-none m-0 py-2 px-0 overflow-y-auto flex-1 min-h-0">
        {#each suggestions as s (s.id)}
          <li class="item">
            <label>
              <input
                type="checkbox"
                checked={selected.get(s.id) ?? false}
                onchange={() => toggle(s.id)}
              />
              <span class="target">
                <span class="level">{s.level}</span>
                <span class="path">
                  {s.cube.cubeName} · {s.dimension} / {s.hierarchy}
                </span>
                <span class="meta">used by {describeTileCount(s)}</span>
              </span>
            </label>
          </li>
        {/each}
      </ul>
    {/if}

    <footer class="flex gap-2 justify-end py-3 px-4 border-t border-border">
      <Button variant="outline" onclick={onClose}>Cancel</Button>
      <Button onclick={handleAdd} disabled={suggestions.length === 0}>
        Add selected
      </Button>
    </footer>
  </div>
{/if}

<style>
.backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.4);
    z-index: 100;
  }
  .modal {
    position: fixed;
    top: 50%;
    left: 50%;
    transform: translate(-50%, -50%);
    z-index: 101;
    background: hsl(var(--bg));
    border: 1px solid hsl(var(--border));
    border-radius: 8px;
    box-shadow: 0 12px 40px rgba(0, 0, 0, 0.2);
    width: min(540px, 90vw);
    max-height: 80vh;
    display: flex;
    flex-direction: column;
  }
  .head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0.75rem 1rem;
    border-bottom: 1px solid hsl(var(--border));
  }
  .head h2 {
    margin: 0;
    font-size: 1rem;
    font-weight: var(--weight-semibold);
  }
  .hint, .empty {
    padding: 0.75rem 1rem 0;
    color: hsl(var(--fg-muted));
    font-size: 0.8125rem;
    margin: 0;
  }
  .bulk {
    padding: 0.5rem 1rem 0;
    font-size: 0.8125rem;
    color: hsl(var(--fg-muted));
  }
  .link {
    border: none;
    background: transparent;
    color: hsl(var(--primary));
    cursor: pointer;
    padding: 0;
    font: inherit;
  }
  .sep {
    margin: 0 0.375rem;
  }
  .item {
    padding: 0.375rem 1rem;
  }
  .item label {
    display: flex;
    align-items: flex-start;
    gap: 0.5rem;
    cursor: pointer;
  }
  .target {
    display: flex;
    flex-direction: column;
    gap: 0.0625rem;
  }
  .level {
    font-size: 0.9375rem;
    font-weight: var(--weight-medium);
  }
  .path, .meta {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
  }
</style>
