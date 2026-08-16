/*
 * Dashboards REST client.
 *
 * Mirrors org.saiku.web.rest.resources.dashboards.DashboardResource on the
 * Java side. CRUD only — query execution still flows through
 * $lib/api/query (per-tile, with merged filters computed client-side).
 *
 * See docs/plans/2026-05-16-dashboards-design.md for the full design.
 */

import type { ThinQuery } from '$lib/api/query';
import type { CustomTileConfig } from '$lib/dashboard/tileRegistry';
import type { ChartOptions } from '$lib/views/chartTypes';

const REST_BASE = '/rest/saiku/api/dashboards';

/** Cube ref shape — matches AiCubeRef on the Java side. */
/**
 * What a tile is bound to.
 *
 * Historically this was always a Mondrian cube, so the four MDX coordinates are
 * required and `kind` is absent. saiku#1803 adds Ossie semantic models as a
 * second source: `kind: "ossie"` uses `connectionName` + {@link modelName}, and
 * fills `catalog` / `schema` / `cubeName` with the model name so the many
 * places that render "the thing this tile is on" keep working untouched.
 *
 * `kind` is OPTIONAL and absent means `"mdx"` — every `.saikudash` and
 * `.saikuapp` already in the field stays valid and keeps its current meaning.
 * Read it through {@link isOssieSource} rather than comparing the field, so the
 * absent-means-mdx rule lives in one place.
 */
export interface CubeRef {
	connectionName: string;
	catalog: string;
	schema: string;
	cubeName: string;
	/** saiku#1803. Absent = "mdx". */
	kind?: 'mdx' | 'ossie';
	/** saiku#1803, Ossie only: the semantic model's name. */
	modelName?: string;
}

/** Saved-query reference. */
export interface ReferenceQuery {
	kind: 'reference';
	path: string;
}

/** Inline query embedded in the tile. {@code body} matches AiQueryRequest. */
export interface InlineQuery {
	kind: 'inline';
	// Loose body shape — the typed AiQueryRequest types live in $lib/api/query;
	// the dashboard layer treats it opaquely (read in, hand to /ai/query out).
	body: Record<string, unknown>;
}

export type TileQuery = ReferenceQuery | InlineQuery;

export type TileType = 'chart' | 'table' | 'text' | 'filter' | 'kpi' | 'image' | 'custom';

export type FilterWidget =
	'single-select' | 'multi-select' | 'date-range' | 'cascading-select' | 'top-n';

/* --- issue #921: Top-N / Bottom-N filter widget --------------------------
 * A panel filter that ranks the members of its dimension/hierarchy/level by a
 * chosen measure and narrows compatible tiles to the top (or bottom) N. It
 * resolves at runtime to a member-unique-name list (run the ranking query →
 * map the ranked captions to unique names via the level's member catalogue)
 * which flows through the existing filter-merge unchanged — no backend or
 * merge changes. Config lives in this block; pure resolution helpers are in
 * $lib/dashboard/topN. */
export interface TopNConfig {
	/** Measure unique name to rank by, e.g. {@code [Measures].[Unit Sales]}. */
	measure?: string;
	/** Measure caption for display (mirrors the schema's measure caption). */
	measureCaption?: string;
	/** How many members to keep. Defaults to 10. */
	n?: number;
	/** {@code "top"} = highest-N (desc), {@code "bottom"} = lowest-N (asc). */
	direction?: 'top' | 'bottom';
}
/* --- end issue #921 block ------------------------------------------------- */

/* --- issue #922: cascading single-select filter widget -------------------
 * Self-contained additions for the "cascading-select" FilterWidget variant.
 * Kept in one clearly-delimited block (appended, nothing reordered) so the
 * parallel #919 conditional-formatting work rebases cleanly. The variant
 * walks a single hierarchy level-by-level: pick a parent member, the next
 * dropdown shows only that parent's children, and so on. The deepest
 * concrete selection is emitted as the filter member. Pure cascade state
 * logic lives in $lib/dashboard/cascadingFilter; this is just the config
 * DTO carried on the panel filter / tile.
 */

/** Per-widget config for the {@code "cascading-select"} variant. The
 *  cascade walks the levels of {@link DashboardFilter.hierarchy} starting
 *  at {@code startLevel} (which defaults to the filter's own
 *  {@code level}) and descends at most {@code depth} dropdowns. No new
 *  backend surface — each dropdown reuses the existing member-search
 *  fetch, scoped to the parent member chosen above it. */
export interface CascadingSelectConfig {
	/** Root-most cascade level (caption). When omitted the renderer falls
	 *  back to the panel filter's {@link DashboardFilter.level}. */
	startLevel?: string;
	/** How many dropdowns deep the cascade may walk (clamped 1..6 and
	 *  further bounded by the levels actually present below startLevel).
	 *  Defaults to 3 when unset. */
	depth?: number;
}
/* --- end issue #922 block ------------------------------------------------- */

/** saiku#1803: how one concept is addressed on a Mondrian cube. */
export interface MdxFilterBinding {
	kind: 'mdx';
	cube: CubeRef;
	dimension: string;
	hierarchy: string;
	level: string;
}

