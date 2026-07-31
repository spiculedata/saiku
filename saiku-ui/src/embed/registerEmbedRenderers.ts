/*
 * Custom tile renderers for the <saiku-embed/> bundle (App Builder Phase 2,
 * saiku#1441).
 *
 * The embed bundle is self-contained (its own vite config, no `$lib` alias) and
 * MUST NOT pull the app's in-tile component or its store graph in. So we register
 * a registry entry whose ONLY meaningful field for the embed surface is
 * `embedComponent` — EmbedGrid reads `getTileRenderer(id).embedComponent` and
 * never touches `component`. `component` is required by the TileRenderer type,
 * so it's set to the embed component too (unused here).
 *
 * Imported for its side effect from EmbedGrid.svelte, which both the dashboard
 * embed and the App Builder embed render through.
 */

import type { Component } from "svelte";
import { registerTileRenderer, type ValidateOptionsResult } from "../lib/dashboard/tileRegistry";
import { validateEchartsOption } from "../lib/dashboard/custom/echartsOption";
import { validateGraphConfig } from "../lib/dashboard/custom/graphTile";
import EmbedEChartsOptionTile from "../lib/views/dashboard/tiles/custom/EmbedEChartsOptionTile.svelte";
import EmbedGraphTile from "../lib/views/dashboard/tiles/custom/EmbedGraphTile.svelte";

registerTileRenderer({
  id: "echarts-option",
  label: "ECharts option",
  icon: "📉",
  // Unused on the embed surface (EmbedGrid dispatches on embedComponent), but
  // the type requires it — reuse the embed component so no app module is pulled
  // in. Cast through Component: these tiles declare a required `tile` prop.
  component: EmbedEChartsOptionTile as unknown as Component,
  embedComponent: EmbedEChartsOptionTile as unknown as Component,
  isQueryable: true,
  validateOptions: validateEchartsOption,
});

registerTileRenderer({
  id: "graph",
  label: "Graph",
  icon: "🕸️",
  // component unused on the embed surface (see above) — reuse the embed
  // component so no app module is pulled into the self-contained bundle.
  component: EmbedGraphTile as unknown as Component,
  embedComponent: EmbedGraphTile as unknown as Component,
  isQueryable: true,
  // GraphConfig is a typed shape (no index signature); widen the validated
  // value to the registry's Record<string, unknown> at the boundary.
  validateOptions: validateGraphConfig as (o: unknown) => ValidateOptionsResult,
});
