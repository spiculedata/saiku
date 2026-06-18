import type { CubeSchema, DimPick } from "./types";

/** Mondrian "all" levels (grand-total) — never a useful row grouping. */
export function isAllLevel(levelName: string): boolean {
  return /^\(all\b/i.test(levelName.trim());
}

export interface DimLevel extends DimPick { caption: string; }
export interface DimGroup { dimension: string; showHierarchy: boolean; levels: DimLevel[]; }

export function buildDimensionTree(schema: CubeSchema, search = ""): DimGroup[] {
  const q = search.trim().toLowerCase();
  const groups: DimGroup[] = [];
  for (const [dimension, dim] of Object.entries(schema.dimensions)) {
    const showHierarchy = Object.keys(dim.hierarchies).length > 1;
    const levels: DimLevel[] = [];
    for (const [hierarchy, hier] of Object.entries(dim.hierarchies)) {
      for (const [level, meta] of Object.entries(hier.levels)) {
        if (isAllLevel(level)) continue;
        const caption = meta.name || level;
        if (q && !caption.toLowerCase().includes(q) && !dimension.toLowerCase().includes(q)) continue;
        levels.push({ dimension, hierarchy, level, caption });
      }
    }
    if (levels.length) groups.push({ dimension, showHierarchy, levels });
  }
  return groups;
}
