/*
 * AiQueryRequest → ThinQueryModel adapter.
 *
 * The /ai/ask endpoint's server-side AiSchemaConverter builds MDX directly from
 * the AiQueryRequest — it never populates a ThinQueryModel — so the
 * AskResponse.queryModel field is always null. We rebuild the queryModel
 * client-side from the AskResponse.request (the AI's typed AiQueryRequest)
 * so the workbench's chip UI gets an interactive, drag/drop-editable query
 * instead of falling back to MDX-mode paste.
 *
 * Shape mirrors what the workbench builds via includeLevel(), addMeasure(),
 * setLevelSelection(), etc. — same axes/hierarchies/levels/details schema as
 * a chip-built query, so undo/redo and all chip mutations work uniformly.
 *
 * Member selections (when the AI named specific members in a filter or axis)
 * become ThinLevel.selection with type=INCLUSION; empty members → no
 * selection (the level still appears but doesn't filter).
 */

import type {
  AxisLocation,
  ThinHierarchy,
  ThinMeasure,
  ThinQueryModel,
} from "$lib/api/query";

/**
 * The wire shape of AiQueryRequest as returned in AskResponse.request. Typed
 * loosely as `Record<string, unknown>` in aiAsk.ts; this narrows.
 */
export interface AiQueryRequestShape {
  cube?: unknown;
  measures?: { name?: string; aggregators?: unknown[] }[];
  rows?: { dimension?: string; hierarchy?: string; level?: string; members?: string[] }[];
  columns?: { dimension?: string; hierarchy?: string; level?: string; members?: string[] }[];
  filters?: { dimension?: string; hierarchy?: string; level?: string; members?: string[] }[];
  limit?: number;
  visualTotals?: boolean;
  nonEmpty?: boolean;
}

/**
 * Build a ThinQueryModel from the AI's typed request. Returns null when the
 * input is shaped wrong (no measures or no axis entries) — caller falls back
 * to MDX mode in that case.
 */
export function aiRequestToQueryModel(req: AiQueryRequestShape): ThinQueryModel | null {
  if (!req || !req.measures || req.measures.length === 0) return null;

  const model: ThinQueryModel = {
    axes: {
      FILTER: emptyAxis("FILTER"),
      COLUMNS: emptyAxis("COLUMNS"),
      ROWS: emptyAxis("ROWS"),
      PAGES: emptyAxis("PAGES"),
    },
    visualTotals: !!req.visualTotals,
    visualTotalsPattern: null,
    lowestLevelsOnly: false,
  } as ThinQueryModel;

  // Measures go into details. Each entry's `name` is the measure caption
  // (Pharma cube → "Rx Count"); the workbench treats name as the unique-key
  // for chip de-dup, and the server's Fat.convertDetails resolves it back
  // to the live cube measure by name.
  const measures: ThinMeasure[] = [];
  for (const m of req.measures) {
    const name = m?.name;
    if (!name) continue;
    measures.push({
      name,
      uniqueName: bracket(name, "Measures"),
      caption: name,
      type: "EXACT",
    });
  }
  // model.details — same shape as a chip-built query: axis/location pair +
  // the measure list. Default to TOP-of-COLUMNS, mirroring the chip default.
  (model as unknown as { details: unknown }).details = {
    axis: "COLUMNS",
    location: "TOP",
    measures,
  };

  // Hierarchy entries from each axis. The AI emits one entry per (dim,
  // hierarchy, level) — multiple entries for the same hierarchy stack as
  // multi-level chips in the workbench (Year + Quarter + Day on Date).
  applyAxisEntries(model, "ROWS", req.rows ?? []);
  applyAxisEntries(model, "COLUMNS", req.columns ?? []);
  applyAxisEntries(model, "FILTER", req.filters ?? []);

  // Toggle NON EMPTY on the row + column axes when the AI requested it.
  if (req.nonEmpty) {
    model.axes.ROWS.nonEmpty = true;
    model.axes.COLUMNS.nonEmpty = true;
  }

  return model;
}

function emptyAxis(location: AxisLocation) {
  return {
    location,
    mdx: null,
    filters: [],
    sortOrder: null,
    sortEvaluationLiteral: null,
    hierarchizeMode: null,
    hierarchies: [],
    nonEmpty: false,
  };
}

function applyAxisEntries(
  model: ThinQueryModel,
  axis: AxisLocation,
  entries: { dimension?: string; hierarchy?: string; level?: string; members?: string[] }[],
): void {
  for (const entry of entries) {
    const dim = entry.dimension;
    const hier = entry.hierarchy;
    const lvl = entry.level;
    if (!dim || !hier || !lvl) continue;
    // The workbench keys hierarchies by their `name` which is the full
    // bracketed unique-name (e.g. `[Date].[Date]`). The AI emits short
    // names; we reconstruct the bracketed form. Identical to what
    // DimensionList.svelte passes via includeLevel().
    const hierarchyUniqueName = dim === hier ? bracket(dim) : bracket(dim, hier);
    const existing = model.axes[axis].hierarchies.find((h) => h.name === hierarchyUniqueName);
    const target: ThinHierarchy =
      existing ?? {
        name: hierarchyUniqueName,
        caption: hier,
        dimension: dim,
        levels: {},
        cmembers: {},
      };
    target.levels[lvl] = {
      name: lvl,
      // Carry the AI's member picks across as an INCLUSION selection so the
      // filter survives a re-execute. Empty members → no selection (level
      // appears on the axis but doesn't filter — matches the AI's intent
      // when it specified a level without picking members).
      selection:
        entry.members && entry.members.length > 0
          ? {
              type: "INCLUSION",
              members: entry.members.map((uniqueName) => ({ uniqueName })),
            }
          : undefined,
    };
    if (!existing) model.axes[axis].hierarchies.push(target);
  }
}

/**
 * Build a Mondrian-style bracketed name like `[Date]` or `[Date].[Date]`.
 * Helper for the dim/hierarchy uniqueName reconstruction above — the AI
 * emits short names and the workbench expects bracketed forms.
 */
function bracket(...parts: string[]): string {
  return parts.map((p) => `[${p}]`).join(".");
}
