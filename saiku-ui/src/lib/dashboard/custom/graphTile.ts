/*
 * Pure transform + config validator for the `graph` custom tile renderer
 * (App Builder Phase 2, saiku#1441).
 *
 * The `graph` renderer turns a query's RECORDS into an ECharts `graph` series
 * (nodes + edges) — e.g. an ownership / relationship graph. Unlike the
 * `echarts-option` renderer (which projects to categories + series), a graph
 * needs the per-row endpoint columns, so this module operates on the raw
 * records the query returns (`response.data` in-app; the token-scoped `rows` in
 * the embed) rather than the {categories, series} projection.
 *
 * Config is a DECLARATIVE column mapping (Tier-1 — NO code):
 *
 *   { idCol, labelCol?, sourceCol, targetCol, valueCol?, layout? }
 *
 *   - sourceCol / targetCol  — each row is one directed link source → target.
 *   - idCol                  — the column that carries a node's canonical id;
 *                              lets `labelCol` attach a display name to the
 *                              right node. For a plain edge-list, set it equal
 *                              to sourceCol.
 *   - labelCol               — optional friendly name for the idCol node.
 *   - valueCol               — optional numeric measure; carried onto each link
 *                              and summed into node weight.
 *   - layout                 — "force" (default) | "circular" (renderer only).
 *
 * Record cells are either plain strings (dimension captions) or the typed
 * {value, formatted} cell envelope (measures) — this module reads both without
 * importing `$lib` (echartsOption.ts stays self-contained for the same reason:
 * the embed bundle has no `$lib` alias). Pure: no Svelte, no DOM, never throws.
 */

/** One graph node. `id` is the dedupe key; `name` is the display label
 *  (defaults to the id). `value` is an optional weight (sum of incident link
 *  values when a `valueCol` is configured). */
export interface GraphNode {
  id: string;
  name: string;
  value?: number;
}

/** One directed graph link (edge) source → target, with an optional carried
 *  numeric `value`. */
export interface GraphLink {
  source: string;
  target: string;
  value?: number;
}

/** The nodes + links a `graph` series consumes. */
export interface GraphData {
  nodes: GraphNode[];
  links: GraphLink[];
}

/** Supported graph layouts (ECharts `series.layout`). */
export type GraphLayout = "force" | "circular";

/** Declarative column mapping for the `graph` renderer. */
export interface GraphConfig {
  /** Column carrying a node's canonical id (for label attachment). */
  idCol: string;
  /** Optional column carrying a node's display label. */
  labelCol?: string;
  /** Column carrying each link's source endpoint. */
  sourceCol: string;
  /** Column carrying each link's target endpoint. */
  targetCol: string;
  /** Optional numeric column carried onto links + summed into node weight. */
  valueCol?: string;
  /** Layout hint for the renderer. */
  layout?: GraphLayout;
}

/** Result of {@link validateGraphConfig}. Shape-compatible with the tile
 *  registry's {@code ValidateOptionsResult} so it can back a
 *  {@code TileRenderer.validateOptions}. */
export type ValidateGraphConfigResult =
  { ok: true; value: GraphConfig } | { ok: false; error: string };

/** The typed {value, formatted} cell envelope (a measure cell). Detected
 *  structurally to avoid importing `$lib/api/aiQuery` (keeps this module
 *  importable from the alias-less embed bundle). */
interface CellLike {
  value: number | null;
  formatted?: string;
}

function isCellLike(v: unknown): v is CellLike {
  return typeof v === "object" && v !== null && "value" in (v as object);
}

/** Read a display string from a record cell: the `formatted` text of a measure
 *  cell, a bare string, or a stringified number. Missing / null → "". */
function cellText(v: unknown): string {
  if (v === null || v === undefined) return "";
  if (typeof v === "string") return v.trim();
  if (typeof v === "number") return Number.isFinite(v) ? String(v) : "";
  if (isCellLike(v)) {
    if (typeof v.formatted === "string" && v.formatted.trim().length > 0)
      return v.formatted.trim();
    return v.value === null || v.value === undefined ? "" : String(v.value);
  }
  return "";
}

/** Read a numeric value from a record cell: a measure cell's `value`, a bare
 *  number, or a numeric string. Non-numeric → null. */
