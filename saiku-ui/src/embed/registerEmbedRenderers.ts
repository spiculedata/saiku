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
import { registerTileRenderer } from "../lib/dashboard/tileRegistry";
import { validateEchartsOption } from "../lib/dashboard/custom/echartsOption";
import EmbedEChartsOptionTile from "../lib/views/dashboard/tiles/custom/EmbedEChartsOptionTile.svelte";

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
