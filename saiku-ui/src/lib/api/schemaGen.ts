/*
 * REST client for the Saiku schema-generator workflow.
 *
 * Mirrors the endpoints exposed by SchemaGeneratorController (backend C3):
 *   POST /start/{dataSourceId}, GET /{id}/status, GET /{id}/draft,
 *   GET /{id}/suggestions, POST /{id}/ops, POST /{id}/save.
 *
 * Keeps the same shape as the other api/*.ts modules — thin wrappers over
 * `fetch`, relying on cookie-based auth (`credentials: "include"`) and the
 * XSRF interceptor installed in api/http.ts.
 */

/** Lifecycle stage for a schema-generator session; mirrors the backend enum. */
export type Stage =
  | "PENDING"
  | "INTROSPECTING"
  | "INFERRING"
  | "ENRICHING"
  | "READY"
  | "SAVED"
  | "FAILED";

export interface StartResponse {
  sessionId: string;
  dataSourceId: string;
  stage: Stage;
}

export interface StatusResponse {
  sessionId: string;
  stage: Stage;
  failureMessage?: string | null;
  cubeCount: number;
  suggestionCount: number;
}

/**
 * Draft schema payload returned by the backend. The detailed shape is defined
 * by the Jackson-serialised `DraftView` record; we keep the TS surface
 * permissive so downstream UI code can narrow as features land (Task D2+).
 */
export interface DraftView {
  schemaName: string;
  cubes: unknown[];
  sharedDimensions: unknown[];
  [k: string]: unknown;
}

export interface SuggestionView {
  ops: SuggestionOp[];
  degraded: boolean;
}

/** Discriminated union of operations the user can apply to a draft. */
export type SuggestionOp =
  | RenameOp
  | HierarchyOp
  | AggregatorOp
  | DegenerateDimOp
  | IgnoreOp;

/** Target selector used by every op — matches the backend `OpTarget` record. */
export interface OpTarget {
  kind: string;
  path: string[];
}

export interface RenameOp {
  op: "rename";
  target: OpTarget;
  newName: string;
}

export interface HierarchyOp {
  op: "hierarchy";
  target: OpTarget;
  levels: string[];
}

export interface AggregatorOp {
  op: "aggregator";
  target: OpTarget;
  aggregator: string;
}

export interface DegenerateDimOp {
  op: "degenerateDim";
  target: OpTarget;
  column: string;
}

export interface IgnoreOp {
  op: "ignore";
  target: OpTarget;
}

export interface SchemaGenClient {
  start(dataSourceId: string): Promise<StartResponse>;
  status(sessionId: string): Promise<StatusResponse>;
  draft(sessionId: string): Promise<DraftView>;
  suggestions(sessionId: string): Promise<SuggestionView>;
  applyOp(sessionId: string, op: SuggestionOp): Promise<DraftView>;
  save(sessionId: string, schemaName?: string): Promise<void>;
}

const PATH = "/rest/saiku/admin/schema-generator";

/**
 * Build a schema-generator client. Accepts an injectable `fetcher` (handy for
 * tests) and an optional `baseUrl` prefix so callers running outside the Vite
 * dev proxy can point at a remote Saiku host.
 */
export function createSchemaGenClient(
  fetcher: typeof fetch = fetch,
  baseUrl = "",
): SchemaGenClient {
  const root = `${baseUrl}${PATH}`;

  async function getJson<T>(path: string): Promise<T> {
    const res = await fetcher(`${root}${path}`, {
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    if (!res.ok) {
      throw new Error(`schema-generator GET ${path} -> ${res.status}`);
    }
    return (await res.json()) as T;
  }

  async function postJson<T>(path: string, body: unknown): Promise<T | null> {
    const res = await fetcher(`${root}${path}`, {
      method: "POST",
      credentials: "include",
      headers: {
        "Content-Type": "application/json",
        Accept: "application/json",
      },
      body: JSON.stringify(body ?? {}),
    });
    if (!res.ok) {
      throw new Error(`schema-generator POST ${path} -> ${res.status}`);
    }
    // 204 or empty body → return null; callers that expect a payload cast.
    if (res.status === 204) return null;
    const text = await res.text();
    return text ? (JSON.parse(text) as T) : null;
  }

  return {
    start(dataSourceId) {
      // Backend accepts POST with no body; we send `{}` via postJson so the
      // Content-Type header stays consistent and CSRF interception kicks in.
      return postJson<StartResponse>(
        `/start/${encodeURIComponent(dataSourceId)}`,
        {},
      ) as Promise<StartResponse>;
    },
    status(sessionId) {
      return getJson<StatusResponse>(`/${encodeURIComponent(sessionId)}/status`);
    },
    draft(sessionId) {
      return getJson<DraftView>(`/${encodeURIComponent(sessionId)}/draft`);
    },
    suggestions(sessionId) {
      return getJson<SuggestionView>(
        `/${encodeURIComponent(sessionId)}/suggestions`,
      );
    },
    applyOp(sessionId, op) {
      return postJson<DraftView>(
        `/${encodeURIComponent(sessionId)}/ops`,
        { op },
      ) as Promise<DraftView>;
    },
    async save(sessionId, schemaName) {
      const body = schemaName !== undefined ? { schemaName } : {};
      await postJson<void>(`/${encodeURIComponent(sessionId)}/save`, body);
    },
  };
}