/** saiku#1803: how one concept is addressed on an Ossie semantic model. */
export interface OssieFilterBinding {
	kind: 'ossie';
	cube: CubeRef;
	dataset: string;
	field: string;
}

/** One target of a semantic filter, in the vocabulary of one source kind.
 *  Resolution logic lives in $lib/dashboard/semanticFilter; this is the
 *  persisted shape. */
export type FilterBinding = MdxFilterBinding | OssieFilterBinding;

/** A dim/hier/level filter — used as both a dashboard-level default and
 *  as the {@code target} of a filter-widget tile. */
export interface DashboardFilter {
	dimension: string;
	hierarchy: string;
	level: string;
	members: string[]; // MDX unique names; empty = "any"

	/* --- saiku#1803: semantic mapping ---------------------------------------
	 * All three optional; a filter without them behaves exactly as it did before,
	 * which is the state of every dashboard and app already saved.
	 *
	 * A filter names a CONCEPT and carries a binding per source, so one control
	 * narrows a cube tile and a semantic-model tile at once. The selection travels
	 * as CAPTIONS (source-neutral) and each binding resolves it in its own
	 * vocabulary at query time. */

	/** Display name of the concept, e.g. "State". Falls back to {@link level}. */
	label?: string;
	/** Per-source targets. Absent = the legacy single-MDX-target behaviour. */
	bindings?: FilterBinding[];
	/** Source-neutral selection. Absent = derived from {@link members}. */
	captions?: string[];
}

/** Display format for the KPI tile's main number. "custom" enables the
 *  {@link KpiConfig.customFormat} pattern. */
export type KpiFormat = 'number' | 'currency' | 'percent' | 'custom';

/** Reference to a time level on a cube — used by KPI comparison and
 *  sparkline. Same shape as DashboardFilter without members. */
export interface TimeLevelRef {
	dimension: string;
	hierarchy: string;
	level: string;
}

/** Comparison mode for the KPI tile's secondary callout.
 *  - "prior-period" — delta vs the preceding sibling at {@link KpiConfig.timeLevel}.
 *  - "year-over-year" — delta vs the same period one year earlier (#992);
 *    resolves the parallel-period member (e.g. Q2.1997 → Q2.1996). */
export type KpiComparison = 'none' | 'prior-period' | 'year-over-year' | 'target';

/** Direction: "higher" means a bigger number is better (drives both the
 *  prior-period arrow colour and threshold green/red mapping). */
export type KpiDirection = 'higher-is-better' | 'lower-is-better';

/** Threshold breakpoints for colour-banding the main number. Optional
 *  per band — leave a band undefined to skip it. The bands are
 *  interpreted with respect to {@link KpiConfig.direction}. */
export interface KpiThresholds {
	red?: number;
	yellow?: number;
	green?: number;
}

/** Per-tile KPI config. All fields optional so an unfinished tile saves
 *  cleanly; the renderer falls back to a placeholder until the analyst
 *  picks a measure. */
export interface KpiConfig {
	/** Measure unique name, e.g. {@code [Measures].[Unit Sales]}. */
	measure?: string;
	/** Optional cube-side caption to render under the number when no
	 *  comparison is configured. Mirrors the AiQueryMetadata measure
	 *  caption — set by the editor on pick. */
	measureCaption?: string;
	format?: KpiFormat;
	/** When {@link format} is "custom", the d3-style or printf-style
	 *  pattern to apply. Loose-typed; the formatter handles the parse. */
	customFormat?: string;
	comparison?: KpiComparison;
	/** Target value when {@link comparison} is "target". */
	target?: number;
	/** Time level used for prior-period / year-over-year delta and / or
	 *  sparkline. */
	timeLevel?: TimeLevelRef;
	sparkline?: boolean;
	/** Declares that the last N periods of the series are INCOMPLETE — the
	 *  current week/month still filling up, or a data boundary.
	 *
	 *  This does NOT hide them. The headline still reports the newest period's
	 *  real value, and the sparkline still plots every point: the data is what it
	 *  is, and suppressing an inconvenient figure would be a worse lie than the
	 *  one this fixes. What it suppresses is the false COMPARISON — a part-period
	 *  measured against a whole one. FoodMart's weekly series ends on a stub week
	 *  52 (1,856 against 11,880 for week 51); the −84% that produces describes
	 *  the calendar, not the business, so it is withheld and the period is
	 *  labelled "partial" instead.
	 *
	 *  The tile cannot detect this itself — query metadata carries no dates, and
	 *  inferring it from the values would hide genuine collapses. Defaults to 0,
	 *  so existing tiles are unchanged. */
	partialTrailing?: number;
	thresholds?: KpiThresholds;
	direction?: KpiDirection;
	/** Override the comparison suffix on the delta callout (e.g. "vs last Thu",
	 *  "vs 4-wk avg"). When unset the built-in "vs prior" / "vs last year" /
	 *  "vs target" text is used. */
	deltaSuffix?: string;
}

/* --- issue #920: table sparkline column ---------------------------------
 * Opt-in trailing column on a table tile that draws a tiny inline SVG
 * trend (line or bar) from each row's numeric measure cells. Display-only,
 * no backend surface — the geometry is computed client-side from the
 * already-fetched response. Pure geometry lives in
 * $lib/dashboard/sparkline. Config carried on the table tile below.
 */

