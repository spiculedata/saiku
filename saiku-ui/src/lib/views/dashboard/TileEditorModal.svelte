<script lang="ts">
  /*
   * Per-tile edit modal. Opens when the user clicks ⚙ on a tile in the
   * grid. Shows fields appropriate to the tile's type:
   *
   *   all      → title
   *   chart    → cube picker + chartType + inline AiQueryRequest body
   *              (JSON textarea — QueryCanvas embed is a later slice)
   *   table    → cube picker + inline AiQueryRequest body
   *   filter   → cube picker + dim/hier/level + widget kind
   *   text     → markdown content
   *
   * Save writes through dashboardStore.updateTile(id, patch), which
   * marks the dashboard dirty so the toolbar's Save button activates.
   * Cancel discards in-modal edits.
   */

  import { onMount, untrack } from "svelte";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import {
    listAiCubes,
    type AiCubeSummary,
    type CubeRef,
    type DashboardFilter,
    type DashboardTile,
    type FilterWidget,
    type TileQuery,
  } from "$lib/api/dashboards";

  interface Props {
    tile: DashboardTile;
    onClose: () => void;
  }

  let { tile, onClose }: Props = $props();

  // Per-tile editable form state — initialised from the source tile.
  // untrack() so the inits read the prop without subscribing; the modal
  // never re-renders for the same tile, so the one-shot read is the
  // intended behaviour (Svelte 5 warns by default).
  let title = $state(untrack(() => tile.title ?? ""));
  let cube = $state<CubeRef | null>(untrack(() => tile.cube ?? null));
  let chartType = $state(untrack(() => tile.chartType ?? "bar"));
  let text = $state(untrack(() => tile.text ?? ""));
  let widget = $state<FilterWidget>(untrack(() => tile.widget ?? "single-select"));
  let filterTarget = $state<{ dimension: string; hierarchy: string; level: string }>(
    untrack(() => ({
      dimension: tile.target?.dimension ?? "",
      hierarchy: tile.target?.hierarchy ?? "",
      level: tile.target?.level ?? "",
    })),
  );
  let inlineBodyJson = $state<string>(
    untrack(() => (tile.query?.kind === "inline" ? JSON.stringify(tile.query.body, null, 2) : "")),
  );
  let bodyError = $state<string | null>(null);

  // Cube catalogue, fetched once on open.
  let cubes = $state<AiCubeSummary[]>([]);
  let cubesError = $state<string | null>(null);
  let cubesLoading = $state(false);

  onMount(async () => {
    if (tile.type === "text") return; // text tiles don't need the catalogue
    cubesLoading = true;
    try {
      cubes = await listAiCubes();
    } catch (e: unknown) {
      cubesError = e instanceof Error ? e.message : String(e);
    } finally {
      cubesLoading = false;
    }
  });

  // Filter tiles need the cube's schema to populate dim/hier/level
  // dropdowns. Prime when a cube is selected.
  $effect(() => {
    if (tile.type !== "filter" || !cube) return;
    void schemaCache.get(cube).catch(() => {
      // Surface failure inline below
    });
  });

  let schema = $derived(() => {
    if (!cube) return null;
    void schemaCache.version;
    return schemaCache.peek(cube);
  });

  // Build dim/hier/level option lists from the schema. Loose record
  // walks because AiSchemaLike is intentionally untyped at the store
  // level — narrowing happens here at the read site.
  let dimensionOptions = $derived(() => {
    const s = schema() as { dimensions?: Record<string, { name: string }> } | null;
    if (!s?.dimensions) return [] as string[];
    return Object.values(s.dimensions).map((d) => d.name);
  });

  let hierarchyOptions = $derived(() => {
    const s = schema() as
      | { dimensions?: Record<string, { name: string; hierarchies?: Record<string, { name: string }> }> }
      | null;
    if (!s?.dimensions) return [] as string[];
    for (const d of Object.values(s.dimensions)) {
      if (d.name === filterTarget.dimension && d.hierarchies) {
        return Object.values(d.hierarchies).map((h) => h.name);
      }
    }
    return [];
  });

  let levelOptions = $derived(() => {
    const s = schema() as
      | {
          dimensions?: Record<
            string,
            {
              name: string;
              hierarchies?: Record<string, { name: string; levels?: Record<string, { name: string }> }>;
            }
          >;
        }
      | null;
    if (!s?.dimensions) return [] as string[];
    for (const d of Object.values(s.dimensions)) {
      if (d.name !== filterTarget.dimension) continue;
      if (!d.hierarchies) return [];
      for (const h of Object.values(d.hierarchies)) {
        if (h.name === filterTarget.hierarchy && h.levels) {
          return Object.values(h.levels).map((l) => l.name);
        }
      }
    }
    return [];
  });

  function cubeKey(c: CubeRef): string {
    return `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;
  }

  function handleCubeChange(e: Event): void {
    const v = (e.target as HTMLSelectElement).value;
    cube = cubes.find((c) => cubeKey(c) === v) ?? null;
    // Clear stale filter target when the cube changes — the level
    // names won't apply to the new cube's schema.
    if (tile.type === "filter") {
      filterTarget = { dimension: "", hierarchy: "", level: "" };
    }
  }

  function handleSave(): void {
    bodyError = null;
    const patch: Partial<DashboardTile> = { title: title || undefined };

    if (tile.type === "text") {
      patch.text = text;
    } else if (cube) {
      patch.cube = cube;
    }

    if (tile.type === "chart") {
      patch.chartType = chartType;
    }

    if (tile.type === "chart" || tile.type === "table") {
      if (inlineBodyJson.trim()) {
        try {
          const parsed = JSON.parse(inlineBodyJson);
          const q: TileQuery = { kind: "inline", body: parsed };
          patch.query = q;
        } catch (e) {
          bodyError = `JSON parse error: ${(e as Error).message}`;
          return;
        }
      }
    }

    if (tile.type === "filter") {
      patch.widget = widget;
      if (filterTarget.dimension && filterTarget.hierarchy && filterTarget.level) {
        const target: DashboardFilter = {
          dimension: filterTarget.dimension,
          hierarchy: filterTarget.hierarchy,
          level: filterTarget.level,
          members: [],
        };
        patch.target = target;
      }
    }

    dashboardStore.updateTile(tile.id, patch);
    onClose();
  }
</script>

<div
  class="modal-backdrop"
  role="presentation"
  onclick={(e) => {
    if (e.target === e.currentTarget) onClose();
  }}
  onkeydown={(e) => {
    if (e.key === "Escape") onClose();
  }}
>
  <div class="modal" role="dialog" aria-label="Edit tile">
    <header class="modal-header">
      <h2>Edit {tile.type} tile</h2>
      <button type="button" class="close" aria-label="Close" onclick={onClose}>×</button>
    </header>
    <div class="modal-body">
      <label class="field">
        <span>Title</span>
        <input type="text" bind:value={title} placeholder={`Untitled ${tile.type}`} />
      </label>

      {#if tile.type === "text"}
        <label class="field">
          <span>Markdown / HTML (sanitised on render)</span>
          <textarea bind:value={text} rows="10"></textarea>
        </label>
      {/if}

      {#if tile.type !== "text"}
        <label class="field">
          <span>Cube</span>
          {#if cubesLoading}
            <span class="hint">Loading…</span>
          {:else if cubesError}
            <span class="hint error">{cubesError}</span>
          {:else}
            <select onchange={handleCubeChange} disabled={cubes.length === 0}>
              <option value="">— pick a cube —</option>
              {#each cubes as c (cubeKey(c))}
                <option value={cubeKey(c)} selected={cube ? cubeKey(c) === cubeKey(cube) : false}>
                  {c.cubeCaption ?? c.cubeName} ({c.connectionName})
                </option>
              {/each}
            </select>
          {/if}
        </label>
      {/if}

      {#if tile.type === "chart"}
        <label class="field">
          <span>Chart type</span>
          <select bind:value={chartType}>
            {#each ["bar", "stackedBar", "line", "stackedLine", "area", "stackedArea", "pie", "donut"] as ct (ct)}
              <option value={ct}>{ct}</option>
            {/each}
          </select>
        </label>
      {/if}

      {#if tile.type === "chart" || tile.type === "table"}
        <label class="field">
          <span>Inline query body (AiQueryRequest JSON)</span>
          <textarea
            bind:value={inlineBodyJson}
            rows="10"
            spellcheck="false"
            class="json"
            placeholder={JSON.stringify(
              { cube: cube ?? null, measures: [{ name: "..." }], rows: [] },
              null,
              2,
            )}
          ></textarea>
          {#if bodyError}
            <span class="hint error">{bodyError}</span>
          {:else}
            <span class="hint">
              Paste an AiQueryRequest. A no-code editor lands in a later slice.
            </span>
          {/if}
        </label>
      {/if}

      {#if tile.type === "filter"}
        <label class="field">
          <span>Widget</span>
          <select bind:value={widget}>
            <option value="single-select">single-select</option>
            <option value="multi-select">multi-select</option>
            <option value="date-range">date-range</option>
          </select>
        </label>
        <label class="field">
          <span>Dimension</span>
          <select bind:value={filterTarget.dimension} disabled={!cube}>
            <option value="">— pick —</option>
            {#each dimensionOptions() as d (d)}
              <option value={d}>{d}</option>
            {/each}
          </select>
        </label>
        <label class="field">
          <span>Hierarchy</span>
          <select bind:value={filterTarget.hierarchy} disabled={!filterTarget.dimension}>
            <option value="">— pick —</option>
            {#each hierarchyOptions() as h (h)}
              <option value={h}>{h}</option>
            {/each}
          </select>
        </label>
        <label class="field">
          <span>Level</span>
          <select bind:value={filterTarget.level} disabled={!filterTarget.hierarchy}>
            <option value="">— pick —</option>
            {#each levelOptions() as l (l)}
              <option value={l}>{l}</option>
            {/each}
          </select>
        </label>
      {/if}
    </div>
    <footer class="modal-footer">
      <button type="button" class="btn" onclick={onClose}>Cancel</button>
      <button type="button" class="btn primary" onclick={handleSave}>Save</button>
    </footer>
  </div>
</div>

<style>
  .modal-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.45);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 50;
  }
  .modal {
    background: var(--bg-modal, #fff);
    border-radius: 8px;
    box-shadow: 0 16px 48px rgba(0, 0, 0, 0.2);
    width: min(560px, 92vw);
    max-height: 90vh;
    display: flex;
    flex-direction: column;
  }
  .modal-header {
    display: flex;
    align-items: center;
    padding: 0.75rem 1rem;
    border-bottom: 1px solid var(--border, #e5e7eb);
  }
  .modal-header h2 {
    margin: 0;
    font-size: 1rem;
    flex: 1;
    text-transform: capitalize;
  }
  .close {
    border: none;
    background: transparent;
    font-size: 1.25rem;
    cursor: pointer;
    color: var(--fg-muted);
  }
  .modal-body {
    padding: 1rem;
    overflow: auto;
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }
  .field {
    display: flex;
    flex-direction: column;
    gap: 0.25rem;
  }
  .field span:first-child {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  input, select, textarea {
    padding: 0.375rem 0.5rem;
    border: 1px solid var(--border, #d1d5db);
    border-radius: 4px;
    font-size: 0.875rem;
    font-family: inherit;
  }
  textarea.json {
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, monospace;
    font-size: 0.8125rem;
    white-space: pre;
  }
  .hint {
    font-size: 0.75rem;
    color: var(--fg-muted);
  }
  .hint.error { color: #991b1b; }
  .modal-footer {
    display: flex;
    justify-content: flex-end;
    gap: 0.5rem;
    padding: 0.75rem 1rem;
    border-top: 1px solid var(--border, #e5e7eb);
  }
  .btn {
    padding: 0.375rem 0.75rem;
    border: 1px solid var(--border, #d1d5db);
    background: var(--bg-button, #fff);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.875rem;
  }
  .btn.primary {
    background: var(--accent, #2563eb);
    color: white;
    border-color: var(--accent, #2563eb);
  }
</style>
