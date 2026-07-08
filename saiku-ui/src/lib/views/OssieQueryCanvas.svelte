<script lang="ts">
  import { ossieQuery } from "$lib/stores/ossieQuery.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { Button } from "$lib/components/ui";
  import { toasts } from "$lib/stores/toasts.svelte";
  import { FolderOpen, Play, Save, X } from "lucide-svelte";
  import type { OssieFieldRef, OssieFilterExpr, OssieMetricRef } from "$lib/api/ossie";

  const FIELD_MIME = "application/x-saiku-ossie-field";
  const METRIC_MIME = "application/x-saiku-ossie-metric";

  /**
   * Which shelf a drop should land on. Filters accept fields; Values accept metrics;
   * Rows / Columns accept fields. Passed to the drop handler so we don't need four near-
   * identical event handlers.
   */
  type Shelf = "rows" | "columns" | "values" | "filters";

  function acceptsField(shelf: Shelf): boolean {
    return shelf === "rows" || shelf === "columns" || shelf === "filters";
  }

  function acceptsMetric(shelf: Shelf): boolean {
    return shelf === "values";
  }

  function onDragOver(e: DragEvent, shelf: Shelf) {
    if (!e.dataTransfer) return;
    const types = e.dataTransfer.types;
    const fieldOk = acceptsField(shelf) && types.includes(FIELD_MIME);
    const metricOk = acceptsMetric(shelf) && types.includes(METRIC_MIME);
    if (fieldOk || metricOk) {
      e.preventDefault();
      e.dataTransfer.dropEffect = "copy";
    }
  }

  function onDrop(e: DragEvent, shelf: Shelf) {
    if (!e.dataTransfer) return;
    e.preventDefault();
    if (acceptsField(shelf)) {
      const raw = e.dataTransfer.getData(FIELD_MIME);
      if (raw) {
        const ref = JSON.parse(raw) as OssieFieldRef;
        if (shelf === "rows") ossieQuery.addRow(ref);
        else if (shelf === "columns") ossieQuery.addColumn(ref);
        else if (shelf === "filters") {
          // Filters need an operator + value; drop-and-fill mini-form comes next iteration.
          // MVP: drop creates an EQ-with-empty-value filter the user then edits inline.
          const expr: OssieFilterExpr = { dataset: ref.dataset, field: ref.field, op: "EQ", value: "" };
          ossieQuery.addFilter(expr);
        }
        return;
      }
    }
    if (acceptsMetric(shelf)) {
      const raw = e.dataTransfer.getData(METRIC_MIME);
      if (raw) {
        const ref = JSON.parse(raw) as OssieMetricRef;
        ossieQuery.addValue(ref);
      }
    }
  }

  async function runQuery() {
    await ossieQuery.run();
  }

  /**
   * Prompt for a repository path + name and persist the current shelf state as a `.saiku`
   * file. Uses browser prompt() rather than the fancier SaveQueryModal because the Ossie
   * shell is intentionally minimal (F4 MVP). Full RepositoryBrowser integration is a
   * follow-up when the MDX ↔ Ossie save modal gets unified.
   */
  async function saveQuery() {
    if (!ossieQuery.current) return;
    const defaultName = ossieQuery.savedName ?? "untitled-ossie-query";
    const name = window.prompt("Save as (name)", defaultName);
    if (!name) return;
    const defaultPath = ossieQuery.savedPath ?? `/homes/home:${session.current?.username ?? "admin"}/${name}.saiku`;
    const path = window.prompt("Save to path", defaultPath);
    if (!path) return;
    try {
      await ossieQuery.save(path, name);
      toasts.success("Saved", path);
    } catch (e) {
      toasts.danger("Save failed", e instanceof Error ? e.message : String(e));
    }
  }

  /**
   * Prompt for a saved-query path, load it, and re-select the connection so the sidebar
   * reflects the loaded model. Falls back with a friendly toast when the file is
   * MDX-shaped — the user picked from a mixed folder, we can't hydrate it here.
   */
  async function loadQuery() {
    const path = window.prompt("Load from path");
    if (!path) return;
    try {
      const loaded = await ossieQuery.load(path);
      if (!loaded) {
        toasts.danger("Not an Ossie query", "This file is a Mondrian/MDX query. Open it from the MDX workbench.");
        return;
      }
      const conn = loaded.ossieQueryModel.connection;
      const model = loaded.ossieQueryModel.model;
      // Flip the selection so the sidebar reloads the semantic model tree matching what
      // the saved shelf state references. loadModel is memoised on connection name so a
      // reload of the current connection is a no-op.
      selection.selectOssie({ connectionName: conn, modelName: model });
      const user = session.current?.username;
      if (user) await ossieQuery.loadModel(user, conn, model);
      toasts.success("Loaded", loaded.name);
    } catch (e) {
      toasts.danger("Load failed", e instanceof Error ? e.message : String(e));
    }
  }

  function updateFilterOp(idx: number, op: OssieFilterExpr["op"]) {
    if (!ossieQuery.current) return;
    const filters = ossieQuery.current.filters.map((f, i) => (i === idx ? { ...f, op } : f));
    ossieQuery.current = { ...ossieQuery.current, filters };
  }

  function updateFilterValue(idx: number, value: string) {
    if (!ossieQuery.current) return;
    const filters = ossieQuery.current.filters.map((f, i) => (i === idx ? { ...f, value } : f));
    ossieQuery.current = { ...ossieQuery.current, filters };
  }

  const runnable = $derived(ossieQuery.hasRunnableShape());
