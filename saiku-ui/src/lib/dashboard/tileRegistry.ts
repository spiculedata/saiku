/*
 * Tile renderer registry (App Builder Phase 2, saiku#1441).
 *
 * Opens the previously-hardcoded tile dispatch so later tasks can register
 * custom tile renderers by import side-effect. Built-in tiles (chart / table /
 * text / kpi / image / filter) are NOT registered here — they keep their
 * dedicated {#if} branches in Tile.svelte / EmbedGrid.svelte. This registry is
 * consulted only for tiles of type "custom", keyed by `tile.custom.renderer`.
 *
 * The registry is a plain module-global map. Registration happens once, at
 * import time, from each renderer's own module — a later task adds
 *   import "$lib/dashboard/renderers/<name>";
 * to a barrel that the dashboard + embed entry points import.
 */

import type { Component } from "svelte";

/** Persisted per-tile config for a custom-rendered tile. Carried on
 *  {@code DashboardTile.custom} and handed verbatim to the renderer. */
export interface CustomTileConfig {
  /** Renderer id — matches a {@link TileRenderer.id} in the registry. */
  renderer: string;
  /** Opaque renderer-specific options. Validated by the renderer's
   *  {@link TileRenderer.validateOptions} before use. */
  options: Record<string, unknown>;
  /** echarts-option renderer only: emphasise the final data point of the first
   *  series with an accent-coloured marker (the "current period" dot). */
  emphasizeLast?: boolean;
  /** echarts-option renderer only: render a "Trend / Breakdown" toggle in the
   *  tile corner that swaps the series between a line (trend) and bars
   *  (breakdown) over the same query — the reference trend-card control. */
  trendBreakdown?: boolean;
}

/** Result of validating a custom tile's opaque options blob. */
export type ValidateOptionsResult =
  | { ok: true; value: Record<string, unknown> }
  | { ok: false; error: string };

/** A pluggable tile renderer. Registered by import side-effect; looked up by
 *  {@link id} when a tile of type "custom" is dispatched. */
export interface TileRenderer {
  /** Stable identifier, referenced by {@link CustomTileConfig.renderer}. */
  id: string;
  /** Human-readable label for the add-tile / renderer picker. */
  label: string;
  /** Optional icon (emoji or glyph) for the picker entry. */
  icon?: string;
  /** In-app tile body component. Receives the same props built-in tiles get. */
  component: Component;
  /** Token-scoped embed tile body. Omit → "Unsupported" in the embed surface. */
  embedComponent?: Component;
  /** Whether this renderer issues a query (drives embed fetch / edit affordances). */
  isQueryable: boolean;
  /** Validate + normalise the tile's opaque options blob. */
  validateOptions: (options: unknown) => ValidateOptionsResult;
}

const REGISTRY = new Map<string, TileRenderer>();

/** Register (or replace) a tile renderer under its {@link TileRenderer.id}. */
export function registerTileRenderer(renderer: TileRenderer): void {
  REGISTRY.set(renderer.id, renderer);
}

/** Look up a registered renderer by id, or {@code undefined} if none. */
export function getTileRenderer(id: string): TileRenderer | undefined {
  return REGISTRY.get(id);
}

/** All registered renderers, in insertion order. */
export function listTileRenderers(): TileRenderer[] {
  return [...REGISTRY.values()];
}