/** Sparkline glyph style. {@code "line"} = mini polyline; {@code "bar"} =
 *  mini column chart. Defaults to "line". */
export type SparklineType = 'line' | 'bar';

/** Per-tile sparkline config (issue #920). Only consulted when
 *  {@code type === "table"} and {@link SparklineConfig.enabled} is true. */
export interface SparklineConfig {
	/** Master opt-in. When false / unset the column is not rendered. */
	enabled: boolean;
	/** Glyph style. Defaults to "line". */
	type?: SparklineType;
}
/* --- end issue #920 block ------------------------------------------------- */

/* --- issue #918: image tile --------------------------------------------- */

/** Where an image tile's bytes come from. {@code "url"} → {@link ImageConfig.src}
 *  is an external http(s) URL the browser loads directly. {@code "upload"} →
 *  src is the download path of an asset stored in the repository via the
 *  image upload endpoint. */
export type ImageSource = 'url' | 'upload';

/** CSS object-fit for the image within its tile box. */
export type ImageFit = 'contain' | 'cover' | 'fill' | 'scale-down';

/** Per-tile image config (issue #918). Only consulted when
 *  {@code type === "image"}. All fields optional so an unconfigured tile
 *  saves cleanly; the renderer shows a placeholder until {@code src} is set. */
export interface ImageConfig {
	/** How {@link src} is sourced. Defaults to "url". */
	source?: ImageSource;
	/** The image to render: an external http(s) URL ("url" mode) or the
	 *  repository asset download path returned by the upload endpoint
	 *  ("upload" mode). */
	src?: string;
	/** CSS object-fit. Defaults to "contain". */
	fit?: ImageFit;
	/** Optional caption rendered below the image (plain text). */
	caption?: string;
	/** Alt text for accessibility / broken-image fallback. */
	alt?: string;
}
/* --- end issue #918 block ------------------------------------------------ */

/* --- issue #907: anomaly detection on time-series chart tiles ------------
 * Opt-in server-side statistical anomaly detection (Tier-3, NO LLM) layered
 * over a time-series chart tile (line / bar / area). When enabled the chart
 * tile calls POST /ai/anomaly instead of /ai/query; anomalous points are drawn
 * as ECharts markPoints on top of the existing render path. Config carried on
 * the chart tile below; all computation is server-side. */

/** Detector method. {@code "stl"} is reserved — the backend currently returns
 *  a clear "not yet supported" validation error for it. */
export type AnomalyMethodConfig = 'zscore' | 'mad' | 'stl';

/** Per-tile anomaly config (issue #907). Only consulted when
 *  {@code type === "chart"} and {@link AnomalyConfig.enabled} is true. */
export interface AnomalyConfig {
	/** Master opt-in. When false / unset no anomaly request is made. */
	enabled: boolean;
	/** Detector method. Defaults to "zscore". */
	method?: AnomalyMethodConfig;
	/** Detector cutoff. Omit to use the method default (zscore 3.0, mad 3.5). */
	threshold?: number;
	/** Unique name of the time axis to scan. Defaults to the tile's row axis
	 *  (the chart's category axis) when omitted at request time. */
	timeAxis?: string;
}
/* --- end issue #907 block ------------------------------------------------- */

/* --- issue #908: time-series forecast on chart tiles ---------------------
 * Opt-in server-side statistical forecast (Tier-3, NO LLM) that extends a
 * time-series chart with a projected horizon. When enabled the chart tile calls
 * POST /ai/forecast instead of /ai/query; the observed data renders as usual and
 * the horizon is appended as a dashed continuation with a confidence band. All
 * computation is server-side. */

/** Forecast method. {@code "arima"} / {@code "prophet"} are reserved — the
 *  backend currently returns a clear "not yet supported" validation error for
 *  them (only {@code "ets"} is implemented). */
export type ForecastMethodConfig = 'ets' | 'arima' | 'prophet';

/** Per-tile forecast config (issue #908). Only consulted when
 *  {@code type === "chart"} and {@link ForecastConfig.enabled} is true. */
export interface ForecastConfig {
	/** Master opt-in. When false / unset no forecast request is made. */
	enabled: boolean;
	/** Forecast method. Defaults to "ets". */
	method?: ForecastMethodConfig;
	/** Number of future points to project. Defaults to 6 (server clamps 1–365). */
	horizon?: number;
	/** Prediction-interval confidence in (0,1). Defaults to 0.95. */
	confidence?: number;
	/** Unique name of the time axis. Defaults to the tile's row axis when omitted. */
	timeAxis?: string;
}
/* --- end issue #908 block ------------------------------------------------- */

/* --- issue #1085: brush cross-filter on chart tiles ----------------------
 * Opt-in. When enabled on a brushable cartesian chart tile, a rectangular
 * x-range brush emits a (usually multi-member) dashboard filter on the tile's
 * row hierarchy, narrowing every OTHER tile via the existing filter bus. The
 * source tile keeps full context (effectiveQueryFor excludes its own cross).
 * Off by default so existing dashboards are unchanged. */
export interface BrushCrossFilterConfig {
	/** Master opt-in. When false / unset no brush mode is entered. */
	enabled: boolean;
}
/* --- end issue #1085 block ------------------------------------------------ */

