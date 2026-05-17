/*
 * Filter-target suggestion: scan the dashboard's inline tiles, collect
 * every (cube, dim, hier, level) the queries already touch, dedupe, and
 * surface as candidate filter-widget targets. Lets the author pick from a
 * pre-populated list instead of choosing dim/hier/level blind through the
 * tile editor.
 *
 * v1 covers inline tiles only — reference tiles would need an async load
 * of their saved ThinQuery to inspect. Tracked as a follow-up.
 *
 * Pure: no fetches, no DOM, no store reads. The caller hands in the tile
 * array (typically `dashboardStore.current.layout.tiles`) and receives a
 * deduped list of suggestions.
 */

import type { CubeRef, DashboardTile } from "$lib/api/dashboards";

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

/** Scan every inline tile's `body.rows`, `body.columns`, and `body.filters`
 *  for dim/hier/level triples. Returns one suggestion per unique
 *  (cube, dim, hier, level) tuple, with `contributingTileIds` collecting
 *  every tile that referenced it. */
export function suggestFiltersForTiles(tiles: DashboardTile[]): FilterSuggestion[] {
  const map = new Map<string, FilterSuggestion>();

  for (const tile of tiles) {
    if (!tile.cube) continue;
    if (!tile.query || tile.query.kind !== "inline") continue;
    const body = tile.query.body as Record<string, unknown>;
    if (!body || typeof body !== "object") continue;

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
      const key = `${cubeKey(tile.cube)}|${dim}/${hier}/${level}`;
      const existing = map.get(key);
      if (existing) {
        if (!existing.contributingTileIds.includes(tile.id)) {
          existing.contributingTileIds.push(tile.id);
        }
      } else {
        map.set(key, {
          id: key,
          cube: tile.cube,
          dimension: dim,
          hierarchy: hier,
          level,
          contributingTileIds: [tile.id],
        });
      }
    }
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
