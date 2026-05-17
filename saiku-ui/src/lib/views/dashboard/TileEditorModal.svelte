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
  import { flatten, listRepository, type RepositoryNode } from "$lib/api/repository";
  import { repositionTile } from "$lib/dashboard/tilePlacement";

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
  // Position + size — numeric controls per the design's "no drag-resize"
  // call. Users rearrange via these fields; the grid auto-places tiles
  // on add and re-renders to the new (x, y, w, h) on save.
  let tileX = $state<number>(untrack(() => tile.x));
  let tileY = $state<number>(untrack(() => tile.y));
  let tileW = $state<number>(untrack(() => tile.w));
  let tileH = $state<number>(untrack(() => tile.h));
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
  // Query source — "reference" picks a saved .saiku from the repo,
  // "inline" pastes an AiQueryRequest body. Default to "reference" so
  // non-technical authors aren't dropped into a JSON textarea on a
  // fresh tile.
  let queryMode = $state<"reference" | "inline">(
    untrack(() => (tile.query?.kind === "inline" ? "inline" : "reference")),
  );
  let referencePath = $state<string>(untrack(() => (tile.query?.kind === "reference" ? tile.query.path : "")));

  // Cube catalogue + saved-query catalogue, fetched once on open.
  let cubes = $state<AiCubeSummary[]>([]);
  let cubesError = $state<string | null>(null);
  let cubesLoading = $state(false);
  let savedQueries = $state<RepositoryNode[]>([]);
  let savedQueriesError = $state<string | null>(null);
  let savedQueriesLoading = $state(false);

  onMount(async () => {
    if (tile.type === "text") return; // text tiles don't need the catalogue
    cubesLoading = true;
    const needSavedQueries = tile.type === "chart" || tile.type === "table";
    if (needSavedQueries) savedQueriesLoading = true;
    try {
      const tasks: Array<Promise<void>> = [
        listAiCubes()
          .then((c) => {
            cubes = c;
          })
          .catch((e: unknown) => {
            cubesError = e instanceof Error ? e.message : String(e);
          }),
      ];
      if (needSavedQueries) {
        tasks.push(
          listRepository(["saiku"])
            .then((tree) => {
              savedQueries = flatten(tree).filter((n) => n.type === "FILE");
            })
            .catch((e: unknown) => {
              savedQueriesError = e instanceof Error ? e.message : String(e);
            }),
        );
      }
      await Promise.all(tasks);
    } finally {
      cubesLoading = false;
      savedQueriesLoading = false;
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

  let positionError = $state<string | null>(null);

  function handleSave(): void {
    bodyError = null;
    positionError = null;

    // Resolve the new position via the layout-aware helper: validates
    // x+w <= cols, refuses sub-1 sizes, and cascade-pushes siblings if
    // the new rectangle overlaps them. Returns either a full new tiles
    // array or an inline error to surface in the modal.
    const layout = dashboardStore.current?.layout;
    if (!layout) return;
    const reposition = repositionTile(layout, tile.id, {
      x: Math.floor(tileX),
      y: Math.floor(tileY),
      w: Math.floor(tileW),
      h: Math.floor(tileH),
    });
    if (!reposition.ok) {
      positionError = reposition.error ?? "Invalid position or size.";
      return;
    }

    // Build the non-position patch (title, content, cube, query, etc.)
    // and apply it on top of the repositioned source tile, then commit
    // the whole tiles array in one go via replaceTiles so dirty bumps
    // once for the entire change.
    const patch: Partial<DashboardTile> = {
      title: title || undefined,
    };

    if (tile.type === "text") {
      patch.text = text;
    } else if (cube) {
      patch.cube = cube;
    }

    if (tile.type === "chart") {
      patch.chartType = chartType;
    }

    if (tile.type === "chart" || tile.type === "table") {
      if (queryMode === "reference") {
        if (referencePath) {
          const q: TileQuery = { kind: "reference", path: referencePath };
          patch.query = q;
        }
      } else if (inlineBodyJson.trim()) {
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

    // Merge the patch into the repositioned source tile, then commit
    // the whole cascade in one go.
    const tiles = reposition.tiles!.map((t) => (t.id === tile.id ? { ...t, ...patch } : t));
    dashboardStore.replaceTiles(tiles);
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

      <fieldset class="size">
        <legend>Position &amp; size (12-col grid)</legend>
        <label class="field inline">
          <span>x</span>
          <input type="number" min="0" max="11" bind:value={tileX} />
        </label>
        <label class="field inline">
          <span>y</span>
          <input type="number" min="0" bind:value={tileY} />
        </label>
        <label class="field inline">
          <span>w</span>
          <input type="number" min="1" max="12" bind:value={tileW} />
        </label>
        <label class="field inline">
          <span>h</span>
          <input type="number" min="1" bind:value={tileH} />
        </label>
      </fieldset>
      {#if positionError}
        <div class="position-error" role="alert">{positionError}</div>
      {/if}

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
        <fieldset class="mode">
          <legend>Query source</legend>
          <label class="radio">
            <input type="radio" bind:group={queryMode} value="reference" />
            <span>Saved query</span>
          </label>
          <label class="radio">
            <input type="radio" bind:group={queryMode} value="inline" />
            <span>Inline JSON</span>
          </label>
        </fieldset>

        {#if queryMode === "reference"}
          <label class="field">
            <span>Saved query (.saiku file)</span>
            {#if savedQueriesLoading}
              <span class="hint">Loading…</span>
            {:else if savedQueriesError}
              <span class="hint error">{savedQueriesError}</span>
            {:else if savedQueries.length === 0}
              <span class="hint">
                No saved queries in the repository yet — switch to Inline JSON, or
                save a query from the main workspace first.
              </span>
            {:else}
              <select bind:value={referencePath}>
                <option value="">— pick a saved query —</option>
                {#each savedQueries as q (q.path)}
                  <option value={q.path}>{q.path}</option>
                {/each}
              </select>
              <span class="hint">
                Runtime fetch of saved queries lands in a follow-up — the
                reference is persisted, the tile renderer will pick it up
                once the resolver endpoint is wired.
              </span>
            {/if}
          </label>
        {:else}
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
    background: var(--bg);
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
    border-bottom: 1px solid var(--border);
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
  .size {
    display: flex;
    gap: 0.5rem;
    align-items: flex-end;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .size legend {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  .field.inline {
    flex: 1;
    min-width: 4rem;
  }
  .mode {
    display: flex;
    gap: 1rem;
    align-items: center;
    border: 1px solid var(--border);
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .mode legend {
    font-size: 0.75rem;
    color: var(--fg-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  .radio {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    font-size: 0.875rem;
    cursor: pointer;
  }
  .position-error {
    padding: 0.5rem 0.75rem;
    background: color-mix(in srgb, var(--danger) 14%, transparent);
    color: var(--danger);
    border-radius: 4px;
    font-size: 0.8125rem;
  }
  .field.inline input {
    width: 100%;
  }
  input, select, textarea {
    padding: 0.375rem 0.5rem;
    border: 1px solid var(--border-strong);
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
  .hint.error { color: var(--danger); }
  .modal-footer {
    display: flex;
    justify-content: flex-end;
    gap: 0.5rem;
    padding: 0.75rem 1rem;
    border-top: 1px solid var(--border);
  }
  .btn {
    padding: 0.375rem 0.75rem;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    border-radius: 4px;
    cursor: pointer;
    font-size: 0.875rem;
  }
  .btn.primary {
    background: var(--accent);
    color: white;
    border-color: var(--accent);
  }
</style>
