<script lang="ts">
  import { onMount, onDestroy } from "svelte";
  import {
    createGrid,
    AllCommunityModule,
    ModuleRegistry,
    type ColDef,
    type GridApi,
    type GridOptions,
  } from "ag-grid-community";
  import type { QueryResult } from "$lib/api/query";
  import { parseCellset } from "$lib/views/cellsetUtils";

  ModuleRegistry.registerModules([AllCommunityModule]);

  interface Props {
    result: QueryResult;
    dark?: boolean;
  }

  let { result, dark = true }: Props = $props();

  let host: HTMLDivElement | null = null;
  let api: GridApi | null = null;

  function buildOptions(r: QueryResult): GridOptions {
    const parsed = parseCellset(r);
    const rowHeaderCols = parsed.rowHeaderColCount;
    const dataCols = parsed.dataRows[0]?.length ?? 0;

    const columnDefs: ColDef[] = [];
    for (let i = 0; i < rowHeaderCols; i++) {
      const headerName =
        parsed.columnHeaderRows.length > 0
          ? parsed.columnHeaderRows[parsed.columnHeaderRows.length - 1]?.[i]?.value ?? ""
          : "";
      columnDefs.push({
        headerName,
        field: `r${i}`,
        pinned: "left",
        cellClass: "cell cell--rowhead",
        resizable: true,
        sortable: false,
        minWidth: 120,
      });
    }
    for (let i = 0; i < dataCols; i++) {
      const labelParts: string[] = [];
      for (const headerRow of parsed.columnHeaderRows) {
        const c = headerRow[rowHeaderCols + i];
        if (c && c.value) labelParts.push(c.value);
      }
      columnDefs.push({
        headerName: labelParts[labelParts.length - 1] ?? `Col ${i + 1}`,
        headerTooltip: labelParts.join(" / "),
        field: `c${i}`,
        cellClass: (p) => {
          const raw = (p.data as Record<string, string>)[`c${i}`];
          return typeof raw === "string" && /^-?\d/.test(raw.replace(/[, ]/g, ""))
            ? "cell cell--data cell--data-num"
            : "cell cell--data";
        },
        resizable: true,
        sortable: true,
        minWidth: 100,
      });
    }

    const rowData: Record<string, string>[] = [];
    for (let r = 0; r < parsed.bodyRows.length; r++) {
      const row: Record<string, string> = {};
      for (let i = 0; i < rowHeaderCols; i++) {
        row[`r${i}`] = parsed.bodyRows[r][i]?.value ?? "";
      }
      for (let i = 0; i < dataCols; i++) {
        row[`c${i}`] = parsed.dataRows[r]?.[i]?.value ?? "";
      }
      rowData.push(row);
    }

    return {
      columnDefs,
      rowData,
      rowBuffer: 20,
      suppressContextMenu: true,
      defaultColDef: { filter: true, floatingFilter: false },
      theme: "legacy",
    };
  }

  onMount(() => {
    if (host) api = createGrid(host, buildOptions(result));
  });

  $effect(() => {
    if (api) {
      const next = buildOptions(result);
      api.setGridOption("columnDefs", next.columnDefs!);
      api.setGridOption("rowData", next.rowData!);
    }
  });

  onDestroy(() => {
    api?.destroy();
    api = null;
  });
</script>

{#if result.error}
  <p class="callout callout--danger">{result.error}</p>
{:else if (result.cellset?.length ?? 0) === 0}
  <p class="empty">No rows returned.</p>
{:else}
  <div class="grid-host" class:grid-host--dark={dark} bind:this={host}></div>
  {#if result.runtime != null}
    <p class="runtime">Runtime: {result.runtime} ms · {result.height ?? 0} rows × {result.width ?? 0} cols</p>
  {/if}
{/if}

<style>
  .grid-host {
    width: 100%;
    height: 60vh;
    min-height: 320px;
    border: 1px solid var(--border);
    border-radius: var(--radius-sm);
    background: var(--bg);
  }
  .empty { color: var(--fg-muted); padding: var(--space-4); }
  .runtime { color: var(--fg-subtle); font-size: var(--fs-xs); margin: var(--space-2) 0 0; }
  :global(.ag-theme-legacy),
  :global(.ag-root-wrapper) { background: var(--bg); color: var(--fg); font-family: var(--font-sans); }
  :global(.cell) { padding: 2px var(--space-2); border-right: 1px solid var(--border); border-bottom: 1px solid var(--border); font-size: var(--fs-sm); }
  :global(.cell--rowhead) { background: var(--bg-muted); font-weight: 500; }
  :global(.cell--data) { text-align: left; }
  :global(.cell--data-num) { text-align: right; font-variant-numeric: tabular-nums; }
  :global(.grid-host--dark .ag-header) { background: var(--bg-muted); color: var(--fg); border-bottom: 1px solid var(--border); }
  :global(.grid-host--dark .ag-row) { background: var(--bg); color: var(--fg); border-bottom: 1px solid var(--border); }
  :global(.grid-host--dark .ag-row-hover) { background: var(--bg-subtle); }
  :global(.grid-host--dark .ag-pinned-left-cols-container) { background: var(--bg-muted); }
</style>
