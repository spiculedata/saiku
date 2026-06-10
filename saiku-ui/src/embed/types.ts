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
 * Records-mode payload from the embed query endpoint. Each row is a
 * {column-caption → cell} map.
 */
export interface EmbedQueryResponse {
  /** "records" — only mode we render in v1. */
  format: string;
  /** One {column-caption → cell} per data row. */
  data: EmbedRow[];
  /** Echoed by the AI Query API; useful for client-side debugging but
   *  the bundle doesn't render it. */
  queryId?: string;
}

/** {column-caption → cell} for one row. */
export type EmbedRow = Record<string, EmbedCell>;

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
}
