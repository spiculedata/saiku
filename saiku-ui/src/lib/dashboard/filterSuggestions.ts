/*
 * Filter-target suggestion: scan the dashboard's tiles, collect every
 * (cube, dim, hier, level) the queries already touch, dedupe, and surface
 * as candidate filter-widget targets. Lets the author pick from a
 * pre-populated list instead of choosing dim/hier/level blind through the
 * tile editor.
 *
 * Covers both branches:
 *   - inline tiles  — synchronous walk of the body's rows/columns/filters
 *     AxisSelections
 *   - reference tiles — async fetch of the saved .saiku ThinQuery
 *     followed by a walk of its queryModel.axes
 *
 * The async path runs reference fetches in parallel and tolerates
 * individual failures (a missing or unparseable .saiku just gets skipped
 * — the rest of the panel still renders).
 *
 * Pure of stores / DOM. The caller hands in the tile array (typically
 * `dashboardStore.current.layout.tiles`) and gets a deduped suggestion
 * list back.
 */

import { getResource } from "$lib/api/repository";
import type { CubeRef, DashboardTile } from "$lib/api/dashboards";

/** Fetch a saved .saiku ThinQuery and extract its cube ref. Used by the
 *  tile renderers when a reference tile was authored without an explicit
 *  cube pick — early v1 authoring left tile.cube undefined for reference
 *  binds, which broke the schema-driven filter applicability check. */
export async function inferCubeFromReference(path: string): Promise<CubeRef | null> {
  try {
    const raw = await getResource(path);
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const cube = parsed.cube as Record<string, unknown> | undefined;
    if (!cube) return null;
    const connectionName = asString(cube.connection);
    const catalog = asString(cube.catalog);
    const schema = asString(cube.schema);
    const cubeName = asString(cube.name);
    if (!connectionName || !catalog || !schema || !cubeName) return null;
    return { connectionName, catalog, schema, cubeName };
  } catch {
    return null;
  }
}

export interface FilterSuggestion {
  /** Stable id keyed off the cube+target so the UI can use it for
   *  checkboxes / animations. */
  id: string;
  cube: CubeRef;
  dimension: string;
  hierarchy: string;
  level: string;
  /** Tile ids that contributed this target — surfaced in the UI so the
   *  user knows which charts/tables a suggested filter would touch. */
  contributingTileIds: string[];
}

interface AxisRef {
  dimension?: unknown;
  hierarchy?: unknown;
  level?: unknown;
}

function readAxis(v: unknown): AxisRef[] {
  if (!Array.isArray(v)) return [];
  return v.filter((x) => x && typeof x === "object") as AxisRef[];
}

function asString(v: unknown): string | null {
  return typeof v === "string" && v.length > 0 ? v : null;
}

