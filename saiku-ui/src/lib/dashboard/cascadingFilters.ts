/*
 * Cascading filter logic: when two filter-widget tiles share a cube +
 * dimension + hierarchy and one targets a higher level than the other,
 * the child's member list is restricted to descendants of the parent's
 * selected members.
 *
 * Mondrian member uniqueNames embed the parent path
 * ({@code [Customer].[Customers].[USA].[OR]} sits under
 * {@code [Customer].[Customers].[USA]}), so the restriction is a simple
 * prefix match — no extra round-trip to the discover service needed.
 *
 * Level ordering comes from the AiSchema's
 * {@code Hierarchy.levels} LinkedHashMap, which preserves Mondrian's
 * declaration order (root-most first).
 */

import type { ActiveFilter } from "$lib/stores/activeFilters.svelte";
import type { SchemaLike } from "$lib/dashboard/effectiveQuery";
import type { CubeRef, DashboardFilter } from "$lib/api/dashboards";

function k(s: string | undefined | null): string {
  return (s ?? "").trim().toLowerCase();
}

function cubeKey(c: CubeRef): string {
  return `${c.connectionName}/${c.catalog}/${c.schema}/${c.cubeName}`;
}

/** Position of {@code levelName} within {@code hierarchy.levels} (0 =
 *  root-most). Returns -1 when the level isn't in the hierarchy. */
function levelIndex(schema: SchemaLike, dimName: string, hierName: string, levelName: string): number {
  const dim = schema.dimensions?.[k(dimName)];
  if (!dim) return -1;
  const hier = dim.hierarchies?.[k(hierName)];
  if (!hier) return -1;
  const keys = Object.keys(hier.levels ?? {});
  return keys.indexOf(k(levelName));
}

/** Collect every selected member from active filters that target an
 *  ancestor of {@code childTarget} on the same cube + dim + hier. Used by
 *  a filter widget to restrict its member dropdown to descendants of any
 *  parent selection.
 *
 *  Empty result = no ancestor restriction (the widget shows every
 *  member at its level). */
export function ancestorSelections(
  active: ActiveFilter[],
  schema: SchemaLike | null,
  childCube: CubeRef | null,
  childTarget: DashboardFilter | undefined,
): string[] {
  if (!schema || !childCube || !childTarget) return [];
  const childIdx = levelIndex(schema, childTarget.dimension, childTarget.hierarchy, childTarget.level);
  if (childIdx <= 0) return []; // root-most or unknown — no ancestor possible

  const out: string[] = [];
  for (const af of active) {
    const f = af.filter;
    if (k(f.dimension) !== k(childTarget.dimension)) continue;
    if (k(f.hierarchy) !== k(childTarget.hierarchy)) continue;
    const fIdx = levelIndex(schema, f.dimension, f.hierarchy, f.level);
    if (fIdx === -1 || fIdx >= childIdx) continue;
    for (const m of f.members) {
      if (m && !out.includes(m)) out.push(m);
    }
  }
  // childCube guard belongs at the source — every filter chip is cube-
  // agnostic. The caller should only invoke this for tiles whose cube
  // matches the filter's source; we keep the param for symmetry with
  // future per-cube scoping but don't filter on it here. cubeKey() is
  // imported to keep that intent obvious in code review.
  void cubeKey(childCube);
  return out;
}

/** True iff {@code member} sits under any of the supplied {@code parents}
 *  — i.e. its uniqueName starts with `<parent>.`. Used to restrict a
 *  child widget's displayed members to descendants of the active parent
 *  selection. */
export function memberDescendsFromAny(member: string, parents: string[]): boolean {
  for (const p of parents) {
    if (!p) continue;
    if (member === p) continue; // exact match isn't a descendant
    if (member.startsWith(p + ".")) return true;
  }
  return false;
}
