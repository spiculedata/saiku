<script lang="ts">
  /*
   * saiku#1770 — table-tile per-column number formatting.
   *
   * The table was the only value-bearing tile with no number formatting at all:
   * KPI has Format + a custom pattern, the ranked list has "Value format", the
   * chart has a whole Number-format block — the table had only conditional
   * formatting. So it printed whatever the cube's formatString produced, e.g. an
   * integer count carrying `#.0` rendering as "10759.0" with no thousands
   * separator, sitting next to money at three decimals.
   *
   * Rules are keyed by header caption, exactly like the conditional-formatting
   * rules next door, and use the same pattern vocabulary as the KPI and
   * ranked-list tiles so authors learn one syntax. Config capture only — the
   * formatting itself is applied in TableTile.renderCell via formatKpi().
   */

  interface Props {
    /** Bound map of column header caption → pattern. */
    columnFormats: Record<string, string>;
  }
  let { columnFormats = $bindable() }: Props = $props();

  // Edited as an ordered list so a row with a not-yet-typed column name doesn't
  // collapse into a single "" key while the author is still typing.
  type Row = { column: string; pattern: string };
  let rows = $state<Row[]>(
    Object.entries(columnFormats ?? {}).map(([column, pattern]) => ({ column, pattern })),
  );

  function sync(): void {
    const next: Record<string, string> = {};
    for (const r of rows) {
      const col = r.column.trim();
      const pat = r.pattern.trim();
      if (col && pat) next[col] = pat;
    }
    columnFormats = next;
  }

  function add(): void {
    rows = [...rows, { column: "", pattern: "" }];
  }

  function remove(i: number): void {
    rows = rows.filter((_, n) => n !== i);
    sync();
  }
</script>

<fieldset class="cf-section">
  <legend>Number format (per column)</legend>
  <span class="hint">
    Display-only — the underlying data is unchanged. Match a column by its header
    caption. Leave a column out to keep the cube's own formatting.
  </span>

  {#if rows.length === 0}
    <span class="hint">No column formats yet.</span>
  {/if}

  {#each rows as row, i (i)}
    <div class="fmt-row">
      <label class="field flex-1">
        <span class="field__label">Column (header caption)</span>
        <input
          class="field__input"
          type="text"
          placeholder="e.g. Units Shipped"
          bind:value={row.column}
          onchange={sync}
        />
      </label>
      <label class="field flex-1">
        <span class="field__label">Pattern</span>
        <input
          class="field__input"
          type="text"
          placeholder="e.g. 0 / $c1 / 1%"
          bind:value={row.pattern}
          onchange={sync}
        />
      </label>
      <button type="button" class="fmt-remove" onclick={() => remove(i)} aria-label="Remove format rule">
        ×
      </button>
    </div>
  {/each}

  <span class="hint">
    <code>0</code> → 1,234 · <code>2</code> → 1,234.00 · <code>$c1</code> → $48.2K ·
    <code>$2</code> → $99.50 · <code>1%</code> → 15.6%
  </span>

  <button type="button" class="cf-add" onclick={add}>+ Add column format</button>
</fieldset>

<style>
  .cf-section {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    border: 1px solid hsl(var(--border));
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .cf-section legend {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  .fmt-row {
    display: flex;
    gap: 0.5rem;
    align-items: flex-end;
  }
  .fmt-remove {
    background: none;
    border: 1px solid hsl(var(--border));
    border-radius: 4px;
    color: hsl(var(--fg-muted));
    cursor: pointer;
    height: 2rem;
    width: 2rem;
    flex: none;
  }
  .cf-add {
    align-self: flex-start;
    background: none;
    border: 1px dashed hsl(var(--border));
    border-radius: 4px;
    color: hsl(var(--fg-muted));
    cursor: pointer;
    padding: 0.25rem 0.5rem;
    font-size: 0.8125rem;
  }
</style>
