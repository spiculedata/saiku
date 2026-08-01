/**
 * Cube-designer backend seam (saiku-cloud → OSS extraction, Phase 0).
 *
 * The designer component library is otherwise host-agnostic — its only tie to a
 * backend is a handful of network calls (profile a connection, sample rows, run
 * a try-query, load / convert a schema, and the optional DimSum AI turn). Rather
 * than hard-code the Cloud `/api/*` URLs inside the components, they resolve a
 * {@link CubeDesignerBackend} from Svelte context, so each host wires its own:
 *
 *   - Saiku **Cloud**  → the existing `/api/inference/*` + `/api/schemas/*`
 *     proxies (which re-sign to the gateway `/me/*`).
 *   - Saiku **OSS**    → Saiku's own REST (`/saiku/api/schemagen`, AI Query API,
 *     repository resource).
 *
 * Each method returns a raw {@link Response} so call sites keep their existing
 * `resp.ok` / `resp.status` / `resp.json()` handling verbatim — the seam only
 * owns URL construction, nothing else. The DimSum AI ({@link CubeDesignerAI}) is
 * a SEPARATE, OPTIONAL context: a host that doesn't provide it gets the manual
 * designer with the AI surface disabled.
 */
import { getContext, setContext } from "svelte";

/** Backend transport for the designer's data-plane calls. URL-only seam. */
export interface CubeDesignerBackend {
  /** Profile a connection → table/column catalog for the source sidebar. */
  profileConnection(connectionId: string): Promise<Response>;
  /** Fetch up to `limit` preview rows for a fact table (Sample-data tab). */
  sample(connectionId: string, table: string, limit: number): Promise<Response>;
  /** Run a starter query against the unsaved proposal (Try-a-query tab). */
  tryQuery(body: unknown): Promise<Response>;
  /** Load a saved schema's Mondrian XML from the library into the canvas. */
  loadSchema(entryId: string): Promise<Response>;
  /** Convert pasted legacy Mondrian-3 XML → Mondrian-4. */
  convertSchema(body: unknown): Promise<Response>;
}

/** Optional AI transport for the DimSum agent turn. Absent ⇒ AI disabled
 *  (Phase 1 will gate the DimSum surface on presence; Phase 0 only routes the
 *  transport). `fetchImpl` is the existing dimsum-agent injection seam — a host
 *  provides one to point the DimSum turn at its own AI endpoint. */
export interface CubeDesignerAI {
  fetchImpl?: typeof fetch;
}

const BACKEND_KEY = Symbol("cube-designer-backend");
const AI_KEY = Symbol("cube-designer-ai");

/** Host: provide the backend to the designer subtree (call during init). */
export function setCubeDesignerBackend(backend: CubeDesignerBackend): void {
  setContext(BACKEND_KEY, backend);
}

/** Host: provide the optional AI adapter to the designer subtree. */
export function setCubeDesignerAI(ai: CubeDesignerAI): void {
  setContext(AI_KEY, ai);
}

/** Designer component: resolve the backend. Throws if the host forgot to wire
 *  one — a loud failure beats a silent hard-coded fallback. */
export function getCubeDesignerBackend(): CubeDesignerBackend {
  const backend = getContext<CubeDesignerBackend | undefined>(BACKEND_KEY);
  if (!backend) {
    throw new Error(
      "CubeDesignerBackend not provided — call setCubeDesignerBackend() in a host ancestor.",
    );
  }
  return backend;
}

/** Designer component: resolve the optional AI adapter (null ⇒ AI disabled). */
export function getCubeDesignerAI(): CubeDesignerAI | null {
  return getContext<CubeDesignerAI | undefined>(AI_KEY) ?? null;
}