function cubeKey(c: CubeRef): string {
  return `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;
}

/** One (dim, hier, level) reference extracted from a tile, ready to feed
 *  into the dedupe map. */
interface ExtractedTarget {
  cube: CubeRef;
  dimension: string;
  hierarchy: string;
  level: string;
  tileId: string;
}

/** Walk an inline AiQueryRequest body's rows / columns / filters and
 *  yield every fully-populated (dim, hier, level) triple. */
function extractFromInline(tile: DashboardTile): ExtractedTarget[] {
  if (!tile.cube) return [];
  if (!tile.query || tile.query.kind !== "inline") return [];
  const body = tile.query.body as Record<string, unknown>;
  if (!body || typeof body !== "object") return [];
  const out: ExtractedTarget[] = [];
  const triples: AxisRef[] = [
    ...readAxis(body.rows),
    ...readAxis(body.columns),
    ...readAxis(body.filters),
  ];
  for (const t of triples) {
    const dim = asString(t.dimension);
    const hier = asString(t.hierarchy);
    const level = asString(t.level);
    if (!dim || !hier || !level) continue;
    out.push({ cube: tile.cube, dimension: dim, hierarchy: hier, level, tileId: tile.id });
  }
  return out;
}

/** Surface a KPI tile's time level as a candidate dimension for the
 *  "Suggest filters" panel. Lets the analyst promote a KPI's time
 *  level into a dashboard widget that then drives the slice. */
function extractFromKpi(tile: DashboardTile): ExtractedTarget[] {
  if (tile.type !== "kpi" || !tile.cube || !tile.kpi) return [];
  const tl = tile.kpi.timeLevel;
  if (!tl || !tl.dimension || !tl.hierarchy || !tl.level) return [];
  return [
    {
      cube: tile.cube,
      dimension: tl.dimension,
      hierarchy: tl.hierarchy,
      level: tl.level,
      tileId: tile.id,
    },
  ];
}

/** Walk a parsed ThinQuery JSON object's queryModel.axes and yield every
 *  (dim, hier, level) triple. ThinHierarchy carries display names directly
 *  (dimension="Store", caption="Stores"); ThinLevel.name is the level
 *  display name. Falls back to parsing the MDX unique name when the
 *  display fields are missing. */
function extractFromThinQuery(thinQueryJson: unknown): Omit<ExtractedTarget, "tileId">[] {
  if (!thinQueryJson || typeof thinQueryJson !== "object") return [];
  const tq = thinQueryJson as Record<string, unknown>;
  const cube = tq.cube as Record<string, unknown> | undefined;
  if (!cube) return [];
  const connectionName = asString(cube.connection) ?? "";
  const catalog = asString(cube.catalog) ?? "";
  const schema = asString(cube.schema) ?? "";
  const cubeName = asString(cube.name) ?? "";
  if (!connectionName || !catalog || !schema || !cubeName) return [];
  const cubeRef: CubeRef = { connectionName, catalog, schema, cubeName };

  const queryModel = tq.queryModel as Record<string, unknown> | undefined;
  if (!queryModel) return [];
  const axes = queryModel.axes as Record<string, unknown> | undefined;
  if (!axes) return [];

  const out: Omit<ExtractedTarget, "tileId">[] = [];
  for (const axisKey of Object.keys(axes)) {
    const axis = axes[axisKey] as Record<string, unknown> | undefined;
    if (!axis) continue;
    const hierarchies = axis.hierarchies as unknown;
    if (!Array.isArray(hierarchies)) continue;
    for (const h of hierarchies) {
      if (!h || typeof h !== "object") continue;
      const hier = h as Record<string, unknown>;
      const dim = asString(hier.dimension);
      // ThinHierarchy.caption is the display name (e.g. "Stores");
      // hier.name is the MDX unique name like "[Store].[Stores]" —
      // fall back to the last bracketed segment if caption is missing.
      let hierName = asString(hier.caption);
      if (!hierName) {
        const raw = asString(hier.name);
        if (raw) {
          const m = raw.match(/\[([^\]]+)\]\s*$/);
          hierName = m ? m[1] : raw;
        }
      }
      const levels = hier.levels as Record<string, unknown> | undefined;
      if (!dim || !hierName || !levels || typeof levels !== "object") continue;
      for (const levelKey of Object.keys(levels)) {
        const level = levels[levelKey] as Record<string, unknown> | undefined;
        const levelName = asString(level?.name) ?? levelKey;
        if (!levelName) continue;
        out.push({ cube: cubeRef, dimension: dim, hierarchy: hierName, level: levelName });
      }
    }
  }
  return out;
}

/** Row-axis (dim, hier, level) triple from a saved query, used by the
 *  reference-tile click-to-filter capture. Charts read the first entry;
 *  tables match clicked column captions against the level field. */
export interface RowAxisRef {
  dimension: string;
  hierarchy: string;
  level: string;
}

/** Fetch + parse a saved-query reference and return the ROW-axis
 *  (dim, hier, level) triples in declared order. Empty on any failure
 *  or when the query has no row axis (column-only or measure-only
 *  reports). Used by ChartTile / TableTile click-to-filter when the
 *  tile is bound to a saved query — inline tiles read the same shape
 *  directly off `tile.query.body.rows`. */
export async function inferRowAxesFromReference(path: string): Promise<RowAxisRef[]> {
  try {
    const raw = await getResource(path);
    const parsed = JSON.parse(raw) as unknown;
    return extractAxisRefs(parsed, "ROWS");
  } catch {
    return [];
  }
}

function extractAxisRefs(thinQueryJson: unknown, axisName: string): RowAxisRef[] {
  if (!thinQueryJson || typeof thinQueryJson !== "object") return [];
  const tq = thinQueryJson as Record<string, unknown>;
  const queryModel = tq.queryModel as Record<string, unknown> | undefined;
  if (!queryModel) return [];
  const axes = queryModel.axes as Record<string, unknown> | undefined;
  if (!axes) return [];
  const axis = axes[axisName] as Record<string, unknown> | undefined;
  if (!axis) return [];
  const hierarchies = axis.hierarchies;
  if (!Array.isArray(hierarchies)) return [];

  const out: RowAxisRef[] = [];
  for (const h of hierarchies) {
    if (!h || typeof h !== "object") continue;
    const hier = h as Record<string, unknown>;
    const dim = asString(hier.dimension);
    let hierName = asString(hier.caption);
    if (!hierName) {
      const raw = asString(hier.name);
      if (raw) {
        const m = raw.match(/\[([^\]]+)\]\s*$/);
        hierName = m ? m[1] : raw;
      }
    }
    const levels = hier.levels as Record<string, unknown> | undefined;
    if (!dim || !hierName || !levels || typeof levels !== "object") continue;
    for (const levelKey of Object.keys(levels)) {
      const level = levels[levelKey] as Record<string, unknown> | undefined;
      const levelName = asString(level?.name) ?? levelKey;
      if (!levelName) continue;
      out.push({ dimension: dim, hierarchy: hierName, level: levelName });
    }
  }
  return out;
}

/** Fetch + parse a saved-query reference into ExtractedTargets. Returns
 *  [] on any failure (missing file, unparseable JSON, missing cube) so
 *  the suggestion panel degrades gracefully. */
async function extractFromReference(tile: DashboardTile): Promise<ExtractedTarget[]> {
  if (!tile.query || tile.query.kind !== "reference") return [];
  try {
    const raw = await getResource(tile.query.path);
    const parsed = JSON.parse(raw) as unknown;
    const triples = extractFromThinQuery(parsed);
    return triples.map((t) => ({ ...t, tileId: tile.id }));
  } catch {
    return [];
  }
}

function pushTarget(map: Map<string, FilterSuggestion>, t: ExtractedTarget): void {
  const key = `${cubeKey(t.cube)}|${t.dimension}/${t.hierarchy}/${t.level}`;
  const existing = map.get(key);
  if (existing) {
    if (!existing.contributingTileIds.includes(t.tileId)) {
      existing.contributingTileIds.push(t.tileId);
    }
  } else {
    map.set(key, {
      id: key,
      cube: t.cube,
      dimension: t.dimension,
      hierarchy: t.hierarchy,
      level: t.level,
      contributingTileIds: [t.tileId],
    });
  }
}

/** Sync scan — inline tiles only. Kept for tests and callers that don't
 *  want the async fetch overhead. Reference tiles are silently skipped. */
export function suggestFiltersForTiles(tiles: DashboardTile[]): FilterSuggestion[] {
  const map = new Map<string, FilterSuggestion>();
  for (const tile of tiles) {
    for (const t of extractFromInline(tile)) pushTarget(map, t);
    for (const t of extractFromKpi(tile)) pushTarget(map, t);
  }
  return Array.from(map.values());
}

/** Async scan — covers inline tiles and async-fetches reference tiles in
 *  parallel. Returns a deduped suggestion list. */
export async function suggestFiltersForTilesAsync(tiles: DashboardTile[]): Promise<FilterSuggestion[]> {
  const map = new Map<string, FilterSuggestion>();
  for (const tile of tiles) {
    for (const t of extractFromInline(tile)) pushTarget(map, t);
    for (const t of extractFromKpi(tile)) pushTarget(map, t);
  }
  const refTiles = tiles.filter((t) => t.query?.kind === "reference");
  const refResults = await Promise.all(refTiles.map(extractFromReference));
  for (const batch of refResults) {
    for (const t of batch) pushTarget(map, t);
  }
  return Array.from(map.values());
}

/** Filter the suggestion list down to ones whose target isn't already
 *  surfaced by an existing filter-widget tile. Stops the panel offering
 *  duplicates when the author has already added some widgets. */
export function pruneAlreadyExposed(
  suggestions: FilterSuggestion[],
  tiles: DashboardTile[],
): FilterSuggestion[] {
  const exposed = new Set<string>();
  for (const tile of tiles) {
    if (tile.type !== "filter" || !tile.cube || !tile.target) continue;
    exposed.add(`${cubeKey(tile.cube)}|${tile.target.dimension}/${tile.target.hierarchy}/${tile.target.level}`);
  }
  return suggestions.filter((s) => !exposed.has(s.id));
}
