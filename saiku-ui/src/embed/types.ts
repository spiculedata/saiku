/*
 * Public types for the <saiku-embed/> Web Component flow.
 *
 * The embed bundle is shipped as a self-contained custom element loaded
 * directly into a host page (React / Vue / vanilla HTML — same script).
 * It speaks to the Saiku launcher's /saiku/api/embed/* surface and renders
 * inline; nothing here is shared with the main SvelteKit app's stores or
 * API layer, so the bundle stays focused.
 */

/**
 * Payload from the embed query endpoint. Two shapes:
 *
 * - `format="records"` (default) — `data` populated, each row is a
 *   {column-caption → cell} map.
 * - `format="matrix"` — `matrix` populated, each row is a {column-index → cell}
 *   map, and `metadata` carries the row/column captions the client needs to
 *   render headers.
 */
export interface EmbedQueryResponse {
  /** "records" or "matrix". */
  format: string;
  /** Records format: one {column-caption → cell} per data row. */
  data?: EmbedRow[];
  /** Matrix format: one {"0" → cell, "1" → cell, …} per row. */
  matrix?: EmbedMatrixRow[];
  /** Matrix format only: row + column headers. */
  metadata?: EmbedQueryMetadata;
  /** Echoed by the AI Query API; useful for client-side debugging but
   *  the bundle doesn't render it. */
  queryId?: string;
}

/** {column-caption → cell} for one row. */
export type EmbedRow = Record<string, EmbedCell>;

/** {column-index (string) → cell} for one row in matrix mode. */
export type EmbedMatrixRow = Record<string, EmbedCell>;

/**
 * Matrix-mode header captions. `rows[i]` is the header for the i-th matrix
 * row (row-axis member caption); `columns[j]` is the header for column index
 * j (the value the client puts on that column of the emitted table).
 */
export interface EmbedQueryMetadata {
  rows: EmbedCaption[];
  columns: EmbedCaption[];
}

/** Mondrian name/caption pair — captions are already localised on the server. */
export interface EmbedCaption {
  name: string;
  caption: string;
}

/**
 * One records-mode cell. Mirrors the AI Query API's typed cell:
 *   - `value` is the raw number (null on #null / error cells)
 *   - `formatted` is the pre-formatted display string from Mondrian
 *   - `unit` is an optional measure unit hint (e.g. "USD", "%")
 *
 * Row-header columns (member captions) are also emitted as cells with
 * `formatted` set to the caption and `value = null` — embed renderers
 * can treat them as strings rather than numbers.
 */
export interface EmbedCell {
  value: number | null;
  formatted: string;
  unit?: string;
}

/** Error envelope the server returns on the embed surface (mirrors the
 *  EMBED_INVALID 401 + the AI Query error shapes). */
export interface EmbedError {
  status: string;
  error?: string;
  field?: string;
}

/* ------------------------- dashboard shapes ------------------------ */

/**
 * Dashboard layout returned by GET /saiku/api/embed/dashboard/{path}.
 * Mirrors the structure of Saiku's saiku-web Dashboard DTO; only the
 * fields the embed renderer reads are typed here — anything else
 * round-trips through the Record<string, unknown> escape hatch on the
 * tile so extra fields don't fight the renderer.
 */
export interface EmbedDashboardLayout {
  id: string;
  name: string;
  version: number;
  layout: {
    cols: number;
    tiles: EmbedDashboardTile[];
  };
}

/**
 * One renderable cell in the dashboard grid. Position is on a `cols`
 * (default 12) wide grid; `w`/`h` are span units, with `h=1` ≈ 60 px
 * by convention (the embed renderer uses that scaling factor to map
 * the abstract row height to CSS).
 */
export interface EmbedDashboardTile {
  id: string;
  x: number;
  y: number;
  w: number;
  h: number;
  /** "text" | "chart" | "kpi" | "filter" — the renderer dispatches
   *  on this. Unknown types are skipped with a friendly placeholder so
   *  a server-side tile-type addition doesn't break old embed bundles. */
  type: string;
  title?: string;
  /** Chart-specific. */
  chartType?: string;
  /** KPI-specific. */
  kpi?: {
    measure: string;
    measureCaption?: string;
    format?: string;
  };
  /** Text-tile body — plain string in v1, markdown rendered as
   *  paragraphs (no `marked` import to keep the bundle tight). */
  text?: string;
  /** Filter-tile axis binding — which dimension/hierarchy/level the widget
   *  drives. Null for non-filter tiles. Server-authored; the guest never
   *  overrides it (that would defeat the purpose of pinning). */
  target?: EmbedFilterTarget;
  /** Filter widget subtype: "single-select" | "multi-select". Defaults to
   *  multi-select if omitted. */
  widget?: string;
}

/** Filter-tile axis binding. Mirrors the server-side {@code DashboardFilter}. */
export interface EmbedFilterTarget {
  dimension: string;
  hierarchy?: string;
  level: string;
}

/* ---------------------------- app shapes --------------------------- */

/**
 * A {@code .saikuapp} document as returned by GET /saiku/api/embed/app/{path}
 * (App Builder — saiku#1441). A token pins ONE app; the whole document — nav +
 * every page + every tile — rides that single grant, so the embed renders it
 * read-only as one unit. Only the fields the read-only renderer needs are typed;
 * anything else round-trips untouched (the launcher serves the doc verbatim).
 */
export interface EmbedAppDoc {
  id: string;
  name: string;
  version: number;
  logo?: string | null;
  nav?: { position?: "rail" | "top" };
  theme?: { mode?: "light" | "dark" | "auto" };
  pages: EmbedAppPage[];
}

/**
 * One page in an embedded app. {@code grid} is the SAME layout shape a dashboard
 * uses — {cols, tiles} — so each page renders through the identical tile
 * dispatch, and each page's tiles issue the SAME per-tile embed query the
 * dashboard embed does (see EmbedGrid + api.fetchAppTile).
 */
export interface EmbedAppPage {
  id: string;
  title: string;
  icon?: string;
  grid: {
    cols?: number;
    tiles?: EmbedDashboardTile[];
  };
}
