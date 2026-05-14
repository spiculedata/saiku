/*
 * Pure helpers that back the schema-generator left-pane tree view.
 *
 * The components (SchemaTree.svelte / NodeDrawer.svelte) stay thin; all of the
 * non-trivial logic — flattening a DraftView into a tree, looking up nodes by
 * path, mapping the backend provenance enum to a user-facing badge label —
 * lives here so it can be unit-tested without a browser.
 *
 * Path convention mirrors the backend `OpTarget.path` representation:
 *   cubes/<cube>
 *   cubes/<cube>/dimensions/<dim>
 *   cubes/<cube>/dimensions/<dim>/hierarchies/<hier>
 *   cubes/<cube>/dimensions/<dim>/hierarchies/<hier>/levels/<level>
 *   cubes/<cube>/measures/<measure>
 *   sharedDimensions/<dim>
 *   sharedDimensions/<dim>/hierarchies/<hier>
 *   sharedDimensions/<dim>/hierarchies/<hier>/levels/<level>
 */

import type {
  CubeView,
  DimView,
  DraftView,
  HierarchyView,
  LevelView,
  MeasureView,
  Provenance,
  ProvenanceSource,
  SharedDimView,
} from "$lib/api/schemaGen";

export type TreeNodeKind =
  | "schema"
  | "cubesGroup"
  | "sharedDimsGroup"
  | "cube"
  | "dimension"
  | "hierarchy"
  | "level"
  | "measure"
  | "sharedDim";

export interface TreeNode {
  path: string;
  label: string;
  kind: TreeNodeKind;
  provenance: ProvenanceSource | null;
  ruleId: string | null;
  children: TreeNode[];
}

function prov(p: Provenance | null | undefined): {
  source: ProvenanceSource | null;
  ruleId: string | null;
} {
  if (!p) return { source: null, ruleId: null };
  return { source: p.source, ruleId: p.ruleId ?? null };
}

function levelNode(l: LevelView, base: string): TreeNode {
  const path = `${base}/levels/${l.name}`;
  const { source, ruleId } = prov(l.provenance);
  return {
    path,
    label: l.caption ?? l.name,
    kind: "level",
    provenance: source,
    ruleId,
    children: [],
  };
}

function hierarchyNode(h: HierarchyView, base: string): TreeNode {
  const path = `${base}/hierarchies/${h.name}`;
  const { source, ruleId } = prov(h.provenance);
  return {
    path,
    label: h.caption ?? h.name,
    kind: "hierarchy",
    provenance: source,
    ruleId,
    children: h.levels.map((l) => levelNode(l, path)),
  };
}

function dimensionNode(d: DimView, base: string): TreeNode {
  const path = `${base}/dimensions/${d.name}`;
  const { source, ruleId } = prov(d.provenance);
  return {
    path,
    label: d.caption ?? d.name,
    kind: "dimension",
    provenance: source,
    ruleId,
    children: d.hierarchies.map((h) => hierarchyNode(h, path)),
  };
}

function measureNode(m: MeasureView, base: string): TreeNode {
  const path = `${base}/measures/${m.name}`;
  const { source, ruleId } = prov(m.provenance);
  return {
    path,
    label: m.caption ?? m.name,
    kind: "measure",
    provenance: source,
    ruleId,
    children: [],
  };
}

function cubeNode(c: CubeView): TreeNode {
  const path = `cubes/${c.name}`;
  const { source, ruleId } = prov(c.provenance);
  return {
    path,
    label: c.caption ?? c.name,
    kind: "cube",
    provenance: source,
    ruleId,
    children: [
      ...c.dimensions.map((d) => dimensionNode(d, path)),
      ...c.measures.map((m) => measureNode(m, path)),
    ],
  };
}

function sharedDimNode(d: SharedDimView): TreeNode {
  const path = `sharedDimensions/${d.name}`;
  const { source, ruleId } = prov(d.provenance);
  return {
    path,
    label: d.caption ?? d.name,
    kind: "sharedDim",
    provenance: source,
    ruleId,
    children: d.hierarchies.map((h) => hierarchyNode(h, path)),
  };
}

/**
 * Build the display tree for a DraftView. Returns a single-element array with
 * the schema root (so the caller can render uniformly), or an empty array when
 * the draft is null/undefined.
 */