</script>

<div class="ossie-canvas">
  <header class="ossie-canvas__toolbar">
    <Button
      onclick={runQuery}
      disabled={!runnable || ossieQuery.running}
    >
      <Play size={14} />
      {ossieQuery.running ? "Running…" : "Run"}
    </Button>
    <Button variant="outline" onclick={saveQuery} disabled={!ossieQuery.current}>
      <Save size={14} />
      Save
    </Button>
    <Button variant="outline" onclick={loadQuery}>
      <FolderOpen size={14} />
      Load
    </Button>
    {#if ossieQuery.savedName}
      <span class="ossie-canvas__saved-name">{ossieQuery.savedName}</span>
    {/if}
    {#if ossieQuery.error}
      <span class="ossie-canvas__error">{ossieQuery.error}</span>
    {/if}
    {#if ossieQuery.result?.runtime !== undefined}
      <span class="ossie-canvas__runtime">{ossieQuery.result.runtime}ms</span>
    {/if}
  </header>

  <div class="ossie-canvas__shelves">
    <div
      class="ossie-shelf"
      ondragover={(e) => onDragOver(e, "rows")}
      ondrop={(e) => onDrop(e, "rows")}
      role="group"
      aria-label="Rows shelf"
    >
      <span class="ossie-shelf__label">Rows</span>
      {#each ossieQuery.current?.rows ?? [] as f, i}
        <span class="ossie-chip">
          {f.dataset}.{f.field}
          <button
            type="button"
            class="ossie-chip__x"
            aria-label="Remove {f.field}"
            onclick={() => ossieQuery.removeRow(i)}
          ><X size={12} /></button>
        </span>
      {/each}
    </div>

    <div
      class="ossie-shelf"
      ondragover={(e) => onDragOver(e, "columns")}
      ondrop={(e) => onDrop(e, "columns")}
      role="group"
      aria-label="Columns shelf"
    >
      <span class="ossie-shelf__label">Columns</span>
      {#each ossieQuery.current?.columns ?? [] as f, i}
        <span class="ossie-chip">
          {f.dataset}.{f.field}
          <button
            type="button"
            class="ossie-chip__x"
            aria-label="Remove {f.field}"
            onclick={() => ossieQuery.removeColumn(i)}
          ><X size={12} /></button>
        </span>
      {/each}
    </div>

    <div
      class="ossie-shelf"
      ondragover={(e) => onDragOver(e, "values")}
      ondrop={(e) => onDrop(e, "values")}
      role="group"
      aria-label="Values shelf"
    >
      <span class="ossie-shelf__label">Values</span>
      {#each ossieQuery.current?.values ?? [] as v, i}
        <span class="ossie-chip ossie-chip--metric">
          Σ {v.metric}
          <button
            type="button"
            class="ossie-chip__x"
            aria-label="Remove {v.metric}"
            onclick={() => ossieQuery.removeValue(i)}
          ><X size={12} /></button>
        </span>
      {/each}
    </div>

    <div
      class="ossie-shelf"
      ondragover={(e) => onDragOver(e, "filters")}
      ondrop={(e) => onDrop(e, "filters")}
      role="group"
      aria-label="Filters shelf"
    >
      <span class="ossie-shelf__label">Filters</span>
      {#each ossieQuery.current?.filters ?? [] as f, i}
        <span class="ossie-chip ossie-chip--filter">
          <span class="ossie-chip__col">{f.dataset ?? ""}.{f.field}</span>
          <select
            class="ossie-chip__op"
            value={f.op}
            onchange={(e) => updateFilterOp(i, (e.currentTarget as HTMLSelectElement).value as OssieFilterExpr["op"])}
          >
            <option value="EQ">=</option>
            <option value="NEQ">≠</option>
            <option value="LT">&lt;</option>
            <option value="LTE">≤</option>
            <option value="GT">&gt;</option>
            <option value="GTE">≥</option>
            <option value="IS_NULL">is null</option>
            <option value="IS_NOT_NULL">is not null</option>
          </select>
          {#if f.op !== "IS_NULL" && f.op !== "IS_NOT_NULL"}
            <input
              class="ossie-chip__value"
              value={f.value ?? ""}
              oninput={(e) => updateFilterValue(i, (e.currentTarget as HTMLInputElement).value)}
              placeholder="value"
            />
          {/if}
          <button
            type="button"
            class="ossie-chip__x"
            aria-label="Remove filter"
            onclick={() => ossieQuery.removeFilter(i)}
          ><X size={12} /></button>
        </span>
      {/each}
    </div>
  </div>

  <div class="ossie-canvas__result">
    {#if ossieQuery.result}
      <table class="ossie-result">
        <thead>
          <tr>
            {#each ossieQuery.result.cellSetHeaders?.[0] ?? [] as h}
              <th>{h.formattedValue ?? h.rawValue ?? ""}</th>
            {/each}
          </tr>
        </thead>
        <tbody>
          {#each ossieQuery.result.cellSetBody ?? [] as row}
            <tr>
              {#each row as c}
                <td class:ossie-result__num={c.rawNumber !== undefined}>
                  {c.formattedValue ?? c.rawValue ?? ""}
                </td>
              {/each}
            </tr>
          {/each}
        </tbody>
      </table>
      {#if (ossieQuery.result.cellSetBody?.length ?? 0) === 0}
        <p class="ossie-canvas__empty">Query returned no rows.</p>
      {/if}
    {:else if runnable}
      <p class="ossie-canvas__hint">Ready to run. Hit Run to execute.</p>
    {:else}
      <p class="ossie-canvas__hint">
        Drag fields onto Rows/Columns, metrics onto Values, and pick a fact dataset.
      </p>
    {/if}
  </div>
</div>

<style>
  .ossie-canvas {
    display: flex;
    flex-direction: column;
    height: 100%;
    padding: var(--space-3);
    gap: var(--space-3);
  }
  .ossie-canvas__toolbar {
    display: flex;
    align-items: center;
    gap: var(--space-3);
  }
  .ossie-canvas__error {
    color: var(--danger-fg, #b91c1c);
    font-size: var(--fs-sm);
  }
  .ossie-canvas__runtime {
    color: var(--fg-subtle);
    font-size: var(--fs-xs);
    margin-left: auto;
  }
  .ossie-canvas__saved-name {
    color: var(--fg-muted);
    font-size: var(--fs-xs);
    font-family: monospace;
  }
  .ossie-canvas__shelves {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }
  .ossie-shelf {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: 6px;
    min-height: 42px;
    padding: 8px 12px;
    background: var(--bg-subtle, var(--bg-hover));
    border: 1px dashed var(--border-strong);
    border-radius: 4px;
  }
  .ossie-shelf__label {
    font-size: var(--fs-xs);
    font-weight: var(--weight-semibold);
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--fg-muted);
    margin-right: 8px;
    min-width: 60px;
  }
  .ossie-chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 8px;
    background: var(--bg);
    border: 1px solid var(--border-strong);
    border-radius: 4px;
    font-size: var(--fs-sm);
  }
  .ossie-chip--metric {
    background: var(--bg-accent-subtle, var(--bg-hover));
  }
  .ossie-chip--filter {
    gap: 6px;
  }
  .ossie-chip__col {
    font-family: monospace;
    font-size: var(--fs-xs);
  }
  .ossie-chip__op {
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 3px;
    padding: 1px 4px;
    font-size: var(--fs-xs);
  }
  .ossie-chip__value {
    background: transparent;
    border: 1px solid var(--border);
    border-radius: 3px;
    padding: 2px 6px;
    font-size: var(--fs-xs);
    width: 100px;
  }
  .ossie-chip__x {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    background: transparent;
    border: none;
    color: var(--fg-subtle);
    cursor: pointer;
    padding: 0;
    margin-left: 2px;
  }
  .ossie-chip__x:hover {
    color: var(--fg);
  }
  .ossie-canvas__result {
    flex: 1;
    min-height: 0;
    overflow: auto;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: var(--space-2);
  }
  .ossie-result {
    width: 100%;
    border-collapse: collapse;
    font-size: var(--fs-sm);
  }
  .ossie-result th,
  .ossie-result td {
    padding: 6px 10px;
    border-bottom: 1px solid var(--border);
    text-align: left;
  }
  .ossie-result th {
    background: var(--bg-hover);
    font-weight: var(--weight-semibold);
    position: sticky;
    top: 0;
  }
  .ossie-result__num {
    text-align: right;
    font-variant-numeric: tabular-nums;
  }
  .ossie-canvas__hint,
  .ossie-canvas__empty {
    color: var(--fg-subtle);
    font-size: var(--fs-sm);
  }
</style>
