/*
 * Built-in custom tile renderers (App Builder Phase 2, saiku#1441).
 *
 * Registers the renderers that ship with Saiku into the tile registry by IMPORT
 * SIDE EFFECT. Importing this module once (at app boot — see the dashboards /
 * apps route entries) makes them discoverable from the tile registry, which:
 *   - enables AddTileMenu's "Custom…" entry (gated on listTileRenderers()), and
 *   - lets Tile.svelte's `custom` branch resolve `tile.custom.renderer`.
 *
 * Two renderers today: the `echarts-option` renderer (a declarative, validated —
 * NO code — ECharts option tile) and the `graph` renderer (records → an ECharts
 * graph series via a declarative column mapping). Their embed variants are
 * registered SEPARATELY for the embed bundle (src/embed/registerEmbedRenderers.ts)
 * so the app-only in-tile components + their store graph never get pulled into
 * the self-contained embed IIFE.
 */

import type { Component } from "svelte";
import { registerTileRenderer, type ValidateOptionsResult } from "$lib/dashboard/tileRegistry";
import { validateEchartsOption } from "$lib/dashboard/custom/echartsOption";
import { validateGraphConfig } from "$lib/dashboard/custom/graphTile";
import { validatePluginOptions } from "$lib/dashboard/custom/pluginBridge";
import EChartsOptionTile from "$lib/views/dashboard/tiles/custom/EChartsOptionTile.svelte";
import EmbedEChartsOptionTile from "$lib/views/dashboard/tiles/custom/EmbedEChartsOptionTile.svelte";
import GraphTile from "$lib/views/dashboard/tiles/custom/GraphTile.svelte";
import EmbedGraphTile from "$lib/views/dashboard/tiles/custom/EmbedGraphTile.svelte";
import PluginTile from "$lib/views/dashboard/tiles/custom/PluginTile.svelte";
import EmbedPluginTile from "$lib/views/dashboard/tiles/custom/EmbedPluginTile.svelte";

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

registerTileRenderer({
  id: "graph",
  label: "Graph",
  icon: "🕸️",
  component: GraphTile as unknown as Component,
  embedComponent: EmbedGraphTile as unknown as Component,
  isQueryable: true,
  // GraphConfig is a typed shape (no index signature); the registry types the
  // validated value as Record<string, unknown>. The runtime value is a plain
  // record, so widen it at the registration boundary.
  validateOptions: validateGraphConfig as (o: unknown) => ValidateOptionsResult,
});

// Tier-2 `plugin` renderer — runs ARBITRARY author JavaScript inside a
// locked-down iframe (sandbox="allow-scripts" + strict CSP + per-mount nonce).
// See PluginTile.svelte / pluginBridge.ts for the full containment contract.
registerTileRenderer({
  id: "plugin",
  label: "Plugin (sandboxed JS)",
  icon: "🧩",
  component: PluginTile as unknown as Component,
  embedComponent: EmbedPluginTile as unknown as Component,
  isQueryable: true,
  validateOptions: validatePluginOptions,
});
