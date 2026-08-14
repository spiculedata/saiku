<script lang="ts">
  /*
   * Empty-state guidance shown when an editable dashboard has zero tiles
   * (issue #916). Replaces the blank canvas with a single visible call-
   * to-action: "Add your first tile" plus three primary buttons that
   * each pre-pick a tile type (Chart, Table, KPI) and hand it off to
   * the parent's existing handleAddTile() path.
   *
   * A secondary "More tile types" affordance points users at the
   * toolbar's existing Add tile dropdown — we deliberately do NOT
   * duplicate the dropdown itself here. One source of truth for the
   * full tile menu, an obvious starter set for first-time users.
   *
   * Render contract: parent decides when to show this (tiles.length === 0
   * AND !readOnly). The component is purely presentational; no store
   * subscriptions, no lifecycle.
   */

  import type { TileType } from "$lib/api/dashboards";
  import { BarChart3, Gauge, Table2 } from "lucide-svelte";

  interface Props {
    /** Invoked with the selected tile type. Parent owns placement +
     *  dashboardStore.addTile() (already implemented as handleAddTile()
     *  in DashboardEditor — same callback wired to AddTileMenu). */
    onAddTile: (type: TileType) => void;
    /** Override the subtitle line — the App Builder passes "page" copy since a
     *  page isn't a dashboard from the author's point of view. Defaults to the
     *  dashboard wording. */
    subtitle?: string;
  }

  let {
    onAddTile,
    subtitle = "Pick a tile type to start building this dashboard. You can configure data, filters, and layout after the tile is dropped.",
  }: Props = $props();
</script>

<div class="flex items-start justify-center py-10 px-4 flex-1 min-h-0" role="region" aria-label="Add your first tile">
  <div class="card">
    <h2 class="m-0 text-xl font-semibold text-fg">Add your first tile</h2>
    <p class="subtitle">{subtitle}</p>

    <div class="cta-row">
      <button
        type="button"
        class="cta"
        onclick={() => onAddTile("chart")}
        aria-label="Add a chart tile"
      >
        <span class="inline-flex items-center justify-center text-primary mb-1" aria-hidden="true">
          <BarChart3 size={28} strokeWidth={1.75} />
        </span>
        <span class="font-semibold text-base">Chart</span>
        <span class="cta-hint">Bar, line, pie or area visualisations of your measures.</span>
      </button>

      <button
        type="button"
        class="cta"
        onclick={() => onAddTile("table")}
        aria-label="Add a table tile"
      >
        <span class="inline-flex items-center justify-center text-primary mb-1" aria-hidden="true">
          <Table2 size={28} strokeWidth={1.75} />
        </span>
        <span class="font-semibold text-base">Table</span>
        <span class="cta-hint">Tabular cells with row and column headers.</span>
      </button>

      <button
        type="button"
        class="cta"
        onclick={() => onAddTile("kpi")}
        aria-label="Add a KPI tile"
      >
        <span class="inline-flex items-center justify-center text-primary mb-1" aria-hidden="true">
          <Gauge size={28} strokeWidth={1.75} />
        </span>
        <span class="font-semibold text-base">KPI</span>
        <span class="cta-hint">A single measure as a big number with optional comparison.</span>
      </button>
    </div>

    <!-- saiku#1762: this promised a "filter widget" in the + Add tile menu.
         There isn't one — the menu is Chart / Table / KPI / Text / Image plus
         the custom renderers, and filters live in the Filters panel. -->
    <p class="more">
      Need a text note, an image or a custom renderer? Use
      <strong>+ Add tile</strong> in the toolbar above for the full list.
      Filters are added in the <strong>Filters</strong> panel.
    </p>
  </div>
</div>

<style>
.card {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 1rem;
    max-width: 56rem;
    width: 100%;
    padding: 2rem 1.5rem;
    border: 1px dashed hsl(var(--border-strong));
    border-radius: 8px;
    background: hsl(var(--bg-subtle));
    text-align: center;
  }
  .subtitle {
    margin: 0;
    max-width: 36rem;
    color: hsl(var(--fg-muted));
    font-size: 0.875rem;
    line-height: 1.5;
  }
  .cta-row {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 0.75rem;
    width: 100%;
    margin-top: 0.5rem;
  }
  @media (max-width: 640px) {
    .cta-row {
      grid-template-columns: 1fr;
    }
  }
  .cta {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.375rem;
    padding: 1.25rem 1rem;
    background: hsl(var(--bg));
    border: 1px solid hsl(var(--border-strong));
    border-radius: 6px;
    cursor: pointer;
    color: hsl(var(--fg));
    font-family: inherit;
    transition: border-color 120ms ease, background 120ms ease, transform 120ms ease;
  }
  .cta:hover {
    border-color: hsl(var(--primary));
    background: color-mix(in srgb, hsl(var(--primary)) 6%, hsl(var(--bg)));
  }
  .cta:focus-visible {
    outline: 2px solid hsl(var(--primary));
    outline-offset: 2px;
  }
  .cta:active {
    transform: translateY(1px);
  }
  .cta-hint {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
    line-height: 1.4;
  }
  .more {
    margin: 0.25rem 0 0;
    color: hsl(var(--fg-muted));
    font-size: 0.8125rem;
  }
  .more strong {
    font-weight: var(--weight-medium);
    color: hsl(var(--fg));
  }
</style>