export interface DashboardTile {
	id: string;
	x: number;
	y: number;
	w: number;
	h: number;
	type: TileType;
	title?: string;
	cube?: CubeRef;
	query?: TileQuery;
	chartType?: string;
	/** Per-tile chart options (title / axes / legend / dualAxis / seriesAxis /
	 *  trend lines). Only consulted when {@code type === "chart"}. Absent on
	 *  legacy tiles → the renderer falls back to the dashboard baseline, so the
	 *  appearance is unchanged (migration-safe). Issue #1077. */
	chartOptions?: ChartOptions;
	/** Anomaly-detection config (issue #907). Only consulted when
	 *  {@code type === "chart"} and the chart is a time-series type. */
	anomaly?: AnomalyConfig;
	/** Forecast config (issue #908). Only consulted when
	 *  {@code type === "chart"} and the chart is a time-series type. */
	forecast?: ForecastConfig;
	/** Brush cross-filter config (issue #1085). Only consulted when
	 *  {@code type === "chart"} and the chart type is brushable. */
	brushCrossFilter?: BrushCrossFilterConfig;
	text?: string;
	target?: DashboardFilter;
	widget?: FilterWidget;
	/** Cascading-select config (issue #922). Only consulted when
	 *  {@code widget === "cascading-select"}. */
	cascading?: CascadingSelectConfig;
	/** KPI-tile config. Only consulted when {@code type === "kpi"}. */
	kpi?: KpiConfig;
	/** Per-column conditional formatting rules. Only consulted when
	 *  {@code type === "table"}. See the issue-#919 block below. */
	conditionalFormat?: ConditionalFormatRule[];
	/** saiku#1770 — per-column number formatting, keyed by header caption
	 *  (same keying as {@link conditionalFormat}). Values use the KPI /
	 *  ranked-list pattern vocabulary: `$cN` compact currency, `$N` plain
	 *  currency, `N%` percent, bare `N` fractional digits.
	 *
	 *  Without this the table was the only value-bearing tile with no number
	 *  formatting at all, so it printed whatever the cube's formatString
	 *  produced — an integer count carrying `#.0` rendered "10759.0", with no
	 *  thousands separator, next to money at three decimals. Unset columns keep
	 *  the server's formatted text, so existing tiles are unchanged. */
	columnFormats?: Record<string, string>;
	/** Sparkline column config (issue #920). Only consulted when
	 *  {@code type === "table"}. See the issue-#920 block above. */
	sparkline?: SparklineConfig;
	/** Image-tile config (issue #918). Only consulted when
	 *  {@code type === "image"}. */
	image?: ImageConfig;
	/** Custom-renderer config (App Builder Phase 2, saiku#1441). Only consulted
	 *  when {@code type === "custom"} — names the registered renderer and carries
	 *  its opaque options. See $lib/dashboard/tileRegistry. */
	custom?: CustomTileConfig;
	/* --- issue #931: per-tile auto-refresh --------------------------------
	 * Auto-refresh cadence in MINUTES. 0 / undefined = off (the default —
	 * tiles fetch once and stay static). When set to a positive value the
	 * tile re-runs its existing (filter-aware) query on that interval, pausing
	 * while the tab is hidden. Valid values are constrained to the picker list
	 * in $lib/dashboard/autoRefresh (normaliseInterval clamps anything else to
	 * off, so a hand-edited JSON can't smuggle in a sub-minute loop). Consulted
	 * by the chart / table / KPI tiles. */
	refreshInterval?: number;
	/* --- end issue #931 block --------------------------------------------- */
}

/* ====================================================================
 * Issue #919 — conditional formatting on table tiles.
 *
 * Self-contained block, appended so a rebase against the parallel #922
 * (cascading-filter) branch is trivial. The only edit above is the
 * `conditionalFormat?` field on DashboardTile.
 * ==================================================================== */

/** How a table cell is decorated by its rule. Display-only.
 *   - "background": cell background colour by threshold band.
 *   - "bar":        horizontal mini data-bar, width ∝ value.
 *   - "font":       font colour by sign or by threshold band.
 *   - "icon":       ↑ ↓ → glyph by threshold band (or by sign). */
export type ConditionalFormatType = 'background' | 'bar' | 'font' | 'icon';

/** Whether thresholds are read as percentiles of the column's own values
 *  ("relative", 0–100) or as fixed numeric values ("absolute"). */
export type ConditionalThresholdMode = 'relative' | 'absolute';

/** Per-band colour overrides for background / font / icon rules. Omit a
 *  band to fall back to the helper's default palette. */
export interface ConditionalBandColors {
	low?: string;
	mid?: string;
	high?: string;
}

/** A single per-column conditional-formatting rule on a table tile. The
 *  evaluation logic lives in $lib/dashboard/conditionalFormat.ts; this
 *  type is just the serialisable config persisted on the dashboard. */
