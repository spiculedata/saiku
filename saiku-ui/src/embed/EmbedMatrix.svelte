<script lang="ts">
  /*
   * Minimal matrix-cellset → HTML table renderer for the embed bundle.
   *
   * Records mode is fine for flat rowsets ("Q1 = 100, Q2 = 120") but hides
   * hierarchical pivots — the row axis members become part of the row keys
   * and the caller loses the shape. Matrix mode preserves the shape:
   *
   *   {
   *     format: "matrix",
   *     matrix: [ { "0": cell, "1": cell, ... }, ... ],
   *     metadata: {
   *       rows: [{name, caption}, ...],       // one entry per matrix row
   *       columns: [{name, caption}, ...],    // column headers
   *     }
   *   }
   *
   * Each row of the emitted table has a leading TH (from metadata.rows[i])
   * plus one TD per column. If matrix carries no rows at all we render the
   * same empty-state as EmbedTable so the two components feel consistent.
   */
  import type { EmbedCaption, EmbedCell, EmbedMatrixRow } from "./types";

  interface Props {
    rows: EmbedMatrixRow[];
    rowCaptions: EmbedCaption[];
    columnCaptions: EmbedCaption[];
  }

  let { rows, rowCaptions, columnCaptions }: Props = $props();

  /** True when every row's cell for {@code idx} is numeric — same right-align
   *  test as EmbedTable, adapted to the numeric column-index key. */
  function isNumericColumn(idx: number): boolean {
    for (const r of rows) {
      const v = r[String(idx)]?.value;
      if (v === null || v === undefined) continue;
      if (typeof v !== "number" || Number.isNaN(v)) return false;
    }
    return true;
  }

  function cellAt(row: EmbedMatrixRow, idx: number): EmbedCell | undefined {
    return row[String(idx)];
  }
</script>

{#if rows.length === 0}
  <div class="empty">No rows returned</div>
{:else}
  <table>
    <thead>
      <tr>
        <!-- Leading column carries the row-axis caption (blank when the query
             has no row shelf — measures-only matrix). -->
        <th class="row-header"></th>
        {#each columnCaptions as col (col.name)}
          {@const idx = columnCaptions.indexOf(col)}
          <th class:numeric={isNumericColumn(idx)}>{col.caption}</th>
        {/each}
      </tr>
    </thead>
    <tbody>
      {#each rows as row, i (i)}
        <tr>
          <th class="row-header">{rowCaptions[i]?.caption ?? ""}</th>
          {#each columnCaptions as _col, idx (idx)}
            {@const cell = cellAt(row, idx)}
            <td
              class:numeric={isNumericColumn(idx)}
              class:negative={cell && cell.value !== null && cell.value < 0}
            >
              {cell?.formatted ?? ""}
            </td>
          {/each}
        </tr>
      {/each}
    </tbody>
  </table>
{/if}

<style>
  table {
    border-collapse: collapse;
    width: 100%;
    font-family:
      system-ui,
      -apple-system,
      Segoe UI,
      sans-serif;
    font-size: 13px;
    color: var(--saiku-embed-fg, #1f2937);
  }
  th,
  td {
    padding: 6px 10px;
    border-bottom: 1px solid var(--saiku-embed-border, #e5e7eb);
    text-align: left;
    white-space: nowrap;
  }
  th {
    background: var(--saiku-embed-header-bg, #f9fafb);
    font-weight: 600;
  }
  /* Row-axis headers live in the first column — sticky-left in wide tables
     so the axis label stays visible when the caller wraps us in a scroller. */
  th.row-header {
    position: sticky;
    left: 0;
    background: var(--saiku-embed-header-bg, #f9fafb);
    z-index: 1;
  }
  th.numeric,
  td.numeric {
    text-align: right;
    font-variant-numeric: tabular-nums;
  }
  td.negative {
    color: var(--saiku-embed-negative, #b91c1c);
  }
  tbody tr:hover {
    background: var(--saiku-embed-row-hover, #f3f4f6);
  }
  .empty {
    padding: 12px;
    color: var(--saiku-embed-muted, #6b7280);
    font-family: system-ui, sans-serif;
    font-size: 13px;
  }
</style>
