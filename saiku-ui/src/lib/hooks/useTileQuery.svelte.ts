/*
 * Shared fetch state + effect body for ChartTile / TableTile
 * (saiku#1231). Both tiles share the SAME pattern:
 *
 *   tile.query → effectiveQueryFor() (or saved-query reference) →
 *   executeAiQuery() / executeSavedQuery() → loading / error / response
 *
 * plus retry, auto-refresh, share-viewer overrides, and JSON-dedupe so
 * an unchanged query doesn't fire a duplicate fetch.
 *
 * KpiTile intentionally NOT covered here — it builds its body inline
 * with prior-period expansion, no tile.query, and a different dedupe
 * key. Extracting it would need a different rune (issue #1231 calls
 * this out as a known scope boundary).
 *
 * Pattern: state held on a class so the consumer instantiates once
 * (`const query = new TileQueryState()`); the effect body is a free
 * function the consumer calls from inside its own $effect so reactive
 * deps register on the COMPONENT's scope, not the class's. Returning
 * `$state` from a factory closure can capture the wrong context — see
 * the Risk note in #1231.
 */

import {
  executeAiQuery,
  executeSavedQuery,
  type AiQueryResponse,
} from "$lib/api/aiQuery";
import {
  effectiveQueryFor,
  applicableSavedFilters,
  type SchemaLike,
} from "$lib/dashboard/effectiveQuery";
import { TileAutoRefresh } from "$lib/dashboard/tileAutoRefresh.svelte";
import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";
import type { DashboardTile } from "$lib/api/dashboards";

/** Reactive state container for one tile's fetch lifecycle. */
export class TileQueryState {
  loading = $state(false);
  error = $state<string | null>(null);
  response = $state<AiQueryResponse | null>(null);

  /** Bumped by {@link retry} to force a refetch with unchanged inputs. */
  retryTick = $state(0);
  /** Bumped by {@link triggerAutoRefresh} on the configured cadence. */
  refreshTick = $state(0);

  /** Per-tile auto-refresh timer ({@link arm}, {@link startHeartbeat}). */
  auto = new TileAutoRefresh();

  /** Internal JSON dedupe key — not reactive (it would re-trigger the
   *  effect we're running inside). Cleared by retry / refresh to force
   *  a re-fetch with unchanged inputs. */
  lastQueryJson = "";

  retry(): void {
    this.lastQueryJson = "";
    this.retryTick++;
  }

  triggerAutoRefresh(): void {
    this.lastQueryJson = "";
    this.refreshTick++;
  }
}

export interface TileQueryDeps {
  tile: DashboardTile;
  activeFilters: ActiveFilter[];
  schema: SchemaLike | null;
  /** Share-viewer prefetched response — when set, render this verbatim
   *  and skip the live fetch (no /ai/query access in the guest view). */
  sharedResponse: AiQueryResponse | null;
}

/**
 * Effect body shared by ChartTile + TableTile. Call from inside the
 * consumer's $effect; read state.retryTick + state.refreshTick before
 * calling so they register as reactive deps.
 *
 * Mutates `state` in place — assigns loading / error / response /
 * lastQueryJson as the fetch progresses.
 */
export function runTileQueryEffect(
  state: TileQueryState,
  deps: TileQueryDeps,
): void {
  const { tile, activeFilters, schema, sharedResponse } = deps;

  // Share viewer (#941): render the prefetched guest response; never
  // fetch live (a share guest has no session / no /ai/query access).
  if (sharedResponse) {
    state.response = sharedResponse;
    state.loading = false;
    state.error = null;
    return;
  }

  const tileQuery = tile.query;
  if (!tileQuery) return;

  if (tileQuery.kind === "reference") {
    // Reference tile: server loads the saved ThinQuery, merges any
    // applicable dashboard filters onto it via ThinQueryFilterMerge,
    // then runs it. Filter applicability is checked client-side first
    // against the tile's cube schema so we don't ship filters the
    // server would have to drop anyway.
    const refFilters = applicableSavedFilters(schema, activeFilters);
    const key = `ref:${tileQuery.path}|${JSON.stringify(refFilters)}`;
    if (key === state.lastQueryJson) return;
    state.lastQueryJson = key;
    state.loading = true;
    state.error = null;
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
        state.response = r;
        if (r.status !== "SUCCESS")
          state.error = r.error ?? `Query failed: ${r.status}`;
        else state.auto.markUpdated(); // #931
      } catch (e: unknown) {
        state.error = e instanceof Error ? e.message : String(e);
        state.response = null;
      } finally {
        state.loading = false;
      }
    })();
    return;
  }

  // Inline tile: merge active filters into the base body via the
  // effective-query builder, then POST to /ai/query.
  const effective = effectiveQueryFor(tile, activeFilters, schema);
  if (!effective) return;
  const json = JSON.stringify(effective);
  if (json === state.lastQueryJson) return; // no-op; avoid duplicate fetches
  state.lastQueryJson = json;

  state.loading = true;
  state.error = null;
  void (async () => {
    try {
      const r = await executeAiQuery(effective, "records");
      state.response = r;
      if (r.status !== "SUCCESS") {
        state.error = r.error ?? `Query failed: ${r.status}`;
      } else {
        state.auto.markUpdated(); // #931
      }
    } catch (e: unknown) {
      state.error = e instanceof Error ? e.message : String(e);
      state.response = null;
    } finally {
      state.loading = false;
    }
  })();
}