export interface ConditionalFormatRule {
	/** Column caption this rule targets — matches the table header text. */
	column: string;
	type: ConditionalFormatType;
	/** Threshold interpretation. Ignored by "bar" (always min/max scaled)
	 *  and by "font"/"icon" when both thresholds are unset (sign mode). */
	thresholdMode: ConditionalThresholdMode;
	/** Lower cut: percentile (0–100) when relative, raw value when
	 *  absolute. Leave unset (with {@link highThreshold}) on font/icon to
	 *  get sign-based colouring. */
	lowThreshold?: number;
	/** Upper cut: percentile (0–100) when relative, raw value when
	 *  absolute. */
	highThreshold?: number;
	/** Per-band colour overrides for background / font / icon. */
	colors?: ConditionalBandColors;
	/** Fill colour for "bar" rules. */
	barColor?: string;
}

export interface DashboardLayout {
	cols: number;
	tiles: DashboardTile[];
}

/** One entry in the unified filter panel. Inherits the dim/hier/level
 *  target shape from DashboardFilter and adds:
 *   - {@code id}: stable identifier so the drag-reorder UI can key
 *     entries without re-deriving them from target identity.
 *   - {@code widget}: the picker style (single-select / multi-select /
 *     date-range). Mirrors the {@code widget} field that used to live
 *     on filter-type tiles. Required so a panel entry always renders
 *     with a definite control.
 *   - {@code cube}: source cube ref. Lets the panel populate the
 *     member dropdown via /ai/members/search without having to derive
 *     the cube from a sibling tile. Optional for forward-compat. */
export interface PanelFilter extends DashboardFilter {
	id: string;
	widget: FilterWidget;
	cube?: CubeRef;
	/** Config for {@code widget === "cascading-select"} (issue #922).
	 *  Ignored by the other variants. */
	cascading?: CascadingSelectConfig;
	/** Config for {@code widget === "top-n"} (issue #921). Ignored by others. */
	topN?: TopNConfig;
}

/** Unified filter panel: docks at the top of the dashboard editor as
 *  a collapsible row of compact pickers. Replaces the per-filter-tile
 *  model from the dashboards v1 design — filter widgets no longer
 *  occupy grid cells. See saiku#996. */
export interface DashboardFilterPanel {
	collapsed: boolean;
	filters: PanelFilter[];
}

export interface Dashboard {
	id: string;
	name: string;
	version: number;
	layout: DashboardLayout;
	filters: DashboardFilter[];
	filterPanel?: DashboardFilterPanel;
	/** Free-text labels for catalogue grouping + filtering (#935). */
	tags?: string[];
}

export interface DashboardSaveResponse {
	status: 'OK';
	path: string;
}

export interface DashboardErrorResponse {
	status: 'NOT_FOUND' | 'VALIDATION_ERROR' | 'ERROR';
	field?: string;
	error: string;
	path?: string;
}

/** Load a dashboard by repository path. Throws on 4xx / 5xx with the
 *  parsed error body when available. */
export async function loadDashboard(path: string): Promise<Dashboard> {
	const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		const err = await readError(res);
		throw new Error(`loadDashboard(${path}) -> ${res.status}: ${err}`);
	}
	return (await res.json()) as Dashboard;
}

/** Save (create or overwrite) a dashboard. */
export async function saveDashboard(
	path: string,
	dashboard: Dashboard
): Promise<DashboardSaveResponse> {
	const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
		method: 'POST',
		credentials: 'include',
		headers: {
			'Content-Type': 'application/json',
			Accept: 'application/json'
		},
		body: JSON.stringify(dashboard)
	});
	if (!res.ok) {
		const err = await readError(res);
		throw new Error(`saveDashboard(${path}) -> ${res.status}: ${err}`);
	}
	return (await res.json()) as DashboardSaveResponse;
}