export function buildTree(draft: DraftView | null | undefined): TreeNode[] {
  if (!draft) return [];
  const children: TreeNode[] = [];
  if (draft.sharedDimensions.length > 0) {
    children.push({
      path: "sharedDimensions",
      label: "Shared Dimensions",
      kind: "sharedDimsGroup",
      provenance: null,
      ruleId: null,
      children: draft.sharedDimensions.map((d) => sharedDimNode(d)),
    });
  }
  children.push({
    path: "cubes",
    label: "Cubes",
    kind: "cubesGroup",
    provenance: null,
    ruleId: null,
    children: draft.cubes.map((c) => cubeNode(c)),
  });
  return [
    {
      path: "",
      label: draft.schemaName,
      kind: "schema",
      provenance: null,
      ruleId: null,
      children,
    },
  ];
}

/** Depth-first search for a node with the given path. Returns null if not found. */
export function findNode(nodes: TreeNode[], path: string): TreeNode | null {
  for (const n of nodes) {
    if (n.path === path) return n;
    const hit = findNode(n.children, path);
    if (hit !== null) return hit;
  }
  return null;
}

/**
 * Display label for a provenance source (the small badge text). Returns
 * lowercase tokens so the UI can style uniformly; null/unknown sources produce
 * an empty string so callers can skip rendering the badge.
 */
export function provenanceBadgeLabel(source: ProvenanceSource | null): string {
  switch (source) {
    case "RULE":
      return "rule";
    case "LLM":
      return "llm";
    case "USER":
      return "user";
    default:
      return "";
  }
}

/** Resolve the underlying DraftView node (not a TreeNode) at a given path. */
export function resolveDraftNode(
  draft: DraftView | null | undefined,
  path: string,
):
  | { kind: "cube"; node: CubeView }
  | { kind: "dimension"; node: DimView; cube: CubeView }
  | {
      kind: "hierarchy";
      node: HierarchyView;
      parent: DimView | SharedDimView;
    }
  | {
      kind: "level";
      node: LevelView;
      parent: HierarchyView;
    }
  | { kind: "measure"; node: MeasureView; cube: CubeView }
  | { kind: "sharedDim"; node: SharedDimView }
  | null {
  if (!draft || !path) return null;
  const parts = path.split("/");
  if (parts[0] === "cubes" && parts.length >= 2) {
    const cube = draft.cubes.find((c) => c.name === parts[1]);
    if (!cube) return null;
    if (parts.length === 2) return { kind: "cube", node: cube };
    if (parts[2] === "dimensions" && parts.length >= 4) {
      const dim = cube.dimensions.find((d) => d.name === parts[3]);
      if (!dim) return null;
      if (parts.length === 4) return { kind: "dimension", node: dim, cube };
      if (parts[4] === "hierarchies" && parts.length >= 6) {
        const hier = dim.hierarchies.find((h) => h.name === parts[5]);
        if (!hier) return null;
        if (parts.length === 6)
          return { kind: "hierarchy", node: hier, parent: dim };
        if (parts[6] === "levels" && parts.length === 8) {
          const lvl = hier.levels.find((l) => l.name === parts[7]);
          if (!lvl) return null;
          return { kind: "level", node: lvl, parent: hier };
        }
      }
    }
    if (parts[2] === "measures" && parts.length === 4) {
      const m = cube.measures.find((x) => x.name === parts[3]);
      if (!m) return null;
      return { kind: "measure", node: m, cube };
    }
  }
  if (parts[0] === "sharedDimensions" && parts.length >= 2) {
    const dim = draft.sharedDimensions.find((d) => d.name === parts[1]);
    if (!dim) return null;
    if (parts.length === 2) return { kind: "sharedDim", node: dim };
    if (parts[2] === "hierarchies" && parts.length >= 4) {
      const hier = dim.hierarchies.find((h) => h.name === parts[3]);
      if (!hier) return null;
      if (parts.length === 4)
        return { kind: "hierarchy", node: hier, parent: dim };
      if (parts[4] === "levels" && parts.length === 6) {
        const lvl = hier.levels.find((l) => l.name === parts[5]);
        if (!lvl) return null;
        return { kind: "level", node: lvl, parent: hier };
      }
    }
  }
  return null;
}
