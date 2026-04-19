import type { SaikuCube } from "$lib/api/discover";

export type AxisLocation = "FILTER" | "COLUMNS" | "ROWS" | "PAGES";

export interface ThinLevel {
  name: string;
  selection?: {
    type: "INCLUSION" | "EXCLUSION";
    members: unknown[];
    parameterName?: string;
  };
}

export interface ThinHierarchy {
  name: string;
  caption?: string;
  dimension?: string;
  levels: Record<string, ThinLevel>;
  cmembers: Record<string, string>;
}

export interface ThinAxis {
  location: AxisLocation;
  mdx: string | null;
  filters: unknown[];
  sortOrder: string | null;
  sortEvaluationLiteral: string | null;
  hierarchizeMode: string | null;
  hierarchies: ThinHierarchy[];
  nonEmpty: boolean;
}

export interface ThinMeasure {
  name: string;
  uniqueName: string;
  caption: string;
  type: "EXACT" | "CALCULATED";
}

export interface ThinDetails {
  axis: AxisLocation;
  location: "TOP" | "BOTTOM";
  measures: ThinMeasure[];
}

export interface ThinQueryModel {
  axes: Record<AxisLocation, ThinAxis>;
  visualTotals: boolean;
  visualTotalsPattern: string | null;
  lowestLevelsOnly: boolean;
  details: ThinDetails;
  calculatedMeasures: unknown[];
  calculatedMembers: unknown[];
}

export interface ThinQuery {
  name: string;
  cube: SaikuCube;
  queryType: "OLAP";
  type: "QUERYMODEL" | "MDX";
  queryModel?: ThinQueryModel;
  mdx?: string;
  properties?: Record<string, unknown>;
  parameters?: Record<string, string>;
}

export interface CellEntry {
  value: string;
  type:
    | "ROW_HEADER"
    | "ROW_HEADER_HEADER"
    | "COLUMN_HEADER"
    | "DATA_CELL"
    | "EMPTY"
    | "UNKNOWN"
    | "ERROR";
  properties?: Record<string, string>;
}

export interface QueryResult {
  cellset: CellEntry[][];
  rowTotalsLists?: unknown[];
  colTotalsLists?: unknown[];
  runtime?: number;
  error?: string;
  height?: number;
  width?: number;
  query?: ThinQuery;
  topOffset?: number;
  leftOffset?: number;
}

const REST_BASE = "/rest/saiku/api/query";

export function newQueryModel(): ThinQueryModel {
  return {
    axes: {
      FILTER: {
        location: "FILTER",
        mdx: null,
        filters: [],
        sortOrder: null,
        sortEvaluationLiteral: null,
        hierarchizeMode: null,
        hierarchies: [],
        nonEmpty: false,
      },
      COLUMNS: {
        location: "COLUMNS",
        mdx: null,
        filters: [],
        sortOrder: null,
        sortEvaluationLiteral: null,
        hierarchizeMode: null,
        hierarchies: [],
        nonEmpty: true,
      },
      ROWS: {
        location: "ROWS",
        mdx: null,
        filters: [],
        sortOrder: null,
        sortEvaluationLiteral: null,
        hierarchizeMode: null,
        hierarchies: [],
        nonEmpty: true,
      },
      PAGES: {
        location: "PAGES",
        mdx: null,
        filters: [],
        sortOrder: null,
        sortEvaluationLiteral: null,
        hierarchizeMode: null,
        hierarchies: [],
        nonEmpty: false,
      },
    },
    visualTotals: false,
    visualTotalsPattern: null,
    lowestLevelsOnly: false,
    details: { axis: "COLUMNS", location: "BOTTOM", measures: [] },
    calculatedMeasures: [],
    calculatedMembers: [],
  };
}

function uuid(): string {
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function newQuery(cube: SaikuCube): ThinQuery {
  return {
    name: uuid(),
    cube,
    queryType: "OLAP",
    type: "QUERYMODEL",
    queryModel: newQueryModel(),
  };
}

export async function executeQuery(q: ThinQuery): Promise<QueryResult> {
  const res = await fetch(`${REST_BASE}/execute`, {
    method: "POST",
    credentials: "include",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(q),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`execute ${res.status}: ${text.slice(0, 200)}`);
  }
  return (await res.json()) as QueryResult;
}

export async function drillthrough(
  queryName: string,
  opts: { maxRows?: number; position?: string; returns?: string[] } = {},
): Promise<QueryResult> {
  const params = new URLSearchParams();
  params.set("maxrows", String(opts.maxRows ?? 1000));
  if (opts.position) params.set("position", opts.position);
  if (opts.returns?.length) params.set("returns", opts.returns.join(","));
  const url = `${REST_BASE}/${encodeURIComponent(queryName)}/drillthrough?${params.toString()}`;
  const res = await fetch(url, { credentials: "include" });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`drillthrough ${res.status}: ${text.slice(0, 200)}`);
  }
  return (await res.json()) as QueryResult;
}

export async function cancelQuery(name: string): Promise<void> {
  await fetch(`${REST_BASE}/${encodeURIComponent(name)}/cancel`, {
    method: "DELETE",
    credentials: "include",
  });
}
