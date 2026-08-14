/*
 * Fetch a cube level's members, for surfaces that offer them as choices.
 *
 * Shared + cached because several places want the same list and none of them
 * should each hit the endpoint: the header context selector reads it to build
 * its dropdown, and the KPI tile already resolves prior-period siblings the
 * same way.
 *
 * Failures resolve to an empty list rather than throwing — a selector that
 * can't load its options degrades to static text, which is the same as not
 * having been configured. It must never take the app down with it.
 */

import type { CubeRef } from "$lib/api/dashboards";
import type { LevelMember } from "$lib/views/app/contextPill";

/** Cache key is the full cube + level coordinate, so two apps on different
 *  cubes never share a list. */
function keyFor(cube: CubeRef, dimension: string, hierarchy: string, level: string): string {
  return `${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}|${dimension}/${hierarchy}/${level}`;
}

const cache = new Map<string, Promise<LevelMember[]>>();

/** Server-side cap. Deliberately above the selector's own display cap so the
 *  caller can tell the difference between "a long level" and "a level longer
 *  than we're willing to render". */
const FETCH_LIMIT = 1000;

/**
 * All members of a level, newest fetch cached per coordinate.
 *
 * Returns `[]` when the cube or coordinate is incomplete — callers treat that
 * identically to "not configured".
 */
export function fetchLevelMembers(
  cube: CubeRef | null | undefined,
  dimension: string | undefined,
  hierarchy: string | undefined,
  level: string | undefined,
): Promise<LevelMember[]> {
  if (!cube || !dimension || !hierarchy || !level) return Promise.resolve([]);
  const key = keyFor(cube, dimension, hierarchy, level);
  const hit = cache.get(key);
  if (hit) return hit;

  const params = new URLSearchParams({
    cubeId: `${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}`,
    dimension,
    hierarchy,
    level,
    limit: String(FETCH_LIMIT),
  });
  const promise = fetch(`/rest/saiku/api/ai/members/search?${params.toString()}`, {
    credentials: "include",
    headers: { Accept: "application/json" },
  })
    .then((r) => (r.ok ? r.json() : []))
    .then((hits: unknown) => (Array.isArray(hits) ? (hits as LevelMember[]) : []))
    .catch(() => {
      // Don't cache a failure — a transient network blip shouldn't leave the
      // selector permanently empty for the life of the page.
      cache.delete(key);
      return [] as LevelMember[];
    });
  cache.set(key, promise);
  return promise;
}