function cellNumber(v: unknown): number | null {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (isCellLike(v))
    return typeof v.value === "number" && Number.isFinite(v.value)
      ? v.value
      : null;
  if (typeof v === "string") {
    const trimmed = v.trim();
    if (trimmed === "") return null;
    const n = Number(trimmed);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

/**
 * Turn query records into graph nodes + links using a declarative column
 * mapping. Behaviour:
 *
 *  - Each record becomes one link `sourceCol → targetCol`; records missing
 *    either endpoint (empty / null) are SKIPPED.
 *  - Nodes are collected from every source + target endpoint and DEDUPED by id
 *    (a node appearing as both a source and a target across rows yields one
 *    node).
 *  - When `valueCol` is set, its numeric value is carried onto the link and
 *    summed into both endpoints' node weight.
 *  - When `labelCol` is set, its text names the node whose id matches the
 *    record's `idCol` value (first non-empty label wins).
 *
 * Tolerates a non-array / empty / malformed input by returning
 * `{ nodes: [], links: [] }`. Pure; never mutates its input.
 */
export function recordsToGraph(
  records: unknown,
  config: GraphConfig,
): GraphData {
  if (!Array.isArray(records) || records.length === 0)
    return { nodes: [], links: [] };

  const nodes = new Map<string, GraphNode>();
  const links: GraphLink[] = [];

  const ensureNode = (id: string): GraphNode => {
    let node = nodes.get(id);
    if (!node) {
      node = { id, name: id };
      nodes.set(id, node);
    }
    return node;
  };

  for (const rec of records) {
    if (!rec || typeof rec !== "object" || Array.isArray(rec)) continue;
    const row = rec as Record<string, unknown>;

    const source = cellText(row[config.sourceCol]);
    const target = cellText(row[config.targetCol]);
    if (source === "" || target === "") continue; // skip rows missing an endpoint

    const sourceNode = ensureNode(source);
    const targetNode = ensureNode(target);

    // Optional label: attach to the node this row identifies via idCol.
    if (config.labelCol) {
      const idVal = cellText(row[config.idCol]);
      const label = cellText(row[config.labelCol]);
      if (idVal !== "" && label !== "") {
        const node = nodes.get(idVal);
        // Only override the default (id) name, so the first label wins.
        if (node && node.name === node.id) node.name = label;
      }
    }

    const link: GraphLink = { source, target };
    if (config.valueCol) {
      const value = cellNumber(row[config.valueCol]);
      if (value !== null) {
        link.value = value;
        sourceNode.value = (sourceNode.value ?? 0) + value;
        targetNode.value = (targetNode.value ?? 0) + value;
      }
    }
    links.push(link);
  }

  return { nodes: [...nodes.values()], links };
}

/** Non-empty-string guard for a required config field. */
function isNonEmptyString(v: unknown): v is string {
  return typeof v === "string" && v.trim().length > 0;
}

/**
 * Validate an author-supplied graph column mapping. Requires `idCol`,
 * `sourceCol` and `targetCol` to be non-empty strings; `labelCol` / `valueCol`
 * are optional strings; `layout` is coerced to "force" | "circular" (default
 * "force"). Returns a fresh, trimmed {@link GraphConfig} on success or a
 * human-readable reason on failure. Never throws.
 *
 * Shape-compatible with {@code TileRenderer.validateOptions}.
 */
export function validateGraphConfig(o: unknown): ValidateGraphConfigResult {
  if (typeof o !== "object" || o === null || Array.isArray(o)) {
    return { ok: false, error: "Graph config must be a JSON object." };
  }
  const obj = o as Record<string, unknown>;

  for (const key of ["idCol", "sourceCol", "targetCol"] as const) {
    if (!isNonEmptyString(obj[key])) {
      return {
        ok: false,
        error: `"${key}" is required and must be a non-empty string.`,
      };
    }
  }
  for (const key of ["labelCol", "valueCol"] as const) {
    const v = obj[key];
    if (v !== undefined && v !== null && typeof v !== "string") {
      return { ok: false, error: `"${key}" must be a string.` };
    }
  }

  const value: GraphConfig = {
    idCol: (obj.idCol as string).trim(),
    sourceCol: (obj.sourceCol as string).trim(),
    targetCol: (obj.targetCol as string).trim(),
    layout: obj.layout === "circular" ? "circular" : "force",
  };
  if (isNonEmptyString(obj.labelCol)) value.labelCol = obj.labelCol.trim();
  if (isNonEmptyString(obj.valueCol)) value.valueCol = obj.valueCol.trim();

  return { ok: true, value };
}

/* -------------------------------------------------------------------------
 * Node symbol sizing (saiku#1755)
 *
 * Sizing must be RELATIVE to the weights in the graph, not absolute. The
 * original `min(60, 20 + sqrt(value))` saturated above value 1600, so a graph
 * weighted by any real measure — revenue, script volume, headcount — rendered
 * every node at the cap and the weighting told the reader nothing.
 * ---------------------------------------------------------------------- */

/** Smallest node diameter, in px — the lightest weighted node. */
export const NODE_SIZE_MIN = 20;
/** Largest node diameter, in px — the heaviest weighted node. */
export const NODE_SIZE_MAX = 60;
/** Diameter for a node with no usable weight, and for every node when the
 *  graph carries no `valueCol` at all. Sits inside the band so an unweighted
 *  graph looks deliberate rather than uniformly tiny. */
export const NODE_SIZE_DEFAULT = 24;

/** The span of usable weights across `nodes`, or null when none carries one
 *  (no `valueCol`, or every value missing / non-finite).
 *
 *  Zero and negative weights COUNT: a measure can legitimately be 0 (a region
 *  with no sales) or negative (a loss, a returns line), and excluding them
 *  would push those nodes onto the "unweighted" default — rendering a
 *  zero-weight node LARGER than a small positive one. */
export function weightRange(
  nodes: readonly GraphNode[],
): { min: number; max: number } | null {
  const values = nodes
    .map((n) => n.value)
    .filter((v): v is number => typeof v === "number" && Number.isFinite(v));
  if (values.length === 0) return null;
  return { min: Math.min(...values), max: Math.max(...values) };
}

/** Map one node weight onto the symbol-size band, relative to `range`.
 *
 *  `sqrt` on the NORMALISED position (not the raw value) keeps the mid-range
 *  legible without letting one huge node flatten everything else, and — unlike
 *  the absolute scale it replaces — the largest node always reaches the top of
 *  the band whatever the units are. A degenerate range (every node equal)
 *  yields one consistent mid-band size. */
export function nodeSize(
  value: number | undefined,
  range: { min: number; max: number } | null,
): number {
  if (range === null) return NODE_SIZE_DEFAULT;
  if (typeof value !== "number" || !Number.isFinite(value))
    return NODE_SIZE_DEFAULT;
  const span = range.max - range.min;
  if (span <= 0) return (NODE_SIZE_MIN + NODE_SIZE_MAX) / 2;
  const clamped = Math.min(Math.max(value, range.min), range.max);
  const t = (clamped - range.min) / span;
  return NODE_SIZE_MIN + (NODE_SIZE_MAX - NODE_SIZE_MIN) * Math.sqrt(t);
}