export async function deleteDashboard(path: string): Promise<void> {
	const res = await fetch(`${REST_BASE}/${encodePath(path)}`, {
		method: 'DELETE',
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		const err = await readError(res);
		throw new Error(`deleteDashboard(${path}) -> ${res.status}: ${err}`);
	}
}

/** Upload an image asset for an image tile (issue #918). POSTs the file as
 *  multipart to the hardened upload endpoint (server enforces auth +
 *  content-type allowlist + size cap + path-traversal-safe per-user storage)
 *  and returns the same-origin download path to persist as the tile's
 *  {@link ImageConfig.src}. */
export async function uploadImageAsset(tileId: string, file: File): Promise<string> {
	const form = new FormData();
	form.append('file', file);
	form.append('tileId', tileId);
	const res = await fetch(`${REST_BASE}/image/upload`, {
		method: 'POST',
		credentials: 'include',
		// No Content-Type header — the browser sets the multipart boundary.
		body: form
	});
	if (!res.ok) {
		const err = await readError(res);
		throw new Error(`uploadImageAsset -> ${res.status}: ${err}`);
	}
	const json = (await res.json()) as { src?: string };
	if (!json.src) throw new Error('upload response missing src');
	return json.src;
}

/** Build an empty Dashboard with a fresh id, ready to be populated by the
 *  editor before the first save. */
export function newDashboard(name = 'Untitled dashboard'): Dashboard {
	return {
		id: cryptoUuid(),
		name,
		version: 1,
		layout: { cols: 12, tiles: [] },
		filters: []
	};
}

/** AI Query API cube summary — what /ai/cubes returns. Used by the
 *  add-tile flow's cube picker. */
export interface AiCubeSummary {
	connectionName: string;
	catalog: string;
	schema: string;
	cubeName: string;
	cubeCaption?: string;
	defaultMeasure?: string;
	measureCount?: number;
}

/** GET /rest/saiku/api/ai/cubes — list of cubes the current user can
 *  query. Same endpoint MCP's list_cubes wraps. */
export async function listAiCubes(): Promise<AiCubeSummary[]> {
	const res = await fetch('/rest/saiku/api/ai/cubes', {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) throw new Error(`listAiCubes -> ${res.status}`);
	return (await res.json()) as AiCubeSummary[];
}

/** One row from GET /ai/ossie/models (saiku#1803). */
export interface AiOssieModelSummary {
	connectionName: string;
	modelName: string;
	description?: string;
	factDataset?: string;
	datasetCount?: number;
	metricCount?: number;
}

/**
 * GET /rest/saiku/api/ai/ossie/models — the semantic models the current user
 * can query (saiku#1803).
 *
 * Resolves to an empty list rather than throwing when the endpoint is absent or
 * errors: a deployment with no Ossie datasources is the normal case, and a tile
 * editor that refuses to open its source picker because an optional surface is
 * unavailable would be a regression for every existing user.
 */
export async function listAiOssieModels(): Promise<AiOssieModelSummary[]> {
	try {
		const res = await fetch('/rest/saiku/api/ai/ossie/models', {
			credentials: 'include',
			headers: { Accept: 'application/json' }
		});
		if (!res.ok) return [];
		const raw = (await res.json()) as unknown;
		return Array.isArray(raw) ? (raw as AiOssieModelSummary[]) : [];
	} catch {
		return [];
	}
}

/** One dataset of an Ossie model, reduced to what a binding picker needs. */
export interface AiOssieDataset {
	name: string;
	fields: string[];
}

/**
 * GET /rest/saiku/api/ai/ossie/schema/{connection}/{model} — datasets + field
 * names, for the semantic-filter binding picker (saiku#1803).
 *
 * Tolerant by design: the AI schema envelope has grown fields over time and a
 * binding picker that threw on an unexpected shape would take the whole filter
 * panel down. Anything unparseable resolves to an empty list, which the picker
 * renders as "no datasets" rather than an error.
 */
export async function listOssieDatasets(
	connectionName: string,
	modelName: string
): Promise<AiOssieDataset[]> {
	try {
		const url = `/rest/saiku/api/ai/ossie/schema/${encodeURIComponent(connectionName)}/${encodeURIComponent(modelName)}`;
		const res = await fetch(url, {
			credentials: 'include',
			headers: { Accept: 'application/json' }
		});
		if (!res.ok) return [];
		const raw = (await res.json()) as { datasets?: unknown };
		const datasets = raw?.datasets;
		if (!datasets || typeof datasets !== 'object') return [];
		// The envelope keys datasets by name, each carrying a `fields` map keyed by
		// field name (same shape the MDX /ai/schema uses for dimensions).
		return Object.entries(datasets as Record<string, unknown>).map(([name, d]) => {
			const fields = (d as { fields?: unknown })?.fields;
			return {
				name: ((d as { name?: string })?.name ?? name) as string,
				fields:
					fields && typeof fields === 'object' ? Object.keys(fields as Record<string, unknown>) : []
			};
		});
	} catch {
		return [];
	}
}

/** Mint a fresh tile id for the add-tile flow. Exposed so the modal
 *  can do it inline rather than reaching into the internals. */
export function newTileId(): string {
	return cryptoUuid();
}

/** Pure: clone a Dashboard with a fresh top-level id and fresh per-tile
 *  ids, so the copy has no overlap with the source for store keying /
 *  $effect identity / ACL boundaries. Tiles' positions, sizes, cube
 *  bindings, queries, and KPI / filter config are preserved verbatim;
 *  only ids change.
 *
 *  When {@code newName} is provided it replaces the dashboard name;
 *  otherwise the source name with " (copy)" appended is used.
 *
 *  Pure / immutable: the source is not mutated. Issue #939. */
export function cloneDashboardWithFreshIds(source: Dashboard, newName?: string): Dashboard {
	return {
		...source,
		id: cryptoUuid(),
		name: newName ?? `${source.name} (copy)`,
		layout: {
			...source.layout,
			tiles: source.layout.tiles.map((t) => ({ ...t, id: cryptoUuid() }))
		}
	};
}

/** Pure: clone a single tile with a fresh id and an optional title
 *  suffix. Position / size / type / cube / query / kpi / target /
 *  widget / chartType / text are preserved verbatim; only id (and
 *  optionally title) change.
 *
 *  Title rules:
 *   - When {@code source.title} is set, the clone's title becomes
 *     {@code `${source.title}${titleSuffix ?? " (copy)"}`}.
 *   - When {@code source.title} is missing (the tile relies on the
 *     `defaultTitle` fallback in Tile.svelte), the clone's title is
 *     left undefined too — the fallback continues to apply.
 *
 *  Pure / immutable: the source is not mutated. Caller must pick the
 *  layout slot separately (see {@code firstFreeSlot} in
 *  $lib/dashboard/tilePlacement) and merge x/y after the clone.
 *  Issue #913. */
export function cloneTileWithFreshId(
	source: DashboardTile,
	opts?: { newId?: string; titleSuffix?: string }
): DashboardTile {
	const suffix = opts?.titleSuffix ?? ' (copy)';
	const title = source.title ? `${source.title}${suffix}` : source.title;
	return {
		...source,
		id: opts?.newId ?? cryptoUuid(),
		title
	};
}

/** Glue: load a dashboard at {@code srcPath}, clone it with fresh ids,
 *  save the clone at {@code destPath}, and return the new dashboard.
 *  The caller is responsible for picking a non-colliding destPath —
 *  saveDashboard overwrites by design. Issue #939. */
export async function duplicateDashboard(
	srcPath: string,
	destPath: string,
	newName?: string
): Promise<Dashboard> {
	const source = await loadDashboard(srcPath);
	const cloned = cloneDashboardWithFreshIds(source, newName);
	await saveDashboard(destPath, cloned);
	return cloned;
}

/* ---------------------------- internals ---------------------------- */

/** URL-encode each path segment but leave slashes as-is — DashboardResource
 *  binds {path:.+} which captures slashes natively. */
function encodePath(path: string): string {
	return path.split('/').map(encodeURIComponent).join('/');
}

/** Normalise a user- or URL-supplied dashboard path into a save-safe repo
 *  path. Paths that start with `/` are treated as explicit ("save as" to an
 *  absolute repo location) and pass through unchanged (less the leading
 *  slash, which the JCR layer doesn't expect). Paths that already live under
 *  `homes/...` pass through. Everything else gets the user's home prefixed
 *  so non-admins don't trip the saveFile ACL gate from saiku#895. */
export function normaliseDashboardPath(rawPath: string, username: string): string {
	const trimmed = rawPath.trim();
	if (!trimmed) throw new Error('Dashboard path is required');
	// The leading-slash signal ("this is an explicit absolute repo path,
	// don't prefix with homes/<user>") has to be captured BEFORE the
	// toRepoRelative + normaliseRepoPath pass strips it. Without this
	// the per-user home-prefix path swallows the user's intent.
	const isAbsolute = trimmed.startsWith('/');
	const p = isAbsolute ? normaliseRepoPath(trimmed) : toRepoRelative(trimmed).trim();
	if (!p) throw new Error('Dashboard path is required');
	if (isAbsolute) return p;
	if (p.startsWith('homes/')) return p;
	if (!username) throw new Error('Cannot resolve home: no current user');
	return `homes/${username}/${p}`;
}

/** Strip the saiku-home filesystem prefix the repository listing API returns
 *  ({@code /Users/.../saiku-home/repository/data/<workspace>/homes/admin/foo})
 *  down to the repo-relative form the dashboards REST API expects
 *  ({@code homes/admin/foo}). Absolute paths from `listRepository(...)` need
 *  this; already-relative inputs (from the editor or hand-typed) pass
 *  through unchanged.
 *
 *  Also normalises any leading / trailing / duplicate slashes — some
 *  workspace seedings include a leading `/` on the JCR path
 *  (`/homes/<uuid>/foo.saikudash`) which downstream URL builders would
 *  otherwise turn into `/ui/dashboards//homes/<uuid>/foo.saikudash`. The
 *  double slash breaks SvelteKit's [...path] router on refresh
 *  (user-reported 2026-06-08). */
export function toRepoRelative(path: string): string {
	// Match anything up to and including `/data/<workspace>/`; the capture
	// group is the repo-relative remainder. Falls through to the input
	// when the pattern doesn't match (already-relative paths).
	const m = path.match(/^.*?\/data\/[^/]+\/(.+)$/);
	const candidate = m ? m[1] : path;
	return normaliseRepoPath(candidate);
}

/** Strip leading + trailing slashes and collapse duplicate slashes. Safe
 *  to call on both repo paths (`homes/admin/foo.saikudash`) and the rest
 *  segment of a URL (`/homes/admin/foo.saikudash/`). */
export function normaliseRepoPath(path: string): string {
	return path.replace(/\/+/g, '/').replace(/^\/+|\/+$/g, '');
}

/** Drop the reserved `.saikudash` extension for display. The repository
 *  stores dashboards as `<name>.saikudash` files, but the extension is an
 *  implementation detail the catalogue shouldn't surface — a path shown to
 *  a user should read `homes/admin/sales`, not `homes/admin/sales.saikudash`.
 *  Directory segments and anything before the extension pass through
 *  untouched; a path without the extension is returned unchanged. (#1605) */
export function displayPath(path: string): string {
	return path.endsWith('.saikudash') ? path.slice(0, -'.saikudash'.length) : path;
}

async function readError(res: Response): Promise<string> {
	try {
		const body = (await res.json()) as DashboardErrorResponse;
		return body.error ?? JSON.stringify(body);
	} catch {
		return res.statusText;
	}
}

function cryptoUuid(): string {
	// Same generator pattern as query.svelte.ts — avoid the global UUID
	// helper import to keep this module standalone.
	return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
		const r = (Math.random() * 16) | 0;
		const v = c === 'x' ? r : (r & 0x3) | 0x8;
		return v.toString(16);
	});
}

