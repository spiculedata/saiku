/**
 * Workbench ⇄ doc cube model (saiku-cloud#1039 split).
 *
 * The durable cube model lives on the doc (`SchemaCanvasCube` — a trimmed
 * shape of measure groups + calcs). The workbench keeps a richer live
 * `WorkbenchCube` carrying UI-only flags (edit modes, sequences, confirm
 * state). These pure helpers translate between the two shapes and render a
 * calculated member's chip tokens back to a Mondrian formula.
 *
 * Extracted from `WorkbenchView.svelte` so the mapping is unit-testable in
 * the node-env vitest (no Svelte render harness). The component imports
 * these; nothing here touches Svelte reactivity.
 */
import type {
  SchemaCanvasCube,
  SchemaCanvasMeasureGroup as DocMeasureGroup,
  SchemaCanvasCalc as DocCalc,
} from "./types.js";

// ── Calculated members ──────────────────────────────────────────────
//   - `build`      — drag/click chips: alternating measure / op tokens
//                    that always stay well-formed.
//   - `expression` — free-text formula for anything the chip builder
//                    can't express (CASE, IIF, ratios with constants).
export type FactsCalcToken =
  | { kind: "measure"; name: string; fromGroup?: boolean }
  | { kind: "op"; op: "+" | "-" | "*" | "/" };
export type FactsCalcMode = "build" | "expression";
export type FactsCalc = {
  id: string;
  name: string;
  tokens: FactsCalcToken[];
  formula?: string;
  mode?: FactsCalcMode;
};

export type FactsMeasureGroup = {
  id: string;
  name: string;
  measureColumns: string[];
};

// v2: FactLink (degenerate dim on the fact table) + ReferenceLink
// (multi-hop dim via another dim's attribute) round-trip support.
// `linkKind` undefined ≡ 'foreign-key' (the v1 default).
export type MeasureGroupDimLink = {
  dimensionId: string;
  foreignKeyColumn: string;
  linkKind?: "foreign-key" | "fact" | "reference";
  viaDimension?: string;
  viaAttribute?: string;
};

export type WorkbenchMeasureGroup = FactsMeasureGroup & {
  factTableId?: string | null;
  dimensionLinks?: MeasureGroupDimLink[];
  // Per-section commit flags — the per-MG editor collapses into a
  // summary once each section is confirmed (fact → measures → dims).
  factConfirmed?: boolean;
  measuresConfirmed?: boolean;
  dimsConfirmed?: boolean;
  // Human captions for measures — round-trip into Mondrian 4 as
  // `<Measure caption="Unit Sales" ...>`. Keyed by column name.
  measureCaptions?: Record<string, string>;
};

export type WorkbenchCube = {
  id: string;
  name: string;
  // All per-cube live state. Mirrors the persisted facts shape minus
  // workbench-wide UI prefs (slots / collapse / inspector tab).
  editMode: boolean;
  selectedTableId: string | null;
  tableConfirmed: boolean;
  selectedMeasures: string[];
  tableSearch: string;
  measureGroups: WorkbenchMeasureGroup[];
  selectedGroupId: string | null;
  groupsEditMode: boolean;
  groupSeq: number;
  factsCalcs: FactsCalc[];
  selectedCalcId: string | null;
  calcsEditMode: boolean;
  calcSeq: number;
};

/** Highest `<n>` matched by `re` (capture group 1) across `ids`, or 0. */
export function maxSeq(ids: string[], re: RegExp): number {
  let max = 0;
  for (const id of ids) {
    const m = re.exec(id);
    if (m) max = Math.max(max, parseInt(m[1], 10));
  }
  return max;
}

export function blankCube(id: string, name: string): WorkbenchCube {
  return {
    id,
    name,
    editMode: true,
    selectedTableId: null,
    tableConfirmed: false,
    selectedMeasures: [],
    tableSearch: "",
    measureGroups: [],
    selectedGroupId: null,
    groupsEditMode: true,
    groupSeq: 0,
    factsCalcs: [],
    selectedCalcId: null,
    calcsEditMode: true,
    calcSeq: 0,
  };
}

export function calcFromDoc(c: DocCalc): FactsCalc {
  return {
    id: c.id,
    name: c.name,
    tokens: c.tokens.map((t) =>
      t.kind === "op"
        ? { kind: "op", op: t.op ?? "+" }
        : { kind: "measure", name: t.name ?? "", fromGroup: t.fromGroup },
    ),
    formula: c.formula,
    mode: c.mode,
  };
}

export function mgFromDoc(g: DocMeasureGroup): WorkbenchMeasureGroup {
  return {
    id: g.id,
    name: g.name,
    measureColumns: [...g.measureColumns],
    factTableId: g.factTableId ?? null,
    dimensionLinks: (g.dimensionLinks ?? []).map((l) => ({ ...l })),
    measureCaptions: g.measureCaptions ? { ...g.measureCaptions } : {},
    // Re-derive the confirm flags from content: an imported / DimSum
    // cube that already has a fact + measures + links reads as
    // confirmed; a blank group opens in edit mode.
    factConfirmed: !!g.factTableId,
    measuresConfirmed: g.measureColumns.length > 0,
    dimsConfirmed: (g.dimensionLinks?.length ?? 0) > 0,
  };
}

export function cubeFromDoc(sc: SchemaCanvasCube): WorkbenchCube {
  const measureGroups = sc.measureGroups.map(mgFromDoc);
  const factsCalcs = sc.calcs.map(calcFromDoc);
  const firstFact =
    measureGroups.find((g) => g.factTableId)?.factTableId ?? null;
  return {
    id: sc.id,
    name: sc.name,
    editMode: true,
    selectedTableId: firstFact,
    tableConfirmed: !!firstFact,
    selectedMeasures: [],
    tableSearch: "",
    measureGroups,
    selectedGroupId: measureGroups[0]?.id ?? null,
    groupsEditMode: measureGroups.length === 0,
    groupSeq: maxSeq(
      measureGroups.map((g) => g.id),
      /mg-(\d+)/,
    ),
    factsCalcs,
    selectedCalcId: factsCalcs[0]?.id ?? null,
    calcsEditMode: factsCalcs.length === 0,
    calcSeq: maxSeq(
      factsCalcs.map((c) => c.id),
      /calc-(\d+)/,
    ),
  };
}

export function cubeToDoc(c: WorkbenchCube): SchemaCanvasCube {
  return {
    id: c.id,
    name: c.name,
    measureGroups: c.measureGroups.map((g) => ({
      id: g.id,
      name: g.name,
      measureColumns: [...g.measureColumns],
      factTableId: g.factTableId ?? null,
      dimensionLinks: (g.dimensionLinks ?? []).map((l) => ({ ...l })),
      measureCaptions: g.measureCaptions ? { ...g.measureCaptions } : {},
    })),
    calcs: c.factsCalcs.map((calc) => ({
      id: calc.id,
      name: calc.name,
      tokens: calc.tokens.map((t) => ({ ...t })),
      formula: calc.formula,
      mode: calc.mode,
    })),
  };
}

/** Render token chips back to a textual formula so a build→expression
 *  switch loses nothing. Uses Mondrian-flavoured square-bracket member
 *  refs. Matches `mondrian-export.ts`'s `renderCalcFormula` build branch. */
export function renderCalcTokens(c: FactsCalc): string {
  return c.tokens
    .map((t) => (t.kind === "measure" ? `[${t.name}]` : ` ${t.op} `))
    .join("")
    .replace(/\s+/g, " ")
    .trim();
}

export function calcMode(c: FactsCalc): FactsCalcMode {
  return c.mode ?? "build";
}
