<script lang="ts">
  import type { QueryResult } from "$lib/api/query";
  import { parseCellset, toNumber } from "$lib/views/cellsetUtils";

  interface Props {
    result: QueryResult;
    /** "line" = sparkline, "bar" = sparkbar */
    mode?: "line" | "bar";
  }
  let { result, mode = "line" }: Props = $props();

  interface Row {
    label: string;
    values: (number | null)[];
    min: number;
    max: number;
  }

  const rows: Row[] = $derived.by(() => {
    const p = parseCellset(result);
    const out: Row[] = [];
    for (let r = 0; r < p.dataRows.length; r++) {
      const label = p.rowCategories[r] ?? `Row ${r + 1}`;
      const values = p.dataRows[r].map(toNumber);
      const nums = values.filter((v): v is number => v != null && Number.isFinite(v));
      const min = nums.length ? Math.min(...nums) : 0;
      const max = nums.length ? Math.max(...nums) : 1;
      out.push({ label, values, min, max });
    }
    return out;
  });

  function linePath(values: (number | null)[], min: number, max: number, w: number, h: number): string {
    if (values.length === 0) return "";
    const span = max - min || 1;
    const step = values.length > 1 ? w / (values.length - 1) : 0;
    const pts: string[] = [];
    for (let i = 0; i < values.length; i++) {
      const v = values[i];
      if (v == null) continue;
      const x = i * step;
      const y = h - ((v - min) / span) * h;
      pts.push(`${pts.length === 0 ? "M" : "L"}${x.toFixed(1)} ${y.toFixed(1)}`);
    }
    return pts.join(" ");
  }

  function barBands(values: (number | null)[], min: number, max: number, w: number, h: number) {
    const span = max - min || 1;
    const step = w / Math.max(values.length, 1);
    const bars: { x: number; y: number; w: number; h: number }[] = [];
    for (let i = 0; i < values.length; i++) {
      const v = values[i];
      if (v == null) continue;
      const bh = ((v - min) / span) * h;
      bars.push({ x: i * step + 1, y: h - bh, w: Math.max(1, step - 2), h: bh });
    }
    return bars;
  }

  const W = 160;
  const H = 24;
</script>

{#if result.error}
  <p class="callout callout--danger">{result.error}</p>
{:else if rows.length === 0}
  <p class="empty">No rows returned.</p>
{:else}
  <div class="spark-wrap">
    <table class="spark">
      <thead>
        <tr><th>Row</th><th>{mode === "bar" ? "Sparkbar" : "Sparkline"}</th><th>First</th><th>Last</th><th>Min</th><th>Max</th></tr>
      </thead>
      <tbody>
        {#each rows as r}
          <tr>
            <th>{r.label}</th>
            <td class="chart-cell">
              <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} aria-hidden="true">
                {#if mode === "line"}
                  <path d={linePath(r.values, r.min, r.max, W, H)} stroke="var(--accent)" stroke-width="1.5" fill="none" />
                {:else}
                  {#each barBands(r.values, r.min, r.max, W, H) as b}
                    <rect x={b.x} y={b.y} width={b.w} height={b.h} fill="var(--accent)" />
                  {/each}
                {/if}
              </svg>
            </td>
            <td class="n">{(r.values.find((v) => v != null) ?? 0).toLocaleString(undefined, { maximumFractionDigits: 2 })}</td>
            <td class="n">{([...r.values].reverse().find((v) => v != null) ?? 0).toLocaleString(undefined, { maximumFractionDigits: 2 })}</td>
            <td class="n">{r.min.toLocaleString(undefined, { maximumFractionDigits: 2 })}</td>
            <td class="n">{r.max.toLocaleString(undefined, { maximumFractionDigits: 2 })}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
{/if}

<style>
  .spark-wrap { flex: 1; min-height: 0; overflow: auto; border: 1px solid var(--border); background: var(--bg); border-radius: 4px; }
  .spark { border-collapse: separate; border-spacing: 0; width: 100%; font-size: var(--fs-sm); }
  .spark th, .spark td { padding: 6px 12px; border-bottom: 1px solid var(--border); text-align: left; white-space: nowrap; }
  .spark thead th { position: sticky; top: 0; background: var(--bg-muted); color: var(--fg); font-weight: 600; }
  .spark tbody th { background: var(--bg-muted); font-weight: 500; color: var(--fg); }
  .spark td.n { text-align: right; font-variant-numeric: tabular-nums; }
  .chart-cell { padding: 2px 8px; }
  .empty { color: var(--fg-muted); padding: var(--space-4); }
</style>
