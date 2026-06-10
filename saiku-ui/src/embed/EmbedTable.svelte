<script lang="ts">
  /*
   * Minimal records → HTML table renderer. Lives inside the embed bundle
   * rather than reusing CellsetTable.svelte from the main app because
   * CellsetTable pulls in the whole query/datasource/session graph; the
   * embed bundle has to stay tight and import-graph independent.
   *
   * Shape: each row is a {column-caption → cell} map. The first row's
   * keys define the column order; missing cells in later rows render
   * blank. Numeric cells render right-aligned via the cell.value type
   * test, which also drives the dark-mode-aware "negative" colour.
   */
  import type { EmbedRow } from "./types";

  interface Props {
    rows: EmbedRow[];
  }

  let { rows }: Props = $props();

  let columns = $derived(rows.length > 0 ? Object.keys(rows[0]) : []);

  /** True when every row's value for {@code col} is numeric — drives the
   *  text-align: right styling so we never right-align a member-caption
   *  column that happens to have a number in its first row. */
  function isNumericColumn(col: string): boolean {
    for (const r of rows) {
      const v = r[col]?.value;
      if (v === null || v === undefined) continue;
      if (typeof v !== "number" || Number.isNaN(v)) return false;
    }
    return true;
  }
</script>

{#if rows.length === 0}
  <div class="empty">No rows returned</div>
{:else}
  <table>
    <thead>
      <tr>
        {#each columns as col (col)}
          <th class:numeric={isNumericColumn(col)}>{col}</th>
        {/each}
      </tr>
    </thead>
    <tbody>
      {#each rows as row, i (i)}
        <tr>
          {#each columns as col (col)}
            {@const cell = row[col]}
            <td
              class:numeric={isNumericColumn(col)}
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
  /* Scoped styles ship inside the custom element's shadow DOM, so they
   * never leak into the host page's CSS and vice versa. Deliberately
   * conservative — host page should be able to wrap us in any layout. */
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
