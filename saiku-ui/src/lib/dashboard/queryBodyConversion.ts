/*
 * Inline-query ↔ ThinQuery conversion (issue #912).
 *
 * The dashboard tile editor embeds the workspace QueryCanvas, which builds
 * a {@link ThinQuery} model in the singleton query store. A chart/table
 * tile, however, persists its query as an {@code InlineQuery} whose
 * {@code body} is an AiQueryRequest (the shape the tile renderers already
 * consume via /ai/query). These two pure helpers bridge the two:
 *
 *   - {@link bodyToThinQuery} seeds the canvas from an existing inline body
 *     (so re-opening "Edit query" shows the tile's current query), and
 *   - {@link thinQueryToBody} reads the canvas's built model back out into
 *     an AiQueryRequest body to commit onto the tile on Save.
 *
 * The AiQueryRequest axis fields ({@code rows[]}, {@code columns[]},
 * {@code filters[]}) carry {@code {dimension, hierarchy, level, members?}}
 * triples; measures carry {@code {name}}. The server resolves those names
 * case-insensitively against the live cube (canonical OR display names),
 * so we emit the most human-readable form available on the model
 * (caption-preferred) and tolerate either on the way in.
 *
 * No DOM, no fetch, no store access — pure data mapping so it unit-tests
 * cleanly under the node vitest env.
 */

import type { SaikuCube } from "$lib/api/discover";
import type { ThinHierarchy, ThinMeasure, ThinQuery } from "$lib/api/query";
import { newQuery } from "$lib/api/query";

/** One axis selection in an AiQueryRequest body (rows / columns). */
export interface BodyAxisSelection {
  dimension: string;
  hierarchy: string;
  level: string;
  members?: string[];
}

/** One filter selection in an AiQueryRequest body. */
export interface BodyFilterSelection {
  dimension: string;
  hierarchy: string;
  level: string;
  members?: string[];
}

/** One measure reference in an AiQueryRequest body. */
export interface BodyMeasure {
  name: string;
}

/** The subset of an AiQueryRequest body these helpers read / write. Extra
 *  fields on a real body (order, limit, nonEmpty, visualTotals, …) are
 *  preserved verbatim by {@link bodyToThinQuery}'s caller and re-emitted
 *  on the way out — this module never strips them. */
export interface InlineQueryBody {
  cube?: unknown;
  measures?: BodyMeasure[];
  rows?: BodyAxisSelection[];
  columns?: BodyAxisSelection[];
  filters?: BodyFilterSelection[];
  [extra: string]: unknown;
}

function asString(v: unknown): string {
  return typeof v === "string" ? v : "";
}

/** Coerce a loose record into a BodyAxisSelection, dropping entries that
 *  lack the three required name fields. */
function readAxis(v: unknown): BodyAxisSelection[] {
  if (!Array.isArray(v)) return [];
  const out: BodyAxisSelection[] = [];
  for (const raw of v) {
    if (!raw || typeof raw !== "object") continue;
    const r = raw as Record<string, unknown>;
    const dimension = asString(r.dimension);
    const hierarchy = asString(r.hierarchy);
    const level = asString(r.level);
    if (!dimension || !hierarchy || !level) continue;
    const members = Array.isArray(r.members)
      ? (r.members.filter((m) => typeof m === "string") as string[])
      : undefined;
    out.push(members && members.length ? { dimension, hierarchy, level, members } : { dimension, hierarchy, level });
  }
  return out;
}

/** Build a ThinHierarchy for an axis selection. The hierarchy name carries
 *  the readable hierarchy token; the dimension is stashed on
 *  {@code dimension}; the single level keyed by its name. When members are
 *  present they become an INCLUSION selection so the canvas shows the
 *  narrowed set. */
function axisToHierarchy(sel: BodyAxisSelection): ThinHierarchy {
  const hier: ThinHierarchy = {
    name: sel.hierarchy,
    caption: sel.hierarchy,
    dimension: sel.dimension,
    levels: {
      [sel.level]: {
        name: sel.level,
        ...(sel.members && sel.members.length
          ? {
              selection: {
                type: "INCLUSION" as const,
                members: sel.members.map((uniqueName) => ({ uniqueName })),
              },
            }
          : {}),
      },
    },
    cmembers: {},
  };
  return hier;
}

/** Seed a {@link ThinQuery} model from an existing inline-query body, for
 *  the given cube. Measures land on {@code details.measures}; row / column
 *  selections become hierarchies on the ROWS / COLUMNS axes; filters land
 *  on the FILTER axis. Returns a runnable ThinQuery the canvas can render
 *  and the user can keep editing.
 *
 *  Pure: the input body is not mutated. */
