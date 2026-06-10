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
