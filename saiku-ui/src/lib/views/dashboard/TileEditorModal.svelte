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
  import { Button } from "$lib/components/ui";
  import { dashboardStore } from "$lib/stores/dashboard.svelte";
  import { schemaCache } from "$lib/stores/schemaCache.svelte";
  import {
    listAiCubes,
    type AiCubeSummary,
    type CubeRef,
    type DashboardFilter,
    type DashboardTile,
    type FilterWidget,
    type KpiConfig,
    type ImageSource,
    type ImageFit,
    uploadImageAsset,
    type TileQuery,
    // Issue #919 — conditional formatting on table tiles.
    type ConditionalFormatRule,
    type ConditionalFormatType,
    type ConditionalThresholdMode,
    // Issue #920 — inline sparkline column on table tiles.
    type SparklineType,
    // Issue #907 — anomaly detection on time-series chart tiles.
    type AnomalyMethodConfig,
    // Issue #908 — forecast on time-series chart tiles.
    type ForecastMethodConfig,
  } from "$lib/api/dashboards";
  import { flatten, listRepository, type RepositoryNode } from "$lib/api/repository";
  import { repositionTile } from "$lib/dashboard/tilePlacement";
  import { DEFAULT_CHART_OPTIONS, type ChartOptions } from "$lib/views/chartTypes";
  // #1085: which chart kinds support a brush cross-filter (gates the toggle).
  import { BRUSHABLE_CHART_TYPES } from "$lib/charts/build";
  // #1077: reuse the workspace chart-options editor for dashboard chart tiles.
  import ChartEditorModal from "$lib/modals/ChartEditorModal.svelte";
  import TileEditorImage from "$lib/views/dashboard/TileEditorImage.svelte";
  import TileEditorText from "$lib/views/dashboard/TileEditorText.svelte";
  import TileEditorChart from "$lib/views/dashboard/TileEditorChart.svelte";
  import TileEditorFilter from "$lib/views/dashboard/TileEditorFilter.svelte";
  import TileEditorKpi from "$lib/views/dashboard/TileEditorKpi.svelte";
  import TileEditorTableConditional from "$lib/views/dashboard/TileEditorTableConditional.svelte";
  import TileEditorTableSparkline from "$lib/views/dashboard/TileEditorTableSparkline.svelte";
  // ── Issue #912: inline visual query editor (embedded QueryCanvas) ──
  import QueryCanvas from "$lib/views/QueryCanvas.svelte";
  import DimensionList from "$lib/views/DimensionList.svelte";
  import { query } from "$lib/stores/query.svelte";
  import { selection } from "$lib/stores/selection.svelte";
  import { session } from "$lib/stores/session.svelte";
  import { datasources } from "$lib/stores/datasources.svelte";
  import { findCubeByRef } from "$lib/api/starterCube";
  import {
    bodyToThinQuery,
    thinQueryToBody,
    bodyIsRunnable,
    type InlineQueryBody,
  } from "$lib/dashboard/queryBodyConversion";
  import type { QueryStateSnapshot } from "$lib/stores/query.svelte";
  import type { SaikuCube } from "$lib/api/discover";
  // Issue #931 — per-tile auto-refresh interval picker (chart / table / kpi).
  import { REFRESH_INTERVAL_OPTIONS, normaliseInterval } from "$lib/dashboard/autoRefresh";
  import { i18n } from "$lib/stores/i18n.svelte";
  // App Builder Phase 2 (saiku#1441): custom tile renderers. The echarts-option
  // renderer gets a JSON option editor with live safe-subset validation.
  import { getTileRenderer } from "$lib/dashboard/tileRegistry";
  import { validateEchartsOption } from "$lib/dashboard/custom/echartsOption";
  // The `graph` renderer gets a declarative column-mapping editor (NO code).
  import { validateGraphConfig, type GraphLayout } from "$lib/dashboard/custom/graphTile";
  // The `ranked-list` renderer gets a declarative form too (NO code, NO CSS) —
  // it exists precisely so this card stops being a custom-CSS exercise.
  import {
    DEFAULT_LIMIT,
    validateRankedListConfig,
    type RankedSort,
    type RankedTone,
  } from "$lib/dashboard/custom/rankedList";
  import { listTilePlugins, type TilePluginSummary } from "$lib/api/tilePlugins";

  interface Props {
    tile: DashboardTile;
    onClose: () => void;
  }

  let { tile, onClose }: Props = $props();

  /** saiku#1781: display casing for the header. `tile.type` is a lowercase
   *  discriminator, and the old `text-transform: capitalize` turned "kpi" into
   *  the misspelt "Kpi". Acronyms get their real casing; everything else is
   *  sentence-cased from the discriminator so a new tile type still reads fine
   *  without touching this map. */
  const TILE_TYPE_LABELS: Record<string, string> = { kpi: "KPI" };
  const tileTypeLabel = $derived(
    TILE_TYPE_LABELS[tile.type] ?? tile.type.charAt(0).toUpperCase() + tile.type.slice(1),
  );

  // Per-tile editable form state — initialised from the source tile.
  // untrack() so the inits read the prop without subscribing; the modal
  // never re-renders for the same tile, so the one-shot read is the
  // intended behaviour (Svelte 5 warns by default).
  let title = $state(untrack(() => tile.title ?? ""));
  let cube = $state<CubeRef | null>(untrack(() => tile.cube ?? null));
  let chartType = $state(untrack(() => tile.chartType ?? "bar"));
  // #1077: per-tile chart options (title/axes/legend/dualAxis/seriesAxis/trend).
  // Working copy seeded from the tile (or defaults). Only persisted if the user
  // actually opens + saves the chart-options editor (chartOptionsTouched) — so
  // editing a legacy chart tile's title alone never stamps default options onto
  // it (which would change its appearance).
  let chartOptions = $state<ChartOptions>(
    untrack(() => ({ ...DEFAULT_CHART_OPTIONS, ...(tile.chartOptions ?? {}) })),
  );
  let chartOptionsTouched = $state(false);
  let chartOptionsOpen = $state(false);
  let text = $state(untrack(() => tile.text ?? ""));
  // Position + size — numeric controls per the design's "no drag-resize"
  // call. Users rearrange via these fields; the grid auto-places tiles
  // on add and re-renders to the new (x, y, w, h) on save.
  let tileX = $state<number>(untrack(() => tile.x));
  let tileY = $state<number>(untrack(() => tile.y));
  let tileW = $state<number>(untrack(() => tile.w));
  let tileH = $state<number>(untrack(() => tile.h));
  let widget = $state<FilterWidget>(untrack(() => tile.widget ?? "single-select"));
  // --- issue #922: cascading-select config (start level + depth). Held in
  // a self-contained working copy so the rebase against #919 stays clean;
  // only consulted when widget === "cascading-select". ---
  let cascadeStartLevel = $state<string>(untrack(() => tile.cascading?.startLevel ?? ""));
  let cascadeDepth = $state<number>(untrack(() => tile.cascading?.depth ?? 3));
  // --- end issue #922 ---
  let filterTarget = $state<{ dimension: string; hierarchy: string; level: string }>(
    untrack(() => ({
      dimension: tile.target?.dimension ?? "",
      hierarchy: tile.target?.hierarchy ?? "",
      level: tile.target?.level ?? "",
    })),
  );
  // KPI tile config — single working-copy object; nested fields bind
  // directly via Svelte 5's $state proxy. Defaults fill in unset fields
  // so the form has stable controls even for a fresh KPI tile.
  let kpiConfig = $state<KpiConfig>(
    untrack(() => ({
      measure: tile.kpi?.measure ?? "",
      measureCaption: tile.kpi?.measureCaption ?? "",
      format: tile.kpi?.format ?? "number",
      customFormat: tile.kpi?.customFormat ?? "",
      comparison: tile.kpi?.comparison ?? "none",
      target: tile.kpi?.target,
      timeLevel: tile.kpi?.timeLevel ?? { dimension: "", hierarchy: "", level: "" },
      sparkline: tile.kpi?.sparkline ?? false,
      partialTrailing: tile.kpi?.partialTrailing ?? 0,
      thresholds: tile.kpi?.thresholds ?? {},
      direction: tile.kpi?.direction ?? "higher-is-better",
    })),
  );
  // ── Issue #918: image tile config (image only) ──
  // mode "url" → imageUrl is an external http(s) URL; mode "upload" → a file
  // is uploaded on save and its returned download path becomes the src
  // (imageExistingSrc preserves the already-stored path when no new file is
  // picked). Held as a self-contained working copy, persisted in handleSave.
  let imageMode = $state<ImageSource>(untrack(() => tile.image?.source ?? "url"));
  let imageUrl = $state<string>(
    untrack(() => ((tile.image?.source ?? "url") === "url" ? (tile.image?.src ?? "") : "")),
  );
  let imageExistingSrc = $state<string>(untrack(() => tile.image?.src ?? ""));
  let imageFit = $state<ImageFit>(untrack(() => tile.image?.fit ?? "contain"));
  let imageCaption = $state<string>(untrack(() => tile.image?.caption ?? ""));
  let imageAlt = $state<string>(untrack(() => tile.image?.alt ?? ""));
  let imageFile = $state<File | null>(null);
  let imageUploading = $state(false);
  // ── end issue #918 ──
  // ── Issue #931: per-tile auto-refresh interval (minutes; 0 = off) ──
  // Self-contained working copy, normalised on init so a hand-edited /
  // off-list persisted value falls back to "off". Persisted in handleSave for
  // chart / table / kpi tiles. Kept a small clearly-delimited block so the
  // parallel #912 (inline query editor) edit on this modal rebases cleanly.
  let refreshInterval = $state<number>(untrack(() => normaliseInterval(tile.refreshInterval)));
  // ── end issue #931 ──
  let inlineBodyJson = $state<string>(
    untrack(() => (tile.query?.kind === "inline" ? JSON.stringify(tile.query.body, null, 2) : "")),
  );
  let bodyError = $state<string | null>(null);
  // ── Issue #919: per-column conditional-formatting rules (table only) ──
  // Self-contained working copy, deep-cloned from the source so edits are
  // discardable on Cancel. Persisted in handleSave under a table-guarded
  // block. Isolated here so the parallel #922 edit rebases cleanly.
  let conditionalFormat = $state<ConditionalFormatRule[]>(
    untrack(() =>
      (tile.conditionalFormat ?? []).map((r) => ({
        ...r,
        colors: r.colors ? { ...r.colors } : undefined,
      })),
    ),
  );
  // ── Issue #920: opt-in inline sparkline column (table only) ──
  // Working copy of the table tile's sparkline config; persisted in
  // handleSave under the table-guarded block. Isolated so it rebases
  // cleanly against the parallel #919 conditional-format edit.
  let sparklineEnabled = $state<boolean>(untrack(() => tile.sparkline?.enabled ?? false));
  let sparklineType = $state<SparklineType>(untrack(() => tile.sparkline?.type ?? "line"));

  // ── Issue #907: anomaly detection (time-series chart tiles only) ──
  // Working copy of the chart tile's anomaly config; persisted on save.
  // Only meaningful for line / bar / area charts — the panel is shown
  // conditionally in the template.
  const ANOMALY_CHART_KINDS = new Set(["line", "bar", "area"]);
  let anomalyEnabled = $state<boolean>(untrack(() => tile.anomaly?.enabled ?? false));
  // Only offer / accept methods the backend implements. A tile persisted with a
  // stub method (stl, saiku#908) coerces back to a working default so it can't
  // silently keep 400ing after this dropdown dropped the option.
  let anomalyMethod = $state<AnomalyMethodConfig>(
    untrack(() => (tile.anomaly?.method === "mad" ? "mad" : "zscore")),
  );
  let anomalyThreshold = $state<number | null>(untrack(() => tile.anomaly?.threshold ?? null));
  let anomalyTimeAxis = $state<string>(untrack(() => tile.anomaly?.timeAxis ?? ""));
  // Default threshold shown as the placeholder, tracking the chosen method.
  let anomalyDefaultThreshold = $derived(anomalyMethod === "mad" ? 3.5 : 3.0);
  // ── Issue #908: forecast (time-series chart tiles only) ──
  // Working copy of the chart tile's forecast config; persisted on save.
  let forecastEnabled = $state<boolean>(untrack(() => tile.forecast?.enabled ?? false));
  // Only ETS is implemented; arima/prophet are stubs (saiku#908), so the dropdown
  // offers ets alone and any persisted stub method coerces to ets — a saved tile
  // can't keep 400ing. Widen this back to a passthrough when more methods land.
  let forecastMethod = $state<ForecastMethodConfig>("ets");
  let forecastHorizon = $state<number>(untrack(() => tile.forecast?.horizon ?? 6));
  let forecastConfidence = $state<number>(untrack(() => tile.forecast?.confidence ?? 0.95));
  let forecastTimeAxis = $state<string>(untrack(() => tile.forecast?.timeAxis ?? ""));
  // ── Issue #1085: brush cross-filter (brushable cartesian chart tiles only) ──
  let brushCrossFilterEnabled = $state<boolean>(untrack(() => tile.brushCrossFilter?.enabled ?? false));
  // Query source — "reference" picks a saved .saiku from the repo,
  // "inline" pastes an AiQueryRequest body. Default to "reference" so
  // non-technical authors aren't dropped into a JSON textarea on a
  // fresh tile.
  let queryMode = $state<"reference" | "inline">(
    untrack(() => (tile.query?.kind === "inline" ? "inline" : "reference")),
  );
  let referencePath = $state<string>(untrack(() => (tile.query?.kind === "reference" ? tile.query.path : "")));

  // ── App Builder Phase 2 (saiku#1441): custom tile renderer config ──
  // A queryable custom renderer (e.g. echarts-option) binds a cube + query the
  // same way chart/table tiles do; wantsQuery gates the shared query-source UI.
  // Read the stable `tile` prop under untrack (the modal never re-renders for
  // the same tile — matches the one-shot init pattern used throughout).
  const isEchartsOptionTile = untrack(
    () => tile.type === "custom" && tile.custom?.renderer === "echarts-option",
  );
  const isGraphTile = untrack(
    () => tile.type === "custom" && tile.custom?.renderer === "graph",
  );
  const isRankedListTile = untrack(
    () => tile.type === "custom" && tile.custom?.renderer === "ranked-list",
  );
  // App Builder Phase 2, Task 7 (saiku#1441): the `plugin` renderer runs
  // arbitrary author HTML/JS in a locked-down iframe. Its editor is just a
  // textarea for the self-contained plugin HTML.
  const isPluginTile = untrack(
    () => tile.type === "custom" && tile.custom?.renderer === "plugin",
  );
  const customQueryable = untrack(() =>
    tile.type === "custom" && !!tile.custom
      ? (getTileRenderer(tile.custom.renderer)?.isQueryable ?? false)
      : false,
  );
  const wantsQuery = untrack(
    () => tile.type === "chart" || tile.type === "table" || customQueryable,
  );
  // The author's declarative ECharts option (JSON textarea), seeded from the
  // saved tile. Live-validated against the safe subset below.
  let customOptionsJson = $state<string>(
    untrack(() =>
      tile.custom?.options && Object.keys(tile.custom.options).length > 0
        ? JSON.stringify(tile.custom.options, null, 2)
        : "",
    ),
  );
  // App Builder (saiku#1441): echarts-option display toggles — a live
  // Trend/Breakdown segmented control + an emphasised last data point.
  let trendBreakdown = $state<boolean>(untrack(() => !!tile.custom?.trendBreakdown));
  let emphasizeLast = $state<boolean>(untrack(() => !!tile.custom?.emphasizeLast));
  // Declarative value-axis format (see valueAxisFormat.ts for why it can't
  // simply live in the author's option).
  let valueFormat = $state<string>(untrack(() => tile.custom?.valueFormat ?? ""));
  // Live validation feedback for the option editor — parse + safe-subset check.
  let customOptionsValidation = $derived.by(() => {
    const txt = customOptionsJson.trim();
    if (!txt) return { ok: false as const, error: "Paste an ECharts option object." };
    let parsed: unknown;
    try {
      parsed = JSON.parse(txt);
    } catch (e) {
      return { ok: false as const, error: `JSON parse error: ${(e as Error).message}` };
    }
    return validateEchartsOption(parsed);
  });

  // ── App Builder Phase 2 (saiku#1441): `graph` renderer column mapping ──
  // A declarative mapping of query columns → graph endpoints (NO code). Seeded
  // from the saved tile.custom.options, live-validated below, persisted on Save.
  const savedGraph = untrack(() => (tile.custom?.options ?? {}) as Record<string, unknown>);
  let graphIdCol = $state<string>(untrack(() => String(savedGraph.idCol ?? "")));
  let graphLabelCol = $state<string>(untrack(() => String(savedGraph.labelCol ?? "")));
  let graphSourceCol = $state<string>(untrack(() => String(savedGraph.sourceCol ?? "")));
  let graphTargetCol = $state<string>(untrack(() => String(savedGraph.targetCol ?? "")));
  let graphValueCol = $state<string>(untrack(() => String(savedGraph.valueCol ?? "")));
  let graphLayout = $state<GraphLayout>(
    untrack(() => (savedGraph.layout === "circular" ? "circular" : "force")),
  );
  // Assemble the current form into an options object (empty optionals dropped).
  function graphOptionsFromForm(): Record<string, unknown> {
    const opts: Record<string, unknown> = {
      idCol: graphIdCol.trim(),
      sourceCol: graphSourceCol.trim(),
      targetCol: graphTargetCol.trim(),
      layout: graphLayout,
    };
    if (graphLabelCol.trim()) opts.labelCol = graphLabelCol.trim();
    if (graphValueCol.trim()) opts.valueCol = graphValueCol.trim();
    return opts;
  }
  // Live validation feedback for the graph mapping.
  let graphConfigValidation = $derived.by(() =>
    validateGraphConfig({
      idCol: graphIdCol.trim(),
      sourceCol: graphSourceCol.trim(),
      targetCol: graphTargetCol.trim(),
      layout: graphLayout,
      labelCol: graphLabelCol.trim() || undefined,
      valueCol: graphValueCol.trim() || undefined,
    }),
  );

  // ── `ranked-list` renderer form (saiku#1441) ────────────────────────
  // Every knob the FoodMart Ops "Movers" card needed, as fields: which columns
  // to read, how many rows, the ordering, whether values are coloured by sign,
  // and the muted subtitle that used to be a CSS ::after pseudo-element.
  const savedRanked = untrack(() => (tile.custom?.options ?? {}) as Record<string, unknown>);
  let rankedLabelCol = $state<string>(untrack(() => String(savedRanked.labelColumn ?? "")));
  let rankedValueCol = $state<string>(untrack(() => String(savedRanked.valueColumn ?? "")));
  let rankedSubtitle = $state<string>(untrack(() => String(savedRanked.subtitle ?? "")));
  let rankedLimit = $state<number>(
    untrack(() =>
      typeof savedRanked.limit === "number" ? savedRanked.limit : DEFAULT_LIMIT,
    ),
  );
  let rankedSort = $state<RankedSort>(
    untrack(() =>
      savedRanked.sort === "desc" || savedRanked.sort === "asc" ? savedRanked.sort : "none",
    ),
  );
  let rankedTone = $state<RankedTone>(
    untrack(() => (savedRanked.tone === "none" ? "none" : "signed")),
  );
  let rankedShowRank = $state<boolean>(untrack(() => savedRanked.showRank !== false));
  let rankedValueFormat = $state<string>(
    untrack(() => String(savedRanked.valueFormat ?? "")),
  );

  /** Assemble the form into an options object (empty optionals dropped, so an
   *  unset column stays inferred rather than pinned to ""). */
  function rankedOptionsFromForm(): Record<string, unknown> {
    const opts: Record<string, unknown> = {
      limit: rankedLimit,
      sort: rankedSort,
      tone: rankedTone,
      showRank: rankedShowRank,
    };
    if (rankedLabelCol.trim()) opts.labelColumn = rankedLabelCol.trim();
    if (rankedValueCol.trim()) opts.valueColumn = rankedValueCol.trim();
    if (rankedSubtitle.trim()) opts.subtitle = rankedSubtitle.trim();
    if (rankedValueFormat.trim()) opts.valueFormat = rankedValueFormat.trim();
    return opts;
  }
  let rankedValidation = $derived.by(() => validateRankedListConfig(rankedOptionsFromForm()));

  // ── App Builder Phase 2 (saiku#1441): `plugin` renderer picker ──
  // SECURITY: the author only PICKS an admin-installed plugin by its slug id.
  // Raw HTML can NEVER ride tile config (the arbitrary-JS exfil hole — an author
  // could otherwise run JS that navigates the frame to exfiltrate the tile's
  // data); the markup is served only from the admin registry at render time. So
  // the editor is a picker over GET /rest/saiku/api/tile-plugins, not a textarea.
  let selectedPluginId = $state<string>(
    untrack(() => (typeof tile.custom?.options?.pluginId === "string" ? tile.custom.options.pluginId : "")),
  );
  let installedPlugins = $state<TilePluginSummary[]>([]);
  let pluginsLoading = $state(false);
  let pluginsError = $state<string | null>(null);

  // ── Issue #912: inline visual query editor ──────────────────────────
  // For chart / table tiles, "Edit query visually" mounts the workspace
  // QueryCanvas inside this modal (embedded mode). The canvas + drop zones
  // drive the SINGLETON query store, so before seeding it we stash the
  // workspace's live query + cube snapshot and restore them when the embed
  // closes — the dashboard author's open workspace tab must survive an
  // inline edit untouched. On commit, the built model is converted to the
  // tile's inline AiQueryRequest body (no separate Save inside the embed).
  let queryEditorOpen = $state(false);
  let queryEditorError = $state<string | null>(null);
  // Saved workspace store state, restored on close.
  let savedQuerySnapshot: QueryStateSnapshot | null = null;
  // Selection snapshot around the tile-editor lifecycle. SelectionSnapshot is the
  // discriminated union that carries either an MDX cube or an Ossie selection — dashboards
  // stay OLAP-only for MVP but the store returns the wider type, so track it here too.
  let savedCube: import("$lib/stores/selection.svelte").SelectionSnapshot | null = null;
  // The body the embed was seeded from — passed back to thinQueryToBody so
  // unmanaged passthrough fields (order / limit / …) survive the round-trip.
  let seedBody: InlineQueryBody | null = null;

  /** Resolve the tile's CubeRef to a live SaikuCube via the loaded
   *  connection tree (loading it first if needed). Returns null when the
   *  cube can't be found — the embed surfaces a hint instead of mounting
   *  a canvas with no cube. */
  async function resolveTileCube(): Promise<SaikuCube | null> {
    if (!cube) return null;
    if (!datasources.loaded && session.current) {
      try {
        await datasources.load(session.current.username);
      } catch {
        return null;
      }
    }
    return findCubeByRef(datasources.connections, {
      connection: cube.connectionName,
      catalog: cube.catalog,
      schema: cube.schema,
      name: cube.cubeName,
    });
  }

  async function openQueryEditor(): Promise<void> {
    queryEditorError = null;
    const resolved = await resolveTileCube();
    if (!resolved) {
      queryEditorError = i18n.t("tileEditor.query.pickCubeFirst");
      return;
    }
    // Stash the workspace's live state so we can put it back on close.
    // snapshotAndReset cancels any in-flight workspace query too.
    savedQuerySnapshot = query.snapshotAndReset();
    savedCube = selection.snapshot();

    // Seed the singleton store from the tile's current inline body (if any).
    seedBody =
      tile.query?.kind === "inline" ? (tile.query.body as InlineQueryBody) : null;
    const seeded = bodyToThinQuery(seedBody, resolved);
    query.hydrate(seeded);
    selection.restore(resolved);
    if (query.hasRunnableShape()) void query.run();
    queryEditorOpen = true;
  }

  /** Read the embed's built model back into the tile's inline body fields
   *  and switch the tile to inline mode. Returns false (with an inline
   *  error) when the built query isn't runnable, so the caller can keep
   *  the embed open. */
  function commitQueryEditor(): boolean {
    if (!query.current) return false;
    if (!bodyIsRunnable(query.current)) {
      queryEditorError = i18n.t("tileEditor.query.notRunnable");
      return false;
    }
    const built = thinQueryToBody(query.current, seedBody);
    inlineBodyJson = JSON.stringify(built, null, 2);
    queryMode = "inline";
    bodyError = null;
    return true;
  }

  /** Restore the workspace store state captured in openQueryEditor and
   *  close the embed. Safe to call when nothing was captured. */
  function teardownQueryEditor(): void {
    if (savedQuerySnapshot) {
      query.restore(savedQuerySnapshot);
      savedQuerySnapshot = null;
    }
    selection.restore(savedCube);
    savedCube = null;
    queryEditorOpen = false;
  }

  function applyQueryEditor(): void {
    if (commitQueryEditor()) teardownQueryEditor();
  }

  function cancelQueryEditor(): void {
    queryEditorError = null;
    teardownQueryEditor();
  }
  // ── end issue #912 ──

  // Cube catalogue + saved-query catalogue, fetched once on open.
  let cubes = $state<AiCubeSummary[]>([]);
  let cubesError = $state<string | null>(null);
  let cubesLoading = $state(false);
  let savedQueries = $state<RepositoryNode[]>([]);
  let savedQueriesError = $state<string | null>(null);
  let savedQueriesLoading = $state(false);

  onMount(async () => {
    if (tile.type === "text") return; // text tiles don't need the catalogue
    // Plugin tiles: load the installed-plugin catalogue for the picker.
    if (isPluginTile) {
      pluginsLoading = true;
      listTilePlugins()
        .then((list) => {
          installedPlugins = list;
        })
        .catch((e: unknown) => {
          pluginsError = e instanceof Error ? e.message : String(e);
        })
        .finally(() => {
          pluginsLoading = false;
        });
    }
    cubesLoading = true;
    const needSavedQueries = wantsQuery;
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

  // Filter and KPI tiles need the cube's schema — filters for the
  // dim/hier/level dropdowns, KPI for the measure picker and (when
  // prior-period or sparkline are on) the time-level picker. Prime
  // whenever a cube is selected so the dropdowns aren't empty.
  $effect(() => {
    if (tile.type !== "filter" && tile.type !== "kpi") return;
    if (!cube) return;
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

  // KPI tile derivers — keyed on kpiConfig.timeLevel.* so they're
  // independent of the filter-tile dropdowns above. Same loose-schema
  // narrowing as the filter ones.
  let measureOptions = $derived(() => {
    const s = schema() as
      | { measures?: Record<string, { name?: string; displayName?: string | null; visible?: boolean }> }
      | null;
    if (!s?.measures) return [] as { name: string; label: string }[];
    const out: { name: string; label: string }[] = [];
    for (const m of Object.values(s.measures)) {
      if (m?.visible === false) continue;
      const name = m?.name;
      if (!name) continue;
      out.push({ name, label: m?.displayName ?? name });
    }
    return out;
  });

  let kpiHierarchyOptions = $derived(() => {
    const s = schema() as
      | { dimensions?: Record<string, { name: string; hierarchies?: Record<string, { name: string }> }> }
      | null;
    if (!s?.dimensions) return [] as string[];
    for (const d of Object.values(s.dimensions)) {
      if (d.name === kpiConfig.timeLevel?.dimension && d.hierarchies) {
        return Object.values(d.hierarchies).map((h) => h.name);
      }
    }
    return [];
  });

  let kpiLevelOptions = $derived(() => {
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
      if (d.name !== kpiConfig.timeLevel?.dimension) continue;
      if (!d.hierarchies) return [];
      for (const h of Object.values(d.hierarchies)) {
        if (h.name === kpiConfig.timeLevel?.hierarchy && h.levels) {
          return Object.values(h.levels).map((l) => l.name);
        }
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

  // #1077: measure names for the chart-options editor's per-series axis picker.
  // Only inline tiles expose their measures statically; reference tiles resolve
  // at fetch time, so the picker hides (empty → ChartEditorModal omits it).
  let chartSeriesNames = $derived(() => {
    if (queryMode !== "inline") return [] as string[];
    try {
      const b = JSON.parse(inlineBodyJson) as { measures?: Array<{ name?: string }> };
      return Array.isArray(b?.measures)
        ? b.measures.map((m) => m?.name).filter((n): n is string => !!n)
        : [];
    } catch {
      return [];
    }
  });

  function cubeKey(c: CubeRef): string {
    return `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;
  }

  function handleCubeChange(e: Event): void {
    const v = (e.target as HTMLSelectElement).value;
    const picked = cubes.find((c) => cubeKey(c) === v);
    // Project the AiCubeSummary down to the four CubeRef fields the
    // server's AiCubeRef accepts. Extra summary fields (cubeCaption,
    // defaultMeasure, measureCount) get serialised onto the dashboard
    // JSON otherwise, and Jackson rejects them with
    // "Unknown field 'cubeCaption' on AiCubeRef" on save.
    cube = picked
      ? {
          connectionName: picked.connectionName,
          catalog: picked.catalog,
          schema: picked.schema,
          cubeName: picked.cubeName,
        }
      : null;
    // Clear stale filter target when the cube changes — the level
    // names won't apply to the new cube's schema.
    if (tile.type === "filter") {
      filterTarget = { dimension: "", hierarchy: "", level: "" };
    }
    // Same reasoning for KPI: measure + time-level names are cube-scoped.
    if (tile.type === "kpi") {
      kpiConfig.measure = "";
      kpiConfig.measureCaption = "";
      kpiConfig.timeLevel = { dimension: "", hierarchy: "", level: "" };
    }
  }

  let positionError = $state<string | null>(null);

  // saiku#1229: addConditionalRule / removeConditionalRule + CONDITIONAL_TYPES
  // / CONDITIONAL_MODES moved into TileEditorTableConditional.svelte. handleSave
  // below still needs ruleUsesThresholds when persisting the saved payload, so
  // it stays here as a parent-local helper.
  /** A rule needs explicit thresholds for background; font/icon may run in
   *  sign mode (no thresholds). bar never uses thresholds. */
  function ruleUsesThresholds(r: ConditionalFormatRule): boolean {
    return r.type === "background" || r.type === "font" || r.type === "icon";
  }

  async function handleSave(): Promise<void> {
    bodyError = null;
    positionError = null;

    // #912: if the visual query editor is still open, commit its built
    // query into the inline body first. If it isn't runnable, keep the
    // modal open so the author can finish the query (commitQueryEditor
    // surfaces the inline error).
    if (queryEditorOpen) {
      if (!commitQueryEditor()) return;
      teardownQueryEditor();
    }

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
      // #1077: only persist chart options if the user actually edited them, so
      // saving an untouched legacy chart tile preserves its existing options
      // (and look) rather than stamping defaults onto it.
      if (chartOptionsTouched) {
        patch.chartOptions = chartOptions;
      }
      // ── Issue #907: persist anomaly config. Store undefined when disabled
      // so a fresh / opted-out tile keeps tidy JSON. Threshold is omitted when
      // left blank (server applies the method default). ──
      patch.anomaly =
        anomalyEnabled && ANOMALY_CHART_KINDS.has(chartType)
          ? {
              enabled: true,
              method: anomalyMethod,
              threshold:
                anomalyThreshold != null && Number.isFinite(anomalyThreshold) && anomalyThreshold > 0
                  ? anomalyThreshold
                  : undefined,
              timeAxis: anomalyTimeAxis.trim() || undefined,
            }
          : undefined;
      // ── Issue #908: persist forecast config (undefined when disabled). ──
      patch.forecast =
        forecastEnabled && ANOMALY_CHART_KINDS.has(chartType)
          ? {
              enabled: true,
              method: forecastMethod,
              horizon:
                Number.isFinite(forecastHorizon) && forecastHorizon >= 1
                  ? Math.round(forecastHorizon)
                  : undefined,
              confidence:
                Number.isFinite(forecastConfidence) && forecastConfidence > 0 && forecastConfidence < 1
                  ? forecastConfidence
                  : undefined,
              timeAxis: forecastTimeAxis.trim() || undefined,
            }
          : undefined;
      // ── Issue #1085: persist brush cross-filter config (undefined when off
      // or on a non-brushable chart type, keeping tidy JSON). ──
      patch.brushCrossFilter =
        brushCrossFilterEnabled && BRUSHABLE_CHART_TYPES.has(chartType) ? { enabled: true } : undefined;
    }

    // App Builder Phase 2 (saiku#1441): persist the echarts-option config.
    // Validate the option again on save (defence in depth) and store the
    // SAFE, normalised value — never the raw author text.
    if (isEchartsOptionTile && tile.custom) {
      const txt = customOptionsJson.trim();
      let options: Record<string, unknown> = {};
      if (txt) {
        let parsed: unknown;
        try {
          parsed = JSON.parse(txt);
        } catch (e) {
          bodyError = `ECharts option JSON parse error: ${(e as Error).message}`;
          return;
        }
        const v = validateEchartsOption(parsed);
        if (!v.ok) {
          bodyError = `Invalid ECharts option: ${v.error}`;
          return;
        }
        options = v.value;
      }
      patch.custom = {
        renderer: tile.custom.renderer,
        options,
        trendBreakdown,
        emphasizeLast,
        // Dropped when blank so clearing the field restores ECharts' defaults
        // rather than persisting an empty pattern.
        ...(valueFormat.trim() ? { valueFormat: valueFormat.trim() } : {}),
      };
    }

    // App Builder Phase 2 (saiku#1441): persist the graph column mapping.
    // Validate again on save (defence in depth) and store the SAFE, normalised
    // value — never raw form strings.
    if (isGraphTile && tile.custom) {
      const v = validateGraphConfig(graphOptionsFromForm());
      if (!v.ok) {
        bodyError = `Invalid graph config: ${v.error}`;
        return;
      }
      patch.custom = {
        renderer: tile.custom.renderer,
        options: v.value as unknown as Record<string, unknown>,
      };
    }

    // Persist the ranked-list config. Validate again on save (defence in depth)
    // and store the normalised value, never raw form strings.
    if (isRankedListTile && tile.custom) {
      const v = validateRankedListConfig(rankedOptionsFromForm());
      if (!v.ok) {
        bodyError = `Invalid ranked list config: ${v.error}`;
        return;
      }
      patch.custom = { renderer: tile.custom.renderer, options: v.value };
    }

    // App Builder Phase 2 (saiku#1441): persist the selected plugin ID ONLY. Raw
    // HTML never rides tile config — the markup is served from the admin registry
    // at render time. Any legacy `html` option is dropped on save so a resaved
    // tile can never carry author markup forward.
    if (isPluginTile && tile.custom) {
      const existing = { ...(tile.custom.options ?? {}) } as Record<string, unknown>;
      delete existing.html;
      const pid = selectedPluginId.trim();
      if (pid) existing.pluginId = pid;
      else delete existing.pluginId;
      patch.custom = { renderer: tile.custom.renderer, options: existing };
    }

    if (wantsQuery) {
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
      // issue #922: persist cascade config only for the cascading variant;
      // clear it otherwise so the saved JSON stays tidy across widget swaps.
      patch.cascading =
        widget === "cascading-select"
          ? {
              startLevel: cascadeStartLevel || undefined,
              depth: cascadeDepth,
            }
          : undefined;
    }

    if (tile.type === "kpi") {
      // Drop the timeLevel placeholder when none of the time-aware
      // features need it — keeps the saved JSON tidy.
      const needsTime =
        kpiConfig.comparison === "prior-period" ||
        kpiConfig.comparison === "year-over-year" ||
        kpiConfig.sparkline === true;
      const tl = kpiConfig.timeLevel;
      const cleaned: KpiConfig = {
        ...kpiConfig,
        timeLevel:
          needsTime && tl && tl.dimension && tl.hierarchy && tl.level ? tl : undefined,
        // 0 is the default — omit it so an untouched tile's JSON stays empty,
        // and so the field can't linger on a tile that no longer has a series.
        partialTrailing:
          needsTime && Number(kpiConfig.partialTrailing) > 0
            ? Math.floor(Number(kpiConfig.partialTrailing))
            : undefined,
        // Drop empty thresholds object so the JSON diff is empty when
        // the analyst doesn't touch the threshold inputs.
        thresholds:
          kpiConfig.thresholds &&
          (kpiConfig.thresholds.red != null ||
            kpiConfig.thresholds.yellow != null ||
            kpiConfig.thresholds.green != null)
            ? kpiConfig.thresholds
            : undefined,
        target: kpiConfig.comparison === "target" ? kpiConfig.target : undefined,
        customFormat: kpiConfig.format === "custom" ? kpiConfig.customFormat : undefined,
      };
      patch.kpi = cleaned;
    }

    // ── Issue #919: persist conditional-formatting rules (table only) ──
    // Drop incomplete rules (no column) and normalise the threshold
    // fields away for sign-mode font/icon and for bar. Persist undefined
    // when no usable rule remains so the saved JSON stays tidy.
    if (tile.type === "table") {
      const cleaned = conditionalFormat
        .filter((r) => r.column.trim() !== "")
        .map((r) => {
          const out: ConditionalFormatRule = {
            column: r.column.trim(),
            type: r.type,
            thresholdMode: r.thresholdMode,
          };
          if (ruleUsesThresholds(r)) {
            if (r.lowThreshold != null) out.lowThreshold = r.lowThreshold;
            if (r.highThreshold != null) out.highThreshold = r.highThreshold;
          }
          if (r.colors && (r.colors.low || r.colors.mid || r.colors.high)) {
            out.colors = { ...r.colors };
          }
          if (r.type === "bar" && r.barColor) out.barColor = r.barColor;
          return out;
        });
      patch.conditionalFormat = cleaned.length > 0 ? cleaned : undefined;

      // ── Issue #920: persist sparkline column config. Only store when
      // enabled so a fresh / opted-out tile keeps tidy JSON. ──
      patch.sparkline = sparklineEnabled ? { enabled: true, type: sparklineType } : undefined;
    }

    // ── Issue #931: persist the auto-refresh interval (chart / table / kpi).
    // Normalise so an off-list value never reaches the saved JSON; store
    // undefined for "off" so the diff is empty when untouched. ──
    if (tile.type === "chart" || tile.type === "table" || tile.type === "kpi") {
      const mins = normaliseInterval(refreshInterval);
      patch.refreshInterval = mins > 0 ? mins : undefined;
    }

    // ── Issue #918: image tile. URL mode persists the typed URL; upload mode
    // POSTs the picked file to the (auth + content-type + size + path-traversal
    // hardened) endpoint on save and persists the returned download path,
    // keeping the existing path when no new file was picked. Upload failures
    // surface in the modal and abort the save. ──
    if (tile.type === "image") {
      let src = imageExistingSrc;
      if (imageMode === "url") {
        src = imageUrl.trim();
      } else if (imageFile) {
        imageUploading = true;
        try {
          src = await uploadImageAsset(tile.id, imageFile);
        } catch (e) {
          bodyError = `Image upload failed: ${(e as Error).message}`;
          imageUploading = false;
          return;
        }
        imageUploading = false;
      }
      patch.image = {
        source: imageMode,
        src: src || undefined,
        fit: imageFit,
        caption: imageCaption.trim() || undefined,
        alt: imageAlt.trim() || undefined,
      };
    }

    // Merge the patch into the repositioned source tile, then commit
    // the whole cascade in one go.
    const tiles = reposition.tiles!.map((t) => (t.id === tile.id ? { ...t, ...patch } : t));
    dashboardStore.replaceTiles(tiles);
    onClose();
  }

  /** Close the modal without saving. #912: tear the visual query editor
   *  down first so the workspace's live query store is restored even when
   *  the author cancels mid-edit. */
  function handleClose(): void {
    if (queryEditorOpen || savedQuerySnapshot) teardownQueryEditor();
    onClose();
  }
</script>

<div
  class="modal-backdrop"
  role="presentation"
  onclick={(e) => {
    if (e.target === e.currentTarget) handleClose();
  }}
  onkeydown={(e) => {
    if (e.key === "Escape") handleClose();
  }}
>
  <div class="modal" class:modal--wide={queryEditorOpen} role="dialog" aria-label="Edit tile">
    <header class="modal-header">
      <h2>Edit {tileTypeLabel} tile</h2>
      <button type="button" class="border-0 bg-transparent text-xl cursor-pointer text-fg-muted" aria-label="Close" onclick={handleClose}>×</button>
    </header>
    <div class="p-4 overflow-auto flex flex-col gap-3">
      <label class="field">
        <span class="field__label">Title</span>
        <input class="field__input" type="text" bind:value={title} placeholder={`Untitled ${tile.type}`} />
      </label>

      <fieldset class="size">
        <legend>Position &amp; size (12-col grid)</legend>
        <label class="field flex-1">
          <span class="field__label">x</span>
          <input class="field__input" type="number" min="0" max="11" bind:value={tileX} />
        </label>
        <label class="field flex-1">
          <span class="field__label">y</span>
          <input class="field__input" type="number" min="0" bind:value={tileY} />
        </label>
        <label class="field flex-1">
          <span class="field__label">w</span>
          <input class="field__input" type="number" min="1" max="12" bind:value={tileW} />
        </label>
        <label class="field flex-1">
          <span class="field__label">h</span>
          <input class="field__input" type="number" min="1" bind:value={tileH} />
        </label>
      </fieldset>
      {#if positionError}
        <div class="position-error" role="alert">{positionError}</div>
      {/if}

      {#if tile.type === "text"}
        <TileEditorText bind:text />
      {/if}

      {#if tile.type === "image"}
        <!-- saiku#1229: image tile editor extracted to TileEditorImage.
             Parent stays the persistence boundary (handleSave still reads
             imageFile / imageUrl / etc to build the ImageConfig payload). -->
        <TileEditorImage
          bind:imageMode
          bind:imageUrl
          imageExistingSrc={imageExistingSrc}
          bind:imageFit
          bind:imageCaption
          bind:imageAlt
          bind:imageFile
          onFileChosen={() => { bodyError = null; }}
        />
      {/if}

      {#if tile.type !== "text" && tile.type !== "image"}
        <label class="field">
          <span class="field__label">Cube</span>
          {#if cubesLoading}
            <span class="hint">Loading…</span>
          {:else if cubesError}
            <span class="hint error">{cubesError}</span>
          {:else}
            <select class="field__input" onchange={handleCubeChange} disabled={cubes.length === 0}>
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
        <TileEditorChart
          bind:chartType
          {chartOptionsTouched}
          onOpenChartOptions={() => (chartOptionsOpen = true)}
        />

        <!-- ── Issue #907: anomaly detection (time-series charts only) ── -->
        {#if ANOMALY_CHART_KINDS.has(chartType)}
          <fieldset class="anomaly">
            <legend>{i18n.t("dashboard.anomaly.legend", "Anomaly detection")}</legend>
            <label class="checkbox">
              <input type="checkbox" bind:checked={anomalyEnabled} />
              <span>{i18n.t("dashboard.anomaly.enable", "Detect anomalies")}</span>
            </label>
            {#if anomalyEnabled}
              <label class="field">
                <span class="field__label">{i18n.t("dashboard.anomaly.method", "Method")}</span>
                <!-- Only methods the backend actually implements are offered. STL is a
                     registered-but-throwing stub (saiku#908) — surfacing it here let a user
                     pick a method that 400s. Re-add when StlAnomalyDetector is implemented. -->
                <select class="field__input" bind:value={anomalyMethod}>
                  <option value="zscore">{i18n.t("dashboard.anomaly.method.zscore", "Z-score")}</option>
                  <option value="mad">{i18n.t("dashboard.anomaly.method.mad", "MAD (robust)")}</option>
                </select>
              </label>
              <label class="field">
                <span class="field__label">{i18n.t("dashboard.anomaly.threshold", "Threshold")}</span>
                <input class="field__input"
                  type="number"
                  min="0"
                  step="0.1"
                  bind:value={anomalyThreshold}
                  placeholder={String(anomalyDefaultThreshold)}
                />
                <span class="hint">{i18n.t("dashboard.anomaly.threshold.hint", "Sigmas from expectation. Blank uses the method default.")}</span>
              </label>
              <label class="field">
                <span class="field__label">{i18n.t("dashboard.anomaly.timeAxis", "Time axis")}</span>
                <input class="field__input"
                  type="text"
                  bind:value={anomalyTimeAxis}
                  placeholder={i18n.t("dashboard.anomaly.timeAxis.placeholder", "Defaults to the first row axis")}
                />
                <span class="hint">{i18n.t("dashboard.anomaly.timeAxis.hint", "Unique name of the time level, e.g. [Time].[Time].[Month].")}</span>
              </label>
            {/if}
          </fieldset>

          <!-- ── Issue #908: forecast (time-series charts only) ── -->
          <fieldset class="anomaly">
            <legend>{i18n.t("dashboard.forecast.legend", "Forecast")}</legend>
            <label class="checkbox">
              <input type="checkbox" bind:checked={forecastEnabled} />
              <span>{i18n.t("dashboard.forecast.enable", "Show forecast")}</span>
            </label>
            {#if forecastEnabled}
              <label class="field">
                <span class="field__label">{i18n.t("dashboard.forecast.method", "Method")}</span>
                <!-- Only ETS is implemented; ARIMA + Prophet are registered-but-throwing
                     stubs (saiku#908). Offering them let a user pick a method that 400s.
                     Re-add when ArimaForecaster / ProphetForecaster are implemented. -->
                <select class="field__input" bind:value={forecastMethod}>
                  <option value="ets">{i18n.t("dashboard.forecast.method.ets", "Exponential smoothing")}</option>
                </select>
              </label>
              <label class="field">
                <span class="field__label">{i18n.t("dashboard.forecast.horizon", "Horizon (points)")}</span>
                <input class="field__input" type="number" min="1" max="365" step="1" bind:value={forecastHorizon} />
                <span class="hint"
                  >{i18n.t("dashboard.forecast.horizon.hint", "How many future points to project.")}</span
                >
              </label>
              <label class="field">
                <span class="field__label">{i18n.t("dashboard.forecast.confidence", "Confidence")}</span>
                <input class="field__input" type="number" min="0.5" max="0.999" step="0.01" bind:value={forecastConfidence} />
                <span class="hint"
                  >{i18n.t("dashboard.forecast.confidence.hint", "Prediction-interval level, e.g. 0.95.")}</span
                >
              </label>
              <label class="field">
                <span class="field__label">{i18n.t("dashboard.forecast.timeAxis", "Time axis")}</span>
                <input class="field__input"
                  type="text"
                  bind:value={forecastTimeAxis}
                  placeholder={i18n.t("dashboard.forecast.timeAxis.placeholder", "Defaults to the first row axis")}
                />
                <span class="hint"
                  >{i18n.t("dashboard.forecast.timeAxis.hint", "Unique name of the time level, e.g. [Time].[Time].[Month].")}</span
                >
              </label>
            {/if}
          </fieldset>
        {/if}

        <!-- ── Issue #1085: brush cross-filter (brushable cartesian charts) ── -->
        {#if BRUSHABLE_CHART_TYPES.has(chartType)}
          <fieldset class="anomaly">
            <legend>{i18n.t("dashboard.crossfilter.legend", "Cross-filter")}</legend>
            <label class="checkbox">
              <input type="checkbox" bind:checked={brushCrossFilterEnabled} />
              <span>{i18n.t("dashboard.crossfilter.enable", "Emit cross-filter on brush")}</span>
            </label>
            <span class="hint"
              >{i18n.t(
                "dashboard.crossfilter.hint",
                "Drag to select a range on this chart; the other tiles filter to those members. This tile keeps full context — Esc clears it.",
              )}</span
            >
          </fieldset>
        {/if}
      {/if}

      <!-- App Builder Phase 2 (saiku#1441): echarts-option renderer editor.
           A declarative ECharts option (NO code) with live safe-subset
           validation. Persisted into tile.custom.options on Save. -->
      {#if isEchartsOptionTile}
        <label class="field">
          <span class="field__label">ECharts option (JSON)</span>
          <textarea
            bind:value={customOptionsJson}
            rows="12"
            spellcheck="false"
            class="field__input json"
            placeholder={JSON.stringify(
              { title: { text: "My chart" }, xAxis: { type: "category" }, yAxis: { type: "value" }, series: [{ type: "bar" }] },
              null,
              2,
            )}
          ></textarea>
          {#if customOptionsValidation.ok}
            <span class="hint ok">✓ Valid option — query data is merged into the series on render.</span>
          {:else}
            <span class="hint error">{customOptionsValidation.error}</span>
          {/if}
          <span class="hint">
            Declarative ECharts option only — functions and remote URLs are rejected.
            Categories + series data come from the tile's query below.
          </span>
        </label>
        <label class="checkbox">
          <input type="checkbox" bind:checked={trendBreakdown} />
          <span>Trend / Breakdown toggle (swap line ↔ bars over the same data)</span>
        </label>
        <label class="checkbox">
          <input type="checkbox" bind:checked={emphasizeLast} />
          <span>Emphasise last point (accent “current period” marker)</span>
        </label>
        <label class="field">
          <span>Value axis format <span class="hint">(optional)</span></span>
          <input type="text" bind:value={valueFormat} spellcheck="false" placeholder="e.g. $c0" />
        </label>
        <span class="hint">
          $c0 / €c1 for compact currency ($149K), $2 for plain currency, 1% for
          percent, 0 for fractional digits. Numeric axis formatting needs a
          function, which author options can't contain — so it's stated here and
          compiled at render time.
        </span>
      {/if}

      <!-- App Builder Phase 2 (saiku#1441): graph renderer editor. A declarative
           mapping of the query's columns → graph nodes + edges (NO code).
           Persisted into tile.custom.options on Save. -->
      {#if isGraphTile}
        <fieldset class="graph-map">
          <legend>Graph columns</legend>
          <label class="field">
            <span class="field__label">Source column</span>
            <input class="field__input" type="text" bind:value={graphSourceCol} spellcheck="false" placeholder="e.g. Parent" />
          </label>
          <label class="field">
            <span class="field__label">Target column</span>
            <input class="field__input" type="text" bind:value={graphTargetCol} spellcheck="false" placeholder="e.g. Child" />
          </label>
          <label class="field">
            <span class="field__label">Id column</span>
            <input class="field__input" type="text" bind:value={graphIdCol} spellcheck="false" placeholder="node id (often = source)" />
          </label>
          <label class="field">
            <span class="field__label">Label column <span class="hint">(optional)</span></span>
            <input class="field__input" type="text" bind:value={graphLabelCol} spellcheck="false" placeholder="display name for the id node" />
          </label>
          <label class="field">
            <span class="field__label">Value column <span class="hint">(optional)</span></span>
            <input class="field__input" type="text" bind:value={graphValueCol} spellcheck="false" placeholder="measure carried onto edges" />
          </label>
          <label class="field">
            <span class="field__label">Layout</span>
            <select class="field__input" bind:value={graphLayout}>
              <option value="force">Force</option>
              <option value="circular">Circular</option>
            </select>
          </label>
          {#if graphConfigValidation.ok}
            <span class="hint ok">✓ Valid mapping — nodes + edges are built from the tile's query below.</span>
          {:else}
            <span class="hint error">{graphConfigValidation.error}</span>
          {/if}
          <span class="hint">
            Each query row becomes an edge source → target; nodes are deduped across rows.
            Column names must match the query's row-header / measure captions.
          </span>
        </fieldset>
      {/if}

      {#if isRankedListTile}
        <fieldset class="graph-map">
          <legend>Ranked list</legend>
          <label class="field">
            <span>Subtitle <span class="hint">(optional)</span></span>
            <input type="text" bind:value={rankedSubtitle} placeholder="e.g. Product department · MoM" />
          </label>
          <label class="field">
            <span>Label column <span class="hint">(optional)</span></span>
            <input type="text" bind:value={rankedLabelCol} spellcheck="false" placeholder="blank = inferred" />
          </label>
          <label class="field">
            <span>Value column <span class="hint">(optional)</span></span>
            <input type="text" bind:value={rankedValueCol} spellcheck="false" placeholder="blank = inferred" />
          </label>
          <label class="field">
            <span>Rows</span>
            <input type="number" min="1" max="100" bind:value={rankedLimit} />
          </label>
          <label class="field">
            <span>Order</span>
            <select bind:value={rankedSort}>
              <option value="none">Keep the query's order</option>
              <option value="desc">Highest first</option>
              <option value="asc">Lowest first</option>
            </select>
          </label>
          <label class="field">
            <span>Value format <span class="hint">(optional)</span></span>
            <input
              type="text"
              bind:value={rankedValueFormat}
              spellcheck="false"
              placeholder="e.g. $c1 / 2% / 0"
            />
            <span class="hint">
              $cN for compact currency ($27.4M), $N for plain currency, N% for percent, N for
              fractional digits. Blank keeps the cube's own formatting.
            </span>
          </label>
          <label class="field">
            <span>Value colour</span>
            <select bind:value={rankedTone}>
              <option value="signed">By sign (up green / down red)</option>
              <option value="none">Plain</option>
            </select>
          </label>
          <label class="checkbox">
            <input type="checkbox" bind:checked={rankedShowRank} />
            <span>Show rank numbers</span>
          </label>
          {#if rankedValidation.ok}
            <span class="hint ok">✓ Valid — rows come from the tile's query below.</span>
          {:else}
            <span class="hint error">{rankedValidation.error}</span>
          {/if}
          <span class="hint">
            Colours and type follow the app theme — no custom CSS needed.
          </span>
        </fieldset>
      {/if}

      <!-- App Builder Phase 2 (saiku#1441): plugin renderer picker.
           SECURITY: the author only PICKS an admin-installed plugin by id — raw
           HTML can never ride tile config. The chosen plugin's markup is served
           from the admin registry at render time and runs in a locked-down
           iframe (sandbox="allow-scripts" + strict CSP + per-mount nonce).
           Persisted into tile.custom.options.pluginId on Save. -->
      {#if isPluginTile}
        <label class="field">
          <span class="field__label">Plugin</span>
          {#if pluginsLoading}
            <span class="hint">Loading installed plugins…</span>
          {:else if pluginsError}
            <span class="hint error">Couldn't load plugins: {pluginsError}</span>
          {:else if installedPlugins.length === 0}
            <span class="hint">
              No plugins installed — an admin installs them under saiku-home/tile-plugins/.
            </span>
          {:else}
            <select class="field__input" bind:value={selectedPluginId}>
              <option value="">— Select a plugin —</option>
              {#each installedPlugins as p (p.id)}
                <option value={p.id}>{p.label} ({p.id})</option>
              {/each}
            </select>
          {/if}
          <span class="hint">
            Runs admin-installed, trusted plugin code in a sandboxed iframe. It cannot read the
            host page, cookies, or session token; query data (below) is delivered as records.
          </span>
        </label>
      {/if}

      {#if wantsQuery}
        <!-- #912/#1175 fix: while the visual builder is open it owns the whole
             query section — hide the mode radios + the reference/inline blocks
             so they don't render on top of the embedded canvas. -->
        {#if !queryEditorOpen}
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
        {/if}

        <!-- ════════════════════════════════════════════════════════════
             Issue #912 — inline visual query editor. Mounts the workspace
             QueryCanvas (embedded mode) so the author builds the tile's
             query with drag-and-drop without leaving the dashboard. The
             modal widens while the editor is open; Save commits the built
             query into the inline body (no separate Save inside the embed).
             ════════════════════════════════════════════════════════════ -->
        {#if !queryEditorOpen}
          <div class="qe-launch">
            <Button variant="outline" disabled={!cube} onclick={() => void openQueryEditor()}>
              {i18n.t("tileEditor.query.editInline")}
            </Button>
            <span class="hint">
              {cube ? i18n.t("tileEditor.query.editInline.hint") : i18n.t("tileEditor.query.pickCubeFirst")}
            </span>
          </div>
          {#if queryEditorError}
            <span class="hint error">{queryEditorError}</span>
          {/if}
        {:else}
          <fieldset class="qe-section">
            <legend>{i18n.t("tileEditor.query.builder")}</legend>
            {#if queryEditorError}
              <span class="hint error">{queryEditorError}</span>
            {/if}
            <div class="qe-embed">
              {#if session.current}
                <aside class="overflow-y-auto p-2 border-r border-border bg-bg-subtle min-w-0">
                  <DimensionList username={session.current.username} />
                </aside>
              {/if}
              <div class="qe-canvas">
                <QueryCanvas embedded />
              </div>
            </div>
            <div class="flex justify-end gap-2">
              <Button variant="outline" onclick={cancelQueryEditor}>
                {i18n.t("tileEditor.query.collapse")}
              </Button>
              <Button onclick={applyQueryEditor}>
                {i18n.t("modal.apply")}
              </Button>
            </div>
          </fieldset>
        {/if}

        {#if !queryEditorOpen}
        {#if queryMode === "reference"}
          <label class="field">
            <span class="field__label">Saved query (.saiku file)</span>
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
              <select class="field__input" bind:value={referencePath}>
                <option value="">— pick a saved query —</option>
                {#each savedQueries as q (q.path)}
                  <option value={q.path}>{q.path}</option>
                {/each}
              </select>
              <span class="hint">
                The tile renders live from the saved query — the resolver
                (POST /ai/query/saved) loads it, merges any applicable
                dashboard filters, and runs it on each refresh.
              </span>
            {/if}
          </label>
        {:else}
          <label class="field">
            <span class="field__label">Inline query body (AiQueryRequest JSON)</span>
            <textarea
              bind:value={inlineBodyJson}
              rows="10"
              spellcheck="false"
              class="field__input json"
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
                Paste an AiQueryRequest, or use “{i18n.t("tileEditor.query.editInline")}”
                above to build it visually.
              </span>
            {/if}
          </label>
        {/if}
        {/if}
      {/if}

      {#if tile.type === "filter"}
        <TileEditorFilter
          bind:widget
          bind:filterTarget
          bind:cascadeStartLevel
          bind:cascadeDepth
          cubePicked={!!cube}
          dimensions={dimensionOptions()}
          hierarchies={hierarchyOptions()}
          levels={levelOptions()}
        />
      {/if}

      {#if tile.type === "kpi"}
        <TileEditorKpi
          bind:kpiConfig
          cubePicked={!!cube}
          measures={measureOptions()}
          dimensions={dimensionOptions()}
          hierarchies={kpiHierarchyOptions()}
          levels={kpiLevelOptions()}
        />
      {/if}

      <!-- ════════════════════════════════════════════════════════════
           Issue #919 — Conditional formatting (TABLE tiles only).
           Self-contained block guarded by tile.type === "table" so the
           parallel #922 (cascading filter) edit rebases cleanly. All
           rule maths lives in $lib/dashboard/conditionalFormat.ts; this
           is config capture only.
           ════════════════════════════════════════════════════════════ -->
      {#if tile.type === "table"}
        <TileEditorTableConditional bind:conditionalFormat />
      {/if}

      <!-- ════════════════════════════════════════════════════════════
           Issue #920 — Sparkline column (TABLE tiles only). Opt-in
           trailing column drawing a tiny inline trend per row from the
           row's numeric measure cells. Geometry maths lives in
           $lib/dashboard/sparkline.ts; this is config capture only.
           ════════════════════════════════════════════════════════════ -->
      {#if tile.type === "table"}
        <TileEditorTableSparkline bind:sparklineEnabled bind:sparklineType />
      {/if}

      <!-- ════════════════════════════════════════════════════════════
           Issue #931 — Auto-refresh interval (chart / table / kpi tiles).
           Re-runs the tile's existing (filter-aware) query on the chosen
           cadence; pauses while the tab is hidden. Pure option list +
           normalisation live in $lib/dashboard/autoRefresh.ts. Small,
           clearly-delimited block so the parallel #912 (inline query
           editor) edit on this modal rebases cleanly.
           ════════════════════════════════════════════════════════════ -->
      {#if tile.type === "chart" || tile.type === "table" || tile.type === "kpi"}
        <label class="field">
          <span class="field__label">{i18n.t("dashboard.refresh.label", "Auto-refresh")}</span>
          <select class="field__input"
            value={String(refreshInterval)}
            onchange={(e) => (refreshInterval = Number((e.target as HTMLSelectElement).value))}
          >
            {#each REFRESH_INTERVAL_OPTIONS as opt (opt.minutes)}
              <option value={String(opt.minutes)}>{i18n.t(opt.labelKey, opt.labelFallback)}</option>
            {/each}
          </select>
          <span class="hint">
            {i18n.t(
              "dashboard.refresh.hint",
              "Re-runs this tile's query on the interval, honouring active filters. Pauses while the browser tab is hidden.",
            )}
          </span>
        </label>
      {/if}
    </div>
    <footer class="flex justify-end gap-2 py-3 px-4 border-t border-border">
      <Button variant="outline" onclick={handleClose} disabled={imageUploading}>Cancel</Button>
      <Button onclick={handleSave} disabled={imageUploading}>
        {imageUploading ? "Uploading…" : "Save"}
      </Button>
    </footer>
  </div>
</div>

<!-- #1077: chart-options editor (reused from the workspace), layered above the
     tile editor. Edits a working copy; persisted with the tile on Save. -->
{#if tile.type === "chart"}
  <ChartEditorModal
    initial={chartOptions}
    {chartType}
    seriesNames={chartSeriesNames()}
    open={chartOptionsOpen}
    onSave={(next) => {
      chartOptions = next;
      chartOptionsTouched = true;
      chartOptionsOpen = false;
    }}
    onCancel={() => (chartOptionsOpen = false)}
  />
{/if}

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
    background: hsl(var(--bg));
    border-radius: 8px;
    box-shadow: 0 16px 48px rgba(0, 0, 0, 0.2);
    width: min(560px, 92vw);
    max-height: 90vh;
    display: flex;
    flex-direction: column;
    /* #912: smooth the grow/shrink when the visual editor opens. */
    transition: width 0.15s ease;
  }
  /* #912: the modal grows to ~80vw while the embedded QueryCanvas is open
     so the drop zones + result preview have room to work. */
  .modal--wide {
    width: min(1100px, 92vw);
    height: 88vh;
  }
  .modal-header {
    display: flex;
    align-items: center;
    padding: 0.75rem 1rem;
    border-bottom: 1px solid hsl(var(--border));
  }
  .modal-header h2 {
    margin: 0;
    font-size: 1rem;
    flex: 1;
    /* saiku#1781: `text-transform: capitalize` over a raw tile.type rendered
       "Edit Kpi Tile". The heading now supplies its own casing via
       tileTypeLabel, so leave the text alone. */
  }
  /* saiku#1258: field layout now comes from the global app.css design-system
     pattern (.field / .field__label / .field__input) — the same one
     ChartEditorModal uses. The scoped .field / .field.inline rules that used
     to live here never reached the child tile editors (Svelte scoping does
     not cross component boundaries), which is exactly why the editor looked
     ragged. Side-by-side pairs are `field flex-1` inside the flex fieldsets. */
  /* saiku#1258: fieldset/hint/checkbox affordances are shared with the child
     tile editors rendered inside this modal, so they're declared as
     descendant :global rules — plain scoped rules never reached the children
     and left their fieldsets/hints unstyled. */
  .modal :global(.size) {
    display: flex;
    gap: 0.5rem;
    align-items: flex-end;
    border: 1px solid hsl(var(--border));
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .modal :global(.size legend) {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  .modal :global(.mode) {
    display: flex;
    gap: 1rem;
    align-items: center;
    border: 1px solid hsl(var(--border));
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .modal :global(.mode legend) {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  /* #907: anomaly-detection config block on chart tiles. */
  .anomaly {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    border: 1px solid hsl(var(--border));
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
  }
  .anomaly legend {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  .modal :global(.radio),
  .modal :global(.checkbox) {
    display: inline-flex;
    align-items: center;
    gap: 0.375rem;
    font-size: 0.875rem;
    cursor: pointer;
  }
  .position-error {
    padding: 0.5rem 0.75rem;
    background: color-mix(in srgb, hsl(var(--danger)) 14%, transparent);
    color: hsl(var(--danger));
    border-radius: 4px;
    font-size: 0.8125rem;
  }
  textarea.json {
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, monospace;
    font-size: 0.8125rem;
    white-space: pre;
  }
  .modal :global(.hint) {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
  }
  .modal :global(.hint.error) { color: hsl(var(--danger)); }
  .modal :global(.hint.ok) { color: var(--success, hsl(var(--primary))); }
  /* #1077, #919: chart-options + conditional-formatting styles moved
     into TileEditorChart / TileEditorTableConditional / TileEditorKpi /
     TileEditorTableSparkline (per saiku#1229). Svelte's scoped CSS
     does not cross component boundaries, so the rules live next to
     the markup that uses them. */
  /* ── Issue #912: inline visual query editor (embedded QueryCanvas) ── */
  .qe-launch {
    display: flex;
    align-items: center;
    gap: 0.625rem;
    flex-wrap: wrap;
  }
  .qe-launch .hint {
    flex: 1;
    min-width: 12rem;
  }
  .qe-section {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    border: 1px solid hsl(var(--border));
    border-radius: 4px;
    padding: 0.5rem 0.75rem;
    margin: 0;
    /* Let the embed take the freed-up vertical space when the modal grows.
       saiku#1773: `flex: 1` alone means `flex-basis: 0%`, and paired with
       `min-height: 0` inside the modal body — a CONTENT-SIZED `overflow-auto`
       column flex container, so there is no free space to distribute — the
       fieldset collapsed to its legend (35px) while `.qe-embed`'s 360px floor
       overflowed it. Everything after this block (Auto-refresh, table
       conditional formatting, sparkline) then painted on top of the builder.
       `1 0 auto` keeps the grow behaviour but floors the box at its content. */
    flex: 1 0 auto;
  }
  .qe-section legend {
    font-size: 0.75rem;
    color: hsl(var(--fg-muted));
    text-transform: uppercase;
    letter-spacing: 0.04em;
    padding: 0 0.25rem;
  }
  /* Two-pane embed: dimension sidebar (drag source) + the canvas/result. */
  .qe-embed {
    display: grid;
    grid-template-columns: 240px 1fr;
    gap: 0.5rem;
    flex: 1;
    /* A floor so the drop zones + result preview are usable even before the
       modal's flex height resolves. (Previously preceded by a `min-height: 0`
       that this declaration always overrode — dropped as dead in saiku#1773.) */
    min-height: 360px;
    /* saiku#1773: now that `.qe-section` is content-sized (`flex: 1 0 auto`)
       the embed would otherwise grow to the full height of the cube tree —
       ~2000px — and the modal body would just scroll past it. Cap it so the
       sidebar and canvas use their own internal scrollers as designed. */
    max-height: 60vh;
    border: 1px solid hsl(var(--border));
    border-radius: 4px;
    overflow: hidden;
  }
  .qe-canvas {
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
    overflow: hidden;
  }
  /* QueryCanvas's root is .canvas (flex:1); make it fill the embed pane. */
  .qe-canvas :global(.canvas) {
    flex: 1;
    min-height: 0;
  }
</style>
