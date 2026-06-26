<script lang="ts">
  import type { QueryResult } from "$lib/api/query";
  import { parseCellset, toNumber } from "$lib/views/cellsetUtils";
  import { i18n } from "$lib/stores/i18n.svelte";

  interface Props { result: QueryResult }
  let { result }: Props = $props();

  function stats(vals: number[]) {
    if (vals.length === 0) return { min: 0, max: 0, sum: 0, avg: 0, stdDev: 0, count: 0 };
    let min = Infinity, max = -Infinity, sum = 0;
    for (const v of vals) { if (v < min) min = v; if (v > max) max = v; sum += v; }
    const avg = sum / vals.length;
    let sse = 0;
    for (const v of vals) { const d = v - avg; sse += d * d; }
    const stdDev = Math.sqrt(sse / vals.length);
    return { min, max, sum, avg, stdDev, count: vals.length };
  }

  const table = $derived.by(() => {
    const p = parseCellset(result);
    const rows: { column: string; count: number; min: string; max: string; sum: string; avg: string; stdDev: string }[] = [];
    const colCount = p.dataRows[0]?.length ?? 0;
    for (let c = 0; c < colCount; c++) {
      const name = p.columnCategories[c] ?? `Col ${c + 1}`;
      const vals: number[] = [];
      for (const row of p.dataRows) {
        const n = toNumber(row[c]);
        if (n != null) vals.push(n);
      }
      const s = stats(vals);
      const fmt = (n: number) => Number.isFinite(n) ? n.toLocaleString(undefined, { maximumFractionDigits: 2 }) : "—";
      rows.push({
        column: name,
        count: s.count,
        min: fmt(s.min), max: fmt(s.max),
        sum: fmt(s.sum), avg: fmt(s.avg),
        stdDev: fmt(s.stdDev),
      });
    }
    return rows;
  });
</script>

{#if result.error}
  <p class="callout callout--danger">{result.error}</p>
{:else if !result.cellset || result.cellset.length === 0}
  <p class="empty">{i18n.t("cellset.noRows")}</p>
{:else}
  <div class="stats-wrap">
    <table class="stats">
      <thead>
        <tr>
          <th>{i18n.t("stats.col.column")}</th>
          <th>{i18n.t("stats.count")}</th>
          <th>{i18n.t("stats.min")}</th>
          <th>{i18n.t("stats.max")}</th>
          <th>{i18n.t("stats.sum")}</th>
          <th>{i18n.t("stats.average")}</th>
          <th>{i18n.t("stats.stdDev")}</th>
        </tr>
      </thead>
      <tbody>
        {#each table as r}
          <tr>
            <th>{r.column}</th>
            <td>{r.count.toLocaleString()}</td>
            <td class="n">{r.min}</td>
            <td class="n">{r.max}</td>
            <td class="n">{r.sum}</td>
            <td class="n">{r.avg}</td>
            <td class="n">{r.stdDev}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
{/if}

<style>
  .stats-wrap { flex: 1; min-height: 0; overflow: auto; border: 1px solid var(--border); background: var(--bg); border-radius: 4px; }
  .stats { border-collapse: separate; border-spacing: 0; width: 100%; font-size: var(--fs-sm); }
  .stats th, .stats td { padding: 6px 12px; border-bottom: 1px solid var(--border); text-align: left; white-space: nowrap; }
  .stats thead th { position: sticky; top: 0; background: var(--bg-muted); color: var(--fg); font-weight: var(--weight-semibold); }
  .stats tbody th { background: var(--bg-muted); font-weight: var(--weight-medium); color: var(--fg); }
  .stats td.n { text-align: right; font-variant-numeric: tabular-nums; }
  .empty { color: var(--fg-muted); padding: var(--space-4); }
</style>