// Kept for forward compatibility — the inline TileQuery body uses the same
// shape ThinQuery already round-trips through the AI API.
export type { ThinQuery };

/* =========================================================================
 * PR2 clients for the merged governance/sharing backends.
 * ========================================================================= */

const SHARE_BASE = '/rest/saiku/share';

/** Load a dashboard via the account-free guest share surface (#941). Token in
 *  the `X-Saiku-Share-Token` header — no cookie. */
export async function loadSharedDashboard(token: string): Promise<Dashboard> {
	const res = await fetch(`${SHARE_BASE}/view/dashboard`, {
		headers: { 'X-Saiku-Share-Token': token, Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`loadSharedDashboard -> ${res.status}`);
	}
	return (await res.json()) as Dashboard;
}

/* --------------------------- comments (#942) --------------------------- */

export interface DashboardComment {
	id: string;
	tileId: string;
	author: string;
	body: string;
	mentions: string[];
	createdAt: number;
	deleted: boolean;
}

export async function getComments(
	dashboardPath: string,
	tileId: string
): Promise<DashboardComment[]> {
	const params = new URLSearchParams({ dashboard: dashboardPath, tile: tileId });
	const res = await fetch(`${REST_BASE}/comments?${params}`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`getComments -> ${res.status}`);
	}
	return (await res.json()) as DashboardComment[];
}

