<script lang="ts">
  /*
   * Single big-number KPI tile. The most common embed shape — an OEM host
   * page wants one governed figure ("Net revenue", "Total Rx") rendered
   * large, not a whole table. Derives its value from the same records
   * response every other renderer consumes, so `render="kpi"` needs no
   * server change.
   *
   * Selection rule (data-driven, no config):
   *   - the LAST numeric column is the headline measure (measures sit to the
   *     right of the category columns in Saiku's records output);
   *   - its value comes from the single row when the query returns one row,
   *     else the SUM across rows (a measure with no breakdown → a total);
   *   - the label is the measure's column caption;
   *   - when the query carries exactly one prior numeric column we treat it
   *     as a comparison base and render a delta chip (e.g. measure vs prior).
   *
   * `formatted` from Mondrian drives the single-row display so the host sees
   * the same string the workbench would; summed/multi-row values fall back to
   * a compact locale format since there's no server-formatted total.
   */
  import type { EmbedRow } from "./types";

  interface Props {
    rows: EmbedRow[];
    /** Optional label override; defaults to the measure's column caption. */
    label?: string;
  }

  let { rows, label }: Props = $props();

  function numericColumns(rs: EmbedRow[]): string[] {
    if (rs.length === 0) return [];
    const cols = Object.keys(rs[0]);
    return cols.filter((c) =>
      rs.every((r) => {
        const v = r[c]?.value;
        return v === null || v === undefined || (typeof v === "number" && !Number.isNaN(v));
      }),
    ).filter((c) => rs.some((r) => typeof r[c]?.value === "number"));
  }

  function sumColumn(rs: EmbedRow[], col: string): number {
    return rs.reduce((a, r) => a + (typeof r[col]?.value === "number" ? (r[col].value as number) : 0), 0);
  }

  const numCols = $derived(numericColumns(rows));
  const measureCol = $derived(numCols.length > 0 ? numCols[numCols.length - 1] : null);
  const baseCol = $derived(numCols.length > 1 ? numCols[numCols.length - 2] : null);

  const compact = new Intl.NumberFormat(undefined, { notation: "compact", maximumFractionDigits: 1 });

  const kpi = $derived.by(() => {
    if (!measureCol || rows.length === 0) return null;
    const singleRow = rows.length === 1;
    const value = singleRow ? (rows[0][measureCol]?.value ?? 0) : sumColumn(rows, measureCol);
    const display = singleRow ? (rows[0][measureCol]?.formatted ?? compact.format(value)) : compact.format(value);
    const unit = rows[0][measureCol]?.unit;
    let deltaPct: number | null = null;
    if (baseCol) {
      const base = singleRow ? (rows[0][baseCol]?.value ?? 0) : sumColumn(rows, baseCol);
      if (base !== 0) deltaPct = ((value - base) / Math.abs(base)) * 100;
    }
    return { label: label ?? measureCol, display, unit, deltaPct };
  });
</script>

{#if !kpi}
  <div class="empty">No measure to display</div>
{:else}
  <div class="kpi" role="figure" aria-label={kpi.label}>
    <div class="cap">{kpi.label}{#if kpi.unit}<span class="unit"> · {kpi.unit}</span>{/if}</div>
    <div class="num">{kpi.display}</div>
    {#if kpi.deltaPct !== null}
      <div class="delta" class:down={kpi.deltaPct < 0}>
        {kpi.deltaPct >= 0 ? "▲" : "▼"}
        {Math.abs(kpi.deltaPct).toFixed(1)}%<span class="vs"> vs prior</span>
      </div>
    {/if}
  </div>
{/if}

<style>
  .kpi {
    font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
    padding: 16px 18px;
    color: var(--saiku-embed-fg, #1f2937);
  }
  .cap {
    font-size: 11px;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--saiku-embed-muted, #6b7280);
  }
  .unit {
    text-transform: none;
    letter-spacing: 0;
  }
  .num {
    margin-top: 8px;
    font-size: 34px;
    font-weight: 650;
    letter-spacing: -0.5px;
    font-variant-numeric: tabular-nums;
    line-height: 1.05;
  }
  .delta {
    margin-top: 8px;
    font-size: 13px;
    font-weight: 600;
    color: var(--saiku-embed-positive, #12a67a);
  }
  .delta.down {
    color: var(--saiku-embed-negative, #b91c1c);
  }
  .delta .vs {
    color: var(--saiku-embed-muted, #6b7280);
    font-weight: 500;
  }
  .empty {
    padding: 12px;
    color: var(--saiku-embed-muted, #6b7280);
    font-family: system-ui, sans-serif;
    font-size: 13px;
  }
</style>
