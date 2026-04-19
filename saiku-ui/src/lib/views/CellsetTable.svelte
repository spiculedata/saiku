<script lang="ts">
  import type { CellEntry, QueryResult } from "$lib/api/query";

  interface Props {
    result: QueryResult;
  }

  let { result }: Props = $props();

  function classFor(c: CellEntry): string {
    switch (c.type) {
      case "COLUMN_HEADER":
        return "cell cell--colhead";
      case "ROW_HEADER":
      case "ROW_HEADER_HEADER":
        return "cell cell--rowhead";
      case "DATA_CELL":
        return "cell cell--data";
      case "EMPTY":
        return "cell cell--empty";
      case "ERROR":
        return "cell cell--error";
      default:
        return "cell";
    }
  }
</script>

{#if result.error}
  <p class="callout callout--danger">{result.error}</p>
{:else if !result.cellset || result.cellset.length === 0}
  <p class="empty">No rows returned.</p>
{:else}
  <div class="grid-wrap">
    <table class="grid">
      <tbody>
        {#each result.cellset as row}
          <tr>
            {#each row as cell}
              <td class={classFor(cell)} title={cell.properties?.uniquename ?? ""}>
                {cell.value ?? ""}
              </td>
            {/each}
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
  {#if result.runtime != null}
    <p class="runtime">Runtime: {result.runtime} ms · {result.height ?? result.cellset.length} rows × {result.width ?? result.cellset[0]?.length ?? 0} cols</p>
  {/if}
{/if}

<style>
  .grid-wrap {
    overflow: auto;
    max-height: 60vh;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg);
  }
  .grid {
    border-collapse: collapse;
    width: max-content;
    min-width: 100%;
    font-size: var(--fs-sm);
  }
  .cell {
    padding: var(--space-1) var(--space-2);
    border-right: 1px solid var(--border);
    border-bottom: 1px solid var(--border);
    white-space: nowrap;
    min-width: 80px;
  }
  .cell--colhead {
    background: var(--bg-muted);
    font-weight: 600;
    position: sticky;
    top: 0;
    text-align: center;
  }
  .cell--rowhead {
    background: var(--bg-muted);
    font-weight: 500;
    position: sticky;
    left: 0;
  }
  .cell--data { text-align: right; font-variant-numeric: tabular-nums; }
  .cell--empty { color: var(--fg-subtle); }
  .cell--error { color: var(--danger); }
  .empty { color: var(--fg-muted); padding: var(--space-4); }
  .runtime { color: var(--fg-subtle); font-size: var(--fs-xs); margin: var(--space-2) 0 0; }
</style>
