/*
 * AI dashboard assembler — turn a validated DashboardSpec (from the NL
 * build endpoint) into a REAL Dashboard the existing editor / grid / tile
 * renderers already understand.
 *
 * PURE: no store writes, no navigation, no id side effects beyond minting
 * fresh tile ids. Reuses Saiku's existing dashboard model + placement
 * helpers (newDashboard / newTileId / defaultSizeFor / firstFreeSlot) rather
 * than reinventing geometry or the tile shape — so an assembled dashboard is
 * indistinguishable from one an analyst built by hand, and the human's Save
 * writes it exactly like any other dashboard (REVIEW-THEN-SAVE: this module
 * never persists).
 *
 * Titles come from the LLM: they are carried through verbatim as tile / name
 * strings and MUST be rendered as TEXT downstream (Tile.svelte + the toolbar
 * already use `{title}` interpolation, never {@html}) — the SEC carry-forward
 * from D1. This module does not HTML-encode or otherwise transform them.
 */

import {
  newDashboard,
  newTileId,
  type CubeRef,
  type Dashboard,
  type DashboardTile,
  type TileType,
} from "$lib/api/dashboards";
import type { DashboardSpec } from "$lib/api/aiDashboard";
import { defaultSizeFor, firstFreeSlot } from "$lib/dashboard/tilePlacement";
import { isChartType } from "$lib/views/chartTypes";

/** UI tile types the builder emits. The backend already constrains `type` to
 *  this set, but we validate defensively — an out-of-set value degrades to a
 *  table (mirroring the backend's own coerce default) rather than producing an
 *  unrenderable tile. */
const ASSEMBLABLE_TILE_TYPES: ReadonlySet<string> = new Set<TileType>(["chart", "table", "kpi"]);

/** Default chart kind for a chart tile whose spec chartType is missing /
 *  unrecognised — matches the backend's `coerceChartType` fallback. */
const DEFAULT_CHART_TYPE = "bar";

function coerceTileType(type: string | undefined): TileType {
  return type && ASSEMBLABLE_TILE_TYPES.has(type) ? (type as TileType) : "table";
}

/**
 * Assemble a non-degraded DashboardSpec into a Dashboard.
 *
 * - name = spec.title
 * - layout.cols = 12 (from newDashboard)
 * - one DashboardTile per spec tile, each:
 *   - fresh id (newTileId)
 *   - default size for its type (defaultSizeFor) placed at the first free
 *     slot (firstFreeSlot) against the tiles already positioned, so no two
 *     tiles share a footprint
 *   - type validated to the UI tile-type set
 *   - chartType only on chart tiles (validated against the UI chart set)
 *   - title carried verbatim (rendered as text downstream)
 *   - cube = the passed session CubeRef (backend already pinned every tile
 *     query to it)
 *   - query wrapped as an InlineQuery: {kind:"inline", body: <AiQueryRequest>}
 *
 * Throws if handed a degraded spec — degraded specs carry no tiles and must be
 * surfaced to the user (reason), never assembled. The caller guards on
 * `spec.degraded`; this is a defensive backstop.
 */
export function assembleDashboard(spec: DashboardSpec, cube: CubeRef): Dashboard {
  if (spec.degraded) {
    throw new Error("assembleDashboard: refusing to assemble a degraded spec");
  }

  const dashboard: Dashboard = newDashboard(spec.title ?? "AI dashboard");

  for (const tileSpec of spec.tiles) {
    const type = coerceTileType(tileSpec.type);
    const size = defaultSizeFor(type);
    // Place against the growing layout so each tile clears the ones already
    // positioned. newDashboard() seeds layout.cols = 12.
    const slot = firstFreeSlot(dashboard.layout, size.w, size.h);

    const tile: DashboardTile = {
      id: newTileId(),
      x: slot.x,
      y: slot.y,
      w: size.w,
      h: size.h,
      type,
      // LLM-authored — rendered as TEXT by Tile.svelte, never @html (SEC).
      title: tileSpec.title,
      cube,
      // Inline the full AiQueryRequest verbatim; the per-tile query path
      // (useTileQuery → /ai/query) executes it exactly like any inline tile.
      query: { kind: "inline", body: tileSpec.query },
    };

    if (type === "chart") {
      tile.chartType = isChartType(tileSpec.chartType ?? "")
        ? tileSpec.chartType
        : DEFAULT_CHART_TYPE;
    }

    dashboard.layout.tiles.push(tile);
  }

  return dashboard;
}
