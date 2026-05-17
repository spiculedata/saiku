<script lang="ts">
  /*
   * Table tile. Reads the tile's effective query, fetches via the AI
   * Query API in records format, and renders the result as an HTML
   * table. Row-header columns emit a `clickFilter` event when clicked
   * — Tile.svelte bubbles it up to the grid, which pushes onto
   * activeFilters with the source tile id.
   *
   * Inline tiles only in v1. Reference tiles (where query.kind ===
   * "reference") display a placeholder until the .saiku → AiQueryRequest
   * conversion lands.
   *
   * The fetch runs in an $effect keyed by:
   *   - the tile's id
   *   - the active filter set (so filter changes refire)
   *   - the schema cache version (so the merge re-resolves when a
   *     previously-missing schema arrives)
   */

  import type { DashboardTile, DashboardFilter } from "$lib/api/dashboards";
  import {
    executeAiQuery,
    executeSavedQuery,
    isAiCell,
    type AiQueryResponse,
    type AiCell,
  } from "$lib/api/aiQuery";
  import { activeFilters } from "$lib/stores/activeFilters.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import {
    effectiveQueryFor,
    applicableSavedFilters,
    type SchemaLike,
  } from "$lib/dashboard/effectiveQuery";

  interface Props {
    tile: DashboardTile;
    /** Called with the dim/hier/level/members context when the user
     *  clicks a row-header cell. Parent (Tile.svelte / grid) pushes the
     *  resulting ActiveFilter onto the active-filter set. */
    onClickFilter?: (filter: DashboardFilter) => void;
  }

  let { tile, onClickFilter }: Props = $props();

  let loading = $state(false);
  let error = $state<string | null>(null);
  let response = $state<AiQueryResponse | null>(null);
  let schema = $state<SchemaLike | null>(null);

  // Resolve the cube's schema once on mount; the effect below picks it
  // up via the version sentinel. We keep a local copy so the tile
  // doesn't churn through the cache map on every reactive tick.
  $effect(() => {
    const v = schemaCache.version; // dep
    void v;
    if (!tile.cube) {
      schema = null;
      return;
    }
    const cached = schemaCache.peek(tile.cube) as SchemaLike | null;
    if (cached) {
      schema = cached;
    } else {
      // Fire the fetch; effect re-runs when version bumps.
      void schemaCache.get(tile.cube).catch(() => {
        // Failure surfaces in error banner of the next fetch attempt.
      });
    }
  });

  // Recompute the effective query whenever the active filter set or
  // schema changes; refetch on identity change.
  let lastQueryJson = $state<string>("");

  $effect(() => {
    const tileQuery = tile.query;
    const active = activeFilters.all;
    const s = schema;
    void s;
    if (!tileQuery) return;

    if (tileQuery.kind === "reference") {
      // Reference tile: server loads the saved ThinQuery, merges any
      // applicable dashboard filters onto it via ThinQueryFilterMerge,
      // then runs it. Filter applicability is checked client-side first
      // against the tile's cube schema so we don't ship filters the
      // server would have to drop anyway.
      const refFilters = applicableSavedFilters(schema, active);
      const key = `ref:${tileQuery.path}|${JSON.stringify(refFilters)}`;
      if (key === lastQueryJson) return;
      lastQueryJson = key;
      loading = true;
      error = null;
      void (async () => {
        try {
          const r = await executeSavedQuery(
            tileQuery.path,
            refFilters.map((f) => ({
              dimension: f.dimension,
              hierarchy: f.hierarchy,
              level: f.level,
              members: f.members ?? [],
            })),
          );
          response = r;
          if (r.status !== "SUCCESS") error = r.error ?? `Query failed: ${r.status}`;
        } catch (e: unknown) {
          error = e instanceof Error ? e.message : String(e);
          response = null;
        } finally {
          loading = false;
        }
      })();
      return;
    }

    // Inline tile: merge active filters into the base body via the
    // effective-query builder, then POST to /ai/query.
    const effective = effectiveQueryFor(tile, active, schema);
    if (!effective) return;
    const json = JSON.stringify(effective);
    if (json === lastQueryJson) return; // no-op; avoid duplicate fetches
    lastQueryJson = json;

    loading = true;
    error = null;
    void (async () => {
      try {
        const r = await executeAiQuery(effective, "records");
        response = r;
        if (r.status !== "SUCCESS") {
          error = r.error ?? `Query failed: ${r.status}`;
        }
      } catch (e: unknown) {
        error = e instanceof Error ? e.message : String(e);
        response = null;
      } finally {
        loading = false;
      }
    })();
  });

  // Derived: a flat list of column captions (row headers first, then
  // measures) so the table render can iterate by index.
  let columns = $derived<{ caption: string; isRowHeader: boolean }[]>((() => {
    if (!response?.metadata) return [];
    const rowHeader: { caption: string; isRowHeader: boolean }[] = [];
    // The first data row tells us which columns are row-headers (plain
    // strings) vs measures (AiCell). We pick the row-header captions
    // from the first data row's plain-string keys, in insertion order.
    const firstRow = response.data?.[0];
    if (firstRow) {
      for (const key of Object.keys(firstRow)) {
        if (!isAiCell(firstRow[key])) {
          rowHeader.push({ caption: key, isRowHeader: true });
        }
      }
    }
    const measures = (response.metadata.columns ?? []).map((c) => ({
      caption: c.caption,
      isRowHeader: false,
    }));
    return [...rowHeader, ...measures];
  })());

  /** Build the click-filter context for a row-header cell. The dashboard
   *  level for the click is inferred from the tile's base query: we
   *  match the column caption against the rows[]/columns[] axis level
   *  captions in the AiQueryMetadata. */
  function clickFilterForCell(column: string, cellValue: string): DashboardFilter | null {
    // Find the row/column axis level whose caption corresponds to this
    // column. For now this is best-effort: agents that need column-axis
    // click capture (cross-join measures × time) will be served when
    // task #14's live test pins the contract. For row-axis levels the
    // header caption equals the level caption.
    if (!tile.query || tile.query.kind !== "inline") return null;
    const body = tile.query.body as {
      rows?: Array<{ dimension: string; hierarchy: string; level: string }>;
    };
    const match = (body.rows ?? []).find((ax) => ax.level === column);
    if (!match) return null;
    // The cell value is the formatted caption — we don't have the MDX
    // unique name here. The server's filter applicability check will
    // accept the caption AS the member ref in records format because
    // the merge layer (effectiveQuery.mergeFilters) doesn't validate
    // member uniqueNames — only dim/hier/level. The AI converter then
    // resolves the caption to a unique name at query-execute time;
    // mismatches surface as VALIDATION_ERROR for the agent to retry.
    return {
      dimension: match.dimension,
      hierarchy: match.hierarchy,
      level: match.level,
      members: [cellValue],
    };
  }

  function handleCellClick(column: string, cellValue: string, isRowHeader: boolean): void {
    if (!isRowHeader) return;
    const filter = clickFilterForCell(column, cellValue);
    if (!filter) return;
    onClickFilter?.(filter);
  }

  function renderCell(v: AiCell | string): string {
    if (typeof v === "string") return v;
    return v.formatted ?? "";
  }
