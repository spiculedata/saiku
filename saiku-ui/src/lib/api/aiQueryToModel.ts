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
    // Mondrian hierarchy unique names ALWAYS follow the `[dim].[hier]`
    // form, even when the hierarchy name matches the dimension name
    // (the Pharma cube's Date dimension has its default hierarchy also
    // called "Date", and the unique name is `[Date].[Date]`). A previous
    // attempt to short-circuit to just `[Date]` when dim===hier silently
    // failed Mondrian's hierarchy lookup in Fat.java's queryModel→MDX
    // path — the server stripped the unresolvable hierarchy from the axis
    // and the result came back as 1 row × 2 cols (just the measure label
    // + value, no axis content). Visible in the launcher log as
    //   "SELECT NON EMPTY {[Measures].[Quantity]} ON COLUMNS FROM [Pharma Rx]"
    // — no CROSSJOINs because the Geography / Date axes silently emptied.
    const hierarchyUniqueName = bracket(dim, hier);
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

/**
 * Inverse of {@link aiRequestToQueryModel} — serialise the current chip-built
 * query (or any prior AI-built one) into the AiQueryRequest shape the LLM was
 * trained to emit. We feed this back into the next /ai/ask call as
 * {@code currentQuery} so the LLM has full context on what the user already
 * has on screen — and can EXTEND rather than REPLACE on follow-up turns like
 * "add therapeutic class to columns".
 *
 * Lossy: we don't carry order/limit/visualTotals/named-sets/calculated members
 * here — those are rarely set via chip flow and the LLM doesn't need them as
 * context to make a sensible extension. If we find the AI is forgetting them
 * on follow-ups, add them to this projection.
 */
export function queryModelToAiRequest(
  model: unknown,
  cube: { connectionName: string; catalog: string; schema: string; cubeName: string },
): AiQueryRequestShape | null {
  if (!model || typeof model !== "object") return null;
  const m = model as {
    axes?: Record<string, { hierarchies?: unknown[]; nonEmpty?: boolean }>;
    details?: { measures?: { name?: string }[] };
  };
  const req: AiQueryRequestShape = {
    cube,
    measures: [],
    rows: [],
    columns: [],
    filters: [],
  };
  for (const me of m.details?.measures ?? []) {
    if (me?.name) req.measures!.push({ name: me.name });
  }
  collectAxisEntries(req.rows!, m.axes?.ROWS?.hierarchies);
  collectAxisEntries(req.columns!, m.axes?.COLUMNS?.hierarchies);
  collectAxisEntries(req.filters!, m.axes?.FILTER?.hierarchies);
  req.nonEmpty = !!(m.axes?.ROWS?.nonEmpty || m.axes?.COLUMNS?.nonEmpty);
  return req;
}

function collectAxisEntries(out: NonNullable<AiQueryRequestShape["rows"]>, hiers: unknown): void {
  if (!Array.isArray(hiers)) return;
  for (const h of hiers as Array<{
    caption?: string;
    dimension?: string;
    levels?: Record<string, { selection?: { members?: Array<{ uniqueName?: string }> } }>;
  }>) {
    if (!h || !h.dimension || !h.levels) continue;
    for (const [lvlName, lvl] of Object.entries(h.levels)) {
      const memberUniques =
        lvl?.selection?.members
          ?.map((mem) => mem?.uniqueName)
          .filter((u): u is string => !!u) ?? [];
      out.push({
        dimension: h.dimension,
        hierarchy: h.caption ?? h.dimension,
        level: lvlName,
        members: memberUniques,
      });
    }
  }
}
