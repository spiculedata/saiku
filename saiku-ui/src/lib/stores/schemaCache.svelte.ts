/*
 * Per-cube AiSchema cache. Tiles need a schema to:
 *   - decide whether an ActiveFilter applies (does the cube have this
 *     dim/hier/level?)
 *   - populate filter-widget member dropdowns
 *   - resolve alias maps when matching filter dimensions to canonical
 *     schema names.
 *
 * The cache is lazy: tiles call `schemaCache.get(cube)` and the store
 * fetches on miss, deduplicates concurrent requests for the same cube,
 * and pins the resolved value until `clear()` is called (which the
 * editor only does on logout / explicit reload).
 *
 * The AiSchema type lives server-side; the wire shape is rich (measures,
 * dimensions, hierarchies, alias maps). The store keeps it as a loose
 * record here — the typed shape will land alongside the filter
 * applicability check in task #12 when we know exactly which fields the
 * client reads.
 */

import type { CubeRef } from "$lib/api/dashboards";
import { isOssieSource } from "$lib/dashboard/tileSource";

/** Loose schema shape — narrowed when the applicability check needs it. */
export type AiSchemaLike = Record<string, unknown>;

function cubeKey(cube: CubeRef): string {
  return `${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}`;
}

class SchemaCacheStore {
  private resolved = new Map<string, AiSchemaLike>();
  private inflight = new Map<string, Promise<AiSchemaLike>>();
  /** Exposed for reactive consumers — bumps on every cache write so a
   *  $derived can re-pull. */
  version = $state<number>(0);

  /** Get the schema for a cube, fetching on miss. Concurrent calls for
   *  the same cube share a single in-flight promise. */
  async get(cube: CubeRef): Promise<AiSchemaLike> {
    const key = cubeKey(cube);
    const cached = this.resolved.get(key);
    if (cached) return cached;

    // saiku#1821: an Ossie semantic model has no MDX schema, and asking the
    // cube endpoint for one fails. A failed fetch is not cached (see the
    // rejection path below), so every caller that primes schemas on an
    // interaction — the filter panel does it on hover AND on focus — fired a
    // fresh doomed request each time, and each rejection still ticked the
    // reactive version through the surrounding re-render. The visible symptom
    // was a filter you could not click: focusing the <select> re-primed, the
    // panel re-rendered, and the browser closed the open dropdown.
    //
    // Answer definitively and cache it: a model has no dimensions to resolve an
    // MDX dim/hier/level against, which is exactly what applicability checks
    // need to know.
    if (isOssieSource(cube)) {
      const empty = { dimensions: {} } as AiSchemaLike;
      this.resolved.set(key, empty);
      return empty;
    }
    const inflight = this.inflight.get(key);
    if (inflight) return inflight;

    const p = this.fetchSchema(cube).then(
      (schema) => {
        this.resolved.set(key, schema);
        this.inflight.delete(key);
        this.version++;
        return schema;
      },
      (err) => {
        this.inflight.delete(key);
        throw err;
      },
    );
    this.inflight.set(key, p);
    return p;
  }

  /** Synchronous accessor — returns the cached schema or null. Callers
   *  that need to check applicability without awaiting use this. */
  peek(cube: CubeRef): AiSchemaLike | null {
    return this.resolved.get(cubeKey(cube)) ?? null;
  }

  clear(): void {
    this.resolved.clear();
    this.inflight.clear();
    this.version++;
  }

  /** Internal fetch — kept private so call sites don't bypass the cache.
   *  The AI Schema REST path matches AiQueryResource @ /saiku/api/ai. */
  private async fetchSchema(cube: CubeRef): Promise<AiSchemaLike> {
    const path =
      `/rest/saiku/api/ai/schema/${encodeURIComponent(cube.connectionName)}` +
      `/${encodeURIComponent(cube.catalog)}` +
      `/${encodeURIComponent(cube.schema)}` +
      `/${encodeURIComponent(cube.cubeName)}`;
    const res = await fetch(path, {
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    if (!res.ok) throw new Error(`schema fetch -> ${res.status}`);
    return (await res.json()) as AiSchemaLike;
  }
}

export const schemaCache = new SchemaCacheStore();