export async function postComment(
	dashboardPath: string,
	tileId: string,
	body: string
): Promise<DashboardComment> {
	const res = await fetch(`${REST_BASE}/comments`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
		body: JSON.stringify({ dashboard: dashboardPath, tile: tileId, body })
	});
	if (!res.ok) {
		throw new Error(`postComment -> ${res.status}: ${await readError(res)}`);
	}
	return (await res.json()) as DashboardComment;
}

export async function deleteComment(dashboardPath: string, commentId: string): Promise<void> {
	const params = new URLSearchParams({ dashboard: dashboardPath });
	const res = await fetch(`${REST_BASE}/comments/${encodeURIComponent(commentId)}?${params}`, {
		method: 'DELETE',
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`deleteComment -> ${res.status}`);
	}
}

/* --------------------------- history (#947) ---------------------------- */

export interface DashboardHistoryEntry {
	version: string;
	createdAt: number;
	author: string;
}

export async function getHistory(dashboardPath: string): Promise<DashboardHistoryEntry[]> {
	const res = await fetch(`${REST_BASE}/history?dashboard=${encodeURIComponent(dashboardPath)}`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`getHistory -> ${res.status}`);
	}
	return (await res.json()) as DashboardHistoryEntry[];
}

export async function getHistoryVersion(
	dashboardPath: string,
	version: string
): Promise<Dashboard> {
	const params = new URLSearchParams({ dashboard: dashboardPath, version });
	const res = await fetch(`${REST_BASE}/history/version?${params}`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`getHistoryVersion -> ${res.status}`);
	}
	return (await res.json()) as Dashboard;
}

export async function restoreHistory(dashboardPath: string, version: string): Promise<void> {
	const params = new URLSearchParams({ dashboard: dashboardPath, version });
	const res = await fetch(`${REST_BASE}/history/restore?${params}`, {
		method: 'POST',
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`restoreHistory -> ${res.status}`);
	}
}

/* ---------------------------- share mint (#941) ------------------------ */

export interface ShareMintResult {
	status: string;
	token: string;
	url: string;
	expiresAt: number;
}

export interface ShareTokenInfo {
	token: string;
	dashboardPath: string;
	createdBy: string;
	createdAt: number;
	expiresAt: number;
	revoked: boolean;
	label: string;
	active: boolean;
}

export async function mintShare(
	dashboardPath: string,
	ttlHours?: number,
	label?: string
): Promise<ShareMintResult> {
	const res = await fetch(`${SHARE_BASE}/mint`, {
		method: 'POST',
		credentials: 'include',
		headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
		body: JSON.stringify({ dashboardPath, ttlHours, label })
	});
	if (!res.ok) {
		throw new Error(`mintShare -> ${res.status}: ${await readError(res)}`);
	}
	return (await res.json()) as ShareMintResult;
}

export async function listShareTokens(): Promise<ShareTokenInfo[]> {
	const res = await fetch(`${SHARE_BASE}/tokens`, {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`listShareTokens -> ${res.status}`);
	}
	return (await res.json()) as ShareTokenInfo[];
}

export async function revokeShareToken(token: string): Promise<void> {
	const res = await fetch(`${SHARE_BASE}/tokens/${encodeURIComponent(token)}`, {
		method: 'DELETE',
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`revokeShareToken -> ${res.status}`);
	}
}

/* ---------------------- user directory (#942 follow-up) ----------------- */

export interface SaikuUserRef {
	username: string;
}

/** Minimal authenticated user list (usernames only) — powers @-mention
 *  autocomplete in comments. */
export async function getUsers(): Promise<SaikuUserRef[]> {
	const res = await fetch('/rest/saiku/api/users', {
		credentials: 'include',
		headers: { Accept: 'application/json' }
	});
	if (!res.ok) {
		throw new Error(`getUsers -> ${res.status}`);
	}
	return (await res.json()) as SaikuUserRef[];
}
