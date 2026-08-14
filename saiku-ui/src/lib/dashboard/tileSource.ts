/*
 * Tile source helpers (saiku#1803).
 *
 * A tile is bound to either a Mondrian cube (`/ai/query`) or an Ossie semantic
 * model (`/ai/ossie/query`). The two speak different vocabularies — a cube has
 * dimension / hierarchy / level and measures; a model has dataset / field and
 * metrics — and the request bodies share no fields. What they DO share is the
 * response envelope, so everything downstream of the fetch is untouched.
 *
 * `CubeRef.kind` is optional and absent means "mdx", because every dashboard
 * and app document written before this predates the field. That rule is
 * load-bearing and easy to get wrong at a call site (`c.kind === "mdx"` is
 * false for every existing document), so nothing outside this module should
 * compare the field directly — use {@link isOssieSource} / {@link sourceKind}.
 *
 * Pure: no DOM, no fetches.
 */

import type { CubeRef } from "$lib/api/dashboards";

export type SourceKind = "mdx" | "ossie";

/** The kind of a source ref, resolving the absent-means-mdx default. */
export function sourceKind(cube: CubeRef | null | undefined): SourceKind {
  return cube?.kind === "ossie" ? "ossie" : "mdx";
}

/** True when the tile queries an Ossie semantic model rather than a cube. */
export function isOssieSource(cube: CubeRef | null | undefined): boolean {
  return sourceKind(cube) === "ossie";
}

/**
 * Build a source ref for an Ossie model.
 *
 * `catalog` / `schema` / `cubeName` are filled with the model name rather than
 * left blank: a long tail of code renders `cube.cubeName` as "what this tile is
 * on" and builds cache keys from the four coordinates. Giving them a real value
 * keeps all of that correct instead of showing an empty label.
 */
export function ossieSource(connectionName: string, modelName: string): CubeRef {
  return {
    kind: "ossie",
    connectionName,
    modelName,
    catalog: modelName,
    schema: modelName,
    cubeName: modelName,
  };
}

/**
 * Fully-qualified identity, including the kind.
 *
 * The kind is part of the key on purpose: a connection can expose a cube and a
 * model of the same name, and a cache or filter-applicability check that
 * collapsed them would hand one tile the other's schema.
 */
export function sourceKey(cube: CubeRef): string {
  return isOssieSource(cube)
    ? `ossie:${cube.connectionName}/${cube.modelName ?? cube.cubeName}`
    : `mdx:${cube.connectionName}/${cube.catalog}/${cube.schema}/${cube.cubeName}`;
}

/** Human label for the source — what a tile says it is reading. */
export function sourceLabel(cube: CubeRef): string {
  return isOssieSource(cube) ? (cube.modelName ?? cube.cubeName) : cube.cubeName;
}

/** True when two refs address the same source. */
export function sameSource(a: CubeRef | null | undefined, b: CubeRef | null | undefined): boolean {
  if (!a || !b) return false;
  return sourceKey(a) === sourceKey(b);
}