export function bodyToThinQuery(body: InlineQueryBody | null | undefined, cube: SaikuCube): ThinQuery {
  const q = newQuery(cube);
  const model = q.queryModel;
  if (!model || !body) return q;

  const measures: ThinMeasure[] = [];
  for (const m of body.measures ?? []) {
    const name = asString((m as { name?: unknown })?.name);
    if (!name) continue;
    measures.push({
      name,
      uniqueName: `[Measures].[${name}]`,
      caption: name,
      type: "EXACT",
    });
  }
  model.details.measures = measures;

  for (const sel of readAxis(body.rows)) {
    model.axes.ROWS.hierarchies.push(axisToHierarchy(sel));
  }
  for (const sel of readAxis(body.columns)) {
    model.axes.COLUMNS.hierarchies.push(axisToHierarchy(sel));
  }
  for (const sel of readAxis(body.filters)) {
    model.axes.FILTER.hierarchies.push(axisToHierarchy(sel));
  }

  return q;
}

/** Read the INCLUSION member unique-names off a level selection, if any. */
function levelMembers(hier: ThinHierarchy, levelName: string): string[] {
  const sel = hier.levels[levelName]?.selection;
  if (!sel || sel.type !== "INCLUSION") return [];
  return (sel.members as Array<{ uniqueName?: string }>)
    .map((m) => m?.uniqueName)
    .filter((v): v is string => typeof v === "string" && v.length > 0);
}

/** Project one axis's hierarchies into BodyAxisSelection entries — one per
 *  (hierarchy, level) pair, in axis order. A hierarchy carrying multiple
 *  levels yields one entry per level (the drilldown grain the canvas
 *  built). */
function hierarchiesToAxis(hierarchies: ThinHierarchy[]): BodyAxisSelection[] {
  const out: BodyAxisSelection[] = [];
  for (const h of hierarchies) {
    const dimension = h.dimension ?? h.name;
    const hierarchy = h.caption || h.name;
    const levelNames = Object.keys(h.levels);
    if (levelNames.length === 0) continue;
    for (const level of levelNames) {
      const members = levelMembers(h, level);
      out.push(members.length ? { dimension, hierarchy, level, members } : { dimension, hierarchy, level });
    }
  }
  return out;
}

/** Read a built {@link ThinQuery} model back out into an AiQueryRequest
 *  body. The {@code cube} is emitted in the 4-segment object form the
 *  AI API + dashboard tile renderers expect (matches AiCubeRef). Existing
 *  body fields the editor doesn't manage (order, limit, …) are carried
 *  over from {@code prevBody} so an inline-edit round-trip is lossless.
 *
 *  Returns a plain JSON-serialisable object suitable for an InlineQuery
 *  {@code body}. Pure: neither the query nor prevBody is mutated. */
export function thinQueryToBody(
  query: ThinQuery,
  prevBody?: InlineQueryBody | null,
): InlineQueryBody {
  const model = query.queryModel;
  const cube = query.cube;
  // 4-segment AiCubeRef form — what the tile renderers + /ai/query consume.
  const cubeRef = {
    connectionName: cube.connection,
    catalog: cube.catalog,
    schema: cube.schema,
    cubeName: cube.name,
  };

  // Preserve unmanaged passthrough fields (order/limit/nonEmpty/…) but
  // overwrite the axis + measure + cube fields we own.
  const carried: InlineQueryBody = {};
  if (prevBody && typeof prevBody === "object") {
    for (const [key, value] of Object.entries(prevBody)) {
      if (key === "cube" || key === "measures" || key === "rows" || key === "columns" || key === "filters") {
        continue;
      }
      carried[key] = value;
    }
  }

  const measures: BodyMeasure[] = (model?.details.measures ?? []).map((m) => ({ name: m.name }));
  const rows = hierarchiesToAxis(model?.axes.ROWS.hierarchies ?? []);
  const columns = hierarchiesToAxis(model?.axes.COLUMNS.hierarchies ?? []);
  const filters = hierarchiesToAxis(model?.axes.FILTER.hierarchies ?? []);

  const body: InlineQueryBody = {
    ...carried,
    cube: cubeRef,
    measures,
    rows,
    columns,
  };
  if (filters.length) body.filters = filters;
  return body;
}

/** True when a built model has at least one measure and at least one
 *  hierarchy on rows or columns — i.e. it would produce a non-trivial
 *  query. Mirrors QueryStore.hasRunnableShape so the modal can refuse to
 *  commit an empty query onto a tile. */
export function bodyIsRunnable(query: ThinQuery | null | undefined): boolean {
  const model = query?.queryModel;
  if (!model) return false;
  const hasMeasure = model.details.measures.length > 0;
  const hasHierarchy =
    model.axes.ROWS.hierarchies.length > 0 || model.axes.COLUMNS.hierarchies.length > 0;
  return hasMeasure && hasHierarchy;
}
