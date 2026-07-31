/*
 * Built-in custom tile renderers (App Builder Phase 2, saiku#1441).
 *
 * Registers the renderers that ship with Saiku into the tile registry by IMPORT
 * SIDE EFFECT. Importing this module once (at app boot — see the dashboards /
 * apps route entries) makes them discoverable from the tile registry, which:
 *   - enables AddTileMenu's "Custom…" entry (gated on listTileRenderers()), and
 *   - lets Tile.svelte's `custom` branch resolve `tile.custom.renderer`.
 *
 * Currently just the `echarts-option` renderer: a declarative, validated (NO
 * code) ECharts option tile. Its embed variant is registered SEPARATELY for the
 * embed bundle (src/embed/registerEmbedRenderers.ts) so the app-only in-tile
 * component + its store graph never get pulled into the self-contained embed
 * IIFE.
 */

import type { Component } from "svelte";
import { registerTileRenderer } from "$lib/dashboard/tileRegistry";
import { validateEchartsOption } from "$lib/dashboard/custom/echartsOption";
import EChartsOptionTile from "$lib/views/dashboard/tiles/custom/EChartsOptionTile.svelte";
import EmbedEChartsOptionTile from "$lib/views/dashboard/tiles/custom/EmbedEChartsOptionTile.svelte";

// The registry types `component`/`embedComponent` as the prop-erased `Component`
// (renderers are dispatched dynamically); these tiles declare a required `tile`
// prop, so cast through `Component` at the registration boundary.
registerTileRenderer({
  id: "echarts-option",
  label: "ECharts option",
  icon: "📉",
  component: EChartsOptionTile as unknown as Component,
  embedComponent: EmbedEChartsOptionTile as unknown as Component,
  isQueryable: true,
  validateOptions: validateEchartsOption,
});