</script>

{#if !tile.query}
  <div class="placeholder">Tile has no query binding — open ⚙ to set one.</div>
{:else}
  <div class="table-tile" role="region" aria-label="Table tile">
    {#if loading && !response}
      <div class="state">Loading…</div>
    {/if}
    {#if error}
      <div class="state error">{error}</div>
    {/if}
    {#if response && response.status === "SUCCESS"}
      {@const rows = response.data ?? []}
      {#if rows.length === 0}
        <div class="state empty">No rows.</div>
      {:else}
        <table>
          <thead>
            <tr>
              {#each columns as col (col.caption)}
                <th class:row-header={col.isRowHeader}>{col.caption}</th>
              {/each}
            </tr>
          </thead>
          <tbody>
            {#each rows as row, i (i)}
              <tr>
                {#each columns as col (col.caption)}
                  {@const v = row[col.caption]}
                  <td
                    class:row-header={col.isRowHeader}
                    class:clickable={col.isRowHeader}
                    onclick={() => handleCellClick(col.caption, renderCell(v), col.isRowHeader)}
                    role={col.isRowHeader ? "button" : undefined}
                    tabindex={col.isRowHeader ? 0 : undefined}
                    onkeydown={(e) => {
                      if (col.isRowHeader && (e.key === "Enter" || e.key === " ")) {
                        e.preventDefault();
                        handleCellClick(col.caption, renderCell(v), col.isRowHeader);
                      }
                    }}
                  >
                    {renderCell(v)}
                  </td>
                {/each}
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    {/if}
  </div>
{/if}

<style>
  .table-tile {
    height: 100%;
    overflow: auto;
    padding: 0.25rem 0.5rem;
  }
  .placeholder {
    padding: 1rem;
    color: var(--fg-muted);
    font-size: 0.8125rem;
  }
  .state {
    padding: 0.5rem;
    color: var(--fg-muted);
    font-size: 0.875rem;
  }
  .state.error {
    background: color-mix(in srgb, var(--danger) 10%, transparent);
    color: var(--danger);
    border-radius: 4px;
  }
  .state.empty {
    text-align: center;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.8125rem;
  }
  th, td {
    padding: 0.25rem 0.5rem;
    text-align: right;
    border-bottom: 1px solid var(--border);
  }
  th {
    background: var(--bg-muted);
    font-weight: 600;
    position: sticky;
    top: 0;
    z-index: 1;
  }
  th.row-header, td.row-header {
    text-align: left;
  }
  td.clickable {
    cursor: pointer;
  }
  td.clickable:hover {
    background: var(--bg-subtle);
  }
  td.clickable:focus {
    outline: 2px solid var(--accent);
    outline-offset: -2px;
  }
</style>
